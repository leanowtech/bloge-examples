package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline verifier for the Capability Studio Feature Rehearsal Baseline v1 projection.
 *
 * <p>The verifier is deliberately independent of Resource Gateway server classes. It validates
 * the strict packaged wire schema and the baseline's cross-field invariants. The result contains
 * only stable checks and machine-readable error codes. {@code DEVELOPMENT_TEST_OWNED} evidence
 * is never promoted to production acceptance, authorization, or governance evidence here.</p>
 */
public final class CapabilityStudioFeatureRehearsalBaselineVerifier {
    /** Maximum UTF-8 wire document accepted before parsing. */
    public static final int MAXIMUM_BASELINE_BYTES = 16 * 1024 * 1024;
    /** Canonical case order frozen by Baseline v1. */
    public static final List<String> CANONICAL_CASE_IDS = List.of(
            "case-standard-cancellation-fee",
            "case-rider-not-responsible",
            "case-driver-responsible",
            "case-city-policy-missing",
            "case-compensation-history-empty",
            "case-compensation-history-timeout",
            "case-duplicate-cancellation",
            "case-forbidden-write-effect",
            "case-policy-revision-regression");
    private static final String TIMEOUT_CASE = "case-compensation-history-timeout";
    private static final Map<String, String> CANONICAL_OPERATORS;
    private static final Map<String, String> CANONICAL_SIDE_EFFECTS;
    private static final ObjectMapperHolder JSON = new ObjectMapperHolder();

    static {
        Map<String, String> operators = new LinkedHashMap<>();
        operators.put("orderLookup", "httpResource");
        operators.put("responsibilityLookup", "httpResource");
        operators.put("cityPolicyLookup", "httpResource");
        operators.put("compensationHistoryLookup", "httpResource");
        operators.put("aggregateCancellationContext", "capabilityStudio.aggregate");
        operators.put("cancellationDecision", "capabilityStudio.decision");
        CANONICAL_OPERATORS = Collections.unmodifiableMap(operators);
        Map<String, String> sideEffects = new LinkedHashMap<>();
        sideEffects.put("orderLookup", "EXTERNAL_CALL");
        sideEffects.put("responsibilityLookup", "EXTERNAL_CALL");
        sideEffects.put("cityPolicyLookup", "EXTERNAL_CALL");
        sideEffects.put("compensationHistoryLookup", "EXTERNAL_CALL");
        sideEffects.put("aggregateCancellationContext", "READ_ONLY");
        sideEffects.put("cancellationDecision", "READ_ONLY");
        CANONICAL_SIDE_EFFECTS = Collections.unmodifiableMap(sideEffects);
    }

    /** Stable classification of a verification result. */
    public enum FailureKind {
        /** Every schema and semantic check passed. */
        NONE,
        /** The wire document violates the strict JSON Schema or size limit. */
        SCHEMA,
        /** The wire document is schema-valid but violates a cross-field invariant. */
        SEMANTIC
    }

    /**
     * Payload-free result intended for CI logs and development evidence ledgers.
     *
     * @param failureKind stable failure classification
     * @param checks immutable names of checks completed before the result was returned
     * @param errorCode stable protocol error code, or {@code null} when verification succeeds
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode) {
        /** Creates an immutable result with a protocol-shaped error code. */
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
         * Returns true only when the complete Baseline contract was verified.
         *
         * @return whether every required schema and semantic check passed
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioFeatureRehearsalBaselineVerifier() {
    }

