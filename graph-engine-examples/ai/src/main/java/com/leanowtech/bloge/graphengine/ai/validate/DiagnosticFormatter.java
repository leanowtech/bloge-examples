package com.leanowtech.bloge.graphengine.ai.validate;

import java.util.List;

/**
 * Formats structured diagnostics into repair-friendly plain text for LLM retries.
 */
public final class DiagnosticFormatter {

    private DiagnosticFormatter() {
    }

    /**
     * Formats diagnostics into one human-readable list sorted in the existing encounter order.
     *
     * @param diagnostics diagnostics to format
     * @return plain-text diagnostic block
     */
    public static String format(List<DslDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "No diagnostics.";
        }
        StringBuilder builder = new StringBuilder();
        for (DslDiagnostic diagnostic : diagnostics) {
            builder.append("- [")
                    .append(diagnostic.severity())
                    .append("][")
                    .append(diagnostic.stage())
                    .append("][")
                    .append(diagnostic.code())
                    .append("] ")
                    .append(diagnostic.message());
            if (diagnostic.line() > 0) {
                builder.append(" (line ").append(diagnostic.line());
                if (diagnostic.column() > 0) {
                    builder.append(", column ").append(diagnostic.column());
                }
                builder.append(')');
            }
            if (diagnostic.nodeId() != null && !diagnostic.nodeId().isBlank()) {
                builder.append(" [node=").append(diagnostic.nodeId()).append(']');
            }
            if (diagnostic.field() != null && !diagnostic.field().isBlank()) {
                builder.append(" [field=").append(diagnostic.field()).append(']');
            }
            builder.append('\n');
        }
        return builder.toString().stripTrailing();
    }
}
