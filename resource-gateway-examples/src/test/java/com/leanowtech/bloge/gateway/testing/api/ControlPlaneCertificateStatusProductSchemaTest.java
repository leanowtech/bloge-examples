package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlPlaneCertificateStatusProductSchemaTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void descriptorSchemasExactlyMatchEveryPublicJavaProjection() throws Exception {
        var source = new ControlPlaneCertificateStatusSource.Descriptor(
                ControlPlaneCertificateStatusSource.Descriptor.SCHEMA_VERSION,
                true, true, true, true, true, true);
        var admission = new ControlPlaneCertificateStatusAdmission.Descriptor(
                ControlPlaneCertificateStatusAdmission.Descriptor.SCHEMA_VERSION,
                true, true, 1, 1, 1, 0, 0, 60, "FRESH");
        var monitor = new ControlPlaneCertificateStatusMonitor.Descriptor(
                ControlPlaneCertificateStatusMonitor.Descriptor.SCHEMA_VERSION,
                ControlPlaneCertificateStatusMonitor.RefreshStatus.CURRENT,
                true, true, true, 1, 0, NOW, NOW.plusSeconds(60));
        var trust = new ControlPlaneCertificateStatusTrustStore.Descriptor("", true,
                "enterprise-ca", 1, 1, 1, 1,
                Map.of("privateMaterialPresent", false));

        assertRecord(source, "control-plane-certificate-status-source-descriptor-v1.schema.json");
        assertRecord(admission,
                "control-plane-certificate-status-admission-descriptor-v1.schema.json");
        assertRecord(monitor,
                "control-plane-certificate-status-monitor-descriptor-v1.schema.json");
        assertRecord(trust,
                "control-plane-certificate-status-trust-store-descriptor-v1.schema.json");
    }

    @Test
    void healthSchemaMatchesActualReadyAndUnavailableFieldSets() throws Exception {
        ControlPlaneCertificateStatusSource source = mock(
                ControlPlaneCertificateStatusSource.class);
        when(source.descriptor()).thenReturn(new ControlPlaneCertificateStatusSource.Descriptor(
                ControlPlaneCertificateStatusSource.Descriptor.SCHEMA_VERSION,
                true, true, true, true, true, true));
        ControlPlaneCertificateStatusAdmission admission = mock(
                ControlPlaneCertificateStatusAdmission.class);
        when(admission.descriptor()).thenReturn(
                new ControlPlaneCertificateStatusAdmission.Descriptor(
                        ControlPlaneCertificateStatusAdmission.Descriptor.SCHEMA_VERSION,
                        true, true, 1, 1, 1, 0, 0, 60, "FRESH"));
        ControlPlaneCertificateStatusTrustStore trust = mock(
                ControlPlaneCertificateStatusTrustStore.class);
        when(trust.descriptor()).thenReturn(new ControlPlaneCertificateStatusTrustStore.Descriptor(
                "", true, "enterprise-ca", 1, 1, 1, 1, Map.of()));
        ControlPlaneCertificateStatusMonitor monitor = mock(
                ControlPlaneCertificateStatusMonitor.class);
        when(monitor.descriptor()).thenReturn(new ControlPlaneCertificateStatusMonitor.Descriptor(
                ControlPlaneCertificateStatusMonitor.Descriptor.SCHEMA_VERSION,
                ControlPlaneCertificateStatusMonitor.RefreshStatus.CURRENT,
                true, true, true, 1, 0, NOW, NOW.plusSeconds(60)));
        var health = new ControlPlaneCertificateStatusHealth(
                monitor, source, trust, admission);
        JsonNode schema = schema("control-plane-certificate-status-health-v1.schema.json");

        assertProperties(objectMapper.valueToTree(health.health().getDetails()),
                schema.path("properties"));
        when(source.descriptor()).thenThrow(new IllegalStateException("secret"));
        assertProperties(objectMapper.valueToTree(health.health().getDetails()),
                schema.path("properties"));
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.at("/properties/productionReady/const").asBoolean()).isFalse();
    }

    @Test
    void configurationSchemaTracksBothSpringPropertyRecordsAndRuntimeBounds()
            throws Exception {
        JsonNode schema = schema(
                "control-plane-certificate-status-configuration-v1.schema.json");

        assertThat(propertyNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(recordPropertyNames(
                        ControlPlaneCertificateStatusRuntimeProperties.class));
        assertThat(propertyNames(schema.at("/$defs/transport/properties")))
                .containsExactlyInAnyOrderElementsOf(recordPropertyNames(
                        RecoveryFleetPublicationTransportProperties.class));
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.at("/$defs/transport/additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(schema.at("/properties/maximum-publication-bytes/maximum").asInt())
                .isEqualTo(2 * 1024 * 1024);
        assertThat(schema.at("/properties/authority-keys-json/maxLength").asInt())
                .isEqualTo(512 * 1024);
        assertThat(schema.at("/properties/maximum-batch/maximum").asInt()).isEqualTo(32);
    }

    @Test
    void schemasUseClosedMonitorVocabularyAndCannotCarrySensitiveMaterial()
            throws Exception {
        JsonNode monitor = schema(
                "control-plane-certificate-status-monitor-descriptor-v1.schema.json");
        assertThat(monitor.at("/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                                ControlPlaneCertificateStatusMonitor.RefreshStatus.values())
                        .map(Enum::name).toArray(String[]::new));

        String combined = "";
        for (String name : schemaNames()) {
            combined += Files.readString(schemaPath(name));
        }
        for (String forbidden : new String[]{
                "privateKey", "passwordValue", "certificatePem", "certificateBytes",
                "ocspResponse", "crlPayload", "responderUrl", "stackTrace",
                "exceptionMessage"}) {
            assertThat(combined).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private void assertRecord(Object value, String schemaName) throws Exception {
        JsonNode schema = schema(schemaName);
        assertProperties(objectMapper.valueToTree(value), schema.path("properties"));
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
    }

    private static Set<String> recordPropertyNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> kebab(component.getName()))
                .collect(Collectors.toSet());
    }

    private static Set<String> propertyNames(JsonNode properties) {
        return properties.properties().stream()
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(propertyNames(value)).containsExactlyInAnyOrderElementsOf(
                propertyNames(properties));
    }

    private static JsonNode schema(String name) throws Exception {
        return new ObjectMapper().readTree(Files.readString(schemaPath(name)));
    }

    private static Path schemaPath(String name) {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing", name);
    }

    private static Set<String> schemaNames() {
        return Set.of(
                "control-plane-certificate-status-source-descriptor-v1.schema.json",
                "control-plane-certificate-status-admission-descriptor-v1.schema.json",
                "control-plane-certificate-status-monitor-descriptor-v1.schema.json",
                "control-plane-certificate-status-trust-store-descriptor-v1.schema.json",
                "control-plane-certificate-status-health-v1.schema.json",
                "control-plane-certificate-status-configuration-v1.schema.json");
    }
}
