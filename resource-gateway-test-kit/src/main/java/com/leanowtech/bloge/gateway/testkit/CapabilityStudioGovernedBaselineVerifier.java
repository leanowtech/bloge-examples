package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline verifier for the Capability Studio governed baseline v1 projection.
 *
 * <p>The verifier validates the packaged strict schema and the cross-field evidence contract for
 * the payload-free 9 Case x 3 round receipt. A {@code FAILED_CLOSED} receipt is accepted only when
 * it contains no fabricated execution evidence.</p>
 */
public final class CapabilityStudioGovernedBaselineVerifier {
    /** Maximum UTF-8 wire document accepted before parsing. */
    public static final int MAXIMUM_BASELINE_BYTES = 16 * 1024 * 1024;
    /** Canonical governed baseline case identifiers. */
    public static final List<String> CANONICAL_CASE_IDS = List.of(
            "case-city-policy-missing",
            "case-compensation-history-empty",
            "case-compensation-history-timeout",
            "case-driver-responsible",
            "case-duplicate-cancellation",
            "case-forbidden-write-effect",
            "case-policy-revision-regression",
            "case-rider-not-responsible",
            "case-standard-cancellation-fee");
    /** Limitations that must remain visible on both truthful receipt states. */
    public static final List<String> LIMITATIONS = List.of(
            "BUSINESS_RESULT_FINGERPRINT_NOT_EXPORTED",
            "DEPLOYMENT_EGRESS_NOT_OBSERVED",
            "OWNER_SIGNOFF_NOT_PRESENT");

    private static final ObjectMapperHolder JSON = new ObjectMapperHolder();

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
     * Payload-free result intended for CI logs and governance ledgers.
     *
     * @param failureKind stable classification of the verification result
     * @param checks immutable names of checks completed before returning
     * @param errorCode stable protocol error code, or {@code null} on success
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
         * Returns true only when the complete governed baseline contract passed.
         *
         * @return whether the result is fully verified
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioGovernedBaselineVerifier() {
    }

