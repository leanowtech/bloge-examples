package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceMapping;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactCaseRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Immutable payload-free lineage manifest decorating one terminal suite-run evidence record. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessEvidenceCompanion(
        String schemaVersion,
        String evidenceCompanionId,
        EnterpriseScope scope,
        String suiteRunId,
        String suiteEvidenceFingerprint,
        String clientRequestFingerprint,
        CorrectnessRunRequest.PublicationRef publicationRef,
        ExactTargetRef target,
        ExactAssetRef definitionRef,
        ExactAssetRef inventoryRef,
        ExactAssetRef scenarioDraftSetRef,
        List<ExactCaseRef> caseRefs,
        List<ExactAssetRef> oracleRefs,
        List<ExactAssetRef> assertionSetRefs,
        List<ExactAssetRef> fixtureAssetRefs,
        List<ExactAssetRef> compiledFixtureBundleRefs,
        ExactAssetRef compiledTestSuiteRef,
        CorrectnessRunRequest.Selection selection,
        List<CaseExecutionRef> caseExecutions,
        List<SourceMapping> sourceMap,
        CorrectnessPreflightReport.RiskSummary riskSummary,
        List<String> dataClassifications,
        CorrectnessVerdict verdict,
        TestSuiteRunAttestation attestation,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessEvidenceCompanion.v1";

    public CorrectnessEvidenceCompanion {
        schemaVersion = version(schemaVersion);
        evidenceCompanionId = required(evidenceCompanionId, "evidenceCompanionId");
        suiteRunId = required(suiteRunId, "suiteRunId");
        suiteEvidenceFingerprint = fingerprint(
                suiteEvidenceFingerprint, "suiteEvidenceFingerprint");
        clientRequestFingerprint = fingerprint(
                clientRequestFingerprint, "clientRequestFingerprint");
        if (scope == null || publicationRef == null || target == null || definitionRef == null
                || inventoryRef == null || scenarioDraftSetRef == null
                || compiledTestSuiteRef == null || selection == null || riskSummary == null
                || verdict == null || attestation == null || metadata == null) {
            throw new IllegalArgumentException("Complete correctness evidence identity is required");
        }
        caseRefs = caseRefs == null ? List.of() : caseRefs.stream().distinct()
                .sorted(Comparator.comparing(ExactCaseRef::caseId)).toList();
        oracleRefs = refs(oracleRefs);
        assertionSetRefs = refs(assertionSetRefs);
        fixtureAssetRefs = refs(fixtureAssetRefs);
        compiledFixtureBundleRefs = refs(compiledFixtureBundleRefs);
        caseExecutions = caseExecutions == null ? List.of() : caseExecutions.stream().distinct()
                .sorted(Comparator.comparing(CaseExecutionRef::caseId)).toList();
        sourceMap = sourceMap == null ? List.of() : sourceMap.stream().distinct()
                .sorted(Comparator
                        .comparing((SourceMapping value) -> value.source().assetRef().kind())
                        .thenComparing(value -> value.source().assetRef().id())
                        .thenComparing(value -> value.source().elementKind())
                        .thenComparing(value -> value.source().elementId())
                        .thenComparing(value -> value.output().assetRef().kind())
                        .thenComparing(value -> value.output().assetRef().id())
                        .thenComparing(value -> value.output().elementKind())
                        .thenComparing(value -> value.output().elementId()))
                .toList();
        dataClassifications = dataClassifications == null ? List.of()
                : dataClassifications.stream().map(value -> required(value, "classification")
                        .toUpperCase(Locale.ROOT)).distinct().sorted().toList();
        if (caseRefs.isEmpty() || caseExecutions.isEmpty() || sourceMap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Evidence companion requires exact Case, execution, and source-map closure");
        }
        List<String> caseIds = caseRefs.stream().map(ExactCaseRef::caseId).sorted().toList();
        List<String> executionCaseIds = caseExecutions.stream()
                .map(CaseExecutionRef::caseId).sorted().toList();
        if (!caseIds.equals(executionCaseIds)
                || !attestation.suiteRunId().equals(suiteRunId)
                || !attestation.aggregateEvidenceFingerprint().equals(suiteEvidenceFingerprint)
                || attestation.scope() != TestSuiteRunAttestation.Scope.TERMINAL) {
            throw new IllegalArgumentException(
                    "Evidence companion Case and terminal attestation closure is invalid");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseExecutionRef(
            String caseId,
            ExactAssetRef fixtureBundleRef,
            String executionPlanFingerprint,
            String childRunId,
            TestRunEvidence.EvidenceClass evidenceClass
    ) {
        public CaseExecutionRef {
            caseId = required(caseId, "caseId");
            executionPlanFingerprint = fingerprint(
                    executionPlanFingerprint, "executionPlanFingerprint");
            childRunId = required(childRunId, "childRunId");
            if (fixtureBundleRef == null || evidenceClass == null) {
                throw new IllegalArgumentException(
                        "Case execution requires exact Fixture and evidence class");
            }
        }
    }

    private static List<ExactAssetRef> refs(List<ExactAssetRef> values) {
        return values == null ? List.of() : values.stream().distinct()
                .sorted(Comparator.comparing(ExactAssetRef::kind)
                        .thenComparing(ExactAssetRef::id)
                        .thenComparingLong(ExactAssetRef::revision))
                .toList();
    }

    private static String version(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return SCHEMA_VERSION;
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported evidence companion schemaVersion");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field).toLowerCase(Locale.ROOT);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
