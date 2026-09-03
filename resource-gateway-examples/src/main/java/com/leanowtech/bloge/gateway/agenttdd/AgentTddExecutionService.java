package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.importer.DslRewriteGateResult;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture.DependencyBehavior;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture.DependencyBehaviorKind;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationResponse;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;

import java.util.ArrayList;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Library-contract-aware compile and red-to-green execution kernel for Agent TDD tools.
 *
 * <p>Every call supplies its library references explicitly. Compilation reuses the existing DSL
 * projection/importer. RED uses the visual mock-runner and deterministic stand-ins; GREEN uses the
 * authoritative visual runtime after every contract binding is executable.</p>
 */
public final class AgentTddExecutionService {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final int MAX_FINGERPRINT_BYTES = 10 * 1024 * 1024;

    private final OperatorLibraryRegistry libraries;
    private final GraphDraftRepository drafts;
    private final DslImportService projection;
    private final VisualGraphSimulationService simulation;
    private final ObjectMapper mapper;
    private final AgentTddStateRepository states;
    private final VisualGraphRunService runtime;

    /** Creates the execution kernel from the same compiler and simulator used by the web surface. */
    public AgentTddExecutionService(OperatorLibraryRegistry libraries,
                                    GraphDraftRepository drafts,
                                    DslImportService projection,
                                    VisualGraphSimulationService simulation,
                                    ObjectMapper mapper) {
        this(libraries, drafts, projection, simulation, mapper, null);
    }

    /** Creates the execution kernel with durable case-set resolution. */
    public AgentTddExecutionService(OperatorLibraryRegistry libraries,
                                    GraphDraftRepository drafts,
                                    DslImportService projection,
                                    VisualGraphSimulationService simulation,
                                    ObjectMapper mapper,
                                    AgentTddStateRepository states) {
        this(libraries, drafts, projection, simulation, mapper, states, null);
    }

    /** Creates the execution kernel with durable cases and the authoritative bound runtime. */
    public AgentTddExecutionService(OperatorLibraryRegistry libraries,
                                    GraphDraftRepository drafts,
                                    DslImportService projection,
                                    VisualGraphSimulationService simulation,
                                    ObjectMapper mapper,
                                    AgentTddStateRepository states,
                                    VisualGraphRunService runtime) {
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.simulation = Objects.requireNonNull(simulation, "simulation");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.states = states;
        this.runtime = runtime;
    }

    /**
     * Parses and projects BLOGE DSL against only the explicitly supplied library contracts.
     *
     * @param arguments MCP arguments containing source and libraryRefs
     * @return projection, source map and server-derived speccing state
     */
    public Map<String, Object> preview(JsonNode arguments) {
        List<String> refs = libraryRefs(arguments);
        DslVisualProjection result = project(arguments, refs);
        return Map.of(
                "projection", result,
                "libraryRefs", refs,
                "speccing", speccing(result.draft()),
                "executable", !speccing(result.draft())
        );
    }

    /**
     * Applies the existing round-trip rewrite gate and adds an honest four-dimensional verdict.
     *
     * @param arguments MCP arguments containing source and libraryRefs
     * @return merge-gate decision and explicit proof limits
     */
    public Map<String, Object> gate(JsonNode arguments) {
        List<String> refs = libraryRefs(arguments);
        DslVisualProjection result = project(arguments, refs);
        DslRewriteGateResult rewrite = DslRewriteGateResult.from(result);
        boolean compileAccepted = result.diagnostics().stream().noneMatch(diagnostic -> diagnostic.error());
        return Map.of(
                "accepted", compileAccepted && rewrite.allowed(),
                "compileAccepted", compileAccepted,
                "rewriteGate", rewrite,
                "libraryRefs", refs,
                "honestVerdict", Map.of("dimensions", List.of(
                        dimension("contract-conformance", compileAccepted ? "PASS" : "FAIL",
                                "DSL resolves against the explicit contract set.",
                                "It does not prove business correctness or runtime behavior."),
                        dimension("business-correctness", "NOT_PROVEN", "No business claim is made.",
                                "Golden Oracle cases have not been executed."),
                        dimension("dependency-isolation", "NOT_PROVEN", "No execution occurred.",
                                "Zero real calls require simulate or baseline evidence."),
                        dimension("runtime-governance", "NOT_PROVEN", "No publish claim is made.",
                                "Runtime environment and owner signoff remain unverified.")))
        );
    }

