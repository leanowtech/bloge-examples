package com.leanowtech.bloge.gateway.capabilitystudio;

import java.util.List;
import java.util.Objects;

/** Payload-free development evidence projection for the canonical 9 Case x 3 round rehearsal. */
public record CapabilityStudioFeatureRehearsalBaselineProjection(
        String schemaVersion,
        String evidenceKind,
        String baselineId,
        String status,
        String graphId,
        String graphFingerprint,
        int caseCount,
        int roundCount,
        int runCount,
        int realExternalCallCount,
        List<CaseResult> cases,
        List<OperatorSummary> operators,
        List<String> diagnostics) {

    public static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.feature-rehearsal-baseline.v1";
    public static final String EVIDENCE_KIND = "DEVELOPMENT_TEST_OWNED";

    public CapabilityStudioFeatureRehearsalBaselineProjection {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Feature Rehearsal Baseline schema version");
        }
        evidenceKind = evidenceKind == null || evidenceKind.isBlank()
                ? EVIDENCE_KIND : evidenceKind.trim();
        if (!EVIDENCE_KIND.equals(evidenceKind)) {
            throw new IllegalArgumentException("Feature Rehearsal Baseline evidence kind is not development-owned");
        }
        baselineId = safe(baselineId);
        status = safe(status);
        graphId = safe(graphId);
        graphFingerprint = safe(graphFingerprint);
        caseCount = Math.max(0, caseCount);
        roundCount = Math.max(0, roundCount);
        runCount = Math.max(0, runCount);
        realExternalCallCount = Math.max(0, realExternalCallCount);
        cases = cases == null ? List.of() : List.copyOf(cases);
        operators = operators == null ? List.of() : List.copyOf(operators);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public record CaseResult(
            String caseId,
            String caseName,
            List<RoundResult> rounds,
            CapabilityStudioFeatureRehearsalOracle.Evaluation oracle,
            String businessFingerprint) {
        public CaseResult {
            caseId = safe(caseId);
            caseName = safe(caseName);
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
            Objects.requireNonNull(oracle, "oracle");
            businessFingerprint = safe(businessFingerprint);
        }
    }

    public record RoundResult(
            int round,
            String runId,
            String status,
            String semanticFingerprint,
            int realExternalCallCount) {
        public RoundResult {
            round = Math.max(0, round);
            runId = safe(runId);
            status = safe(status);
            semanticFingerprint = safe(semanticFingerprint);
            realExternalCallCount = Math.max(0, realExternalCallCount);
        }
    }

    public record OperatorSummary(
            String nodeId,
            String operatorRef,
            String sideEffectType) {
        public OperatorSummary {
            nodeId = safe(nodeId);
            operatorRef = safe(operatorRef);
            sideEffectType = safe(sideEffectType);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
