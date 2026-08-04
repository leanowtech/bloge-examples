package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;

/** Immutable command for one server-authoritative Scenario table run. */
public record TableSuiteRunCommand(
        String schemaVersion,
        String requestId,
        GraphDraft graphDraft,
        ContractDraft contract,
        ScenarioDraftSet draftSet,
        Selection selection,
        Preflight preflight,
        String baselineBatchId
) {
    /** Current command protocol version. */
    public static final String SCHEMA_VERSION = "bloge.tableSuiteRunCommand.v1";

    /** Normalizes transport strings and freezes requested ids. */
    public TableSuiteRunCommand {
        schemaVersion = normalized(schemaVersion);
        requestId = normalized(requestId);
        selection = selection == null ? new Selection(null, List.of()) : selection;
        preflight = preflight == null ? new Preflight("", null, null, 0, 0, 0, 0) : preflight;
        baselineBatchId = normalized(baselineBatchId);
    }

    /** Predicate resolved by the server to an immutable ordered closure. */
    public record Selection(SelectionMode mode, List<String> caseIds) {
        public Selection {
            caseIds = caseIds == null ? List.of() : caseIds.stream()
                    .map(TableSuiteRunCommand::normalized)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        }
    }

    /** Supported server-side selection predicates. */
    public enum SelectionMode {
        ALL,
        SELECTED,
        FAILED,
        CHANGED,
        AFFECTED
    }

    /** Explicit safety envelope checked before queue admission. */
    public record Preflight(
            String environment,
            DependencyMode dependencyMode,
            EffectProfile effectProfile,
            int maxCases,
            int maxFailures,
            int maxConcurrency,
            long caseTimeoutMs
    ) {
        public Preflight {
            environment = normalized(environment);
        }
    }

    /** This authoring batch intentionally supports simulation only. */
    public enum DependencyMode {
        SIMULATED
    }

    /** This authoring batch is structurally side-effect free. */
    public enum EffectProfile {
        SIDE_EFFECT_FREE
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
