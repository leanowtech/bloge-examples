package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;

import java.util.Objects;

/**
 * Profile-owned capability marker; absent from production application contexts.
 *
 * @param executionEndpointEnabled whether the isolated testing control plane is assembled
 * @param workerQuarantineRequestIndexMode exact request-index write/readiness mode of this replica
 */
public record TestabilityAvailability(
        boolean executionEndpointEnabled,
        WorkerQuarantineRequestIndexMode workerQuarantineRequestIndexMode) {

    /** Rejects an enabled marker that cannot report its exact migration mode. */
    public TestabilityAvailability {
        if (executionEndpointEnabled) {
            workerQuarantineRequestIndexMode = Objects.requireNonNull(
                    workerQuarantineRequestIndexMode,
                    "workerQuarantineRequestIndexMode");
        } else {
            workerQuarantineRequestIndexMode = null;
        }
    }

    /** Preserves the previous marker API with the established dual-read default. */
    public TestabilityAvailability(boolean executionEndpointEnabled) {
        this(executionEndpointEnabled, executionEndpointEnabled
                ? WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE : null);
    }
}
