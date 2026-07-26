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

class ReadOnlyShadowSourceBindingProtocolSchemaTest {
    private static final String SCHEMA =
            "read-only-shadow-source-binding-v1.schema.json";
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void strictSchemaExactlyMatchesTheSignedPayloadFreeProtocol() throws Exception {
        ReadOnlyShadowSourceBinding value =
                new ReadOnlyShadowSourceBindingIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                Clock.fixed(
                                        ReadOnlyShadowJobTestFixtures.NOW,
                                        ZoneOffset.UTC)),
                        Clock.fixed(
                                ReadOnlyShadowJobTestFixtures.NOW,
                                ZoneOffset.UTC))
                        .sign(
                                ReadOnlyShadowJobTestFixtures.sourceBinding(
                                        "schema-source-pair",
                                        "candidate-run"));
        JsonNode json = mapper.valueToTree(value);
        JsonNode schema = mapper.readTree(
                Files.readString(schemaPath()));

        assertExact(json, schema);
        assertExact(
                json.path("baseline"),
                schema.at("/$defs/baselineObservation"));
        assertThat(schema.at(
                "/$defs/candidateEvidenceRef/allOf/1/properties/kind/const")
                .asText())
                .isEqualTo("MIRROR_EVIDENCE_BUNDLE");
        assertThat(schema.at(
                "/$defs/baselineObservation/properties/"
                        + "normalizedFactFingerprints/maxProperties")
                .asInt())
                .isEqualTo(4);
    }

    @Test
    void strictRegistrationSchemaOmitsDerivedAddressesAndSeal() throws Exception {
        ReadOnlyShadowSourceBinding source =
                ReadOnlyShadowJobTestFixtures.sourceBinding(
                        "registration-source-pair",
                        "candidate-run");
        var request =
                new ReadOnlyShadowSourceBindingRegistrationRequest(
                        ReadOnlyShadowSourceBindingRegistrationRequest
                                .SCHEMA_VERSION,
                        source.bindingId(),
                        source.revision(),
                        source.scope(),
                        source.scenarioCaseRef(),
                        source.targetCapabilityRef(),
                        source.candidatePlanRef(),
                        source.baselineBindingRef(),
                        source.comparisonPolicyRef(),
                        source.requestContextFingerprint(),
                        source.baseline(),
                        source.candidateEvidenceRef(),
                        source.validFrom(),
                        source.expiresAt(),
                        source.issuedAt());
        JsonNode json = mapper.valueToTree(request);
        JsonNode schema = mapper.readTree(
                Files.readString(schemaPath(
                        "read-only-shadow-source-binding-registration-request-v1"
                                + ".schema.json")));

        assertExact(json, schema);
        assertThat(json.has("bindingFingerprint")).isFalse();
        assertThat(json.has("baselineObservationFingerprint"))
                .isFalse();
        assertThat(json.has("bindingSeal")).isFalse();
        assertThat(request.toUnsignedBinding()
                .bindingFingerprint()).isBlank();
    }

    @Test
    void protocolCannotCarryPayloadCredentialOrEndpointFields() throws Exception {
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
                "stackTrace")) {
            assertThat(source)
                    .doesNotContain("\"" + forbidden + "\"");
        }
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

    private static Path schemaPath() {
        return schemaPath(SCHEMA);
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
