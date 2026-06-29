package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    private final OperatorLibraryValidator validator;

    /**
     * @param registry library registry
     * @param validator library validator
     */
    public OperatorLibraryAdminController(OperatorLibraryRegistry registry,
                                          OperatorLibraryValidator validator) {
        this.registry = registry;
        this.validator = validator;
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
    public ResponseEntity<?> create(@RequestBody OperatorLibrary library) {
        VisualValidationResult validation = validator.validate(library);
        if (!validation.valid()) {
            return ResponseEntity.badRequest().body(validation);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(registry.upsert(library));
    }

    /**
     * Validates a library without storing it.
     *
     * @param library library body
     * @return structured validation diagnostics
     */
    @PostMapping("/validate")
    public VisualValidationResult validate(@RequestBody OperatorLibrary library) {
        return validator.validate(library);
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
    public ResponseEntity<?> update(@PathVariable String libraryId,
                                    @RequestBody OperatorLibrary library) {
        if (!libraryId.equals(library.libraryId())) {
            throw new IllegalArgumentException("Path libraryId '%s' does not match body libraryId '%s'"
                    .formatted(libraryId, library.libraryId()));
        }
        VisualValidationResult validation = validator.validate(library);
        if (!validation.valid()) {
            return ResponseEntity.badRequest().body(validation);
        }
        return ResponseEntity.ok(registry.upsert(library));
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

    /**
     * @param ex invalid library payload
     * @return structured 400 response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<VisualValidationResult> handleUnreadablePayload(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new VisualValidationResult(false, java.util.List.of(
                VisualDiagnostic.error("visual.library.unreadable",
                        ex.getMostSpecificCause().getMessage(),
                        "/")
        )));
    }

    /**
     * @param ex invalid request or registry conflict
     * @return structured 400/409 response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<VisualValidationResult> handleBadRequest(IllegalArgumentException ex) {
        HttpStatus status = ex.getMessage() != null && ex.getMessage().contains("already provided by library")
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new VisualValidationResult(false, java.util.List.of(
                VisualDiagnostic.error("visual.library.invalid", ex.getMessage(), "/")
        )));
    }
}
