package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Payload-isolated connector for one exact sealed candidate Mirror plan.
 *
 * <p>The connector must resolve the exact candidate plan and target capability carried by the
 * durable request. It may not fall back to a live mutable draft or ordinary production runtime.</p>
 */
public interface ReadOnlyShadowCandidateConnector {
    /** @return whether the sealed candidate runtime and evidence producer are currently ready */
    boolean ready();

    /**
     * Produces one independently signed candidate observation.
     *
     * @param invocation exact governed invocation coordinates
     * @return payload-free candidate result
     */
    ReadOnlyShadowConnectorObservation observe(
            ReadOnlyShadowConnectorInvocation invocation);

    /** Creates a fail-closed placeholder. */
    static ReadOnlyShadowCandidateConnector unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowCandidateConnector {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public ReadOnlyShadowConnectorObservation observe(
                ReadOnlyShadowConnectorInvocation invocation) {
            Objects.requireNonNull(
                    invocation, "invocation");
            throw new ReadOnlyShadowDataPlane.Failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .CANDIDATE_RUNTIME_UNAVAILABLE);
        }
    }
}
