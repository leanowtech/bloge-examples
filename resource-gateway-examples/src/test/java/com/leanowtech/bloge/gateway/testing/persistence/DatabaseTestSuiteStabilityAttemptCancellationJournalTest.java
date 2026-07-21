package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationCallSupervisor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationCoordinator;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationJournal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityAttemptCancellationJournalTest {

    private static final String PROVIDER_ID = "attempt-runtime-a";
    private static final String DEPLOYMENT_ID = "attempt-runtime-a.generation-7";
    private static final String KEY_ID = "attempt-runtime-a.key-3";
    private static final long CONCURRENCY_TIMEOUT_MILLIS = 5_000L;
    private static final AtomicLong CONCURRENCY_THREAD_SEQUENCE = new AtomicLong();

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private KeyPair keyPair;
    private TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor;
    private DatabaseTestSuiteStabilityAttemptCancellationJournal journal;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:attempt-cancel-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        descriptor = descriptor(Duration.ofSeconds(5));
        TestSuiteStabilityAttemptCancellationVerifier verifier =
                new TestSuiteStabilityAttemptCancellationVerifier(
                        mapper,
                        Set.of(new TestSuiteStabilityAttemptCancellationVerifier.TrustKey(
                                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, keyPair.getPublic(),
                                databaseTime().minus(Duration.ofDays(1)),
                                databaseTime().plus(Duration.ofDays(1)))),
                        Duration.ofSeconds(2));
        journal = new DatabaseTestSuiteStabilityAttemptCancellationJournal(
                jdbc, mapper, verifier);
        journal.init();
    }

    @Test
    void preparesAndAtomicallyAcceptsProviderConfirmedTermination() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');

        TestSuiteStabilityAttemptCancellationJournal.Preparation prepared =
                journal.prepare(command, descriptor);
        TestSuiteStabilityAttemptCancellationJournal.Acceptance accepted = journal.accept(
                command.commandId(), attestation(command, 11,
                        TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED));

        assertThat(prepared.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.PreparationStatus.PREPARED);
        assertThat(accepted.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.CONFIRMED);
        assertThat(accepted.entry().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED);
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .contains(accepted.entry());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_cancel_provider_sequences
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void exactPrepareAndTerminalAcceptanceReplayWithoutAdvancingSequence() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        var attestation = attestation(command, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED);

        assertThat(journal.prepare(command, descriptor).status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.PreparationStatus.PREPARED);
        assertThat(journal.prepare(command, descriptor).status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.PreparationStatus.REPLAYED);
        assertThat(journal.accept(command.commandId(), attestation).status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.CONFIRMED);
        assertThat(journal.accept(command.commandId(), attestation).status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.REPLAYED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_cancel_provider_sequences
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void retainedPreparationReplaysAfterItsNewInvocationWindowCloses() throws Exception {
        Instant now = databaseTime();
        TestSuiteStabilityAttemptCancellationCommand command = command(
                1, 7, 'a', now, now.plusMillis(100));
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor fastDescriptor =
                descriptor(Duration.ofMillis(100));
        journal.prepare(command, fastDescriptor);
        Thread.sleep(150L);

        assertThat(journal.prepare(command, fastDescriptor).status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.PreparationStatus.REPLAYED);
        assertThatThrownBy(() -> journal.authorizeInvocation(command.commandId()))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationJournal.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationJournal.ConflictReason
                                        .COMMAND_EXPIRED));
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityAttemptCancellationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
    }

    @Test
    void sameAttemptEpochCannotBeReboundToAnotherCommand() {
        TestSuiteStabilityAttemptCancellationCommand first = command(1, 7, 'a');
        TestSuiteStabilityAttemptCancellationCommand changed = command(1, 7, 'b');
        journal.prepare(first, descriptor);

        assertThatThrownBy(() -> journal.prepare(changed, descriptor))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationJournal.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationJournal.ConflictReason
                                        .ATTEMPT_COMMAND_CONFLICT));
        assertThat(journal.find("tenant-a", "test", first.commandId())).isPresent();
        assertThat(journal.find("tenant-a", "test", changed.commandId())).isEmpty();
    }

    @Test
    void terminalAttestationCannotBeRewritten() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        journal.prepare(command, descriptor);
        journal.accept(command.commandId(), attestation(command, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED));

        assertThatThrownBy(() -> journal.accept(command.commandId(), attestation(command, 12,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationJournal.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationJournal.ConflictReason
                                        .IDEMPOTENCY_CONFLICT));
    }

    @Test
    void providerSequenceMustAdvanceAcrossDifferentAttempts() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand first = command(1, 7, 'a');
        TestSuiteStabilityAttemptCancellationCommand second = command(2, 8, 'b');
        journal.prepare(first, descriptor);
        journal.accept(first.commandId(), attestation(first, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED));
        journal.prepare(second, descriptor);

        assertThatThrownBy(() -> journal.accept(second.commandId(), attestation(second, 10,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationJournal.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationJournal.ConflictReason
                                        .PROVIDER_SEQUENCE_ROLLBACK));
        assertThat(journal.find("tenant-a", "test", second.commandId()))
                .get().extracting(TestSuiteStabilityAttemptCancellationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);

        assertThat(journal.accept(second.commandId(), attestation(second, 12,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED)).status())
                .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.CONFIRMED);
    }

    @Test
    void invalidSignatureRollsBackAndLeavesPreparedCommandRecoverable() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        journal.prepare(command, descriptor);
        KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        assertThatThrownBy(() -> journal.accept(command.commandId(), attestation(
                command, 11, TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                wrong)))
                .isInstanceOf(TestSuiteStabilityAttemptCancellationVerifier
                        .VerificationException.class);
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityAttemptCancellationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_cancel_provider_floors
                """, Integer.class)).isZero();
    }

    @Test
    void signedNotFoundIsDurablyUnconfirmedNotCancelled() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        journal.prepare(command, descriptor);

        var accepted = journal.accept(command.commandId(), attestation(command, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.NOT_FOUND));

        assertThat(accepted.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.UNCONFIRMED);
        assertThat(accepted.entry().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.UNCONFIRMED);
        assertThat(accepted.entry().attestation()).get()
                .extracting(value -> value.receipt().terminationConfirmed())
                .isEqualTo(false);
    }

    @Test
    void coordinatorPersistsVerifiedTerminationAndReplaysWithoutProvider() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        var signed = attestation(command, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED);
        AtomicInteger descriptorCalls = new AtomicInteger();
        AtomicInteger cancellationCalls = new AtomicInteger();
        TestSuiteStabilityAttemptCancellationAuthority authority = authority(
                descriptorCalls, cancellationCalls, ignored -> signed);

        try (var supervisor = supervisor(Duration.ofSeconds(1))) {
            var coordinator = new TestSuiteStabilityAttemptCancellationCoordinator(
                    journal, supervisor);

            assertThat(coordinator.cancel(authority, command).status()).isEqualTo(
                    TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.CONFIRMED);
            assertThat(coordinator.cancel(authority, command).status()).isEqualTo(
                    TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.REPLAYED);
        }

        assertThat(descriptorCalls).hasValue(1);
        assertThat(cancellationCalls).hasValue(1);
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityAttemptCancellationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_cancel_provider_sequences
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void coordinatorPersistsVerifiedNotFoundAsUnconfirmed() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        var signed = attestation(command, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.NOT_FOUND);
        TestSuiteStabilityAttemptCancellationAuthority authority = authority(
                new AtomicInteger(), new AtomicInteger(), ignored -> signed);

        TestSuiteStabilityAttemptCancellationJournal.Acceptance accepted;
        try (var supervisor = supervisor(Duration.ofSeconds(1))) {
            accepted = new TestSuiteStabilityAttemptCancellationCoordinator(journal, supervisor)
                    .cancel(authority, command);
        }

        assertThat(accepted.status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus.UNCONFIRMED);
        assertThat(accepted.entry().status()).isEqualTo(
                TestSuiteStabilityAttemptCancellationJournal.Status.UNCONFIRMED);
        assertThat(accepted.entry().attestation()).get()
                .extracting(value -> value.receipt().terminationConfirmed())
                .isEqualTo(false);
    }

    @Test
    void coordinatorTimeoutLeavesDurablePreparationWithoutProviderSequence() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        var signed = attestation(command, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED);
        TestSuiteStabilityAttemptCancellationAuthority authority = authority(
                new AtomicInteger(), new AtomicInteger(), ignored -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(5));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return signed;
                });

        try (var supervisor = supervisor(Duration.ofMillis(100))) {
            var coordinator = new TestSuiteStabilityAttemptCancellationCoordinator(
                    journal, supervisor);

            assertThatThrownBy(() -> coordinator.cancel(authority, command))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityAttemptCancellationCallSupervisor
                                    .InvocationException.class,
                            failure -> assertThat(failure.disposition()).isEqualTo(
                                    TestSuiteStabilityAttemptCancellationCallSupervisor
                                            .Disposition.TIMED_OUT));
        }

        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityAttemptCancellationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_cancel_provider_sequences
                """, Integer.class)).isZero();
    }

    @Test
    void scopeLookupDoesNotRevealAnotherTenantOrEnvironment() {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        journal.prepare(command, descriptor);

        assertThat(journal.find("tenant-b", "test", command.commandId())).isEmpty();
        assertThat(journal.find("tenant-a", "staging", command.commandId())).isEmpty();
        assertThat(journal.find("tenant-a", "test", command.commandId())).isPresent();
    }

    @Test
    void expiredCommandAndIncompatibleProviderFailBeforePersistence() {
        Instant now = databaseTime();
        TestSuiteStabilityAttemptCancellationCommand expired = command(
                1, 7, 'a', now.minusSeconds(2), now.minusSeconds(1));
        TestSuiteStabilityAttemptCancellationCommand live = command(2, 8, 'b');

        assertThatThrownBy(() -> journal.prepare(expired, descriptor))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationJournal.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationJournal.ConflictReason
                                        .COMMAND_EXPIRED));
        assertThatThrownBy(() -> journal.prepare(
                live, descriptor(Duration.ofSeconds(31))))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationJournal.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationJournal.ConflictReason
                                        .PROVIDER_INCOMPATIBLE));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_cancel_entries
                """, Integer.class)).isZero();
    }

    @Test
    void invocationAuthorizationRejectsInsufficientRemainingProviderWindow() throws Exception {
        Instant now = databaseTime();
        TestSuiteStabilityAttemptCancellationCommand command = command(
                1, 7, 'a', now, now.plusSeconds(1));
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor slowDescriptor =
                descriptor(Duration.ofSeconds(1));
        journal.prepare(command, slowDescriptor);
        Thread.sleep(10L);

        assertThatThrownBy(() -> journal.authorizeInvocation(command.commandId()))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationJournal.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationJournal.ConflictReason
                                        .PROVIDER_INCOMPATIBLE));
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityAttemptCancellationJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
    }

    @Test
    void entryAndFloorTamperingFailClosed() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand first = command(1, 7, 'a');
        journal.prepare(first, descriptor);
        jdbc.update("""
                UPDATE rg_test_stability_attempt_cancel_entries
                SET status = 'CONFIRMED'
                WHERE command_id = ?
                """, first.commandId());
        assertThatThrownBy(() -> journal.find("tenant-a", "test", first.commandId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Attempt cancellation journal entry integrity failed");

        TestSuiteStabilityAttemptCancellationCommand second = command(2, 8, 'b');
        JdbcTemplate cleanJdbc = newDatabaseJdbc();
        DatabaseTestSuiteStabilityAttemptCancellationJournal cleanJournal =
                new DatabaseTestSuiteStabilityAttemptCancellationJournal(
                        cleanJdbc, mapper, verifierFor(keyPair));
        cleanJournal.init();
        cleanJournal.prepare(second, descriptor);
        cleanJournal.accept(second.commandId(), attestation(second, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED));
        cleanJdbc.update("""
                UPDATE rg_test_stability_attempt_cancel_provider_floors
                SET provider_sequence = 1
                """);
        TestSuiteStabilityAttemptCancellationCommand third = command(3, 9, 'c');
        cleanJournal.prepare(third, descriptor);
        assertThatThrownBy(() -> cleanJournal.accept(third.commandId(), attestation(third, 12,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Attempt cancellation provider floor integrity failed");
    }

    @Test
    void terminalReadRequiresItsImmutableProviderSequence() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        journal.prepare(command, descriptor);
        journal.accept(command.commandId(), attestation(command, 11,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED));
        jdbc.update("""
                DELETE FROM rg_test_stability_attempt_cancel_provider_sequences
                WHERE command_id = ?
                """, command.commandId());

        assertThatThrownBy(() -> journal.find("tenant-a", "test", command.commandId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Attempt cancellation provider sequence continuity failed");
    }

    @Test
    void concurrentExactPreparationHasOneCreatorAndOneReplay() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand command = command(1, 7, 'a');
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = concurrencyPool();
        try {
            Future<TestSuiteStabilityAttemptCancellationJournal.Preparation> first =
                    pool.submit(() -> {
                        await(start, CONCURRENCY_TIMEOUT_MILLIS);
                        return journal.prepare(command, descriptor);
                    });
            Future<TestSuiteStabilityAttemptCancellationJournal.Preparation> second =
                    pool.submit(() -> {
                        await(start, CONCURRENCY_TIMEOUT_MILLIS);
                        return journal.prepare(command, descriptor);
                    });
            start.countDown();

            assertThat(List.of(get(first).status(), get(second).status()))
                    .containsExactlyInAnyOrder(
                            TestSuiteStabilityAttemptCancellationJournal.PreparationStatus.PREPARED,
                            TestSuiteStabilityAttemptCancellationJournal.PreparationStatus.REPLAYED);
        } finally {
            start.countDown();
            shutdown(pool);
        }
    }

    @Test
    void concurrentReplicaAcceptanceSerializesTheProviderSequenceFloor() throws Exception {
        TestSuiteStabilityAttemptCancellationCommand first = command(1, 7, 'a');
        TestSuiteStabilityAttemptCancellationCommand second = command(2, 8, 'b');
        journal.prepare(first, descriptor);
        journal.prepare(second, descriptor);
        DatabaseTestSuiteStabilityAttemptCancellationJournal replica =
                new DatabaseTestSuiteStabilityAttemptCancellationJournal(
                        new JdbcTemplate(jdbc.getDataSource()), mapper, verifierFor(keyPair));
        replica.init();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = concurrencyPool();
        try {
            Future<ConcurrentAcceptance> firstResult = pool.submit(() -> acceptConcurrently(
                    journal, first, ready, start));
            Future<ConcurrentAcceptance> secondResult = pool.submit(() -> acceptConcurrently(
                    replica, second, ready, start));
            await(ready, CONCURRENCY_TIMEOUT_MILLIS);
            start.countDown();

            List<ConcurrentAcceptance> results = List.of(
                    get(firstResult), get(secondResult));
            assertThat(results).filteredOn(ConcurrentAcceptance::confirmed).hasSize(1);
            assertThat(results).filteredOn(result -> result.conflict()
                            == TestSuiteStabilityAttemptCancellationJournal.ConflictReason
                            .PROVIDER_SEQUENCE_ROLLBACK)
                    .hasSize(1);
            ConcurrentAcceptance rejected = results.stream()
                    .filter(result -> !result.confirmed())
                    .findFirst()
                    .orElseThrow();
            TestSuiteStabilityAttemptCancellationCommand retry =
                    rejected.commandId().equals(first.commandId()) ? first : second;
            assertThat(journal.find("tenant-a", "test", retry.commandId()))
                    .get()
                    .extracting(TestSuiteStabilityAttemptCancellationJournal.Entry::status)
                    .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED);
            assertThat(journal.accept(retry.commandId(), attestation(retry, 12,
                    TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED)).status())
                    .isEqualTo(TestSuiteStabilityAttemptCancellationJournal.AcceptanceStatus
                            .CONFIRMED);
        } finally {
            start.countDown();
            shutdown(pool);
        }
    }

    @Test
    void concurrencyHarnessBoundsMissingParticipantsAndUnfinishedFutures() {
        CountDownLatch missingParticipant = new CountDownLatch(1);
        CompletableFuture<Object> unfinished = new CompletableFuture<>();

        assertThatThrownBy(() -> await(missingParticipant, 100L))
                .isInstanceOf(TimeoutException.class)
                .hasMessage("Concurrency barrier did not converge before its deadline");
        assertThatThrownBy(() -> get(unfinished, 100L))
                .isInstanceOf(TimeoutException.class);
    }

    private ConcurrentAcceptance acceptConcurrently(
            TestSuiteStabilityAttemptCancellationJournal target,
            TestSuiteStabilityAttemptCancellationCommand command,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        await(start, CONCURRENCY_TIMEOUT_MILLIS);
        try {
            target.accept(command.commandId(), attestation(command, 11,
                    TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED));
            return new ConcurrentAcceptance(command.commandId(), true, null);
        } catch (TestSuiteStabilityAttemptCancellationJournal.ConflictException conflict) {
            return new ConcurrentAcceptance(command.commandId(), false, conflict.reason());
        }
    }

    private static ExecutorService concurrencyPool() {
        return Executors.newFixedThreadPool(2, task -> Thread.ofPlatform()
                .daemon(true)
                .name("attempt-cancellation-journal-race-"
                        + CONCURRENCY_THREAD_SEQUENCE.incrementAndGet())
                .unstarted(task));
    }

    private static void await(CountDownLatch latch, long timeoutMillis) throws Exception {
        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException(
                    "Concurrency barrier did not converge before its deadline");
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        return get(future, CONCURRENCY_TIMEOUT_MILLIS);
    }

    private static <T> T get(Future<T> future, long timeoutMillis) throws Exception {
        return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private static void shutdown(ExecutorService pool) throws Exception {
        pool.shutdownNow();
        if (!pool.awaitTermination(CONCURRENCY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException(
                    "Concurrency executor did not terminate before its deadline");
        }
    }

    private TestSuiteStabilityAttemptCancellationCommand command(
            int attemptNumber, long leaseEpoch, char challenge) {
        Instant now = databaseTime();
        return command(attemptNumber, leaseEpoch, challenge,
                now.minusMillis(10), now.plusSeconds(30));
    }

    private TestSuiteStabilityAttemptCancellationCommand command(
            int attemptNumber,
            long leaseEpoch,
            char challenge,
            Instant requestedAt,
            Instant deadlineAt) {
        return TestSuiteStabilityAttemptCancellationCommand.create(
                mapper, "tenant-a", "test", "stability-job-" + "1".repeat(64),
                "stability-attempt-" + Integer.toHexString(attemptNumber).repeat(64),
                "worker-a", leaseEpoch, "sha256:" + "3".repeat(64),
                TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED,
                requestedAt, deadlineAt, challenge(challenge));
    }

    private TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation(
            TestSuiteStabilityAttemptCancellationCommand command,
            long providerSequence,
            TestSuiteStabilityAttemptCancellationReceipt.Outcome outcome) throws Exception {
        return attestation(command, providerSequence, outcome, keyPair);
    }

    private TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation(
            TestSuiteStabilityAttemptCancellationCommand command,
            long providerSequence,
            TestSuiteStabilityAttemptCancellationReceipt.Outcome outcome,
            KeyPair signer) throws Exception {
        TestSuiteStabilityAttemptCancellationReceipt.TerminationMode mode =
                outcome == TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED
                        ? TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL
                        : TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.NONE;
        Instant confirmedAt = databaseTime();
        if (confirmedAt.isBefore(command.requestedAt())) {
            confirmedAt = command.requestedAt();
        }
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                new TestSuiteStabilityAttemptCancellationReceipt(
                        TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), PROVIDER_ID,
                        DEPLOYMENT_ID, command.attemptId(), command.leaseEpoch(),
                        providerSequence,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS,
                        outcome, mode, "sha256:" + "4".repeat(64),
                        "sha256:" + "5".repeat(64), confirmedAt);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(TestSuiteStabilityAttemptCancellationVerifier.signingBytes(
                mapper,
                TestSuiteStabilityAttemptCancellationReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID));
        return new TestSuiteStabilityAttemptCancellationReceipt.Attestation(
                TestSuiteStabilityAttemptCancellationReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }

    private TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor(
            Duration latency) {
        return new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                TestSuiteStabilityAttemptCancellationAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                latency);
    }

    private TestSuiteStabilityAttemptCancellationAuthority authority(
            AtomicInteger descriptorCalls,
            AtomicInteger cancellationCalls,
            Cancellation cancellation) {
        return new TestSuiteStabilityAttemptCancellationAuthority() {
            @Override
            public Descriptor descriptor() {
                descriptorCalls.incrementAndGet();
                return descriptor;
            }

            @Override
            public TestSuiteStabilityAttemptCancellationReceipt.Attestation cancel(
                    TestSuiteStabilityAttemptCancellationCommand command) {
                cancellationCalls.incrementAndGet();
                return cancellation.cancel(command);
            }
        };
    }

    private static TestSuiteStabilityAttemptCancellationCallSupervisor supervisor(
            Duration cancellationTimeout) {
        return new TestSuiteStabilityAttemptCancellationCallSupervisor(
                new TestSuiteStabilityAttemptCancellationCallSupervisor.Policy(
                        Duration.ofSeconds(1), cancellationTimeout, 1));
    }

    private TestSuiteStabilityAttemptCancellationVerifier verifierFor(KeyPair pair) {
        Instant now = databaseTime();
        return new TestSuiteStabilityAttemptCancellationVerifier(
                mapper, Set.of(new TestSuiteStabilityAttemptCancellationVerifier.TrustKey(
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, pair.getPublic(),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))),
                Duration.ofSeconds(2));
    }

    private JdbcTemplate newDatabaseJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:attempt-cancel-clean-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", ""));
    }

    private Instant databaseTime() {
        Timestamp timestamp = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return java.util.Objects.requireNonNull(timestamp).toInstant()
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private static String challenge(char value) {
        byte[] challenge = new byte[32];
        java.util.Arrays.fill(challenge, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
    }

    @FunctionalInterface
    private interface Cancellation {
        TestSuiteStabilityAttemptCancellationReceipt.Attestation cancel(
                TestSuiteStabilityAttemptCancellationCommand command);
    }

    private record ConcurrentAcceptance(
            String commandId,
            boolean confirmed,
            TestSuiteStabilityAttemptCancellationJournal.ConflictReason conflict) {
    }
}
