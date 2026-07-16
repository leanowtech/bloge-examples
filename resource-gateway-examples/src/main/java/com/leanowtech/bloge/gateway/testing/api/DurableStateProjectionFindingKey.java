package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload-free identity of one durable BLOGE projection finding.
 *
 * @param entityType {@code EXECUTION} or {@code WORK_ITEM}
 * @param rowId internal authority row identity
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DurableStateProjectionFindingKey(String entityType, String rowId) {
    /** Normalizes protocol text while leaving semantic validation to the service. */
    public DurableStateProjectionFindingKey {
        entityType = normalized(entityType);
        rowId = normalized(rowId);
    }

    /**
     * Rejects future or caller-owned key fields instead of silently weakening identity matching.
     *
     * @param field unknown JSON field
     * @param value ignored caller value
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unknown projection finding key field: " + field);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
