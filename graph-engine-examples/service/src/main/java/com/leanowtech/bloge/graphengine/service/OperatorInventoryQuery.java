package com.leanowtech.bloge.graphengine.service;

/**
 * Query filter for the operator inventory API.
 *
 * @param pattern   glob-style operator-name pattern (defaults to {@code *})
 * @param tenantId  tenant scope filter; {@code null} to use the current bound tenant
 * @param namespace namespace scope filter; {@code null} to use the current bound namespace
 */
public record OperatorInventoryQuery(
        String pattern,
        String tenantId,
        String namespace
) {
    public OperatorInventoryQuery {
        pattern = (pattern == null || pattern.isBlank()) ? "*" : pattern;
    }
}
