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
    void flattensLocalReferenceObjectAllOfSchemas() {
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "allOf", List.of(
                        Map.of("$ref", "#/$defs/BaseApplicant"),
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "segment", Map.of("type", "string")
                                ),
                                "required", List.of("segment"),
                                "additionalProperties", false)
                ),
                "$defs", Map.of(
                        "BaseApplicant", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "score", Map.of("type", "integer")
                                ),
                                "required", List.of("score"),
                                "minProperties", 1)
                )
        ));

        assertThat(envelope.schema()).doesNotContainKey("allOf");
        assertThat(envelope.schema()).containsEntry("type", "object");
        assertThat(property(envelope.schema(), "score")).containsEntry("type", "integer");
        assertThat(property(envelope.schema(), "segment")).containsEntry("type", "string");
        assertThat((List<Object>) envelope.schema().get("required")).containsExactly("score", "segment");
        assertThat(envelope.schema())
                .containsEntry("additionalProperties", false)
                .containsEntry("minProperties", 1L);
    }

    @Test
    void flattensSafeScalarAllOfSchemas() {
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "customerCode", Map.of(
                                "allOf", List.of(
                                        Map.of("type", "string"),
                                        Map.of("minLength", 3),
                                        Map.of("pattern", "^[A-Z]+$"))),
                        "score", Map.of(
                                "allOf", List.of(
                                        Map.of("type", "integer"),
                                        Map.of("minimum", 300),
                                        Map.of("maximum", 850)))
                )
        ));

        Map<String, Object> customerCode = property(envelope.schema(), "customerCode");
        assertThat(customerCode)
                .doesNotContainKey("allOf")
                .containsEntry("type", "string")
                .containsEntry("minLength", 3)
                .containsEntry("pattern", "^[A-Z]+$");
        Map<String, Object> score = property(envelope.schema(), "score");
        assertThat(score)
                .doesNotContainKey("allOf")
                .containsEntry("type", "integer")
                .containsEntry("minimum", 300)
                .containsEntry("maximum", 850);
    }

    @Test
    void keepsConflictingScalarAllOfSchemasUnsupported() {
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of(
                                "allOf", List.of(
                                        Map.of("type", "string", "pattern", "^[A-Z]+$"),
                                        Map.of("pattern", "^[a-z]+$"))))
        ));

        assertThat(property(envelope.schema(), "decision"))
                .containsKey("allOf");
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