    /**
     * Verifies one decoded Baseline v1 projection without exposing business payloads.
     *
     * @param projection decoded Baseline v1 wire projection
     * @return payload-free verification result with stable checks and error code
     */
    public VerificationResult verify(JsonNode projection) {
        VerificationResult schema = verifySchema(projection);
        if (!schema.verified()) {
            return schema;
        }
        VerificationResult identity = verifyIdentityAndCounts(projection);
        if (!identity.verified()) {
            return identity;
        }
        VerificationResult cases = verifyCases(projection);
        if (!cases.verified()) {
            return cases;
        }
        VerificationResult operators = verifyOperators(projection);
        if (!operators.verified()) {
            return operators;
        }
        VerificationResult diagnostics = verifyDiagnosticsAndAggregate(projection);
        if (!diagnostics.verified()) {
            return diagnostics;
        }
        return valid(
                "SCHEMA",
                "EVIDENCE_OWNERSHIP",
                "BASELINE_CARDINALITY",
                "CASE_ORDER_AND_ROUNDS",
                "UNIQUE_RUN_IDS",
                "SEMANTIC_FINGERPRINT_STABILITY",
                "CASE_STATUS_MATRIX",
                "ORACLE_GATE",
                "ZERO_REAL_EXTERNAL_CALLS",
                "OPERATOR_SIDE_EFFECT_SET",
                "DIAGNOSTICS_EMPTY",
                "AGGREGATE_STATUS");
    }

