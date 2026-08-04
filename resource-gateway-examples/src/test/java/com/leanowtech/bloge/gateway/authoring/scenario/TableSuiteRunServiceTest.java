package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableSuiteRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final GraphDraft graph = ScenarioValidationServiceTest.graphDraft();
    private final ContractDraft contract = new ContractDraftProjectionService().project(
            graph, ScenarioValidationServiceTest.fingerprint('a'));
    private final IntegrationRequestContext identity = new IntegrationRequestContext(
            "tenant-a", "org-a", "project-a", "test", "sg",
            "HUMAN", "author-1", "", "TEST_EXECUTION", "corr-1");
    private final QueueExecutor executor = new QueueExecutor();
    private final MutableRunner runner = new MutableRunner();
    private TableSuiteRunRepository repository;
    private TableSuiteRunService service;

    @BeforeEach
    void setUp() {
        repository = new DatabaseTableSuiteRunRepository(new JdbcTemplate(
                new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true).build()), mapper);
        service = new TableSuiteRunService(repository, runner, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), executor);
    }

    @Test
    void freezesSelectedClosureAndStreamsOnlyEventsAfterTheObservedRevision() {
        TableSuiteRunBatch admitted = service.submit(command(
                "selected-1", scenarios(), TableSuiteRunCommand.SelectionMode.SELECTED,
                List.of("case-b"), "", 10), identity);

        assertThat(admitted.selection().caseIds()).containsExactly("case-b");
        assertThat(admitted.status()).isEqualTo(TableSuiteRunBatch.BatchStatus.QUEUED);
        long admittedRevision = admitted.revision();

        executor.runAll();
        TableSuiteRunBatch completed = service.find(admitted.batchId(), identity);
        TableSuiteRunBatch.Delta delta = service.delta(admitted.batchId(), admittedRevision, identity);

        assertThat(completed.status()).isEqualTo(TableSuiteRunBatch.BatchStatus.SUCCEEDED);
        assertThat(completed.rows()).extracting(TableSuiteRunBatch.RowEvidence::caseId)
                .containsExactly("case-b");
        assertThat(completed.promotion()).isEqualTo(
                new TableSuiteRunBatch.Promotion(false, "PARTIAL_SELECTION"));
        assertThat(delta.events()).isNotEmpty().allMatch(event -> event.revision() > admittedRevision);
        assertThat(delta.events()).extracting(TableSuiteRunBatch.RunEvent::type)
                .contains(TableSuiteRunBatch.EventType.ROW_RUNNING,
                        TableSuiteRunBatch.EventType.ROW_TERMINAL,
                        TableSuiteRunBatch.EventType.BATCH_TERMINAL);
    }

    @Test
    void resolvesFailedChangedAndAffectedFromTheExactBaselineInCanonicalOrder() {
        runner.statuses.put("case-b", TableSuiteRunBatch.RowStatus.ASSERTION_FAILED);
        TableSuiteRunBatch baseline = service.submit(command(
                "baseline", scenarios(), TableSuiteRunCommand.SelectionMode.ALL,
                List.of(), "", 10), identity);
        executor.runAll();
        baseline = service.find(baseline.batchId(), identity);

        runner.statuses.clear();
        TableSuiteRunBatch failed = service.submit(command(
                "failed", scenarios(), TableSuiteRunCommand.SelectionMode.FAILED,
                List.of(), baseline.batchId(), 10), identity);
        assertThat(failed.selection().caseIds()).containsExactly("case-b");

        List<ScenarioDraftSet.ScenarioDraft> changedScenarios = new ArrayList<>(scenarios());
        ScenarioDraftSet.ScenarioDraft third = changedScenarios.get(2);
        changedScenarios.set(2, new ScenarioDraftSet.ScenarioDraft(
                third.scenarioId(), "Changed case C", third.description(), third.caseType(),
                third.tags(), third.given(), third.dependencies(), third.then()));
        TableSuiteRunBatch changed = service.submit(command(
                "changed", changedScenarios, TableSuiteRunCommand.SelectionMode.CHANGED,
                List.of(), baseline.batchId(), 10), identity);
        assertThat(changed.selection().caseIds()).containsExactly("case-c");

        TableSuiteRunBatch affected = service.submit(command(
                "affected", changedScenarios, TableSuiteRunCommand.SelectionMode.AFFECTED,
                List.of(), baseline.batchId(), 10), identity);
        assertThat(affected.selection().caseIds()).containsExactly("case-b", "case-c");
    }

    @Test
    void stopsQueuedRowsAtTheFailureBudgetAndMakesEveryRowStatusExplicit() {
        runner.statuses.put("case-a", TableSuiteRunBatch.RowStatus.RUNTIME_ERROR);
        TableSuiteRunBatch batch = service.submit(command(
                "budget", scenarios(), TableSuiteRunCommand.SelectionMode.ALL,
                List.of(), "", 0), identity);

        executor.runAll();
        TableSuiteRunBatch completed = service.find(batch.batchId(), identity);

        assertThat(completed.status()).isEqualTo(TableSuiteRunBatch.BatchStatus.BUDGET_STOPPED);
        assertThat(completed.rows()).extracting(TableSuiteRunBatch.RowEvidence::status)
                .containsExactly(TableSuiteRunBatch.RowStatus.RUNTIME_ERROR,
                        TableSuiteRunBatch.RowStatus.BUDGET_STOPPED,
                        TableSuiteRunBatch.RowStatus.BUDGET_STOPPED);
        assertThat(completed.rows().get(1).attempts()).hasSize(1);
        assertThat(completed.promotion().eligible()).isFalse();
        assertProblem(command("budget-diff", scenarios(), TableSuiteRunCommand.SelectionMode.CHANGED,
                List.of(), completed.batchId(), 10), "RG.TABLE_RUN.CONCLUSIVE_BASELINE_REQUIRED");
    }

    @Test
    void cancellationBeforeWorkerAdmissionProducesAuditableCancelledAttempts() {
        TableSuiteRunBatch batch = service.submit(command(
                "cancel", scenarios(), TableSuiteRunCommand.SelectionMode.ALL,
                List.of(), "", 10), identity);
        service.cancel(batch.batchId(), identity);

        executor.runAll();
        TableSuiteRunBatch cancelled = service.find(batch.batchId(), identity);

        assertThat(cancelled.status()).isEqualTo(TableSuiteRunBatch.BatchStatus.CANCELLED);
        assertThat(cancelled.rows()).allMatch(row -> row.status() == TableSuiteRunBatch.RowStatus.CANCELLED);
        assertThat(cancelled.rows()).allMatch(row -> row.attempts().size() == 1);
        assertThat(cancelled.events()).extracting(TableSuiteRunBatch.RunEvent::type)
                .contains(TableSuiteRunBatch.EventType.CANCEL_REQUESTED);
    }

    @Test
    void retryAppendsAttemptsMarksFlakyAndCanMakeAFullClosurePromotionEligible() {
        runner.statuses.put("case-b", TableSuiteRunBatch.RowStatus.ASSERTION_FAILED);
        TableSuiteRunBatch batch = service.submit(command(
                "retry", scenarios(), TableSuiteRunCommand.SelectionMode.ALL,
                List.of(), "", 10), identity);
        executor.runAll();

        runner.statuses.clear();
        service.retryFailed(batch.batchId(), identity);
        executor.runAll();
        TableSuiteRunBatch retried = service.find(batch.batchId(), identity);
        TableSuiteRunBatch.RowEvidence flaky = retried.row("case-b");

        assertThat(retried.status()).isEqualTo(TableSuiteRunBatch.BatchStatus.SUCCEEDED);
        assertThat(retried.promotion()).isEqualTo(
                new TableSuiteRunBatch.Promotion(true, "FULL_CURRENT_CLOSURE_SUCCEEDED"));
        assertThat(flaky.attempts()).hasSize(2);
        assertThat(flaky.flaky()).isTrue();
        assertThat(flaky.attempts()).extracting(TableSuiteRunBatch.AttemptEvidence::status)
                .containsExactly(TableSuiteRunBatch.RowStatus.ASSERTION_FAILED,
                        TableSuiteRunBatch.RowStatus.SUCCESS);
    }

    @Test
    void requestIdempotencyRejectsDriftAndStoredEvidenceContainsNoBusinessPayloads() throws Exception {
        List<ScenarioDraftSet.ScenarioDraft> payloadScenarios = scenarios().stream()
                .map(scenario -> new ScenarioDraftSet.ScenarioDraft(
                        scenario.scenarioId(), scenario.name(), scenario.description(), scenario.caseType(),
                        scenario.tags(), new ScenarioDraftSet.Given(
                        Map.of("secretBusinessValue", "DO-NOT-PERSIST"),
                        ScenarioDraftSet.ValueProvenance.AUTHORED),
                        scenario.dependencies(), scenario.then()))
                .toList();
        TableSuiteRunCommand first = command("idem", payloadScenarios,
                TableSuiteRunCommand.SelectionMode.ALL, List.of(), "", 10);
        TableSuiteRunBatch admitted = service.submit(first, identity);
        assertThat(service.submit(first, identity).batchId()).isEqualTo(admitted.batchId());

        assertThatThrownBy(() -> service.submit(command(
                "idem", payloadScenarios, TableSuiteRunCommand.SelectionMode.SELECTED,
                List.of("case-a"), "", 10), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.TABLE_RUN.REQUEST_CONFLICT"));

        String stored = mapper.writeValueAsString(service.find(admitted.batchId(), identity));
        assertThat(stored).doesNotContain("DO-NOT-PERSIST", "secretBusinessValue");
    }

    @Test
    void rejectsCrossScopeUnsafePreflightAndMissingBaselinesWithoutLeakingTargets() {
        TableSuiteRunCommand valid = command("invalid", scenarios(),
                TableSuiteRunCommand.SelectionMode.ALL, List.of(), "", 10);
        TableSuiteRunCommand unsafe = new TableSuiteRunCommand(
                valid.schemaVersion(), valid.requestId(), valid.graphDraft(), valid.contract(),
                valid.draftSet(), valid.selection(),
                new TableSuiteRunCommand.Preflight("prod",
                        TableSuiteRunCommand.DependencyMode.SIMULATED,
                        TableSuiteRunCommand.EffectProfile.SIDE_EFFECT_FREE,
                        500, 10, 4, 5_000), "");
        assertProblem(unsafe, "RG.TABLE_RUN.PREFLIGHT_REJECTED");

        TableSuiteRunCommand missingBaseline = command("missing-baseline", scenarios(),
                TableSuiteRunCommand.SelectionMode.FAILED, List.of(), "unknown", 10);
        assertProblem(missingBaseline, "RG.TABLE_RUN.BASELINE_NOT_FOUND");

        TableSuiteRunBatch partialBaseline = service.submit(command(
                "partial-baseline", scenarios(), TableSuiteRunCommand.SelectionMode.SELECTED,
                List.of("case-a"), "", 10), identity);
        executor.runAll();
        assertProblem(command("partial-diff", scenarios(), TableSuiteRunCommand.SelectionMode.CHANGED,
                List.of(), partialBaseline.batchId(), 10), "RG.TABLE_RUN.FULL_BASELINE_REQUIRED");

        IntegrationRequestContext otherTenant = new IntegrationRequestContext(
                "tenant-b", "org-a", "project-a", "test", "sg",
                "HUMAN", "author-1", "", "TEST_EXECUTION", "corr-2");
        assertThatThrownBy(() -> service.submit(valid, otherTenant))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.TABLE_RUN.NOT_FOUND"));
    }

    @Test
    void expiresPayloadBearingRetryContextAfterTheBoundedWindow() {
        MutableClock mutableClock = new MutableClock(NOW);
        service = new TableSuiteRunService(repository, runner, mapper, mutableClock, executor);
        runner.statuses.put("case-a", TableSuiteRunBatch.RowStatus.RUNTIME_ERROR);
        TableSuiteRunBatch batch = service.submit(command(
                "expiring-context", List.of(scenario("case-a")),
                TableSuiteRunCommand.SelectionMode.ALL, List.of(), "", 10), identity);
        executor.runAll();
        mutableClock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> service.retryFailed(batch.batchId(), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo("RG.TABLE_RUN.CONTEXT_EXPIRED"));
    }

    private void assertProblem(TableSuiteRunCommand command, String code) {
        assertThatThrownBy(() -> service.submit(command, identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code()).isEqualTo(code));
    }

    private TableSuiteRunCommand command(
            String requestId,
            List<ScenarioDraftSet.ScenarioDraft> scenarios,
            TableSuiteRunCommand.SelectionMode mode,
            List<String> selected,
            String baselineBatchId,
            int maxFailures) {
        ScenarioDraftSet draftSet = new ScenarioDraftSet(
                "", "loan-scenarios", 3,
                new ScenarioDraftSet.EnterpriseScope("tenant-a", "org-a", "project-a", "test", "sg"),
                contract.target(), contract.fingerprint(mapper), scenarios,
                new ScenarioDraftSet.Metadata("credit-platform", "INTERNAL", null, null, Map.of()));
        return new TableSuiteRunCommand(TableSuiteRunCommand.SCHEMA_VERSION,
                requestId, graph, contract, draftSet,
                new TableSuiteRunCommand.Selection(mode, selected),
                new TableSuiteRunCommand.Preflight("test",
                        TableSuiteRunCommand.DependencyMode.SIMULATED,
                        TableSuiteRunCommand.EffectProfile.SIDE_EFFECT_FREE,
                        500, maxFailures, 1, 5_000), baselineBatchId);
    }

    private List<ScenarioDraftSet.ScenarioDraft> scenarios() {
        return List.of(scenario("case-a"), scenario("case-b"), scenario("case-c"));
    }

    private ScenarioDraftSet.ScenarioDraft scenario(String id) {
        return new ScenarioDraftSet.ScenarioDraft(
                id, "Scenario " + id, "", ScenarioDraftSet.CaseType.GOLDEN,
                List.of(), new ScenarioDraftSet.Given(
                Map.of("applicantId", id), ScenarioDraftSet.ValueProvenance.AUTHORED),
                List.of(), new ScenarioDraftSet.Then(List.of()));
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.remove().run();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class MutableRunner implements TableSuiteCaseRunner {
        private final Map<String, TableSuiteRunBatch.RowStatus> statuses = new LinkedHashMap<>();

        @Override
        public TableSuiteRunBatch.AttemptEvidence run(
                TableSuiteRunCommand command,
                String caseId,
                int attempt) {
            TableSuiteRunBatch.RowStatus status = statuses.getOrDefault(
                    caseId, TableSuiteRunBatch.RowStatus.SUCCESS);
            boolean success = status == TableSuiteRunBatch.RowStatus.SUCCESS;
            Instant completed = NOW.plusMillis(attempt);
            return new TableSuiteRunBatch.AttemptEvidence(attempt, status,
                    success ? TableSuiteRunBatch.AssertionState.PASSED
                            : TableSuiteRunBatch.AssertionState.FAILED,
                    TableSuiteRunBatch.ProofStrength.MOCK, 1,
                    "sha256:" + Integer.toHexString((caseId + attempt).hashCode()).replace("-", "0")
                            .repeat(64).substring(0, 64),
                    success ? null : new TableSuiteRunBatch.Failure(
                    "ASSERTION", "RG.TABLE_RUN.ASSERTION_MISMATCH", "/decision",
                    "Expected and actual values differ."),
                    List.of(), NOW, completed);
        }
    }
}
