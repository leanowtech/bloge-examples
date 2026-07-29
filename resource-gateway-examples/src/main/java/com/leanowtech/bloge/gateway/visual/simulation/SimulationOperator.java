package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.CollectionSchema;
import com.leanowtech.bloge.core.schema.FieldDescriptor;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaAware;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.StructuredSchema;
import com.leanowtech.bloge.core.schema.TypedSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>The stand-in also publishes a bounded schema projection of the configured output. This is
 * important because BLOGE compiles downstream expressions before execution. Without the projected
 * schema, a valid expression such as {@code resource.output.payload.score} would be reported as an
 * unknown path merely because the runtime double uses {@code Object} as its Java output type. The
 * projection is deliberately based on the exact fixture value: it preserves useful path validation
 * while falling back to an opaque schema for nulls, cycles, and structures beyond the depth limit.</p>
 */
public final class SimulationOperator implements Operator<Object, Object>, SchemaAware {

    private static final int MAX_SCHEMA_DEPTH = 12;
    private final String label;
    private final Object output;
    private final SchemaDescriptor outputSchema;
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
        this.outputSchema = projectSchema(output, 0, new IdentityHashMap<>());
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
     * Simulation inputs are supplied by the graph runtime and may have any shape.
     *
     * @return an opaque descriptor so the runtime does not invent input constraints
     */
    @Override
    public SchemaDescriptor inputSchema() {
        return OpaqueSchema.INSTANCE;
    }

    /**
     * Describes the fixture value returned by this stand-in.
     *
     * <p>The descriptor is computed once during construction so compilation and execution observe
     * the same immutable schema view even if callers supplied a mutable fixture object.</p>
     *
     * @return a bounded structural projection of the configured fixture output
     */
    @Override
    public SchemaDescriptor outputSchema() {
        return outputSchema;
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

    /**
     * Projects a runtime fixture into the subset of BLOGE schemas needed for compile-time path
     * validation.
     *
     * <p>Maps become structured records, collections use the first non-null element as their item
     * sample, and scalar values retain their concrete Java type. Identity-based cycle detection and
     * a fixed depth limit keep arbitrary user-provided fixtures from causing unbounded recursion.</p>
     */
    private static SchemaDescriptor projectSchema(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> ancestors
    ) {
        if (value == null || depth >= MAX_SCHEMA_DEPTH) {
            return OpaqueSchema.INSTANCE;
        }
        if (isComposite(value) && ancestors.put(value, Boolean.TRUE) != null) {
            return OpaqueSchema.INSTANCE;
        }
        try {
            if (value instanceof Map<?, ?> map) {
                List<FieldDescriptor> fields = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    Object fieldValue = entry.getValue();
                    SchemaDescriptor nested = projectSchema(fieldValue, depth + 1, ancestors);
                    fields.add(new FieldDescriptor(
                            name,
                            runtimeType(fieldValue),
                            fieldValue != null,
                            "Projected from the simulation fixture.",
                            nested
                    ));
                }
                return new StructuredSchema(fields, Map.class);
            }
            if (value instanceof Collection<?> collection) {
                Object sample = collection.stream()
                        .filter(element -> element != null)
                        .findFirst()
                        .orElse(null);
                return new CollectionSchema(
                        List.class,
                        projectSchema(sample, depth + 1, ancestors)
                );
            }
            if (value.getClass().isArray()) {
                return OpaqueSchema.INSTANCE;
            }
            return new TypedSchema(value.getClass());
        } finally {
            if (isComposite(value)) {
                ancestors.remove(value);
            }
        }
    }

    private static boolean isComposite(Object value) {
        return value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || (value != null && value.getClass().isArray());
    }

    private static Class<?> runtimeType(Object value) {
        if (value instanceof Map<?, ?>) {
            return Map.class;
        }
        if (value instanceof Collection<?>) {
            return List.class;
        }
        return value == null ? Object.class : value.getClass();
    }
}
