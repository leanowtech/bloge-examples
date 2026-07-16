package com.leanowtech.bloge.gateway.testing.runtime;

import java.util.Objects;

/**
 * Profile-gated holder for the isolated durable test runtime entry point.
 *
 * <p>The staged execution lifecycle and checkpoint stores remain encapsulated by the factory. The
 * runtime never publishes a raw BLOGE store as a global Spring autowire candidate.</p>
 *
 * @param engineFactory factory that always configures checkpoint failures as fail-fast
 */
public record DurableTestRuntimeResources(
        IndependentDurableTestEngineFactory engineFactory) {

    /** Validates the isolated runtime entry point. */
    public DurableTestRuntimeResources {
        engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
    }
}
