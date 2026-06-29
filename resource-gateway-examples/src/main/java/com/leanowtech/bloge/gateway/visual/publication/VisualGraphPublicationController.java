package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * Public API for immutable visual graph publications.
 */
@RestController
@RequestMapping("/api/visual/publications")
public class VisualGraphPublicationController {

    private final VisualGraphPublicationRepository repository;
    private final VisualGraphRunService runner;

    /**
     * @param repository publication repository
     * @param runner publication runner
     */
    public VisualGraphPublicationController(VisualGraphPublicationRepository repository,
                                            VisualGraphRunService runner) {
        this.repository = repository;
        this.runner = runner;
    }

    /**
     * @return all publications
     */
    @GetMapping
    public Collection<VisualGraphPublication> list() {
        return repository.all();
    }

    /**
     * Gets a publication.
     *
     * @param publicationId publication id
     * @return publication when present
     */
    @GetMapping("/{publicationId}")
    public ResponseEntity<VisualGraphPublication> get(@PathVariable String publicationId) {
        return repository.find(publicationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Runs a published immutable visual graph artifact.
     *
     * @param publicationId publication id
     * @param request run request
     * @return run response when publication exists
     */
    @PostMapping("/{publicationId}/run")
    public ResponseEntity<VisualGraphRunResponse> run(@PathVariable String publicationId,
                                                      @RequestBody VisualStoredDraftRunRequest request) {
        return repository.find(publicationId)
                .map(publication -> ResponseEntity.ok(runner.run(publication, request.context(), request.outputNode())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
