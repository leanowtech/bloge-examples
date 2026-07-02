package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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

    private static final ObjectMapper OPERATOR_LIBRARY_TEXT_MAPPER = new YAMLMapper();

    private final OperatorLibraryRegistry registry;
    private final OperatorLibraryValidator validator;
    private final GraphDraftRepository draftRepository;
    private final VisualGraphPublicationRepository publicationRepository;
    private final JavaOperatorInventoryProjector javaOperatorProjector;
    private final AsyncApiOperatorLibraryImporter asyncApiImporter;

    /**
     * @param registry library registry
     * @param validator library validator
     * @param draftRepository stored visual graph draft repository
     * @param publicationRepository immutable visual graph publication repository
     * @param javaOperatorProjector runtime Java operator projector
     * @param asyncApiImporter AsyncAPI operator-library preview importer
     */
    @Autowired
    public OperatorLibraryAdminController(OperatorLibraryRegistry registry,
                                          OperatorLibraryValidator validator,
                                          GraphDraftRepository draftRepository,
                                          VisualGraphPublicationRepository publicationRepository,
                                          JavaOperatorInventoryProjector javaOperatorProjector,
                                          AsyncApiOperatorLibraryImporter asyncApiImporter) {
        this.registry = registry;
        this.validator = validator;
        this.draftRepository = draftRepository;
        this.publicationRepository = publicationRepository;
        this.javaOperatorProjector = javaOperatorProjector == null
                ? JavaOperatorInventoryProjector.empty()
                : javaOperatorProjector;
        this.asyncApiImporter = asyncApiImporter == null
                ? new AsyncApiOperatorLibraryImporter()
                : asyncApiImporter;
    }

    OperatorLibraryAdminController(OperatorLibraryRegistry registry,
                                   OperatorLibraryValidator validator,
                                   GraphDraftRepository draftRepository,
                                   VisualGraphPublicationRepository publicationRepository,
                                   JavaOperatorInventoryProjector javaOperatorProjector) {
        this(registry, validator, draftRepository, publicationRepository, javaOperatorProjector,
                new AsyncApiOperatorLibraryImporter());
    }

    OperatorLibraryAdminController(OperatorLibraryRegistry registry,
                                   OperatorLibraryValidator validator,
                                   GraphDraftRepository draftRepository,
                                   VisualGraphPublicationRepository publicationRepository) {
        this(registry, validator, draftRepository, publicationRepository, JavaOperatorInventoryProjector.empty(),
                new AsyncApiOperatorLibraryImporter());
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
     * @param actor user or system actor producing this registry revision
     * @param changeSource UI or integration source producing this registry revision
     * @param changeSummary human-readable change summary
     * @param reason optional reason for audit review
     * @return stored library
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody OperatorLibrary library,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    @RequestParam(defaultValue = "false") boolean ackWarnings,
                                    @RequestParam(defaultValue = "") String actor,
                                    @RequestParam(defaultValue = "") String changeSource,
                                    @RequestParam(defaultValue = "") String changeSummary,
                                    @RequestParam(defaultValue = "") String reason) {
        return importLibrary(library, force, ackWarnings, HttpStatus.CREATED,
                revisionMetadata(actor, changeSource, changeSummary, reason));
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
     * Validates raw operator-library source text without storing it.
     *
     * @param sourceText raw JSON or YAML operator-library source text
     * @param force bypass stored-draft replacement impact diagnostics
     * @return structured validation diagnostics
     */
    @PostMapping("/validate-text")
    public ResponseEntity<OperatorLibraryValidationResult> validateText(@RequestBody(required = false) String sourceText,
                                                                        @RequestParam(defaultValue = "false")
                                                                        boolean force) {
        OperatorLibraryTextParseResult parsed = parseOperatorLibrarySourceText(sourceText);
        if (parsed.error() != null) {
            return ResponseEntity.badRequest().body(parsed.error());
        }
        return ResponseEntity.ok(validateAgainstRegistry(parsed.library(), force));
    }

    /**
     * Imports raw operator-library source text.
     *
     * @param sourceText raw JSON or YAML operator-library source text
     * @param force bypass stored-draft reference protection when re-importing an existing library
     * @param ackWarnings true when the caller already reviewed non-blocking replacement warnings
     * @param actor user or system actor producing this registry revision
     * @param changeSource UI or integration source producing this registry revision
     * @param changeSummary human-readable change summary
     * @param reason optional reason for audit review
     * @return stored library
     */
    @PostMapping("/import-text")
    public ResponseEntity<?> importText(@RequestBody(required = false) String sourceText,
                                        @RequestParam(defaultValue = "false") boolean force,
                                        @RequestParam(defaultValue = "false") boolean ackWarnings,
                                        @RequestParam(defaultValue = "") String actor,
                                        @RequestParam(defaultValue = "") String changeSource,
                                        @RequestParam(defaultValue = "") String changeSummary,
                                        @RequestParam(defaultValue = "") String reason) {
        OperatorLibraryTextParseResult parsed = parseOperatorLibrarySourceText(sourceText);
        if (parsed.error() != null) {
            return ResponseEntity.badRequest().body(parsed.error());
        }
        HttpStatus successStatus = registry.find(parsed.library().libraryId()).isPresent()
                ? HttpStatus.OK
                : HttpStatus.CREATED;
        return importLibrary(parsed.library(), force, ackWarnings, successStatus,
                revisionMetadata(actor, changeSource, changeSummary, reason));
    }

    /**
     * Projects AsyncAPI JSON or YAML into an operator-library draft without storing it.
     *
     * @param request projection request
     * @param force bypass stored-draft replacement impact diagnostics in preview
     * @return generated library draft plus the same validation/profile/impact evidence used by imports
     */
    @PostMapping("/from-asyncapi")
    public AsyncApiOperatorLibraryImportResult fromAsyncApi(
            @RequestBody(required = false) AsyncApiOperatorLibraryImportRequest request,
            @RequestParam(defaultValue = "false") boolean force) {
        AsyncApiOperatorLibraryImportResult projected = asyncApiImporter.project(request);
        if (projected.library() == null || !projected.validation().valid()) {
            return projected;
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>(projected.validation().diagnostics());
        diagnostics.addAll(validateAgainstRegistry(projected.library(), force).diagnostics());
        return new AsyncApiOperatorLibraryImportResult(projected.library(),
                validationResult(projected.library(), diagnostics),
                projected.availableOperations(),
                projected.selectedOperations(),
                projected.omittedOperationCount(),
                projected.selectionApplied());
    }

    /**
     * Discovers AsyncAPI operation/message projection candidates before building an operator-library draft.
     *
     * @param request discovery request
     * @return operation/message summaries and parse/discovery diagnostics
     */
    @PostMapping("/from-asyncapi/operations")
    public AsyncApiOperationDiscoveryResult fromAsyncApiOperations(
            @RequestBody(required = false) AsyncApiOperatorLibraryImportRequest request) {
        return asyncApiImporter.discoverOperations(request);
    }

    /**
     * Imports a portable operator-library export bundle into the target environment.
     *
     * @param bundle portable export bundle
     * @param force bypass stored-draft reference protection when replacing an existing library
     * @param ackWarnings true when the caller already reviewed non-blocking target warnings
     * @param actor user or system actor producing this registry revision
     * @param changeSource UI or integration source producing this registry revision
     * @param changeSummary human-readable change summary
     * @param reason optional reason for audit review
     * @return target-environment import result
     */
    @PostMapping("/import-bundle")
    public ResponseEntity<OperatorLibraryImportResult> importBundle(
            @RequestBody(required = false) OperatorLibraryExportBundle bundle,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(defaultValue = "false") boolean ackWarnings,
            @RequestParam(defaultValue = "") String actor,
            @RequestParam(defaultValue = "") String changeSource,
            @RequestParam(defaultValue = "") String changeSummary,
            @RequestParam(defaultValue = "") String reason) {
        if (bundle != null && !OperatorLibraryExportBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            OperatorLibraryValidationResult validation = operatorLibraryBundleSchemaVersionValidation(bundle);
            return ResponseEntity.badRequest().body(OperatorLibraryImportResult.rejected(bundle,
                    bundle.library(), OperatorLibraryImportResult.ACTION_REJECTED, validation));
        }
        OperatorLibrary library = bundle == null ? null : bundle.library();
        if (library == null) {
            OperatorLibraryValidationResult validation = operatorLibraryBundleMissingSnapshotValidation();
            return ResponseEntity.badRequest().body(OperatorLibraryImportResult.rejected(bundle, validation));
        }

        boolean replacing = registry.find(library.libraryId()).isPresent();
        String mutationAction = replacing
                ? OperatorLibraryRevision.ACTION_REPLACE
                : OperatorLibraryRevision.ACTION_CREATE;
        OperatorLibraryValidationResult validation = validateAgainstRegistry(library, force);
        if (!validation.valid()) {
            return ResponseEntity.status(validationFailureStatus(validation))
                    .body(OperatorLibraryImportResult.rejected(bundle, library, mutationAction, validation));
        }
        OperatorLibraryValidationResult governanceEvidence = governanceEvidenceResult(library,
                revisionMetadata(actor, changeSource, changeSummary, reason), force, ackWarnings);
        if (governanceEvidence != null) {
            return ResponseEntity.badRequest()
                    .body(OperatorLibraryImportResult.rejected(bundle, library, mutationAction,
                            governanceEvidence));
        }
        if (hasWarningDiagnostic(validation) && !ackWarnings) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(OperatorLibraryImportResult.rejected(bundle, library, mutationAction, validation));
        }
        ResponseEntity<OperatorLibraryValidationResult> impact = replacementImpactResponse(library, force);
        if (impact != null) {
            return ResponseEntity.status(impact.getStatusCode())
                    .body(OperatorLibraryImportResult.rejected(bundle, library, mutationAction, impact.getBody()));
        }

        OperatorLibrary stored = registry.upsert(library, revisionMetadata(actor, changeSource, changeSummary,
                reason));
        OperatorLibraryRevision latestRevision = registry.revisions(stored.libraryId()).stream()
                .findFirst()
                .orElse(null);
        return ResponseEntity.status(replacing ? HttpStatus.OK : HttpStatus.CREATED)
                .body(OperatorLibraryImportResult.imported(bundle, stored, latestRevision, validation));
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
     * Exports the current library as a portable, reviewable operator-library artifact.
     *
     * @param libraryId library id
     * @return current library snapshot, latest revision evidence, and export-time validation
     */
    @GetMapping("/{libraryId}/export")
    public ResponseEntity<OperatorLibraryExportBundle> export(@PathVariable String libraryId) {
        return registry.find(libraryId)
                .map(library -> {
                    OperatorLibraryRevision latestRevision = registry.revisions(libraryId).stream()
                            .findFirst()
                            .orElse(null);
                    OperatorLibraryValidationResult validation = validateAgainstRegistry(library, true);
                    return ResponseEntity.ok(OperatorLibraryExportBundle.from(library, latestRevision, validation));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lists immutable registry snapshots for a library.
     *
     * @param libraryId library id
     * @return newest snapshots first
     */
    @GetMapping("/{libraryId}/revisions")
    public ResponseEntity<List<OperatorLibraryRevision>> revisions(@PathVariable String libraryId) {
        List<OperatorLibraryRevision> revisions = registry.revisions(libraryId);
        if (revisions.isEmpty() && registry.find(libraryId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(revisions);
    }

    /**
     * Loads one immutable registry snapshot.
     *
     * @param libraryId library id
     * @param revision revision number
     * @return matching snapshot
     */
    @GetMapping("/{libraryId}/revisions/{revision}")
    public ResponseEntity<OperatorLibraryRevision> revision(@PathVariable String libraryId,
                                                            @PathVariable long revision) {
        return registry.findRevision(libraryId, revision)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Compares two immutable registry snapshots.
     *
     * @param libraryId library id
     * @param baseRevision base revision number
     * @param targetRevision target revision number
     * @return machine-readable diff
     */
    @GetMapping("/{libraryId}/revisions/{baseRevision}/diff/{targetRevision}")
    public ResponseEntity<OperatorLibraryDiff> revisionDiff(@PathVariable String libraryId,
                                                            @PathVariable long baseRevision,
                                                            @PathVariable long targetRevision) {
        Optional<OperatorLibraryRevision> base = registry.findRevision(libraryId, baseRevision);
        Optional<OperatorLibraryRevision> target = registry.findRevision(libraryId, targetRevision);
        if (base.isEmpty() || target.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(OperatorLibraryDiff.between(base.get(), target.get()));
    }

    /**
     * Restores an immutable registry snapshot as a new latest library revision.
     *
     * @param libraryId library id
     * @param revision source revision number
     * @param force bypass stored-draft reference protection
     * @param ackWarnings true when the caller already reviewed non-blocking restore warnings
     * @param allowVersionRegression true for explicit emergency rollback to an older library version
     * @param actor user or system actor producing this registry revision
     * @param changeSource UI or integration source producing this registry revision
     * @param changeSummary human-readable change summary
     * @param reason optional reason for audit review
     * @return restored library
     */
    @PostMapping("/{libraryId}/revisions/{revision}/restore")
    public ResponseEntity<?> restore(@PathVariable String libraryId,
                                     @PathVariable long revision,
                                     @RequestParam(defaultValue = "false") boolean force,
                                     @RequestParam(defaultValue = "false") boolean ackWarnings,
                                     @RequestParam(defaultValue = "false") boolean allowVersionRegression,
                                     @RequestParam(defaultValue = "") String actor,
                                     @RequestParam(defaultValue = "") String changeSource,
                                     @RequestParam(defaultValue = "") String changeSummary,
                                     @RequestParam(defaultValue = "") String reason) {
        Optional<OperatorLibraryRevision> sourceRevision = registry.findRevision(libraryId, revision);
        if (sourceRevision.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        OperatorLibrary library = sourceRevision.get().library();
        if (library == null) {
            return ResponseEntity.badRequest().body(new VisualValidationResult(false, List.of(
                    VisualDiagnostic.error("visual.library.revisionSnapshotMissing",
                            "Operator library revision '%s@%d' cannot be restored because it has no library snapshot."
                                    .formatted(libraryId, revision),
                            "/library")
            )));
        }
        if (!libraryId.equals(library.libraryId())) {
            throw new IllegalArgumentException("Path libraryId '%s' does not match revision libraryId '%s'"
                    .formatted(libraryId, library.libraryId()));
        }
        OperatorLibraryValidationResult validation = validateAgainstRegistry(library, force,
                allowVersionRegression);
        if (!validation.valid()) {
            return ResponseEntity.status(validationFailureStatus(validation)).body(validation);
        }
        OperatorLibraryRevision.RevisionMetadata revisionMetadata = revisionMetadata(actor, changeSource,
                changeSummary, reason);
        OperatorLibraryValidationResult governanceEvidence = governanceEvidenceResult(library, revisionMetadata,
                force, ackWarnings);
        if (governanceEvidence != null) {
            return ResponseEntity.badRequest().body(governanceEvidence);
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
        return ResponseEntity.ok(registry.restore(sourceRevision.get(), revisionMetadata));
    }

    /**
     * Replaces a library.
     *
     * @param libraryId library id
     * @param library library body
     * @param force bypass stored-draft reference protection
     * @param ackWarnings true when the caller already reviewed non-blocking replacement warnings
     * @param actor user or system actor producing this registry revision
     * @param changeSource UI or integration source producing this registry revision
     * @param changeSummary human-readable change summary
     * @param reason optional reason for audit review
     * @return stored library
     */
    @PutMapping("/{libraryId}")
    public ResponseEntity<?> update(@PathVariable String libraryId,
                                    @RequestBody OperatorLibrary library,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    @RequestParam(defaultValue = "false") boolean ackWarnings,
                                    @RequestParam(defaultValue = "") String actor,
                                    @RequestParam(defaultValue = "") String changeSource,
                                    @RequestParam(defaultValue = "") String changeSummary,
                                    @RequestParam(defaultValue = "") String reason) {
        if (!libraryId.equals(library.libraryId())) {
            throw new IllegalArgumentException("Path libraryId '%s' does not match body libraryId '%s'"
                    .formatted(libraryId, library.libraryId()));
        }
        return importLibrary(library, force, ackWarnings, HttpStatus.OK,
                revisionMetadata(actor, changeSource, changeSummary, reason));
    }

    private ResponseEntity<?> importLibrary(OperatorLibrary library,
                                            boolean force,
                                            boolean ackWarnings,
                                            HttpStatus successStatus,
                                            OperatorLibraryRevision.RevisionMetadata revisionMetadata) {
        OperatorLibraryValidationResult validation = validateAgainstRegistry(library, force);
        if (!validation.valid()) {
            return ResponseEntity.status(validationFailureStatus(validation)).body(validation);
        }
        OperatorLibraryValidationResult governanceEvidence = governanceEvidenceResult(library, revisionMetadata,
                force, ackWarnings);
        if (governanceEvidence != null) {
            return ResponseEntity.badRequest().body(governanceEvidence);
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
        return ResponseEntity.status(successStatus).body(registry.upsert(library, revisionMetadata));
    }

    /**
     * Deletes a library.
     *
     * @param libraryId library id
     * @param force bypass stored-draft reference protection
     * @param actor user or system actor producing this registry revision
     * @param changeSource UI or integration source producing this registry revision
     * @param changeSummary human-readable change summary
     * @param reason optional reason for audit review
     * @return empty response
     */
    @DeleteMapping("/{libraryId}")
    public ResponseEntity<?> delete(@PathVariable String libraryId,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    @RequestParam(defaultValue = "") String actor,
                                    @RequestParam(defaultValue = "") String changeSource,
                                    @RequestParam(defaultValue = "") String changeSummary,
                                    @RequestParam(defaultValue = "") String reason) {
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
        OperatorLibraryValidationResult governanceEvidence = governanceEvidenceResult(library.orElse(null),
                revisionMetadata(actor, changeSource, changeSummary, reason), force, false);
        if (governanceEvidence != null) {
            return ResponseEntity.badRequest().body(governanceEvidence);
        }
        registry.delete(libraryId, revisionMetadata(actor, changeSource, changeSummary, reason));
        return ResponseEntity.noContent().build();
    }

    private static OperatorLibraryRevision.RevisionMetadata revisionMetadata(String actor,
                                                                             String changeSource,
                                                                             String changeSummary,
                                                                             String reason) {
        return OperatorLibraryRevision.RevisionMetadata.of(actor, changeSource, changeSummary, reason);
    }

    private static OperatorLibraryTextParseResult parseOperatorLibrarySourceText(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return OperatorLibraryTextParseResult.error(VisualDiagnostic.error(
                    "visual.library.source.missing",
                    "Operator library source text is required as JSON or YAML.",
                    "/sourceText"));
        }
        try {
            OperatorLibrary library = OPERATOR_LIBRARY_TEXT_MAPPER.readValue(sourceText, OperatorLibrary.class);
            if (library == null) {
                return OperatorLibraryTextParseResult.error(VisualDiagnostic.error(
                        "visual.library.source.missing",
                        "Operator library source text did not contain an operator library object.",
                        "/sourceText"));
            }
            return new OperatorLibraryTextParseResult(library, null);
        } catch (JsonProcessingException ex) {
            return OperatorLibraryTextParseResult.error(VisualDiagnostic.error(
                    "visual.library.source.malformed",
                    "Operator library source must be valid JSON or YAML: " + ex.getOriginalMessage(),
                    "/sourceText"));
        }
    }

    private record OperatorLibraryTextParseResult(
            OperatorLibrary library,
            OperatorLibraryValidationResult error
    ) {
        private static OperatorLibraryTextParseResult error(VisualDiagnostic diagnostic) {
            return new OperatorLibraryTextParseResult(null, new OperatorLibraryValidationResult(false,
                    List.of(diagnostic),
                    OperatorLibraryImpactReview.empty(),
                    OperatorLibraryProfile.empty()));
        }
    }

    private OperatorLibraryValidationResult validateAgainstRegistry(OperatorLibrary library, boolean force) {
        return validateAgainstRegistry(library, force, false);
    }

    private OperatorLibraryValidationResult validateAgainstRegistry(OperatorLibrary library,
                                                                    boolean force,
                                                                    boolean allowVersionRegression) {
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
        diagnostics.addAll(replacementVersionGovernanceDiagnostics(library, allowVersionRegression));
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

    private static boolean hasWarningDiagnostic(OperatorLibraryValidationResult validation) {
        return validation != null && validation.diagnostics().stream()
                .anyMatch(diagnostic -> "WARNING".equalsIgnoreCase(diagnostic.level()));
    }

    private static OperatorLibraryValidationResult operatorLibraryBundleMissingSnapshotValidation() {
        return new OperatorLibraryValidationResult(false, List.of(VisualDiagnostic.error(
                "visual.library.bundle.snapshotMissing",
                "Operator library import bundle must include a library snapshot.",
                "/library"
        )), OperatorLibraryImpactReview.empty(), OperatorLibraryProfile.empty());
    }

    private static OperatorLibraryValidationResult operatorLibraryBundleSchemaVersionValidation(
            OperatorLibraryExportBundle bundle) {
        String actual = bundle == null ? "" : bundle.schemaVersion();
        return new OperatorLibraryValidationResult(false, List.of(VisualDiagnostic.error(
                "visual.library.bundle.schemaVersionUnsupported",
                "Operator library import bundle schemaVersion '%s' is not supported; expected '%s'."
                        .formatted(actual, OperatorLibraryExportBundle.SCHEMA_VERSION),
                "/schemaVersion",
                Map.of("actual", actual, "expected", OperatorLibraryExportBundle.SCHEMA_VERSION)
        )), OperatorLibraryImpactReview.empty(), OperatorLibraryProfile.empty());
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

    private List<VisualDiagnostic> replacementVersionGovernanceDiagnostics(OperatorLibrary replacement,
                                                                           boolean allowVersionRegression) {
        if (replacement == null) {
            return List.of();
        }
        Optional<OperatorLibrary> existing = registry.find(replacement.libraryId());
        if (existing.isEmpty()) {
            return List.of();
        }
        Optional<SemanticVersion> previousVersion = SemanticVersion.parse(existing.get().version());
        Optional<SemanticVersion> replacementVersion = SemanticVersion.parse(replacement.version());
        if (previousVersion.isEmpty() || replacementVersion.isEmpty()) {
            return List.of();
        }

        LibraryReplacementChange change = LibraryReplacementChange.from(existing.get(), replacement);
        if (!change.changed()) {
            return List.of();
        }

        SemanticVersion previous = previousVersion.get();
        SemanticVersion next = replacementVersion.get();
        if (next.compareCore(previous) < 0) {
            if (allowVersionRegression) {
                return List.of(VisualDiagnostic.warning("visual.library.restore.versionRegressionAllowed",
                        "Operator library '%s' restore changes catalog surface and regresses version from '%s' to '%s'; acknowledge only for controlled rollback after reviewing affected drafts and publications."
                                .formatted(replacement.libraryId(), existing.get().version(), replacement.version()),
                        "/version",
                        changeMetadata(existing.get().version(), replacement.version(), change)));
            }
            return List.of(VisualDiagnostic.error("visual.library.version.regressed",
                    "Operator library '%s' replacement changes catalog surface but regresses version from '%s' to '%s'; publish a forward semantic version instead."
                            .formatted(replacement.libraryId(), existing.get().version(), replacement.version()),
                    "/version",
                    changeMetadata(existing.get().version(), replacement.version(), change)));
        }
        if (change.breaking() && next.major() <= previous.major()) {
            return List.of(VisualDiagnostic.warning("visual.library.version.breakingRequiresMajor",
                    "Operator library '%s' replacement contains breaking operator contract changes but version moves from '%s' to '%s'; use a new major version or acknowledge the governance warning after review."
                            .formatted(replacement.libraryId(), existing.get().version(), replacement.version()),
                    "/version",
                    changeMetadata(existing.get().version(), replacement.version(), change)));
        }
        if (change.compatible() && !next.hasMinorOrMajorBumpFrom(previous)) {
            return List.of(VisualDiagnostic.warning("visual.library.version.compatibleRequiresMinor",
                    "Operator library '%s' replacement adds or compatibly changes operator contracts but version moves from '%s' to '%s'; use a minor version bump or acknowledge the governance warning after review."
                            .formatted(replacement.libraryId(), existing.get().version(), replacement.version()),
                    "/version",
                    changeMetadata(existing.get().version(), replacement.version(), change)));
        }
        if (next.compareCore(previous) == 0) {
            return List.of(VisualDiagnostic.warning("visual.library.version.unchangedForReplacement",
                    "Operator library '%s' replacement changes catalog surface without advancing version '%s'; acknowledge only for non-contract metadata repairs."
                            .formatted(replacement.libraryId(), replacement.version()),
                    "/version",
                    changeMetadata(existing.get().version(), replacement.version(), change)));
        }
        return List.of();
    }

    private static Map<String, Object> changeMetadata(String previousVersion,
                                                      String replacementVersion,
                                                      LibraryReplacementChange change) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("previousVersion", previousVersion);
        metadata.put("replacementVersion", replacementVersion);
        metadata.put("changeRisk", change.risk());
        metadata.put("changeCategories", change.categories());
        metadata.put("changeSummary", change.summary());
        metadata.put("operatorRefs", change.operatorRefs());
        return metadata;
    }

    private record SemanticVersion(int major, int minor, int patch) {
        private static Optional<SemanticVersion> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            String core = value.trim().split("[-+]", 2)[0];
            String[] parts = core.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            try {
                return Optional.of(new SemanticVersion(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2])
                ));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }

        private int compareCore(SemanticVersion other) {
            int majorCompare = Integer.compare(major, other.major);
            if (majorCompare != 0) {
                return majorCompare;
            }
            int minorCompare = Integer.compare(minor, other.minor);
            if (minorCompare != 0) {
                return minorCompare;
            }
            return Integer.compare(patch, other.patch);
        }

        private boolean hasMinorOrMajorBumpFrom(SemanticVersion previous) {
            return major > previous.major || major == previous.major && minor > previous.minor;
        }
    }

    private record LibraryReplacementChange(
            Set<String> operatorRefs,
            List<String> categories,
            String risk,
            String summary
    ) {
        private static LibraryReplacementChange from(OperatorLibrary existing, OperatorLibrary replacement) {
            Map<String, OperatorDefinition> existingByRef = operatorsByRef(existing);
            Map<String, OperatorDefinition> replacementByRef = replacement.visibleInCatalog(true)
                    ? operatorsByRef(replacement)
                    : Map.of();
            Set<String> refs = new LinkedHashSet<>();
            Set<String> categories = new LinkedHashSet<>();
            List<String> changes = new ArrayList<>();

            existingByRef.keySet().stream()
                    .filter(operatorRef -> !replacementByRef.containsKey(operatorRef))
                    .forEach(operatorRef -> {
                        refs.add(operatorRef);
                        categories.add(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA);
                        changes.add("operatorRef '" + operatorRef + "' removed");
                    });
            replacementByRef.keySet().stream()
                    .filter(operatorRef -> !existingByRef.containsKey(operatorRef))
                    .forEach(operatorRef -> {
                        refs.add(operatorRef);
                        categories.add(OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA);
                        changes.add("operatorRef '" + operatorRef + "' added");
                    });
            replacementByRef.forEach((operatorRef, operator) -> {
                OperatorDefinition previous = existingByRef.get(operatorRef);
                if (previous == null || previous.fingerprint().equals(operator.fingerprint())) {
                    return;
                }
                OperatorDefinitionChangeSummary.ChangeReport report = OperatorDefinitionChangeSummary.analyze(
                        previous, operator);
                refs.add(operatorRef);
                categories.addAll(report.categories());
                changes.add("operatorRef '" + operatorRef + "' changed: " + report.summary());
            });
            List<String> sortedCategories = categories.stream()
                    .sorted((left, right) -> Integer.compare(
                            OperatorDefinitionChangeSummary.riskRank(right),
                            OperatorDefinitionChangeSummary.riskRank(left)))
                    .toList();
            String risk = sortedCategories.isEmpty()
                    ? OperatorDefinitionChangeSummary.RISK_METADATA
                    : sortedCategories.getFirst();
            return new LibraryReplacementChange(
                    java.util.Collections.unmodifiableSet(new LinkedHashSet<>(refs)),
                    sortedCategories,
                    risk,
                    summarize(changes)
            );
        }

        private boolean changed() {
            return !categories.isEmpty();
        }

        private boolean breaking() {
            return categories.contains(OperatorDefinitionChangeSummary.RISK_BREAKING_SCHEMA);
        }

        private boolean compatible() {
            return categories.contains(OperatorDefinitionChangeSummary.RISK_COMPATIBLE_SCHEMA);
        }

        private static Map<String, OperatorDefinition> operatorsByRef(OperatorLibrary library) {
            Map<String, OperatorDefinition> byRef = new LinkedHashMap<>();
            for (OperatorDefinition operator : library.operators()) {
                if (operator != null && !operator.operatorRef().isBlank()) {
                    byRef.putIfAbsent(operator.operatorRef(), operator);
                }
            }
            return byRef;
        }

        private static String summarize(List<String> changes) {
            if (changes.isEmpty()) {
                return "";
            }
            int visible = Math.min(5, changes.size());
            String summary = String.join("; ", changes.subList(0, visible));
            int remaining = changes.size() - visible;
            return remaining > 0 ? summary + "; +" + remaining + " more" : summary;
        }
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
        OperatorLibraryProfile profile = OperatorLibraryProfile.from(library, diagnostics);
        if (diagnostics == null || diagnostics.isEmpty()) {
            return new OperatorLibraryValidationResult(false, diagnostics, OperatorLibraryImpactReview.empty(),
                    profile);
        }
        return new OperatorLibraryValidationResult(false, diagnostics,
                OperatorLibraryImpactReview.fromDiagnostics(diagnostics, impactOperatorRefs(library, diagnostics)),
                profile);
    }

    private OperatorLibraryValidationResult governanceEvidenceResult(
            OperatorLibrary library,
            OperatorLibraryRevision.RevisionMetadata metadata,
            boolean force,
            boolean ackWarnings) {
        if (!force && !ackWarnings) {
            return null;
        }
        OperatorLibraryRevision.RevisionMetadata safeMetadata = metadata == null
                ? OperatorLibraryRevision.RevisionMetadata.empty()
                : metadata;
        List<String> requiredFor = new ArrayList<>();
        if (force) {
            requiredFor.add("force");
        }
        if (ackWarnings) {
            requiredFor.add("ackWarnings");
        }

        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> metadataPayload = Map.of("requiredFor", List.copyOf(requiredFor));
        if (safeMetadata.actor().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.governanceEvidenceMissing",
                    "Operator library high-risk mutation requires actor when using "
                            + String.join(", ", requiredFor) + ".",
                    "/actor",
                    metadataPayload));
        }
        if (safeMetadata.reason().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.library.governanceEvidenceMissing",
                    "Operator library high-risk mutation requires reason when using "
                            + String.join(", ", requiredFor) + ".",
                    "/reason",
                    metadataPayload));
        }
        return diagnostics.isEmpty() ? null : validationResult(library, diagnostics);
    }

    private Set<String> impactOperatorRefs(OperatorLibrary library, List<VisualDiagnostic> diagnostics) {
        Set<String> operatorRefs = new LinkedHashSet<>();
        operatorRefs.addAll(operatorRefsFromOperatorDiagnosticTargets(library, diagnostics));
        operatorRefs.addAll(operatorRefsFromDiagnosticMetadata(diagnostics));
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

    private Set<String> operatorRefsFromDiagnosticMetadata(List<VisualDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Set.of();
        }
        Set<String> operatorRefs = new LinkedHashSet<>();
        for (VisualDiagnostic diagnostic : diagnostics) {
            Object refs = diagnostic == null ? null : diagnostic.metadata().get("operatorRefs");
            if (!(refs instanceof Iterable<?> iterable)) {
                continue;
            }
            for (Object ref : iterable) {
                if (ref != null && !ref.toString().isBlank()) {
                    operatorRefs.add(ref.toString());
                }
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
