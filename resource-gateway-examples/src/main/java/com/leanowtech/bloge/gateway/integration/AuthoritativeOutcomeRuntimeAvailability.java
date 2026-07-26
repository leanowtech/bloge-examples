package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Independent capability marker for the authoritative outcome reconciliation surface.
 *
 * <p>Protected routes, lifecycle audit, connector readiness, worker readiness, and autonomous
 * scheduling are separate facts. Continuous calibration is advertised only when all required
 * layers are currently ready.</p>
 */
public final class AuthoritativeOutcomeRuntimeAvailability {
    private final boolean inboxApi;
    private final boolean lifecycleAudit;
    private final BooleanSupplier connectorReadiness;
    private final BooleanSupplier workerReadiness;
    private final BooleanSupplier schedulerReadiness;

    /** Creates one profile-owned runtime marker. */
    public AuthoritativeOutcomeRuntimeAvailability(
            boolean inboxApi,
            boolean lifecycleAudit,
            BooleanSupplier connectorReadiness,
            BooleanSupplier workerReadiness,
            BooleanSupplier schedulerReadiness) {
        this.inboxApi = inboxApi;
        this.lifecycleAudit = lifecycleAudit;
        this.connectorReadiness = Objects.requireNonNull(
                connectorReadiness, "connectorReadiness");
        this.workerReadiness = Objects.requireNonNull(
                workerReadiness, "workerReadiness");
        this.schedulerReadiness = Objects.requireNonNull(
                schedulerReadiness, "schedulerReadiness");
    }

    /** @return whether protected append and read routes are assembled */
    public boolean inboxApi() {
        return inboxApi;
    }

    /** @return whether append-only lifecycle reads are assembled */
    public boolean lifecycleAudit() {
        return lifecycleAudit;
    }

    /** @return whether the customer-owned authority connector is currently ready */
    public boolean connectorReady() {
        return probe(connectorReadiness);
    }

    /** @return whether the fenced reconciliation worker and trust boundaries are ready */
    public boolean workerReady() {
        return probe(workerReadiness);
    }

    /** @return whether autonomous bounded polling is currently active */
    public boolean schedulerReady() {
        return probe(schedulerReadiness);
    }

    /** @return whether observations can be continuously admitted, reconciled, and audited */
    public boolean continuousReady() {
        return inboxApi
                && lifecycleAudit
                && connectorReady()
                && workerReady()
                && schedulerReady();
    }

    private static boolean probe(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
