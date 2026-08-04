package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.UnaryOperator;

/** Server-authoritative exact selection, durable progress, and append-only attempt orchestration. */
public final class TableSuiteRunService {

    private static final int MAX_COMMAND_BYTES = 16 * 1_048_576;
    private static final int MAX_CASES = 500;
    private static final int MAX_CAS_RETRIES = 20;
    private static final int MAX_RETAINED_EXECUTION_CONTEXTS = 256;
    private static final int MAX_CONCURRENT_BATCHES = 8;
    private static final Duration RETRY_CONTEXT_RETENTION = Duration.ofMinutes(30);

    private final TableSuiteRunRepository repository;
    private final TableSuiteCaseRunner runner;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Executor executor;
    private final Semaphore batchPermits = new Semaphore(MAX_CONCURRENT_BATCHES, true);
    private final Map<String, ExecutionContext> contexts = new ConcurrentHashMap<>();

    /** Creates the durable authoring batch application service. */
    public TableSuiteRunService(
            TableSuiteRunRepository repository,
            TableSuiteCaseRunner runner,
            ObjectMapper mapper,
            Clock clock,
            Executor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Validates, resolves, seals, persists, and schedules one exact batch. */
    public TableSuiteRunBatch submit(
            TableSuiteRunCommand command,
            IntegrationRequestContext identity) {
        validate(command, identity);
        evictExpiredContexts();
        String requestFingerprint = ProtocolFingerprint.ofBounded(mapper, command, MAX_COMMAND_BYTES);
        Optional<TableSuiteRunBatch> retained = repository.findByRequest(command.draftSet().scope(), command.requestId());
        if (retained.isPresent()) {
            if (!retained.get().requestFingerprint().equals(requestFingerprint)) {
                throw conflict(identity, "RG.TABLE_RUN.REQUEST_CONFLICT",
                        "The request id is already bound to another exact command.");
            }
            return retained.get();
        }
        if (contexts.size() >= MAX_RETAINED_EXECUTION_CONTEXTS) {
            throw capacity(identity);
        }

        Optional<TableSuiteRunBatch> baseline = baseline(command, identity);
        List<String> caseIds = resolveSelection(command, baseline, identity);
        if (caseIds.isEmpty()) {
            throw badRequest(identity, "RG.TABLE_RUN.SELECTION_EMPTY",
                    "The server-side selection resolved to no Scenario cases.");
        }
        if (caseIds.size() > command.preflight().maxCases()) {
            throw badRequest(identity, "RG.TABLE_RUN.CASE_BUDGET_EXCEEDED",
                    "The exact selection exceeds the admitted case budget.");
        }

        String draftSetFingerprint = ProtocolFingerprint.ofBounded(
                mapper, command.draftSet(), MAX_COMMAND_BYTES);
        String selectionFingerprint = ProtocolFingerprint.ofBounded(mapper, Map.of(
                "draftSetFingerprint", draftSetFingerprint,
                "mode", command.selection().mode(),
                "caseIds", caseIds,
                "baselineBatchId", command.baselineBatchId()), MAX_COMMAND_BYTES);
        boolean fullSuite = caseIds.size() == command.draftSet().scenarios().size()
                && caseIds.equals(command.draftSet().scenarios().stream()
                .map(ScenarioDraftSet.ScenarioDraft::scenarioId).toList());
        TableSuiteRunBatch.SelectionClosure closure = new TableSuiteRunBatch.SelectionClosure(
                command.selection().mode(), caseIds, selectionFingerprint, fullSuite);
        List<TableSuiteRunBatch.RowEvidence> rows = rows(command, caseIds, baseline);
        TableSuiteRunBatch candidate = TableSuiteRunBatch.admitted(
                "table-run-" + UUID.randomUUID(), command.requestId(), requestFingerprint,
                command.draftSet(), draftSetFingerprint, closure, command.preflight(),
                command.baselineBatchId(), rows, clock.instant());
        TableSuiteRunBatch stored = repository.create(candidate);
        if (!stored.requestFingerprint().equals(requestFingerprint)) {
            throw conflict(identity, "RG.TABLE_RUN.REQUEST_CONFLICT",
                    "The request id was concurrently bound to another exact command.");
        }
        contexts.put(stored.batchId(), ExecutionContext.active(command));
        if (stored.status() == TableSuiteRunBatch.BatchStatus.QUEUED) {
            executor.execute(() -> execute(stored.scope(), stored.batchId(), caseIds, false));
        }
        return repository.find(stored.scope(), stored.batchId()).orElse(stored);
    }

    /** Reads one exact batch inside the authenticated scope. */
    public TableSuiteRunBatch find(String batchId, IntegrationRequestContext identity) {
        identity.requireComplete();
        return repository.find(scope(identity), normalized(batchId)).orElseThrow(() ->
                notFound(identity, "RG.TABLE_RUN.NOT_FOUND", "Table suite batch was not found."));
    }

    /** Returns only events newer than the caller's last durable revision. */
    public TableSuiteRunBatch.Delta delta(
            String batchId,
            long afterRevision,
            IntegrationRequestContext identity) {
        TableSuiteRunBatch batch = find(batchId, identity);
        List<TableSuiteRunBatch.RunEvent> events = batch.events().stream()
                .filter(event -> event.revision() > Math.max(0, afterRevision))
                .toList();
        long firstRetainedRevision = batch.events().isEmpty()
                ? batch.revision() + 1
                : batch.events().getFirst().revision();
        boolean resetRequired = afterRevision > 0
                && afterRevision < firstRetainedRevision - 1;
        return new TableSuiteRunBatch.Delta("", batch.batchId(), batch.revision(), batch.status(),
                batch.counts(), batch.promotion(), resetRequired, events);
    }

    /** Requests cooperative cancellation and keeps already terminal attempts unchanged. */
    public TableSuiteRunBatch cancel(String batchId, IntegrationRequestContext identity) {
        TableSuiteRunBatch batch = find(batchId, identity);
        if (batch.terminal()) return batch;
        return update(batch.scope(), batch.batchId(), current -> current.cancellationRequested(clock.instant()));
    }

    /** Re-runs failed rows while appending attempts to the same immutable row identities. */
    public TableSuiteRunBatch retryFailed(String batchId, IntegrationRequestContext identity) {
        TableSuiteRunBatch batch = find(batchId, identity);
        ExecutionContext context = retainedContext(batch.batchId());
        if (context == null) {
            throw conflict(identity, "RG.TABLE_RUN.CONTEXT_EXPIRED",
                    "The payload-bearing execution context is no longer available; submit a new exact command.");
        }
        List<String> failed = batch.rows().stream()
                .filter(row -> row.status().failed())
                .map(TableSuiteRunBatch.RowEvidence::caseId)
                .toList();
        if (failed.isEmpty()) {
            throw conflict(identity, "RG.TABLE_RUN.NO_FAILED_ROWS",
                    "This batch has no failed rows to retry.");
        }
        update(batch.scope(), batch.batchId(), current -> current.running(failed, clock.instant(), true));
        executor.execute(() -> execute(batch.scope(), batch.batchId(), failed, true));
        return find(batchId, identity);
    }

    private void execute(
            ScenarioDraftSet.EnterpriseScope scope,
            String batchId,
            List<String> caseIds,
            boolean retry) {
        ExecutionContext context = retainedContext(batchId);
        if (context == null) return;
        boolean acquired = false;
        try {
            batchPermits.acquire();
            acquired = true;
            if (!retry) {
                update(scope, batchId, current -> current.running(caseIds, clock.instant(), false));
            }
            int failures = 0;
            for (int index = 0; index < caseIds.size(); index++) {
                String caseId = caseIds.get(index);
                TableSuiteRunBatch current = require(scope, batchId);
                if (current.cancelRequested()) {
                    terminalizeRemaining(scope, batchId, caseIds.subList(index, caseIds.size()),
                            TableSuiteRunBatch.RowStatus.CANCELLED, "RG.TABLE_RUN.CANCELLED");
                    finish(scope, batchId, TableSuiteRunBatch.BatchStatus.CANCELLED);
                    return;
                }
                if (failures > current.preflight().maxFailures()) {
                    terminalizeRemaining(scope, batchId, caseIds.subList(index, caseIds.size()),
                            TableSuiteRunBatch.RowStatus.BUDGET_STOPPED, "RG.TABLE_RUN.FAILURE_BUDGET");
                    finish(scope, batchId, TableSuiteRunBatch.BatchStatus.BUDGET_STOPPED);
                    return;
                }
                update(scope, batchId, batch -> batch.rowRunning(caseId, clock.instant()));
                int attempt = require(scope, batchId).row(caseId).attempts().size() + 1;
                TableSuiteRunBatch.AttemptEvidence evidence = runAttempt(
                        context.command(), caseId, attempt);
                update(scope, batchId, batch -> batch.appendAttempt(caseId, evidence, clock.instant()));
                if (evidence.status().failed()) failures++;
            }
            TableSuiteRunBatch completed = require(scope, batchId);
            finish(scope, batchId, completed.counts().failed() > 0
                    ? TableSuiteRunBatch.BatchStatus.FAILED
                    : TableSuiteRunBatch.BatchStatus.SUCCEEDED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminalizeRemaining(scope, batchId, caseIds,
                    TableSuiteRunBatch.RowStatus.CANCELLED, "RG.TABLE_RUN.WORKER_INTERRUPTED");
            finish(scope, batchId, TableSuiteRunBatch.BatchStatus.CANCELLED);
        } finally {
            if (acquired) batchPermits.release();
        }
    }

    private TableSuiteRunBatch.AttemptEvidence runAttempt(
            TableSuiteRunCommand command,
            String caseId,
            int attempt) {
        try {
            return runner.run(command, caseId, attempt);
        } catch (RuntimeException failure) {
            Instant now = clock.instant();
            return new TableSuiteRunBatch.AttemptEvidence(attempt,
                    TableSuiteRunBatch.RowStatus.RUNTIME_ERROR,
                    TableSuiteRunBatch.AssertionState.INCONCLUSIVE,
                    TableSuiteRunBatch.ProofStrength.SCHEMA, 0, "",
                    new TableSuiteRunBatch.Failure("EXECUTION", "RG.TABLE_RUN.RUNNER_FAILED",
                            "/run", "Scenario runner failed before producing evidence."),
                    List.of(), now, now);
        }
    }

    private void terminalizeRemaining(
            ScenarioDraftSet.EnterpriseScope scope,
            String batchId,
            List<String> caseIds,
            TableSuiteRunBatch.RowStatus status,
            String code) {
        for (String caseId : caseIds) {
            TableSuiteRunBatch.RowEvidence row = require(scope, batchId).row(caseId);
            if (row.status() != TableSuiteRunBatch.RowStatus.QUEUED) continue;
            Instant now = clock.instant();
            TableSuiteRunBatch.AttemptEvidence attempt = new TableSuiteRunBatch.AttemptEvidence(
                    row.attempts().size() + 1, status, TableSuiteRunBatch.AssertionState.INCONCLUSIVE,
                    TableSuiteRunBatch.ProofStrength.SCHEMA, 0, "",
                    new TableSuiteRunBatch.Failure("CONTROL", code, "/run",
                            status == TableSuiteRunBatch.RowStatus.CANCELLED
                                    ? "Scenario was cancelled before execution."
                                    : "Scenario was not started after the failure budget was exhausted."),
                    List.of(), now, now);
            update(scope, batchId, batch -> batch.appendAttempt(caseId, attempt, now));
        }
    }

    private void finish(
            ScenarioDraftSet.EnterpriseScope scope,
            String batchId,
            TableSuiteRunBatch.BatchStatus status) {
        update(scope, batchId, batch -> batch.completed(status, promotion(batch, status), clock.instant()));
        contexts.computeIfPresent(batchId, (ignored, context) ->
                context.retainedUntil(clock.instant().plus(RETRY_CONTEXT_RETENTION)));
    }

    private TableSuiteRunBatch.Promotion promotion(
            TableSuiteRunBatch batch,
            TableSuiteRunBatch.BatchStatus status) {
        if (!batch.selection().fullSuite()) {
            return new TableSuiteRunBatch.Promotion(false, "PARTIAL_SELECTION");
        }
        if (status != TableSuiteRunBatch.BatchStatus.SUCCEEDED) {
            return new TableSuiteRunBatch.Promotion(false, "NON_SUCCESS_TERMINAL_STATUS");
        }
        if (batch.rows().stream().anyMatch(row -> row.status() != TableSuiteRunBatch.RowStatus.SUCCESS)) {
            return new TableSuiteRunBatch.Promotion(false, "ROW_EVIDENCE_INCOMPLETE");
        }
        return new TableSuiteRunBatch.Promotion(true, "FULL_CURRENT_CLOSURE_SUCCEEDED");
    }

    private List<TableSuiteRunBatch.RowEvidence> rows(
            TableSuiteRunCommand command,
            List<String> caseIds,
            Optional<TableSuiteRunBatch> baseline) {
        Map<String, TableSuiteRunBatch.RowEvidence> previous = new LinkedHashMap<>();
        baseline.ifPresent(batch -> batch.rows().forEach(row -> previous.put(row.caseId(), row)));
        Map<String, ScenarioDraftSet.ScenarioDraft> scenarios = scenarios(command.draftSet());
        List<TableSuiteRunBatch.RowEvidence> result = new ArrayList<>();
        for (String caseId : caseIds) {
            String fingerprint = ProtocolFingerprint.ofBounded(
                    mapper, scenarios.get(caseId), MAX_COMMAND_BYTES);
            TableSuiteRunBatch.RowEvidence before = previous.get(caseId);
            TableSuiteRunBatch.BaselineComparison comparison;
            if (baseline.isEmpty()) {
                comparison = TableSuiteRunBatch.BaselineComparison.none();
            } else if (before == null) {
                comparison = new TableSuiteRunBatch.BaselineComparison(
                        baseline.get().batchId(), null, TableSuiteRunBatch.BaselineOutcome.NEW);
            } else if (!before.caseFingerprint().equals(fingerprint)) {
                comparison = new TableSuiteRunBatch.BaselineComparison(
                        baseline.get().batchId(), before.status(), TableSuiteRunBatch.BaselineOutcome.CHANGED_INPUT);
            } else {
                comparison = new TableSuiteRunBatch.BaselineComparison(
                        baseline.get().batchId(), before.status(), TableSuiteRunBatch.BaselineOutcome.SAME);
            }
            result.add(new TableSuiteRunBatch.RowEvidence(caseId, fingerprint,
                    TableSuiteRunBatch.RowStatus.QUEUED, List.of(), false, comparison));
        }
        return result;
    }

    private List<String> resolveSelection(
            TableSuiteRunCommand command,
            Optional<TableSuiteRunBatch> baseline,
            IntegrationRequestContext identity) {
        Map<String, ScenarioDraftSet.ScenarioDraft> current = scenarios(command.draftSet());
        Set<String> requested = Set.copyOf(command.selection().caseIds());
        if (command.selection().mode() == TableSuiteRunCommand.SelectionMode.SELECTED
                && !current.keySet().containsAll(requested)) {
            throw badRequest(identity, "RG.TABLE_RUN.SELECTION_INVALID",
                    "Selected case ids do not belong to the exact submitted Scenario set.");
        }
        if (Set.of(TableSuiteRunCommand.SelectionMode.FAILED,
                        TableSuiteRunCommand.SelectionMode.CHANGED,
                        TableSuiteRunCommand.SelectionMode.AFFECTED)
                .contains(command.selection().mode()) && baseline.isEmpty()) {
            throw badRequest(identity, "RG.TABLE_RUN.BASELINE_REQUIRED",
                    "This selection mode requires an exact retained baseline batch.");
        }
        if (Set.of(TableSuiteRunCommand.SelectionMode.FAILED,
                        TableSuiteRunCommand.SelectionMode.CHANGED,
                        TableSuiteRunCommand.SelectionMode.AFFECTED)
                .contains(command.selection().mode())
                && baseline.isPresent() && !baseline.get().selection().fullSuite()) {
            throw badRequest(identity, "RG.TABLE_RUN.FULL_BASELINE_REQUIRED",
                    "Differential selection requires a complete retained baseline closure.");
        }
        if (Set.of(TableSuiteRunCommand.SelectionMode.FAILED,
                        TableSuiteRunCommand.SelectionMode.CHANGED,
                        TableSuiteRunCommand.SelectionMode.AFFECTED)
                .contains(command.selection().mode())
                && baseline.isPresent() && !completeBaseline(baseline.get())) {
            throw badRequest(identity, "RG.TABLE_RUN.CONCLUSIVE_BASELINE_REQUIRED",
                    "Differential selection requires every baseline row to have a conclusive attempt.");
        }
        Map<String, TableSuiteRunBatch.RowEvidence> previous = new LinkedHashMap<>();
        baseline.ifPresent(batch -> batch.rows().forEach(row -> previous.put(row.caseId(), row)));
        boolean targetChanged = baseline.isPresent()
                && (!baseline.get().target().equals(command.draftSet().target())
                || !baseline.get().contractFingerprint().equals(command.draftSet().contractFingerprint()));
        return current.entrySet().stream().filter(entry -> switch (command.selection().mode()) {
            case ALL -> true;
            case SELECTED -> requested.contains(entry.getKey());
            case FAILED -> Optional.ofNullable(previous.get(entry.getKey()))
                    .map(row -> row.status().failed()).orElse(false);
            case CHANGED -> changed(entry.getValue(), previous.get(entry.getKey()));
            case AFFECTED -> targetChanged
                    || changed(entry.getValue(), previous.get(entry.getKey()))
                    || Optional.ofNullable(previous.get(entry.getKey()))
                    .map(row -> row.status().failed()).orElse(false);
        }).map(Map.Entry::getKey).toList();
    }

    private boolean changed(
            ScenarioDraftSet.ScenarioDraft scenario,
            TableSuiteRunBatch.RowEvidence baseline) {
        return baseline == null || !baseline.caseFingerprint().equals(
                ProtocolFingerprint.ofBounded(mapper, scenario, MAX_COMMAND_BYTES));
    }

    private static boolean completeBaseline(TableSuiteRunBatch batch) {
        return batch.selection().fullSuite()
                && Set.of(TableSuiteRunBatch.BatchStatus.SUCCEEDED, TableSuiteRunBatch.BatchStatus.FAILED)
                .contains(batch.status())
                && batch.rows().size() == batch.selection().caseIds().size()
                && batch.rows().stream().noneMatch(row -> Set.of(
                        TableSuiteRunBatch.RowStatus.QUEUED,
                        TableSuiteRunBatch.RowStatus.RUNNING,
                        TableSuiteRunBatch.RowStatus.CANCELLED,
                        TableSuiteRunBatch.RowStatus.BUDGET_STOPPED).contains(row.status()));
    }

    private Optional<TableSuiteRunBatch> baseline(
            TableSuiteRunCommand command,
            IntegrationRequestContext identity) {
        if (command.baselineBatchId().isBlank()) return Optional.empty();
        Optional<TableSuiteRunBatch> baseline = repository.find(
                command.draftSet().scope(), command.baselineBatchId());
        if (baseline.isEmpty()) {
            throw notFound(identity, "RG.TABLE_RUN.BASELINE_NOT_FOUND",
                    "The addressed baseline batch was not found in this scope.");
        }
        if (!baseline.get().scenarioDraftSetId().equals(command.draftSet().scenarioDraftSetId())) {
            throw badRequest(identity, "RG.TABLE_RUN.BASELINE_TARGET_MISMATCH",
                    "The baseline belongs to another Scenario asset.");
        }
        return baseline;
    }

    private void validate(TableSuiteRunCommand command, IntegrationRequestContext identity) {
        identity.requireComplete();
        if (command == null || !TableSuiteRunCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.requestId().isBlank() || command.requestId().length() > 128
                || command.graphDraft() == null || command.contract() == null || command.draftSet() == null
                || command.selection().mode() == null) {
            throw badRequest(identity, "RG.TABLE_RUN.COMMAND_INVALID",
                    "A versioned exact Graph, Contract, Scenario set, and selection are required.");
        }
        ScenarioDraftSet.EnterpriseScope scope = command.draftSet().scope();
        if (!scope.equals(scope(identity))) {
            throw notFound(identity, "RG.TABLE_RUN.NOT_FOUND",
                    "The Scenario set does not exist in the authenticated scope.");
        }
        if (!command.preflight().environment().equals(scope.environment())
                || command.preflight().dependencyMode() != TableSuiteRunCommand.DependencyMode.SIMULATED
                || command.preflight().effectProfile() != TableSuiteRunCommand.EffectProfile.SIDE_EFFECT_FREE
                || command.preflight().maxCases() < 1 || command.preflight().maxCases() > MAX_CASES
                || command.preflight().maxFailures() < 0
                || command.preflight().maxFailures() > command.preflight().maxCases()
                || command.preflight().maxConcurrency() != 1
                || command.preflight().caseTimeoutMs() < 100
                || command.preflight().caseTimeoutMs() > 10_000) {
            throw badRequest(identity, "RG.TABLE_RUN.PREFLIGHT_REJECTED",
                    "Environment, simulation effect, concurrency, timeout, or budget is not admitted.");
        }
        if (!command.contract().target().equals(command.draftSet().target())
                || !command.contract().fingerprint(mapper).equals(command.draftSet().contractFingerprint())
                || command.draftSet().target().kind() != com.leanowtech.bloge.gateway.visual.contract.ContractDraft.TargetKind.GRAPH
                || !command.graphDraft().draftId().equals(command.draftSet().target().id())
                || command.graphDraft().revision() != command.draftSet().target().revision()) {
            throw badRequest(identity, "RG.TABLE_RUN.CLOSURE_DRIFT",
                    "Graph, Contract, and Scenario coordinates do not form one exact current closure.");
        }
        if (command.draftSet().scenarios().isEmpty()
                || command.draftSet().scenarios().size() > MAX_CASES
                || scenarios(command.draftSet()).size() != command.draftSet().scenarios().size()) {
            throw badRequest(identity, "RG.TABLE_RUN.SCENARIOS_INVALID",
                    "Scenario ids must be unique and within the server case budget.");
        }
    }

    private TableSuiteRunBatch update(
            ScenarioDraftSet.EnterpriseScope scope,
            String batchId,
            UnaryOperator<TableSuiteRunBatch> mutation) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            TableSuiteRunBatch current = require(scope, batchId);
            TableSuiteRunBatch next = mutation.apply(current);
            if (next == current) return current;
            if (repository.replace(next, current.revision())) return next;
        }
        throw new IllegalStateException("Table suite batch update contention exceeded retry budget");
    }

