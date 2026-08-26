package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Strict, payload-free consumer projection of the server's E1 state/function evidence.
 *
 * <p>This type intentionally has no state values, request/response values, function arguments,
 * return values, error messages, or schemas. It is a consumer-owned wire model rather than a
 * dependency on Resource Gateway server classes.</p>
 */
public final class TestRunControlEvidenceProjection {
    /** Projection schema version. */
    public static final String SCHEMA_VERSION = "bloge.testRunControlEvidence.v1";
    /** Maximum number of payload-free entries in any projection collection. */
    public static final int MAX_ITEMS = 4_096;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MAX_TEXT = 4_096;

    private final String schemaVersion;
    private final String runId;
    private final String scenarioFingerprint;
    private final String worldFingerprint;
    private final String targetFingerprint;
    private final String executionPlanFingerprint;
    private final String functionPlanFingerprint;
    private final State state;
    private final Function function;
    private final String projectionFingerprint;

    private TestRunControlEvidenceProjection(String schemaVersion, String runId,
            String scenarioFingerprint, String worldFingerprint, String targetFingerprint,
            String executionPlanFingerprint, String functionPlanFingerprint, State state,
            Function function, String projectionFingerprint) {
        this.schemaVersion = schemaVersion;
        this.runId = runId;
        this.scenarioFingerprint = scenarioFingerprint;
        this.worldFingerprint = worldFingerprint;
        this.targetFingerprint = targetFingerprint;
        this.executionPlanFingerprint = executionPlanFingerprint;
        this.functionPlanFingerprint = functionPlanFingerprint;
        this.state = state;
        this.function = function;
        this.projectionFingerprint = projectionFingerprint;
        validate();
    }

    /**
     * Decodes one already-JSON-decoded projection and verifies all consumer invariants.
     * @param value JSON object received from the Resource Gateway evidence response
     * @return validated payload-free evidence projection
     */
    public static TestRunControlEvidenceProjection from(JsonNode value) {
        try {
            TestingProtocolSchemaValidator.require(value, "testRunControlEvidence");
            if (value == null || !value.isObject()) throw invalid();
            State state = value.path("state").isMissingNode() || value.path("state").isNull()
                    ? null : parseState(value.path("state"));
            Function function = value.path("function").isMissingNode() || value.path("function").isNull()
                    ? null : parseFunction(value.path("function"));
            return new TestRunControlEvidenceProjection(
                    text(value, "schemaVersion"), text(value, "runId"),
                    optionalFingerprint(value, "scenarioFingerprint"),
                    optionalFingerprint(value, "worldFingerprint"),
                    fingerprint(value, "targetFingerprint"),
                    fingerprint(value, "executionPlanFingerprint"),
                    optionalFingerprint(value, "functionPlanFingerprint"), state, function,
                    fingerprint(value, "projectionFingerprint"));
        } catch (RuntimeException ignored) {
            throw invalid();
        }
    }

    /**
     * Returns the projection schema version.
     * @return projection schema version
     */
    public String schemaVersion() { return schemaVersion; }
    /**
     * Returns the run identity.
     * @return run identity
     */
    public String runId() { return runId; }
    /**
     * Returns the scenario asset fingerprint.
     * @return scenario asset fingerprint, or empty
     */
    public String scenarioFingerprint() { return scenarioFingerprint; }
    /**
     * Returns the world asset fingerprint.
     * @return world asset fingerprint, or empty
     */
    public String worldFingerprint() { return worldFingerprint; }
    /**
     * Returns the graph target fingerprint.
     * @return graph target fingerprint
     */
    public String targetFingerprint() { return targetFingerprint; }
    /**
     * Returns the execution plan fingerprint.
     * @return execution plan fingerprint
     */
    public String executionPlanFingerprint() { return executionPlanFingerprint; }
    /**
     * Returns the function plan fingerprint.
     * @return function plan fingerprint, or empty
     */
    public String functionPlanFingerprint() { return functionPlanFingerprint; }
    /**
     * Returns the payload-free state projection.
     * @return state projection, or null for a function-only run
     */
    public State state() { return state; }
    /**
     * Returns the payload-free function projection.
     * @return function projection, or null for a state-only run
     */
    public Function function() { return function; }
    /**
     * Returns the complete evidence projection fingerprint.
     * @return complete projection fingerprint
     */
    public String projectionFingerprint() { return projectionFingerprint; }

