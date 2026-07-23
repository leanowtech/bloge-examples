package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonPointer;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Closed, bounded expression AST used by state-model invariants and virtual write effects.
 *
 * <p>The protocol deliberately exposes a small deterministic vocabulary instead of embedding
 * JavaScript, SpEL, JEXL, or BLOGE DSL. It has no loop, recursion, assignment, external lookup,
 * secret access, ambient clock, or dynamically resolved function. Runtime evaluation must use the
 * session logical clock and transaction-derived identity streams for the two nondeterministic
 * operators.</p>
 *
 * @param operator closed operation vocabulary
 * @param literal detached JSON literal, used only by {@link Operator#LITERAL}
 * @param path JSON Pointer used by input and entity reads
 * @param reference entity alias or deterministic provider scope
 * @param arguments ordered child expressions
 * @param fields ordered object-projection fields
 */
public record BoundedStateExpression(
        Operator operator,
        Object literal,
        String path,
        String reference,
        List<BoundedStateExpression> arguments,
        Map<String, BoundedStateExpression> fields
) {
    /** Current bounded-expression wire vocabulary version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.boundedStateExpression.v1";
    /** Maximum AST depth admitted by protocol and runtime. */
    public static final int MAXIMUM_DEPTH = 32;
    /** Maximum AST node count admitted by one root expression. */
    public static final int MAXIMUM_NODES = 1024;
    private static final Pattern REFERENCE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FIELD =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]{0,127}");

    /** Deterministic operations supported by the v1 state expression language. */
    public enum Operator {
        LITERAL,
        INPUT_POINTER,
        ENTITY_POINTER,
        LOGICAL_TIME,
        DETERMINISTIC_ID,
        SEQUENCE,
        ADD,
        CONCAT,
        EQUALS,
        GREATER_THAN_OR_EQUAL,
        NOT_NULL,
        AND,
        OBJECT
    }

    /** Detaches collections and rejects operator-specific field-shape violations. */
    public BoundedStateExpression {
        operator = Objects.requireNonNull(operator, "operator");
        literal = ProtocolJsonValue.freeze(literal);
        path = normalized(path);
        reference = normalized(reference);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        fields = immutableFields(fields);
        validateShape(operator, literal, path, reference, arguments, fields);
    }

    /** @return a detached literal expression, including an explicit JSON null */
    public static BoundedStateExpression literal(Object value) {
        return expression(Operator.LITERAL, value, "", "", List.of(), Map.of());
    }

    /** @return a request-input JSON Pointer read */
    public static BoundedStateExpression input(String path) {
        return expression(Operator.INPUT_POINTER, null, pointer(path), "", List.of(), Map.of());
    }

    /** @return a JSON Pointer read from a mutation alias */
    public static BoundedStateExpression entity(String mutationId, String path) {
        return expression(Operator.ENTITY_POINTER, null, pointer(path),
                reference(mutationId), List.of(), Map.of());
    }

    /** @return the transaction's governed logical instant */
    public static BoundedStateExpression logicalTime() {
        return expression(Operator.LOGICAL_TIME, null, "", "", List.of(), Map.of());
    }

    /** @return a deterministic identifier scoped inside one idempotent command */
    public static BoundedStateExpression deterministicId(String scope) {
        return expression(Operator.DETERMINISTIC_ID, null, "", reference(scope),
                List.of(), Map.of());
    }

    /** @return a deterministic, commit-revision-derived positive sequence */
    public static BoundedStateExpression sequence(String scope) {
        return expression(Operator.SEQUENCE, null, "", reference(scope), List.of(), Map.of());
    }

    /** @return exact decimal addition */
    public static BoundedStateExpression add(
            BoundedStateExpression left, BoundedStateExpression right) {
        return binary(Operator.ADD, left, right);
    }

    /** @return deterministic text concatenation */
    public static BoundedStateExpression concat(
            BoundedStateExpression left, BoundedStateExpression right) {
        return binary(Operator.CONCAT, left, right);
    }

    /** @return scalar or structural JSON equality */
    public static BoundedStateExpression equalsTo(
            BoundedStateExpression left, BoundedStateExpression right) {
        return binary(Operator.EQUALS, left, right);
    }

    /** @return numeric greater-than-or-equal comparison */
    public static BoundedStateExpression greaterThanOrEqual(
            BoundedStateExpression left, BoundedStateExpression right) {
        return binary(Operator.GREATER_THAN_OR_EQUAL, left, right);
    }

    /** @return explicit non-null predicate */
    public static BoundedStateExpression notNull(BoundedStateExpression value) {
        return expression(Operator.NOT_NULL, null, "", "",
                List.of(Objects.requireNonNull(value, "value")), Map.of());
    }

    /** @return short-circuit conjunction */
    public static BoundedStateExpression and(List<BoundedStateExpression> values) {
        return expression(Operator.AND, null, "", "", values, Map.of());
    }

    /** @return deterministic JSON object projection */
    public static BoundedStateExpression object(
            Map<String, BoundedStateExpression> fields) {
        return expression(Operator.OBJECT, null, "", "", List.of(), fields);
    }

    /**
     * Iteratively validates depth and node-count limits.
     *
     * @param root expression root
     * @throws IllegalArgumentException when the AST exceeds a protocol bound
     */
    public static void validate(BoundedStateExpression root) {
        Objects.requireNonNull(root, "expression");
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, 1));
        int nodes = 0;
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            nodes++;
            if (nodes > MAXIMUM_NODES) {
                throw new IllegalArgumentException(
                        "bounded state expression exceeds maximum node count");
            }
            if (frame.depth() > MAXIMUM_DEPTH) {
                throw new IllegalArgumentException(
                        "bounded state expression exceeds maximum depth");
            }
            List<BoundedStateExpression> children = new ArrayList<>(frame.value().arguments());
            children.addAll(frame.value().fields().values());
            for (int index = children.size() - 1; index >= 0; index--) {
                stack.push(new Frame(Objects.requireNonNull(children.get(index),
                        "expression child"), frame.depth() + 1));
            }
        }
    }

    private static BoundedStateExpression binary(
            Operator operator, BoundedStateExpression left, BoundedStateExpression right) {
        return expression(operator, null, "", "", List.of(
                Objects.requireNonNull(left, "left"),
                Objects.requireNonNull(right, "right")), Map.of());
    }

    private static BoundedStateExpression expression(
            Operator operator,
            Object literal,
            String path,
            String reference,
            List<BoundedStateExpression> arguments,
            Map<String, BoundedStateExpression> fields) {
        return new BoundedStateExpression(operator, literal, path, reference, arguments, fields);
    }

    private static void validateShape(
            Operator operator,
            Object literal,
            String path,
            String reference,
            List<BoundedStateExpression> arguments,
            Map<String, BoundedStateExpression> fields) {
        boolean literalOperator = operator == Operator.LITERAL;
        if (!literalOperator && literal != null) {
            throw new IllegalArgumentException(operator + " must not carry a literal");
        }
        switch (operator) {
            case LITERAL -> empty(path, reference, arguments, fields, operator);
            case INPUT_POINTER -> {
                requirePointer(path);
                empty(reference, arguments, fields, operator);
            }
            case ENTITY_POINTER -> {
                requirePointer(path);
                requireReference(reference);
                empty(arguments, fields, operator);
            }
            case LOGICAL_TIME -> empty(path, reference, arguments, fields, operator);
            case DETERMINISTIC_ID, SEQUENCE -> {
                requireReference(reference);
                empty(path, arguments, fields, operator);
            }
            case ADD, CONCAT, EQUALS, GREATER_THAN_OR_EQUAL -> {
                if (arguments.size() != 2) {
                    throw new IllegalArgumentException(operator + " requires two arguments");
                }
                empty(path, reference, fields, operator);
            }
            case NOT_NULL -> {
                if (arguments.size() != 1) {
                    throw new IllegalArgumentException("NOT_NULL requires one argument");
                }
                empty(path, reference, fields, operator);
            }
            case AND -> {
                if (arguments.isEmpty() || arguments.size() > 64) {
                    throw new IllegalArgumentException("AND requires between 1 and 64 arguments");
                }
                empty(path, reference, fields, operator);
            }
            case OBJECT -> {
                if (fields.isEmpty() || fields.size() > 128) {
                    throw new IllegalArgumentException(
                            "OBJECT requires between 1 and 128 fields");
                }
                empty(path, reference, arguments, operator);
            }
        }
    }

    private static void requirePointer(String value) {
        try {
            JsonPointer.compile(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("expression path must be a JSON Pointer");
        }
    }

    private static void requireReference(String value) {
        if (!REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("expression reference is invalid");
        }
    }

    private static void empty(
            String first,
            String second,
            List<?> third,
            Map<?, ?> fourth,
            Operator operator) {
        if (!first.isEmpty() || !second.isEmpty() || !third.isEmpty() || !fourth.isEmpty()) {
            throw new IllegalArgumentException(operator + " carries unsupported fields");
        }
    }

    private static void empty(
            String first, List<?> second, Map<?, ?> third, Operator operator) {
        if (!first.isEmpty() || !second.isEmpty() || !third.isEmpty()) {
            throw new IllegalArgumentException(operator + " carries unsupported fields");
        }
    }

    private static void empty(
            List<?> first, Map<?, ?> second, Operator operator) {
        if (!first.isEmpty() || !second.isEmpty()) {
            throw new IllegalArgumentException(operator + " carries unsupported fields");
        }
    }

    private static void empty(
            String first, String second, Map<?, ?> third, Operator operator) {
        if (!first.isEmpty() || !second.isEmpty() || !third.isEmpty()) {
            throw new IllegalArgumentException(operator + " carries unsupported fields");
        }
    }

    private static void empty(
            String first, String second, List<?> third, Operator operator) {
        if (!first.isEmpty() || !second.isEmpty() || !third.isEmpty()) {
            throw new IllegalArgumentException(operator + " carries unsupported fields");
        }
    }

    private static String pointer(String value) {
        String normalized = normalized(value);
        requirePointer(normalized);
        return normalized;
    }

    private static String reference(String value) {
        String normalized = normalized(value);
        requireReference(normalized);
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, BoundedStateExpression> immutableFields(
            Map<String, BoundedStateExpression> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, BoundedStateExpression> copy = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = normalized(entry.getKey());
                    if (!FIELD.matcher(name).matches()) {
                        throw new IllegalArgumentException(
                                "expression object field name is invalid");
                    }
                    if (copy.put(name, Objects.requireNonNull(entry.getValue(),
                            "expression field")) != null) {
                        throw new IllegalArgumentException(
                                "expression object contains duplicate fields");
                    }
                });
        return Collections.unmodifiableMap(copy);
    }

    private record Frame(BoundedStateExpression value, int depth) {
    }
}
