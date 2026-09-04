package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.InstructionContract;
import com.leanowtech.bloge.gateway.solution.SolutionContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Coordinates immutable Solution proposal, owner signoff, readiness and publication records.
 *
 * <p>Every governance decision is bound to the current Solution revision, contract fingerprint,
 * GOLDEN set and GREEN evidence fingerprint. Editing any of those coordinates invalidates an old
 * signoff rather than silently authorizing a different Solution.</p>
 */
@Service
public final class SolutionGovernanceService {
    /** Durable proposal submitted by an Agent for independent review. */
    public static final String COMMIT = "SOLUTION_COMMIT";
    /** Durable human decision bound to one exact GREEN line. */
    public static final String SIGNOFF = "SOLUTION_SIGNOFF";
    /** Immutable published Solution coordinate. */
    public static final String PUBLICATION = "SOLUTION_PUBLICATION";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final ObjectMapper mapper;

    /** Creates the governance boundary over canonical Solution entities and Agent TDD state. */
    public SolutionGovernanceService(
            AgentTddStateRepository states, SolutionEntityRegistry registry, ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Submits the exact current Solution and authoring receipt for independent human review. */
    public Map<String, Object> commit(
            String solutionRef,
            String authoringReceiptFingerprint,
            IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_PROPOSE);
        String scope = AgentTddMutationService.scopeKey(identity);
        SolutionEntityRegistry.RegisteredEntity solution = registered(scope, solutionRef);
        ObjectNode data = mapper.createObjectNode();
        data.put("solutionRef", solutionRef);
        data.put("solutionRevision", solution.revision());
        data.put("solutionContractFingerprint", solution.contractFingerprint());
        data.put("authoringReceiptFingerprint", required(authoringReceiptFingerprint));
        data.put("proposalStatus", "PENDING");
        data.put("proposedBy", identity.actorId());
        data.put("proposedAt", Instant.now().toString());
        String proposalFingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, data, MAX_BYTES);
        data.put("proposalFingerprint", proposalFingerprint);
        AgentTddStoredAsset stored = states.save(scope, COMMIT, solutionRef, data);
        return Map.of("solutionRef", solutionRef, "proposalStatus", "PENDING",
                "revision", stored.revision(), "proposalFingerprint", proposalFingerprint,
                "awaiting", "HUMAN_SOLUTION_SIGNOFF");
    }

    /**
     * Records an independent human owner's approval of the exact current Solution evidence line.
     *
     * <p>This method is intentionally absent from the MCP catalog. It is called only by the
     * governed web review surface after the reviewer has opened the payload-bearing material.</p>
     */
    public AgentTddStoredAsset approve(
            String solutionRef,
            String signoffRef,
            long solutionRevision,
            String goldenSetId,
            String evidenceFingerprint,
            String proposalFingerprint,
            IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE);
        requireHuman(identity);
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset proposal = states.find(scope, COMMIT, solutionRef)
                .orElseThrow(() -> new AgentTddToolException(
                        "GATE_REJECTED", "A current Solution proposal is required."));
        if (!"PENDING".equals(proposal.data().path("proposalStatus").asText())
                || !proposal.data().path("proposalFingerprint").asText().equals(required(proposalFingerprint))
                || proposal.data().path("proposedBy").asText().equals(identity.actorId())) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The reviewer is not independent or the proposal changed.");
        }
        Readiness line = assess(scope, solutionRef, null);
        if (!line.logicGreen || !line.implementationBound || !line.writeReconciled
                || line.solutionRevision != solutionRevision
                || proposal.data().path("solutionRevision").asLong(-1) != line.solutionRevision
                || !proposal.data().path("solutionContractFingerprint").asText()
                        .equals(line.solutionContractFingerprint)
                || !line.goldenSetId.equals(required(goldenSetId))
                || !line.evidenceFingerprint.equals(required(evidenceFingerprint))) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The reviewed Solution evidence is no longer current.");
        }
        ObjectNode data = mapper.createObjectNode();
        data.put("solutionRef", solutionRef);
        data.put("signoffRef", required(signoffRef));
        data.put("solutionRevision", line.solutionRevision);
        data.put("solutionContractFingerprint", line.solutionContractFingerprint);
        data.put("goldenSetId", line.goldenSetId);
        data.put("evidenceFingerprint", line.evidenceFingerprint);
        data.put("proposalFingerprint", proposalFingerprint);
        data.put("status", "APPROVED");
        data.put("approvedBy", identity.actorId());
        data.put("approvedAt", Instant.now().toString());
        return states.saveIfRevision(scope, SIGNOFF, signoffRef, 0, data);
    }

    /** Returns current publish gates without changing state or exposing business payloads. */
    public Map<String, Object> readiness(String solutionRef, IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_READ);
        Readiness line = assess(AgentTddMutationService.scopeKey(identity), solutionRef, null);
        return line.projection();
    }

    /** Publishes one immutable Solution only when the supplied signoff matches every live gate. */
    public Map<String, Object> publish(
            String solutionRef, String signoffRef, IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE);
        String scope = AgentTddMutationService.scopeKey(identity);
        Readiness line = assess(scope, solutionRef, signoffRef);
        if (!line.publishable()) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The Solution is not ready for governed publication.");
        }
        String publicationId = "solution-publication:" + VisualBundleFingerprint
                .fromCanonicalValue(mapper, Map.of(
                        "solutionRef", solutionRef,
                        "solutionRevision", line.solutionRevision,
                        "goldenSetId", line.goldenSetId,
                        "evidenceFingerprint", line.evidenceFingerprint,
                        "signoffRef", signoffRef), MAX_BYTES)
                .substring("sha256:".length(), "sha256:".length() + 24);
        ObjectNode data = mapper.createObjectNode();
        data.put("publicationId", publicationId);
        data.put("solutionRef", solutionRef);
        data.put("solutionRevision", line.solutionRevision);
        data.put("solutionContractFingerprint", line.solutionContractFingerprint);
        data.put("goldenSetId", line.goldenSetId);
        data.put("evidenceFingerprint", line.evidenceFingerprint);
        data.put("signoffRef", signoffRef);
        data.put("publishedBy", identity.actorId());
        data.put("publishedAt", Instant.now().toString());
        states.saveIfRevision(scope, PUBLICATION, publicationId, 0, data);
        return Map.of("solutionRef", solutionRef, "publicationId", publicationId,
                "artifactKind", "SOLUTION", "goldenSetId", line.goldenSetId,
                "signoffRef", signoffRef);
    }

    private Readiness assess(String scope, String solutionRef, String requestedSignoffRef) {
        SolutionEntityRegistry.RegisteredEntity registered = registered(scope, solutionRef);
        SolutionContract solution;
        try {
            solution = registry.requireSolution(scope, solutionRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
        boolean implementationBound = true;
        boolean hasWrite = false;
        for (String instructionRef : solution.instructions()) {
            try {
                InstructionContract instruction = registry.requireInstruction(scope, instructionRef);
                implementationBound &= !instruction.speccing();
                hasWrite |= instruction.effect() == InstructionContract.Effect.WRITE;
            } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
                implementationBound = false;
            }
        }
        AgentTddStoredAsset evidence = states.find(scope, SolutionTestingService.SOLUTION_EVIDENCE, solutionRef)
                .orElse(null);
        boolean logicGreen = evidence != null
                && "GREEN".equals(evidence.data().path("side").asText())
                && evidence.data().path("businessBacklog").isArray()
                && evidence.data().path("businessBacklog").isEmpty()
                && evidence.data().path("solutionRevision").asLong(-1) == registered.revision()
                && registered.contractFingerprint().equals(
                        evidence.data().path("solutionContractFingerprint").asText());
        String goldenSetId = logicGreen ? evidence.data().path("goldenSetId").asText() : "";
        String evidenceFingerprint = logicGreen ? evidence.fingerprint() : "";
        boolean writeReconciled = !hasWrite;
        if (hasWrite && logicGreen) {
            writeReconciled = states.find(scope, SolutionWriteExecutionRunner.RECONCILIATION, solutionRef)
                    .map(AgentTddStoredAsset::data)
                    .filter(value -> "RECONCILED".equals(value.path("status").asText()))
                    .filter(value -> goldenSetId.equals(value.path("goldenSetId").asText()))
                    .filter(value -> evidenceFingerprint.equals(value.path("evidenceFingerprint").asText()))
                    .filter(value -> registered.revision() == value.path("solutionRevision").asLong(-1))
                    .filter(value -> registered.contractFingerprint().equals(
                            value.path("solutionContractFingerprint").asText()))
                    .isPresent();
        }
        java.util.function.Predicate<AgentTddStoredAsset> currentSignoff = signoff ->
                "APPROVED".equals(signoff.data().path("status").asText())
                        && solutionRef.equals(signoff.data().path("solutionRef").asText())
                        && registered.revision() == signoff.data().path("solutionRevision").asLong(-1)
                        && registered.contractFingerprint().equals(
                                signoff.data().path("solutionContractFingerprint").asText())
                        && goldenSetId.equals(signoff.data().path("goldenSetId").asText())
                        && evidenceFingerprint.equals(signoff.data().path("evidenceFingerprint").asText());
        boolean ownerSignoff = requestedSignoffRef == null || requestedSignoffRef.isBlank()
                ? states.list(scope, SIGNOFF).stream().anyMatch(currentSignoff)
                : states.find(scope, SIGNOFF, requestedSignoffRef).filter(currentSignoff).isPresent();
        return new Readiness(solutionRef, registered.revision(), registered.contractFingerprint(),
                goldenSetId, evidenceFingerprint, logicGreen, implementationBound,
                writeReconciled, ownerSignoff);
    }

    private SolutionEntityRegistry.RegisteredEntity registered(String scope, String solutionRef) {
        try {
            return registry.requireRegisteredSolution(scope, required(solutionRef));
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Solution is unavailable.");
        }
    }

    private static void requirePurpose(
            IntegrationRequestContext identity, IntegrationOperation operation) {
        if (identity == null || !operation.accepts(identity.purpose())) {
            throw new AgentTddToolException("FORBIDDEN_PURPOSE", "The requested purpose is not authorized.");
        }
        identity.requireComplete();
    }

    private static void requireHuman(IntegrationRequestContext identity) {
        if (!("USER".equals(identity.actorType()) || "HUMAN".equals(identity.actorType()))) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "Solution approval requires an independent human identity.");
        }
    }

    private static String required(String value) {
        if (value == null || value.trim().isBlank()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "A required coordinate is missing.");
        }
        return value.trim();
    }

    private record Readiness(
            String solutionRef,
            long solutionRevision,
            String solutionContractFingerprint,
            String goldenSetId,
            String evidenceFingerprint,
            boolean logicGreen,
            boolean implementationBound,
            boolean writeReconciled,
            boolean ownerSignoff) {
        private boolean publishable() {
            return logicGreen && implementationBound && writeReconciled && ownerSignoff;
        }

        private Map<String, Object> projection() {
            List<String> remaining = new ArrayList<>();
            if (!logicGreen) remaining.add("LOGIC_GREEN_REQUIRED");
            if (!implementationBound) remaining.add("IMPLEMENTATION_BINDING_REQUIRED");
            if (!writeReconciled) remaining.add("WRITE_EXECUTION_NOT_RECONCILED");
            if (!ownerSignoff) remaining.add("OWNER_SIGNOFF_REQUIRED");
            Map<String, Object> gates = new LinkedHashMap<>();
            gates.put("logicGreen", logicGreen);
            gates.put("implementationBound", implementationBound);
            gates.put("writeReconciled", writeReconciled);
            gates.put("ownerSignoff", ownerSignoff);
            return Map.of("solutionRef", solutionRef,
                    "state", publishable() ? "READY" : "BLOCKED",
                    "publishable", publishable(),
                    "solutionRevision", solutionRevision,
                    "solutionContractFingerprint", solutionContractFingerprint,
                    "goldenSetId", goldenSetId,
                    "evidenceFingerprint", evidenceFingerprint,
                    "gates", Map.copyOf(gates),
                    "remainingLimitations", List.copyOf(remaining));
        }
    }
}
