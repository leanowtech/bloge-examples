package com.leanowtech.bloge.gateway.capabilitystudio;

import java.util.List;

/**
 * Payload-free projection of the Capability Studio governed 9 x 3 development baseline.
 *
 * <p>The projection contains only immutable coordinates, fingerprints, statuses, and failure
 * diagnostics. Fixture/request/response material is deliberately absent from this protocol.</p>
 */
public record CapabilityStudioGovernedBaselineProjection(
        String schemaVersion,
        String evidenceKind,
        String baselineId,
        String status,
        String verificationScope,
        String releaseGateStatus,
        String evidenceClass,
        int caseCount,
        int roundCount,
        int suiteRunCount,
        int childRunCount,
        int oraclePassCount,
        int businessCheckCount,
        int businessCheckPassCount,
        Integer realExternalCallCount,
        String compilationFingerprint,
        String sourceMapFingerprint,
        String provenanceFingerprint,
        Publication publication,
        List<Round> rounds,
        List<CaseProjection> cases,
        List<String> limitations,
        List<String> diagnostics) {

    public static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.governed-baseline.v2";
    public static final String EVIDENCE_KIND = "DEVELOPMENT_TEST_OWNED";
    public static final String VERIFICATION_SCOPE =
            "GOVERNED_SUITE_ASSERTIONS_AND_BUSINESS_ORACLES";
    public static final String RELEASE_GATE_STATUS = "NO_GO";
    public static final String EVIDENCE_CLASS = "EXPLORATORY";
    public static final String PASSED = "PASSED";
    public static final String FAILED_CLOSED = "FAILED_CLOSED";

    public CapabilityStudioGovernedBaselineProjection {
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
        cases = cases == null ? List.of() : List.copyOf(cases);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public record Publication(
            String receiptFingerprint,
            SuiteRef suiteRef,
            int fixtureCount) {
    }

    public record SuiteRef(
            String kind,
            String id,
            long revision,
            String fingerprint) {
    }

    public record Round(
            int round,
            String suiteRunId,
            String evidenceFingerprint,
            String status,
            int childRunCount) {
    }

    public record CaseProjection(
            String caseId,
            String oracleId,
            String oracleStatus,
            String semanticResultFingerprint,
            int assertionsEvaluated,
            int assertionsPassed,
            int fixtureControlsEvaluated,
            int fixtureControlsSatisfied,
            List<String> proofs,
            List<CaseRound> rounds) {
        public CaseProjection {
            proofs = proofs == null ? List.of() : List.copyOf(proofs);
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
        }
    }

    public record CaseRound(
            int round,
            String runId,
            String status,
            String fixtureBundleId,
            long fixtureRevision,
            String fixtureFingerprint,
            String evidenceFingerprint,
            String semanticResultFingerprint,
            int assertionsEvaluated,
            int assertionsPassed,
            int fixtureControlsEvaluated,
            int fixtureControlsSatisfied) {
    }
}
