package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Conservative round-trip readiness summary for an imported DSL preview.
 *
 * @param supported whether the preview can be losslessly regenerated as BLOGE DSL
 * @param status machine-readable round-trip state
 * @param message human readable explanation
 * @param generatedDsl generated DSL used for the semantic round-trip check
 * @param sourceFingerprint canonical visual-semantic fingerprint of the source projection
 * @param generatedFingerprint canonical visual-semantic fingerprint of the generated DSL projection
 * @param diagnostics round-trip generation or re-parse diagnostics
 */
public record DslRoundTripSummary(
        boolean supported,
        String status,
        String message,
        String generatedDsl,
        String sourceFingerprint,
        String generatedFingerprint,
        List<VisualDiagnostic> diagnostics
) {
    /**
     * Creates a normalized summary.
     */
    public DslRoundTripSummary {
        status = status == null || status.isBlank() ? "NOT_ASSESSED" : status;
        message = message == null ? "" : message;
        generatedDsl = generatedDsl == null ? "" : generatedDsl;
        sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
        generatedFingerprint = generatedFingerprint == null ? "" : generatedFingerprint;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static DslRoundTripSummary notAssessed() {
        return new DslRoundTripSummary(false, "NOT_ASSESSED",
                "Preview import preserves editable visual structure first; semantic DSL regeneration was not assessed.",
                "", "", "", List.of());
    }

    public static DslRoundTripSummary supported(String generatedDsl, String fingerprint) {
        return new DslRoundTripSummary(true, "SUPPORTED",
                "Generated DSL re-parsed into the same canonical visual semantics as the source DSL.",
                generatedDsl, fingerprint, fingerprint, List.of());
    }

    public static DslRoundTripSummary drift(String generatedDsl,
                                            String sourceFingerprint,
                                            String generatedFingerprint) {
        return new DslRoundTripSummary(false, "DRIFT",
                "Generated DSL parses successfully, but its canonical visual semantics differ from the source projection.",
                generatedDsl, sourceFingerprint, generatedFingerprint, List.of());
    }

    public static DslRoundTripSummary partial(String message) {
        return partial(message, "", "", "", List.of());
    }

    public static DslRoundTripSummary partial(String message,
                                              String generatedDsl,
                                              String sourceFingerprint,
                                              String generatedFingerprint,
                                              List<VisualDiagnostic> diagnostics) {
        return new DslRoundTripSummary(false, "PARTIAL", message, generatedDsl, sourceFingerprint,
                generatedFingerprint, diagnostics);
    }
}
