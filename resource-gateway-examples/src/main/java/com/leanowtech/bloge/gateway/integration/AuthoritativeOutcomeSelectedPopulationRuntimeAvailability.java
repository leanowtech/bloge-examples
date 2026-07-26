package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Independent readiness marker for selected-population completeness.
 *
 * <p>Route assembly and three external trust boundaries remain separate facts. Product readiness
 * is true only when durable storage, source-closure reads, selection authority, outcome authority,
 * deletion authority, and Resource Gateway signing can all serve the protocol.</p>
 */
public final class
AuthoritativeOutcomeSelectedPopulationRuntimeAvailability {
    private final boolean api;
    private final boolean durableRegistry;
    private final boolean sourceClosure;
    private final boolean stagedUpload;
    private final BooleanSupplier authorityReadiness;

    /** Creates one profile-owned selected-population runtime marker. */
    public AuthoritativeOutcomeSelectedPopulationRuntimeAvailability(
            boolean api,
            boolean durableRegistry,
            boolean sourceClosure,
            BooleanSupplier authorityReadiness) {
        this(
                api,
                durableRegistry,
                sourceClosure,
                false,
                authorityReadiness);
    }

    /** Creates one profile-owned marker including resumable staged upload. */
    public AuthoritativeOutcomeSelectedPopulationRuntimeAvailability(
            boolean api,
            boolean durableRegistry,
            boolean sourceClosure,
            boolean stagedUpload,
            BooleanSupplier authorityReadiness) {
        this.api = api;
        this.durableRegistry = durableRegistry;
        this.sourceClosure = sourceClosure;
        this.stagedUpload = stagedUpload;
        this.authorityReadiness = Objects.requireNonNull(
                authorityReadiness, "authorityReadiness");
    }

    /** @return whether protected population routes are assembled */
    public boolean api() {
        return api;
    }

    /** @return whether append-only population/disposition/assessment storage is assembled */
    public boolean durableRegistry() {
        return durableRegistry;
    }

    /** @return whether historical assessment source pages are available */
    public boolean sourceClosure() {
        return sourceClosure;
    }

    /** @return whether durable resumable chunk upload and fenced finalize are assembled */
    public boolean stagedUpload() {
        return stagedUpload;
    }

    /** @return whether selection, outcome, deletion, and signing boundaries are currently usable */
    public boolean authoritiesReady() {
        try {
            return authorityReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** @return whether selected-population completeness can be produced and audited now */
    public boolean continuousReady() {
        return api
                && durableRegistry
                && sourceClosure
                && stagedUpload
                && authoritiesReady();
    }
}
