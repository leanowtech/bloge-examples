package com.leanowtech.bloge.gateway.visual.golden;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Promotion-readiness status derived from current golden cases and latest certification.
 *
 * @param schemaVersion status schema version
 * @param publicationId immutable publication id
 * @param status machine-readable promotion gate status
 * @param promotionReady whether the publication is currently certified for promotion
 * @param caseCount current number of golden cases
 * @param caseSetFingerprint current golden case set fingerprint
 * @param latestCaseCreatedAt newest golden case timestamp
 * @param certification latest stored certification, when present
 * @param diagnostics gate diagnostics
 */
public record VisualGraphGoldenCertificationStatus(
        String schemaVersion,
        String publicationId,
        Status status,
        boolean promotionReady,
        int caseCount,
        String caseSetFingerprint,
        Instant latestCaseCreatedAt,
        VisualGraphGoldenCertification certification,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphGoldenCertificationStatus.v1";

    /**
     * Golden promotion gate statuses.
     */
    public enum Status {
        CERTIFIED,
        STALE,
        FAILED,
        MISSING_CASES,
        UNCERTIFIED
    }

    /**
     * Creates a status record.
     */
    public VisualGraphGoldenCertificationStatus {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        publicationId = publicationId == null ? "" : publicationId;
        status = status == null ? Status.UNCERTIFIED : status;
        promotionReady = status == Status.CERTIFIED && (diagnostics == null
                || diagnostics.stream().noneMatch(VisualDiagnostic::error));
        caseCount = Math.max(0, caseCount);
        caseSetFingerprint = caseSetFingerprint == null ? "" : caseSetFingerprint;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Builds current certification status for one publication.
     *
     * @param publicationId publication id
     * @param cases current golden cases
     * @param certification latest certification, or {@code null}
     * @param caseSetFingerprint current case set fingerprint
     * @return current status
     */
    public static VisualGraphGoldenCertificationStatus from(String publicationId,
                                                            Collection<VisualGraphGoldenCase> cases,
                                                            VisualGraphGoldenCertification certification,
                                                            String caseSetFingerprint) {
        List<VisualGraphGoldenCase> safeCases = cases == null ? List.of() : List.copyOf(cases);
        Instant latestCaseCreatedAt = safeCases.stream()
                .map(VisualGraphGoldenCase::createdAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (safeCases.isEmpty()) {
            return new VisualGraphGoldenCertificationStatus("", publicationId, Status.MISSING_CASES, false,
                    0, caseSetFingerprint, latestCaseCreatedAt, certification, List.of(
                            VisualDiagnostic.error("visual.golden.status.noCases",
                                    "Publication '%s' has no golden cases and cannot be promoted."
                                            .formatted(publicationId),
                                    "/publicationId")
                    ));
        }
        if (certification == null) {
            return new VisualGraphGoldenCertificationStatus("", publicationId, Status.UNCERTIFIED, false,
                    safeCases.size(), caseSetFingerprint, latestCaseCreatedAt, null, List.of(
                            VisualDiagnostic.error("visual.golden.status.uncertified",
                                    "Publication '%s' has golden cases but no certification run."
                                            .formatted(publicationId),
                                    "/certification")
                    ));
        }
        if (certificationIsStale(certification, safeCases.size(), latestCaseCreatedAt, caseSetFingerprint)) {
            return new VisualGraphGoldenCertificationStatus("", publicationId, Status.STALE, false,
                    safeCases.size(), caseSetFingerprint, latestCaseCreatedAt, certification, List.of(
                            VisualDiagnostic.error("visual.golden.status.stale",
                                    "Golden certification for publication '%s' is stale; run certify again before promotion."
                                            .formatted(publicationId),
                                    "/certification")
                    ));
        }
        if (!certification.certified()) {
            List<VisualDiagnostic> diagnostics = new ArrayList<>();
            diagnostics.add(VisualDiagnostic.error("visual.golden.status.failed",
                    "Golden certification for publication '%s' is failing and cannot be promoted."
                            .formatted(publicationId),
                    "/certification"));
            diagnostics.addAll(certification.diagnostics());
            return new VisualGraphGoldenCertificationStatus("", publicationId, Status.FAILED, false,
                    safeCases.size(), caseSetFingerprint, latestCaseCreatedAt, certification, diagnostics);
        }
        return new VisualGraphGoldenCertificationStatus("", publicationId, Status.CERTIFIED, true,
                safeCases.size(), caseSetFingerprint, latestCaseCreatedAt, certification, List.of());
    }

    private static boolean certificationIsStale(VisualGraphGoldenCertification certification,
                                                int caseCount,
                                                Instant latestCaseCreatedAt,
                                                String caseSetFingerprint) {
        if (certification.caseSetFingerprint().isBlank()) {
            return true;
        }
        if (!certification.caseSetFingerprint().equals(caseSetFingerprint)) {
            return true;
        }
        if (certification.totalCases() != caseCount) {
            return true;
        }
        return latestCaseCreatedAt != null && latestCaseCreatedAt.isAfter(certification.certifiedAt());
    }
}
