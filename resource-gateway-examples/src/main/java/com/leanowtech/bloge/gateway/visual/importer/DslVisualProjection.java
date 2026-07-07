package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;

/**
 * Visual projection produced from BLOGE DSL.
 *
 * @param schemaVersion response contract version
 * @param sourceId source id used in source maps and diagnostics
 * @param draft editable visual graph draft
 * @param sourceMap mapping from visual artifacts to DSL source locations
 * @param coverage import coverage summary
 * @param roundTrip round-trip readiness summary
 * @param diagnostics diagnostics produced during projection
 */
public record DslVisualProjection(
        String schemaVersion,
        String sourceId,
        GraphDraft draft,
        DslSourceMap sourceMap,
        DslImportCoverage coverage,
        DslRoundTripSummary roundTrip,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.dslVisualProjection.v1";

    /**
     * Creates a normalized projection response.
     */
    public DslVisualProjection {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        sourceId = sourceId == null ? "" : sourceId;
        sourceMap = sourceMap == null ? DslSourceMap.empty() : sourceMap;
        coverage = coverage == null ? DslImportCoverage.empty() : coverage;
        roundTrip = roundTrip == null ? DslRoundTripSummary.notAssessed() : roundTrip;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