    /**
     * Verifies one UTF-8 JSON wire document with a size limit applied before parsing.
     *
     * @param wireBytes UTF-8 Baseline v1 wire document
     * @return payload-free verification result with stable checks and error code
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_BASELINE_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SIZE_LIMIT");
        }
        try {
            return verify(JSON.mapper.readTree(wireBytes));
        } catch (java.io.IOException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_INVALID_JSON");
        }
    }

    private static VerificationResult verifySchema(JsonNode projection) {
        try {
            if (projection == null || !CapabilityStudioSchemaSupport.validate(
                    projection, CapabilityStudioSchemaSupport.FEATURE_REHEARSAL_BASELINE_RESOURCE)
                    .isEmpty()) {
                return schemaFailure("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure("RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SCHEMA_UNAVAILABLE");
        }
        return valid("SCHEMA");
    }

    private static VerificationResult verifyIdentityAndCounts(JsonNode projection) {
        if (!"DEVELOPMENT_TEST_OWNED".equals(projection.path("evidenceKind").textValue())) {
            return semanticFailure(
                    "EVIDENCE_OWNERSHIP",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_EVIDENCE_KIND_INVALID");
        }
        if (projection.path("caseCount").intValue() != CANONICAL_CASE_IDS.size()
                || projection.path("roundCount").intValue() != 3
                || projection.path("runCount").intValue() != 27
                || projection.path("cases").size() != 9
                || projection.path("realExternalCallCount").intValue() != 0) {
            return semanticFailure(
                    "BASELINE_CARDINALITY",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_CARDINALITY_MISMATCH");
        }
        return valid("EVIDENCE_OWNERSHIP", "BASELINE_CARDINALITY", "ZERO_REAL_EXTERNAL_CALLS");
    }

    private static VerificationResult verifyCases(JsonNode projection) {
        Set<String> runIds = new LinkedHashSet<>();
        JsonNode cases = projection.path("cases");
        for (int caseIndex = 0; caseIndex < CANONICAL_CASE_IDS.size(); caseIndex++) {
            JsonNode caseNode = cases.get(caseIndex);
            String expectedCaseId = CANONICAL_CASE_IDS.get(caseIndex);
            if (!expectedCaseId.equals(caseNode.path("caseId").textValue())) {
                return semanticFailure(
                        "CASE_ORDER_AND_ROUNDS",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_CASE_ORDER_INVALID");
            }
            JsonNode rounds = caseNode.path("rounds");
            String semanticFingerprint = null;
            String expectedStatus = TIMEOUT_CASE.equals(expectedCaseId)
                    && "TIMED_OUT".equals(rounds.get(0).path("status").textValue())
                    ? "TIMED_OUT"
                    : "PASSED";
            for (int roundIndex = 0; roundIndex < 3; roundIndex++) {
                JsonNode round = rounds.get(roundIndex);
                if (round.path("round").intValue() != roundIndex + 1) {
                    return semanticFailure(
                            "CASE_ORDER_AND_ROUNDS",
                            "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_ROUND_ORDER_INVALID");
                }
                if (!expectedStatus.equals(round.path("status").textValue())) {
                    return semanticFailure(
                            "CASE_STATUS_MATRIX",
                            "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_CASE_STATUS_INVALID");
                }
                if (round.path("realExternalCallCount").intValue() != 0) {
                    return semanticFailure(
                            "ZERO_REAL_EXTERNAL_CALLS",
                            "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_REAL_EXTERNAL_CALLS");
                }
                if (!runIds.add(round.path("runId").textValue())) {
                    return semanticFailure(
                            "UNIQUE_RUN_IDS",
                            "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_DUPLICATE_RUN_ID");
                }
                String currentFingerprint = round.path("semanticFingerprint").textValue();
                if (semanticFingerprint == null) {
                    semanticFingerprint = currentFingerprint;
                } else if (!semanticFingerprint.equals(currentFingerprint)) {
                    return semanticFailure(
                            "SEMANTIC_FINGERPRINT_STABILITY",
                            "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SEMANTIC_FINGERPRINT_DRIFT");
                }
            }
            if (!"PASS".equals(caseNode.path("oracle").path("status").textValue())) {
                return semanticFailure(
                        "ORACLE_GATE",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_ORACLE_NOT_PASS");
            }
            String expectedAssertionId = "oracle-" + expectedCaseId.substring("case-".length());
            if (!expectedAssertionId.equals(caseNode.path("oracle").path("assertionId").textValue())) {
                return semanticFailure(
                        "ORACLE_GATE",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_ORACLE_ASSERTION_ID_INVALID");
            }
        }
        if (runIds.size() != 27) {
            return semanticFailure(
                    "UNIQUE_RUN_IDS",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_RUN_ID_CARDINALITY_INVALID");
        }
        return valid(
                "CASE_ORDER_AND_ROUNDS",
                "UNIQUE_RUN_IDS",
                "SEMANTIC_FINGERPRINT_STABILITY",
                "CASE_STATUS_MATRIX",
                "ORACLE_GATE",
                "ZERO_REAL_EXTERNAL_CALLS");
    }

    private static VerificationResult verifyOperators(JsonNode projection) {
        JsonNode operators = projection.path("operators");
        if (operators.size() != CANONICAL_OPERATORS.size()) {
            return semanticFailure(
                    "OPERATOR_SIDE_EFFECT_SET",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_OPERATOR_CARDINALITY_INVALID");
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        for (JsonNode operator : operators) {
            String nodeId = operator.path("nodeId").textValue();
            String operatorRef = operator.path("operatorRef").textValue();
            if (!nodeIds.add(nodeId) || !operatorRef.equals(CANONICAL_OPERATORS.get(nodeId))) {
                return semanticFailure(
                        "OPERATOR_SIDE_EFFECT_SET",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_OPERATOR_SET_INVALID");
            }
            String sideEffectType = operator.path("sideEffectType").textValue();
            if ("WRITE".equals(sideEffectType) || "MIXED".equals(sideEffectType)) {
                return semanticFailure(
                        "OPERATOR_SIDE_EFFECT_SET",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_WRITE_OPERATOR_FORBIDDEN");
            }
            if (!sideEffectType.equals(CANONICAL_SIDE_EFFECTS.get(nodeId))) {
                return semanticFailure(
                        "OPERATOR_SIDE_EFFECT_SET",
                        "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_OPERATOR_SIDE_EFFECT_INVALID");
            }
        }
        if (!nodeIds.equals(CANONICAL_OPERATORS.keySet())) {
            return semanticFailure(
                    "OPERATOR_SIDE_EFFECT_SET",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_OPERATOR_SET_INVALID");
        }
        return valid("OPERATOR_SIDE_EFFECT_SET");
    }

    private static VerificationResult verifyDiagnosticsAndAggregate(JsonNode projection) {
        if (projection.path("diagnostics").size() != 0) {
            return semanticFailure(
                    "DIAGNOSTICS_EMPTY",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_DIAGNOSTICS_NOT_EMPTY");
        }
        if (!"PASSED".equals(projection.path("status").textValue())) {
            return semanticFailure(
                    "AGGREGATE_STATUS",
                    "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_AGGREGATE_NOT_PASSED");
        }
        return valid("DIAGNOSTICS_EMPTY", "AGGREGATE_STATUS");
    }

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(FailureKind.NONE, new LinkedHashSet<>(List.of(checks)), null);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of("SCHEMA"), errorCode);
    }

    private static VerificationResult semanticFailure(String check, String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(check), errorCode);
    }

    private static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
