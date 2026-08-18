package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioAcceptanceVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASELINE =
            "docs/acceptance/capability-studio/"
                    + "capability-studio-acceptance-baseline-v1.json";
    private static final String MANIFEST =
            "docs/acceptance/capability-studio/"
                    + "capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json";
    private static final String BASELINE_SCHEMA =
            "docs/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-acceptance-baseline-v1.schema.json";
    private static final String MANIFEST_SCHEMA =
            "docs/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-golden-path-acceptance-manifest-v1.schema.json";

    private final CapabilityStudioAcceptanceVerifier verifier =
            new CapabilityStudioAcceptanceVerifier();

    @Test
    void packagesBothAuthoritativeSchemas() {
        assertThat(CapabilityStudioAcceptanceVerifier.class
                .getResource(CapabilityStudioSchemaSupport.BASELINE_RESOURCE)).isNotNull();
        assertThat(CapabilityStudioAcceptanceVerifier.class
                .getResource(CapabilityStudioSchemaSupport.MANIFEST_RESOURCE)).isNotNull();
    }

    @Test
    void verifiesTruthfulNoGoAndPendingRootFixtures() throws IOException {
        JsonNode baseline = rootFixture(BASELINE);
        JsonNode manifest = rootFixture(MANIFEST);
        ObjectNode pendingBaseline = ((ObjectNode) baseline).deepCopy();
        pendingBaseline.put("status", "PENDING");
        refreshArtifactFingerprint(pendingBaseline);

        CapabilityStudioAcceptanceVerifier.VerificationResult baselineResult =
                verifier.verifyAcceptanceBaseline(baseline);
        CapabilityStudioAcceptanceVerifier.VerificationResult pendingResult =
                verifier.verifyAcceptanceBaseline(pendingBaseline);
        CapabilityStudioAcceptanceVerifier.VerificationResult manifestResult =
                verifier.verifyGoldenPathAcceptanceManifest(manifest);
        ObjectNode baselineMaterial = ((ObjectNode) baseline).deepCopy();
        baselineMaterial.putNull("artifactFingerprint");
        String expectedBaselineFingerprint = EvidenceVerificationSupport.sha256(baselineMaterial);
        ObjectNode manifestMaterial = ((ObjectNode) manifest).deepCopy();
        manifestMaterial.putNull("artifactFingerprint");
        String expectedManifestFingerprint = EvidenceVerificationSupport.sha256(manifestMaterial);

        assertThat(baselineResult.verified())
                .withFailMessage("Baseline verification failed: %s; declared=%s expected=%s",
                        baselineResult, baseline.path("artifactFingerprint").asText(),
                        expectedBaselineFingerprint)
                .isTrue();
        assertThat(baselineResult.artifactStatus()).isEqualTo("NO_GO");
        assertThat(pendingResult.verified()).isTrue();
        assertThat(pendingResult.artifactStatus()).isEqualTo("PENDING");
        assertThat(manifestResult.verified())
                .withFailMessage("Manifest verification failed: %s; declared=%s expected=%s",
                        manifestResult, manifest.path("artifactFingerprint").asText(),
                        expectedManifestFingerprint)
                .isTrue();
        assertThat(manifestResult.artifactStatus()).isEqualTo("NO_GO");
        assertThat(baselineResult.errorCode()).isNull();
        assertThat(manifestResult.errorCode()).isNull();
        assertThat(baselineResult.checks()).contains("ARTIFACT_FINGERPRINT");
        assertThat(manifestResult.checks()).contains("ARTIFACT_FINGERPRINT");
    }

    @Test
    void rejectsTamperedPayloadFreeArtifactsWithSafeFingerprintErrors() throws IOException {
        ObjectNode baseline = rootFixture(BASELINE).deepCopy();
        baseline.put("planRef", "docs/tampered-plan.md");
        ObjectNode manifest = rootFixture(MANIFEST).deepCopy();
        manifest.put("generatedAt", "2026-08-17T12:00:01Z");

        CapabilityStudioAcceptanceVerifier.VerificationResult baselineResult =
                verifier.verifyAcceptanceBaseline(baseline);
        CapabilityStudioAcceptanceVerifier.VerificationResult manifestResult =
                verifier.verifyGoldenPathAcceptanceManifest(manifest);

        assertThat(baselineResult.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.BASELINE_ARTIFACT_FINGERPRINT_MISMATCH");
        assertThat(manifestResult.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.MANIFEST_ARTIFACT_FINGERPRINT_MISMATCH");
    }

    @Test
    void rootDocsSchemasHaveTheExpectedVersionIds() throws IOException {
        assertThat(rootFixture(BASELINE_SCHEMA).path("$id").asText())
                .endsWith("capability-studio-acceptance-baseline-v1.schema.json");
        assertThat(rootFixture(MANIFEST_SCHEMA).path("$id").asText())
                .endsWith("capability-studio-golden-path-acceptance-manifest-v1.schema.json");
    }

    @Test
    void rejectsOptimisticApprovedBaseline() throws IOException {
        ObjectNode candidate = rootFixture(BASELINE).deepCopy();
        candidate.put("status", "APPROVED");

        CapabilityStudioAcceptanceVerifier.VerificationResult result =
                verifier.verifyAcceptanceBaseline(candidate);

        assertThat(result.verified()).isFalse();
        assertThat(result.schemaValid()).isTrue();
        assertThat(result.semanticValid()).isFalse();
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BASELINE_APPROVED_REQUIRES_EVIDENCE");
    }

    @Test
    void rejectsOptimisticAcceptedManifest() throws IOException {
        ObjectNode candidate = rootFixture(MANIFEST).deepCopy();
        candidate.put("status", "ACCEPTED");

        CapabilityStudioAcceptanceVerifier.VerificationResult result =
                verifier.verifyGoldenPathAcceptanceManifest(candidate);

        assertThat(result.verified()).isFalse();
        assertThat(result.schemaValid()).isTrue();
        assertThat(result.semanticValid()).isFalse();
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.MANIFEST_ACCEPTED_REQUIRES_EVIDENCE");
    }

    @Test
    void rejectsDuplicateOrMissingGoldenPathGates() throws IOException {
        ObjectNode duplicate = rootFixture(BASELINE).deepCopy();
        duplicate.withArray("goldenPaths").set(9,
                duplicate.withArray("goldenPaths").get(0).deepCopy());
        ObjectNode missing = rootFixture(MANIFEST).deepCopy();
        missing.withArray("gpResults").set(9,
                missing.withArray("gpResults").get(0).deepCopy());

        assertThat(verifier.verifyAcceptanceBaseline(duplicate).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BASELINE_GOLDEN_PATH_COVERAGE_INVALID");
        assertThat(verifier.verifyGoldenPathAcceptanceManifest(missing).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.MANIFEST_GOLDEN_PATH_COVERAGE_INVALID");
    }

    @Test
    void rejectsDuplicateOrMissingScenarioCases() throws IOException {
        ObjectNode candidate = rootFixture(MANIFEST).deepCopy();
        candidate.withArray("scenarioResults").set(8,
                candidate.withArray("scenarioResults").get(0).deepCopy());

        CapabilityStudioAcceptanceVerifier.VerificationResult result =
                verifier.verifyGoldenPathAcceptanceManifest(candidate);

        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.MANIFEST_SCENARIO_COVERAGE_INVALID");
    }

    @Test
    void rejectsWrongCanonicalCaseTypeMapping() throws IOException {
        ObjectNode candidate = rootFixture(MANIFEST).deepCopy();
        ObjectNode caseThree = (ObjectNode) candidate.withArray("scenarioResults").get(2);
        caseThree.put("caseType", "NEGATIVE");

        CapabilityStudioAcceptanceVerifier.VerificationResult result =
                verifier.verifyGoldenPathAcceptanceManifest(candidate);

        assertThat(result.schemaValid()).isTrue();
        assertThat(result.semanticValid()).isFalse();
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.MANIFEST_CASE_TYPE_MAPPING_INVALID");
    }

    @Test
    void rejectsEvidenceFreeAllGreenManifest() throws IOException {
        ObjectNode candidate = rootFixture(MANIFEST).deepCopy();
        markManifestGreenExceptEvidence(candidate);

        CapabilityStudioAcceptanceVerifier.VerificationResult result =
                verifier.verifyGoldenPathAcceptanceManifest(candidate);

        assertThat(result.schemaValid()).isTrue();
        assertThat(result.semanticValid()).isFalse();
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.MANIFEST_ACCEPTED_REQUIRES_EVIDENCE");
    }

    @Test
    void rejectsNonzeroOrUnknownExternalCallsUnderPassedScenario() throws IOException {
        ObjectNode nonzero = rootFixture(MANIFEST).deepCopy();
        ObjectNode nonzeroScenario = (ObjectNode) nonzero.withArray("scenarioResults").get(0);
        nonzeroScenario.put("status", "PASSED");
        nonzeroScenario.put("realCallCount", 1);

        ObjectNode unknown = rootFixture(MANIFEST).deepCopy();
        ObjectNode unknownScenario = (ObjectNode) unknown.withArray("scenarioResults").get(0);
        unknownScenario.put("status", "PASSED");

        assertThat(verifier.verifyGoldenPathAcceptanceManifest(nonzero).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.PASS_REQUIRES_OBSERVED_ZERO_EXTERNAL_CALLS");
        assertThat(verifier.verifyGoldenPathAcceptanceManifest(unknown).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.PASS_REQUIRES_OBSERVED_ZERO_EXTERNAL_CALLS");
    }

    @Test
    void rejectsPayloadLikeEvidenceFieldsWithoutEchoingTheirValue() throws IOException {
        ObjectNode candidate = rootFixture(MANIFEST).deepCopy();
        ObjectNode firstGate = (ObjectNode) candidate.withArray("gpResults").get(0);
        ObjectNode evidence = firstGate.withArray("evidenceRefs").addObject();
        evidence.put("payload", "customer-sensitive-value");

        CapabilityStudioAcceptanceVerifier.VerificationResult result =
                verifier.verifyGoldenPathAcceptanceManifest(candidate);

        assertThat(result.verified()).isFalse();
        assertThat(result.schemaValid()).isFalse();
        assertThat(result.semanticValid()).isFalse();
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.RAW_PAYLOAD_OR_SECRET_FIELD");
        assertThat(result.toString()).doesNotContain("customer-sensitive-value");
    }

    @Test
    void rejectsSchemaUnknownFieldsWithSafeErrorCode() throws IOException {
        ObjectNode candidate = rootFixture(BASELINE).deepCopy();
        candidate.put("rawBusinessPayload", "secret-value");

        CapabilityStudioAcceptanceVerifier.VerificationResult result =
                verifier.verifyAcceptanceBaseline(candidate);

        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.RAW_PAYLOAD_OR_SECRET_FIELD");
        assertThat(result.schemaValid()).isFalse();
        assertThat(result.semanticValid()).isFalse();
        assertThat(result.toString()).doesNotContain("secret-value");
    }

    @Test
    void distinguishesPlainSchemaFailureFromSemanticFailure() throws IOException {
        ObjectNode candidate = rootFixture(BASELINE).deepCopy();
        candidate.put("revision", "not-an-integer");

        CapabilityStudioAcceptanceVerifier.VerificationResult result =
                verifier.verifyAcceptanceBaseline(candidate);

        assertThat(result.schemaValid()).isFalse();
        assertThat(result.semanticValid()).isFalse();
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BASELINE_SCHEMA_INVALID");
    }

    @Test
    void mutatingCandidateDoesNotMutateLoadedRootFixture() throws IOException {
        JsonNode original = rootFixture(MANIFEST);
        String originalJson = original.toString();
        ObjectNode candidate = original.deepCopy();
        candidate.put("status", "ACCEPTED");
        ((ObjectNode) candidate.withArray("scenarioResults").get(0)).put("realCallCount", 7);

        assertThat(original.toString()).isEqualTo(originalJson);
        assertThat(original.path("status").asText()).isEqualTo("NO_GO");
        assertThat(original.path("scenarioResults").get(0).path("realCallCount").isNull())
                .isTrue();
    }

    private static void markManifestGreenExceptEvidence(ObjectNode candidate) {
        candidate.put("status", "ACCEPTED");
        for (JsonNode gate : candidate.withArray("gpResults")) {
            ((ObjectNode) gate).put("status", "PASSED");
        }
        for (JsonNode scenario : candidate.withArray("scenarioResults")) {
            ObjectNode value = (ObjectNode) scenario;
            value.put("status", "PASSED");
            value.put("assertionStatus", "PASSED");
            value.put("realCallCount", 0);
        }
        candidate.put("realExternalCallCount", 0);
        ObjectNode egress = (ObjectNode) candidate.path("egressObservation");
        egress.put("status", "PASSED");
        egress.put("observedAt", "2026-08-17T12:00:00Z");
        egress.put("count", 0);
        for (JsonNode value : candidate.withArray("browserAndViewportResults")) {
            ((ObjectNode) value).put("status", "PASSED");
        }
        for (JsonNode value : candidate.withArray("accessibilityResults")) {
            ((ObjectNode) value).put("status", "PASSED");
        }
        for (JsonNode value : candidate.withArray("protocolAndSecurityResults")) {
            ((ObjectNode) value).put("status", "PASSED");
        }
        for (JsonNode limitation : candidate.withArray("knownLimitations")) {
            ((ObjectNode) limitation).put("blocksAcceptance", false);
        }
        for (JsonNode signoff : candidate.withArray("signOffs")) {
            ObjectNode value = (ObjectNode) signoff;
            value.put("status", "APPROVED");
            value.put("actorRef", "test-owner");
            value.put("signedAt", "2026-08-17T12:00:00Z");
            value.put("signatureRef", "sig-test");
        }
    }

    private static void refreshArtifactFingerprint(ObjectNode artifact) {
        artifact.putNull("artifactFingerprint");
        artifact.put("artifactFingerprint", EvidenceVerificationSupport.sha256(artifact));
    }

    private static JsonNode rootFixture(String relativePath) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        Path path = directory.resolve(relativePath);
        for (int depth = 0; depth < 4 && !Files.isRegularFile(path); depth++) {
            directory = directory.getParent();
            if (directory == null) {
                break;
            }
            path = directory.resolve(relativePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Root fixture is absent: " + relativePath);
        }
        return JSON.readTree(Files.readString(path));
    }
}
