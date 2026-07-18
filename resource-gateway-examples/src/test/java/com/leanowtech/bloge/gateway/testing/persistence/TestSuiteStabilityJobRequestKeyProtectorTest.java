package com.leanowtech.bloge.gateway.testing.persistence;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityJobRequestKeyProtectorTest {

    private static final byte[] KEY_A = bytes(0);
    private static final byte[] KEY_B = bytes(32);

    @Test
    void derivesDeterministicScopeBoundIndexesWithoutRetainingPlaintext() {
        var protector = new TestSuiteStabilityJobRequestKeyProtector(
                "key-a", Map.of("key-a", KEY_A));

        var first = protector.protect("tenant-a", "test", "human-readable-request");
        var replay = protector.protect("tenant-a", "test", "human-readable-request");

        assertThat(first).isEqualTo(replay);
        assertThat(first.value()).startsWith("v1.")
                .doesNotContain("human-readable-request");
        assertThat(first).isNotEqualTo(protector.protect(
                "tenant-b", "test", "human-readable-request"));
        assertThat(first).isNotEqualTo(protector.protect(
                "tenant-a", "staging", "human-readable-request"));
        assertThat(protector.matches("tenant-a", "test", "human-readable-request",
                first.keyId(), first.value())).isTrue();
        assertThat(protector.matches("tenant-a", "test", "another-request",
                first.keyId(), first.value())).isFalse();
    }

    @Test
    void rotationReadsOldGenerationAndWritesOnlyActiveGeneration() {
        var old = new TestSuiteStabilityJobRequestKeyProtector(
                "key-a", Map.of("key-a", KEY_A));
        var rotated = new TestSuiteStabilityJobRequestKeyProtector(
                "key-b", Map.of("key-a", KEY_A, "key-b", KEY_B));
        var oldIndex = old.protect("tenant-a", "test", "request-a");

        assertThat(rotated.protect("tenant-a", "test", "request-a").keyId())
                .isEqualTo("key-b");
        assertThat(rotated.lookupCandidates("tenant-a", "test", "request-a"))
                .extracting(TestSuiteStabilityJobRequestKeyProtector.IndexKey::keyId)
                .containsExactly("key-b", "key-a");
        assertThat(rotated.matches("tenant-a", "test", "request-a",
                oldIndex.keyId(), oldIndex.value())).isTrue();
    }

    @Test
    void indexDomainIsIndependentFromOtherControlPlanesUsingTheSameRoot() {
        var stability = new TestSuiteStabilityJobRequestKeyProtector(
                "key-a", Map.of("key-a", KEY_A));
        var recovery = new RecoverySequenceRequestKeyProtector(
                "key-a", Map.of("key-a", KEY_A));

        assertThat(stability.protect("tenant-a", "test", "request-a").value())
                .isNotEqualTo(recovery.protect("tenant-a", "test", "request-a").value());
    }

    @Test
    void parsesConfigurationAndRejectsUnsafeKeyRingsAndIndexes() {
        String keyA = Base64.getEncoder().encodeToString(KEY_A);
        String keyB = Base64.getEncoder().encodeToString(KEY_B);

        var parsed = TestSuiteStabilityJobRequestKeyProtector.fromConfiguration(
                "key-b", "key-a=" + keyA + ",key-b=" + keyB);

        assertThat(parsed.activeKeyId()).isEqualTo("key-b");
        assertThat(parsed.toString()).doesNotContain(keyA).doesNotContain(keyB);
        assertThatThrownBy(() -> TestSuiteStabilityJobRequestKeyProtector
                .fromConfiguration("missing", "key-a=" + keyA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");
        assertThatThrownBy(() -> TestSuiteStabilityJobRequestKeyProtector
                .fromConfiguration("key-a", "key-a="
                        + Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> TestSuiteStabilityJobRequestKeyProtector
                .fromConfiguration("key-a", "not-an-entry"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId=base64Key");
        assertThatThrownBy(() -> new TestSuiteStabilityJobRequestKeyProtector.IndexKey(
                "key-a", "v2.not-current"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    private static byte[] bytes(int offset) {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (offset + index);
        }
        return value;
    }
}
