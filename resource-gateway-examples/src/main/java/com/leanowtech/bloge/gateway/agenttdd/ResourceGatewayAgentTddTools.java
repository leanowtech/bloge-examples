package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureEvaluationBackend;
import com.leanowtech.bloge.gateway.solution.FeatureTokenKeyProvider;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.capability.BusinessCapabilityIndex;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenService;
import com.leanowtech.bloge.gateway.solution.journey.BusinessGoldenMaterialStore;
import com.leanowtech.bloge.gateway.solution.journey.BusinessJourneyService;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
    private final VisualOperatorCatalog catalog;
    private final AgentTddExecutionService execution;
    private final AgentTddMutationService mutations;
    private final AgentTddWorkflowService workflow;
    private final AgentTddResourceDeclarationService declarations;
    private final AgentTddAttestationService attestations;
    private final AgentDslAuthoringSupport dslAuthoring;
    private final SolutionAgentTools solutionTools;
    private final AgentTddLibraryOverviewService libraryOverview;
    private final BusinessCapabilityIndex capabilityIndex;
    private final BusinessJourneyService journeys;
    private final BusinessGoldenService businessGolden;

    /** Creates the Agent tool facade over authoritative RG repositories. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper) {
        this(libraries, drafts, mapper, null, null, null, null, null, null, null, null);
    }

    /** Creates a focused facade with contract-aware DSL and simulation services. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation) {
        this(libraries, drafts, mapper, projection, simulation, null, null, null, null, null, null);
    }

    /** Creates the fully wired facade including canonical mutations and durable Agent overlays. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, null, null, null, null);
    }

    /**
     * Creates a facade with zero-egress execution evidence and governed publication enabled.
     *
     * <p>This overload is retained for focused tests and embedders that do not need discovery of
     * Resource Gateway runtime descriptors.</p>
     */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring,
                                        AgentTddWorkflowService workflow) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, workflow, null, null, null);
    }

    /** Creates the catalog-aware facade retained for existing embedders and focused tests. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring,
                                        AgentTddWorkflowService workflow,
                                        VisualOperatorCatalog catalog) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, workflow, catalog, null, null);
    }

    /** Creates the resource-declaration facade retained for focused tests and embedders. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring,
                                        AgentTddWorkflowService workflow,
                                        VisualOperatorCatalog catalog,
                                        AgentTddResourceDeclarationService declarations) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, workflow,
                catalog, declarations, null);
    }

    /**
     * Creates the Spring facade over both authored libraries and the live visual operator catalog.
     *
     * <p>The catalog is required in production so an Agent can discover the exact runtime
     * {@code bindingRef}, port contract, effect, and readiness of descriptor-backed APIs instead
     * of guessing identifiers that are not present in authored operator libraries.</p>
     */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring,
                                        AgentTddWorkflowService workflow,
                                        VisualOperatorCatalog catalog,
                                        AgentTddResourceDeclarationService declarations,
                                        AgentTddAttestationService attestations) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, workflow,
                catalog, declarations, attestations, AgentTddAuthoringTelemetry.noop());
    }

    /** Creates the Spring facade with payload-free Agent authoring telemetry. */
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring,
                                        AgentTddWorkflowService workflow,
                                        VisualOperatorCatalog catalog,
                                        AgentTddResourceDeclarationService declarations,
                                        AgentTddAttestationService attestations,
                                        AgentTddAuthoringTelemetry telemetry) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, workflow,
                catalog, declarations, attestations, telemetry,
                (FeatureEvaluationBackend) null,
                (InstructionDispatchChannel) null,
                (FeatureTokenKeyProvider) null, null);
    }

    /** Creates the Spring facade with optional Feature and Instruction runtime adapters. */
    @Autowired
    public ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                        GraphDraftRepository drafts,
                                        ObjectMapper mapper,
                                        DslImportService projection,
                                        VisualGraphSimulationService simulation,
                                        AgentTddStateRepository states,
                                        AuthoringPreviewService authoring,
                                        AgentTddWorkflowService workflow,
                                        VisualOperatorCatalog catalog,
                                        AgentTddResourceDeclarationService declarations,
                                        AgentTddAttestationService attestations,
                                        AgentTddAuthoringTelemetry telemetry,
                                        ObjectProvider<FeatureEvaluationBackend> featureBackends,
                                        ObjectProvider<InstructionDispatchChannel> instructionChannels,
                                        ObjectProvider<FeatureTokenKeyProvider> tokenKeys,
                                        ObjectProvider<SolutionWriteExecutionRunner> writeRunners,
                                        AgentTddLibraryOverviewService libraryOverview,
                                        BusinessCapabilityIndex capabilityIndex,
                                        BusinessJourneyService journeys,
                                        BusinessGoldenService businessGolden,
                                        BusinessGoldenMaterialStore goldenMaterials) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, workflow,
                catalog, declarations, attestations, telemetry,
                featureBackends.getIfUnique(), instructionChannels.getIfUnique(), tokenKeys.getIfUnique(),
                writeRunners.getIfUnique(), libraryOverview, capabilityIndex, journeys, businessGolden,
                goldenMaterials);
    }

    private ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                         GraphDraftRepository drafts,
                                         ObjectMapper mapper,
                                         DslImportService projection,
                                         VisualGraphSimulationService simulation,
                                         AgentTddStateRepository states,
                                         AuthoringPreviewService authoring,
                                         AgentTddWorkflowService workflow,
                                         VisualOperatorCatalog catalog,
                                         AgentTddResourceDeclarationService declarations,
                                         AgentTddAttestationService attestations,
                                         AgentTddAuthoringTelemetry telemetry,
                                         FeatureEvaluationBackend featureBackend,
                                         InstructionDispatchChannel instructionChannel,
                                         FeatureTokenKeyProvider tokenKeys,
                                         SolutionWriteExecutionRunner writeRunner) {
        this(libraries, drafts, mapper, projection, simulation, states, authoring, workflow,
                catalog, declarations, attestations, telemetry, featureBackend, instructionChannel,
                tokenKeys, writeRunner, null, null, null, null, null);
    }

    private ResourceGatewayAgentTddTools(OperatorLibraryRegistry libraries,
                                         GraphDraftRepository drafts,
                                         ObjectMapper mapper,
                                         DslImportService projection,
                                         VisualGraphSimulationService simulation,
                                         AgentTddStateRepository states,
                                         AuthoringPreviewService authoring,
                                         AgentTddWorkflowService workflow,
                                         VisualOperatorCatalog catalog,
                                         AgentTddResourceDeclarationService declarations,
                                         AgentTddAttestationService attestations,
                                         AgentTddAuthoringTelemetry telemetry,
                                         FeatureEvaluationBackend featureBackend,
                                         InstructionDispatchChannel instructionChannel,
                                         FeatureTokenKeyProvider tokenKeys,
                                         SolutionWriteExecutionRunner writeRunner,
                                         AgentTddLibraryOverviewService libraryOverview,
                                         BusinessCapabilityIndex capabilityIndex,
                                         BusinessJourneyService journeys,
                                         BusinessGoldenService businessGolden,
                                         BusinessGoldenMaterialStore goldenMaterials) {
        this.libraries = Objects.requireNonNull(libraries, "libraries");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.catalog = catalog;
        this.dslAuthoring = catalog == null ? null
                : new AgentDslAuthoringSupport(catalog, libraries, mapper, telemetry);
        this.execution = projection == null || simulation == null
                ? null
                : new AgentTddExecutionService(
                        libraries, drafts, projection, simulation, mapper, states);
        this.mutations = states == null || authoring == null || projection == null
                ? null
                : new AgentTddMutationService(libraries, drafts, states, authoring, projection, mapper,
                        this.dslAuthoring);
        this.workflow = workflow;
        this.declarations = declarations;
        this.attestations = attestations;
        this.libraryOverview = libraryOverview != null
                ? libraryOverview
                : catalog == null ? null : new AgentTddLibraryOverviewService(catalog);
        this.capabilityIndex = capabilityIndex;
        this.journeys = journeys;
        this.businessGolden = businessGolden;
        this.solutionTools = states == null ? null : new SolutionAgentTools(
                states, mapper, projection, featureBackend, instructionChannel, tokenKeys, writeRunner,
                goldenMaterials);
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
            case "rg.library.overview.get" -> success(libraryOverview().overview(
                    identity, safeArguments.path("includeSamples").asBoolean(false)));
            case "rg.capability.search" -> success(capabilityIndex().search(safeArguments, identity));
            case "rg.entity.list" -> success(capabilityIndex().list(safeArguments, identity));
            case "rg.entity.get" -> success(capabilityIndex().get(safeArguments, identity));
            case "rg.journey.start" -> success(journeys().start(safeArguments, identity));
            case "rg.journey.next" -> success(journeys().next(safeArguments, identity));
            case "rg.solution.golden.propose" -> executionSuccess(journeyAction(name, safeArguments, identity,
                    () -> businessGolden().propose(safeArguments, identity)));
            case "rg.solution.golden.list" -> success(businessGolden().list(safeArguments, identity));
            case "rg.contract.get" -> contract(safeArguments, identity);
            case "rg.tool.getInstruction" -> executionSuccess(mutations().getInstruction(safeArguments, identity));
            case "rg.scenario.listCases" -> executionSuccess(mutations().listCases(safeArguments, identity));
            case "rg.verdict.get" -> workflow == null
                    ? failure("DRAFT_NOT_FOUND", "No red-to-green verdict exists for the requested tool.")
                    : executionSuccess(workflow.verdict(safeArguments, identity));
            case "rg.evidence.get" -> workflow == null
                    ? failure("DRAFT_NOT_FOUND", "No evidence exists for the requested reference.")
                    : executionSuccess(workflow.evidence(safeArguments, identity));
            case "rg.dsl.reference.get" -> success(dslAuthoring().reference(
                    new DslReferenceRequest(
                            requiredStringList(safeArguments, "libraryRefs"),
                            optionalStringList(safeArguments, "topics"),
                            optionalStringList(safeArguments, "operatorRefs"),
                            safeArguments.path("includeExamples").asBoolean(false)), identity));
            case "rg.feature.define" -> executionSuccess(businessAuthoringAction(name, safeArguments, identity,
                    () -> solutionTools().defineFeature(safeArguments, identity)));
            case "rg.feature.handoff" -> executionSuccess(journeyAction(name, safeArguments, identity,
                    () -> solutionTools().handoffFeature(safeArguments, identity)));
            case "rg.feature.evaluate" -> executionSuccess(solutionTools().evaluateFeature(safeArguments, identity));
            case "rg.scenario.define" -> executionSuccess(businessAuthoringAction(name, safeArguments, identity,
                    () -> solutionTools().defineScenario(safeArguments, identity)));
            case "rg.instruction.define" -> executionSuccess(
                    businessAuthoringAction(name, safeArguments, identity,
                            () -> solutionTools().defineInstruction(safeArguments, identity)));
            case "rg.solution.compose" -> executionSuccess(
                    businessAuthoringAction(name, safeArguments, identity,
                            () -> solutionTools().composeSolution(safeArguments, identity)));
            case "rg.solution.getContract" -> executionSuccess(
                    solutionTools().getSolutionContract(safeArguments, identity));
            case "rg.solution.invoke" -> executionSuccess(
                    solutionTools().invokeSolution(safeArguments, identity));
            case "rg.scenario.test" -> executionSuccess(
                    solutionTools().testScenario(safeArguments, identity));
            case "rg.solution.baseline" -> executionSuccess(
                    journeyBaseline(safeArguments, identity));
            case "rg.solution.commit" -> executionSuccess(
                    journeyAction(name, safeArguments, identity,
                            () -> solutionTools().commitSolution(safeArguments, identity)));
            case "rg.engineering.handoff" -> executionSuccess(
                    journeyAction(name, safeArguments, identity,
                            () -> solutionTools().handoffSolution(safeArguments, identity)));
            case "rg.solution.readiness" -> executionSuccess(
                    solutionTools().readinessSolution(safeArguments, identity));
            case "rg.solution.performance" -> executionSuccess(
                    solutionTools().performanceSolution(safeArguments, identity));
            case "rg.solution.publish" -> executionSuccess(
                    journeyAction(name, safeArguments, identity,
                            () -> solutionTools().publishSolution(safeArguments, identity)));
            case "rg.readiness.get" -> workflow == null
                    ? readiness(safeArguments, identity)
                    : executionSuccess(workflow.readiness(safeArguments, identity));
            case "rg.library.upsert" -> executionSuccess(mutations().upsertLibrary(safeArguments, identity));
            case "rg.resource.declare" -> executionSuccess(declarations().declare(safeArguments, identity));
            case "rg.feature.compose" -> executionSuccess(
                    mutations().compose(safeArguments, "featureRef", "FEATURE", identity));
            case "rg.tool.compose" -> executionSuccess(
                    mutations().compose(safeArguments, "toolRef", "TOOL", identity));
            case "rg.tool.setInstruction" -> executionSuccess(mutations().setInstruction(safeArguments, identity));
            case "rg.scenario.upsertCases" -> executionSuccess(mutations().upsertCases(safeArguments, identity));
            case "rg.oracle.propose" -> executionSuccess(mutations().proposeOracle(safeArguments, identity));
            case "rg.scenario.setDependencyBehavior" -> executionSuccess(
                    mutations().setDependencyBehavior(safeArguments, identity));
            case "rg.dsl.preview" -> executionSuccess(authoringPreview(safeArguments, identity, false));
            case "rg.gate.check" -> executionSuccess(authoringPreview(safeArguments, identity, true));
            case "rg.simulate" -> executionSuccess(workflow == null
                    ? execution().simulate(safeArguments, identity)
                    : workflow.recordEvidence("rg.simulate", safeArguments,
                            execution().simulate(safeArguments, identity), identity));
            case "rg.feature.rehearse" -> executionSuccess(workflow == null
                    ? execution().rehearse(safeArguments, identity)
                    : workflow.recordEvidence("rg.feature.rehearse", featureArguments(safeArguments),
                            execution().rehearse(safeArguments, identity), identity));
            case "rg.tool.baseline" -> executionSuccess(baseline(safeArguments, identity));
            case "rg.fixture.promote" -> executionSuccess(workflow().promoteFixture(safeArguments, identity));
            case "rg.fixture.provide" -> executionSuccess(workflow().provideFixture(safeArguments, identity));
            case "rg.tool.publishSpec" -> executionSuccess(workflow().publishSpec(safeArguments, identity));
            case "rg.tool.publish" -> executionSuccess(workflow().publish(safeArguments, identity));
            default -> failure("GATE_REJECTED", "The requested workflow operation is not available yet.");
            };
        } catch (AgentTddToolException failure) {
            return failure(failure.code(), safeErrorMessage(failure.code()), failure.details(), failure.retryable());
        }
    }

    private SolutionAgentTools solutionTools() {
        if (solutionTools == null) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The solution authoring service is unavailable.");
        }
        return solutionTools;
    }

    private AgentTddLibraryOverviewService libraryOverview() {
        if (libraryOverview == null) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The business library overview is unavailable.");
        }
        return libraryOverview;
    }

    private BusinessCapabilityIndex capabilityIndex() {
        if (capabilityIndex == null) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The business capability index is unavailable.");
        }
        return capabilityIndex;
    }

    private BusinessJourneyService journeys() {
        if (journeys == null) throw new AgentTddToolException(
                "GATE_REJECTED", "Business journey navigation is unavailable.");
        return journeys;
    }

    private BusinessGoldenService businessGolden() {
        if (businessGolden == null) throw new AgentTddToolException(
                "GATE_REJECTED", "Business GOLDEN authoring is unavailable.");
        return businessGolden;
    }

    private Map<String, Object> journeyAction(String name, JsonNode arguments,
                                              IntegrationRequestContext identity,
                                              java.util.function.Supplier<Map<String, Object>> action) {
        return arguments.path("journeyRef").isTextual()
                ? journeys().executeAction(name, arguments, identity, action) : action.get();
    }

    /**
     * Executes one four-entity authoring action against the exact server template read by Codex.
     *
     * <p>The coordinate is required only inside the new business journey so legacy direct authoring
     * remains wire compatible. A missing or stale coordinate fails before the entity write and
     * returns no current fingerprint, forcing the caller to fetch the governed overview again.</p>
     */
    private Map<String, Object> businessAuthoringAction(
            String name, JsonNode arguments, IntegrationRequestContext identity,
            java.util.function.Supplier<Map<String, Object>> action) {
        if (!arguments.path("journeyRef").isTextual()) return action.get();
        String supplied = optionalText(arguments, "authoringPatternsFingerprint");
        String current = Objects.toString(libraryOverview().overview(identity, false)
                .get("authoringPatternsFingerprint"), "");
        if (supplied.isBlank() || !supplied.equals(current)) {
            throw new AgentTddToolException("CAPABILITY_CONTEXT_STALE",
                    "The business authoring template context is missing or stale.", Map.of(), true);
        }
        return journeys().executeAction(name, arguments, identity, action);
    }

    private Map<String, Object> journeyBaseline(JsonNode arguments, IntegrationRequestContext identity) {
        if (!arguments.path("journeyRef").isTextual()) return solutionTools().baselineSolution(arguments, identity);
        com.fasterxml.jackson.databind.node.ObjectNode resolved = (com.fasterxml.jackson.databind.node.ObjectNode)
                arguments.deepCopy();
        resolved.put("caseSetRef", journeys().associatedCaseSet(arguments, identity));
        return journeys().executeAction("rg.solution.baseline", arguments, identity,
                () -> solutionTools().baselineSolution(resolved, identity));
    }

    private Map<String, Object> capabilities(JsonNode arguments, IntegrationRequestContext identity) {
        String requestedKind = optionalText(arguments, "kind").toUpperCase(java.util.Locale.ROOT);
        Map<String, Map<String, Object>> byRef = new LinkedHashMap<>();
        libraries.all().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .flatMap(library -> library.operators().stream()
                        .filter(Objects::nonNull)
                        .map(operator -> operatorCapability(library, operator)))
                .filter(value -> requestedKind.isBlank() || requestedKind.equals(value.get("kind")))
                .forEach(value -> byRef.put(value.get("ref").toString(), value));
        runtimeOperators(identity).stream()
                .map(this::runtimeOperatorCapability)
                .filter(value -> requestedKind.isBlank() || requestedKind.equals(value.get("kind")))
                .forEach(value -> byRef.put(value.get("ref").toString(), value));
        drafts.all().stream()
                .filter(Objects::nonNull)
                .filter(identity::matchesDraftScope)
                .sorted(Comparator.comparing(GraphDraft::draftId))
                .map(this::graphCapability)
                .filter(value -> requestedKind.isBlank() || requestedKind.equals(value.get("kind")))
                .forEach(value -> byRef.put(value.get("ref").toString(), value));
        List<Map<String, Object>> values = byRef.values().stream()
                .sorted(Comparator.comparing(value -> value.get("ref").toString()))
                .toList();
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
        OperatorDefinition runtimeOperator = runtimeOperators(identity).stream()
                .filter(operator -> assetRef.equals(operator.operatorRef()))
                .findFirst()
                .orElse(null);
        if (runtimeOperator != null) {
            return success(Map.of(
                    "assetRef", assetRef,
                    "kind", operatorKind(runtimeOperator),
                    "inputs", runtimeOperator.ports().inputs(),
                    "outputs", runtimeOperator.ports().outputs(),
                    "effect", runtimeOperator.capabilities().effect(),
                    "sourceKind", runtimeOperator.source().kind(),
                    "bindingRef", runtimeOperator.operatorRef(),
                    "runtimeState", runtimeOperator.runtimeReadiness().state(),
                    "speccing", false
            ));
        }
        GraphDraft draft = drafts.find(assetRef).filter(identity::matchesDraftScope).orElse(null);
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
        GraphDraft draft = drafts.find(toolRef).filter(identity::matchesDraftScope).orElse(null);
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

    /**
     * Runs preview and merge-gate requests through the same immutable authoring compiler.
     *
     * <p>The response contains only the safe receipt projection. The internal graph projection is
     * retained by the compiler for compose revalidation and is excluded from JSON serialization.</p>
     */
    private Map<String, Object> authoringPreview(JsonNode arguments,
                                                 IntegrationRequestContext identity,
                                                 boolean gate) {
        JsonNode source = arguments.path("source");
        String sourceId;
        String dsl;
        if (source.isTextual()) {
            sourceId = "inline.bloge";
            dsl = source.asText();
        } else if (source.isObject()) {
            sourceId = optionalText(source, "sourceId");
            dsl = requiredText(source, "dsl");
        } else {
            throw new McpProtocolException(-32602, "source must be a string or DSL envelope");
        }
        List<String> refs = requiredStringList(arguments, "libraryRefs");
        DslPreviewReceipt receipt = dslAuthoring().preview(new DslPreviewRequest(
                sourceId, dsl, refs, requiredText(arguments, "authoringContextFingerprint")), identity);
        boolean compileAccepted = receipt.stages().stream()
                .filter(stage -> List.of("PARSE", "RESOLVE", "TYPE_CHECK", "SEMANTIC_COMPILE")
                        .contains(stage.phase()))
                .noneMatch(stage -> "FAIL".equals(stage.status()) || "NOT_RUN".equals(stage.status()));
        boolean speccing = receipt.serverProjection().draft().operatorSnapshots().values().stream()
                .filter(Objects::nonNull).anyMatch(ResourceGatewayAgentTddTools::designOnly);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", receipt.accepted());
        result.put("compileAccepted", compileAccepted);
        if (gate) {
            result.put("rewriteGate", Map.of(
                    "allowed", receipt.accepted(),
                    "decision", receipt.accepted() ? "ALLOW" : "BLOCK"));
        }
        result.put("speccing", speccing);
        result.put("executable", !speccing);
        result.put("libraryRefs", refs);
        result.put("authoringContext", receipt.authoringContext());
        result.put("stages", receipt.stages());
        result.put("technicalAcceptance", receipt.technicalAcceptance());
        result.put("projection", receipt.projection());
        result.put("roundTrip", receipt.roundTrip());
        result.put("authoringDiagnostics", receipt.authoringDiagnostics());
        result.put("diagnosticSummary", receipt.diagnosticSummary());
        result.put("nextAction", receipt.nextAction());
        result.put("authoringReceiptFingerprint", receipt.authoringReceiptFingerprint());
        result.put("honestVerdict", authoringVerdict(compileAccepted, gate));
        return Map.copyOf(result);
    }

    /** Four proof dimensions prevent a technical gate from being presented as business proof. */
    private static Map<String, Object> authoringVerdict(boolean compileAccepted, boolean gate) {
        return Map.of("dimensions", List.of(
                Map.of("name", "contract-conformance", "status", compileAccepted ? "PASS" : "FAIL",
                        "proves", "Server authoring pipeline accepted the technical contract.",
                        "doesNotProve", "Business correctness and runtime behavior."),
                Map.of("name", "business-correctness", "status", "NOT_PROVEN",
                        "proves", "No business claim.",
                        "doesNotProve", "Approved GOLDEN cases have not been executed."),
                Map.of("name", "dependency-isolation", "status", "NOT_PROVEN",
                        "proves", "No dependency claim.",
                        "doesNotProve", "Zero-egress evidence requires simulate or baseline."),
                Map.of("name", "runtime-governance", "status", "NOT_PROVEN",
                        "proves", gate ? "Technical gate only." : "Preview only.",
                        "doesNotProve", "Attestation and owner signoff remain required.")));
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

    private Map<String, Object> runtimeOperatorCapability(OperatorDefinition operator) {
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("ref", operator.operatorRef());
        capability.put("kind", operatorKind(operator));
        capability.put("name", operator.display().name());
        capability.put("version", operator.operatorVersion());
        capability.put("bindingRef", operator.operatorRef());
        capability.put("inputPorts", operator.ports().inputs().stream().map(OperatorDefinition.Port::name).toList());
        capability.put("outputPorts", operator.ports().outputs().stream().map(OperatorDefinition.Port::name).toList());
        capability.put("effect", operator.capabilities().effect());
        capability.put("requiresSecrets", operator.capabilities().requiresSecrets());
        capability.put("sourceKind", operator.source().kind());
        capability.put("speccing", false);
        capability.put("runtimeState", operator.runtimeReadiness().state());
        return Map.copyOf(capability);
    }

    /** Returns resource-backed runtime APIs visible in the authenticated authoring scope. */
    private List<OperatorDefinition> runtimeOperators(IntegrationRequestContext identity) {
        if (catalog == null) {
            return List.of();
        }
        return catalog.list(new OperatorCatalogQuery(
                "", List.of(), true, false,
                identity.tenantId(), identity.projectId(), identity.environmentId()));
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

    private static List<String> requiredStringList(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || !value.isArray()) {
            throw new McpProtocolException(-32602, field + " is required");
        }
        return stringList(value, field);
    }

    private static List<String> optionalStringList(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        return value == null ? List.of() : stringList(value, field);
    }

    private static List<String> stringList(JsonNode value, String field) {
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new McpProtocolException(-32602, field + " must contain non-blank strings");
            }
            values.add(item.asText().trim());
        }
        return List.copyOf(values);
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

    private AgentTddResourceDeclarationService declarations() {
        if (declarations == null) {
            throw new AgentTddToolException("GATE_REJECTED", "Agent resource declaration is unavailable.");
        }
        return declarations;
    }

    private AgentDslAuthoringSupport dslAuthoring() {
        if (dslAuthoring == null) {
            throw new AgentTddToolException("GATE_REJECTED", "DSL authoring support is unavailable.");
        }
        return dslAuthoring;
    }

    /** Records logical GREEN first, then lets the platform-only runner attach real-call evidence. */
    private Map<String, Object> baseline(JsonNode arguments, IntegrationRequestContext identity) {
        Map<String, Object> logical = execution().baseline(arguments, identity);
        if (workflow == null) return logical;
        Map<String, Object> recorded = workflow.recordEvidence(
                "rg.tool.baseline", arguments, logical, identity);
        if (attestations == null || !"GO".equals(Objects.toString(recorded.get("status"), ""))
                || !"GREEN".equals(Objects.toString(recorded.get("side"), ""))) {
            return recorded;
        }
        LinkedHashMap<String, Object> response = new LinkedHashMap<>(recorded);
        Map<String, Object> attestation = attestations.attestAfterGreen(recorded, identity);
        response.put("attestation", attestation);
        response.put("remainingLimitations", "ATTESTED".equals(attestation.get("status"))
                ? List.of("OWNER_SIGNOFF_ABSENT")
                : List.of("RUNTIME_ENV_NOT_ATTESTED", "LIVE_INTEGRATION_NOT_ATTESTED",
                        "OWNER_SIGNOFF_ABSENT"));
        return Map.copyOf(response);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode featureArguments(JsonNode arguments) {
        com.fasterxml.jackson.databind.node.ObjectNode adapted = ((com.fasterxml.jackson.databind.node.ObjectNode)
                arguments).deepCopy();
        adapted.put("toolRef", requiredText(arguments, "featureRef"));
        return adapted;
    }

    private static Map<String, Object> failure(String code, String message) {
        return failure(code, message, Map.of(), false);
    }

    private static Map<String, Object> failure(String code,
                                               String message,
                                               Map<String, Object> details) {
        return failure(code, message, details, false);
    }

    private static Map<String, Object> failure(String code,
                                               String message,
                                               Map<String, Object> details,
                                               boolean retryable) {
        return Map.of(
                "ok", false,
                "error", Map.of(
                        "code", code,
                        "message", message,
                        "retryable", retryable,
                        "details", details == null ? Map.of() : Map.copyOf(details)),
                "diagnostics", List.of()
        );
    }

    /**
     * Maps application failures to stable payload-free protocol prose.
     *
     * <p>Lower layers may include rejected source fragments, fixture values, or provider details in
     * exception messages. The MCP surface exposes the stable error code while retaining only this
     * catalog-owned explanation.</p>
     */
    private static String safeErrorMessage(String code) {
        return switch (code == null ? "" : code) {
            case "UNAUTHENTICATED" -> "Authentication is required.";
            case "FORBIDDEN_PURPOSE" -> "The authenticated purpose does not authorize this operation.";
            case "DRAFT_NOT_FOUND" -> "The requested governed asset was not found.";
            case "LIBRARY_NOT_FOUND" -> "A referenced library or runtime binding was not found.";
            case "RESOURCE_NOT_REGISTERED" -> "A referenced resource must be declared before composition.";
            case "REFERENCE_UNRESOLVED" -> "A referenced solution entity is unavailable in this scope.";
            case "FEATURE_TOKEN_INVALID" -> "The Feature evaluation token is invalid.";
            case "USE_NATIVE_INTERACTION" -> "Collect this Feature through its declared user interaction.";
            case "FEATURE_BINDING_REQUIRED" -> "The Feature evaluation binding is unavailable.";
            case "FEATURE_EVALUATOR_UNAVAILABLE" -> "The Feature evaluation runtime is unavailable.";
            case "FEATURE_EVALUATION_FAILED" -> "Feature evaluation failed.";
            case "FEATURE_INPUT_INVALID" -> "Feature evaluation inputs do not match the contract.";
            case "FEATURE_OUTPUT_INVALID" -> "Feature evaluation output does not match the contract.";
            case "FEATURE_BUSINESS_DEFINITION_REQUIRED" ->
                    "Complete the Feature business definition before saving it.";
            case "CAPABILITY_CONTEXT_STALE" -> "Search the current business capability snapshot again.";
            case "CAPABILITY_INDEX_UNSTABLE" -> "Retry after the business capability catalog stabilizes.";
            case "CAPABILITY_NOT_FOUND" -> "The requested business capability was not found.";
            case "INVALID_CAPABILITY_QUERY" -> "The business capability query is invalid.";
            case "SOLUTION_INPUT_INVALID" -> "The Solution input envelope is invalid.";
            case "SOLUTION_NOT_PUBLISHED" -> "The current Solution has no matching publication.";
            case "SOLUTION_INVOCATION_RECOVERY_REQUIRED" ->
                    "The Solution invocation outcome requires operator recovery.";
            case "INSTRUCTION_BINDING_UNAVAILABLE" -> "The Instruction runtime binding is unavailable.";
            case "SCENARIO_OUTLET_UNRESOLVED" -> "A scenario outlet is not declared by the solution.";
            case "SCENARIO_TREE_CYCLE" -> "The scenario tree contains a cycle.";
            case "SCENARIO_TREE_TOO_DEEP" -> "The scenario tree exceeds the configured depth.";
            case "SCENARIO_BIND_INCOMPLETE" -> "A scenario outlet binding is incomplete.";
            case "SCENARIO_HIT_NOT_UNIQUE" -> "More than one scenario rule matched.";
            case "SOLUTION_LOWERING_FAILED" -> "The pure solution graph did not pass precompilation.";
            case "SOLUTION_EXECUTION_UNAUTHORIZED" -> "Solution execution authority is required.";
            case "COMPILE_ERROR" -> "Compilation failed; inspect payload-free diagnostics.";
            case "SPECCING_NOT_EXECUTABLE" -> "A design-only asset is not executable.";
            case "GOLDEN_REQUIRES_APPROVAL" -> "Business approval is required for the golden Oracle.";
            case "IDEMPOTENCY_CONFLICT" -> "The idempotency key conflicts with an existing request.";
            case "SCHEMA_NONCONFORMANT" -> "The request does not conform to the declared tool schema.";
            case "AMBIGUOUS_OUTPUT_PORT" -> "The requested output port is ambiguous.";
            case "RETENTION_POLICY_VIOLATION" -> "The requested retention policy is not allowed.";
            case "EGRESS_NOT_ALLOWED" -> "Outbound access is not allowed for this operation.";
            case "WRITE_EFFECT_NOT_ALLOWED" -> "Agent resource declaration permits read-only methods.";
            case "PUBLISH_GATE_NOT_MET" -> "One or more publication gates are not satisfied.";
            case "SIM_REAL_CALL_DETECTED" -> "Simulation detected a forbidden real invocation.";
            case "COMBINATORIAL_CAP_EXCEEDED" -> "Scenario enumeration exceeds its configured cap.";
            case "DSL_AUTHORING_CONTEXT_REQUIRED" -> "Fetch the DSL authoring reference before compiling.";
            case "DSL_AUTHORING_CONTEXT_STALE" -> "Refetch the DSL reference before compiling again.";
            case "DSL_AUTHORING_RECEIPT_STALE" -> "Run the DSL gate again for this exact candidate.";
            case "DSL_SOURCE_TOO_LARGE" -> "Narrow the DSL source before compiling again.";
            case "DSL_LIBRARY_NOT_VISIBLE" -> "A requested DSL library is not visible in this authoring scope.";
            case "DSL_REFERENCE_TOPIC_UNKNOWN" -> "A requested DSL reference topic is not supported.";
            case "DSL_REFERENCE_TOO_LARGE" -> "Narrow the requested DSL reference scope.";
            default -> "The requested operation was rejected by a governance gate.";
        };
    }
}
