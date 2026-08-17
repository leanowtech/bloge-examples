package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline verifier for the Capability Studio Feature Rehearsal v1 wire projection.
 *
 * <p>This verifier is intentionally independent from Resource Gateway server classes. It validates
 * the packaged strict JSON Schema and then checks the cross-field invariants that are essential to
 * treating a rehearsal as a trustworthy visual trace: the canonical demo cardinality, run/Data
 * Lens identity, invocation-site edge closure, payload permission boundary, zero real calls, and
 * independently recomputed Data Lens and payload fingerprints. It does not make production
 * signature, identity, or authorization claims.</p>
 */
public final class CapabilityStudioFeatureRehearsalVerifier {
    /** Maximum UTF-8 wire document accepted before parsing. */
    public static final int MAXIMUM_REHEARSAL_BYTES = 16 * 1024 * 1024;
    /** Canonical Feature Rehearsal graph node count. */
    public static final int EXPECTED_NODE_COUNT = 6;
    /** Canonical Feature Rehearsal graph edge count. */
    public static final int EXPECTED_EDGE_COUNT = 5;

    private static final int MAX_FINGERPRINT_MATERIAL_BYTES = 16 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Stable classification of a verification result. */
    public enum FailureKind {
        /** The projection passed schema and semantic verification. */
        NONE,
        /** The wire document violates the strict schema or size limit. */
        SCHEMA,
        /** The document is schema-valid but violates a cross-field invariant. */
        SEMANTIC
    }

    /**
     * Payload-free result suitable for CI logs and governance records.
     *
     * @param failureKind schema or semantic failure classification
     * @param checks completed verification groups
     * @param errorCode stable machine-readable failure code, or {@code null}
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode) {
        /** Creates an immutable, protocol-shaped result. */
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
         * Indicates whether every required check passed.
         *
         * @return true only when every required check passed
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioFeatureRehearsalVerifier() {
    }

