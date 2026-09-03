package com.leanowtech.bloge.gateway.visual.simulation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;

/**
 * An author-supplied per-node simulation fixture.
 *
 * <p>When a fixture is present for a node, its {@link #output()} value is injected as that node's
 * simulated output, taking precedence over the schema-synthesized sample (decisions D4 and D20).
 * Pinning a fixture also forces the node to be mocked even if it would otherwise execute for real, so
 * authors can pin exact values to validate downstream logic — "the orchestrator owns the result".</p>
 *
 * <p>The optional {@link #expectedInput()} value is asserted against the input observed by the
 * simulation stand-in after execution. This is the request-scoped "input = assert" half of the node
 * inspector simulation fixture (decision D13).</p>
 *
 * @param output the value injected as the node's simulated output; may be {@code null}
 * @param expectedInput optional input payload expected by the node during simulation; {@code null}
 *                      means no input assertion is evaluated
 * @param governedRef optional immutable governed fixture identity
 * @param resourceFidelity boundary fidelity requested for a resource fixture
 * @param dependencyBehavior optional advanced Agent TDD dependency behavior
 */
public record NodeFixture(Object output, Object expectedInput,
                          @JsonInclude(JsonInclude.Include.NON_NULL) GovernedFixtureRef governedRef,
                          ResourceFidelity resourceFidelity,
                          @JsonInclude(JsonInclude.Include.NON_NULL) DependencyBehavior dependencyBehavior) {

    /** Evidence boundary used when a resource fixture is applied. */
    public enum ResourceFidelity { OUTPUT_LEVEL, PROTOCOL_DERIVED, TRANSPORT_LEVEL }

    /** Agent-facing dependency behaviors supported by the isolated simulation kernel. */
    public enum DependencyBehaviorKind {
        RETURN, ERROR, DELAY, TIMEOUT, REPLAY, OBSERVE, MUST_NOT_CALL
    }

    /**
     * One declarative dependency behavior carried from an Agent TDD scenario into the test kernel.
     *
     * <p>{@code value} is used by RETURN, DELAY, REPLAY and the safe local delegate observed by
     * OBSERVE. REPLAY additionally requires an exact governed {@code replayRef}; its value is
     * frozen for this simulation run and is never read from a live dependency.</p>
     *
     * @param kind behavior kind
     * @param value fixed or frozen output value
     * @param errorCode stable injected error code
     * @param errorType stable injected error type
     * @param errorMessage bounded injected diagnostic
     * @param after logical delay or timeout duration
     * @param replayRef exact content-addressed replay reference
     */
    public record DependencyBehavior(
            DependencyBehaviorKind kind,
            Object value,
            String errorCode,
            String errorType,
            String errorMessage,
            Duration after,
            String replayRef
    ) {
        /** Normalizes optional diagnostic fields while preserving an explicit behavior kind. */
        public DependencyBehavior {
            if (kind == null) throw new IllegalArgumentException("dependency behavior kind is required");
            errorCode = errorCode == null ? "" : errorCode.trim();
            errorType = errorType == null ? "" : errorType.trim();
            errorMessage = errorMessage == null ? "" : errorMessage;
            replayRef = replayRef == null ? "" : replayRef.trim();
        }
    }

    /**
     * Backward-compatible constructor for output-only pins.
     *
     * @param output the value injected as the node's simulated output; may be {@code null}
     */
    public NodeFixture(Object output) {
        this(output, null, null, ResourceFidelity.OUTPUT_LEVEL, null);
    }

    /** Backward-compatible constructor for output and input assertion fixtures. */
    public NodeFixture(Object output, Object expectedInput) {
        this(output, expectedInput, null, ResourceFidelity.OUTPUT_LEVEL, null);
    }

    /** Backward-compatible governed fixture constructor with output-level fidelity. */
    public NodeFixture(Object output, Object expectedInput, GovernedFixtureRef governedRef) {
        this(output, expectedInput, governedRef, ResourceFidelity.OUTPUT_LEVEL, null);
    }

    /** Backward-compatible governed fixture constructor. */
    public NodeFixture(Object output, Object expectedInput, GovernedFixtureRef governedRef,
                       ResourceFidelity resourceFidelity) {
        this(output, expectedInput, governedRef, resourceFidelity, null);
    }

    /** Normalizes omitted fidelity to the historical output-level behavior. */
    public NodeFixture {
        resourceFidelity = resourceFidelity == null ? ResourceFidelity.OUTPUT_LEVEL : resourceFidelity;
    }
}
