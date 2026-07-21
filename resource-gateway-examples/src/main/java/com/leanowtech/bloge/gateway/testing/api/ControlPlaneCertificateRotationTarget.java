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

    /**
     * Preloads one successor with its externally authorized settings identity.
     *
     * <p>Status-aware transports override this method so two settings materials sharing a
     * generation cannot be confused. Compatibility implementations retain the original method.
     * </p>
     *
     * @param generation exact active generation plus one
     * @param activateAt deterministic activation instant
     * @param settings complete successor settings
     * @param settingsFingerprint signed path- and credential-free settings identity
     */
    default void stage(
            long generation,
            Instant activateAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings,
            String settingsFingerprint) {
        stage(generation, activateAt, settings);
    }

    /**
     * Atomically catches up one already-active durable successor.
     *
     * <p>Only a controller backed by a durable floor may invoke this operation. Implementations
     * must still require the exact next generation and fully validate candidate identity before
     * replacing the active request path.</p>
     *
     * @param generation exact active generation plus one
     * @param activatedAt signed activation instant already reached by the durable authority
     * @param settings complete successor settings
     */
    default void reconcileActive(
            long generation,
            Instant activatedAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings) {
        throw new UnsupportedOperationException(
                "Active certificate generation reconciliation is unsupported");
    }

    /**
     * Catches up an active successor with its externally authorized settings identity.
     *
     * @param generation exact active generation plus one
     * @param activatedAt signed activation instant already reached by the durable authority
     * @param settings complete successor settings
     * @param settingsFingerprint signed path- and credential-free settings identity
     */
    default void reconcileActive(
            long generation,
            Instant activatedAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings,
            String settingsFingerprint) {
        reconcileActive(generation, activatedAt, settings);
    }

    /**
     * Restores one successor already authorized and staged by the durable floor.
     *
     * @param generation exact active generation plus one
     * @param activateAt signed activation instant
     * @param settings complete successor settings
     */
    default void restorePending(
            long generation,
            Instant activateAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings) {
        throw new UnsupportedOperationException(
                "Pending certificate generation restoration is unsupported");
    }

    /**
     * Restores a pending successor with its externally authorized settings identity.
     *
     * @param generation exact active generation plus one
     * @param activateAt signed activation instant
     * @param settings complete successor settings
     * @param settingsFingerprint signed path- and credential-free settings identity
     */
    default void restorePending(
            long generation,
            Instant activateAt,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings,
            String settingsFingerprint) {
        restorePending(generation, activateAt, settings);
    }
}
