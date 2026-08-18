package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioStageAcceptanceResultV2VerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CapabilityStudioStageAcceptanceResultV2Verifier VERIFIER =
            new CapabilityStudioStageAcceptanceResultV2Verifier();
    private static final Instant NOW = Instant.parse("2026-01-01T00:10:00Z");
    private static final String STARTED = "2026-01-01T00:00:00Z";
    private static final String EVIDENCE_COMPLETED = "2026-01-01T00:05:00Z";
    private static final String SIGNED = "2026-01-01T00:06:00Z";
    private static final String DECIDED = "2026-01-01T00:07:00Z";
    private static final String EXPIRES = "2026-01-01T01:00:00Z";

    @Test
    void verifiesACompleteStageExitPassWithCanonicalClosure() {
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult result =
                VERIFIER.verify(validStagePass(), NOW);

        assertThat(result.verified()).isTrue();
        assertThat(result.checks()).containsExactly(
                "SCHEMA", "PAYLOAD_FREE", "AC_STD_EXACT_SET", "CANDIDATE_EXECUTION_BINDING",
                "ENVIRONMENT_ATTESTATION", "DEPLOYMENT_EGRESS", "EVIDENCE_CLOSURE",
                "SIGNOFF_CLOSURE", "STATUS_STATE_MACHINE", "STAGE_EXIT_GATE");
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void verifiesTheUtf8WireEntryPoint() throws Exception {
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult result =
                VERIFIER.verify(JSON.writeValueAsBytes(validStagePass()), NOW);

        assertThat(result.verified()).isTrue();
    }

    @Test
    void rejectsTheWrongSchemaVersionAtTheSchemaBoundary() {
        ObjectNode result = validStagePass().put(
                "schemaVersion", "bloge.capabilityStudioStageAcceptanceResult.v1");

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsDevelopmentLedgerKindInV2() {
        ObjectNode result = validStagePass().put("resultKind", "DEVELOPMENT_LEDGER");

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsMissingFormalContractIdentity() {
        ObjectNode result = validStagePass();
        result.remove("contractRevision");

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsUnknownFieldsAtTheStrictSchemaBoundary() {
        ObjectNode result = validStagePass().put("unexpected", true);

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsSensitiveFieldsWithoutEchoingTheirValues() {
        ObjectNode result = validStagePass();
        result.putObject("payload").put("customerSecret", "do-not-log");

        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult verification =
                VERIFIER.verify(result, NOW);

        assertThat(verification.failureKind())
                .isEqualTo(CapabilityStudioStageAcceptanceResultV2Verifier.FailureKind.SCHEMA);
        assertThat(verification.errorCode()).isEqualTo(
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SENSITIVE_FIELD");
        assertThat(verification.toString()).doesNotContain("do-not-log");
    }

    @Test
    void rejectsAnOversizedWireDocumentBeforeParsing() {
        byte[] oversized = ("{" + "x".repeat(
                CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES) + "}")
                .getBytes(StandardCharsets.UTF_8);

        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult result =
                VERIFIER.verify(oversized, NOW);

        assertSchemaFailureResult(result,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIZE_LIMIT");
    }

    @Test
    void rejectsMalformedJsonWithoutReturningParserDetails() {
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult result =
                VERIFIER.verify("{not-json}".getBytes(StandardCharsets.UTF_8), NOW);

        assertSchemaFailureResult(result,
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_INVALID_JSON");
        assertThat(result.toString()).doesNotContain("not-json");
    }

    @Test
    void rejectsPartialBecauseV2HasNoPartialState() {
        ObjectNode result = validStagePass().put("status", "PARTIAL");

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsARepeatedAcceptanceCheckId() {
        ObjectNode result = validStagePass();
        check(result, 1).put("checkId", "AC-STD-01");

        assertSemanticFailure(result, "AC_STD_EXACT_SET",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_DUPLICATE");
    }

    @Test
    void rejectsAnIncompleteAcceptanceCheckSet() {
        ObjectNode result = validStagePass();
        check(result, 8).put("checkId", "AC-STD-01");

        assertSemanticFailure(result, "AC_STD_EXACT_SET",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_DUPLICATE");
    }

    @Test
    void rejectsPassWhenOneStandardIsNotPass() {
        ObjectNode result = validStagePass();
        check(result, 0).put("status", "FAIL");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_PASS_REQUIRES_ALL_AC_STD_PASS");
    }

    @Test
    void rejectsNonPassThatCarriesNinePassingStandards() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        replaceDiagnostics(result, "ACCEPTANCE_CHECK_FAILED");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_NON_PASS_REQUIRES_FAILED_CLOSED_CHECK");
    }

    @Test
    void acceptsAFailedClosedResultWithAConcreteFailingStandard() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        check(result, 0).put("status", "FAIL");
        replaceDiagnostics(result, "ACCEPTANCE_CHECK_FAILED");
        refreshClosure(result);

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void acceptsABlockedClosedResultWithAConcreteBlockedStandard() {
        ObjectNode result = validStagePass().put("status", "BLOCKED");
        check(result, 0).put("status", "BLOCKED");
        replaceDiagnostics(result, "ACCEPTANCE_CHECK_BLOCKED");
        refreshClosure(result);

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void acceptsAnHonestNotRunWithoutProofProjectionsOrSignoffs() {
        ObjectNode result = honestNotRun();

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void acceptsPreExecutionBlockedWithAcStd01BlockedAndAcStd06NotRun() {
        ObjectNode result = honestBlockedWithoutEgress();

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void acceptsBlockedDuringExecutionWithACompleteWindow() {
        ObjectNode result = validStagePass().put("status", "BLOCKED");
        check(result, 0).put("status", "BLOCKED");
        replaceDiagnostics(result, "ACCEPTANCE_CHECK_BLOCKED");
        refreshClosure(result);

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void acceptsFailedEgressObservationWithARealCallAsAClosedFailure() {
        ObjectNode result = failedWithRealEgressCall();

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void rejectsNotRunWithApprovedSignoffsAndNinePassingChecks() {
        ObjectNode result = validStagePass().put("status", "NOT_RUN");
        result.set("environmentAttestation", JSON.nullNode());
        result.set("deploymentEgressObservation", JSON.nullNode());
        object(result, "candidateExecutionBinding")
                .putNull("executionStartedAt")
                .putNull("evidenceCompletedAt");
        replaceDiagnostics(result, "RUN_NOT_STARTED", "ENVIRONMENT_ATTESTATION_UNAVAILABLE",
                "DEPLOYMENT_EGRESS_UNAVAILABLE");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_01_PROJECTION_CONTRADICTION");
    }

    @Test
    void rejectsEnvironmentProjectionAbsenceWithAcStd01Pass() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        result.set("environmentAttestation", JSON.nullNode());
        check(result, 5).put("status", "FAIL");
        replaceDiagnostics(result, "ENVIRONMENT_ATTESTATION_UNAVAILABLE",
                "ACCEPTANCE_CHECK_FAILED");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_01_PROJECTION_CONTRADICTION");
    }

    @Test
    void rejectsEgressProjectionAbsenceWithAcStd06Pass() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        result.set("deploymentEgressObservation", JSON.nullNode());
        check(result, 0).put("status", "FAIL");
        replaceDiagnostics(result, "DEPLOYMENT_EGRESS_UNAVAILABLE",
                "ACCEPTANCE_CHECK_FAILED");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_06_PROJECTION_CONTRADICTION");
    }

    @Test
    void rejectsMissingRequiredSignoffWithAcStd09Pass() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        check(result, 0).put("status", "FAIL");
        ((ArrayNode) result.path("signoffs")).remove(2);
        replaceDiagnostics(result, "ACCEPTANCE_CHECK_FAILED", "SIGNOFFS_UNAVAILABLE");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_09_PROJECTION_CONTRADICTION");
    }

    @Test
    void rejectsBlockedWithOnlyOneExecutionTime() {
        ObjectNode result = honestBlockedWithoutEgress();
        object(result, "candidateExecutionBinding").put("executionStartedAt", STARTED);
        refreshClosure(result);

        assertSemanticFailure(result, "CANDIDATE_EXECUTION_BINDING",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_BLOCKED_TIMES_MUST_BOTH_BE_NULL_OR_SET");
    }

    @Test
    void rejectsExecutionTimeWithRunNotStartedDiagnostic() {
        ObjectNode result = validStagePass().put("status", "BLOCKED");
        check(result, 0).put("status", "BLOCKED");
        replaceDiagnostics(result, "RUN_NOT_STARTED", "ACCEPTANCE_CHECK_BLOCKED");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EXECUTION_DIAGNOSTIC_CONTRADICTION");
    }

    @Test
    void rejectsNullExecutionTimesWithoutRunNotStartedDiagnostic() {
        ObjectNode result = honestBlockedWithoutEgress();
        removeDiagnostic(result, "RUN_NOT_STARTED");

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EXECUTION_DIAGNOSTIC_MISSING");
    }

    @Test
    void rejectsPreExecutionBlockedWithProofProjection() {
        ObjectNode result = honestBlockedWithoutEgress();
        result.set("environmentAttestation", validStagePass().path("environmentAttestation"));
        refreshClosure(result);

        assertSemanticFailure(result, "ENVIRONMENT_ATTESTATION",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_WINDOW_INVALID");
    }

    @Test
    void rejectsPassWithoutEnvironmentAttestation() {
        ObjectNode result = validStagePass();
        result.set("environmentAttestation", JSON.nullNode());
        refreshClosure(result);

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsPassWithoutDeploymentEgressObservation() {
        ObjectNode result = validStagePass();
        result.set("deploymentEgressObservation", JSON.nullNode());
        refreshClosure(result);

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsPassWithoutTheThreeRequiredSignoffs() {
        ObjectNode result = validStagePass();
        result.remove("signoffs");
        result.putArray("signoffs");

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsEnvironmentIssuedAfterExecutionStarted() {
        ObjectNode result = validStagePass();
        object(result, "environmentAttestation").put("issuedAt", "2026-01-01T00:01:00Z");

        assertSemanticFailure(result, "ENVIRONMENT_ATTESTATION",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_WINDOW_INVALID");
    }

    @Test
    void rejectsEnvironmentThatExpiresBeforeEvidenceCompletion() {
        ObjectNode result = validStagePass();
        object(result, "environmentAttestation").put("expiresAt", "2026-01-01T00:04:00Z");

        assertSemanticFailure(result, "ENVIRONMENT_ATTESTATION",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_WINDOW_INVALID");
    }

    @Test
    void rejectsAnExpiredEnvironmentAttestationForPass() {
        ObjectNode result = validStagePass();
        object(result, "environmentAttestation").put("expiresAt", "2026-01-01T00:09:00Z");

        assertSemanticFailure(result, "ENVIRONMENT_ATTESTATION",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_EXPIRED");
    }

    @Test
    void rejectsAnEgressWindowThatOnlyCoversPartOfExecution() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation")
                .put("observationStartedAt", "2026-01-01T00:01:00Z");

        assertSemanticFailure(result, "DEPLOYMENT_EGRESS",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_WINDOW_INVALID");
    }

    @Test
    void rejectsAnEgressWindowThatEndsBeforeEvidenceCompletion() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation")
                .put("observationCompletedAt", "2026-01-01T00:04:00Z");

        assertSemanticFailure(result, "DEPLOYMENT_EGRESS",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_WINDOW_INVALID");
    }

    @Test
    void rejectsDecisionBeforeEvidenceCompletion() {
        ObjectNode result = validStagePass().put("decidedAt", "2026-01-01T00:04:00Z");

        assertSemanticFailure(result, "CANDIDATE_EXECUTION_BINDING",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_BINDING_TIME_INVALID");
    }

    @Test
    void rejectsADecisionInTheFuture() {
        ObjectNode result = validStagePass().put("decidedAt", "2026-01-01T00:11:00Z");

        assertSemanticFailure(result, "CANDIDATE_EXECUTION_BINDING",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_DECISION_IN_FUTURE");
    }

    @Test
    void rejectsNotRunWhenItCarriesExecutionTimes() {
        ObjectNode result = honestNotRun();
        object(result, "candidateExecutionBinding").put("executionStartedAt", STARTED);
        refreshClosure(result);

        assertSemanticFailure(result, "CANDIDATE_EXECUTION_BINDING",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_NOT_RUN_TIMES_MUST_BE_NULL");
    }

    @Test
    void rejectsCandidateArtifactDrift() {
        ObjectNode result = validStagePass();
        object(object(result, "candidateExecutionBinding"), "candidateBuild")
                .put("artifactFingerprint", fingerprint('6'));

        assertSemanticFailure(result, "CANDIDATE_EXECUTION_BINDING",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CANDIDATE_ARTIFACT_MISMATCH");
    }

    @Test
    void rejectsDirtyCandidateTreesForPassSemantically() {
        ObjectNode result = validStagePass();
        object(object(result, "candidateExecutionBinding"), "candidateBuild")
                .put("sourceTreeStatus", "DIRTY");

        assertSemanticFailure(result, "CANDIDATE_EXECUTION_BINDING",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_PASS_CANDIDATE_NOT_CLEAN");
    }

    @Test
    void acceptsFailedResultWithDirtyCandidateAndHonestDiagnostic() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        check(result, 0).put("status", "FAIL");
        object(object(result, "candidateExecutionBinding"), "candidateBuild")
                .put("sourceTreeStatus", "DIRTY");
        replaceDiagnostics(result, "ACCEPTANCE_CHECK_FAILED", "CANDIDATE_NOT_CLEAN");
        refreshClosure(result);

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void rejectsFailWithoutAcceptanceCheckFailedDiagnostic() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        check(result, 0).put("status", "FAIL");
        replaceDiagnostics(result, "SIGNOFFS_UNAVAILABLE");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_STATUS_DIAGNOSTIC_MISSING");
    }

    @Test
    void rejectsBlockedWithoutAcceptanceCheckBlockedDiagnostic() {
        ObjectNode result = validStagePass().put("status", "BLOCKED");
        check(result, 0).put("status", "BLOCKED");
        replaceDiagnostics(result, "SIGNOFFS_UNAVAILABLE");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_STATUS_DIAGNOSTIC_MISSING");
    }

    @Test
    void rejectsInvalidFingerprintFormatAtTheSchemaBoundary() {
        ObjectNode result = validStagePass();
        result.put("evidenceClosureFingerprint", "closure");

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsEnvironmentFingerprintDrift() {
        ObjectNode result = validStagePass();
        object(result, "environmentAttestation").put("environmentFingerprint", fingerprint('6'));

        assertSemanticFailure(result, "ENVIRONMENT_ATTESTATION",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_BINDING_MISMATCH");
    }

    @Test
    void rejectsEgressIntentDrift() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation")
                .put("candidateIntentFingerprint", fingerprint('6'));

        assertSemanticFailure(result, "DEPLOYMENT_EGRESS",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_BINDING_MISMATCH");
    }

    @Test
    void rejectsPassWhenDeploymentObservedARealExternalCall() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation").put("observedExternalCallCount", 1);

        assertSemanticFailure(result, "DEPLOYMENT_EGRESS",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_EXTERNAL_CALLS_NONZERO");
    }

    @Test
    void rejectsPassWhenDeploymentHadDeniedExternalAttempts() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation").put("deniedAttemptCount", 1);

        assertSemanticFailure(result, "DEPLOYMENT_EGRESS",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_DENIED_ATTEMPTS_NONZERO");
    }

    @Test
    void rejectsEgressCompletedAfterDecision() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation")
                .put("observationCompletedAt", "2026-01-01T00:08:00Z");

        assertSemanticFailure(result, "DEPLOYMENT_EGRESS",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_WINDOW_INVALID");
    }

    @Test
    void rejectsPassWhenEgressStatusIsNotPass() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation").put("status", "BLOCKED");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_06_PROJECTION_CONTRADICTION");
    }

    @Test
    void permitsZeroDeniedAttempts() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation").put("deniedAttemptCount", 0);

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void permitsTheSameCatalogEvidenceIdOnMultipleChecks() {
        ObjectNode result = validStagePass();
        for (int i = 0; i < 9; i++) {
            check(result, i).putArray("evidenceIds").add("check-1");
        }
        refreshClosure(result);

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
    }

    @Test
    void rejectsDuplicateEvidenceIdsInTheAuthoritativeCatalog() {
        ObjectNode result = validStagePass();
        evidence(result, 1).put("evidenceId", "check-1");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EVIDENCE_CATALOG_DUPLICATE");
    }

    @Test
    void rejectsNonAvailableEvidenceAtTheSchemaBoundary() {
        ObjectNode result = validStagePass();
        evidence(result, 0).put("status", "MISSING");

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsCopiedEvidenceObjectsInsideAnAcceptanceCheck() {
        ObjectNode result = validStagePass();
        check(result, 0).set("evidenceRefs", JSON.createArrayNode());

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsAnUnknownCheckEvidenceId() {
        ObjectNode result = validStagePass();
        check(result, 0).putArray("evidenceIds").add("evidence:not-in-catalog");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EVIDENCE_ID_UNRESOLVED");
    }

    @Test
    void rejectsAnEnvironmentCoordinateAbsentFromTheCatalog() {
        ObjectNode result = validStagePass();
        object(result, "environmentAttestation").put("exactRef", "attestation:unknown");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_EVIDENCE_UNRESOLVED");
    }

    @Test
    void rejectsAnEgressCoordinateAbsentFromTheCatalog() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation").put("exactRef", "egress:unknown");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_EVIDENCE_UNRESOLVED");
    }

    @Test
    void rejectsAStringSignatureRefAtTheSchemaBoundary() {
        ObjectNode result = validStagePass();
        item(result, "signoffs", 0).put("signatureRef", "signature:bare");

        assertSchemaFailure(result, "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
    }

    @Test
    void rejectsRootClosureFingerprintTampering() {
        ObjectNode result = validStagePass().put("evidenceClosureFingerprint", fingerprint('6'));

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsResultIdentityTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass().put("resultId", "SAR-tampered");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsResultRevisionTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass().put("revision", 3);

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsStatusTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        replaceDiagnostics(result, "ACCEPTANCE_CHECK_FAILED");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsEnvironmentProjectionTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass();
        object(result, "environmentAttestation").put("profile", "profile:tampered");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsEgressProjectionTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass();
        object(result, "deploymentEgressObservation")
                .put("networkPolicyRef", "network-policy:tampered");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsSignoffClosureTampering() {
        ObjectNode result = validStagePass();
        item(result, "signoffs", 0).put("evidenceClosureFingerprint", fingerprint('6'));

        assertSemanticFailure(result, "SIGNOFF_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_CLOSURE_MISMATCH");
    }

    @Test
    void permitsSignatureCoordinateChangesWithoutChangingEvidenceClosure() {
        ObjectNode result = validStagePass();
        object(item(result, "signoffs", 0), "signatureRef")
                .put("exactRef", "signature:correctness:rotated");

        assertThat(VERIFIER.verify(result, NOW).verified()).isTrue();
        assertThat(item(result, "signoffs", 0).path("evidenceClosureFingerprint").textValue())
                .isEqualTo(result.path("evidenceClosureFingerprint").textValue());
    }

    @Test
    void rejectsPartialSignoffsWithoutAnUnavailableDiagnostic() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        check(result, 0).put("status", "FAIL");
        check(result, 8).put("status", "NOT_RUN").putArray("evidenceIds");
        ((ArrayNode) result.path("signoffs")).remove(2);
        replaceDiagnostics(result, "ACCEPTANCE_CHECK_FAILED");
        refreshClosure(result);

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_UNAVAILABLE_DIAGNOSTIC_MISSING");
    }

    @Test
    void rejectsCandidateCoordinateTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass();
        object(result, "candidateExecutionBinding")
                .put("candidateIntentFingerprint", fingerprint('6'));

        assertSemanticFailure(result, "DEPLOYMENT_EGRESS",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_BINDING_MISMATCH");
    }

    @Test
    void rejectsBaselineCoordinateTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass();
        object(result, "candidateExecutionBinding").path("baselineRef");
        object(object(result, "candidateExecutionBinding"), "baselineRef")
                .put("exactRef", "baseline:tampered");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsEvidenceCoordinateTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass();
        evidence(result, 5).put("exactRef", "evidence:tampered");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
    }

    @Test
    void rejectsCheckEvidenceCoordinateTamperingWithoutRecomputingClosure() {
        ObjectNode result = validStagePass();
        check(result, 0).putArray("evidenceIds").add("check-2");
        refreshClosure(result);
        check(result, 0).putArray("evidenceIds").add("check-unknown");

        assertSemanticFailure(result, "EVIDENCE_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EVIDENCE_ID_UNRESOLVED");
    }

    @Test
    void rejectsMissingUnavailableEnvironmentDiagnostic() {
        ObjectNode result = honestNotRun();
        removeDiagnostic(result, "ENVIRONMENT_ATTESTATION_UNAVAILABLE");

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_UNAVAILABLE_DIAGNOSTIC_MISSING");
    }

    @Test
    void rejectsNotRunWithoutRunStartedDiagnostic() {
        ObjectNode result = honestNotRun();
        removeDiagnostic(result, "RUN_NOT_STARTED");

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_STATUS_DIAGNOSTIC_MISSING");
    }

    @Test
    void rejectsNotRunWithoutAcceptanceCheckNotRunDiagnostic() {
        ObjectNode result = honestNotRun();
        removeDiagnostic(result, "ACCEPTANCE_CHECK_NOT_RUN");

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_STATUS_DIAGNOSTIC_MISSING");
    }

    @Test
    void rejectsDuplicateSignoffRoles() {
        ObjectNode result = validStagePass();
        item(result, "signoffs", 2).put("role", "RUNTIME_OWNER");

        assertSemanticFailure(result, "SIGNOFF_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_SET_INVALID");
    }

    @Test
    void rejectsPassWithoutExactRequiredRoleCoverage() {
        ObjectNode result = validStagePass();
        item(result, "signoffs", 2).put("role", "OWNER");

        assertSemanticFailure(result, "SIGNOFF_CLOSURE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_SET_INVALID");
    }

    @Test
    void rejectsRejectedSignoffOnPass() {
        ObjectNode result = validStagePass();
        item(result, "signoffs", 0).put("decision", "REJECTED");

        assertSemanticFailure(result, "STATUS_STATE_MACHINE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_09_PROJECTION_CONTRADICTION");
    }

    @Test
    void rejectsSignoffBeforeEvidenceCompletion() {
        ObjectNode result = validStagePass();
        item(result, "signoffs", 0).put("signedAt", EVIDENCE_COMPLETED);

        assertSemanticFailure(result, "STAGE_EXIT_GATE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_TIME_INVALID");
    }

    @Test
    void rejectsSignoffAfterResultDecision() {
        ObjectNode result = validStagePass();
        item(result, "signoffs", 0).put("signedAt", "2026-01-01T00:08:00Z");

        assertSemanticFailure(result, "STAGE_EXIT_GATE",
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_TIME_INVALID");
    }

    @Test
    void rejectsNullInput() {
        assertSchemaFailureResult(VERIFIER.verify((JsonNode) null, NOW),
                "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_NULL");
    }

    private static ObjectNode validStagePass() {
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "bloge.capabilityStudioStageAcceptanceResult.v2");
        result.put("resultId", "SAR-s0-ac-01-v2-pass");
        result.put("revision", 2);
        result.put("contractId", "contract:capability-studio-stage-acceptance");
        result.put("contractRevision", "2026-01");
        result.put("resultKind", "STAGE_EXIT");
        result.put("status", "PASS");
        result.put("decidedAt", DECIDED);
        ObjectNode binding = result.putObject("candidateExecutionBinding");
        binding.putObject("candidateBuild")
                .put("buildRef", "build:capability-studio")
                .put("revision", "rev-2")
                .put("sourceCommit", "abcdef1234567")
                .put("sourceTreeStatus", "CLEAN")
                .put("artifactFingerprint", fingerprint('5'));
        binding.put("candidateIntentFingerprint", fingerprint('4'));
        binding.putObject("baselineRef")
                .put("exactRef", "baseline:capability-studio:v2")
                .put("fingerprint", fingerprint('1'));
        binding.putObject("demoPackRef")
                .put("exactRef", "demo-pack:capability-studio:v2")
                .put("fingerprint", fingerprint('2'));
        binding.put("environmentFingerprint", fingerprint('3'))
                .put("executionStartedAt", STARTED)
                .put("evidenceCompletedAt", EVIDENCE_COMPLETED);

        result.putObject("environmentAttestation")
                .put("exactRef", "attestation:environment:1")
                .put("fingerprint", fingerprint('a'))
                .put("environmentFingerprint", fingerprint('3'))
                .put("profile", "capability-studio:stage-acceptance")
                .put("scope", "tenant:demo/environment:acceptance")
                .put("issuer", "issuer:deployment-control-plane")
                .put("issuedAt", STARTED)
                .put("expiresAt", EXPIRES)
                .put("candidateArtifactFingerprint", fingerprint('5'));

        result.putObject("deploymentEgressObservation")
                .put("exactRef", "egress-observation:deployment:1")
                .put("fingerprint", fingerprint('b'))
                .put("candidateIntentFingerprint", fingerprint('4'))
                .put("observationStartedAt", STARTED)
                .put("observationCompletedAt", EVIDENCE_COMPLETED)
                .put("networkPolicyRef", "network-policy:deny-external-v1")
                .put("observedExternalCallCount", 0)
                .put("deniedAttemptCount", 0)
                .put("status", "PASS");

        ArrayNode evidence = result.putArray("evidenceRefs");
        evidence.add(evidence("environment", "attestation:environment:1", 'a'));
        evidence.add(evidence("egress", "egress-observation:deployment:1", 'b'));
        for (int i = 1; i <= 9; i++) {
            evidence.add(evidence("check-" + i, "evidence:check:" + i, (char) ('0' + i)));
        }
        ArrayNode checks = result.putArray("acceptanceChecks");
        for (int i = 1; i <= 9; i++) {
            checks.addObject()
                    .put("checkId", "AC-STD-0" + i)
                    .put("status", "PASS")
                    .putArray("evidenceIds")
                    .add("check-" + i);
        }
        ArrayNode signoffs = result.putArray("signoffs");
        signoffs.add(signoff("CORRECTNESS_OWNER", "actor:correctness", "signature:correctness", 'c'));
        signoffs.add(signoff("RUNTIME_OWNER", "actor:runtime", "signature:runtime", 'd'));
        signoffs.add(signoff("QA_OWNER", "actor:qa", "signature:qa", 'e'));
        result.putArray("diagnostics");
        refreshClosure(result);
        return result;
    }

    private static ObjectNode honestNotRun() {
        ObjectNode result = baseNonPass("NOT_RUN");
        object(result, "candidateExecutionBinding")
                .putNull("executionStartedAt")
                .putNull("evidenceCompletedAt");
        for (int i = 0; i < 9; i++) {
            check(result, i).put("status", "NOT_RUN").putArray("evidenceIds");
        }
        replaceDiagnostics(result, "RUN_NOT_STARTED", "ENVIRONMENT_ATTESTATION_UNAVAILABLE",
                "DEPLOYMENT_EGRESS_UNAVAILABLE", "SIGNOFFS_UNAVAILABLE",
                "ACCEPTANCE_CHECK_NOT_RUN");
        refreshClosure(result);
        return result;
    }

    private static ObjectNode honestBlockedWithoutEgress() {
        ObjectNode result = honestNotRun().put("status", "BLOCKED");
        check(result, 0).put("status", "BLOCKED");
        check(result, 5).put("status", "NOT_RUN");
        check(result, 8).put("status", "NOT_RUN");
        replaceDiagnostics(result, "RUN_NOT_STARTED", "ENVIRONMENT_ATTESTATION_UNAVAILABLE",
                "DEPLOYMENT_EGRESS_UNAVAILABLE", "SIGNOFFS_UNAVAILABLE", "ACCEPTANCE_CHECK_BLOCKED");
        refreshClosure(result);
        return result;
    }

    private static ObjectNode failedWithRealEgressCall() {
        ObjectNode result = validStagePass().put("status", "FAIL");
        result.set("environmentAttestation", JSON.nullNode());
        result.remove("signoffs");
        result.putArray("signoffs");
        check(result, 0).put("status", "NOT_RUN").putArray("evidenceIds");
        check(result, 5).put("status", "FAIL");
        check(result, 8).put("status", "NOT_RUN").putArray("evidenceIds");
        object(result, "deploymentEgressObservation")
                .put("observedExternalCallCount", 1)
                .put("status", "FAIL");
        replaceDiagnostics(result, "ENVIRONMENT_ATTESTATION_UNAVAILABLE", "SIGNOFFS_UNAVAILABLE",
                "ACCEPTANCE_CHECK_FAILED");
        refreshClosure(result);
        return result;
    }

    private static ObjectNode baseNonPass(String status) {
        ObjectNode result = validStagePass().put("status", status);
        result.set("environmentAttestation", JSON.nullNode());
        result.set("deploymentEgressObservation", JSON.nullNode());
        result.remove("evidenceRefs");
        result.putArray("evidenceRefs");
        result.remove("signoffs");
        result.putArray("signoffs");
        return result;
    }

    private static ObjectNode evidence(ObjectNode result, int index) {
        return (ObjectNode) result.path("evidenceRefs").path(index);
    }

    private static ObjectNode evidence(String id, String exactRef, char seed) {
        return JSON.createObjectNode()
                .put("evidenceId", id)
                .put("exactRef", exactRef)
                .put("fingerprint", fingerprint(seed))
                .put("status", "AVAILABLE");
    }

    private static ObjectNode signoff(
            String role, String actor, String exactRef, char seed) {
        ObjectNode signoff = JSON.createObjectNode()
                .put("role", role)
                .put("actorRef", actor)
                .put("decision", "APPROVED")
                .put("signedAt", SIGNED);
        signoff.set("signatureRef", JSON.createObjectNode()
                .put("exactRef", exactRef)
                .put("fingerprint", fingerprint(seed)));
        signoff.put("evidenceClosureFingerprint", fingerprint('0'));
        return signoff;
    }

    private static ObjectNode check(ObjectNode result, int index) {
        return (ObjectNode) result.path("acceptanceChecks").path(index);
    }

    private static ObjectNode object(ObjectNode value, String field) {
        return (ObjectNode) value.path(field);
    }

    private static ObjectNode item(ObjectNode value, String field, int index) {
        return (ObjectNode) value.path(field).path(index);
    }

    private static void refreshClosure(ObjectNode result) {
        String closure = CapabilityStudioStageAcceptanceResultV2Verifier.closureFingerprint(result);
        result.put("evidenceClosureFingerprint", closure);
        for (JsonNode signoff : result.path("signoffs")) {
            ((ObjectNode) signoff).put("evidenceClosureFingerprint", closure);
        }
    }

    private static void replaceDiagnostics(ObjectNode result, String... codes) {
        ArrayNode diagnostics = result.putArray("diagnostics");
        for (String code : codes) {
            diagnostics.addObject().put("code", code);
        }
    }

    private static void removeDiagnostic(ObjectNode result, String code) {
        ArrayNode diagnostics = (ArrayNode) result.path("diagnostics");
        for (int i = diagnostics.size() - 1; i >= 0; i--) {
            if (code.equals(diagnostics.path(i).path("code").textValue())) {
                diagnostics.remove(i);
            }
        }
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static void assertSchemaFailure(JsonNode value, String errorCode) {
        assertSchemaFailureResult(VERIFIER.verify(value, NOW), errorCode);
    }

    private static void assertSchemaFailureResult(
            CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult result,
            String errorCode) {
        assertThat(result.verified()).isFalse();
        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioStageAcceptanceResultV2Verifier.FailureKind.SCHEMA);
        assertThat(result.errorCode()).isEqualTo(errorCode);
    }

    private static void assertSemanticFailure(
            JsonNode value, String check, String errorCode) {
        CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult result =
                VERIFIER.verify(value, NOW);

        assertThat(result.verified()).isFalse();
        assertThat(result.failureKind())
                .isEqualTo(CapabilityStudioStageAcceptanceResultV2Verifier.FailureKind.SEMANTIC);
        assertThat(result.checks()).containsExactly(check);
        assertThat(result.errorCode()).isEqualTo(errorCode);
    }
}
