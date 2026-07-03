package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftHistorySummary;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Public API for environment-level visual authoring asset health.
 */
@RestController
@RequestMapping("/api/visual/assets")
public class VisualAssetOverviewController {

    private final GraphDraftRepository draftRepository;
    private final GraphDraftValidator validator;
    private final VisualOperatorCatalog catalog;
    private final VisualGraphPublicationRepository publicationRepository;
    private final VisualRuntimeBindingImplementationRepository implementationRepository;

    /**
     * @param draftRepository draft repository
     * @param validator draft validator
     * @param catalog visual operator catalog
     * @param publicationRepository publication repository
     */
    public VisualAssetOverviewController(GraphDraftRepository draftRepository,
                                         GraphDraftValidator validator,
                                         VisualOperatorCatalog catalog,
                                         VisualGraphPublicationRepository publicationRepository) {
        this(draftRepository, validator, catalog, publicationRepository,
                new InMemoryVisualRuntimeBindingImplementationRepository());
    }

    /**
     * @param draftRepository draft repository
     * @param validator draft validator
     * @param catalog visual operator catalog
     * @param publicationRepository publication repository
     * @param implementationRepository runtime implementation binding repository
     */
    @Autowired
    public VisualAssetOverviewController(GraphDraftRepository draftRepository,
                                         GraphDraftValidator validator,
                                         VisualOperatorCatalog catalog,
                                         VisualGraphPublicationRepository publicationRepository,
                                         VisualRuntimeBindingImplementationRepository implementationRepository) {
        this.draftRepository = draftRepository;
        this.validator = validator;
        this.catalog = catalog;
        this.publicationRepository = publicationRepository;
        this.implementationRepository = implementationRepository == null
                ? new InMemoryVisualRuntimeBindingImplementationRepository()
                : implementationRepository;
    }

    /**
     * Summarizes drafts, publications, and catalog readiness for one authoring scope.
     *
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @param actionLimit requested number of action item details
     * @param actionOffset zero-based action item offset after filtering
     * @param actionSeverity optional action severity filter
     * @param actionType optional action type filter
     * @param actionTargetKind optional action target kind filter
     * @param actionOperatorRef optional related operator reference filter
     * @param actionOperatorLibraryId optional related owner operator library id filter
     * @return visual asset overview
     */
    @GetMapping("/overview")
    public VisualAssetOverview overview(@RequestParam(defaultValue = "") String tenantId,
                                        @RequestParam(defaultValue = "") String namespace,
                                        @RequestParam(defaultValue = "") String environment,
                                        @RequestParam(defaultValue = "12") int actionLimit,
                                        @RequestParam(defaultValue = "0") int actionOffset,
                                        @RequestParam(defaultValue = "") String actionSeverity,
                                        @RequestParam(defaultValue = "") String actionType,
                                        @RequestParam(defaultValue = "") String actionTargetKind,
                                        @RequestParam(defaultValue = "") String actionOperatorRef,
                                        @RequestParam(defaultValue = "") String actionOperatorLibraryId) {
        List<GraphDraftSummary> draftSummaries = draftSummaries(tenantId, namespace, environment);
        List<VisualGraphPublicationSummary> publicationSummaries =
                publicationSummaries(tenantId, namespace, environment);
        OperatorCatalogQuery query = new OperatorCatalogQuery(
                "",
                List.of(),
                false,
                false,
                tenantId,
                namespace,
                environment,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        List<OperatorDefinition> operators = catalog.list(query);
        List<VisualDiagnostic> diagnostics = catalog.diagnostics(query);
        return VisualAssetOverview.from(
                draftSummaries,
                publicationSummaries,
                operators,
                diagnostics,
                catalog.operatorLibraryIdsByOperatorRef(true),
                tenantId,
                namespace,
                environment,
                actionLimit,
                actionOffset,
                actionSeverity,
                actionType,
                actionTargetKind,
                actionOperatorRef,
                actionOperatorLibraryId
        );
    }

    /**
     * Lists node-scoped runtime binding gaps for design-capable but non-executable graph assets.
     *
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @param limit requested number of requirement item details
     * @param offset zero-based requirement item offset after filtering
     * @param targetKind optional target kind filter
     * @param operatorRef optional operator reference filter
     * @param operatorLibraryId optional owner operator library id filter
     * @param bindingKind optional binding kind filter
     * @param handoffLane optional runtime-plane handoff lane filter
     * @param handoffKind optional runtime-plane handoff work kind filter
     * @param handoffTarget optional runtime-plane routing target filter
     * @param sourceKind optional source kind filter
     * @param loweringMode optional lowering mode filter
     * @param readinessState optional graph or node readiness state filter
     * @param requirementKey optional stable requirement key filter
     * @return runtime binding requirement index
     */
    @GetMapping("/runtime-binding-requirements")
    public VisualRuntimeBindingRequirements runtimeBindingRequirements(
            @RequestParam(defaultValue = "") String tenantId,
            @RequestParam(defaultValue = "") String namespace,
            @RequestParam(defaultValue = "") String environment,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "") String targetKind,
            @RequestParam(defaultValue = "") String operatorRef,
            @RequestParam(defaultValue = "") String operatorLibraryId,
            @RequestParam(defaultValue = "") String bindingKind,
            @RequestParam(defaultValue = "") String handoffLane,
            @RequestParam(defaultValue = "") String handoffKind,
            @RequestParam(defaultValue = "") String handoffTarget,
            @RequestParam(defaultValue = "") String sourceKind,
            @RequestParam(defaultValue = "") String loweringMode,
            @RequestParam(defaultValue = "") String readinessState,
            @RequestParam(defaultValue = "") String requirementKey) {
        return VisualRuntimeBindingRequirements.from(
                draftSummaries(tenantId, namespace, environment),
                publicationSummaries(tenantId, namespace, environment),
                catalog.operatorLibraryIdsByOperatorRef(true),
                tenantId,
                namespace,
                environment,
                limit,
                offset,
                targetKind,
                operatorRef,
                operatorLibraryId,
                bindingKind,
                handoffLane,
                handoffKind,
                handoffTarget,
                sourceKind,
                loweringMode,
                readinessState,
                requirementKey
        );
    }

