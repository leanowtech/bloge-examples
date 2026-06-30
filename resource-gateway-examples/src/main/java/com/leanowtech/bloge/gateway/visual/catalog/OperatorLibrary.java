package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * User-provided visual operator library.
 *
 * @param schemaVersion library schema version
 * @param libraryId stable library id
 * @param displayName display name
 * @param version library version
 * @param owner owner or publishing team
 * @param status lifecycle status such as ACTIVE or DEPRECATED
 * @param operators operators contributed by this library
 */
public record OperatorLibrary(
        String schemaVersion,
        String libraryId,
        String displayName,
        String version,
        String owner,
        String status,
        List<OperatorDefinition> operators
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPRECATED = "DEPRECATED";
    public static final String STATUS_DISABLED = "DISABLED";

    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            STATUS_ACTIVE,
            STATUS_DEPRECATED,
            STATUS_DISABLED
    );

    /**
     * Creates a library.
     */
    public OperatorLibrary {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "bloge.visualOperatorLibrary.v1"
                : schemaVersion;
        libraryId = libraryId == null ? "" : libraryId;
        displayName = displayName == null || displayName.isBlank() ? libraryId : displayName;
        version = version == null || version.isBlank() ? "1.0.0" : version;
        owner = owner == null ? "" : owner;
        status = normalizeStatus(status);
        operators = operators == null ? List.of() : List.copyOf(operators);
    }

    /**
     * @param status raw lifecycle status
     * @return true when the status is part of the supported lifecycle contract
     */
    public static boolean isSupportedStatus(String status) {
        return SUPPORTED_STATUSES.contains(normalizeStatus(status));
    }

    /**
     * @return true when the library contributes operators to the public authoring catalog
     */
    public boolean visibleInCatalog(boolean includeDeprecated) {
        return STATUS_ACTIVE.equals(status)
                || includeDeprecated && STATUS_DEPRECATED.equals(status);
    }

    private static String normalizeStatus(String value) {
        return value == null || value.isBlank()
                ? STATUS_ACTIVE
                : value.trim().toUpperCase(Locale.ROOT);
    }
}
