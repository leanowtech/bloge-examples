package com.leanowtech.bloge.gateway.visual.runtime;

/** Result of a visual run-control lookup or cancellation command. */
public record VisualRunControlResult(
        boolean accepted,
        String code,
        String message,
        VisualRunControlView control
) {
    public VisualRunControlResult {
        code = code == null || code.isBlank() ? "RG.RUN_CONTROL.UNKNOWN" : code;
        message = message == null ? "" : message;
        control = control == null ? VisualRunControlView.unmanaged() : control;
    }
}
