package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * Minimal atomic certificate-rotation target consumed by the signed control plane.
 *
 * <p>The small interface keeps event authorization and replay policy independent from HTTP/TLS
 * implementation details while preserving one indivisible staging operation.</p>
 */
public interface ControlPlaneCertificateRotationTarget {

    /** @return current active certificate generation after applying any due activation */
    long activeGeneration();

    /** @return staged successor generation, or empty when none is pending */
    OptionalLong pendingGeneration();

    /**
     * Atomically preloads one successor without changing the active request path.
     *
     * @param generation exact active generation plus one
     * @param activateAt deterministic activation instant
     * @param settings complete successor settings
     */
    void stage(
            long generation,
            Instant activateAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings);
}
