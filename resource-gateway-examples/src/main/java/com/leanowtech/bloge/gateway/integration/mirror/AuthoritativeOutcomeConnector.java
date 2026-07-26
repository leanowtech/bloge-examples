package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Customer-specific payload-isolated connector for advancing one outcome observation watermark.
 *
 * <p>The connector receives an already fully verified current revision and may return either no
 * change or one unsigned successor candidate. It owns access to customer outcome authorities but
 * does not own Resource Gateway signing, lineage admission, retry policy, scheduling, or profile
 * publication. Raw payloads, credentials, and source exceptions must not cross this boundary.</p>
 */
public interface AuthoritativeOutcomeConnector {
    /** @return whether the customer authority path can currently be queried */
    boolean ready();

    /**
     * Reconciles one exact current head at a database-time cut.
     *
     * @param current independently verified current observation
     * @param observedAt database-time worker cut
     * @param control cooperative lease heartbeat boundary
     * @return no change or one structurally valid unsigned successor candidate
     * @throws Failure bounded connector failure without customer data or exception detail
     */
    Result reconcile(
            AuthoritativeOutcomeObservation current,
            Instant observedAt,
            ExecutionControl control);

    /** Cooperative liveness boundary available during one connector turn. */
    interface ExecutionControl {
        /** @return current database-time lease expiry */
        Instant leaseExpiresAt();

        /** Renews the exact owner/epoch fence after cooperative liveness is proven. */
        Instant heartbeat();
    }

    /** One bounded connector reconciliation result. */
    record Result(
            Disposition disposition,
            AuthoritativeOutcomeObservation successor
    ) {
        /** Enforces successor presence only for the changed disposition. */
        public Result {
            disposition = Objects.requireNonNull(
                    disposition, "disposition");
            if ((disposition == Disposition.SUCCESSOR)
                    != (successor != null)) {
                throw new IllegalArgumentException(
                        "authoritative outcome connector result is inconsistent");
            }
        }

        /** Creates a no-change result. */
        public static Result noChange() {
            return new Result(
                    Disposition.NO_CHANGE, null);
        }

        /** Creates a successor result. */
        public static Result successor(
                AuthoritativeOutcomeObservation value) {
            return new Result(
                    Disposition.SUCCESSOR,
                    Objects.requireNonNull(value, "value"));
        }

        /** @return optional successor candidate */
        public Optional<AuthoritativeOutcomeObservation>
        successorOptional() {
            return Optional.ofNullable(successor);
        }
    }

    /** Connector result disposition. */
    enum Disposition {
        NO_CHANGE,
        SUCCESSOR
    }

    /** Closed connector failure classes with server-owned retry classification. */
    enum FailureReason {
        AUTHORITY_UNAVAILABLE(true),
        WATERMARK_UNAVAILABLE(true),
        RATE_LIMITED(true),
        SOURCE_REJECTED(false),
        RESPONSE_INVALID(false);

        private final boolean retryable;

        FailureReason(boolean retryable) {
            this.retryable = retryable;
        }

        /** @return whether retrying can be safe without changing business semantics */
        public boolean retryable() {
            return retryable;
        }
    }

    /** Stable payload-free connector failure. */
    final class Failure extends RuntimeException {
        private final FailureReason reason;

        /** Creates one bounded connector failure. */
        public Failure(FailureReason reason) {
            super("Authoritative outcome connector failed: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable connector failure reason */
        public FailureReason reason() {
            return reason;
        }
    }
}
