package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Dependency-light independent verifier for a durable Shadow job export.
 *
 * <p>The verifier recomputes the immutable request fingerprint, deterministic job id, mutable job
 * record fingerprint, lifecycle correspondence, and exact request/job scope. A successful job
 * additionally requires a v2 comparison whose signature, policy, source-resolution attestation,
 * grant proof, and artifact reference independently verify. Queue uniqueness and live lease
 * ownership remain online database properties and cannot be inferred from one exported row.</p>
 */
public final class ReadOnlyShadowJobVerifier {
    /** Maximum request bytes admitted to canonical hashing. */
    public static final int MAXIMUM_REQUEST_BYTES =
            256 * 1024;
    /** Maximum public job bytes admitted to canonical hashing. */
    public static final int MAXIMUM_JOB_BYTES =
            256 * 1024;

    private final ReadOnlyShadowComparisonVerifier
            comparisonVerifier;

    /** Creates a verifier using packaged strict Schemas and the independent comparison verifier. */
    public ReadOnlyShadowJobVerifier() {
        comparisonVerifier =
                new ReadOnlyShadowComparisonVerifier();
    }

    /** Bounded payload-free verification outcome. */
    public enum Outcome {
        /** Every structural, lifecycle, fingerprint, signature, and policy check passed. */
        VERIFIED,
        /** The export is structurally inconsistent, corrupt, incomplete, or otherwise invalid. */
        INVALID,
        /** The comparison signature key was not available to the verifier. */
        COMPARISON_KEY_UNAVAILABLE,
        /** The comparison is authentic but violates the accepted certification policy. */
        COMPARISON_POLICY_REJECTED
    }

