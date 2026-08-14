package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCertificationVerifierTest {
    private static final String ROOT = "/schemas/resource-gateway-mirror/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RuntimeCertificationVerifier verifier = new RuntimeCertificationVerifier();

    @Test
    void packagedFixturesVerifyWithoutServerOrSpring() throws Exception {
        JsonNode manifest = resource("runtime-certification-manifest-v1.fixture.json");
        JsonNode authorization = resource(
                "runtime-certification-execution-authorization-v1.fixture.json");
        JsonNode report = resource("runtime-certification-report-v1.fixture.json");

        RuntimeCertificationVerifier.VerifiedCoordinates verified = require(
                manifest, authorization, report, true, true);

        assertThat(verified.manifestId()).isEqualTo("runtime-certification:sg");
        assertThat(verified.authorizationId()).isEqualTo("runtime-authorization:sg:3");
        assertThat(verified.reportId()).isEqualTo("runtime-report:sg:3");
        assertThat(report.path("scenarioResults")).hasSize(12);
    }

    @Test
    void selfContainedReplayBundleVerifiesRegionalAndRuntimeClosure() throws Exception {
        JsonNode bundle = resource(
                "runtime-certification-replay-bundle-v1.fixture.json");

        RuntimeCertificationVerifier.VerifiedReplayBundleCoordinates verified =
                verifier.requireReplayBundle(bundle,
                        (seal, certification) -> true,
                        (seal, authorization) -> true,
                        (seal, report) -> true);

        assertThat(verified.bundleId()).isEqualTo("runtime-replay-bundle:sg:3");
        assertThat(verified.runtime().reportId()).isEqualTo("runtime-report:sg:3");
        assertThat(verified.regional().certificationId())
                .isEqualTo("regional-certification:sg");
    }

    @Test
    void replayBundleAddressAndUnknownFieldsFailClosed() throws Exception {
        ObjectNode tampered = (ObjectNode) resource(
                "runtime-certification-replay-bundle-v1.fixture.json");
        tampered.put("exporter", "runtime-certification-exporter:drifted");
        assertThatThrownBy(() -> verifier.requireReplayBundle(tampered,
                (seal, certification) -> true,
                (seal, authorization) -> true,
                (seal, report) -> true))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_REPLAY_BUNDLE_FINGERPRINT_INVALID");

        ObjectNode unknown = (ObjectNode) resource(
                "runtime-certification-replay-bundle-v1.fixture.json");
        unknown.put("businessPayload", "forbidden");
        assertThatThrownBy(() -> verifier.requireReplayBundle(unknown,
                (seal, certification) -> true,
                (seal, authorization) -> true,
                (seal, report) -> true))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_REPLAY_BUNDLE_SCHEMA_INVALID");
    }

    @Test
    void unknownPayloadFieldAndIncompleteDenominatorFailSchemaValidation() throws Exception {
        JsonNode manifest = resource("runtime-certification-manifest-v1.fixture.json");
        JsonNode authorization = resource(
                "runtime-certification-execution-authorization-v1.fixture.json");
        ObjectNode payload = (ObjectNode) resource(
                "runtime-certification-report-v1.fixture.json");
        payload.put("requestPayload", "forbidden");

        assertThatThrownBy(() -> require(manifest, authorization, payload, true, true))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_REPORT_SCHEMA_INVALID");

        ObjectNode incomplete = (ObjectNode) resource(
                "runtime-certification-report-v1.fixture.json");
        ((com.fasterxml.jackson.databind.node.ArrayNode) incomplete.path("scenarioResults"))
                .remove(11);
        assertThatThrownBy(() -> require(manifest, authorization, incomplete, true, true))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_REPORT_SCHEMA_INVALID");
    }

    @Test
    void contentAddressTamperingAndExternalTrustRejectionFailClosed() throws Exception {
        JsonNode manifest = resource("runtime-certification-manifest-v1.fixture.json");
        JsonNode authorization = resource(
                "runtime-certification-execution-authorization-v1.fixture.json");
        ObjectNode report = (ObjectNode) resource(
                "runtime-certification-report-v1.fixture.json");
        report.put("issuer", "runtime-certification-authority:drifted");

        assertThatThrownBy(() -> require(manifest, authorization, report, true, true))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_REPORT_MATERIAL_FINGERPRINT_INVALID");
        JsonNode original = resource("runtime-certification-report-v1.fixture.json");
        assertThatThrownBy(() -> require(manifest, authorization, original, false, true))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_AUTHORIZATION_AUTHORITY_REJECTED");
        assertThatThrownBy(() -> require(manifest, authorization, original, true, false))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_REPORT_AUTHORITY_REJECTED");
    }

    @Test
    void recoverySloViolationFailsEvenAfterValidReaddressing() throws Exception {
        JsonNode manifest = resource("runtime-certification-manifest-v1.fixture.json");
        JsonNode authorization = resource(
                "runtime-certification-execution-authorization-v1.fixture.json");
        ObjectNode report = (ObjectNode) resource(
                "runtime-certification-report-v1.fixture.json");
        ObjectNode first = (ObjectNode) report.path("scenarioResults").get(0);
        first.put("recoveryObservedAt", "2026-08-10T00:02:08Z");
        first.put("completedAt", "2026-08-10T00:02:09Z");
        readdressReport(report);

        assertThatThrownBy(() -> require(manifest, authorization, report, true, true))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_SCENARIO_WINDOW_INVALID");
    }

    @Test
    void callerPinnedRegionalAndIsolationRefsCannotDrift() throws Exception {
        JsonNode manifest = resource("runtime-certification-manifest-v1.fixture.json");
        JsonNode authorization = resource(
                "runtime-certification-execution-authorization-v1.fixture.json");
        ObjectNode report = (ObjectNode) resource(
                "runtime-certification-report-v1.fixture.json");
        JsonNode expectedRegional = report.path(
                "regionalDataPlaneCertificationRef").deepCopy();
        JsonNode expectedDecision = report.path("isolationDecisionRef").deepCopy();
        JsonNode expectedAttestation = report.path("isolationAttestationRef").deepCopy();
        ((ObjectNode) report.path("regionalDataPlaneCertificationRef")).put("revision", 12);
        readdressReport(report);

        assertThatThrownBy(() -> verifier.require(manifest, authorization, report,
                expectedRegional, expectedDecision, expectedAttestation,
                (seal, artifact) -> true, (seal, artifact) -> true))
                .hasMessage("RG.MIRROR.CLIENT.RUNTIME_REPORT_CLOSURE_INVALID");
    }

    private RuntimeCertificationVerifier.VerifiedCoordinates require(
            JsonNode manifest,
            JsonNode authorization,
            JsonNode report,
            boolean authorizationTrust,
            boolean reportTrust) {
        return verifier.require(manifest, authorization, report,
                report.path("regionalDataPlaneCertificationRef"),
                report.path("isolationDecisionRef"), report.path("isolationAttestationRef"),
                (seal, artifact) -> authorizationTrust,
                (seal, artifact) -> reportTrust);
    }

    private static void readdressReport(ObjectNode report) {
        ObjectNode material = JSON.createObjectNode();
        for (String field : List.of(
                "reportId", "revision", "manifestRef", "authorizationRef",
                "authorizationConsumptionRef", "regionalDataPlaneCertificationRef",
                "isolationDecisionRef", "isolationAttestationRef", "scope", "region",
                "deployment", "environmentClass", "environmentFingerprint", "adapter",
                "observedComponents", "startedAt", "completedAt", "scenarioResults",
                "verdict", "externalBusinessWriteAttemptCount", "writeEscapeCount", "issuer",
                "proofRefs")) {
            material.set(field, report.path(field).deepCopy());
        }
        ObjectNode signature = JSON.createObjectNode();
        signature.put("domain", RuntimeCertificationVerifier.REPORT_SIGNATURE_DOMAIN);
        signature.put("schemaVersion", report.path("schemaVersion").asText());
        signature.set("material", material);
        ((ObjectNode) report.path("reportSeal")).put("materialFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        signature, RuntimeCertificationVerifier.MAXIMUM_REPORT_BYTES));
        ObjectNode address = JSON.createObjectNode();
        address.put("schemaVersion", report.path("schemaVersion").asText());
        address.put("reportFingerprint", "");
        address.set("material", material);
        address.set("reportSeal", report.path("reportSeal").deepCopy());
        report.put("reportFingerprint", EvidenceVerificationSupport.sha256Bounded(
                address, RuntimeCertificationVerifier.MAXIMUM_REPORT_BYTES));
    }

    private static JsonNode resource(String name) throws Exception {
        try (InputStream input = RuntimeCertificationVerifierTest.class
                .getResourceAsStream(ROOT + name)) {
            if (input == null) {
                throw new IllegalStateException("missing packaged fixture: " + name);
            }
            return JSON.readTree(input);
        }
    }
}
