package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayPayloadIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void canonicalSnapshotDetachesMutableBeanValueBeforeTrust() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        MutableValue value = new MutableValue(new ArrayList<>(List.of("approved")));
        StoredReplayPayload candidate = payload("tenant-a", "test", "replay-a", 1,
                value, now, now.plusSeconds(60));

        StoredReplayPayload snapshot = ReplayPayloadIntegrity.verifiedAvailableSnapshot(
                MAPPER, candidate);
        value.decisions().add("denied");

        assertThat(snapshot.value()).isEqualTo(Map.of("decisions", List.of("approved")));
        assertThat(snapshot.descriptor().fingerprint())
                .isEqualTo(ReplayPayloadIntegrity.payloadFingerprint(
                        MAPPER, snapshot.descriptor(), snapshot.value()));
    }

    @Test
    void createReceiptRejectsAValidButDifferentReplacement() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        StoredReplayPayload expected = payload("tenant-a", "test", "replay-a", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(60));
        StoredReplayPayload replacement = payload("tenant-a", "test", "replay-b", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(60));

        assertThatThrownBy(() -> ReplayPayloadIntegrity.verifiedCreateReceipt(
                MAPPER, replacement, expected))
                .isInstanceOf(ReplayPayloadIntegrityException.class)
                .hasMessageNotContaining("approved");
    }

    @Test
    void lookupRejectsCrossScopeReplacementEvenWhenPayloadIsValid() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        StoredReplayPayload valid = payload("tenant-b", "staging", "replay-a", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(60));

        assertThatThrownBy(() -> ReplayPayloadIntegrity.verifiedLookup(
                MAPPER, valid, "tenant-a", "test", "replay-a", 1))
                .isInstanceOf(ReplayPayloadIntegrityException.class);
    }

    @Test
    void tombstoneCommitmentChangesWithLifecycleButNeverContainsValue() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        StoredReplayPayload available = payload("tenant-a", "test", "replay-a", 1,
                Map.of("secret", "already-redacted"), now, now.plusSeconds(60));
        StoredReplayPayload alternateValue = new StoredReplayPayload("", available.tenantId(),
                available.environmentId(), available.descriptor(), StoredReplayPayload.AVAILABLE,
                true, Map.of("different", "value"), available.storedAt(), available.storedBy());
        StoredReplayPayload expired = available.expired();

        assertThat(ReplayPayloadIntegrity.recordFingerprint(MAPPER, alternateValue))
                .isEqualTo(ReplayPayloadIntegrity.recordFingerprint(MAPPER, available));
        assertThat(ReplayPayloadIntegrity.recordFingerprint(MAPPER, expired))
                .isNotEqualTo(ReplayPayloadIntegrity.recordFingerprint(MAPPER, available));
        assertThat(MAPPER.valueToTree(Map.of("fingerprint",
                        ReplayPayloadIntegrity.recordFingerprint(MAPPER, expired))).toString())
                .doesNotContain("already-redacted");
        assertThat(ReplayPayloadIntegrity.verifiedSnapshot(MAPPER, expired)).isEqualTo(expired);
        assertThatThrownBy(() -> ReplayPayloadIntegrity.verifiedSnapshot(MAPPER, alternateValue))
                .isInstanceOf(ReplayPayloadIntegrityException.class);
    }

    private static StoredReplayPayload payload(String tenantId, String environmentId,
                                                String id, long revision, Object value,
                                                Instant storedAt, Instant expiresAt) {
        ReplayPayloadDescriptor.Source source = new ReplayPayloadDescriptor.Source(
                "GOVERNED_RUN_NODE_ATTEMPT", "run-a", "fetch", 1,
                fingerprint('e'), fingerprint('f'), environmentId);
        ReplayPayloadDescriptor.Redaction redaction = new ReplayPayloadDescriptor.Redaction(
                "source@1", 1, "capture@1", 0, false, List.of());
        ReplayPayloadDescriptor draft = new ReplayPayloadDescriptor("", id, revision, "",
                "INTERNAL", source, redaction, storedAt, expiresAt, true, List.of());
        String fingerprint = ReplayPayloadIntegrity.payloadFingerprint(MAPPER, draft, value);
        ReplayPayloadDescriptor descriptor = new ReplayPayloadDescriptor("", id, revision,
                fingerprint, "INTERNAL", source, redaction, storedAt, expiresAt, true, List.of());
        return new StoredReplayPayload("", tenantId, environmentId, descriptor,
                StoredReplayPayload.AVAILABLE, true, value, storedAt, "runner");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record MutableValue(List<String> decisions) {
    }
}
