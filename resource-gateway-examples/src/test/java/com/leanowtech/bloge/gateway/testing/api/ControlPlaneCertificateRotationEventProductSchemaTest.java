package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateRotationEventProductSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void configurationSchemaFreezesEveryTopLevelAndNestedTransportField() throws Exception {
        JsonNode schema = schema(
                "control-plane-certificate-rotation-event-source-configuration-v1.schema.json");

        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder(
                "enabled", "required", "endpoint-uri", "baseline-sequence",
                "baseline-page-fingerprint", "poll-interval-seconds",
                "maximum-pages-per-poll", "request-timeout-millis",
                "maximum-page-bytes", "clock-skew-seconds",
                "maximum-page-lifetime-seconds", "allow-insecure-loopback", "transport");
        assertThat(names(schema.at("/$defs/transport/properties")))
                .containsExactlyInAnyOrder(
                        "enabled", "required", "trust-store-path",
                        "trust-store-password-ref", "client-key-store-path",
                        "client-key-store-password-ref", "server-spki-pins",
                        "certificate-identity-required", "expected-client-subject-dn",
                        "expected-client-uri-san", "client-issuer-spki-pins",
                        "expected-server-uri-san", "server-issuer-spki-pins");
        assertThat(schema.at("/$defs/transport/additionalProperties").asBoolean(true))
                .isFalse();
    }

    @Test
    void configurationSchemaMatchesJavaBoundsAndFailClosedDefaults() throws Exception {
        JsonNode schema = schema(
                "control-plane-certificate-rotation-event-source-configuration-v1.schema.json");

        assertThat(schema.at("/properties/maximum-pages-per-poll/maximum").asInt())
                .isEqualTo(32);
        assertThat(schema.at("/properties/maximum-page-bytes/maximum").asInt())
                .isEqualTo(512 * 1024);
        assertThat(schema.at("/properties/request-timeout-millis/minimum").asInt())
                .isEqualTo(100);
        assertThat(schema.at("/properties/request-timeout-millis/maximum").asInt())
                .isEqualTo(30_000);
        assertThat(schema.at("/properties/clock-skew-seconds/maximum").asInt())
                .isEqualTo(300);
        assertThat(schema.at("/allOf/1/then/properties/allow-insecure-loopback/const")
                .asBoolean(true)).isFalse();
        assertThat(schema.at("/allOf/2/then/properties/transport/allOf/1/properties/required/const")
                .asBoolean()).isTrue();
        assertThat(schema.at(
                "/$defs/transport/allOf/0/then/properties/trust-store-path/minLength")
                .asInt()).isEqualTo(1);
        assertThat(schema.at(
                "/$defs/transport/allOf/0/then/properties/certificate-identity-required/const")
                .asBoolean()).isTrue();
    }

    @Test
    void healthSchemaExactlyMatchesRuntimeDetailsAndClosedStateVocabulary() throws Exception {
        JsonNode schema = schema(
                "control-plane-certificate-rotation-event-watcher-health-v1.schema.json");
        var descriptor = new ControlPlaneCertificateRotationEventWatcher.Descriptor(
                ControlPlaneCertificateRotationEventWatcher.Descriptor.SCHEMA_VERSION,
                true, true, true, true, true, true,
                4, false, 2, 3, "IDLE", "NO_EVENTS");
        var health = new ControlPlaneCertificateRotationEventWatcherHealth(
                () -> descriptor, true).health();

        assertThat(names(objectMapper.valueToTree(health.getDetails())))
                .containsExactlyInAnyOrderElementsOf(names(schema.path("properties")));
        Set<String> expectedStatuses = Arrays.stream(
                        ControlPlaneCertificateRotationEventWatcher.WatcherStatus.values())
                .map(Enum::name).collect(Collectors.toSet());
        expectedStatuses.add("UNAVAILABLE");
        assertThat(schema.at("/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrderElementsOf(expectedStatuses);
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void healthContractCannotExposeSourceOrTlsIdentityMaterial() throws Exception {
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-event-watcher-health-v1.schema.json"));

        for (String forbidden : new String[]{
                "deploymentScopeId", "instanceId", "startupId", "eventId",
                "pageFingerprint", "endpointUri", "certificate", "privateKey",
                "password", "secretRef", "keyStore", "trustStore", "exception",
                "errorMessage", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void testAndStagingProfilesExactlyPublishTheFrozenConfigurationKeys()
            throws Exception {
        JsonNode schema = schema(
                "control-plane-certificate-rotation-event-source-configuration-v1.schema.json");
        Set<String> expected = names(schema.path("properties"));
        Set<String> expectedTransport = names(schema.at("/$defs/transport/properties"));
        YAMLMapper yaml = new YAMLMapper();

        for (String profile : new String[]{"test", "staging"}) {
            JsonNode configured = yaml.readTree(Files.readString(profilePath(profile)))
                    .at("/gateway/testing/control-plane-certificate-rotation-event-source");
            assertThat(names(configured)).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(names(configured.path("transport")))
                    .containsExactlyInAnyOrderElementsOf(expectedTransport);
        }
        JsonNode staging = yaml.readTree(Files.readString(profilePath("staging")))
                .at("/gateway/testing/control-plane-certificate-rotation-event-source");
        assertThat(staging.path("required").asText()).isEqualTo(
                "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_ENABLED:false}");
        assertThat(staging.at("/transport/required").asText()).isEqualTo(
                "${RG_TEST_CONTROL_PLANE_CERTIFICATE_ROTATION_EVENT_SOURCE_TRANSPORT_ENABLED:false}");
    }

    @Test
    void java25BuildPublishesDocumentedNestedSpringConfigurationMetadata()
            throws Exception {
        JsonNode metadata = projectConfigurationMetadata();
        String prefix = ControlPlaneCertificateRotationEventSourceProperties.PREFIX;

        assertThat(metadata.path("groups").valueStream()
                .map(value -> value.path("name").asText()))
                .contains(prefix, prefix + ".transport");
        var properties = metadata.path("properties").valueStream()
                .filter(value -> value.path("name").asText().startsWith(prefix + "."))
                .toList();
        assertThat(properties).hasSize(25)
                .allSatisfy(value -> assertThat(value.path("description").asText())
                        .isNotBlank());
        assertThat(properties.stream().map(value -> value.path("name").asText()))
                .contains(prefix + ".baseline-page-fingerprint",
                        prefix + ".maximum-pages-per-poll",
                        prefix + ".transport.client-key-store-password-ref",
                        prefix + ".transport.server-spki-pins",
                        prefix + ".transport.certificate-identity-required",
                        prefix + ".transport.expected-server-uri-san");
    }

    private JsonNode schema(String name) throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath(name)));
    }

    private Path schemaPath(String name) {
        Path path = Path.of("..", "docs", "schemas", "resource-gateway-testing", name);
        return Files.exists(path) ? path : Path.of("docs", "schemas",
                "resource-gateway-testing", name);
    }

    private Path profilePath(String profile) {
        Path path = Path.of("src", "main", "resources", "application-" + profile + ".yml");
        return Files.exists(path) ? path : Path.of("resource-gateway-examples", "src",
                "main", "resources", "application-" + profile + ".yml");
    }

    private JsonNode projectConfigurationMetadata() throws Exception {
        String prefix = ControlPlaneCertificateRotationEventSourceProperties.PREFIX;
        Enumeration<URL> resources = getClass().getClassLoader().getResources(
                "META-INF/spring-configuration-metadata.json");
        while (resources.hasMoreElements()) {
            try (InputStream input = resources.nextElement().openStream()) {
                JsonNode candidate = objectMapper.readTree(input);
                boolean ownsPrefix = candidate.path("groups").valueStream().anyMatch(
                        group -> prefix.equals(group.path("name").asText()));
                if (ownsPrefix) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException(
                "Certificate rotation event source configuration metadata is missing");
    }

    private static LinkedHashSet<String> names(JsonNode properties) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
