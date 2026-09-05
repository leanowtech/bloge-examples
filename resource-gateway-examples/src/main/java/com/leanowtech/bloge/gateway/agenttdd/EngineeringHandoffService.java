package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the durable lifecycle for design-only WRITE Instruction implementation handoffs.
 *
 * <p>The Agent-facing surface can only create an {@code OPEN} handoff. A separately
 * authenticated accountable engineer may bind an implementation through the non-MCP HTTP
 * boundary. The platform closes the handoff only after governed WRITE reconciliation succeeds.</p>
 */
@Service
public final class EngineeringHandoffService {
    /** Durable handoff asset kind. */
    public static final String HANDOFF = "SOLUTION_HANDOFF";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final ObjectMapper mapper;

    /** Creates the proposal boundary over canonical contracts and the shared asset store. */
    public EngineeringHandoffService(
            AgentTddStateRepository states, SolutionEntityRegistry registry, ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Aggregates only unbound WRITE Instructions and never grants execution authority. */
    public Map<String, Object> submit(
            String solutionRef, IntegrationRequestContext identity) {
        if (identity == null || !IntegrationOperation.AGENT_TDD_PROPOSE.accepts(identity.purpose())) {
            throw new AgentTddToolException("FORBIDDEN_PURPOSE", "Authoring purpose is required.");
        }
        String scope = AgentTddMutationService.scopeKey(identity);
        SolutionContract solution;
        SolutionEntityRegistry.RegisteredEntity registered;
        try {
            solution = registry.requireSolution(scope, solutionRef);
            registered = registry.requireRegisteredSolution(scope, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
        ObjectNode data = mapper.createObjectNode();
        data.put("solutionRef", solution.solutionRef());
        data.put("status", "OPEN");
        data.put("submittedBy", identity.actorId());
        data.put("createdAt", Instant.now().toString());
        data.put("solutionRevision", registered.revision());
        data.put("solutionContractFingerprint", registered.contractFingerprint());
        ArrayNode items = data.putArray("items");
        for (String instructionRef : solution.instructions()) {
            InstructionContract instruction;
            SolutionEntityRegistry.RegisteredEntity registeredInstruction;
            try {
                instruction = registry.requireInstruction(scope, instructionRef);
                registeredInstruction = registry.requireRegisteredInstruction(scope, instructionRef);
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                throw new AgentTddToolException("REFERENCE_UNRESOLVED", "An Instruction is unavailable.");
            }
            if (instruction.effect() != InstructionContract.Effect.WRITE || !instruction.speccing()) continue;
            ObjectNode item = items.addObject();
            item.put("instructionId", instruction.instructionRef());
            item.put("instructionRevision", registeredInstruction.revision());
            item.put("contractFingerprint", registeredInstruction.contractFingerprint());
            item.set("inputs", instruction.inputs());
            item.set("output", instruction.output());
            item.put("effect", "WRITE");
            item.put("downstreamSystem", instruction.writeGovernance().downstreamSystem());
            item.put("reconciliationKey", instruction.writeGovernance().reconciliationKey());
            item.put("reconciliationAdapterRef", instruction.writeGovernance().reconciliationAdapterRef());
            item.put("businessIntent", solution.problem());
            item.put("acceptanceGolden", solution.goldenRef());
            item.put("state", "DESIGN_ONLY");
        }
        if (items.isEmpty()) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "No design-only WRITE Instruction requires a handoff.");
        }
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, data, MAX_BYTES);
        String handoffId = "handoff:" + fingerprint.substring("sha256:".length(), "sha256:".length() + 24);
        data.put("handoffId", handoffId);
        AgentTddStoredAsset stored = states.save(scope, HANDOFF, solution.solutionRef(), data);
        return Map.of("handoffId", handoffId, "solutionRef", solution.solutionRef(),
                "status", "OPEN", "items", stored.data().path("items"), "revision", stored.revision());
    }

