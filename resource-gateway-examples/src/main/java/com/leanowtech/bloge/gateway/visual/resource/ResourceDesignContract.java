package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Authoring contract that turns a resource descriptor into a schema-aware visual operator.
 *
 * @param contractId unique contract identifier
 * @param resourceId descriptor id that this contract describes
 * @param displayName palette/display label
 * @param description authoring description
 * @param tags search/filter tags
 * @param requestSchema visual input schema
 * @param responseSchema visual output payload schema
 * @param examples sample request payloads
 * @param status lifecycle status such as ACTIVE or DEPRECATED
 */
public record ResourceDesignContract(
        String contractId,
        String resourceId,
        String displayName,
        String description,
        List<String> tags,
        SchemaEnvelope requestSchema,
        SchemaEnvelope responseSchema,
        Map<String, Object> examples,
        String status
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
     * Creates a design contract.
     */
    public ResourceDesignContract {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
        contractId = contractId == null || contractId.isBlank()
                ? "contract:" + resourceId
                : contractId;
        displayName = displayName == null || displayName.isBlank() ? resourceId : displayName;
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        requestSchema = requestSchema == null ? SchemaEnvelope.opaque() : requestSchema;
        responseSchema = responseSchema == null ? SchemaEnvelope.opaque() : responseSchema;
        examples = examples == null ? Map.of() : new LinkedHashMap<>(examples);
        status = normalizeStatus(status);
    }

    /**
     * @param status raw lifecycle status
     * @return true when the status is part of the supported resource contract lifecycle
     */
    public static boolean isSupportedStatus(String status) {
        return SUPPORTED_STATUSES.contains(normalizeStatus(status));
    }

    /**
     * @return true when this contract should project a resource operator into the catalog
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
