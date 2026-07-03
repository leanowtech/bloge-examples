package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portable export artifact for a user-provided visual operator library.
 *
 * @param schemaVersion export bundle schema version
 * @param sourceLibraryId source library id
 * @param sourceVersion source library semantic version
 * @param sourceStatus source library lifecycle status
 * @param sourceRevision latest registry revision at export time
 * @param exportedAt server timestamp for this export
 * @param bundleFingerprint stable fingerprint of the operator-library export material
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
        String bundleFingerprint,
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
        bundleFingerprint = bundleFingerprint == null || bundleFingerprint.isBlank()
                ? computedFingerprint(
                        schemaVersion,
                        sourceLibraryId,
                        sourceVersion,
                        sourceStatus,
                        sourceRevision,
                        library,
                        latestRevision,
                        validation)
                : bundleFingerprint.trim();
    }

    /**
     * Backward-compatible constructor for callers that do not supply the derived fingerprint.
     */
    public OperatorLibraryExportBundle(String schemaVersion,
                                       String sourceLibraryId,
                                       String sourceVersion,
                                       String sourceStatus,
                                       long sourceRevision,
                                       Instant exportedAt,
                                       OperatorLibrary library,
                                       OperatorLibraryRevision latestRevision,
                                       OperatorLibraryValidationResult validation) {
        this(schemaVersion, sourceLibraryId, sourceVersion, sourceStatus, sourceRevision, exportedAt, "",
                library, latestRevision, validation);
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
                "",
                library,
                latestRevision,
                validation
        );
    }

    /**
     * Computes the canonical fingerprint for the current normalized operator-library export material.
     *
     * @return expected fingerprint derived from bundle content
     */
    public String computedBundleFingerprint() {
        return computedFingerprint(
                schemaVersion,
                sourceLibraryId,
                sourceVersion,
                sourceStatus,
                sourceRevision,
                library,
                latestRevision,
                validation);
    }

    /**
     * Checks whether the submitted fingerprint matches the current normalized material.
     *
     * @return true when the operator-library export fingerprint is current for this bundle body
     */
    public boolean bundleFingerprintVerified() {
        return bundleFingerprint.equals(computedBundleFingerprint());
    }

    private static String computedFingerprint(String schemaVersion,
                                              String sourceLibraryId,
                                              String sourceVersion,
                                              String sourceStatus,
                                              long sourceRevision,
                                              OperatorLibrary library,
                                              OperatorLibraryRevision latestRevision,
                                              OperatorLibraryValidationResult validation) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("sourceLibraryId", sourceLibraryId);
        material.put("sourceVersion", sourceVersion);
        material.put("sourceStatus", sourceStatus);
        material.put("sourceRevision", sourceRevision);
        material.put("library", library);
        material.put("latestRevision", latestRevision);
        material.put("validation", validation);
        return VisualBundleFingerprint.fromMaterial(material);
    }
}
