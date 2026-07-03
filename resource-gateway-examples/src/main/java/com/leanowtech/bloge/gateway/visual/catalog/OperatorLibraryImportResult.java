package com.leanowtech.bloge.gateway.visual.catalog;

import java.time.Instant;

/**
 * Result returned after importing a portable visual operator-library bundle.
 *
 * @param schemaVersion import result schema version
 * @param imported whether the target registry stored the library
 * @param sourceBundleSchemaVersion source export bundle schema version
 * @param sourceBundleFingerprint source export bundle fingerprint
 * @param sourceLibraryId source library id from the bundle
 * @param sourceVersion source library version from the bundle
 * @param sourceStatus source library lifecycle status from the bundle
 * @param sourceRevision source registry revision from the bundle
 * @param importedAt target-environment timestamp for this import attempt
 * @param importedLibraryId target library id when known
 * @param mutationAction target registry action or intended action
 * @param library stored or rejected library snapshot
 * @param latestRevision latest target registry revision after successful import
 * @param targetDiff target-environment current library to source bundle snapshot diff
 * @param validation target-environment preflight validation used for the decision
 */
public record OperatorLibraryImportResult(
        String schemaVersion,
        boolean imported,
        String sourceBundleSchemaVersion,
        String sourceBundleFingerprint,
        String sourceLibraryId,
        String sourceVersion,
        String sourceStatus,
        long sourceRevision,
        Instant importedAt,
        String importedLibraryId,
        String mutationAction,
        OperatorLibrary library,
        OperatorLibraryRevision latestRevision,
        OperatorLibraryDiff targetDiff,
        OperatorLibraryValidationResult validation
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorLibraryImportResult.v1";
    public static final String ACTION_REJECTED = "REJECTED";

    /**
     * Creates a normalized import result.
     */
    public OperatorLibraryImportResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        sourceBundleSchemaVersion = sourceBundleSchemaVersion == null ? "" : sourceBundleSchemaVersion.trim();
        sourceBundleFingerprint = sourceBundleFingerprint == null ? "" : sourceBundleFingerprint.trim();
        sourceLibraryId = sourceLibraryId == null || sourceLibraryId.isBlank()
                ? library == null ? "" : library.libraryId()
                : sourceLibraryId.trim();
        sourceVersion = sourceVersion == null || sourceVersion.isBlank()
                ? library == null ? "" : library.version()
                : sourceVersion.trim();
        sourceStatus = sourceStatus == null || sourceStatus.isBlank()
                ? library == null ? "" : library.status()
                : sourceStatus.trim();
        sourceRevision = Math.max(0, sourceRevision);
        importedAt = importedAt == null ? Instant.EPOCH : importedAt;
        importedLibraryId = importedLibraryId == null || importedLibraryId.isBlank()
                ? library == null ? "" : library.libraryId()
                : importedLibraryId.trim();
        mutationAction = mutationAction == null || mutationAction.isBlank()
                ? imported ? OperatorLibraryRevision.ACTION_REPLACE : ACTION_REJECTED
                : mutationAction.trim().toUpperCase(java.util.Locale.ROOT);
        validation = validation == null
                ? new OperatorLibraryValidationResult(false, java.util.List.of(),
                        OperatorLibraryImpactReview.empty(), OperatorLibraryProfile.empty())
                : validation;
        imported = imported && library != null && latestRevision != null && validation.valid();
    }

    /**
     * @param bundle source export bundle
     * @param library stored target library
     * @param latestRevision latest target registry revision
     * @param validation target-environment preflight validation
     * @return successful import result
     */
    public static OperatorLibraryImportResult imported(OperatorLibraryExportBundle bundle,
                                                       OperatorLibrary library,
                                                       OperatorLibraryRevision latestRevision,
                                                       OperatorLibraryValidationResult validation) {
        return imported(bundle, library, latestRevision, validation, null);
    }

    /**
     * @param bundle source export bundle
     * @param library stored target library
     * @param latestRevision latest target registry revision
     * @param validation target-environment preflight validation
     * @param targetDiff target-environment current-to-bundle diff
     * @return successful import result
     */
    public static OperatorLibraryImportResult imported(OperatorLibraryExportBundle bundle,
                                                       OperatorLibrary library,
                                                       OperatorLibraryRevision latestRevision,
                                                       OperatorLibraryValidationResult validation,
                                                       OperatorLibraryDiff targetDiff) {
        return from(bundle, true, library, latestRevision,
                latestRevision == null ? OperatorLibraryRevision.ACTION_REPLACE : latestRevision.action(),
                validation, targetDiff);
    }

    /**
     * @param bundle source export bundle
     * @param library library snapshot under target-environment review
     * @param mutationAction intended target registry action
     * @param validation target-environment diagnostics
     * @return non-stored preview result
     */
    public static OperatorLibraryImportResult previewed(OperatorLibraryExportBundle bundle,
                                                        OperatorLibrary library,
                                                        String mutationAction,
                                                        OperatorLibraryValidationResult validation) {
        return previewed(bundle, library, mutationAction, validation, null);
    }

    /**
     * @param bundle source export bundle
     * @param library library snapshot under target-environment review
     * @param mutationAction intended target registry action
     * @param validation target-environment diagnostics
     * @param targetDiff target-environment current-to-bundle diff
     * @return non-stored preview result
     */
    public static OperatorLibraryImportResult previewed(OperatorLibraryExportBundle bundle,
                                                        OperatorLibrary library,
                                                        String mutationAction,
                                                        OperatorLibraryValidationResult validation,
                                                        OperatorLibraryDiff targetDiff) {
        return from(bundle, false, library, null, mutationAction, validation, targetDiff);
    }

    /**
     * @param bundle source export bundle
     * @param library library snapshot under review
     * @param mutationAction intended target registry action
     * @param validation target-environment diagnostics
     * @return non-stored import result
     */
    public static OperatorLibraryImportResult rejected(OperatorLibraryExportBundle bundle,
                                                       OperatorLibrary library,
                                                       String mutationAction,
                                                       OperatorLibraryValidationResult validation) {
        return rejected(bundle, library, mutationAction, validation, null);
    }

    /**
     * @param bundle source export bundle
     * @param library library snapshot under review
     * @param mutationAction intended target registry action
     * @param validation target-environment diagnostics
     * @param targetDiff target-environment current-to-bundle diff
     * @return non-stored import result
     */
    public static OperatorLibraryImportResult rejected(OperatorLibraryExportBundle bundle,
                                                       OperatorLibrary library,
                                                       String mutationAction,
                                                       OperatorLibraryValidationResult validation,
                                                       OperatorLibraryDiff targetDiff) {
        return from(bundle, false, library, null, mutationAction, validation, targetDiff);
    }

    /**
     * @param bundle source export bundle
     * @param validation target-environment diagnostics
     * @return rejected result when the bundle has no usable library snapshot
     */
    public static OperatorLibraryImportResult rejected(OperatorLibraryExportBundle bundle,
                                                       OperatorLibraryValidationResult validation) {
        return from(bundle, false, null, null, ACTION_REJECTED, validation, null);
    }

    private static OperatorLibraryImportResult from(OperatorLibraryExportBundle bundle,
                                                    boolean imported,
                                                    OperatorLibrary library,
                                                    OperatorLibraryRevision latestRevision,
                                                    String mutationAction,
                                                    OperatorLibraryValidationResult validation,
                                                    OperatorLibraryDiff targetDiff) {
        OperatorLibraryExportBundle safeBundle = bundle == null
                ? new OperatorLibraryExportBundle("", "", "", "", 0, null, "", library, null, null)
                : bundle;
        return new OperatorLibraryImportResult(
                SCHEMA_VERSION,
                imported,
                safeBundle.schemaVersion(),
                safeBundle.bundleFingerprint(),
                safeBundle.sourceLibraryId(),
                safeBundle.sourceVersion(),
                safeBundle.sourceStatus(),
                safeBundle.sourceRevision(),
                Instant.now(),
                library == null ? "" : library.libraryId(),
                mutationAction,
                library,
                latestRevision,
                targetDiff,
                validation
        );
    }
}
