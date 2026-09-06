package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddExecutionService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.DatabaseProtectedFixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualProductionAdmissionPolicy;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visualadapter.DynamicGatewayComposerVisualDslRunner;
import com.leanowtech.bloge.gateway.visualadapter.VisualSimulationKernelAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the complete compose-to-suite-to-VERIFIED Feature path over the real rehearsal kernel. */
class FeatureControlledHandoffOperationalTest {
    private static final String FEATURE_REF = "risk-tool";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void realFeatureRehearsalEvidenceBindsTheFeatureWithoutExternalCalls() {
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
        registry.upsertFeature(scope(), new FeatureContract(
                FEATURE_REF, mapper.valueToTree(Map.of("type", "object")),
                FeatureContract.EvaluationKind.DAG, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("score", "integer", "amount", "decimal")),
                "", "", "", "Assess risk eligibility."));

        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        OperatorLibrary source = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibrary risk = new OperatorLibrary(source.schemaVersion(), "risk", source.displayName(),
                source.version(), source.owner(), source.status(), source.builtInFunctions(), source.operators());
        libraries.upsert(risk);
        VisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(risk);
        DslImportService projection = new DslImportService(catalog, new OperatorLibraryValidator());
        VisualGraphSimulationService simulation = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(),
                new DynamicGatewayComposerVisualDslRunner(new DefaultOperatorRegistry()),
                new VisualSimulationKernelAdapter(mapper), VisualProductionAdmissionPolicy.nonProductionTest());
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft projected = projection.preview(new DslImportPreviewRequest(
                "risk.bloge", eligibilityDsl(), List.of("risk"), List.of(), "test", Map.of())).draft();
        drafts.save(scoped(projected));
        AgentTddExecutionService execution = new AgentTddExecutionService(
                libraries, drafts, projection, simulation, mapper, states);

        FeatureControlledSuiteService suites = new FeatureControlledSuiteService(
                states, registry, materialStore(), FeatureControlledCaseRunner.using(execution, mapper),
                mapper, new FeatureControlledSuiteProperties());
        FeatureControlledSuiteService.SuiteSummary draft = suites.upsert(new FeatureControlledSuiteDefinition(
                FEATURE_REF, "graph:risk-v1", 0, List.of("risk"), List.of("node:eligibility"),
                List.of(new FeatureControlledSuiteDefinition.Case(
                        "eligible", "Eligible applicant is accepted",
                        mapper.valueToTree(Map.of("score", 720, "amount", 100)),
                        List.of(new FeatureControlledSuiteDefinition.NodeBehavior("eligibility",
                                mapper.valueToTree(Map.of("behavior", "RETURN", "value",
                                        Map.of("eligible", true, "ruleId", "R1"))))),
                        mapper.valueToTree(Map.of("eligible", true, "ruleId", "R1")),
                        List.of("node:eligibility")))), author());
        FeatureControlledSuiteEvidence evidence = suites.run(FEATURE_REF, draft.revision(), executor());
        AtomicInteger runtimeBackendCalls = new AtomicInteger();
        FeatureHandoffService handoff = new FeatureHandoffService(states, registry,
                (feature, inputs, identity) -> {
                    runtimeBackendCalls.incrementAndGet();
                    return mapper.nullNode();
                }, mapper, suites, new FeatureControlledSuiteProperties());
        handoff.submit(FEATURE_REF, author());

        Map<String, Object> fulfilled = handoff.fulfil(
                FEATURE_REF, "graph:risk-v1", evidence.evidenceFingerprint(), null, engineer());

        assertThat(evidence.status()).isEqualTo("PASSED");
        assertThat(evidence.realExternalCalls()).isZero();
        assertThat(fulfilled).containsEntry("status", "VERIFIED")
                .containsEntry("verificationMode", "CONTROLLED_SUITE");
        assertThat(runtimeBackendCalls).hasValue(0);
        assertThat(registry.requireFeature(scope(), FEATURE_REF).evaluationRef()).isEqualTo("graph:risk-v1");
    }

    private FeatureControlledMaterialStore materialStore() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-material-schema.sql")).execute(database);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        FixtureMaterialService vault = new FixtureMaterialService(
                new DatabaseProtectedFixtureMaterialRepository(new JdbcTemplate(database), mapper),
                AuthoringFixturePayloadProtector.fromConfiguration(
                        "feature-suite-operational", "feature-suite-operational=" + key), mapper);
        return new FeatureControlledMaterialStore(vault, mapper);
    }

    private static String eligibilityDsl() {
        return """
                graph eligibilityPolicy {
                  input { score: Int amount: Decimal }
                  output { eligible: Boolean ruleId: String }
                  node eligibility : "risk:eligibility" {
                    input { score = ctx.score amount = ctx.amount }
                  }
                  transform response {
                    eligible = eligibility.output.eligible
                    ruleId = eligibility.output.ruleId
                  }
                }
                """;
    }

    private static GraphDraft scoped(GraphDraft draft) {
        return new GraphDraft(draft.schemaVersion(), FEATURE_REF, 0, draft.graphName(),
                executor().tenantId(), executor().projectId(), executor().environmentId(), draft.status(),
                draft.inputSchema(), draft.outputSchema(), draft.nodes(), draft.edges(), draft.visualLayout(),
                draft.nodeFixtures(), draft.output(), draft.operatorFingerprints(), draft.operatorSnapshots(),
                draft.revisionMetadata());
    }

    private static IntegrationRequestContext author() {
        return identity("WORKLOAD", "AGENT_TDD_AUTHORING");
    }

    private static IntegrationRequestContext executor() {
        return identity("WORKLOAD", "AGENT_TDD_EXECUTION");
    }

    private static IntegrationRequestContext engineer() {
        return identity("USER", "AGENT_TDD_FEATURE_ENG");
    }

    private static IntegrationRequestContext identity(String actorType, String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                actorType, actorType.toLowerCase() + "-1", "", purpose, "corr-operational");
    }

    private static String scope() {
        return AgentTddMutationService.scopeKey(author());
    }
}
