package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Payload-isolated connector for one governed read-only baseline observation.
 *
 * <p>Implementations must use {@link ReadOnlyShadowConnectorInvocation#executionId()} as the
 * source idempotency key, expose no write-capable credential, and return only payload-free
 * normalized facts.</p>
 */
public interface ReadOnlyShadowBaselineConnector {
    /** @return whether the exact baseline binding resolver and transport are currently ready */
    boolean ready();

    /**
     * Produces one independently signed baseline observation.
     *
     * @param invocation exact governed invocation coordinates
     * @return payload-free baseline result
     */
    ReadOnlyShadowConnectorObservation observe(
            ReadOnlyShadowConnectorInvocation invocation);

    /** Creates a fail-closed placeholder. */
    static ReadOnlyShadowBaselineConnector unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowBaselineConnector {
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
                            .BASELINE_SOURCE_UNAVAILABLE);
        }
    }
}
