package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateRotationEventWatcherProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemasExactlyMatchSourceAndWatcherDescriptors() throws Exception {
        JsonNode sourceSchema = schema(
                "control-plane-certificate-rotation-event-source-descriptor-v1.schema.json");
        JsonNode watcherSchema = schema(
                "control-plane-certificate-rotation-event-watcher-descriptor-v1.schema.json");
        var source = new ControlPlaneCertificateRotationEventSource.Descriptor(
                ControlPlaneCertificateRotationEventSource.Descriptor.SCHEMA_VERSION,
                true, true, true, true, true);
        var watcher = new ControlPlaneCertificateRotationEventWatcher.Descriptor(
                ControlPlaneCertificateRotationEventWatcher.Descriptor.SCHEMA_VERSION,
                true, true, true, true, true, true,
                7, false, 2, 4, "IDLE", "NO_EVENTS");

        assertProperties(objectMapper.valueToTree(source), sourceSchema.path("properties"));
        assertProperties(objectMapper.valueToTree(watcher), watcherSchema.path("properties"));
        assertThat(sourceSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(watcherSchema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void schemaVersionsSecurityConstantsAndStatusesMatchJavaExactly() throws Exception {
        JsonNode sourceSchema = schema(
                "control-plane-certificate-rotation-event-source-descriptor-v1.schema.json");
        JsonNode watcherSchema = schema(
                "control-plane-certificate-rotation-event-watcher-descriptor-v1.schema.json");

        assertThat(sourceSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEventSource.Descriptor.SCHEMA_VERSION);
        assertThat(watcherSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateRotationEventWatcher.Descriptor.SCHEMA_VERSION);
        for (String property : new String[]{
                "authenticatedProtocol", "privateTrustStore", "serverSpkiPinned",
                "mutualTls", "certificateIdentityBound"}) {
            assertThat(sourceSchema.at("/properties/" + property + "/const").asBoolean())
                    .isTrue();
        }
        assertThat(watcherSchema.at("/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                                ControlPlaneCertificateRotationEventWatcher.WatcherStatus.values())
                        .map(Enum::name).toArray(String[]::new));
    }

    @Test
    void watcherDescriptorCannotExposeSourceIdentityEventOrTlsMaterial() throws Exception {
        String source = Files.readString(schemaPath(
                "control-plane-certificate-rotation-event-watcher-descriptor-v1.schema.json"));

        for (String forbidden : new String[]{
                "deploymentScopeId", "instanceId", "startupId", "eventId", "pageFingerprint",
                "materialId", "settingsFingerprint", "certificate", "privateKey", "password",
                "secretRef", "keyStore", "trustStore", "sourceUri", "exception",
                "errorMessage", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private JsonNode schema(String name) throws Exception {
        return objectMapper.readTree(Files.readString(schemaPath(name)));
    }

    private Path schemaPath(String name) {
        Path path = Path.of("..", "docs", "schemas", "resource-gateway-testing", name);
        return Files.exists(path) ? path : Path.of("docs", "schemas",
                "resource-gateway-testing", name);
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(propertyNames(value)).containsExactlyInAnyOrderElementsOf(
                propertyNames(properties));
    }

    private static LinkedHashSet<String> propertyNames(JsonNode node) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
