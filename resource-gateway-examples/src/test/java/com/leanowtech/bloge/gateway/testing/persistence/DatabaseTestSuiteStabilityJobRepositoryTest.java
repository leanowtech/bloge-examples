package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobLeaseCheck;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobPrincipal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmission;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueuePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityJobRepositoryTest {

    private ObjectMapper mapper;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DatabaseTestSuiteStabilityJobRepository repository;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-stability-jobs-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseTestSuiteStabilityJobRepository(
                jdbc, mapper, new DataSourceTransactionManager(dataSource));
        repository.init();
    }

    @Test
    void submissionIsScopedIdempotentAndEnforcesTenantAndGlobalCapacity() {
        TestSuiteStabilityQueuePolicy policy = policy(2, 1, 2, 1, 3);
        TestSuiteStabilityJobSubmission tenantA = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);

        TestSuiteStabilityJobRecord first = repository.submit(tenantA, policy);

        assertThat(repository.submit(tenantA, policy)).isEqualTo(first);
        assertThat(repository.find("tenant-a", "test", tenantA.jobId())).contains(first);
        assertThat(repository.find("tenant-b", "test", tenantA.jobId())).isEmpty();
        assertThatThrownBy(() -> repository.submit(
                submission('2', "tenant-a", "request-b",
                        TestSuiteStabilityJobSubmission.Priority.NORMAL), policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason.TENANT_QUEUE_FULL));

        repository.submit(submission('3', "tenant-b", "request-c",
                TestSuiteStabilityJobSubmission.Priority.NORMAL), policy);
        assertThatThrownBy(() -> repository.submit(
                submission('4', "tenant-c", "request-d",
                        TestSuiteStabilityJobSubmission.Priority.NORMAL), policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason.GLOBAL_QUEUE_FULL));
    }

    @Test
    void sameRequestKeyCannotChangeDeadlinePriorityOrPrincipal() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission original = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(original, policy);
        TestSuiteStabilityJobSubmission changed = new TestSuiteStabilityJobSubmission(
                original.jobId(), original.request(), original.requestFingerprint(),
                original.classification(), original.principal(),
                TestSuiteStabilityJobSubmission.Priority.HIGH,
                original.deadlineAt());

        assertThatThrownBy(() -> repository.submit(changed, policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void tenantRoundRobinDominatesWithinTenantPriority() {
        TestSuiteStabilityQueuePolicy policy = policy(20, 10, 1, 1, 3);
        TestSuiteStabilityJobSubmission aLow = submission('1', "tenant-a", "request-a-low",
                TestSuiteStabilityJobSubmission.Priority.LOW);
        TestSuiteStabilityJobSubmission aHigh = submission('2', "tenant-a", "request-a-high",
                TestSuiteStabilityJobSubmission.Priority.HIGH);
        TestSuiteStabilityJobSubmission bLow = submission('3', "tenant-b", "request-b-low",
                TestSuiteStabilityJobSubmission.Priority.LOW);
        repository.submit(aLow, policy);
        repository.submit(aHigh, policy);
        repository.submit(bLow, policy);

        TestSuiteStabilityJobClaim first = repository.claimNext("test", "worker-a", policy);
        assertThat(first.job().jobId()).isEqualTo(aHigh.jobId());
        repository.complete(first.lease(), "stability-result-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('6'), policy);

        TestSuiteStabilityJobClaim second = repository.claimNext("test", "worker-a", policy);
        assertThat(second.job().jobId()).isEqualTo(bLow.jobId());
        repository.complete(second.lease(), "stability-result-b",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);

        assertThat(repository.claimNext("test", "worker-a", policy).job().jobId())
                .isEqualTo(aLow.jobId());
    }

    @Test
    void crossReplicaClaimsCannotExceedTheDatabaseRunningLimit() throws Exception {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 1, 1, 3);
        repository.submit(submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL), policy);
        repository.submit(submission('2', "tenant-b", "request-b",
                TestSuiteStabilityJobSubmission.Priority.NORMAL), policy);
        DatabaseTestSuiteStabilityJobRepository replica =
                new DatabaseTestSuiteStabilityJobRepository(new JdbcTemplate(dataSource), mapper,
                        new DataSourceTransactionManager(dataSource));
        replica.init();
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Future<TestSuiteStabilityJobClaim> left = workers.submit(() -> {
                start.await();
                return repository.claimNext("test", "worker-a", policy);
            });
            Future<TestSuiteStabilityJobClaim> right = workers.submit(() -> {
                start.await();
                return replica.claimNext("test", "worker-b", policy);
            });
            start.countDown();

            assertThat(List.of(left.get().outcome(), right.get().outcome()))
                    .containsExactlyInAnyOrder(TestSuiteStabilityJobClaim.Outcome.ACQUIRED,
                            TestSuiteStabilityJobClaim.Outcome.NO_WORK);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void runningCancellationIsCooperativeAndFencesTerminalPublication() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobClaim claim = repository.claimNext("test", "worker-a", policy);
        String cancellationFingerprint = TestSuiteStabilityProtocolFixtures.fingerprint('7');

        TestSuiteStabilityJobRecord requested = repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a",
                cancellationFingerprint, policy);

        assertThat(requested.status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED);
        TestSuiteStabilityJobLeaseCheck check = repository.checkAndRenew(claim.lease(), policy);
        assertThat(check.decision())
                .isEqualTo(TestSuiteStabilityJobLeaseCheck.Decision.CANCELLED);
        assertThat(repository.find("tenant-a", "test", submission.jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.CANCELLED);
        assertThatThrownBy(() -> repository.complete(claim.lease(), "stability-result-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('8'), policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason.LEASE_LOST));
    }

    @Test
    void queuedCancellationIsImmediateAndItsCommandIsExactlyIdempotent() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        String fingerprint = TestSuiteStabilityProtocolFixtures.fingerprint('7');

        TestSuiteStabilityJobRecord cancelled = repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a", fingerprint, policy);

        assertThat(cancelled.status()).isEqualTo(TestSuiteStabilityJobRecord.Status.CANCELLED);
        assertThat(repository.cancel("tenant-a", "test", submission.jobId(),
                "cancel-a", fingerprint, policy)).isEqualTo(cancelled);
        assertThat(repository.claimNext("test", "worker-a", policy).outcome())
                .isEqualTo(TestSuiteStabilityJobClaim.Outcome.NO_WORK);
        assertThatThrownBy(() -> repository.cancel("tenant-a", "test", submission.jobId(),
                "cancel-b", TestSuiteStabilityProtocolFixtures.fingerprint('8'), policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason.CANCELLATION_CONFLICT));
    }

    @Test
    void retryCannotResurrectACancellationRequestEvenWhenWorkerOrderingIsWrong() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobClaim claim = repository.claimNext("test", "worker-a", policy);
        repository.cancel("tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);

        TestSuiteStabilityJobRecord result = repository.retry(claim.lease(),
                "RG.TEST.STABILITY_JOB_SOURCE_UNAVAILABLE", policy);

        assertThat(result.status()).isEqualTo(TestSuiteStabilityJobRecord.Status.CANCELLED);
        assertThat(result.cancellationRequestId()).isEqualTo("cancel-a");
        assertThat(result.failureCode()).isEqualTo("RG.TEST.STABILITY_JOB_CANCELLED");
    }

    @Test
    void retryExhaustionIsTerminalAndDoesNotRetainWorkerOwnership() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 0);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobClaim claim = repository.claimNext("test", "worker-a", policy);

        TestSuiteStabilityJobRecord failed = repository.retry(claim.lease(),
                "RG.TEST.STABILITY_JOB_SOURCE_UNAVAILABLE", policy);

        assertThat(failed.status()).isEqualTo(TestSuiteStabilityJobRecord.Status.FAILED);
        assertThat(failed.retryCount()).isEqualTo(1);
        assertThat(repository.checkAndRenew(claim.lease(), policy).decision())
                .isEqualTo(TestSuiteStabilityJobLeaseCheck.Decision.LEASE_LOST);
    }

    @Test
    void policyCannotDriftWhileWorkIsRetainedButMayAdvanceAfterTheQueueDrains() {
        TestSuiteStabilityQueuePolicy firstPolicy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityQueuePolicy changed = new TestSuiteStabilityQueuePolicy(
                2, 20, 10, 4, 2, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1), 3, Duration.ofDays(30));
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, firstPolicy);

        assertThatThrownBy(() -> repository.claimNext("test", "worker-a", changed))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason.POLICY_DRIFT));

        repository.cancel("tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), firstPolicy);
        assertThat(repository.claimNext("test", "worker-a", changed).outcome())
                .isEqualTo(TestSuiteStabilityJobClaim.Outcome.NO_WORK);
    }

    @Test
    void queueObservationUsesOnlyClosedStatusesAndPurgePreservesRetainedJobs() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission queued = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        TestSuiteStabilityJobSubmission cancelled = submission('2', "tenant-b", "request-b",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(queued, policy);
        repository.submit(cancelled, policy);
        repository.cancel("tenant-b", "test", cancelled.jobId(), "cancel-b",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);

        var snapshot = repository.observe("test");

        assertThat(snapshot.totals())
                .containsEntry(TestSuiteStabilityJobRecord.Status.QUEUED, 1L)
                .containsEntry(TestSuiteStabilityJobRecord.Status.CANCELLED, 1L)
                .hasSize(TestSuiteStabilityJobRecord.Status.values().length);
        assertThat(snapshot.distinctQueuedTenants()).isEqualTo(1);
        assertThat(snapshot.oldestQueuedAt()).isNotNull();
        assertThat(repository.purgeExpired(1)).isZero();
        assertThat(repository.find("tenant-b", "test", cancelled.jobId())).isPresent();
        assertThat(repository.find("tenant-a", "test", queued.jobId())).isPresent();
    }

    @Test
    void tamperedStoredIntentFailsClosedBeforeItCanBeClaimed() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        jdbc.update("""
                UPDATE rg_test_suite_stability_jobs SET retry_count = 9 WHERE job_id = ?
                """, submission.jobId());

        assertThatThrownBy(() -> repository.find("tenant-a", "test", submission.jobId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
        assertThatThrownBy(() -> repository.claimNext("test", "worker-a", policy))
                .isInstanceOf(IllegalStateException.class);
    }

    private static TestSuiteStabilityQueuePolicy policy(
            int maximumQueued,
            int maximumQueuedPerTenant,
            int maximumRunning,
            int maximumRunningPerTenant,
            int maximumRetries) {
        return new TestSuiteStabilityQueuePolicy(
                1, maximumQueued, maximumQueuedPerTenant,
                maximumRunning, maximumRunningPerTenant,
                Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(1),
                Duration.ofMinutes(1), maximumRetries, Duration.ofDays(30));
    }

    private static TestSuiteStabilityJobSubmission submission(
            char id,
            String tenant,
            String clientRequestId,
            TestSuiteStabilityJobSubmission.Priority priority) {
        TestSuiteStabilityExecutionRequest request = new TestSuiteStabilityExecutionRequest(
                "", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                clientRequestId, 3, Map.of("pipeline", "nightly"));
        return new TestSuiteStabilityJobSubmission(
                "stability-job-" + String.valueOf(id).repeat(64), request,
                TestSuiteStabilityProtocolFixtures.fingerprint('9'), "INTERNAL",
                new TestSuiteStabilityJobPrincipal(
                        tenant, "org-a", "project-a", "test", "sg-1", "SERVICE",
                        "ci-runner", "", "TEST_EXECUTION", "correlation-1",
                        Set.of("test-runners"), "INTERNAL", ""),
                priority, Instant.now().plus(Duration.ofHours(1)));
    }
}
