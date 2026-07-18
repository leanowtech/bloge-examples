package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class SuiteStabilityAuthorityProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesSerializedRequestResponseAndKeyFreeDescriptors() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));
        TestSuiteStabilityAuthorityRequest request = request(objectMapper);
        TestSuiteStabilityAuthorityResponse response = response(
                objectMapper, keyPair(), request,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "");
        TestSuiteStabilityAuthorityTrustStore.Descriptor trustDescriptor =
                TestSuiteStabilityAuthorityTrustStore.unavailable().descriptor();
        TestSuiteStabilityJobAuthorizer.Descriptor authorizerDescriptor =
                new TestSuiteStabilityJobAuthorizer.Descriptor(
                        "", true, "HTTPS_SIGNED_PDP", AUTHORITY_ID,
                        java.util.Map.of(
                                "protocolVersion", TestSuiteStabilityAuthorityRequest.SCHEMA_VERSION,
                                "responseProtocolVersion",
                                TestSuiteStabilityAuthorityResponse.SCHEMA_VERSION,
                                "signedDecisions", true,
                                "challengeBound", true,
                                "redirectsFollowed", false,
                                "automaticRetries", false,
                                "privateMaterialPresent", false,
                                "requestTimeoutMillis", Duration.ofSeconds(3).toMillis()));

        assertProperties(objectMapper.valueToTree(request),
                schema.at("/$defs/request/properties"));
        assertProperties(objectMapper.valueToTree(request.principal()),
                schema.at("/$defs/principal/properties"));
        assertProperties(objectMapper.valueToTree(request.suiteRef()),
                schema.at("/$defs/suiteRef/properties"));
        assertProperties(objectMapper.valueToTree(response),
                schema.at("/$defs/response/properties"));
        assertProperties(objectMapper.valueToTree(response.signature()),
                schema.at("/$defs/signature/properties"));
        assertProperties(objectMapper.valueToTree(trustDescriptor),
                schema.at("/$defs/trustDescriptor/properties"));
        assertProperties(objectMapper.valueToTree(authorizerDescriptor),
                schema.at("/$defs/authorizerDescriptor/properties"));

        assertThat(schema.at("/$defs/request/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityAuthorityRequest.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/response/properties/schemaVersion/const").asText())
                .isEqualTo(TestSuiteStabilityAuthorityResponse.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/response/properties/decision/enum"))
                .extracting(JsonNode::asText).containsExactly("AUTHORIZED", "REVOKED");
        assertThat(List.of("request", "response", "principal", "suiteRef", "signature",
                "trustDescriptor", "authorizerDescriptor"))
                .allSatisfy(definition -> assertThat(
                        schema.at("/$defs/" + definition + "/additionalProperties")
                                .asBoolean()).isFalse());
    }

    @Test
    void schemaAndRequestSourceExcludeCredentialsCorrelationsAndBusinessData() throws Exception {
        String schema = Files.readString(schemaPath());
        String request = objectMapper.writeValueAsString(request(objectMapper));

        for (String forbidden : List.of("\"credential\"", "\"correlationId\"",
                "\"metadata\"", "\"fixture\"", "\"context\"", "\"payload\"",
                "\"privateKey\"", "\"encodedPrivateKey\"")) {
            assertThat(schema).doesNotContain(forbidden);
            assertThat(request).doesNotContain(forbidden);
        }
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "suite-stability-authority-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
