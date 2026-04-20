package com.leanowtech.bloge.graphengine.service;

import java.util.Objects;

/**
 * Unified lint/compile diagnostic surfaced by the product-layer authoring
 * service APIs.
 *
 * @param source diagnostic source such as {@code lint}, {@code compile}, or {@code service}
 * @param code source-specific rule or diagnostic code
 * @param severity diagnostic severity
 * @param message human-readable diagnostic message
 * @param nodeId related node identifier when available
 * @param field related field identifier when available
 * @param line 1-based source line, or {@code 0} when unavailable
 * @param column 1-based source column, or {@code 0} when unavailable
 */
public record GraphEngineDiagnostic(
        String source,
        String code,
        Severity severity,
        String message,
        String nodeId,
        String field,
        int line,
        int column
) {
    public GraphEngineDiagnostic {
        source = source == null || source.isBlank() ? "service" : source;
        code = code == null || code.isBlank() ? "unknown" : code;
        severity = Objects.requireNonNullElse(severity, Severity.INFO);
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        line = Math.max(0, line);
        column = Math.max(0, column);
    }

    /**
     * Returns whether this diagnostic blocks publish/start operations.
     *
     * @return {@code true} when the diagnostic is an error
     */
    public boolean error() {
        return severity == Severity.ERROR;
    }

    /**
     * Severity model shared across lint, compilation, and service validation.
     */
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}
