package com.leanowtech.bloge.gateway.integration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Deployment-owned capability marker for the runtime-certification harness.
 *
 * <p>Protocol support is always advertised separately. This marker exists only when the
 * deployment has deliberately assembled the plan or destructive execution surface and supplies
 * current probes for the customer Adapter, journal, and independent signing authorities.</p>
 */
public final class RuntimeCertificationRuntimeAvailability {
    private final boolean planSurface;
    private final boolean executionSurface;
    private final BooleanSupplier adapterReadiness;
    private final BooleanSupplier journalReadiness;
    private final BooleanSupplier authorizationAuthorityReadiness;
    private final BooleanSupplier reportAuthorityReadiness;

    /** Creates one explicit deployment assembly marker. */
    public RuntimeCertificationRuntimeAvailability(
            boolean planSurface,
            boolean executionSurface,
            BooleanSupplier adapterReadiness,
            BooleanSupplier journalReadiness,
            BooleanSupplier authorizationAuthorityReadiness,
            BooleanSupplier reportAuthorityReadiness) {
        this.planSurface = planSurface;
        this.executionSurface = executionSurface;
        this.adapterReadiness = Objects.requireNonNull(
                adapterReadiness, "adapterReadiness");
        this.journalReadiness = Objects.requireNonNull(journalReadiness, "journalReadiness");
        this.authorizationAuthorityReadiness = Objects.requireNonNull(
                authorizationAuthorityReadiness, "authorizationAuthorityReadiness");
        this.reportAuthorityReadiness = Objects.requireNonNull(
                reportAuthorityReadiness, "reportAuthorityReadiness");
    }

    /** @return fail-closed marker for a deployment with no installed harness */
    public static RuntimeCertificationRuntimeAvailability unavailable() {
        return new RuntimeCertificationRuntimeAvailability(
                false, false, () -> false, () -> false, () -> false, () -> false);
    }

    /** @return whether plan-only preflight can query a current customer Adapter */
    public boolean planReady() {
        return planSurface && probe(adapterReadiness);
    }

    /** @return whether the database-backed single-use journal is currently ready */
    public boolean journalReady() {
        return executionSurface && probe(journalReadiness);
    }

    /** @return whether all local destructive-execution dependencies are currently ready */
    public boolean executionReady() {
        return executionSurface && planReady() && journalReady()
                && probe(authorizationAuthorityReadiness) && probe(reportAuthorityReadiness);
    }

    private static boolean probe(BooleanSupplier value) {
        try {
            return value.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
