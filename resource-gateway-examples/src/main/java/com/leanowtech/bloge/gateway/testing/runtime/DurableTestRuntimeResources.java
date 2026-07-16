package com.leanowtech.bloge.gateway.testing.runtime;

import java.util.Objects;

/**
 * Profile-gated holder for the isolated durable test engine entry point.
 *
 * <p>The staged {@code ExecutionCheckpointStore} remains encapsulated by the factory so it cannot
 * become a global Spring autowire candidate or be mutated outside a controlled run session.</p>
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
