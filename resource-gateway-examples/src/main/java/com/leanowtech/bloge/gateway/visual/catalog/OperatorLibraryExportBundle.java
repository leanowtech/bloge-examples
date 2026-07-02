package com.leanowtech.bloge.gateway.visual.catalog;

import java.time.Instant;
import java.util.List;

/**
 * Portable export artifact for a user-provided visual operator library.
 *
 * @param schemaVersion export bundle schema version
 * @param sourceLibraryId source library id
 * @param sourceVersion source library semantic version
 * @param sourceStatus source library lifecycle status
 * @param sourceRevision latest registry revision at export time
 * @param exportedAt server timestamp for this export
 * @param library current operator library snapshot
 * @param latestRevision latest immutable registry revision snapshot
 * @param validation export-time validation/profile/impact result
 */
public record OperatorLibraryExportBundle(
        String schemaVersion,
        String sourceLibraryId,
        String sourceVersion,
        String sourceStatus,
        long sourceRevision,
        Instant exportedAt,
        OperatorLibrary library,
        OperatorLibraryRevision latestRevision,
        OperatorLibraryValidationResult validation
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorLibraryExport.v1";

    /**
     * Creates a normalized export bundle.
     */
    public OperatorLibraryExportBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        sourceLibraryId = sourceLibraryId == null || sourceLibraryId.isBlank()
                ? library == null ? "" : library.libraryId()
                : sourceLibraryId;
        sourceVersion = sourceVersion == null || sourceVersion.isBlank()
                ? library == null ? "" : library.version()
                : sourceVersion;
        sourceStatus = sourceStatus == null || sourceStatus.isBlank()
                ? library == null ? "" : library.status()
                : sourceStatus;
        sourceRevision = Math.max(0, sourceRevision);
        exportedAt = exportedAt == null ? Instant.EPOCH : exportedAt;
        validation = validation == null
                ? new OperatorLibraryValidationResult(true, List.of(),
                        OperatorLibraryImpactReview.empty(), OperatorLibraryProfile.empty())
                : validation;
    }

    /**
     * Builds an export bundle from current registry state.
     *
     * @param library current library snapshot
     * @param latestRevision latest immutable revision snapshot
     * @param validation export-time validation result
     * @return portable export bundle
     */
    public static OperatorLibraryExportBundle from(OperatorLibrary library,
                                                   OperatorLibraryRevision latestRevision,
                                                   OperatorLibraryValidationResult validation) {
        return new OperatorLibraryExportBundle(
                SCHEMA_VERSION,
                library == null ? "" : library.libraryId(),
                library == null ? "" : library.version(),
                library == null ? "" : library.status(),
                latestRevision == null ? 0 : latestRevision.revision(),
                Instant.now(),
                library,
                latestRevision,
                validation
        );
    }
}
