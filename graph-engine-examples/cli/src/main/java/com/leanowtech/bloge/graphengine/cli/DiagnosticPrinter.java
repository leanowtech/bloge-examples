package com.leanowtech.bloge.graphengine.cli;

import com.leanowtech.bloge.bpmn.diagnostic.TranslationDiagnostic;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/**
 * Renders translation diagnostics into deterministic stderr lines for the CLI.
 */
public final class DiagnosticPrinter {

    private final PrintStream err;

    /**
     * Creates a printer that writes to the supplied stream.
     *
     * @param err stderr-like stream used for diagnostic output
     */
    public DiagnosticPrinter(PrintStream err) {
        this.err = Objects.requireNonNull(err, "err must not be null");
    }

    /**
     * Prints every diagnostic in order.
     *
     * @param diagnostics diagnostics to render
     */
    public void printAll(List<TranslationDiagnostic> diagnostics) {
        if (diagnostics == null) {
            return;
        }
        for (TranslationDiagnostic diagnostic : diagnostics) {
            print(diagnostic);
        }
    }

    /**
     * Prints a single diagnostic.
     *
     * @param diagnostic diagnostic to render
     */
    public void print(TranslationDiagnostic diagnostic) {
        err.println(format(diagnostic));
    }

    /**
     * Formats a diagnostic using the CLI's stable text representation.
     *
     * @param diagnostic diagnostic to format
     * @return formatted diagnostic line
     */
    public String format(TranslationDiagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic must not be null");
        String elementId = diagnostic.elementId().isBlank() ? "<process>" : diagnostic.elementId();
        StringBuilder builder = new StringBuilder()
                .append(elementId)
                .append(": [")
                .append(diagnostic.severity().name())
                .append("] ")
                .append(diagnostic.code().name())
                .append(" - ")
                .append(diagnostic.message());
        if (!diagnostic.suggestion().isBlank()) {
            builder.append(" (suggestion: ").append(diagnostic.suggestion()).append(')');
        }
        return builder.toString();
    }
}
