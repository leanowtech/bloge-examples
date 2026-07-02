package com.leanowtech.bloge.gateway.visual.catalog;

import java.time.Instant;
import java.util.Locale;

/**
 * Immutable audit snapshot for one operator-library registry change.
 *
 * @param schemaVersion revision record schema version
 * @param libraryId operator library id
 * @param revision monotonically increasing library-local revision
 * @param action registry action that produced this snapshot
 * @param storedAt server timestamp when the snapshot was recorded
 * @param library operator library snapshot at the time of the action
 * @param restoredFromRevision source revision when action is RESTORE
 * @param revisionMetadata audit metadata explaining the control-plane change
 */
public record OperatorLibraryRevision(
        String schemaVersion,
        String libraryId,
        long revision,
        String action,
        Instant storedAt,
        OperatorLibrary library,
        Long restoredFromRevision,
        RevisionMetadata revisionMetadata
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorLibraryRevision.v1";
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_REPLACE = "REPLACE";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_RESTORE = "RESTORE";

    /**
     * Creates a normalized revision snapshot.
     */
    public OperatorLibraryRevision {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        libraryId = libraryId == null || libraryId.isBlank()
                ? library == null ? "" : library.libraryId()
                : libraryId;
        action = action == null || action.isBlank()
                ? ACTION_REPLACE
                : action.trim().toUpperCase(Locale.ROOT);
        storedAt = storedAt == null ? Instant.EPOCH : storedAt;
        restoredFromRevision = restoredFromRevision == null || restoredFromRevision <= 0
                ? null
                : restoredFromRevision;
        revisionMetadata = (revisionMetadata == null ? RevisionMetadata.empty() : revisionMetadata)
                .storedFor(action, libraryId);
    }

    /**
     * @param library library snapshot
     * @param revision next revision number
     * @param action registry action
     * @return new server-timestamped revision snapshot
     */
    public static OperatorLibraryRevision record(OperatorLibrary library, long revision, String action) {
        return record(library, revision, action, RevisionMetadata.empty());
    }

    /**
     * @param library library snapshot
     * @param revision next revision number
     * @param action registry action
     * @param metadata audit metadata
     * @return new server-timestamped revision snapshot
     */
    public static OperatorLibraryRevision record(OperatorLibrary library,
                                                 long revision,
                                                 String action,
                                                 RevisionMetadata metadata) {
        return new OperatorLibraryRevision(SCHEMA_VERSION,
                library == null ? "" : library.libraryId(),
                revision,
                action,
                Instant.now(),
                library,
                null,
                metadata);
    }

    /**
     * @param library library snapshot being restored
     * @param revision next revision number
     * @param restoredFromRevision source revision number
     * @return new server-timestamped restore snapshot
     */
    public static OperatorLibraryRevision restore(OperatorLibrary library,
                                                  long revision,
                                                  long restoredFromRevision) {
        return restore(library, revision, restoredFromRevision, RevisionMetadata.empty());
    }

    /**
     * @param library library snapshot being restored
     * @param revision next revision number
     * @param restoredFromRevision source revision number
     * @param metadata audit metadata
     * @return new server-timestamped restore snapshot
     */
    public static OperatorLibraryRevision restore(OperatorLibrary library,
                                                  long revision,
                                                  long restoredFromRevision,
                                                  RevisionMetadata metadata) {
        return new OperatorLibraryRevision(SCHEMA_VERSION,
                library == null ? "" : library.libraryId(),
                revision,
                ACTION_RESTORE,
                Instant.now(),
                library,
                restoredFromRevision,
                metadata);
    }

    /**
     * Audit metadata supplied by the control-plane caller for one library revision.
     *
     * @param actor user or system actor that initiated the change
     * @param changeSource UI, API, import, restore, or backfill source
     * @param changeSummary concise human-readable summary
     * @param reason optional operator-facing reason for the change
     */
    public record RevisionMetadata(
            String actor,
            String changeSource,
            String changeSummary,
            String reason
    ) {
        /**
         * Creates normalized metadata.
         */
        public RevisionMetadata {
            actor = actor == null ? "" : actor.trim();
            changeSource = changeSource == null ? "" : changeSource.trim();
            changeSummary = changeSummary == null ? "" : changeSummary.trim();
            reason = reason == null ? "" : reason.trim();
        }

        public static RevisionMetadata empty() {
            return new RevisionMetadata("", "", "", "");
        }

        public static RevisionMetadata of(String actor,
                                          String changeSource,
                                          String changeSummary,
                                          String reason) {
            return new RevisionMetadata(actor, changeSource, changeSummary, reason);
        }

        RevisionMetadata storedFor(String action, String libraryId) {
            String normalizedAction = action == null || action.isBlank()
                    ? ACTION_REPLACE
                    : action.trim().toUpperCase(Locale.ROOT);
            String id = libraryId == null || libraryId.isBlank() ? "operator library" : libraryId.trim();
            return new RevisionMetadata(
                    defaultString(actor, "visual-canvas"),
                    defaultString(changeSource, "api"),
                    defaultString(changeSummary, defaultSummary(normalizedAction, id)),
                    reason
            );
        }

        private static String defaultSummary(String action, String libraryId) {
            return switch (action) {
                case ACTION_CREATE -> "Created operator library " + libraryId + ".";
                case ACTION_DELETE -> "Deleted operator library " + libraryId + ".";
                case ACTION_RESTORE -> "Restored operator library " + libraryId + ".";
                default -> "Replaced operator library " + libraryId + ".";
            };
        }

        private static String defaultString(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