    /**
     * Binds one exact handoff item without exposing implementation authority to MCP Agents.
     *
     * <p>The handoff and Instruction revisions are locked in one transaction. All approved
     * business contract material is preserved; only {@code bindingRef} changes. The handoff is
     * {@code IMPLEMENTED} once every item has an implementation, but remains unclosed until the
     * platform reconciles the resulting downstream effects.</p>
     */
    public Map<String, Object> fulfil(String solutionRef, String instructionRef, String bindingRef,
                                      IntegrationRequestContext identity) {
        requireEngineer(identity);
        if (solutionRef == null || solutionRef.isBlank() || instructionRef == null
                || instructionRef.isBlank() || bindingRef == null || bindingRef.isBlank()) {
            throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT", "Solution, Instruction and binding are required.");
        }
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset handoff = states.find(scope, HANDOFF, solutionRef)
                .orElseThrow(() -> new AgentTddToolException(
                        "REFERENCE_UNRESOLVED", "An engineering handoff is unavailable."));
        InstructionContract instruction;
        SolutionEntityRegistry.RegisteredEntity registered;
        try {
            instruction = registry.requireInstruction(scope, instructionRef);
            registered = registry.requireRegisteredInstruction(scope, instructionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "An Instruction is unavailable.");
        }
        if (instruction.effect() != InstructionContract.Effect.WRITE || !instruction.speccing()) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "Only a design-only WRITE Instruction can be fulfilled.");
        }
        int itemIndex = matchingItem(handoff.data(), instructionRef, registered);
        InstructionContract bound = new InstructionContract(
                instruction.instructionRef(), instruction.inputs(), instruction.output(),
                instruction.effect(), bindingRef.trim(), instruction.writeGovernance(),
                instruction.businessSemantics(), instruction.businessDefinition(),
                instruction.display());
        return states.executeAtomically(() -> {
            states.lockRevision(scope, HANDOFF, solutionRef, handoff.revision());
            states.lockRevision(scope, SolutionEntityRegistry.INSTRUCTION,
                    instructionRef, registered.revision());
            registry.upsertInstruction(scope, bound);
            ObjectNode data = handoff.data().deepCopy();
            ObjectNode item = (ObjectNode) data.path("items").path(itemIndex);
            item.put("state", "IMPLEMENTED");
            item.put("bindingRef", bindingRef.trim());
            item.put("implementedBy", identity.actorId());
            item.put("implementedAt", Instant.now().toString());
            boolean allImplemented = true;
            for (JsonNode candidate : data.path("items")) {
                if (!"IMPLEMENTED".equals(candidate.path("state").asText())) {
                    allImplemented = false;
                    break;
                }
            }
            data.put("status", allImplemented ? "IMPLEMENTED" : "OPEN");
            AgentTddStoredAsset stored = states.saveIfRevision(
                    scope, HANDOFF, solutionRef, handoff.revision(), data);
            return projection(stored);
        });
    }

    /**
     * Closes an implemented handoff after the platform has persisted successful reconciliation.
     * This method performs no external call and is idempotent for missing or closed handoffs.
     */
    public void closeAfterReconciliation(String scope, String solutionRef, JsonNode reconciliation) {
        if (reconciliation == null || !"RECONCILED".equals(reconciliation.path("status").asText())) return;
        states.find(scope, HANDOFF, solutionRef).ifPresent(handoff -> {
            if ("CLOSED".equals(handoff.data().path("status").asText())) return;
            if (!"IMPLEMENTED".equals(handoff.data().path("status").asText())) {
                throw new AgentTddToolException(
                        "GATE_REJECTED", "The engineering handoff is not fully implemented.");
            }
            states.lockRevision(scope, HANDOFF, solutionRef, handoff.revision());
            ObjectNode data = handoff.data().deepCopy();
            data.put("status", "CLOSED");
            data.put("closedAt", Instant.now().toString());
            data.put("reconciliationEvidenceFingerprint",
                    reconciliation.path("evidenceFingerprint").asText());
            for (JsonNode candidate : data.path("items")) {
                ((ObjectNode) candidate).put("state", "CLOSED");
            }
            states.saveIfRevision(scope, HANDOFF, solutionRef, handoff.revision(), data);
        });
    }

    private int matchingItem(JsonNode handoff, String instructionRef,
                             SolutionEntityRegistry.RegisteredEntity registered) {
        if ("CLOSED".equals(handoff.path("status").asText())) {
            throw new AgentTddToolException("GATE_REJECTED", "The engineering handoff is closed.");
        }
        for (int index = 0; index < handoff.path("items").size(); index++) {
            JsonNode item = handoff.path("items").path(index);
            if (!instructionRef.equals(item.path("instructionId").asText())) continue;
            if (!"DESIGN_ONLY".equals(item.path("state").asText())
                    || item.path("instructionRevision").asLong(-1) != registered.revision()
                    || !item.path("contractFingerprint").asText()
                    .equals(registered.contractFingerprint())) {
                throw new AgentTddToolException(
                        "GATE_REJECTED", "The handoff item is stale or already implemented.");
            }
            return index;
        }
        throw new AgentTddToolException(
                "REFERENCE_UNRESOLVED", "The Instruction is not part of this handoff.");
    }

    private Map<String, Object> projection(AgentTddStoredAsset stored) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("handoffId", stored.data().path("handoffId").asText());
        result.put("solutionRef", stored.data().path("solutionRef").asText());
        result.put("status", stored.data().path("status").asText());
        result.put("items", stored.data().path("items"));
        result.put("revision", stored.revision());
        return Map.copyOf(result);
    }

    private static void requireEngineer(IntegrationRequestContext identity) {
        if (identity == null
                || !IntegrationOperation.AGENT_TDD_INSTRUCTION_ENG.accepts(identity.purpose())) {
            throw new AgentTddToolException(
                    "FORBIDDEN_PURPOSE", "Instruction engineering purpose is required.");
        }
        identity.requireComplete();
        if (!"USER".equals(identity.actorType()) && !"HUMAN".equals(identity.actorType())) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "Instruction fulfillment requires an accountable engineer.");
        }
    }
}
