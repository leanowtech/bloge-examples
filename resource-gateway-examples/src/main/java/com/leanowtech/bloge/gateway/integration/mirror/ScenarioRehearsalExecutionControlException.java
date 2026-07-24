package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;

import java.util.Map;
import java.util.Objects;

/**
 * Stable cooperative stop raised by a server-owned Scenario execution controller.
 *
 * <p>The exception carries no batch, case, fixture, or payload identity. Callers use the closed
 * reason vocabulary to map the already persisted control-plane decision to a bounded worker
 * disposition.</p>
 */
public final class ScenarioRehearsalExecutionControlException
        extends IntegrationProblemException {
    private final Reason reason;

    /**
     * Creates one stable integration problem for a persisted control decision.
     *
     * @param reason closed cooperative-stop reason
     * @param correlationId request or worker correlation identifier
     */
    public ScenarioRehearsalExecutionControlException(
            Reason reason,
            String correlationId) {
        super(problem(
                Objects.requireNonNull(reason, "reason"),
                correlationId));
        this.reason = reason;
    }

    /** @return closed cooperative-stop reason */
    public Reason reason() {
        return reason;
    }

    /** Cooperative stop reason and stable failure code. */
    public enum Reason {
        CANCELLED(
                "RG.MIRROR.REHEARSAL_BATCH.CANCELLED",
                "Scenario rehearsal batch cancellation was observed.",
                409),
        DEADLINE_EXCEEDED(
                "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED",
                "Scenario rehearsal batch deadline was observed.",
                410),
        LEASE_LOST(
                "RG.MIRROR.REHEARSAL_BATCH.LEASE_LOST",
                "Scenario rehearsal batch execution authority was lost.",
                409);

        private final String code;
        private final String title;
        private final int status;

        Reason(String code, String title, int status) {
            this.code = code;
            this.title = title;
            this.status = status;
        }

        /** @return stable payload-free failure code */
        public String code() {
            return code;
        }
    }

    private static IntegrationProblem problem(
            Reason reason,
            String correlationId) {
        return switch (reason.status) {
            case 410 -> IntegrationProblem.gone(
                    reason.code,
                    reason.title,
                    normalized(correlationId),
                    Map.of());
            case 409 -> reason == Reason.LEASE_LOST
                    ? IntegrationProblem.retryableConflict(
                    reason.code,
                    reason.title,
                    normalized(correlationId),
                    Map.of("retryAfterSeconds", 1))
                    : IntegrationProblem.conflict(
                    reason.code,
                    reason.title,
                    normalized(correlationId),
                    Map.of());
            default -> throw new IllegalStateException(
                    "Unsupported Scenario execution-control status");
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
