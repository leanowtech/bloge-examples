package com.leanowtech.bloge.gateway.visual.simulation;

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
 */
public record NodeFixture(Object output, Object expectedInput) {

    /**
     * Backward-compatible constructor for output-only pins.
     *
     * @param output the value injected as the node's simulated output; may be {@code null}
     */
    public NodeFixture(Object output) {
        this(output, null);
    }
}
