package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Profile-owned capability marker for the protected mirror HTTP surface.
 *
 * <p>The marker is created only by the isolated test/staging composition root. Capability probes
 * consume it instead of inferring readiness from configuration text or classpath presence. Route
 * assembly and time-sensitive serving readiness are deliberately reported separately.</p>
 */
public final class MirrorRuntimeAvailability {
    private final boolean planCompilationApi;
    private final boolean executionApi;
    private final BooleanSupplier executionReadiness;
    private final boolean authorityDistributionApi;
    private final BooleanSupplier authorityDistributionReadiness;

    /** Creates a marker with static readiness, primarily for disabled composition and tests. */
    public MirrorRuntimeAvailability(boolean planCompilationApi, boolean executionApi) {
        this(planCompilationApi, executionApi, () -> executionApi, false, () -> false);
    }

    /**
     * Creates a marker that rechecks time-sensitive execution dependencies for every probe.
     *
     * @param planCompilationApi protected plan compile/read routes are assembled
     * @param executionApi protected run/evidence routes are assembled
     * @param executionReadiness dynamic run/evidence and signing-authority readiness
     */
    public MirrorRuntimeAvailability(
            boolean planCompilationApi,
            boolean executionApi,
            BooleanSupplier executionReadiness) {
        this(planCompilationApi, executionApi, executionReadiness, false, () -> false);
    }

    /**
     * Creates a marker that independently probes execution and authority-distribution readiness.
     *
     * @param planCompilationApi protected plan compile/read routes are assembled
     * @param executionApi protected run/evidence routes are assembled
     * @param executionReadiness dynamic run/evidence and signing-authority readiness
     * @param authorityDistributionApi protected authority publication routes are assembled
     * @param authorityDistributionReadiness dynamic local trust-policy readiness
     */
    public MirrorRuntimeAvailability(
            boolean planCompilationApi,
            boolean executionApi,
            BooleanSupplier executionReadiness,
            boolean authorityDistributionApi,
            BooleanSupplier authorityDistributionReadiness) {
        this.planCompilationApi = planCompilationApi;
        this.executionApi = executionApi;
        this.executionReadiness = Objects.requireNonNull(
                executionReadiness, "executionReadiness");
        this.authorityDistributionApi = authorityDistributionApi;
        this.authorityDistributionReadiness = Objects.requireNonNull(
                authorityDistributionReadiness, "authorityDistributionReadiness");
    }

    /** @return whether protected plan compile/read routes are physically assembled */
    public boolean planCompilationApi() {
        return planCompilationApi;
    }

    /** @return whether protected run/evidence routes are physically assembled */
    public boolean executionApi() {
        return executionApi;
    }

    /** @return whether the assembled execution route and signing chain are currently usable */
    public boolean executionReady() {
        if (!executionApi) {
            return false;
        }
        try {
            return executionReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** @return whether protected authority publish/read routes are physically assembled */
    public boolean authorityDistributionApi() {
        return authorityDistributionApi;
    }

    /** @return whether assembled authority routes have a usable local trust-policy source */
    public boolean authorityDistributionReady() {
        if (!authorityDistributionApi) {
            return false;
        }
        try {
            return authorityDistributionReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
