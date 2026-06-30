package com.leanowtech.bloge.gateway.visual.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual schema envelope normalization.
 */
class SchemaEnvelopeTest {

    @Test
    void expandsPureLocalDefinitionsReferences() {
        Map<String, Object> addressSchema = new LinkedHashMap<>();
        addressSchema.put("type", "object");
        addressSchema.put("properties", Map.of(
                "city", Map.of("type", "string")
        ));
        addressSchema.put("required", List.of("city"));
        addressSchema.put("additionalProperties", false);

        Map<String, Object> propertyRef = new LinkedHashMap<>();
        propertyRef.put("$ref", "#/$defs/Address");
        propertyRef.put("description", "Billing address.");

        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "address", propertyRef
                ),
                "$defs", Map.of(
                        "Address", addressSchema
                )
        ));

        Map<String, Object> address = property(envelope.schema(), "address");
        assertThat(address).doesNotContainKey("$ref");
        assertThat(address)
                .containsEntry("type", "object")
                .containsEntry("description", "Billing address.");
        assertThat(property(address, "city")).containsEntry("type", "string");
    }

    @Test
    void preservesNullValuesInsideSchemaLists() {
        List<Object> values = new ArrayList<>();
        values.add("ACTIVE");
        values.add(null);

        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "string",
                "enum", values
        ));

        assertThat((List<Object>) envelope.schema().get("enum")).containsExactly("ACTIVE", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String property) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        return (Map<String, Object>) properties.get(property);
    }
}
