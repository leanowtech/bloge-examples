package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityMirrorSchemaPackagingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testKitPackagesEveryMirrorSchemaAndTheValidatedStageZeroBaseline() throws Exception {
        for (String name : List.of(
                "artifact-provenance-v1.schema.json",
                "effect-contract-v1.schema.json",
                "capability-contract-v1.schema.json",
                "capability-snapshot-v1.schema.json",
                "capability-closure-v1.schema.json",
                "capability-lifecycle-transition-v1.schema.json",
                "capability-mirror-compatibility-v1.schema.json",
                "mirror-execution-request-v1.schema.json",
                "mirror-run-summary-v1.schema.json",
                "mirror-resolution-v1.schema.json",
                "mirror-run-evidence-v1.schema.json",
                "mirror-evidence-attestation-v1.schema.json",
                "mirror-evidence-bundle-v1.schema.json")) {
            String resource = CapabilityMirrorProtocol.SCHEMA_RESOURCE_ROOT + name;
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertThat(input).as(resource).isNotNull();
                JsonNode schema = objectMapper.readTree(input);
                assertThat(schema.path("$schema").asText())
                        .isEqualTo("https://json-schema.org/draft/2020-12/schema");
                assertThat(schema.path("$id").asText()).endsWith(name);
            }
        }

        assertThat(CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_V1)
                .isEqualTo("resourceGateway.mirrorExecutionRequest.v1");
        assertThat(CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_V1)
                .isEqualTo("resourceGateway.mirrorRunSummary.v1");
        assertThat(CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_SCHEMA_RESOURCE)
                .endsWith("mirror-execution-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE)
                .endsWith("mirror-run-summary-v1.schema.json");

        JsonNode baseline = CapabilityMirrorProtocol.compatibilityBaseline();
        assertThat(baseline.path("schemaVersion").asText())
                .isEqualTo(CapabilityMirrorProtocol.COMPATIBILITY_V1);
        assertThat(baseline.path("requiredObjects").size()).isEqualTo(6);
        assertThat(baseline.path("requiredFeatures").size()).isEqualTo(6);
        assertThat(baseline.path("deferredFeatures").size()).isEqualTo(3);
    }

    @Test
    void callersCannotMutateTheProcessWideCompatibilityBaseline() {
        JsonNode first = CapabilityMirrorProtocol.compatibilityBaseline();
        ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("protocol", "changed");

        assertThat(CapabilityMirrorProtocol.compatibilityBaseline().path("protocol").asText())
                .isEqualTo(CapabilityMirrorProtocol.INTEGRATION_PROTOCOL);
    }

    @Test
    void packagedExecutionSchemasAcceptClosedPayloadFreeExamples() {
        com.fasterxml.jackson.databind.node.ObjectNode request = objectMapper.createObjectNode()
                .put("schemaVersion", CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_V1)
                .put("requestId", "request-1")
                .put("planId", "plan-1")
                .put("expectedPlanFingerprint", fingerprint('1'));
        request.set("context", objectMapper.createObjectNode().put("customerId", "C-1"));
        com.fasterxml.jackson.databind.node.ObjectNode summary = objectMapper.createObjectNode()
                .put("schemaVersion", CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_V1)
                .put("runId", "run-1")
                .put("requestId", "request-1")
                .put("planId", "plan-1")
                .put("planFingerprint", fingerprint('1'))
                .put("requestContextFingerprint", fingerprint('2'))
                .put("status", "PASSED")
                .put("evidenceClass", "EXPLORATORY")
                .put("startedAt", "2026-07-23T00:00:00Z")
                .put("completedAt", "2026-07-23T00:00:01Z")
                .put("durationMs", 1000)
                .put("nodeTraceCount", 2)
                .put("edgeTraceCount", 1)
                .put("resolutionCount", 1)
                .put("evidenceBundleFingerprint", fingerprint('3'));
        summary.set("scope", objectMapper.createObjectNode()
                .put("tenantId", "tenant-a")
                .put("organizationId", "org-a")
                .put("projectId", "support")
                .put("environmentId", "test")
                .put("region", "sg"));

        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(request,
                CapabilityMirrorProtocol.MIRROR_EXECUTION_REQUEST_SCHEMA_RESOURCE,
                "invalid-request")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(summary,
                CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE,
                "invalid-summary")).doesNotThrowAnyException();

        summary.put("output", "payload-must-not-fit");
        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(summary,
                CapabilityMirrorProtocol.MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE,
                "invalid-summary")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void packagesOneFixedIndependentlyVerifiableStageOneEvidenceFixture() {
        MirrorEvidenceCompatibilityFixture fixture =
                CapabilityMirrorProtocol.mirrorEvidenceCompatibilityFixture();

        MirrorEvidenceVerifier.VerificationResult verified = new MirrorEvidenceVerifier()
                .verify(fixture.bundle(), fixture.verificationKey());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.runId()).isEqualTo("mirror-run-schema");
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixture.bundle())
                .put("bundleFingerprint", "changed");
        assertThat(new MirrorEvidenceVerifier().verify(
                CapabilityMirrorProtocol.mirrorEvidenceCompatibilityFixture().bundle(),
                fixture.verificationKey()).verified()).isTrue();
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
