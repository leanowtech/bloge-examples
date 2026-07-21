package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobParentAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobPrincipal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmission;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptIdentity;
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

class DatabaseTestSuiteStabilityPhysicalAttemptStartJournalTest {

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
    private TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor;
    private TestSuiteStabilityPhysicalAttemptStartVerifier verifier;
    private DatabaseTestSuiteStabilityPhysicalAttemptStartJournal journal;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:physical-attempt-start-" + System.nanoTime()
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
        descriptor = descriptor(Duration.ofSeconds(5));
        verifier = verifierFor(keyPair);
        journal = new DatabaseTestSuiteStabilityPhysicalAttemptStartJournal(
                jdbc, mapper, jobs, verifier, transactions);
        journal.init();
    }

    @Test
    void preparesAuthorizesAndAtomicallyAcceptsConfirmedStart() throws Exception {
        ReservedContext context = reserved('a', '1');
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(context.identity(), 'a');

        var prepared = journal.prepare(command, descriptor);
        journal.authorizeInvocation(command.commandId());
        var accepted = journal.accept(command.commandId(), attestation(
                command, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED));

        assertThat(prepared.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.PreparationStatus.PREPARED);
        assertThat(accepted.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.CONFIRMED);
        assertThat(accepted.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.CONFIRMED);
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .contains(accepted.entry());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_start_provider_sequences
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void exactPrepareAndTerminalAcceptanceReplayWithoutAdvancingSequence()
            throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(reserved('a', '1').identity(), 'a');

        assertThat(journal.prepare(command, descriptor).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.PreparationStatus.PREPARED);
        assertThat(journal.prepare(command, descriptor).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.PreparationStatus.REPLAYED);
        var signed = attestation(
                command, 11,
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.ALREADY_STARTED);
        assertThat(journal.accept(command.commandId(), signed).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.CONFIRMED);
        assertThat(journal.accept(command.commandId(), signed).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.REPLAYED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_start_provider_sequences
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void leaseLossKeepsPreparationReadableBlocksInvocationAndStillAcceptsStartFact()
            throws Exception {
        ReservedContext context = reserved('a', '1');
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(context.identity(), 'a');
        journal.prepare(command, descriptor);
        jobs.retry(context.claim().lease(), "RG.TEST.RUNTIME_UNAVAILABLE", POLICY);

        assertThat(journal.prepare(command, descriptor).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.PreparationStatus.REPLAYED);
        assertConflict(() -> journal.authorizeInvocation(command.commandId()),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .RESERVATION_NOT_ACTIVE);

        var accepted = journal.accept(command.commandId(), attestation(
                command, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED));
        assertThat(accepted.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.CONFIRMED);
    }

    @Test
    void sameAttemptEpochCannotBeReboundToAnotherEnvelope() {
        TestSuiteStabilityPhysicalAttemptIdentity identity = reserved('a', '1').identity();
        TestSuiteStabilityPhysicalAttemptStartCommand first = command(identity, 'a');
        TestSuiteStabilityPhysicalAttemptStartCommand changed = command(identity, 'b');
        journal.prepare(first, descriptor);

        assertConflict(() -> journal.prepare(changed, descriptor),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .ATTEMPT_COMMAND_CONFLICT);
        assertThat(journal.find("tenant-a", "test", changed.commandId())).isEmpty();
    }

    @Test
    void canonicalCommandMutationCannotReuseDerivedIdentity() {
        TestSuiteStabilityPhysicalAttemptStartCommand original =
                command(reserved('a', '1').identity(), 'a');
        TestSuiteStabilityPhysicalAttemptStartCommand mutated =
                new TestSuiteStabilityPhysicalAttemptStartCommand(
                        original.schemaVersion(), original.commandId(),
                        original.commandFingerprint(), original.identity(),
                        original.executionEnvelopeRef(), fingerprint('f'),
                        original.requestedAt(), original.confirmationDeadlineAt(),
                        original.challenge());

        assertConflict(() -> journal.prepare(mutated, descriptor),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .IDEMPOTENCY_CONFLICT);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_start_entries
                """, Integer.class)).isZero();
    }

    @Test
    void terminalAttestationCannotBeRewritten() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(reserved('a', '1').identity(), 'a');
        journal.prepare(command, descriptor);
        journal.accept(command.commandId(), attestation(
                command, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED));

        assertConflict(() -> journal.accept(command.commandId(), attestation(
                        command, 12,
                        TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED)),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .IDEMPOTENCY_CONFLICT);
    }

    @Test
    void providerSequenceMustAdvanceAcrossDifferentAttempts() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand first =
                command(reserved('a', '1').identity(), 'a');
        TestSuiteStabilityPhysicalAttemptStartCommand second =
                command(reserved('b', '2').identity(), 'b');
        journal.prepare(first, descriptor);
        journal.prepare(second, descriptor);
        journal.accept(first.commandId(), attestation(
                first, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED));

        assertConflict(() -> journal.accept(second.commandId(), attestation(
                        second, 10,
                        TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED)),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .PROVIDER_SEQUENCE_ROLLBACK);
        assertThat(journal.find("tenant-a", "test", second.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptStartJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
        assertThat(journal.accept(second.commandId(), attestation(
                second, 12,
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED)).status())
                .isEqualTo(
                        TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.CONFIRMED);
    }

    @Test
    void invalidSignatureRollsBackAndLeavesPreparationRecoverable() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(reserved('a', '1').identity(), 'a');
        journal.prepare(command, descriptor);
        KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        assertThatThrownBy(() -> journal.accept(command.commandId(), attestation(
                command, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                wrong)))
                .isInstanceOf(TestSuiteStabilityPhysicalAttemptStartVerifier
                        .VerificationException.class);
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptStartJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_start_provider_floors
                """, Integer.class)).isZero();
    }

    @Test
    void signedRejectionIsDurablyUnconfirmedNotNonStartProof() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(reserved('a', '1').identity(), 'a');
        journal.prepare(command, descriptor);

        var accepted = journal.accept(command.commandId(), attestation(
                command, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.REJECTED));

        assertThat(accepted.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.UNCONFIRMED);
        assertThat(accepted.entry().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.Status.UNCONFIRMED);
        assertThat(accepted.entry().attestation()).get()
                .extracting(value -> value.receipt().startConfirmed()).isEqualTo(false);
    }

    @Test
    void timelyProviderConfirmationRemainsAcceptableAfterLocalDeadline() throws Exception {
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptStartCommand command = command(
                reserved('a', '1').identity(), 'a', now, now.plusMillis(200));
        var fastDescriptor = descriptor(Duration.ofMillis(100));
        journal.prepare(command, fastDescriptor);
        var timely = attestation(
                command, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                keyPair, now.plusMillis(100));
        Thread.sleep(250L);

        assertThat(journal.accept(command.commandId(), timely).status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.CONFIRMED);
    }

    @Test
    void providerConfirmationCannotPredateDurablePreparation() throws Exception {
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptStartCommand command = command(
                reserved('a', '1').identity(), 'a', now.minusSeconds(1),
                now.plusSeconds(5));
        var signedBeforePrepare = attestation(
                command, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                keyPair, now.minusMillis(500));
        journal.prepare(command, descriptor);

        assertConflict(() -> journal.accept(command.commandId(), signedBeforePrepare),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .START_PRECEDES_PREPARATION);
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptStartJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
    }

    @Test
    void localUnknownAfterAuthorizationLeavesDurablePreparation() {
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(reserved('a', '1').identity(), 'a');
        journal.prepare(command, descriptor);
        journal.authorizeInvocation(command.commandId());

        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptStartJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_start_provider_sequences
                """, Integer.class)).isZero();
    }

    @Test
    void missingReservationCannotPrepareAStartCommand() {
        TestSuiteStabilityJobClaim claim = claimed('a');
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity(claim.lease(), '1');

        assertConflict(() -> journal.prepare(command(identity, 'a'), descriptor),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .RESERVATION_NOT_ACTIVE);
    }

    @Test
    void scopeLookupDoesNotRevealAnotherTenantOrEnvironment() {
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(reserved('a', '1').identity(), 'a');
        journal.prepare(command, descriptor);

        assertThat(journal.find("tenant-b", "test", command.commandId())).isEmpty();
        assertThat(journal.find("tenant-a", "staging", command.commandId())).isEmpty();
        assertThat(journal.find("tenant-a", "test", command.commandId())).isPresent();
    }

    @Test
    void expiredCommandAndIncompatibleProviderFailBeforePersistence() {
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptIdentity first = reserved('a', '1').identity();
        TestSuiteStabilityPhysicalAttemptStartCommand expired = command(
                first, 'a', now.minusSeconds(2), now.minusSeconds(1));
        TestSuiteStabilityPhysicalAttemptIdentity second = reserved('b', '2').identity();
        TestSuiteStabilityPhysicalAttemptStartCommand live = command(second, 'b');
        var wrongDeployment = new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, "isolated-runtime-a.generation-8", KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(5));

        assertConflict(() -> journal.prepare(expired, descriptor),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason.COMMAND_EXPIRED);
        assertConflict(() -> journal.prepare(live, wrongDeployment),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .PROVIDER_INCOMPATIBLE);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_start_entries
                """, Integer.class)).isZero();
    }

    @Test
    void invocationAuthorizationRejectsInsufficientRemainingProviderWindow()
            throws Exception {
        Instant now = databaseTime();
        TestSuiteStabilityPhysicalAttemptStartCommand command = command(
                reserved('a', '1').identity(), 'a', now, now.plusSeconds(1));
        var slowDescriptor = descriptor(Duration.ofSeconds(1));
        journal.prepare(command, slowDescriptor);
        Thread.sleep(10L);

        assertConflict(() -> journal.authorizeInvocation(command.commandId()),
                TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .PROVIDER_INCOMPATIBLE);
        assertThat(journal.find("tenant-a", "test", command.commandId()))
                .get().extracting(TestSuiteStabilityPhysicalAttemptStartJournal.Entry::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED);
    }

    @Test
    void reservationAndJournalEntryTamperingFailClosed() {
        TestSuiteStabilityPhysicalAttemptStartCommand reservationCommand =
                command(reserved('a', '1').identity(), 'a');
        jdbc.update("""
                UPDATE rg_test_stability_physical_attempts
                SET deployment_id = 'isolated-runtime-a.generation-tampered'
                WHERE attempt_id = ?
                """, reservationCommand.identity().attemptId());
        assertThatThrownBy(() -> journal.prepare(reservationCommand, descriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Physical-attempt start reservation integrity failed");

        TestSuiteStabilityPhysicalAttemptStartCommand journalCommand =
                command(reserved('b', '2').identity(), 'b');
        journal.prepare(journalCommand, descriptor);
        jdbc.update("""
                UPDATE rg_test_stability_attempt_start_entries
                SET status = 'CONFIRMED'
                WHERE command_id = ?
                """, journalCommand.commandId());
        assertThatThrownBy(() -> journal.find(
                "tenant-a", "test", journalCommand.commandId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Physical-attempt start journal entry integrity failed");
    }

    @Test
    void providerFloorTamperingFailsClosedBeforeNextAcceptance() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand first =
                command(reserved('a', '1').identity(), 'a');
        TestSuiteStabilityPhysicalAttemptStartCommand second =
                command(reserved('b', '2').identity(), 'b');
        journal.prepare(first, descriptor);
        journal.accept(first.commandId(), attestation(
                first, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED));
        jdbc.update("""
                UPDATE rg_test_stability_attempt_start_provider_floors
                SET provider_sequence = 1
                """);
        journal.prepare(second, descriptor);

        assertThatThrownBy(() -> journal.accept(second.commandId(), attestation(
                second, 12, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Physical-attempt start provider floor integrity failed");
    }

    @Test
    void terminalReadRequiresItsImmutableProviderSequence() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(reserved('a', '1').identity(), 'a');
        journal.prepare(command, descriptor);
        journal.accept(command.commandId(), attestation(
                command, 11, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED));
        jdbc.update("""
                DELETE FROM rg_test_stability_attempt_start_provider_sequences
                WHERE command_id = ?
                """, command.commandId());

        assertThatThrownBy(() -> journal.find("tenant-a", "test", command.commandId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Physical-attempt start provider sequence continuity failed");
    }

    @Test
    void concurrentExactPreparationHasOneCreatorAndOneReplay() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(reserved('a', '1').identity(), 'a');

        List<TestSuiteStabilityPhysicalAttemptStartJournal.PreparationStatus> results =
                concurrently(
                        () -> journal.prepare(command, descriptor).status(),
                        () -> journal.prepare(command, descriptor).status());

        assertThat(results).containsExactlyInAnyOrder(
                TestSuiteStabilityPhysicalAttemptStartJournal.PreparationStatus.PREPARED,
                TestSuiteStabilityPhysicalAttemptStartJournal.PreparationStatus.REPLAYED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_stability_attempt_start_entries
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentPreparationAndQueueRetryLinearizeWithoutStaleAuthorization()
            throws Exception {
        ReservedContext context = reserved('a', '1');
        TestSuiteStabilityPhysicalAttemptStartCommand command =
                command(context.identity(), 'a');

        List<String> results = concurrently(
                () -> {
                    try {
                        journal.prepare(command, descriptor);
                        return "PREPARED";
                    } catch (TestSuiteStabilityPhysicalAttemptStartJournal
                            .ConflictException conflict) {
                        return "CONFLICT:" + conflict.reason();
                    }
                },
                () -> {
                    jobs.retry(context.claim().lease(),
                            "RG.TEST.RUNTIME_UNAVAILABLE", POLICY);
                    return "RETRIED";
                });

        assertThat(results).contains("RETRIED");
        assertThat(results).anyMatch(result -> Set.of(
                "PREPARED", "CONFLICT:RESERVATION_NOT_ACTIVE").contains(result));
        if (journal.find("tenant-a", "test", command.commandId()).isPresent()) {
            assertConflict(() -> journal.authorizeInvocation(command.commandId()),
                    TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                            .RESERVATION_NOT_ACTIVE);
        }
    }

    @Test
    void concurrentReplicaAcceptanceSerializesProviderSequenceFloor() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand first =
                command(reserved('a', '1').identity(), 'a');
        TestSuiteStabilityPhysicalAttemptStartCommand second =
                command(reserved('b', '2').identity(), 'b');
        journal.prepare(first, descriptor);
        journal.prepare(second, descriptor);
        DatabaseTestSuiteStabilityPhysicalAttemptStartJournal replica =
                new DatabaseTestSuiteStabilityPhysicalAttemptStartJournal(
                        new JdbcTemplate(jdbc.getDataSource()), mapper, jobs, verifier,
                        transactions);
        replica.init();
        List<ConcurrentAcceptance> results = concurrently(
                () -> acceptConcurrently(journal, first),
                () -> acceptConcurrently(replica, second));

        assertThat(results).filteredOn(ConcurrentAcceptance::confirmed).hasSize(1);
        assertThat(results).filteredOn(result -> result.conflict()
                        == TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason
                        .PROVIDER_SEQUENCE_ROLLBACK)
                .hasSize(1);
        ConcurrentAcceptance rejected = results.stream()
                .filter(result -> !result.confirmed()).findFirst().orElseThrow();
        TestSuiteStabilityPhysicalAttemptStartCommand retry =
                rejected.commandId().equals(first.commandId()) ? first : second;
        assertThat(journal.accept(retry.commandId(), attestation(
                retry, 12,
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED)).status())
                .isEqualTo(
                        TestSuiteStabilityPhysicalAttemptStartJournal.AcceptanceStatus.CONFIRMED);
    }

    private ConcurrentAcceptance acceptConcurrently(
            TestSuiteStabilityPhysicalAttemptStartJournal target,
            TestSuiteStabilityPhysicalAttemptStartCommand command) throws Exception {
        try {
            target.accept(command.commandId(), attestation(
                    command, 11,
                    TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED));
            return new ConcurrentAcceptance(command.commandId(), true, null);
        } catch (TestSuiteStabilityPhysicalAttemptStartJournal.ConflictException conflict) {
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
                        .name("physical-attempt-start-journal-race-"
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
                                "Start journal concurrency barrier timed out");
                    }
                    return operation.call();
                }));
            }
            if (!ready.await(CONCURRENCY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "Start journal concurrency participants did not arrive");
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

    private ReservedContext reserved(char jobId, char runtime) {
        TestSuiteStabilityJobClaim claim = claimed(jobId);
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity(claim.lease(), runtime);
        attempts.reserve(identity);
        return new ReservedContext(identity, claim);
    }

    private TestSuiteStabilityJobClaim claimed(char id) {
        jobs.submit(submission(id), POLICY);
        TestSuiteStabilityJobClaim claim = jobs.claimNext("test", "worker-a", POLICY);
        assertThat(claim.outcome()).isEqualTo(TestSuiteStabilityJobClaim.Outcome.ACQUIRED);
        return claim;
    }

    private TestSuiteStabilityPhysicalAttemptIdentity identity(
            TestSuiteStabilityJobLease lease, char runtime) {
        return TestSuiteStabilityPhysicalAttemptIdentity.create(
                mapper, lease, fingerprint(runtime), PROVIDER_ID, DEPLOYMENT_ID,
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
    }

    private TestSuiteStabilityPhysicalAttemptStartCommand command(
            TestSuiteStabilityPhysicalAttemptIdentity identity, char envelope) {
        Instant now = databaseTime();
        return command(identity, envelope, now.minusMillis(10), now.plusSeconds(30));
    }

    private TestSuiteStabilityPhysicalAttemptStartCommand command(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            char envelope,
            Instant requestedAt,
            Instant deadlineAt) {
        return TestSuiteStabilityPhysicalAttemptStartCommand.create(
                mapper, identity,
                "stability-envelope-" + String.valueOf(envelope).repeat(64),
                fingerprint(envelope), requestedAt, deadlineAt, challenge(envelope));
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            long providerSequence,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome outcome) throws Exception {
        return attestation(command, providerSequence, outcome, keyPair);
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            long providerSequence,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome outcome,
            KeyPair signer) throws Exception {
        Instant confirmedAt = databaseTime();
        if (confirmedAt.isBefore(command.requestedAt())) {
            confirmedAt = command.requestedAt();
        }
        return attestation(command, providerSequence, outcome, signer, confirmedAt);
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            long providerSequence,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome outcome,
            KeyPair signer,
            Instant confirmedAt) throws Exception {
        boolean confirmed = outcome != TestSuiteStabilityPhysicalAttemptStartReceipt
                .Outcome.REJECTED;
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt =
                new TestSuiteStabilityPhysicalAttemptStartReceipt(
                        TestSuiteStabilityPhysicalAttemptStartReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), PROVIDER_ID,
                        DEPLOYMENT_ID, command.identity().attemptId(),
                        command.identity().identityFingerprint(),
                        command.identity().leaseEpoch(), providerSequence,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS,
                        outcome, confirmed ? fingerprint('4') : "",
                        confirmed ? fingerprint('5') : "", confirmedAt);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(TestSuiteStabilityPhysicalAttemptStartVerifier.signingBytes(
                mapper,
                TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID));
        return new TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation.SCHEMA_VERSION,
                receipt, KEY_ID,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
    }

    private TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor(
            Duration latency) {
        return new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                latency);
    }

    private TestSuiteStabilityPhysicalAttemptStartVerifier verifierFor(KeyPair pair) {
        Instant now = databaseTime();
        return new TestSuiteStabilityPhysicalAttemptStartVerifier(
                mapper, Set.of(new TestSuiteStabilityPhysicalAttemptStartVerifier.TrustKey(
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, pair.getPublic(),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))),
                Duration.ofSeconds(2));
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
            TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason reason) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartJournal.ConflictException.class,
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

    private record ReservedContext(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityJobClaim claim) {
    }

    private record ConcurrentAcceptance(
            String commandId,
            boolean confirmed,
            TestSuiteStabilityPhysicalAttemptStartJournal.ConflictReason conflict) {
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
