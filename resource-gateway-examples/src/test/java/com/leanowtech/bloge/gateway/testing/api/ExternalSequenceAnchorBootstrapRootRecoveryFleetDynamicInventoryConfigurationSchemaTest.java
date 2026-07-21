package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
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
    private static final String TRANSPORT_PREFIX = PREFIX + ".transport";
    private static final String ROOT_TRANSPORT_PREFIX = ROOT_PREFIX + ".transport";
    private static final String EXTERNAL_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties.PREFIX;
    private static final String MANAGED_NOTARY_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                    .ManagedTrustProperties.PREFIX;
    private static final String BOOTSTRAP_ROOT_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                    .BootstrapRootProperties.PREFIX;
    private static final String EXTERNAL_TRANSPORT_PREFIX = EXTERNAL_PREFIX + ".transport";
    private static final String MANAGED_NOTARY_TRANSPORT_PREFIX =
            MANAGED_NOTARY_PREFIX + ".transport";
    private static final String BOOTSTRAP_ROOT_TRANSPORT_PREFIX =
            BOOTSTRAP_ROOT_PREFIX + ".transport";

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
        assertThat(propertyNames(schema.at("/$defs/publicationTransport/properties")))
                .containsExactlyInAnyOrderElementsOf(recordProperties(
                        RecoveryFleetPublicationTransportProperties.class));
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/managedTrustRoots/additionalProperties").asBoolean())
                .isFalse();
        assertThat(schema.at("/$defs/publicationTransport/additionalProperties").asBoolean())
                .isFalse();
    }

    @Test
    void externalAnchorSchemaExactlyMatchesAllThreeStrictNestedPropertyRecords()
            throws Exception {
        JsonNode schema = externalSchema();

        assertThat(propertyNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(recordProperties(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                                .class));
        assertThat(propertyNames(schema.at("/$defs/managedTrust/properties")))
                .containsExactlyInAnyOrderElementsOf(recordProperties(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                                .ManagedTrustProperties.class));
        assertThat(propertyNames(schema.at("/$defs/bootstrapRoots/properties")))
                .containsExactlyInAnyOrderElementsOf(recordProperties(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                                .BootstrapRootProperties.class));
        assertThat(propertyNames(schema.at("/$defs/publicationTransport/properties")))
                .containsExactlyInAnyOrderElementsOf(recordProperties(
                        RecoveryFleetPublicationTransportProperties.class));
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/managedTrust/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/bootstrapRoots/additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/$defs/publicationTransport/additionalProperties").asBoolean())
                .isFalse();
    }

    @Test
    void schemaFreezesStaticManagedExclusionAndBoundedTransportPolicy() throws Exception {
        JsonNode schema = schema();

        assertThat(schema.path("allOf")).hasSize(4);
        assertThat(schema.at("/$defs/managedTrustRoots/allOf")).hasSize(2);
        assertThat(schema.at("/$defs/publicationTransport/allOf")).hasSize(4);
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
        assertThat(schema.at(
                "/$defs/publicationTransport/allOf/0/then/properties/enabled/const")
                .asBoolean()).isTrue();
        assertThat(schema.at(
                "/$defs/publicationTransport/allOf/1/then/properties/server-spki-pins/const")
                .asText()).isEmpty();
        assertThat(schema.at(
                "/$defs/publicationTransport/allOf/2/then/properties/server-spki-pins/minLength")
                .asInt()).isEqualTo(71);
        assertThat(schema.at(
                "/$defs/publicationTransport/allOf/3/then/properties/client-issuer-spki-pins/minLength")
                .asInt()).isEqualTo(71);
        assertThat(schema.at(
                "/$defs/publicationTransport/allOf/3/then/properties/expected-server-uri-san/minLength")
                .asInt()).isEqualTo(4);
    }

    @Test
    void Java25BuildPublishesDocumentedNestedSpringConfigurationMetadata() throws Exception {
        JsonNode metadata = projectConfigurationMetadata();
        Set<String> groups = metadata.path("groups").valueStream()
                .map(value -> value.path("name").asText()).collect(Collectors.toSet());
        assertThat(groups).contains(PREFIX, ROOT_PREFIX, TRANSPORT_PREFIX,
                ROOT_TRANSPORT_PREFIX, EXTERNAL_PREFIX,
                MANAGED_NOTARY_PREFIX, BOOTSTRAP_ROOT_PREFIX,
                EXTERNAL_TRANSPORT_PREFIX, MANAGED_NOTARY_TRANSPORT_PREFIX,
                BOOTSTRAP_ROOT_TRANSPORT_PREFIX);

        var properties = metadata.path("properties").valueStream()
                .filter(value -> value.path("name").asText().startsWith(PREFIX + "."))
                .toList();
        int expectedProperties = ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                .DynamicInventoryProperties.class.getRecordComponents().length
                + ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                .ManagedTrustRootProperties.class.getRecordComponents().length
                + ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                .class.getRecordComponents().length
                + ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                .ManagedTrustProperties.class.getRecordComponents().length
                + ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                .BootstrapRootProperties.class.getRecordComponents().length
                + 5 * RecoveryFleetPublicationTransportProperties.class
                .getRecordComponents().length - 9;
        assertThat(properties).hasSize(expectedProperties);
        assertThat(properties)
                .allSatisfy(value -> assertThat(value.path("description").asText())
                        .isNotBlank());
        assertThat(properties.stream().map(value -> value.path("name").asText()))
                .contains(ROOT_PREFIX + ".enabled",
                        ROOT_PREFIX + ".deployment-root-authority-keys-json",
                        ROOT_PREFIX + ".unknown-key-refresh-interval-seconds",
                        TRANSPORT_PREFIX + ".client-key-store-password-ref",
                        TRANSPORT_PREFIX + ".server-spki-pins",
                        TRANSPORT_PREFIX + ".certificate-identity-required",
                        TRANSPORT_PREFIX + ".expected-client-uri-san",
                        TRANSPORT_PREFIX + ".server-issuer-spki-pins",
                        ROOT_TRANSPORT_PREFIX + ".client-key-store-path",
                        EXTERNAL_PREFIX + ".enabled",
                        EXTERNAL_PREFIX + ".maximum-faults",
                        EXTERNAL_TRANSPORT_PREFIX + ".server-spki-pins",
                        MANAGED_NOTARY_PREFIX + ".publication-uri",
                        MANAGED_NOTARY_TRANSPORT_PREFIX + ".client-key-store-password-ref",
                        BOOTSTRAP_ROOT_PREFIX + ".genesis-json",
                        BOOTSTRAP_ROOT_TRANSPORT_PREFIX + ".client-key-store-path");
    }

    private JsonNode projectConfigurationMetadata() throws Exception {
        Enumeration<URL> resources = getClass().getClassLoader().getResources(
                "META-INF/spring-configuration-metadata.json");
        while (resources.hasMoreElements()) {
            try (InputStream input = resources.nextElement().openStream()) {
                JsonNode candidate = objectMapper.readTree(input);
                boolean ownsPrefix = candidate.path("groups").valueStream().anyMatch(
                        group -> PREFIX.equals(group.path("name").asText()));
                if (ownsPrefix) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException(
                "Resource Gateway Spring configuration metadata is missing");
    }

    @Test
    void configurationContractContainsNoPrivateSignerOrBusinessPayloadField() throws Exception {
        String source = Files.readString(schemaPath()) + Files.readString(externalSchemaPath());

        for (String forbidden : new String[]{"private-key", "signer-credential",
                "provider-token", "business-payload", "request-payload", "response-payload"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void legacyV1ConfigurationRemainsFrozenWithoutPublicationTransportFields()
            throws Exception {
        JsonNode legacy = objectMapper.readTree(Files.readString(legacySchemaPath()));

        assertThat(legacy.path("properties").has("transport")).isFalse();
        assertThat(legacy.at("/$defs/managedTrustRoots/properties").has("transport")).isFalse();
        assertThat(legacy.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void priorTransportSchemasRemainFrozenWithoutCertificateIdentityFields()
            throws Exception {
        JsonNode dynamic = objectMapper.readTree(Files.readString(legacyV2SchemaPath()));
        JsonNode external = objectMapper.readTree(Files.readString(legacyExternalSchemaPath()));

        assertThat(dynamic.at("/$defs/publicationTransport/properties")
                .has("certificate-identity-required")).isFalse();
        assertThat(dynamic.at("/$defs/publicationTransport/properties")
                .has("expected-client-uri-san")).isFalse();
        assertThat(external.at("/$defs/publicationTransport/properties")
                .has("server-issuer-spki-pins")).isFalse();
    }

    private JsonNode schema() throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath()));
    }

    private JsonNode externalSchema() throws Exception {
        return objectMapper.readTree(Files.readString(externalSchemaPath()));
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
                        + "configuration-v3.schema.json");
    }

    private static Path legacySchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-dynamic-inventory-"
                        + "configuration-v1.schema.json");
    }

    private static Path legacyV2SchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-dynamic-inventory-"
                        + "configuration-v2.schema.json");
    }

    private static Path externalSchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-external-anchor-"
                        + "configuration-v2.schema.json");
    }

    private static Path legacyExternalSchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-recovery-fleet-external-anchor-"
                        + "configuration-v1.schema.json");
    }
}
