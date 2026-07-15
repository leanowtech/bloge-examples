package com.leanowtech.bloge.gateway.testing.planning;

import java.util.List;

/**
 * Indicates that an execution-control request was rejected before any graph node was scheduled.
 *
 * <p>The stable {@link #code()} is intended for API and evidence projection. Diagnostics are
 * bounded, immutable preflight facts and must never contain fixture payloads.</p>
 */
public class ControlPlanRejectedException extends IllegalArgumentException {

    private final String code;
    private final List<String> diagnostics;

    /**
     * @param code stable control-plane failure code
     * @param diagnostics bounded human-readable diagnostics
     */
    public ControlPlanRejectedException(String code, List<String> diagnostics) {
        super(diagnostics == null || diagnostics.isEmpty() ? code : diagnostics.getFirst());
        this.code = code == null || code.isBlank() ? "CONTROL_PLAN_REJECTED" : code.trim();
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** @return stable machine-readable rejection code */
    public String code() {
        return code;
    }

    /** @return immutable bounded diagnostics */
    public List<String> diagnostics() {
        return diagnostics;
    }
}
