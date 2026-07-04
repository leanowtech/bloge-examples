package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for shared visual JSON Schema introspection helpers.
 */
class VisualSchemaIntrospectionTest {

    @Test
    void discoversTypelessArrayPathsAndResolvesTupleRemainderItems() {
        Map<String, Object> schema = Map.of(
                "prefixItems", List.of(Map.of("type", "integer")),
                "items", Map.of("type", "string")
        );

        List<String> paths = VisualSchemaIntrospection.connectableSchemaPaths(
                new SchemaEnvelope("json-schema", "", schema), 64, 4);

        assertThat(paths).contains("", "0", "1");
        assertThat(VisualSchemaIntrospection.schemaType(schema)).isEqualTo("array");
        assertThat(VisualSchemaIntrospection.schemaAtPath(schema, "0")).containsEntry("type", "integer");
        assertThat(VisualSchemaIntrospection.schemaAtPath(schema, "1")).containsEntry("type", "string");
        assertThat(VisualSchemaIntrospection.schemaAtPath(schema, "+1")).isNull();
    }

    @Test
    void resolvesTypelessObjectPatternPropertiesThroughPropertyNameGate() {
        Map<String, Object> schema = Map.of(
                "propertyNames", Map.of("pattern", "^metric_"),
                "patternProperties", Map.of("^metric_[a-z]+$", Map.of("type", "number"))
        );

        assertThat(VisualSchemaIntrospection.schemaType(schema)).isEqualTo("object");
        assertThat(VisualSchemaIntrospection.schemaAtPath(schema, "metric_score"))
                .containsEntry("type", "number");
        assertThat(VisualSchemaIntrospection.schemaAtPath(schema, "score")).isNull();
    }
}
