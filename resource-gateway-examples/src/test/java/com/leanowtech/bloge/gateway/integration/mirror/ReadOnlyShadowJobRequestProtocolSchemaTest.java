package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowJobRequestProtocolSchemaTest {
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void v1RemainsExactWhileV2RequiresDetachedSourceCoordinates() throws Exception {
        ReadOnlyShadowJobRequest online =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-schema", 17);
        ReadOnlyShadowJobRequest detached =
                new ReadOnlyShadowJobRequest(
                        ReadOnlyShadowJobRequest.V2_SCHEMA_VERSION,
                        online.requestId(),
                        online.scope(),
                        online.inventoryRef(),
                        online.unitId(),
                        online.scenarioCaseRef(),
                        online.targetCapabilityRef(),
                        online.candidatePlanRef(),
                        online.baselineBindingRef(),
                        online.comparisonPolicyRef(),
                        ReadOnlyShadowJobRequest.SourceMode
                                .DETACHED_EVIDENCE,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_SOURCE_BINDING",
                                "source-pair",
                                'a'),
                        online.accessGrant(),
                        online.deadlineAt());

        assertExact(
                mapper.valueToTree(online),
                schema("read-only-shadow-job-request-v1.schema.json"));
        JsonNode v2Schema =
                schema("read-only-shadow-job-request-v2.schema.json");
        assertExact(mapper.valueToTree(detached), v2Schema);
        assertThat(v2Schema.at(
                "/properties/sourceMode/const").asText())
                .isEqualTo("DETACHED_EVIDENCE");
        assertThat(v2Schema.at(
                "/$defs/sourceBindingRef/allOf/1/properties/kind/const")
                .asText())
                .isEqualTo("SHADOW_SOURCE_BINDING");
    }

    private static void assertExact(
            JsonNode value,
            JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(schema.path("properties")));
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(
                        textValues(schema.path("required")));
    }

    private JsonNode schema(String filename) throws Exception {
        return mapper.readTree(
                Files.readString(schemaPath(filename)));
    }

    private static Path schemaPath(String filename) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas",
                "resource-gateway-mirror", filename);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of(
                        "docs", "schemas",
                        "resource-gateway-mirror", filename);
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(JsonNode value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        value.forEach(item -> values.add(item.asText()));
        return values;
    }
}
