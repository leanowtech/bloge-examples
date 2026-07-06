package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.ArrayList;
import java.util.Collections;
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
 * @param builtInFunctions BLOGE expression functions exposed to visual expression editors
 * @param operators operators contributed by this library
 */
public record OperatorLibrary(
        String schemaVersion,
        String libraryId,
        String displayName,
        String version,
        String owner,
        String status,
        List<BuiltInFunction> builtInFunctions,
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
     * Backward-compatible constructor for libraries that only contribute operators.
     */
    public OperatorLibrary(String schemaVersion,
                           String libraryId,
                           String displayName,
                           String version,
                           String owner,
                           String status,
                           List<OperatorDefinition> operators) {
        this(schemaVersion, libraryId, displayName, version, owner, status, List.of(), operators);
    }

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
        builtInFunctions = nullableElementsCopy(builtInFunctions);
        operators = nullableElementsCopy(operators);
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

    private static <T> List<T> nullableElementsCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * BLOGE expression function that can be referenced from transform/branch/config expressions.
     *
     * @param name callable function name, for example {@code coalesce} or {@code string.trim}
     * @param namespace optional owner namespace used for governance and display
     * @param displayName human friendly label
     * @param description short authoring help text
     * @param category UI grouping hint
     * @param signatures callable signatures
     * @param examples expression snippets
     */
    public record BuiltInFunction(
            String name,
            String namespace,
            String displayName,
            String description,
            String category,
            List<Signature> signatures,
            List<String> examples
    ) {
        public BuiltInFunction {
            name = name == null ? "" : name.trim();
            namespace = namespace == null ? "" : namespace.trim();
            displayName = displayName == null || displayName.isBlank() ? name : displayName;
            description = description == null ? "" : description;
            category = category == null ? "" : category;
            signatures = nullableElementsCopy(signatures);
            examples = nullableElementsCopy(examples);
        }
    }

    /**
     * One callable function overload.
     *
     * @param label display signature such as {@code coalesce(value, fallback)}
     * @param description overload-specific help text
     * @param parameters ordered parameter list
     * @param returns return value contract
     */
    public record Signature(
            String label,
            String description,
            List<Parameter> parameters,
            ReturnValue returns
    ) {
        public Signature {
            label = label == null ? "" : label.trim();
            description = description == null ? "" : description;
            parameters = nullableElementsCopy(parameters);
            returns = returns == null ? ReturnValue.any() : returns;
        }
    }

    /**
     * Function parameter contract.
     *
     * @param name parameter name
     * @param type compact type label
     * @param schema optional JSON Schema envelope for stricter tooling
     * @param optional whether the argument can be omitted
     * @param variadic whether this parameter consumes the rest of the argument list
     * @param description parameter help text
     */
    public record Parameter(
            String name,
            String type,
            com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope schema,
            boolean optional,
            boolean variadic,
            String description
    ) {
        public Parameter {
            name = name == null ? "" : name.trim();
            type = normalizeFunctionType(type);
            description = description == null ? "" : description;
        }
    }

    /**
     * Function return value contract.
     *
     * @param type compact type label
     * @param schema optional JSON Schema envelope for stricter tooling
     * @param description return value help text
     */
    public record ReturnValue(
            String type,
            com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope schema,
            String description
    ) {
        public ReturnValue {
            type = normalizeFunctionType(type);
            description = description == null ? "" : description;
        }

        public static ReturnValue any() {
            return new ReturnValue("any", null, "");
        }
    }

    private static String normalizeFunctionType(String value) {
        return value == null || value.isBlank() ? "any" : value.trim().toLowerCase(Locale.ROOT);
    }
}
