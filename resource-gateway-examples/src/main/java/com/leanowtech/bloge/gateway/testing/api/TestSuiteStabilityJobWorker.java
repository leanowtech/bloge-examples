package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Bounded synchronous worker for one durable suite-stability queue poll.
 *
 * <p>A local fair semaphore is acquired before durable claim, so a process never owns more jobs
 * than it can execute. Every acquired job is reauthorized against current policy and executed
 * through {@link TestSuiteStabilityJobExecutionCoordinator}. This class owns failure
 * classification; it never mutates a queue after lease loss or control ambiguity.</p>
 */
public final class TestSuiteStabilityJobWorker {

    private static final Pattern OWNER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,210}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    private final TestSuiteStabilityJobRepository repository;
    private final TestSuiteStabilityExecutionService executions;
    private final TestSuiteStabilityJobExecutionCoordinator coordinator;
    private final TestSuiteStabilityJobAuthorizer authorizer;
    private final TestSuiteStabilityQueuePolicy policy;
    private final String ownerId;
    private final Semaphore localSlots;

    /**
     * @param repository database-authoritative job queue
     * @param executions existing controlled stability algorithm
     * @param coordinator queue heartbeat and checkpoint guard
     * @param authorizer current-authority revalidation boundary
     * @param policy active cross-replica queue policy
     * @param ownerId stable process worker identity
     * @param maximumLocalExecutions maximum concurrently owned jobs in this process
     */
    public TestSuiteStabilityJobWorker(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityExecutionService executions,
            TestSuiteStabilityJobExecutionCoordinator coordinator,
            TestSuiteStabilityJobAuthorizer authorizer,
            TestSuiteStabilityQueuePolicy policy,
            String ownerId,
            int maximumLocalExecutions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ownerId = normalized(ownerId);
        if (!OWNER.matcher(this.ownerId).matches()) {
            throw new IllegalArgumentException("Invalid stability job worker owner id");
        }
        if (maximumLocalExecutions <= 0 || maximumLocalExecutions > 1_024) {
            throw new IllegalArgumentException(
                    "Stability job local concurrency must be between 1 and 1024");
        }
        localSlots = new Semaphore(maximumLocalExecutions, true);
    }

    /**
     * Claims and fully handles at most one eligible job on the caller thread.
     *
     * <p>The method never waits for local capacity. A future scheduler may call it concurrently;
     * lack of a slot is a bounded observation and cannot create a durable lease.</p>
     *
     * @param environmentId exact {@code test} or {@code staging} queue
     * @return payload-free bounded work result
     */
    public TestSuiteStabilityJobWorkResult processNext(String environmentId) {
        String environment = normalized(environmentId);
        if (!Set.of("test", "staging").contains(environment)) {
            throw new IllegalArgumentException(
                    "Stability job worker requires test or staging environment");
        }
        if (!localSlots.tryAcquire()) {
            return TestSuiteStabilityJobWorkResult.localCapacity();
        }
        try {
            TestSuiteStabilityJobClaim claim;
            try {
                claim = repository.claimNext(environment, ownerId, policy);
            } catch (RuntimeException unavailable) {
                return TestSuiteStabilityJobWorkResult.queueUnavailable();
            }
            if (claim == null || claim.outcome() == TestSuiteStabilityJobClaim.Outcome.NO_WORK) {
                return claim == null
                        ? TestSuiteStabilityJobWorkResult.queueUnavailable()
                        : TestSuiteStabilityJobWorkResult.noWork();
            }
            return execute(claim);
        } finally {
            localSlots.release();
        }
    }

    private TestSuiteStabilityJobWorkResult execute(TestSuiteStabilityJobClaim claim) {
        TestSuiteStabilityJobRecord job = claim.job();
        String jobId = job.jobId();
        try (TestSuiteStabilityJobExecutionCoordinator.ExecutionGuard guard =
                     coordinator.monitor(job, claim.lease(), policy)) {
            TestSuiteStabilityJobAuthorizer.Authorization authorization = authorize(job);
            if (authorization.decision()
                    != TestSuiteStabilityJobAuthorizer.Decision.AUTHORIZED) {
                return authorizationFailure(guard, authorization);
            }
            try {
                TestSuiteStabilityExecutionResponse response = executions.executeControlled(
                        job.request().suiteRef().suiteId(), job.request(),
                        job.principal().toContext(), guard);
                if (response == null) {
                    return mutateAfterFailure(guard, true,
                            "RG.TEST.STABILITY_JOB_EXECUTION_UNAVAILABLE");
                }
                TestSuiteStabilityJobLease completionLease = guard.leaseForCompletion();
                TestSuiteStabilityJobRecord completed = repository.complete(
                        completionLease, response.stabilityRunId(),
                        response.evidenceFingerprint(), policy);
                if (completed.status() != TestSuiteStabilityJobRecord.Status.SUCCEEDED) {
                    return result(completed,
                            "RG.TEST.STABILITY_JOB_COMPLETION_CONFLICT", false);
                }
                guard.completed();
                return TestSuiteStabilityJobWorkResult.succeeded(jobId);
            } catch (TestSuiteStabilityJobExecutionCoordinator.ControlException stopped) {
                return controlResult(jobId, stopped);
            } catch (IntegrationProblemException problem) {
                return mutateAfterFailure(guard, problem.problem().retryable(),
                        failureCode(problem.problem().code(),
                                "RG.TEST.STABILITY_JOB_EXECUTION_FAILED"));
            } catch (RuntimeException unavailable) {
                return mutateAfterFailure(guard, true,
                        guard.publicationPrepared()
                                ? "RG.TEST.STABILITY_JOB_PUBLICATION_UNAVAILABLE"
                                : "RG.TEST.STABILITY_JOB_EXECUTION_UNAVAILABLE");
            }
        } catch (TestSuiteStabilityJobExecutionCoordinator.ControlException stopped) {
            return controlResult(jobId, stopped);
        } catch (RuntimeException unavailable) {
            return TestSuiteStabilityJobWorkResult.stopped(
                    TestSuiteStabilityJobWorkResult.Outcome.CONTROL_UNAVAILABLE,
                    jobId, "RG.TEST.STABILITY_JOB_CONTROL_UNAVAILABLE");
        }
    }

    private TestSuiteStabilityJobAuthorizer.Authorization authorize(
            TestSuiteStabilityJobRecord job) {
        try {
            TestSuiteStabilityJobAuthorizer.Authorization result =
                    authorizer.reauthorize(job);
            return result == null
                    ? TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                    "RG.TEST.STABILITY_JOB_AUTHORIZATION_UNAVAILABLE")
                    : result;
        } catch (RuntimeException unavailable) {
            return TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                    "RG.TEST.STABILITY_JOB_AUTHORIZATION_UNAVAILABLE");
        }
    }

    private TestSuiteStabilityJobWorkResult authorizationFailure(
            TestSuiteStabilityJobExecutionCoordinator.ExecutionGuard guard,
            TestSuiteStabilityJobAuthorizer.Authorization authorization) {
        boolean revoked = authorization.decision()
                == TestSuiteStabilityJobAuthorizer.Decision.REVOKED;
        try {
            TestSuiteStabilityJobLease lease = guard.leaseForAdministrativeMutation();
            TestSuiteStabilityJobRecord terminal = revoked
                    ? repository.fail(lease, authorization.failureCode(), policy)
                    : repository.retry(lease, authorization.failureCode(), policy);
            return result(terminal, authorization.failureCode(), revoked);
        } catch (TestSuiteStabilityJobExecutionCoordinator.ControlException stopped) {
            return controlResult(guard.job().jobId(), stopped);
        } catch (RuntimeException unavailable) {
            return TestSuiteStabilityJobWorkResult.stopped(
                    TestSuiteStabilityJobWorkResult.Outcome.CONTROL_UNAVAILABLE,
                    guard.job().jobId(), "RG.TEST.STABILITY_JOB_CONTROL_UNAVAILABLE");
        }
    }

    private TestSuiteStabilityJobWorkResult mutateAfterFailure(
            TestSuiteStabilityJobExecutionCoordinator.ExecutionGuard guard,
            boolean retryable,
            String failureCode) {
        String code = failureCode(failureCode,
                "RG.TEST.STABILITY_JOB_EXECUTION_FAILED");
        try {
            TestSuiteStabilityJobLease lease = guard.executionBound()
                    ? guard.leaseForMutation()
                    : guard.leaseForAdministrativeMutation();
            TestSuiteStabilityJobRecord terminal =
                    retryable || guard.publicationPrepared()
                            ? repository.retry(lease, guard.publicationPrepared()
                            ? "RG.TEST.STABILITY_JOB_PUBLICATION_UNAVAILABLE" : code, policy)
                            : repository.fail(lease, code, policy);
            return result(terminal, code, false);
        } catch (TestSuiteStabilityJobExecutionCoordinator.ControlException stopped) {
            return controlResult(guard.job().jobId(), stopped);
        } catch (RuntimeException unavailable) {
            return TestSuiteStabilityJobWorkResult.stopped(
                    TestSuiteStabilityJobWorkResult.Outcome.CONTROL_UNAVAILABLE,
                    guard.job().jobId(), "RG.TEST.STABILITY_JOB_CONTROL_UNAVAILABLE");
        }
    }

    private static TestSuiteStabilityJobWorkResult result(
            TestSuiteStabilityJobRecord record,
            String failureCode,
            boolean authorizationRevoked) {
        String jobId = record.jobId();
        return switch (record.status()) {
            case SUCCEEDED -> TestSuiteStabilityJobWorkResult.stopped(
                    TestSuiteStabilityJobWorkResult.Outcome.PARENT_COMPLETED,
                    jobId, "RG.TEST.STABILITY_JOB_PARENT_COMPLETED");
            case QUEUED, COMMITTING -> TestSuiteStabilityJobWorkResult.stopped(
                    TestSuiteStabilityJobWorkResult.Outcome.RETRIED,
                    jobId, failureCode(record.failureCode(),
                            failureCode(failureCode,
                                    "RG.TEST.STABILITY_JOB_RETRY_SCHEDULED")));
            case FAILED, QUARANTINED -> TestSuiteStabilityJobWorkResult.stopped(
                    authorizationRevoked
                            ? TestSuiteStabilityJobWorkResult.Outcome.AUTHORIZATION_REVOKED
                            : TestSuiteStabilityJobWorkResult.Outcome.FAILED,
                    jobId, failureCode(record.failureCode(), failureCode));
            case CANCELLED -> TestSuiteStabilityJobWorkResult.stopped(
                    TestSuiteStabilityJobWorkResult.Outcome.CANCELLED,
                    jobId, "RG.TEST.STABILITY_JOB_CANCELLED");
            case EXPIRED -> TestSuiteStabilityJobWorkResult.stopped(
                    TestSuiteStabilityJobWorkResult.Outcome.DEADLINE_EXCEEDED,
                    jobId, "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED");
            case RUNNING, CANCEL_REQUESTED -> TestSuiteStabilityJobWorkResult.stopped(
                    TestSuiteStabilityJobWorkResult.Outcome.CONTROL_UNAVAILABLE,
                    jobId, "RG.TEST.STABILITY_JOB_CONTROL_UNAVAILABLE");
        };
    }

    private static TestSuiteStabilityJobWorkResult controlResult(
            String jobId,
            TestSuiteStabilityJobExecutionCoordinator.ControlException stopped) {
        TestSuiteStabilityJobWorkResult.Outcome outcome = switch (stopped.reason()) {
            case CANCELLED -> TestSuiteStabilityJobWorkResult.Outcome.CANCELLED;
            case DEADLINE_EXCEEDED ->
                    TestSuiteStabilityJobWorkResult.Outcome.DEADLINE_EXCEEDED;
            case PARENT_COMPLETED -> TestSuiteStabilityJobWorkResult.Outcome.PARENT_COMPLETED;
            case LEASE_LOST -> TestSuiteStabilityJobWorkResult.Outcome.LEASE_LOST;
            case STORE_UNAVAILABLE, DESCRIPTOR_MISMATCH, COORDINATOR_CLOSED ->
                    TestSuiteStabilityJobWorkResult.Outcome.CONTROL_UNAVAILABLE;
        };
        return TestSuiteStabilityJobWorkResult.stopped(
                outcome, jobId, stopped.failureCode());
    }

    private static String failureCode(String candidate, String fallback) {
        String normalized = normalized(candidate).toUpperCase(java.util.Locale.ROOT);
        if (CODE.matcher(normalized).matches()) {
            return normalized;
        }
        String safeFallback = normalized(fallback).toUpperCase(java.util.Locale.ROOT);
        if (!CODE.matcher(safeFallback).matches()) {
            throw new IllegalArgumentException("Invalid stability worker fallback code");
        }
        return safeFallback;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
