package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Optional;

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
     * Requests idempotent cancellation without disclosing another scope's job.
     *
     * @param tenantId verified tenant
     * @param environmentId verified environment
     * @param jobId governed job identity
     * @param clientRequestId cancellation idempotency key
     * @param commandFingerprint canonical cancellation command
     * @param policy active cross-replica queue policy
     * @return immediate cancellation, cooperative request, or existing terminal job
     */
    TestSuiteStabilityJobRecord cancel(
            String tenantId,
            String environmentId,
            String jobId,
            String clientRequestId,
            String commandFingerprint,
            TestSuiteStabilityQueuePolicy policy);

    /** Resolves one integrity-verified job inside its exact tenant and environment scope. */
    Optional<TestSuiteStabilityJobRecord> find(
            String tenantId, String environmentId, String jobId);

    /** Returns a payload-free fixed-cardinality queue observation for one environment. */
    TestSuiteStabilityQueueSnapshot observe(String environmentId);

    /** Deletes at most {@code limit} oldest terminal jobs past retention. */
    int purgeExpired(int limit);
}
