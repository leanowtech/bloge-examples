package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the executable operator coordinate and input represented by a visual operator.
 *
 * <p>A visual operator may be a business-facing virtual resource while the BLOGE runtime executes
 * a generic operator such as {@code httpResource}. Keeping this translation in one server-side
 * component prevents test publication, direct operator tests, and future protocol clients from
 * assigning different meanings to the same catalog lowering metadata.</p>
 */
public final class OperatorExecutionLowering {

    private OperatorExecutionLowering() {
    }

    /**
     * Returns the exact runtime registry reference declared by the operator.
     *
     * @param operator authoritative visual operator definition
     * @return non-blank runtime operator reference
     */
    public static String runtimeOperatorRef(OperatorDefinition operator) {
        OperatorDefinition definition = Objects.requireNonNull(operator, "operator");
        String lowered = definition.lowering().operatorRef().trim();
        return lowered.isBlank() ? definition.operatorRef() : lowered;
    }

    /**
     * Deterministically translates one visual Contract input into the runtime input shape.
     *
     * <p>Resource descriptors lower to {@code httpResource} by combining the descriptor-owned
     * {@code resourceId} with author-provided parameters. Transport test overrides are retained
     * when present. Other lowering modes currently preserve the Contract input verbatim.</p>
     *
     * @param operator authoritative visual operator definition
     * @param input schema-validated visual Contract input
     * @return immutable runtime input value
     * @throws IllegalArgumentException when a resource lowering has no resolvable resource id
     */
    public static Object lowerInput(OperatorDefinition operator, Object input) {
        OperatorDefinition definition = Objects.requireNonNull(operator, "operator");
        if (!resourceLowering(definition)) {
            return input;
        }
        Map<String, Object> inputObject = stringMap(input);
        String resourceId = resourceId(definition, inputObject);
        Object params;
        if (inputObject.containsKey("params")) {
            params = inputObject.get("params");
        } else {
            LinkedHashMap<String, Object> flat = new LinkedHashMap<>(inputObject);
            flat.remove("resourceId");
            flat.remove("headerOverrides");
            flat.remove("authOverride");
            flat.remove("timeoutOverride");
            params = flat;
        }
        LinkedHashMap<String, Object> lowered = new LinkedHashMap<>();
        lowered.put("resourceId", resourceId);
        lowered.put("params", params == null ? Map.of() : params);
        for (String key : new String[]{"headerOverrides", "authOverride", "timeoutOverride"}) {
            if (inputObject.containsKey(key)) {
                lowered.put(key, inputObject.get(key));
            }
        }
        return Collections.unmodifiableMap(lowered);
    }

    private static boolean resourceLowering(OperatorDefinition operator) {
        return "resource-descriptor".equals(operator.lowering().mode())
                || "httpResource".equals(runtimeOperatorRef(operator));
    }

    private static String resourceId(
            OperatorDefinition operator,
            Map<String, Object> input) {
        Object configured = operator.lowering().parameters().get("resourceId");
        String configuredValue = configured instanceof String value ? value.trim() : "";
        String suppliedValue = input.get("resourceId") instanceof String value ? value.trim() : "";
        String resolved = configuredValue.isBlank() ? suppliedValue : configuredValue;
        if (resolved.isBlank()) {
            throw new IllegalArgumentException(
                    "Resource-backed operator requires lowering.parameters.resourceId "
                            + "or input.resourceId.");
        }
        return resolved;
    }

    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(
                    "Resource-backed operator input must be a JSON object.");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "Resource-backed operator input keys must be strings.");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }
}
