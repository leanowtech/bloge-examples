package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link JsonSchemaSampleGenerator}: layered precedence, canonical typing, combinators,
 * bounds, and malformed-input resilience.
 */
class JsonSchemaSampleGeneratorTest {

    private final JsonSchemaSampleGenerator generator = new JsonSchemaSampleGenerator();

    @Test
    void constWinsOverEveryOtherLayer() {
        Map<String, Object> schema = schema(
                "type", "string",
                "const", "fixed",
                "default", "d",
                "examples", List.of("e"),
                "enum", List.of("x"));
        assertThat(generator.generate(schema)).isEqualTo("fixed");
    }

    @Test
    void defaultUsedWhenNoConst() {
        Map<String, Object> schema = schema(
                "type", "string",
                "default", "the-default",
                "examples", List.of("e"));
        assertThat(generator.generate(schema)).isEqualTo("the-default");
    }

    @Test
    void firstExampleUsedWhenNoConstOrDefault() {
        Map<String, Object> schema = schema("type", "integer", "examples", List.of(42, 43));
        assertThat(generator.generate(schema)).isEqualTo(42);
    }

    @Test
    void firstEnumUsedWhenNoConstDefaultOrExample() {
        Map<String, Object> schema = schema("enum", List.of("red", "green", "blue"));
        assertThat(generator.generate(schema)).isEqualTo("red");
    }

    @Test
    void objectGeneratesDeclaredPropertiesAndRequired() {
        Map<String, Object> schema = schema(
                "type", "object",
                "properties", schema(
                        "id", schema("type", "string"),
                        "count", schema("type", "integer")),
                "required", List.of("id", "count", "missingButRequired"));

        Object value = generator.generate(schema);

        assertThat(value).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        assertThat(object).containsKeys("id", "count", "missingButRequired");
        assertThat(object.get("id")).isEqualTo("string");
        assertThat(object.get("count")).isEqualTo(0L);
        assertThat(object.get("missingButRequired")).isNull();
    }

