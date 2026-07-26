package com.leanowtech.bloge.gateway.integration;

import java.util.function.BooleanSupplier;

/**
 * Fail-closed live readiness projection for the online candidate and paired-source data plane.
 *
 * <p>Installation, candidate execution authority, candidate evidence verification, paired
 * resolver authority, and aggregate data-plane readiness are sampled as separate facts. A caller
 * can therefore distinguish a missing adapter from a transient authority outage and from an
 * incomplete end-to-end composition.</p>
 */
public final class OnlineReadOnlyShadowDataPlaneRuntimeAvailability {
    private final boolean candidateConnectorInstalled;
    private final boolean pairedResolverInstalled;
    private final BooleanSupplier candidateAuthorityReady;
    private final BooleanSupplier candidateEvidenceReady;
    private final BooleanSupplier pairedResolverReady;
    private final BooleanSupplier dataPlaneReady;

    /**
     * Creates one dynamically sampled online data-plane marker.
     *
     * @param candidateConnectorInstalled whether the same-input candidate connector is assembled
     * @param pairedResolverInstalled whether the online exact-read resolver is assembled
     * @param candidateAuthorityReady live isolated candidate authority probe
     * @param candidateEvidenceReady live Mirror evidence verification probe
     * @param pairedResolverReady live source-resolution authority probe
     * @param dataPlaneReady aggregate governed data-plane probe
     */
    public OnlineReadOnlyShadowDataPlaneRuntimeAvailability(
            boolean candidateConnectorInstalled,
            boolean pairedResolverInstalled,
            BooleanSupplier candidateAuthorityReady,
            BooleanSupplier candidateEvidenceReady,
            BooleanSupplier pairedResolverReady,
            BooleanSupplier dataPlaneReady) {
        this.candidateConnectorInstalled =
                candidateConnectorInstalled;
        this.pairedResolverInstalled =
                pairedResolverInstalled;
        this.candidateAuthorityReady =
                candidateAuthorityReady == null
                        ? () -> false
                        : candidateAuthorityReady;
        this.candidateEvidenceReady =
                candidateEvidenceReady == null
                        ? () -> false
                        : candidateEvidenceReady;
        this.pairedResolverReady =
                pairedResolverReady == null
                        ? () -> false
                        : pairedResolverReady;
        this.dataPlaneReady =
                dataPlaneReady == null
                        ? () -> false
                        : dataPlaneReady;
    }

    /**
     * Samples each externally supplied readiness probe once and derives closed conjunctions.
     *
     * @return one internally consistent fail-closed snapshot
     */
    public Snapshot snapshot() {
        boolean authority =
                safe(candidateAuthorityReady);
        boolean evidence =
                safe(candidateEvidenceReady);
        boolean resolver =
                safe(pairedResolverReady);
        boolean aggregate =
                safe(dataPlaneReady);
        boolean candidate =
                candidateConnectorInstalled
                        && authority
                        && evidence;
        boolean paired =
                pairedResolverInstalled
                        && resolver;
        return new Snapshot(
                candidateConnectorInstalled,
                pairedResolverInstalled,
                authority,
                evidence,
                candidate,
                paired,
                candidate
                        && paired
                        && aggregate);
    }

    private static boolean safe(
            BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * One bounded online candidate and paired-source readiness sample.
     *
     * @param candidateConnectorInstalled candidate connector bean exists
     * @param pairedResolverInstalled online paired resolver bean exists
     * @param candidateAuthorityReady isolated candidate authority is live
     * @param candidateEvidenceVerificationReady Mirror evidence can be independently verified
     * @param candidateReady complete candidate connector dependencies are live
     * @param pairedResolverReady source-resolution signing authority is live
     * @param dataPlaneReady candidate, resolver, and governed data plane are all live
     */
    public record Snapshot(
            boolean candidateConnectorInstalled,
            boolean pairedResolverInstalled,
            boolean candidateAuthorityReady,
            boolean candidateEvidenceVerificationReady,
            boolean candidateReady,
            boolean pairedResolverReady,
            boolean dataPlaneReady
    ) {
    }
}
