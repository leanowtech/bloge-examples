package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.util.List;

/**
 * Stable Tool Studio import projection of one graph draft snapshot.
 */
public record GraphDraftIntegrationBundle(
        String schemaVersion,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String draftFingerprint,
        GraphDraft draft,
        List<OperatorDefinition> operatorSnapshots,
        GraphDraftDependencyProfile dependencyProfile,
        VisualValidationResult validation
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.graphDraftIntegrationBundle.v1";

    public GraphDraftIntegrationBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        tenantId = tenantId == null ? "" : tenantId;
        organizationId = organizationId == null ? "" : organizationId;
        projectId = projectId == null ? "" : projectId;
        environmentId = environmentId == null ? "" : environmentId;
        draftFingerprint = draftFingerprint == null ? "" : draftFingerprint;
        operatorSnapshots = operatorSnapshots == null ? List.of() : List.copyOf(operatorSnapshots);
    }
}
