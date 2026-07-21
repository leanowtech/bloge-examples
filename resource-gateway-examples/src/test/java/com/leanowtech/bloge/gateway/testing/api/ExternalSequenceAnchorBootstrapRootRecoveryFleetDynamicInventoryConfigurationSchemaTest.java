package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationSchemaTest {

    private static final String PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .DynamicInventoryProperties.PREFIX;
    private static final String ROOT_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .ManagedTrustRootProperties.PREFIX;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaFieldsExactlyMatchBothStrictSpringPropertyRecords() throws Exception {
        JsonNode schema = schema();

        assertThat(propertyNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(recordProperties(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                                .DynamicInventoryProperties.class));
        assertThat(propertyNames(schema.at("/$defs/managedTrustRoots/properties")))
                .containsExactlyInAnyOrderElementsOf(recordProperties(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                                .ManagedTrustRootProperties.class));
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/managedTrustRoots/additionalProperties").asBoolean())
                .isFalse();
    }

    @Test
    void schemaFreezesStaticManagedExclusionAndBoundedTransportPolicy() throws Exception {
        JsonNode schema = schema();

        assertThat(schema.path("allOf")).hasSize(4);
        assertThat(schema.at("/$defs/managedTrustRoots/allOf")).hasSize(2);
        assertThat(schema.at("/allOf/2/then/properties/signature-threshold/minimum")
                .asInt()).isOne();
        assertThat(schema.at("/allOf/3/then/properties/signature-threshold/const")
                .asInt()).isZero();
        assertThat(schema.at("/$defs/managedTrustRoots/allOf/0/then/properties/enabled/const")
                .asBoolean()).isTrue();
        assertThat(schema.at("/properties/request-timeout-millis/minimum").asInt())
                .isEqualTo(100);
        assertThat(schema.at("/properties/request-timeout-millis/maximum").asInt())
                .isEqualTo(30_000);
        assertThat(schema.at("/$defs/managedTrustRoots/properties/maximum-snapshot-age-seconds/maximum")
                .asInt()).isEqualTo(86_400);
    }

    @Test
    void Java25BuildPublishesDocumentedNestedSpringConfigurationMetadata() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring-configuration-metadata.json")) {
            assertThat(input).as("generated Spring configuration metadata").isNotNull();
            JsonNode metadata = objectMapper.readTree(input);
            Set<String> groups = metadata.path("groups").valueStream()
                    .map(value -> value.path("name").asText()).collect(Collectors.toSet());
            assertThat(groups).contains(PREFIX, ROOT_PREFIX);

            var properties = metadata.path("properties").valueStream()
                    .filter(value -> value.path("name").asText().startsWith(PREFIX + "."))
                    .toList();
            int expectedProperties = ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .DynamicInventoryProperties.class.getRecordComponents().length
                    + ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .ManagedTrustRootProperties.class.getRecordComponents().length - 1;
            assertThat(properties).hasSize(expectedProperties);
            assertThat(properties)
                    .allSatisfy(value -> assertThat(value.path("description").asText())
                            .isNotBlank());
            assertThat(properties.stream().map(value -> value.path("name").asText()))
                    .contains(ROOT_PREFIX + ".enabled",
                            ROOT_PREFIX + ".deployment-root-authority-keys-json",
                            ROOT_PREFIX + ".unknown-key-refresh-interval-seconds");
        }
    }

    @Test
    void configurationContractContainsNoPrivateSignerOrBusinessPayloadField() throws Exception {
        String source = Files.readString(schemaPath());

        for (String forbidden : new String[]{"private-key", "signer-credential",
                "provider-token", "business-payload", "request-payload", "response-payload"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private JsonNode schema() throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath()));
    }

    private static Set<String> propertyNames(JsonNode properties) {
        return properties.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static Set<String> recordProperties(Class<?> recordType) {
        return java.util.Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName).map(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationSchemaTest
                                ::kebabCase)
                .collect(Collectors.toSet());
    }

    private static String kebabCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(
                java.util.Locale.ROOT);
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-dynamic-inventory-"
                        + "configuration-v1.schema.json");
    }
}
