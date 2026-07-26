package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Independent readiness projection for the regional online Shadow baseline connector.
 *
 * <p>Protocol support, physical connector assembly, regional sidecar readiness, and observation
 * verification are separate facts. The complete online Shadow data plane must remain unavailable
 * until a candidate connector and paired-source resolver are also assembled.</p>
 */
public final class OnlineReadOnlyShadowBaselineRuntimeAvailability {
    private final boolean connectorInstalled;
    private final BooleanSupplier authorityReadiness;
    private final BooleanSupplier evidenceVerificationReadiness;

    /**
     * Creates one dynamically probed online baseline projection.
     *
     * @param connectorInstalled whether the online baseline connector is physically assembled
     * @param authorityReadiness fresh regional sidecar safety-capability readiness
     * @param evidenceVerificationReadiness independent observation trust readiness
     */
    public OnlineReadOnlyShadowBaselineRuntimeAvailability(
            boolean connectorInstalled,
            BooleanSupplier authorityReadiness,
            BooleanSupplier evidenceVerificationReadiness) {
        this.connectorInstalled = connectorInstalled;
        this.authorityReadiness = Objects.requireNonNull(
                authorityReadiness, "authorityReadiness");
        this.evidenceVerificationReadiness = Objects.requireNonNull(
                evidenceVerificationReadiness,
                "evidenceVerificationReadiness");
    }

    /**
     * Samples every dynamic dependency exactly once and fails closed on provider errors.
     *
     * @return one internally consistent capability-request snapshot
     */
    public Snapshot snapshot() {
        boolean authorityReady = connectorInstalled
                && probe(authorityReadiness);
        boolean evidenceReady = connectorInstalled
                && probe(evidenceVerificationReadiness);
        return new Snapshot(
                connectorInstalled,
                authorityReady,
                evidenceReady,
                connectorInstalled
                        && authorityReady
                        && evidenceReady);
    }

    /**
     * One immutable online baseline readiness sample.
     *
     * @param connectorInstalled physical connector assembly
     * @param authorityReady fresh regional sidecar safety readiness
     * @param evidenceVerificationReady observation trust readiness
     * @param baselineReady complete baseline-only readiness
     */
    public record Snapshot(
            boolean connectorInstalled,
            boolean authorityReady,
            boolean evidenceVerificationReady,
            boolean baselineReady
    ) {
    }

    private static boolean probe(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