    /**
     * Runs cases through the isolated RED boundary or authoritative bound GREEN runtime.
     *
     * @param arguments toolRef, explicit libraryRefs, side and case rows
     * @param identity trusted enterprise identity used for draft-scope closure
     * @return case verdicts with execution-path-specific external invocation evidence
     */
    public Map<String, Object> simulate(JsonNode arguments, IntegrationRequestContext identity) {
        String toolRef = requiredText(arguments, "toolRef");
        List<String> refs = libraryRefs(arguments);
        GraphDraft draft = drafts.find(toolRef)
                .filter(candidate -> sameScope(candidate, identity))
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Tool draft was not found in the authorized scope."));
        requireDraftLibraries(draft, refs);
        String side = optionalText(arguments, "side").toUpperCase(Locale.ROOT);
        if (side.isBlank()) {
            side = speccing(draft) ? "RED" : "GREEN";
        }
        if (!Set.of("RED", "GREEN").contains(side)) {
            throw new AgentTddToolException("GATE_REJECTED", "side must be RED or GREEN.");
        }
        List<JsonNode> rows = caseRows(arguments, identity);
        List<String> caseIds = rows.stream().map(row -> requiredText(row, "caseId")).sorted().toList();
        String goldenSetId = goldenSetId(mapper, toolRef, draft, caseIds);
        boolean greenBlocked = "GREEN".equals(side) && speccing(draft);
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int realExternalCalls = 0;
        for (JsonNode row : rows) {
            Map<String, Object> result = greenBlocked
                    ? blockedCase(row)
                    : executeCase(draft, row, side, arguments.path("adhocFixtures"));
            results.add(result);
            String verdict = result.get("verdict").toString();
            if (verdict.endsWith("PASS")) passed++;
            else failed++;
            realExternalCalls += ((Number) result.getOrDefault("realExternalCalls", 0)).intValue();
        }
        return Map.of(
                "goldenSetId", goldenSetId,
                "side", side,
                "byLayer", Map.of("contract", Map.of("pass", passed, "fail", failed)),
                "cases", results,
                "realExternalCalls", realExternalCalls,
                "honestVerdict", Map.of("dimensions", List.of(
                        dimension("business-correctness", failed == 0 && !greenBlocked ? "PASS" : "FAIL",
                                "Literal expected outcomes were compared with simulated graph results.",
                                "RED uses mocked dependencies and does not prove real integrations."),
                        dimension("dependency-isolation", "RED".equals(side) ? "PASS" : "NOT_CLAIMED",
                                "RED".equals(side)
                                        ? "Every operator invocation was replaced by the visual simulation boundary."
                                        : "GREEN executed the bound runtime rather than dependency stand-ins.",
                                "It does not attest a production egress monitor.")))
        );
    }

    /** Runs the same zero-egress kernel for a Feature draft and always evaluates the red side. */
    public Map<String, Object> rehearse(JsonNode arguments, IntegrationRequestContext identity) {
        if (arguments == null || !arguments.isObject()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Feature rehearsal arguments are required.");
        }
        ObjectNode adapted = ((ObjectNode) arguments).deepCopy();
        adapted.put("toolRef", requiredText(arguments, "featureRef"));
        adapted.put("side", "RED");
        return simulate(adapted, identity);
    }

