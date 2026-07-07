package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Server-authoritative gate for deciding whether generated DSL may overwrite a source DSL file.
 *
 * <p>This result is intentionally separate from preview: preview answers whether the DSL can be
 * rendered as an editable graph, while the rewrite gate answers whether generated DSL has enough
 * semantic evidence to be used as an automatic source replacement.</p>
 *
 * @param schemaVersion response schema version
 * @param sourceId source DSL id
 * @param allowed whether automatic source rewrite is allowed
 * @param decision machine-readable gate decision
 * @param message human-readable gate explanation
 * @param generatedDsl generated DSL considered by the gate
 * @param roundTrip semantic round-trip evidence from preview
 * @param diagnostics import or round-trip diagnostics that explain blocked decisions
 */
public record DslRewriteGateResult(
        String schemaVersion,
        String sourceId,
        boolean allowed,
        String decision,
        String message,
        String generatedDsl,
        DslRoundTripSummary roundTrip,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.dslRewriteGate.v1";

    /**
     * Creates a normalized gate response.
     */
    public DslRewriteGateResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        sourceId = sourceId == null ? "" : sourceId;
        decision = decision == null || decision.isBlank() ? "BLOCK_NOT_ASSESSED" : decision;
        message = message == null ? "" : message;
        generatedDsl = generatedDsl == null ? "" : generatedDsl;
        roundTrip = roundTrip == null ? DslRoundTripSummary.notAssessed() : roundTrip;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Builds a rewrite gate decision from the preview projection.
     *
     * @param projection preview projection carrying diagnostics and round-trip evidence
     * @return rewrite gate result
     */
    public static DslRewriteGateResult from(DslVisualProjection projection) {
        if (projection == null) {
            return new DslRewriteGateResult(SCHEMA_VERSION, "", false, "BLOCK_IMPORT_DIAGNOSTICS",
                    "DSL rewrite is blocked because no preview projection was produced.",
                    "", DslRoundTripSummary.notAssessed(), List.of());
        }
        List<VisualDiagnostic> diagnostics = projection.diagnostics();
        DslRoundTripSummary roundTrip = projection.roundTrip();
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return blocked(projection, "BLOCK_IMPORT_DIAGNOSTICS",
                    "DSL rewrite is blocked because the import projection still has blocking diagnostics.");
        }
        if (roundTrip.supported()) {
            return new DslRewriteGateResult(SCHEMA_VERSION, projection.sourceId(), true, "ALLOW_REWRITE",
                    "Generated DSL has the same canonical visual semantics as the source projection.",
                    roundTrip.generatedDsl(), roundTrip, diagnostics);
        }
        return switch (roundTrip.status()) {
            case "DRIFT" -> blocked(projection, "BLOCK_SEMANTIC_DRIFT",
                    "DSL rewrite is blocked because generated DSL semantics drift from the source projection.");
            case "PARTIAL" -> blocked(projection, "BLOCK_INCOMPLETE_EVIDENCE",
                    "DSL rewrite is blocked because semantic round-trip evidence is incomplete.");
            default -> blocked(projection, "BLOCK_NOT_ASSESSED",
                    "DSL rewrite is blocked because semantic round-trip was not assessed.");
        };
    }

    private static DslRewriteGateResult blocked(DslVisualProjection projection,
                                                String decision,
                                                String message) {
        DslRoundTripSummary roundTrip = projection.roundTrip();
        List<VisualDiagnostic> combinedDiagnostics = roundTrip.diagnostics().isEmpty()
                ? projection.diagnostics()
                : roundTrip.diagnostics();
        return new DslRewriteGateResult(SCHEMA_VERSION, projection.sourceId(), false, decision, message,
                roundTrip.generatedDsl(), roundTrip, combinedDiagnostics);
    }
}
