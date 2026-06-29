package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.LinkedHashSet;
import java.util.List;
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
    /**
     * Creates a library.
     */
    public OperatorLibrary {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "bloge.visualOperatorLibrary.v1"
                : schemaVersion;
        if (libraryId == null || libraryId.isBlank()) {
            throw new IllegalArgumentException("libraryId must not be blank");
        }
        displayName = displayName == null || displayName.isBlank() ? libraryId : displayName;
        version = version == null || version.isBlank() ? "1.0.0" : version;
        owner = owner == null ? "" : owner;
        status = status == null || status.isBlank() ? "ACTIVE" : status;
        operators = operators == null ? List.of() : List.copyOf(operators);
        validateUniqueOperatorRefs(operators);
    }

    private static void validateUniqueOperatorRefs(List<OperatorDefinition> operators) {
        Set<String> seen = new LinkedHashSet<>();
        for (OperatorDefinition operator : operators) {
            if (!seen.add(operator.operatorRef())) {
                throw new IllegalArgumentException("Duplicate operatorRef in library: " + operator.operatorRef());
            }
        }
    }
}
