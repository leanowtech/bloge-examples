package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Durable payload-free projection of one exact Scenario table execution. */
public record TableSuiteRunBatch(
        String schemaVersion,
        String batchId,
        String requestId,
        String requestFingerprint,
        ScenarioDraftSet.EnterpriseScope scope,
        ContractDraft.Target target,
        String scenarioDraftSetId,
        long scenarioDraftSetRevision,
        String scenarioDraftSetFingerprint,
        String contractFingerprint,
        SelectionClosure selection,
        TableSuiteRunCommand.Preflight preflight,
        String baselineBatchId,
        BatchStatus status,
        long revision,
        boolean cancelRequested,
        List<RowEvidence> rows,
        Counts counts,
        Promotion promotion,
        List<RunEvent> events,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
    /** Current durable batch protocol version. */
    public static final String SCHEMA_VERSION = "bloge.tableSuiteRunBatch.v1";
    private static final int MAX_EVENTS = 5_000;

    /** Freezes all payload-free collections. */
    public TableSuiteRunBatch {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        batchId = normalized(batchId, "");
        requestId = normalized(requestId, "");
        requestFingerprint = normalized(requestFingerprint, "");
        scope = scope == null ? ScenarioDraftSet.EnterpriseScope.empty() : scope;
        target = target == null ? ContractDraft.Target.unknown() : target;
        scenarioDraftSetId = normalized(scenarioDraftSetId, "");
        scenarioDraftSetFingerprint = normalized(scenarioDraftSetFingerprint, "");
        contractFingerprint = normalized(contractFingerprint, "");
        selection = selection == null ? SelectionClosure.empty() : selection;
        preflight = preflight == null
                ? new TableSuiteRunCommand.Preflight("", null, null, 0, 0, 0, 0)
                : preflight;
        baselineBatchId = normalized(baselineBatchId, "");
        status = status == null ? BatchStatus.QUEUED : status;
        revision = Math.max(1, revision);
        rows = rows == null ? List.of() : List.copyOf(rows);
        counts = counts == null ? Counts.from(rows) : counts;
        promotion = promotion == null ? Promotion.pending() : promotion;
        events = events == null ? List.of() : List.copyOf(events);
    }

    /** Creates an admitted batch whose rows are already frozen in canonical order. */
    public static TableSuiteRunBatch admitted(
            String batchId,
            String requestId,
            String requestFingerprint,
            ScenarioDraftSet draftSet,
            String draftSetFingerprint,
            SelectionClosure selection,
            TableSuiteRunCommand.Preflight preflight,
            String baselineBatchId,
            List<RowEvidence> rows,
            Instant now) {
        RunEvent admitted = new RunEvent(1, EventType.BATCH_ADMITTED, "", null, now);
        return new TableSuiteRunBatch(SCHEMA_VERSION, batchId, requestId, requestFingerprint,
                draftSet.scope(), draftSet.target(), draftSet.scenarioDraftSetId(), draftSet.revision(),
                draftSetFingerprint, draftSet.contractFingerprint(), selection, preflight,
                baselineBatchId, BatchStatus.QUEUED, 1, false, rows, Counts.from(rows),
                Promotion.pending(), List.of(admitted), now, null, null);
    }

    /** Marks the batch running or retrying. */
    public TableSuiteRunBatch running(List<String> caseIds, Instant now, boolean retry) {
        List<RowEvidence> nextRows = rows.stream()
                .map(row -> caseIds.contains(row.caseId()) ? row.queuedForRetry() : row)
                .toList();
        return evolve(retry ? EventType.BATCH_RETRY_STARTED : EventType.BATCH_STARTED,
                "", null, BatchStatus.RUNNING, cancelRequested, nextRows, Promotion.pending(),
                startedAt == null ? now : startedAt, null, now);
    }

    /** Marks one row as actively executing. */
    public TableSuiteRunBatch rowRunning(String caseId, Instant now) {
        RowEvidence changed = row(caseId).running();
        return evolve(EventType.ROW_RUNNING, caseId, changed, status, cancelRequested,
                replace(changed), promotion, startedAt, completedAt, now);
    }

    /** Appends one immutable physical attempt and updates the row projection. */
    public TableSuiteRunBatch appendAttempt(String caseId, AttemptEvidence attempt, Instant now) {
        RowEvidence changed = row(caseId).append(attempt);
        return evolve(EventType.ROW_TERMINAL, caseId, changed, status, cancelRequested,
                replace(changed), promotion, startedAt, completedAt, now);
    }

    /** Records a cooperative cancellation request without claiming terminal completion. */
    public TableSuiteRunBatch cancellationRequested(Instant now) {
        if (terminal() || cancelRequested) return this;
        return evolve(EventType.CANCEL_REQUESTED, "", null, status, true, rows,
                promotion, startedAt, completedAt, now);
    }

    /** Finalizes status and promotion eligibility after every selected row is terminal. */
    public TableSuiteRunBatch completed(BatchStatus terminalStatus, Promotion finalPromotion, Instant now) {
        return evolve(EventType.BATCH_TERMINAL, "", null, terminalStatus, cancelRequested,
                rows, finalPromotion, startedAt, now, now);
    }

    /** @return true when no further row transition is admitted without an explicit retry */
    public boolean terminal() {
        return switch (status) {
            case SUCCEEDED, FAILED, CANCELLED, BUDGET_STOPPED -> true;
            default -> false;
        };
    }

    /** @return one row or fail closed on a corrupt closure */
    public RowEvidence row(String caseId) {
        return rows.stream().filter(candidate -> candidate.caseId().equals(caseId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Batch row does not exist: " + caseId));
    }

    private TableSuiteRunBatch evolve(
            EventType type,
            String caseId,
            RowEvidence row,
            BatchStatus nextStatus,
            boolean nextCancelRequested,
            List<RowEvidence> nextRows,
            Promotion nextPromotion,
            Instant nextStartedAt,
            Instant nextCompletedAt,
            Instant observedAt) {
        long nextRevision = revision + 1;
        List<RunEvent> nextEvents = new ArrayList<>(events);
        nextEvents.add(new RunEvent(nextRevision, type, caseId, row, observedAt));
        if (nextEvents.size() > MAX_EVENTS) {
            nextEvents = new ArrayList<>(nextEvents.subList(nextEvents.size() - MAX_EVENTS, nextEvents.size()));
        }
        return new TableSuiteRunBatch(schemaVersion, batchId, requestId, requestFingerprint,
                scope, target, scenarioDraftSetId, scenarioDraftSetRevision,
                scenarioDraftSetFingerprint, contractFingerprint, selection, preflight,
                baselineBatchId, nextStatus, nextRevision, nextCancelRequested,
                nextRows, Counts.from(nextRows), nextPromotion, nextEvents,
                createdAt, nextStartedAt, nextCompletedAt);
    }

    private List<RowEvidence> replace(RowEvidence changed) {
        return rows.stream().map(row -> row.caseId().equals(changed.caseId()) ? changed : row).toList();
    }

    /** Exact immutable selection accepted by the server. */
    public record SelectionClosure(
            TableSuiteRunCommand.SelectionMode mode,
            List<String> caseIds,
            String fingerprint,
            boolean fullSuite
    ) {
        public SelectionClosure {
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            fingerprint = normalized(fingerprint, "");
        }

        public static SelectionClosure empty() {
            return new SelectionClosure(null, List.of(), "", false);
        }
    }

    /** One Scenario's durable, payload-free evidence and append-only attempts. */
    public record RowEvidence(
            String caseId,
            String caseFingerprint,
            RowStatus status,
            List<AttemptEvidence> attempts,
            boolean flaky,
            BaselineComparison baseline
    ) {
        public RowEvidence {
            caseId = normalized(caseId, "");
            caseFingerprint = normalized(caseFingerprint, "");
            status = status == null ? RowStatus.QUEUED : status;
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
            baseline = baseline == null ? BaselineComparison.none() : baseline;
        }

        public RowEvidence running() {
            return new RowEvidence(caseId, caseFingerprint, RowStatus.RUNNING, attempts, flaky, baseline);
        }

        public RowEvidence queuedForRetry() {
            return new RowEvidence(caseId, caseFingerprint, RowStatus.QUEUED, attempts, flaky, baseline);
        }

        public RowEvidence append(AttemptEvidence attempt) {
            List<AttemptEvidence> next = new ArrayList<>(attempts);
            next.add(attempt);
            boolean nextFlaky = flaky || (attempt.status() == RowStatus.SUCCESS
                    && attempts.stream().anyMatch(previous -> previous.status() != RowStatus.SUCCESS));
            return new RowEvidence(caseId, caseFingerprint, attempt.status(), next, nextFlaky,
                    baseline.completed(attempt.status()));
        }
    }

    /** One physical attempt. Expected and actual values are represented only by fingerprints. */
    public record AttemptEvidence(
            int attempt,
            RowStatus status,
            AssertionState assertions,
            ProofStrength proofStrength,
            long durationMs,
            String runFingerprint,
            Failure firstFailure,
            List<AssertionEvidence> assertionEvidence,
            Instant startedAt,
            Instant completedAt
    ) {
        public AttemptEvidence {
            attempt = Math.max(1, attempt);
            status = status == null ? RowStatus.RUNTIME_ERROR : status;
            assertions = assertions == null ? AssertionState.INCONCLUSIVE : assertions;
            proofStrength = proofStrength == null ? ProofStrength.SCHEMA : proofStrength;
            durationMs = Math.max(0, durationMs);
            runFingerprint = normalized(runFingerprint, "");
            assertionEvidence = assertionEvidence == null ? List.of() : List.copyOf(assertionEvidence);
        }
    }

    /** Payload-free assertion comparison. */
    public record AssertionEvidence(
            String assertionId,
            String path,
            boolean passed,
            String expectedFingerprint,
            String actualFingerprint,
            String diagnosticCode
    ) {
        public AssertionEvidence {
            assertionId = normalized(assertionId, "");
            path = normalized(path, "");
            expectedFingerprint = normalized(expectedFingerprint, "");
            actualFingerprint = normalized(actualFingerprint, "");
            diagnosticCode = normalized(diagnosticCode, "");
        }
    }

    /** First actionable failure with no business payload. */
    public record Failure(String category, String code, String target, String summary) {
        public Failure {
            category = normalized(category, "EXECUTION");
            code = normalized(code, "RG.TABLE_RUN.UNKNOWN");
            target = normalized(target, "/run");
            summary = normalized(summary, "Execution did not satisfy the Scenario.");
        }
    }

    /** Comparison against the explicitly addressed baseline batch. */
    public record BaselineComparison(
            String baselineBatchId,
            RowStatus baselineStatus,
            BaselineOutcome outcome
    ) {
        public BaselineComparison {
            baselineBatchId = normalized(baselineBatchId, "");
            outcome = outcome == null ? BaselineOutcome.NONE : outcome;
        }

        public static BaselineComparison none() {
            return new BaselineComparison("", null, BaselineOutcome.NONE);
        }

        public BaselineComparison completed(RowStatus current) {
            if (outcome == BaselineOutcome.CHANGED_INPUT || outcome == BaselineOutcome.NEW
                    || outcome == BaselineOutcome.NONE || baselineStatus == null) {
                return this;
            }
            boolean before = baselineStatus == RowStatus.SUCCESS;
            boolean after = current == RowStatus.SUCCESS;
            BaselineOutcome completed = before == after
                    ? BaselineOutcome.SAME
                    : after ? BaselineOutcome.IMPROVED : BaselineOutcome.REGRESSED;
            return new BaselineComparison(baselineBatchId, baselineStatus, completed);
        }
    }

    /** Aggregate counts for cheap polling and first paint. */
    public record Counts(int total, int queued, int running, int succeeded, int failed,
                         int cancelled, int budgetStopped) {
        public static Counts from(List<RowEvidence> rows) {
            List<RowEvidence> safe = rows == null ? List.of() : rows;
            return new Counts(safe.size(), count(safe, RowStatus.QUEUED), count(safe, RowStatus.RUNNING),
                    count(safe, RowStatus.SUCCESS), (int) safe.stream().filter(row -> row.status().failed()).count(),
                    count(safe, RowStatus.CANCELLED), count(safe, RowStatus.BUDGET_STOPPED));
        }

        private static int count(List<RowEvidence> rows, RowStatus status) {
            return (int) rows.stream().filter(row -> row.status() == status).count();
        }
    }

    /** Promotion is independent from a visually green partial run. */
    public record Promotion(boolean eligible, String reason) {
        public Promotion {
            reason = normalized(reason, "PENDING");
        }

        public static Promotion pending() {
            return new Promotion(false, "PENDING");
        }
    }

    /** Incremental payload-free transition event. */
    public record RunEvent(long revision, EventType type, String caseId,
                           RowEvidence row, Instant observedAt) {
        public RunEvent {
            revision = Math.max(1, revision);
            type = type == null ? EventType.BATCH_ADMITTED : type;
            caseId = normalized(caseId, "");
        }
    }

    /** Bounded polling response after a caller's last observed revision. */
    public record Delta(String schemaVersion, String batchId, long revision, BatchStatus status,
                        Counts counts, Promotion promotion, boolean resetRequired,
                        List<RunEvent> events) {
        public static final String SCHEMA_VERSION = "bloge.tableSuiteRunDelta.v1";

        public Delta {
            schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
            batchId = normalized(batchId, "");
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    public enum BatchStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, BUDGET_STOPPED }

    public enum RowStatus {
        QUEUED, RUNNING, SUCCESS, ASSERTION_FAILED, COMPILE_ERROR, RUNTIME_ERROR,
        TIMEOUT, CANCELLED, BUDGET_STOPPED;

        public boolean failed() {
            return switch (this) {
                case ASSERTION_FAILED, COMPILE_ERROR, RUNTIME_ERROR, TIMEOUT -> true;
                default -> false;
            };
        }
    }

    public enum AssertionState { NONE, PASSED, FAILED, INCONCLUSIVE }
    public enum ProofStrength { SCHEMA, MOCK, RUNTIME }
    public enum BaselineOutcome { NONE, SAME, IMPROVED, REGRESSED, CHANGED_INPUT, NEW }
    public enum EventType {
        BATCH_ADMITTED, BATCH_STARTED, BATCH_RETRY_STARTED, ROW_RUNNING,
        ROW_TERMINAL, CANCEL_REQUESTED, BATCH_TERMINAL
    }

    private static String normalized(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}
