package com.leanowtech.bloge.gateway.testing.domain;

import java.util.Optional;

/** Closed set of legal test-kernel execution modes. */
public enum ExecutionMode {
    PRIMITIVE_REAL,
    SCHEMA_STANDIN,
    DESCRIPTOR_PROTOCOL,
    DESCRIPTOR_TRANSPORT,
    BINDING_TRANSPORT,
    BINDING_REAL,
    WORLD_DELEGATE;

    /**
     * Resolves only execution semantics implemented by the stage-zero unified kernel.
     * Output-level controls intentionally remain unclassified; they are not schema stand-ins.
     */
    public static Optional<ExecutionMode> resolve(
            String operatorRef, FixtureRule.Behavior behavior) {
        if (behavior.kind() == FixtureRule.BehaviorKind.REAL
                || behavior.kind() == FixtureRule.BehaviorKind.SPY) {
            return Optional.of(BINDING_REAL);
        }
        if (behavior.kind() == FixtureRule.BehaviorKind.RETURN
                && "httpResource".equals(operatorRef)
                && behavior.statusCode() != null
                && behavior.value() == null) {
            return Optional.of(behavior.boundary() == FixtureRule.DoubleBoundary.TRANSPORT
                    ? DESCRIPTOR_TRANSPORT : DESCRIPTOR_PROTOCOL);
        }
        return Optional.empty();
    }
}
