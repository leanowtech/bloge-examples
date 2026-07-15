package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates protocol values against definitions in the exact JSON Schema packaged with the JAR.
 * Validation failures deliberately omit instance values and validator messages from exceptions.
 */
final class TestingProtocolSchemaValidator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDialect(Dialects.getDraft202012());
    private static final Map<String, Schema> DEFINITIONS = new ConcurrentHashMap<>();

    private TestingProtocolSchemaValidator() {
    }

    /**
     * Requires a value to satisfy one named definition in the authoritative protocol schema.
     *
     * @param value decoded request or response value
     * @param definition definition name under {@code $defs}
     * @throws IllegalArgumentException when the value does not satisfy the definition
     */
    static void require(JsonNode value, String definition) {
        if (value == null) {
            throw invalid(definition);
        }
        List<com.networknt.schema.Error> errors;
        try {
            errors = schema(definition).validate(
                    value.toString(), InputFormat.JSON,
                    context -> context.executionConfig(config -> config
                            .formatAssertionsEnabled(true)
                            .failFast(true)));
        } catch (RuntimeException failure) {
            throw invalid(definition);
        }
        if (!errors.isEmpty()) {
            throw invalid(definition);
        }
    }

    private static Schema schema(String definition) {
        return DEFINITIONS.computeIfAbsent(definition, TestingProtocolSchemaValidator::load);
    }

    private static Schema load(String definition) {
        try (InputStream input = TestingProtocolSchemaValidator.class.getResourceAsStream(
                TestingProtocol.SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IOException("Schema resource is absent");
            }
            JsonNode decoded = JSON.readTree(input);
            if (!decoded.path("$defs").has(definition) || !decoded.isObject()) {
                throw new IOException("Schema definition is absent");
            }
            ObjectNode definitionSchema = ((ObjectNode) decoded).deepCopy();
            definitionSchema.put("$ref", "#/$defs/" + definition);
            return REGISTRY.getSchema(definitionSchema.toString(), InputFormat.JSON);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("The packaged testing protocol schema is unavailable");
        }
    }

    private static IllegalArgumentException invalid(String definition) {
        return new IllegalArgumentException(
                "Protocol value failed authoritative schema validation for " + safeDefinition(definition));
    }

    private static String safeDefinition(String definition) {
        String normalized = definition == null ? "unknown" : definition.trim();
        return normalized.matches("[A-Za-z][A-Za-z0-9]{0,127}") ? normalized : "unknown";
    }
}