    /**
     * Log-safe verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param jobId job identity when structurally available
     * @param requestFingerprint request fingerprint when structurally available
     * @param comparisonFingerprint terminal comparison fingerprint when available
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String jobId,
            String requestFingerprint,
            String comparisonFingerprint
    ) {
        /** Normalizes a bounded result and rejects unsafe reason strings. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = bounded(
                    reasonCode, 255);
            jobId = bounded(jobId, 512);
            requestFingerprint = bounded(
                    requestFingerprint, 128);
            comparisonFingerprint = bounded(
                    comparisonFingerprint, 128);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Shadow job verification result is invalid");
            }
        }

        /**
         * Reports whether every offline structural and cryptographic check passed.
         *
         * @return {@code true} only for {@link Outcome#VERIFIED}
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one job/request export and its terminal comparison when successful.
     *
     * @param job decoded public job projection
     * @param request decoded immutable submission
     * @param comparison decoded signed comparison; required only for successful jobs
     * @param comparisonKey key selected by the comparison seal
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode job,
            JsonNode request,
            JsonNode comparison,
            EvidenceVerificationKey comparisonKey) {
        Coordinates coordinates =
                Coordinates.from(job);
        try {
            CapabilityMirrorSchemaValidator.require(
                    request,
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_JOB_REQUEST_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_JOB_REQUEST_SCHEMA_INVALID");
            CapabilityMirrorSchemaValidator.require(
                    job,
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_JOB_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_JOB_SCHEMA_INVALID");
            verifyClosure(job, request);
            String status = text(job, "status");
            if ("SUCCEEDED".equals(status)) {
                if (comparison == null
                        || comparison.isNull()) {
                    return result(
                            Outcome.INVALID,
                            "SHADOW_JOB_COMPARISON_MISSING",
                            coordinates);
                }
                ReadOnlyShadowComparisonVerifier
                        .VerificationResult verified =
                        comparisonVerifier.verify(
                                comparison,
                                comparisonKey);
                if (!verified.verified()) {
                    Outcome outcome = switch (
                            verified.outcome()) {
                        case KEY_UNAVAILABLE ->
                                Outcome
                                        .COMPARISON_KEY_UNAVAILABLE;
                        case POLICY_REJECTED ->
                                Outcome
                                        .COMPARISON_POLICY_REJECTED;
                        case INVALID, VERIFIED ->
                                Outcome.INVALID;
                    };
                    return result(
                            outcome,
                            "SHADOW_JOB_"
                                    + verified.reasonCode(),
                            coordinates);
                }
                verifyComparisonClosure(
                        job, request, comparison);
                return result(
                        Outcome.VERIFIED,
                        "VERIFIED",
                        Coordinates.from(
                                job,
                                text(
                                        comparison,
                                        "comparisonFingerprint")));
            }
            if (comparison != null
                    && !comparison.isNull()
                    || !job.path("comparisonRef")
                    .isNull()) {
                fail("SHADOW_JOB_UNEXPECTED_COMPARISON");
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
                    "SHADOW_JOB_CLOSURE_INVALID",
                    coordinates);
        }
    }

    private static void verifyClosure(
            JsonNode job,
            JsonNode request) {
        String requestFingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                request,
                                MAXIMUM_REQUEST_BYTES);
        if (!requestFingerprint.equals(
                text(job, "requestFingerprint"))
                || !("shadow-"
                + requestFingerprint.substring(
                "sha256:".length()))
                .equals(text(job, "jobId"))
                || !text(request, "requestId").equals(
                text(job, "requestId"))
                || !request.path("scope").equals(
                job.path("scope"))
                || !request.path("deadlineAt").equals(
                job.path("deadlineAt"))) {
            fail("SHADOW_JOB_REQUEST_CLOSURE_INVALID");
        }
        ObjectNode material = job.deepCopy();
        material.put("recordFingerprint", "");
        if (!EvidenceVerificationSupport
                .sha256Bounded(
                        material,
                        MAXIMUM_JOB_BYTES)
                .equals(text(
                        job, "recordFingerprint"))) {
            fail("SHADOW_JOB_RECORD_FINGERPRINT_INVALID");
        }
        Instant createdAt = instant(
                job.path("createdAt"));
        Instant updatedAt = instant(
                job.path("updatedAt"));
        Instant deadlineAt = instant(
                job.path("deadlineAt"));
        Instant nextEligibleAt = instant(
                job.path("nextEligibleAt"));
        Instant leaseExpiresAt = instant(
                job.path("leaseExpiresAt"));
        String status = text(job, "status");
        boolean terminal =
                "SUCCEEDED".equals(status)
                        || "FAILED".equals(status)
                        || "EXPIRED".equals(status);
        JsonNode completed = job.path("completedAt");
        int attempts = job.path(
                "attemptCount").asInt(-1);
        int maximumAttempts = job.path(
                "maximumAttempts").asInt(-1);
        long leaseEpoch = job.path(
                "leaseEpoch").asLong(-1);
        String failureCode = text(
                job, "failureCode");
        if (attempts < 0
                || maximumAttempts < 1
                || attempts > maximumAttempts
                || leaseEpoch < 0
                || updatedAt.isBefore(createdAt)
                || deadlineAt.isBefore(createdAt)
                || nextEligibleAt.isBefore(createdAt)
                || nextEligibleAt.isAfter(deadlineAt)
                || leaseExpiresAt.isBefore(createdAt)
                || leaseExpiresAt.isAfter(deadlineAt)
                || terminal == completed.isNull()
                || !completed.isNull()
                && instant(completed).isBefore(createdAt)
                || "QUEUED".equals(status)
                && attempts >= maximumAttempts
                || "RUNNING".equals(status)
                && (attempts < 1
                || leaseEpoch < 1
                || !leaseExpiresAt.isAfter(updatedAt))
                || "SUCCEEDED".equals(status)
                && (!failureCode.isBlank()
                || job.path("comparisonRef").isNull())
                || !"SUCCEEDED".equals(status)
                && !job.path("comparisonRef").isNull()
                || ("FAILED".equals(status)
                || "EXPIRED".equals(status))
                && failureCode.isBlank()) {
            fail("SHADOW_JOB_LIFECYCLE_INVALID");
        }
    }

    private static void verifyComparisonClosure(
            JsonNode job,
            JsonNode request,
            JsonNode comparison) {
        JsonNode accessGrant =
                request.path("accessGrant");
        JsonNode accessProof =
                comparison.path("accessProof");
        if (!CapabilityMirrorProtocol
                .READ_ONLY_SHADOW_COMPARISON_V2
                .equals(text(
                        comparison, "schemaVersion"))
                || !text(job, "jobId").equals(
                text(comparison, "comparisonId"))
                || comparison.path("revision").asLong()
                != 1
                || !request.path("scope").equals(
                comparison.path("scope"))
                || !request.path("inventoryRef").equals(
                comparison.path("inventoryRef"))
                || !text(request, "unitId").equals(
                text(comparison, "unitId"))
                || !request.path("scenarioCaseRef").equals(
                comparison.path("scenarioCaseRef"))
                || !request.path("targetCapabilityRef").equals(
                comparison.path("targetCapabilityRef"))
                || !request.path("comparisonPolicyRef").equals(
                comparison.path("comparisonPolicyRef"))
                || !accessGrant.path("accessMode").equals(
                accessProof.path("accessMode"))
                || !accessGrant.path("samplingGrantRef").equals(
                accessProof.path("samplingGrantRef"))
                || !accessGrant.path("egressAuthorityRef").equals(
                accessProof.path("egressAuthorityRef"))
                || !accessGrant.path("killSwitchRef").equals(
                accessProof.path("killSwitchRef"))
                || !accessGrant.path("sampleOrdinal").equals(
                accessProof.path("sampleOrdinal"))
                || !accessGrant.path("maximumSamples").equals(
                accessProof.path("maximumSamples"))
                || accessProof.path(
                "writeCredentialExposed").asBoolean()
                || accessProof.path(
                "writeAttemptCount").asLong() != 0
                || !job.path("comparisonRef").equals(
                comparisonRef(comparison))) {
            fail("SHADOW_JOB_COMPARISON_CLOSURE_INVALID");
        }
    }

    private static ObjectNode comparisonRef(
            JsonNode comparison) {
        ObjectNode ref =
                com.fasterxml.jackson.databind.node
                        .JsonNodeFactory.instance
                        .objectNode();
        ref.put(
                "kind",
                "FIDELITY_SHADOW_COMPARISON");
        ref.put(
                "id",
                text(comparison, "comparisonId"));
        ref.set(
                "revision",
                comparison.path("revision").deepCopy());
        ref.put(
                "fingerprint",
                text(
                        comparison,
                        "comparisonFingerprint"));
        return ref;
    }

    private static Instant instant(
            JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (RuntimeException invalid) {
            fail("SHADOW_JOB_TIME_INVALID");
            throw new IllegalStateException();
        }
    }

    private static String text(
            JsonNode value,
            String field) {
        return value == null
                ? "" : value.path(field).asText("");
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reason,
                coordinates.jobId,
                coordinates.requestFingerprint,
                coordinates.comparisonFingerprint);
    }

    private static void fail(String reason) {
        throw new VerificationFailure(reason);
    }

    private static String bounded(
            String value,
            int maximumLength) {
        String normalized = value == null
                ? "" : value.trim();
        return normalized.length() <= maximumLength
                ? normalized
                : normalized.substring(0, maximumLength);
    }

    private record Coordinates(
            String jobId,
            String requestFingerprint,
            String comparisonFingerprint
    ) {
        private static Coordinates from(
                JsonNode job) {
            return from(job, "");
        }

        private static Coordinates from(
                JsonNode job,
                String comparisonFingerprint) {
            return new Coordinates(
                    text(job, "jobId"),
                    text(job, "requestFingerprint"),
                    comparisonFingerprint);
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(
                String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }
    }
}
