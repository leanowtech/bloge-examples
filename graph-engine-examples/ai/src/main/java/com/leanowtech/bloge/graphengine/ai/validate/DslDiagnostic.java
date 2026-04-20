package com.leanowtech.bloge.graphengine.ai.validate;

/**
 * Structured validation diagnostic returned by the AI authoring pipeline.
 *
 * @param stage pipeline stage that emitted the diagnostic
 * @param code stable diagnostic code or rule identifier
 * @param severity diagnostic severity
 * @param message human-readable message
 * @param nodeId offending node identifier when known
 * @param field offending field when known
 * @param line one-based source line, or {@code 0} when unknown
 * @param column one-based source column, or {@code 0} when unknown
 */
public record DslDiagnostic(
        Stage stage,
        String code,
        Severity severity,
        String message,
        String nodeId,
        String field,
        int line,
        int column
) {
    /**
     * Pipeline stage that produced the diagnostic.
     */
    public enum Stage {
        PARSE,
        LINT,
        COMPILE,
        SERVICE
    }

    /**
     * Severity level of one diagnostic.
     */
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public DslDiagnostic {
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (line < 0) {
            throw new IllegalArgumentException("line must be >= 0");
        }
        if (column < 0) {
            throw new IllegalArgumentException("column must be >= 0");
        }
    }
}
