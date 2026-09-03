package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent-facing application facade over the existing Resource Gateway catalog and graph authorities.
 *
 * <p>This first facade intentionally reads the same registries used by the visual control plane;
 * it does not create an MCP-only shadow catalog. Later workflow operations share this dispatcher
 * and therefore retain the same authenticated tenant and environment boundary.</p>
 */
@Service
public final class ResourceGatewayAgentTddTools implements McpToolInvoker {
    private final OperatorLibraryRegistry libraries;
    private final GraphDraftRepository drafts;
    private final ObjectMapper mapper;
    private final AgentTddExecutionService execution;
    private final AgentTddMutationService mutations;
    private final AgentTddWorkflowService workflow;

    /** Creates the Agent tool facade over authoritative RG repositories. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper) {
        this(libraries, drafts, mapper, null, null, null, null, null);
    }

    /** Creates a focused facade with contract-aware DSL and simulation services. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation) {
        this(libraries, drafts, mapper, projection, simulation, null, null, null);
    }

    /** Creates the fully wired facade including canonical mutations and durable Agent overlays. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, null);
    }

    /** Creates the Spring facade with execution evidence and governed publication enabled. */
    @Autowired
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring,
                                        AgentTddWorkflowService workflow) {
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.execution = projection == null || simulation == null
                ? null
                : new AgentTddExecutionService(libraries, drafts, projection, simulation, mapper, states);
        this.mutations = states == null || authoring == null || projection == null
                ? null
                : new AgentTddMutationService(libraries, drafts, states, authoring, projection, mapper);
        this.workflow = workflow;
    }

    /**
     * Dispatches one authenticated catalog tool.
     *
     * @param name exact tool name already resolved by {@link McpToolCatalog}
     * @param arguments request arguments
     * @param identity trusted integration identity
     * @return shared success/error envelope
     */
    @Override
    public Object invoke(String name, JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        JsonNode safeArguments = arguments == null ? mapper.createObjectNode() : arguments;
        try {
            return switch (name) {
            case "rg.capability.list" -> success(capabilities(safeArguments, identity));
            case "rg.library.get" -> library(safeArguments);
            case "rg.library.list" -> success(Map.of("libraries", librarySummaries()));
            case "rg.contract.get" -> contract(safeArguments, identity);
            case "rg.tool.getInstruction" -> executionSuccess(mutations().getInstruction(safeArguments, identity));
            case "rg.scenario.listCases" -> executionSuccess(mutations().listCases(safeArguments, identity));
            case "rg.verdict.get" -> workflow == null
                    ? failure("DRAFT_NOT_FOUND", "No red-to-green verdict exists for the requested tool.")
                    : executionSuccess(workflow.verdict(safeArguments, identity));
            case "rg.evidence.get" -> workflow == null
                    ? failure("DRAFT_NOT_FOUND", "No evidence exists for the requested reference.")
                    : executionSuccess(workflow.evidence(safeArguments, identity));
            case "rg.readiness.get" -> workflow == null
                    ? readiness(safeArguments, identity)
                    : executionSuccess(workflow.readiness(safeArguments, identity));
            case "rg.library.upsert" -> executionSuccess(mutations().upsertLibrary(safeArguments, identity));
            case "rg.feature.compose" -> executionSuccess(
                    mutations().compose(safeArguments, "featureRef", "FEATURE", identity));
            case "rg.tool.compose" -> executionSuccess(
                    mutations().compose(safeArguments, "toolRef", "TOOL", identity));
            case "rg.tool.setInstruction" -> executionSuccess(mutations().setInstruction(safeArguments, identity));
            case "rg.scenario.upsertCases" -> executionSuccess(mutations().upsertCases(safeArguments, identity));
            case "rg.oracle.propose" -> executionSuccess(mutations().proposeOracle(safeArguments, identity));
            case "rg.scenario.setDependencyBehavior" -> executionSuccess(
                    mutations().setDependencyBehavior(safeArguments, identity));
            case "rg.dsl.preview" -> executionSuccess(execution().preview(safeArguments));
            case "rg.gate.check" -> executionSuccess(execution().gate(safeArguments));
            case "rg.simulate" -> executionSuccess(workflow == null
                    ? execution().simulate(safeArguments, identity)
                    : workflow.recordEvidence("rg.simulate", safeArguments,
                            execution().simulate(safeArguments, identity), identity));
            case "rg.feature.rehearse" -> executionSuccess(workflow == null
                    ? execution().rehearse(safeArguments, identity)
                    : workflow.recordEvidence("rg.feature.rehearse", featureArguments(safeArguments),
                            execution().rehearse(safeArguments, identity), identity));
            case "rg.tool.baseline" -> executionSuccess(workflow == null
                    ? execution().baseline(safeArguments, identity)
                    : workflow.recordEvidence("rg.tool.baseline", safeArguments,
                            execution().baseline(safeArguments, identity), identity));
            case "rg.fixture.promote" -> executionSuccess(workflow().promoteFixture(safeArguments, identity));
            case "rg.tool.publishSpec" -> executionSuccess(workflow().publishSpec(safeArguments, identity));
            case "rg.tool.publish" -> executionSuccess(workflow().publish(safeArguments, identity));
            default -> failure("GATE_REJECTED", "The requested workflow operation is not available yet.");
            };
        } catch (AgentTddToolException failure) {
            return failure(failure.code(), failure.getMessage());
        }
    }

    private Map<String, Object> capabilities(JsonNode arguments, IntegrationRequestContext identity) {
        String requestedKind = optionalText(arguments, "kind").toUpperCase(java.util.Locale.ROOT);
        List<Map<String, Object>> values = new ArrayList<>();
        libraries.all().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .flatMap(library -> library.operators().stream()
                        .filter(Objects::nonNull)
                        .map(operator -> operatorCapability(library, operator)))
                .filter(value -> requestedKind.isBlank() || requestedKind.equals(value.get("kind")))
                .forEach(values::add);
        drafts.all().stream()
                .filter(Objects::nonNull)
                .filter(draft -> sameScope(draft, identity))
                .sorted(Comparator.comparing(GraphDraft::draftId))
                .map(this::graphCapability)
                .filter(value -> requestedKind.isBlank() || requestedKind.equals(value.get("kind")))
                .forEach(values::add);
        return Map.of("capabilities", values, "nextCursor", "");
    }

    private Map<String, Object> library(JsonNode arguments) {
        String libraryId = requiredText(arguments, "libraryId");
        OperatorLibrary library = libraries.find(libraryId).orElse(null);
        if (library == null) {
            return failure("LIBRARY_NOT_FOUND", "Library contract was not found.");
        }
        List<Map<String, Object>> operators = library.operators().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .map(this::operatorSummary)
                .toList();
        return success(Map.of(
                "library", library,
                "operators", operators,
                "speccing", operators.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("speccing")))
        ));
    }

    private List<Map<String, Object>> librarySummaries() {
        return libraries.all().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .map(library -> {
                    long designOnly = library.operators().stream()
                            .filter(Objects::nonNull)
                            .filter(ResourceGatewayAgentTddTools::designOnly)
                            .count();
                    return Map.<String, Object>of(
                            "libraryId", library.libraryId(),
                            "version", library.version(),
                            "owner", library.owner(),
                            "status", library.status(),
                            "operatorCount", library.operators().size(),
                            "designOnlyOperatorCount", designOnly,
                            "speccing", designOnly > 0
                    );
                })
                .toList();
    }

    private Map<String, Object> contract(JsonNode arguments, IntegrationRequestContext identity) {
        String assetRef = requiredText(arguments, "assetRef");
        for (OperatorLibrary library : libraries.all()) {
            if (library == null) continue;
            for (OperatorDefinition operator : library.operators()) {
                if (operator != null && assetRef.equals(operator.operatorRef())) {
                    return success(Map.of(
                            "assetRef", assetRef,
                            "kind", operatorKind(operator),
                            "inputs", operator.ports().inputs(),
                            "outputs", operator.ports().outputs(),
                            "effect", operator.capabilities().effect(),
                            "owner", library.owner(),
                            "speccing", designOnly(operator)
                    ));
                }
            }
        }
        GraphDraft draft = drafts.find(assetRef).filter(value -> sameScope(value, identity)).orElse(null);
        if (draft == null) {
            return failure("DRAFT_NOT_FOUND", "Business contract was not found.");
        }
        return success(Map.of(
                "assetRef", assetRef,
                "kind", graphKind(draft),
                "inputSchema", draft.inputSchema(),
                "outputSchema", draft.outputSchema(),
                "revision", draft.revision()
        ));
    }

    private Map<String, Object> readiness(JsonNode arguments, IntegrationRequestContext identity) {
        String toolRef = requiredText(arguments, "toolRef");
        GraphDraft draft = drafts.find(toolRef).filter(value -> sameScope(value, identity)).orElse(null);
        if (draft == null) {
            return failure("DRAFT_NOT_FOUND", "Tool draft was not found.");
        }
        long designOnly = draft.operatorSnapshots().values().stream()
                .filter(Objects::nonNull)
                .filter(ResourceGatewayAgentTddTools::designOnly)
                .count();
        return success(Map.of(
                "toolRef", toolRef,
                "state", designOnly > 0 ? "SPECCING" : "IMPLEMENTING",
                "publishable", false,
                "gates", Map.of("designOnlyOperatorCount", designOnly),
                "remainingLimitations", designOnly > 0
                        ? List.of("SPECCING_NOT_EXECUTABLE", "GREEN_BASELINE_ABSENT", "OWNER_SIGNOFF_ABSENT")
                        : List.of("GREEN_BASELINE_ABSENT", "OWNER_SIGNOFF_ABSENT")
        ));
    }

    private Map<String, Object> operatorCapability(OperatorLibrary library, OperatorDefinition operator) {
        return Map.of(
                "ref", operator.operatorRef(),
                "kind", operatorKind(operator),
                "libraryId", library.libraryId(),
                "version", operator.operatorVersion(),
                "speccing", designOnly(operator),
                "runtimeState", operator.runtimeReadiness().state()
        );
    }

    private Map<String, Object> graphCapability(GraphDraft draft) {
        return Map.of(
                "ref", draft.draftId(),
                "kind", graphKind(draft),
                "name", draft.graphName(),
                "revision", draft.revision(),
                "status", draft.status()
        );
    }

    private Map<String, Object> operatorSummary(OperatorDefinition operator) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("operatorRef", operator.operatorRef());
        summary.put("archetype", operator.source().kind());
        summary.put("speccing", designOnly(operator));
        summary.put("bindingRef", designOnly(operator) ? null : operator.lowering().operatorRef());
        summary.put("runtimeState", operator.runtimeReadiness().state());
        return summary;
    }

    private static String graphKind(GraphDraft draft) {
        Object explicit = draft.visualLayout().get("assetKind");
        String value = explicit == null ? "" : explicit.toString().trim().toUpperCase(java.util.Locale.ROOT);
        if ("FEATURE".equals(value) || "TOOL".equals(value)) {
            return value;
        }
        return draft.draftId().toLowerCase(java.util.Locale.ROOT).contains("feature") ? "FEATURE" : "TOOL";
    }

    private static String operatorKind(OperatorDefinition operator) {
        String kind = operator.source().kind();
        return operator.capabilities().effect().contains("EXTERNAL")
                || kind.contains("resource") || !operator.source().resourceId().isBlank()
                ? "API" : "OPERATOR";
    }

    private static boolean designOnly(OperatorDefinition operator) {
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

    private static String requiredText(JsonNode arguments, String field) {
        String value = optionalText(arguments, field);
        if (value.isBlank()) {
            throw new McpProtocolException(-32602, field + " is required");
        }
        return value;
    }

    private static String optionalText(JsonNode arguments, String field) {
        return arguments != null && arguments.path(field).isTextual()
                ? arguments.path(field).asText().trim() : "";
    }

    private static Map<String, Object> success(Object data) {
        return Map.of("ok", true, "data", data, "diagnostics", List.of());
    }

    private static Map<String, Object> executionSuccess(Object data) {
        return success(data);
    }

    private AgentTddExecutionService execution() {
        if (execution == null) {
            throw new AgentTddToolException("GATE_REJECTED", "Agent execution services are unavailable.");
        }
        return execution;
    }

    private AgentTddMutationService mutations() {
        if (mutations == null) {
            throw new AgentTddToolException("GATE_REJECTED", "Agent mutation services are unavailable.");
        }
        return mutations;
    }

    private AgentTddWorkflowService workflow() {
        if (workflow == null) {
            throw new AgentTddToolException("GATE_REJECTED", "Agent governance services are unavailable.");
        }
        return workflow;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode featureArguments(JsonNode arguments) {
        com.fasterxml.jackson.databind.node.ObjectNode adapted = ((com.fasterxml.jackson.databind.node.ObjectNode)
                arguments).deepCopy();
        adapted.put("toolRef", requiredText(arguments, "featureRef"));
        return adapted;
    }

    private static Map<String, Object> failure(String code, String message) {
        return Map.of(
                "ok", false,
                "error", Map.of(
                        "code", code,
                        "message", message,
                        "retryable", false,
                        "details", Map.of()),
                "diagnostics", List.of()
        );
    }
}
