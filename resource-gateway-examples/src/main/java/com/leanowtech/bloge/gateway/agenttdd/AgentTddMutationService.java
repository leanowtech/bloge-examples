package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.parse.AuthoringDocumentDecoder;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.importer.DslRewriteGateResult;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Idempotent mutation boundary for Agent TDD authoring tools.
 *
 * <p>Libraries and graphs are committed to their existing canonical registries. Only Agent-facing
 * instructions, golden case tables and pending human proposals are placed in the Agent overlay
 * repository. Every successful result is persisted for exact idempotency replay.</p>
 */
public final class AgentTddMutationService {
    static final String CASE_SET = "CASE_SET";
    static final String TOOL_INSTRUCTION = "TOOL_INSTRUCTION";
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Set<String> CATEGORIES = Set.of(
            "GOLDEN", "REGRESSION", "NEGATIVE", "BOUNDARY", "FAULT", "SECURITY");
    private static final Set<String> LIFECYCLES = Set.of("DRAFT", "ACTIVE", "STALE", "RETIRED");
    private static final Set<String> QUALITY_STATES = Set.of(
            "DESIGNED_NOT_RUN", "READY", "STALE", "BLOCKED");
    private static final Set<String> BEHAVIORS = Set.of(
            "RETURN", "ERROR", "DELAY", "TIMEOUT", "REPLAY", "OBSERVE", "MUST_NOT_CALL");

    private final OperatorLibraryRegistry libraries;
    private final GraphDraftRepository drafts;
    private final AgentTddStateRepository states;
    private final AuthoringPreviewService authoring;
    private final DslImportService projection;
    private final ObjectMapper mapper;
    private final AuthoringDocumentDecoder decoder = new AuthoringDocumentDecoder();
    private final AgentTddDecisionScenarioEnumerator enumerator;

    /** Creates the mutation boundary over canonical registries and the Agent overlay repository. */
    public AgentTddMutationService(OperatorLibraryRegistry libraries,
                                   GraphDraftRepository drafts,
                                   AgentTddStateRepository states,
                                   AuthoringPreviewService authoring,
                                   DslImportService projection,
                                   ObjectMapper mapper) {
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.states = Objects.requireNonNull(states, "states");
        this.authoring = Objects.requireNonNull(authoring, "authoring");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.enumerator = new AgentTddDecisionScenarioEnumerator(mapper);
    }

