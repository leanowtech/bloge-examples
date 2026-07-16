package com.leanowtech.bloge.gateway.testing.runtime;

import java.util.Objects;

/**
 * Profile-gated holder for the isolated durable test engine entry point.
 *
 * <p>The staged execution lifecycle and checkpoint stores remain encapsulated by the factory so
 * they cannot become global Spring autowire candidates or be mutated outside a controlled run
 * session.</p>
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
