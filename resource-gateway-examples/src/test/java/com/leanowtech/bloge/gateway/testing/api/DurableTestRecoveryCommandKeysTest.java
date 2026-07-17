package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableTestRecoveryCommandKeysTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);

    @Test
    void derivesStableScopedSequenceAndChildCommandKeys() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        String namespace = DurableTestRecoveryCommandKeys.sequenceNamespace(
                mapper, "tenant-a", "test", "sequence-a");

        assertThat(namespace).isEqualTo(
                "421518ee2c11384b1d706b6a351e4cc4bf451f4b625ce18cecca70651fd89598");
        assertThat(DurableTestRecoveryCommandKeys.sequenceStep(namespace, 0))
                .isEqualTo("rseq:" + namespace + ":step:0");
        assertThat(DurableTestRecoveryCommandKeys.sequenceClaim(namespace, 1))
                .isEqualTo("rseq:" + namespace + ":claim:1");
        assertThat(DurableTestRecoveryCommandKeys.sequenceNamespace(
                mapper, "tenant-b", "test", "sequence-a"))
                .isNotEqualTo(namespace);
        assertThat(DurableTestRecoveryCommandKeys.sequenceNamespace(
                mapper, "tenant-a", "staging", "sequence-a"))
                .isNotEqualTo(namespace);
    }

    @Test
    void derivesStableAutomaticHeartbeatKeysFromCanonicalOperationFingerprint() {
        assertThat(DurableTestRecoveryCommandKeys.automaticHeartbeatPrefix(SHA_A))
                .isEqualTo("auto-recovery-" + "a".repeat(64) + "-");
        assertThat(DurableTestRecoveryCommandKeys.automaticHeartbeat(SHA_A, 42))
                .isEqualTo("auto-recovery-" + "a".repeat(64) + "-42");
    }

    @Test
    void rejectsKeysThatCouldEscapeTheBoundedDerivedIdentitySpace() {
        assertThatThrownBy(() -> DurableTestRecoveryCommandKeys.sequenceStep("bad", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");
        assertThatThrownBy(() -> DurableTestRecoveryCommandKeys.sequenceStep(
                "a".repeat(64), 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero and fifteen");
        assertThatThrownBy(() -> DurableTestRecoveryCommandKeys.sequenceClaim(
                "a".repeat(64), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> DurableTestRecoveryCommandKeys.automaticHeartbeat(
                SHA_A, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> DurableTestRecoveryCommandKeys
                .automaticHeartbeatPrefix("not-a-fingerprint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical SHA-256");
    }
}
