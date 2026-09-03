package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.GraphDraftOperatorSnapshotCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionException;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionRequest;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Durable evidence and governed-publication boundary for the Agent TDD workflow.
 *
 * <p>The service deliberately stores only payload-free simulation evidence. Executable publication
 * reuses the existing visual compiler and immutable publication repository, and fails closed unless
 * the latest baseline and an independently approved signoff refer to the requested Tool.</p>
 */
@Service
public final class AgentTddWorkflowService {
    static final String EVIDENCE = "EVIDENCE";
    static final String VERDICT = "VERDICT";
    static final String VERDICT_LINE = "VERDICT_LINE";
    static final String PUBLISH_SPEC = "PUBLISH_SPEC";
    static final String SIGNOFF = "SIGNOFF";
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final GraphDraftRepository drafts;
    private final GraphNodeFixturePromotionService fixtures;
    private final VisualGraphRunService runner;
    private final VisualOperatorCatalog catalog;
    private final VisualGraphPublicationRepository publications;
    private final ObjectMapper mapper;

    /** Creates the workflow boundary over existing authoritative RG services. */
    public AgentTddWorkflowService(AgentTddStateRepository states,
                                   GraphDraftRepository drafts,
                                   GraphNodeFixturePromotionService fixtures,
                                   VisualGraphRunService runner,
                                   VisualOperatorCatalog catalog,
                                   VisualGraphPublicationRepository publications,
                                   ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.fixtures = fixtures;
        this.runner = Objects.requireNonNull(runner, "runner");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.publications = Objects.requireNonNull(publications, "publications");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Creates the Spring workflow while allowing optional governed-Fixture infrastructure. */
    @Autowired
    public AgentTddWorkflowService(AgentTddStateRepository states,
                                   GraphDraftRepository drafts,
                                   ObjectProvider<GraphNodeFixturePromotionService> fixtureProvider,
                                   VisualGraphRunService runner,
                                   VisualOperatorCatalog catalog,
                                   VisualGraphPublicationRepository publications,
                                   ObjectMapper mapper) {
        this(states, drafts, fixtureProvider.getIfAvailable(), runner, catalog, publications, mapper);
    }

    /** Persists a payload-free execution result and advances the current red-to-green verdict. */
    public Map<String, Object> recordEvidence(String operation,
                                              JsonNode arguments,
                                              Map<String, Object> result,
                                              IntegrationRequestContext identity) {
        return states.executeAtomically(() -> recordEvidenceAtomically(operation, arguments, result, identity));
    }

    /** Performs the case revision fence and all derived evidence writes inside one store transaction. */
    private Map<String, Object> recordEvidenceAtomically(String operation,
                                                         JsonNode arguments,
                                                         Map<String, Object> result,
                                                         IntegrationRequestContext identity) {
        String toolRef = requiredText(arguments, "toolRef");
        markPassingCasesReady(result, identity);
        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("toolRef", toolRef);
        evidence.put("operation", operation);
        evidence.set("result", mapper.valueToTree(result));
        AgentTddStoredAsset stored = states.save(scope(identity), EVIDENCE,
                evidenceRef(toolRef, operation, result), evidence);
        ObjectNode verdict = mapper.createObjectNode();
        verdict.put("toolRef", toolRef);
        verdict.put("goldenSetId", Objects.toString(result.get("goldenSetId"), ""));
        verdict.put("state", state(draft(toolRef, identity), result));
        verdict.put("evidenceRef", stored.assetRef());
        verdict.set("latest", mapper.valueToTree(result));
        verdict.set("byLayer", mergedLayerMatrix(toolRef, result, identity));
        verdict.set("businessBacklog", businessBacklog(result));
        if (result.containsKey("honestVerdict")) {
            verdict.set("honestVerdict", mapper.valueToTree(result.get("honestVerdict")));
        }
        if ("rg.tool.baseline".equals(operation)) {
            verdict.set("baseline", mapper.valueToTree(result));
        }
        states.save(scope(identity), VERDICT, toolRef, verdict);
        String goldenSetId = verdict.path("goldenSetId").asText();
        if (!goldenSetId.isBlank()) {
            states.save(scope(identity), VERDICT_LINE, verdictLineRef(toolRef, goldenSetId), verdict);
        }
        LinkedHashMap<String, Object> response = new LinkedHashMap<>(result);
        response.put("evidenceRef", stored.assetRef());
        return Map.copyOf(response);
    }

    /** Reads the current line, or an archived line when {@code goldenSetId} is supplied. */
    public Map<String, Object> verdict(JsonNode arguments, IntegrationRequestContext identity) {
        String toolRef = requiredText(arguments, "toolRef");
        String goldenSetId = arguments.path("goldenSetId").asText().trim();
        String kind = goldenSetId.isBlank() ? VERDICT : VERDICT_LINE;
        String ref = goldenSetId.isBlank() ? toolRef : verdictLineRef(toolRef, goldenSetId);
        return states.find(scope(identity), kind, ref)
                .map(AgentTddStoredAsset::data)
                .map(value -> mapper.convertValue(value, OBJECT_MAP))
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "No red-to-green verdict exists for the requested tool."));
    }

    /** Reads one payload-free evidence artifact in the authorized enterprise scope. */
    public Map<String, Object> evidence(JsonNode arguments, IntegrationRequestContext identity) {
        String evidenceRef = requiredText(arguments, "evidenceRef");
        return states.find(scope(identity), EVIDENCE, evidenceRef)
                .map(AgentTddStoredAsset::data)
                .map(value -> mapper.convertValue(value, OBJECT_MAP))
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Execution evidence was not found in the authorized scope."));
    }

    /** Proposes a frozen design publication without making it executable or effective. */
    public Map<String, Object> publishSpec(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.tool.publishSpec", arguments, identity, () -> {
            String toolRef = requiredText(arguments, "toolRef");
            GraphDraft draft = draft(toolRef, identity);
            ObjectNode proposal = mapper.createObjectNode();
            proposal.put("toolRef", toolRef);
            proposal.put("draftRevision", draft.revision());
            proposal.put("status", "PENDING");
            proposal.put("proposedBy", identity.actorId());
            proposal.set("draft", mapper.valueToTree(draft));
            proposal.put("proposalFingerprint",
                    com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint
                            .fromCanonicalValue(mapper, proposal, MAX_BYTES));
            AgentTddStoredAsset stored = states.save(scope(identity), PUBLISH_SPEC, toolRef, proposal);
            return Map.of("toolRef", toolRef, "proposalStatus", "PENDING",
                    "revision", stored.revision(), "awaiting", "human-approval");
        });
    }

    /**
     * Publishes an immutable executable Tool after compile, green baseline, and signoff gates pass.
     */
    public Map<String, Object> publish(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.tool.publish", arguments, identity, () -> {
            String toolRef = requiredText(arguments, "toolRef");
            String signoffRef = requiredText(arguments, "signoffRef");
            GraphDraft draft = draft(toolRef, identity);
            if (speccing(draft)) {
                throw new AgentTddToolException(
                        "SPECCING_NOT_EXECUTABLE", "A Tool with unbound library operators cannot be published.");
            }
            FrozenExecutable frozen = frozenExecutable(draft);
            GraphDraft executable = frozen.draft();
            JsonNode latest = states.find(scope(identity), VERDICT, toolRef)
                    .map(AgentTddStoredAsset::data)
                    .map(data -> data.path("latest"))
                    .orElseThrow(() -> gate("A green baseline is required before publication."));
            String caseSetRef = latest.path("caseSetRef").asText();
            String currentGoldenSetId = currentGoldenSetId(toolRef, draft, identity, caseSetRef);
            String currentEvidenceFingerprint = currentEvidenceFingerprint(
                    toolRef, draft, executable, identity, caseSetRef, "GREEN");
            if (!"GREEN".equals(latest.path("side").asText())
                    || !"GO".equals(latest.path("status").asText()) || currentGoldenSetId.isBlank()
                    || !currentGoldenSetId.equals(latest.path("goldenSetId").asText())
                    || draft.revision() != latest.path("draftRevision").asLong(-1)
                    || !currentEvidenceFingerprint.equals(latest.path("evidenceFingerprint").asText())) {
                throw gate("The latest evidence is not a stable green baseline.");
            }
            JsonNode signoff = states.find(scope(identity), SIGNOFF, signoffRef)
                    .map(AgentTddStoredAsset::data)
                    .orElseThrow(() -> gate("An approved owner signoff is required."));
            if (!"APPROVED".equals(signoff.path("status").asText())
                    || !toolRef.equals(signoff.path("toolRef").asText())
                    || draft.revision() != signoff.path("draftRevision").asLong(-1)
                    || !currentGoldenSetId.equals(signoff.path("goldenSetId").asText())
                    || !currentEvidenceFingerprint.equals(signoff.path("evidenceFingerprint").asText())) {
                throw gate("The owner signoff does not approve this Tool.");
            }
            DslGenerationResult generation = runner.compileAgainst(executable, frozen.catalog());
            if (!generation.generated() || !generation.validation().valid()
                    || !generation.validation().actionReadiness().publishExecutableNow()) {
                throw gate("The authoritative visual compiler did not accept executable publication.");
            }
            List<OperatorDefinition> snapshots = snapshots(executable);
            VisualGraphPublication candidate = VisualGraphPublication.from(
                    executable, snapshots, generation.validation(), generation,
                    GraphDraftDependencyReport.from(executable, frozen.catalog()),
                    VisualGraphPublication.PublicationMetadata.of(
                            identity.actorId(), "MCP_AGENT_TDD",
                            "Published after stable green baseline and owner signoff.", signoffRef));
            VisualGraphPublication stored = publications.create(candidate);
            return Map.of("toolRef", toolRef, "publicationId", stored.publicationId(),
                    "artifactKind", stored.artifactKind(), "goldenSetId", latest.path("goldenSetId").asText(),
                    "signoffRef", signoffRef);
        });
    }

    /** Wraps the server-derived graph-node Fixture promotion service with MCP idempotency. */
    public Map<String, Object> promoteFixture(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.fixture.promote", arguments, identity, () -> {
            if (fixtures == null) {
                throw new AgentTddToolException(
                        "GATE_REJECTED", "Governed Fixture material infrastructure is unavailable.");
            }
            List<String> redactions = new ArrayList<>();
            arguments.path("redactPaths").forEach(path -> redactions.add(jsonPointer(path.asText())));
            try {
                var result = fixtures.promote(requiredText(arguments, "draftId"),
                        requiredText(arguments, "nodeId"), requiredText(arguments, "outputPort"),
                        new GraphNodeFixturePromotionRequest(
                                GraphNodeFixturePromotionRequest.SCHEMA_VERSION,
                                requiredText(arguments, "fixtureId"), requiredText(arguments, "category"),
                                arguments.path("retentionDays").asInt(), redactions), identity);
                return Map.of("fixtureId", result.fixtureAssetId(), "revision", result.revision(),
                        "lifecycle", result.lifecycle(), "scope", scope(identity),
                        "schemaRef", result.schemaRef(), "lineageRef", result.assetRef(),
                        "sourceKind", result.sourceKind());
            } catch (GraphNodeFixturePromotionException failure) {
                String code = failure.code().substring(failure.code().lastIndexOf('.') + 1);
                if ("OUTPUT_SCHEMA_NON_UNIQUE".equals(code)) code = "AMBIGUOUS_OUTPUT_PORT";
                if ("REQUEST_INVALID".equals(code)) code = "SCHEMA_NONCONFORMANT";
                throw new AgentTddToolException(code, failure.getMessage());
            }
        });
    }

    /**
     * Persists a direct business sample as a governed Fixture after exact output-schema validation.
     *
     * <p>The Fixture id is content-addressed from the canonical request. Scope, schema and lineage
     * are always derived by the server; the MCP response never returns the supplied value.</p>
     */
    public Map<String, Object> provideFixture(JsonNode arguments, IntegrationRequestContext identity) {
        return idempotent("rg.fixture.provide", arguments, identity, () -> {
            if (fixtures == null) {
                throw new AgentTddToolException(
                        "GATE_REJECTED", "Governed Fixture material infrastructure is unavailable.");
            }
            List<String> redactions = new ArrayList<>();
            arguments.path("redactPaths").forEach(path -> redactions.add(jsonPointer(path.asText())));
            String requestFingerprint = com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint
                    .fromCanonicalValue(mapper, arguments, MAX_BYTES);
            String fixtureId = "provided-" + requestFingerprint.substring("sha256:".length(), 30);
            try {
                var result = fixtures.provide(
                        requiredText(arguments, "operatorRef"), requiredText(arguments, "outputPort"),
                        mapper.convertValue(arguments.get("sampleValue"), Object.class),
                        new GraphNodeFixturePromotionRequest(
                                GraphNodeFixturePromotionRequest.SCHEMA_VERSION, fixtureId,
                                requiredText(arguments, "category"),
                                arguments.path("retentionDays").asInt(), redactions), identity);
                return Map.of("fixtureId", result.fixtureAssetId(), "revision", result.revision(),
                        "lifecycle", result.lifecycle(), "scope", scope(identity),
                        "schemaRef", result.schemaRef(), "lineageRef", result.assetRef(),
                        "sourceKind", result.sourceKind());
            } catch (GraphNodeFixturePromotionException failure) {
                String code = failure.code().substring(failure.code().lastIndexOf('.') + 1);
                if ("OUTPUT_SCHEMA_INVALID".equals(code) || "REQUEST_INVALID".equals(code)) {
                    code = "SCHEMA_NONCONFORMANT";
                } else if ("OPERATOR_NOT_FOUND".equals(code)) {
                    code = "LIBRARY_NOT_FOUND";
                } else if ("OUTPUT_SCHEMA_NON_UNIQUE".equals(code)) {
                    code = "AMBIGUOUS_OUTPUT_PORT";
                }
                throw new AgentTddToolException(code, failure.getMessage());
            }
        });
    }

    /** Derives all current publication gates and unresolved limitations. */
    public Map<String, Object> readiness(JsonNode arguments, IntegrationRequestContext identity) {
        String toolRef = requiredText(arguments, "toolRef");
        GraphDraft draft = draft(toolRef, identity);
        JsonNode latest = states.find(scope(identity), VERDICT, toolRef)
                .map(AgentTddStoredAsset::data).map(data -> data.path("latest")).orElse(null);
        String caseSetRef = latest == null ? "" : latest.path("caseSetRef").asText();
        String currentGoldenSetId = currentGoldenSetId(toolRef, draft, identity, caseSetRef);
        GraphDraft executable = frozenExecutable(draft).draft();
        String currentEvidenceFingerprint = currentEvidenceFingerprint(
                toolRef, draft, executable, identity, caseSetRef, "GREEN");
        boolean green = latest != null && "GREEN".equals(latest.path("side").asText())
                && "GO".equals(latest.path("status").asText())
                && !currentGoldenSetId.isBlank()
                && currentGoldenSetId.equals(latest.path("goldenSetId").asText())
                && draft.revision() == latest.path("draftRevision").asLong(-1)
                && currentEvidenceFingerprint.equals(latest.path("evidenceFingerprint").asText());
        boolean signed = states.list(scope(identity), SIGNOFF).stream().map(AgentTddStoredAsset::data)
                .anyMatch(data -> toolRef.equals(data.path("toolRef").asText())
                        && "APPROVED".equals(data.path("status").asText())
                        && draft.revision() == data.path("draftRevision").asLong(-1)
                        && currentGoldenSetId.equals(data.path("goldenSetId").asText())
                        && currentEvidenceFingerprint.equals(data.path("evidenceFingerprint").asText()));
        boolean design = speccing(draft);
        List<String> remaining = new ArrayList<>();
        if (design) remaining.add("SPECCING_NOT_EXECUTABLE");
        if (!green) remaining.add("GREEN_BASELINE_ABSENT");
        if (!signed) remaining.add("OWNER_SIGNOFF_ABSENT");
        return Map.of("toolRef", toolRef,
                "state", design ? "SPECCING" : green ? "IMPLEMENTED" : "IMPLEMENTING",
                "publishable", remaining.isEmpty(),
                "goldenSetId", currentGoldenSetId,
                "gates", Map.of("bindingsComplete", !design, "greenBaseline", green, "ownerSignoff", signed),
                "remainingLimitations", remaining);
    }

    private Map<String, Object> idempotent(String operation,
                                           JsonNode arguments,
                                           IntegrationRequestContext identity,
                                           Supplier<Map<String, Object>> action) {
        String key = requiredText(arguments, "idempotencyKey");
        String fingerprint = com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint
                .fromCanonicalValue(mapper, arguments, MAX_BYTES);
        JsonNode result = states.executeOnce(scope(identity), operation, key, fingerprint,
                () -> mapper.valueToTree(action.get()));
        return mapper.convertValue(result, OBJECT_MAP);
    }

    private GraphDraft draft(String toolRef, IntegrationRequestContext identity) {
        return drafts.find(toolRef).filter(identity::matchesDraftScope)
                .orElseThrow(() -> new AgentTddToolException(
                        "DRAFT_NOT_FOUND", "Tool draft was not found in the authorized scope."));
    }

    private static List<OperatorDefinition> snapshots(GraphDraft draft) {
        LinkedHashMap<String, OperatorDefinition> values = new LinkedHashMap<>();
        draft.nodes().forEach(node -> {
            OperatorDefinition value = draft.operatorSnapshots().get(node.id());
            if (value != null) values.putIfAbsent(value.operatorRef() + "@" + value.fingerprint(), value);
        });
        return List.copyOf(values.values());
    }

    private String currentGoldenSetId(String toolRef,
                                      GraphDraft draft,
                                      IntegrationRequestContext identity,
                                      String caseSetRef) {
        if (caseSetRef == null || caseSetRef.isBlank()) return "";
        List<String> caseIds = states.list(scope(identity), AgentTddMutationService.CASE_SET).stream()
                .filter(asset -> caseSetRef.equals(asset.assetRef()))
                .map(AgentTddStoredAsset::data)
                .filter(data -> toolRef.equals(data.path("toolRef").asText()))
                .flatMap(data -> {
                    List<JsonNode> rows = new ArrayList<>();
                    data.path("rows").forEach(rows::add);
                    return rows.stream();
                })
                .filter(row -> "ACTIVE".equals(row.path("lifecycle").asText()))
                .map(row -> row.path("caseId").asText())
                .filter(value -> !value.isBlank())
                .sorted()
                .toList();
        return caseIds.isEmpty() ? "" : AgentTddExecutionService.goldenSetId(mapper, toolRef, draft, caseIds);
    }

    private String currentEvidenceFingerprint(String toolRef,
                                              GraphDraft draft,
                                              GraphDraft executable,
                                              IntegrationRequestContext identity,
                                              String caseSetRef,
                                              String side) {
        if (caseSetRef == null || caseSetRef.isBlank()) return "";
        List<JsonNode> rows = states.find(scope(identity), AgentTddMutationService.CASE_SET, caseSetRef)
                .filter(asset -> toolRef.equals(asset.data().path("toolRef").asText()))
                .map(AgentTddStoredAsset::data)
                .map(data -> {
                    List<JsonNode> active = new ArrayList<>();
                    data.path("rows").forEach(row -> {
                        if ("ACTIVE".equals(row.path("lifecycle").asText())) active.add(row);
                    });
                    return List.copyOf(active);
                }).orElse(List.of());
        return rows.isEmpty() ? "" : AgentTddExecutionService.evidenceFingerprint(
                mapper, toolRef, draft, rows, side,
                AgentTddRuntimeBindingResolver.bindingIdentity(draft, executable));
    }

    private ObjectNode mergedLayerMatrix(String toolRef,
                                         Map<String, Object> result,
                                         IntegrationRequestContext identity) {
        String goldenSetId = Objects.toString(result.get("goldenSetId"), "");
        ObjectNode matrix = states.find(scope(identity), VERDICT, toolRef)
                .map(AgentTddStoredAsset::data)
                .filter(data -> goldenSetId.equals(data.path("goldenSetId").asText()))
                .filter(data -> data.path("byLayer").isObject())
                .map(data -> (ObjectNode) data.path("byLayer").deepCopy())
                .orElseGet(mapper::createObjectNode);
        String side = Objects.toString(result.get("side"), "RED").toLowerCase(java.util.Locale.ROOT);
        JsonNode current = mapper.valueToTree(result.getOrDefault("byLayer", Map.of()));
        for (String layer : List.of("unit", "contract", "integration", "smoke")) {
            ObjectNode cell = matrix.path(layer).isObject()
                    ? (ObjectNode) matrix.path(layer)
                    : matrix.putObject(layer);
            cell.set(side, current.path(layer).isObject()
                    ? current.path(layer).deepCopy()
                    : mapper.valueToTree(Map.of("pass", 0, "fail", 0)));
            String other = "red".equals(side) ? "green" : "red";
            if (!cell.has(other)) cell.set(other, mapper.valueToTree(Map.of("pass", 0, "fail", 0)));
        }
        return matrix;
    }

    /**
     * Advances successfully executed ACTIVE cases at the exact revision used by execution.
     *
     * <p>The execution result, rather than client arguments, is the only accepted durable source.
     * A case-set edit between resolution and evidence persistence rejects the entire evidence write;
     * it can never mark a different row revision READY merely because a case id was reused.</p>
     */
    private void markPassingCasesReady(Map<String, Object> result,
                                       IntegrationRequestContext identity) {
        String caseSetRef = Objects.toString(result.get("caseSetRef"), "").trim();
        if (caseSetRef.isBlank()) return;
        long executedRevision = result.get("caseSetRevision") instanceof Number revision
                ? revision.longValue() : -1;
        if (executedRevision < 1) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "Durable case execution revision is missing.");
        }
        String resolvedCaseSetRef = caseSetRef;
        java.util.Set<String> passing = new java.util.LinkedHashSet<>();
        JsonNode executed = mapper.valueToTree(result.getOrDefault("cases", List.of()));
        executed.forEach(row -> {
            if (row.path("verdict").asText().endsWith("PASS")) {
                passing.add(row.path("caseId").asText());
            }
        });
        AgentTddStoredAsset current = states.lockRevision(
                scope(identity), AgentTddMutationService.CASE_SET,
                resolvedCaseSetRef, executedRevision);
        if (passing.isEmpty()) return;
        ObjectNode data = (ObjectNode) current.data().deepCopy();
        boolean[] changed = {false};
        data.path("rows").forEach(row -> {
            if (passing.contains(row.path("caseId").asText())
                    && "ACTIVE".equals(row.path("lifecycle").asText())
                    && !"READY".equals(row.path("qualityState").asText())) {
                ((ObjectNode) row).put("qualityState", "READY");
                changed[0] = true;
            }
        });
        if (changed[0]) {
            states.saveIfRevision(scope(identity), AgentTddMutationService.CASE_SET,
                    resolvedCaseSetRef, executedRevision, data);
        }
    }

    private FrozenExecutable frozenExecutable(GraphDraft draft) {
        Map<String, OperatorDefinition> targets = catalog.findAll(
                AgentTddRuntimeBindingResolver.bindingLookupRefs(draft));
        GraphDraft executable = AgentTddRuntimeBindingResolver.materialize(
                draft, ref -> java.util.Optional.ofNullable(targets.get(ref)));
        try {
            return new FrozenExecutable(executable,
                    GraphDraftOperatorSnapshotCatalog.from(executable));
        } catch (IllegalArgumentException failure) {
            throw new AgentTddToolException(
                    "PUBLISH_GATE_NOT_MET", "Executable operator snapshots are incomplete or inconsistent.");
        }
    }

    private record FrozenExecutable(GraphDraft draft, GraphDraftOperatorSnapshotCatalog catalog) { }

    private static String verdictLineRef(String toolRef, String goldenSetId) {
        return toolRef + ":" + goldenSetId;
    }

    private ArrayNode businessBacklog(Map<String, Object> result) {
        ArrayNode backlog = mapper.createArrayNode();
        JsonNode cases = mapper.valueToTree(result.getOrDefault("cases", List.of()));
        cases.forEach(row -> {
            String verdict = row.path("verdict").asText();
            if ("GOLDEN".equals(row.path("category").asText()) && !verdict.endsWith("PASS")) {
                ObjectNode item = backlog.addObject();
                item.put("caseId", row.path("caseId").asText());
                item.put("layer", row.path("layer").asText());
                item.put("reason", verdict);
                item.put("owner", row.path("oracleOwner").asText());
            }
        });
        return backlog;
    }

    private static boolean speccing(GraphDraft draft) {
        return draft.operatorSnapshots().values().stream().filter(Objects::nonNull).anyMatch(operator -> {
            if (operator.source().libraryId().isBlank()) {
                return "design".equals(operator.lowering().mode()) || !operator.runtimeReadiness().executable();
            }
            Object binding = operator.lowering().parameters().get("bindingRef");
            return !(binding instanceof String value) || value.isBlank();
        });
    }

    private static String state(GraphDraft draft, Map<String, Object> result) {
        if (speccing(draft)) return "SPECCING";
        return "GO".equals(Objects.toString(result.get("status"), "")) ? "IMPLEMENTED" : "IMPLEMENTING";
    }

    private String evidenceRef(String toolRef, String operation, Map<String, Object> result) {
        String contentFingerprint = com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint
                .fromCanonicalValue(mapper, Map.of("operation", operation, "result", result), MAX_BYTES);
        return toolRef + ":" + Objects.toString(result.get("side"), "BASELINE") + ":"
                + Objects.toString(result.get("goldenSetId"), "unknown") + ":" + contentFingerprint;
    }

    private static AgentTddToolException gate(String message) {
        return new AgentTddToolException("PUBLISH_GATE_NOT_MET", message);
    }

    private static String jsonPointer(String path) {
        String value = path == null ? "" : path.trim();
        if (value.startsWith("$.")) return "/" + value.substring(2).replace(".", "/");
        return value;
    }

    private static String scope(IntegrationRequestContext identity) {
        return AgentTddMutationService.scopeKey(identity);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node != null && node.path(field).isTextual() ? node.path(field).asText().trim() : "";
        if (value.isBlank()) throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " is required.");
        return value;
    }
}
