package com.leanowtech.bloge.gateway.testing.api;

/**
 * Observes the verified local staging lifecycle without weakening controller authorization.
 *
 * <p>The controller invokes {@link #prepare} only after signature, material and durable-floor
 * verification, but before the transport can activate a due generation. Implementations may
 * therefore install a fail-closed local activation gate without acknowledging material that has
 * not yet loaded successfully. All callbacks must be bounded and must not expose certificate or
 * secret material.</p>
 */
public interface ControlPlaneCertificateRotationLifecycle {

    /** Installs the exact verified rotation identity before local transport mutation. */
    void prepare(ControlPlaneCertificateRotationEvent event);

    /** Publishes the resulting locally staged or active state. */
    void applied(ControlPlaneCertificateRotationEvent event);

    /** Publishes a bounded local failure for the exact prepared rotation. */
    void failed(ControlPlaneCertificateRotationEvent event, String failureCode);

    /** @return a compatibility lifecycle for runtimes without fleet convergence */
    static ControlPlaneCertificateRotationLifecycle noop() {
        return Noop.INSTANCE;
    }

    /** Allocation-free no-op singleton. */
    enum Noop implements ControlPlaneCertificateRotationLifecycle {
        INSTANCE;

        @Override
        public void prepare(ControlPlaneCertificateRotationEvent event) {
        }

        @Override
        public void applied(ControlPlaneCertificateRotationEvent event) {
        }

        @Override
        public void failed(ControlPlaneCertificateRotationEvent event, String failureCode) {
        }
    }
}
