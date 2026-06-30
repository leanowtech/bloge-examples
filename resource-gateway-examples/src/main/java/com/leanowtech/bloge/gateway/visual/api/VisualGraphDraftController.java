package com.leanowtech.bloge.gateway.visual.api;

import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
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
    private final GraphDraftDslGenerator generator;
    private final VisualGraphRunService runner;
    private final VisualOperatorCatalog catalog;
    private final VisualGraphPublicationRepository publicationRepository;
    private final GraphDraftPatchService patchService;

    /**
     * @param repository draft repository
     * @param validator draft validator
     * @param generator DSL generator
     * @param runner draft runner
     */
    public VisualGraphDraftController(GraphDraftRepository repository,
                                      GraphDraftValidator validator,
                                      GraphDraftDslGenerator generator,
                                      VisualGraphRunService runner,
                                      VisualOperatorCatalog catalog,
                                      VisualGraphPublicationRepository publicationRepository,
                                      GraphDraftPatchService patchService) {
        this.repository = repository;
        this.validator = validator;
        this.generator = generator;
        this.runner = runner;
        this.catalog = catalog;
        this.publicationRepository = publicationRepository;
        this.patchService = patchService;
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
        return repository.save(withCurrentOperatorFingerprints(draft));
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
    public GraphDraft update(@PathVariable String draftId, @RequestBody GraphDraft draft) {
        return repository.save(withCurrentOperatorFingerprints(draft.withIdentity(draftId, draft.revision())));
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
            GraphDraft candidate = withCurrentOperatorFingerprints(patched);
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
     * Deletes a draft.
     *
     * @param draftId draft id
     * @return empty response
     */
    @DeleteMapping("/{draftId}")
    public ResponseEntity<Void> delete(@PathVariable String draftId) {
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
        VisualValidationResult validation = validator.validate(draft);
        if (!validation.valid()) {
            return new DslGenerationResult(false, "", validation.diagnostics());
        }
        return generator.generate(draft);
    }

    /**
     * Runs a transient draft.
     *
     * @param request run request
     * @return run response
     */
    @PostMapping("/run")
    public VisualGraphRunResponse runTransient(@RequestBody VisualGraphRunRequest request) {
        return runner.run(request.draft(), request.context(), request.outputNode());
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
                .map(draft -> ResponseEntity.ok(runner.run(draft, request.context(), request.outputNode())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Publishes a stored draft as an immutable visual graph artifact.
     *
     * @param draftId draft id
     * @return publication result
     */
    @PostMapping("/{draftId}/publish")
    public ResponseEntity<VisualGraphPublicationResult> publish(@PathVariable String draftId) {
        return repository.find(draftId)
                .map(this::publishDraft)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private GraphDraft withCurrentOperatorFingerprints(GraphDraft draft) {
        return draft.withOperatorFingerprints(currentOperatorFingerprints(draft));
    }

    private ResponseEntity<VisualGraphPublicationResult> publishDraft(GraphDraft draft) {
        VisualValidationResult validation = validator.validate(draft);
        if (!validation.valid()) {
            return ResponseEntity.badRequest()
                    .body(VisualGraphPublicationResult.rejected(validation.diagnostics()));
        }
        DslGenerationResult generation = generator.generate(draft);
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

    private Map<String, String> fingerprintsWithMissingCurrentValues(GraphDraft draft) {
        Map<String, String> fingerprints = new LinkedHashMap<>(currentOperatorFingerprints(draft));
        fingerprints.putAll(draft.operatorFingerprints());
        return fingerprints;
    }

    private Map<String, String> currentOperatorFingerprints(GraphDraft draft) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            catalog.find(node.operatorRef())
                    .map(OperatorDefinition::fingerprint)
                    .ifPresent(fingerprint -> fingerprints.put(node.id(), fingerprint));
        }
        return fingerprints;
    }

    private List<OperatorDefinition> operatorSnapshots(GraphDraft draft) {
        return draft.nodes().stream()
                .map(GraphDraft.DraftNode::operatorRef)
                .distinct()
                .map(catalog::find)
                .flatMap(Optional::stream)
                .toList();
    }

    private ResponseEntity<GraphDraftPatchResult> conflictResponse(String draftId,
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
}
