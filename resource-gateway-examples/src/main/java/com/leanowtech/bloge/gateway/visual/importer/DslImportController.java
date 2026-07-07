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

import java.util.ArrayList;
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
     * Assesses multiple existing BLOGE DSL files against the same effective visual schema view.
     *
     * <p>This endpoint is meant for repository migration, CI import reports, and batch readiness
     * dashboards. It does not persist drafts or write source files; each source reuses the same
     * preview and rewrite-gate semantics as the interactive canvas.</p>
     *
     * @param request DSL sources plus the already-normalized visual catalog view
     * @return aggregate migration coverage and per-source readiness details
     */
    @PostMapping("/batch-report")
    public DslImportBatchReport batchReport(@RequestBody DslImportBatchReportRequest request) {
        return service.batchReport(request);
    }

    /**
     * Projects multiple existing BLOGE DSL files and stores eligible projections as governed drafts.
     *
     * <p>This endpoint is the batch counterpart of single-source commit. It never writes source
     * files and it does not trust client-side draft payloads; every item is re-projected on the
     * server, classified with the same report/rewrite rules, and then stored only when the chosen
     * commit policy allows it.</p>
     *
     * @param request DSL sources plus the already-normalized visual catalog view
     * @param actor user or system actor committing the migration drafts
     * @param changeSource UI surface or integration source committing the drafts
     * @param changeSummary human-readable commit summary
     * @param reason operator-facing reason for committing the drafts
     * @return aggregate commit result and per-source stored/skipped/failed evidence
     */
    @PostMapping("/batch-commit")
    public DslImportBatchCommitResult batchCommit(@RequestBody DslImportBatchCommitRequest request,
                                                  @RequestParam(defaultValue = "") String actor,
                                                  @RequestParam(defaultValue = "") String changeSource,
                                                  @RequestParam(defaultValue = "") String changeSummary,
                                                  @RequestParam(defaultValue = "") String reason) {
        DslImportBatchCommitRequest normalized = request == null
                ? new DslImportBatchCommitRequest(List.of(), List.of(), List.of(), "", "", false)
                : request;
        List<DslImportBatchCommitItem> items = new ArrayList<>();
        List<DslImportBatchReportItem> reportItems = new ArrayList<>();
        Map<String, Integer> commitDecisionCounts = new LinkedHashMap<>();
        int committed = 0;
        int skipped = 0;
        int failed = 0;

        for (DslImportBatchSource source : normalized.sources()) {
            DslImportPreviewRequest previewRequest = new DslImportPreviewRequest(
                    source.sourceId(),
                    source.dsl(),
                    normalized.operatorLibraryIds(),
                    normalized.inlineLibraries(),
                    normalized.mode(),
                    source.layout()
            );
            DslVisualProjection projection = service.preview(previewRequest);
            DslImportBatchReportItem reportItem = service.reportItem(projection, normalized.includeDrafts());
            reportItems.add(reportItem);
            BatchCommitPlan plan = batchCommitPlan(normalized.commitPolicy(), reportItem);

            if (!plan.attemptCommit()) {
                GraphDraftImportResult skippedResult = skippedBatchImportResult(projection, reportItem, plan);
                items.add(new DslImportBatchCommitItem(reportItem.sourceId(), reportItem.graphName(), false,
                        plan.decision(), plan.message(), reportItem, skippedResult));
                increment(commitDecisionCounts, plan.decision());
                skipped++;
                continue;
            }

            GraphDraft candidate = commitCandidate(projection, actor, changeSource, changeSummary, reason);
            VisualValidationResult previewValidation = validator.validate(candidate);
            GraphDraftDependencyReport previewDependencyReport = GraphDraftDependencyReport.from(candidate, catalog);
            try {
                GraphDraft stored = repository.save(candidate);
                VisualValidationResult validation = validator.validate(stored);
                GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(stored, catalog);
                String decision = committedDecision(plan.decision());
                items.add(new DslImportBatchCommitItem(reportItem.sourceId(), stored.graphName(), true,
                        decision, "DSL source was stored as a governed visual graph draft.",
                        reportItem, GraphDraftImportResult.imported(stored, validation, dependencyReport)));
                increment(commitDecisionCounts, decision);
                committed++;
            } catch (RuntimeException e) {
                String decision = "FAILED_PERSISTENCE";
                items.add(new DslImportBatchCommitItem(reportItem.sourceId(), reportItem.graphName(), false,
                        decision, "DSL projection passed the batch commit policy but could not be persisted.",
                        reportItem, GraphDraftImportResult.rejected(null, candidate,
                        List.of(commitPersistenceFailureDiagnostic(projection, candidate, e)),
                        previewValidation, previewDependencyReport)));
                increment(commitDecisionCounts, decision);
                failed++;
            }
        }

        DslImportBatchCommitSummary summary = new DslImportBatchCommitSummary(
                normalized.sources().size(),
                committed,
                skipped,
                failed,
                service.summarize(reportItems),
                commitDecisionCounts
        );
        return new DslImportBatchCommitResult(DslImportBatchCommitResult.SCHEMA_VERSION,
                normalized.mode(), normalized.commitPolicy(), summary, items);
    }

    /**
     * Checks whether generated DSL is safe enough to overwrite the source DSL.
     *
     * <p>The rewrite gate does not persist drafts or modify source files. It is a deterministic
     * preflight for tools that want to perform source replacement only when semantic round-trip
     * evidence is strong enough.</p>
     *
     * @param request DSL source plus the already-normalized visual catalog view
     * @return rewrite gate result with the generated DSL and blocking evidence
     */
    @PostMapping("/rewrite-gate")
    public DslRewriteGateResult rewriteGate(@RequestBody DslImportPreviewRequest request) {
        return DslRewriteGateResult.from(service.preview(request));
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

    private GraphDraftImportResult skippedBatchImportResult(DslVisualProjection projection,
                                                            DslImportBatchReportItem reportItem,
                                                            BatchCommitPlan plan) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>(reportItem.diagnostics());
        diagnostics.add(batchCommitSkippedDiagnostic(reportItem, plan));
        GraphDraft candidate = projection == null ? null : projection.draft();
        VisualValidationResult validation = candidate == null
                ? new VisualValidationResult(false, diagnostics)
                : validator.validate(candidate);
        GraphDraftDependencyReport dependencyReport = candidate == null
                ? GraphDraftDependencyReport.empty()
                : GraphDraftDependencyReport.from(candidate, catalog);
        return GraphDraftImportResult.rejected(null, candidate, diagnostics, validation, dependencyReport);
    }

    private static VisualDiagnostic batchCommitSkippedDiagnostic(DslImportBatchReportItem reportItem,
                                                                 BatchCommitPlan plan) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceId", reportItem.sourceId());
        metadata.put("graphName", reportItem.graphName());
        metadata.put("decision", plan.decision());
        metadata.put("commitPolicy", plan.policy());
        return new VisualDiagnostic(plan.unknownPolicy() ? "ERROR" : "WARNING",
                "visual.dslImport.batchCommitSkipped", plan.message(), "/batchCommit", -1, -1, metadata);
    }

    private static BatchCommitPlan batchCommitPlan(String policy, DslImportBatchReportItem item) {
        String normalizedPolicy = policy == null || policy.isBlank() ? "renderable" : policy;
        return switch (normalizedPolicy) {
            case "renderable", "renderable-repairable" -> item.renderable()
                    ? new BatchCommitPlan(normalizedPolicy, true, "COMMIT_RENDERABLE",
                    "Renderable DSL projection is eligible for governed draft commit.", false)
                    : new BatchCommitPlan(normalizedPolicy, false, "SKIP_NOT_RENDERABLE",
                    "DSL source could not be parsed into a graph-level visual projection.", false);
            case "fully-projected" -> {
                if (!item.renderable()) {
                    yield new BatchCommitPlan(normalizedPolicy, false, "SKIP_NOT_RENDERABLE",
                            "DSL source could not be parsed into a graph-level visual projection.", false);
                }
                yield item.fullyProjected()
                        ? new BatchCommitPlan(normalizedPolicy, true, "COMMIT_FULLY_PROJECTED",
                        "Fully projected DSL source is eligible for governed draft commit.", false)
                        : new BatchCommitPlan(normalizedPolicy, false, "SKIP_NOT_FULLY_PROJECTED",
                        "DSL source rendered but still has repair diagnostics or loss-aware coverage gaps.", false);
            }
            case "rewrite-allowed" -> {
                if (!item.renderable()) {
                    yield new BatchCommitPlan(normalizedPolicy, false, "SKIP_NOT_RENDERABLE",
                            "DSL source could not be parsed into a graph-level visual projection.", false);
                }
                yield item.rewriteAllowed()
                        ? new BatchCommitPlan(normalizedPolicy, true, "COMMIT_REWRITE_ALLOWED",
                        "DSL source passed semantic rewrite evidence and is eligible for governed draft commit.",
                        false)
                        : new BatchCommitPlan(normalizedPolicy, false, "SKIP_REWRITE_NOT_ALLOWED",
                        "DSL source did not pass the semantic rewrite gate required by this commit policy.", false);
            }
            default -> new BatchCommitPlan(normalizedPolicy, false, "SKIP_UNKNOWN_POLICY",
                    "Unknown DSL batch commit policy '%s'; no draft was stored for this source."
                            .formatted(normalizedPolicy), true);
        };
    }

    private static String committedDecision(String planDecision) {
        return switch (planDecision) {
            case "COMMIT_FULLY_PROJECTED" -> "COMMITTED_FULLY_PROJECTED";
            case "COMMIT_REWRITE_ALLOWED" -> "COMMITTED_REWRITE_ALLOWED";
            default -> "COMMITTED_RENDERABLE";
        };
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key == null || key.isBlank() ? "UNKNOWN" : key, 1, Integer::sum);
    }

    private record BatchCommitPlan(
            String policy,
            boolean attemptCommit,
            String decision,
            String message,
            boolean unknownPolicy
    ) {
    }
}
