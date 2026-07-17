package com.leanowtech.bloge.gateway.testing.persistence;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoverySequenceRequestKeyProtectorTest {

    private static final byte[] KEY_A = bytes(0);
    private static final byte[] KEY_B = bytes(32);

    @Test
    void derivesDeterministicScopeBoundIndexesWithoutRetainingTheRequestId() {
        var protector = new RecoverySequenceRequestKeyProtector(
                "key-a", Map.of("key-a", KEY_A));

        var first = protector.protect("tenant-a", "test", "human-readable-request");
        var replay = protector.protect("tenant-a", "test", "human-readable-request");
        var otherTenant = protector.protect(
                "tenant-b", "test", "human-readable-request");
        var otherEnvironment = protector.protect(
                "tenant-a", "staging", "human-readable-request");

        assertThat(first).isEqualTo(replay);
        assertThat(first.value()).startsWith("v1.")
                .doesNotContain("human-readable-request");
        assertThat(first).isNotEqualTo(otherTenant).isNotEqualTo(otherEnvironment);
        assertThat(protector.matches(
                "tenant-a", "test", "human-readable-request",
                first.keyId(), first.value())).isTrue();
        assertThat(protector.matches(
                "tenant-a", "test", "different-request",
                first.keyId(), first.value())).isFalse();
    }

    @Test
    void readsOldGenerationDuringRotationButWritesOnlyTheActiveGeneration() {
        var old = new RecoverySequenceRequestKeyProtector(
                "key-a", Map.of("key-a", KEY_A));
        var rotated = new RecoverySequenceRequestKeyProtector(
                "key-b", Map.of("key-a", KEY_A, "key-b", KEY_B));
        var oldIndex = old.protect("tenant-a", "test", "request-a");

        assertThat(rotated.protect("tenant-a", "test", "request-a").keyId())
                .isEqualTo("key-b");
        assertThat(rotated.lookupCandidates("tenant-a", "test", "request-a"))
                .extracting(RecoverySequenceRequestKeyProtector.IndexKey::keyId)
                .containsExactly("key-b", "key-a");
        assertThat(rotated.matches(
                "tenant-a", "test", "request-a",
                oldIndex.keyId(), oldIndex.value())).isTrue();
        assertThat(rotated.containsKey("key-a")).isTrue();
    }

    @Test
    void separatesRecoverySequenceIndexesFromWorkerQuarantineIndexes() {
        var sequence = new RecoverySequenceRequestKeyProtector(
                "key-a", Map.of("key-a", KEY_A));
        var quarantine = new WorkerQuarantineRequestKeyProtector(
                "key-a", Map.of("key-a", KEY_A));

        var sequenceIndex = sequence.protect("tenant-a", "test", "request-a");
        var quarantineIndex = quarantine.protect(
                "RECOVERY_SEQUENCE", "tenant-a:test", "request-a");

        assertThat(sequenceIndex.value()).isNotEqualTo(quarantineIndex.value());
    }

    @Test
    void parsesConfiguredKeyRingAndRejectsUnsafeKeyMaterial() {
        String keyA = Base64.getEncoder().encodeToString(KEY_A);
        String keyB = Base64.getEncoder().encodeToString(KEY_B);

        var parsed = RecoverySequenceRequestKeyProtector.fromConfiguration(
                "key-b", "key-a=" + keyA + ",key-b=" + keyB);

        assertThat(parsed.activeKeyId()).isEqualTo("key-b");
        assertThatThrownBy(() -> RecoverySequenceRequestKeyProtector
                .fromConfiguration("missing", "key-a=" + keyA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");
        assertThatThrownBy(() -> RecoverySequenceRequestKeyProtector
                .fromConfiguration("key-a", "key-a="
                        + Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> RecoverySequenceRequestKeyProtector
                .fromConfiguration("key-a", "not-an-entry"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId=base64Key");
    }

    @Test
    void rejectsMalformedPersistedIndexShapes() {
        assertThatThrownBy(() -> new RecoverySequenceRequestKeyProtector.IndexKey(
                "key-a", "v2.not-current"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> new RecoverySequenceRequestKeyProtector.IndexKey(
                "bad key", "v1.AAAA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key ids");
    }

    private static byte[] bytes(int offset) {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (offset + index);
        }
        return value;
    }
}
