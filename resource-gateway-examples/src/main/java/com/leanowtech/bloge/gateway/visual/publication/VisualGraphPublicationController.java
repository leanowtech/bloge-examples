package com.leanowtech.bloge.gateway.visual.publication;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * @param repository publication repository
     */
    public VisualGraphPublicationController(VisualGraphPublicationRepository repository) {
        this.repository = repository;
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
}
