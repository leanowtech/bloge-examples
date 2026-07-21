package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateStatusSourceResponseProtocolTest {

    private static final String SCOPE = "resource-gateway-staging";
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void bindsAnOptionalCandidateToTheSameScopeAndAHeadAtOrBeyondIt() {
        var publication = publication(SCOPE, 2);
        var laterHead = sourceHead(SCOPE, 4, fingerprint('4'));
        var exactHead = sourceHead(SCOPE, 2, publication.materialFingerprint());

        assertThat(new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                laterHead, publication).hasPublication()).isTrue();
        assertThat(new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                exactHead, publication).publication()).isEqualTo(publication);
        assertThat(new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                sourceHead(SCOPE, 0, fingerprint('0')), null).hasPublication()).isFalse();

        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                sourceHead(SCOPE, 1, fingerprint('1')), publication))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                sourceHead(SCOPE, 2, fingerprint('9')), publication))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                sourceHead("another-scope", 2, publication.materialFingerprint()),
                publication)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                sourceHead(SCOPE, 2, publication.materialFingerprint(), fingerprint('e')),
                publication)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responseAndDurableFloorSchemasExactlyMatchTheJavaProtocols() throws Exception {
        var response = new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                sourceHead(SCOPE, 0, fingerprint('0')), null);
        var floor = new ControlPlaneCertificateStatusSourceHeadFloor.Snapshot(
                ControlPlaneCertificateStatusSourceHeadFloor.Snapshot.SCHEMA_VERSION,
                SCOPE, 0, fingerprint('0'), 0, fingerprint('0'), "", "",
                null, null, null);
        JsonNode responseSchema = schema(
                "control-plane-certificate-status-source-response-v2.schema.json");
        JsonNode floorSchema = schema(
                "control-plane-certificate-status-source-head-floor-snapshot-v1.schema.json");

        assertProperties(objectMapper.valueToTree(response),
                responseSchema.path("properties"));
        assertProperties(objectMapper.valueToTree(floor), floorSchema.path("properties"));
        assertThat(responseSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(floorSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(responseSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION);
        assertThat(floorSchema.at("/properties/schemaVersion/const").asText())
                .isEqualTo(ControlPlaneCertificateStatusSourceHeadFloor.Snapshot.SCHEMA_VERSION);
    }

    @Test
    void fetchResultKeepsSuccessfulAndFailureFormsClosed() {
        var head = sourceHead(SCOPE, 0, fingerprint('0'));

        assertThat(ControlPlaneCertificateStatusSource.FetchResult.unchanged(head)
                .exactSourceHead()).isTrue();
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSource.FetchResult(
                ControlPlaneCertificateStatusSource.FetchStatus.UNCHANGED,
                null, head, "STALE_REASON"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateStatusSource.FetchResult(
                ControlPlaneCertificateStatusSource.FetchStatus.SOURCE_UNAVAILABLE,
                null, head, "CERTIFICATE_STATUS_SOURCE_UNAVAILABLE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durableSnapshotExposesExactLagOnlyWhileTheProofIsFreshAndInRange() {
        var snapshot = new ControlPlaneCertificateStatusSourceHeadFloor.Snapshot(
                ControlPlaneCertificateStatusSourceHeadFloor.Snapshot.SCHEMA_VERSION,
                SCOPE, 0, fingerprint('0'), 8, fingerprint('8'), "head-008",
                fingerprint('a'), NOW, NOW.plusSeconds(60), NOW.plusSeconds(1));

        assertThat(snapshot.exactLagFrom(3, fingerprint('3'), NOW.plusSeconds(2)))
                .isEqualTo(5);
        assertThat(snapshot.exactLagFrom(8, fingerprint('9'), NOW.plusSeconds(2)))
                .isEqualTo(-1);
        assertThat(snapshot.exactLagFrom(9, fingerprint('9'), NOW.plusSeconds(2)))
                .isEqualTo(-1);
        assertThat(snapshot.exactLagFrom(-1, fingerprint('9'), NOW.plusSeconds(2)))
                .isEqualTo(-1);
        assertThat(snapshot.exactLagFrom(3, fingerprint('3'), NOW.plusSeconds(60)))
                .isEqualTo(-1);
    }

    private static ControlPlaneCertificateStatusPublication publication(
            String scope, long sequence) {
        var client = evidence(
                ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT, 'c');
        var server = evidence(
                ControlPlaneCertificateStatusPublication.CertificateRole.SERVER, 'd');
        var target = new ControlPlaneCertificateStatusPublication.TargetStatus(
                "recovery-fleet.publisher", 1, fingerprint('a'),
                List.of(client, server));
        var material = new ControlPlaneCertificateStatusPublication.Material(
                ControlPlaneCertificateStatusPublication.Material.SCHEMA_VERSION,
                "enterprise-pki", "status-002", scope, sequence,
                fingerprint('1'), fingerprint('f'), NOW, NOW.plusSeconds(60),
                List.of(target));
        return new ControlPlaneCertificateStatusPublication(
                ControlPlaneCertificateStatusPublication.SCHEMA_VERSION, material,
                fingerprint('2'), List.of(signature()));
    }

    private static ControlPlaneCertificateStatusPublication.CertificateEvidence evidence(
            ControlPlaneCertificateStatusPublication.CertificateRole role, char value) {
        return new ControlPlaneCertificateStatusPublication.CertificateEvidence(role,
                ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                ControlPlaneCertificateStatusPublication.EvidenceType.OCSP,
                fingerprint(value), fingerprint('e'), fingerprint('6'),
                "CERTIFICATE_GOOD", NOW, NOW, NOW.plusSeconds(120));
    }

    private static ControlPlaneCertificateStatusSourceHead sourceHead(
            String scope, long sequence, String publicationFingerprint) {
        return sourceHead(scope, sequence, publicationFingerprint, fingerprint('f'));
    }

    private static ControlPlaneCertificateStatusSourceHead sourceHead(
            String scope,
            long sequence,
            String publicationFingerprint,
            String policyFingerprint) {
        var material = new ControlPlaneCertificateStatusSourceHead.Material(
                ControlPlaneCertificateStatusSourceHead.Material.SCHEMA_VERSION,
                "enterprise-pki", "head-%03d".formatted(sequence), scope, sequence,
                publicationFingerprint, policyFingerprint, NOW, NOW.plusSeconds(60));
        return new ControlPlaneCertificateStatusSourceHead(
                ControlPlaneCertificateStatusSourceHead.SCHEMA_VERSION, material,
                fingerprint('a'), List.of(signature()));
    }

    private static ControlPlaneCertificateStatusPublication.AuthoritySignature signature() {
        return new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                "authority-a", "key-a", "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[64]));
    }

    private static JsonNode schema(String name) throws Exception {
        return new ObjectMapper().readTree(Files.readString(schemaPath(name)));
    }

    private static Path schemaPath(String name) {
        Path moduleRelative = Path.of("..", "docs", "schemas",
                "resource-gateway-testing", name);
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs", "schemas",
                "resource-gateway-testing", name);
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(value.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(
                        properties.properties().stream().map(Map.Entry::getKey).toList());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
