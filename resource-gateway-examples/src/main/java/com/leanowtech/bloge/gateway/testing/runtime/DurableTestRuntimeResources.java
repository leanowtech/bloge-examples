package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;

import java.util.Objects;

/**
 * Profile-gated holder for isolated durable test runtime and maintenance entry points.
 *
 * <p>The staged execution lifecycle and checkpoint stores remain encapsulated by the factory. The
 * maintenance boundary can only inspect and rebuild their derived scheduling projections; neither
 * path publishes a raw BLOGE store as a global Spring autowire candidate.</p>
 *
 * @param engineFactory factory that always configures checkpoint failures as fail-fast
 * @param projectionReconciler bounded system-level repair boundary for derived scheduling indexes
 */
public record DurableTestRuntimeResources(
        IndependentDurableTestEngineFactory engineFactory,
        DurableStateProjectionReconciler projectionReconciler) {

    /** Validates the isolated runtime entry point. */
    public DurableTestRuntimeResources {
        engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        projectionReconciler = Objects.requireNonNull(projectionReconciler, "projectionReconciler");
    }
}