    /**
     * Exports the current runtime-binding requirement window as a portable handoff bundle.
     *
     * <p>The bundle is a transfer snapshot for runtime-plane implementation teams. It
     * is derived from the same read model as {@link #runtimeBindingRequirements} and
     * does not create workflow state.</p>
     *
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @param limit requested number of requirement item details
     * @param offset zero-based requirement item offset after filtering
     * @param targetKind optional target kind filter
     * @param operatorRef optional operator reference filter
     * @param operatorLibraryId optional owner operator library id filter
     * @param bindingKind optional binding kind filter
     * @param handoffLane optional runtime-plane handoff lane filter
     * @param handoffKind optional runtime-plane handoff work kind filter
     * @param handoffTarget optional runtime-plane routing target filter
     * @param sourceKind optional operator source kind filter
     * @param loweringMode optional lowering mode filter
     * @param readinessState optional graph or node readiness state filter
     * @param requirementKey optional stable requirement key filter
     * @return portable runtime-binding handoff bundle
     */
    @GetMapping("/runtime-binding-requirements/handoff-bundle")
    public VisualRuntimeBindingHandoffBundle runtimeBindingHandoffBundle(
            @RequestParam(defaultValue = "") String tenantId,
            @RequestParam(defaultValue = "") String namespace,
            @RequestParam(defaultValue = "") String environment,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "") String targetKind,
            @RequestParam(defaultValue = "") String operatorRef,
            @RequestParam(defaultValue = "") String operatorLibraryId,
            @RequestParam(defaultValue = "") String bindingKind,
            @RequestParam(defaultValue = "") String handoffLane,
            @RequestParam(defaultValue = "") String handoffKind,
            @RequestParam(defaultValue = "") String handoffTarget,
            @RequestParam(defaultValue = "") String sourceKind,
            @RequestParam(defaultValue = "") String loweringMode,
            @RequestParam(defaultValue = "") String readinessState,
            @RequestParam(defaultValue = "") String requirementKey) {
        VisualRuntimeBindingRequirements index = runtimeBindingRequirements(
                tenantId,
                namespace,
                environment,
                limit,
                offset,
                targetKind,
                operatorRef,
                operatorLibraryId,
                bindingKind,
                handoffLane,
                handoffKind,
                handoffTarget,
                sourceKind,
                loweringMode,
                readinessState,
                requirementKey
        );
        return VisualRuntimeBindingHandoffBundle.from(index, operatorContractsForRuntimeBindingHandoff(index));
    }

    /**
     * Reviews an exported runtime-binding handoff bundle against the current read model.
     *
     * <p>This endpoint is intentionally read-only. It lets runtime-plane teams or
     * browser users verify whether a portable handoff snapshot is still current,
     * stale, drifted, or already resolved without creating a second source of
     * workflow truth.</p>
     *
     * @param bundle portable runtime-binding handoff bundle
     * @return current-environment handoff review
     */
    @PostMapping("/runtime-binding-requirements/handoff-review")
    public ResponseEntity<VisualRuntimeBindingHandoffReview> reviewRuntimeBindingHandoffBundle(
            @RequestBody(required = false) VisualRuntimeBindingHandoffBundle bundle) {
        if (bundle == null) {
            return ResponseEntity.badRequest().body(VisualRuntimeBindingHandoffReview.rejected(
                    null,
                    List.of(VisualDiagnostic.error(
                            "visual.runtimeBindingHandoff.bundleMissing",
                            "Runtime binding handoff review requires a handoff bundle body.",
                            "/"))
            ));
        }
        if (!VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(VisualRuntimeBindingHandoffReview.rejected(
                    bundle,
                    List.of(VisualDiagnostic.error(
                            "visual.runtimeBindingHandoff.schemaVersionUnsupported",
                            "Runtime binding handoff bundle schemaVersion '%s' is not supported; expected '%s'."
                                    .formatted(bundle.schemaVersion(), VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION),
                            "/schemaVersion",
                            Map.of(
                                    "actual", bundle.schemaVersion(),
                                    "expected", VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION
                            )))
            ));
        }
        if (!bundle.bundleFingerprintVerified()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(VisualRuntimeBindingHandoffReview.rejected(
                    bundle,
                    List.of(VisualDiagnostic.error(
                            "visual.runtimeBindingHandoff.fingerprintMismatch",
                            "Runtime binding handoff bundle fingerprint '%s' does not match the submitted bundle material; expected '%s'."
                                    .formatted(bundle.bundleFingerprint(), bundle.computedBundleFingerprint()),
                            "/bundleFingerprint",
                            Map.of(
                                    "actual", bundle.bundleFingerprint(),
                                    "expected", bundle.computedBundleFingerprint()
                            )))
            ));
        }

        VisualAssetOverview.AuthoringScope scope = bundle.scope() == null
                ? VisualAssetOverview.AuthoringScope.all()
                : bundle.scope();
        VisualRuntimeBindingRequirements.RequirementFilter filter = bundle.filter() == null
                ? VisualRuntimeBindingRequirements.RequirementFilter.all()
                : bundle.filter();
        VisualRuntimeBindingRequirements currentWindow = runtimeBindingRequirements(
                scope.tenantId(),
                scope.namespace(),
                scope.environment(),
                bundle.itemLimit(),
                bundle.offset(),
                filter.targetKind(),
                filter.operatorRef(),
                filter.operatorLibraryId(),
                filter.bindingKind(),
                filter.handoffLane(),
                filter.handoffKind(),
                filter.handoffTarget(),
                filter.sourceKind(),
                filter.loweringMode(),
                filter.readinessState(),
                filter.requirementKey()
        );
        Map<String, VisualRuntimeBindingRequirements.RequirementItem> currentByRequirementKey =
                new LinkedHashMap<>();
        for (String requirementKey : VisualRuntimeBindingHandoffReview.requirementKeys(bundle)) {
            VisualRuntimeBindingRequirements currentRequirement = runtimeBindingRequirements(
                    scope.tenantId(),
                    scope.namespace(),
                    scope.environment(),
                    1,
                    0,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    requirementKey
            );
            currentRequirement.items().stream()
                    .findFirst()
                    .ifPresent(item -> currentByRequirementKey.put(requirementKey, item));
        }
        return ResponseEntity.ok(VisualRuntimeBindingHandoffReview.from(
                bundle,
                currentByRequirementKey,
                currentWindow
        ));
    }

    /**
     * Validates a runtime-plane implementation binding proposal against a handoff operator contract.
     *
     * <p>This endpoint is intentionally stateless. It is the pre-mutation gate for
     * a future bind/supersede lifecycle and does not close runtime-binding
     * requirements by itself.</p>
     *
     * @param request submitted implementation binding proposal
     * @return validation result with contract, catalog, and evidence diagnostics
     */
    @PostMapping("/runtime-binding-requirements/implementation-bindings/validate")
    public ResponseEntity<VisualRuntimeBindingImplementationValidation> validateRuntimeBindingImplementation(
            @RequestBody(required = false) VisualRuntimeBindingImplementationValidation.Request request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(VisualRuntimeBindingImplementationValidation.missingRequest());
        }
        String operatorRef = implementationOperatorRef(request);
        OperatorDefinition currentOperator = operatorRef.isBlank()
                ? null
                : catalog.find(operatorRef).orElse(null);
        VisualRuntimeBindingImplementationValidation result =
                VisualRuntimeBindingImplementationValidation.from(request, currentOperator);
        boolean unsupportedVersion = result.diagnostics().stream()
                .anyMatch(diagnostic ->
                        "visual.runtimeBindingImplementation.schemaVersionUnsupported".equals(diagnostic.code()));
        return unsupportedVersion
                ? ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result)
                : ResponseEntity.ok(result);
    }

    /**
     * Lists submitted runtime implementation binding proposals.
     *
     * @param operatorRef optional operator reference filter
     * @param state optional binding proposal state filter
     * @return matching implementation binding proposals
     */
    @GetMapping("/runtime-binding-requirements/implementation-bindings")
    public List<VisualRuntimeBindingImplementationBinding> runtimeBindingImplementationBindings(
            @RequestParam(defaultValue = "") String operatorRef,
            @RequestParam(defaultValue = "") String state) {
        String normalizedOperatorRef = operatorRef == null ? "" : operatorRef.trim();
        String normalizedState = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        return implementationRepository.all().stream()
                .filter(binding -> normalizedOperatorRef.isBlank()
                        || binding.operatorRef().equals(normalizedOperatorRef))
                .filter(binding -> normalizedState.isBlank()
                        || binding.state().equals(normalizedState))
                .toList();
    }

    /**
     * Submits a runtime implementation binding proposal after contract validation.
     *
     * <p>Rejected proposals are not persisted. Accepted proposals are still only
     * control-plane records; they do not close runtime-binding requirements or
     * mutate graph/publication state.</p>
     *
     * @param request submitted implementation binding proposal
     * @return stored proposal when accepted, otherwise validation diagnostics
     */
    @PostMapping("/runtime-binding-requirements/implementation-bindings")
    public ResponseEntity<Object> submitRuntimeBindingImplementation(
            @RequestBody(required = false) VisualRuntimeBindingImplementationValidation.Request request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(VisualRuntimeBindingImplementationValidation.missingRequest());
        }
        String operatorRef = implementationOperatorRef(request);
        OperatorDefinition currentOperator = operatorRef.isBlank()
                ? null
                : catalog.find(operatorRef).orElse(null);
        VisualRuntimeBindingImplementationValidation validation =
                VisualRuntimeBindingImplementationValidation.from(request, currentOperator);
        if (!validation.valid()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(validation);
        }
        String bindingId = implementationBindingId(request);
        if (!bindingId.isBlank() && implementationRepository.find(bindingId).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(validationWithBlockingDiagnostic(
                    validation,
                    VisualDiagnostic.error(
                            "visual.runtimeBindingImplementation.bindingIdDuplicate",
                            "Runtime binding implementation bindingId '%s' already exists.".formatted(bindingId),
                            "/implementation/bindingId",
                            Map.of("bindingId", bindingId))
            ));
        }
        VisualRuntimeBindingImplementationBinding stored;
        try {
            stored = implementationRepository.create(
                    VisualRuntimeBindingImplementationBinding.from(request, validation));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(validationWithBlockingDiagnostic(
                    validation,
                    VisualDiagnostic.error(
                            "visual.runtimeBindingImplementation.bindingIdDuplicate",
                            e.getMessage(),
                            "/implementation/bindingId",
                            Map.of("bindingId", bindingId))
            ));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }

    /**
     * Marks one accepted implementation proposal as the active bound implementation fact.
     *
     * <p>This records control-plane lifecycle evidence only. It does not rewrite
     * graph artifacts and does not pretend that an executable adapter exists.</p>
     *
     * @param bindingId binding proposal id
     * @param request transition audit request
     * @return lifecycle result
     */
    @PostMapping("/runtime-binding-requirements/implementation-bindings/{bindingId}/bind")
    public ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> bindRuntimeBindingImplementation(
            @PathVariable String bindingId,
            @RequestBody(required = false) VisualRuntimeBindingImplementationTransitionRequest request) {
        ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> requestError =
                transitionRequestError(request, false);
        if (requestError != null) {
            return requestError;
        }
        VisualRuntimeBindingImplementationBinding binding = implementationRepository.find(bindingId)
                .orElse(null);
        if (binding == null) {
            return lifecycleFailure(
                    HttpStatus.NOT_FOUND,
                    "missing",
                    "visual.runtimeBindingImplementation.bindingMissing",
                    "Runtime binding implementation '%s' does not exist.".formatted(bindingId),
                    "/bindingId",
                    null,
                    null,
                    Map.of("bindingId", bindingId)
            );
        }
        ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> stateError =
                bindableStateError(binding, request, null);
        if (stateError != null) {
            return stateError;
        }
        VisualRuntimeBindingImplementationBinding active = implementationRepository
                .findActiveBound(binding.operatorRef())
                .filter(existing -> !existing.bindingId().equals(binding.bindingId()))
                .orElse(null);
        if (active != null) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.activeBindingExists",
                    "Operator '%s' already has active runtime binding '%s'; use supersede instead."
                            .formatted(binding.operatorRef(), active.bindingId()),
                    "/bindingId",
                    binding,
                    active,
                    Map.of("operatorRef", binding.operatorRef(), "activeBindingId", active.bindingId())
            );
        }
        Instant now = Instant.now();
        VisualRuntimeBindingImplementationBinding.LifecycleEvent event = lifecycleEvent(
                "bound",
                binding.state(),
                VisualRuntimeBindingImplementationBinding.STATE_BOUND,
                request,
                "",
                now
        );
        VisualRuntimeBindingImplementationBinding updated = implementationRepository.update(
                binding.withLifecycleTransition(
                        VisualRuntimeBindingImplementationBinding.STATE_BOUND,
                        "success",
                        null,
                        null,
                        event,
                        now
                ));
        return ResponseEntity.ok(VisualRuntimeBindingImplementationLifecycleResult.accepted(
                "Runtime binding implementation '%s' is now bound.".formatted(updated.bindingId()),
                updated,
                null
        ));
    }

    /**
     * Supersedes one active implementation binding with another accepted proposal.
     *
     * @param bindingId active binding id to supersede
     * @param request transition audit request with replacementBindingId
     * @return lifecycle result
     */
    @PostMapping("/runtime-binding-requirements/implementation-bindings/{bindingId}/supersede")
    public ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> supersedeRuntimeBindingImplementation(
            @PathVariable String bindingId,
            @RequestBody(required = false) VisualRuntimeBindingImplementationTransitionRequest request) {
        ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> requestError =
                transitionRequestError(request, true);
        if (requestError != null) {
            return requestError;
        }
        VisualRuntimeBindingImplementationBinding current = implementationRepository.find(bindingId)
                .orElse(null);
        if (current == null) {
            return lifecycleFailure(
                    HttpStatus.NOT_FOUND,
                    "missing",
                    "visual.runtimeBindingImplementation.bindingMissing",
                    "Runtime binding implementation '%s' does not exist.".formatted(bindingId),
                    "/bindingId",
                    null,
                    null,
                    Map.of("bindingId", bindingId)
            );
        }
        VisualRuntimeBindingImplementationBinding replacement =
                implementationRepository.find(request.replacementBindingId()).orElse(null);
        if (replacement == null) {
            return lifecycleFailure(
                    HttpStatus.NOT_FOUND,
                    "missing",
                    "visual.runtimeBindingImplementation.replacementMissing",
                    "Replacement runtime binding implementation '%s' does not exist."
                            .formatted(request.replacementBindingId()),
                    "/replacementBindingId",
                    current,
                    null,
                    Map.of("replacementBindingId", request.replacementBindingId())
            );
        }
        if (current.bindingId().equals(replacement.bindingId())) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.replacementSelf",
                    "A runtime binding implementation cannot supersede itself.",
                    "/replacementBindingId",
                    current,
                    replacement,
                    Map.of("bindingId", current.bindingId())
            );
        }
        if (!current.bound()) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.currentNotBound",
                    "Runtime binding implementation '%s' must be bound before it can be superseded."
                            .formatted(current.bindingId()),
                    "/bindingId",
                    current,
                    replacement,
                    Map.of("bindingId", current.bindingId(), "state", current.state())
            );
        }
        if (!current.operatorRef().equals(replacement.operatorRef())) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.replacementOperatorMismatch",
                    "Replacement binding operatorRef '%s' does not match active binding operatorRef '%s'."
                            .formatted(replacement.operatorRef(), current.operatorRef()),
                    "/replacementBindingId",
                    current,
                    replacement,
                    Map.of("currentOperatorRef", current.operatorRef(),
                            "replacementOperatorRef", replacement.operatorRef())
            );
        }
        ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> replacementStateError =
                bindableStateError(replacement, request, current);
        if (replacementStateError != null) {
            return replacementStateError;
        }
        VisualRuntimeBindingImplementationBinding active = implementationRepository
                .findActiveBound(current.operatorRef())
                .filter(existing -> !existing.bindingId().equals(current.bindingId()))
                .orElse(null);
        if (active != null) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.activeBindingExists",
                    "Operator '%s' has active runtime binding '%s' instead of '%s'."
                            .formatted(current.operatorRef(), active.bindingId(), current.bindingId()),
                    "/bindingId",
                    current,
                    replacement,
                    Map.of("operatorRef", current.operatorRef(), "activeBindingId", active.bindingId())
            );
        }

        Instant now = Instant.now();
        VisualRuntimeBindingImplementationBinding replacementBound = implementationRepository.update(
                replacement.withLifecycleTransition(
                        VisualRuntimeBindingImplementationBinding.STATE_BOUND,
                        "success",
                        current.bindingId(),
                        null,
                        lifecycleEvent(
                                "bound",
                                replacement.state(),
                                VisualRuntimeBindingImplementationBinding.STATE_BOUND,
                                request,
                                current.bindingId(),
                                now
                        ),
                        now
                ));
        VisualRuntimeBindingImplementationBinding currentSuperseded = implementationRepository.update(
                current.withLifecycleTransition(
                        VisualRuntimeBindingImplementationBinding.STATE_SUPERSEDED,
                        "info",
                        null,
                        replacement.bindingId(),
                        lifecycleEvent(
                                "superseded",
                                current.state(),
                                VisualRuntimeBindingImplementationBinding.STATE_SUPERSEDED,
                                request,
                                replacement.bindingId(),
                                now
                        ),
                        now
                ));
        return ResponseEntity.ok(VisualRuntimeBindingImplementationLifecycleResult.accepted(
                "Runtime binding implementation '%s' superseded '%s'."
                        .formatted(replacementBound.bindingId(), currentSuperseded.bindingId()),
                currentSuperseded,
                replacementBound
        ));
    }

    VisualAssetOverview overview(String tenantId, String namespace, String environment) {
        return overview(tenantId, namespace, environment, VisualAssetOverview.DEFAULT_ACTION_ITEM_LIMIT);
    }

    VisualAssetOverview overview(String tenantId, String namespace, String environment, int actionLimit) {
        return overview(tenantId, namespace, environment, actionLimit, 0, "", "", "");
    }

    VisualAssetOverview overview(String tenantId,
                                 String namespace,
                                 String environment,
                                 int actionLimit,
                                 int actionOffset,
                                 String actionSeverity,
                                 String actionType,
                                 String actionTargetKind) {
        return overview(tenantId, namespace, environment, actionLimit, actionOffset, actionSeverity, actionType,
                actionTargetKind, "");
    }

    VisualAssetOverview overview(String tenantId,
                                 String namespace,
                                 String environment,
                                 int actionLimit,
                                 int actionOffset,
                                 String actionSeverity,
                                 String actionType,
                                 String actionTargetKind,
                                 String actionOperatorRef) {
        return overview(tenantId, namespace, environment, actionLimit, actionOffset, actionSeverity, actionType,
                actionTargetKind, actionOperatorRef, "");
    }

    VisualRuntimeBindingRequirements runtimeBindingRequirements(String tenantId,
                                                                String namespace,
                                                                String environment) {
        return runtimeBindingRequirements(tenantId, namespace, environment,
                VisualRuntimeBindingRequirements.DEFAULT_ITEM_LIMIT, 0, "", "", "", "", "", "", "", "", "", "", "");
    }

    VisualRuntimeBindingRequirements runtimeBindingRequirements(String tenantId,
                                                                String namespace,
                                                                String environment,
                                                                int limit,
                                                                int offset,
                                                                String targetKind,
                                                                String bindingKind,
                                                                String handoffLane,
                                                                String handoffKind,
                                                                String handoffTarget,
                                                                String sourceKind,
                                                                String loweringMode,
                                                        String readinessState,
                                                        String requirementKey) {
        return runtimeBindingRequirements(tenantId, namespace, environment, limit, offset, targetKind, "", "",
                bindingKind, handoffLane, handoffKind, handoffTarget, sourceKind, loweringMode, readinessState,
                requirementKey);
    }

    VisualRuntimeBindingHandoffBundle runtimeBindingHandoffBundle(String tenantId,
                                                                  String namespace,
                                                                  String environment,
                                                                  int limit,
                                                                  int offset,
                                                                  String targetKind,
                                                                  String bindingKind,
                                                                  String handoffLane,
                                                                  String handoffKind,
                                                                  String handoffTarget,
                                                                  String sourceKind,
                                                                  String loweringMode,
                                                          String readinessState,
                                                          String requirementKey) {
        return runtimeBindingHandoffBundle(tenantId, namespace, environment, limit, offset, targetKind, "", "",
                bindingKind, handoffLane, handoffKind, handoffTarget, sourceKind, loweringMode, readinessState,
                requirementKey);
    }

    private List<OperatorDefinition> operatorContractsForRuntimeBindingHandoff(VisualRuntimeBindingRequirements index) {
        Map<String, OperatorDefinition> operatorsByRef = new LinkedHashMap<>();
        for (VisualRuntimeBindingRequirements.RequirementItem item
                : index == null ? List.<VisualRuntimeBindingRequirements.RequirementItem>of() : index.items()) {
            if (item == null || item.operatorRef().isBlank() || operatorsByRef.containsKey(item.operatorRef())) {
                continue;
            }
            catalog.find(item.operatorRef())
                    .ifPresent(operator -> operatorsByRef.put(item.operatorRef(), operator));
        }
        return List.copyOf(operatorsByRef.values());
    }

    private static String implementationOperatorRef(VisualRuntimeBindingImplementationValidation.Request request) {
        if (request == null) {
            return "";
        }
        if (!request.operatorRef().isBlank()) {
            return request.operatorRef();
        }
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract = request.operatorContract();
        return contract == null ? "" : contract.operatorRef();
    }

    private static String implementationBindingId(VisualRuntimeBindingImplementationValidation.Request request) {
        if (request == null || request.implementation() == null) {
            return "";
        }
        return request.implementation().bindingId();
    }

    private static VisualRuntimeBindingImplementationValidation validationWithBlockingDiagnostic(
            VisualRuntimeBindingImplementationValidation validation,
            VisualDiagnostic diagnostic) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>(validation.diagnostics());
        diagnostics.add(diagnostic);
        return new VisualRuntimeBindingImplementationValidation(
                validation.schemaVersion(),
                validation.validatedAt(),
                false,
                false,
                "rejected",
                "error",
                diagnostic.message(),
                validation.operatorRef(),
                validation.operatorFingerprint(),
                validation.sourceHandoffBundleFingerprint(),
                validation.contractFingerprint(),
                validation.currentCatalogFingerprint(),
                validation.currentCatalogState(),
                validation.implementation(),
                diagnostics
        );
    }

    private static ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> transitionRequestError(
            VisualRuntimeBindingImplementationTransitionRequest request,
            boolean requireReplacement) {
        if (request == null) {
            return lifecycleFailure(
                    HttpStatus.BAD_REQUEST,
                    "rejected",
                    "visual.runtimeBindingImplementation.transitionRequestMissing",
                    "Runtime binding implementation lifecycle transition requires a request body.",
                    "/",
                    null,
                    null,
                    Map.of()
            );
        }
        if (!VisualRuntimeBindingImplementationTransitionRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            return lifecycleFailure(
                    HttpStatus.BAD_REQUEST,
                    "rejected",
                    "visual.runtimeBindingImplementation.transitionSchemaVersionUnsupported",
                    "Runtime binding implementation transition schemaVersion '%s' is not supported; expected '%s'."
                            .formatted(request.schemaVersion(),
                                    VisualRuntimeBindingImplementationTransitionRequest.SCHEMA_VERSION),
                    "/schemaVersion",
                    null,
                    null,
                    Map.of("actual", request.schemaVersion(),
                            "expected", VisualRuntimeBindingImplementationTransitionRequest.SCHEMA_VERSION)
            );
        }
        if (request.actor().isBlank()) {
            return lifecycleFailure(
                    HttpStatus.BAD_REQUEST,
                    "rejected",
                    "visual.runtimeBindingImplementation.actorMissing",
                    "Runtime binding implementation lifecycle transition requires actor.",
                    "/actor",
                    null,
                    null,
                    Map.of()
            );
        }
        if (request.reason().isBlank()) {
            return lifecycleFailure(
                    HttpStatus.BAD_REQUEST,
                    "rejected",
                    "visual.runtimeBindingImplementation.reasonMissing",
                    "Runtime binding implementation lifecycle transition requires reason.",
                    "/reason",
                    null,
                    null,
                    Map.of()
            );
        }
        if (requireReplacement && request.replacementBindingId().isBlank()) {
            return lifecycleFailure(
                    HttpStatus.BAD_REQUEST,
                    "rejected",
                    "visual.runtimeBindingImplementation.replacementBindingIdMissing",
                    "Runtime binding implementation supersede transition requires replacementBindingId.",
                    "/replacementBindingId",
                    null,
                    null,
                    Map.of()
            );
        }
        return null;
    }

    private static ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> bindableStateError(
            VisualRuntimeBindingImplementationBinding binding,
            VisualRuntimeBindingImplementationTransitionRequest request,
            VisualRuntimeBindingImplementationBinding relatedBinding) {
        if (binding.bound()) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.alreadyBound",
                    "Runtime binding implementation '%s' is already bound.".formatted(binding.bindingId()),
                    "/bindingId",
                    binding,
                    relatedBinding,
                    Map.of("bindingId", binding.bindingId(), "state", binding.state())
            );
        }
        if (binding.superseded()) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.alreadySuperseded",
                    "Runtime binding implementation '%s' has already been superseded.".formatted(binding.bindingId()),
                    "/bindingId",
                    binding,
                    relatedBinding,
                    Map.of("bindingId", binding.bindingId(), "state", binding.state())
            );
        }
        if (binding.requiresReview() && !request.ackReview()) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.reviewAcknowledgementMissing",
                    "Runtime binding implementation '%s' requires review before it can be bound."
                            .formatted(binding.bindingId()),
                    "/ackReview",
                    binding,
                    relatedBinding,
                    Map.of("bindingId", binding.bindingId(), "state", binding.state())
            );
        }
        if (!binding.readyToBind() && !binding.requiresReview()) {
            return lifecycleFailure(
                    HttpStatus.CONFLICT,
                    "conflict",
                    "visual.runtimeBindingImplementation.stateNotBindable",
                    "Runtime binding implementation '%s' is in state '%s' and cannot be bound."
                            .formatted(binding.bindingId(), binding.state()),
                    "/bindingId",
                    binding,
                    relatedBinding,
                    Map.of("bindingId", binding.bindingId(), "state", binding.state())
            );
        }
        return null;
    }

    private static ResponseEntity<VisualRuntimeBindingImplementationLifecycleResult> lifecycleFailure(
            HttpStatus status,
            String state,
            String code,
            String message,
            String target,
            VisualRuntimeBindingImplementationBinding binding,
            VisualRuntimeBindingImplementationBinding replacementBinding,
            Map<String, Object> metadata) {
        VisualDiagnostic diagnostic = metadata == null || metadata.isEmpty()
                ? VisualDiagnostic.error(code, message, target)
                : VisualDiagnostic.error(code, message, target, metadata);
        return ResponseEntity.status(status).body(VisualRuntimeBindingImplementationLifecycleResult.rejected(
                state,
                message,
                binding,
                replacementBinding,
                List.of(diagnostic)
        ));
    }

    private static VisualRuntimeBindingImplementationBinding.LifecycleEvent lifecycleEvent(
            String eventType,
            String fromState,
            String toState,
            VisualRuntimeBindingImplementationTransitionRequest request,
            String relatedBindingId,
            Instant occurredAt) {
        return new VisualRuntimeBindingImplementationBinding.LifecycleEvent(
                eventType,
                fromState,
                toState,
                request.actor(),
                request.changeSource(),
                request.reason(),
                request.changeSummary(),
                relatedBindingId,
                occurredAt
        );
    }

    private List<GraphDraftSummary> draftSummaries(String tenantId, String namespace, String environment) {
        return draftRepository.history().stream()
                .map(this::draftSummary)
                .filter(summary -> matchesDraftScope(summary, tenantId, namespace, environment))
                .toList();
    }

    private List<VisualGraphPublicationSummary> publicationSummaries(String tenantId,
                                                                     String namespace,
                                                                     String environment) {
        return publicationRepository.all().stream()
                .map(VisualGraphPublicationSummary::from)
                .filter(summary -> matchesPublicationScope(summary, tenantId, namespace, environment))
                .toList();
    }

    private GraphDraftSummary draftSummary(GraphDraftHistorySummary history) {
        GraphDraft draft = draftRepository.find(history.draftId())
                .orElseGet(() -> draftRepository.revisions(history.draftId()).stream()
                        .findFirst()
                        .orElse(null));
        if (draft == null) {
            return GraphDraftSummary.from(history, null, null, null);
        }
        VisualValidationResult validation = validator.validate(draft);
        GraphDraftDependencyReport dependencies = GraphDraftDependencyReport.from(draft, catalog);
        return GraphDraftSummary.from(history, draft, validation, dependencies);
    }

    private static boolean matchesDraftScope(GraphDraftSummary summary,
                                             String tenantId,
                                             String namespace,
                                             String environment) {
        return summary != null
                && matchesScope(summary.tenantId(), tenantId)
                && matchesScope(summary.namespace(), namespace)
                && matchesScope(summary.environment(), environment);
    }

    private static boolean matchesPublicationScope(VisualGraphPublicationSummary summary,
                                                   String tenantId,
                                                   String namespace,
                                                   String environment) {
        return summary != null
                && matchesScope(summary.tenantId(), tenantId)
                && matchesScope(summary.namespace(), namespace)
                && matchesScope(summary.environment(), environment);
    }

    private static boolean matchesScope(String actual, String expected) {
        return expected == null || expected.isBlank() || String.valueOf(actual).equals(expected);
    }
}
