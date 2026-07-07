package com.leanowtech.bloge.gateway.visual.importer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One DSL source in a batch migration report request.
 *
 * @param sourceId logical source file id
 * @param dsl BLOGE DSL source text
 * @param layout optional per-source layout hints
 */
public record DslImportBatchSource(
        String sourceId,
        String dsl,
        Map<String, Object> layout
) {
    /**
     * Creates a normalized batch source.
     */
    public DslImportBatchSource {
        sourceId = sourceId == null || sourceId.isBlank() ? "inline.dsl" : sourceId.trim();
        dsl = dsl == null ? "" : dsl;
        layout = layout == null ? Map.of() : new LinkedHashMap<>(layout);
    }
}
