package com.leanowtech.bloge.gateway.visual.catalog;

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

/**
 * Admin API for user-provided visual operator libraries.
 */
@RestController
@RequestMapping("/admin/visual-operator-libraries")
public class OperatorLibraryAdminController {

    private final OperatorLibraryRegistry registry;

    /**
     * @param registry library registry
     */
    public OperatorLibraryAdminController(OperatorLibraryRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return all libraries
     */
    @GetMapping
    public Collection<OperatorLibrary> list() {
        return registry.all();
    }

    /**
     * Imports a library.
     *
     * @param library library body
     * @return stored library
     */
    @PostMapping
    public ResponseEntity<OperatorLibrary> create(@RequestBody OperatorLibrary library) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registry.upsert(library));
    }

    /**
     * @param libraryId library id
     * @return matching library
     */
    @GetMapping("/{libraryId}")
    public ResponseEntity<OperatorLibrary> get(@PathVariable String libraryId) {
        return registry.find(libraryId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Replaces a library.
     *
     * @param libraryId library id
     * @param library library body
     * @return stored library
     */
    @PutMapping("/{libraryId}")
    public OperatorLibrary update(@PathVariable String libraryId,
                                  @RequestBody OperatorLibrary library) {
        if (!libraryId.equals(library.libraryId())) {
            throw new IllegalArgumentException("Path libraryId '%s' does not match body libraryId '%s'"
                    .formatted(libraryId, library.libraryId()));
        }
        return registry.upsert(library);
    }

    /**
     * Deletes a library.
     *
     * @param libraryId library id
     * @return empty response
     */
    @DeleteMapping("/{libraryId}")
    public ResponseEntity<Void> delete(@PathVariable String libraryId) {
        registry.delete(libraryId);
        return ResponseEntity.noContent().build();
    }
}
