package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Independent capability marker for the production outcome source control and worker surface. */
public final class AuthoritativeOutcomeSourceRuntimeAvailability {
    private final boolean controlApi;
    private final boolean durableCheckpoint;
    private final BooleanSupplier sourceReadiness;
    private final BooleanSupplier authorityReadiness;
    private final BooleanSupplier workerReadiness;
    private final BooleanSupplier schedulerReadiness;

    /** Creates one profile-owned runtime marker. */
    public AuthoritativeOutcomeSourceRuntimeAvailability(
            boolean controlApi,
            boolean durableCheckpoint,
            BooleanSupplier sourceReadiness,
            BooleanSupplier authorityReadiness,
            BooleanSupplier workerReadiness,
            BooleanSupplier schedulerReadiness) {
        this.controlApi = controlApi;
        this.durableCheckpoint = durableCheckpoint;
        this.sourceReadiness = Objects.requireNonNull(sourceReadiness, "sourceReadiness");
        this.authorityReadiness = Objects.requireNonNull(
                authorityReadiness, "authorityReadiness");
        this.workerReadiness = Objects.requireNonNull(workerReadiness, "workerReadiness");
        this.schedulerReadiness = Objects.requireNonNull(
                schedulerReadiness, "schedulerReadiness");
    }

    /** @return whether protected management and checkpoint routes are assembled */
    public boolean controlApi() {
        return controlApi;
    }

    /** @return whether staged pages and cursors survive restart */
    public boolean durableCheckpoint() {
        return durableCheckpoint;
    }

    /** @return whether the customer source transport is currently ready */
    public boolean sourceReady() {
        return probe(sourceReadiness);
    }

    /** @return whether source page and command trust roots are ready */
    public boolean authorityReady() {
        return probe(authorityReadiness);
    }

    /** @return whether one database-fenced worker turn can run */
    public boolean workerReady() {
        return probe(workerReadiness);
    }

    /** @return whether autonomous polling lanes are active */
    public boolean schedulerReady() {
        return probe(schedulerReadiness);
    }

    /** @return whether production facts can be continuously ingested */
    public boolean continuousReady() {
        return controlApi && durableCheckpoint && sourceReady()
                && authorityReady() && workerReady() && schedulerReady();
    }

    private static boolean probe(BooleanSupplier value) {
        try {
            return value.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
