package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Lightweight identifying projection of a {@link GraphVersion} used as one
 * side of a {@link GraphVersionDiff}.
 *
 * @param versionId     internal version identifier
 * @param version       semantic version string
 * @param status        lifecycle status at the time of comparison
 * @param contentHash   SHA-256 hash of the DSL source
 * @param executionMode detected execution mode from version metadata
 * @param createdAt         creation timestamp
 * @param publishedAt       publication timestamp, or {@code null} when unpublished
 * @param runtimeName       derived runtime artifact name from validation, or blank when not computed
 * @param declaredRootName  original DSL root name from validation, or blank when not computed
 * @param compiledArtifactRef compiled artifact reference from validation when available
 * @param valid             whether validation produced no blocking diagnostics
 * @param errorCount        number of error diagnostics seen during validation
 * @param warningCount      number of warning diagnostics seen during validation
 */
public record VersionSummary(
        String versionId,
        String version,
        GraphVersionStatus status,
        String contentHash,
        GraphExecutionMode executionMode,
        Instant createdAt,
        Instant publishedAt,
        String runtimeName,
        String declaredRootName,
        String compiledArtifactRef,
        boolean valid,
        int errorCount,
        int warningCount
) {
    public VersionSummary {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        status = Objects.requireNonNullElse(status, GraphVersionStatus.DRAFT);
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
        executionMode = Objects.requireNonNullElse(executionMode, GraphExecutionMode.GRAPH);
        runtimeName = runtimeName == null ? "" : runtimeName;
        declaredRootName = declaredRootName == null ? "" : declaredRootName;
        errorCount = Math.max(0, errorCount);
        warningCount = Math.max(0, warningCount);
    }

    /**
     * Creates a summary projection from a full version record.
     *
     * @param version source version
     * @return lightweight summary
     */
    public static VersionSummary from(GraphVersion version) {
        Objects.requireNonNull(version, "version");
        return new VersionSummary(
                version.versionId(),
                version.version(),
                version.status(),
                version.contentHash(),
                version.metadata().executionMode(),
                version.createdAt(),
                version.publishedAt(),
                "",
                "",
                version.compiledArtifactRef(),
                false,
                0,
                0
        );
    }

    /**
     * Creates a summary projection from a full version record and its latest
     * validation result.
     *
     * @param version source version
     * @param validation validation result used to enrich the summary
     * @return lightweight summary with validation counts
     */
    public static VersionSummary from(GraphVersion version, VersionValidationResult validation) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(validation, "validation");
        int errorCount = (int) validation.diagnostics().stream()
                .filter(GraphEngineDiagnostic::error)
                .count();
        int warningCount = (int) validation.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == GraphEngineDiagnostic.Severity.WARNING)
                .count();
        return new VersionSummary(
                version.versionId(),
                version.version(),
                version.status(),
                version.contentHash(),
                validation.executionMode(),
                version.createdAt(),
                version.publishedAt(),
                validation.runtimeName(),
                validation.declaredRootName(),
                validation.compiledArtifactRef(),
                validation.valid(),
                errorCount,
                warningCount
        );
    }
}
