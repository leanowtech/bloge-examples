package com.leanowtech.bloge.gateway.visual.importer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;

import java.util.List;

/**
 * Request for assessing a set of existing BLOGE DSL files against the same effective visual schema view.
 *
 * <p>This is intentionally schema-provenance agnostic, just like preview/commit/rewrite-gate: callers may
 * provide registry library ids, handwritten visual libraries, code-generated libraries, or any other
 * structurally valid {@code bloge.visualOperatorLibrary.v1} sources.</p>
 *
 * @param sources DSL files to assess
 * @param operatorLibraryIds already-imported visual operator library ids to include
 * @param inlineLibraries temporary visual operator libraries to use only for this report
 * @param mode import mode label
 * @param includeDrafts whether each item should include the projected draft payload
 */
public record DslImportBatchReportRequest(
        List<DslImportBatchSource> sources,
        @JsonAlias({"catalogIds", "libraryIds"})
        List<String> operatorLibraryIds,
        List<OperatorLibrary> inlineLibraries,
        String mode,
        boolean includeDrafts
) {
    /**
     * Creates a normalized batch request.
     */
    public DslImportBatchReportRequest {
        sources = sources == null ? List.of() : sources.stream()
                .map(source -> source == null ? new DslImportBatchSource("", "", null) : source)
                .toList();
        operatorLibraryIds = operatorLibraryIds == null ? List.of() : operatorLibraryIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        inlineLibraries = inlineLibraries == null ? List.of() : List.copyOf(inlineLibraries);
        mode = mode == null || mode.isBlank() ? "batch-report" : mode.trim();
    }
}
