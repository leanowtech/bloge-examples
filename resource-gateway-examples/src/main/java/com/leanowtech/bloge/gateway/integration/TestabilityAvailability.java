package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobAuthorizer;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorizationTrustStore;

import java.util.Objects;

/**
 * Profile-owned capability marker; absent from production application contexts.
 *
 * @param executionEndpointEnabled whether the isolated testing control plane is assembled
 * @param suiteStabilityJobSubmissionEnabled whether fresh asynchronous stability jobs can run
 * @param workerQuarantineRequestIndexMode exact request-index write/readiness mode of this replica
 * @param workerQuarantineChangeAuthorizationTrust key-free external approval readiness
 * @param suiteStabilityCurrentAuthority key-free background reauthorization readiness
 */
public record TestabilityAvailability(
        boolean executionEndpointEnabled,
        boolean suiteStabilityJobSubmissionEnabled,
        WorkerQuarantineRequestIndexMode workerQuarantineRequestIndexMode,
        WorkerQuarantineChangeAuthorizationTrustStore.Descriptor
                workerQuarantineChangeAuthorizationTrust,
        TestSuiteStabilityJobAuthorizer.Descriptor suiteStabilityCurrentAuthority) {

    /** Rejects an enabled marker that cannot report its exact migration mode. */
    public TestabilityAvailability {
        if (suiteStabilityJobSubmissionEnabled && !executionEndpointEnabled) {
            throw new IllegalArgumentException(
                    "Stability-job submission requires the testing control plane");
        }
        if (executionEndpointEnabled) {
            workerQuarantineRequestIndexMode = Objects.requireNonNull(
                    workerQuarantineRequestIndexMode,
                    "workerQuarantineRequestIndexMode");
        } else {
            workerQuarantineRequestIndexMode = null;
        }
        workerQuarantineChangeAuthorizationTrust =
                workerQuarantineChangeAuthorizationTrust == null
                        ? WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor()
                        : workerQuarantineChangeAuthorizationTrust;
        suiteStabilityCurrentAuthority = suiteStabilityCurrentAuthority == null
                ? unavailableCurrentAuthority() : suiteStabilityCurrentAuthority;
        if (suiteStabilityJobSubmissionEnabled && !suiteStabilityCurrentAuthority.available()) {
            throw new IllegalArgumentException(
                    "Stability-job submission requires a ready current-authority provider");
        }
    }

    /** Preserves the previous marker shape with undeclared current-authority readiness. */
    public TestabilityAvailability(
            boolean executionEndpointEnabled,
            boolean suiteStabilityJobSubmissionEnabled,
            WorkerQuarantineRequestIndexMode workerQuarantineRequestIndexMode,
            WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust) {
        this(executionEndpointEnabled, suiteStabilityJobSubmissionEnabled,
                workerQuarantineRequestIndexMode, trust,
                suiteStabilityJobSubmissionEnabled ? customCurrentAuthority()
                        : unavailableCurrentAuthority());
    }

    /** Preserves the request-index marker API with unavailable external approval trust. */
    public TestabilityAvailability(
            boolean executionEndpointEnabled,
            WorkerQuarantineRequestIndexMode workerQuarantineRequestIndexMode) {
        this(executionEndpointEnabled, false, workerQuarantineRequestIndexMode,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                unavailableCurrentAuthority());
    }

    /** Preserves the previous marker shape while defaulting asynchronous submission to disabled. */
    public TestabilityAvailability(
            boolean executionEndpointEnabled,
            WorkerQuarantineRequestIndexMode workerQuarantineRequestIndexMode,
            WorkerQuarantineChangeAuthorizationTrustStore.Descriptor trust) {
        this(executionEndpointEnabled, false, workerQuarantineRequestIndexMode, trust);
    }

    /** Preserves the previous marker API with the established dual-read default. */
    public TestabilityAvailability(boolean executionEndpointEnabled) {
        this(executionEndpointEnabled, false, executionEndpointEnabled
                        ? WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE : null,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor());
    }

    private static TestSuiteStabilityJobAuthorizer.Descriptor unavailableCurrentAuthority() {
        return new TestSuiteStabilityJobAuthorizer.Descriptor(
                "", false, "UNAVAILABLE", "", java.util.Map.of());
    }

    private static TestSuiteStabilityJobAuthorizer.Descriptor customCurrentAuthority() {
        return new TestSuiteStabilityJobAuthorizer.Descriptor(
                "", true, "CUSTOM_UNDECLARED", "", java.util.Map.of(
                "signedDecisions", false,
                "challengeBound", false,
                "privateMaterialPresent", false));
    }
}
