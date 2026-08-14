package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionalDataPlaneCertificationVerifierTest {
    private static final String ROOT = "/schemas/resource-gateway-mirror/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RegionalDataPlaneCertificationVerifier verifier =
            new RegionalDataPlaneCertificationVerifier();

    @Test
    void packagedFixturesVerifyWithoutServerOrSpring() throws Exception {
        JsonNode contract = resource(
                "regional-data-plane-deployment-contract-v1.fixture.json");
        JsonNode certification = resource(
                "regional-data-plane-certification-v1.fixture.json");
        JsonNode isolation = resource(
                "mirror-deployment-isolation-attestation-bundle-v2.fixture.json");

        var verified = verifier.require(contract, certification, isolation,
                Instant.parse("2026-08-10T00:00:02Z"),
                Instant.parse("2026-08-10T00:00:03Z"),
                (seal, artifact) -> seal.path("materialFingerprint").asText().equals(
                        "sha256:0cf14c2e139e4c0ae9d84ef94ffe40519c893061ffba22e8c34906d18204689c"));

        assertThat(verified.contractId()).isEqualTo("regional-data-plane:sg");
        assertThat(verified.certificationId()).isEqualTo("regional-certification:sg");
        assertThat(verified.isolationDecisionFingerprint())
                .isEqualTo(isolation.path("bundleFingerprint").asText());
    }

    @Test
    void tamperedContentAddressOrExternalSealFailsClosed() throws Exception {
        JsonNode contract = resource(
                "regional-data-plane-deployment-contract-v1.fixture.json");
        ObjectNode certification = (ObjectNode) resource(
                "regional-data-plane-certification-v1.fixture.json");
        JsonNode isolation = resource(
                "mirror-deployment-isolation-attestation-bundle-v2.fixture.json");
        certification.put("issuer", "security:another-authority");

        assertThatThrownBy(() -> require(contract, certification, isolation, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_CERTIFICATION_MATERIAL_FINGERPRINT_INVALID");

        JsonNode original = resource("regional-data-plane-certification-v1.fixture.json");
        assertThatThrownBy(() -> require(contract, original, isolation, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_CERTIFICATION_AUTHORITY_REJECTED");
    }

    @Test
    void staleComponentAndIncompleteRotationFailAfterValidReaddressing() throws Exception {
        JsonNode contract = resource(
                "regional-data-plane-deployment-contract-v1.fixture.json");
        ObjectNode stale = (ObjectNode) resource(
                "regional-data-plane-certification-v1.fixture.json");
        ((ObjectNode) stale.path("componentObservations").get(1))
                .put("observedAt", "2026-08-09T23:58:00Z");
        Pair stalePair = readdress(stale);
        assertThatThrownBy(() -> require(contract, stalePair.certification(),
                stalePair.isolation(), true))
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_COMPONENT_STALE");

        ObjectNode rotation = (ObjectNode) resource(
                "regional-data-plane-certification-v1.fixture.json");
        ((ObjectNode) rotation.path("rotationObservations").get(0))
                .put("allReplicasConverged", false);
        Pair rotationPair = readdress(rotation);
        assertThatThrownBy(() -> require(contract, rotationPair.certification(),
                rotationPair.isolation(), true))
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_ROTATION_NOT_CONVERGED");
    }

    @Test
    void expiredActiveGenerationAndInsufficientOverlapFailClosed() throws Exception {
        JsonNode contract = resource(
                "regional-data-plane-deployment-contract-v1.fixture.json");
        ObjectNode expired = (ObjectNode) resource(
                "regional-data-plane-certification-v1.fixture.json");
        ((ObjectNode) expired.path("rotationObservations").get(0))
                .put("activeGenerationActivatedAt", "2026-05-01T00:00:00Z");
        Pair expiredPair = readdress(expired);
        assertThatThrownBy(() -> require(contract, expiredPair.certification(),
                expiredPair.isolation(), true))
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_ACTIVE_KEY_AGE_REJECTED");

        ObjectNode insufficient = (ObjectNode) resource(
                "regional-data-plane-certification-v1.fixture.json");
        ((ObjectNode) insufficient.path("rotationObservations").get(1))
                .put("overlapAchievedSeconds", 599);
        Pair insufficientPair = readdress(insufficient);
        assertThatThrownBy(() -> require(contract, insufficientPair.certification(),
                insufficientPair.isolation(), true))
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_ROTATION_NOT_CONVERGED");
    }

    @Test
    void writeAttemptAndIsolationRefDriftFailClosed() throws Exception {
        JsonNode contract = resource(
                "regional-data-plane-deployment-contract-v1.fixture.json");
        ObjectNode write = (ObjectNode) resource(
                "regional-data-plane-certification-v1.fixture.json");
        write.put("externalBusinessWriteAttemptCount", 1);
        Pair writePair = readdress(write);
        assertThatThrownBy(() -> require(contract, writePair.certification(),
                writePair.isolation(), true))
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_WRITE_ESCAPE_OBSERVED");

        ObjectNode isolation = (ObjectNode) resource(
                "mirror-deployment-isolation-attestation-bundle-v2.fixture.json");
        ((ObjectNode) isolation.path("regionalDataPlaneCertificationRef"))
                .put("revision", 12);
        readdressIsolation(isolation);
        JsonNode certification = resource(
                "regional-data-plane-certification-v1.fixture.json");
        assertThatThrownBy(() -> require(contract, certification, isolation, true))
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_IDENTITY_CLOSURE_INVALID");
    }

    @Test
    void strictSchemasRejectUnknownPayloadFields() throws Exception {
        JsonNode contract = resource(
                "regional-data-plane-deployment-contract-v1.fixture.json");
        ObjectNode certification = (ObjectNode) resource(
                "regional-data-plane-certification-v1.fixture.json");
        JsonNode isolation = resource(
                "mirror-deployment-isolation-attestation-bundle-v2.fixture.json");
        certification.put("requestPayload", "forbidden");

        assertThatThrownBy(() -> require(contract, certification, isolation, true))
                .hasMessage("RG.MIRROR.CLIENT.REGIONAL_CERTIFICATION_SCHEMA_INVALID");
    }

    private RegionalDataPlaneCertificationVerifier.VerifiedCoordinates require(
            JsonNode contract, JsonNode certification, JsonNode isolation, boolean trust) {
        return verifier.require(contract, certification, isolation,
                Instant.parse("2026-08-10T00:00:02Z"),
                Instant.parse("2026-08-10T00:00:03Z"),
                (seal, artifact) -> trust);
    }

    private Pair readdress(ObjectNode certification) throws Exception {
        ObjectNode material = JSON.createObjectNode();
        for (String field : List.of("certificationId", "revision", "contractRef", "scope",
                "region", "deployment", "observedAt", "validFrom", "expiresAt",
                "componentObservations", "rotationObservations",
                "externalBusinessWriteAttemptCount", "writeEscapeCount", "issuer",
                "proofRefs")) {
            material.set(field, certification.path(field).deepCopy());
        }
        ObjectNode signatureMaterial = JSON.createObjectNode();
        signatureMaterial.put("domain",
                RegionalDataPlaneCertificationVerifier.SIGNATURE_DOMAIN);
        signatureMaterial.put("schemaVersion", certification.path("schemaVersion").asText());
        signatureMaterial.set("material", material);
        ((ObjectNode) certification.path("certificationSeal")).put("materialFingerprint",
                EvidenceVerificationSupport.sha256Bounded(signatureMaterial,
                        RegionalDataPlaneCertificationVerifier.MAXIMUM_CERTIFICATION_BYTES));
        ObjectNode complete = JSON.createObjectNode();
        complete.put("schemaVersion", certification.path("schemaVersion").asText());
        complete.put("certificationFingerprint", "");
        complete.set("material", material);
        complete.set("certificationSeal",
                certification.path("certificationSeal").deepCopy());
        certification.put("certificationFingerprint",
                EvidenceVerificationSupport.sha256Bounded(complete,
                        RegionalDataPlaneCertificationVerifier.MAXIMUM_CERTIFICATION_BYTES));

        ObjectNode isolation = (ObjectNode) resource(
                "mirror-deployment-isolation-attestation-bundle-v2.fixture.json");
        ObjectNode ref = (ObjectNode) isolation.path("regionalDataPlaneCertificationRef");
        ref.put("fingerprint", certification.path("certificationFingerprint").asText());
        readdressIsolation(isolation);
        return new Pair(certification, isolation);
    }

    private static void readdressIsolation(ObjectNode isolation) {
        isolation.put("bundleFingerprint", "");
        isolation.put("bundleFingerprint", EvidenceVerificationSupport.sha256Bounded(
                isolation,
                RegionalDataPlaneCertificationVerifier.MAXIMUM_ISOLATION_BUNDLE_BYTES));
    }

    private static JsonNode resource(String name) throws Exception {
        try (InputStream input = RegionalDataPlaneCertificationVerifierTest.class
                .getResourceAsStream(ROOT + name)) {
            if (input == null) {
                throw new IllegalStateException("missing packaged fixture: " + name);
            }
            return JSON.readTree(input);
        }
    }

    private record Pair(JsonNode certification, JsonNode isolation) {
    }
}
