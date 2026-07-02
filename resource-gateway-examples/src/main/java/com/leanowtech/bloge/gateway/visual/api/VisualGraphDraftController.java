package com.leanowtech.bloge.gateway.visual.api;

import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDiff;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftExportBundle;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftHistorySummary;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftImportResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftOperatorFingerprintRebaseRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRevisionRestoreRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationResult;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublishRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public API for visual graph drafts.
 */
@RestController
@RequestMapping("/api/visual/drafts")
public class VisualGraphDraftController {

    private final GraphDraftRepository repository;
    private final GraphDraftValidator validator;
    private final VisualGraphRunService runner;
    private final VisualOperatorCatalog catalog;
    private final VisualGraphPublicationRepository publicationRepository;
    private final GraphDraftPatchService patchService;
    private final VisualGraphRunRepository runRepository;

    /**
     * @param repository draft repository
     * @param validator draft validator
     * @param runner draft runner
     */
    public VisualGraphDraftController(GraphDraftRepository repository,
                                      GraphDraftValidator validator,
                                      VisualGraphRunService runner,
                                      VisualOperatorCatalog catalog,
                                      VisualGraphPublicationRepository publicationRepository,
                                      GraphDraftPatchService patchService,
                                      VisualGraphRunRepository runRepository) {
        this.repository = repository;
        this.validator = validator;
        this.runner = runner;
        this.catalog = catalog;
        this.publicationRepository = publicationRepository;
        this.patchService = patchService;
        this.runRepository = runRepository;
    }

