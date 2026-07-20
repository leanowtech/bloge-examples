package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class TestSecretAuthorityProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesRequestResponseNestedMaterialAndDescriptors() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));
        TestSecretAuthorityRequest request = request(objectMapper);
        TestSecretAuthorityResponse response = response(objectMapper, keyPair(), request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "");
        TestSecretAuthorityTrustStore.Descriptor trust =
                TestSecretAuthorityTrustStore.unavailable().descriptor();
        DynamicJwksTestSecretAuthorityTrustStore.RefreshSnapshot refresh =
                new DynamicJwksTestSecretAuthorityTrustStore.RefreshSnapshot(
                        DynamicJwksTestSecretAuthorityTrustStore.RefreshSnapshot.SCHEMA_VERSION,
                        true, "HEALTHY", 1, 1, NOW, 1, 0, "", 30, 60);
        TestSecretAuthority.Descriptor authority = new TestSecretAuthority.Descriptor(
                "", true, "HTTPS_SIGNED_TEST_SECRET_AUTHORITY", AUTHORITY_ID,
                Map.ofEntries(
                        Map.entry("protocolVersion", TestSecretAuthorityRequest.SCHEMA_VERSION),
                        Map.entry("responseProtocolVersion",
                                TestSecretAuthorityResponse.SCHEMA_VERSION),
                        Map.entry("signedResponses", true),
                        Map.entry("challengeBound", true),
                        Map.entry("credentialFree", true),
                        Map.entry("redirectsFollowed", false),
                        Map.entry("automaticRetries", false),
                        Map.entry("privateMaterialPresent", false),
                        Map.entry("requestTimeoutMillis", Duration.ofSeconds(3).toMillis())));

        assertProperties(objectMapper.valueToTree(request),
                schema.at("/$defs/request/properties"));
        assertProperties(objectMapper.valueToTree(request.context()),
                schema.at("/$defs/resolutionContext/properties"));
        assertProperties(objectMapper.valueToTree(response),
                schema.at("/$defs/response/properties"));
        assertProperties(objectMapper.valueToTree(response.secrets().get(ALIAS)),
                schema.at("/$defs/secretMaterial/properties"));
        assertProperties(objectMapper.valueToTree(response.signature()),
                schema.at("/$defs/signature/properties"));
        assertProperties(objectMapper.valueToTree(trust),
                schema.at("/$defs/trustDescriptor/properties"));
        assertProperties(objectMapper.valueToTree(refresh),
                schema.at("/$defs/trustRefreshSnapshot/properties"));
        assertProperties(objectMapper.valueToTree(authority),
                schema.at("/$defs/authorityDescriptor/properties"));

        assertThat(schema.at("/$defs/request/properties/schemaVersion/const").asText())
                .isEqualTo(TestSecretAuthorityRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/response/properties/schemaVersion/const").asText())
                .isEqualTo(TestSecretAuthorityResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/trustRefreshSnapshot/properties/schemaVersion/const")
                .asText()).isEqualTo(
                DynamicJwksTestSecretAuthorityTrustStore.RefreshSnapshot.SCHEMA_VERSION);
        assertThat(fieldNames(schema.at("/$defs/trustProperties/properties")))
                .containsExactlyInAnyOrderElementsOf(
                        TestSecretAuthorityTrustStore.DESCRIPTOR_PROPERTIES);
        assertThat(fieldNames(schema.at("/$defs/authorityProperties/properties")))
                .containsExactlyInAnyOrderElementsOf(TestSecretAuthority.DESCRIPTOR_PROPERTIES);
        assertThat(List.of("request", "response", "resolutionContext", "secretMaterial",
                "signature", "trustDescriptor", "trustRefreshSnapshot",
                "authorityDescriptor"))
                .allSatisfy(definition -> assertThat(schema.at("/$defs/" + definition
                        + "/additionalProperties").asBoolean()).isFalse());
    }

    @Test
    void serializedRequestExcludesCredentialsCorrelationsBusinessPayloadAndValues()
            throws Exception {
        String request = objectMapper.writeValueAsString(request(objectMapper));
        for (String forbidden : List.of("\"credential\"", "\"correlationId\"",
                "\"graphInput\"", "\"fixturePayload\"", "\"evidence\"",
                "\"privateKey\"", "\"value\"", VALUE)) {
            assertThat(request).doesNotContain(forbidden);
        }
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "test-secret-authority-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value)).containsExactlyInAnyOrderElementsOf(fieldNames(properties));
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
