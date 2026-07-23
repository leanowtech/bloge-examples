package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Map;
import java.util.Objects;

/**
 * Aggregate-only health projection for the encrypted mirror-session data plane.
 *
 * <p>The projection contains only global counts and configured limits. It never exposes enterprise
 * scope, session identity, fingerprints, keys, payloads, leases, or provider diagnostics.
 * Saturation keeps the component UP but reports {@code admissionAvailable=false}; connectivity or
 * capacity-observation failure is DOWN.</p>
 */
public final class MirrorSessionCapacityHealth implements HealthIndicator {
    private final MirrorSessionStateStore store;

    /**
     * Creates the aggregate state-plane health projection.
     *
     * @param store dedicated encrypted state store
     */
    public MirrorSessionCapacityHealth(MirrorSessionStateStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Returns connectivity and global admission capacity without customer dimensions.
     */
    @Override
    public Health health() {
        try {
            if (!store.ready()) {
                return Health.down()
                        .withDetail("state", "UNAVAILABLE")
                        .build();
            }
            MirrorSessionStateStore.CapacitySnapshot snapshot =
                    store.capacity();
            return Health.up().withDetails(Map.of(
                    "state", "READY",
                    "admissionAvailable", snapshot.admissionAvailable(),
                    "activeSessions", snapshot.activeSessions(),
                    "maximumActiveSessions",
                    snapshot.maximumActiveSessions(),
                    "retainedPayloadBytes",
                    snapshot.retainedPayloadBytes(),
                    "expiredRetainedPayloadBytes",
                    snapshot.expiredRetainedPayloadBytes(),
                    "maximumRetainedPayloadBytes",
                    snapshot.maximumRetainedPayloadBytes()))
                    .build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("state", "UNAVAILABLE")
                    .build();
        }
    }
}
