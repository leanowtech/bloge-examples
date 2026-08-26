package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlConsumption;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlEvidenceBinding;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlObservation;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlRunEvidence;
import com.leanowtech.bloge.gateway.testing.world.WorldInvocationCoordinate;
import com.leanowtech.bloge.gateway.testing.world.WorldStateSnapshot;
import com.leanowtech.bloge.gateway.testing.world.WorldStateTransactionObservation;

import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned, payload-free external evidence for controlled world/function execution. */
public record TestRunControlEvidenceProjection(
        String schemaVersion,
        String runId,
        String scenarioFingerprint,
        String worldFingerprint,
        String targetFingerprint,
        String executionPlanFingerprint,
        String functionPlanFingerprint,
        StateProjection state,
        FunctionProjection function,
        String projectionFingerprint
) {
    public static final String SCHEMA_VERSION = "bloge.testRunControlEvidence.v1";
    public static final int MAX_ITEMS = 4_096;
    public static final int MAX_BYTES = 256 * 1024;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TestRunControlEvidenceProjection {
        if (!SCHEMA_VERSION.equals(schemaVersion) || blank(runId) || runId.length() > 256
                || state == null && function == null || scenarioFingerprint == null
                || worldFingerprint == null || state != null && (!valid(scenarioFingerprint)
                || !valid(worldFingerprint)) || state == null
                && (!scenarioFingerprint.isBlank() || !worldFingerprint.isBlank())
                || !valid(targetFingerprint)
                || !valid(executionPlanFingerprint) || functionPlanFingerprint == null
                || !functionPlanFingerprint.isBlank() && !valid(functionPlanFingerprint)
                || !valid(projectionFingerprint)
                || state != null && (!runId.equals(state.runId())
                || !scenarioFingerprint.equals(state.scenarioFingerprint())
                || !worldFingerprint.equals(state.worldFingerprint())
                || !targetFingerprint.equals(state.graphArtifactFingerprint()))
                || function != null && !functionPlanFingerprint.equals(function.planFingerprint())
                || function == null && !functionPlanFingerprint.isBlank()
                || !compute(schemaVersion, runId, scenarioFingerprint, worldFingerprint,
                targetFingerprint, executionPlanFingerprint,
                functionPlanFingerprint, state, function).equals(projectionFingerprint)) {
            throw invalid();
        }
        runId = runId.trim();
        scenarioFingerprint = scenarioFingerprint.trim();
        worldFingerprint = worldFingerprint.trim();
        targetFingerprint = targetFingerprint.trim();
        executionPlanFingerprint = executionPlanFingerprint.trim();
        functionPlanFingerprint = functionPlanFingerprint.trim();
        projectionFingerprint = projectionFingerprint.trim();
    }

    /** Creates a run-bound projection from server-owned runtime facts. */
    public static TestRunControlEvidenceProjection from(
            String runId, String scenarioFingerprint, String worldFingerprint,
            String targetFingerprint, String executionPlanFingerprint,
            String functionPlanFingerprint, WorldStateSnapshot snapshot,
            FunctionControlRunEvidence functionEvidence) {
        StateProjection state = snapshot == null ? null : StateProjection.from(snapshot);
        FunctionProjection function = functionEvidence == null
                ? null : FunctionProjection.from(functionEvidence);
        String fingerprint = compute(SCHEMA_VERSION, runId, scenarioFingerprint, worldFingerprint,
                targetFingerprint,
                executionPlanFingerprint, functionPlanFingerprint, state, function);
        return new TestRunControlEvidenceProjection(SCHEMA_VERSION, runId, scenarioFingerprint,
                worldFingerprint, targetFingerprint,
                executionPlanFingerprint, functionPlanFingerprint, state, function, fingerprint);
    }

    /** Computes the complete run-bound projection identity, excluding only itself. */
    public static String compute(String schemaVersion, String runId, String scenarioFingerprint,
                                 String worldFingerprint, String targetFingerprint,
                                 String executionPlanFingerprint, String functionPlanFingerprint,
                                 StateProjection state, FunctionProjection function) {
        try {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", schemaVersion);
            material.put("runId", runId);
            material.put("scenarioFingerprint", scenarioFingerprint);
            material.put("worldFingerprint", worldFingerprint);
            material.put("targetFingerprint", targetFingerprint);
            material.put("executionPlanFingerprint", executionPlanFingerprint);
            material.put("functionPlanFingerprint", functionPlanFingerprint);
            material.put("state", state);
            material.put("function", function);
            return ProtocolFingerprint.ofBounded(MAPPER, material, MAX_BYTES);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** Stable semantic material. It deliberately omits run identity and self-fingerprints. */
    public Map<String, Object> stableSemanticMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("scenarioFingerprint", scenarioFingerprint);
        material.put("worldFingerprint", worldFingerprint);
        material.put("targetFingerprint", targetFingerprint);
        material.put("executionPlanFingerprint", executionPlanFingerprint);
        material.put("functionPlanFingerprint", functionPlanFingerprint);
        material.put("state", state == null ? null : state.stableSemanticMaterial());
        material.put("function", function == null ? null : function.stableSemanticMaterial());
        return Collections.unmodifiableMap(material);
    }

    /** Payload-free state facts; state values remain server-owned and are never projected. */
    public record StateProjection(
            String scenarioFingerprint,
            String worldFingerprint,
            String graphArtifactFingerprint,
            String runId,
            String stateSpecFingerprint,
            long revision,
            String stateEvidenceFingerprint,
            List<TransactionProjection> transactions
    ) {
        public StateProjection {
            requireFingerprint(scenarioFingerprint);
            requireFingerprint(worldFingerprint);
            requireFingerprint(graphArtifactFingerprint);
            requireFingerprint(stateSpecFingerprint);
            requireFingerprint(stateEvidenceFingerprint);
            if (blank(runId) || runId.length() > 256 || revision < 0 || transactions == null
                    || transactions.size() > MAX_ITEMS || transactions.stream().anyMatch(Objects::isNull)) {
                throw invalid();
            }
            List<TransactionProjection> canonicalTransactions = transactions.stream()
                    .sorted(Comparator.comparing(value -> value.coordinate().canonicalKey())).toList();
            if (hasDuplicate(canonicalTransactions.stream()
                    .map(value -> value.coordinate().canonicalKey()).toList())
                    || revision != canonicalTransactions.size()
                    || !stateEvidenceFingerprint.equals(computeStateEvidence(
                    scenarioFingerprint, worldFingerprint, graphArtifactFingerprint,
                    stateSpecFingerprint, revision, canonicalTransactions))) throw invalid();
            transactions = canonicalTransactions;
        }

        static StateProjection from(WorldStateSnapshot snapshot) {
            WorldStateSnapshot value = Objects.requireNonNull(snapshot, "snapshot");
            List<TransactionProjection> transactions = value.observations().stream()
                    .map(TransactionProjection::from).toList();
            String stateFingerprint = computeStateEvidence(value.binding().scenarioFingerprint(),
                    value.binding().worldFingerprint(), value.binding().graphArtifactFingerprint(),
                    value.stateSpecFingerprint(), value.revision(), transactions);
            return new StateProjection(value.binding().scenarioFingerprint(),
                    value.binding().worldFingerprint(), value.binding().graphArtifactFingerprint(),
                    value.binding().runId(), value.stateSpecFingerprint(), value.revision(),
                    stateFingerprint, transactions);
        }

        Map<String, Object> stableSemanticMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("scenarioFingerprint", scenarioFingerprint);
            material.put("worldFingerprint", worldFingerprint);
            material.put("graphArtifactFingerprint", graphArtifactFingerprint);
            material.put("stateSpecFingerprint", stateSpecFingerprint);
            material.put("revision", revision);
            material.put("stateEvidenceFingerprint", stateEvidenceFingerprint);
            material.put("transactions", transactions);
            return Collections.unmodifiableMap(material);
        }

        private static String computeStateEvidence(StateProjection value) {
            return computeStateEvidence(value.scenarioFingerprint, value.worldFingerprint,
                    value.graphArtifactFingerprint, value.stateSpecFingerprint, value.revision,
                    value.transactions);
        }

        private static String computeStateEvidence(String scenario, String world, String graph,
                                                    String spec, long revision,
                                                    List<TransactionProjection> transactions) {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("scenarioFingerprint", scenario);
            material.put("worldFingerprint", world);
            material.put("graphArtifactFingerprint", graph);
            material.put("stateSpecFingerprint", spec);
            material.put("revision", revision);
            material.put("transactions", transactions);
            return ProtocolFingerprint.ofBounded(MAPPER, material, MAX_BYTES);
        }
    }

    public record TransactionProjection(
            WorldInvocationCoordinate coordinate, List<String> readKeys, List<String> writeKeys,
            String readFingerprint, String writeFingerprint, String resultFingerprint
    ) {
        public TransactionProjection {
            if (coordinate == null || readKeys == null || writeKeys == null
                    || readKeys.size() > MAX_ITEMS || writeKeys.size() > MAX_ITEMS) throw invalid();
            requireFingerprint(readFingerprint); requireFingerprint(writeFingerprint);
            requireFingerprint(resultFingerprint);
            readKeys = canonicalKeys(readKeys); writeKeys = canonicalKeys(writeKeys);
        }

        static TransactionProjection from(WorldStateTransactionObservation value) {
            WorldStateTransactionObservation observation = Objects.requireNonNull(value, "observation");
            return new TransactionProjection(observation.coordinate(), observation.readKeys(),
                    observation.writeKeys(), observation.readFingerprint(), observation.writeFingerprint(),
                    observation.resultFingerprint());
        }
    }

    public record FunctionProjection(
            String planFingerprint, String evidenceCeiling, List<FunctionBindingProjection> bindings,
            List<ConsumptionProjection> consumptions, List<FunctionObservationProjection> observations,
            String evidenceFingerprint
    ) {
        public FunctionProjection {
            requireFingerprint(planFingerprint); requireFingerprint(evidenceFingerprint);
            requireEnum(evidenceCeiling, "CERTIFIABLE", "EXPLORATORY", "PREVIEW");
            if (bindings == null || consumptions == null || observations == null
                    || bindings.size() > MAX_ITEMS || consumptions.size() > MAX_ITEMS
                    || observations.size() > MAX_ITEMS || bindings.stream().anyMatch(Objects::isNull)
                    || consumptions.stream().anyMatch(Objects::isNull)
                    || observations.stream().anyMatch(Objects::isNull)) throw invalid();
            bindings = bindings.stream().sorted(Comparator.comparing(FunctionBindingProjection::siteKey)).toList();
            consumptions = consumptions.stream().sorted(Comparator.comparing(ConsumptionProjection::ruleId)).toList();
            observations = observations.stream().sorted(Comparator.comparing(FunctionObservationProjection::siteKey)
                    .thenComparing(FunctionObservationProjection::ruleId)
                    .thenComparingLong(FunctionObservationProjection::occurrence)
                    .thenComparing(FunctionObservationProjection::invocationScopeFingerprint)
                    .thenComparing(FunctionObservationProjection::argumentsFingerprint)).toList();
            if (hasDuplicate(bindings.stream().map(FunctionBindingProjection::siteKey).toList())
                    || hasDuplicate(consumptions.stream().map(ConsumptionProjection::ruleId).toList())
                    || hasDuplicate(observations.stream().map(value -> value.siteKey() + "\u0000"
                    + value.ruleId() + "\u0000" + value.occurrence() + "\u0000"
                    + value.invocationScopeFingerprint() + "\u0000" + value.argumentsFingerprint()).toList())) throw invalid();
        }

        static FunctionProjection from(FunctionControlRunEvidence value) {
            FunctionControlRunEvidence evidence = Objects.requireNonNull(value, "evidence");
            return new FunctionProjection(evidence.planFingerprint(), evidence.evidenceCeiling().name(),
                    evidence.bindings().stream().map(FunctionBindingProjection::from).toList(),
                    evidence.consumptions().stream().map(ConsumptionProjection::from).toList(),
                    evidence.observations().stream().map(FunctionObservationProjection::from).toList(),
                    evidence.evidenceFingerprint());
        }

        Map<String, Object> stableSemanticMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("planFingerprint", planFingerprint); material.put("evidenceCeiling", evidenceCeiling);
            material.put("bindings", bindings); material.put("consumptions", consumptions);
            material.put("observations", observations); material.put("evidenceFingerprint", evidenceFingerprint);
            return Collections.unmodifiableMap(material);
        }
    }

    public record FunctionBindingProjection(
            String siteKey, String graphPath, String nodeId, String functionName, int line, int column,
            String functionFingerprint, String runtimeFingerprint, String mode, String evidenceCeiling,
            String downgradeReason
    ) {
        public FunctionBindingProjection {
            requiredText(siteKey); requiredText(graphPath); requiredText(nodeId); requiredText(functionName);
            requireFingerprint(functionFingerprint); requireFingerprint(runtimeFingerprint);
            requireEnum(mode, "DIRECT", "CONTROLLED"); requireEnum(evidenceCeiling, "CERTIFIABLE", "EXPLORATORY", "PREVIEW");
            if (line < 0 || column < 0 || downgradeReason == null || downgradeReason.length() > 4_096
                    || hasControl(downgradeReason)) throw invalid();
            downgradeReason = downgradeReason.trim();
        }

        static FunctionBindingProjection from(FunctionControlEvidenceBinding value) {
            var site = value.site();
            return new FunctionBindingProjection(site.structuralKey(), site.graphPath(), site.nodeId(),
                    site.functionName(), site.line(), site.column(), value.functionFingerprint(),
                    value.runtimeFingerprint(), value.mode().name(), value.evidenceCeiling().name(), value.downgradeReason());
        }
    }

    public record ConsumptionProjection(String ruleId, long minimum, long maximum, long used, String status) {
        public ConsumptionProjection {
            requiredText(ruleId); requireEnum(status, "SATISFIED", "MAX_REACHED", "MINIMUM_UNSATISFIED");
            if (minimum < 0 || maximum < minimum || used < 0 || used > maximum) throw invalid();
        }

        static ConsumptionProjection from(FunctionControlConsumption value) {
            return new ConsumptionProjection(value.ruleId(), value.minimum(), value.maximum(), value.used(), value.status());
        }
    }

    public record FunctionObservationProjection(
            String siteKey, String ruleId, String behavior, String invocationScopeFingerprint,
            String argumentsFingerprint, String resultFingerprint, String errorFingerprint,
            long occurrence, long logicalDurationMillis
    ) {
        public FunctionObservationProjection {
            requiredText(siteKey); requiredText(ruleId); requireEnum(behavior, "RETURN", "THROW", "DELAY", "TIMEOUT");
            requireFingerprint(invocationScopeFingerprint); requireFingerprint(argumentsFingerprint);
            resultFingerprint = normalizeFingerprint(resultFingerprint); errorFingerprint = normalizeFingerprint(errorFingerprint);
            boolean failed = !errorFingerprint.isBlank();
            boolean resultExpected = "RETURN".equals(behavior) || "DELAY".equals(behavior);
            if (occurrence < 1 || logicalDurationMillis < 0
                    || (!failed && resultExpected && resultFingerprint.isBlank())
                    || (!failed && !resultExpected)
                    || (failed && !resultFingerprint.isBlank())) throw invalid();
        }

        static FunctionObservationProjection from(FunctionControlObservation value) {
            return new FunctionObservationProjection(value.site().structuralKey(), value.ruleId(), value.behavior().name(),
                    value.invocationScopeFingerprint(), value.argumentsFingerprint(), value.resultFingerprint(),
                    value.errorFingerprint(), value.occurrence(), value.logicalDurationMillis());
        }
    }

    private static List<String> canonicalKeys(List<String> values) {
        List<String> normalized = values.stream().map(TestRunControlEvidenceProjection::requiredKey).sorted().toList();
        if (hasDuplicate(normalized)) throw invalid();
        return normalized;
    }

    private static String normalizeFingerprint(String value) {
        if (value == null || value.isBlank()) return "";
        requireFingerprint(value); return value.trim();
    }

    private static String requiredKey(String value) {
        if (blank(value) || value.length() > 4_096 || hasControl(value)) throw invalid();
        return value.trim();
    }

    private static void requiredText(String value) {
        if (blank(value) || value.length() > 4_096 || hasControl(value)) throw invalid();
    }

    private static void requireFingerprint(String value) {
        if (!valid(value)) throw invalid();
    }

    private static void requireEnum(String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return;
        throw invalid();
    }

    private static boolean valid(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static boolean hasDuplicate(List<String> values) {
        return values.size() != new HashSet<>(values).size();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasControl(String value) {
        return value != null && value.chars().anyMatch(Character::isISOControl);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("RG.TEST.CONTROL_EVIDENCE_INVALID");
    }
}
