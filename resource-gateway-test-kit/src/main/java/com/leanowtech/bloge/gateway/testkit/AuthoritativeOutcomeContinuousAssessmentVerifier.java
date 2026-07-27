package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Independent payload-free verifier for one continuous completeness status.
 *
 * <p>The verifier applies the packaged strict Schema, recomputes the durable projection content
 * address, verifies the server-owned assessment stream, enforces state/lease/time closure, derives
 * source freshness at the producer's database observation time, and derives effective readiness.
 * It does not prove database lease uniqueness or verify the immutable assessment referenced by
 * {@code lastAssessmentRef}; consumers must fetch and independently verify that evidence
 * separately.</p>
 */
public final class
AuthoritativeOutcomeContinuousAssessmentVerifier {
    /** Maximum canonical projection bytes accepted by the verifier. */
    public static final int MAXIMUM_PROJECTION_BYTES =
            256 * 1024;

    /** Creates a stateless verifier that is safe to reuse across status documents. */
    public AuthoritativeOutcomeContinuousAssessmentVerifier() {
    }

    /** Bounded verification outcome. */
    public enum Outcome {
        /** Every offline structural, content-address, and derivation check passed. */
        VERIFIED,
        /** The status is malformed, inconsistent, or content-addressed incorrectly. */
        INVALID
    }

    /**
     * Log-safe payload-free verification result.
     *
     * @param outcome bounded result class
     * @param reasonCode stable machine-readable reason
     * @param projectionId projection identity when structurally available
     * @param assessmentId server-owned assessment stream when structurally available
     * @param recordFingerprint claimed projection content address when structurally available
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String projectionId,
            String assessmentId,
            String recordFingerprint
    ) {
        /** Bounds every diagnostic coordinate and rejects unsafe reason strings. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = bounded(
                    reasonCode, 255);
            projectionId = bounded(
                    projectionId, 512);
            assessmentId = bounded(
                    assessmentId, 512);
            recordFingerprint = bounded(
                    recordFingerprint, 128);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Continuous assessment verification result is invalid");
            }
        }

        /**
         * Reports whether all bounded offline checks passed.
         *
         * @return {@code true} only when every offline check passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one effective status without linking Resource Gateway server classes.
     *
     * @param status decoded status payload
     * @return bounded payload-free result
     */
    public VerificationResult verify(JsonNode status) {
        Coordinates coordinates =
                Coordinates.from(status);
        try {
            CapabilityMirrorSchemaValidator.require(
                    status,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_STATUS_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_CONTINUOUS_ASSESSMENT_STATUS_SCHEMA_INVALID");
            JsonNode projection =
                    status.path("projection");
            requireProjection(
                    projection,
                    instant(status, "observedAt"));
            String derivedFreshness =
                    freshness(
                            projection,
                            instant(status, "observedAt"));
            if (!derivedFreshness.equals(
                    text(status, "sourceFreshness"))) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_FRESHNESS_INVALID");
            }
            boolean derivedReady =
                    "CURRENT".equals(derivedFreshness)
                            && status.path(
                            "authoritiesReady").asBoolean();
            if (derivedReady
                    != status.path("ready").asBoolean()) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_READINESS_INVALID");
            }
            return result(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates);
        } catch (VerificationFailure failure) {
            return result(
                    Outcome.INVALID,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_CONTINUOUS_ASSESSMENT_INVALID",
                    coordinates);
        }
    }

    static void requireProjection(
            JsonNode projection,
            Instant observedAt) {
        CapabilityMirrorSchemaValidator.require(
                projection,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_PROJECTION_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.OUTCOME_CONTINUOUS_ASSESSMENT_PROJECTION_SCHEMA_INVALID");
        ObjectNode material = object(projection);
        String claimed =
                text(projection, "recordFingerprint");
        material.put("recordFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material,
                MAXIMUM_PROJECTION_BYTES).equals(
                claimed)) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_FINGERPRINT_INVALID");
        }
        String projectionId =
                text(projection, "projectionId");
        String assessmentId =
                text(projection, "assessmentId");
        if (!assessmentId.equals(
                "continuous-assessment:"
                        + projectionId)) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_STREAM_INVALID");
        }
        JsonNode lastAssessment =
                projection.path("lastAssessmentRef");
        if (!lastAssessment.isNull()
                && !assessmentId.equals(
                text(lastAssessment, "id"))) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_HEAD_INVALID");
        }
        Instant currentThrough =
                instant(projection, "currentThrough");
        Instant freshUntil =
                instant(projection, "freshUntil");
        Instant nextEligibleAt =
                instant(projection, "nextEligibleAt");
        Instant leaseExpiresAt =
                instant(projection, "leaseExpiresAt");
        Instant createdAt =
                instant(projection, "createdAt");
        Instant updatedAt =
                instant(projection, "updatedAt");
        if (updatedAt.isBefore(createdAt)
                || observedAt.isBefore(updatedAt)
                || !lastAssessment.isNull()
                && (!freshUntil.isAfter(currentThrough)
                || Instant.EPOCH.equals(currentThrough))) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_TIME_INVALID");
        }
        String workStatus =
                text(projection, "status");
        boolean running =
                "RUNNING".equals(workStatus);
        if (running
                && !leaseExpiresAt.isAfter(updatedAt)
                || !running
                && !Instant.EPOCH.equals(leaseExpiresAt)
                || !running
                && nextEligibleAt.isBefore(updatedAt)) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_STATE_INVALID");
        }
        JsonNode terminalAt =
                projection.path("terminalAt");
        if ("QUARANTINED".equals(workStatus)) {
            if (terminalAt.isNull()
                    || !instant(
                    projection, "terminalAt")
                    .equals(updatedAt)) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_TERMINAL_INVALID");
            }
        } else if (!terminalAt.isNull()) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_TERMINAL_INVALID");
        }
    }

    private static String freshness(
            JsonNode projection,
            Instant observedAt) {
        String workStatus =
                text(projection, "status");
        if ("QUARANTINED".equals(workStatus)) {
            return "QUARANTINED";
        }
        if (projection.path(
                "lastAssessmentRef").isNull()) {
            return "RUNNING".equals(workStatus)
                    ? "REFRESHING"
                    : "UNINITIALIZED";
        }
        if ("RUNNING".equals(workStatus)) {
            return "REFRESHING";
        }
        return "QUEUED".equals(workStatus)
                && observedAt.isBefore(
                instant(projection, "freshUntil"))
                ? "CURRENT"
                : "STALE";
    }

    private static ObjectNode object(
            JsonNode value) {
        if (value == null || !value.isObject()) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_OBJECT_INVALID");
        }
        return ((ObjectNode) value).deepCopy();
    }

    private static String text(
            JsonNode value,
            String field) {
        String result =
                value.path(field).asText("");
        if (result.isBlank()) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_FIELD_INVALID");
        }
        return result;
    }

    private static Instant instant(
            JsonNode value,
            String field) {
        try {
            return Instant.parse(
                    text(value, field));
        } catch (DateTimeParseException invalid) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_TIME_INVALID");
            throw new IllegalStateException(
                    "unreachable");
        }
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.projectionId,
                coordinates.assessmentId,
                coordinates.recordFingerprint);
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(
                reasonCode);
    }

    private static String bounded(
            String value,
            int maximum) {
        String exact = value == null
                ? "" : value.trim();
        return exact.length() <= maximum
                ? exact
                : exact.substring(0, maximum);
    }

    private record Coordinates(
            String projectionId,
            String assessmentId,
            String recordFingerprint
    ) {
        private static Coordinates from(
                JsonNode status) {
            JsonNode projection =
                    status == null
                            ? null
                            : status.path("projection");
            if (projection == null
                    || !projection.isObject()) {
                return new Coordinates(
                        "", "", "");
            }
            return new Coordinates(
                    projection.path(
                            "projectionId").asText(""),
                    projection.path(
                            "assessmentId").asText(""),
                    projection.path(
                            "recordFingerprint").asText(""));
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(
                String reasonCode) {
            super(null, null, false, false);
            this.reasonCode = reasonCode;
        }
    }
}
