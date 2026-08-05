package com.leanowtech.bloge.gateway.authoring.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.DatabaseScenarioDraftSetRepository;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSetAuthoringService;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioValidationService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.ScenarioOperatorTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.DatabaseGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceForkServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private GraphDraftRepository graphs;
    private DatabaseScenarioDraftSetRepository scenarios;
    private WorkspaceForkService service;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DatabaseGraphDraftRepository databaseGraphs = new DatabaseGraphDraftRepository(jdbc, mapper);
        databaseGraphs.init();
        graphs = databaseGraphs;
        scenarios = new DatabaseScenarioDraftSetRepository(jdbc, mapper);
        scenarios.init();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        OperatorDefinition operator = ScenarioOperatorTestSupport.operator();
        VisualOperatorCatalog catalog = ScenarioOperatorTestSupport.catalog(operator);
        ContractDraftProjectionService contracts = new ContractDraftProjectionService();
        ScenarioDraftSetAuthoringService scenarioAuthoring = new ScenarioDraftSetAuthoringService(
                scenarios,
                graphs,
                catalog,
                contracts,
                new ScenarioValidationService(mapper),
                mapper);
        service = new WorkspaceForkService(
                graphs,
                new GraphDraftValidator(catalog),
                catalog,
                contracts,
                scenarioAuthoring,
                new InMemoryWorkspaceForkReceiptRepository(),
                mapper);
    }

    @Test
    void atomicallyForksAndRebindsACompleteSeed() {
        WorkspaceForkReceipt receipt = transactions.execute(status ->
                service.fork("first-loan-demo", command(validScenarios()), identity()));

        GraphDraft graph = graphs.find(receipt.graphCoordinate().draftId()).orElseThrow();
        ScenarioDraftSet stored = scenarios.find(scope(), receipt.scenarioSuiteCoordinates().getFirst().id())
                .orElseThrow().draftSet();

        assertThat(receipt.workspaceId()).isNotBlank();
        assertThat(receipt.graphCoordinate().revision()).isEqualTo(1);
        assertThat(receipt.graphCoordinate().fingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(receipt.contractCoordinate().target()).isEqualTo(stored.target());
        assertThat(receipt.contractCoordinate().fingerprint()).isEqualTo(stored.contractFingerprint());
        assertThat(stored.target().id()).isEqualTo(graph.draftId());
        assertThat(stored.target().revision()).isEqualTo(graph.revision());
        assertThat(stored.scenarios()).extracting(ScenarioDraftSet.ScenarioDraft::caseType)
                .containsExactly(
                        ScenarioDraftSet.CaseType.GOLDEN,
                        ScenarioDraftSet.CaseType.NEGATIVE,
                        ScenarioDraftSet.CaseType.BOUNDARY);
        assertThat(receipt.runtimeProfile()).isEqualTo("SANDBOX_MOCK");
        assertThat(receipt.proofStrength()).isEqualTo("EXPLORATORY");
        assertThat(receipt.replayed()).isFalse();
    }

    @Test
    void returnsTheSameReceiptForAnIdempotentRetryWithoutDuplicatingAssets() {
        WorkspaceForkCommand request = command(validScenarios());

        WorkspaceForkReceipt first = service.fork("same-request", request, identity());
        WorkspaceForkReceipt replay = service.fork("same-request", request, identity());

        assertThat(replay.workspaceId()).isEqualTo(first.workspaceId());
        assertThat(replay.graphCoordinate()).isEqualTo(first.graphCoordinate());
        assertThat(replay.scenarioSuiteCoordinates()).isEqualTo(first.scenarioSuiteCoordinates());
        assertThat(replay.replayed()).isTrue();
        assertThat(graphs.all()).hasSize(1);
        assertThat(scenarios.find(scope(), first.scenarioSuiteCoordinates().getFirst().id())).isPresent();
    }

    @Test
    void removesTheGraphWhenScenarioPersistenceFails() {
        ScenarioDraftSet.ScenarioDraft invalid = new ScenarioDraftSet.ScenarioDraft(
                "invalid-input",
                "Invalid input",
                "Exercises rollback after Graph persistence.",
                ScenarioDraftSet.CaseType.NEGATIVE,
                List.of("rollback"),
                new ScenarioDraftSet.Given(Map.of("applicantId", 42),
                        ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(),
                ScenarioDraftSet.Then.empty());

        assertThatThrownBy(() -> service.fork(
                "invalid-request", command(List.of(invalid)), identity()))
                .isInstanceOf(RuntimeException.class);
        assertThat(graphs.all()).isEmpty();
    }

    private WorkspaceForkCommand command(List<ScenarioDraftSet.ScenarioDraft> cases) {
        WorkspaceSeedBundle seed = new WorkspaceSeedBundle(
                "",
                new WorkspaceSeedBundle.TemplateIdentity("loan-policy-fallback", "1.0.0", "Loan policy fallback"),
                graph(),
                List.of(new ScenarioDraftSet(
                        "",
                        "loan-policy-fallback-scenarios",
                        0,
                        scope(),
                        null,
                        "",
                        cases,
                        new ScenarioDraftSet.Metadata(
                                "credit-platform", "INTERNAL", null, null,
                                Map.of("source", "workspace-seed")))),
                List.of("inline:scenario-dependencies"),
                new WorkspaceSeedBundle.RuntimeProfile(
                        "SANDBOX_MOCK", true, false, List.of("risk:score")),
                "EXPLORATORY",
                List.of("SANDBOX_RUN", "DURABLE_FORK"),
                List.of());
        return new WorkspaceForkCommand("", seed, "Loan policy demo", "author-canvas");
    }

    private GraphDraft graph() {
        OperatorDefinition operator = ScenarioOperatorTestSupport.operator();
        return new GraphDraft(
                "", "", 0, "loanPolicyDemo", "tenant-a", "local", "test", "",
                SchemaEnvelope.object(Map.of("applicantId", Map.of("type", "string")), List.of("applicantId")),
                SchemaEnvelope.object(Map.of(
                        "decision", Map.of("type", "string"),
                        "score", Map.of("type", "integer")), List.of("decision", "score")),
                List.of(new GraphDraft.DraftNode(
                        "risk", operator.operatorRef(), "Risk score",
                        Map.of(
                                "applicantId",
                                GraphDraft.Binding.contextPath("applicantId", "applicantId", "")),
                        Map.of(), new GraphDraft.Position(80, 80))),
                List.of(),
                Map.of(),
                Map.of("risk", new GraphDraft.NodeFixture(
                        Map.of("decision", "APPROVE", "score", 720))),
                new GraphDraft.OutputSelection("risk", ""),
                Map.of(), Map.of(), GraphDraft.RevisionMetadata.empty());
    }

    private List<ScenarioDraftSet.ScenarioDraft> validScenarios() {
        return List.of(
                scenario("prime-approved", "Prime applicant approved", ScenarioDraftSet.CaseType.GOLDEN, "A-1001"),
                scenario("missing-applicant", "Missing applicant rejected", ScenarioDraftSet.CaseType.NEGATIVE, ""),
                scenario("long-applicant-id", "Maximum applicant id", ScenarioDraftSet.CaseType.BOUNDARY,
                        "APPLICANT-999999999999"));
    }

    private ScenarioDraftSet.ScenarioDraft scenario(
            String id,
            String name,
            ScenarioDraftSet.CaseType type,
            String applicantId) {
        return new ScenarioDraftSet.ScenarioDraft(
                id,
                name,
                "Meaningful " + type.name().toLowerCase() + " business example.",
                type,
                List.of("demo", type.name().toLowerCase()),
                new ScenarioDraftSet.Given(Map.of("applicantId", applicantId),
                        ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(),
                new ScenarioDraftSet.Then(List.of(new ScenarioDraftSet.AssertionDraft(
                        id + "-decision",
                        ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                        "", "", "", "/decision",
                        ScenarioDraftSet.AssertionOperator.EXISTS,
                        null,
                        null))));
    }

    private static ScenarioDraftSet.EnterpriseScope scope() {
        return new ScenarioDraftSet.EnterpriseScope(
                "tenant-a", "knowledge-governance", "tool-studio", "test", "sg");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "knowledge-governance", "tool-studio", "test", "sg",
                "WORKLOAD", "author-a", "", "TEST_SUITE_WRITE", "correlation-a",
                Set.of("authors"), "CONFIDENTIAL", "");
    }
}