    private TableSuiteRunBatch require(ScenarioDraftSet.EnterpriseScope scope, String batchId) {
        return repository.find(scope, batchId)
                .orElseThrow(() -> new IllegalStateException("Admitted table suite batch disappeared"));
    }

    private static Map<String, ScenarioDraftSet.ScenarioDraft> scenarios(ScenarioDraftSet draftSet) {
        Map<String, ScenarioDraftSet.ScenarioDraft> result = new LinkedHashMap<>();
        draftSet.scenarios().forEach(scenario -> result.putIfAbsent(scenario.scenarioId(), scenario));
        return result;
    }

    private static ScenarioDraftSet.EnterpriseScope scope(IntegrationRequestContext identity) {
        return new ScenarioDraftSet.EnterpriseScope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                code, title, identity.correlationId(), Map.of()));
    }

    private IntegrationProblemException capacity(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.tooManyRequests(
                "RG.TABLE_RUN.CONTEXT_CAPACITY",
                "The bounded table-run execution context capacity is currently exhausted.",
                identity.correlationId(), Map.of("retryAfterSeconds", 30)));
    }

    private ExecutionContext retainedContext(String batchId) {
        ExecutionContext context = contexts.get(batchId);
        if (context != null && context.expired(clock.instant())) {
            contexts.remove(batchId, context);
            return null;
        }
        return context;
    }

    private void evictExpiredContexts() {
        Instant now = clock.instant();
        contexts.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    private record ExecutionContext(TableSuiteRunCommand command, Instant expiresAt) {
        static ExecutionContext active(TableSuiteRunCommand command) {
            return new ExecutionContext(command, Instant.MAX);
        }

        ExecutionContext retainedUntil(Instant value) {
            return new ExecutionContext(command, value);
        }

        boolean expired(Instant now) {
            return !expiresAt.equals(Instant.MAX) && !expiresAt.isAfter(now);
        }
    }
}
