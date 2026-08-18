package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline verifier for the Capability Studio governed baseline v3 projection.
 *
 * <p>The verifier validates the packaged strict schema and the cross-field evidence contract for
 * the payload-free 9 case x 3 round receipt. A {@code FAILED_CLOSED} receipt is accepted only when
 * it contains no fabricated execution evidence.</p>
 */
public final class CapabilityStudioGovernedBaselineVerifier {
    /** Maximum UTF-8 wire document accepted before parsing. */
    public static final int MAXIMUM_BASELINE_BYTES = 16 * 1024 * 1024;
    /** Canonical governed baseline case identifiers, in wire order. */
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
    /** Complete limitation vocabulary, retained for compatibility with fixture builders. */
    public static final List<String> LIMITATIONS = List.of(
            "IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND",
            "RUNTIME_ENVIRONMENT_NOT_ATTESTED",
            "CERTIFIABLE_EVIDENCE_NOT_ESTABLISHED",
            "DEPLOYMENT_EGRESS_NOT_OBSERVED",
            "OWNER_SIGNOFF_NOT_PRESENT");
    private static final List<String> COMMON_PROOFS = List.of(
            "BUSINESS_ASSERTION_PASSED",
            "SEMANTIC_RESULT_STABLE",
            "FIXTURE_CONTROL_SATISFIED",
            "ZERO_REAL_EXTERNAL_CALLS");

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
                || projection.path("oraclePassCount").intValue() != 9
                || projection.path("businessCheckCount").intValue() != 27
                || projection.path("businessCheckPassCount").intValue() != 27
                || !projection.path("realExternalCallCount").isInt()
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
        VerificationResult candidate = verifyCandidateBinding(projection, true);
        if (!candidate.verified()) {
            return candidate;
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
                "CASE_ORACLES",
                "CASE_ASSERTIONS",
                "FIXTURE_CONTROLS",
                "SEMANTIC_RESULT_STABILITY",
                "CASE_PROOFS",
                "CANDIDATE_BUILD_BINDING",
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
            VerificationResult semantics = verifyCase(caseNode, caseId, childRunIds);
            if (!semantics.verified()) {
                return semantics;
            }
        }
        if (caseIds.size() != CANONICAL_CASE_IDS.size()) {
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
                "PASSED_STATUS_MATRIX", "CASE_ORACLES", "CASE_ASSERTIONS", "FIXTURE_CONTROLS",
                "SEMANTIC_RESULT_STABILITY", "CASE_PROOFS");
    }

    private static VerificationResult verifyCase(
            JsonNode caseNode, String caseId, Set<String> childRunIds) {
        if (!("oracle-" + caseId.substring("case-".length()))
                .equals(caseNode.path("oracleId").textValue())
                || !"PASS".equals(caseNode.path("oracleStatus").textValue())) {
            return semanticFailure(
                    "CASE_ORACLES",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_ORACLE_INVALID");
        }
        if (caseNode.path("assertionsEvaluated").intValue() != 3
                || caseNode.path("assertionsPassed").intValue() != 3) {
            return semanticFailure(
                    "CASE_ASSERTIONS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ASSERTIONS_INVALID");
        }
        int fixtureControlsEvaluated = caseNode.path("fixtureControlsEvaluated").intValue();
        int fixtureControlsSatisfied = caseNode.path("fixtureControlsSatisfied").intValue();
        if (fixtureControlsEvaluated <= 0 || fixtureControlsEvaluated != fixtureControlsSatisfied) {
            return semanticFailure(
                    "FIXTURE_CONTROLS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_FIXTURE_CONTROLS_INVALID");
        }
        if (!expectedProofs(caseId).equals(toStrings(caseNode.path("proofs")))) {
            return semanticFailure(
                    "CASE_PROOFS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_PROOFS_INVALID");
        }

        String semanticFingerprint = caseNode.path("semanticResultFingerprint").textValue();
        JsonNode rounds = caseNode.path("rounds");
        int assertionTotal = 0;
        int assertionPassTotal = 0;
        int fixtureTotal = 0;
        int fixtureSatisfiedTotal = 0;
        for (int roundIndex = 0; roundIndex < 3; roundIndex++) {
            JsonNode round = rounds.get(roundIndex);
            if (round.path("round").intValue() != roundIndex + 1
                    || !"PASSED".equals(round.path("status").textValue())
                    || !childRunIds.add(round.path("runId").textValue())) {
                return semanticFailure(
                        "CASE_ROUND_COVERAGE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ROUND_INVALID");
            }
            if (round.path("assertionsEvaluated").intValue() != 1
                    || round.path("assertionsPassed").intValue() != 1) {
                return semanticFailure(
                        "CASE_ASSERTIONS",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ROUND_ASSERTIONS_INVALID");
            }
            int evaluated = round.path("fixtureControlsEvaluated").intValue();
            int satisfied = round.path("fixtureControlsSatisfied").intValue();
            if (evaluated <= 0 || evaluated != satisfied) {
                return semanticFailure(
                        "FIXTURE_CONTROLS",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_ROUND_FIXTURE_CONTROLS_INVALID");
            }
            if (!semanticFingerprint.equals(round.path("semanticResultFingerprint").textValue())) {
                return semanticFailure(
                        "SEMANTIC_RESULT_STABILITY",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_SEMANTIC_RESULT_FINGERPRINT_DRIFT");
            }
            assertionTotal += round.path("assertionsEvaluated").intValue();
            assertionPassTotal += round.path("assertionsPassed").intValue();
            fixtureTotal += evaluated;
            fixtureSatisfiedTotal += satisfied;
        }
        if (assertionTotal != 3 || assertionPassTotal != 3
                || fixtureTotal != fixtureControlsEvaluated
                || fixtureSatisfiedTotal != fixtureControlsSatisfied) {
            return semanticFailure(
                    "CASE_ASSERTIONS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CASE_TOTALS_INVALID");
        }
        return valid("CASE_ROUND_COVERAGE", "PASSED_STATUS_MATRIX", "CASE_ORACLES",
                "CASE_ASSERTIONS", "FIXTURE_CONTROLS", "SEMANTIC_RESULT_STABILITY", "CASE_PROOFS");
    }

    private static List<String> expectedProofs(String caseId) {
        if ("case-compensation-history-timeout".equals(caseId)) {
            return appendProof(COMMON_PROOFS, "TIMEOUT_FALLBACK_CONFIRMED");
        }
        if ("case-duplicate-cancellation".equals(caseId)) {
            return appendProof(COMMON_PROOFS, "DUPLICATE_IDEMPOTENCY_CONFIRMED");
        }
        if ("case-forbidden-write-effect".equals(caseId)) {
            return appendProof(COMMON_PROOFS, "FORBIDDEN_WRITE_EFFECT_ABSENT");
        }
        return COMMON_PROOFS;
    }

    private static List<String> appendProof(List<String> common, String highRiskProof) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(common);
        result.add(highRiskProof);
        return List.copyOf(result);
    }

    private static List<String> toStrings(JsonNode values) {
        if (!values.isArray()) {
            return List.of();
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.textValue()));
        return List.copyOf(result);
    }

    private static VerificationResult verifyFailedClosed(JsonNode projection) {
        if (projection.path("suiteRunCount").intValue() != 0
                || projection.path("childRunCount").intValue() != 0
                || projection.path("oraclePassCount").intValue() != 0
                || projection.path("businessCheckCount").intValue() != 0
                || projection.path("businessCheckPassCount").intValue() != 0
                || !projection.path("realExternalCallCount").isNull()
                || !"NOT_VERIFIED".equals(projection.path("verificationLevel").textValue())
                || !projection.path("evidenceClass").isNull()
                || !projection.path("compilationFingerprint").isNull()
                || !projection.path("sourceMapFingerprint").isNull()
                || !projection.path("provenanceFingerprint").isNull()
                || !projection.path("candidateIntentFingerprint").isNull()
                || !projection.path("publication").isNull()
                || projection.path("rounds").size() != 0
                || projection.path("cases").size() != 0
                || projection.path("diagnostics").size() == 0) {
            return semanticFailure(
                    "FAILED_CLOSED_NO_EVIDENCE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_FAILED_CLOSED_HAS_EVIDENCE");
        }
        VerificationResult candidate = verifyCandidateBinding(projection, false);
        if (!candidate.verified()) {
            return candidate;
        }
        VerificationResult limitations = verifyLimitations(projection);
        if (!limitations.verified()) {
            return limitations;
        }
        return valid("SCHEMA", "BASELINE_IDENTITY", "FAILED_CLOSED_NO_EVIDENCE",
                "CANDIDATE_BUILD_BINDING", "LIMITATIONS");
    }

    private static VerificationResult verifyLimitations(JsonNode projection) {
        List<String> expected = new java.util.ArrayList<>();
        if (projection.path("candidateBuild").isNull()) {
            expected.add("IMMUTABLE_RELEASE_CANDIDATE_NOT_BOUND");
        }
        expected.add("RUNTIME_ENVIRONMENT_NOT_ATTESTED");
        if (!"CERTIFIABLE".equals(projection.path("evidenceClass").textValue())) {
            expected.add("CERTIFIABLE_EVIDENCE_NOT_ESTABLISHED");
        }
        expected.add("DEPLOYMENT_EGRESS_NOT_OBSERVED");
        expected.add("OWNER_SIGNOFF_NOT_PRESENT");
        if (!expected.equals(toStrings(projection.path("limitations")))) {
            return semanticFailure(
                    "LIMITATIONS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_LIMITATIONS_INVALID");
        }
        return valid("LIMITATIONS");
    }

    private static VerificationResult verifyCandidateBinding(
            JsonNode projection, boolean executionCompleted) {
        JsonNode candidate = projection.path("candidateBuild");
        JsonNode intentFingerprint = projection.path("candidateIntentFingerprint");
        if (candidate.isNull()) {
            if (!intentFingerprint.isNull()) {
                return semanticFailure(
                        "CANDIDATE_BUILD_BINDING",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_UNBOUND_CANDIDATE_HAS_INTENT");
            }
            return valid("CANDIDATE_BUILD_BINDING");
        }
        if (!executionCompleted) {
            if (!intentFingerprint.isNull()) {
                return semanticFailure(
                        "CANDIDATE_BUILD_BINDING",
                        "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_FAILED_CANDIDATE_HAS_INTENT");
            }
            return valid("CANDIDATE_BUILD_BINDING");
        }
        com.fasterxml.jackson.databind.node.ObjectNode metadata =
                JSON.mapper.createObjectNode();
        metadata.put("schemaVersion", "bloge.capabilityStudioGovernedCandidateIntent.v1");
        metadata.put("compilationFingerprint",
                projection.path("compilationFingerprint").textValue());
        metadata.put("sourceMapFingerprint",
                projection.path("sourceMapFingerprint").textValue());
        metadata.put("publicationReceiptFingerprint",
                projection.path("publication").path("receiptFingerprint").textValue());
        metadata.set("suiteRef", projection.path("publication").path("suiteRef").deepCopy());
        metadata.set("candidateBuild", candidate.deepCopy());
        String expected;
        try {
            expected = BusinessMirrorCanonical.fingerprint(
                    metadata,
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CANDIDATE_INTENT_TOO_LARGE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CANDIDATE_INTENT_INVALID");
        } catch (IllegalArgumentException failure) {
            return semanticFailure("CANDIDATE_BUILD_BINDING", failure.getMessage());
        }
        if (!expected.equals(intentFingerprint.textValue())) {
            return semanticFailure(
                    "CANDIDATE_BUILD_BINDING",
                    "RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_CANDIDATE_INTENT_DRIFT");
        }
        return valid("CANDIDATE_BUILD_BINDING");
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
