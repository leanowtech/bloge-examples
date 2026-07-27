package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.ScenarioOperatorTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies durable Scenario authoring storage, optimistic concurrency, scope, and safety policy.
 */
class ScenarioDraftSetPersistenceTest {

    private static final int MAX_TARGET_BYTES = 16 * 1_048_576;

    private ObjectMapper objectMapper;
    private JdbcTemplate jdbc;
    private DatabaseScenarioDraftSetRepository repository;
    private InMemoryGraphDraftRepository graphDrafts;
    private ContractDraftProjectionService contracts;
    private ScenarioDraftSetAuthoringService service;
    private GraphDraft graph;
    private ContractDraft contract;
    private OperatorDefinition operator;
    private VisualOperatorCatalog operators;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        repository = new DatabaseScenarioDraftSetRepository(jdbc, objectMapper);
        repository.init();
        graphDrafts = new InMemoryGraphDraftRepository();
        contracts = new ContractDraftProjectionService();
        graph = graphDrafts.save(ScenarioValidationServiceTest.graphDraft());
        String targetFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, graph, MAX_TARGET_BYTES);
        contract = contracts.project(graph, targetFingerprint);
        operator = ScenarioOperatorTestSupport.operator();
        operators = ScenarioOperatorTestSupport.catalog(operator);
        service = new ScenarioDraftSetAuthoringService(
                repository,
                graphDrafts,
                operators,
                contracts,
                new ScenarioValidationService(objectMapper),
                objectMapper);
    }

    @Test
    void storesAndReloadsAnIntegrityAddressedRevision() {
        StoredScenarioDraftSet stored = service.save(
                "loan-scenarios", 0, draftSet(scope(), Map.of("applicantId", "A-1")), identity());

        assertThat(stored.revision()).isEqualTo(1);
        assertThat(stored.fingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(stored.savedBy()).isEqualTo("author-a");
        assertThat(stored.draftSet().metadata().owner()).isEqualTo("credit-platform");
        assertThat(service.find("loan-scenarios", identity())).isEqualTo(stored);
        assertThat(repository.findContractBaseline(scope(), "loan-scenarios", 1))
                .get()
                .extracting(ScenarioContractBaseline::contractFingerprint)
                .isEqualTo(contract.fingerprint(objectMapper));
    }

    @Test
    void projectsTheAuthoritativeStoredGraphContractCoordinate() {
        ScenarioContractProjection projection =
                service.projectGraphContract(graph.draftId(), identity());

        assertThat(projection.scope()).isEqualTo(scope());
        assertThat(projection.contract()).isEqualTo(contract);
        assertThat(projection.contractFingerprint()).isEqualTo(contract.fingerprint(objectMapper));
        assertThat(projection.contract().target().fingerprint())
                .matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void projectsAndPersistsAnAuthoritativeOperatorScenario() {
        ContractDraft operatorContract = contracts.project(operator);
        ScenarioContractProjection projection =
                service.projectOperatorContract(operator.operatorRef(), identity());

        assertThat(projection.contract()).isEqualTo(operatorContract);
        assertThat(projection.contract().target().kind())
                .isEqualTo(ContractDraft.TargetKind.OPERATOR);
        assertThat(projection.contract().inputSchema().required())
                .containsExactly("applicantId");
        assertThat(projection.contract().outputSchema().properties())
                .containsKeys("decision", "score");

        StoredScenarioDraftSet stored = service.save(
                "risk-operator-scenarios",
                0,
                operatorDraftSet(operatorContract),
                identity());

        assertThat(stored.draftSet().target()).isEqualTo(operatorContract.target());
        assertThat(stored.draftSet().contractFingerprint())
                .isEqualTo(operatorContract.fingerprint(objectMapper));
    }

    @Test
    void rejectsNamespaceRestrictedOperatorWhenScenarioHasNoNamespaceCoordinate() {
        OperatorDefinition restricted = new OperatorDefinition(
                operator.schemaVersion(),
                operator.operatorRef(),
                operator.operatorVersion(),
                operator.display(),
                operator.source(),
                operator.ports(),
                operator.configSchema(),
                operator.capabilities(),
                new OperatorDefinition.Policy(
                        List.of("tenant-a"), List.of("credit-private"), List.of("test")),
                operator.lowering(),
                List.of());
        ScenarioDraftSetAuthoringService restrictedService =
                new ScenarioDraftSetAuthoringService(
                        repository,
                        graphDrafts,
                        ScenarioOperatorTestSupport.catalog(restricted),
                        contracts,
                        new ScenarioValidationService(objectMapper),
                        objectMapper);

        assertThatThrownBy(() -> restrictedService.projectOperatorContract(
                restricted.operatorRef(), identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.TARGET_SCOPE_MISMATCH"));
    }

    @Test
    void retainsHistoryAndRejectsSilentLastWriteWins() {
        StoredScenarioDraftSet first = service.save(
                "loan-scenarios", 0, draftSet(scope(), Map.of("applicantId", "A-1")), identity());
        ScenarioDraftSet edited = withScenarioName(first.draftSet(), "Applicant approved");
        StoredScenarioDraftSet second = service.save(
                "loan-scenarios", first.revision(), edited, identity());

        assertThat(second.revision()).isEqualTo(2);
        assertThat(service.revisions("loan-scenarios", identity()))
                .extracting(StoredScenarioDraftSet::revision)
                .containsExactly(2L, 1L);
        assertThatThrownBy(() -> service.save(
                "loan-scenarios", first.revision(), edited, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.REVISION_CONFLICT"));
    }

    @Test
    void survivesRepositoryReinitializationAndReverifiesFingerprint() {
        StoredScenarioDraftSet stored = service.save(
                "loan-scenarios", 0, draftSet(scope(), Map.of("applicantId", "A-1")), identity());

        DatabaseScenarioDraftSetRepository reloaded =
                new DatabaseScenarioDraftSetRepository(jdbc, objectMapper);
        reloaded.init();

        assertThat(reloaded.find(scope(), "loan-scenarios")).contains(stored);
        assertThat(reloaded.revisions(scope(), "loan-scenarios")).containsExactly(stored);
        assertThat(reloaded.findContractBaseline(scope(), "loan-scenarios", stored.revision()))
                .get()
                .extracting(ScenarioContractBaseline::contract)
                .isEqualTo(contract);
    }

    @Test
    void exposesAnUnchangedCompatibilityReportForTheCapturedContract() {
        StoredScenarioDraftSet stored = service.save(
                "loan-scenarios", 0, draftSet(scope(), Map.of("applicantId", "A-1")), identity());

        ContractCompatibilityReport report =
                service.compatibility("loan-scenarios", stored.revision(), identity());

        assertThat(report.classification())
                .isEqualTo(ContractCompatibilityReport.Classification.UNCHANGED);
        assertThat(report.scenarioRevision()).isEqualTo(stored.revision());
        assertThat(report.reportFingerprint()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void isolatesTheSameAssetIdAcrossCompleteEnterpriseScopes() {
        ScenarioDraftSet.EnterpriseScope otherScope =
                new ScenarioDraftSet.EnterpriseScope("tenant-b", "org-b", "project-b", "test", "us");
        StoredScenarioDraftSet first = repository.saveIfRevision(
                0, draftSet(scope(), Map.of("applicantId", "A-1")), "author-a").orElseThrow();
        StoredScenarioDraftSet second = repository.saveIfRevision(
                0, draftSet(otherScope, Map.of("applicantId", "B-1")), "author-b").orElseThrow();

        assertThat(repository.find(scope(), "loan-scenarios")).contains(first);
        assertThat(repository.find(otherScope, "loan-scenarios")).contains(second);
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
    }

    @Test
    void rejectsBodyScopeThatDoesNotMatchVerifiedIdentity() {
        ScenarioDraftSet.EnterpriseScope other =
                new ScenarioDraftSet.EnterpriseScope("tenant-b", "org-a", "project-a", "test", "sg");

        assertThatThrownBy(() -> service.save(
                "loan-scenarios", 0, draftSet(other, Map.of("applicantId", "A-1")), identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.SCOPE_MISMATCH"));
        assertThat(repository.find(scope(), "loan-scenarios")).isEmpty();
    }

    @Test
    void rejectsStaleTargetAndContractCoordinatesBeforePersistence() {
        ScenarioDraftSet valid = draftSet(scope(), Map.of("applicantId", "A-1"));
        ScenarioDraftSet stale = new ScenarioDraftSet(
                valid.schemaVersion(),
                valid.scenarioDraftSetId(),
                valid.revision(),
                valid.scope(),
                new ContractDraft.Target(
                        valid.target().kind(),
                        valid.target().id(),
                        valid.target().revision(),
                        ScenarioValidationServiceTest.fingerprint('f')),
                valid.contractFingerprint(),
                valid.scenarios(),
                valid.metadata());

        assertThatThrownBy(() -> service.save("loan-scenarios", 0, stale, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    IntegrationProblemException problem = (IntegrationProblemException) failure;
                    assertThat(problem.problem().code()).isEqualTo("RG.SCENARIO.VALIDATION_FAILED");
                    assertThat(problem.problem().details().get("diagnosticCodes").toString())
                            .contains("visual.scenario.target.contractMismatch");
                });
    }

    @Test
    void rejectsRawSecretWithoutEchoingItsValue() {
        String secret = "sk-scenarioSecretValue123456";
        ScenarioDraftSet unsafe = draftSet(scope(), Map.of(
                "applicantId", "A-1",
                "apiKey", secret));

        assertThatThrownBy(() -> service.save("loan-scenarios", 0, unsafe, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .hasMessageNotContaining(secret)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.RAW_SECRET_FORBIDDEN"));
    }

    @Test
    void rejectsProductionIdentityEvenWhenBodyClaimsTestScope() {
        IntegrationRequestContext production = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "production", "sg",
                "WORKLOAD", "author-a", "", "TEST_SUITE_WRITE", "correlation-a",
                java.util.Set.of(), "RESTRICTED", "");

        assertThatThrownBy(() -> service.save(
                "loan-scenarios", 0, draftSet(scope(), Map.of("applicantId", "A-1")), production))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(
                        ((IntegrationProblemException) failure).problem().code())
                        .isEqualTo("RG.SCENARIO.ENVIRONMENT_FORBIDDEN"));
    }

    private ScenarioDraftSet draftSet(
            ScenarioDraftSet.EnterpriseScope enterpriseScope,
            Map<String, Object> input) {
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "approved",
                "Approved applicant",
                "Controlled CRM response produces an approval.",
                ScenarioDraftSet.CaseType.GOLDEN,
                List.of("loan"),
                new ScenarioDraftSet.Given(input, ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(new ScenarioDraftSet.DependencyBehaviorDraft(
                        "crm-return",
                        ScenarioDraftSet.DependencySelector.node("crm"),
                        ScenarioDraftSet.DependencyBehavior.returning(Map.of("score", 720)),
                        ScenarioDraftSet.Consumption.once(),
                        ScenarioDraftSet.SchemaCheck.strict(),
                        "AUTHORED")),
                new ScenarioDraftSet.Then(List.of(new ScenarioDraftSet.AssertionDraft(
                        "decision-approved",
                        ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                        "",
                        "",
                        "",
                        "/decision",
                        ScenarioDraftSet.AssertionOperator.EQUALS,
                        "APPROVED",
                        null))));
        return new ScenarioDraftSet(
                "",
                "loan-scenarios",
                0,
                enterpriseScope,
                contract.target(),
                contract.fingerprint(objectMapper),
                List.of(scenario),
                new ScenarioDraftSet.Metadata(
                        "credit-platform", "INTERNAL", null, null, Map.of("source", "test")));
    }

    private ScenarioDraftSet operatorDraftSet(ContractDraft operatorContract) {
        ScenarioDraftSet.ScenarioDraft scenario = new ScenarioDraftSet.ScenarioDraft(
                "approved",
                "Risk score approves applicant",
                "Exercises the operator as an independently governed target.",
                ScenarioDraftSet.CaseType.GOLDEN,
                List.of("operator", "risk"),
                new ScenarioDraftSet.Given(
                        Map.of("applicantId", "A-1"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(),
                new ScenarioDraftSet.Then(List.of(new ScenarioDraftSet.AssertionDraft(
                        "decision-approved",
                        ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                        "", "", "", "/decision",
                        ScenarioDraftSet.AssertionOperator.EQUALS,
                        "APPROVED", null))));
        return new ScenarioDraftSet(
                "",
                "risk-operator-scenarios",
                0,
                scope(),
                operatorContract.target(),
                operatorContract.fingerprint(objectMapper),
                List.of(scenario),
                new ScenarioDraftSet.Metadata(
                        "credit-platform", "INTERNAL", null, null, Map.of("source", "test")));
    }

    private static ScenarioDraftSet withScenarioName(ScenarioDraftSet source, String name) {
        ScenarioDraftSet.ScenarioDraft current = source.scenarios().getFirst();
        ScenarioDraftSet.ScenarioDraft changed = new ScenarioDraftSet.ScenarioDraft(
                current.scenarioId(), name, current.description(), current.caseType(), current.tags(),
                current.given(), current.dependencies(), current.then());
        return new ScenarioDraftSet(
                source.schemaVersion(), source.scenarioDraftSetId(), source.revision(),
                source.scope(), source.target(), source.contractFingerprint(),
                List.of(changed), source.metadata());
    }

    private static ScenarioDraftSet.EnterpriseScope scope() {
        return new ScenarioDraftSet.EnterpriseScope(
                "tenant-a", "org-a", "project-a", "test", "sg");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "author-a", "", "TEST_SUITE_WRITE", "correlation-a",
                java.util.Set.of(), "RESTRICTED", "");
    }
}
