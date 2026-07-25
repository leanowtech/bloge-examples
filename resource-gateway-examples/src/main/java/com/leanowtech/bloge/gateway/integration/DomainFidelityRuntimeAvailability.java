package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Independent capability marker for the protected Domain Fidelity surface.
 *
 * <p>Route assembly, managed signing, and source-adapter readiness are separate facts. This keeps
 * an inventory API or a readable historical profile from falsely advertising that a new profile
 * can currently be projected.</p>
 */
public final class DomainFidelityRuntimeAvailability {
    private final boolean inventoryApi;
    private final boolean profileReadApi;
    private final BooleanSupplier signingReadiness;
    private final BooleanSupplier sourceAdapterReadiness;

    /** Creates one profile-owned runtime marker. */
    public DomainFidelityRuntimeAvailability(
            boolean inventoryApi,
            boolean profileReadApi,
            BooleanSupplier signingReadiness,
            BooleanSupplier sourceAdapterReadiness) {
        this.inventoryApi = inventoryApi;
        this.profileReadApi = profileReadApi;
        this.signingReadiness = Objects.requireNonNull(
                signingReadiness, "signingReadiness");
        this.sourceAdapterReadiness = Objects.requireNonNull(
                sourceAdapterReadiness, "sourceAdapterReadiness");
    }

    /** @return whether protected inventory register/read routes are assembled */
    public boolean inventoryApi() {
        return inventoryApi;
    }

    /** @return whether protected signed profile read routes are assembled */
    public boolean profileReadApi() {
        return profileReadApi;
    }

    /** @return current managed signing and verification readiness */
    public boolean signingReady() {
        return probe(signingReadiness);
    }

    /**
     * Reports complete projection readiness.
     *
     * @return true only when routes, signing, and independently verified source adapters are ready
     */
    public boolean projectionReady() {
        return inventoryApi
                && profileReadApi
                && signingReady()
                && probe(sourceAdapterReadiness);
    }

    private static boolean probe(BooleanSupplier source) {
        try {
            return source.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
