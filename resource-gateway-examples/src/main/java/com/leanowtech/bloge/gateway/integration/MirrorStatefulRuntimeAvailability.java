package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Profile-owned capability marker for the protected stateful mirror Session API.
 *
 * <p>Route assembly and data-plane readiness are intentionally independent. The marker is absent
 * in production and disabled deployments, while every capability probe rechecks the encrypted
 * state store without reading or decrypting a customer payload.</p>
 */
public final class MirrorStatefulRuntimeAvailability {
    private final boolean sessionApi;
    private final BooleanSupplier stateStoreReadiness;
    private final BooleanSupplier checkpointSignerReadiness;

    /**
     * Creates an honest stateful Session API marker.
     *
     * @param sessionApi protected Session routes are physically assembled
     * @param stateStoreReadiness dynamic encrypted data-plane readiness
     */
    public MirrorStatefulRuntimeAvailability(
            boolean sessionApi, BooleanSupplier stateStoreReadiness) {
        this(sessionApi, stateStoreReadiness, () -> false);
    }

    /**
     * Creates an honest Session and signed-checkpoint capability marker.
     *
     * @param sessionApi protected Session routes are physically assembled
     * @param stateStoreReadiness dynamic encrypted data-plane readiness
     * @param checkpointSignerReadiness dynamic checkpoint signing authority readiness
     */
    public MirrorStatefulRuntimeAvailability(
            boolean sessionApi,
            BooleanSupplier stateStoreReadiness,
            BooleanSupplier checkpointSignerReadiness) {
        this.sessionApi = sessionApi;
        this.stateStoreReadiness = Objects.requireNonNull(
                stateStoreReadiness, "stateStoreReadiness");
        this.checkpointSignerReadiness = Objects.requireNonNull(
                checkpointSignerReadiness,
                "checkpointSignerReadiness");
    }

    /**
     * Reports whether protected Session routes are physically assembled.
     *
     * @return {@code true} when the Session API is present
     */
    public boolean sessionApi() {
        return sessionApi;
    }

    /**
     * Probes the encrypted state data plane without propagating provider failure.
     *
     * @return whether the assembled store can currently accept encrypted writes
     */
    public boolean stateStoreReady() {
        if (!sessionApi) {
            return false;
        }
        try {
            return stateStoreReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Probes exact recovery prerequisites without creating or reading a checkpoint.
     *
     * @return whether the Session store and checkpoint signing authority are both ready
     */
    public boolean checkpointReady() {
        if (!stateStoreReady()) {
            return false;
        }
        try {
            return checkpointSignerReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
