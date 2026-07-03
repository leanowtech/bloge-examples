package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Portable export package for one immutable visual graph publication.
 *
 * @param schemaVersion export package schema version
 * @param exportedAt export timestamp
 * @param bundleFingerprint stable fingerprint of the publication export material
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
        String bundleFingerprint,
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
        bundleFingerprint = bundleFingerprint == null || bundleFingerprint.isBlank()
                ? computedFingerprint(
                        schemaVersion,
                        sourcePublicationId,
                        sourceDraftId,
                        sourceDraftRevision,
                        sourceArtifactKind,
                        publication,
                        validation,
                        dependencyReport)
                : bundleFingerprint.trim();
    }

    /**
     * Backward-compatible constructor for callers that do not supply the derived fingerprint.
     */
    public VisualGraphPublicationExportBundle(String schemaVersion,
                                              Instant exportedAt,
                                              String sourcePublicationId,
                                              String sourceDraftId,
                                              long sourceDraftRevision,
                                              String sourceArtifactKind,
                                              VisualGraphPublication publication,
                                              VisualValidationResult validation,
                                              GraphDraftDependencyReport dependencyReport) {
        this(schemaVersion, exportedAt, "", sourcePublicationId, sourceDraftId, sourceDraftRevision,
                sourceArtifactKind, publication, validation, dependencyReport);
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
                "",
                publication == null ? "" : publication.publicationId(),
                publication == null ? "" : publication.draftId(),
                publication == null ? 0 : publication.draftRevision(),
                publication == null ? "" : publication.artifactKind(),
                publication,
                publication == null ? null : publication.validation(),
                publication == null ? null : publication.dependencyReport()
        );
    }

    /**
     * Computes the canonical fingerprint for the current normalized publication export material.
     *
     * @return expected fingerprint derived from bundle content
     */
    public String computedBundleFingerprint() {
        return computedFingerprint(
                schemaVersion,
                sourcePublicationId,
                sourceDraftId,
                sourceDraftRevision,
                sourceArtifactKind,
                publication,
                validation,
                dependencyReport);
    }

    /**
     * Checks whether the submitted fingerprint matches the current normalized material.
     *
     * @return true when the publication export fingerprint is current for this bundle body
     */
    public boolean bundleFingerprintVerified() {
        return bundleFingerprint.equals(computedBundleFingerprint());
    }

    private static String computedFingerprint(String schemaVersion,
                                              String sourcePublicationId,
                                              String sourceDraftId,
                                              long sourceDraftRevision,
                                              String sourceArtifactKind,
                                              VisualGraphPublication publication,
                                              VisualValidationResult validation,
                                              GraphDraftDependencyReport dependencyReport) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("sourcePublicationId", sourcePublicationId);
        material.put("sourceDraftId", sourceDraftId);
        material.put("sourceDraftRevision", sourceDraftRevision);
        material.put("sourceArtifactKind", sourceArtifactKind);
        material.put("publication", publication);
        material.put("validation", validation);
        material.put("dependencyReport", dependencyReport);
        return VisualBundleFingerprint.fromMaterial(material);
    }
}
