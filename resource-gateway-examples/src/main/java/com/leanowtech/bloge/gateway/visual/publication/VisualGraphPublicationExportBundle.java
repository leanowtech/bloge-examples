package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.time.Instant;

/**
 * Portable export package for one immutable visual graph publication.
 *
 * @param schemaVersion export package schema version
 * @param exportedAt export timestamp
 * @param sourcePublicationId original publication id
 * @param sourceDraftId original source draft id
 * @param sourceDraftRevision original source draft revision
 * @param sourceArtifactKind original artifact kind
 * @param publication immutable publication snapshot
 * @param validation frozen publish-time validation/readiness snapshot
 * @param dependencyReport frozen publish-time dependency report
 */
public record VisualGraphPublicationExportBundle(
        String schemaVersion,
        Instant exportedAt,
        String sourcePublicationId,
        String sourceDraftId,
        long sourceDraftRevision,
        String sourceArtifactKind,
        VisualGraphPublication publication,
        VisualValidationResult validation,
        GraphDraftDependencyReport dependencyReport
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphPublicationExport.v1";

    /**
     * Creates a normalized publication export bundle.
     */
    public VisualGraphPublicationExportBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        exportedAt = exportedAt == null ? Instant.now() : exportedAt;
        sourcePublicationId = sourcePublicationId == null || sourcePublicationId.isBlank()
                ? publication == null ? "" : publication.publicationId()
                : sourcePublicationId.trim();
        sourceDraftId = sourceDraftId == null || sourceDraftId.isBlank()
                ? publication == null ? "" : publication.draftId()
                : sourceDraftId.trim();
        sourceDraftRevision = sourceDraftRevision > 0
                ? sourceDraftRevision
                : publication == null ? 0 : publication.draftRevision();
        sourceArtifactKind = sourceArtifactKind == null || sourceArtifactKind.isBlank()
                ? publication == null ? "" : publication.artifactKind()
                : sourceArtifactKind.trim().toUpperCase(java.util.Locale.ROOT);
        validation = validation == null && publication != null ? publication.validation() : validation;
        dependencyReport = dependencyReport == null && publication != null
                ? publication.dependencyReport()
                : dependencyReport;
        dependencyReport = dependencyReport == null ? GraphDraftDependencyReport.empty() : dependencyReport;
    }

    /**
     * Creates a portable bundle from a stored immutable publication.
     *
     * @param publication publication snapshot
     * @return portable publication export bundle
     */
    public static VisualGraphPublicationExportBundle from(VisualGraphPublication publication) {
        return new VisualGraphPublicationExportBundle(
                SCHEMA_VERSION,
                Instant.now(),
                publication == null ? "" : publication.publicationId(),
                publication == null ? "" : publication.draftId(),
                publication == null ? 0 : publication.draftRevision(),
                publication == null ? "" : publication.artifactKind(),
                publication,
                publication == null ? null : publication.validation(),
                publication == null ? null : publication.dependencyReport()
        );
    }
}