    /** Strictly decodes, compiles and stores one canonical visual operator library. */
    public Map<String, Object> upsertLibrary(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.library.upsert", arguments, identity, () -> {
            var decoded = decoder.decode(requiredText(arguments, "libraryYaml").getBytes(StandardCharsets.UTF_8));
            if (!decoded.successful()) {
                throw new AgentTddToolException("COMPILE_ERROR", decoded.failure().message());
            }
            AuthoringCompileResult compiled = authoring.preview(decoded.document());
            if (!compiled.importable()) {
                String codes = compiled.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code()).distinct().sorted()
                        .reduce((left, right) -> left + "," + right).orElse("UNKNOWN");
                throw new AgentTddToolException("COMPILE_ERROR",
                        "Library authoring source did not pass the authoritative compile gate: " + codes + ".");
            }
            OperatorLibrary library = libraries.upsert(compiled.canonicalLibrary(),
                    OperatorLibraryRevision.RevisionMetadata.of(identity.actorId(), "MCP_AGENT_TDD",
                            "Agent TDD library upsert", "Explicit idempotent authoring request"));
            List<Map<String, Object>> operators = library.operators().stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                    .map(operator -> Map.<String, Object>of(
                            "id", operator.operatorRef(),
                            "archetype", operator.source().kind(),
                            "speccing", designOnly(operator),
                            "bindingRef", bindingRef(operator)))
                    .toList();
            return Map.of(
                    "libraryId", library.libraryId(),
                    "version", library.version(),
                    "operators", operators,
                    "functions", library.builtInFunctions(),
                    "types", decoded.document().types().keySet().stream().sorted().toList(),
                    "canonicalFingerprint", compiled.canonicalFingerprint());
        });
    }

    /** Compiles and commits one Feature or Tool graph under an exact server-derived scope. */
    public Map<String, Object> compose(JsonNode arguments,
                                       String referenceField,
                                       String assetKind,
                                       IntegrationRequestContext identity) {
        String operation = "FEATURE".equals(assetKind) ? "rg.feature.compose" : "rg.tool.compose";
        return idempotent(operation, arguments, identity, () -> {
            String assetRef = requiredText(arguments, referenceField);
            List<String> refs = libraryRefs(arguments);
            JsonNode graph = arguments.path("graph");
            if (!graph.isObject()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "graph must be an object.");
            }
            GraphDraft candidate = graph.hasNonNull("dsl")
                    ? compileGraph(graph, refs)
                    : convertGraph(graph);
            candidate = resolveRuntimeBindings(candidate);
            requireReferencedLibraries(candidate, refs);
            GraphDraft current = drafts.find(assetRef).orElse(null);
            if (current != null && (!current.tenantId().equals(identity.tenantId())
                    || !current.environment().equals(identity.environmentId()))) {
                throw new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Graph draft was not found in the authorized scope.");
            }
            long expectedRevision = current == null ? 0 : current.revision();
            GraphDraft scoped = scoped(candidate, assetRef, expectedRevision, assetKind, refs, identity);
            GraphDraft stored = current == null
                    ? drafts.save(scoped)
                    : drafts.saveIfRevision(assetRef, expectedRevision, scoped)
                            .orElseThrow(() -> new AgentTddToolException(
                                    "GATE_REJECTED", "Graph draft changed during the compose operation."));
            if (current != null && !AgentTddExecutionService.contractFingerprint(mapper, current)
                    .equals(AgentTddExecutionService.contractFingerprint(mapper, stored))) {
                markCaseSetsStale(assetRef, identity);
            }
            boolean speccing = stored.operatorSnapshots().values().stream()
                    .filter(Objects::nonNull).anyMatch(AgentTddMutationService::designOnly);
            return Map.of("assetRef", stored.draftId(), "assetKind", assetKind,
                    "revision", stored.revision(), "libraryRefs", refs,
                    "speccing", speccing, "executable", !speccing);
        });
    }

    /** Stores the non-example portion of a ToolAgentContract. */
    public Map<String, Object> setInstruction(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.tool.setInstruction", arguments, identity, () -> {
            String toolRef = requiredText(arguments, "toolRef");
            requireScopedDraft(toolRef, identity);
            JsonNode instruction = arguments.path("instruction");
            if (!instruction.isObject()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "instruction must be an object.");
            }
            for (String field : List.of("name", "title", "description", "whenToUse")) {
                requiredText(instruction, field);
            }
            if (!instruction.path("inputs").isArray() || !instruction.path("outputs").isObject()
                    || !instruction.path("errors").isArray()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                        "Tool instruction requires inputs, outputs and errors contracts.");
            }
            if (instruction.has("examples")) {
                throw new AgentTddToolException("GATE_REJECTED",
                        "Tool examples are derived from approved ACTIVE GOLDEN cases and cannot be authored separately.");
            }
            ObjectNode stored = ((ObjectNode) instruction).deepCopy();
            stored.put("toolRef", toolRef);
            AgentTddStoredAsset asset = states.save(scopeKey(identity), TOOL_INSTRUCTION, toolRef, stored);
            return Map.of("toolRef", toolRef, "revision", asset.revision(),
                    "instructionFingerprint", asset.fingerprint(), "examplesDerivedFromGolden", true);
        });
    }

    /** Upserts scenario rows while forcing GOLDEN Oracle material through a pending proposal. */
    public Map<String, Object> upsertCases(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.scenario.upsertCases", arguments, identity, () -> {
            String caseSetRef = requiredText(arguments, "caseSetRef");
            String toolRef = optionalText(arguments, "toolRef");
            if (!toolRef.isBlank()) requireScopedDraft(toolRef, identity);
            JsonNode rowsNode = arguments.path("rows");
            if (!rowsNode.isArray()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "rows must be an array.");
            }
            ObjectNode data = currentObject(identity, CASE_SET, caseSetRef);
            LinkedHashMap<String, ObjectNode> rows = indexedRows(data.path("rows"));
            List<Map<String, Object>> proposed = new ArrayList<>();
            rowsNode.forEach(value -> {
                ObjectNode row = normalizedRow(value);
                prepareForStorage(row, proposed);
                rows.put(row.path("caseId").asText(), row);
            });
            int enumeratedCount = 0;
            if (arguments.path("enumerateFrom").isObject()) {
                if (toolRef.isBlank()) {
                    throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                            "toolRef is required when enumerateFrom is supplied.");
                }
                List<ObjectNode> generated = enumerator.enumerate(
                        requireScopedDraft(toolRef, identity), arguments.path("enumerateFrom"));
                generated.forEach(row -> {
                    prepareForStorage(row, proposed);
                    rows.put(row.path("caseId").asText(), row);
                });
                enumeratedCount = generated.size();
            }
            data.put("caseSetRef", caseSetRef);
            if (!toolRef.isBlank()) data.put("toolRef", toolRef);
            ArrayNode storedRows = data.putArray("rows");
            rows.values().forEach(storedRows::add);
            AgentTddStoredAsset stored = states.save(scopeKey(identity), CASE_SET, caseSetRef, data);
            return Map.of("caseSetRef", caseSetRef, "revision", stored.revision(),
                    "rows", stored.data().path("rows"), "proposed", proposed,
                    "enumeratedCount", enumeratedCount);
        });
    }

    /** Converts every authored or generated GOLDEN Oracle into a human-owned pending proposal. */
    private void prepareForStorage(ObjectNode row, List<Map<String, Object>> proposed) {
        if (!"GOLDEN".equals(row.path("category").asText())) return;
        String caseId = row.path("caseId").asText();
        JsonNode expected = row.remove("expect");
        if (expected != null && !expected.isNull()) {
            ObjectNode proposal = mapper.createObjectNode();
            proposal.set("expect", expected);
            proposal.put("oracleOwner", requiredText(row, "oracleOwner"));
            proposal.put("status", "PENDING");
            row.set("proposedOracle", proposal);
            proposed.add(Map.of("caseId", caseId, "awaiting", "human-approval"));
        }
        row.put("lifecycle", "DRAFT");
        if (!"BLOCKED".equals(row.path("qualityState").asText())) {
            row.put("qualityState", "DESIGNED_NOT_RUN");
        }
    }

    /** Records a business-owned Oracle proposal without making it effective. */
    public Map<String, Object> proposeOracle(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.oracle.propose", arguments, identity, () -> {
            String caseSetRef = requiredText(arguments, "caseSetRef");
            String caseId = requiredText(arguments, "caseId");
            String owner = requiredText(arguments, "oracleOwner");
            JsonNode expected = arguments.path("expect");
            if (!expected.isObject()) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "expect must be an object.");
            }
            ObjectNode data = requiredAssetObject(identity, CASE_SET, caseSetRef);
            ObjectNode row = indexedRows(data.path("rows")).get(caseId);
            if (row == null || !"GOLDEN".equals(row.path("category").asText())) {
                throw new AgentTddToolException("DRAFT_NOT_FOUND", "GOLDEN case was not found.");
            }
            ObjectNode proposal = mapper.createObjectNode();
            proposal.set("expect", expected.deepCopy());
            proposal.put("oracleOwner", owner);
            proposal.put("status", "PENDING");
            row.set("proposedOracle", proposal);
            row.put("lifecycle", "DRAFT");
            replaceRow(data, row);
            AgentTddStoredAsset stored = states.save(scopeKey(identity), CASE_SET, caseSetRef, data);
            return Map.of("caseSetRef", caseSetRef, "caseId", caseId,
                    "proposalStatus", "PENDING", "revision", stored.revision(),
                    "awaiting", "human-approval");
        });
    }

    /** Updates one dependency behavior with a bounded, enumerated behavior kind. */
    public Map<String, Object> setDependencyBehavior(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.scenario.setDependencyBehavior", arguments, identity, () -> {
            String caseSetRef = requiredText(arguments, "caseSetRef");
            String caseId = requiredText(arguments, "caseId");
            String nodeId = requiredText(arguments, "nodeId");
            JsonNode behavior = arguments.path("behavior");
            String behaviorKind = requiredText(behavior, "behavior").toUpperCase(Locale.ROOT);
            if (!BEHAVIORS.contains(behaviorKind)) {
                throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Unsupported dependency behavior.");
            }
            AgentTddExecutionService.dependencyFixture(mapper, behavior);
            ObjectNode data = requiredAssetObject(identity, CASE_SET, caseSetRef);
            ObjectNode row = indexedRows(data.path("rows")).get(caseId);
            if (row == null) {
                throw new AgentTddToolException("DRAFT_NOT_FOUND", "Scenario case was not found.");
            }
            ObjectNode stubs = row.withObject("stubs");
            ObjectNode normalized = ((ObjectNode) behavior).deepCopy();
            normalized.put("behavior", behaviorKind);
            stubs.set(nodeId, normalized);
            replaceRow(data, row);
            AgentTddStoredAsset stored = states.save(scopeKey(identity), CASE_SET, caseSetRef, data);
            return Map.of("caseSetRef", caseSetRef, "caseId", caseId, "nodeId", nodeId,
                    "behavior", normalized, "revision", stored.revision());
        });
    }

    /** Reads one case set, optionally filtering rows by lifecycle. */
    public Map<String, Object> listCases(JsonNode arguments, IntegrationRequestContext identity) {
        String ref = requiredText(arguments, "caseSetRef");
        ObjectNode data = requiredAssetObject(identity, CASE_SET, ref);
        String lifecycle = optionalText(arguments, "lifecycle").toUpperCase(Locale.ROOT);
        List<JsonNode> rows = new ArrayList<>();
        data.path("rows").forEach(row -> {
            if (lifecycle.isBlank() || lifecycle.equals(row.path("lifecycle").asText())) {
                rows.add(row.deepCopy());
            }
        });
        return Map.of("caseSetRef", ref, "toolRef", optionalText(data, "toolRef"),
                "rows", rows, "revision", states.find(scopeKey(identity), CASE_SET, ref).orElseThrow().revision());
    }

    /** Reads ToolAgentContract semantics and projects examples from approved ACTIVE GOLDEN rows. */
    public Map<String, Object> getInstruction(JsonNode arguments, IntegrationRequestContext identity) {
        String toolRef = requiredText(arguments, "toolRef");
        AgentTddStoredAsset instruction = states.find(scopeKey(identity), TOOL_INSTRUCTION, toolRef)
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "No Agent instruction is stored for the requested tool."));
        ObjectNode result = (ObjectNode) instruction.data().deepCopy();
        ArrayNode examples = result.putArray("examples");
        states.list(scopeKey(identity), CASE_SET).stream()
                .map(AgentTddStoredAsset::data)
                .filter(data -> toolRef.equals(optionalText(data, "toolRef")))
                .flatMap(data -> {
                    List<JsonNode> rows = new ArrayList<>();
                    data.path("rows").forEach(rows::add);
                    return rows.stream();
                })
                .filter(row -> "GOLDEN".equals(row.path("category").asText())
                        && "ACTIVE".equals(row.path("lifecycle").asText())
                        && row.has("expect"))
                .sorted(Comparator.comparing(row -> row.path("caseId").asText()))
                .forEach(row -> examples.add(mapper.valueToTree(Map.of(
                        "fromGoldenCaseId", row.path("caseId").asText(),
                        "input", row.path("given"), "output", row.path("expect")))));
        return mapper.convertValue(result, OBJECT_MAP);
    }

    private Map<String, Object> idempotent(String operation,
                                           JsonNode arguments,
                                           IntegrationRequestContext identity,
                                           Supplier<Map<String, Object>> action) {
        String key = requiredText(arguments, "idempotencyKey");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, arguments, MAX_BYTES);
        JsonNode result = states.executeOnce(scopeKey(identity), operation, key, fingerprint,
                () -> mapper.valueToTree(action.get()));
        return mapper.convertValue(result, OBJECT_MAP);
    }

    private GraphDraft compileGraph(JsonNode graph, List<String> refs) {
        String dsl = requiredText(graph, "dsl");
        DslVisualProjection projected = projection.preview(new DslImportPreviewRequest(
                optionalText(graph, "sourceId"), dsl, refs, List.of(), "agent-tdd-compose", Map.of()));
        boolean projectionStillDesign = projected.draft().operatorSnapshots().values().stream()
                .filter(Objects::nonNull).anyMatch(operator -> "design".equals(operator.lowering().mode()));
        if (projected.diagnostics().stream().anyMatch(diagnostic -> diagnostic.error())
                || !projectionStillDesign && !DslRewriteGateResult.from(projected).allowed()) {
            throw new AgentTddToolException("GATE_REJECTED",
                    "Graph source did not pass compile and round-trip rewrite gates.");
        }
        return projected.draft();
    }

    private GraphDraft convertGraph(JsonNode graph) {
        try {
            return mapper.treeToValue(graph, GraphDraft.class);
        } catch (Exception failure) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "graph is not a valid GraphDraft projection.");
        }
    }

    private GraphDraft resolveRuntimeBindings(GraphDraft draft) {
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>(draft.operatorSnapshots());
        Map<String, String> fingerprints = new LinkedHashMap<>(draft.operatorFingerprints());
        snapshots.replaceAll((nodeId, operator) -> {
            if (operator == null) return null;
            Object configured = bindingRef(operator);
            if (!(configured instanceof String binding) || binding.isBlank()) return operator;
            OperatorDefinition target = projection.resolveOperator(binding)
                    .or(() -> projection.resolveOperator("resource:" + binding))
                    .orElseThrow(() -> new AgentTddToolException(
                            "LIBRARY_NOT_FOUND", "runtime.bindingRef does not resolve in the server catalog."));
            requireCompatibleBinding(operator, target);
            OperatorDefinition.Source source = new OperatorDefinition.Source(
                    target.source().kind(), target.source().resourceId(), target.source().method(),
                    target.source().urlTemplate(), target.source().virtual(), operator.source().libraryId());
            Map<String, Object> loweringParameters = new LinkedHashMap<>(target.lowering().parameters());
            loweringParameters.put("bindingRef", binding);
            OperatorDefinition.Lowering lowering = new OperatorDefinition.Lowering(
                    target.lowering().mode(), target.lowering().operatorRef(), loweringParameters);
            OperatorDefinition resolved = new OperatorDefinition(
                    operator.schemaVersion(), operator.operatorRef(), operator.operatorVersion(), "",
                    operator.display(), source, operator.ports(), operator.configSchema(), target.capabilities(),
                    target.policy(), lowering, target.diagnostics());
            fingerprints.put(nodeId, resolved.fingerprint());
            return resolved;
        });
        return draft.withOperatorSnapshotState(fingerprints, snapshots);
    }

    /** Rejects a runtime implementation that weakens the authored semantic contract. */
    static void requireCompatibleBinding(OperatorDefinition contract, OperatorDefinition target) {
        if (!bindingArchetype(contract).equals(bindingArchetype(target))) {
            throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT", "runtime.bindingRef archetype differs from its contract.");
        }
        if (!contract.capabilities().effect().equals(target.capabilities().effect())
                || contract.capabilities().requiresSecrets() != target.capabilities().requiresSecrets()) {
            throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT", "runtime.bindingRef effect or secret requirement differs from its contract.");
        }
        if (contract.ports().inputs().size() != target.ports().inputs().size()
                || contract.ports().outputs().size() != target.ports().outputs().size()) {
            throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT", "runtime.bindingRef port cardinality differs from its contract.");
        }
        for (int index = 0; index < contract.ports().inputs().size(); index++) {
            OperatorDefinition.Port expected = contract.ports().inputs().get(index);
            OperatorDefinition.Port actual = target.ports().inputs().get(index);
            if (!expected.name().equals(actual.name()) || expected.required() != actual.required()
                    || !expected.schema().equals(actual.schema())) {
                throw new AgentTddToolException(
                        "SCHEMA_NONCONFORMANT", "runtime.bindingRef input port differs from its contract.");
            }
        }
        for (int index = 0; index < contract.ports().outputs().size(); index++) {
            OperatorDefinition.Port expected = contract.ports().outputs().get(index);
            OperatorDefinition.Port actual = target.ports().outputs().get(index);
            if (!expected.name().equals(actual.name()) || expected.required() != actual.required()
                    || !expected.schema().equals(actual.schema())) {
                throw new AgentTddToolException(
                        "SCHEMA_NONCONFORMANT", "runtime.bindingRef output port differs from its contract.");
            }
        }
    }

    private static String bindingArchetype(OperatorDefinition operator) {
        return operator.display().tags().stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(Set.of("resource-read", "external-write", "remote-worker", "ai-tool",
                        "event-source", "message-handler", "webhook")::contains)
                .findFirst()
                .orElse(operator.source().kind());
    }

    private GraphDraft scoped(GraphDraft source,
                              String ref,
                              long revision,
                              String kind,
                              List<String> refs,
                              IntegrationRequestContext identity) {
        Map<String, Object> layout = new LinkedHashMap<>(source.visualLayout());
        layout.put("agentTdd", Map.of("assetKind", kind, "libraryRefs", refs));
        return new GraphDraft(source.schemaVersion(), ref, revision, source.graphName(),
                identity.tenantId(), identity.projectId(), identity.environmentId(), GraphDraft.STATUS_DRAFT,
                source.inputSchema(), source.outputSchema(), source.nodes(), source.edges(), layout,
                source.nodeFixtures(), source.output(), source.operatorFingerprints(), source.operatorSnapshots(),
                GraphDraft.RevisionMetadata.empty());
    }

    private void requireReferencedLibraries(GraphDraft draft, List<String> refs) {
        Set<String> explicit = Set.copyOf(refs);
        for (OperatorDefinition operator : draft.operatorSnapshots().values()) {
            if (operator == null || operator.source().libraryId().isBlank()) continue;
            if (!explicit.contains(operator.source().libraryId())) {
                throw new AgentTddToolException("LIBRARY_NOT_FOUND",
                        "Graph uses a library that was not explicitly referenced.");
            }
        }
    }

    private List<String> libraryRefs(JsonNode arguments) {
        if (!arguments.path("libraryRefs").isArray()) {
            throw new AgentTddToolException("COMPILE_ERROR", "libraryRefs must be an explicit array.");
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        arguments.path("libraryRefs").forEach(value -> refs.add(value.asText().trim()));
        refs.forEach(ref -> {
            if (ref.isBlank() || libraries.find(ref).isEmpty()) {
                throw new AgentTddToolException("LIBRARY_NOT_FOUND", "Referenced library contract was not found.");
            }
        });
        return List.copyOf(refs);
    }

    private ObjectNode normalizedRow(JsonNode value) {
        if (!value.isObject()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Every scenario row must be an object.");
        }
        ObjectNode row = ((ObjectNode) value).deepCopy();
        requiredText(row, "caseId");
        String category = enumValue(row, "category", "GOLDEN", CATEGORIES);
        enumValue(row, "lifecycle", "DRAFT", LIFECYCLES);
        enumValue(row, "qualityState", "DESIGNED_NOT_RUN", QUALITY_STATES);
        if (!row.path("given").isObject() || !row.path("stubs").isObject()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "Scenario given and stubs must be objects.");
        }
        if ("REGRESSION".equals(category)) requiredText(row, "sourceRunRef");
        return row;
    }

    private static String enumValue(ObjectNode row, String field, String fallback, Set<String> allowed) {
        String value = optionalText(row, field).toUpperCase(Locale.ROOT);
        if (value.isBlank()) value = fallback;
        if (!allowed.contains(value)) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " has an unsupported value.");
        }
        row.put(field, value);
        return value;
    }

    private ObjectNode currentObject(IntegrationRequestContext identity, String kind, String ref) {
        return states.find(scopeKey(identity), kind, ref)
                .map(AgentTddStoredAsset::data)
                .map(value -> (ObjectNode) value.deepCopy())
                .orElseGet(mapper::createObjectNode);
    }

    private ObjectNode requiredAssetObject(IntegrationRequestContext identity, String kind, String ref) {
        return states.find(scopeKey(identity), kind, ref)
                .map(AgentTddStoredAsset::data)
                .map(value -> (ObjectNode) value.deepCopy())
                .orElseThrow(() -> new AgentTddToolException("DRAFT_NOT_FOUND", "Agent TDD asset was not found."));
    }

    private LinkedHashMap<String, ObjectNode> indexedRows(JsonNode rows) {
        LinkedHashMap<String, ObjectNode> indexed = new LinkedHashMap<>();
        if (rows.isArray()) rows.forEach(row -> indexed.put(row.path("caseId").asText(), (ObjectNode) row.deepCopy()));
        return indexed;
    }

    private void replaceRow(ObjectNode data, ObjectNode replacement) {
        LinkedHashMap<String, ObjectNode> rows = indexedRows(data.path("rows"));
        rows.put(replacement.path("caseId").asText(), replacement);
        ArrayNode array = data.putArray("rows");
        rows.values().forEach(array::add);
    }

    private GraphDraft requireScopedDraft(String ref, IntegrationRequestContext identity) {
        return drafts.find(ref).filter(draft -> draft.tenantId().equals(identity.tenantId())
                        && draft.environment().equals(identity.environmentId()))
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Tool draft was not found in the authorized scope."));
    }

    private void markCaseSetsStale(String toolRef, IntegrationRequestContext identity) {
        states.list(scopeKey(identity), CASE_SET).stream()
                .filter(asset -> toolRef.equals(optionalText(asset.data(), "toolRef")))
                .forEach(asset -> {
                    ObjectNode data = (ObjectNode) asset.data().deepCopy();
                    ArrayNode rows = data.putArray("rows");
                    asset.data().path("rows").forEach(raw -> {
                        ObjectNode row = (ObjectNode) raw.deepCopy();
                        if ("ACTIVE".equals(row.path("lifecycle").asText())) {
                            row.put("lifecycle", "STALE");
                            row.put("qualityState", "STALE");
                        }
                        rows.add(row);
                    });
                    states.save(scopeKey(identity), CASE_SET, asset.assetRef(), data);
                });
    }

    static String scopeKey(IntegrationRequestContext identity) {
        return String.join("|", identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static boolean designOnly(OperatorDefinition operator) {
        if (operator.source().libraryId().isBlank()) {
            return "design".equals(operator.lowering().mode()) || !operator.runtimeReadiness().executable();
        }
        return "".equals(bindingRef(operator));
    }

    private static Object bindingRef(OperatorDefinition operator) {
        Object value = operator.lowering().parameters().get("bindingRef");
        return value instanceof String binding && !binding.isBlank() ? binding : "";
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
