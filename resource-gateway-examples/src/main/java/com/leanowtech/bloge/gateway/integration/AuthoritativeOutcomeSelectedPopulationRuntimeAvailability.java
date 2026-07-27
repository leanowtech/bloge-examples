package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Independent readiness marker for selected-population completeness.
 *
 * <p>Route assembly, durable evidence, continuous projection, worker scheduling, and external
 * trust boundaries remain separate facts. Product readiness is true only when every required
 * layer can currently serve the protocol.</p>
 */
public final class
AuthoritativeOutcomeSelectedPopulationRuntimeAvailability {
    private final boolean api;
    private final boolean durableRegistry;
    private final boolean sourceClosure;
    private final boolean stagedUpload;
    private final boolean continuousAssessmentApi;
    private final boolean durableProjection;
    private final BooleanSupplier authorityReadiness;
    private final BooleanSupplier workerReadiness;
    private final BooleanSupplier schedulerReadiness;

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
                false,
                false,
                authorityReadiness,
                () -> false,
                () -> false);
    }

    /** Creates one profile-owned marker including resumable staged upload. */
    public AuthoritativeOutcomeSelectedPopulationRuntimeAvailability(
            boolean api,
            boolean durableRegistry,
            boolean sourceClosure,
            boolean stagedUpload,
            BooleanSupplier authorityReadiness) {
        this(
                api,
                durableRegistry,
                sourceClosure,
                stagedUpload,
                false,
                false,
                authorityReadiness,
                () -> false,
                () -> false);
    }

    /** Creates one complete marker including continuous projection runtime facts. */
    public AuthoritativeOutcomeSelectedPopulationRuntimeAvailability(
            boolean api,
            boolean durableRegistry,
            boolean sourceClosure,
            boolean stagedUpload,
            boolean continuousAssessmentApi,
            boolean durableProjection,
            BooleanSupplier authorityReadiness,
            BooleanSupplier workerReadiness,
            BooleanSupplier schedulerReadiness) {
        this.api = api;
        this.durableRegistry = durableRegistry;
        this.sourceClosure = sourceClosure;
        this.stagedUpload = stagedUpload;
        this.continuousAssessmentApi =
                continuousAssessmentApi;
        this.durableProjection = durableProjection;
        this.authorityReadiness = Objects.requireNonNull(
                authorityReadiness, "authorityReadiness");
        this.workerReadiness = Objects.requireNonNull(
                workerReadiness, "workerReadiness");
        this.schedulerReadiness = Objects.requireNonNull(
                schedulerReadiness, "schedulerReadiness");
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

    /** @return whether protected continuous projection register/read routes are assembled */
    public boolean continuousAssessmentApi() {
        return continuousAssessmentApi;
    }

    /** @return whether database-authoritative freshness and fencing storage is assembled */
    public boolean durableProjection() {
        return durableProjection;
    }

    /** @return whether selection, outcome, deletion, and signing boundaries are currently usable */
    public boolean authoritiesReady() {
        try {
            return authorityReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** @return whether the one-step projection worker can currently call all authorities */
    public boolean projectionWorkerReady() {
        try {
            return workerReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** @return whether bounded local polling lanes are assembled and live */
    public boolean projectionSchedulerReady() {
        try {
            return schedulerReadiness.getAsBoolean();
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
                && continuousAssessmentApi
                && durableProjection
                && authoritiesReady()
                && projectionWorkerReady()
                && projectionSchedulerReady();
    }
}
