package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineReadOnlyShadowBaselineProtocolSchemaTest {
    private static final String COMMAND_SCHEMA =
            "online-read-only-shadow-baseline-command-v1.schema.json";
    private static final String OBSERVATION_SCHEMA =
            "online-read-only-shadow-baseline-observation-v1.schema.json";
    private static final String CAPABILITY_SCHEMA =
            "online-read-only-shadow-baseline-capability-v1.schema.json";
    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .mapper();

    @Test
    void strictSchemasExactlyMatchAllThreeJavaWireModels()
            throws Exception {
        OnlineReadOnlyShadowBaselineCommand command =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .command(mapper);
        OnlineReadOnlyShadowBaselineObservation
                observation =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .integrity(mapper)
                        .sign(
                                OnlineReadOnlyShadowBaselineTestFixtures
                                        .unsigned(
                                                mapper, command));
        OnlineReadOnlyShadowBaselineProtocol.Capability
                capability =
                new OnlineReadOnlyShadowBaselineProtocol
                        .Capability(
                        OnlineReadOnlyShadowBaselineProtocol
                                .Capability.SCHEMA_VERSION,
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION,
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .NOW.plusSeconds(3),
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .NOW.plusSeconds(60),
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true);

        assertExact(
                mapper.valueToTree(command),
                schema(COMMAND_SCHEMA));
        JsonNode observationSchema =
                schema(OBSERVATION_SCHEMA);
        assertExact(
                mapper.valueToTree(observation),
                observationSchema);
        assertExact(
                mapper.valueToTree(capability),
                schema(CAPABILITY_SCHEMA));
        assertThat(observationSchema.at(
                "/properties/accessMode/const")
                .asText()).isEqualTo("READ_ONLY");
        assertThat(observationSchema.at(
                "/properties/writeCredentialExposed/type")
                .asText()).isEqualTo("boolean");
        assertThat(observationSchema.at(
                "/properties/writeAttemptCount/minimum")
                .asInt()).isZero();
    }

    @Test
    void schemasCannotAddPayloadCredentialEndpointOrFreeTextFields()
            throws Exception {
        Set<String> forbidden = Set.of(
                "payload",
                "requestPayload",
                "responsePayload",
                "requestBody",
                "responseBody",
                "credential",
                "credentialRef",
                "credentialValue",
                "secret",
                "token",
                "password",
                "endpoint",
                "endpointUri",
                "stackTrace",
                "message",
                "description");

        for (String file : Set.of(
                COMMAND_SCHEMA,
                OBSERVATION_SCHEMA,
                CAPABILITY_SCHEMA)) {
            Set<String> propertyNames =
                    new LinkedHashSet<>();
            collectPropertyNames(
                    schema(file), propertyNames);
            assertThat(propertyNames)
                    .doesNotContainAnyElementsOf(
                            forbidden);
        }
    }

    private static void assertExact(
            JsonNode value,
            JsonNode schema) {
        assertThat(schema.path(
                "additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(
                                schema.path(
                                        "properties")));
        assertThat(fieldNames(
                schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(
                        textValues(
                                schema.path(
                                        "required")));
    }

    private JsonNode schema(
            String name) throws Exception {
        return mapper.readTree(
                Files.readString(
                        schemaPath(name)));
    }

    private static Path schemaPath(
            String name) {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas",
                "resource-gateway-mirror", name);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of(
                        "docs", "schemas",
                        "resource-gateway-mirror", name);
    }

    private static void collectPropertyNames(
            JsonNode value,
            Set<String> names) {
        if (value.isObject()) {
            JsonNode properties = value.get(
                    "properties");
            if (properties != null
                    && properties.isObject()) {
                properties.fieldNames()
                        .forEachRemaining(names::add);
            }
            value.elements().forEachRemaining(
                    child -> collectPropertyNames(
                            child, names));
        } else if (value.isArray()) {
            value.forEach(
                    child -> collectPropertyNames(
                            child, names));
        }
    }

    private static LinkedHashSet<String> fieldNames(
            JsonNode value) {
        LinkedHashSet<String> names =
                new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(
                names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(
            JsonNode value) {
        LinkedHashSet<String> values =
                new LinkedHashSet<>();
        value.forEach(
                item -> values.add(
                        item.asText()));
        return values;
    }
}
