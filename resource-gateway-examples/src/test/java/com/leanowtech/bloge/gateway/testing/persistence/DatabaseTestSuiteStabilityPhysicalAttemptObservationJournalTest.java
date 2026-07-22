package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationVerifier;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobCancellationCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobParentAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobPrincipal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmission;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptIdentity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationCallSupervisor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationCoordinator;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReconciler;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationVerifier;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartVerifier;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueuePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityPhysicalAttemptObservationJournalTest {

    private static final String PROVIDER_ID = "isolated-runtime-a";
    private static final String DEPLOYMENT_ID = "isolated-runtime-a.generation-7";
    private static final String KEY_ID = "isolated-runtime-a.key-3";
    private static final long CONCURRENCY_TIMEOUT_MILLIS = 5_000L;
    private static final AtomicLong CONCURRENCY_THREAD_SEQUENCE = new AtomicLong();
    private static final TestSuiteStabilityQueuePolicy POLICY =
            new TestSuiteStabilityQueuePolicy(
                    1, 20, 10, 4, 2, Duration.ofSeconds(30), Duration.ofMinutes(5),
                    Duration.ofSeconds(1), Duration.ofMinutes(1), 3, Duration.ofDays(1),
                    Duration.ofDays(7));
    private static final TestSuiteStabilityQueuePolicy FENCING_POLICY =
            new TestSuiteStabilityQueuePolicy(
                    1, 10, 10, 3, 3, Duration.ofSeconds(5), Duration.ofMinutes(5),
                    Duration.ofSeconds(1), Duration.ofMinutes(1), 3, Duration.ofDays(1),
                    Duration.ofDays(7));
    private static final TestSuiteStabilityQueuePolicy NO_RETRY_POLICY =
            new TestSuiteStabilityQueuePolicy(
                    1, 20, 10, 4, 2, Duration.ofSeconds(30), Duration.ofMinutes(5),
                    Duration.ofSeconds(1), Duration.ofMinutes(1), 0, Duration.ofDays(1),
                    Duration.ofDays(7));

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseTestSuiteStabilityJobRepository jobs;
    private DatabaseTestSuiteStabilityPhysicalAttemptRegistry attempts;
    private KeyPair keyPair;
    private TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor startDescriptor;
    private TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor;
    private DatabaseTestSuiteStabilityPhysicalAttemptStartJournal starts;
    private DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal journal;
    private DatabaseTestSuiteStabilityAttemptCancellationJournal cancellations;
    private DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal projections;
    private DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
            terminalProjectionWork;
    private DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
            reconciliations;
    private AtomicLong startSequence;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:physical-attempt-observation-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jobs = new DatabaseTestSuiteStabilityJobRepository(
                jdbc, mapper, new StoppedParentAuthority(),
                new TestSuiteStabilityJobRequestKeyProtector(
                        "request-key-a", Map.of("request-key-a", new byte[32])),
                "retention-a", Duration.ofSeconds(30), transactions, true);
        jobs.init();
        attempts = new DatabaseTestSuiteStabilityPhysicalAttemptRegistry(
                jdbc, mapper, jobs, transactions);
        attempts.init();
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        startDescriptor = new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofMillis(100));
        descriptor = descriptor(Duration.ofMillis(100));
        starts = new DatabaseTestSuiteStabilityPhysicalAttemptStartJournal(
                jdbc, mapper, jobs, startVerifier(), transactions);
        starts.init();
        journal = new DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal(
                jdbc, mapper, starts, observationVerifier(), transactions);
        journal.init();
        cancellations = new DatabaseTestSuiteStabilityAttemptCancellationJournal(
                jdbc, mapper, cancellationVerifier(), transactions);
        cancellations.init();
        projections = new DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal(
                jobs, attempts, starts, journal, cancellations);
        terminalProjectionWork =
                new DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
                        jdbc, mapper);
        terminalProjectionWork.init();
        reconciliations = reconciliationJournal(10);
        startSequence = new AtomicLong(10);
    }

    @Test
    void preparesAuthorizesAndAtomicallyAcceptsRunningState() throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');

        var prepared = journal.prepare(command, descriptor);
        journal.authorizeInvocation(command.commandId());
        var accepted = journal.accept(command.commandId(), observation(
                command, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        assertThat(prepared.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.PreparationStatus.PREPARED);
        assertThat(accepted.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus.POSITIVE);
        assertThat(journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId()))
                .get().extracting(state -> state.receipt().state())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_stability_attempt_observation_provider_sequences
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void possibleProviderStartsHoldExpiredQueueFencesAcrossReplicas() throws Exception {
        Instant preparedDeadline = Instant.now().plusSeconds(6);
        AttemptContext prepared = retainedStart(
                'a', false, "tenant-a", FENCING_POLICY, preparedDeadline);
        AttemptContext confirmed = retainedStart(
                'b', true, "tenant-a", FENCING_POLICY);
        AttemptContext rejected = retainedStart(
                'c', false, "tenant-a", FENCING_POLICY);
        starts.accept(rejected.start().commandId(), startObservation(
                rejected.start(), startSequence.incrementAndGet(),
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.REJECTED));
        jobs.prepareCompletion(confirmed.claim().lease(), FENCING_POLICY);
        jobs.submit(submission('d'), FENCING_POLICY);

        Thread.sleep(5_100);

        DatabaseTestSuiteStabilityJobRepository replica =
                new DatabaseTestSuiteStabilityJobRepository(
                        jdbc, mapper, new StoppedParentAuthority(),
                        new TestSuiteStabilityJobRequestKeyProtector(
                                "request-key-a", Map.of("request-key-a", new byte[32])),
                        "retention-b", Duration.ofSeconds(30), transactions, true);
        replica.init();
        var claims = concurrently(
                () -> jobs.claimNext("test", "worker-b", FENCING_POLICY),
                () -> replica.claimNext("test", "worker-c", FENCING_POLICY));

        assertThat(claims).allSatisfy(claim -> assertThat(claim.outcome())
                .isEqualTo(TestSuiteStabilityJobClaim.Outcome.NO_WORK));
        assertThat(jobs.find("tenant-a", "test", prepared.identity().jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.RUNNING);
        assertThat(jobs.find("tenant-a", "test", confirmed.identity().jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);
        assertThat(jobs.find("tenant-a", "test", rejected.identity().jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.RUNNING);
        assertThat(jobs.find("tenant-a", "test", submission('d').jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.QUEUED);
        assertThat(jdbc.queryForObject("""
                SELECT MAX(lease_epoch) FROM rg_test_suite_stability_jobs
                WHERE job_id IN (?, ?, ?)
                """, Long.class, prepared.identity().jobId(), confirmed.identity().jobId(),
                rejected.identity().jobId())).isEqualTo(1L);

        Thread.sleep(Math.max(0L,
                Duration.between(Instant.now(), preparedDeadline).toMillis() + 150L));
        assertThat(replica.claimNext("test", "worker-deadline", FENCING_POLICY).outcome())
                .isEqualTo(TestSuiteStabilityJobClaim.Outcome.NO_WORK);
        assertThat(jdbc.queryForObject("""
                SELECT failure_code FROM rg_test_suite_stability_jobs
                WHERE job_id = ?
                """, String.class, prepared.identity().jobId()))
                .isEqualTo("RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED");

        TestSuiteStabilityJobRecord rejectedJob = jobs.find(
                "tenant-a", "test", rejected.identity().jobId()).orElseThrow();
        var cancelled = jobs.cancel(new TestSuiteStabilityJobCancellationCommand(
                        "tenant-a", "test", rejectedJob.jobId(), "cancel-c",
                        fingerprint('8'), rejectedJob.principal()),
                FENCING_POLICY, ignored -> TestRuntimeTransactionMutation.noop());

        assertThat(cancelled.job().status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED);
        assertThat(replica.claimNext("test", "worker-d", FENCING_POLICY).outcome())
                .isEqualTo(TestSuiteStabilityJobClaim.Outcome.NO_WORK);
        assertThat(jobs.observe("test").expiredLiveLeases()).isEqualTo(3);
    }

    @Test
    void failedTerminalProjectionRequeuesReplaysAndAllowsNextLeaseEpoch() throws Exception {
        TerminalFixture fixture = terminalFixture(
                'e', TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.FAILED, POLICY);

        var projected = projections.project(fixture.command(), POLICY);
        jdbc.update("""
                DELETE FROM rg_test_stability_attempt_observation_entries
                WHERE command_id = ?
                """, fixture.command().observationCommandId());
        var replayed = projections.project(fixture.command(), POLICY);

        assertThat(projected.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.PROJECTED);
        assertThat(projected.entry().decision()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .QueueDecision.REQUEUED);
        assertThat(projected.entry().queueResult().retryCount()).isEqualTo(1);
        assertThat(replayed.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.REPLAYED);
        assertThat(replayed.entry()).isEqualTo(projected.entry());
        assertThat(projections.find("tenant-a", "test",
                fixture.command().projectionId())).contains(projected.entry());

        Thread.sleep(1_100);
        TestSuiteStabilityJobClaim next = jobs.claimNext("test", "worker-next", POLICY);
        assertThat(next.outcome()).isEqualTo(TestSuiteStabilityJobClaim.Outcome.ACQUIRED);
        assertThat(next.lease().epoch()).isEqualTo(2L);
    }

    @Test
    void retryExhaustionProjectsFailureAndReleasesCapacity() throws Exception {
        TerminalFixture fixture = terminalFixture(
                'e', TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.PROVIDER_ABORTED, NO_RETRY_POLICY);
        jobs.submit(submission('f'), NO_RETRY_POLICY);

        var projected = projections.project(fixture.command(), NO_RETRY_POLICY);

        assertThat(projected.entry().decision()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .QueueDecision.FAILED);
        assertThat(projected.entry().queueResult().failureCode())
                .isEqualTo("RG.TEST.STABILITY_ATTEMPT_PROVIDER_ABORTED");
        assertThat(jobs.claimNext("test", "worker-f", NO_RETRY_POLICY).outcome())
                .isEqualTo(TestSuiteStabilityJobClaim.Outcome.ACQUIRED);
    }

    @Test
    void cancelledProjectionRequiresQueueIntentAndConfirmedProviderReceipt() throws Exception {
        TerminalFixture fixture = terminalFixture(
                'e', TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.CANCELLED, POLICY);

        assertProjectionConflict(() -> projections.project(fixture.command(), POLICY),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason
                        .CANCELLATION_PROOF_CONFLICT);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_terminal_projections
                """, Integer.class)).isZero();

        TestSuiteStabilityJobRecord job = jobs.find(
                "tenant-a", "test", fixture.context().identity().jobId()).orElseThrow();
        jobs.cancel(new TestSuiteStabilityJobCancellationCommand(
                        "tenant-a", "test", job.jobId(), "terminal-project-cancel-e",
                        fingerprint('a'), job.principal()),
                POLICY, ignored -> TestRuntimeTransactionMutation.noop());
        var projected = projections.project(fixture.command(), POLICY);

        assertThat(projected.entry().decision()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .QueueDecision.CANCELLED);
        assertThat(projected.entry().queueResult().status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.CANCELLED);
    }

    @Test
    void successProjectionRollsBackUntilSignedParentWinnerIsAvailable() throws Exception {
        TerminalFixture fixture = terminalFixture(
                'e', TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.SUCCEEDED, POLICY);

        assertProjectionConflict(() -> projections.project(fixture.command(), POLICY),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason
                        .PARENT_CONFLICT);
        assertThat(jobs.find("tenant-a", "test", fixture.context().identity().jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.RUNNING);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_terminal_projections
                """, Integer.class)).isZero();

        DatabaseTestSuiteStabilityJobRepository completedJobs =
                new DatabaseTestSuiteStabilityJobRepository(
                        jdbc, mapper, new CompletedParentAuthority(
                                fixture.parentRunId(), fixture.parentEvidenceFingerprint()),
                        new TestSuiteStabilityJobRequestKeyProtector(
                                "request-key-a", Map.of("request-key-a", new byte[32])),
                        "retention-success", Duration.ofSeconds(30), transactions, true);
        completedJobs.init();
        var completedProjections =
                new DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal(
                        completedJobs, attempts, starts, journal, cancellations);

        var projected = completedProjections.project(fixture.command(), POLICY);

        assertThat(projected.entry().decision()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .QueueDecision.SUCCEEDED);
        assertThat(projected.entry().queueResult().terminalStabilityRunId())
                .isEqualTo(fixture.parentRunId());
        assertThat(projected.entry().queueResult().terminalEvidenceFingerprint())
                .isEqualTo(fixture.parentEvidenceFingerprint());
    }

    @Test
    void sourceTamperFailsClosedWithoutQueueOrSlotMutation() throws Exception {
        TerminalFixture fixture = terminalFixture(
                'e', TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.FAILED, POLICY);
        jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_entries
                SET record_fingerprint = ? WHERE command_id = ?
                """, fingerprint('a'), fixture.command().observationCommandId());

        assertProjectionConflict(() -> projections.project(fixture.command(), POLICY),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason
                        .SOURCE_CHANGED);
        assertThat(jobs.find("tenant-a", "test", fixture.context().identity().jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.RUNNING);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_terminal_projections
                """, Integer.class)).isZero();
    }

    @Test
    void changedQueueStateCannotConsumeAValidTerminalFact() throws Exception {
        TerminalFixture fixture = terminalFixture(
                'e', TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.FAILED, POLICY);
        DatabaseTestSuiteStabilityJobRepository legacy =
                new DatabaseTestSuiteStabilityJobRepository(
                        jdbc, mapper, new StoppedParentAuthority(),
                        new TestSuiteStabilityJobRequestKeyProtector(
                                "request-key-a", Map.of("request-key-a", new byte[32])),
                        "retention-legacy", Duration.ofSeconds(30), transactions);
        legacy.retry(fixture.context().claim().lease(),
                "RG.TEST.LEGACY_RETRY", POLICY);

        assertProjectionConflict(() -> projections.project(fixture.command(), POLICY),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason
                        .JOB_FENCE_CHANGED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_terminal_projections
                """, Integer.class)).isZero();
    }

    @Test
    void replicasConvergeOnOneTerminalProjectionWinner() throws Exception {
        TerminalFixture fixture = terminalFixture(
                'e', TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.FAILED, POLICY);
        var replica = new DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal(
                jobs, attempts, starts, journal, cancellations);

        var results = concurrently(
                () -> projections.project(fixture.command(), POLICY),
                () -> replica.project(fixture.command(), POLICY));

        assertThat(results).extracting(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .Projection::status)
                .containsExactlyInAnyOrder(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                                .ProjectionStatus.PROJECTED,
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                                .ProjectionStatus.REPLAYED);
        assertThat(results.get(0).entry()).isEqualTo(results.get(1).entry());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_terminal_projections
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void projectionReadDetectsWholeRowDecisionTamper() throws Exception {
        TerminalFixture fixture = terminalFixture(
                'e', TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.FAILED, POLICY);
        projections.project(fixture.command(), POLICY);
        jdbc.update("""
                UPDATE rg_test_stability_attempt_terminal_projections
                SET queue_decision = 'FAILED' WHERE projection_id = ?
                """, fixture.command().projectionId());

        assertThatThrownBy(() -> projections.find(
                "tenant-a", "test", fixture.command().projectionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity failed");
    }

    @Test
    void nonTerminalPositiveStateCannotBeTurnedIntoAProjectionCommand() throws Exception {
        AttemptContext context = retainedStart('e', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'e');
        journal.prepare(command, descriptor);
        journal.accept(command.commandId(), observation(
                command, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        assertThatThrownBy(() -> TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand.create(
                mapper,
                attempts.find("tenant-a", "test", context.identity().attemptId())
                        .orElseThrow(),
                starts.find("tenant-a", "test", context.start().commandId()).orElseThrow(),
                journal.find("tenant-a", "test", command.commandId()).orElseThrow(),
                journal.latestPositive("tenant-a", "test", context.identity().attemptId())
                        .orElseThrow(),
                Optional.empty(), "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Terminal");
    }

    @Test
    void coordinatorPersistsVerifiedPositiveAndReplaysWithoutProviderIo() throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');
        AtomicInteger descriptorCalls = new AtomicInteger();
        AtomicInteger observationCalls = new AtomicInteger();
        var authority = authority(
                descriptorCalls, observationCalls,
                ignored -> uncheckedObservation(
                        command, 101, 1,
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        try (var supervisor = supervisor(Duration.ofSeconds(1))) {
            var coordinator = new TestSuiteStabilityPhysicalAttemptObservationCoordinator(
                    journal, supervisor);

            var accepted = coordinator.observe(authority, command);
            var replayed = coordinator.observe(authority, command);

            assertThat(accepted.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus
                            .POSITIVE);
            assertThat(replayed.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus
                            .REPLAYED);
            assertThat(journal.latestPositive(
                    "tenant-a", "test", context.identity().attemptId()))
                    .get().extracting(state -> state.receipt().state())
                    .isEqualTo(TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING);
            assertThat(descriptorCalls).hasValue(1);
            assertThat(observationCalls).hasValue(1);
        }
    }

    @Test
    void coordinatorTimeoutLeavesPreparedAndExactRetryCanAccept() throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');
        AtomicInteger descriptorCalls = new AtomicInteger();
        AtomicInteger observationCalls = new AtomicInteger();
        var retryDescriptor = descriptor(Duration.ofSeconds(1));
        var slowAuthority = authority(
                retryDescriptor, descriptorCalls, observationCalls, ignored -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return uncheckedObservation(
                    command, 101, 1,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING);
        });

        try (var supervisor = supervisor(Duration.ofMillis(100))) {
            var coordinator = new TestSuiteStabilityPhysicalAttemptObservationCoordinator(
                    journal, supervisor);
            assertThatThrownBy(() -> coordinator.observe(slowAuthority, command))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                    .InvocationException.class,
                            failure -> assertThat(failure.disposition()).isEqualTo(
                                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                            .Disposition.TIMED_OUT));
        }
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptObservationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED);

        var retryAuthority = authority(
                retryDescriptor, descriptorCalls, observationCalls,
                ignored -> uncheckedObservation(
                        command, 101, 1,
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));
        try (var supervisor = supervisor(Duration.ofSeconds(1))) {
            var accepted = new TestSuiteStabilityPhysicalAttemptObservationCoordinator(
                    journal, supervisor).observe(retryAuthority, command);
            assertThat(accepted.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus
                            .POSITIVE);
        }
        assertThat(descriptorCalls).hasValue(2);
        assertThat(observationCalls).hasValue(2);
    }

    @Test
    void coordinatorObservesConfirmedStartAfterQueueLeaseLoss() throws Exception {
        AttemptContext context = retainedStart('a', true);
        jobs.retry(context.claim().lease(), "RG.TEST.RUNTIME_UNAVAILABLE", POLICY);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');
        var authority = authority(
                new AtomicInteger(), new AtomicInteger(),
                ignored -> uncheckedObservation(
                        command, 101, 1,
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        try (var supervisor = supervisor(Duration.ofSeconds(1))) {
            var accepted = new TestSuiteStabilityPhysicalAttemptObservationCoordinator(
                    journal, supervisor).observe(authority, command);
            assertThat(accepted.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus
                            .POSITIVE);
        }
    }

    @Test
    void exactPrepareAndAcceptanceReplayDoNotAdvanceFloors() throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');

        assertThat(journal.prepare(command, descriptor).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.PreparationStatus.PREPARED);
        assertThat(journal.prepare(command, descriptor).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.PreparationStatus.REPLAYED);
        var signed = observation(command, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING);
        assertThat(journal.accept(command.commandId(), signed).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus.POSITIVE);
        assertThat(journal.accept(command.commandId(), signed).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus.REPLAYED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_stability_attempt_observation_provider_sequences
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void nonConfirmingObservationNeverCreatesOrOverwritesPositiveFloor() throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand running = command(context, 'a');
        journal.prepare(running, descriptor);
        journal.accept(running.commandId(), observation(
                running, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));
        var before = journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId()).orElseThrow();

        TestSuiteStabilityPhysicalAttemptObservationCommand missing = command(context, 'b');
        journal.prepare(missing, descriptor);
        var accepted = journal.accept(missing.commandId(), observation(
                missing, 102, 0,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED));

        assertThat(accepted.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus
                        .NON_CONFIRMING);
        assertThat(journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId())).contains(before);
    }

    @Test
    void repeatedProofOfExactSameStateDoesNotInventAnotherStateTransition()
            throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand first = command(context, 'a');
        journal.prepare(first, descriptor);
        var initial = observation(
                first, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING);
        journal.accept(first.commandId(), initial);
        var before = journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId()).orElseThrow();

        TestSuiteStabilityPhysicalAttemptObservationCommand repeated = command(context, 'b');
        journal.prepare(repeated, descriptor);
        var sameState = observation(
                repeated, 102, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                keyPair, initial.receipt().stateEffectiveAt(), databaseTime(),
                fingerprint('4'));
        journal.accept(repeated.commandId(), sameState);

        assertThat(journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId())).contains(before);
        assertThat(journal.find("tenant-a", "test", repeated.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptObservationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptObservationJournal.Status.POSITIVE);
    }

    @Test
    void pendingRunningAndNaturalTerminalAdvanceOneMonotonicStateFloor()
            throws Exception {
        AttemptContext context = retainedStart('a', false);
        TestSuiteStabilityPhysicalAttemptObservationCommand pending = command(context, 'a');
        journal.prepare(pending, descriptor);
        journal.accept(pending.commandId(), observation(
                pending, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.START_PENDING));

        TestSuiteStabilityPhysicalAttemptObservationCommand running = command(context, 'b');
        journal.prepare(running, descriptor);
        journal.accept(running.commandId(), observation(
                running, 102, 2,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        TestSuiteStabilityPhysicalAttemptObservationCommand terminal = command(context, 'c');
        journal.prepare(terminal, descriptor);
        journal.accept(terminal.commandId(), observation(
                terminal, 103, 3,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL));

        var floor = journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId()).orElseThrow();
        assertThat(floor.receipt().state()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL);
        assertThat(floor.receipt().terminalDisposition()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                        .SUCCEEDED);
        assertThat(floor.receipt().attemptRevision()).isEqualTo(3);
    }

    @Test
    void observationRemainsAuthorizedAfterQueueLeaseLossForOrphanReconciliation() {
        AttemptContext context = retainedStart('a', false);
        jobs.retry(context.claim().lease(), "RG.TEST.RUNTIME_UNAVAILABLE", POLICY);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');

        assertThat(journal.prepare(command, descriptor).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationJournal.PreparationStatus.PREPARED);
        journal.authorizeInvocation(command.commandId());
    }

    @Test
    void oneUnexpiredObservationPerAttemptPreventsQueryStorms() {
        AttemptContext context = retainedStart('a', false);
        TestSuiteStabilityPhysicalAttemptObservationCommand first = command(context, 'a');
        TestSuiteStabilityPhysicalAttemptObservationCommand second = command(context, 'b');
        journal.prepare(first, descriptor);

        assertConflict(() -> journal.prepare(second, descriptor),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .OBSERVATION_IN_FLIGHT);
    }

    @Test
    void expiredPreparationAllowsRecoveryCommandAndLateOldRevisionIsRejected()
            throws Exception {
        AttemptContext context = retainedStart('a', false);
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptObservationCommand old = command(
                context, "", 0, 'a', now.minusMillis(10), now.plusMillis(200));
        var late = observation(
                old, 103, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.START_PENDING,
                keyPair, now.plusMillis(80), now.plusMillis(80), fingerprint('4'));
        journal.prepare(old, descriptor);
        Thread.sleep(250L);

        TestSuiteStabilityPhysicalAttemptObservationCommand recovery = command(context, 'b');
        journal.prepare(recovery, descriptor);
        journal.accept(recovery.commandId(), observation(
                recovery, 102, 2,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.START_PENDING));

        assertConflict(() -> journal.accept(old.commandId(), late),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .ATTEMPT_REVISION_ROLLBACK);
        assertThat(journal.find("tenant-a", "test", old.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptObservationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED);
    }

    @Test
    void lateUnknownProcessCannotReplaceNewlyConfirmedProcess() throws Exception {
        AttemptContext context = retainedStart('a', false);
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptObservationCommand old = command(
                context, "", 0, 'a', now.minusMillis(10), now.plusMillis(200));
        var replacement = observation(
                old, 103, 2,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                keyPair, now.plusMillis(80), now.plusMillis(80), fingerprint('8'));
        journal.prepare(old, descriptor);
        Thread.sleep(250L);

        TestSuiteStabilityPhysicalAttemptObservationCommand recovery = command(context, 'b');
        journal.prepare(recovery, descriptor);
        journal.accept(recovery.commandId(), observation(
                recovery, 102, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        assertConflict(() -> journal.accept(old.commandId(), replacement),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .PROCESS_IDENTITY_CONFLICT);
    }

    @Test
    void lateHigherRevisionCannotRegressRunningBackToPending() throws Exception {
        AttemptContext context = retainedStart('a', false);
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptObservationCommand old = command(
                context, "", 0, 'a', now.minusMillis(10), now.plusMillis(200));
        var regressed = observation(
                old, 103, 2,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.START_PENDING,
                keyPair, now.plusMillis(80), now.plusMillis(80), fingerprint('4'));
        journal.prepare(old, descriptor);
        Thread.sleep(250L);

        TestSuiteStabilityPhysicalAttemptObservationCommand recovery = command(context, 'b');
        journal.prepare(recovery, descriptor);
        journal.accept(recovery.commandId(), observation(
                recovery, 102, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        assertConflict(() -> journal.accept(old.commandId(), regressed),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .LIFECYCLE_STATE_ROLLBACK);
    }

    @Test
    void terminalStateCannotBeRewrittenByAnotherSignedTerminalFact() throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand terminal = command(context, 'a');
        journal.prepare(terminal, descriptor);
        journal.accept(terminal.commandId(), observation(
                terminal, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL));

        TestSuiteStabilityPhysicalAttemptObservationCommand rewrite = command(context, 'b');
        journal.prepare(rewrite, descriptor);
        var failed = observation(
                rewrite, 102, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition.FAILED);

        assertConflict(() -> journal.accept(rewrite.commandId(), failed),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .TERMINAL_STATE_CONFLICT);
    }

    @Test
    void staleCommandFenceIsRejectedBeforeProviderInvocation() throws Exception {
        AttemptContext context = retainedStart('a', false);
        TestSuiteStabilityPhysicalAttemptObservationCommand first = command(context, 'a');
        journal.prepare(first, descriptor);
        journal.accept(first.commandId(), observation(
                first, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));
        TestSuiteStabilityPhysicalAttemptObservationCommand stale = command(
                context, "", 0, 'b', databaseTime(), databaseTime().plusSeconds(5));

        assertConflict(() -> journal.prepare(stale, descriptor),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .STATE_FENCE_CHANGED);
    }

    @Test
    void providerSequenceMustAdvanceAcrossAttempts() throws Exception {
        AttemptContext firstContext = retainedStart('a', true);
        AttemptContext secondContext = retainedStart('b', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand first = command(firstContext, 'a');
        TestSuiteStabilityPhysicalAttemptObservationCommand second = command(secondContext, 'b');
        journal.prepare(first, descriptor);
        journal.prepare(second, descriptor);
        journal.accept(first.commandId(), observation(
                first, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        assertConflict(() -> journal.accept(second.commandId(), observation(
                        second, 100, 1,
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING)),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .PROVIDER_SEQUENCE_ROLLBACK);
        assertThat(journal.accept(second.commandId(), observation(
                second, 102, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING)).status())
                .isEqualTo(
                        TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus
                                .POSITIVE);
    }

    @Test
    void invalidSignatureRollsBackAllFloorsAndLeavesPreparationRecoverable()
            throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');
        journal.prepare(command, descriptor);
        KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        assertThatThrownBy(() -> journal.accept(command.commandId(), observation(
                command, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                wrong, databaseTime(), databaseTime(), fingerprint('4'))))
                .isInstanceOf(TestSuiteStabilityPhysicalAttemptObservationVerifier
                        .VerificationException.class);
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptObservationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptObservationJournal.Status.PREPARED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_stability_attempt_observation_provider_floors
                """, Integer.class)).isZero();
        assertThat(journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId())).isEmpty();
    }

    @Test
    void missingOriginalStartFailsClosedBeforeObservationIo() {
        AttemptContext context = retainedStart('a', false);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');
        jdbc.update("DELETE FROM rg_test_stability_attempt_start_entries");

        assertConflict(() -> journal.prepare(command, descriptor),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .START_COMMAND_NOT_RETAINED);
    }

    @Test
    void providerConfirmationCannotPredateDurablePreparation() throws Exception {
        AttemptContext context = retainedStart('a', true);
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(
                context, fingerprint('4'), 0, 'a', now.minusMillis(50),
                now.plusSeconds(5));
        var premature = observation(
                command, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                keyPair, now, now, fingerprint('4'));
        Thread.sleep(20L);
        journal.prepare(command, descriptor);

        assertConflict(() -> journal.accept(command.commandId(), premature),
                TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .OBSERVATION_PRECEDES_PREPARATION);
    }

    @Test
    void wholeRowProviderSequenceAndStateFloorTamperingFailClosed() throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');
        journal.prepare(command, descriptor);
        journal.accept(command.commandId(), observation(
                command, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_entries
                SET attempt_revision = 9
                WHERE command_id = ?
                """, command.commandId());
        assertThatThrownBy(() -> journal.find(
                "tenant-a", "test", command.commandId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity failed");

        jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_entries
                SET attempt_revision = 1
                WHERE command_id = ?
                """, command.commandId());
        jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_provider_floors
                SET provider_sequence = 999
                """);
        assertThatThrownBy(() -> journal.find(
                "tenant-a", "test", command.commandId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity failed");

        jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_provider_floors
                SET provider_sequence = 101
                """);
        jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_state_floors
                SET attempt_revision = 9
                WHERE attempt_id = ?
                """, context.identity().attemptId());
        assertThatThrownBy(() -> journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity failed");

        jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_state_floors
                SET attempt_revision = 1
                WHERE attempt_id = ?
                """, context.identity().attemptId());
        jdbc.update("""
                DELETE FROM rg_test_stability_attempt_observation_provider_sequences
                """);
        assertThatThrownBy(() -> journal.find(
                "tenant-a", "test", command.commandId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity failed");
    }

    @Test
    void tenantAndEnvironmentScopeHideCommandsAndStateFloors() throws Exception {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');
        journal.prepare(command, descriptor);
        journal.accept(command.commandId(), observation(
                command, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        assertThat(journal.find("tenant-b", "test", command.commandId())).isEmpty();
        assertThat(journal.find("tenant-a", "staging", command.commandId())).isEmpty();
        assertThat(journal.latestPositive(
                "tenant-b", "test", context.identity().attemptId())).isEmpty();
        assertThat(journal.latestPositive(
                "tenant-a", "staging", context.identity().attemptId())).isEmpty();
    }

    @Test
    void independentJournalInstancesSerializeProviderSequenceAcrossAttempts()
            throws Exception {
        AttemptContext firstContext = retainedStart('a', true);
        AttemptContext secondContext = retainedStart('b', true);
        TestSuiteStabilityPhysicalAttemptObservationCommand first = command(firstContext, 'a');
        TestSuiteStabilityPhysicalAttemptObservationCommand second = command(secondContext, 'b');
        journal.prepare(first, descriptor);
        journal.prepare(second, descriptor);
        DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal replica =
                new DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal(
                        new JdbcTemplate(jdbc.getDataSource()), mapper, starts,
                        observationVerifier(), transactions);
        replica.init();

        List<ConcurrentAcceptance> results = concurrently(
                () -> acceptConcurrently(journal, first),
                () -> acceptConcurrently(replica, second));

        assertThat(results).filteredOn(ConcurrentAcceptance::accepted).hasSize(1);
        assertThat(results).filteredOn(result -> result.conflict()
                        == TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason
                        .PROVIDER_SEQUENCE_ROLLBACK)
                .hasSize(1);
        ConcurrentAcceptance rejected = results.stream()
                .filter(result -> !result.accepted()).findFirst().orElseThrow();
        TestSuiteStabilityPhysicalAttemptObservationCommand retry =
                rejected.commandId().equals(first.commandId()) ? first : second;
        assertThat(journal.accept(retry.commandId(), observation(
                retry, 102, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING)).status())
                .isEqualTo(
                        TestSuiteStabilityPhysicalAttemptObservationJournal.AcceptanceStatus
                                .POSITIVE);
    }

    @Test
    void reconciliationDiscoversDurableStartAndClaimsItWithDatabaseLease() {
        AttemptContext context = retainedStart('a', true);

        var claim = reconciliations.claimNext("reconciler-a").orElseThrow();

        assertThat(claim.startCommand()).isEqualTo(context.start());
        assertThat(claim.lease().epoch()).isEqualTo(1);
        assertThat(claim.automaticAttempts()).isZero();
        assertThat(claim.consecutiveUncertainty()).isZero();
        assertThat(reconciliations.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.ready()).isZero();
            assertThat(snapshot.leased()).isEqualTo(1);
            assertThat(snapshot.expiredLeases()).isZero();
        });
    }

    @Test
    void reconciliationCompletionIsExactlyReplayableAndRejectsChangedResult() {
        AttemptContext context = retainedStart('a', true);
        var claim = reconciliations.claimNext("reconciler-a").orElseThrow();
        var result = new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.ResultKind
                        .REMOTE_UNCERTAIN,
                command(context, 'a').commandId());

        var completed = reconciliations.complete(claim.lease(), result);
        var replayed = reconciliations.complete(claim.lease(), result);

        assertThat(completed.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .CompletionStatus.RESCHEDULED);
        assertThat(completed.automaticAttempts()).isEqualTo(1);
        assertThat(completed.consecutiveUncertainty()).isEqualTo(1);
        assertThat(replayed.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .CompletionStatus.REPLAYED);
        assertThat(terminalProjectionWork.find(
                "tenant-a", "test", context.identity().attemptId())).isEmpty();
        assertReconciliationConflict(() -> reconciliations.complete(
                        claim.lease(),
                        new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .Result(
                                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                        .ResultKind.NON_CONFIRMING,
                                result.observationCommandId())),
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .ConflictException.Reason.RESULT_CONFLICT);
    }

    @Test
    void localBackpressureBacksOffSeparatelyWithoutConsumingProviderBudget()
            throws Exception {
        retainedStart('a', true);
        var claim = reconciliations.claimNext("reconciler-a").orElseThrow();

        var completed = reconciliations.complete(
                claim.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.LOCAL_BACKPRESSURE,
                        ""));

        assertThat(completed.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .CompletionStatus.RESCHEDULED);
        assertThat(completed.automaticAttempts()).isZero();
        assertThat(completed.consecutiveUncertainty()).isZero();
        Thread.sleep(150);
        var retry = reconciliations.claimNext("reconciler-a").orElseThrow();
        reconciliations.complete(
                retry.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.LOCAL_BACKPRESSURE,
                        ""));
        assertThat(jdbc.queryForObject("""
                SELECT consecutive_local_failures
                FROM rg_test_stability_attempt_observation_reconciliation_targets
                WHERE attempt_id = ?
                """, Integer.class, claim.lease().attemptId())).isEqualTo(2);
    }

    @Test
    void repeatedRemoteUncertaintyExhaustsBudgetWithoutInventingTerminalState()
            throws Exception {
        AttemptContext context = retainedStart('a', false);
        String commandId = command(context, 'a').commandId();
        var first = reconciliations.claimNext("reconciler-a").orElseThrow();
        reconciliations.complete(first.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.REMOTE_UNCERTAIN,
                        commandId));
        Thread.sleep(150);

        var second = reconciliations.claimNext("reconciler-b").orElseThrow();
        var quarantined = reconciliations.complete(second.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.NON_CONFIRMING,
                        commandId));

        assertThat(second.lease().epoch()).isEqualTo(2);
        assertThat(quarantined.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .CompletionStatus.QUARANTINED);
        assertThat(quarantined.targetStatus()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .TargetStatus.QUARANTINED);
        assertThat(quarantined.automaticAttempts()).isEqualTo(2);
        assertThat(quarantined.consecutiveUncertainty()).isEqualTo(2);
        assertThat(journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId())).isEmpty();
    }

    @Test
    void positiveActiveObservationResetsUncertaintyAndUsesSteadyPollDelay()
            throws Exception {
        AttemptContext context = retainedStart('a', true);
        String commandId = command(context, 'a').commandId();
        var first = reconciliations.claimNext("reconciler-a").orElseThrow();
        reconciliations.complete(first.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.REMOTE_UNCERTAIN,
                        commandId));
        Thread.sleep(150);

        var second = reconciliations.claimNext("reconciler-a").orElseThrow();
        var active = reconciliations.complete(second.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.POSITIVE_ACTIVE,
                        commandId));

        assertThat(active.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .CompletionStatus.RESCHEDULED);
        assertThat(active.automaticAttempts()).isEqualTo(2);
        assertThat(active.consecutiveUncertainty()).isZero();
    }

    @Test
    void positiveTerminalAtomicallyRegistersExactlyOneProjectionWorkItem() {
        AttemptContext context = retainedStart('a', true);
        var claim = reconciliations.claimNext("reconciler-a").orElseThrow();
        String observationCommandId = command(context, 'a').commandId();
        var result =
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.POSITIVE_TERMINAL,
                        observationCommandId);

        var terminal = reconciliations.complete(claim.lease(), result);
        var replayed = reconciliations.complete(claim.lease(), result);

        assertThat(terminal.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .CompletionStatus.TERMINAL);
        assertThat(replayed.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .CompletionStatus.REPLAYED);
        assertThat(reconciliations.claimNext("reconciler-b")).isEmpty();
        assertThat(terminalProjectionWork.find(
                "tenant-a", "test", context.identity().attemptId())).get().satisfies(work -> {
                    assertThat(work.status()).isEqualTo(
                            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                    .Status.READY);
                    assertThat(work.trigger().observationCommandId())
                            .isEqualTo(observationCommandId);
                    assertThat(work.executionAttempts()).isZero();
                    assertThat(work.nextAttemptAt()).isAfter(Instant.EPOCH);
                });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_stability_attempt_terminal_projection_work
                WHERE attempt_id = ?
                """, Integer.class, context.identity().attemptId())).isEqualTo(1);
        assertThat(jobs.find("tenant-a", "test", context.identity().jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.RUNNING);
    }

    @Test
    void projectionWorkRegistrationFailureRollsBackTerminalTargetTransition() {
        AttemptContext context = retainedStart('a', true);
        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal failingWork =
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal() {
                    @Override
                    public Policy policy() {
                        return Policy.DEFAULT;
                    }

                    @Override
                    public TestRuntimeTransactionMutation boundRegister(Trigger trigger) {
                        return ignored -> {
                            throw new IllegalStateException("injected registration failure");
                        };
                    }

                    @Override
                    public Optional<Entry> find(
                            String tenantId, String environmentId, String attemptId) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<Claim> claimNext(String ownerId) {
                        return Optional.empty();
                    }

                    @Override
                    public Completion complete(Lease lease, Result result) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Snapshot snapshot() {
                        return new Snapshot(Instant.EPOCH, 0, 0, 0, 0, 0, 0,
                                Optional.empty());
                    }
                };
        var failing = reconciliationJournal(10, failingWork);
        var claim = failing.claimNext("reconciler-a").orElseThrow();
        var result =
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.POSITIVE_TERMINAL,
                        command(context, 'a').commandId());

        assertThatThrownBy(() -> failing.complete(claim.lease(), result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected registration failure");

        assertThat(jdbc.queryForObject("""
                SELECT target_status
                FROM rg_test_stability_attempt_observation_reconciliation_targets
                WHERE attempt_id = ?
                """, String.class, context.identity().attemptId()))
                .isEqualTo(TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .TargetStatus.LEASED.name());
        assertThat(terminalProjectionWork.find(
                "tenant-a", "test", context.identity().attemptId())).isEmpty();
    }

    @Test
    void projectionWorkRejectsChangedTriggerForTheSameAttempt() {
        AttemptContext context = retainedStart('a', true);
        var claim = reconciliations.claimNext("reconciler-a").orElseThrow();
        reconciliations.complete(claim.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.POSITIVE_TERMINAL,
                        command(context, 'a').commandId()));
        var changed = TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Trigger
                .create(mapper, "tenant-a", "test", context.identity().attemptId(),
                        command(context, 'b').commandId(), fingerprint('9'));

        assertThatThrownBy(() -> terminalProjectionWork.boundRegister(changed).apply(jdbc))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                .ConflictException.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                        .ConflictReason.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void projectionWorkReadRejectsTamperedLifecycleState() {
        AttemptContext context = retainedStart('a', true);
        var claim = reconciliations.claimNext("reconciler-a").orElseThrow();
        reconciliations.complete(claim.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.POSITIVE_TERMINAL,
                        command(context, 'a').commandId()));
        jdbc.update("""
                UPDATE rg_test_stability_attempt_terminal_projection_work
                SET work_status = ?
                WHERE attempt_id = ?
                """, TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .Status.COMPLETED.name(), context.identity().attemptId());

        assertThatThrownBy(() -> terminalProjectionWork.find(
                "tenant-a", "test", context.identity().attemptId()))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                .ConflictException.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                        .ConflictReason.INTEGRITY_FAILURE));
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleOwnerCannotComplete() throws Exception {
        AttemptContext context = retainedStart('a', true);
        var stale = reconciliations.claimNext("reconciler-a").orElseThrow();
        Thread.sleep(1_100);

        var takeover = reconciliations.claimNext("reconciler-b").orElseThrow();

        assertThat(takeover.lease().epoch()).isEqualTo(2);
        assertThat(takeover.startCommand()).isEqualTo(context.start());
        assertReconciliationConflict(() -> reconciliations.complete(
                        stale.lease(),
                        new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .Result(
                                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                        .ResultKind.LOCAL_BACKPRESSURE,
                                "")),
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .ConflictException.Reason.LEASE_LOST);
    }

    @Test
    void reconciliationDiscoveryIsPageBounded() {
        retainedStart('a', true);
        retainedStart('b', true);
        var bounded = reconciliationJournal(1);

        assertThat(bounded.claimNext("reconciler-a")).isPresent();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_stability_attempt_observation_reconciliation_targets
                """, Integer.class)).isEqualTo(1);
        assertThat(bounded.snapshot().undiscoveredSources()).isEqualTo(1);
    }

    @Test
    void dueClaimsRotateAcrossTenantEnvironmentScopes() throws Exception {
        retainedStart('a', true, "tenant-a");
        retainedStart('b', true, "tenant-b");
        var first = reconciliations.claimNext("reconciler-a").orElseThrow();
        reconciliations.complete(first.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.LOCAL_BACKPRESSURE,
                        ""));
        Thread.sleep(150);

        var second = reconciliations.claimNext("reconciler-a").orElseThrow();

        assertThat(second.startCommand().identity().tenantId())
                .isNotEqualTo(first.startCommand().identity().tenantId());
    }

    @Test
    void reconciliationTargetTamperingFailsClosedBeforeAnotherClaim()
            throws Exception {
        retainedStart('a', true);
        var claim = reconciliations.claimNext("reconciler-a").orElseThrow();
        reconciliations.complete(claim.lease(),
                new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Result(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ResultKind.LOCAL_BACKPRESSURE,
                        ""));
        Thread.sleep(150);
        jdbc.update("""
                UPDATE rg_test_stability_attempt_observation_reconciliation_targets
                SET automatic_attempts = 99
                WHERE attempt_id = ?
                """, claim.lease().attemptId());

        assertReconciliationConflict(() -> reconciliations.claimNext("reconciler-b"),
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                        .ConflictException.Reason.INTEGRITY_FAILURE);
    }

    @Test
    void reconcilerPersistsRunningFactAndSchedulesSteadyObservation() {
        AttemptContext context = retainedStart('a', true);
        AtomicInteger descriptorCalls = new AtomicInteger();
        AtomicInteger observationCalls = new AtomicInteger();
        var authority = authority(descriptorCalls, observationCalls,
                command -> uncheckedObservation(
                        command, 101, 1,
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));

        try (var supervisor = supervisor(Duration.ofMillis(500))) {
            var result = reconciler(supervisor, (provider, deployment) -> authority)
                    .reconcileNext("reconciler-a");

            assertThat(result.stage()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.RESCHEDULED);
            assertThat(result.automaticAttempts()).isEqualTo(1);
            assertThat(result.consecutiveUncertainty()).isZero();
            assertThat(descriptorCalls).hasValue(1);
            assertThat(observationCalls).hasValue(1);
            assertThat(journal.latestPositive(
                    "tenant-a", "test", context.identity().attemptId()))
                    .get().extracting(state -> state.receipt().state())
                    .isEqualTo(
                            TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING);
        }
    }

    @Test
    void reconcilerRetainsSignedNonConfirmationAsUncertainty() {
        retainedStart('a', false);
        var authority = authority(new AtomicInteger(), new AtomicInteger(),
                command -> uncheckedObservation(
                        command, 101, 0,
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED));

        try (var supervisor = supervisor(Duration.ofMillis(500))) {
            var result = reconciler(supervisor, (provider, deployment) -> authority)
                    .reconcileNext("reconciler-a");

            assertThat(result.stage()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.RESCHEDULED);
            assertThat(result.automaticAttempts()).isEqualTo(1);
            assertThat(result.consecutiveUncertainty()).isEqualTo(1);
        }
    }

    @Test
    void reconcilerClosesFromRetainedTerminalFloorWithoutProviderIo() throws Exception {
        AttemptContext context = retainedStart('a', true);
        var completionLost = reconciliations.claimNext("reconciler-lost").orElseThrow();
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command(context, 'a');
        journal.prepare(command, descriptor);
        journal.accept(command.commandId(), observation(
                command, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL));
        Thread.sleep(1_100);
        AtomicInteger resolutions = new AtomicInteger();

        try (var supervisor = supervisor(Duration.ofMillis(500))) {
            var result = reconciler(supervisor, (provider, deployment) -> {
                resolutions.incrementAndGet();
                throw new IllegalStateException("must not resolve provider");
            }).reconcileNext("reconciler-a");

            assertThat(result.stage()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.TERMINAL);
            assertThat(result.automaticAttempts()).isZero();
            assertThat(resolutions).hasValue(0);
            assertThat(completionLost.lease().epoch()).isEqualTo(1);
            assertThat(jobs.find("tenant-a", "test", context.identity().jobId()))
                    .get().extracting(TestSuiteStabilityJobRecord::status)
                    .isEqualTo(TestSuiteStabilityJobRecord.Status.RUNNING);
        }
    }

    @Test
    void independentReplicasCannotClaimTheSameDueTarget() throws Exception {
        retainedStart('a', true);
        var replica = reconciliationJournal(10);

        var claims = concurrently(
                () -> reconciliations.claimNext("reconciler-a"),
                () -> replica.claimNext("reconciler-b"));

        assertThat(claims).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(claims).filteredOn(Optional::isEmpty).hasSize(1);
    }

    @Test
    void reconcilerObservationTimeoutConsumesUncertaintyButNotTerminalTruth() {
        AttemptContext context = retainedStart('a', true);
        var authority = authority(descriptor(Duration.ofMillis(100)),
                new AtomicInteger(), new AtomicInteger(), command -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return uncheckedObservation(
                    command, 101, 1,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING);
        });

        try (var supervisor = supervisor(Duration.ofMillis(100))) {
            var result = reconciler(supervisor, (provider, deployment) -> authority)
                    .reconcileNext("reconciler-a");

            assertThat(result.stage()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.RESCHEDULED);
            assertThat(result.automaticAttempts()).isEqualTo(1);
            assertThat(result.consecutiveUncertainty()).isEqualTo(1);
            assertThat(journal.latestPositive(
                    "tenant-a", "test", context.identity().attemptId())).isEmpty();
        }
    }

    @Test
    void reconcilerResolverOutageIsLocalBackpressure() {
        retainedStart('a', true);

        try (var supervisor = supervisor(Duration.ofMillis(500))) {
            var result = reconciler(supervisor, (provider, deployment) -> {
                throw new IllegalStateException("resolver unavailable");
            }).reconcileNext("reconciler-a");

            assertThat(result.stage()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.RESCHEDULED);
            assertThat(result.automaticAttempts()).isZero();
            assertThat(result.consecutiveUncertainty()).isZero();
        }
    }

    @Test
    void reconcilerCountsProviderCallBeforeQuarantiningInvalidAttestation() {
        AttemptContext context = retainedStart('a', true);
        var authority = authority(new AtomicInteger(), new AtomicInteger(), command -> {
            var signed = uncheckedObservation(
                    command, 101, 1,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING);
            return new TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation(
                    signed.schemaVersion(), signed.receipt(), signed.keyId(),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]));
        });

        try (var supervisor = supervisor(Duration.ofMillis(500))) {
            var result = reconciler(supervisor, (provider, deployment) -> authority)
                    .reconcileNext("reconciler-a");

            assertThat(result.stage()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptObservationReconciler.Stage.QUARANTINED);
            assertThat(result.automaticAttempts()).isEqualTo(1);
            assertThat(journal.latestPositive(
                    "tenant-a", "test", context.identity().attemptId())).isEmpty();
        }
    }

    @Test
    void reconcilerFailsFastWhenLeaseCannotContainCommandWindow() {
        try (var supervisor = supervisor(Duration.ofMillis(500))) {
            assertThatThrownBy(() -> new
                    TestSuiteStabilityPhysicalAttemptObservationReconciler(
                    mapper, reconciliations, starts, journal,
                    new TestSuiteStabilityPhysicalAttemptObservationCoordinator(
                            journal, supervisor),
                    (provider, deployment) -> null,
                    new TestSuiteStabilityPhysicalAttemptObservationReconciler.Policy(
                            Duration.ofMillis(950), Duration.ofMillis(100))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lease cannot contain");
        }
    }

    private ConcurrentAcceptance acceptConcurrently(
            TestSuiteStabilityPhysicalAttemptObservationJournal target,
            TestSuiteStabilityPhysicalAttemptObservationCommand command) throws Exception {
        try {
            target.accept(command.commandId(), observation(
                    command, 101, 1,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING));
            return new ConcurrentAcceptance(command.commandId(), true, null);
        } catch (TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictException conflict) {
            return new ConcurrentAcceptance(command.commandId(), false, conflict.reason());
        }
    }

    @SafeVarargs
    private final <T> List<T> concurrently(Callable<T>... operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.length);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(
                operations.length, task -> Thread.ofPlatform()
                        .daemon(true)
                        .name("physical-attempt-observation-journal-race-"
                                + CONCURRENCY_THREAD_SEQUENCE.incrementAndGet())
                        .unstarted(task));
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(
                            CONCURRENCY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException(
                                "Observation journal concurrency barrier timed out");
                    }
                    return operation.call();
                }));
            }
            if (!ready.await(CONCURRENCY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "Observation journal concurrency participants did not arrive");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(
                        CONCURRENCY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            }
            return List.copyOf(results);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(
                    CONCURRENCY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    private TerminalFixture terminalFixture(
            char id,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition,
            TestSuiteStabilityQueuePolicy policy) throws Exception {
        AttemptContext context = retainedStart(id, true, "tenant-a", policy);
        Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> cancellation =
                Optional.empty();
        if (disposition
                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED) {
            TestSuiteStabilityAttemptCancellationCommand cancellationCommand =
                    cancellationCommand(context, id);
            cancellations.prepare(cancellationCommand, cancellationDescriptor());
            cancellations.accept(cancellationCommand.commandId(),
                    cancellationAttestation(cancellationCommand, 501));
            cancellation = cancellations.find(
                    "tenant-a", "test", cancellationCommand.commandId());
        }
        TestSuiteStabilityPhysicalAttemptObservationCommand observationCommand =
                command(context, id);
        journal.prepare(observationCommand, descriptor);
        journal.accept(observationCommand.commandId(), observation(
                observationCommand, 101, 1,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL,
                disposition));
        String parentRunId = disposition
                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.SUCCEEDED
                ? "stability-run-" + String.valueOf(id).repeat(64) : "";
        String parentEvidence = disposition
                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.SUCCEEDED ? fingerprint('7') : "";
        TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand projectionCommand =
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand.create(
                        mapper,
                        attempts.find("tenant-a", "test", context.identity().attemptId())
                                .orElseThrow(),
                        starts.find("tenant-a", "test", context.start().commandId())
                                .orElseThrow(),
                        journal.find("tenant-a", "test", observationCommand.commandId())
                                .orElseThrow(),
                        journal.latestPositive(
                                "tenant-a", "test", context.identity().attemptId())
                                .orElseThrow(),
                        cancellation, parentRunId, parentEvidence);
        return new TerminalFixture(
                context, projectionCommand, parentRunId, parentEvidence);
    }

    private TestSuiteStabilityAttemptCancellationCommand cancellationCommand(
            AttemptContext context, char challenge) {
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptIdentity identity = context.identity();
        return TestSuiteStabilityAttemptCancellationCommand.create(
                mapper, identity.tenantId(), identity.environmentId(), identity.jobId(),
                identity.attemptId(), identity.ownerId(), identity.leaseEpoch(),
                identity.runtimeBindingFingerprint(),
                TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED,
                now.minusMillis(10), now.plusSeconds(30), challenge(challenge));
    }

    private TestSuiteStabilityAttemptCancellationReceipt.Attestation cancellationAttestation(
            TestSuiteStabilityAttemptCancellationCommand command,
            long providerSequence) throws Exception {
        Instant confirmedAt = databaseTime();
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                new TestSuiteStabilityAttemptCancellationReceipt(
                        TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), PROVIDER_ID,
                        DEPLOYMENT_ID, command.attemptId(), command.leaseEpoch(),
                        providerSequence,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS,
                        TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                        TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                        fingerprint('4'), fingerprint('6'), confirmedAt);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(TestSuiteStabilityAttemptCancellationVerifier.signingBytes(
                mapper,
                TestSuiteStabilityAttemptCancellationReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID));
        return new TestSuiteStabilityAttemptCancellationReceipt.Attestation(
                TestSuiteStabilityAttemptCancellationReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }

    private TestSuiteStabilityAttemptCancellationAuthority.Descriptor
            cancellationDescriptor() {
        return new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                TestSuiteStabilityAttemptCancellationAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofMillis(100));
    }

    private void assertProjectionConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason reason) {
        assertThatThrownBy(invocation).isInstanceOfSatisfying(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ConflictException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private AttemptContext retainedStart(char id, boolean confirmed) {
        return retainedStart(id, confirmed, "tenant-a");
    }

    private AttemptContext retainedStart(char id, boolean confirmed, String tenantId) {
        return retainedStart(id, confirmed, tenantId, POLICY);
    }

    private AttemptContext retainedStart(
            char id,
            boolean confirmed,
            String tenantId,
            TestSuiteStabilityQueuePolicy policy) {
        return retainedStart(id, confirmed, tenantId, policy,
                Instant.now().plus(Duration.ofHours(1)));
    }

    private AttemptContext retainedStart(
            char id,
            boolean confirmed,
            String tenantId,
            TestSuiteStabilityQueuePolicy policy,
            Instant deadline) {
        TestSuiteStabilityJobClaim claim = claimed(id, tenantId, policy, deadline);
        TestSuiteStabilityPhysicalAttemptIdentity identity =
                TestSuiteStabilityPhysicalAttemptIdentity.create(
                        mapper, claim.lease(), fingerprint(id), PROVIDER_ID, DEPLOYMENT_ID,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
        attempts.reserve(identity);
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptStartCommand start =
                TestSuiteStabilityPhysicalAttemptStartCommand.create(
                        mapper, identity,
                        "stability-envelope-" + String.valueOf(id).repeat(64),
                        fingerprint(id), now.minusMillis(10), now.plusSeconds(30),
                        challenge(id));
        starts.prepare(start, startDescriptor);
        if (confirmed) {
            try {
                starts.accept(start.commandId(), startObservation(
                        start, startSequence.incrementAndGet()));
            } catch (Exception failure) {
                throw new IllegalStateException("Unable to retain test start", failure);
            }
        }
        return new AttemptContext(identity, start, claim, confirmed);
    }

    private TestSuiteStabilityJobClaim claimed(char id) {
        return claimed(id, "tenant-a");
    }

    private TestSuiteStabilityJobClaim claimed(char id, String tenantId) {
        return claimed(id, tenantId, POLICY);
    }

    private TestSuiteStabilityJobClaim claimed(
            char id, String tenantId, TestSuiteStabilityQueuePolicy policy) {
        return claimed(id, tenantId, policy, Instant.now().plus(Duration.ofHours(1)));
    }

    private TestSuiteStabilityJobClaim claimed(
            char id,
            String tenantId,
            TestSuiteStabilityQueuePolicy policy,
            Instant deadline) {
        jobs.submit(submission(id, tenantId, deadline), policy);
        TestSuiteStabilityJobClaim claim = jobs.claimNext("test", "worker-a", policy);
        assertThat(claim.outcome()).isEqualTo(TestSuiteStabilityJobClaim.Outcome.ACQUIRED);
        return claim;
    }

    private TestSuiteStabilityPhysicalAttemptObservationCommand command(
            AttemptContext context, char challenge) {
        var floor = journal.latestPositive(
                "tenant-a", "test", context.identity().attemptId());
        String expectedProcess = floor
                .filter(state -> state.receipt().processIdentityConfirmed())
                .map(state -> state.receipt().processIdentityFingerprint())
                .orElse(context.confirmed() ? fingerprint('4') : "");
        long revision = floor.map(
                state -> state.receipt().attemptRevision()).orElse(0L);
        Instant now = databaseTime();
        return command(context, expectedProcess, revision, challenge,
                now.minusMillis(10), now.plusSeconds(5));
    }

    private TestSuiteStabilityPhysicalAttemptObservationCommand command(
            AttemptContext context,
            String expectedProcess,
            long minimumRevision,
            char challenge,
            Instant requestedAt,
            Instant deadlineAt) {
        return TestSuiteStabilityPhysicalAttemptObservationCommand.create(
                mapper, context.start(), expectedProcess, minimumRevision,
                requestedAt, deadlineAt, challenge(challenge));
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observation(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            long providerSequence,
            long revision,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state)
            throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition =
                state == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL
                        ? TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.SUCCEEDED
                        : TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.NONE;
        return observation(command, providerSequence, revision, state, disposition);
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation
            uncheckedObservation(
                    TestSuiteStabilityPhysicalAttemptObservationCommand command,
                    long providerSequence,
                    long revision,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.State state) {
        try {
            return observation(command, providerSequence, revision, state);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to sign test observation", failure);
        }
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observation(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            long providerSequence,
            long revision,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition)
            throws Exception {
        Instant now = databaseTime();
        if (now.isBefore(command.requestedAt())) {
            now = command.requestedAt();
        }
        return observation(command, providerSequence, revision, state,
                keyPair, now, now, fingerprint('4'), disposition);
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observation(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            long providerSequence,
            long revision,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state,
            KeyPair signer,
            Instant stateEffectiveAt,
            Instant confirmedAt,
            String processFingerprint) throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition =
                state == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL
                        ? TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.SUCCEEDED
                        : TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.NONE;
        return observation(command, providerSequence, revision, state, signer,
                stateEffectiveAt, confirmedAt, processFingerprint, disposition);
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observation(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            long providerSequence,
            long revision,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state,
            KeyPair signer,
            Instant stateEffectiveAt,
            Instant confirmedAt,
            String processFingerprint,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition)
            throws Exception {
        boolean positive = state
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED
                && state
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.INDETERMINATE;
        boolean process = state
                == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING
                || state == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL;
        boolean terminal = state
                == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL;
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt =
                new TestSuiteStabilityPhysicalAttemptObservationReceipt(
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), PROVIDER_ID,
                        DEPLOYMENT_ID, command.identity().attemptId(),
                        command.identity().identityFingerprint(),
                        command.startCommand().commandId(),
                        command.startCommand().commandFingerprint(),
                        command.identity().leaseEpoch(), providerSequence, revision,
                        command.identity().isolationMode(), state,
                        process ? processFingerprint : "",
                        positive ? fingerprint('6') : "", disposition,
                        terminal ? fingerprint(disposition
                                == TestSuiteStabilityPhysicalAttemptObservationReceipt
                                .TerminalDisposition.SUCCEEDED ? '7' : '8') : "",
                        stateEffectiveAt, confirmedAt);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(TestSuiteStabilityPhysicalAttemptObservationVerifier.signingBytes(
                mapper,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID));
        return new TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation startObservation(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            long providerSequence) throws Exception {
        return startObservation(command, providerSequence,
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED);
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation startObservation(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            long providerSequence,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome outcome) throws Exception {
        Instant confirmedAt = databaseTime();
        boolean confirmed = outcome
                != TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.REJECTED;
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt =
                new TestSuiteStabilityPhysicalAttemptStartReceipt(
                        TestSuiteStabilityPhysicalAttemptStartReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), PROVIDER_ID,
                        DEPLOYMENT_ID, command.identity().attemptId(),
                        command.identity().identityFingerprint(),
                        command.identity().leaseEpoch(), providerSequence,
                        command.identity().isolationMode(),
                        outcome, confirmed ? fingerprint('4') : "",
                        confirmed ? fingerprint('5') : "", confirmedAt);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(TestSuiteStabilityPhysicalAttemptStartVerifier.signingBytes(
                mapper,
                TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID));
        return new TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }

    private TestSuiteStabilityPhysicalAttemptStartVerifier startVerifier() {
        Instant now = databaseTime();
        return new TestSuiteStabilityPhysicalAttemptStartVerifier(
                mapper, Set.of(new TestSuiteStabilityPhysicalAttemptStartVerifier.TrustKey(
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, keyPair.getPublic(),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))),
                Duration.ofSeconds(2));
    }

    private TestSuiteStabilityPhysicalAttemptObservationVerifier observationVerifier() {
        Instant now = databaseTime();
        return new TestSuiteStabilityPhysicalAttemptObservationVerifier(
                mapper,
                Set.of(new TestSuiteStabilityPhysicalAttemptObservationVerifier.TrustKey(
                        PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, keyPair.getPublic(),
                        now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))),
                Duration.ofSeconds(2));
    }

    private TestSuiteStabilityAttemptCancellationVerifier cancellationVerifier() {
        Instant now = databaseTime();
        return new TestSuiteStabilityAttemptCancellationVerifier(
                mapper,
                Set.of(new TestSuiteStabilityAttemptCancellationVerifier.TrustKey(
                        PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, keyPair.getPublic(),
                        now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))),
                Duration.ofSeconds(2));
    }

    private TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor(
            Duration latency) {
        return new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                latency, Duration.ofHours(1));
    }

    private TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor(
            Duration observationTimeout) {
        return new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                        Duration.ofSeconds(1), observationTimeout, 1));
    }

    private DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
            reconciliationJournal(int discoveryPageSize) {
        return reconciliationJournal(discoveryPageSize, terminalProjectionWork);
    }

    private DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
            reconciliationJournal(
                    int discoveryPageSize,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal work) {
        var value =
                new DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal(
                        jdbc, mapper, starts,
                        new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .Policy(
                                Duration.ofSeconds(1), Duration.ofMillis(100),
                                Duration.ofMillis(100), Duration.ofMillis(400), 2,
                                Duration.ofMinutes(1), discoveryPageSize),
                        transactions, work);
        value.init();
        return value;
    }

    private TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler(
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor,
            TestSuiteStabilityPhysicalAttemptObservationReconciler.AuthorityResolver resolver) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciler(
                mapper, reconciliations, starts, journal,
                new TestSuiteStabilityPhysicalAttemptObservationCoordinator(
                        journal, supervisor),
                resolver,
                new TestSuiteStabilityPhysicalAttemptObservationReconciler.Policy(
                        Duration.ofMillis(500), Duration.ofMillis(100)));
    }

    private TestSuiteStabilityPhysicalAttemptObservationAuthority authority(
            AtomicInteger descriptorCalls,
            AtomicInteger observationCalls,
            java.util.function.Function<TestSuiteStabilityPhysicalAttemptObservationCommand,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation> response) {
        return authority(descriptor, descriptorCalls, observationCalls, response);
    }

    private TestSuiteStabilityPhysicalAttemptObservationAuthority authority(
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor currentDescriptor,
            AtomicInteger descriptorCalls,
            AtomicInteger observationCalls,
            java.util.function.Function<TestSuiteStabilityPhysicalAttemptObservationCommand,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation> response) {
        return new TestSuiteStabilityPhysicalAttemptObservationAuthority() {
            @Override
            public Descriptor descriptor() {
                descriptorCalls.incrementAndGet();
                return currentDescriptor;
            }

            @Override
            public TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observe(
                    TestSuiteStabilityPhysicalAttemptObservationCommand command) {
                observationCalls.incrementAndGet();
                return response.apply(command);
            }
        };
    }

    private Instant databaseTime() {
        Timestamp timestamp = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return java.util.Objects.requireNonNull(timestamp).toInstant()
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private static TestSuiteStabilityJobSubmission submission(char id) {
        return submission(id, "tenant-a");
    }

    private static TestSuiteStabilityJobSubmission submission(char id, String tenantId) {
        return submission(id, tenantId, Instant.now().plus(Duration.ofHours(1)));
    }

    private static TestSuiteStabilityJobSubmission submission(
            char id, String tenantId, Instant deadline) {
        TestSuiteStabilityExecutionRequest request = new TestSuiteStabilityExecutionRequest(
                "", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                "request-" + id, 3, Map.of("pipeline", "nightly"));
        return new TestSuiteStabilityJobSubmission(
                "stability-job-" + String.valueOf(id).repeat(64), request,
                fingerprint('9'), "INTERNAL",
                new TestSuiteStabilityJobPrincipal(
                        tenantId, "org-a", "project-a", "test", "sg-1", "SERVICE",
                        "ci-runner", "", "TEST_EXECUTION", "correlation-" + id,
                        Set.of("test-runners"), "INTERNAL", ""),
                TestSuiteStabilityJobSubmission.Priority.NORMAL,
                deadline);
    }

    private static void assertConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
            TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason reason) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationJournal
                                .ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static void assertReconciliationConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                    .ConflictException.Reason reason) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
                                .ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String challenge(char value) {
        byte[] challenge = new byte[32];
        java.util.Arrays.fill(challenge, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
    }

    private record AttemptContext(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityPhysicalAttemptStartCommand start,
            TestSuiteStabilityJobClaim claim,
            boolean confirmed) {
    }

    private record TerminalFixture(
            AttemptContext context,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand command,
            String parentRunId,
            String parentEvidenceFingerprint) {
    }

    private record ConcurrentAcceptance(
            String commandId,
            boolean accepted,
            TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason conflict) {
    }

    private record CompletedParentAuthority(
            String runId,
            String evidenceFingerprint) implements TestSuiteStabilityJobParentAuthority {

        @Override
        public Resolution stop(
                TestSuiteStabilityJobRecord job,
                TestSuiteStabilityExecutionStop.Reason reason,
                String failureCode,
                Duration retention) {
            return Resolution.completed(runId, evidenceFingerprint);
        }

        @Override
        public Resolution requireCompleted(
                TestSuiteStabilityJobRecord job,
                String stabilityRunId,
                String expectedEvidenceFingerprint) {
            assertThat(stabilityRunId).isEqualTo(runId);
            assertThat(expectedEvidenceFingerprint).isEqualTo(evidenceFingerprint);
            return Resolution.completed(runId, evidenceFingerprint);
        }
    }

    private static final class StoppedParentAuthority
            implements TestSuiteStabilityJobParentAuthority {

        @Override
        public Resolution stop(
                TestSuiteStabilityJobRecord job,
                TestSuiteStabilityExecutionStop.Reason reason,
                String failureCode,
                Duration retention) {
            return Resolution.stopped();
        }

        @Override
        public Resolution requireCompleted(
                TestSuiteStabilityJobRecord job,
                String stabilityRunId,
                String evidenceFingerprint) {
            return Resolution.stopped();
        }
    }
}
