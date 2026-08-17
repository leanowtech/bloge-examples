package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioBranchProtocolVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FP_B = "sha256:" + "b".repeat(64);
    private static final String FP_C = "sha256:" + "c".repeat(64);

    private final CapabilityStudioBranchProtocolVerifier verifier =
            new CapabilityStudioBranchProtocolVerifier();

    @Test
    void packagesAllGp04Schemas() {
        List<String> resources = List.of(
                CapabilityStudioSchemaSupport.BRANCH_PROJECTION_RESOURCE,
                CapabilityStudioSchemaSupport.BRANCH_UPDATE_REQUEST_RESOURCE,
                CapabilityStudioSchemaSupport.PREFLIGHT_RESOURCE,
                CapabilityStudioSchemaSupport.ERROR_RESOURCE);

        for (String resource : resources) {
            assertThat(getClass().getResource(resource)).as(resource).isNotNull();
        }
    }

    @Test
    void acceptsAValidChangedSaveAndExactIsolatedPreflight() {
        ObjectNode after = changedAfter();
        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier.verify(
                before(), after,
                isolatedPreflight(after.path("fingerprint").textValue(), 2));

        assertThat(result.verified()).isTrue();
        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.NONE);
        assertThat(result.checks()).containsExactly(
                "BEFORE_PROJECTION_SCHEMA",
                "AFTER_PROJECTION_SCHEMA",
                "PREFLIGHT_SCHEMA",
                "REVISION_MONOTONICITY",
                "CONTENT_FINGERPRINT",
                "CANONICAL_BASELINE_BINDING",
                "PREFLIGHT_AFTER_BINDING",
                "ISOLATED_PREFLIGHT");
    }

    @Test
    void acceptsAnIdempotentSaveWithTheSameRevisionAndFingerprint() {
        ObjectNode unchanged = before();

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier.verify(
                before(), unchanged,
                isolatedPreflight(unchanged.path("fingerprint").textValue(), 1));

        assertThat(result.verified()).isTrue();
    }

    @Test
    void rejectsCanonicalBaselineMutation() {
        ObjectNode after = changedAfter();
        after.put("canonicalBaselineFingerprint", FP_C);

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                .verifyAfterProjection(before(), after);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.CANONICAL_BASELINE_FINGERPRINT_DRIFT");
    }

    @Test
    void rejectsContentChangeWithoutRevisionIncrement() {
        ObjectNode after = changedAfter();
        after.put("revision", 1);

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                .verifyAfterProjection(before(), after);

        assertThat(result.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.BRANCH_CONTENT_CHANGE_REQUIRES_NEXT_REVISION");
    }

    @Test
    void rejectsContentChangeWithoutFingerprintChange() {
        ObjectNode after = changedAfter();
        after.put("fingerprint", before().path("fingerprint").textValue());

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                .verifyAfterProjection(before(), after);

        assertThat(result.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.BRANCH_AFTER_CONTENT_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsFingerprintMutationWithoutContentChange() {
        ObjectNode after = before();
        after.put("fingerprint", FP_C);

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                .verifyAfterProjection(before(), after);

        assertThat(result.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.BRANCH_AFTER_CONTENT_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsARevisionGapForOneContentChange() {
        ObjectNode after = changedAfter();
        after.put("revision", 3);

        assertThat(verifier.verifyAfterProjection(before(), after).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BRANCH_CONTENT_CHANGE_REQUIRES_NEXT_REVISION");
    }

    @Test
    void rejectsRevisionChurnForAnIdempotentSave() {
        ObjectNode after = before();
        after.put("revision", 2);

        assertThat(verifier.verifyAfterProjection(before(), after).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BRANCH_REVISION_CHANGED_WITHOUT_CONTENT_CHANGE");
    }

    @Test
    void rejectsPreflightThatCallsARealDependency() {
        ObjectNode after = changedAfter();
        ObjectNode preflight = isolatedPreflight(
                after.path("fingerprint").textValue(), 2);
        preflight.put("realExternalCallCount", 1);

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                .verifyPreflight(after, preflight);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.SEMANTIC);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.PREFLIGHT_REAL_EXTERNAL_CALLS");
    }

    @Test
    void rejectsPreflightWithRealFallbackAtSchemaBoundary() {
        ObjectNode after = changedAfter();
        ObjectNode preflight = isolatedPreflight(
                after.path("fingerprint").textValue(), 2);
        preflight.put("fallbackToReal", true);

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                .verifyPreflight(after, preflight);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.PREFLIGHT_SCHEMA_INVALID");
    }

    @Test
    void rejectsPreflightBoundToTheWrongAfterRevisionAndFingerprint() {
        ObjectNode before = before();
        ObjectNode preflight = isolatedPreflight(
                before.path("fingerprint").textValue(), 1);

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                .verifyPreflight(changedAfter(), preflight);

        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.PREFLIGHT_REVISION_MISMATCH");
    }

    @Test
    void rejectsEveryForbiddenRequestPropertyAsAnUnknownStrictProperty() {
        ObjectNode request = validUpdateRequest();
        for (String forbidden : List.of(
                "fixture", "mock", "payload", "replay", "bindingOverride")) {
            ObjectNode candidate = request.deepCopy();
            candidate.put(forbidden, "redacted-value");

            CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                    .verifyUpdateRequest(candidate);

            assertThat(result.failureKind())
                    .as(forbidden)
                    .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.SCHEMA);
            assertThat(result.errorCode()).as(forbidden).isEqualTo(
                    "RG.CAPABILITY_STUDIO.BRANCH_UPDATE_REQUEST_SCHEMA_INVALID");
            assertThat(result.toString()).doesNotContain("redacted-value");
        }
    }

    @Test
    void rejectsOutOfRangeTimeoutDurationAndInvalidFingerprint() {
        ObjectNode request = validUpdateRequest();
        request.put("durationMs", 99);
        assertThat(verifier.verifyUpdateRequest(request).verified()).isFalse();

        ObjectNode projection = before();
        projection.put("fingerprint", "sha256:" + "A".repeat(64));
        assertThat(verifier.verifyBeforeProjection(projection).failureKind())
                .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.SCHEMA);
    }

    @Test
    void rejectsAnErrorWithoutRecoveryAction() {
        ObjectNode error = JSON.createObjectNode()
                .put("code", "RG.CAPABILITY_STUDIO.BAD_REQUEST")
                .put("whatHappened", "The request was rejected.")
                .put("impact", "The branch was not saved.");

        CapabilityStudioBranchProtocolVerifier.VerificationResult result = verifier
                .verifyError(error);

        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.SCHEMA);
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.ERROR_SCHEMA_INVALID");
    }

    @Test
    void distinguishesSchemaFailureFromSemanticFailureWithoutLeakingInput() {
        ObjectNode malformed = before();
        malformed.put("revision", 0);
        CapabilityStudioBranchProtocolVerifier.VerificationResult schema = verifier
                .verifyBeforeProjection(malformed);

        ObjectNode semanticAfter = changedAfter();
        semanticAfter.put("revision", 1);
        CapabilityStudioBranchProtocolVerifier.VerificationResult semantic = verifier
                .verifyAfterProjection(before(), semanticAfter);

        assertThat(schema.failureKind())
                .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.SCHEMA);
        assertThat(semantic.failureKind())
                .isEqualTo(CapabilityStudioBranchProtocolVerifier.FailureKind.SEMANTIC);
        assertThat(schema.toString()).doesNotContain("branch-secret");
        assertThat(semantic.toString()).doesNotContain("business-payload");
    }

    private static ObjectNode before() {
        return projection(1, FP_B, "status == 'timeout'", 1000);
    }

    private static ObjectNode changedAfter() {
        return projection(2, FP_B, "status == 'timeout' && retryable", 1500);
    }

    private static ObjectNode projection(
            long revision,
            String baselineFingerprint,
            String condition,
            int durationMs) {
        ObjectNode projection = JSON.createObjectNode()
                .put("branchId", "branch-timeout-demo")
                .put("revision", revision)
                .put("canonicalBaselineFingerprint", baselineFingerprint)
                .set("behavior", JSON.createObjectNode()
                        .put("dependencyId", "customer-profile")
                        .put("dependencyName", "customer-profile")
                        .put("condition", condition)
                        .put("behavior", "TIMEOUT")
                        .put("durationMs", durationMs));
        projection.put("fingerprint", fingerprint(projection));
        return projection;
    }

    private static String fingerprint(ObjectNode projection) {
        ObjectNode canonical = JSON.createObjectNode();
        canonical.put("schemaVersion", 1);
        canonical.put("branchId", projection.path("branchId").textValue());
        canonical.put("canonicalBaselineFingerprint",
                projection.path("canonicalBaselineFingerprint").textValue());
        JsonNode behavior = projection.path("behavior");
        ObjectNode canonicalBehavior = canonical.putObject("behavior");
        canonicalBehavior.put("dependencyId", behavior.path("dependencyId").textValue());
        canonicalBehavior.put("condition", behavior.path("condition").textValue());
        canonicalBehavior.put("behavior", behavior.path("behavior").textValue());
        canonicalBehavior.put("durationMs", behavior.path("durationMs").longValue());
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(JSON.writeValueAsBytes(canonical)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ObjectNode isolatedPreflight(String fingerprint, long revision) {
        return JSON.createObjectNode()
                .put("mode", "ISOLATED")
                .put("unresolvedDependencies", 0)
                .put("realExternalCallCount", 0)
                .put("fallbackToReal", false)
                .put("branchId", "branch-timeout-demo")
                .put("revision", revision)
                .put("fingerprint", fingerprint);
    }

    private static ObjectNode validUpdateRequest() {
        return JSON.createObjectNode()
                .put("condition", "status == 'timeout'")
                .put("behavior", "TIMEOUT")
                .put("durationMs", 1000)
                .put("expectedRevision", 1);
    }
}