    /**
     * Returns stable, run-independent material for semantic comparisons.
     * @return immutable semantic material without run identity or self fingerprints
     */
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

    private void validate() {
        if (!SCHEMA_VERSION.equals(schemaVersion) || blank(runId) || runId.length() > 256
                || state == null && function == null || scenarioFingerprint == null
                || worldFingerprint == null || !valid(targetFingerprint)
                || !valid(executionPlanFingerprint) || functionPlanFingerprint == null
                || !functionPlanFingerprint.isBlank() && !valid(functionPlanFingerprint)
                || !valid(projectionFingerprint)) throw invalid();
        if (state == null) {
            if (!scenarioFingerprint.isBlank() || !worldFingerprint.isBlank()) throw invalid();
        } else if (!runId.equals(state.runId()) || !scenarioFingerprint.equals(state.scenarioFingerprint())
                || !worldFingerprint.equals(state.worldFingerprint())
                || !targetFingerprint.equals(state.graphArtifactFingerprint())) throw invalid();
        if (function == null && !functionPlanFingerprint.isBlank()) throw invalid();
        if (function != null && !functionPlanFingerprint.equals(function.planFingerprint())) throw invalid();
        if (!projectionFingerprint.equals(computeFingerprint())) throw invalid();
    }

    private String computeFingerprint() {
        return ProtocolCanonical.fingerprint(projectionMaterial());
    }

