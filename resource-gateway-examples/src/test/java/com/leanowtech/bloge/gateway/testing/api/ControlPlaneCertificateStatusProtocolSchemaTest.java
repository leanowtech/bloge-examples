package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateStatusProtocolSchemaTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaExactlyMatchesEverySerializedProtocolRecord() throws Exception {
        JsonNode schema = schema();
        ControlPlaneCertificateStatusPublication publication = publication();

        assertProperties(objectMapper.valueToTree(publication),
                schema.at("/$defs/publication/properties"));
        assertProperties(objectMapper.valueToTree(publication.material()),
                schema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(publication.material().targets().getFirst()),
                schema.at("/$defs/targetStatus/properties"));
        assertProperties(objectMapper.valueToTree(
                        publication.material().targets().getFirst().certificates().getFirst()),
                schema.at("/$defs/certificateEvidence/properties"));
        assertProperties(objectMapper.valueToTree(publication.signatures().getFirst()),
                schema.at("/$defs/authoritySignature/properties"));
        for (String definition : List.of("publication", "material", "targetStatus",
                "certificateEvidence", "authoritySignature")) {
            assertThat(schema.at("/$defs/" + definition + "/additionalProperties")
                    .asBoolean(true)).isFalse();
        }
    }

    @Test
    void schemaVersionsBoundsAndClosedVocabulariesMatchJava() throws Exception {
        JsonNode schema = schema();

        assertThat(schema.at("/$defs/publication/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateStatusPublication.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/material/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateStatusPublication.Material.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/material/properties/targets/maxItems").asInt())
                .isEqualTo(128);
        assertThat(schema.at("/$defs/publication/properties/signatures/maxItems").asInt())
                .isEqualTo(32);
        assertThat(schema.at("/$defs/certificateEvidence/properties/status/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                                ControlPlaneCertificateStatusPublication.CertificateStatus.values())
                        .map(Enum::name).toArray(String[]::new));
        assertThat(schema.at("/$defs/certificateEvidence/properties/evidenceType/enum"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(Arrays.stream(
                                ControlPlaneCertificateStatusPublication.EvidenceType.values())
                        .map(Enum::name).toArray(String[]::new));
    }

    @Test
    void protocolCannotCarryRawCertificatesResponderLocationsOrCredentials() throws Exception {
        String source = Files.readString(schemaPath());
        for (String forbidden : new String[]{
                "certificateBytes", "certificatePem", "privateKey", "password", "secretRef",
                "keyStore", "trustStore", "responderUrl", "crlUrl", "ocspResponse",
                "crlPayload", "exception", "stackTrace"}) {
            assertThat(source).doesNotContain("\"" + forbidden + "\"");
        }
    }

    private ControlPlaneCertificateStatusPublication publication() {
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        var client = new ControlPlaneCertificateStatusPublication.CertificateEvidence(
                ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                ControlPlaneCertificateStatusPublication.EvidenceType.OCSP,
                FINGERPRINT, FINGERPRINT, FINGERPRINT, "CERTIFICATE_GOOD",
                now, now, now.plusSeconds(3600));
        var server = new ControlPlaneCertificateStatusPublication.CertificateEvidence(
                ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                ControlPlaneCertificateStatusPublication.EvidenceType.CRL,
                FINGERPRINT, FINGERPRINT, FINGERPRINT, "CERTIFICATE_GOOD",
                now, now, now.plusSeconds(3600));
        var target = new ControlPlaneCertificateStatusPublication.TargetStatus(
                "recovery-fleet.inventory", 1, FINGERPRINT, List.of(client, server));
        var material = new ControlPlaneCertificateStatusPublication.Material(
                ControlPlaneCertificateStatusPublication.Material.SCHEMA_VERSION,
                "enterprise-pki", "status-001", "rg-staging-sg", 1, "", FINGERPRINT,
                now, now.plusSeconds(3600), List.of(target));
        var signature = new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                "authority-a", "key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(new byte[64]));
        return new ControlPlaneCertificateStatusPublication(
                ControlPlaneCertificateStatusPublication.SCHEMA_VERSION,
                material, FINGERPRINT, List.of(signature));
    }

    private static JsonNode schema() throws Exception {
        return new ObjectMapper().readTree(Files.readString(schemaPath()));
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "control-plane-certificate-status-publication-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(value.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(
                        properties.properties().stream().map(java.util.Map.Entry::getKey).toList());
    }
}
