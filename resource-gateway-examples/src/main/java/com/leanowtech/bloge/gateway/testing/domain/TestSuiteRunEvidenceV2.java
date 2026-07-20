package com.leanowtech.bloge.gateway.testing.domain;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregate suite evidence generation that commits typed semantic coverage facts.
 *
 * <p>The record is intentionally separate from v1. Its concrete canonical JSON, aggregate
 * fingerprint, and attestation domain are v2-only.</p>
 *
 * @param schemaVersion exact v2 aggregate evidence version
 * @param suiteRunId durable aggregate run id
 * @param clientRequestId caller idempotency key
 * @param status aggregate execution status
 * @param executionPurpose authorized execution purpose
 * @param suiteRef exact suite v2 revision
 * @param target exact graph or operator target
 * @param startedAt authoritative start time
 * @param completedAt terminal time
 * @param caseResults ordered case outcomes
 * @param coverage structural coverage verdict
 * @param semanticCoverage semantic coverage verdict
 * @param promotion promotion eligibility verdict
 * @param diagnostics bounded diagnostics
 * @param metadata bounded provenance
 */
public record TestSuiteRunEvidenceV2(
        String schemaVersion,
        String suiteRunId,
        String clientRequestId,
        TestSuiteRunEvidence.Status status,
        String executionPurpose,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        TestSuite.Target target,
        Instant startedAt,
        Instant completedAt,
        List<TestSuiteRunEvidence.CaseResult> caseResults,
        TestSuiteRunEvidence.CoverageVerdict coverage,
        SemanticCoverageVerdict semanticCoverage,
        TestSuiteRunEvidence.PromotionVerdict promotion,
        List<String> diagnostics,
        Map<String, Object> metadata
) implements TestSuiteRunEvidenceProtocol {
    /** Current semantic aggregate evidence protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteRunEvidence.v2";

    /** Normalizes values while preserving an independent v2 canonical record. */
    public TestSuiteRunEvidenceV2 {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        suiteRunId = normalized(suiteRunId);
        clientRequestId = normalized(clientRequestId);
        status = status == null ? TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE : status;
        executionPurpose = normalized(executionPurpose);
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        coverage = coverage == null
                ? TestSuiteRunEvidence.CoverageVerdict.notEvaluated() : coverage;
        semanticCoverage = semanticCoverage == null
                ? SemanticCoverageVerdict.notEvaluated(List.of()) : semanticCoverage;
        promotion = promotion == null
                ? TestSuiteRunEvidence.PromotionVerdict.notEvaluated() : promotion;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        metadata = ProtocolJsonValue.freezeMap(metadata);
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
