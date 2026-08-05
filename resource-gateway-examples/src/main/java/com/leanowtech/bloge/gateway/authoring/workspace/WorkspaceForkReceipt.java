package com.leanowtech.bloge.gateway.authoring.workspace;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import java.util.List;

/** Durable, payload-free coordinate closure returned by one Workspace fork. */
public record WorkspaceForkReceipt(
        String schemaVersion,
        String workspaceId,
        GraphCoordinate graphCoordinate,
        ContractCoordinate contractCoordinate,
        List<AssetCoordinate> scenarioSuiteCoordinates,
        List<AssetCoordinate> fixtureCoordinates,
        String sourceTemplateFingerprint,
        String forkedWorkspaceFingerprint,
        String runtimeProfile,
        String proofStrength,
        List<String> warnings,
        boolean replayed
) {
    /** Current receipt protocol version. */
    public static final String SCHEMA_VERSION = "bloge.workspaceForkReceipt.v1";

    public WorkspaceForkReceipt {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        workspaceId = normalized(workspaceId, "");
        scenarioSuiteCoordinates = scenarioSuiteCoordinates == null
                ? List.of() : List.copyOf(scenarioSuiteCoordinates);
        fixtureCoordinates = fixtureCoordinates == null
                ? List.of() : List.copyOf(fixtureCoordinates);
        sourceTemplateFingerprint = normalized(sourceTemplateFingerprint, "");
        forkedWorkspaceFingerprint = normalized(forkedWorkspaceFingerprint, "");
        runtimeProfile = normalized(runtimeProfile, "UNKNOWN");
        proofStrength = normalized(proofStrength, "EXPLORATORY");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** @return the same durable receipt marked as an idempotent replay */
    public WorkspaceForkReceipt asReplay() {
        return new WorkspaceForkReceipt(
                schemaVersion, workspaceId, graphCoordinate, contractCoordinate,
                scenarioSuiteCoordinates, fixtureCoordinates, sourceTemplateFingerprint,
                forkedWorkspaceFingerprint, runtimeProfile, proofStrength, warnings, true);
    }

    /** Exact retained Graph coordinate. */
    public record GraphCoordinate(String draftId, long revision, String fingerprint) {
        public GraphCoordinate {
            draftId = normalized(draftId, "");
            fingerprint = normalized(fingerprint, "");
        }
    }

    /** Exact Contract coordinate projected from the retained Graph. */
    public record ContractCoordinate(ContractDraft.Target target, String fingerprint) {
        public ContractCoordinate {
            fingerprint = normalized(fingerprint, "");
        }
    }

    /** Exact durable or inline asset coordinate. */
    public record AssetCoordinate(String kind, String id, long revision, String fingerprint) {
        public AssetCoordinate {
            kind = normalized(kind, "UNKNOWN");
            id = normalized(id, "");
            fingerprint = normalized(fingerprint, "");
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
