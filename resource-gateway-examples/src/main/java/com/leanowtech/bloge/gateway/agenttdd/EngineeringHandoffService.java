package com.leanowtech.bloge.gateway.agenttdd;

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
import java.util.Objects;

/** Builds a durable engineering handoff from design-only WRITE Instruction contracts. */
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
    public java.util.Map<String, Object> submit(
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
            try {
                instruction = registry.requireInstruction(scope, instructionRef);
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                throw new AgentTddToolException("REFERENCE_UNRESOLVED", "An Instruction is unavailable.");
            }
            if (instruction.effect() != InstructionContract.Effect.WRITE || !instruction.speccing()) continue;
            ObjectNode item = items.addObject();
            item.put("instructionId", instruction.instructionRef());
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
        return java.util.Map.of("handoffId", handoffId, "solutionRef", solution.solutionRef(),
                "status", "OPEN", "items", stored.data().path("items"), "revision", stored.revision());
    }
}
