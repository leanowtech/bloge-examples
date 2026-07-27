package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeContinuousAssessmentSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FINGERPRINT =
            "sha256:" + "a".repeat(64);

    @Test
    void acceptsClosedInitialRegistrationProjectionAndAdmission() {
        ObjectNode request = request();
        ObjectNode projection = projection();
        ObjectNode status = status(projection);
        ObjectNode admission = JSON.createObjectNode();
        admission.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_ADMISSION_V1);
        admission.set("status", status);
        admission.put("idempotentReplay", false);

        assertThatCode(() -> require(
                request,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REQUEST_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> require(
                projection,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_PROJECTION_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> require(
                status,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_STATUS_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
        assertThatCode(() -> require(
                admission,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_ADMISSION_SCHEMA_RESOURCE))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAReadyClaimWithoutCurrentSourceFreshness() {
        ObjectNode status = status(projection());
        status.put("ready", true);

        assertThatThrownBy(() -> require(
                status,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_STATUS_SCHEMA_RESOURCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownProjectionFieldsAndWrongPopulationKinds() {
        ObjectNode projection = projection();
        projection.put("memberPayload", "must-not-cross");
        ObjectNode request = request();
        ((ObjectNode) request.path("populationRef"))
                .put("kind", "CALLER_SELECTED_KIND");

        assertThatThrownBy(() -> require(
                projection,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_PROJECTION_SCHEMA_RESOURCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> require(
                request,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REQUEST_SCHEMA_RESOURCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode request() {
        ObjectNode request = JSON.createObjectNode();
        request.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REQUEST_V1);
        request.put("projectionId", "refunds/continuous");
        request.set("populationRef", populationRef());
        return request;
    }

    private static ObjectNode projection() {
        ObjectNode projection = JSON.createObjectNode();
        projection.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_PROJECTION_V1);
        projection.set(
                "scope",
                JSON.createObjectNode()
                        .put("tenantId", "tenant-a")
                        .put("organizationId", "org-a")
                        .put("projectId", "support")
                        .put("environmentId", "staging")
                        .put("region", "sg"));
        projection.put(
                "projectionId", "refunds/continuous");
        projection.set("populationRef", populationRef());
        projection.put(
                "assessmentId",
                "continuous-assessment:refunds/continuous");
        projection.put("status", "QUEUED");
        projection.putNull("lastAssessmentRef");
        projection.put("observationSetFingerprint", "");
        projection.put("dispositionSetFingerprint", "");
        projection.put(
                "currentThrough", Instant.EPOCH.toString());
        projection.put(
                "freshUntil", Instant.EPOCH.toString());
        projection.put("attemptCount", 0);
        projection.put("consecutiveFailures", 0);
        projection.put(
                "nextEligibleAt", "2026-07-27T00:00:00Z");
        projection.put("leaseOwnerFingerprint", "");
        projection.put("leaseEpoch", 0);
        projection.put(
                "leaseExpiresAt", Instant.EPOCH.toString());
        projection.put("failureCode", "");
        projection.put(
                "createdAt", "2026-07-27T00:00:00Z");
        projection.put(
                "updatedAt", "2026-07-27T00:00:00Z");
        projection.putNull("terminalAt");
        projection.put("recordFingerprint", FINGERPRINT);
        return projection;
    }

    private static ObjectNode status(ObjectNode projection) {
        ObjectNode status = JSON.createObjectNode();
        status.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_STATUS_V1);
        status.set("projection", projection);
        status.put(
                "observedAt", "2026-07-27T00:00:00Z");
        status.put("sourceFreshness", "UNINITIALIZED");
        status.put("authoritiesReady", true);
        status.put("ready", false);
        return status;
    }

    private static ObjectNode populationRef() {
        return JSON.createObjectNode()
                .put(
                        "kind",
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST")
                .put("id", "refund-population")
                .put("revision", 3)
                .put("fingerprint", FINGERPRINT);
    }

    private static void require(
            ObjectNode value,
            String schemaResource) {
        CapabilityMirrorSchemaValidator.require(
                value,
                schemaResource,
                "RG.MIRROR.CLIENT.CONTINUOUS_ASSESSMENT_SCHEMA_INVALID");
    }
}