    @Test
    void nestedObjectIsGeneratedRecursively() {
        Map<String, Object> schema = schema(
                "type", "object",
                "properties", schema(
                        "user", schema(
                                "type", "object",
                                "properties", schema("name", schema("type", "string")))));

        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) generator.generate(schema);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) object.get("user");
        assertThat(user).containsEntry("name", "string");
    }

    @Test
    void arrayWithItemsProducesOneSampleItem() {
        Map<String, Object> schema = schema(
                "type", "array",
                "items", schema("type", "string"));

        Object value = generator.generate(schema);
        assertThat(value).isInstanceOf(List.class);
        List<?> items = (List<?>) value;
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).isEqualTo("string");
    }

    @Test
    void arrayHonoursMinItems() {
        Map<String, Object> schema = schema(
                "type", "array",
                "minItems", 5,
                "items", schema("type", "integer"));

        List<?> value = (List<?>) generator.generate(schema);
        // Realistic minItems is honoured so the sample conforms to its own schema.
        assertThat(value).hasSize(5).allMatch(item -> item.equals(0L));
    }

    @Test
    void arrayWithAdversarialMinItemsIsBounded() {
        Map<String, Object> schema = schema(
                "type", "array",
                "minItems", 1_000_000,
                "items", schema("type", "string"));

        List<?> value = (List<?>) generator.generate(schema);
        // A single array can never exceed the per-array cap, so generation stays bounded.
        assertThat(value.size()).isLessThanOrEqualTo(25);
    }

    @Test
    void arrayWithoutItemsIsEmpty() {
        Map<String, Object> schema = schema("type", "array");
        assertThat((List<?>) generator.generate(schema)).isEmpty();
    }

    @Test
    void stringFormatsProduceRecognizableValues() {
        assertThat(generator.generate(schema("type", "string", "format", "date-time")))
                .isEqualTo("1970-01-01T00:00:00Z");
        assertThat(generator.generate(schema("type", "string", "format", "email")))
                .isEqualTo("user@example.com");
        assertThat(generator.generate(schema("type", "string", "format", "uuid")))
                .isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void stringHonoursMinAndMaxLength() {
        assertThat(generator.generate(schema("type", "string", "minLength", 10)))
                .isEqualTo("stringxxxx");
        assertThat(generator.generate(schema("type", "string", "maxLength", 3)))
                .isEqualTo("str");
    }

    @Test
    void integerHonoursMinimumAndExclusiveMinimum() {
        assertThat(generator.generate(schema("type", "integer", "minimum", 7))).isEqualTo(7L);
        assertThat(generator.generate(schema("type", "integer", "exclusiveMinimum", 7))).isEqualTo(8L);
    }

    @Test
    void numberHonoursMinimum() {
        assertThat(generator.generate(schema("type", "number", "minimum", 1.5))).isEqualTo(1.5d);
        assertThat(generator.generate(schema("type", "number"))).isEqualTo(0.0d);
    }

    @Test
    void booleanCanonicalIsFalse() {
        assertThat(generator.generate(schema("type", "boolean"))).isEqualTo(false);
    }

    @Test
    void typeUnionPicksFirstNonNullType() {
        assertThat(generator.generate(schema("type", List.of("null", "string")))).isEqualTo("string");
    }

    @Test
    void oneOfUsesFirstBranch() {
        Map<String, Object> schema = schema(
                "oneOf", List.of(
                        schema("type", "integer", "const", 1),
                        schema("type", "string")));
        assertThat(generator.generate(schema)).isEqualTo(1);
    }

    @Test
    void typelessSchemaWithPropertiesInfersObject() {
        Map<String, Object> schema = schema(
                "properties", schema("flag", schema("type", "boolean")));
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) generator.generate(schema);
        assertThat(object).containsEntry("flag", false);
    }

    @Test
    void opaqueEnvelopeYieldsEmptyObject() {
        assertThat(generator.generate(SchemaEnvelope.opaque())).isEqualTo(Map.of());
    }

    @Test
    void nullAndEmptySchemaYieldNull() {
        assertThat(generator.generate((SchemaEnvelope) null)).isNull();
        assertThat(generator.generate((Map<String, Object>) null)).isNull();
        assertThat(generator.generate(Map.of())).isNull();
    }

    @Test
    void deeplyNestedSchemaTerminatesWithoutError() {
        // Build an object chain far deeper than MAX_DEPTH; generation must terminate (bounded).
        Map<String, Object> leaf = schema("type", "string");
        Map<String, Object> current = leaf;
        for (int i = 0; i < 30; i++) {
            current = schema("type", "object", "properties", schema("child", current));
        }
        final Map<String, Object> root = current;
        assertThatCode(() -> assertThat(generator.generate(root)).isInstanceOf(Map.class))
                .doesNotThrowAnyException();
    }

    @Test
    void wideObjectIsTruncatedByNodeBudget() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < JsonSchemaSampleGenerator.MAX_SAMPLE_NODES + 200; i++) {
            properties.put("p" + i, schema("type", "string"));
        }
        Map<String, Object> schema = schema("type", "object", "properties", properties);

        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) generator.generate(schema);
        // The node budget stops generation before all properties are materialized.
        assertThat(object.size()).isLessThan(JsonSchemaSampleGenerator.MAX_SAMPLE_NODES + 200);
    }

    @Test
    void malformedSchemaDoesNotThrow() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", List.of("not", "a", "map"));
        schema.put("required", "not-a-list");
        schema.put("items", "not-a-schema");

        assertThatCode(() -> generator.generate(schema)).doesNotThrowAnyException();
        assertThat(generator.generate(schema)).isInstanceOf(Map.class);
    }

    @Test
    void constObjectIsDeepCopiedNotShared() {
        Map<String, Object> constValue = new LinkedHashMap<>();
        constValue.put("nested", new ArrayList<>(List.of("a")));
        Map<String, Object> schema = schema("const", constValue);

        @SuppressWarnings("unchecked")
        Map<String, Object> generated = (Map<String, Object>) generator.generate(schema);
        assertThat(generated).isEqualTo(constValue);
        assertThat(generated).isNotSameAs(constValue);
    }

    private static Map<String, Object> schema(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
