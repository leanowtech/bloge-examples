package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Side-effect-free operator stand-in used by the visual canvas mock-run (simulate) path.
 *
 * <p>When a graph references an operator that the server has not implemented (a schema-only,
 * design-only operator) or an operator whose real execution would cause side effects (HTTP calls,
 * database writes, LLM calls), the simulate path substitutes a {@code SimulationOperator} that simply
 * returns a pre-computed, schema-conforming value. This lets the whole graph execute end-to-end on the
 * real BLOGE engine for runtime-correctness validation without performing any real work.</p>
 *
 * <p>This is a purpose-built runtime double (decision D3): {@code bloge-test} stays a test-only
 * dependency, so the simulate feature does not drag test scaffolding into the running server.</p>
 *
 * <p>The operator is declared {@link SideEffectType#READ_ONLY} and {@link Idempotency#IDEMPOTENT}
 * because it never mutates anything and always returns the same value. It records the input it was
 * called with so callers can later assert per-node input expectations.</p>
 */
public final class SimulationOperator implements Operator<Object, Object> {

    private final String label;
    private final Object output;
    private final AtomicInteger invocationCount = new AtomicInteger();
    private volatile Object lastInput;

    /**
     * Creates a simulation operator.
     *
     * @param label human-readable label identifying what this stand-in represents (e.g. the node id)
     * @param output the value returned on every invocation; may be {@code null}
     */
    public SimulationOperator(String label, Object output) {
        this.label = label == null ? "" : label;
        this.output = output;
    }

    /**
     * Creates a simulation operator that always returns the supplied value.
     *
     * @param label human-readable label identifying what this stand-in represents
     * @param output the value returned on every invocation; may be {@code null}
     * @return a new simulation operator
     */
    public static SimulationOperator returning(String label, Object output) {
        return new SimulationOperator(label, output);
    }

    @Override
    public Object execute(Object input, OperatorContext ctx) {
        invocationCount.incrementAndGet();
        this.lastInput = input;
        return output;
    }

    @Override
    public SideEffectType sideEffectType() {
        return SideEffectType.READ_ONLY;
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.IDEMPOTENT;
    }

    /**
     * @return the label identifying what this stand-in represents
     */
    public String label() {
        return label;
    }

    /**
     * @return how many times this stand-in has been invoked
     */
    public int invocationCount() {
        return invocationCount.get();
    }

    /**
     * @return the most recent input passed to {@link #execute(Object, OperatorContext)}, or
     *         {@code null} when it has not been invoked
     */
    public Object lastInput() {
        return lastInput;
    }
}
