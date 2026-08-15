package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet.AssertionDraft;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;

import java.util.List;

/** Non-authoritative v1-to-v2 migration preview with complete loss diagnostics. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LegacyScenarioV1MigrationPreview(
        String schemaVersion,
        ExactAssetRef legacySourceRef,
        ScenarioDraftSetV2 proposedDraftSet,
        List<LegacyAssertionProposal> assertionProposals,
        List<MigrationDiagnostic> diagnostics,
        boolean reviewRequired
) {
    public static final String SCHEMA_VERSION = "bloge.scenarioV1MigrationPreview.v1";

    public enum DiagnosticSeverity { INFO, WARNING, BLOCKER }

    public LegacyScenarioV1MigrationPreview {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Scenario migration schemaVersion");
        }
        if (legacySourceRef == null || proposedDraftSet == null
                || !"SCENARIO_DRAFT_SET_V1".equals(legacySourceRef.kind())) {
            throw new IllegalArgumentException("Exact legacy Scenario migration source is required");
        }
        assertionProposals = assertionProposals == null ? List.of()
                : List.copyOf(assertionProposals);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (!reviewRequired) {
            throw new IllegalArgumentException(
                    "Legacy Scenario migration must remain human-reviewed");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LegacyAssertionProposal(
            String scenarioId,
            List<AssertionDraft> assertions
    ) {
        public LegacyAssertionProposal {
            scenarioId = required(scenarioId, "scenarioId");
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
            if (assertions.isEmpty()) {
                throw new IllegalArgumentException(
                        "Legacy Assertion proposal requires source assertions");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MigrationDiagnostic(
            DiagnosticSeverity severity,
            String code,
            String scenarioId,
            String dependencyId
    ) {
        public MigrationDiagnostic {
            if (severity == null) throw new IllegalArgumentException("severity is required");
            code = required(code, "code");
            scenarioId = scenarioId == null ? "" : scenarioId.trim();
            dependencyId = dependencyId == null ? "" : dependencyId.trim();
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
