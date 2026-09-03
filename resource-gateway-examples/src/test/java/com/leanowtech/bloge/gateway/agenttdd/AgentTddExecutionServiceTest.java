package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visualadapter.DynamicGatewayComposerVisualDslRunner;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualProductionAdmissionPolicy;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visualadapter.VisualSimulationKernelAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies library-aware compilation and the zero-egress red-side execution seam. */
class AgentTddExecutionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void previewsDslAgainstExplicitDesignOnlyLibraryWithoutRuntimeBinding() {
        Fixture fixture = fixture();
        JsonNode arguments = mapper.valueToTree(Map.of(
                "source", Map.of("sourceId", "eligibility.bloge", "dsl", eligibilityDsl()),
                "libraryRefs", List.of("risk")));

        JsonNode response = mapper.valueToTree(fixture.tools().invoke("rg.dsl.preview", arguments, identity()));

        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(response.path("data").path("projection").path("coverage")
                .path("missingOperatorCount").asInt()).isZero();
        assertThat(response.path("data").path("speccing").asBoolean())
                .as(response.toPrettyString()).isTrue();
        assertThat(response.path("data").path("libraryRefs").get(0).asText()).isEqualTo("risk");
    }

    @Test
    void runsRedSideWithExactFixtureAndProvesZeroExternalCalls() {
        Fixture fixture = fixture();
        GraphDraft draft = fixture.projection().preview(new com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest(
                "eligibility.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        fixture.drafts().save(draft.withIdentity("risk-tool", 0));
        JsonNode arguments = mapper.valueToTree(Map.of(
                "toolRef", "risk-tool",
                "libraryRefs", List.of("risk"),
                "side", "RED",
                "cases", Map.of("rows", List.of(Map.of(
                        "caseId", "g1",
                        "layer", "contract",
                        "given", Map.of("score", 720, "amount", 100),
                        "stubs", Map.of("eligibility", Map.of("eligible", true, "ruleId", "R1")),
                        "expect", Map.of("eligible", true, "ruleId", "R1"))))));

        JsonNode response = mapper.valueToTree(fixture.tools().invoke("rg.simulate", arguments, identity()));

        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(response.path("data").path("side").asText()).isEqualTo("RED");
        assertThat(response.path("data").path("realExternalCalls").asInt()).isZero();
        assertThat(response.path("data").path("cases").get(0).path("verdict").asText())
                .as(response.toPrettyString())
                .isEqualTo("RED_PASS");
        assertThat(response.path("data").path("cases").get(0).path("mockedNodeIds"))
                .anySatisfy(node -> assertThat(node.asText()).isEqualTo("eligibility"));
    }

    @Test
    void compilesDelayDependencyBehaviorIntoTheIsolatedTestingKernel() {
        Fixture fixture = fixture();
        GraphDraft draft = fixture.projection().preview(new com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest(
                "eligibility.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        fixture.drafts().save(draft.withIdentity("risk-tool", 0));
        JsonNode arguments = mapper.valueToTree(Map.of(
                "toolRef", "risk-tool", "libraryRefs", List.of("risk"), "side", "RED",
                "cases", Map.of("rows", List.of(Map.of(
                        "caseId", "delay-1", "given", Map.of("score", 720, "amount", 100),
                        "stubs", Map.of("eligibility", Map.of(
                                "behavior", "DELAY", "afterMillis", 5,
                                "value", Map.of("eligible", true, "ruleId", "R1"))),
                        "expect", Map.of("eligible", true, "ruleId", "R1"))))));

        JsonNode result = mapper.valueToTree(fixture.tools().invoke("rg.simulate", arguments, identity()));

        assertThat(result.path("data").path("cases").get(0).path("verdict").asText())
                .isEqualTo("RED_PASS");
        assertThat(result.path("data").path("realExternalCalls").asInt()).isZero();
    }

    @Test
    void rejectsMalformedReplayBeforeGraphExecution() {
        assertThatThrownBy(() -> AgentTddExecutionService.dependencyFixture(mapper,
                mapper.valueToTree(Map.of("behavior", "REPLAY", "replayRef", "latest", "value", Map.of()))))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("SCHEMA_NONCONFORMANT"));
    }

    @Test
    void blocksGreenSideWhileAnyReferencedOperatorIsDesignOnly() {
        Fixture fixture = fixture();
        GraphDraft draft = fixture.projection().preview(new com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest(
                "eligibility.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        fixture.drafts().save(draft.withIdentity("risk-tool", 0));
        JsonNode arguments = mapper.valueToTree(Map.of(
                "toolRef", "risk-tool", "libraryRefs", List.of("risk"), "side", "GREEN",
                "cases", Map.of("rows", List.of(Map.of("caseId", "g1", "given", Map.of(),
                        "stubs", Map.of(), "expect", Map.of())))));

        JsonNode response = mapper.valueToTree(fixture.tools().invoke("rg.simulate", arguments, identity()));

        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(response.path("data").path("cases").get(0).path("verdict").asText())
                .as(response.toPrettyString())
                .isEqualTo("GREEN_BLOCKED");
        assertThat(response.path("data").path("cases").get(0).path("reasonCode").asText())
                .isEqualTo("SPECCING_NOT_EXECUTABLE");
        assertThat(response.path("data").path("realExternalCalls").asInt()).isZero();
    }

    @Test
    void rejectsAReferencedLibraryThatDoesNotExistBeforeCompilation() {
        Fixture fixture = fixture();
        JsonNode arguments = mapper.valueToTree(Map.of(
                "source", Map.of("sourceId", "eligibility.bloge", "dsl", eligibilityDsl()),
                "libraryRefs", List.of("missing")));

        JsonNode response = mapper.valueToTree(fixture.tools().invoke("rg.dsl.preview", arguments, identity()));

        assertThat(response.path("ok").asBoolean()).isFalse();
        assertThat(response.path("error").path("code").asText()).isEqualTo("LIBRARY_NOT_FOUND");
    }

    @Test
    void gatePublishesFourDimensionalProofLimits() {
        Fixture fixture = fixture();
        JsonNode arguments = mapper.valueToTree(Map.of(
                "source", Map.of("sourceId", "simple.bloge", "dsl", """
                        graph simple {
                          input { value: String }
                          transform result { value = ctx.value }
                        }
                        """),
                "libraryRefs", List.of()));

        JsonNode response = mapper.valueToTree(fixture.tools().invoke("rg.gate.check", arguments, identity()));

        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(response.path("data").path("accepted").asBoolean()).isTrue();
        assertThat(response.path("data").path("honestVerdict").path("dimensions")).hasSize(4);
        assertThat(response.path("data").path("honestVerdict").path("dimensions").get(1)
                .path("status").asText()).isEqualTo("NOT_PROVEN");
    }

    @Test
    void baselineProvesStableBusinessFingerprintWithoutEgress() {
        Fixture fixture = fixture();
        GraphDraft draft = fixture.projection().preview(new com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest(
                "eligibility.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        fixture.drafts().save(draft.withIdentity("risk-tool", 0));
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        states.save(AgentTddMutationService.scopeKey(identity()), AgentTddMutationService.CASE_SET, "golden-1",
                mapper.valueToTree(Map.of("toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE",
                        "given", Map.of("score", 720, "amount", 100),
                        "stubs", Map.of("eligibility", Map.of("eligible", true, "ruleId", "R1")),
                        "expect", Map.of("eligible", true, "ruleId", "R1"))))));
        AgentTddExecutionService service = new AgentTddExecutionService(
                fixture.libraries(), fixture.drafts(), fixture.projection(), fixture.simulation(), mapper, states);

        Map<String, Object> result = service.baseline(mapper.valueToTree(Map.of(
                "toolRef", "risk-tool", "libraryRefs", List.of("risk"), "caseSetRef", "golden-1",
                "side", "RED", "rounds", 3)), identity());

        assertThat(result).containsEntry("status", "GO")
                .containsEntry("side", "RED")
                .containsEntry("caseSetRef", "golden-1")
                .containsEntry("businessFingerprintStable", true)
                .containsEntry("realExternalCalls", 0);
        assertThat(result.get("rounds")).asList().hasSize(3);
    }

    @Test
    void baselineLoadsOnlyApprovedActiveRowsFromDurableCaseSet() {
        Fixture fixture = fixture();
        GraphDraft draft = fixture.projection().preview(new com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest(
                "eligibility.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        fixture.drafts().save(draft.withIdentity("risk-tool", 0));
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        states.save(AgentTddMutationService.scopeKey(identity()), AgentTddMutationService.CASE_SET, "golden-1",
                mapper.valueToTree(Map.of("toolRef", "risk-tool", "rows", List.of(
                        Map.of("caseId", "g1", "lifecycle", "ACTIVE", "given", Map.of("score", 720, "amount", 100),
                                "stubs", Map.of("eligibility", Map.of("eligible", true, "ruleId", "R1")),
                                "expect", Map.of("eligible", true, "ruleId", "R1")),
                        Map.of("caseId", "g2", "lifecycle", "DRAFT", "given", Map.of(),
                                "stubs", Map.of(), "expect", Map.of())))));
        AgentTddExecutionService service = new AgentTddExecutionService(
                fixture.libraries(), fixture.drafts(), fixture.projection(), fixture.simulation(), mapper, states);

        Map<String, Object> result = service.baseline(mapper.valueToTree(Map.of(
                "toolRef", "risk-tool", "libraryRefs", List.of("risk"),
                "caseSetRef", "golden-1", "side", "RED", "rounds", 2)), identity());

        assertThat(result).containsEntry("status", "GO").containsKey("goldenSetId");
    }

    @Test
    void simulateResolvesCaseSetRefFromTheDocumentedNestedCasesEnvelope() {
        Fixture fixture = fixture();
        GraphDraft draft = fixture.projection().preview(new com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest(
                "eligibility.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        fixture.drafts().save(draft.withIdentity("risk-tool", 0));
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        states.save(AgentTddMutationService.scopeKey(identity()), AgentTddMutationService.CASE_SET, "golden-1",
                mapper.valueToTree(Map.of("toolRef", "risk-tool", "rows", List.of(
                        Map.of("caseId", "g1", "lifecycle", "ACTIVE",
                                "given", Map.of("score", 720, "amount", 100),
                                "stubs", Map.of("eligibility", Map.of("eligible", true, "ruleId", "R1")),
                                "expect", Map.of("eligible", true, "ruleId", "R1"))))));
        AgentTddExecutionService service = new AgentTddExecutionService(
                fixture.libraries(), fixture.drafts(), fixture.projection(), fixture.simulation(), mapper, states);

        Map<String, Object> result = service.simulate(mapper.valueToTree(Map.of(
                "toolRef", "risk-tool", "libraryRefs", List.of("risk"), "side", "RED",
                "cases", Map.of("caseSetRef", "golden-1"))), identity());

        assertThat(result.get("cases")).asList().singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("caseId", "g1").containsEntry("verdict", "RED_PASS");
    }

    @Test
    void baselineRejectsInlineRowsThatCouldBypassTheApprovedGoldenSet() {
        Fixture fixture = fixture();
        GraphDraft draft = fixture.projection().preview(new com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest(
                "eligibility.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        fixture.drafts().save(draft.withIdentity("risk-tool", 0));
        AgentTddExecutionService service = new AgentTddExecutionService(
                fixture.libraries(), fixture.drafts(), fixture.projection(), fixture.simulation(), mapper,
                new InMemoryAgentTddStateRepository());

        assertThatThrownBy(() -> service.baseline(mapper.valueToTree(Map.of(
                "toolRef", "risk-tool", "libraryRefs", List.of("risk"), "caseSetRef", "missing",
                "side", "RED", "rounds", 1,
                "cases", Map.of("rows", List.of(Map.of(
                        "caseId", "g1", "given", Map.of(), "stubs", Map.of()))))), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("DRAFT_NOT_FOUND"));
    }

    @Test
    void approvedExecutionRejectsAnActiveRowWithoutAnExplicitOracle() {
        Fixture fixture = fixture();
        GraphDraft draft = fixture.projection().preview(new com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest(
                "eligibility.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        fixture.drafts().save(draft.withIdentity("risk-tool", 0));
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        states.save(AgentTddMutationService.scopeKey(identity()), AgentTddMutationService.CASE_SET, "invalid-golden",
                mapper.valueToTree(Map.of("toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE", "given", Map.of(), "stubs", Map.of())))));
        AgentTddExecutionService service = new AgentTddExecutionService(
                fixture.libraries(), fixture.drafts(), fixture.projection(), fixture.simulation(), mapper, states);

        assertThatThrownBy(() -> service.simulate(mapper.valueToTree(Map.of(
                "toolRef", "risk-tool", "libraryRefs", List.of("risk"), "side", "RED",
                "cases", Map.of("caseSetRef", "invalid-golden"))), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("SCHEMA_NONCONFORMANT"));
    }

    private Fixture fixture() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibrary risk = new OperatorLibrary(library.schemaVersion(), "risk", library.displayName(),
                library.version(), library.owner(), library.status(), library.builtInFunctions(), library.operators());
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(risk);
        var catalog = VisualCatalogTestSupport.catalogWithLibrary(risk);
        DslImportService projection = new DslImportService(catalog, new OperatorLibraryValidator());
        VisualGraphSimulationService simulation = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(),
                new DynamicGatewayComposerVisualDslRunner(new DefaultOperatorRegistry()),
                new VisualSimulationKernelAdapter(mapper), VisualProductionAdmissionPolicy.nonProductionTest());
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                libraries, drafts, mapper, projection, simulation);
        return new Fixture(tools, drafts, projection, libraries, simulation);
    }

    private static String eligibilityDsl() {
        return """
                graph eligibilityPolicy {
                  input {
                    score: Int
                    amount: Decimal
                  }
                  output {
                    eligible: Boolean
                    ruleId: String
                  }
                  node eligibility : "risk:eligibility" {
                    input {
                      score = ctx.score
                      amount = ctx.amount
                    }
                  }
                  transform response {
                    eligible = eligibility.output.eligible
                    ruleId = eligibility.output.ruleId
                  }
                }
                """;
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "demo-tenant", "org-a", "project-a", "local", "sg", "WORKLOAD", "agent-1",
                "", "AGENT_TDD_EXECUTION", "corr-1");
    }

    private record Fixture(ResourceGatewayAgentTddTools tools,
                           InMemoryGraphDraftRepository drafts,
                           DslImportService projection,
                           InMemoryOperatorLibraryRegistry libraries,
                           VisualGraphSimulationService simulation) { }
}
