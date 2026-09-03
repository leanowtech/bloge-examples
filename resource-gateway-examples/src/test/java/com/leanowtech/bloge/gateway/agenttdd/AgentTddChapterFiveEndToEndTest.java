package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualProductionAdmissionPolicy;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visualadapter.DynamicGatewayComposerVisualDslRunner;
import com.leanowtech.bloge.gateway.visualadapter.VisualSimulationKernelAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the Chapter 5 contract-to-golden-to-publication workflow through real RG services. */
class AgentTddChapterFiveEndToEndTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void completesContractRedBindingGreenSignoffAndImmutablePublication() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualOperatorCatalog catalog = catalog(libraries);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        DefaultOperatorRegistry operatorRegistry = new DefaultOperatorRegistry();
        operatorRegistry.registerRaw("controlledRead", new Operator<Object, Object>() {
            @Override
            public Object execute(Object input, OperatorContext context) {
                return Map.of("decision", "WAIVE_FULL");
            }

            @Override
            public SideEffectType sideEffectType() {
                return SideEffectType.READ_ONLY;
            }
        });
        DynamicGatewayComposerVisualDslRunner dslRunner =
                new DynamicGatewayComposerVisualDslRunner(operatorRegistry);
        DslImportService projection = new DslImportService(catalog, new OperatorLibraryValidator());
        VisualGraphSimulationService simulation = new VisualGraphSimulationService(
                validator, catalog, new JsonSchemaSampleGenerator(), dslRunner,
                new VisualSimulationKernelAdapter(mapper), VisualProductionAdmissionPolicy.nonProductionTest());
        AuthoringPreviewService authoring = new AuthoringPreviewService(
                new AuthoringCompiler(mapper, new OperatorLibraryValidator()), libraries, mapper);
        VisualGraphRunService compiler = new VisualGraphRunService(
                validator, new GraphDraftDslGenerator(catalog), dslRunner);
        AgentTddWorkflowService workflow = new AgentTddWorkflowService(
                states, drafts,
                (com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionService) null,
                compiler, catalog, publications, mapper);
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                libraries, drafts, mapper, projection, simulation, states, authoring, workflow);

        assertOk(invoke(tools, "rg.library.upsert", Map.of(
                "libraryYaml", library(false), "idempotencyKey", "library-red")));
        assertThat(libraries.find("cancel").orElseThrow().operators().getFirst().ports().inputs())
                .extracting(OperatorDefinition.Port::name).containsExactly("orderId");
        JsonNode redCompose = invoke(tools, "rg.tool.compose", Map.of(
                "toolRef", "cancel-tool", "graph", Map.of("sourceId", "cancel.bloge", "dsl", graph(false)),
                "libraryRefs", List.of("cancel"), "idempotencyKey", "compose-red"));
        assertThat(redCompose.path("data").path("speccing").asBoolean()).isTrue();

        JsonNode enumerated = invoke(tools, "rg.scenario.upsertCases", Map.of(
                "caseSetRef", "cancel-boundaries", "toolRef", "cancel-tool", "rows", List.of(),
                "enumerateFrom", Map.of("decisionTableRef", "policy", "mode", "per-rule",
                        "maxCases", 10, "oracleOwner", "cx-policy"),
                "idempotencyKey", "enumerate-policy"));
        assertThat(enumerated.at("/data/enumeratedCount").asInt()).isPositive();
        assertThat(enumerated.at("/data/proposed")).isNotEmpty();

        JsonNode cases = invoke(tools, "rg.scenario.upsertCases", Map.of(
                "caseSetRef", "cancel-golden", "toolRef", "cancel-tool", "idempotencyKey", "cases-1",
                "rows", List.of(Map.of("caseId", "g1", "category", "GOLDEN", "layer", "contract",
                        "given", Map.of("orderId", "o-1"),
                        "stubs", Map.of("lookup", Map.of("decision", "WAIVE_FULL")),
                        "expect", Map.of("decision", "WAIVE_FULL"), "oracleOwner", "cx-policy"))));
        long caseRevision = cases.path("data").path("revision").asLong();
        new AgentTddReviewService(states).approveOracle(
                "cancel-golden", "g1", caseRevision,
                cases.at("/data/rows/0/proposedOracle/proposalFingerprint").asText(), reviewerIdentity());

        JsonNode failingRed = invoke(tools, "rg.simulate", Map.of(
                "toolRef", "cancel-tool", "libraryRefs", List.of("cancel"), "side", "RED",
                "cases", Map.of("caseSetRef", "cancel-golden")));
        assertThat(failingRed.at("/data/cases/0/verdict").asText())
                .as(failingRed.toPrettyString()).isEqualTo("RED_FAIL");
        assertThat(invoke(tools, "rg.verdict.get", Map.of("toolRef", "cancel-tool"))
                .at("/data/businessBacklog/0/caseId").asText()).isEqualTo("g1");

        JsonNode r4Compose = invoke(tools, "rg.tool.compose", Map.of(
                "toolRef", "cancel-tool", "graph", Map.of(
                        "sourceId", "cancel.bloge", "dsl", graph(true)),
                "libraryRefs", List.of("cancel"), "idempotencyKey", "compose-r4"));
        assertThat(r4Compose.at("/data/speccing").asBoolean()).isTrue();
        JsonNode red = invoke(tools, "rg.simulate", Map.of(
                "toolRef", "cancel-tool", "libraryRefs", List.of("cancel"), "side", "RED",
                "cases", Map.of("caseSetRef", "cancel-golden")));
        assertThat(red.at("/data/cases/0/verdict").asText()).as(red.toPrettyString())
                .isEqualTo("RED_PASS");
        assertThat(red.path("data").path("realExternalCalls").asInt()).isZero();
        assertThat(invoke(tools, "rg.scenario.listCases", Map.of("caseSetRef", "cancel-golden"))
                .at("/data/rows/0/qualityState").asText()).isEqualTo("READY");

        assertOk(invoke(tools, "rg.library.upsert", Map.of(
                "libraryYaml", library(true), "idempotencyKey", "library-green")));
        JsonNode greenCompose = invoke(tools, "rg.tool.compose", Map.of(
                "toolRef", "cancel-tool", "graph", Map.of("sourceId", "cancel.bloge", "dsl", graph(true)),
                "libraryRefs", List.of("cancel"), "idempotencyKey", "compose-green"));
        assertOk(greenCompose);
        assertThat(greenCompose.path("data").path("speccing").asBoolean()).isFalse();

        JsonNode green = invoke(tools, "rg.tool.baseline", Map.of(
                "toolRef", "cancel-tool", "libraryRefs", List.of("cancel"),
                "caseSetRef", "cancel-golden", "side", "GREEN", "rounds", 2));
        assertThat(green.path("data").path("status").asText())
                .as(green.toPrettyString()).isEqualTo("GO");
        assertThat(green.path("data").path("realExternalCalls").asInt()).isZero();
        states.save(AgentTddMutationService.scopeKey(identity()), AgentTddAttestationService.ATTESTATION,
                "cancel-tool", mapper.valueToTree(Map.of(
                        "toolRef", "cancel-tool", "status", "ATTESTED", "environment", "test",
                        "goldenSetId", green.at("/data/goldenSetId").asText(),
                        "evidenceFingerprint", green.at("/data/evidenceFingerprint").asText(),
                        "draftRevision", green.at("/data/draftRevision").asLong(),
                        "implementationFingerprint", "sha256:chapter-five-v1",
                        "cases", List.of(), "dependencies", List.of(), "realExternalCalls", 0)));
        new AgentTddReviewService(states).approveToolSignoff(
                "cancel-tool", "owner-signoff", green.path("data").path("draftRevision").asLong(),
                green.path("data").path("goldenSetId").asText(),
                green.path("data").path("evidenceFingerprint").asText(),
                "sha256:chapter-five-v1", reviewerIdentity());

        JsonNode ready = invoke(tools, "rg.readiness.get", Map.of("toolRef", "cancel-tool"));
        assertThat(ready.at("/data/publishable").asBoolean()).as(ready.toPrettyString()).isTrue();

        assertOk(invoke(tools, "rg.library.upsert", Map.of(
                "libraryYaml", library(true).replace(
                        "operatorVersion: 1.0.0", "operatorVersion: 1.0.1"),
                "idempotencyKey", "library-target-drift")));
        JsonNode drifted = invoke(tools, "rg.readiness.get", Map.of("toolRef", "cancel-tool"));
        assertThat(drifted.at("/data/publishable").asBoolean()).isFalse();
        assertThat(drifted.at("/data/remainingLimitations").toString())
                .contains("GREEN_BASELINE_ABSENT", "OWNER_SIGNOFF_ABSENT");

        green = invoke(tools, "rg.tool.baseline", Map.of(
                "toolRef", "cancel-tool", "libraryRefs", List.of("cancel"),
                "caseSetRef", "cancel-golden", "side", "GREEN", "rounds", 2));
        states.save(AgentTddMutationService.scopeKey(identity()), AgentTddAttestationService.ATTESTATION,
                "cancel-tool", mapper.valueToTree(Map.of(
                        "toolRef", "cancel-tool", "status", "ATTESTED", "environment", "test",
                        "goldenSetId", green.at("/data/goldenSetId").asText(),
                        "evidenceFingerprint", green.at("/data/evidenceFingerprint").asText(),
                        "draftRevision", green.at("/data/draftRevision").asLong(),
                        "implementationFingerprint", "sha256:chapter-five-v2",
                        "cases", List.of(), "dependencies", List.of(), "realExternalCalls", 0)));
        new AgentTddReviewService(states).approveToolSignoff(
                "cancel-tool", "owner-signoff-v2", green.at("/data/draftRevision").asLong(),
                green.at("/data/goldenSetId").asText(),
                green.at("/data/evidenceFingerprint").asText(),
                "sha256:chapter-five-v2", reviewerIdentity());

        JsonNode published = invoke(tools, "rg.tool.publish", Map.of(
                "toolRef", "cancel-tool", "signoffRef", "owner-signoff-v2",
                "idempotencyKey", "publish-1"));

        assertOk(published);
        assertThat(published.path("data").path("artifactKind").asText()).isEqualTo("EXECUTABLE");
        assertThat(publications.all()).singleElement().satisfies(publication ->
                assertThat(publication.draftId()).isEqualTo("cancel-tool"));
    }

    private VisualOperatorCatalog catalog(InMemoryOperatorLibraryRegistry libraries) {
        VisualOperatorCatalog base = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(), libraries, null);
        return new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                ArrayList<OperatorDefinition> values = new ArrayList<>(base.list(query));
                runtimeOperator().ifPresent(values::add);
                return values;
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                if ("runtime:lookup".equals(operatorRef)) return runtimeOperator();
                return base.find(operatorRef);
            }

            private Optional<OperatorDefinition> runtimeOperator() {
                return libraries.find("cancel").flatMap(library -> library.operators().stream().findFirst())
                        .map(contract -> new OperatorDefinition(
                                contract.schemaVersion(), "runtime:lookup", contract.operatorVersion(), "",
                                contract.display(), new OperatorDefinition.Source(
                                        "java", "", "", "", false),
                                contract.ports(), contract.configSchema(), contract.capabilities(), contract.policy(),
                                new OperatorDefinition.Lowering("native", "controlledRead", Map.of()), List.of()));
            }

        };
    }

    private JsonNode invoke(ResourceGatewayAgentTddTools tools, String name, Object arguments) {
        return mapper.valueToTree(tools.invoke(name, mapper.valueToTree(arguments), identity()));
    }

    private static void assertOk(JsonNode result) {
        assertThat(result.path("ok").asBoolean()).as(result.toPrettyString()).isTrue();
    }

    private static String library(boolean bound) {
        return """
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: { id: cancel, name: Cancellation, version: 1.0.0, owner: cx-ops }
                defaults: { operatorVersion: 1.0.0, namespace: cancel }
                operators:
                  cancel:lookup:
                    name: Lookup
                    archetype: resource-read
                    requiresSecrets: false
                    input: { orderId: string }
                    output: { decision: string }
                """ + (bound ? "    runtime: { bindingRef: \"runtime:lookup\" }\n" : "");
    }

    private static String graph(boolean includeR4) {
        String r4 = includeR4
                ? "    rule (decision: decision == \"WAIVE_FULL\") -> { decision: \"WAIVE_FULL\" }\n"
                : "";
        return """
                graph cancellation {
                  input { orderId: String }
                  output { decision: String }
                  node lookup : "cancel:lookup" {
                    input { orderId = ctx.orderId }
                  }
                  decision_table policy(
                    decision = lookup.output.decision
                  ) hit=first -> { decision: String } {
                """ + r4 + """
                    otherwise -> { decision: "RETAIN" }
                  }
                  transform response { decision = policy.output.decision }
                }
                """;
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "codex-agent",
                "", "AGENT_TDD_AUTHORING", "corr-1");
    }

    private static IntegrationRequestContext reviewerIdentity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "HUMAN", "reviewer-1",
                "", "AGENT_TDD_GOVERNANCE", "corr-review");
    }
}
