package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioFeatureRehearsalBaselineVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioFeatureRehearsalBaselineVerifier VERIFIER =
            new CapabilityStudioFeatureRehearsalBaselineVerifier();

    @Test
    void verifiesTheExactNineCaseThreeRoundDevelopmentBaseline() {
        CapabilityStudioFeatureRehearsalBaselineVerifier.VerificationResult result =
                VERIFIER.verify(validProjection());

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).contains(
                "EVIDENCE_OWNERSHIP",
                "BASELINE_CARDINALITY",
                "CASE_ORDER_AND_ROUNDS",
                "UNIQUE_RUN_IDS",
                "SEMANTIC_FINGERPRINT_STABILITY",
                "CASE_STATUS_MATRIX",
                "ORACLE_GATE",
                "ZERO_REAL_EXTERNAL_CALLS",
                "OPERATOR_SIDE_EFFECT_SET",
                "DIAGNOSTICS_EMPTY");
    }

    @Test
    void rejectsUnknownFieldsAtTheStrictSchemaBoundary() {
        ObjectNode projection = validProjection();
        projection.put("unexpected", true);

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsBaselineOrGraphIdentityDrift() {
        ObjectNode baselineId = validProjection();
        baselineId.put("baselineId", "another-baseline");
        assertFailure(baselineId, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SCHEMA_INVALID");

        ObjectNode graphId = validProjection();
        graphId.put("graphId", "another-graph");
        assertFailure(graphId, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsMissingAndOutOfOrderCanonicalCases() {
        ObjectNode missing = validProjection();
        missing.withArray("cases").remove(8);
        assertFailure(missing, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SCHEMA_INVALID");

        ObjectNode outOfOrder = validProjection();
        ArrayNode cases = outOfOrder.withArray("cases");
        JsonNode first = cases.get(0).deepCopy();
        cases.set(0, cases.get(1));
        cases.set(1, first);
        assertFailure(outOfOrder, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SCHEMA_INVALID");
    }

    @Test
    void rejectsDuplicateRunIds() {
        ObjectNode projection = validProjection();
        round(projection, 0, 0).put("runId", round(projection, 0, 1).path("runId").textValue());

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_DUPLICATE_RUN_ID");
    }

    @Test
    void rejectsWrongTimeoutStatus() {
        ObjectNode projection = validProjection();
        round(projection, 5, 1).put("status", "PASSED");

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_CASE_STATUS_INVALID");
    }

    @Test
    void rejectsSemanticFingerprintDriftWithinOneCase() {
        ObjectNode projection = validProjection();
        round(projection, 3, 2).put("semanticFingerprint", fingerprint('f'));

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SEMANTIC_FINGERPRINT_DRIFT");
    }

    @Test
    void rejectsOracleFailureEvenWhenTheAggregateClaimsPassed() {
        ObjectNode projection = validProjection();
        caseNode(projection, 4).with("oracle").put("status", "FAIL");

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_ORACLE_NOT_PASS");
    }

    @Test
    void rejectsAnOracleAssertionIdThatDoesNotBelongToTheCase() {
        ObjectNode projection = validProjection();
        caseNode(projection, 0).with("oracle").put("assertionId", "oracle-arbitrary-pass");

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_ORACLE_ASSERTION_ID_INVALID");
    }

    @Test
    void rejectsWriteOrMixedOperatorFootprints() {
        ObjectNode projection = validProjection();
        operator(projection, "cancellationDecision").put("sideEffectType", "WRITE");

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_WRITE_OPERATOR_FORBIDDEN");
    }

    @Test
    void acceptsExternalCallOperatorDeclarationsWhenTheirCallsAreFixtureControlled() {
        ObjectNode projection = validProjection();

        assertThat(VERIFIER.verify(projection).verified()).isTrue();
        assertThat(projection.withArray("operators").get(0).path("sideEffectType").textValue())
                .isEqualTo("EXTERNAL_CALL");
    }

    @Test
    void rejectsAnHttpResourceDeclaredAsReadOnly() {
        ObjectNode projection = validProjection();
        operator(projection, "orderLookup").put("sideEffectType", "READ_ONLY");

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_OPERATOR_SIDE_EFFECT_INVALID");
    }

    @Test
    void rejectsNonZeroCallsAndNonEmptyDiagnostics() {
        ObjectNode calls = validProjection();
        calls.put("realExternalCallCount", 1);
        assertFailure(calls, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SCHEMA,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_SCHEMA_INVALID");

        ObjectNode diagnostics = validProjection();
        diagnostics.withArray("diagnostics").add("FIXTURE_MISMATCH");
        assertFailure(diagnostics, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_DIAGNOSTICS_NOT_EMPTY");
    }

    @Test
    void rejectsFailedAggregateEvenWhenAllOraclesPass() {
        ObjectNode projection = validProjection();
        projection.put("status", "FAILED_CLOSED");

        assertFailure(projection, CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind.SEMANTIC,
                "RG.CAPABILITY_STUDIO.FEATURE_REHEARSAL_BASELINE_AGGREGATE_NOT_PASSED");
    }

    @Test
    void returnsOnlyStableCodesForMalformedWireInput() {
        CapabilityStudioFeatureRehearsalBaselineVerifier.VerificationResult result =
                VERIFIER.verify("{\"schemaVersion\":\"broken\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(result.verified()).isFalse();
        assertThat(result.errorCode()).matches("RG\\.CAPABILITY_STUDIO\\.FEATURE_REHEARSAL_BASELINE_[A-Z_]+");
        assertThat(result.toString()).doesNotContain("DEMO-ORDER", "payload", "fixture");
    }

    private static void assertFailure(
            JsonNode projection,
            CapabilityStudioFeatureRehearsalBaselineVerifier.FailureKind kind,
            String errorCode) {
        CapabilityStudioFeatureRehearsalBaselineVerifier.VerificationResult result =
                VERIFIER.verify(projection);
        assertThat(result.failureKind()).isEqualTo(kind);
        assertThat(result.errorCode()).isEqualTo(errorCode);
    }

    private static ObjectNode validProjection() {
        ObjectNode root = JSON.createObjectNode()
                .put("schemaVersion", "resource-gateway.capability-studio.feature-rehearsal-baseline.v1")
                .put("evidenceKind", "DEVELOPMENT_TEST_OWNED")
                .put("baselineId", "cancellation-fee-canonical-baseline")
                .put("status", "PASSED")
                .put("graphId", "feature-cancellation-dispute-context")
                .put("graphFingerprint", fingerprint('a'))
                .put("caseCount", 9)
                .put("roundCount", 3)
                .put("runCount", 27)
                .put("realExternalCallCount", 0);
        ArrayNode cases = root.putArray("cases");
        for (int caseIndex = 0; caseIndex < CapabilityStudioFeatureRehearsalBaselineVerifier.CANONICAL_CASE_IDS.size(); caseIndex++) {
            cases.add(caseNode(caseIndex));
        }
        ArrayNode operators = root.putArray("operators");
        addOperator(operators, "orderLookup", "httpResource", "EXTERNAL_CALL");
        addOperator(operators, "responsibilityLookup", "httpResource", "EXTERNAL_CALL");
        addOperator(operators, "cityPolicyLookup", "httpResource", "EXTERNAL_CALL");
        addOperator(operators, "compensationHistoryLookup", "httpResource", "EXTERNAL_CALL");
        addOperator(operators, "aggregateCancellationContext", "capabilityStudio.aggregate", "READ_ONLY");
        addOperator(operators, "cancellationDecision", "capabilityStudio.decision", "READ_ONLY");
        root.putArray("diagnostics");
        return root;
    }

    private static ObjectNode caseNode(int caseIndex) {
        String caseId = CapabilityStudioFeatureRehearsalBaselineVerifier.CANONICAL_CASE_IDS.get(caseIndex);
        String status = "case-compensation-history-timeout".equals(caseId) ? "TIMED_OUT" : "PASSED";
        ObjectNode result = JSON.createObjectNode()
                .put("caseId", caseId)
                .put("caseName", "Case " + caseId)
                .put("businessFingerprint", fingerprint('d'));
        ArrayNode rounds = result.putArray("rounds");
        for (int round = 1; round <= 3; round++) {
            rounds.add(JSON.createObjectNode()
                    .put("round", round)
                    .put("runId", "baseline-run-" + caseIndex + "-" + round)
                    .put("status", status)
                    .put("semanticFingerprint", fingerprint('c'))
                    .put("realExternalCallCount", 0));
        }
        result.set("oracle", JSON.createObjectNode()
                .put("assertionId", "oracle-" + caseId.substring("case-".length()))
                .put("status", "PASS")
                .put("expectedSummary", "expected business outcome")
                .put("actualSummary", "actual business outcome")
                .put("actualFingerprint", fingerprint('e')));
        return result;
    }

    private static ObjectNode caseNode(ObjectNode projection, int caseIndex) {
        return (ObjectNode) projection.withArray("cases").get(caseIndex);
    }

    private static ObjectNode round(ObjectNode projection, int caseIndex, int roundIndex) {
        return (ObjectNode) caseNode(projection, caseIndex).withArray("rounds").get(roundIndex);
    }

    private static ObjectNode operator(ObjectNode projection, String nodeId) {
        for (JsonNode value : projection.withArray("operators")) {
            if (nodeId.equals(value.path("nodeId").textValue())) {
                return (ObjectNode) value;
            }
        }
        throw new AssertionError("operator not found: " + nodeId);
    }

    private static void addOperator(
            ArrayNode operators, String nodeId, String operatorRef, String sideEffectType) {
        operators.add(JSON.createObjectNode()
                .put("nodeId", nodeId)
                .put("operatorRef", operatorRef)
                .put("sideEffectType", sideEffectType));
    }

    private static String fingerprint(char fill) {
        return "sha256:" + String.valueOf(fill).repeat(64);
    }
}
