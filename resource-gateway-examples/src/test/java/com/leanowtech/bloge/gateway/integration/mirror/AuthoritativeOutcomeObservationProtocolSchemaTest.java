package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeObservationProtocolSchemaTest {
    private static final String SCHEMA =
            "authoritative-outcome-observation-v1.schema.json";
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(
                            SerializationFeature
                                    .WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemaExactlyMatchesTheSignedPayloadFreeProtocol()
            throws Exception {
        AuthoritativeOutcomeObservation signed =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                DomainFidelityTestFixtures.CLOCK),
                        new AuthoritativeOutcomeAuthorityVerifier() {
                            @Override
                            public boolean available() {
                                return true;
                            }

                            @Override
                            public void verify(
                                    AuthoritativeOutcomeObservation
                                            observation) {
                            }
                        },
                        DomainFidelityTestFixtures.CLOCK)
                        .sign(
                                AuthoritativeOutcomeTestFixtures
                                        .matched());
        JsonNode json = mapper.valueToTree(signed);
        JsonNode schema = mapper.readTree(
                Files.readString(schemaPath()));

        assertExact(json, schema);
        assertExact(
                json.path("selectionProof"),
                schema.at("/$defs/selectionProof"));
        assertExact(
                json.path("attributionWindow"),
                schema.at("/$defs/attributionWindow"));
        assertExact(
                json.path("authorityWatermarks").get(0),
                schema.at("/$defs/authorityWatermark"));
        assertExact(
                json.path("authorityFacts").get(0),
                schema.at("/$defs/authorityFact"));
        assertThat(textValues(
                schema.path("properties")
                        .path("reconciliation")
                        .path("enum")))
                .containsExactlyInAnyOrder(
                        "MATCH",
                        "MISMATCH",
                        "PENDING",
                        "CENSORED",
                        "CONFLICT");
    }

    @Test
    void protocolCannotCarryPayloadCredentialOrEndpointFields()
            throws Exception {
        String source = Files.readString(schemaPath());

        for (String forbidden : Set.of(
                "requestPayload",
                "responsePayload",
                "businessPayload",
                "customerId",
                "credential",
                "secret",
                "token",
                "password",
                "endpointUri",
                "stackTrace")) {
            assertThat(source)
                    .doesNotContain("\"" + forbidden + "\"");
        }
    }

    private static void assertExact(
            JsonNode value,
            JsonNode schema) {
        assertThat(schema.path(
                "additionalProperties").asBoolean(true))
                .isFalse();
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
}
