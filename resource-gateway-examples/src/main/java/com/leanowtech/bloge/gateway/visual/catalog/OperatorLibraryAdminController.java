package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Admin API for user-provided visual operator libraries.
 */
@RestController
@RequestMapping("/admin/visual-operator-libraries")
public class OperatorLibraryAdminController {

    private final OperatorLibraryRegistry registry;
    private final OperatorLibraryValidator validator;
    private final GraphDraftRepository draftRepository;

    /**
     * @param registry library registry
     * @param validator library validator
     * @param draftRepository stored visual graph draft repository
     */
    public OperatorLibraryAdminController(OperatorLibraryRegistry registry,
                                          OperatorLibraryValidator validator,
                                          GraphDraftRepository draftRepository) {
        this.registry = registry;
        this.validator = validator;
        this.draftRepository = draftRepository;
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
     * @param force bypass stored-draft reference protection when re-importing an existing library
     * @return stored library
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody OperatorLibrary library,
                                    @RequestParam(defaultValue = "false") boolean force) {
        VisualValidationResult validation = validateAgainstRegistry(library, force);
        if (!validation.valid()) {
            return ResponseEntity.status(validationFailureStatus(validation)).body(validation);
        }
        ResponseEntity<VisualValidationResult> impact = replacementImpactResponse(library, force);
        if (impact != null) {
            return impact;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(registry.upsert(library));
    }

    /**
     * Validates a library without storing it.
     *
     * @param library library body
     * @param force bypass stored-draft replacement impact diagnostics
     * @return structured validation diagnostics
     */
    @PostMapping("/validate")
    public VisualValidationResult validate(@RequestBody OperatorLibrary library,
                                           @RequestParam(defaultValue = "false") boolean force) {
        return validateAgainstRegistry(library, force);
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
     * @param force bypass stored-draft reference protection
     * @return stored library
     */
    @PutMapping("/{libraryId}")
    public ResponseEntity<?> update(@PathVariable String libraryId,
                                    @RequestBody OperatorLibrary library,
                                    @RequestParam(defaultValue = "false") boolean force) {
        if (!libraryId.equals(library.libraryId())) {
            throw new IllegalArgumentException("Path libraryId '%s' does not match body libraryId '%s'"
                    .formatted(libraryId, library.libraryId()));
        }
        VisualValidationResult validation = validateAgainstRegistry(library, force);
        if (!validation.valid()) {
            return ResponseEntity.status(validationFailureStatus(validation)).body(validation);
        }
        ResponseEntity<VisualValidationResult> impact = replacementImpactResponse(library, force);
        if (impact != null) {
            return impact;
        }
        return ResponseEntity.ok(registry.upsert(library));
    }

    /**
     * Deletes a library.
     *
     * @param libraryId library id
     * @param force bypass stored-draft reference protection
     * @return empty response
     */
    @DeleteMapping("/{libraryId}")
    public ResponseEntity<?> delete(@PathVariable String libraryId,
                                    @RequestParam(defaultValue = "false") boolean force) {
        Optional<OperatorLibrary> library = registry.find(libraryId);
        if (!force && library.isPresent()) {
            Set<String> operatorRefs = library.get().operators().stream()
                    .map(OperatorDefinition::operatorRef)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<VisualDiagnostic> diagnostics = storedDraftReferenceDiagnostics(libraryId, operatorRefs, "deleted");
            if (!diagnostics.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new VisualValidationResult(false, diagnostics));
            }
        }
        registry.delete(libraryId);
        return ResponseEntity.noContent().build();
    }

    private VisualValidationResult validateAgainstRegistry(OperatorLibrary library, boolean force) {
        VisualValidationResult structural = validator.validate(library);
        List<VisualDiagnostic> diagnostics = new ArrayList<>(structural.diagnostics());
        diagnostics.addAll(operatorRefOwnershipDiagnostics(library));
        if (!force) {
            diagnostics.addAll(replacementImpactDiagnostics(library, "replaced without force=true"));
        }
        return new VisualValidationResult(false, diagnostics);
    }

    private List<VisualDiagnostic> operatorRefOwnershipDiagnostics(OperatorLibrary library) {
        if (library == null || library.operators().isEmpty()) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < library.operators().size(); i++) {
            OperatorDefinition operator = library.operators().get(i);
            String owner = registry.all().stream()
                    .filter(existing -> !existing.libraryId().equals(library.libraryId()))
                    .flatMap(existing -> existing.operators().stream()
                            .filter(existingOperator -> existingOperator.operatorRef().equals(operator.operatorRef()))
                            .map(existingOperator -> existing.libraryId()))
                    .findFirst()
                    .orElse("");
            if (!owner.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.library.operatorRefOwned",
                        "operatorRef '%s' already provided by library '%s'"
                                .formatted(operator.operatorRef(), owner),
                        "/operators/%d/operatorRef".formatted(i)));
            }
        }
        return diagnostics;
    }

    private static HttpStatus validationFailureStatus(VisualValidationResult validation) {
        return validation.diagnostics().stream()
                .anyMatch(diagnostic -> "visual.library.operatorRefOwned".equals(diagnostic.code())
                        || "visual.library.inUse".equals(diagnostic.code()))
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
    }

    private ResponseEntity<VisualValidationResult> replacementImpactResponse(OperatorLibrary replacement,
                                                                             boolean force) {
        if (force) {
            return null;
        }
        List<VisualDiagnostic> diagnostics = replacementImpactDiagnostics(replacement,
                "replaced without force=true");
        if (diagnostics.isEmpty()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new VisualValidationResult(false, diagnostics));
    }

    private List<VisualDiagnostic> replacementImpactDiagnostics(OperatorLibrary replacement,
                                                                String action) {
        if (replacement == null) {
            return List.of();
        }
        Optional<OperatorLibrary> existing = registry.find(replacement.libraryId());
        if (existing.isEmpty()) {
            return List.of();
        }
        Set<String> replacementRefs = replacement.operators().stream()
                .map(OperatorDefinition::operatorRef)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> removedRefs = existing.get().operators().stream()
                .map(OperatorDefinition::operatorRef)
                .filter(operatorRef -> !replacementRefs.contains(operatorRef))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return storedDraftReferenceDiagnostics(replacement.libraryId(), removedRefs, action);
    }

    private List<VisualDiagnostic> storedDraftReferenceDiagnostics(String libraryId,
                                                                   Collection<String> operatorRefs,
                                                                   String action) {
        if (operatorRefs.isEmpty()) {
            return List.of();
        }

        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (GraphDraft draft : draftRepository.all()) {
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                if (operatorRefs.contains(node.operatorRef())) {
                    diagnostics.add(VisualDiagnostic.error("visual.library.inUse",
                            "Operator library '%s' cannot be %s because draft '%s@%d' node '%s' still uses operatorRef '%s'."
                                    .formatted(libraryId, action, draft.draftId(), draft.revision(),
                                            node.id(), node.operatorRef()),
                            "/drafts/%s/nodes/%d/operatorRef".formatted(draft.draftId(), i)));
                }
            }
        }
        return diagnostics;
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
