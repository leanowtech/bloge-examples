package com.leanowtech.bloge.gateway.visual.catalog;

/**
 * Immutable-publication usage of one visual operator reference.
 *
 * @param publicationId publication id
 * @param draftId source draft id
 * @param draftRevision source draft revision
 * @param graphName graph name
 * @param tenantId tenant id
 * @param namespace namespace
 * @param environment authoring environment
 * @param nodeId node id
 * @param nodeLabel node label
 * @param frozenFingerprint fingerprint frozen in the publication
 * @param currentFingerprint fingerprint currently exposed by the catalog
 * @param fingerprintStatus CURRENT, DRIFTED, SNAPSHOT_MISSING, or OPERATOR_MISSING
 * @param changedSurface concise description of current-vs-frozen operator surface changes
 */
public record OperatorPublicationUsage(
        String publicationId,
        String draftId,
        long draftRevision,
        String graphName,
        String tenantId,
        String namespace,
        String environment,
        String nodeId,
        String nodeLabel,
        String frozenFingerprint,
        String currentFingerprint,
        String fingerprintStatus,
        String changedSurface
) {
    /**
     * Creates a publication usage item.
     */
    public OperatorPublicationUsage {
        publicationId = publicationId == null ? "" : publicationId;
        draftId = draftId == null ? "" : draftId;
        graphName = graphName == null ? "" : graphName;
        tenantId = tenantId == null ? "" : tenantId;
        namespace = namespace == null ? "" : namespace;
        environment = environment == null ? "" : environment;
        nodeId = nodeId == null ? "" : nodeId;
        nodeLabel = nodeLabel == null ? "" : nodeLabel;
        frozenFingerprint = frozenFingerprint == null ? "" : frozenFingerprint;
        currentFingerprint = currentFingerprint == null ? "" : currentFingerprint;
        fingerprintStatus = fingerprintStatus == null || fingerprintStatus.isBlank()
                ? "UNKNOWN"
                : fingerprintStatus;
        changedSurface = changedSurface == null ? "" : changedSurface;
    }
}
