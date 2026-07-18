package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * Database-authoritative interface for asynchronous suite-stability parent jobs.
 *
 * <p>Submission capacity, tenant rotation, aging, worker fencing, cooperative cancellation,
 * deadline transition, retry backoff, and terminal publication are all decided against database
 * time. Implementations must serialize claims per environment so independently deployed replicas
 * cannot exceed configured running limits or advance different fairness cursors.</p>
 */
public interface TestSuiteStabilityJobRepository {

    /**
     * Submits or exactly replays one job after enforcing current database queue capacity.
     *
     * @param submission exact authenticated job intent
     * @param policy active cross-replica queue policy
     * @return original retained job
     */
    TestSuiteStabilityJobRecord submit(
            TestSuiteStabilityJobSubmission submission,
            TestSuiteStabilityQueuePolicy policy);

    /**
     * Submits a job while preserving whether the durable command was newly admitted or replayed.
     *
     * <p>The default keeps non-database test doubles source compatible. Authoritative
     * implementations should override this method so admission and replay disposition are decided
     * in the same serialized transaction.</p>
     *
     * @param submission exact authenticated job intent
     * @param policy active cross-replica queue policy
     * @return retained job and transaction-authoritative replay disposition
     */
    default SubmissionResult submitDetailed(
            TestSuiteStabilityJobSubmission submission,
            TestSuiteStabilityQueuePolicy policy) {
        return new SubmissionResult(submit(submission, policy), false);
    }

    /** Payload-free result of one serialized queue admission command. */
    record SubmissionResult(
            TestSuiteStabilityJobRecord job,
            boolean idempotentReplay) {

        /** Requires a concrete retained job for both fresh and replayed commands. */
        public SubmissionResult {
            job = java.util.Objects.requireNonNull(job, "job");
        }
    }

    /**
     * Claims at most one job using tenant round-robin and within-tenant aged priority.
     *
     * @param environmentId server-owned test or staging queue
     * @param ownerId server-owned worker identity
     * @param policy active cross-replica queue policy
     * @return acquired job or bounded no-work observation
     */
    TestSuiteStabilityJobClaim claimNext(
            String environmentId,
            String ownerId,
            TestSuiteStabilityQueuePolicy policy);

    /**
     * Renews an exact lease or atomically applies cancellation/deadline stop state.
     *
     * @param lease latest exact worker fence
     * @param policy active cross-replica queue policy
     * @return renewed fence or terminal stop decision
     */
    TestSuiteStabilityJobLeaseCheck checkAndRenew(
            TestSuiteStabilityJobLease lease,
            TestSuiteStabilityQueuePolicy policy);

    /**
     * Linearizes the final cancellation/deadline check before signed parent publication.
     *
     * <p>After this transition a cancellation is explicitly too late. An expired committing lease
     * is recoverable and may replay an already-published idempotent parent result.</p>
     *
     * @param lease latest exact worker fence
     * @param policy active cross-replica queue policy
     * @return prepared {@code COMMITTING} lease or exact terminal/fenced winner
     */
    TestSuiteStabilityJobCompletionPreparation prepareCompletion(
            TestSuiteStabilityJobLease lease,
            TestSuiteStabilityQueuePolicy policy);

    /**
     * Returns an exact owned job to the queue with bounded deterministic backoff.
     *
     * @param lease latest exact worker fence
     * @param failureCode bounded infrastructure diagnostic
     * @param policy active cross-replica queue policy
     * @return queued successor or terminal failed job after retry exhaustion
     */
    TestSuiteStabilityJobRecord retry(
            TestSuiteStabilityJobLease lease,
            String failureCode,
            TestSuiteStabilityQueuePolicy policy);

    /**
     * Fails one exact owned job without another retry.
     *
     * @param lease latest exact worker fence
     * @param failureCode bounded deterministic diagnostic
     * @param policy active cross-replica queue policy
     * @return terminal failed job
     */
    TestSuiteStabilityJobRecord fail(
            TestSuiteStabilityJobLease lease,
            String failureCode,
            TestSuiteStabilityQueuePolicy policy);

    /**
     * Publishes the signed parent result under an exact live queue fence.
     *
     * @param lease latest exact worker fence
     * @param stabilityRunId signed parent execution identity
     * @param evidenceFingerprint signed parent evidence identity
     * @param policy active cross-replica queue policy
     * @return terminal succeeded job
     */
    TestSuiteStabilityJobRecord complete(
            TestSuiteStabilityJobLease lease,
            String stabilityRunId,
            String evidenceFingerprint,
            TestSuiteStabilityQueuePolicy policy);

    /**
     * Requests idempotent cancellation and atomically appends its semantic audit event.
     *
     * <p>The audit factory is invoked exactly once for the first accepted command and inside the
     * same transaction as the resulting queue state. Exact replay returns the retained state
     * without another audit write. Implementations must roll back the queue mutation when the
     * factory is absent, returns no mutation, or its mutation fails.</p>
     *
     * @param command exact current actor and cancellation intent
     * @param policy active cross-replica queue policy
     * @param committedAudit transaction-bound audit mutation derived from the database result
     * @return resulting job and transaction-authoritative replay disposition
     */
    CancellationResult cancel(
            TestSuiteStabilityJobCancellationCommand command,
            TestSuiteStabilityQueuePolicy policy,
            Function<TestSuiteStabilityJobCancellationReceipt,
                    TestRuntimeTransactionMutation> committedAudit);

    /** Payload-free result of one serialized cancellation command. */
    record CancellationResult(
            TestSuiteStabilityJobRecord job,
            boolean idempotentReplay) {

        /** Requires a retained job for fresh and replayed cancellation commands. */
        public CancellationResult {
            job = java.util.Objects.requireNonNull(job, "job");
        }
    }

    /** Resolves one integrity-verified job inside its exact tenant and environment scope. */
    Optional<TestSuiteStabilityJobRecord> find(
            String tenantId, String environmentId, String jobId);

    /** Returns a payload-free fixed-cardinality queue observation for one environment. */
    TestSuiteStabilityQueueSnapshot observe(String environmentId);

    /**
     * Replaces expired terminal jobs with non-reversible idempotency tombstones and purges expired
     * tombstones in one bounded transaction.
     *
     * @param tombstoneRetention request-key reservation after detailed job erasure
     * @param limit independent job and tombstone page bound
     * @return committed aggregate or normal live-lease contention
     */
    TestSuiteStabilityJobRetentionAttempt retainExpired(
            Duration tombstoneRetention, int limit);

    /** Returns one business-identity-free database-clock retention lifecycle snapshot. */
    TestSuiteStabilityJobRetentionSnapshot observeRetention();
}
