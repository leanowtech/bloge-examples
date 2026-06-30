package com.leanowtech.bloge.gateway.visual.resource;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
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
import java.util.Optional;

/**
 * Admin API for resource design contracts consumed by the visual canvas.
 */
@RestController
@RequestMapping("/admin/resource-design-contracts")
public class ResourceDesignContractAdminController {

    private final ResourceDesignContractRegistry registry;
    private final ResourceDesignContractValidator validator;
    private final GraphDraftRepository draftRepository;
    private final ResourceRegistry resourceRegistry;
    private final ResourceVirtualOperatorProjector projector;

    /**
     * @param registry contract registry
     * @param validator contract validator
     * @param draftRepository stored visual graph draft repository
     * @param resourceRegistry resource descriptor registry
     * @param projector resource virtual operator projector
     */
    public ResourceDesignContractAdminController(ResourceDesignContractRegistry registry,
                                                 ResourceDesignContractValidator validator,
                                                 GraphDraftRepository draftRepository,
                                                 ResourceRegistry resourceRegistry,
                                                 ResourceVirtualOperatorProjector projector) {
        this.registry = registry;
        this.validator = validator;
        this.draftRepository = draftRepository;
        this.resourceRegistry = resourceRegistry;
        this.projector = projector;
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
     * @param force suppress stored-draft disablement impact diagnostics
     * @return structured validation diagnostics
     */
    @PostMapping("/validate")
    public VisualValidationResult validate(@RequestBody ResourceDesignContract contract,
                                           @RequestParam(defaultValue = "false") boolean force) {
        return validateAgainstRegistry(contract, force);
    }

    /**
     * Replaces a design contract for a resource.
     *
     * @param resourceId descriptor id
     * @param contract new contract
     * @param force bypass stored-draft disablement protection
     * @return stored contract
     */
    @PutMapping("/{resourceId:.+}")
    public ResponseEntity<?> upsert(@PathVariable String resourceId,
                                    @RequestBody ResourceDesignContract contract,
                                    @RequestParam(defaultValue = "false") boolean force) {
        VisualValidationResult validation = validateAgainstRegistry(contract, force);
        if (!validation.valid()) {
            return ResponseEntity.status(validationFailureStatus(validation)).body(validation);
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
            List<VisualDiagnostic> diagnostics = storedDraftReferenceDiagnostics(resourceId, "deleted");
            if (!diagnostics.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new VisualValidationResult(false, diagnostics));
            }
        }
        registry.deleteByResourceId(resourceId);
        return ResponseEntity.noContent().build();
    }

    private VisualValidationResult validateAgainstRegistry(ResourceDesignContract contract, boolean force) {
        VisualValidationResult structural = validator.validate(contract);
        List<VisualDiagnostic> diagnostics = new ArrayList<>(structural.diagnostics());
        diagnostics.addAll(replacementFingerprintDriftDiagnostics(contract));
        if (!force) {
            diagnostics.addAll(disablementImpactDiagnostics(contract));
        }
        return new VisualValidationResult(false, diagnostics);
    }

    private static HttpStatus validationFailureStatus(VisualValidationResult validation) {
        return validation.diagnostics().stream()
                .anyMatch(diagnostic -> "visual.resourceContract.inUse".equals(diagnostic.code()))
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
    }

    private List<VisualDiagnostic> disablementImpactDiagnostics(ResourceDesignContract contract) {
        if (contract == null
                || contract.visibleInCatalog(true)
                || registry.findByResourceId(contract.resourceId()).isEmpty()) {
            return List.of();
        }
        return storedDraftReferenceDiagnostics(contract.resourceId(), "disabled without force=true");
    }

    private List<VisualDiagnostic> replacementFingerprintDriftDiagnostics(ResourceDesignContract replacement) {
        if (replacement == null || !replacement.visibleInCatalog(true)) {
            return List.of();
        }
        Optional<ResourceDesignContract> existing = registry.findByResourceId(replacement.resourceId());
        if (existing.isEmpty() || !resourceRegistry.contains(replacement.resourceId())) {
            return List.of();
        }

        ResourceDescriptor descriptor = resourceRegistry.resolve(replacement.resourceId());
        OperatorDefinition replacementOperator = projector.project(descriptor, Optional.of(replacement));
        if (projector.project(descriptor, existing).fingerprint().equals(replacementOperator.fingerprint())) {
            return List.of();
        }

        String operatorRef = "resource:" + replacement.resourceId();
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (GraphDraft draft : draftRepository.all()) {
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                if (!operatorRef.equals(node.operatorRef())) {
                    continue;
                }
                String savedFingerprint = draft.operatorFingerprints().get(node.id());
                if (savedFingerprint == null || savedFingerprint.isBlank()) {
                    diagnostics.add(VisualDiagnostic.warning("visual.resourceContract.operatorFingerprintSnapshotMissing",
                            "Resource design contract '%s' changes operatorRef '%s' used by draft '%s@%d' node '%s', but the draft has no saved operator fingerprint; review and resave the draft before execution."
                                    .formatted(replacement.resourceId(), operatorRef, draft.draftId(),
                                            draft.revision(), node.id()),
                            "/drafts/%s/nodes/%d/operatorRef".formatted(draft.draftId(), i)));
                    continue;
                }
                if (savedFingerprint.equals(replacementOperator.fingerprint())) {
                    continue;
                }
                diagnostics.add(VisualDiagnostic.warning("visual.resourceContract.operatorFingerprintDrift",
                        "Resource design contract '%s' changes operatorRef '%s' used by draft '%s@%d' node '%s' from saved fingerprint '%s' to '%s'; review and resave the draft before execution."
                                .formatted(replacement.resourceId(), operatorRef, draft.draftId(),
                                        draft.revision(), node.id(), savedFingerprint,
                                        replacementOperator.fingerprint()),
                        "/drafts/%s/nodes/%d/operatorRef".formatted(draft.draftId(), i)));
            }
        }
        return diagnostics;
    }

    private List<VisualDiagnostic> storedDraftReferenceDiagnostics(String resourceId, String action) {
        String operatorRef = "resource:" + resourceId;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (GraphDraft draft : draftRepository.all()) {
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                if (operatorRef.equals(node.operatorRef())) {
                    diagnostics.add(VisualDiagnostic.error("visual.resourceContract.inUse",
                            "Resource design contract for '%s' cannot be %s because draft '%s@%d' node '%s' still uses operatorRef '%s'."
                                    .formatted(resourceId, action, draft.draftId(), draft.revision(),
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
