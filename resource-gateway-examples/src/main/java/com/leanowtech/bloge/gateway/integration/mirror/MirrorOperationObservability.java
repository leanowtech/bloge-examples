package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Creates single-use operation observations that durably audit before publishing a result.
 *
 * <p>Callers place {@link Observation#succeeded(String)} inside the same local transaction as a
 * successful Plan or Run commit. Rejections are audited before their original stable exception is
 * rethrown. If the mandatory audit sink cannot commit, the result fails closed with a dedicated
 * 503 and no protected response is published.</p>
 */
public final class MirrorOperationObservability {
    private final MirrorOperationAuditRepository audit;
    private final AuditAppender failureAudit;
    private final MirrorOperationTelemetry telemetry;
    private final LongSupplier nanoTime;

    /**
     * Creates the production observer.
     *
     * @param audit mandatory durable payload-free audit sink
     * @param failureAudit isolated writer that preserves failure facts across business rollback
     * @param telemetry fixed-cardinality metric adapter
     */
    public MirrorOperationObservability(
            MirrorOperationAuditRepository audit,
            MirrorOperationFailureAuditService failureAudit,
            MirrorOperationTelemetry telemetry) {
        this(audit, Objects.requireNonNull(failureAudit, "failureAudit")::append,
                telemetry, System::nanoTime);
    }

    /**
     * Creates an observer with a deterministic monotonic clock for focused tests.
     *
     * <p>The supplied repository is used for both success and failure writes; transaction-boundary
     * tests should use the production constructor with an isolated failure writer.</p>
     *
     * @param audit durable audit sink, or {@code null} only for an inert observer
     * @param telemetry fixed-cardinality metric adapter
     * @param nanoTime monotonic nanosecond source
     */
    public MirrorOperationObservability(
            MirrorOperationAuditRepository audit,
            MirrorOperationTelemetry telemetry,
            LongSupplier nanoTime) {
        this(audit, audit == null ? null : audit::append, telemetry, nanoTime);
    }

    private MirrorOperationObservability(
            MirrorOperationAuditRepository audit,
            AuditAppender failureAudit,
            MirrorOperationTelemetry telemetry,
            LongSupplier nanoTime) {
        this.audit = audit;
        this.failureAudit = failureAudit;
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Returns an inert observer for narrow unit tests outside the composition root.
     *
     * @return observer that enforces token lifecycle but writes no audit or metrics
     */
    public static MirrorOperationObservability noop() {
        return new MirrorOperationObservability(null, MirrorOperationTelemetry.noop(), () -> 0L);
    }

    /**
     * Starts one single-use payload-free operation observation.
     *
     * @param operation protected operation being served
     * @param identity authenticated enterprise identity and trace coordinates
     * @param requestId execution request id or authority key-set id, otherwise blank
     * @param planId plan id or authority deployment-scope id, otherwise blank
     * @param runId terminal run id or authority publication fingerprint, otherwise blank
     * @return single-use terminal observation token
     */
    public Observation start(
            MirrorOperationAuditEvent.Operation operation,
            IntegrationRequestContext identity,
            String requestId,
            String planId,
            String runId) {
        return new Observation(operation, Objects.requireNonNull(identity, "identity"),
                requestId, planId, runId, nanoTime.getAsLong());
    }

    /** Single-use terminal observation token; it contains no business payload. */
    public final class Observation {
        private final MirrorOperationAuditEvent.Operation operation;
        private final IntegrationRequestContext identity;
        private final String requestId;
        private final String planId;
        private final String initialRunId;
        private final long startedNanos;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Observation(
                MirrorOperationAuditEvent.Operation operation,
                IntegrationRequestContext identity,
                String requestId,
                String planId,
                String runId,
                long startedNanos) {
            this.operation = Objects.requireNonNull(operation, "operation");
            this.identity = identity;
            this.requestId = normalized(requestId);
            this.planId = normalized(planId);
            this.initialRunId = normalized(runId);
            this.startedNanos = startedNanos;
        }

        /**
         * Commits one success audit before the protected result may be returned.
         *
         * @param terminalRunId terminal run id discovered during execution, or blank
         */
        public void succeeded(String terminalRunId) {
            long duration = completeOnce();
            publish(MirrorOperationAuditEvent.Outcome.SUCCEEDED,
                    MirrorOperationAuditEvent.Reason.NONE, "", terminalRunId,
                    duration, false);
        }

        /**
         * Commits one rejection/failure audit and returns the original stable exception.
         *
         * @param failure stable operation exception, or an unexpected internal exception
         * @return the original exception after its failure audit commits
         * @throws IntegrationProblemException with
         * {@code RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE} when the audit sink cannot commit
         */
        public RuntimeException failed(RuntimeException failure) {
            RuntimeException required = Objects.requireNonNull(failure, "failure");
            if (completed.get() && required instanceof IntegrationProblemException problem
                    && "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE"
                            .equals(problem.problem().code())) {
                return required;
            }
            long duration = completeOnce();
            Classification classification = classify(required);
            publish(classification.outcome(), classification.reason(),
                    classification.reasonCode(), "", duration, true);
            return required;
        }

        private long completeOnce() {
            if (!completed.compareAndSet(false, true)) {
                throw new IllegalStateException("Mirror operation observation is already terminal");
            }
            return Math.max(0, nanoTime.getAsLong() - startedNanos);
        }

        private MirrorOperationAuditEvent newEvent(
                MirrorOperationAuditEvent.Outcome outcome,
                MirrorOperationAuditEvent.Reason reason,
                String reasonCode,
                String terminalRunId,
                long durationNanos) {
            String finalRunId = normalized(terminalRunId);
            if (finalRunId.isBlank()) {
                finalRunId = initialRunId;
            }
            return new MirrorOperationAuditEvent(0, null,
                    identity.tenantId(), identity.organizationId(), identity.projectId(),
                    identity.environmentId(), identity.region(), identity.correlationId(),
                    identity.actorType(), identity.actorId(), operation, outcome, reason,
                    reasonCode, requestId, planId, finalRunId,
                    TimeUnit.NANOSECONDS.toMillis(durationNanos));
        }

        private void publish(
                MirrorOperationAuditEvent.Outcome outcome,
                MirrorOperationAuditEvent.Reason reason,
                String reasonCode,
                String terminalRunId,
                long durationNanos,
                boolean independently) {
            MirrorOperationAuditEvent event;
            try {
                event = newEvent(outcome, reason, reasonCode, terminalRunId, durationNanos);
                if (audit != null) {
                    if (independently) {
                        failureAudit.append(event);
                    } else {
                        audit.append(event);
                    }
                }
            } catch (RuntimeException unavailable) {
                telemetry.record(operation, MirrorOperationAuditEvent.Outcome.FAILED,
                        MirrorOperationAuditEvent.Reason.AUDIT_UNAVAILABLE, durationNanos);
                throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE",
                        "The protected Mirror operation audit could not be committed.",
                        identity.correlationId(), Map.of()));
            }
            telemetry.record(operation, event.outcome(), event.reason(), durationNanos);
        }
    }

    private static Classification classify(RuntimeException failure) {
        if (failure instanceof IntegrationProblemException problemFailure) {
            IntegrationProblem problem = problemFailure.problem();
            MirrorOperationAuditEvent.Outcome outcome = problem.status() >= 500
                    ? MirrorOperationAuditEvent.Outcome.FAILED
                    : MirrorOperationAuditEvent.Outcome.REJECTED;
            MirrorOperationAuditEvent.Reason reason = switch (problem.status()) {
                case 400 -> MirrorOperationAuditEvent.Reason.INVALID_REQUEST;
                case 401, 403 -> MirrorOperationAuditEvent.Reason.FORBIDDEN;
                case 404 -> MirrorOperationAuditEvent.Reason.NOT_FOUND;
                case 409 -> MirrorOperationAuditEvent.Reason.CONFLICT;
                case 410 -> MirrorOperationAuditEvent.Reason.EXPIRED;
                case 429 -> MirrorOperationAuditEvent.Reason.CAPACITY;
                case 503 -> MirrorOperationAuditEvent.Reason.UNAVAILABLE;
                default -> MirrorOperationAuditEvent.Reason.UNEXPECTED;
            };
            return new Classification(outcome, reason, problem.code());
        }
        return new Classification(MirrorOperationAuditEvent.Outcome.FAILED,
                MirrorOperationAuditEvent.Reason.UNEXPECTED,
                "RG.MIRROR.UNEXPECTED_FAILURE");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record Classification(
            MirrorOperationAuditEvent.Outcome outcome,
            MirrorOperationAuditEvent.Reason reason,
            String reasonCode) {
    }

    @FunctionalInterface
    private interface AuditAppender {
        MirrorOperationAuditEvent append(MirrorOperationAuditEvent event);
    }
}
