package com.leanowtech.bloge.gateway.solution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Synthesizes deterministic contract-shaped WRITE results without touching a downstream system. */
public final class InstructionStubFactory {
    private InstructionStubFactory() { }

    /** Returns mandatory result plus a stable simulation explanation. */
    public static Map<String, Object> from(InstructionContract instruction) {
        return Map.of("result", value(instruction.output().path("result")),
                "reasoning", "SIMULATED_WRITE_STUB");
    }

    private static Object value(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return Map.of();
        JsonNode type = schema.has("type") ? schema.path("type") : schema;
        if (type.isTextual()) {
            return switch (type.asText().trim().toLowerCase(java.util.Locale.ROOT)) {
                case "string" -> "";
                case "number", "decimal", "integer" -> 0;
                case "boolean" -> false;
                case "array" -> java.util.List.of();
                default -> Map.of();
            };
        }
        if (type.path("enum").isArray() && !type.path("enum").isEmpty()) {
            return scalar(type.path("enum").get(0));
        }
        JsonNode fields = type.path("fields");
        if (fields.isObject()) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            fields.fields().forEachRemaining(entry -> result.put(entry.getKey(), value(entry.getValue())));
            return Map.copyOf(result);
        }
        return Map.of();
    }

    private static Object scalar(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.asLong();
        if (value.isFloatingPointNumber()) return value.asDouble();
        return value.toString();
    }
}
