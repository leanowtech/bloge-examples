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
     * Resolves execution semantics that can be inferred without an explicit internal mode hint.
     * A schema stand-in is intentionally explicit because it changes evidence fidelity.
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

    /** Returns whether a fixture has the exact shape admitted for a schema stand-in hint. */
    public static boolean isSchemaStandinBehavior(
            String operatorRef, FixtureRule.Behavior behavior) {
        return behavior != null
                && behavior.kind() == FixtureRule.BehaviorKind.RETURN
                && !"httpResource".equals(operatorRef)
                && behavior.boundary() == FixtureRule.DoubleBoundary.NODE
                && behavior.rawBody().isBlank()
                && behavior.statusCode() == null
                && behavior.headers().isEmpty()
                && behavior.errorCode().isBlank()
                && behavior.errorType().isBlank()
                && behavior.errorMessage().isBlank()
                && behavior.after() == null
                && behavior.sequence().isEmpty()
                && behavior.replayRef().isBlank();
    }
}
