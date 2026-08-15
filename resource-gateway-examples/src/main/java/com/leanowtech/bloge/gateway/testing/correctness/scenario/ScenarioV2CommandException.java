package com.leanowtech.bloge.gateway.testing.correctness.scenario;

/** Stable Scenario v2 command failure with optional payload-free closure diagnostics. */
public final class ScenarioV2CommandException extends RuntimeException {

    private final String code;
    private final ScenarioClosureReport closureReport;

    public ScenarioV2CommandException(String code, String message) {
        this(code, message, null);
    }

    public ScenarioV2CommandException(
            String code,
            String message,
            ScenarioClosureReport closureReport
    ) {
        super(message);
        this.code = code == null ? "RG.CORRECTNESS.SCENARIO_INVALID" : code.trim();
        this.closureReport = closureReport;
    }

    public String code() {
        return code;
    }

    public ScenarioClosureReport closureReport() {
        return closureReport;
    }
}
