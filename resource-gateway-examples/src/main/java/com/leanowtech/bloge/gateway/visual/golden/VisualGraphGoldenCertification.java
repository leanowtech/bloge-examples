package com.leanowtech.bloge.gateway.visual.golden;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.time.Instant;
import java.util.List;

/**
 * Latest golden regression certification for one immutable visual graph publication.
 *
 * @param schemaVersion certification schema version
 * @param publicationId immutable publication id
 * @param certified whether the latest suite run passed
 * @param totalCases total golden cases evaluated
 * @param passedCases passing golden cases
 * @param failedCases failing golden cases
 * @param runIds run-history ids created by the suite
 * @param diagnostics suite-level diagnostics
 * @param certifiedAt certification timestamp
 */
public record VisualGraphGoldenCertification(
        String schemaVersion,
        String publicationId,
        boolean certified,
        int totalCases,
        int passedCases,
        int failedCases,
        List<String> runIds,
        List<VisualDiagnostic> diagnostics,
        Instant certifiedAt
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphGoldenCertification.v1";

    /**
     * Creates a certification record.
     */
    public VisualGraphGoldenCertification {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        publicationId = publicationId == null ? "" : publicationId;
        totalCases = Math.max(0, totalCases);
        passedCases = Math.max(0, passedCases);
        failedCases = Math.max(0, failedCases);
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        certifiedAt = certifiedAt == null ? Instant.now() : certifiedAt;
    }

    /**
     * Builds a certification from a suite run.
     *
     * @param suite suite run result
     * @return certification record
     */
    public static VisualGraphGoldenCertification from(VisualGraphGoldenSuiteRunResult suite) {
        List<String> runIds = suite.results().stream()
                .map(result -> result.run().runId())
                .filter(runId -> runId != null && !runId.isBlank())
                .toList();
        return new VisualGraphGoldenCertification(
                "",
                suite.publicationId(),
                suite.passed(),
                suite.totalCases(),
                suite.passedCases(),
                suite.failedCases(),
                runIds,
                suite.diagnostics(),
                null
        );
    }
}
