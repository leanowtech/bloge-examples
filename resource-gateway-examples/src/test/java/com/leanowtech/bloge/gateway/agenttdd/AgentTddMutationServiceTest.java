package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visualadapter.DynamicGatewayComposerVisualDslRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies canonical mutations, proposal boundaries and exact idempotency semantics. */
class AgentTddMutationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void compilesLibraryYamlIntoCanonicalRegistryAndReplaysExactly() {
        Fixture fixture = fixture();
        JsonNode arguments = mapper.valueToTree(Map.of(
                "libraryYaml", minimalLibraryYaml(), "idempotencyKey", "lib-1"));

        JsonNode first = invoke(fixture, "rg.library.upsert", arguments);
        JsonNode replay = invoke(fixture, "rg.library.upsert", arguments);

        assertThat(first.path("ok").asBoolean()).as(first.toPrettyString()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(fixture.libraries().find("shipping")).isPresent();
        assertThat(fixture.libraries().revisions("shipping")).hasSize(1);
        assertThat(first.path("data").path("operators").get(0).path("speccing").asBoolean()).isTrue();
    }

    @Test
    void composesToolThroughCompileGateAndRejectsIdempotencyKeyReuse() {
        Fixture fixture = fixture();
        invoke(fixture, "rg.library.upsert", mapper.valueToTree(Map.of(
                "libraryYaml", minimalLibraryYaml(), "idempotencyKey", "lib-1")));
        JsonNode compose = mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "libraryRefs", List.of("shipping"),
                "graph", Map.of("sourceId", "shipping.bloge", "dsl", shippingDsl()),
                "idempotencyKey", "compose-1"));

        JsonNode first = invoke(fixture, "rg.tool.compose", compose);
        JsonNode conflict = invoke(fixture, "rg.tool.compose", mapper.valueToTree(Map.of(
                "toolRef", "other-tool", "libraryRefs", List.of("shipping"),
                "graph", Map.of("sourceId", "shipping.bloge", "dsl", shippingDsl()),
                "idempotencyKey", "compose-1")));

        assertThat(first.path("ok").asBoolean()).as(first.toPrettyString()).isTrue();
        assertThat(first.path("data").path("speccing").asBoolean()).isTrue();
        assertThat(fixture.drafts().find("shipping-tool")).hasValueSatisfying(draft ->
                assertThat(draft.visualLayout()).containsKey("agentTdd"));
        assertThat(conflict.path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void goldenExpectationRemainsPendingAndDependencyBehaviorIsBounded() {
        Fixture fixture = fixtureWithTool();
        JsonNode upsert = mapper.valueToTree(Map.of(
                "caseSetRef", "shipping-cases", "toolRef", "shipping-tool", "idempotencyKey", "cases-1",
                "rows", List.of(Map.of(
                        "caseId", "g1", "category", "GOLDEN", "lifecycle", "ACTIVE",
                        "given", Map.of("orderId", "o1"), "stubs", Map.of(),
                        "expect", Map.of("fee", 0), "oracleOwner", "cx-ops"))));

        JsonNode stored = invoke(fixture, "rg.scenario.upsertCases", upsert);
        JsonNode behavior = invoke(fixture, "rg.scenario.setDependencyBehavior", mapper.valueToTree(Map.of(
                "caseSetRef", "shipping-cases", "caseId", "g1", "nodeId", "quote",
                "behavior", Map.of("behavior", "RETURN", "value", Map.of("fee", 0)),
                "idempotencyKey", "behavior-1")));
        JsonNode listed = invoke(fixture, "rg.scenario.listCases",
                mapper.valueToTree(Map.of("caseSetRef", "shipping-cases")));

        assertThat(stored.path("data").path("proposed")).hasSize(1);
        JsonNode row = listed.path("data").path("rows").get(0);
        assertThat(row.path("lifecycle").asText()).isEqualTo("DRAFT");
        assertThat(row.has("expect")).isFalse();
        assertThat(row.path("proposedOracle").path("status").asText()).isEqualTo("PENDING");
        assertThat(behavior.path("data").path("behavior").path("behavior").asText()).isEqualTo("RETURN");
    }

    @Test
    void enumeratedGoldenConclusionAlsoRequiresHumanApproval() {
        Fixture fixture = fixture();
        fixture.drafts().save(decisionDraft());

        JsonNode stored = invoke(fixture, "rg.scenario.upsertCases", mapper.valueToTree(Map.of(
                "caseSetRef", "policy-cases", "toolRef", "policy-tool", "rows", List.of(),
                "enumerateFrom", Map.of("decisionTableRef", "policy", "mode", "per-rule",
                        "maxCases", 3, "oracleOwner", "pricing-owner"),
                "idempotencyKey", "enumerate-1")));

        assertThat(stored.path("ok").asBoolean()).as(stored.toPrettyString()).isTrue();
        assertThat(stored.path("data").path("enumeratedCount").asInt()).isEqualTo(3);
        assertThat(stored.path("data").path("proposed")).hasSize(1);
        JsonNode golden = stored.path("data").path("rows").get(0);
        assertThat(golden.path("category").asText()).isEqualTo("GOLDEN");
        assertThat(golden.has("expect")).isFalse();
        assertThat(golden.path("proposedOracle").path("expect").path("decision").asText())
                .isEqualTo("WAIVE");
        assertThat(golden.path("proposedOracle").path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void toolExamplesCannotBeAuthoredOutsideApprovedGoldenCases() {
        Fixture fixture = fixtureWithTool();
        JsonNode response = invoke(fixture, "rg.tool.setInstruction", mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "idempotencyKey", "instruction-1",
                "instruction", Map.of(
                        "name", "shippingQuote", "title", "Shipping quote",
                        "description", "Gets a quote", "whenToUse", "Before checkout",
                        "inputs", List.of(), "outputs", Map.of("kind", "object"), "errors", List.of(),
                        "examples", List.of(Map.of("input", Map.of(), "output", Map.of()))))));

        assertThat(response.path("ok").asBoolean()).isFalse();
        assertThat(response.path("error").path("code").asText()).isEqualTo("GATE_REJECTED");
    }

    @Test
    void approvedGoldenBecomesTheOnlySourceOfToolExamples() {
        Fixture fixture = fixtureWithTool();
        invoke(fixture, "rg.scenario.upsertCases", mapper.valueToTree(Map.of(
                "caseSetRef", "shipping-cases", "toolRef", "shipping-tool", "idempotencyKey", "cases-1",
                "rows", List.of(Map.of(
                        "caseId", "g1", "category", "GOLDEN", "given", Map.of("orderId", "o1"),
                        "stubs", Map.of(), "expect", Map.of("fee", 0), "oracleOwner", "cx-ops")))));
        invoke(fixture, "rg.tool.setInstruction", mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "idempotencyKey", "instruction-1",
                "instruction", Map.of(
                        "name", "shippingQuote", "title", "Shipping quote",
                        "description", "Gets a quote", "whenToUse", "Before checkout",
                        "inputs", List.of(), "outputs", Map.of("kind", "object"), "errors", List.of()))));
        new AgentTddReviewService(fixture.states()).approveOracle(
                "shipping-cases", "g1", 1, identity());

        JsonNode instruction = invoke(fixture, "rg.tool.getInstruction",
                mapper.valueToTree(Map.of("toolRef", "shipping-tool")));

        assertThat(instruction.path("ok").asBoolean()).isTrue();
        assertThat(instruction.path("data").path("examples")).hasSize(1);
        assertThat(instruction.path("data").path("examples").get(0)
                .path("fromGoldenCaseId").asText()).isEqualTo("g1");
    }

    @Test
    void contractChangeMarksPreviouslyActiveGoldenRowsStale() {
        Fixture fixture = fixtureWithTool();
        JsonNode proposed = invoke(fixture, "rg.scenario.upsertCases", mapper.valueToTree(Map.of(
                "caseSetRef", "shipping-golden", "toolRef", "shipping-tool",
                "rows", List.of(Map.of("caseId", "g1", "category", "GOLDEN",
                        "given", Map.of("orderId", "O1"), "stubs", Map.of(),
                        "expect", Map.of("fee", 8), "oracleOwner", "logistics")),
                "idempotencyKey", "cases-drift-1")));
        new AgentTddReviewService(fixture.states()).approveOracle(
                "shipping-golden", "g1", proposed.path("data").path("revision").asLong(), identity());

        invoke(fixture, "rg.tool.compose", mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "graph", Map.of("dsl", shippingDslWithRegion()),
                "libraryRefs", List.of("shipping"), "idempotencyKey", "compose-drift-2")));
        JsonNode cases = invoke(fixture, "rg.scenario.listCases",
                mapper.valueToTree(Map.of("caseSetRef", "shipping-golden")));

        assertThat(cases.path("data").path("rows").get(0).path("lifecycle").asText()).isEqualTo("STALE");
        assertThat(cases.path("data").path("rows").get(0).path("qualityState").asText()).isEqualTo("STALE");
    }

    @Test
    void composeMaterializesAnExplicitCompatibleRuntimeBinding() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        VisualOperatorCatalog catalog = new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                java.util.ArrayList<OperatorDefinition> values = new java.util.ArrayList<>();
                libraries.all().forEach(library -> values.addAll(library.operators()));
                boundOperator().ifPresent(values::add);
                return values;
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return list(new OperatorCatalogQuery("", List.of(), false, true)).stream()
                        .filter(operator -> operatorRef.equals(operator.operatorRef())).findFirst();
            }

            private Optional<OperatorDefinition> boundOperator() {
                return libraries.find("shipping").flatMap(library -> library.operators().stream().findFirst())
                        .map(contract -> new OperatorDefinition(
                                "", "resource:shipping-service.quote", "1.0.0", "",
                                contract.display(), new OperatorDefinition.Source(
                                        "resource-descriptor", "shipping-service.quote", "GET", "/quotes", true),
                                contract.ports(), contract.configSchema(), contract.capabilities(), contract.policy(),
                                new OperatorDefinition.Lowering("resource-descriptor", "httpResource", Map.of()),
                                List.of()));
            }
        };
        DslImportService projection = new DslImportService(catalog, new OperatorLibraryValidator());
        AgentTddMutationService service = new AgentTddMutationService(libraries, drafts,
                new InMemoryAgentTddStateRepository(), new AuthoringPreviewService(
                        new AuthoringCompiler(mapper, new OperatorLibraryValidator()), libraries, mapper),
                projection, mapper);
        service.upsertLibrary(mapper.valueToTree(Map.of(
                "libraryYaml", boundLibraryYaml(), "idempotencyKey", "bound-lib-1")), identity());

        Map<String, Object> composed = service.compose(mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "graph", Map.of("dsl", shippingDsl()),
                "libraryRefs", List.of("shipping"), "idempotencyKey", "bound-compose-1")),
                "toolRef", "TOOL", identity());

        assertThat(composed).containsEntry("speccing", false).containsEntry("executable", true);
        assertThat(drafts.find("shipping-tool").orElseThrow().operatorSnapshots().get("quote")
                .lowering().mode()).isEqualTo("resource-descriptor");
    }

    @Test
    void composeCannotReplaceAnotherTenantDraftWithTheSameReference() {
        Fixture fixture = fixtureWithTool();
        IntegrationRequestContext otherTenant = new IntegrationRequestContext(
                "other-tenant", "org-b", "project-b", "local", "sg", "WORKLOAD", "agent-2",
                "", "AGENT_TDD_DRAFT_WRITE", "corr-2");
        JsonNode response = mapper.valueToTree(fixture.tools().invoke("rg.tool.compose", mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "libraryRefs", List.of("shipping"),
                "graph", Map.of("sourceId", "shipping.bloge", "dsl", shippingDsl()),
                "idempotencyKey", "cross-tenant-compose")), otherTenant));

        assertThat(response.path("ok").asBoolean()).isFalse();
        assertThat(response.path("error").path("code").asText()).isEqualTo("DRAFT_NOT_FOUND");
        assertThat(fixture.drafts().find("shipping-tool")).hasValueSatisfying(draft ->
                assertThat(draft.tenantId()).isEqualTo("demo-tenant"));
    }

    private Fixture fixtureWithTool() {
        Fixture fixture = fixture();
        invoke(fixture, "rg.library.upsert", mapper.valueToTree(Map.of(
                "libraryYaml", minimalLibraryYaml(), "idempotencyKey", "lib-1")));
        invoke(fixture, "rg.tool.compose", mapper.valueToTree(Map.of(
                "toolRef", "shipping-tool", "libraryRefs", List.of("shipping"),
                "graph", Map.of("sourceId", "shipping.bloge", "dsl", shippingDsl()),
                "idempotencyKey", "compose-1")));
        return fixture;
    }

    private Fixture fixture() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        var catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(), libraries, null);
        DslImportService projection = new DslImportService(catalog, new OperatorLibraryValidator());
        VisualGraphSimulationService simulation = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(),
                new DynamicGatewayComposerVisualDslRunner(new DefaultOperatorRegistry()));
        AuthoringPreviewService authoring = new AuthoringPreviewService(
                new AuthoringCompiler(mapper, new OperatorLibraryValidator()), libraries, mapper);
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                libraries, drafts, mapper, projection, simulation,
                states, authoring);
        return new Fixture(tools, libraries, drafts, states);
    }

    private JsonNode invoke(Fixture fixture, String name, JsonNode arguments) {
        return mapper.valueToTree(fixture.tools().invoke(name, arguments, identity()));
    }

    private static String minimalLibraryYaml() {
        return """
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: { id: shipping, name: Shipping, version: 0.1.0, owner: logistics }
                defaults: { operatorVersion: 0.1.0, namespace: shipping }
                operators:
                  shipping:quote:
                    name: Quote
                    archetype: resource-read
                    requiresSecrets: false
                    input: { orderId: string }
                    output: { fee: number }
                """;
    }

    private static String boundLibraryYaml() {
        return """
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: { id: shipping, name: Shipping, version: 0.1.0, owner: logistics }
                defaults: { operatorVersion: 0.1.0, namespace: shipping }
                operators:
                  shipping:quote:
                    name: Quote
                    archetype: resource-read
                    requiresSecrets: false
                    input: { orderId: string }
                    output: { fee: number }
                    runtime: { bindingRef: "resource:shipping-service.quote" }
                """;
    }

    private static String shippingDsl() {
        return """
                graph shippingQuote {
                  input { orderId: String }
                  output { fee: Decimal }
                  node quote : "shipping:quote" {
                    input { orderId = ctx.orderId }
                  }
                  transform response { fee = quote.output.fee }
                }
                """;
    }

    private static String shippingDslWithRegion() {
        return """
                graph shippingQuote {
                  input { orderId: String region: String }
                  output { fee: Decimal }
                  node quote : "shipping:quote" {
                    input { orderId = ctx.orderId }
                  }
                  transform response { fee = quote.output.fee }
                }
                """;
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "demo-tenant", "org-a", "project-a", "local", "sg", "WORKLOAD", "agent-1",
                "", "AGENT_TDD_DRAFT_WRITE", "corr-1");
    }

    private static GraphDraft decisionDraft() {
        return new GraphDraft(GraphDraft.SCHEMA_VERSION, "policy-tool", 1, "Policy",
                "demo-tenant", "project-a", "local", GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode("policy", "bloge:decisionTable", "Policy",
                        Map.of(), Map.of("rules", List.of(Map.of("id", "R1",
                                "conditions", Map.of("seconds", "seconds <= 120"),
                                "output", Map.of("decision", "WAIVE")))), null)),
                List.of(), Map.of(), Map.of(), new GraphDraft.OutputSelection("policy", ""),
                Map.of(), Map.of(), GraphDraft.RevisionMetadata.empty());
    }

    private record Fixture(ResourceGatewayAgentTddTools tools,
                           InMemoryOperatorLibraryRegistry libraries,
                           InMemoryGraphDraftRepository drafts,
                           InMemoryAgentTddStateRepository states) { }
}
