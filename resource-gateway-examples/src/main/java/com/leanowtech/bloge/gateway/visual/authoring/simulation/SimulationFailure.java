package com.leanowtech.bloge.gateway.visual.authoring.simulation;

/** HTTP-neutral, code-only failure boundary for simulation commands and reads. */
public final class SimulationFailure extends RuntimeException {
    public enum Code { VALIDATION, NOT_FOUND, CONFLICT, BUSY, UNSUPPORTED, INTEGRITY }
    private final Code code;

    public SimulationFailure(Code code) {
        super("simulation." + code.name().toLowerCase(java.util.Locale.ROOT));
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() { return code; }
}
