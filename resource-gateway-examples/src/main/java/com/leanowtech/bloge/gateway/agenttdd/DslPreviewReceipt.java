package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;

import java.util.List;
import java.util.Map;

/**
 * Safe authoring result plus an internal immutable projection for gate and compose promotion.
 *
 * @param authoringContext safe identity of the exact compiler context
 * @param stages ordered pipeline status ledger
 * @param technicalAcceptance ACCEPTED, REVISE, REFETCH_REFERENCE, or PLATFORM_DEFECT
 * @param projection payload-free visual projection
 * @param roundTrip semantic round-trip identity without regenerated source
 * @param authoringDiagnostics bounded safe diagnostics
 * @param diagnosticSummary diagnostic totals and truncation state
 * @param nextAction stable action for the coding Agent
 * @param authoringReceiptFingerprint content address binding source, context and result
 * @param accepted whether every blocking technical gate passed
 * @param serverProjection internal projection that is never serialized to MCP
 * @param frozenContext internal catalog snapshot used to compile and promote this receipt
 */
public record DslPreviewReceipt(
        AuthoringContext authoringContext,
        List<Stage> stages,
        String technicalAcceptance,
        Map<String, Object> projection,
        RoundTrip roundTrip,
        List<DslAuthoringDiagnostic> authoringDiagnostics,
        DiagnosticSummary diagnosticSummary,
        String nextAction,
        String authoringReceiptFingerprint,
        boolean accepted,
        @JsonIgnore DslVisualProjection serverProjection,
        @JsonIgnore DslAuthoringContext frozenContext
) {
    /** Reference identity echoed without tenant, project or catalog contents. */
    public record AuthoringContext(
            String fingerprint,
            String status,
            String languageVersion,
            String compilerProfile
    ) { }

    /** One ordered compiler phase and PASS, FAIL or NOT_RUN state. */
    public record Stage(String phase, String status) { }

    /** Round-trip semantic identities; regenerated DSL is deliberately absent. */
    public record RoundTrip(
            String status,
            String sourceSemanticFingerprint,
            String regeneratedSemanticFingerprint,
            List<String> driftKinds
    ) {
        /** Freezes drift classifications. */
        public RoundTrip {
            driftKinds = driftKinds == null ? List.of() : List.copyOf(driftKinds);
        }
    }

    /** Bounded diagnostic accounting used when the response had to truncate detail rows. */
    public record DiagnosticSummary(int total, boolean truncated, List<PhaseCount> byPhase) {
        /** Freezes phase counts. */
        public DiagnosticSummary {
            byPhase = byPhase == null ? List.of() : List.copyOf(byPhase);
        }
    }

    /** Number of emitted diagnostics in one phase. */
    public record PhaseCount(String phase, int count) { }

    /** Freezes every externally visible collection and map. */
    public DslPreviewReceipt {
        stages = stages == null ? List.of() : List.copyOf(stages);
        projection = projection == null ? Map.of() : Map.copyOf(projection);
        authoringDiagnostics = authoringDiagnostics == null ? List.of() : List.copyOf(authoringDiagnostics);
    }
}
