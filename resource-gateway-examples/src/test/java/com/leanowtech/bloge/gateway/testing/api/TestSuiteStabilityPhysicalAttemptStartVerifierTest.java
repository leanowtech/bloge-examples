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

class TestSuiteStabilityPhysicalAttemptStartVerifierTest {

    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");
    private static final String PROVIDER_ID = "isolated-runtime-a";
    private static final String DEPLOYMENT_ID = "isolated-runtime-a.generation-7";
    private static final String KEY_ID = "isolated-runtime-a.key-3";

    private ObjectMapper mapper;
    private KeyPair keyPair;
    private TestSuiteStabilityPhysicalAttemptIdentity identity;
    private TestSuiteStabilityPhysicalAttemptStartCommand command;
    private TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor;
    private TestSuiteStabilityPhysicalAttemptStartVerifier verifier;

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
        command = command(identity);
        descriptor = new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor.SCHEMA_VERSION,
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(30));
        verifier = verifier(keyPair, NOW.minusSeconds(60), NOW.plusSeconds(300));
    }

    @Test
    void verifiesExactChallengeBoundProcessStart() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt = receipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                NOW.plusSeconds(2));

        TestSuiteStabilityPhysicalAttemptStartReceipt verified = verifier.verify(
                command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(3));

        assertThat(verified).isEqualTo(receipt);
        assertThat(verified.startConfirmed()).isTrue();
    }

    @Test
    void signedRejectionRemainsNonConfirming() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt = receipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.REJECTED,
                NOW.plusSeconds(2));

        TestSuiteStabilityPhysicalAttemptStartReceipt verified = verifier.verify(
                command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(3));

        assertThat(verified.startConfirmed()).isFalse();
        assertThat(verified.processIdentityFingerprint()).isEmpty();
        assertThat(verified.runtimeStateFingerprint()).isEmpty();
    }

    @Test
    void verifiesExactIdempotentAlreadyStartedProof() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt = receipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.ALREADY_STARTED,
                NOW.plusSeconds(2));

        assertThat(verifier.verify(command, descriptor, attest(receipt, keyPair),
                NOW.plusSeconds(3)).startConfirmed()).isTrue();
    }

    @Test
    void rejectsCrossAttemptReplayEvenWhenProviderSignsIt() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt = receipt(
                command, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                NOW.plusSeconds(2), "stability-attempt-" + "8".repeat(64),
                identity.identityFingerprint(), identity.isolationMode());

        assertFailure(receipt, TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason
                .COMMAND_BINDING_INVALID);
    }

    @Test
    void rejectsMutatedNestedIdentityBeforeTrustingReceipt() throws Exception {
        TestSuiteStabilityPhysicalAttemptIdentity mutatedIdentity =
                new TestSuiteStabilityPhysicalAttemptIdentity(
                        identity.schemaVersion(), identity.attemptId(),
                        identity.identityFingerprint(), identity.tenantId(),
                        identity.environmentId(), identity.jobId(),
                        identity.requestFingerprint(), identity.ownerId(),
                        identity.leaseEpoch(), identity.runtimeBindingFingerprint(),
                        "isolated-runtime-b", identity.deploymentId(),
                        identity.isolationMode());
        TestSuiteStabilityPhysicalAttemptStartCommand mutated =
                new TestSuiteStabilityPhysicalAttemptStartCommand(
                        command.schemaVersion(), command.commandId(),
                        command.commandFingerprint(), mutatedIdentity,
                        command.executionEnvelopeRef(),
                        command.executionEnvelopeFingerprint(), command.requestedAt(),
                        command.confirmationDeadlineAt(), command.challenge());

        assertThatThrownBy(() -> verifier.verify(
                mutated, descriptor, attest(receipt(command,
                        TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                        NOW.plusSeconds(2), identity.attemptId(),
                        identity.identityFingerprint(), identity.isolationMode()), keyPair),
                NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason
                                        .IDENTITY_INTEGRITY_INVALID));
    }

    @Test
    void rejectsTamperedCommandBeforeTrustingMatchingReceipt() throws Exception {
        String fakeFingerprint = fingerprint('9');
        TestSuiteStabilityPhysicalAttemptStartCommand tampered =
                new TestSuiteStabilityPhysicalAttemptStartCommand(
                        command.schemaVersion(),
                        "stability-attempt-start-"
                                + fakeFingerprint.substring("sha256:".length()),
                        fakeFingerprint, identity, command.executionEnvelopeRef(),
                        command.executionEnvelopeFingerprint(), command.requestedAt(),
                        command.confirmationDeadlineAt(), command.challenge());
        TestSuiteStabilityPhysicalAttemptStartReceipt matching = receipt(
                tampered, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                NOW.plusSeconds(2), identity.attemptId(),
                identity.identityFingerprint(), identity.isolationMode());

        assertThatThrownBy(() -> verifier.verify(
                tampered, descriptor, attest(matching, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason
                                        .COMMAND_INTEGRITY_INVALID));
    }

    @Test
    void rejectsProviderDeploymentIsolationAndAvailabilityDrift() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartReceipt wrongIsolation = receipt(
                command, TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                NOW.plusSeconds(2), identity.attemptId(), identity.identityFingerprint(),
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.CONTAINER);
        assertFailure(wrongIsolation,
                TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason
                        .PROVIDER_BINDING_INVALID);

        TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor unavailable =
                new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                        descriptor.schemaVersion(), descriptor.providerId(),
                        descriptor.deploymentId(), descriptor.keyId(), false,
                        descriptor.isolationModes(), descriptor.maximumStartLatency());
        TestSuiteStabilityPhysicalAttemptStartReceipt valid = receipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                NOW.plusSeconds(2));
        assertThatThrownBy(() -> verifier.verify(
                command, unavailable, attest(valid, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason
                                        .PROVIDER_BINDING_INVALID));
    }

    @Test
    void rejectsLateFutureAndProviderSlowConfirmation() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartReceipt late = receipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                command.confirmationDeadlineAt().plusMillis(1));
        assertFailure(late,
                TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason.TIME_INVALID,
                NOW.plusSeconds(31));

        TestSuiteStabilityPhysicalAttemptStartReceipt future = receipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                NOW.plusSeconds(4));
        assertFailure(future,
                TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason.TIME_INVALID,
                NOW.plusSeconds(1));

        TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor fast =
                new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                        descriptor.schemaVersion(), descriptor.providerId(),
                        descriptor.deploymentId(), descriptor.keyId(), true,
                        descriptor.isolationModes(), Duration.ofSeconds(1));
        TestSuiteStabilityPhysicalAttemptStartReceipt slow = receipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                NOW.plusSeconds(2));
        assertThatThrownBy(() -> verifier.verify(
                command, fast, attest(slow, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason
                                        .TIME_INVALID));
    }

    @Test
    void rejectsExpiredTrustAndInvalidSignatureWithoutDiagnostics() throws Exception {
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt = receipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                NOW.plusSeconds(2));
        TestSuiteStabilityPhysicalAttemptStartVerifier expired = verifier(
                keyPair, NOW.minusSeconds(60), NOW.plusSeconds(1));
        assertThatThrownBy(() -> expired.verify(
                command, descriptor, attest(receipt, keyPair), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason
                                        .TRUST_INVALID));

        KeyPair untrusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(receipt, untrusted), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartVerifier
                                .VerificationException.class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(
                                    TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason
                                            .SIGNATURE_INVALID);
                            assertThat(failure.getCause()).isNull();
                        });
    }

    @Test
    void rejectsContradictoryOutcomeAndInvalidChallengeShapes() {
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptStartReceipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.SCHEMA_VERSION,
                command.commandId(), command.commandFingerprint(), PROVIDER_ID,
                DEPLOYMENT_ID, identity.attemptId(), identity.identityFingerprint(),
                identity.leaseEpoch(), 11, identity.isolationMode(),
                TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.REJECTED,
                fingerprint('5'), fingerprint('6'), NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSuiteStabilityPhysicalAttemptStartCommand.create(
                mapper, identity, envelopeRef('4'), fingerprint('4'), NOW,
                NOW.plusSeconds(30), "not-a-32-byte-challenge"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TestSuiteStabilityPhysicalAttemptStartCommand command(
            TestSuiteStabilityPhysicalAttemptIdentity value) {
        return TestSuiteStabilityPhysicalAttemptStartCommand.create(
                mapper, value, envelopeRef('4'), fingerprint('4'), NOW,
                NOW.plusSeconds(30), challenge('a'));
    }

    private TestSuiteStabilityPhysicalAttemptStartVerifier verifier(
            KeyPair pair, Instant notBefore, Instant notAfter) {
        return new TestSuiteStabilityPhysicalAttemptStartVerifier(
                mapper, Set.of(new TestSuiteStabilityPhysicalAttemptStartVerifier.TrustKey(
                PROVIDER_ID, DEPLOYMENT_ID, KEY_ID, pair.getPublic(), notBefore, notAfter)),
                Duration.ofSeconds(2));
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt receipt(
            TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome outcome,
            Instant confirmedAt) {
        return receipt(command, outcome, confirmedAt, identity.attemptId(),
                identity.identityFingerprint(), identity.isolationMode());
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt receipt(
            TestSuiteStabilityPhysicalAttemptStartCommand source,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome outcome,
            Instant confirmedAt,
            String attemptId,
            String identityFingerprint,
            TestSuiteStabilityAttemptCancellationReceipt.IsolationMode isolationMode) {
        boolean confirming = outcome
                != TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.REJECTED;
        return new TestSuiteStabilityPhysicalAttemptStartReceipt(
                TestSuiteStabilityPhysicalAttemptStartReceipt.SCHEMA_VERSION,
                source.commandId(), source.commandFingerprint(), PROVIDER_ID, DEPLOYMENT_ID,
                attemptId, identityFingerprint, source.identity().leaseEpoch(), 11,
                isolationMode, outcome, confirming ? fingerprint('5') : "",
                confirming ? fingerprint('6') : "", confirmedAt);
    }

    private void assertFailure(
            TestSuiteStabilityPhysicalAttemptStartReceipt receipt,
            TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason reason)
            throws Exception {
        assertFailure(receipt, reason, NOW.plusSeconds(3));
    }

    private void assertFailure(
            TestSuiteStabilityPhysicalAttemptStartReceipt receipt,
            TestSuiteStabilityPhysicalAttemptStartVerifier.FailureReason reason,
            Instant observedAt) throws Exception {
        assertThatThrownBy(() -> verifier.verify(
                command, descriptor, attest(receipt, keyPair), observedAt))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartVerifier
                                .VerificationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attest(
            TestSuiteStabilityPhysicalAttemptStartReceipt receipt,
            KeyPair signer) throws Exception {
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
