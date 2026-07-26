package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowSourceResolutionAttestationProtocolSchemaTest {
    private static final String SCHEMA =
            "read-only-shadow-source-resolution-attestation-v1.schema.json";
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemaExactlyMatchesSignedPayloadFreeAttestation()
            throws Exception {
        var policy =
                new PayloadFreeEqualityReadOnlyShadowPolicy(
                        mapper);
        ReadOnlyShadowSourceResolutionAttestation value =
                new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                clock()),
                        clock())
                        .sign(
                                ReadOnlyShadowSourceResolutionTestFixtures
                                        .unsigned(
                                                policy.reference()));
        JsonNode json = mapper.valueToTree(value);
        JsonNode schema = mapper.readTree(
                Files.readString(schemaPath()));

        assertExact(json, schema);
        assertExact(
                json.path("baseline"),
                schema.at("/$defs/sourceResolution"));
        assertExact(
                json.path("candidate"),
                schema.at("/$defs/sourceResolution"));
        assertThat(schema.at(
                "/properties/baseline/allOf/1/properties/role/const")
                .asText()).isEqualTo("BASELINE");
        assertThat(schema.at(
                "/properties/candidate/allOf/1/properties/role/const")
                .asText()).isEqualTo("CANDIDATE");
        assertThat(schema.at(
                "/$defs/sourceResolution/properties/writeAttemptCount/const")
                .asInt()).isZero();
    }

    @Test
    void protocolCannotCarryPayloadCredentialEndpointOrFreeText()
            throws Exception {
        String source = Files.readString(schemaPath());

        for (String forbidden : Set.of(
                "requestPayload",
                "responsePayload",
                "nodeInput",
                "nodeOutput",
                "credential",
                "secret",
                "token",
                "password",
                "endpointUri",
                "stackTrace",
                "message",
                "description")) {
            assertThat(source)
                    .doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static void assertExact(
            JsonNode value,
            JsonNode schema) {
        assertThat(schema.path("additionalProperties")
                .asBoolean(true)).isFalse();
        assertThat(fieldNames(value))
                .containsExactlyInAnyOrderElementsOf(
                        fieldNames(schema.path("properties")));
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(
                        textValues(schema.path("required")));
    }

    private static Path schemaPath() {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas",
                "resource-gateway-mirror", SCHEMA);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of(
                        "docs", "schemas",
                        "resource-gateway-mirror", SCHEMA);
    }

    private static LinkedHashSet<String> fieldNames(
            JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static LinkedHashSet<String> textValues(
            JsonNode value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        value.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static Clock clock() {
        return Clock.fixed(
                ReadOnlyShadowSourceResolutionTestFixtures
                        .NOW.plusSeconds(4),
                ZoneOffset.UTC);
    }
}
