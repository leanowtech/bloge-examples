package com.leanowtech.bloge.gateway.testing.world;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldInvocationCoordinateTest {
    @Test
    void canonicalEncodingPreventsDelimiterBasedCollisions() {
        WorldInvocationCoordinate first = new WorldInvocationCoordinate(
                "/root/a|b", "node=one", 1, 2, 3, "/root/a|b/node=one#PRIMARY");
        WorldInvocationCoordinate second = new WorldInvocationCoordinate(
                "/root/a", "b|node=one", 1, 2, 3, "/root/a/b|node=one#PRIMARY");

        assertThat(first.canonicalKey()).isNotEqualTo(second.canonicalKey())
                .startsWith(WorldInvocationCoordinate.SCHEMA_VERSION + "|");
    }

    @Test
    void rejectsNonPositiveAndOversizedCoordinateInputs() {
        assertThatThrownBy(() -> new WorldInvocationCoordinate(
                "/root", "node", 1, 0, 1, "/root/node#PRIMARY"))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSACTION_INVALID));
        assertThatThrownBy(() -> new WorldInvocationCoordinate(
                "/" + "x".repeat(4_096), "node", 1, 1, 1, "/root/node#PRIMARY"))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_TRANSACTION_INVALID));
    }
}
