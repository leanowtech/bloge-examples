package com.leanowtech.bloge.gateway.expression;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.NodeResults;
import com.leanowtech.bloge.core.data.InputAssembler;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.exception.ResourceDescriptorException;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates bloge DSL expressions against a data context.
 *
 * <p>Internally wraps each expression in a minimal transform graph, compiles it once
 * via {@link GraphLoader}, caches the resulting {@link InputAssembler}, and evaluates
 * it by feeding the context data through a {@link GraphContext}.
 *
 * <p>The compilation trick uses a synthetic graph:
 * <pre>{@code
 * graph __eval {
 *     transform __r {
 *         __v = <expression>
 *     }
 * }
 * }</pre>
 *
 * <p>Thread-safe — compiled assemblers are cached in a {@link ConcurrentHashMap}.
 */
@Component
public class BlgeExpressionEvaluator {

    private static final String GRAPH_TEMPLATE = """
            graph __eval {
                transform __r {
                    __v = %s
                }
            }
            """;

    private final ConcurrentHashMap<String, InputAssembler<?>> cache = new ConcurrentHashMap<>();
    private final GraphLoader graphLoader;

    /**
     * Creates an evaluator with a default operator registry.
     */
    public BlgeExpressionEvaluator() {
        this.graphLoader = new GraphLoader(new DefaultOperatorRegistry());
    }

    /**
     * Creates an evaluator with the given graph loader (useful for testing or
     * when a pre-configured registry is needed).
     *
     * @param graphLoader the loader to use for compiling expressions
     */
    public BlgeExpressionEvaluator(GraphLoader graphLoader) {
        this.graphLoader = graphLoader;
    }

    /**
     * Evaluates a bloge expression against the provided context data.
     *
     * @param expression the bloge expression to evaluate (e.g. {@code "ctx.body.errno == 0"})
     * @param context    a map of variable bindings available in the expression via {@code ctx.*}
     * @return the result of evaluating the expression
     */
    public Object evaluate(String expression, Map<String, Object> context) {
        InputAssembler<?> assembler = cache.computeIfAbsent(expression, this::compile);
        var graphContext = new GraphContext(context);
        var results = new NodeResults();
        Object assembled = assembler.assemble(results, graphContext);
        if (assembled instanceof Map<?, ?> map) {
            return map.get("__v");
        }
        return assembled;
    }

    /**
     * Evaluates the expression and coerces the result to a boolean.
     *
     * @param expression the bloge expression to evaluate
     * @param context    variable bindings
     * @return {@code true} if the expression result is truthy
     */
    public boolean evaluateBoolean(String expression, Map<String, Object> context) {
        Object result = evaluate(expression, context);
        if (result instanceof Boolean b) {
            return b;
        }
        if (result instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return result != null;
    }

    /**
     * Evaluates the expression and returns the result as a string.
     *
     * @param expression the bloge expression to evaluate
     * @param context    variable bindings
     * @return the string representation of the result, or {@code null}
     */
    public String evaluateString(String expression, Map<String, Object> context) {
        Object result = evaluate(expression, context);
        return result == null ? null : result.toString();
    }

    /**
     * Checks whether the given expression can be compiled without errors.
     *
     * @param expression the bloge expression to check
     * @return {@code true} if the expression compiles successfully
     */
    public boolean canCompile(String expression) {
        try {
            compile(expression);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pre-compiles the expression and caches the result. If compilation fails,
     * throws a {@link ResourceDescriptorException}.
     *
     * <p>Intended for startup-time validation of all expressions in registered
     * {@code ResourceDescriptor} entries.
     *
     * @param expression the bloge expression to pre-compile
     * @throws ResourceDescriptorException if the expression cannot be compiled
     */
    public void precompile(String expression) throws ResourceDescriptorException {
        try {
            cache.computeIfAbsent(expression, this::compile);
        } catch (Exception e) {
            throw new ResourceDescriptorException("(startup-validation)", expression, e);
        }
    }

    private InputAssembler<?> compile(String expression) {
        String dsl = GRAPH_TEMPLATE.formatted(expression);
        Graph graph = graphLoader.load(dsl);
        return graph.nodes().get("__r").inputAssembler();
    }
}
