package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorSessionCapacityPolicyTest {

    @Test
    void acceptsBoundedHierarchicalLimits() {
        MirrorSessionCapacityPolicy policy =
                new MirrorSessionCapacityPolicy(100, 10, 1_000_000, 100_000);

        assertThat(policy.maximumActiveSessions()).isEqualTo(100);
        assertThat(policy.maximumScopeActiveSessions()).isEqualTo(10);
        assertThat(policy.maximumRetainedPayloadBytes()).isEqualTo(1_000_000);
        assertThat(policy.maximumScopeRetainedPayloadBytes()).isEqualTo(100_000);
    }

    @Test
    void rejectsInvertedOrUnboundedLimits() {
        assertThatThrownBy(() ->
                new MirrorSessionCapacityPolicy(10, 11, 1_000, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new MirrorSessionCapacityPolicy(10, 5, 100, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new MirrorSessionCapacityPolicy(0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new MirrorSessionCapacityPolicy(
                        1_000_001, 1, 1_000, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
