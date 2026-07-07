package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftImportResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visual DSL import API.
 */
@RestController
@RequestMapping("/api/visual/dsl-imports")
public class DslImportController {

    private final DslImportService service;
    private final GraphDraftRepository repository;
    private final GraphDraftValidator validator;
    private final VisualOperatorCatalog catalog;

    public DslImportController(DslImportService service,
                               GraphDraftRepository repository,
                               GraphDraftValidator validator,
                               VisualOperatorCatalog catalog) {
        this.service = service;
        this.repository = repository;
        this.validator = validator;
        this.catalog = catalog;
    }

    /**
     * Projects existing BLOGE DSL into an editable visual draft without persisting it.
     *
     * <p>The canvas contract is schema-provenance agnostic: inline libraries, registry libraries,
     * code-generated schemas, and handwritten schemas are equivalent once they are valid visual
     * catalog structures.</p>
     *
     * @param request DSL source plus the already-normalized visual catalog view
     * @return transient visual projection
     */
    @PostMapping("/preview")
    public DslVisualProjection preview(@RequestBody DslImportPreviewRequest request) {
        return service.preview(request);
    }

    /**
     * Re-projects existing BLOGE DSL and stores the resulting visual draft as a governed revision.
     *
     * <p>The commit path intentionally accepts the same request shape as preview and re-runs
     * projection server-side instead of trusting a browser-mutated draft. Only blocking DSL parse
     * and root-shape diagnostics stop persistence; missing operators or functions are saved as
     * repairable migration drafts with validation/dependency diagnostics.</p>
     *
     * @param request DSL source plus the already-normalized visual catalog view
     * @param actor user or system actor committing the migration draft
     * @param changeSource UI surface or integration source committing the draft
     * @param changeSummary human-readable commit summary
     * @param reason operator-facing reason for committing the draft
     * @return import-style result carrying the stored draft, validation, and dependency report
     */
    @PostMapping("/commit")
    public ResponseEntity<GraphDraftImportResult> commit(@RequestBody DslImportPreviewRequest request,
                                                         @RequestParam(defaultValue = "") String actor,
                                                         @RequestParam(defaultValue = "") String changeSource,
                                                         @RequestParam(defaultValue = "") String changeSummary,
                                                         @RequestParam(defaultValue = "") String reason) {
        DslVisualProjection projection = service.preview(request);
        List<VisualDiagnostic> blockingDiagnostics = blockingCommitDiagnostics(projection);
        if (!blockingDiagnostics.isEmpty()) {
            return ResponseEntity.badRequest().body(GraphDraftImportResult.rejected(blockingDiagnostics));
        }

        GraphDraft candidate = commitCandidate(projection, actor, changeSource, changeSummary, reason);
        VisualValidationResult previewValidation = validator.validate(candidate);
        GraphDraftDependencyReport previewDependencyReport = GraphDraftDependencyReport.from(candidate, catalog);
        GraphDraft stored;
        try {
            stored = repository.save(candidate);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(GraphDraftImportResult.rejected(null, candidate,
                            List.of(commitPersistenceFailureDiagnostic(projection, candidate, e)),
                            previewValidation, previewDependencyReport));
        }

        VisualValidationResult validation = validator.validate(stored);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(stored, catalog);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GraphDraftImportResult.imported(stored, validation, dependencyReport));
    }

    private static GraphDraft commitCandidate(DslVisualProjection projection,
                                              String actor,
                                              String changeSource,
                                              String changeSummary,
                                              String reason) {
        GraphDraft draft = projection.draft()
                .withIdentity("", 0)
                .withVisualLayout(visualLayoutWithSourceMap(projection.draft().visualLayout(),
                        projection.sourceMap()))
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        actor,
                        changeSource.isBlank() ? "dsl-import" : changeSource,
                        changeSummary.isBlank()
                                ? "Imported visual draft from BLOGE DSL %s."
                                .formatted(projection.sourceId().isBlank() ? projection.draft().graphName()
                                        : projection.sourceId())
                                : changeSummary,
                        List.of("/"),
                        reason
                ));
        return draft;
    }

    private static Map<String, Object> visualLayoutWithSourceMap(Map<String, Object> visualLayout,
                                                                  DslSourceMap sourceMap) {
        if (sourceMap == null || sourceMapEntryCount(sourceMap) == 0) {
            return visualLayout;
        }
        Map<String, Object> nextVisualLayout = new LinkedHashMap<>(visualLayout == null ? Map.of() : visualLayout);
        Object rawImport = nextVisualLayout.get("import");
        Map<String, Object> importMetadata = rawImport instanceof Map<?, ?> rawMap
                ? mutableStringKeyMap(rawMap)
                : new LinkedHashMap<>();
        importMetadata.put("sourceMap", sourceMap);
        nextVisualLayout.put("import", importMetadata);
        return nextVisualLayout;
    }

    private static Map<String, Object> mutableStringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static int sourceMapEntryCount(DslSourceMap sourceMap) {
        return sourceMap.nodes().size() + sourceMap.edges().size() + sourceMap.bindings().size();
    }

    private static List<VisualDiagnostic> blockingCommitDiagnostics(DslVisualProjection projection) {
        return projection.diagnostics().stream()
                .filter(DslImportController::isBlockingCommitDiagnostic)
                .toList();
    }

    private static boolean isBlockingCommitDiagnostic(VisualDiagnostic diagnostic) {
        return diagnostic != null
                && ("visual.dslImport.parseFailed".equals(diagnostic.code())
                || "visual.dslImport.rootUnsupported".equals(diagnostic.code()));
    }

    private static VisualDiagnostic commitPersistenceFailureDiagnostic(DslVisualProjection projection,
                                                                       GraphDraft candidate,
                                                                       RuntimeException failure) {
        String exceptionMessage = failure.getMessage() == null ? "" : failure.getMessage();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceId", projection == null ? "" : projection.sourceId());
        metadata.put("candidateGraphName", candidate == null ? "" : candidate.graphName());
        metadata.put("candidateDraftId", candidate == null ? "" : candidate.draftId());
        metadata.put("candidateRevision", candidate == null ? 0 : candidate.revision());
        metadata.put("exceptionType", failure.getClass().getSimpleName());
        metadata.put("exceptionMessage", exceptionMessage);
        return VisualDiagnostic.error(
                "visual.dslImport.commitPersistenceFailed",
                "DSL import draft '%s' could not be persisted: %s"
                        .formatted(projection == null ? "" : projection.sourceId(), exceptionMessage),
                "/draft",
                metadata
        );
    }
}
