package com.leanowtech.bloge.gateway.visual.importer;

/**
 * Conservative round-trip readiness summary for an imported DSL preview.
 *
 * @param supported whether the preview can be losslessly regenerated as BLOGE DSL
 * @param status machine-readable round-trip state
 * @param message human readable explanation
 */
public record DslRoundTripSummary(
        boolean supported,
        String status,
        String message
) {
    /**
     * Creates a normalized summary.
     */
    public DslRoundTripSummary {
        status = status == null || status.isBlank() ? "NOT_ASSESSED" : status;
        message = message == null ? "" : message;
    }

    public static DslRoundTripSummary notAssessed() {
        return new DslRoundTripSummary(false, "NOT_ASSESSED",
                "Preview import preserves editable visual structure first; lossless DSL regeneration is assessed later.");
    }

    public static DslRoundTripSummary partial(String message) {
        return new DslRoundTripSummary(false, "PARTIAL", message);
    }
}