    /**
     * Verifies one decoded governed baseline projection.
     *
     * @param projection decoded governed baseline projection
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode projection) {
        VerificationResult schema = verifySchema(projection);
        if (!schema.verified()) {
            return schema;
        }
        VerificationResult identity = verifyIdentity(projection);
        if (!identity.verified()) {
            return identity;
        }
        if ("FAILED_CLOSED".equals(projection.path("status").textValue())) {
            return verifyFailedClosed(projection);
        }
        return verifyPassed(projection);
    }

    /**
     * Verifies one UTF-8 governed baseline wire document with a pre-parse size limit.
     *
     * @param wireBytes UTF-8 governed baseline wire document
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_BASELINE_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SIZE_LIMIT");
        }
        try {
            return verify(JSON.mapper.readTree(wireBytes));
        } catch (java.io.IOException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_INVALID_JSON");
        }
    }

    private static VerificationResult verifySchema(JsonNode projection) {
        try {
            if (projection == null || !CapabilityStudioSchemaSupport.validate(
                    projection, CapabilityStudioSchemaSupport.GOVERNED_BASELINE_RESOURCE)
                    .isEmpty()) {
                return schemaFailure("RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure("RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SCHEMA_UNAVAILABLE");
        }
        return valid("SCHEMA");
    }

    private static VerificationResult verifyIdentity(JsonNode projection) {
        if (projection.path("caseCount").intValue() != CANONICAL_CASE_IDS.size()
                || projection.path("roundCount").intValue() != 3
                || !"DEVELOPMENT_TEST_OWNED".equals(projection.path("evidenceKind").textValue())) {
            return semanticFailure(
                    "BASELINE_IDENTITY",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_IDENTITY_INVALID");
        }
        return valid("BASELINE_IDENTITY");
    }

    private static VerificationResult verifyPassed(JsonNode projection) {
        if (projection.path("suiteRunCount").intValue() != 3
                || projection.path("childRunCount").intValue() != 27
                || projection.path("realExternalCallCount").intValue() != 0) {
            return semanticFailure(
                    "PASSED_COUNTS_AND_CALLS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_PASSED_COUNTS_INVALID");
        }
        VerificationResult rounds = verifyRounds(projection.path("rounds"));
        if (!rounds.verified()) {
            return rounds;
        }
        VerificationResult cases = verifyCases(projection.path("cases"));
        if (!cases.verified()) {
            return cases;
        }
        VerificationResult limitations = verifyLimitations(projection);
        if (!limitations.verified()) {
            return limitations;
        }
        if (projection.path("diagnostics").size() != 0) {
            return semanticFailure(
                    "DIAGNOSTICS_EMPTY",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_PASSED_DIAGNOSTICS_NOT_EMPTY");
        }
        return valid(
                "SCHEMA",
                "BASELINE_IDENTITY",
                "PASSED_COUNTS_AND_CALLS",
                "UNIQUE_SUITE_RUN_IDS",
                "UNIQUE_CASE_IDS",
                "UNIQUE_CHILD_RUN_IDS",
                "CASE_ROUND_COVERAGE",
                "PASSED_STATUS_MATRIX",
                "LIMITATIONS",
                "DIAGNOSTICS_EMPTY");
    }

    private static VerificationResult verifyRounds(JsonNode rounds) {
        Set<String> suiteRunIds = new LinkedHashSet<>();
        for (int index = 0; index < 3; index++) {
            JsonNode round = rounds.get(index);
            if (round.path("round").intValue() != index + 1
                    || !"PASSED".equals(round.path("status").textValue())
                    || round.path("childRunCount").intValue() != 9
                    || !suiteRunIds.add(round.path("suiteRunId").textValue())) {
                return semanticFailure(
                        "UNIQUE_SUITE_RUN_IDS",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SUITE_RUN_INVALID");
            }
        }
        if (suiteRunIds.size() != 3) {
            return semanticFailure(
                    "UNIQUE_SUITE_RUN_IDS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SUITE_RUN_CARDINALITY_INVALID");
        }
        return valid("UNIQUE_SUITE_RUN_IDS");
    }

    private static VerificationResult verifyCases(JsonNode cases) {
        Set<String> caseIds = new LinkedHashSet<>();
        Set<String> childRunIds = new LinkedHashSet<>();
        for (int caseIndex = 0; caseIndex < CANONICAL_CASE_IDS.size(); caseIndex++) {
            JsonNode caseNode = cases.get(caseIndex);
            String caseId = caseNode.path("caseId").textValue();
            if (!CANONICAL_CASE_IDS.get(caseIndex).equals(caseId) || !caseIds.add(caseId)) {
                return semanticFailure(
                        "UNIQUE_CASE_IDS",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ID_INVALID");
            }
            JsonNode caseRounds = caseNode.path("rounds");
            Set<Integer> roundNumbers = new LinkedHashSet<>();
            for (int roundIndex = 0; roundIndex < 3; roundIndex++) {
                JsonNode caseRound = caseRounds.get(roundIndex);
                if (!roundNumbers.add(caseRound.path("round").intValue())
                        || caseRound.path("round").intValue() != roundIndex + 1
                        || !"PASSED".equals(caseRound.path("status").textValue())
                        || !childRunIds.add(caseRound.path("runId").textValue())) {
                    return semanticFailure(
                            "CASE_ROUND_COVERAGE",
                            "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ROUND_INVALID");
                }
            }
            if (!roundNumbers.equals(Set.of(1, 2, 3))) {
                return semanticFailure(
                        "CASE_ROUND_COVERAGE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ROUND_SET_INVALID");
            }
        }
        if (caseIds.size() != 9) {
            return semanticFailure(
                    "UNIQUE_CASE_IDS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ID_CARDINALITY_INVALID");
        }
        if (childRunIds.size() != 27) {
            return semanticFailure(
                    "UNIQUE_CHILD_RUN_IDS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CHILD_RUN_ID_CARDINALITY_INVALID");
        }
        return valid("UNIQUE_CASE_IDS", "UNIQUE_CHILD_RUN_IDS", "CASE_ROUND_COVERAGE",
                "PASSED_STATUS_MATRIX");
    }

    private static VerificationResult verifyFailedClosed(JsonNode projection) {
        if (projection.path("suiteRunCount").intValue() != 0
                || projection.path("childRunCount").intValue() != 0
                || !projection.path("realExternalCallCount").isNull()
                || !projection.path("compilationFingerprint").isNull()
                || !projection.path("sourceMapFingerprint").isNull()
                || !projection.path("provenanceFingerprint").isNull()
                || !projection.path("publication").isNull()
                || projection.path("rounds").size() != 0
                || projection.path("cases").size() != 0
                || projection.path("diagnostics").size() == 0) {
            return semanticFailure(
                    "FAILED_CLOSED_NO_EVIDENCE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_FAILED_CLOSED_HAS_EVIDENCE");
        }
        VerificationResult limitations = verifyLimitations(projection);
        if (!limitations.verified()) {
            return limitations;
        }
        return valid("SCHEMA", "BASELINE_IDENTITY", "FAILED_CLOSED_NO_EVIDENCE", "LIMITATIONS");
    }

    private static VerificationResult verifyLimitations(JsonNode projection) {
        JsonNode limitations = projection.path("limitations");
        if (limitations.size() != LIMITATIONS.size()) {
            return semanticFailure(
                    "LIMITATIONS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_LIMITATIONS_INVALID");
        }
        for (int index = 0; index < LIMITATIONS.size(); index++) {
            if (!LIMITATIONS.get(index).equals(limitations.get(index).textValue())) {
                return semanticFailure(
                        "LIMITATIONS",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_LIMITATIONS_INVALID");
            }
        }
        return valid("LIMITATIONS");
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
