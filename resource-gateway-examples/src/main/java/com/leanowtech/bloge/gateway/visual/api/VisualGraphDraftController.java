package com.leanowtech.bloge.gateway.visual.api;

import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
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
import org.springframework.web.bind.annotation.GetMapping;
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
                                      VisualGraphPublicationRepository publicationRepository) {
        this.repository = repository;
        this.validator = validator;
        this.generator = generator;
        this.runner = runner;
        this.catalog = catalog;
        this.publicationRepository = publicationRepository;
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
}
