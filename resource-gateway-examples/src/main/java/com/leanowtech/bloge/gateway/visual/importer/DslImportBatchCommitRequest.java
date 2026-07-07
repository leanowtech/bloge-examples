package com.leanowtech.bloge.gateway.visual.importer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;

import java.util.List;

/**
 * Request for storing renderable DSL projections as governed visual graph drafts in batch.
 *
 * <p>Like preview/report, this request is schema-provenance agnostic: the canvas only requires a
 * valid effective visual schema view, not knowledge of how that schema was produced.</p>
 *
 * @param sources DSL files to project and optionally store
 * @param operatorLibraryIds already-imported visual operator library ids to include
 * @param inlineLibraries temporary visual operator libraries to use only for this batch
 * @param mode import mode label
 * @param commitPolicy renderable, fully-projected, or rewrite-allowed
 * @param includeDrafts whether skipped report items should include their projected draft payload
 */
public record DslImportBatchCommitRequest(
        List<DslImportBatchSource> sources,
        @JsonAlias({"catalogIds", "libraryIds"})
        List<String> operatorLibraryIds,
        List<OperatorLibrary> inlineLibraries,
        String mode,
        String commitPolicy,
        boolean includeDrafts
) {
    /**
     * Creates a normalized batch commit request.
     */
    public DslImportBatchCommitRequest {
        sources = sources == null ? List.of() : sources.stream()
                .map(source -> source == null ? new DslImportBatchSource("", "", null) : source)
                .toList();
        operatorLibraryIds = operatorLibraryIds == null ? List.of() : operatorLibraryIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        inlineLibraries = inlineLibraries == null ? List.of() : List.copyOf(inlineLibraries);
        mode = mode == null || mode.isBlank() ? "batch-commit" : mode.trim();
        commitPolicy = normalizeCommitPolicy(commitPolicy);
    }

    private static String normalizeCommitPolicy(String value) {
        if (value == null || value.isBlank()) {
            return "renderable";
        }
        return value.trim().toLowerCase().replace('_', '-');
    }
}
