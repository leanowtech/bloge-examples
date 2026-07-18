package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityExternalSequenceCheckpointProtocolTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String SHA_A = fingerprint('a');
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void requestFingerprintBindsFreshChallengeAndCompleteHead() {
        var head = head(1, SHA_A, "");
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32]);
        var request = TestSuiteStabilityExternalSequenceCheckpointRequest.create(
                objectMapper, "inventory-transparency", "notary-set-a", head,
                challenge, NOW, NOW.plusSeconds(10));

        assertThat(request.fingerprintVerified(objectMapper)).isTrue();
        assertThat(new TestSuiteStabilityExternalSequenceCheckpointRequest(
                request.schemaVersion(), request.requestFingerprint(), request.trustDomain(),
                request.anchorSetId(), head,
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[] {
                                1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
                request.requestedAt(), request.expiresAt())
                .fingerprintVerified(objectMapper)).isFalse();
    }

    @Test
    void receiptSeparatesAcceptedHeadFromAuthenticatedConflict() {
        var acceptedMaterial = new TestSuiteStabilityExternalSequenceCheckpointReceipt.Material(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                SHA_A, "inventory-transparency", "notary-set-a", "notary-a",
                "region-a", "key-a",
                TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED,
                1, SHA_A, 1, SHA_A, NOW, NOW.plusSeconds(10), "Ed25519");
        String acceptedFingerprint = ProtocolFingerprint.of(objectMapper, acceptedMaterial);
        var accepted = new TestSuiteStabilityExternalSequenceCheckpointReceipt(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                acceptedFingerprint, SHA_A, "inventory-transparency", "notary-set-a",
                "notary-a", "region-a", "key-a",
                TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED,
                1, SHA_A, 1, SHA_A, NOW, NOW.plusSeconds(10), "Ed25519",
                Base64.getEncoder().encodeToString(new byte[64]));

        assertThat(accepted.fingerprintVerified(objectMapper)).isTrue();
        assertThatThrownBy(() -> new TestSuiteStabilityExternalSequenceCheckpointReceipt(
                accepted.schemaVersion(), accepted.receiptFingerprint(),
                accepted.requestFingerprint(), accepted.trustDomain(), accepted.anchorSetId(),
                accepted.authorityId(), accepted.failureDomain(), accepted.keyId(),
                TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.CONFLICT,
                1, SHA_A, 1, SHA_A, NOW, NOW.plusSeconds(10), "Ed25519",
                accepted.signature()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid external checkpoint receipt");
    }

    @Test
    void descriptorCannotOverclaimByzantineQuorumMath() {
        assertThatThrownBy(() -> new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                true, true, true, true, 3, 2, 1, 3, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid external sequence-anchor descriptor");
        assertThatThrownBy(() -> new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                true, true, true, true, 4, 3, 1, 4,
                Map.of("endpoint", "https://notary.example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid external sequence-anchor descriptor");
        assertThatThrownBy(() -> new TestSuiteStabilityExternalSequenceAnchor.Snapshot(
                TestSuiteStabilityExternalSequenceAnchor.Snapshot.SCHEMA_VERSION,
                true, "HEALTHY", NOW, 1, 0, 0, 3, 2, 1, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid external sequence-anchor snapshot");
    }

    @Test
    void receiptRejectsMalformedEd25519AndSnapshotsRejectPartialSafetyClaims() {
        assertThatThrownBy(() -> new TestSuiteStabilityExternalSequenceCheckpointReceipt(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                SHA_A, SHA_A, "inventory-transparency", "notary-set-a",
                "notary-a", "region-a", "key-a",
                TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED,
                1, SHA_A, 1, SHA_A, NOW, NOW.plusSeconds(10), "Ed25519",
                Base64.getEncoder().encodeToString(new byte[63])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid external checkpoint receipt");

        assertThatThrownBy(() -> new DynamicTestSuiteStabilityServingInventoryAuthority.Snapshot(
                "bloge.testSuiteStabilityServingInventoryRefreshSnapshot.v1",
                true, "HEALTHY", "ACTIVE", 1, NOW, 1, 0, "",
                30, 60, 1, true, false, false, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid serving-inventory refresh snapshot");
        assertThatThrownBy(() ->
                new DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.Snapshot(
                        DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.Snapshot
                                .SCHEMA_VERSION,
                        true, "HEALTHY", 1, NOW, 1, 0, "", 30, 3000,
                        5, 60, 1, 1, 1, 1, true, false, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dynamic serving-inventory trust-root snapshot is invalid");
    }

    private static TestSuiteStabilityExternalSequenceAnchor.Head head(
            long sequence, String current, String previous) {
        return new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_TRUST_ROOT,
                "stability-fleet", "inventory-roots", sequence, current, previous);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
