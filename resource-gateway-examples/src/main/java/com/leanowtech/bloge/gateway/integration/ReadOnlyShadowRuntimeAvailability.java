package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Independent readiness marker for the durable read-only Shadow control and data planes.
 *
 * <p>Route assembly, lifecycle durability, execution authority, and autonomous scheduling are
 * separate facts. This prevents an installed API or idle durable queue from advertising that
 * production reads are currently permitted.</p>
 */
public final class ReadOnlyShadowRuntimeAvailability {
    private final boolean jobApi;
    private final boolean lifecycleAudit;
    private final BooleanSupplier workerReadiness;
    private final BooleanSupplier schedulerReadiness;

    /**
     * Creates one dynamically probed Shadow runtime marker.
     *
     * @param jobApi protected submit/read/evidence routes are assembled
     * @param lifecycleAudit append-only lifecycle publication is assembled
     * @param workerReadiness dynamic signer and trusted data-plane readiness
     * @param schedulerReadiness dynamic autonomous regional scheduler readiness
     */
    public ReadOnlyShadowRuntimeAvailability(
            boolean jobApi,
            boolean lifecycleAudit,
            BooleanSupplier workerReadiness,
            BooleanSupplier schedulerReadiness) {
        this.jobApi = jobApi;
        this.lifecycleAudit = lifecycleAudit;
        this.workerReadiness = Objects.requireNonNull(
                workerReadiness, "workerReadiness");
        this.schedulerReadiness = Objects.requireNonNull(
                schedulerReadiness, "schedulerReadiness");
    }

    /** @return whether the protected durable job routes are physically assembled */
    public boolean jobApi() {
        return jobApi;
    }

    /** @return whether committed state transitions are durably journaled and readable */
    public boolean lifecycleAudit() {
        return lifecycleAudit;
    }

    /** @return whether managed signing and the trusted data plane can execute new work */
    public boolean workerReady() {
        return probe(workerReadiness);
    }

    /** @return whether autonomous bounded polling is active */
    public boolean schedulerReady() {
        return probe(schedulerReadiness);
    }

    /** @return whether the complete autonomous protected Shadow path is currently usable */
    public boolean servingReady() {
        return jobApi
                && lifecycleAudit
                && workerReady()
                && schedulerReady();
    }

    private static boolean probe(
            BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
