package com.leanowtech.bloge.gateway.testing.admission;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRuntimeAdmissionPolicyTest {

    @Test
    void fingerprintChangesWithEveryGovernedLimitAndTimingValue() {
        TestRuntimeAdmissionPolicy baseline = policy(1, 10, Duration.ofSeconds(30),
                Duration.ofSeconds(5));

        assertThat(policy(2, 10, Duration.ofSeconds(30), Duration.ofSeconds(5)).fingerprint())
                .isNotEqualTo(baseline.fingerprint());
        assertThat(policy(1, 11, Duration.ofSeconds(30), Duration.ofSeconds(5)).fingerprint())
                .isNotEqualTo(baseline.fingerprint());
        assertThat(policy(1, 10, Duration.ofSeconds(31), Duration.ofSeconds(5)).fingerprint())
                .isNotEqualTo(baseline.fingerprint());
        assertThat(policy(1, 10, Duration.ofSeconds(30), Duration.ofSeconds(6)).fingerprint())
                .isNotEqualTo(baseline.fingerprint());
    }

    @Test
    void rejectsTimingValuesThatCannotBeRepresentedByTheDatabaseProtocolFingerprint() {
        assertThatThrownBy(() -> policy(1, 10, Duration.ofSeconds(1), Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(1, 10, Duration.ofMillis(2_500), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(1, 10, Duration.ofSeconds(30), Duration.ofMillis(1_500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(1, 10, Duration.ofSeconds(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestRuntimeAdmissionPolicy policy(
            long generation,
            long tenantLimit,
            Duration lease,
            Duration heartbeat) {
        return new TestRuntimeAdmissionPolicy(
                generation, tenantLimit, 3, 4, 5, lease, heartbeat);
    }
}
