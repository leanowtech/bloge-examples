package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobParentAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobPrincipal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmission;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptIdentity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationVerifier;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptStartVerifier;
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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
                "retention-a", Duration.ofSeconds(30), transactions);
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

    private AttemptContext retainedStart(char id, boolean confirmed) {
        TestSuiteStabilityJobClaim claim = claimed(id);
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
        jobs.submit(submission(id), POLICY);
        TestSuiteStabilityJobClaim claim = jobs.claimNext("test", "worker-a", POLICY);
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
        Instant confirmedAt = databaseTime();
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt =
                new TestSuiteStabilityPhysicalAttemptStartReceipt(
                        TestSuiteStabilityPhysicalAttemptStartReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), PROVIDER_ID,
                        DEPLOYMENT_ID, command.identity().attemptId(),
                        command.identity().identityFingerprint(),
                        command.identity().leaseEpoch(), providerSequence,
                        command.identity().isolationMode(),
                        TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                        fingerprint('4'), fingerprint('5'), confirmedAt);
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

    private TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor(
            Duration latency) {
        return new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                latency, Duration.ofHours(1));
    }

    private Instant databaseTime() {
        Timestamp timestamp = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return java.util.Objects.requireNonNull(timestamp).toInstant()
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private static TestSuiteStabilityJobSubmission submission(char id) {
        TestSuiteStabilityExecutionRequest request = new TestSuiteStabilityExecutionRequest(
                "", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                "request-" + id, 3, Map.of("pipeline", "nightly"));
        return new TestSuiteStabilityJobSubmission(
                "stability-job-" + String.valueOf(id).repeat(64), request,
                fingerprint('9'), "INTERNAL",
                new TestSuiteStabilityJobPrincipal(
                        "tenant-a", "org-a", "project-a", "test", "sg-1", "SERVICE",
                        "ci-runner", "", "TEST_EXECUTION", "correlation-" + id,
                        Set.of("test-runners"), "INTERNAL", ""),
                TestSuiteStabilityJobSubmission.Priority.NORMAL,
                Instant.now().plus(Duration.ofHours(1)));
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

    private record ConcurrentAcceptance(
            String commandId,
            boolean accepted,
            TestSuiteStabilityPhysicalAttemptObservationJournal.ConflictReason conflict) {
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
