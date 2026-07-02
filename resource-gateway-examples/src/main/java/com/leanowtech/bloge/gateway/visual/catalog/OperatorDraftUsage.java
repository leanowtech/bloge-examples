package com.leanowtech.bloge.gateway.visual.catalog;

import java.util.List;

/**
 * Stored-draft usage of one visual operator reference.
 *
 * @param draftId draft id
 * @param revision draft revision
 * @param graphName graph name
 * @param tenantId tenant id
 * @param namespace namespace
 * @param environment authoring environment
 * @param nodeId node id
 * @param nodeLabel node label
 * @param savedFingerprint fingerprint snapshot stored on the draft node
 * @param currentFingerprint fingerprint currently exposed by the catalog
 * @param fingerprintStatus CURRENT, DRIFTED, SNAPSHOT_MISSING, or OPERATOR_MISSING
 * @param changedSurface concise description of current-vs-saved operator surface changes
 * @param changeRisk highest-risk category for the surface change
 * @param changeCategories all risk categories present in the surface change
 * @param changeSummary concise human-readable surface change summary
 */
public record OperatorDraftUsage(
        String draftId,
        long revision,
        String graphName,
        String tenantId,
        String namespace,
        String environment,
        String nodeId,
        String nodeLabel,
        String savedFingerprint,
        String currentFingerprint,
        String fingerprintStatus,
        String changedSurface,
        String changeRisk,
        List<String> changeCategories,
        String changeSummary
) {
    /**
     * Creates a draft usage item.
     */
    public OperatorDraftUsage {
        draftId = draftId == null ? "" : draftId;
        graphName = graphName == null ? "" : graphName;
        tenantId = tenantId == null ? "" : tenantId;
        namespace = namespace == null ? "" : namespace;
        environment = environment == null ? "" : environment;
        nodeId = nodeId == null ? "" : nodeId;
        nodeLabel = nodeLabel == null ? "" : nodeLabel;
        savedFingerprint = savedFingerprint == null ? "" : savedFingerprint;
        currentFingerprint = currentFingerprint == null ? "" : currentFingerprint;
        fingerprintStatus = fingerprintStatus == null || fingerprintStatus.isBlank()
                ? "UNKNOWN"
                : fingerprintStatus;
        changedSurface = changedSurface == null ? "" : changedSurface;
        changeRisk = changeRisk == null ? "" : changeRisk;
        changeCategories = changeCategories == null ? List.of() : List.copyOf(changeCategories);
        changeSummary = changeSummary == null ? "" : changeSummary;
    }
}
