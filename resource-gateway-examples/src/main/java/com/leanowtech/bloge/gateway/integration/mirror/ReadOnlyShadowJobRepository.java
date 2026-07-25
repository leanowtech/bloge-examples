package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Database-authoritative queue and evidence store for read-only Shadow jobs.
 *
 * <p>Implementations serialize admission and claim per region/environment, reserve each sampling
 * grant ordinal exactly once inside a complete scope, use database time for deadlines and leases,
 * and fence every worker mutation by owner plus epoch. A lease authorizes one logical sample; its
 * stable job id is also the data-plane idempotency identity across crash recovery.</p>
 */
public interface ReadOnlyShadowJobRepository {
    Pattern OWNER_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    Pattern FAILURE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Durable replay disposition for one submission. */
    record Submission(
            ReadOnlyShadowJob job,
            boolean idempotentReplay
    ) {
        /** Requires a concrete integrity-verified projection. */
        public Submission {
            job = Objects.requireNonNull(job, "job");
        }
    }

    /** Exact current worker fence. */
    record Lease(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String ownerId,
            long epoch,
            Instant expiresAt
    ) {
        /** Validates complete positive lease coordinates. */
        public Lease {
            scope = Objects.requireNonNull(scope, "scope");
            jobId = required(jobId, "jobId");
            ownerId = required(ownerId, "ownerId");
            if (!OWNER_ID.matcher(ownerId).matches()
                    || epoch < 1) {
                throw new IllegalArgumentException(
                        "read-only Shadow lease coordinates are invalid");
            }
            expiresAt = Objects.requireNonNull(
                    expiresAt, "expiresAt");
        }
    }

    /** Queue claim disposition. */
    enum ClaimOutcome {
        ACQUIRED,
        NO_WORK
    }

    /** One acquired job with its immutable request, or a bounded no-work observation. */
    record Claim(
            ClaimOutcome outcome,
            Instant observedAt,
            ReadOnlyShadowJob job,
            ReadOnlyShadowJobRequest request,
            Lease lease
    ) {
        /** Enforces acquired-field correspondence. */
        public Claim {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            boolean acquired =
                    outcome == ClaimOutcome.ACQUIRED;
            if (acquired != (job != null
                    && request != null
                    && lease != null)) {
                throw new IllegalArgumentException(
                        "read-only Shadow claim fields are inconsistent");
            }
        }

        /** Creates one database-clock no-work result. */
        public static Claim noWork(Instant observedAt) {
            return new Claim(
                    ClaimOutcome.NO_WORK,
                    observedAt,
                    null,
                    null,
                    null);
        }
    }

    /** Renewed running projection and replacement lease returned by one heartbeat. */
    record Heartbeat(
            ReadOnlyShadowJob job,
            Lease lease
    ) {
        /** Requires exact job/lease coordinate correspondence. */
        public Heartbeat {
            job = Objects.requireNonNull(job, "job");
            lease = Objects.requireNonNull(lease, "lease");
            if (job.status()
                    != ReadOnlyShadowJob.Status.RUNNING
                    || !job.scope().equals(lease.scope())
                    || !job.jobId().equals(lease.jobId())
                    || job.leaseEpoch() != lease.epoch()
                    || !job.leaseExpiresAt().equals(
                    lease.expiresAt())) {
                throw new IllegalArgumentException(
                        "read-only Shadow heartbeat fields are inconsistent");
            }
        }
    }

    /**
     * Reserves a request id and sampling-grant ordinal or recovers an exact retry.
     *
     * @param request immutable payload-free command
     * @param policy server-owned admission and retry policy
     * @return admitted or idempotently recovered job
     */
    Submission submit(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowJobPolicy policy);

    /** Reads one exact job inside a complete scope. */
    Optional<ReadOnlyShadowJob> find(
            CapabilitySnapshot.Scope scope,
            String jobId);

    /** Reads the immutable request for one exact job. */
    Optional<ReadOnlyShadowJobRequest> findRequest(
            CapabilitySnapshot.Scope scope,
            String jobId);

    /** @return an authoritative database-clock observation without claiming or mutating work */
    Instant observedAt();

    /**
     * Claims the next due job in one region/environment partition.
     *
     * @param region exact execution region
     * @param environmentId exact environment
     * @param ownerId credential-free worker identity
     * @param policy current server-owned lease policy
     * @return one owner/epoch fenced claim or no work
     */
    Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
            ReadOnlyShadowJobPolicy policy);

    /**
     * Renews the current lease after the data plane proves cooperative liveness.
     *
     * @return integrity-verified running projection
     */
    Heartbeat heartbeat(
            Lease lease,
            ReadOnlyShadowJobPolicy policy);

    /**
     * Atomically publishes one signed exact comparison and terminal success.
     *
     * @return terminal integrity-verified projection
     */
    ReadOnlyShadowJob complete(
            Lease lease,
            ReadOnlyShadowComparison comparison);

    /**
     * Records a bounded worker failure, requeueing only while deadline and retry budget permit.
     *
     * @param retryable whether the failure class is safe to retry
     * @return resulting queued or terminal projection
     */
    ReadOnlyShadowJob fail(
            Lease lease,
            String failureCode,
            boolean retryable,
            ReadOnlyShadowJobPolicy policy);

    /** Reads and independently re-verifies the terminal signed comparison. */
    Optional<ReadOnlyShadowComparison> findComparison(
            CapabilitySnapshot.Scope scope,
            String jobId);

    /** Closed payload-free persistence rejection vocabulary. */
    enum Reason {
        REQUEST_CONFLICT,
        SAMPLE_ORDINAL_CONFLICT,
        DEADLINE_INVALID,
        JOB_NOT_FOUND,
        LEASE_LOST,
        COMPARISON_MISMATCH,
        STORED_STATE_CORRUPT
    }

    /** Stable repository failure carrying no payload or worker exception detail. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one stable durable-queue violation. */
        public Violation(Reason reason) {
            super("Read-only Shadow job repository rejected: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable rejection reason */
        public Reason reason() {
            return reason;
        }
    }

    private static String required(
            String value,
            String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is required");
        }
        return normalized;
    }
}
