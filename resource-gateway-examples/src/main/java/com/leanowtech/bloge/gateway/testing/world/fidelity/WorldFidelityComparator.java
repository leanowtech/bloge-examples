package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Schema-aware, deterministic comparison kernel for paired World observations. */
final class WorldFidelityComparator {
    private final ObjectMapper mapper;

    WorldFidelityComparator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    WorldFidelityReport.Observation compare(WorldFidelityRequest.Sample sample,
                                            WorldFidelityRunner.Execution real,
                                            WorldFidelityRunner.Execution world,
                                            WorldFidelityRequest.ComparatorSpec spec) {
        List<String> differences = new ArrayList<>();
        compareExecution(real, world, differences);
        if (real.response() != null && world.response() != null && real.status() < 400 && world.status() < 400) {
            compareSchema(real.response(), spec.responseSchema(), "/realResponse", differences);
            compareSchema(world.response(), spec.responseSchema(), "/worldResponse", differences);
            compareNode(real.response(), world.response(), "", spec.tolerances(), differences);
        } else if (real.response() != null || world.response() != null) {
            differences.add("/response");
        }
        String realTransitions = transitionFingerprint(real.transitions());
        String worldTransitions = transitionFingerprint(world.transitions());
        compareTransitions(real.transitions(), world.transitions(), differences);
        return new WorldFidelityReport.Observation(sample.sampleId(), sample.sampleFingerprint(),
                valueFingerprint(real.response()), valueFingerprint(world.response()), real.status(), world.status(),
                real.errorClass(), world.errorClass(), real.retryable(), world.retryable(), differences,
                realTransitions, worldTransitions, Math.abs(real.durationMillis() - world.durationMillis()));
    }

    private static void compareTransitions(List<WorldFidelityRunner.StateTransition> real,
                                           List<WorldFidelityRunner.StateTransition> world,
                                           List<String> differences) {
        if (real.size() != world.size()) differences.add("/stateTransitions/$size");
        int limit = Math.min(real.size(), world.size());
        for (int index = 0; index < limit; index++) {
            WorldFidelityRunner.StateTransition left = real.get(index);
            WorldFidelityRunner.StateTransition right = world.get(index);
            String path = "/stateTransitions/" + index;
            if (!left.path().equals(right.path())) differences.add(path + "/path");
            if (!left.outcome().equals(right.outcome())) differences.add(path + "/outcome");
            if (!left.stateFingerprint().equals(right.stateFingerprint())) differences.add(path + "/state");
        }
    }

    private void compareExecution(WorldFidelityRunner.Execution real, WorldFidelityRunner.Execution world,
                                  List<String> differences) {
        if (real.status() != world.status()) differences.add("/status");
        if (!real.errorClass().equals(world.errorClass())) differences.add("/errorClass");
        if (real.retryable() != world.retryable()) differences.add("/retryable");
    }

    private void compareSchema(JsonNode value, Map<String, Object> schema, String path, List<String> differences) {
        if (schema == null || schema.isEmpty()) return;
        Object type = schema.get("type");
        if (type instanceof String expected && !typeMatches(value, expected)) differences.add(path + "/$type");
        Object required = schema.get("required");
        if (required instanceof List<?> fields && value != null && value.isObject()) {
            for (Object field : fields) if (field instanceof String name && !value.has(name)) {
                differences.add(path + "/" + name);
            }
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map && value != null && value.isObject()) {
            for (Map.Entry<?, ?> entry : map.entrySet()) if (entry.getKey() instanceof String name
                    && entry.getValue() instanceof Map<?, ?> child && value.has(name)) {
                compareSchema(value.get(name), cast(child), path + "/" + name, differences);
            }
        }
    }

    private static Map<String, Object> cast(Map<?, ?> source) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static boolean typeMatches(JsonNode value, String type) {
        if (value == null) return "null".equals(type);
        return switch (type) {
            case "null" -> value.isNull();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            default -> false;
        };
    }

    private static void compareNode(JsonNode left, JsonNode right, String path, Map<String, BigDecimal> tolerances,
                                    List<String> differences) {
        if (left == null || right == null || left.isNull() || right.isNull()) {
            if (!Objects.equals(left, right)) differences.add(path.isEmpty() ? "/response" : path);
            return;
        }
        if (left.isNumber() && right.isNumber()) {
            BigDecimal tolerance = tolerances.getOrDefault(path, BigDecimal.ZERO);
            if (left.decimalValue().subtract(right.decimalValue()).abs().compareTo(tolerance) > 0) differences.add(path);
            return;
        }
        if (left.isObject() && right.isObject()) {
            java.util.TreeSet<String> fields = new java.util.TreeSet<>();
            left.fieldNames().forEachRemaining(fields::add);
            right.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) compareNode(left.get(field), right.get(field), path + "/" + field, tolerances, differences);
            return;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) differences.add(path + "/$size");
            int limit = Math.min(left.size(), right.size());
            for (int i = 0; i < limit; i++) compareNode(left.get(i), right.get(i), path + "/" + i, tolerances, differences);
            return;
        }
        if (!left.equals(right)) differences.add(path.isEmpty() ? "/response" : path);
    }

    private String valueFingerprint(JsonNode value) {
        return value == null ? "" : ProtocolFingerprint.of(mapper, value);
    }

    private String transitionFingerprint(List<WorldFidelityRunner.StateTransition> transitions) {
        return ProtocolFingerprint.of(mapper, transitions == null ? List.of() : transitions);
    }
}
