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

class TestSuiteStabilityAttemptCancellationVerifierTest {

    private static final Instant NOW = Instant.parse("2026-07-22T02:00:00Z");
    private static final String PROVIDER_ID = "attempt-runtime-a";
    private static final String DEPLOYMENT_ID = "attempt-runtime-a.generation-7";
    private static final String KEY_ID = "attempt-runtime-a.key-3";
    private static final String JOB_ID = "stability-job-" + "1".repeat(64);
    private static final String ATTEMPT_ID = "stability-attempt-" + "2".repeat(64);
    private static final String RUNTIME_FINGERPRINT = fingerprint('3');

    private ObjectMapper mapper;
    private KeyPair keyPair;
    private TestSuiteStabilityAttemptCancellationCommand command;
    private TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor;
    private TestSuiteStabilityAttemptCancellationVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        command = TestSuiteStabilityAttemptCancellationCommand.create(
                mapper, "tenant-a", "test", JOB_ID, ATTEMPT_ID, "worker-a", 7,
                RUNTIME_FINGERPRINT,
                TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED,
                NOW, NOW.plusSeconds(30), challenge('a'));
        descriptor = new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                TestSuiteStabilityAttemptCancellationAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(30));
        verifier = verifier(keyPair, NOW.minusSeconds(60), NOW.plusSeconds(300));
    }

    @Test
    void verifiesExactChallengeBoundProcessTermination() throws Exception {
        TestSuiteStabilityAttemptCancellationReceipt receipt = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusSeconds(2));

        TestSuiteStabilityAttemptCancellationReceipt verified =
                verifier.verify(command, descriptor, attest(receipt, keyPair),
                        NOW.plusSeconds(3));

        assertThat(verified).isEqualTo(receipt);
        assertThat(verified.terminationConfirmed()).isTrue();
    }

    @Test
    void signedNotFoundRemainsNonConfirming() throws Exception {
        TestSuiteStabilityAttemptCancellationReceipt receipt = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.NOT_FOUND,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.NONE,
                NOW.plusSeconds(2));

        TestSuiteStabilityAttemptCancellationReceipt verified =
                verifier.verify(command, descriptor, attest(receipt, keyPair),
                        NOW.plusSeconds(3));

        assertThat(verified.terminationConfirmed()).isFalse();
    }

    @Test
    void rejectsCrossAttemptReplayEvenWhenProviderSignsIt() throws Exception {
        TestSuiteStabilityAttemptCancellationReceipt receipt = new TestSuiteStabilityAttemptCancellationReceipt(
                TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                command.commandId(), command.commandFingerprint(), PROVIDER_ID, DEPLOYMENT_ID,
                "stability-attempt-" + "4".repeat(64), command.leaseEpoch(), 11,
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS,
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                fingerprint('5'), fingerprint('6'), NOW.plusSeconds(2));

        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .COMMAND_BINDING_INVALID));
    }

    @Test
    void rejectsTamperedCommandBeforeTrustingMatchingReceipt() throws Exception {
        String tamperedFingerprint = fingerprint('9');
        TestSuiteStabilityAttemptCancellationCommand tampered =
                new TestSuiteStabilityAttemptCancellationCommand(
                        command.schemaVersion(),
                        "stability-attempt-cancel-"
                                + tamperedFingerprint.substring("sha256:".length()),
                        tamperedFingerprint, command.tenantId(), command.environmentId(),
                        command.jobId(), command.attemptId(), command.ownerId(),
                        command.leaseEpoch(), command.runtimeBindingFingerprint(), command.reason(),
                        command.requestedAt(), command.confirmationDeadlineAt(), command.challenge());
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                new TestSuiteStabilityAttemptCancellationReceipt(
                        TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                        tampered.commandId(), tampered.commandFingerprint(), PROVIDER_ID,
                        DEPLOYMENT_ID, tampered.attemptId(), tampered.leaseEpoch(), 11,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS,
                        TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                        TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                        fingerprint('5'), fingerprint('6'), NOW.plusSeconds(2));

        assertThatThrownBy(() -> verifier.verify(
                tampered, descriptor, attest(receipt, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .COMMAND_INTEGRITY_INVALID));
    }

    @Test
    void rejectsProviderDeploymentAndIsolationDrift() throws Exception {
        TestSuiteStabilityAttemptCancellationReceipt receipt = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.CONTAINER_TERMINATION,
                NOW.plusSeconds(2),
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.CONTAINER);

        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .PROVIDER_BINDING_INVALID));
    }

    @Test
    void rejectsUnavailableProviderBeforeTrustingItsSignedReceipt() throws Exception {
        TestSuiteStabilityAttemptCancellationReceipt receipt = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusSeconds(2));
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor unavailable =
                new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                        descriptor.schemaVersion(), descriptor.providerId(),
                        descriptor.deploymentId(), descriptor.keyId(), false,
                        descriptor.isolationModes(), descriptor.maximumConfirmationLatency());

        assertThatThrownBy(() -> verifier.verify(
                command, unavailable, attest(receipt, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .PROVIDER_BINDING_INVALID));
    }

    @Test
    void rejectsLateOrFutureConfirmation() throws Exception {
        TestSuiteStabilityAttemptCancellationReceipt late = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                command.confirmationDeadlineAt().plusMillis(1));
        TestSuiteStabilityAttemptCancellationReceipt timely = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusMillis(500));

        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(late, keyPair), NOW.plusSeconds(31)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .TIME_INVALID));
        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(timely, keyPair), NOW.minusMillis(1)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .TIME_INVALID));
    }

    @Test
    void rejectsProviderLatencyClockSkewAndExpiredTrust() throws Exception {
        TestSuiteStabilityAttemptCancellationReceipt tooSlow = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusSeconds(3));
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor twoSecondProvider =
                new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                        descriptor.schemaVersion(), descriptor.providerId(),
                        descriptor.deploymentId(), descriptor.keyId(), true,
                        descriptor.isolationModes(), Duration.ofSeconds(2));
        assertThatThrownBy(() -> verifier.verify(
                command, twoSecondProvider, attest(tooSlow, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .TIME_INVALID));

        TestSuiteStabilityAttemptCancellationReceipt future = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusSeconds(4));
        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(future, keyPair), NOW.plusSeconds(1)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .TIME_INVALID));

        TestSuiteStabilityAttemptCancellationVerifier expiredVerifier = verifier(
                keyPair, NOW.minusSeconds(60), NOW.plusSeconds(1));
        TestSuiteStabilityAttemptCancellationReceipt afterKeyExpiry = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusSeconds(2));
        assertThatThrownBy(() -> expiredVerifier.verify(
                command, descriptor, attest(afterKeyExpiry, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                        .TRUST_INVALID));
    }

    @Test
    void rejectsInvalidDetachedSignatureWithoutProviderDiagnostics() throws Exception {
        KeyPair untrustedSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TestSuiteStabilityAttemptCancellationReceipt receipt = receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusSeconds(2));

        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(receipt, untrustedSigner), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationVerifier.VerificationException.class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(
                                    TestSuiteStabilityAttemptCancellationVerifier.FailureReason
                                            .SIGNATURE_INVALID);
                            assertThat(failure.getCause()).isNull();
                        });
    }

    @Test
    void rejectsInvalidOutcomeAndChallengeShapes() {
        assertThatThrownBy(() -> receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.NOT_FOUND,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSuiteStabilityAttemptCancellationCommand.create(
                mapper, "tenant-a", "test", JOB_ID, ATTEMPT_ID, "worker-a", 7,
                RUNTIME_FINGERPRINT,
                TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED,
                NOW, NOW.plusSeconds(30), "not-a-32-byte-challenge"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTerminationMechanismThatCannotProveDeclaredIsolation() {
        assertThatThrownBy(() -> receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.VM_TERMINATION,
                NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(
                TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                NOW.plusSeconds(2),
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.CONTAINER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TestSuiteStabilityAttemptCancellationVerifier verifier(
            KeyPair pair, Instant notBefore, Instant notAfter) {
        return new TestSuiteStabilityAttemptCancellationVerifier(
                mapper, Set.of(new TestSuiteStabilityAttemptCancellationVerifier.TrustKey(
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, pair.getPublic(), notBefore, notAfter)),
                Duration.ofSeconds(2));
    }

    private TestSuiteStabilityAttemptCancellationReceipt receipt(
            TestSuiteStabilityAttemptCancellationReceipt.Outcome outcome,
            TestSuiteStabilityAttemptCancellationReceipt.TerminationMode terminationMode,
            Instant confirmedAt) {
        return receipt(outcome, terminationMode, confirmedAt,
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
    }

    private TestSuiteStabilityAttemptCancellationReceipt receipt(
            TestSuiteStabilityAttemptCancellationReceipt.Outcome outcome,
            TestSuiteStabilityAttemptCancellationReceipt.TerminationMode terminationMode,
            Instant confirmedAt,
            TestSuiteStabilityAttemptCancellationReceipt.IsolationMode isolationMode) {
        return new TestSuiteStabilityAttemptCancellationReceipt(
                TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                command.commandId(), command.commandFingerprint(), PROVIDER_ID, DEPLOYMENT_ID,
                command.attemptId(), command.leaseEpoch(), 11, isolationMode, outcome,
                terminationMode, fingerprint('5'), fingerprint('6'), confirmedAt);
    }

    private TestSuiteStabilityAttemptCancellationReceipt.Attestation attest(
            TestSuiteStabilityAttemptCancellationReceipt receipt,
            KeyPair signer) throws Exception {
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

    private static String challenge(char value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
