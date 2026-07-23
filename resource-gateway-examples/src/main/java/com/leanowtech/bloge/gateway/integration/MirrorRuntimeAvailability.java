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
    private final boolean attestationDistributionApi;
    private final BooleanSupplier attestationDistributionReadiness;
    private final BooleanSupplier certificationReadiness;
    private final boolean observationAdmissionApi;
    private final BooleanSupplier observationAdmissionReadiness;
    private final boolean corpusGovernanceApi;
    private final BooleanSupplier corpusGovernanceReadiness;

    /**
     * Creates a marker with static readiness, primarily for disabled composition and tests.
     *
     * @param planCompilationApi protected plan compile/read routes are assembled
     * @param executionApi protected run/evidence routes are assembled and statically ready
     */
    public MirrorRuntimeAvailability(boolean planCompilationApi, boolean executionApi) {
        this(planCompilationApi, executionApi, () -> executionApi,
                false, () -> false, false, () -> false, () -> false,
                false, () -> false, false, () -> false);
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
        this(planCompilationApi, executionApi, executionReadiness,
                false, () -> false, false, () -> false, () -> false,
                false, () -> false, false, () -> false);
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
        this(planCompilationApi, executionApi, executionReadiness,
                authorityDistributionApi, authorityDistributionReadiness,
                false, () -> false, () -> false, false, () -> false,
                false, () -> false);
    }

    /**
     * Creates a marker with independently probed authority and attestation distribution paths.
     *
     * @param planCompilationApi protected plan compile/read routes are assembled
     * @param executionApi protected run/evidence routes are assembled
     * @param executionReadiness dynamic run/evidence and signing-authority readiness
     * @param authorityDistributionApi protected authority publication routes are assembled
     * @param authorityDistributionReadiness dynamic local authority trust readiness
     * @param attestationDistributionApi protected attestation ingest/read/revoke routes are assembled
     * @param attestationDistributionReadiness dynamic authority and bootstrap-policy readiness
     */
    public MirrorRuntimeAvailability(
            boolean planCompilationApi,
            boolean executionApi,
            BooleanSupplier executionReadiness,
            boolean authorityDistributionApi,
            BooleanSupplier authorityDistributionReadiness,
            boolean attestationDistributionApi,
            BooleanSupplier attestationDistributionReadiness) {
        this(planCompilationApi, executionApi, executionReadiness,
                authorityDistributionApi, authorityDistributionReadiness,
                attestationDistributionApi, attestationDistributionReadiness,
                () -> false, false, () -> false, false, () -> false);
    }

    /**
     * Creates a marker that also probes certification-grade run-trust readiness.
     *
     * @param planCompilationApi protected plan compile/read routes are assembled
     * @param executionApi protected run/evidence routes are assembled
     * @param executionReadiness dynamic run/evidence and signing-authority readiness
     * @param authorityDistributionApi protected authority publication routes are assembled
     * @param authorityDistributionReadiness dynamic local authority trust readiness
     * @param attestationDistributionApi protected attestation ingest/read/revoke routes are assembled
     * @param attestationDistributionReadiness dynamic authority and bootstrap-policy readiness
     * @param certificationReadiness dynamic deployment-agent run-trust readiness
     */
    public MirrorRuntimeAvailability(
            boolean planCompilationApi,
            boolean executionApi,
            BooleanSupplier executionReadiness,
            boolean authorityDistributionApi,
            BooleanSupplier authorityDistributionReadiness,
            boolean attestationDistributionApi,
            BooleanSupplier attestationDistributionReadiness,
            BooleanSupplier certificationReadiness) {
        this(planCompilationApi, executionApi, executionReadiness,
                authorityDistributionApi, authorityDistributionReadiness,
                attestationDistributionApi, attestationDistributionReadiness,
                certificationReadiness, false, () -> false,
                false, () -> false);
    }

    /**
     * Creates a marker that also probes the observation-admission ingress.
     *
     * @param planCompilationApi protected plan compile/read routes are assembled
     * @param executionApi protected run/evidence routes are assembled
     * @param executionReadiness dynamic run/evidence and signing-authority readiness
     * @param authorityDistributionApi protected authority publication routes are assembled
     * @param authorityDistributionReadiness dynamic local authority trust readiness
     * @param attestationDistributionApi protected attestation ingest/read/revoke routes are assembled
     * @param attestationDistributionReadiness dynamic authority and bootstrap-policy readiness
     * @param certificationReadiness dynamic deployment-agent run-trust readiness
     * @param observationAdmissionApi protected observation ingest route is assembled
     * @param observationAdmissionReadiness dynamic policy and payload-reference authority readiness
     */
    public MirrorRuntimeAvailability(
            boolean planCompilationApi,
            boolean executionApi,
            BooleanSupplier executionReadiness,
            boolean authorityDistributionApi,
            BooleanSupplier authorityDistributionReadiness,
            boolean attestationDistributionApi,
            BooleanSupplier attestationDistributionReadiness,
            BooleanSupplier certificationReadiness,
            boolean observationAdmissionApi,
            BooleanSupplier observationAdmissionReadiness) {
        this(planCompilationApi, executionApi, executionReadiness,
                authorityDistributionApi, authorityDistributionReadiness,
                attestationDistributionApi, attestationDistributionReadiness,
                certificationReadiness, observationAdmissionApi,
                observationAdmissionReadiness, false, () -> false);
    }

    /**
     * Creates a marker that also probes governed corpus review and publication.
     *
     * @param planCompilationApi protected plan compile/read routes are assembled
     * @param executionApi protected run/evidence routes are assembled
     * @param executionReadiness dynamic run/evidence and signing-authority readiness
     * @param authorityDistributionApi protected authority publication routes are assembled
     * @param authorityDistributionReadiness dynamic local authority trust readiness
     * @param attestationDistributionApi protected attestation ingest/read/revoke routes are assembled
     * @param attestationDistributionReadiness dynamic authority and bootstrap-policy readiness
     * @param certificationReadiness dynamic deployment-agent run-trust readiness
     * @param observationAdmissionApi protected observation ingest route is assembled
     * @param observationAdmissionReadiness dynamic observation admission readiness
     * @param corpusGovernanceApi protected review/candidate/publication routes are assembled
     * @param corpusGovernanceReadiness dynamic policy and external source-authority readiness
     */
    public MirrorRuntimeAvailability(
            boolean planCompilationApi,
            boolean executionApi,
            BooleanSupplier executionReadiness,
            boolean authorityDistributionApi,
            BooleanSupplier authorityDistributionReadiness,
            boolean attestationDistributionApi,
            BooleanSupplier attestationDistributionReadiness,
            BooleanSupplier certificationReadiness,
            boolean observationAdmissionApi,
            BooleanSupplier observationAdmissionReadiness,
            boolean corpusGovernanceApi,
            BooleanSupplier corpusGovernanceReadiness) {
        this.planCompilationApi = planCompilationApi;
        this.executionApi = executionApi;
        this.executionReadiness = Objects.requireNonNull(
                executionReadiness, "executionReadiness");
        this.authorityDistributionApi = authorityDistributionApi;
        this.authorityDistributionReadiness = Objects.requireNonNull(
                authorityDistributionReadiness, "authorityDistributionReadiness");
        this.attestationDistributionApi = attestationDistributionApi;
        this.attestationDistributionReadiness = Objects.requireNonNull(
                attestationDistributionReadiness, "attestationDistributionReadiness");
        this.certificationReadiness = Objects.requireNonNull(
                certificationReadiness, "certificationReadiness");
        this.observationAdmissionApi = observationAdmissionApi;
        this.observationAdmissionReadiness = Objects.requireNonNull(
                observationAdmissionReadiness, "observationAdmissionReadiness");
        this.corpusGovernanceApi = corpusGovernanceApi;
        this.corpusGovernanceReadiness = Objects.requireNonNull(
                corpusGovernanceReadiness, "corpusGovernanceReadiness");
    }

    /**
     * Reports protected plan-route assembly.
     *
     * @return whether protected plan compile/read routes are physically assembled
     */
    public boolean planCompilationApi() {
        return planCompilationApi;
    }

    /**
     * Reports protected execution-route assembly.
     *
     * @return whether protected run/evidence routes are physically assembled
     */
    public boolean executionApi() {
        return executionApi;
    }

    /**
     * Probes current execution and signer readiness without propagating provider failure.
     *
     * @return whether the assembled execution route and signing chain are currently usable
     */
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

    /**
     * Reports protected authority-route assembly.
     *
     * @return whether protected authority publish/read routes are physically assembled
     */
    public boolean authorityDistributionApi() {
        return authorityDistributionApi;
    }

    /**
     * Probes current authority-distribution readiness without propagating provider failure.
     *
     * @return whether assembled authority routes have a usable local trust-policy source
     */
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

    /**
     * Reports protected attestation-route assembly.
     *
     * @return whether protected attestation ingest/read/revoke routes are physically assembled
     */
    public boolean attestationDistributionApi() {
        return attestationDistributionApi;
    }

    /**
     * Probes current attestation-distribution readiness without propagating provider failure.
     *
     * @return whether attestation routes have current authority and bootstrap-policy readiness
     */
    public boolean attestationDistributionReady() {
        if (!attestationDistributionApi) {
            return false;
        }
        try {
            return attestationDistributionReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Probes whether certification-required runs can currently obtain deployment trust.
     *
     * @return whether the execution route has an active deployment-agent trust decision
     */
    public boolean certificationReady() {
        if (!executionApi) {
            return false;
        }
        try {
            return certificationReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Reports protected observation-ingest route assembly.
     *
     * @return whether the signed observation admission route is physically assembled
     */
    public boolean observationAdmissionApi() {
        return observationAdmissionApi;
    }

    /**
     * Probes current observation policy and external reference-verification readiness.
     *
     * @return whether the assembled observation route can currently make admission decisions
     */
    public boolean observationAdmissionReady() {
        if (!observationAdmissionApi) {
            return false;
        }
        try {
            return observationAdmissionReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Reports protected corpus-governance route assembly.
     *
     * @return whether review, candidate, and publication routes are physically assembled
     */
    public boolean corpusGovernanceApi() {
        return corpusGovernanceApi;
    }

    /**
     * Probes current corpus policy and external source-authority readiness.
     *
     * @return whether assembled corpus routes can currently make governance decisions
     */
    public boolean corpusGovernanceReady() {
        if (!corpusGovernanceApi) {
            return false;
        }
        try {
            return corpusGovernanceReadiness.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
