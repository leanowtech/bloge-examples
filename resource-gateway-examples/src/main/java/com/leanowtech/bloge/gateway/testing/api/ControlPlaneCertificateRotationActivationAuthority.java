package com.leanowtech.bloge.gateway.testing.api;

/**
 * Non-blocking authority for advancing a durable certificate generation floor.
 *
 * <p>The database floor invokes this authority while holding its target transaction lock. An
 * implementation must therefore consult only locally cached convergence state and must never call
 * a database, network service, secret resolver or other blocking provider. The same authority is
 * expected to back the live transport gate so durable and in-memory activation cannot diverge.</p>
 */
@FunctionalInterface
public interface ControlPlaneCertificateRotationActivationAuthority {

    /**
     * Decides whether one exact, already due rotation may become durably active.
     *
     * @param rotation exact signed rotation identity
     * @return true only while a current fleet-convergence lease permits activation
     */
    boolean activationPermitted(
            ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation rotation);

    /**
     * Preserves the historical database-clock behavior for explicit local-only use.
     *
     * @return an authority that permits every structurally valid due successor
     */
    static ControlPlaneCertificateRotationActivationAuthority localOnly() {
        return ignored -> true;
    }
}
