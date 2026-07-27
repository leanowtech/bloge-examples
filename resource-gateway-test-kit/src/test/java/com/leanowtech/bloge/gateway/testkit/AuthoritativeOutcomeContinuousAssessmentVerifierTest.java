package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeContinuousAssessmentVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final String FINGERPRINT =
            "sha256:" + "a".repeat(64);
    private final AuthoritativeOutcomeContinuousAssessmentVerifier
            verifier =
            new AuthoritativeOutcomeContinuousAssessmentVerifier();

    @Test
    void verifiesCanonicalUninitializedProjection() {
        ObjectNode status =
                uninitializedStatus();

        var result = verifier.verify(status);

        assertThat(result.verified()).isTrue();
        assertThat(result.projectionId())
                .isEqualTo("refund-completeness");
        assertThat(result.assessmentId())
                .isEqualTo(
                        "continuous-assessment:refund-completeness");
    }

    @Test
    void rejectsProjectionMutationAfterContentAddressing() {
        ObjectNode status =
                uninitializedStatus();
        ((ObjectNode) status.path("projection"))
                .put("attemptCount", 1);

        var result = verifier.verify(status);

        assertThat(result.verified()).isFalse();
        assertThat(result.reasonCode())
                .isEqualTo(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_FINGERPRINT_INVALID");
    }

    @Test
    void treatsTheFreshnessDeadlineAsExclusive() {
        ObjectNode status =
                currentStatus(
                        "2026-07-27T00:01:00Z");

        var result = verifier.verify(status);

        assertThat(result.verified()).isFalse();
        assertThat(result.reasonCode())
                .isEqualTo(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_FRESHNESS_INVALID");
    }

    @Test
    void acceptsCurrentStatusStrictlyBeforeFreshnessDeadline() {
        ObjectNode status =
                currentStatus(
                        "2026-07-27T00:00:59.999Z");

        assertThat(verifier.verify(status)
                .verified()).isTrue();
    }

    static ObjectNode uninitializedStatus() {
        ObjectNode projection =
                baseProjection();
        projection.put("status", "QUEUED");
        projection.putNull("lastAssessmentRef");
        projection.put(
                "observationSetFingerprint", "");
        projection.put(
                "dispositionSetFingerprint", "");
        projection.put(
                "currentThrough",
                "1970-01-01T00:00:00Z");
        projection.put(
                "freshUntil",
                "1970-01-01T00:00:00Z");
        projection.put(
                "nextEligibleAt",
                "2026-07-27T00:00:00Z");
        seal(projection);
        return status(
                projection,
                "2026-07-27T00:00:00Z",
                "UNINITIALIZED",
                true,
                false);
    }

    private static ObjectNode currentStatus(
            String observedAt) {
        ObjectNode projection =
                baseProjection();
        projection.put("status", "QUEUED");
        ObjectNode assessmentRef =
                projection.putObject(
                        "lastAssessmentRef");
        assessmentRef.put(
                "kind",
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_COMPLETENESS");
        assessmentRef.put(
                "id",
                "continuous-assessment:refund-completeness");
        assessmentRef.put("revision", 1);
        assessmentRef.put(
                "fingerprint", FINGERPRINT);
        projection.put(
                "observationSetFingerprint",
                FINGERPRINT);
        projection.put(
                "dispositionSetFingerprint",
                FINGERPRINT);
        projection.put(
                "currentThrough",
                "2026-07-27T00:00:00Z");
        projection.put(
                "freshUntil",
                "2026-07-27T00:01:00Z");
        projection.put(
                "nextEligibleAt",
                "2026-07-27T00:01:00Z");
        seal(projection);
        return status(
                projection,
                observedAt,
                "CURRENT",
                true,
                true);
    }

    private static ObjectNode baseProjection() {
        ObjectNode projection =
                JSON.createObjectNode();
        projection.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_PROJECTION_V1);
        ObjectNode scope =
                projection.putObject("scope");
        scope.put("tenantId", "tenant-a");
        scope.put("organizationId", "org-a");
        scope.put("projectId", "support");
        scope.put("environmentId", "staging");
        scope.put("region", "sg");
        projection.put(
                "projectionId", "refund-completeness");
        ObjectNode populationRef =
                projection.putObject("populationRef");
        populationRef.put(
                "kind",
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST");
        populationRef.put("id", "refund-population");
        populationRef.put("revision", 3);
        populationRef.put(
                "fingerprint", FINGERPRINT);
        projection.put(
                "assessmentId",
                "continuous-assessment:refund-completeness");
        projection.put("attemptCount", 0);
        projection.put("consecutiveFailures", 0);
        projection.put("leaseOwnerFingerprint", "");
        projection.put("leaseEpoch", 0);
        projection.put(
                "leaseExpiresAt",
                "1970-01-01T00:00:00Z");
        projection.put("failureCode", "");
        projection.put(
                "createdAt", "2026-07-27T00:00:00Z");
        projection.put(
                "updatedAt", "2026-07-27T00:00:00Z");
        projection.putNull("terminalAt");
        return projection;
    }

    private static ObjectNode status(
            ObjectNode projection,
            String observedAt,
            String freshness,
            boolean authoritiesReady,
            boolean ready) {
        ObjectNode status =
                JSON.createObjectNode();
        status.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_STATUS_V1);
        status.set("projection", projection);
        status.put("observedAt", observedAt);
        status.put(
                "sourceFreshness", freshness);
        status.put(
                "authoritiesReady", authoritiesReady);
        status.put("ready", ready);
        return status;
    }

    static void seal(
            ObjectNode projection) {
        projection.put("recordFingerprint", "");
        projection.put(
                "recordFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        projection,
                        AuthoritativeOutcomeContinuousAssessmentVerifier
                                .MAXIMUM_PROJECTION_BYTES));
    }
}
