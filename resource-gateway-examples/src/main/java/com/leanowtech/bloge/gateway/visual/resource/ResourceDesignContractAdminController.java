package com.leanowtech.bloge.gateway.visual.resource;

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
import java.util.List;

/**
 * Admin API for resource design contracts consumed by the visual canvas.
 */
@RestController
@RequestMapping("/admin/resource-design-contracts")
public class ResourceDesignContractAdminController {

    private final ResourceDesignContractRegistry registry;
    private final ResourceDesignContractValidator validator;
    private final GraphDraftRepository draftRepository;

    /**
     * @param registry contract registry
     * @param validator contract validator
     * @param draftRepository stored visual graph draft repository
     */
    public ResourceDesignContractAdminController(ResourceDesignContractRegistry registry,
                                                 ResourceDesignContractValidator validator,
                                                 GraphDraftRepository draftRepository) {
        this.registry = registry;
        this.validator = validator;
        this.draftRepository = draftRepository;
    }

    /**
     * @return all design contracts
     */
    @GetMapping
    public Collection<ResourceDesignContract> list() {
        return registry.all();
    }

    /**
     * @param resourceId descriptor id
     * @return matching contract
     */
    @GetMapping("/{resourceId:.+}")
    public ResponseEntity<ResourceDesignContract> get(@PathVariable String resourceId) {
        return registry.findByResourceId(resourceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Validates a design contract without storing it.
     *
     * @param contract contract body
     * @return structured validation diagnostics
     */
    @PostMapping("/validate")
    public VisualValidationResult validate(@RequestBody ResourceDesignContract contract) {
        return validator.validate(contract);
    }

    /**
     * Replaces a design contract for a resource.
     *
     * @param resourceId descriptor id
     * @param contract new contract
     * @return stored contract
     */
    @PutMapping("/{resourceId:.+}")
    public ResponseEntity<?> upsert(@PathVariable String resourceId,
                                    @RequestBody ResourceDesignContract contract) {
        VisualValidationResult validation = validator.validate(contract);
        if (!validation.valid()) {
            return ResponseEntity.badRequest().body(validation);
        }
        if (!resourceId.equals(contract.resourceId())) {
            throw new IllegalArgumentException("Path resourceId '%s' does not match body resourceId '%s'"
                    .formatted(resourceId, contract.resourceId()));
        }
        return ResponseEntity.ok(registry.upsert(contract));
    }

    /**
     * Deletes a design contract.
     *
     * @param resourceId descriptor id
     * @param force bypass stored-draft reference protection
     * @return empty response
     */
    @DeleteMapping("/{resourceId:.+}")
    public ResponseEntity<?> delete(@PathVariable String resourceId,
                                    @RequestParam(defaultValue = "false") boolean force) {
        if (!force && registry.findByResourceId(resourceId).isPresent()) {
            List<VisualDiagnostic> diagnostics = storedDraftReferenceDiagnostics(resourceId);
            if (!diagnostics.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new VisualValidationResult(false, diagnostics));
            }
        }
        registry.deleteByResourceId(resourceId);
        return ResponseEntity.noContent().build();
    }

    private List<VisualDiagnostic> storedDraftReferenceDiagnostics(String resourceId) {
        String operatorRef = "resource:" + resourceId;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (GraphDraft draft : draftRepository.all()) {
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                if (operatorRef.equals(node.operatorRef())) {
                    diagnostics.add(VisualDiagnostic.error("visual.resourceContract.inUse",
                            "Resource design contract for '%s' cannot be deleted because draft '%s@%d' node '%s' still uses operatorRef '%s'."
                                    .formatted(resourceId, draft.draftId(), draft.revision(),
                                            node.id(), node.operatorRef()),
                            "/drafts/%s/nodes/%d/operatorRef".formatted(draft.draftId(), i)));
                }
            }
        }
        return diagnostics;
    }

    /**
     * @param ex invalid contract payload
     * @return structured 400 response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<VisualValidationResult> handleUnreadablePayload(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new VisualValidationResult(false, List.of(
                VisualDiagnostic.error("visual.resourceContract.unreadable",
                        ex.getMostSpecificCause().getMessage(),
                        "/")
        )));
    }

    /**
     * @param ex invalid request
     * @return structured 400 response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<VisualValidationResult> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new VisualValidationResult(false, List.of(
                VisualDiagnostic.error("visual.resourceContract.invalid", ex.getMessage(), "/")
        )));
    }
}