    /**
     * Repeats one durable approved case set and proves deterministic business fingerprints.
     *
     * <p>RED remains on the simulation boundary. GREEN executes resolved runtime bindings and may
     * call external resources; its response reports those classified binding attempts. Inline rows
     * are discarded: governance baselines execute only the referenced durable ACTIVE set.</p>
     */
    public Map<String, Object> baseline(JsonNode arguments, IntegrationRequestContext identity) {
        int rounds = arguments.path("rounds").isInt() ? arguments.path("rounds").asInt() : 3;
        if (rounds < 1 || rounds > 10) {
            throw new AgentTddToolException("GATE_REJECTED", "rounds must be between 1 and 10.");
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        LinkedHashSet<String> fingerprints = new LinkedHashSet<>();
        boolean allPass = true;
        String goldenSetId = "";
        String side = "";
        int realExternalCalls = 0;
        String caseSetRef = requiredText(arguments, "caseSetRef");
        ObjectNode approvedRun = arguments == null || !arguments.isObject()
                ? mapper.createObjectNode()
                : ((ObjectNode) arguments).deepCopy();
        approvedRun.set("cases", mapper.createObjectNode().put("caseSetRef", caseSetRef));
        for (int round = 1; round <= rounds; round++) {
            Map<String, Object> result = simulate(approvedRun, identity);
            goldenSetId = result.get("goldenSetId").toString();
            side = result.get("side").toString();
            String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                    mapper, result.get("cases"), MAX_FINGERPRINT_BYTES);
            fingerprints.add(fingerprint);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) result.get("cases");
            boolean pass = cases.stream().allMatch(row -> row.get("verdict").toString().endsWith("PASS"));
            allPass &= pass;
            int roundExternalCalls = ((Number) result.getOrDefault("realExternalCalls", 0)).intValue();
            realExternalCalls += roundExternalCalls;
            runs.add(Map.of("round", round, "fingerprint", fingerprint, "pass", pass,
                    "realExternalCalls", roundExternalCalls));
        }
        boolean stable = fingerprints.size() == 1;
        return Map.of(
                "status", allPass && stable ? "GO" : "NO_GO",
                "goldenSetId", goldenSetId,
                "caseSetRef", caseSetRef,
                "side", side,
                "rounds", runs,
                "businessFingerprintStable", stable,
                "realExternalCalls", realExternalCalls,
                "remainingLimitations", List.of("RUNTIME_ENV_NOT_ATTESTED", "EGRESS_NOT_OBSERVED",
                        "OWNER_SIGNOFF_ABSENT")
        );
    }

    private DslVisualProjection project(JsonNode arguments, List<String> refs) {
        JsonNode source = arguments.path("source");
        String dsl = source.isTextual() ? source.asText() : optionalText(source, "dsl");
        if (dsl.isBlank()) {
            throw new AgentTddToolException("COMPILE_ERROR", "source.dsl is required.");
        }
        String sourceId = source.isObject() ? optionalText(source, "sourceId") : "inline.bloge";
        return projection.preview(new DslImportPreviewRequest(
                sourceId, dsl, refs, List.of(), "agent-tdd", Map.of()));
    }

    private List<String> libraryRefs(JsonNode arguments) {
        JsonNode node = arguments.path("libraryRefs");
        if (!node.isArray()) {
            throw new AgentTddToolException("COMPILE_ERROR", "libraryRefs must be an explicit array.");
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        node.forEach(value -> {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new AgentTddToolException("COMPILE_ERROR", "libraryRefs entries must be non-blank strings.");
            }
            refs.add(value.asText().trim());
        });
        for (String ref : refs) {
            if (libraries.find(ref).isEmpty()) {
                throw new AgentTddToolException("LIBRARY_NOT_FOUND", "Referenced library contract was not found.");
            }
        }
        return List.copyOf(refs);
    }

    private void requireDraftLibraries(GraphDraft draft, List<String> refs) {
        Set<String> referenced = Set.copyOf(refs);
        for (OperatorDefinition operator : draft.operatorSnapshots().values()) {
            if (operator == null || operator.source().libraryId().isBlank()) continue;
            if (!referenced.contains(operator.source().libraryId())) {
                throw new AgentTddToolException(
                        "LIBRARY_NOT_FOUND", "Draft uses a library that was not explicitly referenced.");
            }
        }
    }

    private List<JsonNode> caseRows(JsonNode arguments, IntegrationRequestContext identity) {
        JsonNode cases = arguments.path("cases");
        JsonNode rows = cases.path("rows");
        if ((!rows.isArray() || rows.isEmpty()) && states != null) {
            String caseSetRef = optionalText(arguments, "caseSetRef");
            if (caseSetRef.isBlank()) {
                caseSetRef = optionalText(cases, "caseSetRef");
            }
            if (!caseSetRef.isBlank()) {
                AgentTddStoredAsset caseSet = states.find(
                                AgentTddMutationService.scopeKey(identity), AgentTddMutationService.CASE_SET, caseSetRef)
                        .orElseThrow(() -> new AgentTddToolException(
                                "DRAFT_NOT_FOUND", "Case set was not found in the authorized scope."));
                List<JsonNode> active = new ArrayList<>();
                caseSet.data().path("rows").forEach(row -> {
                    if ("ACTIVE".equals(row.path("lifecycle").asText())) active.add(row);
                });
                if (active.isEmpty() && caseSet.data().path("rows").isArray()
                        && !caseSet.data().path("rows").isEmpty()) {
                    throw new AgentTddToolException(
                            "GOLDEN_REQUIRES_APPROVAL", "The case set has no approved ACTIVE rows.");
                }
                active.forEach(AgentTddExecutionService::requireOracle);
                return List.copyOf(active);
            }
        }
        if (!rows.isArray() || rows.isEmpty()) {
            throw new AgentTddToolException("DRAFT_NOT_FOUND", "At least one case row is required.");
        }
        List<JsonNode> values = new ArrayList<>();
        rows.forEach(row -> {
            if (!row.isObject()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Every case row must be an object.");
            }
            requireOracle(row);
            values.add(row);
        });
        return List.copyOf(values);
    }

    private static void requireOracle(JsonNode row) {
        if (!row.has("expect") || row.path("expect").isMissingNode() || row.path("expect").isNull()) {
            throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT", "Every executable case row must contain an explicit expect Oracle.");
        }
    }

    private Map<String, Object> executeCase(GraphDraft draft,
                                            JsonNode row,
                                            String side,
                                            JsonNode adhocFixtures) {
        Map<String, Object> given = objectMap(row.path("given"));
        if ("GREEN".equals(side)) {
            if (adhocFixtures.isArray() && !adhocFixtures.isEmpty()) {
                throw new AgentTddToolException(
                        "GATE_REJECTED", "GREEN execution does not accept ad-hoc fixture overrides.");
            }
            return executeGreenCase(draft, row);
        }
        Map<String, NodeFixture> fixtures = fixtures(row.path("stubs"));
        if (adhocFixtures.isArray()) {
            adhocFixtures.forEach(value -> fixtures.put(
                    requiredText(value, "nodeId"), new NodeFixture(mapper.convertValue(value.get("value"), Object.class))));
        }
        VisualGraphSimulationResponse response = simulation.simulate(draft, given, "", fixtures);
        boolean oracleHeld = response.success() && response.terminalOutputConforms()
                && expectedMatches(row.path("expect"), mapper.valueToTree(response.output()));
        String verdict = side + "_" + (oracleHeld ? "PASS" : "FAIL");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", requiredText(row, "caseId"));
        result.put("layer", normalizedLayer(row));
        result.put("verdict", verdict);
        result.put("oracle", Map.of("invariant", "expected outcome matches", "held", oracleHeld));
        result.put("schemaConformant", response.terminalOutputConforms());
        result.put("mockedNodeIds", response.mockedNodeIds());
        result.put("realNodeIds", response.realNodeIds());
        result.put("diagnostics", response.diagnostics());
        result.put("realExternalCalls", 0);
        if (!response.success()) {
            result.put("reasonCode", "SIMULATION_FAILED");
        }
        return result;
    }

    private Map<String, Object> executeGreenCase(GraphDraft draft, JsonNode row) {
        if (runtime == null) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The authoritative bound runtime is unavailable for GREEN execution.");
        }
        Map<String, Object> given = objectMap(row.path("given"));
        VisualGraphRunResponse response = runtime.run(draft, given, "");
        boolean oracleHeld = response.success()
                && expectedMatches(row.path("expect"), mapper.valueToTree(response.output()));
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", requiredText(row, "caseId"));
        result.put("layer", normalizedLayer(row));
        result.put("verdict", oracleHeld ? "GREEN_PASS" : "GREEN_FAIL");
        result.put("oracle", Map.of("invariant", "expected outcome matches", "held", oracleHeld));
        result.put("schemaConformant", response.validated() && response.compiled());
        result.put("mockedNodeIds", List.of());
        result.put("realNodeIds", response.statusMap().keySet().stream().sorted().toList());
        result.put("ignoredFixtureNodeIds", row.path("stubs").isObject()
                ? iterableFieldNames(row.path("stubs")) : List.of());
        result.put("diagnostics", response.diagnostics());
        result.put("realExternalCalls", realExternalCalls(draft, response));
        if (!response.success()) result.put("reasonCode", "BOUND_RUNTIME_FAILED");
        return result;
    }

    private static int realExternalCalls(GraphDraft draft, VisualGraphRunResponse response) {
        Set<String> externallyBound = draft.nodes().stream().filter(node -> {
            OperatorDefinition operator = draft.operatorSnapshots().get(node.id());
            if (operator == null) return false;
            return operator.display().tags().stream().anyMatch(Set.of(
                    "resource-read", "external-write", "remote-worker", "ai-tool",
                    "event-source", "message-handler", "webhook")::contains);
        }).map(GraphDraft.DraftNode::id).collect(java.util.stream.Collectors.toSet());
        int count = 0;
        for (String nodeId : externallyBound) {
            List<?> attempts = response.nodeAttempts().getOrDefault(nodeId, List.of());
            if (!attempts.isEmpty()) count += attempts.size();
            else if (response.statusMap().containsKey(nodeId)) count++;
        }
        return count;
    }

    private static List<String> iterableFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    private static Map<String, Object> blockedCase(JsonNode row) {
        return Map.of(
                "caseId", requiredText(row, "caseId"),
                "layer", normalizedLayer(row),
                "verdict", "GREEN_BLOCKED",
                "reasonCode", "SPECCING_NOT_EXECUTABLE",
                "oracle", Map.of("invariant", "not executed", "held", false),
                "schemaConformant", false,
                "mockedNodeIds", List.of(),
                "realNodeIds", List.of(),
                "diagnostics", List.of(),
                "realExternalCalls", 0
        );
    }

    private Map<String, NodeFixture> fixtures(JsonNode stubs) {
        LinkedHashMap<String, NodeFixture> values = new LinkedHashMap<>();
        if (stubs.isMissingNode() || stubs.isNull()) return values;
        if (!stubs.isObject()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "stubs must be an object keyed by nodeId.");
        }
        stubs.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isObject() && value.has("behavior")) {
                values.put(entry.getKey(), dependencyFixture(mapper, value));
                return;
            }
            values.put(entry.getKey(), new NodeFixture(mapper.convertValue(value, Object.class)));
        });
        return values;
    }

    /**
     * Validates and compiles one Agent TDD dependency behavior into a simulation fixture.
     *
     * <p>The compiler is intentionally fail-closed: time controls require a positive millisecond
     * duration, replay requires both an exact governed reference and a run-frozen value, and
     * OBSERVE receives only a local deterministic delegate. No behavior can fall through to a live
     * operator.</p>
     *
     * @param mapper protocol mapper
     * @param value behavior directive
     * @return kernel-ready node fixture
     */
    static NodeFixture dependencyFixture(ObjectMapper mapper, JsonNode value) {
        String rawKind = requiredText(value, "behavior").toUpperCase(Locale.ROOT);
        DependencyBehaviorKind kind;
        try {
            kind = DependencyBehaviorKind.valueOf(rawKind);
        } catch (IllegalArgumentException failure) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Unsupported dependency behavior.");
        }
        Object output = mapper.convertValue(value.get("value"), Object.class);
        Duration after = null;
        if (kind == DependencyBehaviorKind.DELAY || kind == DependencyBehaviorKind.TIMEOUT) {
            long millis = value.path("afterMillis").canConvertToLong()
                    ? value.path("afterMillis").asLong() : 0;
            if (millis < 1 || millis > 60_000) {
                throw new AgentTddToolException(
                        "SCHEMA_NONCONFORMANT", "DELAY and TIMEOUT require afterMillis in 1..60000.");
            }
            after = Duration.ofMillis(millis);
        }
        String replayRef = optionalText(value, "replayRef");
        if (kind == DependencyBehaviorKind.REPLAY) {
            if (!value.has("value")) {
                throw new AgentTddToolException(
                        "SCHEMA_NONCONFORMANT", "REPLAY requires a run-frozen value.");
            }
            try {
                com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef.parse(replayRef);
            } catch (IllegalArgumentException failure) {
                throw new AgentTddToolException(
                        "SCHEMA_NONCONFORMANT", "REPLAY requires an exact governed replayRef.");
            }
        }
        if (kind == DependencyBehaviorKind.OBSERVE && !value.has("value")) {
            throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT", "OBSERVE requires a deterministic local delegate value.");
        }
        String errorCode = optionalText(value, "errorCode");
        String errorType = optionalText(value, "errorType");
        String errorMessage = optionalText(value, "errorMessage");
        DependencyBehavior behavior = new DependencyBehavior(
                kind, output, errorCode, errorType, errorMessage, after, replayRef);
        Object expectedInput = mapper.convertValue(value.get("expectedInput"), Object.class);
        return new NodeFixture(output, expectedInput, null,
                NodeFixture.ResourceFidelity.OUTPUT_LEVEL, behavior);
    }

    private boolean expectedMatches(JsonNode expected, JsonNode actual) {
        if (expected == null || expected.isMissingNode() || expected.isNull()) return true;
        if (expected.isObject()) {
            if (actual == null || !actual.isObject()) return false;
            var fields = expected.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!actual.has(field.getKey()) || !expectedMatches(field.getValue(), actual.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            if (actual == null || !actual.isArray() || expected.size() != actual.size()) return false;
            for (int index = 0; index < expected.size(); index++) {
                if (!expectedMatches(expected.get(index), actual.get(index))) return false;
            }
            return true;
        }
        return expected.equals(actual);
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "given must be an object.");
        }
        return mapper.convertValue(node, OBJECT_MAP);
    }

    static String goldenSetId(ObjectMapper mapper, String toolRef, GraphDraft draft, List<String> caseIds) {
        String contractFingerprint = contractFingerprint(mapper, draft);
        return VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "toolRef", toolRef, "contractFingerprint", contractFingerprint, "caseIds", caseIds),
                MAX_FINGERPRINT_BYTES);
    }

    static String contractFingerprint(ObjectMapper mapper, GraphDraft draft) {
        List<Map<String, Object>> contracts = draft.operatorSnapshots().values().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .map(operator -> Map.<String, Object>of(
                        "operatorRef", operator.operatorRef(),
                        "archetype", contractArchetype(operator),
                        "inputs", operator.ports().inputs(),
                        "outputs", operator.ports().outputs()))
                .toList();
        return VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "input", draft.inputSchema(), "output", draft.outputSchema(), "operators", contracts),
                MAX_FINGERPRINT_BYTES);
    }

    private static String contractArchetype(OperatorDefinition operator) {
        for (String tag : operator.display().tags()) {
            if (Set.of("pure", "decision", "resource-read", "external-write", "remote-worker",
                    "ai-tool", "event-source", "message-handler", "webhook").contains(tag)) return tag;
        }
        return operator.source().kind();
    }

    private static boolean speccing(GraphDraft draft) {
        return draft.operatorSnapshots().values().stream()
                .filter(Objects::nonNull)
                .anyMatch(AgentTddExecutionService::requiresBinding);
    }

    private static boolean requiresBinding(OperatorDefinition operator) {
        if (operator.source().libraryId().isBlank()) {
            return "design".equals(operator.lowering().mode()) || !operator.runtimeReadiness().executable();
        }
        Object binding = operator.lowering().parameters().get("bindingRef");
        return !(binding instanceof String value) || value.isBlank();
    }

    private static boolean sameScope(GraphDraft draft, IntegrationRequestContext identity) {
        return draft.tenantId().equals(identity.tenantId())
                && draft.environment().equals(identity.environmentId());
    }

    private static String normalizedLayer(JsonNode row) {
        String layer = optionalText(row, "layer").toLowerCase(Locale.ROOT);
        return Set.of("unit", "contract", "integration", "smoke").contains(layer) ? layer : "contract";
    }

    private static Map<String, Object> dimension(String name,
                                                 String status,
                                                 String proves,
                                                 String doesNotProve) {
        return Map.of("name", name, "status", status, "proves", proves, "doesNotProve", doesNotProve);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() ? node.path(field).asText().trim() : "";
    }
}
