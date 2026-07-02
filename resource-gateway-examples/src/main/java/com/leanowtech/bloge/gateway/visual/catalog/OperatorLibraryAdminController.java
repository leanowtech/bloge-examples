package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final VisualGraphPublicationRepository publicationRepository;
    private final JavaOperatorInventoryProjector javaOperatorProjector;

    /**
     * @param registry library registry
     * @param validator library validator
     * @param draftRepository stored visual graph draft repository
     * @param publicationRepository immutable visual graph publication repository
     * @param javaOperatorProjector runtime Java operator projector
     */
    @Autowired
    public OperatorLibraryAdminController(OperatorLibraryRegistry registry,
                                          OperatorLibraryValidator validator,
                                          GraphDraftRepository draftRepository,
                                          VisualGraphPublicationRepository publicationRepository,
                                          JavaOperatorInventoryProjector javaOperatorProjector) {
        this.registry = registry;
        this.validator = validator;
        this.draftRepository = draftRepository;
        this.publicationRepository = publicationRepository;
        this.javaOperatorProjector = javaOperatorProjector == null
                ? JavaOperatorInventoryProjector.empty()
                : javaOperatorProjector;
    }

    OperatorLibraryAdminController(OperatorLibraryRegistry registry,
                                   OperatorLibraryValidator validator,
                                   GraphDraftRepository draftRepository,
                                   VisualGraphPublicationRepository publicationRepository) {
        this(registry, validator, draftRepository, publicationRepository, JavaOperatorInventoryProjector.empty());
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
     * @param ackWarnings true when the caller already reviewed non-blocking replacement warnings
     * @return stored library
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody OperatorLibrary library,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    @RequestParam(defaultValue = "false") boolean ackWarnings) {
        OperatorLibraryValidationResult validation = validateAgainstRegistry(library, force);
        if (!validation.valid()) {
            return ResponseEntity.status(validationFailureStatus(validation)).body(validation);
        }
        ResponseEntity<OperatorLibraryValidationResult> warningGate = warningAcknowledgementResponse(validation,
                ackWarnings);
        if (warningGate != null) {
            return warningGate;
        }
        ResponseEntity<OperatorLibraryValidationResult> impact = replacementImpactResponse(library, force);
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
    public OperatorLibraryValidationResult validate(@RequestBody OperatorLibrary library,
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
     * @param ackWarnings true when the caller already reviewed non-blocking replacement warnings
     * @return stored library
     */
    @PutMapping("/{libraryId}")
    public ResponseEntity<?> update(@PathVariable String libraryId,
                                    @RequestBody OperatorLibrary library,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    @RequestParam(defaultValue = "false") boolean ackWarnings) {
        if (!libraryId.equals(library.libraryId())) {
            throw new IllegalArgumentException("Path libraryId '%s' does not match body libraryId '%s'"
                    .formatted(libraryId, library.libraryId()));
        }
        OperatorLibraryValidationResult validation = validateAgainstRegistry(library, force);
        if (!validation.valid()) {
            return ResponseEntity.status(validationFailureStatus(validation)).body(validation);
        }
        ResponseEntity<OperatorLibraryValidationResult> warningGate = warningAcknowledgementResponse(validation,
                ackWarnings);
        if (warningGate != null) {
            return warningGate;
        }
        ResponseEntity<OperatorLibraryValidationResult> impact = replacementImpactResponse(library, force);
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
                    .filter(java.util.Objects::nonNull)
                    .map(OperatorDefinition::operatorRef)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<VisualDiagnostic> diagnostics = new ArrayList<>();
            diagnostics.addAll(storedDraftReferenceDiagnostics(libraryId, operatorRefs, "deleted"));
            diagnostics.addAll(publishedArtifactReferenceDiagnostics(libraryId, operatorRefs, "deleted"));
            if (!diagnostics.isEmpty()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(validationResult(library.get(), diagnostics));
            }
        }
        registry.delete(libraryId);
        return ResponseEntity.noContent().build();
    }

    private OperatorLibraryValidationResult validateAgainstRegistry(OperatorLibrary library, boolean force) {
        VisualValidationResult structural = validator.validate(library);
        List<VisualDiagnostic> diagnostics = new ArrayList<>(structural.diagnostics());
        diagnostics.addAll(operatorRefOwnershipDiagnostics(library));
        diagnostics.addAll(runtimeOperatorRefDiagnostics(library));
        diagnostics.addAll(unresolvedNativeLoweringDiagnostics(library));
        diagnostics.addAll(replacementLifecycleDowngradeDiagnostics(library));
        diagnostics.addAll(replacementFingerprintDriftDiagnostics(library));
        diagnostics.addAll(replacementPublicationRemovalDiagnostics(library));
        if (!force) {
            diagnostics.addAll(replacementImpactDiagnostics(library, "replaced without force=true"));
        }
        return validationResult(library, diagnostics);
    }

    private List<VisualDiagnostic> operatorRefOwnershipDiagnostics(OperatorLibrary library) {
        if (library == null || library.operators().isEmpty()) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < library.operators().size(); i++) {
            OperatorDefinition operator = library.operators().get(i);
            if (operator == null) {
                continue;
            }
            String owner = registry.all().stream()
                    .filter(existing -> !existing.libraryId().equals(library.libraryId()))
                    .flatMap(existing -> existing.operators().stream()
                            .filter(java.util.Objects::nonNull)
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

    private List<VisualDiagnostic> runtimeOperatorRefDiagnostics(OperatorLibrary library) {
        if (library == null || library.operators().isEmpty()) {
            return List.of();
        }
        Set<String> runtimeOperatorRefs = javaOperatorProjector.project().stream()
                .map(OperatorDefinition::operatorRef)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (runtimeOperatorRefs.isEmpty()) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < library.operators().size(); i++) {
            OperatorDefinition operator = library.operators().get(i);
            if (operator == null) {
                continue;
            }
            if (runtimeOperatorRefs.contains(operator.operatorRef())) {
                diagnostics.add(VisualDiagnostic.error("visual.library.operatorRefRuntimeOwned",
                        "operatorRef '%s' is already provided by the runtime Java operator inventory."
                                .formatted(operator.operatorRef()),
                        "/operators/%d/operatorRef".formatted(i)));
            }
        }
        return diagnostics;
    }

    private List<VisualDiagnostic> unresolvedNativeLoweringDiagnostics(OperatorLibrary library) {
        if (library == null || library.operators().isEmpty()) {
            return List.of();
        }
        Set<String> runtimeOperatorRefs = javaOperatorProjector.project().stream()
                .map(OperatorDefinition::operatorRef)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (runtimeOperatorRefs.isEmpty()) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < library.operators().size(); i++) {
            OperatorDefinition operator = library.operators().get(i);
            if (operator == null || operator.lowering() == null
                    || !"native".equals(operator.lowering().mode())) {
                continue;
            }
            String executableOperatorRef = operator.lowering().operatorRef();
            if (executableOperatorRef.isBlank() || runtimeOperatorRefs.contains(executableOperatorRef)) {
                continue;
            }
            diagnostics.add(VisualDiagnostic.warning("visual.operator.lowering.operatorRefUnresolved",
                    "Native operator '%s' lowers to executable operatorRef '%s', but that executable is not visible in the runtime Java operator inventory; acknowledge this warning only when an external executor will provide it."
                            .formatted(operator.operatorRef(), executableOperatorRef),
                    "/operators/%d/lowering/operatorRef".formatted(i)));
        }
        return diagnostics;
    }

    private List<VisualDiagnostic> replacementLifecycleDowngradeDiagnostics(OperatorLibrary replacement) {
        if (replacement == null || !OperatorLibrary.STATUS_DEPRECATED.equals(replacement.status())) {
            return List.of();
        }
        Optional<OperatorLibrary> existing = registry.find(replacement.libraryId());
        if (existing.isEmpty() || OperatorLibrary.STATUS_DEPRECATED.equals(existing.get().status())) {
            return List.of();
        }
        Set<String> operatorRefs = replacement.operators().stream()
                .filter(java.util.Objects::nonNull)
                .map(OperatorDefinition::operatorRef)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (operatorRefs.isEmpty()) {
            return List.of();
        }

        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (GraphDraft draft : draftRepository.all()) {
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                if (!operatorRefs.contains(node.operatorRef())) {
                    continue;
                }
                diagnostics.add(VisualDiagnostic.warning("visual.library.lifecycle.deprecated",
                        "Operator library '%s' is being deprecated; draft '%s@%d' node '%s' still uses operatorRef '%s'. Review migration before production promotion."
                                .formatted(replacement.libraryId(), draft.draftId(), draft.revision(),
                                        node.id(), node.operatorRef()),
                        "/drafts/%s/nodes/%d/operatorRef".formatted(draft.draftId(), i),
                        lifecycleMetadata(replacement, existing.get(), node.operatorRef(), node.id())));
            }
        }
        for (VisualGraphPublication publication : publicationRepository.all()) {
            GraphDraft draft = publication.draft();
            if (draft == null) {
                continue;
            }
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                if (!operatorRefs.contains(node.operatorRef())) {
                    continue;
                }
                diagnostics.add(VisualDiagnostic.warning("visual.library.publicationLifecycleDeprecated",
                        "Operator library '%s' is being deprecated while publication '%s' node '%s' was authored with operatorRef '%s'. Existing publication keeps its frozen DSL, but replay, recertification, or republishing should be reviewed."
                                .formatted(replacement.libraryId(), publication.publicationId(),
                                        node.id(), node.operatorRef()),
                        "/publications/%s/nodes/%d/operatorRef".formatted(publication.publicationId(), i),
                        lifecycleMetadata(replacement, existing.get(), node.operatorRef(), node.id())));
            }
        }
        return diagnostics;
    }

    private static Map<String, Object> lifecycleMetadata(OperatorLibrary replacement,
                                                         OperatorLibrary existing,
                                                         String operatorRef,
                                                         String nodeId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("libraryId", replacement.libraryId());
        metadata.put("previousStatus", existing.status());
        metadata.put("libraryStatus", replacement.status());
        metadata.put("operatorRef", operatorRef);
        if (nodeId != null && !nodeId.isBlank()) {
            metadata.put("nodeId", nodeId);
        }
        return metadata;
    }

    private static HttpStatus validationFailureStatus(OperatorLibraryValidationResult validation) {
        return validation.diagnostics().stream()
                .anyMatch(diagnostic -> "visual.library.operatorRefOwned".equals(diagnostic.code())
                        || "visual.library.operatorRefRuntimeOwned".equals(diagnostic.code())
                        || "visual.library.inUse".equals(diagnostic.code()))
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
    }

    private ResponseEntity<OperatorLibraryValidationResult> replacementImpactResponse(OperatorLibrary replacement,
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
                .body(validationResult(replacement, diagnostics));
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
        Set<String> replacementRefs = replacement.visibleInCatalog(true)
                ? replacement.operators().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(OperatorDefinition::operatorRef)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        Set<String> removedRefs = existing.get().operators().stream()
                .filter(java.util.Objects::nonNull)
                .map(OperatorDefinition::operatorRef)
                .filter(operatorRef -> !replacementRefs.contains(operatorRef))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return storedDraftReferenceDiagnostics(replacement.libraryId(), removedRefs, action);
    }

    private List<VisualDiagnostic> replacementFingerprintDriftDiagnostics(OperatorLibrary replacement) {
        if (replacement == null) {
            return List.of();
        }
        Optional<OperatorLibrary> existing = registry.find(replacement.libraryId());
        if (existing.isEmpty()) {
            return List.of();
        }

        Map<String, OperatorDefinition> existingByRef = new LinkedHashMap<>();
        for (OperatorDefinition operator : existing.get().operators()) {
            if (operator == null) {
                continue;
            }
            existingByRef.putIfAbsent(operator.operatorRef(), operator);
        }

        Map<String, OperatorDefinitionChange> changedByRef = new LinkedHashMap<>();
        for (OperatorDefinition operator : replacement.operators()) {
            if (operator == null) {
                continue;
            }
            OperatorDefinition previous = existingByRef.get(operator.operatorRef());
            if (previous != null && !previous.fingerprint().equals(operator.fingerprint())) {
                changedByRef.putIfAbsent(operator.operatorRef(), new OperatorDefinitionChange(previous, operator));
            }
        }
        if (changedByRef.isEmpty()) {
            return List.of();
        }

        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (GraphDraft draft : draftRepository.all()) {
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                OperatorDefinitionChange change = changedByRef.get(node.operatorRef());
                if (change == null) {
                    continue;
                }
                String savedFingerprint = draft.operatorFingerprints().get(node.id());
                if (savedFingerprint == null || savedFingerprint.isBlank()) {
                    diagnostics.add(VisualDiagnostic.warning("visual.library.operatorFingerprintSnapshotMissing",
                            "Operator library '%s' changes operatorRef '%s' used by draft '%s@%d' node '%s', but the draft has no saved operator fingerprint; review and resave the draft before execution."
                                    .formatted(replacement.libraryId(), node.operatorRef(), draft.draftId(),
                                            draft.revision(), node.id()),
                            "/drafts/%s/nodes/%d/operatorRef".formatted(draft.draftId(), i)));
                    continue;
                }
                if (savedFingerprint.equals(change.replacement().fingerprint())) {
                    continue;
                }
                OperatorDefinitionChangeSummary.ChangeReport report = OperatorDefinitionChangeSummary.analyze(
                        change.previous(), change.replacement());
                diagnostics.add(VisualDiagnostic.warning("visual.library.operatorFingerprintDrift",
                        "Operator library '%s' changes operatorRef '%s' used by draft '%s@%d' node '%s' from saved fingerprint '%s' to '%s'; changed surface: %s; review and resave the draft before execution."
                                .formatted(replacement.libraryId(), node.operatorRef(), draft.draftId(),
                                        draft.revision(), node.id(), savedFingerprint,
                                        change.replacement().fingerprint(),
                                        "change risk: " + report.risk() + "; " + report.summary()),
                        "/drafts/%s/nodes/%d/operatorRef".formatted(draft.draftId(), i),
                        changeMetadata(report)));
            }
        }
        for (VisualGraphPublication publication : publicationRepository.all()) {
            GraphDraft draft = publication.draft();
            if (draft == null) {
                continue;
            }
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                OperatorDefinitionChange change = changedByRef.get(node.operatorRef());
                if (change == null) {
                    continue;
                }
                String publishedFingerprint = publication.operatorFingerprints().get(node.id());
                if (publishedFingerprint == null || publishedFingerprint.isBlank()) {
                    diagnostics.add(VisualDiagnostic.warning("visual.library.publicationOperatorFingerprintSnapshotMissing",
                            "Operator library '%s' changes operatorRef '%s' used by publication '%s' node '%s', but the publication has no frozen operator fingerprint; existing publication keeps its frozen DSL, but review before replaying or republishing."
                                    .formatted(replacement.libraryId(), node.operatorRef(),
                                            publication.publicationId(), node.id()),
                            "/publications/%s/nodes/%d/operatorRef".formatted(publication.publicationId(), i)));
                    continue;
                }
                if (publishedFingerprint.equals(change.replacement().fingerprint())) {
                    continue;
                }
                OperatorDefinitionChangeSummary.ChangeReport report = OperatorDefinitionChangeSummary.analyze(
                        change.previous(), change.replacement());
                diagnostics.add(VisualDiagnostic.warning("visual.library.publicationOperatorFingerprintDrift",
                        "Operator library '%s' changes operatorRef '%s' used by publication '%s' node '%s' from frozen fingerprint '%s' to '%s'; changed surface: %s; existing publication keeps its frozen DSL, but review before replaying, recertifying, or republishing."
                                .formatted(replacement.libraryId(), node.operatorRef(), publication.publicationId(),
                                        node.id(), publishedFingerprint, change.replacement().fingerprint(),
                                        "change risk: " + report.risk() + "; " + report.summary()),
                        "/publications/%s/nodes/%d/operatorRef".formatted(publication.publicationId(), i),
                        changeMetadata(report)));
            }
        }
        return diagnostics;
    }

    private record OperatorDefinitionChange(OperatorDefinition previous, OperatorDefinition replacement) {
    }

    private static Map<String, Object> changeMetadata(OperatorDefinitionChangeSummary.ChangeReport report) {
        if (report == null) {
            return Map.of();
        }
        return Map.of(
                "changeRisk", report.risk(),
                "changeCategories", report.categories(),
                "changeSummary", report.summary()
        );
    }

    private List<VisualDiagnostic> replacementPublicationRemovalDiagnostics(OperatorLibrary replacement) {
        if (replacement == null) {
            return List.of();
        }
        Optional<OperatorLibrary> existing = registry.find(replacement.libraryId());
        if (existing.isEmpty()) {
            return List.of();
        }
        Set<String> replacementRefs = replacement.visibleInCatalog(true)
                ? replacement.operators().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(OperatorDefinition::operatorRef)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        Set<String> removedRefs = existing.get().operators().stream()
                .filter(java.util.Objects::nonNull)
                .map(OperatorDefinition::operatorRef)
                .filter(operatorRef -> !replacementRefs.contains(operatorRef))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (removedRefs.isEmpty()) {
            return List.of();
        }

        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (VisualGraphPublication publication : publicationRepository.all()) {
            GraphDraft draft = publication.draft();
            if (draft == null) {
                continue;
            }
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                if (removedRefs.contains(node.operatorRef())) {
                    diagnostics.add(VisualDiagnostic.warning("visual.library.publicationOperatorRemoved",
                            "Operator library '%s' removes operatorRef '%s' used by publication '%s' node '%s'; existing publication keeps its frozen DSL, but replay, recertification, or republishing should be reviewed."
                                    .formatted(replacement.libraryId(), node.operatorRef(),
                                            publication.publicationId(), node.id()),
                            "/publications/%s/nodes/%d/operatorRef".formatted(publication.publicationId(), i)));
                }
            }
        }
        return diagnostics;
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

    private List<VisualDiagnostic> publishedArtifactReferenceDiagnostics(String libraryId,
                                                                         Collection<String> operatorRefs,
                                                                         String action) {
        if (operatorRefs.isEmpty()) {
            return List.of();
        }

        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (VisualGraphPublication publication : publicationRepository.all()) {
            GraphDraft draft = publication.draft();
            if (draft == null) {
                continue;
            }
            for (int i = 0; i < draft.nodes().size(); i++) {
                GraphDraft.DraftNode node = draft.nodes().get(i);
                if (operatorRefs.contains(node.operatorRef())) {
                    diagnostics.add(VisualDiagnostic.error("visual.library.publicationInUse",
                            "Operator library '%s' cannot be %s without force=true because publication '%s' node '%s' was authored with operatorRef '%s'. Existing publication keeps its frozen DSL, but replay, recertification, or republishing should be reviewed."
                                    .formatted(libraryId, action, publication.publicationId(), node.id(),
                                            node.operatorRef()),
                            "/publications/%s/nodes/%d/operatorRef".formatted(publication.publicationId(), i)));
                }
            }
        }
        return diagnostics;
    }

    private OperatorLibraryValidationResult validationResult(OperatorLibrary library,
                                                             List<VisualDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return new OperatorLibraryValidationResult(false, diagnostics, OperatorLibraryImpactReview.empty());
        }
        return new OperatorLibraryValidationResult(false, diagnostics,
                OperatorLibraryImpactReview.fromDiagnostics(diagnostics, impactOperatorRefs(library, diagnostics)));
    }

    private Set<String> impactOperatorRefs(OperatorLibrary library, List<VisualDiagnostic> diagnostics) {
        Set<String> operatorRefs = new LinkedHashSet<>();
        operatorRefs.addAll(operatorRefsFromOperatorDiagnosticTargets(library, diagnostics));
        operatorRefs.addAll(referencedReplacementOperatorRefs(library));
        return operatorRefs;
    }

    private Set<String> operatorRefsFromOperatorDiagnosticTargets(OperatorLibrary library,
                                                                  List<VisualDiagnostic> diagnostics) {
        if (library == null || diagnostics.isEmpty()) {
            return Set.of();
        }
        Set<String> operatorRefs = new LinkedHashSet<>();
        for (VisualDiagnostic diagnostic : diagnostics) {
            Integer index = operatorIndexFromTarget(diagnostic.target());
            if (index == null || index < 0 || index >= library.operators().size()) {
                continue;
            }
            OperatorDefinition operator = library.operators().get(index);
            if (operator != null && !operator.operatorRef().isBlank()) {
                operatorRefs.add(operator.operatorRef());
            }
        }
        return operatorRefs;
    }

    private Set<String> referencedReplacementOperatorRefs(OperatorLibrary replacement) {
        if (replacement == null) {
            return Set.of();
        }
        Optional<OperatorLibrary> existing = registry.find(replacement.libraryId());
        if (existing.isEmpty()) {
            return Set.of();
        }
        Map<String, OperatorDefinition> existingByRef = new LinkedHashMap<>();
        for (OperatorDefinition operator : existing.get().operators()) {
            if (operator != null) {
                existingByRef.putIfAbsent(operator.operatorRef(), operator);
            }
        }
        Set<String> replacementRefs = replacement.visibleInCatalog(true)
                ? replacement.operators().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(OperatorDefinition::operatorRef)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
        Set<String> affectedRefs = existingByRef.keySet().stream()
                .filter(operatorRef -> !replacementRefs.contains(operatorRef))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (OperatorDefinition operator : replacement.operators()) {
            if (operator == null) {
                continue;
            }
            OperatorDefinition previous = existingByRef.get(operator.operatorRef());
            if (previous != null && !previous.fingerprint().equals(operator.fingerprint())) {
                affectedRefs.add(operator.operatorRef());
            }
        }
        if (OperatorLibrary.STATUS_DEPRECATED.equals(replacement.status())
                && !OperatorLibrary.STATUS_DEPRECATED.equals(existing.get().status())) {
            replacement.operators().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(OperatorDefinition::operatorRef)
                    .forEach(affectedRefs::add);
        }
        if (affectedRefs.isEmpty()) {
            return Set.of();
        }
        Set<String> referencedRefs = new LinkedHashSet<>();
        for (GraphDraft draft : draftRepository.all()) {
            for (GraphDraft.DraftNode node : draft.nodes()) {
                if (affectedRefs.contains(node.operatorRef())) {
                    referencedRefs.add(node.operatorRef());
                }
            }
        }
        for (VisualGraphPublication publication : publicationRepository.all()) {
            GraphDraft draft = publication.draft();
            if (draft == null) {
                continue;
            }
            for (GraphDraft.DraftNode node : draft.nodes()) {
                if (affectedRefs.contains(node.operatorRef())) {
                    referencedRefs.add(node.operatorRef());
                }
            }
        }
        return referencedRefs;
    }

    private static Integer operatorIndexFromTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String[] segments = target.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (!"operators".equals(segments[i])) {
                continue;
            }
            try {
                return Integer.parseInt(segments[i + 1]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static ResponseEntity<OperatorLibraryValidationResult> warningAcknowledgementResponse(
            OperatorLibraryValidationResult validation,
            boolean ackWarnings) {
        if (ackWarnings || validation.diagnostics().stream()
                .noneMatch(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()))) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(validation);
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
