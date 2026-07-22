package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueuePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournalTest {

    private static final TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Policy
            POLICY = new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Policy(
            Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(100),
            Duration.ofMillis(400), 16);

    private ObjectMapper mapper;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal journal;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:terminal-projection-work-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        journal = new DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
                jdbc, mapper, POLICY, transactions);
        journal.init();
    }

    @Test
    void registeredWorkIsClaimedWithDatabaseFenceAndVisibleInSnapshot() {
        var trigger = register('a');

        var before = journal.snapshot();
        var claim = journal.claimNext("worker-a").orElseThrow();
        var after = journal.snapshot();

        assertThat(before.ready()).isEqualTo(1);
        assertThat(before.dueReady()).isEqualTo(1);
        assertThat(before.oldestActionableAt()).isPresent();
        assertThat(claim.trigger()).isEqualTo(trigger);
        assertThat(claim.lease().epoch()).isEqualTo(1);
        assertThat(claim.lease().ownerId()).isEqualTo("worker-a");
        assertThat(claim.lease().leaseUntil()).isAfter(claim.lease().claimedAt());
        assertThat(after.ready()).isZero();
        assertThat(after.leased()).isEqualTo(1);
        assertThat(journal.claimNext("worker-b")).isEmpty();
    }

    @Test
    void proofPendingReschedulesWithBackoffAndExactCompletionReplay() throws Exception {
        var trigger = register('a');
        var first = journal.claimNext("worker-a").orElseThrow();
        var pending = proofPending();

        var rescheduled = journal.complete(first.lease(), pending);
        var replayed = journal.complete(first.lease(), pending);

        assertThat(rescheduled.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .CompletionStatus.RESCHEDULED);
        assertThat(rescheduled.consecutiveProofPending()).isEqualTo(1);
        assertThat(rescheduled.consecutiveUnavailable()).isZero();
        assertThat(replayed.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .CompletionStatus.REPLAYED);
        assertThat(journal.claimNext("worker-b")).isEmpty();
        Thread.sleep(150);
        var second = journal.claimNext("worker-b").orElseThrow();
        assertThat(second.lease().epoch()).isEqualTo(2);
        assertThat(second.executionAttempts()).isEqualTo(1);
        assertThat(second.consecutiveProofPending()).isEqualTo(1);
        assertThat(second.trigger()).isEqualTo(trigger);
    }

    @Test
    void unavailableRetryUsesIndependentStreakAndResetsProofPending() throws Exception {
        register('a');
        var first = journal.claimNext("worker-a").orElseThrow();
        journal.complete(first.lease(), proofPending());
        Thread.sleep(150);
        var second = journal.claimNext("worker-a").orElseThrow();

        var unavailable = journal.complete(second.lease(), unavailable());

        assertThat(unavailable.consecutiveProofPending()).isZero();
        assertThat(unavailable.consecutiveUnavailable()).isEqualTo(1);
        assertThat(unavailable.executionAttempts()).isEqualTo(2);
        assertThat(unavailable.nextAttemptAt()).hasValueSatisfying(
                value -> assertThat(value).isAfter(unavailable.completedAt()));
    }

    @Test
    void projectedResultCompletesWorkAndChangedReplayConflicts() {
        var trigger = register('a');
        var claim = journal.claimNext("worker-a").orElseThrow();
        var projected = projected(trigger, 'a');

        var completed = journal.complete(claim.lease(), projected);
        var replayed = journal.complete(claim.lease(), projected);

        assertThat(completed.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .CompletionStatus.COMPLETED);
        assertThat(completed.workStatus()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Status.COMPLETED);
        assertThat(completed.nextAttemptAt()).isEmpty();
        assertThat(replayed.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .CompletionStatus.REPLAYED);
        assertWorkConflict(() -> journal.complete(
                        claim.lease(), projected(trigger, 'b')),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason
                        .RESULT_CONFLICT);
        assertThat(journal.find("tenant-a", "test", trigger.attemptId()))
                .get().extracting(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Entry
                                ::projectionId)
                .isEqualTo(projected.projectionId());
    }

    @Test
    void permanentConflictQuarantinesWithoutInventingProjection() {
        var trigger = register('a');
        var claim = journal.claimNext("worker-a").orElseThrow();

        var quarantined = journal.complete(claim.lease(), permanent());

        assertThat(quarantined.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .CompletionStatus.QUARANTINED);
        assertThat(quarantined.workStatus()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .Status.QUARANTINED);
        assertThat(journal.find("tenant-a", "test", trigger.attemptId()))
                .get().extracting(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Entry
                                ::projectionId)
                .isEqualTo("");
        assertThat(journal.snapshot().quarantined()).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsTakenOverAndOldOwnerCannotComplete() throws Exception {
        register('a');
        var stale = journal.claimNext("worker-a").orElseThrow();
        Thread.sleep(1_100);

        var takeover = journal.claimNext("worker-b").orElseThrow();

        assertThat(takeover.lease().epoch()).isEqualTo(2);
        assertWorkConflict(() -> journal.complete(stale.lease(), unavailable()),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason
                        .LEASE_LOST);
        assertThat(journal.complete(takeover.lease(), unavailable()).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .CompletionStatus.RESCHEDULED);
    }

    @Test
    void twoReplicasCannotOwnTheSameLiveLease() throws Exception {
        register('a');
        var replica = new DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
                new JdbcTemplate(dataSource), mapper, POLICY,
                new DataSourceTransactionManager(dataSource));
        replica.init();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim>>
                    first = executor.submit(() -> claimTogether(journal, ready, start, "worker-a"));
            Future<Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim>>
                    second = executor.submit(() -> claimTogether(replica, ready, start, "worker-b"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim>>
                    results = List.of(first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS));

            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(results).filteredOn(Optional::isEmpty).hasSize(1);
            assertThat(journal.snapshot().leased()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void alteredLeaseFenceFailsBeforeDatabaseMutation() {
        register('a');
        var claim = journal.claimNext("worker-a").orElseThrow();
        var lease = claim.lease();
        var altered = new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Lease(
                lease.workId(), lease.attemptId(), lease.ownerId(), lease.token(), lease.epoch(),
                lease.claimedAt(), lease.leaseUntil(), fingerprint('f'));

        assertWorkConflict(() -> journal.complete(altered, unavailable()),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason
                        .INTEGRITY_FAILURE);
        assertThat(journal.snapshot().leased()).isEqualTo(1);
    }

    @Test
    void tamperedRetryProjectionFailsClosedOnReadAndClaim() {
        var trigger = register('a');
        jdbc.update("""
                UPDATE rg_test_stability_attempt_terminal_projection_work
                SET consecutive_proof_pending = 9
                WHERE attempt_id = ?
                """, trigger.attemptId());

        assertWorkConflict(() -> journal.find("tenant-a", "test", trigger.attemptId()),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason
                        .INTEGRITY_FAILURE);
        assertWorkConflict(() -> journal.claimNext("worker-a"),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason
                        .INTEGRITY_FAILURE);
    }

    @Test
    void resultContractPreservesProofAndProjectionConflictDetails() {
        var pendingAttempt = new TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                .Attempt(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.PROOF_PENDING,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROOF_NOT_READY,
                Optional.of(TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                        .Reason.CANCELLATION_NOT_CONFIRMED), Optional.empty(), Optional.empty());

        var projected = TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result
                .from(pendingAttempt);
        var conflictAttempt = new TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                .Attempt(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PERMANENT_CONFLICT,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROJECTION_CONFLICT,
                Optional.empty(),
                Optional.of(TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ConflictReason.JOB_FENCE_CHANGED), Optional.empty());
        var conflict = TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result
                .from(conflictAttempt);

        assertThat(projected.kind()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ResultKind.PROOF_PENDING);
        assertThat(projected.proofReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .CANCELLATION_NOT_CONFIRMED);
        assertThat(conflict.projectionConflictReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason
                        .JOB_FENCE_CHANGED);
        assertThatThrownBy(() -> newResult(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ResultKind.PROOF_PENDING,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROOF_NOT_READY,
                Optional.empty(), Optional.empty(), "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneShotWorkerDrivesRealRetryClaimAndQuarantineLifecycle() throws Exception {
        var trigger = register('a');
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        when(coordinator.project(anyString(), anyString(), anyString(), any()))
                .thenReturn(proofPendingAttempt())
                .thenReturn(permanentAttempt());

        try (var supervisor = projectionSupervisor(Duration.ofMillis(300))) {
            var worker = projectionWorker(coordinator, supervisor);

            var first = worker.processNext();
            assertThat(first.outcome()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome
                            .RESCHEDULED);
            assertThat(journal.find("tenant-a", "test", trigger.attemptId()))
                    .get().satisfies(entry -> {
                        assertThat(entry.status()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                        .Status.READY);
                        assertThat(entry.executionAttempts()).isEqualTo(1);
                        assertThat(entry.consecutiveProofPending()).isEqualTo(1);
                    });

            Thread.sleep(150L);
            var second = worker.processNext();
            assertThat(second.outcome()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome
                            .QUARANTINED);
            assertThat(journal.find("tenant-a", "test", trigger.attemptId()))
                    .get().satisfies(entry -> {
                        assertThat(entry.status()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                        .Status.QUARANTINED);
                        assertThat(entry.executionAttempts()).isEqualTo(2);
                        assertThat(entry.consecutiveProofPending()).isZero();
                    });
        }
    }

    @Test
    void supervisedTimeoutPersistsUnavailableThroughRealLeaseFence() throws Exception {
        var trigger = register('a');
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        when(coordinator.project(anyString(), anyString(), anyString(), any()))
                .thenAnswer(ignored -> {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignoredInterrupt) {
                            // Simulate a late transaction whose commit status remains unknown.
                        }
                    }
                    return proofPendingAttempt();
                });

        try (var supervisor = projectionSupervisor(Duration.ofMillis(100))) {
            var worker = projectionWorker(coordinator, supervisor);
            try {
                var execution = worker.processNext();

                assertThat(entered.getCount()).isZero();
                assertThat(execution.outcome()).isEqualTo(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome
                                .RESCHEDULED);
                assertThat(execution.localDisposition()).isEqualTo(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker
                                .LocalDisposition.TIMED_OUT);
                assertThat(journal.find("tenant-a", "test", trigger.attemptId()))
                        .get().satisfies(entry -> {
                            assertThat(entry.status()).isEqualTo(
                                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                            .Status.READY);
                            assertThat(entry.lastResultKind()).isEqualTo(
                                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                            .ResultKind.UNAVAILABLE);
                            assertThat(entry.consecutiveUnavailable()).isEqualTo(1);
                        });
                assertThat(supervisor.snapshot().lingeringCalls()).isEqualTo(1);
            } finally {
                release.countDown();
                awaitNoActiveProjection(supervisor);
            }
        } finally {
            release.countDown();
        }
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Trigger register(
            char value) {
        var trigger = TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Trigger
                .create(mapper, "tenant-a", "test", attemptId(value), observationId(value),
                        fingerprint(value));
        journal.boundRegister(trigger).apply(jdbc);
        return trigger;
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result proofPending() {
        return newResult(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ResultKind.PROOF_PENDING,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROOF_NOT_READY,
                Optional.of(TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                        .Reason.CANCELLATION_NOT_CONFIRMED), Optional.empty(), "", "");
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt
            proofPendingAttempt() {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PROOF_PENDING,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROOF_NOT_READY,
                Optional.of(TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                        .Reason.CANCELLATION_NOT_CONFIRMED), Optional.empty(), Optional.empty());
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt
            permanentAttempt() {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PERMANENT_CONFLICT,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .SOURCE_CHAIN_CONFLICT,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result unavailable() {
        return newResult(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ResultKind.UNAVAILABLE,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .SOURCE_UNAVAILABLE,
                Optional.empty(), Optional.empty(), "", "");
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result permanent() {
        return newResult(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ResultKind.PERMANENT_CONFLICT,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .SOURCE_NOT_RETAINED,
                Optional.empty(), Optional.empty(), "", "");
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result projected(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Trigger trigger,
            char value) {
        return newResult(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ResultKind.PROJECTED,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason.NONE,
                Optional.empty(), Optional.empty(),
                "stability-attempt-terminal-project-" + String.valueOf(value).repeat(64),
                fingerprint(value));
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result newResult(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ResultKind kind,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason reason,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason>
                    proofReason,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason>
                    projectionConflict,
            String projectionId,
            String projectionFingerprint) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result
                        .SCHEMA_VERSION,
                kind, reason, proofReason, projectionConflict, projectionId,
                projectionFingerprint);
    }

    private Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim>
            claimTogether(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal target,
                    CountDownLatch ready,
                    CountDownLatch start,
                    String owner) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("claim start timed out");
        }
        return target.claimNext(owner);
    }

    private void assertWorkConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason reason) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ConflictException.class,
                conflict -> assertThat(conflict.reason()).isEqualTo(reason));
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker projectionWorker(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker(
                journal, coordinator, supervisor, queuePolicy(),
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Policy(
                        Duration.ofMillis(200)),
                "replica-a/terminal-projection-1");
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
            projectionSupervisor(Duration timeout) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                        timeout, 1));
    }

    private static TestSuiteStabilityQueuePolicy queuePolicy() {
        return new TestSuiteStabilityQueuePolicy(
                1, 100, 20, 10, 5, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), 2,
                Duration.ofHours(1), Duration.ofDays(30));
    }

    private static void awaitNoActiveProjection(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && supervisor.snapshot().activeCalls() != 0) {
            Thread.sleep(10L);
        }
        assertThat(supervisor.snapshot().activeCalls()).isZero();
    }

    private static String attemptId(char value) {
        return "stability-attempt-" + String.valueOf(value).repeat(64);
    }

    private static String observationId(char value) {
        return "stability-attempt-observe-" + String.valueOf(value).repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
