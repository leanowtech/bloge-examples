package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MirrorSessionCapacityHealthTest {

    @Test
    void reportsPayloadFreeGlobalCapacityWithoutFailingOnSaturation() {
        MirrorSessionStateStore store = mock(MirrorSessionStateStore.class);
        when(store.ready()).thenReturn(true);
        when(store.capacity()).thenReturn(
                new MirrorSessionStateStore.CapacitySnapshot(
                        10, 1_000, 100, 10, 1_000));

        var health = new MirrorSessionCapacityHealth(store).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry(
                "admissionAvailable", false);
        assertThat(health.getDetails()).containsEntry(
                "activeSessions", 10L);
        assertThat(health.getDetails().keySet()).doesNotContain(
                "tenant", "organization", "project", "environment",
                "region", "session", "payload", "fingerprint", "key");
    }

    @Test
    void failsClosedWhenTheStoreOrObservationIsUnavailable() {
        MirrorSessionStateStore store = mock(MirrorSessionStateStore.class);
        when(store.ready()).thenReturn(false);
        assertThat(new MirrorSessionCapacityHealth(store).health().getStatus())
                .isEqualTo(Status.DOWN);

        when(store.ready()).thenReturn(true);
        when(store.capacity()).thenThrow(new IllegalStateException("secret"));
        var health = new MirrorSessionCapacityHealth(store).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsOnlyKeys("state")
                .containsEntry("state", "UNAVAILABLE");
    }
}
