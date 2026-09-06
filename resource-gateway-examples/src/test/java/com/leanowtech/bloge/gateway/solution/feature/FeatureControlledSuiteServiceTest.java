package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.agenttdd.InMemoryAgentTddStateRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixturePayloadProtector;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.DatabaseProtectedFixtureMaterialRepository;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Specifies the protected, zero-egress lifecycle of one Feature controlled test suite. */
class FeatureControlledSuiteServiceTest {
    private static final String FEATURE_REF = "cancel.withinFree";
    private static final String EVALUATION_REF = "graph:cancel-window-v1";
    private static final String EXECUTION_FINGERPRINT = "sha256:" + "e".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final SolutionEntityRegistry registry = new SolutionEntityRegistry(states, mapper);
    private JdbcTemplate jdbc;
    private FeatureControlledMaterialStore materials;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-material-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        FixtureMaterialService vault = new FixtureMaterialService(
                new DatabaseProtectedFixtureMaterialRepository(jdbc, mapper),
                AuthoringFixturePayloadProtector.fromConfiguration(
                        "feature-controlled", "feature-controlled=" + key), mapper);
        materials = new FeatureControlledMaterialStore(vault, mapper);
        registry.upsertFeature(scope(), designFeature("Whether the cancellation is within the free window."));
    }

    @Test
    void protectsTheWholeSuiteAndPersistsOnlyPayloadFreeCurrentEvidence() {
        FeatureControlledSuiteService service = service((request, identity) -> {
            assertThat(request.featureRef()).isEqualTo(FEATURE_REF);
            assertThat(request.evaluationRef()).isEqualTo(EVALUATION_REF);
            assertThat(request.libraryRefs()).containsExactly("lib:time-v1");
            assertThat(request.cases()).singleElement().satisfies(testCase -> {
                assertThat(testCase.givenInputs().toString()).contains("SECRET-ORDER-17");
                assertThat(testCase.expectedOutput()).isEqualTo(mapper.valueToTree(true));
            });
            return new FeatureControlledCaseRunner.RunResult(
                    EXECUTION_FINGERPRINT, 0,
                    List.of(new FeatureControlledCaseRunner.CaseResult(
                            "inside-window", "RED_PASS", List.of("node:within-window"))), 0);
        });

        FeatureControlledSuiteService.SuiteSummary draft = service.upsert(definition(0), author());
        FeatureControlledSuiteEvidence evidence = service.run(FEATURE_REF, draft.revision(), executor());

        assertThat(evidence.status()).isEqualTo("PASSED");
        assertThat(evidence.realExternalCalls()).isZero();
        assertThat(evidence.coverage()).satisfies(coverage -> {
            assertThat(coverage.targetsTotal()).isEqualTo(1);
            assertThat(coverage.targetsCovered()).isEqualTo(1);
            assertThat(coverage.percent()).isEqualTo(100);
        });
        AgentTddStoredAsset stored = states.find(
                scope(), FeatureControlledSuiteService.FEATURE_CONTROLLED_SUITE, FEATURE_REF).orElseThrow();
        assertThat(stored.data().toString())
                .contains("materialReceipt", "evidenceFingerprint", EXECUTION_FINGERPRINT)
                .doesNotContain("SECRET-ORDER-17", "SECRET-STUB-42", "inside-window",
                        "lib:time-v1", EVALUATION_REF);
        assertThat(jdbc.queryForObject(
                "SELECT protected_payload FROM rg_fixture_material_v2_revisions", String.class))
                .doesNotContain("SECRET-ORDER-17", "SECRET-STUB-42");
        assertThat(jdbc.queryForObject(
                "SELECT receipt_json FROM rg_fixture_material_v2_revisions", String.class))
                .doesNotContain("SECRET-ORDER-17", "SECRET-STUB-42");

        FeatureControlledSuiteEvidence current = service.requireCurrentEvidence(
                FEATURE_REF, EVALUATION_REF, evidence.evidenceFingerprint(), engineer());
        assertThat(current).isEqualTo(evidence);
    }

    @Test
    void failsClosedWhenTheRunnerReportsAnyExternalCall() {
        FeatureControlledSuiteService service = service((request, identity) ->
                new FeatureControlledCaseRunner.RunResult(
                        EXECUTION_FINGERPRINT, 0,
                        List.of(new FeatureControlledCaseRunner.CaseResult(
                                "inside-window", "RED_PASS", List.of("node:within-window"))), 1));
        FeatureControlledSuiteService.SuiteSummary draft = service.upsert(definition(0), author());

        FeatureControlledSuiteEvidence evidence = service.run(FEATURE_REF, draft.revision(), executor());

        assertThat(evidence.status()).isEqualTo("FAILED_CLOSED");
        assertThat(evidence.realExternalCalls()).isEqualTo(1);
        assertThatThrownBy(() -> service.requireCurrentEvidence(
                FEATURE_REF, EVALUATION_REF, evidence.evidenceFingerprint(), engineer()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FEATURE_SUITE_NOT_VERIFIED"));
    }

    @Test
    void rejectsStaleEvidenceAfterTheSuiteOrFeatureContractChanges() {
        FeatureControlledSuiteService service = service(passRunner());
        FeatureControlledSuiteService.SuiteSummary draft = service.upsert(definition(0), author());
        FeatureControlledSuiteEvidence evidence = service.run(FEATURE_REF, draft.revision(), executor());

        service.upsert(definition(evidence.suiteRevision()), author());
        assertThatThrownBy(() -> service.requireCurrentEvidence(
                FEATURE_REF, EVALUATION_REF, evidence.evidenceFingerprint(), engineer()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FEATURE_SUITE_EVIDENCE_STALE"));

        FeatureControlledSuiteService.SuiteSummary revised = service.summary(FEATURE_REF, reader());
        FeatureControlledSuiteEvidence current = service.run(FEATURE_REF, revised.revision(), executor());
        registry.upsertFeature(scope(), designFeature("Changed business meaning."));
        assertThatThrownBy(() -> service.requireCurrentEvidence(
                FEATURE_REF, EVALUATION_REF, current.evidenceFingerprint(), engineer()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FEATURE_SUITE_EVIDENCE_STALE"));
    }

    @Test
    void appliesAnExactRevisionFenceToSuiteUpdatesAndRuns() {
        FeatureControlledSuiteService service = service(passRunner());
        FeatureControlledSuiteService.SuiteSummary draft = service.upsert(definition(0), author());

        assertThatThrownBy(() -> service.upsert(definition(0), author()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
        assertThatThrownBy(() -> service.run(FEATURE_REF, draft.revision() + 1, executor()))
                .isInstanceOfSatisfying(AgentTddToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
    }

    private FeatureControlledSuiteService service(FeatureControlledCaseRunner runner) {
        FeatureControlledSuiteProperties properties = new FeatureControlledSuiteProperties();
        properties.setMinimumCoveragePercent(100);
        return new FeatureControlledSuiteService(states, registry, materials, runner, mapper, properties);
    }

    private FeatureControlledCaseRunner passRunner() {
        return (request, identity) -> new FeatureControlledCaseRunner.RunResult(
                EXECUTION_FINGERPRINT, 0,
                List.of(new FeatureControlledCaseRunner.CaseResult(
                        "inside-window", "RED_PASS", List.of("node:within-window"))), 0);
    }

    private FeatureControlledSuiteDefinition definition(long expectedRevision) {
        JsonNode behavior = mapper.valueToTree(Map.of(
                "behavior", "RETURN", "value", Map.of("marker", "SECRET-STUB-42")));
        return new FeatureControlledSuiteDefinition(
                FEATURE_REF, EVALUATION_REF, expectedRevision,
                List.of("lib:time-v1"), List.of("node:within-window"),
                List.of(new FeatureControlledSuiteDefinition.Case(
                        "inside-window", "Order inside free window",
                        mapper.valueToTree(Map.of("orderId", "SECRET-ORDER-17")),
                        List.of(new FeatureControlledSuiteDefinition.NodeBehavior("order-api", behavior)),
                        mapper.valueToTree(true), List.of("node:within-window"))));
    }

    private FeatureContract designFeature(String semantics) {
        return new FeatureContract(FEATURE_REF, mapper.valueToTree(Map.of("type", "boolean")),
                FeatureContract.EvaluationKind.DAG, FeatureContract.Determinism.DETERMINISTIC,
                mapper.valueToTree(Map.of("orderId", "string")), "", "", "", semantics);
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

    private static IntegrationRequestContext reader() {
        return identity("WORKLOAD", "AGENT_TDD_READ");
    }

    private static IntegrationRequestContext identity(String actorType, String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                actorType, actorType.toLowerCase() + "-1", "", purpose, "corr-suite");
    }

    private static String scope() {
        return AgentTddMutationService.scopeKey(author());
    }
}
