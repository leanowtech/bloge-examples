package com.leanowtech.bloge.gateway.visual.diagnostic;

/**
 * Authoring-time diagnostic returned by the visual orchestration APIs.
 *
 * @param level severity such as ERROR, WARNING, or INFO
 * @param code stable machine-readable diagnostic code
 * @param message human-readable diagnostic message
 * @param target JSON pointer or logical target path
 * @param line source line when the diagnostic came from generated DSL
 * @param column source column when the diagnostic came from generated DSL
 */
public record VisualDiagnostic(
        String level,
        String code,
        String message,
        String target,
        int line,
        int column
) {
    /**
     * Creates a diagnostic payload.
     */
    public VisualDiagnostic {
        level = blankToDefault(level, "INFO");
        code = blankToDefault(code, "visual.info");
        message = blankToDefault(message, "");
        target = blankToDefault(target, "");
    }

    /**
     * Creates an error diagnostic.
     *
     * @param code stable diagnostic code
     * @param message human-readable message
     * @param target affected target
     * @return diagnostic instance
     */
    public static VisualDiagnostic error(String code, String message, String target) {
        return new VisualDiagnostic("ERROR", code, message, target, -1, -1);
    }

    /**
     * Creates a warning diagnostic.
     *
     * @param code stable diagnostic code
     * @param message human-readable message
     * @param target affected target
     * @return diagnostic instance
     */
    public static VisualDiagnostic warning(String code, String message, String target) {
        return new VisualDiagnostic("WARNING", code, message, target, -1, -1);
    }

    /**
     * @return true when this diagnostic blocks compilation or execution
     */
    public boolean error() {
        return "ERROR".equalsIgnoreCase(level);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
