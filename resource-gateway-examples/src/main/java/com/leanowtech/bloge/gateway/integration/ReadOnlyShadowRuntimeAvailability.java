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
    private final boolean authorityTrustDistributionApi;
    private final BooleanSupplier authorityTrustDistributionReadiness;
    private final boolean sourceBindingApi;
    private final BooleanSupplier sourceBindingReadiness;
    private final boolean sourceResolutionApi;
    private final BooleanSupplier detachedDataPlaneReadiness;

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
        this(jobApi, lifecycleAudit, workerReadiness, schedulerReadiness,
                false, () -> false, false, () -> false,
                false, () -> false);
    }

    /**
     * Creates a marker that also probes the managed Shadow authority trust-distribution path.
     *
     * @param jobApi protected submit/read/evidence routes are assembled
     * @param lifecycleAudit append-only lifecycle publication is assembled
     * @param workerReadiness dynamic signer and trusted data-plane readiness
     * @param schedulerReadiness dynamic autonomous regional scheduler readiness
     * @param authorityTrustDistributionApi protected authority publish/page routes are assembled
     * @param authorityTrustDistributionReadiness dynamic bootstrap-root policy readiness
     */
    public ReadOnlyShadowRuntimeAvailability(
            boolean jobApi,
            boolean lifecycleAudit,
            BooleanSupplier workerReadiness,
            BooleanSupplier schedulerReadiness,
            boolean authorityTrustDistributionApi,
            BooleanSupplier authorityTrustDistributionReadiness) {
        this(jobApi, lifecycleAudit, workerReadiness, schedulerReadiness,
                authorityTrustDistributionApi,
                authorityTrustDistributionReadiness,
                false, () -> false,
                false, () -> false);
    }

    /**
     * Creates a marker covering trust distribution and detached source-binding admission.
     *
     * @param jobApi protected submit/read/evidence routes are assembled
     * @param lifecycleAudit append-only lifecycle publication is assembled
     * @param workerReadiness dynamic signer and trusted data-plane readiness
     * @param schedulerReadiness dynamic autonomous regional scheduler readiness
     * @param authorityTrustDistributionApi protected authority routes are assembled
     * @param authorityTrustDistributionReadiness dynamic bootstrap-root policy readiness
     * @param sourceBindingApi protected detached source-binding routes are assembled
     * @param sourceBindingReadiness dynamic binding signer and candidate resolver readiness
     */
    public ReadOnlyShadowRuntimeAvailability(
            boolean jobApi,
            boolean lifecycleAudit,
            BooleanSupplier workerReadiness,
            BooleanSupplier schedulerReadiness,
            boolean authorityTrustDistributionApi,
            BooleanSupplier authorityTrustDistributionReadiness,
            boolean sourceBindingApi,
            BooleanSupplier sourceBindingReadiness) {
        this(jobApi, lifecycleAudit, workerReadiness,
                schedulerReadiness,
                authorityTrustDistributionApi,
                authorityTrustDistributionReadiness,
                sourceBindingApi,
                sourceBindingReadiness,
                false,
                () -> false);
    }

    /**
     * Creates the complete marker including exact source-resolution evidence and data plane.
     *
     * @param jobApi protected submit/read/evidence routes are assembled
     * @param lifecycleAudit append-only lifecycle publication is assembled
     * @param workerReadiness dynamic signer and trusted data-plane readiness
     * @param schedulerReadiness dynamic autonomous regional scheduler readiness
     * @param authorityTrustDistributionApi protected authority routes are assembled
     * @param authorityTrustDistributionReadiness dynamic bootstrap-root policy readiness
     * @param sourceBindingApi protected detached source-binding routes are assembled
     * @param sourceBindingReadiness dynamic binding signer and candidate resolver readiness
     * @param sourceResolutionApi protected exact source-resolution read route is assembled
     * @param detachedDataPlaneReadiness dynamic complete detached connector readiness
     */
    public ReadOnlyShadowRuntimeAvailability(
            boolean jobApi,
            boolean lifecycleAudit,
            BooleanSupplier workerReadiness,
            BooleanSupplier schedulerReadiness,
            boolean authorityTrustDistributionApi,
            BooleanSupplier authorityTrustDistributionReadiness,
            boolean sourceBindingApi,
            BooleanSupplier sourceBindingReadiness,
            boolean sourceResolutionApi,
            BooleanSupplier detachedDataPlaneReadiness) {
        this.jobApi = jobApi;
        this.lifecycleAudit = lifecycleAudit;
        this.workerReadiness = Objects.requireNonNull(
                workerReadiness, "workerReadiness");
        this.schedulerReadiness = Objects.requireNonNull(
                schedulerReadiness, "schedulerReadiness");
        this.authorityTrustDistributionApi =
                authorityTrustDistributionApi;
        this.authorityTrustDistributionReadiness =
                Objects.requireNonNull(
                        authorityTrustDistributionReadiness,
                        "authorityTrustDistributionReadiness");
        this.sourceBindingApi = sourceBindingApi;
        this.sourceBindingReadiness =
                Objects.requireNonNull(
                        sourceBindingReadiness,
                        "sourceBindingReadiness");
        this.sourceResolutionApi = sourceResolutionApi;
        this.detachedDataPlaneReadiness =
                Objects.requireNonNull(
                        detachedDataPlaneReadiness,
                        "detachedDataPlaneReadiness");
    }

    /**
     * Reports protected durable job API assembly.
     *
     * @return whether the protected durable job routes are physically assembled
     */
    public boolean jobApi() {
        return jobApi;
    }

    /**
     * Reports durable lifecycle audit availability.
     *
     * @return whether committed state transitions are durably journaled and readable
     */
    public boolean lifecycleAudit() {
        return lifecycleAudit;
    }

    /**
     * Probes worker readiness without allowing probe failures to escape.
     *
     * @return whether managed signing and the trusted data plane can execute new work
     */
    public boolean workerReady() {
        return probe(workerReadiness);
    }

    /**
     * Probes autonomous scheduler readiness.
     *
     * @return whether autonomous bounded polling is active
     */
    public boolean schedulerReady() {
        return probe(schedulerReadiness);
    }

    /**
     * Reports authority trust-distribution API assembly.
     *
     * @return whether protected authority publish/page routes are physically assembled
     */
    public boolean authorityTrustDistributionApi() {
        return authorityTrustDistributionApi;
    }

    /**
     * Probes local trust-distribution readiness.
     *
     * @return whether local bootstrap-root policy can verify authority key-set publications
     */
    public boolean authorityTrustDistributionReady() {
        return authorityTrustDistributionApi
                && probe(authorityTrustDistributionReadiness);
    }

    /**
     * Reports detached source-binding API assembly.
     *
     * @return whether protected detached source-binding routes are physically assembled
     */
    public boolean sourceBindingApi() {
        return sourceBindingApi;
    }

    /**
     * Probes detached source-binding readiness.
     *
     * @return whether source-binding signing and exact candidate resolution are usable
     */
    public boolean sourceBindingReady() {
        return sourceBindingApi
                && probe(sourceBindingReadiness);
    }

    /**
     * Reports exact source-resolution attestation API assembly.
     *
     * @return whether protected exact source-resolution reads are assembled
     */
    public boolean sourceResolutionApi() {
        return sourceResolutionApi;
    }

    /**
     * Probes the complete detached binding, connector, verifier, and policy path.
     *
     * @return whether exact detached evidence can currently produce a comparison
     */
    public boolean detachedDataPlaneReady() {
        return sourceResolutionApi
                && probe(detachedDataPlaneReadiness);
    }

    /**
     * Combines the mandatory autonomous protected Shadow readiness signals.
     *
     * @return whether the complete autonomous protected Shadow path is currently usable
     */
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
