package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityPhysicalAttemptObservationVerifierTest {

    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");
    private static final String PROVIDER_ID = "isolated-runtime-a";
    private static final String DEPLOYMENT_ID = "isolated-runtime-a.generation-7";
    private static final String KEY_ID = "isolated-runtime-a.key-3";

    private ObjectMapper mapper;
    private KeyPair keyPair;
    private TestSuiteStabilityPhysicalAttemptIdentity identity;
    private TestSuiteStabilityPhysicalAttemptStartCommand startCommand;
    private TestSuiteStabilityPhysicalAttemptObservationCommand command;
    private TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor;
    private TestSuiteStabilityPhysicalAttemptObservationVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TestSuiteStabilityJobLease lease = new TestSuiteStabilityJobLease(
                "stability-job-" + "1".repeat(64), "tenant-a", "test",
                fingerprint('2'), "worker-a", 7, NOW.plusSeconds(60));
        identity = TestSuiteStabilityPhysicalAttemptIdentity.create(
                mapper, lease, fingerprint('3'), PROVIDER_ID, DEPLOYMENT_ID,
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
        startCommand = TestSuiteStabilityPhysicalAttemptStartCommand.create(
                mapper, identity, envelopeRef('4'), fingerprint('4'), NOW,
                NOW.plusSeconds(30), challenge('a'));
        command = command("", 0);
        descriptor = new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(30), Duration.ofHours(1));
        verifier = verifier(keyPair, NOW.minusSeconds(60), NOW.plusSeconds(300));
    }

    @Test
    void verifiesExactRunningProcessIdentity() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7));

        TestSuiteStabilityPhysicalAttemptObservationReceipt verified = verifier.verify(
                command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(8));

        assertThat(verified).isEqualTo(receipt);
        assertThat(verified.processIdentityConfirmed()).isTrue();
        assertThat(verified.terminalConfirmed()).isFalse();
        assertThat(verified.reconciliationRequired()).isFalse();
    }

    @Test
    void verifiesTerminalEvidenceForEveryClosedDisposition() throws Exception {
        for (TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition
                : Set.of(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                        .SUCCEEDED,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition.FAILED,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                        .CANCELLED,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                        .TIMED_OUT,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                        .PROVIDER_ABORTED)) {
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = receipt(
                    command,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL,
                    disposition, 5, NOW.plusSeconds(4), NOW.plusSeconds(7));

            TestSuiteStabilityPhysicalAttemptObservationReceipt verified = verifier.verify(
                    command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(8));

            assertThat(verified.terminalConfirmed()).isTrue();
            assertThat(verified.evidenceFingerprint()).isEqualTo(fingerprint('7'));
        }
    }

    @Test
    void signedMissingAndIndeterminateFactsRemainNonConfirming() throws Exception {
        for (TestSuiteStabilityPhysicalAttemptObservationReceipt.State state : Set.of(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.INDETERMINATE)) {
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = receipt(
                    command, state, 0, NOW.plusSeconds(6), NOW.plusSeconds(7));

            TestSuiteStabilityPhysicalAttemptObservationReceipt verified = verifier.verify(
                    command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(8));

            assertThat(verified.processIdentityConfirmed()).isFalse();
            assertThat(verified.terminalConfirmed()).isFalse();
            assertThat(verified.reconciliationRequired()).isTrue();
        }
    }

    @Test
    void verifiesDurablyAcceptedStartWithoutInventingProcessIdentity() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = receipt(
                command,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.START_PENDING,
                2, NOW.plusSeconds(1), NOW.plusSeconds(7));

        TestSuiteStabilityPhysicalAttemptObservationReceipt verified = verifier.verify(
                command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(8));

        assertThat(verified.processIdentityConfirmed()).isFalse();
        assertThat(verified.runtimeStateFingerprint()).isEqualTo(fingerprint('6'));
        assertThat(verified.reconciliationRequired()).isTrue();
    }

    @Test
    void knownProcessFenceAcceptsSameProcessAndNonConfirmingLookup() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationCommand fenced =
                command(fingerprint('5'), 4);
        TestSuiteStabilityPhysicalAttemptObservationReceipt running = receipt(
                fenced, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7));
        assertThat(verifier.verify(fenced, descriptor, attest(running, keyPair),
                NOW.plusSeconds(8)).processIdentityConfirmed()).isTrue();

        TestSuiteStabilityPhysicalAttemptObservationReceipt missing = receipt(
                fenced, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED,
                0, NOW.plusSeconds(6), NOW.plusSeconds(7));
        assertThat(verifier.verify(fenced, descriptor, attest(missing, keyPair),
                NOW.plusSeconds(8)).reconciliationRequired()).isTrue();
    }

    @Test
    void rejectsDifferentProcessAndPendingDowngradeAfterProcessConfirmation() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationCommand fenced =
                command(fingerprint('8'), 4);
        TestSuiteStabilityPhysicalAttemptObservationReceipt different = receipt(
                fenced, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7));
        assertFailure(fenced, different,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason
                        .PROCESS_BINDING_INVALID);

        TestSuiteStabilityPhysicalAttemptObservationReceipt pending = receipt(
                fenced,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.START_PENDING,
                5, NOW.plusSeconds(1), NOW.plusSeconds(7));
        assertFailure(fenced, pending,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason
                        .STATE_ROLLBACK);
    }

    @Test
    void rejectsAttemptRevisionRollback() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationCommand fenced = command("", 5);
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = receipt(
                fenced, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7));

        assertFailure(fenced, receipt,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason
                        .STATE_ROLLBACK);
    }

    @Test
    void rejectsCrossAttemptAndCrossStartReplayEvenWhenSigned() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReceipt crossAttempt = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition.NONE,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7),
                "stability-attempt-" + "8".repeat(64), startCommand.commandId(),
                startCommand.commandFingerprint(), fingerprint('5'));
        assertFailure(command, crossAttempt,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason
                        .COMMAND_BINDING_INVALID);

        TestSuiteStabilityPhysicalAttemptObservationReceipt crossStart = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition.NONE,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7), identity.attemptId(),
                "stability-attempt-start-" + "8".repeat(64), fingerprint('8'),
                fingerprint('5'));
        assertFailure(command, crossStart,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason
                        .COMMAND_BINDING_INVALID);
    }

    @Test
    void rejectsTamperedNestedStartAndObservationCommands() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartCommand tamperedStart =
                new TestSuiteStabilityPhysicalAttemptStartCommand(
                        startCommand.schemaVersion(), startCommand.commandId(),
                        startCommand.commandFingerprint(), startCommand.identity(),
                        envelopeRef('9'), startCommand.executionEnvelopeFingerprint(),
                        startCommand.requestedAt(), startCommand.confirmationDeadlineAt(),
                        startCommand.challenge());
        TestSuiteStabilityPhysicalAttemptObservationCommand nestedTamper =
                new TestSuiteStabilityPhysicalAttemptObservationCommand(
                        command.schemaVersion(), command.commandId(),
                        command.commandFingerprint(), tamperedStart,
                        command.expectedProcessIdentityFingerprint(),
                        command.minimumAttemptRevision(), command.requestedAt(),
                        command.confirmationDeadlineAt(), command.challenge());
        TestSuiteStabilityPhysicalAttemptObservationReceipt valid = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7));
        assertThatThrownBy(() -> verifier.verify(
                nestedTamper, descriptor, attest(valid, keyPair), NOW.plusSeconds(8)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptObservationVerifier
                                        .FailureReason.START_COMMAND_INTEGRITY_INVALID));

        String fakeFingerprint = fingerprint('9');
        TestSuiteStabilityPhysicalAttemptObservationCommand commandTamper =
                new TestSuiteStabilityPhysicalAttemptObservationCommand(
                        command.schemaVersion(),
                        "stability-attempt-observe-"
                                + fakeFingerprint.substring("sha256:".length()),
                        fakeFingerprint, startCommand,
                        command.expectedProcessIdentityFingerprint(), 1,
                        command.requestedAt(), command.confirmationDeadlineAt(),
                        command.challenge());
        assertThatThrownBy(() -> verifier.verify(
                commandTamper, descriptor, attest(valid, keyPair), NOW.plusSeconds(8)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptObservationVerifier
                                        .FailureReason.OBSERVATION_COMMAND_INTEGRITY_INVALID));
    }

    @Test
    void rejectsProviderAvailabilityDeploymentAndIsolationDrift() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReceipt wrongIsolation = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition.NONE,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7), identity.attemptId(),
                startCommand.commandId(), startCommand.commandFingerprint(), fingerprint('5'),
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.CONTAINER);
        assertFailure(command, wrongIsolation,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason
                        .PROVIDER_BINDING_INVALID);

        TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor unavailable =
                new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                        descriptor.schemaVersion(), descriptor.providerId(),
                        descriptor.deploymentId(), descriptor.keyId(), false,
                        descriptor.isolationModes(), descriptor.maximumObservationLatency(),
                        descriptor.minimumStateRetention());
        TestSuiteStabilityPhysicalAttemptObservationReceipt valid = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7));
        assertThatThrownBy(() -> verifier.verify(
                command, unavailable, attest(valid, keyPair), NOW.plusSeconds(8)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptObservationVerifier
                                        .FailureReason.PROVIDER_BINDING_INVALID));
    }

    @Test
    void rejectsLateFutureSlowAndStaleNonConfirmingObservation() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReceipt late = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), command.confirmationDeadlineAt().plusMillis(1));
        assertFailure(command, late,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason.TIME_INVALID,
                NOW.plusSeconds(16));

        TestSuiteStabilityPhysicalAttemptObservationReceipt future = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(10));
        assertFailure(command, future,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason.TIME_INVALID,
                NOW.plusSeconds(7));

        TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor fast =
                new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                        descriptor.schemaVersion(), descriptor.providerId(),
                        descriptor.deploymentId(), descriptor.keyId(), true,
                        descriptor.isolationModes(), Duration.ofSeconds(1),
                        descriptor.minimumStateRetention());
        TestSuiteStabilityPhysicalAttemptObservationReceipt slow = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7));
        assertThatThrownBy(() -> verifier.verify(
                command, fast, attest(slow, keyPair), NOW.plusSeconds(8)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptObservationVerifier
                                        .FailureReason.TIME_INVALID));

        TestSuiteStabilityPhysicalAttemptObservationReceipt staleMissing = receipt(
                command,
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED,
                0, NOW.plusSeconds(4), NOW.plusSeconds(7));
        assertFailure(command, staleMissing,
                TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason.TIME_INVALID);
    }

    @Test
    void rejectsExpiredTrustAndInvalidSignatureWithoutDiagnostics() throws Exception {
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = receipt(
                command, TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                4, NOW.plusSeconds(2), NOW.plusSeconds(7));
        TestSuiteStabilityPhysicalAttemptObservationVerifier expired = verifier(
                keyPair, NOW.minusSeconds(60), NOW.plusSeconds(6));
        assertThatThrownBy(() -> expired.verify(
                command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(8)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptObservationVerifier
                                        .FailureReason.TRUST_INVALID));

        KeyPair untrusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(receipt, untrusted), NOW.plusSeconds(8)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationVerifier
                                .VerificationException.class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(
                                    TestSuiteStabilityPhysicalAttemptObservationVerifier
                                            .FailureReason.SIGNATURE_INVALID);
                            assertThat(failure.getCause()).isNull();
                        });
    }

    @Test
    void rejectsContradictoryStateAndUnboundedDescriptorShapes() {
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptObservationReceipt(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.SCHEMA_VERSION,
                command.commandId(), command.commandFingerprint(), PROVIDER_ID,
                DEPLOYMENT_ID, identity.attemptId(), identity.identityFingerprint(),
                startCommand.commandId(), startCommand.commandFingerprint(),
                identity.leaseEpoch(), 40, 0, identity.isolationMode(),
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL,
                "", "", TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.NONE, "", NOW.plusSeconds(4), NOW.plusSeconds(7)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                        TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor
                                .SCHEMA_VERSION,
                        PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                        Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                        Duration.ofSeconds(30), Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                TestSuiteStabilityPhysicalAttemptObservationCommand.create(
                        mapper, startCommand, "", 0, NOW.plusSeconds(5),
                        NOW.plusSeconds(15), "not-a-32-byte-challenge"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TestSuiteStabilityPhysicalAttemptObservationCommand command(
            String expectedProcess, long minimumRevision) {
        return TestSuiteStabilityPhysicalAttemptObservationCommand.create(
                mapper, startCommand, expectedProcess, minimumRevision,
                NOW.plusSeconds(5), NOW.plusSeconds(15), challenge('b'));
    }

    private TestSuiteStabilityPhysicalAttemptObservationVerifier verifier(
            KeyPair pair, Instant notBefore, Instant notAfter) {
        return new TestSuiteStabilityPhysicalAttemptObservationVerifier(
                mapper,
                Set.of(new TestSuiteStabilityPhysicalAttemptObservationVerifier.TrustKey(
                        PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, pair.getPublic(),
                        notBefore, notAfter)), Duration.ofSeconds(2));
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt receipt(
            TestSuiteStabilityPhysicalAttemptObservationCommand source,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state,
            long revision,
            Instant effectiveAt,
            Instant confirmedAt) {
        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition =
                state == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL
                        ? TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.SUCCEEDED
                        : TestSuiteStabilityPhysicalAttemptObservationReceipt
                        .TerminalDisposition.NONE;
        return receipt(source, state, disposition, revision, effectiveAt, confirmedAt,
                identity.attemptId(), startCommand.commandId(),
                startCommand.commandFingerprint(), fingerprint('5'));
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt receipt(
            TestSuiteStabilityPhysicalAttemptObservationCommand source,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition,
            long revision,
            Instant effectiveAt,
            Instant confirmedAt) {
        return receipt(source, state, disposition, revision, effectiveAt, confirmedAt,
                identity.attemptId(), startCommand.commandId(),
                startCommand.commandFingerprint(), fingerprint('5'));
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt receipt(
            TestSuiteStabilityPhysicalAttemptObservationCommand source,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition,
            long revision,
            Instant effectiveAt,
            Instant confirmedAt,
            String attemptId,
            String sourceStartCommandId,
            String sourceStartFingerprint,
            String processFingerprint) {
        return receipt(source, state, disposition, revision, effectiveAt, confirmedAt,
                attemptId, sourceStartCommandId, sourceStartFingerprint, processFingerprint,
                identity.isolationMode());
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt receipt(
            TestSuiteStabilityPhysicalAttemptObservationCommand source,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.State state,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition,
            long revision,
            Instant effectiveAt,
            Instant confirmedAt,
            String attemptId,
            String sourceStartCommandId,
            String sourceStartFingerprint,
            String processFingerprint,
            TestSuiteStabilityAttemptCancellationReceipt.IsolationMode isolationMode) {
        boolean positive = state
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED
                && state
                != TestSuiteStabilityPhysicalAttemptObservationReceipt.State.INDETERMINATE;
        boolean process = state
                == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING
                || state == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL;
        boolean terminal = state
                == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL;
        return new TestSuiteStabilityPhysicalAttemptObservationReceipt(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.SCHEMA_VERSION,
                source.commandId(), source.commandFingerprint(), PROVIDER_ID, DEPLOYMENT_ID,
                attemptId, identity.identityFingerprint(), sourceStartCommandId,
                sourceStartFingerprint, identity.leaseEpoch(), 40, revision, isolationMode,
                state, process ? processFingerprint : "", positive ? fingerprint('6') : "",
                disposition, terminal ? fingerprint('7') : "", effectiveAt, confirmedAt);
    }

    private void assertFailure(
            TestSuiteStabilityPhysicalAttemptObservationCommand source,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason reason)
            throws Exception {
        assertFailure(source, receipt, reason, NOW.plusSeconds(8));
    }

    private void assertFailure(
            TestSuiteStabilityPhysicalAttemptObservationCommand source,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            TestSuiteStabilityPhysicalAttemptObservationVerifier.FailureReason reason,
            Instant observedAt) throws Exception {
        assertThatThrownBy(() -> verifier.verify(
                source, descriptor, attest(receipt, keyPair), observedAt))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attest(
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            KeyPair signer) throws Exception {
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

    private static String envelopeRef(char value) {
        return "stability-envelope-" + String.valueOf(value).repeat(64);
    }

    private static String challenge(char value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