    /**
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @return stored drafts in scope
     */
    @GetMapping
    public Collection<GraphDraft> list(@RequestParam(defaultValue = "") String tenantId,
                                       @RequestParam(defaultValue = "") String namespace,
                                       @RequestParam(defaultValue = "") String environment) {
        return repository.all().stream()
                .filter(draft -> matchesDraftScope(draft, tenantId, namespace, environment))
                .toList();
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public Collection<GraphDraft> list() {
        return list("", "", "");
    }

    /**
     * Lists active and retained draft history summaries.
     *
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @return newest-first draft history index including deleted recoverable drafts in scope
     */
    @GetMapping("/history")
    public List<GraphDraftHistorySummary> history(@RequestParam(defaultValue = "") String tenantId,
                                                  @RequestParam(defaultValue = "") String namespace,
                                                  @RequestParam(defaultValue = "") String environment) {
        return repository.history().stream()
                .filter(history -> matchesDraftScope(draftForHistory(history), tenantId, namespace, environment))
                .toList();
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public List<GraphDraftHistorySummary> history() {
        return history("", "", "");
    }

    /**
     * Lists active and retained draft summaries with server-derived validation/readiness.
     *
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @return newest-first draft asset summaries for browser and control-plane indexes in scope
     */
    @GetMapping("/summaries")
    public List<GraphDraftSummary> summaries(@RequestParam(defaultValue = "") String tenantId,
                                             @RequestParam(defaultValue = "") String namespace,
                                             @RequestParam(defaultValue = "") String environment) {
        return repository.history().stream()
                .map(this::draftSummary)
                .filter(summary -> matchesDraftSummaryScope(summary, tenantId, namespace, environment))
                .toList();
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public List<GraphDraftSummary> summaries() {
        return summaries("", "", "");
    }

    /**
     * Creates a draft.
     *
     * @param draft draft body
     * @param actor user or system actor creating the draft
     * @param changeSource UI surface or integration source creating the draft
     * @param changeSummary human-readable creation summary
     * @param reason operator-facing reason for creating the draft
     * @return stored draft
     */
    @PostMapping
    public GraphDraft create(@RequestBody GraphDraft draft,
                             @RequestParam(defaultValue = "") String actor,
                             @RequestParam(defaultValue = "") String changeSource,
                             @RequestParam(defaultValue = "") String changeSummary,
                             @RequestParam(defaultValue = "") String reason) {
        requireSupportedDraftContract(draft);
        return repository.save(withCurrentOperatorSnapshotState(draft.withIdentity("", 0))
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        actor,
                        changeSource.isBlank() ? "api" : changeSource,
                        changeSummary.isBlank() ? "Created draft." : changeSummary,
                        List.of("/"),
                        reason
                )));
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public GraphDraft create(GraphDraft draft) {
        return create(draft, "", "", "", "");
    }

    private GraphDraftSummary draftSummary(GraphDraftHistorySummary history) {
        GraphDraft draft = draftForHistory(history);
        if (draft == null) {
            return GraphDraftSummary.from(history, null, null, null);
        }
        VisualValidationResult validation = validator.validate(draft);
        GraphDraftDependencyReport dependencies = GraphDraftDependencyReport.from(draft, catalog);
        return GraphDraftSummary.from(history, draft, validation, dependencies);
    }

    private GraphDraft draftForHistory(GraphDraftHistorySummary history) {
        if (history == null) {
            return null;
        }
        return repository.find(history.draftId())
                .orElseGet(() -> repository.revisions(history.draftId()).stream()
                        .findFirst()
                        .orElse(null));
    }

    private static boolean matchesDraftSummaryScope(GraphDraftSummary summary,
                                                    String tenantId,
                                                    String namespace,
                                                    String environment) {
        return summary != null
                && matchesScope(summary.tenantId(), tenantId)
                && matchesScope(summary.namespace(), namespace)
                && matchesScope(summary.environment(), environment);
    }

    private static boolean matchesDraftScope(GraphDraft draft,
                                             String tenantId,
                                             String namespace,
                                             String environment) {
        return draft != null
                && matchesScope(draft.tenantId(), tenantId)
                && matchesScope(draft.namespace(), namespace)
                && matchesScope(draft.environment(), environment);
    }

    private static boolean matchesScope(String actual, String expected) {
        return expected == null || expected.isBlank() || String.valueOf(actual).equals(expected);
    }

    /**
     * Gets a draft.
     *
     * @param draftId draft id
     * @return draft when present
     */
    @GetMapping("/{draftId}")
    public ResponseEntity<GraphDraft> get(@PathVariable String draftId) {
        return repository.find(draftId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Summarizes a stored draft's current catalog dependencies.
     *
     * @param draftId draft id
     * @return dependency report when the draft exists
     */
    @GetMapping("/{draftId}/dependencies")
    public ResponseEntity<GraphDraftDependencyReport> dependencies(@PathVariable String draftId) {
        return repository.find(draftId)
                .map(draft -> ResponseEntity.ok(GraphDraftDependencyReport.from(draft, catalog)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Exports a stored draft as a portable package with current operator snapshots.
     *
     * @param draftId draft id
     * @return export bundle when the draft exists
     */
    @GetMapping("/{draftId}/export")
    public ResponseEntity<GraphDraftExportBundle> exportDraft(@PathVariable String draftId) {
        return repository.find(draftId)
                .map(draft -> {
                    VisualValidationResult validation = validator.validate(draft);
                    GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(draft, catalog);
                    return ResponseEntity.ok(GraphDraftExportBundle.from(
                            draft,
                            operatorSnapshots(draft),
                            validation,
                            dependencyReport
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Imports a portable draft package as a new stored draft.
     *
     * @param bundle exported draft package
     * @param actor user or system actor importing the draft package
     * @param changeSource UI surface or integration source importing the package
     * @param changeSummary human-readable import summary
     * @param reason operator-facing reason for importing the package
     * @return import result with a stored draft or contract diagnostics
     */
    @PostMapping("/import")
    public ResponseEntity<GraphDraftImportResult> importDraft(@RequestBody GraphDraftExportBundle bundle,
                                                              @RequestParam(defaultValue = "") String actor,
                                                              @RequestParam(defaultValue = "") String changeSource,
                                                              @RequestParam(defaultValue = "") String changeSummary,
                                                              @RequestParam(defaultValue = "") String reason) {
        List<VisualDiagnostic> diagnostics = exportBundleContractDiagnostics(bundle);
        if (!diagnostics.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GraphDraftImportResult.rejected(bundle, diagnostics));
        }
        GraphDraft imported = withBundleOperatorSnapshots(bundle.draft(), bundle.operatorSnapshots())
                .withIdentity("", 0)
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        actor,
                        changeSource.isBlank() ? "import" : changeSource,
                        changeSummary.isBlank() ? "Imported draft from export bundle." : changeSummary,
                        List.of("/"),
                        reason
                ));
        GraphDraft stored = repository.save(withCurrentOrProvidedOperatorSnapshotState(imported));
        VisualValidationResult validation = validator.validate(stored);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(stored, catalog);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GraphDraftImportResult.imported(bundle, stored, validation, dependencyReport));
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public ResponseEntity<GraphDraftImportResult> importDraft(GraphDraftExportBundle bundle) {
        return importDraft(bundle, "", "", "", "");
    }

    /**
     * Lists stored revisions for a draft.
     *
     * @param draftId draft id
     * @return newest-first draft revision snapshots
     */
    @GetMapping("/{draftId}/revisions")
    public ResponseEntity<List<GraphDraft>> revisions(@PathVariable String draftId) {
        List<GraphDraft> revisions = repository.revisions(draftId);
        if (revisions.isEmpty() && repository.find(draftId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(revisions);
    }

    /**
     * Gets one stored draft revision.
     *
     * @param draftId draft id
     * @param revision revision number
     * @return draft revision snapshot when present
     */
    @GetMapping("/{draftId}/revisions/{revision}")
    public ResponseEntity<GraphDraft> revision(@PathVariable String draftId, @PathVariable long revision) {
        return repository.findRevision(draftId, revision)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Compares two stored draft revisions.
     *
     * @param draftId draft id
     * @param baseRevision base revision number
     * @param targetRevision target revision number
     * @return machine-readable draft diff when both revisions exist
     */
    @GetMapping("/{draftId}/revisions/{baseRevision}/diff/{targetRevision}")
    public ResponseEntity<GraphDraftDiff> revisionDiff(@PathVariable String draftId,
                                                       @PathVariable long baseRevision,
                                                       @PathVariable long targetRevision) {
        Optional<GraphDraft> base = repository.findRevision(draftId, baseRevision);
        Optional<GraphDraft> target = repository.findRevision(draftId, targetRevision);
        if (base.isEmpty() || target.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(GraphDraftDiff.between(base.get(), target.get()));
    }

    /**
     * Restores one stored draft revision as a new latest draft revision.
     *
     * @param draftId draft id
     * @param revision immutable revision number to restore
     * @param request optional current-revision precondition and audit metadata
     * @return stored draft or conflict diagnostics
     */
    @PostMapping("/{draftId}/revisions/{revision}/restore")
    public ResponseEntity<GraphDraftPatchResult> restoreRevision(
            @PathVariable String draftId,
            @PathVariable long revision,
            @RequestBody(required = false) GraphDraftRevisionRestoreRequest request) {
        Optional<GraphDraft> current = repository.find(draftId);
        Optional<GraphDraft> snapshot = repository.findRevision(draftId, revision);
        if (snapshot.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GraphDraft latestKnownDraft = current.orElseGet(() -> repository.revisions(draftId).stream()
                .findFirst()
                .orElse(null));
        if (latestKnownDraft == null) {
            return ResponseEntity.notFound().build();
        }

        GraphDraftRevisionRestoreRequest effectiveRequest = request == null
                ? GraphDraftRevisionRestoreRequest.empty()
                : request;
        if (effectiveRequest.expectedRevision() > 0
                && effectiveRequest.expectedRevision() != latestKnownDraft.revision()) {
            return conflictResponse(draftId, effectiveRequest.expectedRevision(), latestKnownDraft);
        }

        GraphDraft candidate = withMissingCurrentOperatorSnapshotState(
                snapshot.get().withIdentity(draftId, latestKnownDraft.revision()))
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        effectiveRequest.effectiveActor(),
                        effectiveRequest.effectiveChangeSource(),
                        effectiveRequest.effectiveChangeSummary(revision),
                        List.of("/"),
                        effectiveRequest.effectiveReason()
                ));
        List<VisualDiagnostic> contractDiagnostics = draftContractDiagnostics(candidate);
        if (!contractDiagnostics.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GraphDraftPatchResult.rejected(latestKnownDraft, contractDiagnostics));
        }
        if (current.isEmpty()) {
            GraphDraft stored = repository.save(candidate);
            return ResponseEntity.ok(GraphDraftPatchResult.patched(stored));
        }
        return repository.saveIfRevision(draftId, latestKnownDraft.revision(), candidate)
                .map(stored -> ResponseEntity.ok(GraphDraftPatchResult.patched(stored)))
                .orElseGet(() -> conflictResponse(draftId, latestKnownDraft.revision(), latestKnownDraft));
    }

    /**
     * Updates a draft.
     *
     * @param draftId draft id
     * @param draft draft body
     * @param actor user or system actor saving the draft
     * @param changeSource UI surface or integration source saving the draft
     * @param changeSummary human-readable save summary
     * @param reason operator-facing reason for saving the draft
     * @return stored draft
     */
    @PutMapping("/{draftId}")
    public ResponseEntity<Object> update(@PathVariable String draftId,
                                         @RequestBody GraphDraft draft,
                                         @RequestParam(defaultValue = "") String actor,
                                         @RequestParam(defaultValue = "") String changeSource,
                                         @RequestParam(defaultValue = "") String changeSummary,
                                         @RequestParam(defaultValue = "") String reason) {
        Optional<GraphDraft> current = repository.find(draftId);
        if (current.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        long expectedRevision = draft.revision();
        if (expectedRevision != current.get().revision()) {
            return updateConflictResponse(draftId, expectedRevision, current.get());
        }
        List<VisualDiagnostic> contractDiagnostics = draftContractDiagnostics(draft);
        if (!contractDiagnostics.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new VisualValidationResult(false, contractDiagnostics));
        }
        GraphDraft candidate = withExistingOrCurrentOperatorSnapshotState(current.get(),
                draft.withIdentity(draftId, expectedRevision).withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        actor,
                        changeSource.isBlank() ? "api" : changeSource,
                        changeSummary.isBlank() ? "Saved draft." : changeSummary,
                        List.of("/"),
                        reason
                )));
        return repository.saveIfRevision(draftId, expectedRevision, candidate)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> updateConflictResponse(draftId, expectedRevision, current.get()));
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public ResponseEntity<Object> update(String draftId, GraphDraft draft) {
        return update(draftId, draft, "", "", "", "");
    }

    /**
     * Applies an optimistic-locking JSON patch to a stored draft.
     *
     * @param draftId draft id
     * @param request patch request
     * @return stored draft or conflict diagnostics
     */
    @PatchMapping("/{draftId}")
    public ResponseEntity<GraphDraftPatchResult> patch(@PathVariable String draftId,
                                                       @RequestBody GraphDraftPatchRequest request) {
        Optional<GraphDraft> current = repository.find(draftId);
        if (current.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<VisualDiagnostic> preconditions = patchService.validateRequest(current.get(), request);
        if (!preconditions.isEmpty()) {
            HttpStatus status = hasDiagnostic(preconditions, "visual.draft.revisionConflict")
                    ? HttpStatus.CONFLICT
                    : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status)
                    .body(GraphDraftPatchResult.rejected(current.get(), preconditions));
        }
        try {
            GraphDraft patched = patchService.apply(current.get(), request)
                    .withIdentity(draftId, current.get().revision())
                    .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                            request.actor(),
                            request.changeSource(),
                            request.changeSummary(),
                            request.changedPaths(),
                            request.reason()
                    ));
            List<VisualDiagnostic> contractDiagnostics = draftContractDiagnostics(patched);
            if (!contractDiagnostics.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(GraphDraftPatchResult.rejected(current.get(), contractDiagnostics));
            }
            GraphDraft candidate = withExistingOrCurrentOperatorSnapshotState(current.get(), patched);
            return repository.saveIfRevision(draftId, request.expectedRevision(), candidate)
                    .map(stored -> ResponseEntity.ok(GraphDraftPatchResult.patched(stored)))
                    .orElseGet(() -> conflictResponse(draftId, request.expectedRevision(), current.get()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(GraphDraftPatchResult.rejected(current.get(), List.of(
                    VisualDiagnostic.error("visual.draft.patchInvalid", ex.getMessage(), "/patch")
            )));
        }
    }

    /**
     * Explicitly rebases stored operator fingerprint snapshots against the current operator catalog.
     *
     * @param draftId draft id
     * @param request optional revision and node selection preconditions
     * @return stored draft or rebase diagnostics
     */
    @PostMapping("/{draftId}/operator-fingerprints/rebase")
    public ResponseEntity<GraphDraftPatchResult> rebaseOperatorFingerprints(
            @PathVariable String draftId,
            @RequestBody(required = false) GraphDraftOperatorFingerprintRebaseRequest request) {
        Optional<GraphDraft> current = repository.find(draftId);
        if (current.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GraphDraft draft = current.get();
        GraphDraftOperatorFingerprintRebaseRequest effectiveRequest = request == null
                ? new GraphDraftOperatorFingerprintRebaseRequest(0, List.of())
                : request;
        long expectedRevision = effectiveRequest.expectedRevision();
        if (expectedRevision > 0 && expectedRevision != draft.revision()) {
            return conflictResponse(draftId, expectedRevision, draft);
        }

        List<String> requestedNodeIds = effectiveRequest.nodeIds();
        Map<String, String> activeFingerprints = currentOperatorFingerprints(draft);
        Map<String, OperatorDefinition> activeSnapshots = currentOperatorSnapshots(draft);
        List<VisualDiagnostic> diagnostics = operatorFingerprintRebaseDiagnostics(
                draft, requestedNodeIds, activeFingerprints);
        if (!diagnostics.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GraphDraftPatchResult.rejected(draft, diagnostics));
        }

        List<GraphDraft.DraftNode> targetNodes = operatorFingerprintRebaseTargets(draft, requestedNodeIds);
        Map<String, String> nextFingerprints = new LinkedHashMap<>(draft.operatorFingerprints());
        Map<String, OperatorDefinition> nextSnapshots = new LinkedHashMap<>(draft.operatorSnapshots());
        for (GraphDraft.DraftNode node : targetNodes) {
            nextFingerprints.put(node.id(), activeFingerprints.get(node.id()));
            nextSnapshots.put(node.id(), activeSnapshots.get(node.id()));
        }
        if (nextFingerprints.equals(draft.operatorFingerprints()) && nextSnapshots.equals(draft.operatorSnapshots())) {
            return ResponseEntity.ok(GraphDraftPatchResult.patched(draft));
        }

        GraphDraft candidate = draft.withOperatorSnapshotState(nextFingerprints, nextSnapshots)
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        effectiveRequest.effectiveActor(),
                        effectiveRequest.effectiveChangeSource(),
                        effectiveRequest.effectiveChangeSummary(),
                        operatorSnapshotRebaseChangedPaths(targetNodes),
                        effectiveRequest.reason()
                ));
        return repository.saveIfRevision(draftId, draft.revision(), candidate)
                .map(stored -> ResponseEntity.ok(GraphDraftPatchResult.patched(stored)))
                .orElseGet(() -> conflictResponse(draftId, draft.revision(), draft));
    }

    /**
     * Deletes a draft.
     *
     * @param draftId draft id
     * @param expectedRevision optional revision precondition
     * @return empty response
     */
    @DeleteMapping("/{draftId}")
    public ResponseEntity<Object> delete(@PathVariable String draftId,
                                         @RequestParam(defaultValue = "0") long expectedRevision,
                                         @RequestParam(defaultValue = "") String actor,
                                         @RequestParam(defaultValue = "") String changeSource,
                                         @RequestParam(defaultValue = "") String changeSummary,
                                         @RequestParam(defaultValue = "") String reason) {
        long revision = Math.max(0, expectedRevision);
        Optional<GraphDraft> current = repository.find(draftId);
        if (revision > 0 && current.isPresent() && current.get().revision() != revision) {
            return updateConflictResponse(draftId, revision, current.get());
        }
        repository.delete(draftId, GraphDraft.RevisionMetadata.patch(
                actor,
                changeSource.isBlank() ? "delete" : changeSource,
                changeSummary.isBlank() ? "Deleted draft." : changeSummary,
                List.of("/"),
                reason
        ));
        return ResponseEntity.noContent().build();
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public ResponseEntity<Object> delete(String draftId,
                                         long expectedRevision,
                                         String actor,
                                         String changeSource,
                                         String changeSummary) {
        return delete(draftId, expectedRevision, actor, changeSource, changeSummary, "");
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public ResponseEntity<Object> delete(String draftId, long expectedRevision) {
        return delete(draftId, expectedRevision, "", "", "", "");
    }

    /**
     * Validates a transient draft.
     *
     * @param draft draft body
     * @return validation result
     */
    @PostMapping("/validate")
    public VisualValidationResult validate(@RequestBody GraphDraft draft) {
        return validator.validate(draft);
    }

    /**
     * Compiles a transient draft into BLOGE DSL.
     *
     * @param draft draft body
     * @return generated DSL
     */
    @PostMapping("/compile")
    public DslGenerationResult compile(@RequestBody GraphDraft draft) {
        return runner.compile(draft);
    }

    /**
     * Runs a transient draft.
     *
     * @param request run request
     * @return run response
     */
    @PostMapping("/run")
    public VisualGraphRunResponse runTransient(@RequestBody VisualGraphRunRequest request) {
        VisualGraphRunResponse response = runner.run(request.draft(), request.context(), request.outputNode());
        return recordRun(VisualGraphRunRecord.transientDraft(request.draft(), request.context(), response),
                response);
    }

    /**
     * Runs a stored draft.
     *
     * @param draftId draft id
     * @param request run request
     * @return run response
     */
    @PostMapping("/{draftId}/run")
    public ResponseEntity<VisualGraphRunResponse> runStored(@PathVariable String draftId,
                                                            @RequestBody VisualStoredDraftRunRequest request) {
        return repository.find(draftId)
                .map(draft -> {
                    if (request.expectedRevision() > 0 && request.expectedRevision() != draft.revision()) {
                        return runConflictResponse(draftId, request.expectedRevision(), draft);
                    }
                    VisualGraphRunResponse response = runner.run(draft, request.context(), request.outputNode());
                    return ResponseEntity.ok(recordRun(VisualGraphRunRecord.storedDraft(draft, request.context(),
                            response), response));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private VisualGraphRunResponse recordRun(VisualGraphRunRecord record, VisualGraphRunResponse response) {
        VisualGraphRunRecord stored = runRepository.create(record);
        return response.withRunId(stored.runId());
    }

    /**
     * Publishes a stored draft as an immutable visual graph artifact.
     *
     * @param draftId draft id
     * @param request optional revision precondition and warning acknowledgement
     * @return publication result
     */
    @PostMapping("/{draftId}/publish")
    public ResponseEntity<VisualGraphPublicationResult> publish(@PathVariable String draftId,
                                                                @RequestBody(required = false)
                                                                VisualGraphPublishRequest request) {
        VisualGraphPublishRequest effectiveRequest = request == null
                ? new VisualGraphPublishRequest(0)
                : request;
        return repository.find(draftId)
                .map(draft -> publishDraft(draftId,
                        effectiveRequest.expectedRevision(),
                        effectiveRequest.ackWarnings(),
                        effectiveRequest.artifactKind(),
                        effectiveRequest,
                        draft))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private GraphDraft withCurrentOperatorSnapshotState(GraphDraft draft) {
        Map<String, OperatorDefinition> snapshots = currentOperatorSnapshots(draft);
        return draft.withOperatorSnapshotState(fingerprintsForSnapshots(snapshots), snapshots);
    }

    private GraphDraft withMissingCurrentOperatorSnapshotState(GraphDraft draft) {
        Map<String, String> fingerprints = new LinkedHashMap<>(currentOperatorFingerprints(draft));
        fingerprints.putAll(fingerprintsForDraftNodes(draft, draft.operatorFingerprints()));
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>(currentOperatorSnapshots(draft));
        snapshots.putAll(matchingSnapshotsForDraftNodes(draft, draft.operatorSnapshots()));
        return draft.withOperatorSnapshotState(fingerprints, snapshots);
    }

    private GraphDraft withCurrentOrProvidedOperatorSnapshotState(GraphDraft draft) {
        Map<String, String> fingerprints = new LinkedHashMap<>(fingerprintsForDraftNodes(draft,
                draft.operatorFingerprints()));
        fingerprints.putAll(currentOperatorFingerprints(draft));
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>(matchingSnapshotsForDraftNodes(draft,
                draft.operatorSnapshots()));
        snapshots.putAll(currentOperatorSnapshots(draft));
        return draft.withOperatorSnapshotState(fingerprints, snapshots);
    }

    private GraphDraft withExistingOrCurrentOperatorSnapshotState(GraphDraft current, GraphDraft draft) {
        Map<String, String> activeFingerprints = currentOperatorFingerprints(draft);
        Map<String, OperatorDefinition> activeSnapshots = currentOperatorSnapshots(draft);
        Map<String, String> fingerprints = new LinkedHashMap<>();
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition existingSnapshot = current.operatorSnapshots().get(node.id());
            String existingFingerprint = current.operatorFingerprints().get(node.id());
            if (existingFingerprint != null && !existingFingerprint.isBlank()
                    && (existingSnapshot == null || snapshotMatchesNode(existingSnapshot, node))) {
                fingerprints.put(node.id(), existingFingerprint);
            } else {
                Optional.ofNullable(activeFingerprints.get(node.id()))
                        .ifPresent(fingerprint -> fingerprints.put(node.id(), fingerprint));
            }
            if (snapshotMatchesNode(existingSnapshot, node)) {
                snapshots.put(node.id(), existingSnapshot);
            } else {
                Optional.ofNullable(activeSnapshots.get(node.id()))
                        .ifPresent(snapshot -> snapshots.put(node.id(), snapshot));
            }
        }
        return draft.withOperatorSnapshotState(fingerprints, snapshots);
    }

    private ResponseEntity<VisualGraphPublicationResult> publishDraft(String draftId,
                                                                      long expectedRevision,
                                                                      boolean ackWarnings,
                                                                      String artifactKind,
                                                                      VisualGraphPublishRequest request,
                                                                      GraphDraft draft) {
        if (!VisualGraphPublishRequest.supportedArtifactKind(artifactKind)) {
            return ResponseEntity.badRequest()
                    .body(VisualGraphPublicationResult.rejected(List.of(VisualDiagnostic.error(
                            "visual.publication.artifactKindUnsupported",
                            "Unsupported visual graph publication artifact kind: " + artifactKind,
                            "/artifactKind"
                    ))));
        }
        if (expectedRevision > 0 && expectedRevision != draft.revision()) {
            return publishConflictResponse(draftId, expectedRevision, draft);
        }
        VisualValidationResult validation = validator.validate(draft);
        if (!validation.valid()) {
            return ResponseEntity.badRequest()
                    .body(VisualGraphPublicationResult.rejected(validation));
        }
        boolean hasWarnings = containsWarnings(validation.diagnostics());
        if (!ackWarnings && hasWarnings) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(VisualGraphPublicationResult.rejected(validation));
        }
        if (ackWarnings && hasWarnings) {
            List<VisualDiagnostic> governanceEvidence = publishGovernanceEvidenceDiagnostics(request);
            if (!governanceEvidence.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(VisualGraphPublicationResult.rejected(governanceEvidence, validation));
            }
        }
        DslGenerationResult generation = runner.compile(draft);
        if (VisualGraphPublishRequest.ARTIFACT_EXECUTABLE.equals(artifactKind) && !generation.generated()) {
            return ResponseEntity.badRequest()
                    .body(VisualGraphPublicationResult.rejected(generation.diagnostics(), validation));
        }

        GraphDraft snapshot = withMissingCurrentOperatorSnapshotState(draft);
        List<OperatorDefinition> snapshots = operatorSnapshots(snapshot);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(snapshot, catalog);
        VisualGraphPublication candidate = VisualGraphPublishRequest.ARTIFACT_DESIGN.equals(artifactKind)
                ? VisualGraphPublication.design(snapshot, snapshots, validation, generation, dependencyReport,
                        request.publicationMetadata())
                : VisualGraphPublication.from(snapshot, snapshots, validation, generation, dependencyReport,
                        request.publicationMetadata());
        VisualGraphPublication publication = publicationRepository.create(candidate);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(VisualGraphPublicationResult.published(publication));
    }

    private static List<VisualDiagnostic> publishGovernanceEvidenceDiagnostics(VisualGraphPublishRequest request) {
        VisualGraphPublishRequest safeRequest = request == null ? new VisualGraphPublishRequest(0) : request;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> metadata = Map.of("requiredFor", List.of("ackWarnings"),
                "artifactKind", safeRequest.artifactKind());
        if (safeRequest.actor().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.publication.governanceEvidenceMissing",
                    "Visual graph publication warning acknowledgement requires actor.",
                    "/actor",
                    metadata));
        }
        if (safeRequest.reason().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.publication.governanceEvidenceMissing",
                    "Visual graph publication warning acknowledgement requires reason.",
                    "/reason",
                    metadata));
        }
        return diagnostics;
    }

    private static boolean containsWarnings(List<VisualDiagnostic> diagnostics) {
        return diagnostics.stream()
                .anyMatch(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()));
    }

    private ResponseEntity<VisualGraphPublicationResult> publishConflictResponse(String draftId,
                                                                                 long expectedRevision,
                                                                                 GraphDraft fallback) {
        GraphDraft current = repository.find(draftId).orElse(fallback);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(VisualGraphPublicationResult.rejected(List.of(revisionConflictDiagnostic(
                        expectedRevision, current.revision()))));
    }

    private ResponseEntity<VisualGraphRunResponse> runConflictResponse(String draftId,
                                                                       long expectedRevision,
                                                                       GraphDraft fallback) {
        GraphDraft current = repository.find(draftId).orElse(fallback);
        VisualDiagnostic diagnostic = revisionConflictDiagnostic(expectedRevision, current.revision());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new VisualGraphRunResponse(
                        false,
                        false,
                        false,
                        current.graphName(),
                        current.output().nodeId(),
                        null,
                        Map.of(),
                        Map.of(),
                        0,
                        List.of(diagnostic),
                        List.of(diagnostic.message()),
                        null,
                        null,
                        ""
                ));
    }

    private Map<String, String> currentOperatorFingerprints(GraphDraft draft) {
        return fingerprintsForSnapshots(currentOperatorSnapshots(draft));
    }

    private Map<String, OperatorDefinition> currentOperatorSnapshots(GraphDraft draft) {
        Map<String, OperatorDefinition> activeOperators = new LinkedHashMap<>();
        catalog.list(new OperatorCatalogQuery(
                "",
                List.of(),
                false,
                false,
                draft.tenantId(),
                draft.namespace(),
                draft.environment()
        )).forEach(operator -> activeOperators.put(operator.operatorRef(), operator));

        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            Optional.ofNullable(activeOperators.get(node.operatorRef()))
                    .ifPresent(operator -> snapshots.put(node.id(), operator));
        }
        return snapshots;
    }

    private static Map<String, String> fingerprintsForSnapshots(Map<String, OperatorDefinition> snapshots) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        snapshots.forEach((nodeId, snapshot) -> Optional.ofNullable(snapshot)
                .map(OperatorDefinition::fingerprint)
                .filter(fingerprint -> !fingerprint.isBlank())
                .ifPresent(fingerprint -> fingerprints.put(nodeId, fingerprint)));
        return fingerprints;
    }

    private static Map<String, String> fingerprintsForDraftNodes(GraphDraft draft,
                                                                 Map<String, String> fingerprints) {
        Map<String, String> result = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            String fingerprint = fingerprints.get(node.id());
            if (fingerprint != null && !fingerprint.isBlank()) {
                result.put(node.id(), fingerprint);
            }
        }
        return result;
    }

    private static Map<String, OperatorDefinition> matchingSnapshotsForDraftNodes(
            GraphDraft draft,
            Map<String, OperatorDefinition> snapshots) {
        Map<String, OperatorDefinition> result = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition snapshot = snapshots.get(node.id());
            if (snapshotMatchesNode(snapshot, node)) {
                result.put(node.id(), snapshot);
            }
        }
        return result;
    }

    private static boolean snapshotMatchesNode(OperatorDefinition snapshot, GraphDraft.DraftNode node) {
        return snapshot != null && node != null && snapshot.operatorRef().equals(node.operatorRef());
    }

    private static List<String> operatorSnapshotRebaseChangedPaths(List<GraphDraft.DraftNode> nodes) {
        List<String> changedPaths = new ArrayList<>();
        for (GraphDraft.DraftNode node : nodes) {
            String segment = jsonPointerSegment(node.id());
            changedPaths.add("/operatorFingerprints/" + segment);
            changedPaths.add("/operatorSnapshots/" + segment);
        }
        return changedPaths;
    }

    private static GraphDraft withBundleOperatorSnapshots(GraphDraft draft, List<OperatorDefinition> snapshots) {
        if (draft == null || snapshots == null || snapshots.isEmpty()) {
            return draft;
        }
        Map<String, OperatorDefinition> byRef = new LinkedHashMap<>();
        for (OperatorDefinition snapshot : snapshots) {
            if (snapshot != null) {
                byRef.putIfAbsent(snapshot.operatorRef(), snapshot);
            }
        }
        Map<String, OperatorDefinition> nodeSnapshots = new LinkedHashMap<>(draft.operatorSnapshots());
        Map<String, String> fingerprints = new LinkedHashMap<>(draft.operatorFingerprints());
        for (GraphDraft.DraftNode node : draft.nodes()) {
            if (!snapshotMatchesNode(nodeSnapshots.get(node.id()), node)) {
                Optional.ofNullable(byRef.get(node.operatorRef()))
                        .ifPresent(snapshot -> nodeSnapshots.put(node.id(), snapshot));
            }
            Optional.ofNullable(nodeSnapshots.get(node.id()))
                    .map(OperatorDefinition::fingerprint)
                    .filter(fingerprint -> !fingerprint.isBlank())
                    .ifPresent(fingerprint -> fingerprints.putIfAbsent(node.id(), fingerprint));
        }
        return draft.withOperatorSnapshotState(fingerprints, nodeSnapshots);
    }

    private static List<VisualDiagnostic> operatorFingerprintRebaseDiagnostics(
            GraphDraft draft,
            List<String> requestedNodeIds,
            Map<String, String> activeFingerprints) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        List<GraphDraft.DraftNode> targetNodes = operatorFingerprintRebaseTargets(draft, requestedNodeIds);
        if (requestedNodeIds.isEmpty() && targetNodes.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.operatorFingerprintRebase.noTargets",
                    "No draft nodes are available for operator fingerprint rebase.",
                    "/nodes"));
        }

        Map<String, GraphDraft.DraftNode> nodesById = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            nodesById.put(node.id(), node);
        }
        for (int i = 0; i < requestedNodeIds.size(); i++) {
            String nodeId = requestedNodeIds.get(i);
            if (!nodesById.containsKey(nodeId)) {
                diagnostics.add(VisualDiagnostic.error("visual.operatorFingerprintRebase.nodeUnknown",
                        "Cannot rebase operator fingerprint for unknown node '%s'.".formatted(nodeId),
                        "/nodeIds/" + i));
            }
        }

        for (GraphDraft.DraftNode node : targetNodes) {
            String activeFingerprint = activeFingerprints.get(node.id());
            if (activeFingerprint == null || activeFingerprint.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.operatorFingerprintRebase.operatorUnavailable",
                        "Cannot rebase node '%s' because operator '%s' is not available in the current catalog scope."
                                .formatted(node.id(), node.operatorRef()),
                        "/nodes/" + nodeIndex(draft, node.id()) + "/operatorRef"));
            }
        }
        return diagnostics;
    }

    private static List<GraphDraft.DraftNode> operatorFingerprintRebaseTargets(
            GraphDraft draft,
            List<String> requestedNodeIds) {
        if (requestedNodeIds.isEmpty()) {
            return draft.nodes();
        }
        return requestedNodeIds.stream()
                .map(nodeId -> draft.nodes().stream()
                        .filter(node -> node.id().equals(nodeId))
                        .findFirst())
                .flatMap(Optional::stream)
                .toList();
    }

    private static int nodeIndex(GraphDraft draft, String nodeId) {
        for (int i = 0; i < draft.nodes().size(); i++) {
            if (draft.nodes().get(i).id().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    private List<OperatorDefinition> operatorSnapshots(GraphDraft draft) {
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>();
        Map<String, OperatorDefinition> currentSnapshots = currentOperatorSnapshots(draft);
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition snapshot = draft.operatorSnapshots().get(node.id());
            if (!snapshotMatchesNode(snapshot, node)) {
                snapshot = currentSnapshots.get(node.id());
            }
            if (snapshot != null) {
                snapshots.putIfAbsent(snapshot.operatorRef() + "@" + snapshot.fingerprint(), snapshot);
            }
        }
        return List.copyOf(snapshots.values());
    }

    private static void requireSupportedDraftContract(GraphDraft draft) {
        List<VisualDiagnostic> diagnostics = draftContractDiagnostics(draft);
        if (!diagnostics.isEmpty()) {
            throw new DraftContractException(diagnostics);
        }
    }

    private static List<VisualDiagnostic> draftContractDiagnostics(GraphDraft draft) {
        if (draft == null) {
            return List.of(VisualDiagnostic.error("visual.draft.missing", "Graph draft is required.", "/"));
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!GraphDraft.SCHEMA_VERSION.equals(draft.schemaVersion())) {
            String actual = draft.schemaVersion();
            diagnostics.add(VisualDiagnostic.error("visual.draft.schemaVersion.unsupported",
                    "Graph draft schemaVersion '%s' is unsupported; visual authoring supports [%s]."
                            .formatted(actual, GraphDraft.SCHEMA_VERSION),
                    "/schemaVersion",
                    Map.of("actual", actual, "expected", GraphDraft.SCHEMA_VERSION)));
        }
        if (!GraphDraft.isSupportedStatus(draft.status())) {
            diagnostics.add(VisualDiagnostic.error("visual.draft.status.unsupported",
                    "Graph draft status '%s' is unsupported; visual authoring supports [%s]."
                            .formatted(draft.status(), GraphDraft.STATUS_DRAFT),
                    "/status"));
        }
        return diagnostics;
    }

    private static List<VisualDiagnostic> exportBundleContractDiagnostics(GraphDraftExportBundle bundle) {
        if (bundle == null) {
            return List.of(VisualDiagnostic.error("visual.draftExport.missing",
                    "Graph draft export bundle is required.", "/"));
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!GraphDraftExportBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            String actual = bundle.schemaVersion();
            diagnostics.add(VisualDiagnostic.error("visual.draftExport.schemaVersion.unsupported",
                    "Graph draft export schemaVersion '%s' is unsupported; visual authoring supports [%s]."
                            .formatted(actual, GraphDraftExportBundle.SCHEMA_VERSION),
                    "/schemaVersion",
                    Map.of("actual", actual, "expected", GraphDraftExportBundle.SCHEMA_VERSION)));
        }
        if (bundle.draft() == null) {
            diagnostics.add(VisualDiagnostic.error("visual.draftExport.draftMissing",
                    "Graph draft export bundle must include a draft snapshot.", "/draft"));
        } else {
            draftContractDiagnostics(bundle.draft()).stream()
                    .map(VisualGraphDraftController::draftBundleDiagnostic)
                    .forEach(diagnostics::add);
        }
        return diagnostics;
    }

    private static VisualDiagnostic draftBundleDiagnostic(VisualDiagnostic diagnostic) {
        String target = diagnostic.target().startsWith("/")
                ? "/draft" + diagnostic.target()
                : "/draft/" + diagnostic.target();
        return new VisualDiagnostic(diagnostic.level(), diagnostic.code(), diagnostic.message(), target,
                diagnostic.line(), diagnostic.column(), diagnostic.metadata());
    }

    private ResponseEntity<GraphDraftPatchResult> conflictResponse(String draftId,
                                                                   long expectedRevision,
                                                                   GraphDraft fallback) {
        GraphDraft current = repository.find(draftId).orElse(fallback);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(GraphDraftPatchResult.rejected(current, List.of(revisionConflictDiagnostic(
                        expectedRevision, current.revision()))));
    }

    private ResponseEntity<Object> updateConflictResponse(String draftId,
                                                          long expectedRevision,
                                                          GraphDraft fallback) {
        GraphDraft current = repository.find(draftId).orElse(fallback);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(GraphDraftPatchResult.rejected(current, List.of(revisionConflictDiagnostic(
                        expectedRevision, current.revision()))));
    }

    private static VisualDiagnostic revisionConflictDiagnostic(long expectedRevision, long currentRevision) {
        return VisualDiagnostic.error("visual.draft.revisionConflict",
                "Draft revision conflict: expected %d but current revision is %d."
                        .formatted(expectedRevision, currentRevision),
                "/expectedRevision");
    }

    private static boolean hasDiagnostic(List<VisualDiagnostic> diagnostics, String code) {
        return diagnostics.stream().anyMatch(diagnostic -> code.equals(diagnostic.code()));
    }

    private static String jsonPointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    /**
     * @param ex invalid draft payload
     * @return structured bad request response
     */
    @ExceptionHandler(DraftContractException.class)
    public ResponseEntity<VisualValidationResult> handleUnsupportedDraftContract(DraftContractException ex) {
        return ResponseEntity.badRequest().body(new VisualValidationResult(false, ex.diagnostics()));
    }

    /**
     * @param ex invalid draft payload
     * @return structured bad request response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<VisualValidationResult> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new VisualValidationResult(false, List.of(
                VisualDiagnostic.error("visual.draft.invalid", ex.getMessage(), "/")
        )));
    }

    private static final class DraftContractException extends IllegalArgumentException {
        private final List<VisualDiagnostic> diagnostics;

        private DraftContractException(List<VisualDiagnostic> diagnostics) {
            super(diagnostics.isEmpty() ? "Unsupported graph draft contract." : diagnostics.getFirst().message());
            this.diagnostics = diagnostics;
        }

        private List<VisualDiagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
