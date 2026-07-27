package com.leanowtech.bloge.gateway.visual.scenario;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Exact-input validation and drift report for one Scenario draft-set revision.
 *
 * @param schemaVersion report protocol version
 * @param targetFingerprint exact target fingerprint evaluated
 * @param contractFingerprint exact Contract fingerprint evaluated
 * @param scenarioDraftSetRevision evaluated mutable revision
 * @param status VALID, INVALID, STALE, or UNKNOWN
 * @param diagnostics authoring diagnostics
 * @param compatibility compatibility findings, empty before Stage 3 analysis
 * @param impactedBindings impacted binding references
 * @param impactedScenarios impacted Scenario references
 * @param publicationImpact impacted published-asset references
 */
public record ScenarioValidationReport(
        String schemaVersion,
        String targetFingerprint,
        String contractFingerprint,
        long scenarioDraftSetRevision,
        Status status,
        List<VisualDiagnostic> diagnostics,
        List<CompatibilityFinding> compatibility,
        List<BindingRef> impactedBindings,
        List<ScenarioRef> impactedScenarios,
        List<PublishedAssetRef> publicationImpact
) {
    /** Current Scenario validation-report protocol version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioValidationReport.v1";

    /** Supported exact-input validation states. */
    public enum Status {
        VALID,
        INVALID,
        STALE,
        UNKNOWN
    }

    /** Freezes report collections and derives invalid state from blocking diagnostics. */
    public ScenarioValidationReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        targetFingerprint = targetFingerprint == null ? "" : targetFingerprint.trim();
        contractFingerprint = contractFingerprint == null ? "" : contractFingerprint.trim();
        scenarioDraftSetRevision = Math.max(0, scenarioDraftSetRevision);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        compatibility = compatibility == null ? List.of() : List.copyOf(compatibility);
        impactedBindings = impactedBindings == null ? List.of() : List.copyOf(impactedBindings);
        impactedScenarios = impactedScenarios == null ? List.of() : List.copyOf(impactedScenarios);
        publicationImpact = publicationImpact == null ? List.of() : List.copyOf(publicationImpact);
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            status = Status.INVALID;
        } else if (status == null) {
            status = Status.VALID;
        }
    }

    /** Placeholder shape for future semantic compatibility findings. */
    public record CompatibilityFinding(String code, String severity, String path, String message) {
    }

    /** Impacted graph binding coordinate. */
    public record BindingRef(String nodeId, String bindingKey, String path) {
    }

    /** Impacted Scenario coordinate. */
    public record ScenarioRef(String scenarioId, String path) {
    }

    /** Impacted immutable publication coordinate. */
    public record PublishedAssetRef(String kind, String id, long revision, String fingerprint) {
    }

    /** @return true when no blocking diagnostic is present and exact inputs are current */
    public boolean valid() {
        return status == Status.VALID && diagnostics.stream().noneMatch(VisualDiagnostic::error);
    }
}