    /**
     * Verifies one decoded Feature Rehearsal projection.
     *
     * @param projection decoded JSON projection
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode projection) {
        VerificationResult schema = verifySchema(projection);
        if (!schema.verified()) {
            return schema;
        }
        VerificationResult identity = verifyRunIdentity(projection);
        if (!identity.verified()) {
            return identity;
        }
        VerificationResult cardinality = verifyCanonicalCardinality(projection);
        if (!cardinality.verified()) {
            return cardinality;
        }
        VerificationResult closure = verifyInvocationSiteClosure(projection);
        if (!closure.verified()) {
            return closure;
        }
        VerificationResult permission = verifyPermissionBoundary(projection);
        if (!permission.verified()) {
            return permission;
        }
        VerificationResult payloadFingerprints = verifyPayloadFingerprints(projection);
        if (!payloadFingerprints.verified()) {
            return payloadFingerprints;
        }
        VerificationResult lensFingerprint = verifyDataLensFingerprint(projection);
        if (!lensFingerprint.verified()) {
            return lensFingerprint;
        }
        return valid(
                "SCHEMA",
                "RUN_DATA_LENS_IDENTITY",
                "CANONICAL_CARDINALITY",
                "INVOCATION_SITE_EDGE_CLOSURE",
                "PERMISSION_BOUNDARY",
                "ZERO_REAL_EXTERNAL_CALLS",
                "PAYLOAD_FINGERPRINTS",
                "DATA_LENS_FINGERPRINT");
    }

    /**
     * Verifies one UTF-8 JSON wire document and applies the raw size bound before parsing.
     *
     * @param wireBytes UTF-8 JSON bytes
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_REHEARSAL_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(wireBytes));
        } catch (java.io.IOException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_INVALID_JSON");
        }
    }

    private static VerificationResult verifySchema(JsonNode projection) {
        try {
            if (projection == null || !CapabilityStudioSchemaSupport.validate(
                    projection, CapabilityStudioSchemaSupport.FEATURE_REHEARSAL_RESOURCE).isEmpty()) {
                return schemaFailure("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_SCHEMA_UNAVAILABLE");
        }
        return valid("SCHEMA");
    }

    private static VerificationResult verifyRunIdentity(JsonNode projection) {
        JsonNode run = projection.path("run");
        JsonNode lens = projection.path("dataLens");
        if (!sameText(run, lens, "runId")) {
            return semanticFailure(
                    "RUN_DATA_LENS_IDENTITY",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_RUN_ID_MISMATCH");
        }
        if (!sameText(run, lens, "status", "runStatus")) {
            return semanticFailure(
                    "RUN_DATA_LENS_IDENTITY",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_RUN_STATUS_MISMATCH");
        }
        if (run.path("realExternalCallCount").intValue() != 0) {
            return semanticFailure(
                    "ZERO_REAL_EXTERNAL_CALLS",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_REAL_EXTERNAL_CALLS");
        }
        return valid("RUN_DATA_LENS_IDENTITY", "ZERO_REAL_EXTERNAL_CALLS");
    }

    private static VerificationResult verifyCanonicalCardinality(JsonNode projection) {
        JsonNode lens = projection.path("dataLens");
        if (lens.path("nodes").size() != EXPECTED_NODE_COUNT
                || lens.path("edges").size() != EXPECTED_EDGE_COUNT) {
            return semanticFailure(
                    "CANONICAL_CARDINALITY",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_CANONICAL_CARDINALITY_MISMATCH");
        }
        JsonNode truncation = lens.path("truncation");
        if (truncation.path("nodesTruncated").booleanValue()
                || truncation.path("omittedNodes").intValue() != 0
                || truncation.path("edgesTruncated").booleanValue()
                || truncation.path("omittedEdges").intValue() != 0) {
            return semanticFailure(
                    "CANONICAL_CARDINALITY",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_CANONICAL_TRACE_TRUNCATED");
        }
        if (truncation.path("nodesTruncated").booleanValue()
                != truncation.path("omittedNodes").intValue() > 0
                || truncation.path("edgesTruncated").booleanValue()
                != truncation.path("omittedEdges").intValue() > 0
                || truncation.path("attemptsTruncated").booleanValue()
                != truncation.path("omittedAttempts").intValue() > 0) {
            return semanticFailure(
                    "CANONICAL_CARDINALITY",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_TRUNCATION_MISMATCH");
        }
        return valid("CANONICAL_CARDINALITY");
    }

    private static VerificationResult verifyInvocationSiteClosure(JsonNode projection) {
        JsonNode lens = projection.path("dataLens");
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> invocationSites = new LinkedHashSet<>();
        for (JsonNode node : lens.path("nodes")) {
            if (!nodeIds.add(node.path("nodeId").textValue())
                    || !invocationSites.add(node.path("invocationSite").textValue())) {
                return semanticFailure(
                        "INVOCATION_SITE_EDGE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_DUPLICATE_NODE_ID");
            }
        }
        Set<String> edgeIds = new LinkedHashSet<>();
        for (JsonNode edge : lens.path("edges")) {
            if (!edgeIds.add(edge.path("edgeId").textValue())) {
                return semanticFailure(
                        "INVOCATION_SITE_EDGE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_DUPLICATE_EDGE_ID");
            }
            if (!invocationSites.contains(edge.path("fromInvocationSite").textValue())
                    || !invocationSites.contains(edge.path("toInvocationSite").textValue())) {
                return semanticFailure(
                        "INVOCATION_SITE_EDGE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_DANGLING_EDGE");
            }
        }
        return valid("INVOCATION_SITE_EDGE_CLOSURE");
    }

    private static VerificationResult verifyPermissionBoundary(JsonNode projection) {
        JsonNode lens = projection.path("dataLens");
        if (!"STRUCTURE_ONLY".equals(lens.path("permissionMode").textValue())) {
            return valid("PERMISSION_BOUNDARY");
        }
        for (JsonNode node : lens.path("nodes")) {
            if (visible(node, "input") || visible(node, "output")) {
                return semanticFailure(
                        "PERMISSION_BOUNDARY",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_PAYLOAD_LEAK");
            }
            for (JsonNode attempt : node.path("attempts")) {
                if (visible(attempt, "input") || visible(attempt, "output")) {
                    return semanticFailure(
                            "PERMISSION_BOUNDARY",
                            "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_PAYLOAD_LEAK");
                }
            }
        }
        for (JsonNode edge : lens.path("edges")) {
            if (visible(edge, "value")) {
                return semanticFailure(
                        "PERMISSION_BOUNDARY",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_PAYLOAD_LEAK");
            }
        }
        JsonNode difference = lens.path("firstDifference");
        if (!difference.isNull()
                && (visible(difference, "expected") || visible(difference, "actual"))) {
            return semanticFailure(
                    "PERMISSION_BOUNDARY",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_PAYLOAD_LEAK");
        }
        return valid("PERMISSION_BOUNDARY");
    }

    private static VerificationResult verifyPayloadFingerprints(JsonNode projection) {
        JsonNode lens = projection.path("dataLens");
        boolean payloadVisible = "PAYLOAD_VISIBLE".equals(
                lens.path("permissionMode").textValue());
        for (JsonNode node : lens.path("nodes")) {
            VerificationResult input = verifyPayloadFingerprint(
                    node, "input", "inputFingerprint", payloadVisible);
            if (!input.verified()) {
                return input;
            }
            VerificationResult output = verifyPayloadFingerprint(
                    node, "output", "outputFingerprint", payloadVisible);
            if (!output.verified()) {
                return output;
            }
            for (JsonNode attempt : node.path("attempts")) {
                VerificationResult attemptInput = verifyPayloadFingerprint(
                        attempt, "input", "inputFingerprint", payloadVisible);
                if (!attemptInput.verified()) {
                    return attemptInput;
                }
                VerificationResult attemptOutput = verifyPayloadFingerprint(
                        attempt, "output", "outputFingerprint", payloadVisible);
                if (!attemptOutput.verified()) {
                    return attemptOutput;
                }
            }
        }
        for (JsonNode edge : lens.path("edges")) {
            VerificationResult value = verifyPayloadFingerprint(
                    edge, "value", "valueFingerprint", payloadVisible);
            if (!value.verified()) {
                return value;
            }
        }
        JsonNode difference = lens.path("firstDifference");
        if (!difference.isNull()) {
            VerificationResult expected = verifyPayloadFingerprint(
                    difference, "expected", "expectedFingerprint", payloadVisible);
            if (!expected.verified()) {
                return expected;
            }
            VerificationResult actual = verifyPayloadFingerprint(
                    difference, "actual", "actualFingerprint", payloadVisible);
            if (!actual.verified()) {
                return actual;
            }
        }
        return valid("PAYLOAD_FINGERPRINTS");
    }

    private static VerificationResult verifyPayloadFingerprint(
            JsonNode owner, String valueField, String fingerprintField, boolean payloadVisible) {
        // Structure-only projections deliberately retain source fingerprints while redacting values.
        // Their fingerprints are therefore opaque commitments and cannot be recomputed offline.
        if (!payloadVisible) {
            return valid("PAYLOAD_FINGERPRINTS");
        }
        JsonNode value = owner.path(valueField);
        String expected;
        try {
            expected = value.isNull() ? "" : EvidenceVerificationSupport.sha256Bounded(
                    value, MAX_FINGERPRINT_MATERIAL_BYTES);
        } catch (RuntimeException invalidPayload) {
            return semanticFailure(
                    "PAYLOAD_FINGERPRINTS",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_PAYLOAD_FINGERPRINT_UNAVAILABLE");
        }
        if (!expected.equals(owner.path(fingerprintField).textValue())) {
            return semanticFailure(
                    "PAYLOAD_FINGERPRINTS",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_PAYLOAD_FINGERPRINT_MISMATCH");
        }
        return valid("PAYLOAD_FINGERPRINTS");
    }

    private static VerificationResult verifyDataLensFingerprint(JsonNode projection) {
        JsonNode lens = projection.path("dataLens");
        ObjectNode material = JSON.createObjectNode();
        material.set("runId", lens.get("runId"));
        material.set("runStatus", lens.get("runStatus"));
        material.set("permissionMode", lens.get("permissionMode"));
        material.set("nodes", lens.get("nodes"));
        material.set("edges", lens.get("edges"));
        material.set("firstDifference", lens.get("firstDifference"));
        material.set("truncation", lens.get("truncation"));
        try {
            String expected = EvidenceVerificationSupport.sha256Bounded(
                    material, MAX_FINGERPRINT_MATERIAL_BYTES);
            if (!expected.equals(lens.path("fingerprint").textValue())) {
                return semanticFailure(
                        "DATA_LENS_FINGERPRINT",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_DATA_LENS_FINGERPRINT_MISMATCH");
            }
        } catch (RuntimeException invalidMaterial) {
            return semanticFailure(
                    "DATA_LENS_FINGERPRINT",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_DATA_LENS_FINGERPRINT_UNAVAILABLE");
        }
        return valid("DATA_LENS_FINGERPRINT");
    }

    private static boolean visible(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return !value.isNull();
    }

    private static boolean sameText(JsonNode left, JsonNode right, String leftField) {
        return sameText(left, right, leftField, leftField);
    }

    private static boolean sameText(
            JsonNode left, JsonNode right, String leftField, String rightField) {
        return left.path(leftField).isTextual()
                && left.path(leftField).textValue().equals(right.path(rightField).textValue());
    }

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(
                FailureKind.NONE, new LinkedHashSet<>(List.of(checks)), null);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of("SCHEMA"), errorCode);
    }

    private static VerificationResult semanticFailure(String check, String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(check), errorCode);
    }
}
