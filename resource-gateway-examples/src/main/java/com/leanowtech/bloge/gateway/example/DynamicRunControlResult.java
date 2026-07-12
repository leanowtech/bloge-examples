package com.leanowtech.bloge.gateway.example;

/** Result of a run-control lookup or cancellation command. */
public record DynamicRunControlResult(
        boolean accepted,
        String code,
        String message,
        DynamicRunControlView control
) {
    public DynamicRunControlResult {
        code = code == null || code.isBlank() ? "RG.RUN_CONTROL.UNKNOWN" : code;
        message = message == null ? "" : message;
        control = control == null ? DynamicRunControlView.unmanaged() : control;
    }
}
