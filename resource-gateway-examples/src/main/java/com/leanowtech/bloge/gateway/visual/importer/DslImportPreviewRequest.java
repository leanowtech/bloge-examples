package com.leanowtech.bloge.gateway.visual.importer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request for projecting existing BLOGE DSL into an editable visual draft.
 *
 * @param sourceId logical source file id for source-map diagnostics
 * @param dsl source DSL text
 * @param operatorLibraryIds already-imported visual operator library ids to include
 * @param inlineLibraries temporary visual operator libraries to use for this preview only
 * @param mode import mode label
 * @param layout optional caller layout hints
 */
public record DslImportPreviewRequest(
        String sourceId,
        String dsl,
        @JsonAlias({"catalogIds", "libraryIds"})
        List<String> operatorLibraryIds,
        List<OperatorLibrary> inlineLibraries,
        String mode,
        Map<String, Object> layout
) {
    /**
     * Creates a normalized request.
     */
    public DslImportPreviewRequest {
        sourceId = sourceId == null || sourceId.isBlank() ? "inline.dsl" : sourceId.trim();
        dsl = dsl == null ? "" : dsl;
        operatorLibraryIds = operatorLibraryIds == null ? List.of() : operatorLibraryIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        inlineLibraries = inlineLibraries == null ? List.of() : List.copyOf(inlineLibraries);
        mode = mode == null || mode.isBlank() ? "preview" : mode.trim();
        layout = layout == null ? Map.of() : new LinkedHashMap<>(layout);
    }
}
