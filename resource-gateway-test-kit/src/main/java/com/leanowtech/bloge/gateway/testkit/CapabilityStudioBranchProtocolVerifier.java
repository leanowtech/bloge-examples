package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline verifier for the Capability Studio GP-04 branch protocol.
 *
 * <p>The verifier validates the strict v1 branch projection, update request, preflight, and
 * error schemas, then applies the invariants that require more than one document. It never
 * returns a JSON path, field value, business payload, secret, or validator detail. This makes
 * the result safe to place in CI logs and cross-system governance records.</p>
 */
public final class CapabilityStudioBranchProtocolVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The kind of failure returned by a verification operation. */
    public enum FailureKind {
        /** No failure occurred. */
        NONE,
        /** One of the input documents violates its strict JSON Schema. */
        SCHEMA,
        /** Documents are individually valid but violate a cross-document invariant. */
        SEMANTIC
    }

    /**
     * Payload-free result of a GP-04 verification operation.
     *
     * @param failureKind schema or semantic failure classification
     * @param checks completed verification groups
     * @param errorCode stable machine-readable failure code, or {@code null}
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode) {
        /** Creates an immutable, log-safe result. */
        public VerificationResult {
            if (failureKind == null) {
                throw new IllegalArgumentException("failureKind is required");
            }
            checks = checks == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(checks));
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("errorCode is not a protocol code");
            }
        }

        /**
         * Returns whether all requested structural and semantic checks passed.
         *
         * @return true only when no failure was found
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }

        /**
         * Returns whether no schema-level failure occurred.
         *
         * @return false only when the result has a schema failure
         */
        public boolean schemaValid() {
            return failureKind != FailureKind.SCHEMA;
        }

        /**
         * Returns whether semantic verification completed successfully.
         *
         * @return true only when the result is fully verified
         */
        public boolean semanticValid() {
            return failureKind == FailureKind.NONE;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioBranchProtocolVerifier() {
    }

    /**
     * Verifies a complete before/after/preflight exchange.
     *
     * @param beforeProjection projection before the save attempt
     * @param afterProjection projection returned after the save attempt
     * @param preflight preflight returned for the after projection
     * @return payload-free verification result
     */
    public VerificationResult verify(
            JsonNode beforeProjection,
            JsonNode afterProjection,
            JsonNode preflight) {
        VerificationResult schemaResult = verifySchemas(
                beforeProjection, afterProjection, preflight);
        if (!schemaResult.verified()) {
            return schemaResult;
        }
        VerificationResult projectionResult = verifyProjectionPair(
                beforeProjection, afterProjection);
        if (!projectionResult.verified()) {
            return projectionResult;
        }
        VerificationResult preflightResult = verifyPreflightSemantics(
                afterProjection, preflight);
        if (!preflightResult.verified()) {
            return preflightResult;
        }
        return valid(
                "BEFORE_PROJECTION_SCHEMA",
                "AFTER_PROJECTION_SCHEMA",
                "PREFLIGHT_SCHEMA",
                "REVISION_MONOTONICITY",
                "CONTENT_FINGERPRINT",
                "CANONICAL_BASELINE_BINDING",
                "PREFLIGHT_AFTER_BINDING",
                "ISOLATED_PREFLIGHT");
    }

    /**
     * Verifies only the strict before projection schema.
     *
     * @param projection decoded projection JSON
     * @return payload-free verification result
     */
    public VerificationResult verifyBeforeProjection(JsonNode projection) {
        return verifySchema(projection, CapabilityStudioSchemaSupport.BRANCH_PROJECTION_RESOURCE,
                "RG.CAPABILITY_STUDIO.BRANCH_BEFORE_SCHEMA_INVALID",
                "BEFORE_PROJECTION_SCHEMA");
    }

    /**
     * Verifies before and after projections plus their revision/fingerprint invariants.
     *
     * @param beforeProjection projection before the save attempt
     * @param afterProjection projection returned after the save attempt
     * @return payload-free verification result
     */
    public VerificationResult verifyAfterProjection(
            JsonNode beforeProjection,
            JsonNode afterProjection) {
        VerificationResult beforeResult = verifyBeforeProjection(beforeProjection);
        if (!beforeResult.verified()) {
            return beforeResult;
        }
        VerificationResult afterResult = verifySchema(afterProjection,
                CapabilityStudioSchemaSupport.BRANCH_PROJECTION_RESOURCE,
                "RG.CAPABILITY_STUDIO.BRANCH_AFTER_SCHEMA_INVALID",
                "AFTER_PROJECTION_SCHEMA");
        if (!afterResult.verified()) {
            return afterResult;
        }
        return verifyProjectionPair(beforeProjection, afterProjection);
    }

    /**
     * Verifies a preflight and its exact binding to an after projection.
     *
     * @param afterProjection projection the preflight claims to assess
     * @param preflight decoded preflight JSON
     * @return payload-free verification result
     */
    public VerificationResult verifyPreflight(
            JsonNode afterProjection,
            JsonNode preflight) {
        VerificationResult afterResult = verifySchema(afterProjection,
                CapabilityStudioSchemaSupport.BRANCH_PROJECTION_RESOURCE,
                "RG.CAPABILITY_STUDIO.BRANCH_AFTER_SCHEMA_INVALID",
                "AFTER_PROJECTION_SCHEMA");
        if (!afterResult.verified()) {
            return afterResult;
        }
        VerificationResult preflightResult = verifySchema(preflight,
                CapabilityStudioSchemaSupport.PREFLIGHT_RESOURCE,
                "RG.CAPABILITY_STUDIO.PREFLIGHT_SCHEMA_INVALID",
                "PREFLIGHT_SCHEMA");
        if (!preflightResult.verified()) {
            return preflightResult;
        }
        return verifyPreflightSemantics(afterProjection, preflight);
    }

    /**
     * Verifies the strict PUT request contract. Unknown properties are rejected by the schema,
     * including fixture, mock, payload, replay, and bindingOverride.
     *
     * @param request decoded update request JSON
     * @return payload-free verification result
     */
    public VerificationResult verifyUpdateRequest(JsonNode request) {
        return verifySchema(request,
                CapabilityStudioSchemaSupport.BRANCH_UPDATE_REQUEST_RESOURCE,
                "RG.CAPABILITY_STUDIO.BRANCH_UPDATE_REQUEST_SCHEMA_INVALID",
                "BRANCH_UPDATE_REQUEST_SCHEMA");
    }

    /**
     * Verifies the safe error response contract, including its required recovery action.
     *
     * @param error decoded error JSON
     * @return payload-free verification result
     */
    public VerificationResult verifyError(JsonNode error) {
        return verifySchema(error, CapabilityStudioSchemaSupport.ERROR_RESOURCE,
                "RG.CAPABILITY_STUDIO.ERROR_SCHEMA_INVALID", "ERROR_SCHEMA");
    }

    private static VerificationResult verifySchemas(
            JsonNode beforeProjection,
            JsonNode afterProjection,
            JsonNode preflight) {
        VerificationResult before = verifySchema(beforeProjection,
                CapabilityStudioSchemaSupport.BRANCH_PROJECTION_RESOURCE,
                "RG.CAPABILITY_STUDIO.BRANCH_BEFORE_SCHEMA_INVALID",
                "BEFORE_PROJECTION_SCHEMA");
        if (!before.verified()) {
            return before;
        }
        VerificationResult after = verifySchema(afterProjection,
                CapabilityStudioSchemaSupport.BRANCH_PROJECTION_RESOURCE,
                "RG.CAPABILITY_STUDIO.BRANCH_AFTER_SCHEMA_INVALID",
                "AFTER_PROJECTION_SCHEMA");
        if (!after.verified()) {
            return after;
        }
        return verifySchema(preflight, CapabilityStudioSchemaSupport.PREFLIGHT_RESOURCE,
                "RG.CAPABILITY_STUDIO.PREFLIGHT_SCHEMA_INVALID", "PREFLIGHT_SCHEMA");
    }

    private static VerificationResult verifyProjectionPair(
            JsonNode beforeProjection,
            JsonNode afterProjection) {
        if (!sameText(beforeProjection, afterProjection, "branchId")) {
            return semanticFailure("RG.CAPABILITY_STUDIO.BRANCH_ID_MISMATCH");
        }
        if (!sameText(beforeProjection, afterProjection, "canonicalBaselineFingerprint")) {
            return semanticFailure(
                    "RG.CAPABILITY_STUDIO.CANONICAL_BASELINE_FINGERPRINT_DRIFT");
        }
        if (!contentFingerprintMatches(beforeProjection)) {
            return semanticFailure(
                    "RG.CAPABILITY_STUDIO.BRANCH_BEFORE_CONTENT_FINGERPRINT_MISMATCH");
        }
        if (!contentFingerprintMatches(afterProjection)) {
            return semanticFailure(
                    "RG.CAPABILITY_STUDIO.BRANCH_AFTER_CONTENT_FINGERPRINT_MISMATCH");
        }

        long beforeRevision = beforeProjection.path("revision").longValue();
        long afterRevision = afterProjection.path("revision").longValue();
        if (afterRevision < beforeRevision) {
            return semanticFailure("RG.CAPABILITY_STUDIO.BRANCH_REVISION_REGRESSED");
        }

        boolean contentChanged = !sameProjectionContent(beforeProjection, afterProjection);
        boolean fingerprintChanged = !sameText(beforeProjection, afterProjection, "fingerprint");
        if (contentChanged && (beforeRevision == Long.MAX_VALUE
                || afterRevision != beforeRevision + 1)) {
            return semanticFailure(
                    "RG.CAPABILITY_STUDIO.BRANCH_CONTENT_CHANGE_REQUIRES_NEXT_REVISION");
        }
        if (contentChanged && !fingerprintChanged) {
            return semanticFailure(
                    "RG.CAPABILITY_STUDIO.BRANCH_CONTENT_CHANGED_WITHOUT_FINGERPRINT_CHANGE");
        }
        if (!contentChanged && fingerprintChanged) {
            return semanticFailure(
                    "RG.CAPABILITY_STUDIO.BRANCH_FINGERPRINT_CHANGED_WITHOUT_CONTENT_CHANGE");
        }
        if (!contentChanged && afterRevision != beforeRevision) {
            return semanticFailure(
                    "RG.CAPABILITY_STUDIO.BRANCH_REVISION_CHANGED_WITHOUT_CONTENT_CHANGE");
        }
        return valid("REVISION_MONOTONICITY", "CONTENT_FINGERPRINT",
                "CANONICAL_BASELINE_BINDING");
    }

    private static VerificationResult verifyPreflightSemantics(
            JsonNode afterProjection,
            JsonNode preflight) {
        if (!sameText(afterProjection, preflight, "branchId")) {
            return semanticFailure("RG.CAPABILITY_STUDIO.PREFLIGHT_BRANCH_MISMATCH");
        }
        if (afterProjection.path("revision").longValue()
                != preflight.path("revision").longValue()) {
            return semanticFailure("RG.CAPABILITY_STUDIO.PREFLIGHT_REVISION_MISMATCH");
        }
        if (!sameText(afterProjection, preflight, "fingerprint")) {
            return semanticFailure("RG.CAPABILITY_STUDIO.PREFLIGHT_FINGERPRINT_MISMATCH");
        }
        if (preflight.path("unresolvedDependencies").longValue() != 0) {
            return semanticFailure(
                    "RG.CAPABILITY_STUDIO.PREFLIGHT_UNRESOLVED_DEPENDENCIES");
        }
        if (preflight.path("realExternalCallCount").longValue() != 0) {
            return semanticFailure("RG.CAPABILITY_STUDIO.PREFLIGHT_REAL_EXTERNAL_CALLS");
        }
        if (preflight.path("fallbackToReal").booleanValue()) {
            return semanticFailure("RG.CAPABILITY_STUDIO.PREFLIGHT_FALLBACK_ENABLED");
        }
        if (!"ISOLATED".equals(preflight.path("mode").textValue())) {
            return semanticFailure("RG.CAPABILITY_STUDIO.PREFLIGHT_MODE_NOT_ISOLATED");
        }
        return valid("PREFLIGHT_AFTER_BINDING", "ISOLATED_PREFLIGHT");
    }

    private static boolean sameProjectionContent(JsonNode before, JsonNode after) {
        return sameText(before, after, "branchId")
                && before.path("behavior").equals(after.path("behavior"));
    }

    private static boolean contentFingerprintMatches(JsonNode projection) {
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
            String actual = "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(JSON.writeValueAsBytes(canonical)));
            return actual.equals(projection.path("fingerprint").textValue());
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            return false;
        }
    }

    private static boolean sameText(JsonNode left, JsonNode right, String field) {
        return left.path(field).isTextual()
                && left.path(field).textValue().equals(right.path(field).textValue());
    }

    private static VerificationResult verifySchema(
            JsonNode value,
            String resource,
            String errorCode,
            String check) {
        try {
            if (value == null || !CapabilityStudioSchemaSupport.validate(value, resource).isEmpty()) {
                return schemaFailure(errorCode);
            }
        } catch (RuntimeException invalid) {
            return schemaFailure(errorCode);
        }
        return valid(check);
    }

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(FailureKind.NONE,
                new LinkedHashSet<>(List.of(checks)), null);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of(), errorCode);
    }

    private static VerificationResult semanticFailure(String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(), errorCode);
    }
}
