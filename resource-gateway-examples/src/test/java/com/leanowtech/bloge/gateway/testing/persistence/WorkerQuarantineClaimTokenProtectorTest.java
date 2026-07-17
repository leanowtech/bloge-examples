package com.leanowtech.bloge.gateway.testing.persistence;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerQuarantineClaimTokenProtectorTest {

    @Test
    void roundTripsWithFreshAuthenticatedEnvelopesThatDoNotExposeTheToken() {
        WorkerQuarantineClaimTokenProtector protector = protector(
                "key-v1", Map.of("key-v1", key(1)));

        String first = protector.protect("secret-claim-token", "command-a");
        String second = protector.protect("secret-claim-token", "command-a");

        assertThat(first).startsWith("v1.key-v1.").doesNotContain("secret-claim-token");
        assertThat(second).isNotEqualTo(first);
        assertThat(protector.unprotect(first, "command-a")).isEqualTo("secret-claim-token");
        assertThat(protector.requiresRewrap(first)).isFalse();
        assertThat(protector.keyId(first)).isEqualTo("key-v1");
    }

    @Test
    void oldKeysRemainDecryptOnlyAndTheirEnvelopesRequireRewrap() {
        WorkerQuarantineClaimTokenProtector oldProtector = protector(
                "key-v1", Map.of("key-v1", key(1)));
        String oldEnvelope = oldProtector.protect("claim-token", "command-a");
        WorkerQuarantineClaimTokenProtector rotated = protector(
                "key-v2", Map.of("key-v1", key(1), "key-v2", key(2)));

        assertThat(rotated.unprotect(oldEnvelope, "command-a")).isEqualTo("claim-token");
        assertThat(rotated.requiresRewrap(oldEnvelope)).isTrue();
        assertThat(rotated.protect("claim-token", "command-a")).startsWith("v1.key-v2.");
    }

    @Test
    void associatedDataCiphertextAndMissingKeysAllFailClosed() {
        WorkerQuarantineClaimTokenProtector protector = protector(
                "key-v1", Map.of("key-v1", key(1)));
        String envelope = protector.protect("claim-token", "command-a");
        String tampered = envelope.substring(0, envelope.length() - 1)
                + (envelope.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> protector.unprotect(envelope, "command-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication failed")
                .hasMessageNotContaining("claim-token");
        assertThatThrownBy(() -> protector.unprotect(tampered, "command-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication failed")
                .hasMessageNotContaining("claim-token");
        WorkerQuarantineClaimTokenProtector missingOldKey = protector(
                "key-v2", Map.of("key-v2", key(2)));
        assertThatThrownBy(() -> missingOldKey.unprotect(envelope, "command-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key is unavailable")
                .hasMessageNotContaining("claim-token");
    }

    @Test
    void configurationRejectsMalformedDuplicateShortAndMissingActiveKeys() {
        String key = Base64.getEncoder().encodeToString(key(1));

        assertThatThrownBy(() -> WorkerQuarantineClaimTokenProtector.fromConfiguration(
                "key-v1", "broken"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerQuarantineClaimTokenProtector.fromConfiguration(
                "key-v1", "key-v1=" + key + ",key-v1=" + key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> WorkerQuarantineClaimTokenProtector.fromConfiguration(
                "key-v1", "key-v1=" + Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte");
        assertThatThrownBy(() -> WorkerQuarantineClaimTokenProtector.fromConfiguration(
                "key-v2", "key-v1=" + key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");
    }

    private static WorkerQuarantineClaimTokenProtector protector(
            String activeKeyId, Map<String, byte[]> keys) {
        return new WorkerQuarantineClaimTokenProtector(
                activeKeyId, keys, new SecureRandom());
    }

    private static byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }
}
