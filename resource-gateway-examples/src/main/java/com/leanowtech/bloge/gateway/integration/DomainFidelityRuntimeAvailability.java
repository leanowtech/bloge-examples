package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityMeasurementSource;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelitySourceAvailability;

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
    private final DomainFidelitySourceAvailability sources;

    /** Creates one profile-owned runtime marker. */
    public DomainFidelityRuntimeAvailability(
            boolean inventoryApi,
            boolean profileReadApi,
            BooleanSupplier signingReadiness,
            DomainFidelitySourceAvailability sources) {
        this.inventoryApi = inventoryApi;
        this.profileReadApi = profileReadApi;
        this.signingReadiness = Objects.requireNonNull(
                signingReadiness, "signingReadiness");
        this.sources = Objects.requireNonNull(sources, "sources");
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
                && sources.anyReady();
    }

    /** @return whether signed Scenario workbooks can be independently verified and projected */
    public boolean scenarioAdapterReady() {
        return sources.ready(
                DomainFidelityMeasurementSource.Type
                        .SCENARIO_REHEARSAL);
    }

    /** @return whether read-only shadow comparisons can be independently verified and projected */
    public boolean shadowAdapterReady() {
        return sources.ready(
                DomainFidelityMeasurementSource.Type
                        .READ_ONLY_SHADOW);
    }

    /** @return whether authoritative business outcomes can be independently verified */
    public boolean outcomeAdapterReady() {
        return sources.ready(
                DomainFidelityMeasurementSource.Type
                        .AUTHORITATIVE_OUTCOME);
    }

    private static boolean probe(BooleanSupplier source) {
        try {
            return source.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
