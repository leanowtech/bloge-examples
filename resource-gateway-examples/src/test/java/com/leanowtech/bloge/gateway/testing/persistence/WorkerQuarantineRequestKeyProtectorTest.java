package com.leanowtech.bloge.gateway.testing.persistence;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerQuarantineRequestKeyProtectorTest {

    @Test
    void createsDeterministicIndexesBoundToKindScopeAndRequestIdentity() {
        WorkerQuarantineRequestKeyProtector protector = protector(
                "index-v1", Map.of("index-v1", key(1)));

        var first = protector.protect("CLAIM", "scope-a", "request-42");
        var second = protector.protect("CLAIM", "scope-a", "request-42");

        assertThat(first).isEqualTo(second);
        assertThat(first.keyId()).isEqualTo("index-v1");
        assertThat(first.value()).startsWith("v1.").doesNotContain("request-42");
        assertThat(protector.matches("CLAIM", "scope-a", "request-42",
                first.keyId(), first.value())).isTrue();
        assertThat(protector.matches("RESOLUTION", "scope-a", "request-42",
                first.keyId(), first.value())).isFalse();
        assertThat(protector.matches("CLAIM", "scope-b", "request-42",
                first.keyId(), first.value())).isFalse();
        assertThat(protector.matches("CLAIM", "scope-a", "request-43",
                first.keyId(), first.value())).isFalse();
    }

    @Test
    void rotationProducesActiveFirstBoundedCandidatesAndKeepsOldKeysVerificationOnly() {
        Map<String, byte[]> roots = new LinkedHashMap<>();
        roots.put("index-v1", key(1));
        roots.put("index-v3", key(3));
        roots.put("index-v2", key(2));
        WorkerQuarantineRequestKeyProtector rotated = protector("index-v3", roots);
        WorkerQuarantineRequestKeyProtector old = protector(
                "index-v1", Map.of("index-v1", key(1)));
        var oldIndex = old.protect("CLAIM", "scope-a", "request-42");

        assertThat(rotated.lookupCandidates("CLAIM", "scope-a", "request-42"))
                .extracting(WorkerQuarantineRequestKeyProtector.IndexKey::keyId)
                .containsExactly("index-v3", "index-v1", "index-v2");
        assertThat(rotated.matches("CLAIM", "scope-a", "request-42",
                oldIndex.keyId(), oldIndex.value())).isTrue();
        assertThat(rotated.requiresRekey(oldIndex.keyId())).isTrue();
        assertThat(rotated.requiresRekey("index-v3")).isFalse();
        assertThat(rotated.containsKey("index-v1")).isTrue();
        assertThat(rotated.protect("CLAIM", "scope-a", "request-42").keyId())
                .isEqualTo("index-v3");
    }

    @Test
    void missingKeysMalformedIndexesAndMalformedConfigurationFailClosed() {
        WorkerQuarantineRequestKeyProtector protector = protector(
                "index-v2", Map.of("index-v2", key(2)));

        assertThatThrownBy(() -> protector.matches(
                "CLAIM", "scope-a", "request-42", "index-v1", "v1." + "A".repeat(43)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key is unavailable")
                .hasMessageNotContaining("request-42");
        assertThatThrownBy(() -> protector.matches(
                "CLAIM", "scope-a", "request-42", "index-v2", "sha256:" + "a".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index is invalid");
        String encoded = Base64.getEncoder().encodeToString(key(1));
        assertThatThrownBy(() -> WorkerQuarantineRequestKeyProtector.fromConfiguration(
                "index-v1", "broken"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerQuarantineRequestKeyProtector.fromConfiguration(
                "index-v1", "index-v1=" + encoded + ","))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId=base64Key");
        assertThatThrownBy(() -> WorkerQuarantineRequestKeyProtector.fromConfiguration(
                "index-v1", "index-v1=" + encoded + ",index-v1=" + encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> WorkerQuarantineRequestKeyProtector.fromConfiguration(
                "index-v1", "index-v1="
                        + Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> WorkerQuarantineRequestKeyProtector.fromConfiguration(
                "index-v2", "index-v1=" + encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");
    }

    @Test
    void keyRingSizeIsBoundedToPreventUnboundedLookupAmplification() {
        Map<String, byte[]> roots = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++) {
            roots.put("key-" + index, key(index));
        }

        assertThatThrownBy(() -> protector("key-0", roots))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 through 16");
    }

    private static WorkerQuarantineRequestKeyProtector protector(
            String activeKeyId, Map<String, byte[]> keys) {
        return new WorkerQuarantineRequestKeyProtector(activeKeyId, keys);
    }

    private static byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }
}
