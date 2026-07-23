package com.leanowtech.bloge.gateway.testing.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStatePayloadProtectorTest {

    @Test
    void encryptsWithFreshNoncesAndBindsAssociatedData() {
        MirrorStatePayloadProtector protector = protector("k2", keys("k2", 2));
        byte[] plaintext = "{\"orderId\":\"O-100\"}".getBytes(
                StandardCharsets.UTF_8);

        String first = protector.protect(plaintext, "scope/session/0");
        String second = protector.protect(plaintext, "scope/session/0");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain("O-100");
        assertThat(protector.unprotect(first, "scope/session/0"))
                .isEqualTo(plaintext);
        assertThatThrownBy(() -> protector.unprotect(
                first, "scope/other-session/0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Mirror state payload envelope authentication failed");
    }

    @Test
    void supportsDecryptOnlyKeysAndReportsRewrap() {
        Map<String, byte[]> ring = new LinkedHashMap<>();
        ring.put("old", key(1));
        ring.put("new", key(2));
        MirrorStatePayloadProtector old = new MirrorStatePayloadProtector(
                "old", ring, new SecureRandom());
        MirrorStatePayloadProtector current = new MirrorStatePayloadProtector(
                "new", ring, new SecureRandom());
        String envelope = old.protect(
                "state".getBytes(StandardCharsets.UTF_8), "row");

        assertThat(current.requiresRewrap(envelope)).isTrue();
        assertThat(current.unprotect(envelope, "row"))
                .isEqualTo("state".getBytes(StandardCharsets.UTF_8));
        assertThat(current.activeKeyId()).isEqualTo("new");
    }

    @Test
    void rejectsWeakMissingAndMalformedKeyMaterial() {
        assertThatThrownBy(() -> MirrorStatePayloadProtector.fromConfiguration(
                "active", "active=" + Base64.getEncoder()
                        .encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte");
        assertThatThrownBy(() -> MirrorStatePayloadProtector.fromConfiguration(
                "missing", "active=" + Base64.getEncoder()
                        .encodeToString(key(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");
        assertThatThrownBy(() -> MirrorStatePayloadProtector.fromConfiguration(
                "active", "not-a-key-ring"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId=base64Key");
    }

    private static MirrorStatePayloadProtector protector(
            String active, Map<String, byte[]> ring) {
        return new MirrorStatePayloadProtector(
                active, ring, new SecureRandom());
    }

    private static Map<String, byte[]> keys(String id, int seed) {
        return Map.of(id, key(seed));
    }

    private static byte[] key(int seed) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) seed);
        return key;
    }
}
