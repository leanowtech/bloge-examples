package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;

/**
 * One graph version's reference to an operator, used inside
 * {@link OperatorUsageSummary} to describe where an operator is consumed.
 *
 * @param definitionKey business-facing definition key
 * @param definitionId  internal definition identifier
 * @param version       semantic version string
 * @param versionId     internal version identifier
 * @param status        version lifecycle status at the time the inventory was computed
 */
public record OperatorUsageReference(
        String definitionKey,
        String definitionId,
        String version,
        String versionId,
        GraphVersionStatus status
) {
    public OperatorUsageReference {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey must not be blank");
        }
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }
}
