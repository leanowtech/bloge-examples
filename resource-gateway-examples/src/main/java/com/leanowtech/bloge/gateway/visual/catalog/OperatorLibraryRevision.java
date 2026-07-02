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
 */
public record OperatorLibraryRevision(
        String schemaVersion,
        String libraryId,
        long revision,
        String action,
        Instant storedAt,
        OperatorLibrary library,
        Long restoredFromRevision
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
    }

    /**
     * @param library library snapshot
     * @param revision next revision number
     * @param action registry action
     * @return new server-timestamped revision snapshot
     */
    public static OperatorLibraryRevision record(OperatorLibrary library, long revision, String action) {
        return new OperatorLibraryRevision(SCHEMA_VERSION,
                library == null ? "" : library.libraryId(),
                revision,
                action,
                Instant.now(),
                library,
                null);
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
        return new OperatorLibraryRevision(SCHEMA_VERSION,
                library == null ? "" : library.libraryId(),
                revision,
                ACTION_RESTORE,
                Instant.now(),
                library,
                restoredFromRevision);
    }
}
