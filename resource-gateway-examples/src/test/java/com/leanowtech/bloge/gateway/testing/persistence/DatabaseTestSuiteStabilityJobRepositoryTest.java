package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobCompletionPreparation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobLeaseCheck;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobParentAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobPrincipal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmission;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueuePolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueueSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private FakeParentAuthority parentAuthority;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-stability-jobs-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        parentAuthority = new FakeParentAuthority();
        repository = new DatabaseTestSuiteStabilityJobRepository(
                jdbc, mapper, parentAuthority, requestKeys("key-a"),
                new DataSourceTransactionManager(dataSource));
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
        var firstTerminalLease = repository.prepareCompletion(first.lease(), policy).lease();
        repository.complete(firstTerminalLease, "stability-result-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('6'), policy);

        TestSuiteStabilityJobClaim second = repository.claimNext("test", "worker-a", policy);
        assertThat(second.job().jobId()).isEqualTo(bLow.jobId());
        var secondTerminalLease = repository.prepareCompletion(second.lease(), policy).lease();
        repository.complete(secondTerminalLease, "stability-result-b",
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
                        parentAuthority, requestKeys("key-a"),
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
        assertThat(parentAuthority.invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.status())
                    .isEqualTo(TestSuiteStabilityJobRecord.Status.QUEUED);
            assertThat(invocation.reason())
                    .isEqualTo(TestSuiteStabilityExecutionStop.Reason.CANCELLED);
            assertThat(invocation.failureCode())
                    .isEqualTo("RG.TEST.STABILITY_JOB_CANCELLED");
            assertThat(invocation.retention()).isEqualTo(policy.terminalRetention());
        });
    }

    @Test
    void signedParentWinnerConvergesQueuedCancellationToSuccess() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        parentAuthority.resolution = TestSuiteStabilityJobParentAuthority.Resolution.completed(
                "stability-signed-winner",
                TestSuiteStabilityProtocolFixtures.fingerprint('6'));

        TestSuiteStabilityJobRecord result = repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);

        assertThat(result.status()).isEqualTo(TestSuiteStabilityJobRecord.Status.SUCCEEDED);
        assertThat(result.terminalStabilityRunId()).isEqualTo("stability-signed-winner");
        assertThat(result.terminalEvidenceFingerprint())
                .isEqualTo(TestSuiteStabilityProtocolFixtures.fingerprint('6'));
        assertThat(result.cancellationRequestId()).isBlank();
        assertThat(result.failureCode()).isBlank();
    }

    @Test
    void parentAuthorityFailureRollsBackQueuedCancellation() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        parentAuthority.failure = new IllegalStateException("parent stop unavailable");

        assertThatThrownBy(() -> repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent stop unavailable");

        assertThat(repository.find("tenant-a", "test", submission.jobId()))
                .get().satisfies(retained -> {
                    assertThat(retained.status())
                            .isEqualTo(TestSuiteStabilityJobRecord.Status.QUEUED);
                    assertThat(retained.cancellationRequestId()).isBlank();
                    assertThat(retained.failureCode()).isBlank();
                });
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
    void completionPreparationReturnsTypedCancellationAndParentWinnerDecisions() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission cancelled = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(cancelled, policy);
        TestSuiteStabilityJobClaim cancelledClaim =
                repository.claimNext("test", "worker-a", policy);
        repository.cancel("tenant-a", "test", cancelled.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);

        var cancellation = repository.prepareCompletion(cancelledClaim.lease(), policy);

        assertThat(cancellation.decision()).isEqualTo(
                TestSuiteStabilityJobCompletionPreparation.Decision.CANCELLED);
        assertThat(cancellation.lease()).isNull();
        assertThat(cancellation.failureCode()).isEqualTo(
                "RG.TEST.STABILITY_JOB_CANCELLED");

        TestSuiteStabilityJobSubmission completed = submission('2', "tenant-b", "request-b",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(completed, policy);
        TestSuiteStabilityJobClaim completedClaim =
                repository.claimNext("test", "worker-a", policy);
        repository.cancel("tenant-b", "test", completed.jobId(), "cancel-b",
                TestSuiteStabilityProtocolFixtures.fingerprint('8'), policy);
        parentAuthority.resolution = TestSuiteStabilityJobParentAuthority.Resolution.completed(
                "stability-parent", TestSuiteStabilityProtocolFixtures.fingerprint('6'));

        var parentWinner = repository.prepareCompletion(completedClaim.lease(), policy);

        assertThat(parentWinner.decision()).isEqualTo(
                TestSuiteStabilityJobCompletionPreparation.Decision.PARENT_COMPLETED);
        assertThat(parentWinner.failureCode()).isEqualTo(
                "RG.TEST.STABILITY_JOB_PARENT_COMPLETED");
    }

    @Test
    void completionPreparationReturnsTypedLeaseLossForAStaleFence() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobClaim claim = repository.claimNext("test", "worker-a", policy);

        var prepared = repository.prepareCompletion(claim.lease(), policy);
        var stale = repository.prepareCompletion(claim.lease(), policy);

        assertThat(prepared.decision()).isEqualTo(
                TestSuiteStabilityJobCompletionPreparation.Decision.PREPARED);
        assertThat(stale.decision()).isEqualTo(
                TestSuiteStabilityJobCompletionPreparation.Decision.LEASE_LOST);
        assertThat(stale.failureCode()).isEqualTo("RG.TEST.STABILITY_JOB_LEASE_LOST");
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
        assertThat(parentAuthority.invocations).singleElement()
                .extracting(FakeParentAuthority.Invocation::reason)
                .isEqualTo(TestSuiteStabilityExecutionStop.Reason.WORKER_FAILED);
    }

    @Test
    void retryExhaustionCannotCommitBeforeParentStopAuthority() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 0);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobClaim claim = repository.claimNext("test", "worker-a", policy);
        parentAuthority.failure = new IllegalStateException("parent stop unavailable");

        assertThatThrownBy(() -> repository.retry(claim.lease(),
                "RG.TEST.STABILITY_JOB_SOURCE_UNAVAILABLE", policy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent stop unavailable");

        assertThat(repository.find("tenant-a", "test", submission.jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.RUNNING);
        parentAuthority.failure = null;
        assertThat(repository.checkAndRenew(claim.lease(), policy).decision())
                .isEqualTo(TestSuiteStabilityJobLeaseCheck.Decision.CONTINUE);
    }

    @Test
    void committingIsTheCancellationLinearizationPointAndCanBeRetriedAfterCrash()
            throws InterruptedException {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobClaim claim = repository.claimNext("test", "worker-a", policy);

        var committingLease = repository.prepareCompletion(claim.lease(), policy).lease();

        assertThat(repository.find("tenant-a", "test", submission.jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);
        assertThat(repository.cancel("tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy).status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);
        assertThat(repository.retry(committingLease,
                "RG.TEST.STABILITY_JOB_PUBLICATION_UNAVAILABLE", policy).status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);
        Thread.sleep(1_100);

        TestSuiteStabilityJobClaim recovery =
                repository.claimNext("test", "worker-b", policy);

        assertThat(recovery.outcome()).isEqualTo(TestSuiteStabilityJobClaim.Outcome.ACQUIRED);
        assertThat(recovery.job().status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);
        assertThat(recovery.lease().epoch()).isEqualTo(committingLease.epoch() + 1);
        assertThat(repository.cancel("tenant-a", "test", submission.jobId(), "cancel-b",
                TestSuiteStabilityProtocolFixtures.fingerprint('8'), policy).status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);
        assertThatThrownBy(() -> repository.fail(recovery.lease(),
                "RG.TEST.STABILITY_JOB_PUBLICATION_FAILED", policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason.TERMINAL_CONFLICT));
    }

    @Test
    void queueSuccessCannotCommitWithoutAnExactParentCompletionProof() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobClaim claim = repository.claimNext("test", "worker-a", policy);
        var committing = repository.prepareCompletion(claim.lease(), policy).lease();
        parentAuthority.completionFailure =
                new IllegalStateException("parent completion unavailable");

        assertThatThrownBy(() -> repository.complete(committing, "stability-result-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('6'), policy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent completion unavailable");

        assertThat(repository.find("tenant-a", "test", submission.jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);
        parentAuthority.completionFailure = null;
        assertThat(repository.checkAndRenew(committing, policy).decision())
                .isEqualTo(TestSuiteStabilityJobLeaseCheck.Decision.CONTINUE);
    }

    @Test
    void contradictoryParentCompletionProofFailsClosed() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobClaim claim = repository.claimNext("test", "worker-a", policy);
        var committing = repository.prepareCompletion(claim.lease(), policy).lease();
        parentAuthority.completionResolution = TestSuiteStabilityJobParentAuthority.Resolution
                .completed("stability-other", TestSuiteStabilityProtocolFixtures.fingerprint('7'));

        assertThatThrownBy(() -> repository.complete(committing, "stability-result-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('6'), policy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contradictory completion proof");
        assertThat(repository.find("tenant-a", "test", submission.jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);
    }

    @Test
    void policyCannotDriftWhileWorkIsRetainedButMayAdvanceAfterTheQueueDrains() {
        TestSuiteStabilityQueuePolicy firstPolicy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityQueuePolicy changed = new TestSuiteStabilityQueuePolicy(
                2, 20, 10, 4, 2, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1), 3, Duration.ofDays(7),
                Duration.ofDays(30));
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
        assertThat(repository.retainExpired(Duration.ofDays(365), 1).jobsTombstoned())
                .isZero();
        assertThat(repository.find("tenant-b", "test", cancelled.jobId())).isPresent();
        assertThat(repository.find("tenant-a", "test", queued.jobId())).isPresent();
    }

    @Test
    void retentionReplacesDetailWithNonReversibleReplayTombstone() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobRecord cancelled = repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);
        expireJob(cancelled);

        var retained = repository.retainExpired(Duration.ofDays(365), 10);

        assertThat(retained.jobsTombstoned()).isEqualTo(1);
        assertThat(retained.tombstonesPurged()).isZero();
        assertThat(repository.find("tenant-a", "test", submission.jobId())).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_job_tombstones",
                Long.class)).isEqualTo(1L);
        Map<String, Object> tombstone = jdbc.queryForMap(
                "SELECT * FROM rg_test_suite_stability_job_tombstones");
        assertThat(tombstone.keySet()).doesNotContain("CLIENT_REQUEST_ID", "JOB_ID");
        assertThat(tombstone.values()).noneMatch(
                value -> "request-a".equals(String.valueOf(value)));

        assertThatThrownBy(() -> repository.submit(submission, policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason
                                        .REPLAY_WINDOW_EXPIRED));

        TestSuiteStabilityJobSubmission changed = new TestSuiteStabilityJobSubmission(
                submission.jobId(), submission.request(), submission.requestFingerprint(),
                submission.classification(), submission.principal(),
                TestSuiteStabilityJobSubmission.Priority.HIGH, submission.deadlineAt());
        assertThatThrownBy(() -> repository.submit(changed, policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason
                                        .IDEMPOTENCY_CONFLICT));
    }

    @Test
    void keyRotationReadsOldTombstonesAndMissingLiveGenerationFailsStartup() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobRecord cancelled = repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);
        expireJob(cancelled);
        repository.retainExpired(Duration.ofDays(365), 10);

        var rotatedKeys = new TestSuiteStabilityJobRequestKeyProtector(
                "key-b", Map.of("key-a", new byte[32], "key-b", bytes(32)));
        DatabaseTestSuiteStabilityJobRepository rotated = repository(rotatedKeys);
        rotated.init();

        assertThatThrownBy(() -> rotated.submit(submission, policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason
                                        .REPLAY_WINDOW_EXPIRED));

        DatabaseTestSuiteStabilityJobRepository missingOld = repository(
                new TestSuiteStabilityJobRequestKeyProtector(
                        "key-b", Map.of("key-b", bytes(32))));
        assertThatThrownBy(missingOld::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable key");
    }

    @Test
    void corruptExpiredSourceRollsBackTombstoneInsertionAndDetailDeletion() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobRecord cancelled = repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);
        expireJob(cancelled);
        jdbc.update("""
                UPDATE rg_test_suite_stability_jobs
                SET failure_code = 'RG.TEST.TAMPERED'
                WHERE job_id = ?
                """, submission.jobId());

        assertThatThrownBy(() -> repository.retainExpired(Duration.ofDays(365), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_jobs", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_job_tombstones", Long.class))
                .isZero();
    }

    @Test
    void corruptLiveTombstoneFailsClosedWithoutRecreatingAJob() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobRecord cancelled = repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);
        expireJob(cancelled);
        repository.retainExpired(Duration.ofDays(365), 10);
        jdbc.update("""
                UPDATE rg_test_suite_stability_job_tombstones
                SET submission_fingerprint = ?
                """, TestSuiteStabilityProtocolFixtures.fingerprint('8'));

        assertThatThrownBy(() -> repository.submit(submission, policy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_jobs", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_suite_stability_job_tombstones", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void expiredTombstoneIsPurgedBeforeItsRequestIdentityCanBeReused() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        TestSuiteStabilityJobRecord cancelled = repository.cancel(
                "tenant-a", "test", submission.jobId(), "cancel-a",
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy);
        expireJob(cancelled);
        repository.retainExpired(Duration.ofDays(365), 10);
        expireTombstone();

        var retained = repository.retainExpired(Duration.ofDays(365), 10);

        assertThat(retained.jobsTombstoned()).isZero();
        assertThat(retained.tombstonesPurged()).isEqualTo(1);
        assertThat(repository.submit(submission, policy).status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.QUEUED);
    }

    @Test
    void retentionRejectsSilentClampingOfUnsafeBounds() {
        assertThatThrownBy(() -> repository.retainExpired(Duration.ofHours(23), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 3650 days");
        assertThatThrownBy(() -> repository.retainExpired(Duration.ofDays(365), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 10000");
        assertThatThrownBy(() -> repository.retainExpired(Duration.ofDays(365), 10_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 10000");
    }

    @Test
    void queueObservationUsesOneDatabaseClockSnapshotForExpiredLiveLeases() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        repository.claimNext("test", "worker-a", policy);
        jdbc.update("""
                UPDATE rg_test_suite_stability_jobs
                SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE job_id = ?
                """, submission.jobId());

        TestSuiteStabilityQueueSnapshot snapshot = repository.observe("test");
        TestSuiteStabilityQueueSnapshot empty = repository.observe("staging");

        assertThat(snapshot.totals())
                .containsEntry(TestSuiteStabilityJobRecord.Status.RUNNING, 1L)
                .containsEntry(TestSuiteStabilityJobRecord.Status.QUEUED, 0L);
        assertThat(snapshot.expiredLiveLeases()).isEqualTo(1);
        assertThat(snapshot.oldestQueuedAt()).isNull();
        assertThat(snapshot.distinctQueuedTenants()).isZero();
        assertThat(snapshot.observedAt()).isNotNull();
        assertThat(empty.totals().values()).containsOnly(0L);
        assertThat(empty.oldestQueuedAt()).isNull();
        assertThat(empty.expiredLiveLeases()).isZero();
    }

    @Test
    void queueObservationFailsClosedWhenAStoredStatusLeavesTheClosedVocabulary() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        jdbc.update("""
                UPDATE rg_test_suite_stability_jobs SET status = 'UNKNOWN'
                WHERE job_id = ?
                """, submission.jobId());

        assertThatThrownBy(() -> repository.observe("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown lifecycle status");
    }

    @Test
    void queueObservationTreatsMissingLiveLeaseAsStaleOwnerBacklog() {
        TestSuiteStabilityQueuePolicy policy = policy(10, 10, 2, 1, 3);
        TestSuiteStabilityJobSubmission submission = submission('1', "tenant-a", "request-a",
                TestSuiteStabilityJobSubmission.Priority.NORMAL);
        repository.submit(submission, policy);
        repository.claimNext("test", "worker-a", policy);
        jdbc.update("""
                UPDATE rg_test_suite_stability_jobs SET lease_expires_at = NULL
                WHERE job_id = ?
                """, submission.jobId());

        assertThat(repository.observe("test").expiredLiveLeases()).isEqualTo(1);
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
                Duration.ofMinutes(1), maximumRetries, Duration.ofDays(7),
                Duration.ofDays(30));
    }

    private static TestSuiteStabilityJobRequestKeyProtector requestKeys(String activeKeyId) {
        return new TestSuiteStabilityJobRequestKeyProtector(
                activeKeyId, Map.of(activeKeyId, new byte[32]));
    }

    private DatabaseTestSuiteStabilityJobRepository repository(
            TestSuiteStabilityJobRequestKeyProtector requestKeys) {
        return new DatabaseTestSuiteStabilityJobRepository(
                new JdbcTemplate(dataSource), mapper, parentAuthority, requestKeys,
                new DataSourceTransactionManager(dataSource));
    }

    private void expireJob(TestSuiteStabilityJobRecord record) {
        Instant createdAt = Instant.parse("2019-01-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2020-01-01T00:00:00Z");
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT submission_fingerprint, owner_id, lease_epoch, lease_expires_at,
                       policy_generation
                FROM rg_test_suite_stability_jobs WHERE job_id = ?
                """, record.jobId());
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.testSuiteStabilityJobRecord.v1");
        material.put("jobId", record.jobId());
        material.put("request", record.request());
        material.put("submissionFingerprint", row.get("SUBMISSION_FINGERPRINT"));
        material.put("requestFingerprint", record.requestFingerprint());
        material.put("classification", record.classification());
        material.put("principal", record.principal());
        material.put("priority", record.priority().name());
        material.put("status", record.status().name());
        material.put("retryCount", record.retryCount());
        material.put("nextEligibleAt", record.nextEligibleAt());
        material.put("deadlineAt", record.deadlineAt());
        material.put("createdAt", createdAt);
        material.put("updatedAt", record.updatedAt());
        material.put("expiresAt", expiresAt);
        material.put("ownerId", row.get("OWNER_ID"));
        material.put("leaseEpoch", ((Number) row.get("LEASE_EPOCH")).longValue());
        Timestamp leaseExpiresAt = (Timestamp) row.get("LEASE_EXPIRES_AT");
        material.put("leaseExpiresAt",
                leaseExpiresAt == null ? null : leaseExpiresAt.toInstant());
        material.put("policyGeneration",
                ((Number) row.get("POLICY_GENERATION")).longValue());
        material.put("terminalStabilityRunId", record.terminalStabilityRunId());
        material.put("terminalEvidenceFingerprint", record.terminalEvidenceFingerprint());
        material.put("failureCode", record.failureCode());
        material.put("cancellationRequestId", record.cancellationRequestId());
        material.put("cancellationFingerprint", record.cancellationFingerprint());
        String fingerprint = ProtocolFingerprint.of(mapper, material);
        assertThat(jdbc.update("""
                UPDATE rg_test_suite_stability_jobs
                SET created_at = ?, expires_at = ?, record_fingerprint = ?
                WHERE job_id = ? AND record_fingerprint = ?
                """, Timestamp.from(createdAt), Timestamp.from(expiresAt), fingerprint,
                record.jobId(), record.recordFingerprint())).isEqualTo(1);
    }

    private void expireTombstone() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM rg_test_suite_stability_job_tombstones");
        Instant tombstonedAt = Instant.parse("2019-01-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2020-01-01T00:00:00Z");
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.testSuiteStabilityJobTombstone.v1");
        material.put("tenantId", row.get("TENANT_ID"));
        material.put("environmentId", row.get("ENVIRONMENT_ID"));
        material.put("requestKeyId", row.get("REQUEST_KEY_ID"));
        material.put("requestKey", row.get("REQUEST_KEY"));
        material.put("submissionFingerprint", row.get("SUBMISSION_FINGERPRINT"));
        material.put("tombstonedAt", tombstonedAt);
        material.put("expiresAt", expiresAt);
        material.put("recordVersion", ((Number) row.get("RECORD_VERSION")).intValue());
        String fingerprint = ProtocolFingerprint.of(mapper, material);
        assertThat(jdbc.update("""
                UPDATE rg_test_suite_stability_job_tombstones
                SET tombstoned_at = ?, expires_at = ?, record_fingerprint = ?
                WHERE record_fingerprint = ?
                """, Timestamp.from(tombstonedAt), Timestamp.from(expiresAt), fingerprint,
                row.get("RECORD_FINGERPRINT"))).isEqualTo(1);
    }

    private static byte[] bytes(int offset) {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (offset + index);
        }
        return value;
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

    private static final class FakeParentAuthority
            implements TestSuiteStabilityJobParentAuthority {
        private final List<Invocation> invocations =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private Resolution resolution = Resolution.stopped();
        private RuntimeException failure;
        private Resolution completionResolution;
        private RuntimeException completionFailure;

        @Override
        public Resolution stop(
                TestSuiteStabilityJobRecord job,
                TestSuiteStabilityExecutionStop.Reason reason,
                String failureCode,
                Duration retention) {
            invocations.add(new Invocation(job.status(), reason, failureCode, retention));
            if (failure != null) {
                throw failure;
            }
            return resolution;
        }

        @Override
        public Resolution requireCompleted(
                TestSuiteStabilityJobRecord job,
                String stabilityRunId,
                String evidenceFingerprint) {
            if (completionFailure != null) {
                throw completionFailure;
            }
            return completionResolution == null
                    ? Resolution.completed(stabilityRunId, evidenceFingerprint)
                    : completionResolution;
        }

        private record Invocation(
                TestSuiteStabilityJobRecord.Status status,
                TestSuiteStabilityExecutionStop.Reason reason,
                String failureCode,
                Duration retention) {
        }
    }
}
