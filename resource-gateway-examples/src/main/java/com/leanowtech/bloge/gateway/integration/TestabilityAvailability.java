package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorizationTrustStore;

import java.util.Objects;

/**
 * Profile-owned capability marker; absent from production application contexts.
 *
 * @param executionEndpointEnabled whether the isolated testing control plane is assembled
 * @param workerQuarantineRequestIndexMode exact request-index write/readiness mode of this replica
 * @param workerQuarantineChangeAuthorizationTrust key-free external approval readiness
 */
public record TestabilityAvailability(
        boolean executionEndpointEnabled,
        WorkerQuarantineRequestIndexMode workerQuarantineRequestIndexMode,
        WorkerQuarantineChangeAuthorizationTrustStore.Descriptor
                workerQuarantineChangeAuthorizationTrust) {

    /** Rejects an enabled marker that cannot report its exact migration mode. */
    public TestabilityAvailability {
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
    }

    /** Preserves the request-index marker API with unavailable external approval trust. */
    public TestabilityAvailability(
            boolean executionEndpointEnabled,
            WorkerQuarantineRequestIndexMode workerQuarantineRequestIndexMode) {
        this(executionEndpointEnabled, workerQuarantineRequestIndexMode,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor());
    }

    /** Preserves the previous marker API with the established dual-read default. */
    public TestabilityAvailability(boolean executionEndpointEnabled) {
        this(executionEndpointEnabled, executionEndpointEnabled
                        ? WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE : null,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor());
    }
}
