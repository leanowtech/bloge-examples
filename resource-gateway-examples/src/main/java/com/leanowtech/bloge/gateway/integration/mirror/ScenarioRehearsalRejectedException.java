package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Map;

/**
 * Payload-free fail-closed rejection from ScenarioPack rehearsal compilation.
 */
public final class ScenarioRehearsalRejectedException extends RuntimeException {
    private final String code;
    private final Map<String, Object> diagnostics;

    /**
     * Creates a stable machine-readable rejection.
     *
     * @param code stable rejection code
     * @param diagnostics bounded payload-free coordinates
     */
    public ScenarioRehearsalRejectedException(
            String code, Map<String, Object> diagnostics) {
        super(code);
        this.code = code;
        this.diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    /** @return stable rejection code */
    public String code() {
        return code;
    }

    /** @return bounded payload-free diagnostic coordinates */
    public Map<String, Object> diagnostics() {
        return diagnostics;
    }
}
