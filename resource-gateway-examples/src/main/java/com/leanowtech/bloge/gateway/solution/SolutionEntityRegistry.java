package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

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
    /** Durable asset kind for independently revised four-entity display contracts. */
    public static final String CAPABILITY_DISPLAY = "SOLUTION_CAPABILITY_DISPLAY";
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
        data.set("contract", storedContract(contract));
        data.put("contractFingerprint", contractFingerprint);
        data.put("speccing", contract.speccing());
        return states.executeAtomically(() -> {
            AgentTddStoredAsset stored = saveWhenChanged(scopeKey, FEATURE, contract.featureRef(), data);
            upsertDisplay(scopeKey, "FEATURE", contract.featureRef(), contract.display());
            return new RegisteredEntity(
                    "FEATURE", contract.featureRef(), stored.revision(), contractFingerprint,
                    contract.speccing(), stored.data().path("contract"));
        });
    }

    /** Stores the next Scenario revision and returns its server-owned identity envelope. */
    public RegisteredEntity upsertScenario(String scopeKey, ScenarioContract contract) {
        String contractFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, contract.contractIdentity(), MAX_BYTES);
        ObjectNode data = mapper.createObjectNode();
        data.put("schemaVersion", "bloge.solutionScenario.v1");
        data.put("entityKind", "SCENARIO");
        data.put("ref", contract.scenarioRef());
        data.set("contract", storedContract(contract));
        data.put("contractFingerprint", contractFingerprint);
        data.put("speccing", false);
        return states.executeAtomically(() -> {
            AgentTddStoredAsset stored = saveWhenChanged(scopeKey, SCENARIO, contract.scenarioRef(), data);
            upsertDisplay(scopeKey, "SCENARIO", contract.scenarioRef(), contract.display());
            return new RegisteredEntity(
                    "SCENARIO", contract.scenarioRef(), stored.revision(), contractFingerprint,
                    false, stored.data().path("contract"));
        });
    }

    /** Stores the next Instruction revision and returns its server-owned identity envelope. */
    public RegisteredEntity upsertInstruction(String scopeKey, InstructionContract contract) {
        String contractFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, contract.contractIdentity(), MAX_BYTES);
        ObjectNode data = mapper.createObjectNode();
        data.put("schemaVersion", "bloge.solutionInstruction.v1");
        data.put("entityKind", "INSTRUCTION");
        data.put("ref", contract.instructionRef());
        data.set("contract", storedContract(contract));
        data.put("contractFingerprint", contractFingerprint);
        data.put("speccing", contract.speccing());
        return states.executeAtomically(() -> {
            AgentTddStoredAsset stored = saveWhenChanged(
                    scopeKey, INSTRUCTION, contract.instructionRef(), data);
            upsertDisplay(scopeKey, "INSTRUCTION", contract.instructionRef(), contract.display());
            return new RegisteredEntity(
                    "INSTRUCTION", contract.instructionRef(), stored.revision(), contractFingerprint,
                    contract.speccing(), stored.data().path("contract"));
        });
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
        return upsertSolution(scopeKey, contract, speccing, loweredDraft, contractFingerprint);
    }

    /** Stores a Solution together with the exact server-issued authoring receipt it may commit. */
    public RegisteredEntity upsertSolution(
            String scopeKey,
            SolutionContract contract,
            boolean speccing,
            com.leanowtech.bloge.gateway.visual.draft.GraphDraft loweredDraft,
            String authoringReceiptFingerprint) {
        String contractFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, contract.contractIdentity(), MAX_BYTES);
        ObjectNode data = mapper.createObjectNode();
        data.put("schemaVersion", "bloge.solutionContract.v1");
        data.put("entityKind", "SOLUTION");
        data.put("ref", contract.solutionRef());
        data.set("contract", storedContract(contract));
        data.put("contractFingerprint", contractFingerprint);
        data.put("speccing", speccing);
        data.put("authoringReceiptFingerprint", Objects.requireNonNull(
                authoringReceiptFingerprint, "authoringReceiptFingerprint"));
        if (loweredDraft != null) data.set("loweredDraft", mapper.valueToTree(loweredDraft));
        return states.executeAtomically(() -> {
            AgentTddStoredAsset stored = saveSolutionWhenChanged(
                    scopeKey, contract.solutionRef(), data);
            upsertDisplay(scopeKey, "SOLUTION", contract.solutionRef(), contract.display());
            return new RegisteredEntity(
                    "SOLUTION", contract.solutionRef(), stored.revision(), contractFingerprint,
                    speccing, stored.data().path("contract"));
        });
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

    /** Resolves the current Solution revision and contract fingerprint for evidence fencing. */
    public RegisteredEntity requireRegisteredSolution(String scopeKey, String solutionRef) {
        return requireRegistered(scopeKey, SOLUTION, "SOLUTION", solutionRef);
    }

    /** Resolves the current Instruction revision and contract fingerprint for handoff fencing. */
    public RegisteredEntity requireRegisteredInstruction(String scopeKey, String instructionRef) {
        return requireRegistered(scopeKey, INSTRUCTION, "INSTRUCTION", instructionRef);
    }

    /**
     * Loads one complete executable closure and the revision fence captured with each contract.
     *
     * <p>Each entity is read once. The caller must verify {@link
     * SolutionExecutableSnapshot#isCurrent(AgentTddStateRepository, String)} before reserving an
     * external effect, after which the returned contracts are safe from authoring-store races.</p>
     */
    public SolutionExecutableSnapshot freezeExecutable(String scopeKey, String solutionRef) {
        ArrayList<SolutionExecutableSnapshot.EntityCoordinate> coordinates = new ArrayList<>();
        AgentTddStoredAsset solutionAsset = requireAsset(scopeKey, SOLUTION, solutionRef);
        RegisteredEntity solutionIdentity = registered("SOLUTION", solutionRef, solutionAsset);
        SolutionContract solution = decode(scopeKey, solutionAsset, SolutionContract.class);
        coordinates.add(coordinate(SOLUTION, solutionRef, solutionAsset));

        LinkedHashMap<String, FeatureContract> features = new LinkedHashMap<>();
        for (String ref : new TreeSet<>(solution.inputs().values())) {
            AgentTddStoredAsset asset = requireAsset(scopeKey, FEATURE, ref);
            features.put(ref, decode(scopeKey, asset, FeatureContract.class));
            coordinates.add(coordinate(FEATURE, ref, asset));
        }

        LinkedHashMap<String, ScenarioContract> scenarios = new LinkedHashMap<>();
        freezeScenarios(scopeKey, solution.rootScenarioRef(), scenarios, coordinates);
        TreeSet<String> instructionRefs = new TreeSet<>(solution.instructions());
        scenarios.values().forEach(value -> instructionRefs.addAll(value.referencedInstructions()));
        LinkedHashMap<String, InstructionContract> instructions = new LinkedHashMap<>();
        for (String ref : instructionRefs) {
            AgentTddStoredAsset asset = requireAsset(scopeKey, INSTRUCTION, ref);
            instructions.put(ref, decode(scopeKey, asset, InstructionContract.class));
            coordinates.add(coordinate(INSTRUCTION, ref, asset));
        }
        return new SolutionExecutableSnapshot(solutionIdentity,
                new PublishedSolutionSnapshot(solution, features, scenarios, instructions), coordinates);
    }

    private void freezeScenarios(
            String scopeKey,
            String ref,
            Map<String, ScenarioContract> scenarios,
            List<SolutionExecutableSnapshot.EntityCoordinate> coordinates) {
        if (scenarios.containsKey(ref)) return;
        AgentTddStoredAsset asset = requireAsset(scopeKey, SCENARIO, ref);
        ScenarioContract scenario = decode(scopeKey, asset, ScenarioContract.class);
        scenarios.put(ref, scenario);
        coordinates.add(coordinate(SCENARIO, ref, asset));
        for (String child : new TreeSet<>(scenario.referencedScenarios())) {
            freezeScenarios(scopeKey, child, scenarios, coordinates);
        }
    }

    private AgentTddStoredAsset requireAsset(String scopeKey, String kind, String ref) {
        return states.find(scopeKey, kind, ref).orElseThrow(EntityUnavailableException::new);
    }

    private RegisteredEntity registered(
            String entityKind, String ref, AgentTddStoredAsset asset) {
        JsonNode data = asset.data();
        if (!data.path("contract").isObject() || data.path("contractFingerprint").asText().isBlank()) {
            throw new EntityUnavailableException();
        }
        return new RegisteredEntity(entityKind, ref, asset.revision(),
                data.path("contractFingerprint").asText(), data.path("speccing").asBoolean(),
                data.path("contract"));
    }

    private static SolutionExecutableSnapshot.EntityCoordinate coordinate(
            String kind, String ref, AgentTddStoredAsset asset) {
        return new SolutionExecutableSnapshot.EntityCoordinate(kind, ref, asset.revision(),
                asset.data().path("contractFingerprint").asText());
    }

    private <T> T decode(String scopeKey, AgentTddStoredAsset asset, Class<T> type) {
        try {
            T contract = mapper.treeToValue(asset.data().path("contract"), type);
            java.util.Optional<RegisteredDisplay> display;
            try {
                display = findDisplay(scopeKey, entityKindForType(type), asset.assetRef());
            } catch (EntityUnavailableException ignored) {
                return contract;
            }
            if (display.isEmpty()) return contract;
            BusinessCapabilityDisplay value = display.get().display();
            if (contract instanceof FeatureContract feature) return type.cast(feature.withDisplay(value));
            if (contract instanceof ScenarioContract scenario) return type.cast(scenario.withDisplay(value));
            if (contract instanceof InstructionContract instruction) return type.cast(instruction.withDisplay(value));
            if (contract instanceof SolutionContract solution) return type.cast(solution.withDisplay(value));
            return contract;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw new EntityUnavailableException();
        }
    }

    private RegisteredEntity requireRegistered(
            String scopeKey, String kind, String entityKind, String ref) {
        AgentTddStoredAsset asset = states.find(scopeKey, kind, ref)
                .orElseThrow(EntityUnavailableException::new);
        JsonNode data = asset.data();
        if (!data.path("contract").isObject() || data.path("contractFingerprint").asText().isBlank()) {
            throw new EntityUnavailableException();
        }
        return new RegisteredEntity(entityKind, ref, asset.revision(),
                data.path("contractFingerprint").asText(), data.path("speccing").asBoolean(),
                data.path("contract"));
    }

    /** Resolves the only authoring receipt accepted when the current Solution is committed. */
    public String requireSolutionAuthoringReceipt(String scopeKey, String solutionRef) {
        String receipt = states.find(scopeKey, SOLUTION, solutionRef)
                .map(AgentTddStoredAsset::data)
                .map(data -> data.path("authoringReceiptFingerprint").asText())
                .orElse("");
        if (receipt.isBlank()) throw new EntityUnavailableException();
        return receipt;
    }

    private <T> T require(String scopeKey, String kind, String ref, Class<T> type) {
        AgentTddStoredAsset asset = states.find(scopeKey, kind, ref)
                .orElseThrow(EntityUnavailableException::new);
        return decode(scopeKey, asset, type);
    }

    /** Returns the current independently revisioned display row for one exact entity. */
    public RegisteredDisplay requireDisplay(String scopeKey, String entityKind, String entityRef) {
        return findDisplay(scopeKey, entityKind, entityRef).orElseThrow(EntityUnavailableException::new);
    }

    /** Creates the collision-free internal reference used by independent display rows. */
    public static String displayAssetRef(String entityKind, String entityRef) {
        return Objects.requireNonNull(entityKind, "entityKind").trim().toUpperCase(java.util.Locale.ROOT)
                + '\u001f' + Objects.requireNonNull(entityRef, "entityRef").trim();
    }

    private java.util.Optional<RegisteredDisplay> findDisplay(
            String scopeKey, String entityKind, String entityRef) {
        return states.find(scopeKey, CAPABILITY_DISPLAY, displayAssetRef(entityKind, entityRef))
                .map(asset -> {
                    JsonNode data = asset.data();
                    if (!entityKind.equalsIgnoreCase(data.path("entityKind").asText())
                            || !entityRef.equals(data.path("entityRef").asText())
                            || !data.path("display").isObject()
                            || data.path("displayFingerprint").asText().isBlank()) {
                        throw new EntityUnavailableException();
                    }
                    BusinessCapabilityDisplay display = BusinessCapabilityDisplay.decode(data.path("display"));
                    String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                            mapper, display, MAX_BYTES);
                    if (!fingerprint.equals(data.path("displayFingerprint").asText())) {
                        throw new EntityUnavailableException();
                    }
                    return new RegisteredDisplay(asset.revision(), fingerprint, display);
                });
    }

    private void upsertDisplay(
            String scopeKey, String entityKind, String entityRef, BusinessCapabilityDisplay display) {
        if (display.legacyProjection()) return;
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, display, MAX_BYTES);
        String assetRef = displayAssetRef(entityKind, entityRef);
        java.util.Optional<AgentTddStoredAsset> current = states.find(
                scopeKey, CAPABILITY_DISPLAY, assetRef);
        if (current.filter(asset -> fingerprint.equals(
                asset.data().path("displayFingerprint").asText())).isPresent()) return;
        ObjectNode data = mapper.createObjectNode();
        data.put("schemaVersion", "rg.businessCapabilityDisplayRecord.v1");
        data.put("entityKind", entityKind);
        data.put("entityRef", entityRef);
        data.set("display", mapper.valueToTree(display));
        data.put("displayFingerprint", fingerprint);
        states.save(scopeKey, CAPABILITY_DISPLAY, assetRef, data);
    }

    private ObjectNode storedContract(Object contract) {
        ObjectNode stored = mapper.valueToTree(contract);
        stored.remove("display");
        return stored;
    }

    private AgentTddStoredAsset saveWhenChanged(
            String scopeKey, String kind, String ref, ObjectNode data) {
        return states.find(scopeKey, kind, ref).filter(asset -> asset.data().equals(data))
                .orElseGet(() -> states.save(scopeKey, kind, ref, data));
    }

    private AgentTddStoredAsset saveSolutionWhenChanged(
            String scopeKey, String ref, ObjectNode data) {
        java.util.Optional<AgentTddStoredAsset> current = states.find(scopeKey, SOLUTION, ref);
        if (current.isPresent()) {
            ObjectNode previous = current.get().data().deepCopy();
            ObjectNode candidate = data.deepCopy();
            previous.remove("authoringReceiptFingerprint");
            candidate.remove("authoringReceiptFingerprint");
            if (previous.equals(candidate)) return current.get();
        }
        return states.save(scopeKey, SOLUTION, ref, data);
    }

    private static String entityKindForType(Class<?> type) {
        if (type == FeatureContract.class) return "FEATURE";
        if (type == ScenarioContract.class) return "SCENARIO";
        if (type == InstructionContract.class) return "INSTRUCTION";
        if (type == SolutionContract.class) return "SOLUTION";
        throw new IllegalArgumentException("Unsupported solution entity type");
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

    /** Immutable identity envelope for an independently revised display contract. */
    public record RegisteredDisplay(
            long revision,
            String displayFingerprint,
            BusinessCapabilityDisplay display
    ) {
        /** Validates the stored display coordinate. */
        public RegisteredDisplay {
            if (revision < 1 || displayFingerprint == null || displayFingerprint.isBlank()
                    || display == null) throw new IllegalArgumentException("Display coordinate is invalid");
        }
    }
}
