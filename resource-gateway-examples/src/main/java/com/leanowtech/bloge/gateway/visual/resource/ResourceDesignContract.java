package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        status = status == null || status.isBlank() ? "ACTIVE" : status;
    }
}
