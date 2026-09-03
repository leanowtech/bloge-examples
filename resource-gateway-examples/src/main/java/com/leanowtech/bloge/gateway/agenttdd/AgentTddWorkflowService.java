package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
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
        String toolRef = requiredText(arguments, "toolRef");
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
        states.save(scope(identity), VERDICT, toolRef, verdict);
        LinkedHashMap<String, Object> response = new LinkedHashMap<>(result);
        response.put("evidenceRef", stored.assetRef());
        return Map.copyOf(response);
    }

    /** Reads the latest red-to-green line for one Tool. */
    public Map<String, Object> verdict(JsonNode arguments, IntegrationRequestContext identity) {
        String toolRef = requiredText(arguments, "toolRef");
        return states.find(scope(identity), VERDICT, toolRef)
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
            proposal.set("draft", mapper.valueToTree(draft));
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
            JsonNode latest = states.find(scope(identity), VERDICT, toolRef)
                    .map(AgentTddStoredAsset::data)
                    .map(data -> data.path("latest"))
                    .orElseThrow(() -> gate("A green baseline is required before publication."));
            String currentGoldenSetId = currentGoldenSetId(toolRef, draft, identity);
            if (!"GO".equals(latest.path("status").asText()) || currentGoldenSetId.isBlank()
                    || !currentGoldenSetId.equals(latest.path("goldenSetId").asText())) {
                throw gate("The latest evidence is not a stable green baseline.");
            }
            JsonNode signoff = states.find(scope(identity), SIGNOFF, signoffRef)
                    .map(AgentTddStoredAsset::data)
                    .orElseThrow(() -> gate("An approved owner signoff is required."));
            if (!"APPROVED".equals(signoff.path("status").asText())
                    || !toolRef.equals(signoff.path("toolRef").asText())) {
                throw gate("The owner signoff does not approve this Tool.");
            }
            DslGenerationResult generation = runner.compile(draft);
            if (!generation.generated() || !generation.validation().valid()
                    || !generation.validation().actionReadiness().publishExecutableNow()) {
                throw gate("The authoritative visual compiler did not accept executable publication.");
            }
            List<OperatorDefinition> snapshots = snapshots(draft);
            VisualGraphPublication candidate = VisualGraphPublication.from(
                    draft, snapshots, generation.validation(), generation,
                    GraphDraftDependencyReport.from(draft, catalog),
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

    /** Derives all current publication gates and unresolved limitations. */
    public Map<String, Object> readiness(JsonNode arguments, IntegrationRequestContext identity) {
        String toolRef = requiredText(arguments, "toolRef");
        GraphDraft draft = draft(toolRef, identity);
        JsonNode latest = states.find(scope(identity), VERDICT, toolRef)
                .map(AgentTddStoredAsset::data).map(data -> data.path("latest")).orElse(null);
        String currentGoldenSetId = currentGoldenSetId(toolRef, draft, identity);
        boolean green = latest != null && "GO".equals(latest.path("status").asText())
                && !currentGoldenSetId.isBlank()
                && currentGoldenSetId.equals(latest.path("goldenSetId").asText());
        boolean signed = states.list(scope(identity), SIGNOFF).stream().map(AgentTddStoredAsset::data)
                .anyMatch(data -> toolRef.equals(data.path("toolRef").asText())
                        && "APPROVED".equals(data.path("status").asText()));
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
        var replay = states.replay(scope(identity), operation, key, fingerprint);
        if (replay.isPresent()) return mapper.convertValue(replay.get(), OBJECT_MAP);
        Map<String, Object> result = action.get();
        states.record(scope(identity), operation, key, fingerprint, mapper.valueToTree(result));
        return result;
    }

    private GraphDraft draft(String toolRef, IntegrationRequestContext identity) {
        return drafts.find(toolRef).filter(value -> value.tenantId().equals(identity.tenantId())
                        && value.environment().equals(identity.environmentId()))
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
                                      IntegrationRequestContext identity) {
        List<String> caseIds = states.list(scope(identity), AgentTddMutationService.CASE_SET).stream()
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