    private Map<String, Object> projectionMaterial() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("runId", runId);
        material.put("scenarioFingerprint", scenarioFingerprint);
        material.put("worldFingerprint", worldFingerprint);
        material.put("targetFingerprint", targetFingerprint);
        material.put("executionPlanFingerprint", executionPlanFingerprint);
        material.put("functionPlanFingerprint", functionPlanFingerprint);
        material.put("state", state == null ? null : stateMaterial(state));
        material.put("function", function == null ? null : functionProjectionMaterial(function));
        return material;
    }

    private static State parseState(JsonNode value) {
        List<Transaction> transactions = new ArrayList<>();
        JsonNode items = value.path("transactions");
        if (!items.isArray() || items.size() > MAX_ITEMS) throw invalid();
        items.forEach(item -> transactions.add(parseTransaction(item)));
        transactions.sort(Comparator.comparing(item -> item.coordinate().canonicalKey()));
        if (transactions.stream().map(item -> item.coordinate().canonicalKey()).distinct().count()
                != transactions.size()) throw invalid();
        String scenario = fingerprint(value, "scenarioFingerprint");
        String world = fingerprint(value, "worldFingerprint");
        String graph = fingerprint(value, "graphArtifactFingerprint");
        String spec = fingerprint(value, "stateSpecFingerprint");
        long revision = nonNegative(value, "revision");
        String stateEvidence = fingerprint(value, "stateEvidenceFingerprint");
        String expectedStateEvidence = ProtocolCanonical.fingerprint(stateMaterial(
                scenario, world, graph, spec, revision, transactions));
        if (!stateEvidence.equals(expectedStateEvidence)) throw invalid();
        return new State(scenario, world, graph, text(value, "runId"), spec, revision,
                stateEvidence, transactions);
    }

    private static Map<String, Object> stateMaterial(String scenario, String world, String graph,
                                                     String spec, long revision,
                                                     List<Transaction> transactions) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("scenarioFingerprint", scenario);
        material.put("worldFingerprint", world);
        material.put("graphArtifactFingerprint", graph);
        material.put("stateSpecFingerprint", spec);
        material.put("revision", revision);
        material.put("transactions", transactions.stream().map(TestRunControlEvidenceProjection::transactionMaterial).toList());
        return material;
    }

    private static Map<String, Object> stateMaterial(State value) {
        return stateMaterial(value.scenarioFingerprint(), value.worldFingerprint(),
                value.graphArtifactFingerprint(), value.stateSpecFingerprint(), value.revision(),
                value.transactions());
    }

    private static Map<String, Object> transactionMaterial(Transaction value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("coordinate", coordinateMaterial(value.coordinate()));
        material.put("readKeys", value.readKeys());
        material.put("writeKeys", value.writeKeys());
        material.put("readFingerprint", value.readFingerprint());
        material.put("writeFingerprint", value.writeFingerprint());
        material.put("resultFingerprint", value.resultFingerprint());
        return material;
    }

    private static Map<String, Object> coordinateMaterial(Coordinate value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("graphPath", value.graphPath());
        material.put("nodeId", value.nodeId());
        material.put("graphOccurrence", value.graphOccurrence());
        material.put("occurrence", value.occurrence());
        material.put("attempt", value.attempt());
        material.put("structuralInvocationSiteId", value.structuralInvocationSiteId());
        return material;
    }

    private static Transaction parseTransaction(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        List<String> reads = keys(value.path("readKeys"));
        List<String> writes = keys(value.path("writeKeys"));
        return new Transaction(parseCoordinate(value.path("coordinate")), reads, writes,
                fingerprint(value, "readFingerprint"), fingerprint(value, "writeFingerprint"),
                fingerprint(value, "resultFingerprint"));
    }

    private static Function parseFunction(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        List<Binding> bindings = new ArrayList<>();
        value.path("bindings").forEach(item -> bindings.add(parseBinding(item)));
        List<Consumption> consumptions = new ArrayList<>();
        value.path("consumptions").forEach(item -> consumptions.add(parseConsumption(item)));
        List<Observation> observations = new ArrayList<>();
        value.path("observations").forEach(item -> observations.add(parseObservation(item)));
        if (bindings.size() > MAX_ITEMS || consumptions.size() > MAX_ITEMS
                || observations.size() > MAX_ITEMS) throw invalid();
        bindings.sort(Comparator.comparing(Binding::siteKey));
        consumptions.sort(Comparator.comparing(Consumption::ruleId));
        observations.sort(Comparator.comparing(Observation::siteKey)
                .thenComparing(Observation::ruleId).thenComparingLong(Observation::occurrence)
                .thenComparing(Observation::invocationScopeFingerprint)
                .thenComparing(Observation::argumentsFingerprint));
        if (unique(bindings.stream().map(Binding::siteKey).toList())
                || unique(consumptions.stream().map(Consumption::ruleId).toList())
                || unique(observations.stream().map(Observation::key).toList())) throw invalid();
        Map<String, Binding> bindingBySite = new LinkedHashMap<>();
        for (Binding binding : bindings) {
            if (!binding.siteKey().equals(siteKey(binding.graphPath(), binding.nodeId(),
                    binding.functionName(), binding.line(), binding.column()))) throw invalid();
            bindingBySite.put(binding.siteKey(), binding);
        }
        for (Observation observation : observations) {
            if (!bindingBySite.containsKey(observation.siteKey())) throw invalid();
        }
        String planFingerprint = fingerprint(value, "planFingerprint");
        String evidenceFingerprint = fingerprint(value, "evidenceFingerprint");
        String ceiling = text(value, "evidenceCeiling");
        String expectedEvidence = ProtocolCanonical.fingerprint(functionMaterial(planFingerprint,
                ceiling, bindings, consumptions, observations));
        if (!evidenceFingerprint.equals(expectedEvidence)) throw invalid();
        requireEnum(ceiling, "CERTIFIABLE", "EXPLORATORY", "PREVIEW");
        return new Function(planFingerprint, ceiling,
                bindings, consumptions, observations, fingerprint(value, "evidenceFingerprint"));
    }

    private static Binding parseBinding(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        String mode = text(value, "mode");
        String ceiling = text(value, "evidenceCeiling");
        requireEnum(mode, "DIRECT", "CONTROLLED");
        requireEnum(ceiling, "CERTIFIABLE", "EXPLORATORY", "PREVIEW");
        return new Binding(text(value, "siteKey"), text(value, "graphPath"), text(value, "nodeId"),
                text(value, "functionName"), nonNegative(value, "line"), nonNegative(value, "column"),
                fingerprint(value, "functionFingerprint"), fingerprint(value, "runtimeFingerprint"),
                mode, ceiling, optionalText(value, "downgradeReason"));
    }

    private static Consumption parseConsumption(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        long minimum = nonNegative(value, "minimum");
        long maximum = nonNegative(value, "maximum");
        long used = nonNegative(value, "used");
        if (maximum < minimum || used > maximum) throw invalid();
        String status = text(value, "status");
        requireEnum(status, "SATISFIED", "MAX_REACHED", "MINIMUM_UNSATISFIED");
        return new Consumption(text(value, "ruleId"), minimum, maximum, used, status);
    }

    private static Observation parseObservation(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        String behavior = text(value, "behavior");
        requireEnum(behavior, "RETURN", "THROW", "DELAY", "TIMEOUT");
        String result = optionalFingerprint(value, "resultFingerprint");
        String error = optionalFingerprint(value, "errorFingerprint");
        if (!result.isBlank() && !error.isBlank()
                || (behavior.equals("THROW") || behavior.equals("TIMEOUT")) && !result.isBlank()
                || (behavior.equals("RETURN") || behavior.equals("DELAY"))
                && result.isBlank() && error.isBlank()) throw invalid();
        return new Observation(text(value, "siteKey"), text(value, "ruleId"), behavior,
                fingerprint(value, "invocationScopeFingerprint"), fingerprint(value, "argumentsFingerprint"),
                result, error, positive(value, "occurrence"), nonNegative(value, "logicalDurationMillis"));
    }

    private static Coordinate parseCoordinate(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        return new Coordinate(text(value, "graphPath"), text(value, "nodeId"),
                positive(value, "graphOccurrence"), positive(value, "occurrence"),
                positive(value, "attempt"), text(value, "structuralInvocationSiteId"));
    }

    private static Map<String, Object> functionMaterial(Function value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("planFingerprint", value.planFingerprint());
        material.put("evidenceCeiling", value.evidenceCeiling());
        material.put("bindings", value.bindings().stream()
                .map(TestRunControlEvidenceProjection::bindingProjectionMaterial).toList());
        material.put("consumptions", value.consumptions().stream()
                .map(TestRunControlEvidenceProjection::consumptionMaterial).toList());
        material.put("observations", value.observations().stream()
                .map(TestRunControlEvidenceProjection::observationProjectionMaterial).toList());
        material.put("evidenceFingerprint", value.evidenceFingerprint());
        return material;
    }

    private static Map<String, Object> functionProjectionMaterial(Function value) {
        return functionMaterial(value);
    }

    private static Map<String, Object> bindingProjectionMaterial(Binding value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("siteKey", value.siteKey());
        material.put("graphPath", value.graphPath());
        material.put("nodeId", value.nodeId());
        material.put("functionName", value.functionName());
        material.put("line", value.line());
        material.put("column", value.column());
        material.put("functionFingerprint", value.functionFingerprint());
        material.put("runtimeFingerprint", value.runtimeFingerprint());
        material.put("mode", value.mode());
        material.put("evidenceCeiling", value.evidenceCeiling());
        material.put("downgradeReason", value.downgradeReason());
        return material;
    }

    private static Map<String, Object> observationProjectionMaterial(Observation value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("siteKey", value.siteKey());
        material.put("ruleId", value.ruleId());
        material.put("behavior", value.behavior());
        material.put("invocationScopeFingerprint", value.invocationScopeFingerprint());
        material.put("argumentsFingerprint", value.argumentsFingerprint());
        material.put("resultFingerprint", value.resultFingerprint());
        material.put("errorFingerprint", value.errorFingerprint());
        material.put("occurrence", value.occurrence());
        material.put("logicalDurationMillis", value.logicalDurationMillis());
        return material;
    }

    private static Map<String, Object> functionMaterial(String planFingerprint, String ceiling,
                                                        List<Binding> bindings,
                                                        List<Consumption> consumptions,
                                                        List<Observation> observations) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("planFingerprint", planFingerprint);
        material.put("evidenceCeiling", ceiling);
        material.put("bindings", bindings.stream().map(TestRunControlEvidenceProjection::bindingMaterial).toList());
        material.put("consumptions", consumptions.stream().map(TestRunControlEvidenceProjection::consumptionMaterial).toList());
        material.put("observations", observations.stream().map(TestRunControlEvidenceProjection::observationMaterial).toList());
        return material;
    }

    private static Map<String, Object> bindingMaterial(Binding value) {
        Map<String, Object> material = new LinkedHashMap<>();
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("graphPath", value.graphPath());
        site.put("nodeId", value.nodeId());
        site.put("functionName", value.functionName());
        site.put("line", value.line());
        site.put("column", value.column());
        material.put("site", site);
        material.put("functionFingerprint", value.functionFingerprint());
        material.put("runtimeFingerprint", value.runtimeFingerprint());
        material.put("mode", value.mode());
        material.put("evidenceCeiling", value.evidenceCeiling());
        material.put("downgradeReason", value.downgradeReason());
        return material;
    }

    private static Map<String, Object> consumptionMaterial(Consumption value) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("ruleId", value.ruleId());
        material.put("minimum", value.minimum());
        material.put("maximum", value.maximum());
        material.put("used", value.used());
        material.put("status", value.status());
        return material;
    }

    private static String observationMaterial(Observation value) {
        return value.siteKey() + "|" + value.ruleId() + "|" + value.behavior() + "|"
                + value.invocationScopeFingerprint() + "|" + value.argumentsFingerprint() + "|"
                + value.resultFingerprint() + "|" + value.errorFingerprint() + "|"
                + value.occurrence() + "|" + value.logicalDurationMillis();
    }

    private static String siteKey(String graphPath, String nodeId, String functionName,
                                  long line, long column) {
        if (!graphPath.startsWith("/") || graphPath.contains("//") || graphPath.endsWith("/")) throw invalid();
        for (String segment : graphPath.substring(1).split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) throw invalid();
        }
        return "bloge.functionInvocationSite.v1:" + encode(graphPath) + "." + encode(nodeId)
                + "." + encode(functionName) + "." + line + "." + column;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<String> keys(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > MAX_ITEMS) throw invalid();
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (item == null || !item.isTextual()) throw invalid();
            result.add(text(item.textValue()));
        });
        result.sort(String::compareTo);
        if (result.stream().distinct().count() != result.size()) throw invalid();
        return List.copyOf(result);
    }

    private static boolean unique(List<String> values) {
        return values.stream().distinct().count() != values.size();
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return text(value.textValue());
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value.trim();
    }

    private static String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return "";
        if (value.isTextual() && value.textValue().isBlank()) return "";
        return text(value, field);
    }

    private static String fingerprint(JsonNode object, String field) {
        String value = text(object, field);
        if (!valid(value)) throw invalid();
        return value;
    }

    private static String optionalFingerprint(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return "";
        if (value.isTextual() && value.textValue().isBlank()) return "";
        if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
        String result = text(value.textValue());
        if (!valid(result)) throw invalid();
        return result;
    }

    private static long nonNegative(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw invalid();
        return value.asLong();
    }

    private static long positive(JsonNode object, String field) {
        long value = nonNegative(object, field);
        if (value < 1) throw invalid();
        return value;
    }

    private static boolean valid(String value) { return value != null && FINGERPRINT.matcher(value).matches(); }
    private static void requireEnum(String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return;
        throw invalid();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid test-run control evidence projection");
    }

    /** Dynamic state invocation coordinate.
     * @param graphPath containing graph path
     * @param nodeId node identity
     * @param graphOccurrence containing graph occurrence
     * @param occurrence site occurrence
     * @param attempt retry attempt
     * @param structuralInvocationSiteId compiler structural site id
     */
    public record Coordinate(String graphPath, String nodeId, long graphOccurrence, long occurrence,
                             long attempt, String structuralInvocationSiteId) {
        /**
         * Returns the collision-resistant stable coordinate key used for duplicate detection.
         * @return canonical coordinate key
         */
        public String canonicalKey() {
            return "bloge.worldInvocationCoordinate.v1"
                    + "|g=" + encoded(graphPath)
                    + "|n=" + encoded(nodeId)
                    + "|go=" + graphOccurrence
                    + "|o=" + occurrence
                    + "|a=" + attempt
                    + "|s=" + encoded(structuralInvocationSiteId);
        }

        private static String encoded(String value) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** Payload-free state transaction observation.
     * @param coordinate dynamic invocation coordinate
     * @param readKeys declared read keys
     * @param writeKeys declared write keys
     * @param readFingerprint read-set fingerprint
     * @param writeFingerprint write-set fingerprint
     * @param resultFingerprint result fingerprint
     */
    public record Transaction(Coordinate coordinate, List<String> readKeys, List<String> writeKeys,
                              String readFingerprint, String writeFingerprint, String resultFingerprint) {
        /** Freezes key collections. */
        public Transaction {
            readKeys = List.copyOf(readKeys);
            writeKeys = List.copyOf(writeKeys);
        }
    }

    /** Payload-free state evidence projection.
     * @param scenarioFingerprint scenario binding
     * @param worldFingerprint world binding
     * @param graphArtifactFingerprint graph binding
     * @param runId run binding
     * @param stateSpecFingerprint state schema binding
     * @param revision transaction revision
     * @param stateEvidenceFingerprint state evidence fingerprint
     * @param transactions ordered transaction observations
     */
    public record State(String scenarioFingerprint, String worldFingerprint, String graphArtifactFingerprint,
                        String runId, String stateSpecFingerprint, long revision,
                        String stateEvidenceFingerprint, List<Transaction> transactions) {
        /** Freezes transaction observations. */
        public State { transactions = List.copyOf(transactions); }
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
    }

    /** Payload-free function evidence projection.
     * @param planFingerprint compiled function plan binding
     * @param evidenceCeiling maximum evidence class
     * @param bindings static function bindings
     * @param consumptions rule consumption summaries
     * @param observations controlled invocation observations
     * @param evidenceFingerprint function evidence fingerprint
     */
    public record Function(String planFingerprint, String evidenceCeiling,
                           List<Binding> bindings, List<Consumption> consumptions,
                           List<Observation> observations, String evidenceFingerprint) {
        /** Freezes all projection collections. */
        public Function {
            bindings = List.copyOf(bindings);
            consumptions = List.copyOf(consumptions);
            observations = List.copyOf(observations);
        }
        Map<String, Object> stableSemanticMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("planFingerprint", planFingerprint);
            material.put("evidenceCeiling", evidenceCeiling);
            material.put("bindings", bindings);
            material.put("consumptions", consumptions);
            material.put("observations", observations);
            material.put("evidenceFingerprint", evidenceFingerprint);
            return Collections.unmodifiableMap(material);
        }
    }

    /** Payload-free function binding.
     * @param siteKey structural site key
     * @param graphPath graph path
     * @param nodeId node id
     * @param functionName function name
     * @param line source line
     * @param column source column
     * @param functionFingerprint declared function fingerprint
     * @param runtimeFingerprint runtime function fingerprint
     * @param mode direct or controlled mode
     * @param evidenceCeiling binding evidence ceiling
     * @param downgradeReason sanitized downgrade reason
     */
    public record Binding(String siteKey, String graphPath, String nodeId, String functionName,
                          long line, long column, String functionFingerprint, String runtimeFingerprint,
                          String mode, String evidenceCeiling, String downgradeReason) {
    }

    /** Payload-free rule consumption.
     * @param ruleId rule identity
     * @param minimum required minimum uses
     * @param maximum permitted uses
     * @param used observed uses
     * @param status bounded consumption status
     */
    public record Consumption(String ruleId, long minimum, long maximum, long used, String status) {
    }

    /** Payload-free controlled function observation.
     * @param siteKey structural site key
     * @param ruleId matched rule id
     * @param behavior controlled behavior
     * @param invocationScopeFingerprint invocation scope fingerprint
     * @param argumentsFingerprint argument fingerprint
     * @param resultFingerprint result fingerprint, or empty
     * @param errorFingerprint error fingerprint, or empty
     * @param occurrence one-based occurrence
     * @param logicalDurationMillis deterministic logical duration
     */
    public record Observation(String siteKey, String ruleId, String behavior,
                              String invocationScopeFingerprint, String argumentsFingerprint,
                              String resultFingerprint, String errorFingerprint, long occurrence,
                              long logicalDurationMillis) {
        String key() {
            return siteKey + "\u0000" + ruleId + "\u0000" + occurrence + "\u0000"
                    + invocationScopeFingerprint + "\u0000" + argumentsFingerprint;
        }
    }
}
