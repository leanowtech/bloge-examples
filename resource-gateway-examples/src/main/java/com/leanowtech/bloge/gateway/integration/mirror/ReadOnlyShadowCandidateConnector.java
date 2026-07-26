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

    /**
     * Produces a candidate observation bound to the exact verified baseline source.
     *
     * <p>Detached connectors ignore the supplied baseline because their signed source binding
     * already closes the pair. Online connectors override this method to resolve the baseline
     * payload-vault receipt and prove that the sealed candidate consumed the same request
     * context.</p>
     *
     * @param invocation exact governed invocation coordinates
     * @param baseline exact verified baseline connector observation
     * @return payload-free candidate result
     */
    default ReadOnlyShadowConnectorObservation observePaired(
            ReadOnlyShadowConnectorInvocation invocation,
            ReadOnlyShadowConnectorObservation baseline) {
        Objects.requireNonNull(baseline, "baseline");
        return observe(invocation);
    }

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
