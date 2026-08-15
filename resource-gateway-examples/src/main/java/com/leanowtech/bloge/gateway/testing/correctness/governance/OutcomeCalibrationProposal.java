package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactCaseRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest.PublicationRef;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Human-reviewed proposal derived from an observed mismatch; it is never business truth itself. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutcomeCalibrationProposal(
        String schemaVersion,
        String proposalId,
        EnterpriseScope scope,
        PublicationRef publicationRef,
        String suiteRunId,
        ExactAssetRef evidenceCompanionRef,
        ExactTargetRef target,
        List<ExactCaseRef> caseRefs,
        List<ExactAssetRef> oracleRefs,
        MismatchKind mismatchKind,
        String reasonCode,
        String businessRationale,
        String proposedRegressionTitle,
        ProposalStatus status,
        PrincipalRef owner,
        String correlationId,
        AuditMetadata metadata
) {
    public static final String SCHEMA_VERSION = "bloge.outcomeCalibrationProposal.v1";

    public enum MismatchKind {
        EXPECTED_OUTCOME_DIFFERED,
        FORBIDDEN_OUTCOME_OBSERVED,
        MISSING_BUSINESS_BRANCH,
        STALE_BUSINESS_ASSUMPTION,
        OTHER
    }

    /** Promotion to a regression Case belongs to a separate reviewed command. */
    public enum ProposalStatus { PROPOSED }

    public OutcomeCalibrationProposal {
        schemaVersion = version(schemaVersion);
        proposalId = required(proposalId, "proposalId");
        suiteRunId = required(suiteRunId, "suiteRunId");
        if (scope == null || publicationRef == null || evidenceCompanionRef == null
                || target == null || mismatchKind == null || owner == null || metadata == null) {
            throw new IllegalArgumentException("Complete calibration proposal coordinates are required");
        }
        if (!"CORRECTNESS_EVIDENCE_COMPANION".equals(evidenceCompanionRef.kind())) {
            throw new IllegalArgumentException(
                    "Calibration proposal requires an exact evidence companion ref");
        }
        caseRefs = caseRefs == null ? List.of() : caseRefs.stream().distinct()
                .sorted(Comparator.comparing(ExactCaseRef::caseId)).toList();
        oracleRefs = refs(oracleRefs);
        if (caseRefs.isEmpty() || oracleRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Calibration proposal requires affected Case and Oracle refs");
        }
        reasonCode = required(reasonCode, "reasonCode").toUpperCase(Locale.ROOT);
        businessRationale = bounded(businessRationale, "businessRationale", 4000);
        proposedRegressionTitle = bounded(
                proposedRegressionTitle, "proposedRegressionTitle", 240);
        status = status == null ? ProposalStatus.PROPOSED : status;
        if (status != ProposalStatus.PROPOSED) {
            throw new IllegalArgumentException(
                    "Outcome calibration can only be persisted as a PROPOSED fact");
        }
        correlationId = required(correlationId, "correlationId");
    }

    private static List<ExactAssetRef> refs(List<ExactAssetRef> values) {
        return values == null ? List.of() : values.stream().distinct()
                .sorted(Comparator.comparing(ExactAssetRef::kind)
                        .thenComparing(ExactAssetRef::id)
                        .thenComparingLong(ExactAssetRef::revision)).toList();
    }

    private static String version(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return SCHEMA_VERSION;
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported calibration proposal schemaVersion");
        }
        return normalized;
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = required(value, field);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
