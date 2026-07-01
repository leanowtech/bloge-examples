package com.leanowtech.bloge.gateway.visual.api;

import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftExportBundle;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftImportResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftOperatorFingerprintRebaseRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
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
     * @return all stored drafts
     */
    @GetMapping
    public Collection<GraphDraft> list() {
        return repository.all();
    }

    /**
     * Creates a draft.
     *
     * @param draft draft body
     * @return stored draft
     */
    @PostMapping
    public GraphDraft create(@RequestBody GraphDraft draft) {
        requireSupportedDraftContract(draft);
        return repository.save(withCurrentOperatorFingerprints(draft.withIdentity("", 0))
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        "visual-canvas",
                        "api",
                        "Saved draft.",
                        List.of()
                )));
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
     * Exports a stored draft as a portable package with current operator snapshots.
     *
     * @param draftId draft id
     * @return export bundle when the draft exists
     */
    @GetMapping("/{draftId}/export")
    public ResponseEntity<GraphDraftExportBundle> exportDraft(@PathVariable String draftId) {
        return repository.find(draftId)
                .map(draft -> ResponseEntity.ok(GraphDraftExportBundle.from(
                        draft,
                        operatorSnapshots(draft),
                        validator.validate(draft).diagnostics()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Imports a portable draft package as a new stored draft.
     *
     * @param bundle exported draft package
     * @return import result with a stored draft or contract diagnostics
     */
    @PostMapping("/import")
    public ResponseEntity<GraphDraftImportResult> importDraft(@RequestBody GraphDraftExportBundle bundle) {
        List<VisualDiagnostic> diagnostics = exportBundleContractDiagnostics(bundle);
        if (!diagnostics.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GraphDraftImportResult.rejected(diagnostics));
        }
        GraphDraft imported = bundle.draft()
                .withIdentity("", 0)
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        "visual-canvas",
                        "import",
                        "Imported draft from export bundle.",
                        List.of()
                ));
        GraphDraft stored = repository.save(withCurrentOperatorFingerprints(imported));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GraphDraftImportResult.imported(stored, validator.validate(stored).diagnostics()));
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
     * Updates a draft.
     *
     * @param draftId draft id
     * @param draft draft body
     * @return stored draft
     */
    @PutMapping("/{draftId}")
    public ResponseEntity<Object> update(@PathVariable String draftId, @RequestBody GraphDraft draft) {
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
        GraphDraft candidate = withExistingOrCurrentOperatorFingerprints(current.get(),
                draft.withIdentity(draftId, expectedRevision).withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        "visual-canvas",
                        "api",
                        "Saved draft.",
                        List.of()
                )));
        return repository.saveIfRevision(draftId, expectedRevision, candidate)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> updateConflictResponse(draftId, expectedRevision, current.get()));
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
                            request.changedPaths()
                    ));
            List<VisualDiagnostic> contractDiagnostics = draftContractDiagnostics(patched);
            if (!contractDiagnostics.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(GraphDraftPatchResult.rejected(current.get(), contractDiagnostics));
            }
            GraphDraft candidate = withExistingOrCurrentOperatorFingerprints(current.get(), patched);
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
        long expectedRevision = request == null ? 0 : request.expectedRevision();
        if (expectedRevision > 0 && expectedRevision != draft.revision()) {
            return conflictResponse(draftId, expectedRevision, draft);
        }

        List<String> requestedNodeIds = request == null ? List.of() : request.nodeIds();
        Map<String, String> activeFingerprints = currentOperatorFingerprints(draft);
        List<VisualDiagnostic> diagnostics = operatorFingerprintRebaseDiagnostics(
                draft, requestedNodeIds, activeFingerprints);
        if (!diagnostics.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(GraphDraftPatchResult.rejected(draft, diagnostics));
        }

        List<GraphDraft.DraftNode> targetNodes = operatorFingerprintRebaseTargets(draft, requestedNodeIds);
        Map<String, String> nextFingerprints = new LinkedHashMap<>(draft.operatorFingerprints());
        for (GraphDraft.DraftNode node : targetNodes) {
            nextFingerprints.put(node.id(), activeFingerprints.get(node.id()));
        }
        if (nextFingerprints.equals(draft.operatorFingerprints())) {
            return ResponseEntity.ok(GraphDraftPatchResult.patched(draft));
        }

        GraphDraft candidate = draft.withOperatorFingerprints(nextFingerprints)
                .withRevisionMetadata(GraphDraft.RevisionMetadata.patch(
                        "visual-canvas",
                        "operator-fingerprint-rebase",
                        "Rebased operator fingerprint snapshot(s).",
                        targetNodes.stream()
                                .map(node -> "/operatorFingerprints/" + jsonPointerSegment(node.id()))
                                .toList()
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
                                         @RequestParam(defaultValue = "0") long expectedRevision) {
        long revision = Math.max(0, expectedRevision);
        Optional<GraphDraft> current = repository.find(draftId);
        if (revision > 0 && current.isPresent() && current.get().revision() != revision) {
            return updateConflictResponse(draftId, revision, current.get());
        }
        repository.delete(draftId);
        return ResponseEntity.noContent().build();
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
     * @param request optional revision precondition
     * @return publication result
     */
    @PostMapping("/{draftId}/publish")
    public ResponseEntity<VisualGraphPublicationResult> publish(@PathVariable String draftId,
                                                                @RequestBody(required = false)
                                                                VisualGraphPublishRequest request) {
        return repository.find(draftId)
                .map(draft -> publishDraft(draftId, request == null ? 0 : request.expectedRevision(), draft))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private GraphDraft withCurrentOperatorFingerprints(GraphDraft draft) {
        return draft.withOperatorFingerprints(currentOperatorFingerprints(draft));
    }

    private GraphDraft withMissingCurrentOperatorFingerprints(GraphDraft draft) {
        return draft.withOperatorFingerprints(fingerprintsWithMissingCurrentValues(draft));
    }

    private GraphDraft withExistingOrCurrentOperatorFingerprints(GraphDraft current, GraphDraft draft) {
        return draft.withOperatorFingerprints(fingerprintsWithExistingOrCurrentValues(current, draft));
    }

    private ResponseEntity<VisualGraphPublicationResult> publishDraft(String draftId,
                                                                      long expectedRevision,
                                                                      GraphDraft draft) {
        if (expectedRevision > 0 && expectedRevision != draft.revision()) {
            return publishConflictResponse(draftId, expectedRevision, draft);
        }
        VisualValidationResult validation = validator.validate(draft);
        if (!validation.valid()) {
            return ResponseEntity.badRequest()
                    .body(VisualGraphPublicationResult.rejected(validation.diagnostics()));
        }
        DslGenerationResult generation = runner.compile(draft);
        if (!generation.generated()) {
            return ResponseEntity.badRequest()
                    .body(VisualGraphPublicationResult.rejected(generation.diagnostics()));
        }

        GraphDraft snapshot = draft.withOperatorFingerprints(fingerprintsWithMissingCurrentValues(draft));
        VisualGraphPublication publication = publicationRepository.create(VisualGraphPublication.from(
                snapshot,
                operatorSnapshots(snapshot),
                validation,
                generation
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(VisualGraphPublicationResult.published(publication));
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

    private Map<String, String> fingerprintsWithMissingCurrentValues(GraphDraft draft) {
        Map<String, String> fingerprints = new LinkedHashMap<>(currentOperatorFingerprints(draft));
        fingerprints.putAll(draft.operatorFingerprints());
        return fingerprints;
    }

    private Map<String, String> fingerprintsWithExistingOrCurrentValues(GraphDraft current, GraphDraft draft) {
        Map<String, String> activeFingerprints = currentOperatorFingerprints(draft);
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            String existing = current.operatorFingerprints().get(node.id());
            if (existing != null && !existing.isBlank()) {
                fingerprints.put(node.id(), existing);
                continue;
            }
            Optional.ofNullable(activeFingerprints.get(node.id()))
                    .ifPresent(fingerprint -> fingerprints.put(node.id(), fingerprint));
        }
        return fingerprints;
    }

    private Map<String, String> currentOperatorFingerprints(GraphDraft draft) {
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

        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            Optional.ofNullable(activeOperators.get(node.operatorRef()))
                    .map(OperatorDefinition::fingerprint)
                    .ifPresent(fingerprint -> fingerprints.put(node.id(), fingerprint));
        }
        return fingerprints;
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
        return draft.nodes().stream()
                .map(GraphDraft.DraftNode::operatorRef)
                .distinct()
                .map(catalog::find)
                .flatMap(Optional::stream)
                .toList();
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
            diagnostics.add(VisualDiagnostic.error("visual.draft.schemaVersion.unsupported",
                    "Graph draft schemaVersion '%s' is unsupported; visual authoring supports [%s]."
                            .formatted(draft.schemaVersion(), GraphDraft.SCHEMA_VERSION),
                    "/schemaVersion"));
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
            diagnostics.add(VisualDiagnostic.error("visual.draftExport.schemaVersion.unsupported",
                    "Graph draft export schemaVersion '%s' is unsupported; visual authoring supports [%s]."
                            .formatted(bundle.schemaVersion(), GraphDraftExportBundle.SCHEMA_VERSION),
                    "/schemaVersion"));
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
                diagnostic.line(), diagnostic.column());
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
