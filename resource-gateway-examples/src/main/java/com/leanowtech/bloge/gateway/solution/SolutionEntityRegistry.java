package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Objects;

/**
 * Canonical persistence boundary for the four solution entity contracts.
 *
 * <p>Entity references are unique only inside the server-derived integration scope. The registry
 * stores the full implementation-bearing definition while computing a separate business contract
 * fingerprint that excludes mutable bindings.</p>
 */
@org.springframework.stereotype.Service
public final class SolutionEntityRegistry {
    /** Durable asset kind for Feature contracts. */
    public static final String FEATURE = "SOLUTION_FEATURE";
    /** Durable asset kind for Scenario contracts. */
    public static final String SCENARIO = "SOLUTION_SCENARIO";
    /** Durable asset kind for Instruction contracts. */
    public static final String INSTRUCTION = "SOLUTION_INSTRUCTION";
    /** Durable asset kind for Solution contracts. */
    public static final String SOLUTION = "SOLUTION_SOLUTION";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final ObjectMapper mapper;

    /** Creates a registry over the existing durable Agent TDD asset store. */
    public SolutionEntityRegistry(AgentTddStateRepository states, ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Stores the next Feature revision and returns its server-owned identity envelope. */
    public RegisteredEntity upsertFeature(String scopeKey, FeatureContract contract) {
        String contractFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, contract.contractIdentity(), MAX_BYTES);
        ObjectNode data = mapper.createObjectNode();
        data.put("schemaVersion", "bloge.solutionFeature.v1");
        data.put("entityKind", "FEATURE");
        data.put("ref", contract.featureRef());
        data.set("contract", mapper.valueToTree(contract));
        data.put("contractFingerprint", contractFingerprint);
        data.put("speccing", contract.speccing());
        AgentTddStoredAsset stored = states.save(scopeKey, FEATURE, contract.featureRef(), data);
        return new RegisteredEntity(
                "FEATURE", contract.featureRef(), stored.revision(), contractFingerprint,
                contract.speccing(), stored.data().path("contract"));
    }

    /** Stores the next Scenario revision and returns its server-owned identity envelope. */
    public RegisteredEntity upsertScenario(String scopeKey, ScenarioContract contract) {
        String contractFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, contract.contractIdentity(), MAX_BYTES);
        ObjectNode data = mapper.createObjectNode();
        data.put("schemaVersion", "bloge.solutionScenario.v1");
        data.put("entityKind", "SCENARIO");
        data.put("ref", contract.scenarioRef());
        data.set("contract", mapper.valueToTree(contract));
        data.put("contractFingerprint", contractFingerprint);
        data.put("speccing", false);
        AgentTddStoredAsset stored = states.save(scopeKey, SCENARIO, contract.scenarioRef(), data);
        return new RegisteredEntity(
                "SCENARIO", contract.scenarioRef(), stored.revision(), contractFingerprint,
                false, stored.data().path("contract"));
    }

    /** Stores the next Instruction revision and returns its server-owned identity envelope. */
    public RegisteredEntity upsertInstruction(String scopeKey, InstructionContract contract) {
        String contractFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, contract.contractIdentity(), MAX_BYTES);
        ObjectNode data = mapper.createObjectNode();
        data.put("schemaVersion", "bloge.solutionInstruction.v1");
        data.put("entityKind", "INSTRUCTION");
        data.put("ref", contract.instructionRef());
        data.set("contract", mapper.valueToTree(contract));
        data.put("contractFingerprint", contractFingerprint);
        data.put("speccing", contract.speccing());
        AgentTddStoredAsset stored = states.save(scopeKey, INSTRUCTION, contract.instructionRef(), data);
        return new RegisteredEntity(
                "INSTRUCTION", contract.instructionRef(), stored.revision(), contractFingerprint,
                contract.speccing(), stored.data().path("contract"));
    }

    /** Stores the next Solution revision and returns its server-owned identity envelope. */
    public RegisteredEntity upsertSolution(
            String scopeKey, SolutionContract contract, boolean speccing) {
        return upsertSolution(scopeKey, contract, speccing, null);
    }

    /** Stores a Solution contract together with its server-precompiled graph projection. */
    public RegisteredEntity upsertSolution(
            String scopeKey,
            SolutionContract contract,
            boolean speccing,
            com.leanowtech.bloge.gateway.visual.draft.GraphDraft loweredDraft) {
        String contractFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, contract.contractIdentity(), MAX_BYTES);
        ObjectNode data = mapper.createObjectNode();
        data.put("schemaVersion", "bloge.solutionContract.v1");
        data.put("entityKind", "SOLUTION");
        data.put("ref", contract.solutionRef());
        data.set("contract", mapper.valueToTree(contract));
        data.put("contractFingerprint", contractFingerprint);
        data.put("speccing", speccing);
        if (loweredDraft != null) data.set("loweredDraft", mapper.valueToTree(loweredDraft));
        AgentTddStoredAsset stored = states.save(scopeKey, SOLUTION, contract.solutionRef(), data);
        return new RegisteredEntity(
                "SOLUTION", contract.solutionRef(), stored.revision(), contractFingerprint,
                speccing, stored.data().path("contract"));
    }

    /** Resolves and decodes one Feature inside the exact authenticated scope. */
    public FeatureContract requireFeature(String scopeKey, String featureRef) {
        return require(scopeKey, FEATURE, featureRef, FeatureContract.class);
    }

    /** Resolves and decodes one Scenario inside the exact authenticated scope. */
    public ScenarioContract requireScenario(String scopeKey, String scenarioRef) {
        return require(scopeKey, SCENARIO, scenarioRef, ScenarioContract.class);
    }

    /** Resolves and decodes one Instruction inside the exact authenticated scope. */
    public InstructionContract requireInstruction(String scopeKey, String instructionRef) {
        return require(scopeKey, INSTRUCTION, instructionRef, InstructionContract.class);
    }

    /** Resolves one Solution contract inside the exact authenticated scope. */
    public SolutionContract requireSolution(String scopeKey, String solutionRef) {
        return require(scopeKey, SOLUTION, solutionRef, SolutionContract.class);
    }

    private <T> T require(String scopeKey, String kind, String ref, Class<T> type) {
        AgentTddStoredAsset asset = states.find(scopeKey, kind, ref)
                .orElseThrow(EntityUnavailableException::new);
        try {
            return mapper.treeToValue(asset.data().path("contract"), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new EntityUnavailableException();
        }
    }

    /** Internal payload-free signal mapped to a stable application error at the MCP boundary. */
    public static final class EntityUnavailableException extends RuntimeException {
        /** Creates a signal without rejected references or persisted payloads in its message. */
        public EntityUnavailableException() {
            super("Referenced solution entity is unavailable.");
        }
    }

    /** Immutable server-owned registry projection. */
    public record RegisteredEntity(
            String entityKind,
            String ref,
            long revision,
            String contractFingerprint,
            boolean speccing,
            JsonNode contract
    ) {
        /** Freezes the persisted contract projection. */
        public RegisteredEntity {
            contract = contract == null ? null : contract.deepCopy();
        }

        @Override
        public JsonNode contract() {
            return contract == null ? null : contract.deepCopy();
        }
    }
}
