package com.leanowtech.bloge.graphengine.server.rest.dto;

import java.util.List;
import java.util.Objects;

/**
 * Response payload for the {@code POST /api/v1/import/bpmn} endpoint.
 *
 * <p>Contains the generated BLOGE DSL source (when translation succeeds) and
 * every diagnostic emitted during the BPMN-to-BLOGE translation pipeline.</p>
 *
 * @param dslSource   generated BLOGE DSL source, or {@code null} when the
 *                    translation produced errors
 * @param success     {@code true} when the translation completed without errors
 * @param diagnostics immutable list of translation diagnostics
 */
public record ImportBpmnResponse(
        String dslSource,
        boolean success,
        List<DiagnosticEntry> diagnostics
) {

    public ImportBpmnResponse {
        diagnostics = List.copyOf(Objects.requireNonNullElse(diagnostics, List.of()));
    }

    /**
     * One diagnostic emitted during BPMN translation.
     *
     * @param severity   diagnostic severity ({@code "WARN"} or {@code "ERROR"})
     * @param code       stable diagnostic code (e.g. {@code "UNSUPPORTED_ELEMENT"})
     * @param elementId  BPMN element identifier that triggered the diagnostic
     * @param location   human-readable source location
     * @param message    diagnostic message
     * @param suggestion actionable suggestion for resolving the diagnostic
     */
    public record DiagnosticEntry(
            String severity,
            String code,
            String elementId,
            String location,
            String message,
            String suggestion
    ) {
    }
}
