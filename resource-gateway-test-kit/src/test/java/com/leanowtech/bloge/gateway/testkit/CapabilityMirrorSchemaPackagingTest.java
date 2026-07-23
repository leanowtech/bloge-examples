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
                "mirror-run-evidence-v2.schema.json",
                "mirror-evidence-attestation-v1.schema.json",
                "mirror-evidence-attestation-v2.schema.json",
                "mirror-evidence-bundle-v1.schema.json",
                "mirror-evidence-bundle-v2.schema.json",
                "mirror-deployment-isolation-attestation-v1.schema.json",
                "mirror-deployment-isolation-attestation-status-v1.schema.json",
                "mirror-deployment-isolation-attestation-revocation-request-v1.schema.json",
                "mirror-deployment-isolation-attestation-bundle-v1.schema.json",
                "mirror-deployment-isolation-agent-snapshot-v1.schema.json",
                "mirror-deployment-isolation-run-trust-v1.schema.json",
                "mirror-deployment-isolation-authority-key-set-publication-v1.schema.json",
                "capability-observation-v1.schema.json",
                "capability-observation-admission-v1.schema.json",
                "capability-observation-receipt-v1.schema.json",
                "capability-observation-review-request-v1.schema.json",
                "capability-observation-review-v1.schema.json",
                "capability-corpus-candidate-request-v1.schema.json",
                "capability-corpus-revision-v1.schema.json",
                "capability-corpus-publish-request-v1.schema.json",
                "capability-corpus-publication-v1.schema.json",
                "capability-corpus-trajectory-publish-request-v1.schema.json",
                "capability-corpus-trajectory-publication-v1.schema.json",
                "fixture-mirror-corpus-bindings-v1.schema.json")) {
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
        assertThat(CapabilityMirrorProtocol.MIRROR_EVIDENCE_BUNDLE_V2_SCHEMA_RESOURCE)
                .endsWith("mirror-evidence-bundle-v2.schema.json");
        assertThat(CapabilityMirrorProtocol
                .MIRROR_DEPLOYMENT_ISOLATION_RUN_TRUST_SCHEMA_RESOURCE)
                .endsWith("mirror-deployment-isolation-run-trust-v1.schema.json");
        assertThat(CapabilityMirrorProtocol.CAPABILITY_OBSERVATION_SCHEMA_RESOURCE)
                .endsWith("capability-observation-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_OBSERVATION_ADMISSION_SCHEMA_RESOURCE)
                .endsWith("capability-observation-admission-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_OBSERVATION_RECEIPT_SCHEMA_RESOURCE)
                .endsWith("capability-observation-receipt-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_OBSERVATION_REVIEW_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-observation-review-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_OBSERVATION_REVIEW_SCHEMA_RESOURCE)
                .endsWith("capability-observation-review-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_CANDIDATE_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-candidate-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_REVISION_SCHEMA_RESOURCE)
                .endsWith("capability-corpus-revision-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_PUBLISH_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-publish-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_PUBLICATION_SCHEMA_RESOURCE)
                .endsWith("capability-corpus-publication-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_TRAJECTORY_PUBLISH_REQUEST_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-trajectory-publish-request-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION_SCHEMA_RESOURCE)
                .endsWith(
                        "capability-corpus-trajectory-publication-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_CORPUS_BINDINGS_SCHEMA_RESOURCE)
                .endsWith("fixture-mirror-corpus-bindings-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_CORPUS_BINDINGS_FIXTURE_RESOURCE)
                .endsWith("fixture-mirror-corpus-bindings-v1.fixture.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_TRAJECTORY_BINDINGS_SCHEMA_RESOURCE)
                .endsWith(
                        "fixture-mirror-trajectory-bindings-v1.schema.json");
        assertThat(CapabilityMirrorProtocol
                .FIXTURE_MIRROR_TRAJECTORY_BINDINGS_FIXTURE_RESOURCE)
                .endsWith(
                        "fixture-mirror-trajectory-bindings-v1.fixture.json");

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

    @Test
    void packagesOneFixedThresholdSignedIsolationAuthorityFixture() {
        var fixture = CapabilityMirrorProtocol
                .mirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture();

        var verified = new MirrorDeploymentIsolationAuthorityKeySetVerifier().verify(
                fixture.publication(), fixture.expectedBinding(), fixture.bootstrapRoots(),
                null, fixture.verificationTime());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.authorityKeys()).hasSize(1);
        assertThat(CapabilityMirrorProtocol
                .MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_SCHEMA_RESOURCE)
                .endsWith("authority-key-set-publication-v1.schema.json");
    }

    @Test
    void packagesOneFixedSignedPayloadFreeObservationFixture() {
        CapabilityObservationCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityObservationCompatibilityFixture();

        CapabilityObservationVerifier.VerificationResult verified =
                new CapabilityObservationVerifier().verify(
                        fixture.observation(),
                        fixture.verificationKey(),
                        fixture.expectedScope(),
                        fixture.verificationTime());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.observationId())
                .isEqualTo("support-refund-observation-0001");
        assertThat(fixture.observation().toString())
                .doesNotContain("customer-123")
                .doesNotContain("requestBody")
                .doesNotContain("responseBody");
    }

    @Test
    void packagesOneFixedPayloadFreeCorpusGovernanceFixture() {
        CapabilityCorpusCompatibilityFixture fixture =
                CapabilityMirrorProtocol.capabilityCorpusCompatibilityFixture();

        CapabilityCorpusVerifier.VerificationResult verified =
                new CapabilityCorpusVerifier().verify(fixture);

        assertThat(verified.verified()).isTrue();
        assertThat(verified.corpusId()).isEqualTo("support-refund-corpus");
        assertThat(fixture.revision().toString())
                .doesNotContain("customer-123")
                .doesNotContain("requestBody")
                .doesNotContain("responseBody");
        assertThat(CapabilityMirrorProtocol
                .CAPABILITY_CORPUS_FIXTURE_RESOURCE)
                .endsWith("capability-corpus-stage2-v1.fixture.json");
    }

    @Test
    void packagedAttestationControlSchemasAcceptAtomicStatusBundleAndRevocation() {
        JsonNode attestation = CapabilityMirrorProtocol
                .mirrorDeploymentIsolationCompatibilityFixture().attestation();
        JsonNode deployment = attestation.at("/material/deployment");
        var scope = objectMapper.createObjectNode()
                .put("tenantId", "tenant-a")
                .put("organizationId", "org-a")
                .put("projectId", "tool-studio")
                .put("environmentId", "staging")
                .put("region", "ap-southeast-1");
        var authorityRef = objectMapper.createObjectNode()
                .put("kind", "DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET")
                .put("id", "mirror-isolation-authorities:staging")
                .put("revision", 3)
                .put("fingerprint", fingerprint('a'));
        var attestationRef = objectMapper.createObjectNode()
                .put("kind", "DEPLOYMENT_ISOLATION_ATTESTATION")
                .put("id", attestation.at("/material/attestationId").asText())
                .put("revision", attestation.at("/material/revision").asLong())
                .put("fingerprint", attestation.path("attestationFingerprint").asText());
        var material = objectMapper.createObjectNode()
                .put("statusRevision", 1)
                .put("previousStatusFingerprint", "")
                .put("state", "ACTIVE")
                .put("reason", "ACCEPTED")
                .put("effectiveAt", "2026-07-23T00:00:11Z");
        material.set("scope", scope.deepCopy());
        material.set("deployment", deployment.deepCopy());
        material.set("authorityKeySetRef", authorityRef.deepCopy());
        material.set("attestationRef", attestationRef);
        var status = objectMapper.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_STATUS_V1)
                .put("statusFingerprint", fingerprint('b'));
        status.set("material", material);
        var bundle = objectMapper.createObjectNode()
                .put("schemaVersion",
                        CapabilityMirrorProtocol.MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE_V1)
                .put("bundleFingerprint", fingerprint('c'));
        bundle.set("scope", scope);
        bundle.set("authorityKeySetRef", authorityRef);
        bundle.set("attestation", attestation);
        bundle.set("status", status);
        JsonNode authority = CapabilityMirrorProtocol
                .mirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture().publication();
        var exactAuthorityRef = objectMapper.createObjectNode()
                .put("kind", "DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET")
                .put("id", authority.at("/material/keySetId").asText())
                .put("revision", authority.at("/material/generation").asLong())
                .put("fingerprint", authority.path("publicationFingerprint").asText());
        bundle.set("authorityKeySetRef", exactAuthorityRef.deepCopy());
        status.withObject("/material").set("authorityKeySetRef", exactAuthorityRef);
        var snapshot = objectMapper.createObjectNode()
                .put("schemaVersion", CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_V1)
                .put("snapshotFingerprint", fingerprint('d'))
                .put("cacheGeneration", 1)
                .put("refreshedAt", "2026-07-23T00:00:11Z")
                .put("validUntil", "2026-07-23T00:05:11Z");
        snapshot.set("authorityPublication", authority);
        snapshot.set("attestationBundle", bundle);
        var revocation = objectMapper.createObjectNode()
                .put("schemaVersion", CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_V1)
                .put("attestationRevision", attestation.at("/material/revision").asLong())
                .put("attestationFingerprint",
                        attestation.path("attestationFingerprint").asText())
                .put("expectedStatusRevision", 1)
                .put("expectedStatusFingerprint", fingerprint('b'))
                .put("reason", "SECURITY_INCIDENT");

        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(status,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_STATUS_SCHEMA_RESOURCE,
                "invalid-status")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(bundle,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE_SCHEMA_RESOURCE,
                "invalid-bundle")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(revocation,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_SCHEMA_RESOURCE,
                "invalid-revocation")).doesNotThrowAnyException();
        assertThatCode(() -> CapabilityMirrorSchemaValidator.require(snapshot,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_SCHEMA_RESOURCE,
                "invalid-agent-snapshot")).doesNotThrowAnyException();

        revocation.put("reason", "ACCEPTED");
        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(revocation,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_SCHEMA_RESOURCE,
                "invalid-revocation")).isInstanceOf(IllegalArgumentException.class);
        snapshot.putNull("authorityPublication");
        assertThatThrownBy(() -> CapabilityMirrorSchemaValidator.require(snapshot,
                CapabilityMirrorProtocol
                        .MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_SCHEMA_RESOURCE,
                "invalid-agent-snapshot")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
