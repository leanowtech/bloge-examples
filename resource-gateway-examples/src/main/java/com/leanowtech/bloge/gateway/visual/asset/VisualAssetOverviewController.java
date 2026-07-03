package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinitionChangeSummary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorExecutablePromotionProjection;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorRuntimeBindingProjection;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftHistorySummary;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualExecutableLoweringIntegrationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegration;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegrationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegrationValidation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableReadinessEvidenceRefreshResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableReadinessRecomputePreview;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableReadinessRecomputeResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationValidation;
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
    private final VisualRuntimeAdapterActivationRepository adapterActivationRepository;
    private final VisualExecutableLoweringIntegrationRepository executableLoweringIntegrationRepository;
    private final OperatorLibraryRegistry operatorLibraryRegistry;

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
                new InMemoryVisualRuntimeBindingImplementationRepository(),
                new InMemoryVisualRuntimeAdapterActivationRepository(),
                new InMemoryVisualExecutableLoweringIntegrationRepository(),
                OperatorLibraryRegistry.empty());
    }

    /**
     * @param draftRepository draft repository
     * @param validator draft validator
     * @param catalog visual operator catalog
     * @param publicationRepository publication repository
     * @param implementationRepository runtime implementation binding repository
     * @param adapterActivationRepository runtime adapter activation repository
     * @param executableLoweringIntegrationRepository executable lowering integration repository
     */
    @Autowired
    public VisualAssetOverviewController(GraphDraftRepository draftRepository,
                                         GraphDraftValidator validator,
                                         VisualOperatorCatalog catalog,
                                         VisualGraphPublicationRepository publicationRepository,
                                         VisualRuntimeBindingImplementationRepository implementationRepository,
                                         VisualRuntimeAdapterActivationRepository adapterActivationRepository,
                                         VisualExecutableLoweringIntegrationRepository
                                                 executableLoweringIntegrationRepository,
                                         OperatorLibraryRegistry operatorLibraryRegistry) {
        this.draftRepository = draftRepository;
        this.validator = validator;
        this.catalog = catalog;
        this.publicationRepository = publicationRepository;
        this.implementationRepository = implementationRepository == null
                ? new InMemoryVisualRuntimeBindingImplementationRepository()
                : implementationRepository;
        this.adapterActivationRepository = adapterActivationRepository == null
                ? new InMemoryVisualRuntimeAdapterActivationRepository()
                : adapterActivationRepository;
        this.executableLoweringIntegrationRepository = executableLoweringIntegrationRepository == null
                ? new InMemoryVisualExecutableLoweringIntegrationRepository()
                : executableLoweringIntegrationRepository;
        this.operatorLibraryRegistry = operatorLibraryRegistry == null
                ? OperatorLibraryRegistry.empty()
                : operatorLibraryRegistry;
    }

    public VisualAssetOverviewController(GraphDraftRepository draftRepository,
                                         GraphDraftValidator validator,
                                         VisualOperatorCatalog catalog,
                                         VisualGraphPublicationRepository publicationRepository,
                                         VisualRuntimeBindingImplementationRepository implementationRepository,
                                         VisualRuntimeAdapterActivationRepository adapterActivationRepository,
                                         VisualExecutableLoweringIntegrationRepository
                                                 executableLoweringIntegrationRepository) {
        this(draftRepository, validator, catalog, publicationRepository, implementationRepository,
                adapterActivationRepository, executableLoweringIntegrationRepository, OperatorLibraryRegistry.empty());
    }

    public VisualAssetOverviewController(GraphDraftRepository draftRepository,
                                         GraphDraftValidator validator,
                                         VisualOperatorCatalog catalog,
                                         VisualGraphPublicationRepository publicationRepository,
                                         VisualRuntimeBindingImplementationRepository implementationRepository) {
        this(draftRepository, validator, catalog, publicationRepository, implementationRepository,
                new InMemoryVisualRuntimeAdapterActivationRepository(),
                new InMemoryVisualExecutableLoweringIntegrationRepository(),
                OperatorLibraryRegistry.empty());
    }

    public VisualAssetOverviewController(GraphDraftRepository draftRepository,
                                         GraphDraftValidator validator,
                                         VisualOperatorCatalog catalog,
                                         VisualGraphPublicationRepository publicationRepository,
                                         VisualRuntimeBindingImplementationRepository implementationRepository,
                                         VisualRuntimeAdapterActivationRepository adapterActivationRepository) {
        this(draftRepository, validator, catalog, publicationRepository, implementationRepository,
                adapterActivationRepository, new InMemoryVisualExecutableLoweringIntegrationRepository(),
                OperatorLibraryRegistry.empty());
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

    /**
     * Validates a runtime adapter activation assertion against the active binding and current catalog.
     *
     * @param request submitted adapter activation request
     * @return validation result with binding, catalog, and runtime assertion diagnostics
     */
    @PostMapping("/runtime-binding-requirements/adapter-activations/validate")
    public ResponseEntity<VisualRuntimeAdapterActivationValidation> validateRuntimeAdapterActivation(
            @RequestBody(required = false) VisualRuntimeAdapterActivationValidation.Request request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(VisualRuntimeAdapterActivationValidation.missingRequest());
        }
        VisualRuntimeBindingImplementationBinding binding = request.bindingId().isBlank()
                ? null
                : implementationRepository.find(request.bindingId()).orElse(null);
        OperatorDefinition currentOperator = binding == null
                ? null
                : catalog.find(binding.operatorRef()).orElse(null);
        VisualRuntimeAdapterActivationValidation result =
                VisualRuntimeAdapterActivationValidation.from(request, binding, currentOperator);
        boolean unsupportedVersion = result.diagnostics().stream()
                .anyMatch(diagnostic ->
                        "visual.runtimeAdapterActivation.schemaVersionUnsupported".equals(diagnostic.code()));
        return unsupportedVersion
                ? ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result)
                : ResponseEntity.ok(result);
    }

    /**
     * Lists submitted runtime adapter activations.
     *
     * @param bindingId optional binding id filter
     * @param operatorRef optional operator reference filter
     * @param state optional activation state filter
     * @return matching runtime adapter activations
     */
    @GetMapping("/runtime-binding-requirements/adapter-activations")
    public List<VisualRuntimeAdapterActivation> runtimeAdapterActivations(
            @RequestParam(defaultValue = "") String bindingId,
            @RequestParam(defaultValue = "") String operatorRef,
            @RequestParam(defaultValue = "") String state) {
        String normalizedBindingId = bindingId == null ? "" : bindingId.trim();
        String normalizedOperatorRef = operatorRef == null ? "" : operatorRef.trim();
        String normalizedState = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        return adapterActivationRepository.all().stream()
                .filter(activation -> normalizedBindingId.isBlank()
                        || activation.bindingId().equals(normalizedBindingId))
                .filter(activation -> normalizedOperatorRef.isBlank()
                        || activation.operatorRef().equals(normalizedOperatorRef))
                .filter(activation -> normalizedState.isBlank()
                        || activation.state().equals(normalizedState))
                .toList();
    }

    /**
     * Stores a healthy runtime adapter activation fact for a bound implementation.
     *
     * <p>Accepted activations are auditable runtime-plane facts. They are surfaced
     * through the operator catalog projection, but they still do not mutate the
     * imported operator definition or make design-only operators executable.</p>
     *
     * @param request submitted adapter activation request
     * @return stored activation when accepted, otherwise validation diagnostics
     */
    @PostMapping("/runtime-binding-requirements/adapter-activations")
    public ResponseEntity<Object> submitRuntimeAdapterActivation(
            @RequestBody(required = false) VisualRuntimeAdapterActivationValidation.Request request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(VisualRuntimeAdapterActivationValidation.missingRequest());
        }
        VisualRuntimeBindingImplementationBinding binding = request.bindingId().isBlank()
                ? null
                : implementationRepository.find(request.bindingId()).orElse(null);
        OperatorDefinition currentOperator = binding == null
                ? null
                : catalog.find(binding.operatorRef()).orElse(null);
        VisualRuntimeAdapterActivationValidation validation =
                VisualRuntimeAdapterActivationValidation.from(request, binding, currentOperator);
        if (!validation.valid()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(validation);
        }
        if (!request.activationId().isBlank() && adapterActivationRepository.find(request.activationId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(adapterActivationValidationWithBlockingDiagnostic(
                    validation,
                    VisualDiagnostic.error(
                            "visual.runtimeAdapterActivation.activationIdDuplicate",
                            "Runtime adapter activationId '%s' already exists.".formatted(request.activationId()),
                            "/activationId",
                            Map.of("activationId", request.activationId()))
            ));
        }
        if (adapterActivationRepository.findActiveByBindingId(validation.bindingId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(adapterActivationValidationWithBlockingDiagnostic(
                    validation,
                    VisualDiagnostic.error(
                            "visual.runtimeAdapterActivation.activeActivationExists",
                            "Runtime adapter activation already exists for binding '%s'."
                                    .formatted(validation.bindingId()),
                            "/bindingId",
                            Map.of("bindingId", validation.bindingId()))
            ));
        }
        VisualRuntimeAdapterActivation stored;
        try {
            stored = adapterActivationRepository.create(VisualRuntimeAdapterActivation.from(request, validation));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(adapterActivationValidationWithBlockingDiagnostic(
                    validation,
                    VisualDiagnostic.error(
                            "visual.runtimeAdapterActivation.activationConflict",
                            e.getMessage(),
                            "/activationId",
                            Map.of("activationId", request.activationId(), "bindingId", validation.bindingId()))
            ));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }

    /**
     * Validates a BLOGE executable lowering integration assertion against an active adapter activation.
     *
     * @param request submitted executable lowering integration request
     * @return validation result with activation, binding, catalog, and executor diagnostics
     */
    @PostMapping("/runtime-binding-requirements/executable-lowering-integrations/validate")
    public ResponseEntity<VisualExecutableLoweringIntegrationValidation> validateExecutableLoweringIntegration(
            @RequestBody(required = false) VisualExecutableLoweringIntegrationValidation.Request request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(VisualExecutableLoweringIntegrationValidation.missingRequest());
        }
        VisualRuntimeAdapterActivation activation = request.activationId().isBlank()
                ? null
                : adapterActivationRepository.find(request.activationId()).orElse(null);
        VisualRuntimeBindingImplementationBinding binding = request.bindingId().isBlank()
                ? null
                : implementationRepository.find(request.bindingId()).orElse(null);
        OperatorDefinition currentOperator = request.operatorRef().isBlank()
                ? null
                : catalog.find(request.operatorRef()).orElse(null);
        VisualExecutableLoweringIntegrationValidation result =
                VisualExecutableLoweringIntegrationValidation.from(request, activation, binding, currentOperator);
        boolean unsupportedVersion = result.diagnostics().stream()
                .anyMatch(diagnostic ->
                        "visual.executableLoweringIntegration.schemaVersionUnsupported"
                                .equals(diagnostic.code()));
        return unsupportedVersion
                ? ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result)
                : ResponseEntity.ok(result);
    }

    /**
     * Lists submitted executable lowering integration facts.
     *
     * @param activationId optional adapter activation id filter
     * @param operatorRef optional operator reference filter
     * @param state optional integration state filter
     * @return matching executable lowering integrations
     */
    @GetMapping("/runtime-binding-requirements/executable-lowering-integrations")
    public List<VisualExecutableLoweringIntegration> executableLoweringIntegrations(
            @RequestParam(defaultValue = "") String activationId,
            @RequestParam(defaultValue = "") String operatorRef,
            @RequestParam(defaultValue = "") String state) {
        String normalizedActivationId = activationId == null ? "" : activationId.trim();
        String normalizedOperatorRef = operatorRef == null ? "" : operatorRef.trim();
        String normalizedState = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        return executableLoweringIntegrationRepository.all().stream()
                .filter(integration -> normalizedActivationId.isBlank()
                        || integration.activationId().equals(normalizedActivationId))
                .filter(integration -> normalizedOperatorRef.isBlank()
                        || integration.operatorRef().equals(normalizedOperatorRef))
                .filter(integration -> normalizedState.isBlank()
                        || integration.state().equals(normalizedState))
                .toList();
    }

    /**
     * Stores an executable lowering integration fact for an active adapter activation.
     *
     * <p>Accepted integrations are auditable executor-plane facts. They can move
     * the catalog promotion projection past executor-integration-required, but
     * they still do not mutate operator definitions or close executable readiness.</p>
     *
     * @param request submitted executable lowering integration request
     * @return stored integration when accepted, otherwise validation diagnostics
     */
    @PostMapping("/runtime-binding-requirements/executable-lowering-integrations")
    public ResponseEntity<Object> submitExecutableLoweringIntegration(
            @RequestBody(required = false) VisualExecutableLoweringIntegrationValidation.Request request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(VisualExecutableLoweringIntegrationValidation.missingRequest());
        }
        VisualRuntimeAdapterActivation activation = request.activationId().isBlank()
                ? null
                : adapterActivationRepository.find(request.activationId()).orElse(null);
        VisualRuntimeBindingImplementationBinding binding = request.bindingId().isBlank()
                ? null
                : implementationRepository.find(request.bindingId()).orElse(null);
        OperatorDefinition currentOperator = request.operatorRef().isBlank()
                ? null
                : catalog.find(request.operatorRef()).orElse(null);
        VisualExecutableLoweringIntegrationValidation validation =
                VisualExecutableLoweringIntegrationValidation.from(request, activation, binding, currentOperator);
        if (!validation.valid()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(validation);
        }
        if (!request.integrationId().isBlank()
                && executableLoweringIntegrationRepository.find(request.integrationId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(executableLoweringIntegrationValidationWithBlockingDiagnostic(
                            validation,
                            VisualDiagnostic.error(
                                    "visual.executableLoweringIntegration.integrationIdDuplicate",
                                    "Executable lowering integrationId '%s' already exists."
                                            .formatted(request.integrationId()),
                                    "/integrationId",
                                    Map.of("integrationId", request.integrationId()))
                    ));
        }
        if (executableLoweringIntegrationRepository.findActiveByActivationId(validation.activationId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(executableLoweringIntegrationValidationWithBlockingDiagnostic(
                            validation,
                            VisualDiagnostic.error(
                                    "visual.executableLoweringIntegration.activeIntegrationExists",
                                    "Executable lowering integration already exists for activation '%s'."
                                            .formatted(validation.activationId()),
                                    "/activationId",
                                    Map.of("activationId", validation.activationId()))
                    ));
        }
        VisualExecutableLoweringIntegration stored;
        try {
            stored = executableLoweringIntegrationRepository.create(
                    VisualExecutableLoweringIntegration.from(request, validation));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(executableLoweringIntegrationValidationWithBlockingDiagnostic(
                            validation,
                            VisualDiagnostic.error(
                                    "visual.executableLoweringIntegration.integrationConflict",
                                    e.getMessage(),
                                    "/integrationId",
                                    Map.of(
                                            "integrationId", request.integrationId(),
                                            "activationId", validation.activationId()))
                    ));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }

    /**
     * Previews the trusted operator surface that a later readiness recompute mutation would write.
     *
     * <p>This endpoint is intentionally read-only. It consumes the same catalog projection chain as the
     * palette response and proves whether the active implementation binding, adapter activation, and
     * executable lowering integration are sufficient to build an executable candidate operator.</p>
     *
     * @param operatorRef operator reference to preview
     * @return readiness recompute preview
     */
    @GetMapping("/runtime-binding-requirements/executable-readiness-recomputations/preview")
    public ResponseEntity<VisualExecutableReadinessRecomputePreview> previewExecutableReadinessRecompute(
            @RequestParam(defaultValue = "") String operatorRef) {
        String normalizedOperatorRef = operatorRef == null ? "" : operatorRef.trim();
        if (normalizedOperatorRef.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(VisualExecutableReadinessRecomputePreview.missingOperatorRef());
        }
        OperatorDefinition currentOperator = catalog.find(normalizedOperatorRef).orElse(null);
        if (currentOperator == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(VisualExecutableReadinessRecomputePreview.missingOperator(normalizedOperatorRef));
        }
        OperatorCatalogQuery query = OperatorCatalogQuery.all();
        List<OperatorRuntimeBindingProjection> runtimeBindingProjections =
                catalog.runtimeBindingProjections(query, List.of(currentOperator));
        List<OperatorExecutablePromotionProjection> promotionProjections =
                catalog.executablePromotionProjections(query, runtimeBindingProjections);
        OperatorExecutablePromotionProjection promotionProjection = promotionProjections.stream()
                .filter(projection -> projection.operatorRef().equals(normalizedOperatorRef))
                .findFirst()
                .orElse(null);
        return ResponseEntity.ok(VisualExecutableReadinessRecomputePreview.from(
                currentOperator,
                promotionProjection
        ));
    }

    /**
     * Applies a recomputable preview as a governed operator-library revision.
     *
     * <p>The caller cannot submit a candidate operator body. The server recomputes the preview from
     * current control-plane evidence and writes only that candidate into the owning user library.</p>
     *
     * @param operatorRef operator reference to apply
     * @param ackWarnings true when the caller reviewed the runtime-binding surface change
     * @param actor user or system actor producing the operator-library revision
     * @param changeSource UI or integration source producing the operator-library revision
     * @param changeSummary human-readable revision summary
     * @param reason reason for the governed mutation
     * @return apply result
     */
    @PostMapping("/runtime-binding-requirements/executable-readiness-recomputations/apply")
    public ResponseEntity<VisualExecutableReadinessRecomputeResult> applyExecutableReadinessRecompute(
            @RequestParam(defaultValue = "") String operatorRef,
            @RequestParam(defaultValue = "false") boolean ackWarnings,
            @RequestParam(defaultValue = "") String actor,
            @RequestParam(defaultValue = "") String changeSource,
            @RequestParam(defaultValue = "") String changeSummary,
            @RequestParam(defaultValue = "") String reason) {
        ResponseEntity<VisualExecutableReadinessRecomputePreview> previewResponse =
                previewExecutableReadinessRecompute(operatorRef);
        VisualExecutableReadinessRecomputePreview preview = previewResponse.getBody();
        if (!previewResponse.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(previewResponse.getStatusCode())
                    .body(VisualExecutableReadinessRecomputeResult.fromPreview(preview));
        }
        if (preview == null || !preview.recomputable()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(VisualExecutableReadinessRecomputeResult.fromPreview(preview));
        }
        if (!ackWarnings) {
            VisualDiagnostic diagnostic = executableReadinessApplyAckRequired(preview);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(VisualExecutableReadinessRecomputeResult.ackRequired(preview, diagnostic));
        }
        List<VisualDiagnostic> governanceDiagnostics = executableReadinessApplyGovernanceDiagnostics(
                actor,
                reason);
        if (!governanceDiagnostics.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(VisualExecutableReadinessRecomputeResult.rejected(preview, governanceDiagnostics));
        }
        OperatorLibrary currentLibrary = operatorLibraryRegistry.find(preview.operatorLibraryId()).orElse(null);
        if (currentLibrary == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(VisualExecutableReadinessRecomputeResult.rejected(
                            preview,
                            List.of(VisualDiagnostic.error(
                                    "visual.executableReadinessRecompute.operatorLibraryMissing",
                                    "Operator library '%s' is not available for executable readiness recompute apply."
                                            .formatted(preview.operatorLibraryId()),
                                    "/operatorLibraryId",
                                    Map.of("operatorLibraryId", preview.operatorLibraryId())))));
        }
        ReplacementLibrary replacement = replacementLibraryForExecutableReadinessApply(currentLibrary, preview);
        if (!replacement.valid()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(VisualExecutableReadinessRecomputeResult.rejected(preview, replacement.diagnostics()));
        }
        OperatorLibraryRevision.RevisionMetadata metadata = OperatorLibraryRevision.RevisionMetadata.of(
                actor,
                defaultIfBlank(changeSource, "visual-asset-overview"),
                defaultIfBlank(changeSummary,
                        "Apply executable readiness recompute for '%s'.".formatted(preview.operatorRef())),
                reason
        );
        OperatorLibrary stored = operatorLibraryRegistry.upsert(replacement.library(), metadata);
        OperatorLibraryRevision latestRevision = operatorLibraryRegistry.revisions(stored.libraryId()).stream()
                .findFirst()
                .orElse(null);
        return ResponseEntity.ok(VisualExecutableReadinessRecomputeResult.applied(
                stored,
                latestRevision,
                preview
        ));
    }

    /**
     * Rebinds runtime evidence after a governed executable readiness apply.
     *
     * <p>The operation creates a fresh binding/activation/integration evidence chain against the current
     * executable operator fingerprint. Existing records are retained for audit; the old binding is superseded.</p>
     *
     * @param operatorRef operator whose evidence should be refreshed
     * @param ackWarnings true when the caller reviewed the fingerprint/surface refresh
     * @param actor actor approving the refresh
     * @param changeSource source workflow
     * @param changeSummary short audit summary
     * @param reason required audit reason
     * @param refreshedBindingId optional id for the refreshed binding
     * @param refreshedActivationId optional id for the refreshed activation
     * @param refreshedIntegrationId optional id for the refreshed lowering integration
     * @return refresh result
     */
    @PostMapping("/runtime-binding-requirements/executable-readiness-recomputations/evidence-refresh")
    public ResponseEntity<VisualExecutableReadinessEvidenceRefreshResult> refreshExecutableReadinessEvidence(
            @RequestParam(defaultValue = "") String operatorRef,
            @RequestParam(defaultValue = "false") boolean ackWarnings,
            @RequestParam(defaultValue = "") String actor,
            @RequestParam(defaultValue = "") String changeSource,
            @RequestParam(defaultValue = "") String changeSummary,
            @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "") String refreshedBindingId,
            @RequestParam(defaultValue = "") String refreshedActivationId,
            @RequestParam(defaultValue = "") String refreshedIntegrationId) {
        String normalizedOperatorRef = operatorRef == null ? "" : operatorRef.trim();
        if (normalizedOperatorRef.isBlank()) {
            return evidenceRefreshFailure(
                    HttpStatus.BAD_REQUEST,
                    "",
                    "",
                    "",
                    "rejected",
                    "error",
                    "Executable readiness evidence refresh requires operatorRef.",
                    null,
                    null,
                    null,
                    List.of(VisualDiagnostic.error(
                            "visual.executableReadinessEvidenceRefresh.operatorRefMissing",
                            "Executable readiness evidence refresh requires operatorRef.",
                            "/operatorRef")));
        }
        OperatorDefinition currentOperator = catalog.find(normalizedOperatorRef).orElse(null);
        if (currentOperator == null) {
            return evidenceRefreshFailure(
                    HttpStatus.NOT_FOUND,
                    normalizedOperatorRef,
                    "",
                    "",
                    "missing",
                    "error",
                    "Operator '%s' is not visible in the current catalog.".formatted(normalizedOperatorRef),
                    null,
                    null,
                    null,
                    List.of(VisualDiagnostic.error(
                            "visual.executableReadinessEvidenceRefresh.operatorMissing",
                            "Operator '%s' is not visible in the current catalog."
                                    .formatted(normalizedOperatorRef),
                            "/operatorRef",
                            Map.of("operatorRef", normalizedOperatorRef))));
        }
        VisualRuntimeBindingImplementationBinding sourceBinding =
                implementationRepository.findActiveBound(normalizedOperatorRef).orElse(null);
        if (sourceBinding == null) {
            return evidenceRefreshFailure(
                    HttpStatus.NOT_FOUND,
                    normalizedOperatorRef,
                    "",
                    currentOperator.fingerprint(),
                    "missing",
                    "error",
                    "Operator '%s' has no active runtime binding evidence to refresh."
                            .formatted(normalizedOperatorRef),
                    null,
                    null,
                    null,
                    List.of(VisualDiagnostic.error(
                            "visual.executableReadinessEvidenceRefresh.bindingMissing",
                            "Operator '%s' has no active runtime binding evidence to refresh."
                                    .formatted(normalizedOperatorRef),
                            "/operatorRef",
                            Map.of("operatorRef", normalizedOperatorRef))));
        }
        VisualRuntimeAdapterActivation sourceActivation =
                adapterActivationRepository.findActiveByBindingId(sourceBinding.bindingId()).orElse(null);
        VisualExecutableLoweringIntegration sourceIntegration = sourceActivation == null
                ? null
                : executableLoweringIntegrationRepository.findActiveByActivationId(
                        sourceActivation.activationId()).orElse(null);
        if (!ackWarnings) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    sourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "ack-required",
                    "warning",
                    "Executable readiness evidence refresh for '%s' requires warning acknowledgement."
                            .formatted(normalizedOperatorRef),
                    sourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    List.of(executableReadinessEvidenceRefreshAckRequired(
                            sourceBinding,
                            sourceActivation,
                            sourceIntegration,
                            currentOperator)));
        }
        List<VisualDiagnostic> governanceDiagnostics = executableReadinessEvidenceRefreshGovernanceDiagnostics(
                actor,
                reason);
        if (!governanceDiagnostics.isEmpty()) {
            return evidenceRefreshFailure(
                    HttpStatus.BAD_REQUEST,
                    normalizedOperatorRef,
                    sourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "rejected",
                    "error",
                    "Executable readiness evidence refresh for '%s' is missing governance evidence."
                            .formatted(normalizedOperatorRef),
                    sourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    governanceDiagnostics);
        }
        if (!operatorExecutable(currentOperator)) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    sourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "blocked",
                    "error",
                    "Operator '%s' is not runtime-executable; apply readiness recompute before refreshing evidence."
                            .formatted(normalizedOperatorRef),
                    sourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    List.of(VisualDiagnostic.error(
                            "visual.executableReadinessEvidenceRefresh.operatorNotExecutable",
                            "Operator '%s' is not runtime-executable; apply readiness recompute before refreshing evidence."
                                    .formatted(normalizedOperatorRef),
                            "/operatorRef",
                            Map.of("operatorRef", normalizedOperatorRef,
                                    "runtimeReadinessState", runtimeReadinessState(currentOperator)))));
        }
        if (sourceBinding.operatorFingerprint().equals(currentOperator.fingerprint())) {
            return ResponseEntity.ok(VisualExecutableReadinessEvidenceRefreshResult.current(
                    normalizedOperatorRef,
                    currentOperator.fingerprint(),
                    sourceBinding));
        }
        List<VisualDiagnostic> duplicateDiagnostics = executableReadinessEvidenceRefreshDuplicateDiagnostics(
                refreshedBindingId,
                refreshedActivationId,
                refreshedIntegrationId);
        if (!duplicateDiagnostics.isEmpty()) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    sourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "conflict",
                    "error",
                    "Executable readiness evidence refresh for '%s' conflicts with existing ids."
                            .formatted(normalizedOperatorRef),
                    sourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    duplicateDiagnostics);
        }
        List<VisualDiagnostic> chainDiagnostics = executableReadinessEvidenceRefreshChainDiagnostics(
                sourceBinding,
                sourceActivation,
                sourceIntegration);
        if (!chainDiagnostics.isEmpty()) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    sourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "blocked",
                    "error",
                    "Executable readiness evidence refresh for '%s' is blocked by stale source evidence."
                            .formatted(normalizedOperatorRef),
                    sourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    chainDiagnostics);
        }
        OperatorDefinitionChangeSummary.ChangeReport changeReport =
                OperatorDefinitionChangeSummary.analyze(
                        operatorDefinitionFromContract(sourceBinding.operatorContract()),
                        currentOperator);
        List<VisualDiagnostic> changeDiagnostics = executableReadinessEvidenceRefreshChangeDiagnostics(
                sourceBinding,
                currentOperator,
                changeReport);
        if (!changeDiagnostics.isEmpty()) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    sourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "blocked",
                    "error",
                    "Executable readiness evidence refresh for '%s' is blocked by unsupported contract changes."
                            .formatted(normalizedOperatorRef),
                    sourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    changeDiagnostics);
        }

        String normalizedChangeSource = defaultIfBlank(changeSource, "visual-asset-overview");
        String normalizedChangeSummary = defaultIfBlank(changeSummary,
                "Refresh executable readiness evidence for '%s'.".formatted(normalizedOperatorRef));
        VisualRuntimeBindingImplementationTransitionRequest transition =
                new VisualRuntimeBindingImplementationTransitionRequest(
                        VisualRuntimeBindingImplementationTransitionRequest.SCHEMA_VERSION,
                        actor,
                        reason,
                        normalizedChangeSource,
                        normalizedChangeSummary,
                        true,
                        "");
        VisualRuntimeBindingImplementationValidation.Request bindingRefreshRequest =
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        currentOperator.operatorRef(),
                        currentOperator.fingerprint(),
                        sourceBinding.sourceHandoffBundleFingerprint(),
                        sourceBinding.sourceRequirementKeys(),
                        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot.from(currentOperator),
                        refreshedImplementation(sourceBinding.implementation(), refreshedBindingId)
                );
        VisualRuntimeBindingImplementationValidation bindingValidation =
                VisualRuntimeBindingImplementationValidation.from(bindingRefreshRequest, currentOperator);
        if (!bindingValidation.valid()) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    sourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "blocked",
                    "error",
                    "Executable readiness evidence refresh for '%s' could not create a current binding."
                            .formatted(normalizedOperatorRef),
                    sourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    bindingValidation.diagnostics());
        }
        VisualRuntimeBindingImplementationBinding refreshedProposal;
        try {
            refreshedProposal = implementationRepository.create(
                    VisualRuntimeBindingImplementationBinding.from(bindingRefreshRequest, bindingValidation));
        } catch (IllegalArgumentException e) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    sourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "conflict",
                    "error",
                    e.getMessage(),
                    sourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    List.of(VisualDiagnostic.error(
                            "visual.executableReadinessEvidenceRefresh.bindingConflict",
                            e.getMessage(),
                            "/refreshedBindingId",
                            Map.of("refreshedBindingId", refreshedBindingId))));
        }
        Instant now = Instant.now();
        VisualRuntimeBindingImplementationBinding refreshedBinding = implementationRepository.update(
                refreshedProposal.withLifecycleTransition(
                        VisualRuntimeBindingImplementationBinding.STATE_BOUND,
                        "success",
                        sourceBinding.bindingId(),
                        null,
                        lifecycleEvent(
                                "bound",
                                refreshedProposal.state(),
                                VisualRuntimeBindingImplementationBinding.STATE_BOUND,
                                transition,
                                sourceBinding.bindingId(),
                                now),
                        now));
        VisualRuntimeBindingImplementationBinding supersededSourceBinding = implementationRepository.update(
                sourceBinding.withLifecycleTransition(
                        VisualRuntimeBindingImplementationBinding.STATE_SUPERSEDED,
                        "info",
                        null,
                        refreshedBinding.bindingId(),
                        lifecycleEvent(
                                "superseded",
                                sourceBinding.state(),
                                VisualRuntimeBindingImplementationBinding.STATE_SUPERSEDED,
                                transition,
                                refreshedBinding.bindingId(),
                                now),
                        now));

        VisualRuntimeAdapterActivationValidation.Request activationRefreshRequest =
                refreshedActivationRequest(
                        sourceActivation,
                        refreshedBinding,
                        refreshedActivationId,
                        actor,
                        normalizedChangeSource,
                        reason);
        VisualRuntimeAdapterActivationValidation activationValidation =
                VisualRuntimeAdapterActivationValidation.from(
                        activationRefreshRequest,
                        refreshedBinding,
                        currentOperator);
        if (!activationValidation.valid()) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    supersededSourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "blocked",
                    "error",
                    "Executable readiness evidence refresh for '%s' could not create a current activation."
                            .formatted(normalizedOperatorRef),
                    supersededSourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    activationValidation.diagnostics());
        }
        VisualRuntimeAdapterActivation refreshedActivation = adapterActivationRepository.create(
                VisualRuntimeAdapterActivation.from(activationRefreshRequest, activationValidation));

        VisualExecutableLoweringIntegrationValidation.Request integrationRefreshRequest =
                refreshedIntegrationRequest(
                        sourceIntegration,
                        refreshedActivation,
                        refreshedIntegrationId,
                        actor,
                        normalizedChangeSource,
                        reason);
        VisualExecutableLoweringIntegrationValidation integrationValidation =
                VisualExecutableLoweringIntegrationValidation.from(
                        integrationRefreshRequest,
                        refreshedActivation,
                        refreshedBinding,
                        currentOperator);
        if (!integrationValidation.valid()) {
            return evidenceRefreshFailure(
                    HttpStatus.CONFLICT,
                    normalizedOperatorRef,
                    supersededSourceBinding.operatorFingerprint(),
                    currentOperator.fingerprint(),
                    "blocked",
                    "error",
                    "Executable readiness evidence refresh for '%s' could not create a current lowering integration."
                            .formatted(normalizedOperatorRef),
                    supersededSourceBinding,
                    sourceActivation,
                    sourceIntegration,
                    integrationValidation.diagnostics());
        }
        VisualExecutableLoweringIntegration refreshedIntegration = executableLoweringIntegrationRepository.create(
                VisualExecutableLoweringIntegration.from(integrationRefreshRequest, integrationValidation));

        return ResponseEntity.ok(VisualExecutableReadinessEvidenceRefreshResult.refreshed(
                normalizedOperatorRef,
                sourceBinding.operatorFingerprint(),
                currentOperator.fingerprint(),
                changeReport.risk(),
                changeReport.categories(),
                changeReport.summary(),
                supersededSourceBinding,
                refreshedBinding,
                sourceActivation,
                refreshedActivation,
                sourceIntegration,
                refreshedIntegration));
    }

    private ResponseEntity<VisualExecutableReadinessEvidenceRefreshResult> evidenceRefreshFailure(
            HttpStatus status,
            String operatorRef,
            String previousOperatorFingerprint,
            String currentOperatorFingerprint,
            String state,
            String level,
            String message,
            VisualRuntimeBindingImplementationBinding sourceBinding,
            VisualRuntimeAdapterActivation sourceActivation,
            VisualExecutableLoweringIntegration sourceIntegration,
            List<VisualDiagnostic> diagnostics) {
        return ResponseEntity.status(status).body(VisualExecutableReadinessEvidenceRefreshResult.rejected(
                operatorRef,
                previousOperatorFingerprint,
                currentOperatorFingerprint,
                state,
                level,
                message,
                sourceBinding,
                sourceActivation,
                sourceIntegration,
                diagnostics));
    }

    private static boolean operatorExecutable(OperatorDefinition operator) {
        return operator != null && operator.runtimeReadiness() != null && operator.runtimeReadiness().executable();
    }

    private static String runtimeReadinessState(OperatorDefinition operator) {
        return operator == null || operator.runtimeReadiness() == null ? "" : operator.runtimeReadiness().state();
    }

    private static VisualDiagnostic executableReadinessEvidenceRefreshAckRequired(
            VisualRuntimeBindingImplementationBinding sourceBinding,
            VisualRuntimeAdapterActivation sourceActivation,
            VisualExecutableLoweringIntegration sourceIntegration,
            OperatorDefinition currentOperator) {
        return VisualDiagnostic.warning(
                "visual.executableReadinessEvidenceRefresh.ackWarningsRequired",
                "Executable readiness evidence refresh for '%s' will supersede runtime evidence from fingerprint '%s' to '%s'; review the current executable operator and retry with ackWarnings=true."
                        .formatted(currentOperator.operatorRef(), sourceBinding.operatorFingerprint(),
                                currentOperator.fingerprint()),
                "/ackWarnings",
                Map.of(
                        "operatorRef", currentOperator.operatorRef(),
                        "sourceBindingId", sourceBinding.bindingId(),
                        "sourceActivationId", sourceActivation == null ? "" : sourceActivation.activationId(),
                        "sourceIntegrationId", sourceIntegration == null ? "" : sourceIntegration.integrationId(),
                        "previousOperatorFingerprint", sourceBinding.operatorFingerprint(),
                        "currentOperatorFingerprint", currentOperator.fingerprint()
                ));
    }

    private static List<VisualDiagnostic> executableReadinessEvidenceRefreshGovernanceDiagnostics(
            String actor,
            String reason) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> metadata = Map.of("requiredFor", List.of("ackWarnings"));
        if (actor == null || actor.isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.governanceEvidenceMissing",
                    "Executable readiness evidence refresh requires actor when using ackWarnings=true.",
                    "/actor",
                    metadata));
        }
        if (reason == null || reason.isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.governanceEvidenceMissing",
                    "Executable readiness evidence refresh requires reason when using ackWarnings=true.",
                    "/reason",
                    metadata));
        }
        return diagnostics;
    }

    private List<VisualDiagnostic> executableReadinessEvidenceRefreshDuplicateDiagnostics(
            String refreshedBindingId,
            String refreshedActivationId,
            String refreshedIntegrationId) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        String bindingId = refreshedBindingId == null ? "" : refreshedBindingId.trim();
        if (!bindingId.isBlank() && implementationRepository.find(bindingId).isPresent()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.bindingIdDuplicate",
                    "Refreshed runtime binding id '%s' already exists.".formatted(bindingId),
                    "/refreshedBindingId",
                    Map.of("refreshedBindingId", bindingId)));
        }
        String activationId = refreshedActivationId == null ? "" : refreshedActivationId.trim();
        if (!activationId.isBlank() && adapterActivationRepository.find(activationId).isPresent()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.activationIdDuplicate",
                    "Refreshed adapter activation id '%s' already exists.".formatted(activationId),
                    "/refreshedActivationId",
                    Map.of("refreshedActivationId", activationId)));
        }
        String integrationId = refreshedIntegrationId == null ? "" : refreshedIntegrationId.trim();
        if (!integrationId.isBlank() && executableLoweringIntegrationRepository.find(integrationId).isPresent()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.integrationIdDuplicate",
                    "Refreshed executable lowering integration id '%s' already exists.".formatted(integrationId),
                    "/refreshedIntegrationId",
                    Map.of("refreshedIntegrationId", integrationId)));
        }
        return diagnostics;
    }

    private static List<VisualDiagnostic> executableReadinessEvidenceRefreshChainDiagnostics(
            VisualRuntimeBindingImplementationBinding sourceBinding,
            VisualRuntimeAdapterActivation sourceActivation,
            VisualExecutableLoweringIntegration sourceIntegration) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (sourceBinding.implementation() == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.implementationMissing",
                    "Source runtime binding has no implementation metadata to rebind.",
                    "/sourceBinding/implementation",
                    Map.of("sourceBindingId", sourceBinding.bindingId())));
        }
        if (sourceBinding.operatorContract() == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.operatorContractMissing",
                    "Source runtime binding has no operator contract snapshot to compare.",
                    "/sourceBinding/operatorContract",
                    Map.of("sourceBindingId", sourceBinding.bindingId())));
        }
        if (sourceActivation == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.activationMissing",
                    "Source runtime binding '%s' has no active adapter activation to refresh."
                            .formatted(sourceBinding.bindingId()),
                    "/sourceActivation",
                    Map.of("sourceBindingId", sourceBinding.bindingId())));
            return diagnostics;
        }
        if (!sourceActivation.active()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.activationNotActive",
                    "Source adapter activation '%s' is not active.".formatted(sourceActivation.activationId()),
                    "/sourceActivation/state",
                    Map.of("sourceActivationId", sourceActivation.activationId(),
                            "state", sourceActivation.state())));
        }
        addEvidenceRefreshMismatch(diagnostics, "activationBindingId", sourceActivation.bindingId(),
                sourceBinding.bindingId(), "/sourceActivation/bindingId");
        addEvidenceRefreshMismatch(diagnostics, "activationBindingRevision", sourceActivation.bindingRevision(),
                sourceBinding.revision(), "/sourceActivation/bindingRevision");
        addEvidenceRefreshMismatch(diagnostics, "activationOperatorRef", sourceActivation.operatorRef(),
                sourceBinding.operatorRef(), "/sourceActivation/operatorRef");
        addEvidenceRefreshMismatch(diagnostics, "activationOperatorFingerprint",
                sourceActivation.operatorFingerprint(), sourceBinding.operatorFingerprint(),
                "/sourceActivation/operatorFingerprint");
        addEvidenceRefreshMismatch(diagnostics, "activationAdapterKind", sourceActivation.adapterKind(),
                sourceBinding.implementation() == null ? "" : sourceBinding.implementation().adapterKind(),
                "/sourceActivation/adapterKind");
        addEvidenceRefreshMismatch(diagnostics, "activationEntrypoint", sourceActivation.entrypoint(),
                sourceBinding.implementation() == null ? "" : sourceBinding.implementation().entrypoint(),
                "/sourceActivation/entrypoint");
        addEvidenceRefreshMismatch(diagnostics, "activationRuntimeOwner", sourceActivation.runtimeOwner(),
                sourceBinding.implementation() == null ? "" : sourceBinding.implementation().runtimeOwner(),
                "/sourceActivation/runtimeOwner");

        if (sourceIntegration == null) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.integrationMissing",
                    "Source adapter activation '%s' has no active executable lowering integration to refresh."
                            .formatted(sourceActivation.activationId()),
                    "/sourceIntegration",
                    Map.of("sourceActivationId", sourceActivation.activationId())));
            return diagnostics;
        }
        if (!sourceIntegration.active()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.integrationNotActive",
                    "Source executable lowering integration '%s' is not active."
                            .formatted(sourceIntegration.integrationId()),
                    "/sourceIntegration/state",
                    Map.of("sourceIntegrationId", sourceIntegration.integrationId(),
                            "state", sourceIntegration.state())));
        }
        addEvidenceRefreshMismatch(diagnostics, "integrationActivationId", sourceIntegration.activationId(),
                sourceActivation.activationId(), "/sourceIntegration/activationId");
        addEvidenceRefreshMismatch(diagnostics, "integrationActivationRevision",
                sourceIntegration.activationRevision(), sourceActivation.revision(),
                "/sourceIntegration/activationRevision");
        addEvidenceRefreshMismatch(diagnostics, "integrationBindingId", sourceIntegration.bindingId(),
                sourceBinding.bindingId(), "/sourceIntegration/bindingId");
        addEvidenceRefreshMismatch(diagnostics, "integrationBindingRevision", sourceIntegration.bindingRevision(),
                sourceBinding.revision(), "/sourceIntegration/bindingRevision");
        addEvidenceRefreshMismatch(diagnostics, "integrationOperatorRef", sourceIntegration.operatorRef(),
                sourceBinding.operatorRef(), "/sourceIntegration/operatorRef");
        addEvidenceRefreshMismatch(diagnostics, "integrationOperatorFingerprint",
                sourceIntegration.operatorFingerprint(), sourceBinding.operatorFingerprint(),
                "/sourceIntegration/operatorFingerprint");
        addEvidenceRefreshMismatch(diagnostics, "integrationAdapterKind", sourceIntegration.adapterKind(),
                sourceActivation.adapterKind(), "/sourceIntegration/adapterKind");
        addEvidenceRefreshMismatch(diagnostics, "integrationEntrypoint", sourceIntegration.entrypoint(),
                sourceActivation.entrypoint(), "/sourceIntegration/entrypoint");
        addEvidenceRefreshMismatch(diagnostics, "integrationRuntimeEnvironment",
                sourceIntegration.runtimeEnvironment(), sourceActivation.runtimeEnvironment(),
                "/sourceIntegration/runtimeEnvironment");
        if (!"native".equals(sourceIntegration.loweringMode())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.loweringModeUnsupported",
                    "Executable readiness evidence refresh currently supports source loweringMode=native; got '%s'."
                            .formatted(sourceIntegration.loweringMode()),
                    "/sourceIntegration/loweringMode",
                    Map.of("loweringMode", sourceIntegration.loweringMode())));
        }
        if (sourceIntegration.executorEntrypoint().isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.executorEntrypointMissing",
                    "Source executable lowering integration has no executorEntrypoint.",
                    "/sourceIntegration/executorEntrypoint"));
        }
        return diagnostics;
    }

    private static void addEvidenceRefreshMismatch(List<VisualDiagnostic> diagnostics,
                                                   String field,
                                                   String actual,
                                                   String expected,
                                                   String target) {
        String normalizedActual = actual == null ? "" : actual.trim();
        String normalizedExpected = expected == null ? "" : expected.trim();
        if (!normalizedActual.equals(normalizedExpected)) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.%sMismatch".formatted(field),
                    "Source evidence %s '%s' does not match expected '%s'."
                            .formatted(field, normalizedActual, normalizedExpected),
                    target,
                    Map.of("actual", normalizedActual, "expected", normalizedExpected)));
        }
    }

    private static void addEvidenceRefreshMismatch(List<VisualDiagnostic> diagnostics,
                                                   String field,
                                                   long actual,
                                                   long expected,
                                                   String target) {
        if (actual != expected) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.%sMismatch".formatted(field),
                    "Source evidence %s '%d' does not match expected '%d'."
                            .formatted(field, actual, expected),
                    target,
                    Map.of("actual", actual, "expected", expected)));
        }
    }

    private static List<VisualDiagnostic> executableReadinessEvidenceRefreshChangeDiagnostics(
            VisualRuntimeBindingImplementationBinding sourceBinding,
            OperatorDefinition currentOperator,
            OperatorDefinitionChangeSummary.ChangeReport changeReport) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (sourceBinding.operatorContract() == null) {
            return diagnostics;
        }
        List<String> categories = changeReport == null ? List.of() : changeReport.categories();
        boolean unsupported = categories.stream()
                .anyMatch(category -> !OperatorDefinitionChangeSummary.RISK_RUNTIME_BINDING.equals(category)
                        && !OperatorDefinitionChangeSummary.RISK_METADATA.equals(category));
        if (unsupported) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessEvidenceRefresh.contractChangeUnsupported",
                    "Executable readiness evidence refresh only supports runtime-binding/metadata surface changes; observed '%s'."
                            .formatted(categories),
                    "/operatorRef",
                    Map.of(
                            "operatorRef", currentOperator.operatorRef(),
                            "previousOperatorFingerprint", sourceBinding.operatorFingerprint(),
                            "currentOperatorFingerprint", currentOperator.fingerprint(),
                            "changeRisk", changeReport.risk(),
                            "changeCategories", categories,
                            "changeSummary", changeReport.summary()
                    )));
        }
        return diagnostics;
    }

    private static OperatorDefinition operatorDefinitionFromContract(
            VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract) {
        if (contract == null) {
            return null;
        }
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                contract.operatorRef(),
                contract.operatorVersion(),
                "",
                contract.display(),
                contract.source(),
                contract.ports(),
                contract.configSchema(),
                contract.capabilities(),
                contract.policy(),
                contract.lowering(),
                List.of());
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata refreshedImplementation(
            VisualRuntimeBindingImplementationValidation.ImplementationMetadata source,
            String refreshedBindingId) {
        if (source == null) {
            return new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                    refreshedBindingId,
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    "");
        }
        return new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                refreshedBindingId,
                source.adapterKind(),
                source.entrypoint(),
                source.runtimeOwner(),
                source.capabilities(),
                source.testEvidence(),
                source.policyEvidence(),
                source.rollbackTarget(),
                source.notes());
    }

    private static VisualRuntimeAdapterActivationValidation.Request refreshedActivationRequest(
            VisualRuntimeAdapterActivation sourceActivation,
            VisualRuntimeBindingImplementationBinding refreshedBinding,
            String refreshedActivationId,
            String actor,
            String changeSource,
            String reason) {
        return new VisualRuntimeAdapterActivationValidation.Request(
                VisualRuntimeAdapterActivationValidation.REQUEST_SCHEMA_VERSION,
                refreshedActivationId,
                refreshedBinding.bindingId(),
                refreshedBinding.revision(),
                refreshedBinding.operatorRef(),
                refreshedBinding.operatorFingerprint(),
                refreshedBinding.implementation().adapterKind(),
                refreshedBinding.implementation().entrypoint(),
                refreshedBinding.implementation().runtimeOwner(),
                sourceActivation.runtimeEnvironment(),
                sourceActivation.healthState(),
                actor,
                changeSource,
                reason,
                refreshedActivationEvidence(sourceActivation, refreshedBinding));
    }

    private static List<VisualRuntimeAdapterActivation.Evidence> refreshedActivationEvidence(
            VisualRuntimeAdapterActivation sourceActivation,
            VisualRuntimeBindingImplementationBinding refreshedBinding) {
        List<VisualRuntimeAdapterActivation.Evidence> evidence = new ArrayList<>(sourceActivation.evidence());
        evidence.add(new VisualRuntimeAdapterActivation.Evidence(
                "post-apply-refresh",
                sourceActivation.activationId(),
                "Rebound adapter activation after executable readiness apply to binding '%s'."
                        .formatted(refreshedBinding.bindingId())));
        return List.copyOf(evidence);
    }

    private static VisualExecutableLoweringIntegrationValidation.Request refreshedIntegrationRequest(
            VisualExecutableLoweringIntegration sourceIntegration,
            VisualRuntimeAdapterActivation refreshedActivation,
            String refreshedIntegrationId,
            String actor,
            String changeSource,
            String reason) {
        return new VisualExecutableLoweringIntegrationValidation.Request(
                VisualExecutableLoweringIntegrationValidation.REQUEST_SCHEMA_VERSION,
                refreshedIntegrationId,
                refreshedActivation.activationId(),
                refreshedActivation.revision(),
                refreshedActivation.bindingId(),
                refreshedActivation.bindingRevision(),
                refreshedActivation.operatorRef(),
                refreshedActivation.operatorFingerprint(),
                refreshedActivation.adapterKind(),
                refreshedActivation.entrypoint(),
                refreshedActivation.runtimeEnvironment(),
                sourceIntegration.loweringMode(),
                sourceIntegration.executorKind(),
                sourceIntegration.executorEntrypoint(),
                sourceIntegration.executorOwner(),
                actor,
                changeSource,
                reason,
                refreshedIntegrationEvidence(sourceIntegration, refreshedActivation));
    }

    private static List<VisualExecutableLoweringIntegration.Evidence> refreshedIntegrationEvidence(
            VisualExecutableLoweringIntegration sourceIntegration,
            VisualRuntimeAdapterActivation refreshedActivation) {
        List<VisualExecutableLoweringIntegration.Evidence> evidence = new ArrayList<>(sourceIntegration.evidence());
        evidence.add(new VisualExecutableLoweringIntegration.Evidence(
                "post-apply-refresh",
                sourceIntegration.integrationId(),
                "Rebound executable lowering integration after executable readiness apply to activation '%s'."
                        .formatted(refreshedActivation.activationId())));
        return List.copyOf(evidence);
    }

    private static VisualDiagnostic executableReadinessApplyAckRequired(
            VisualExecutableReadinessRecomputePreview preview) {
        return VisualDiagnostic.warning(
                "visual.executableReadinessRecompute.ackWarningsRequired",
                "Executable readiness recompute for '%s' changes the trusted runtime-binding surface; review the candidate operator and retry with ackWarnings=true."
                        .formatted(preview.operatorRef()),
                "/ackWarnings",
                Map.of(
                        "operatorRef", preview.operatorRef(),
                        "operatorLibraryId", preview.operatorLibraryId(),
                        "currentOperatorFingerprint", preview.currentOperatorFingerprint(),
                        "candidateOperatorFingerprint", preview.candidateOperatorFingerprint(),
                        "activeBindingId", preview.activeBindingId(),
                        "activeAdapterActivationId", preview.activeAdapterActivationId(),
                        "activeExecutableLoweringIntegrationId", preview.activeExecutableLoweringIntegrationId()
                )
        );
    }

    private static List<VisualDiagnostic> executableReadinessApplyGovernanceDiagnostics(
            String actor,
            String reason) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> metadata = Map.of("requiredFor", List.of("ackWarnings"));
        if (actor == null || actor.isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.governanceEvidenceMissing",
                    "Executable readiness recompute apply requires actor when using ackWarnings=true.",
                    "/actor",
                    metadata));
        }
        if (reason == null || reason.isBlank()) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.governanceEvidenceMissing",
                    "Executable readiness recompute apply requires reason when using ackWarnings=true.",
                    "/reason",
                    metadata));
        }
        return diagnostics;
    }

    private static ReplacementLibrary replacementLibraryForExecutableReadinessApply(
            OperatorLibrary currentLibrary,
            VisualExecutableReadinessRecomputePreview preview) {
        if (preview.candidateOperator() == null) {
            return ReplacementLibrary.rejected(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.candidateOperatorMissing",
                    "Executable readiness recompute apply requires a candidate operator from the preview.",
                    "/candidateOperator"));
        }
        List<OperatorDefinition> operators = new ArrayList<>();
        boolean replaced = false;
        for (int i = 0; i < currentLibrary.operators().size(); i++) {
            OperatorDefinition operator = currentLibrary.operators().get(i);
            if (operator == null || !operator.operatorRef().equals(preview.operatorRef())) {
                operators.add(operator);
                continue;
            }
            if (!operator.fingerprint().equals(preview.currentOperatorFingerprint())) {
                return ReplacementLibrary.rejected(VisualDiagnostic.error(
                        "visual.executableReadinessRecompute.libraryOperatorFingerprintDrift",
                        "Operator library '%s' operator '%s' fingerprint '%s' no longer matches preview fingerprint '%s'. Re-preview before applying."
                                .formatted(currentLibrary.libraryId(), preview.operatorRef(),
                                        operator.fingerprint(), preview.currentOperatorFingerprint()),
                        "/operators/%d/fingerprint".formatted(i),
                        Map.of(
                                "operatorRef", preview.operatorRef(),
                                "libraryOperatorFingerprint", operator.fingerprint(),
                                "previewCurrentOperatorFingerprint", preview.currentOperatorFingerprint()
                        )));
            }
            operators.add(preview.candidateOperator());
            replaced = true;
        }
        if (!replaced) {
            return ReplacementLibrary.rejected(VisualDiagnostic.error(
                    "visual.executableReadinessRecompute.libraryOperatorMissing",
                    "Operator library '%s' no longer contains operator '%s'. Re-preview before applying."
                            .formatted(currentLibrary.libraryId(), preview.operatorRef()),
                    "/operators",
                    Map.of("operatorRef", preview.operatorRef())));
        }
        return new ReplacementLibrary(new OperatorLibrary(
                currentLibrary.schemaVersion(),
                currentLibrary.libraryId(),
                currentLibrary.displayName(),
                currentLibrary.version(),
                currentLibrary.owner(),
                currentLibrary.status(),
                operators
        ), List.of());
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record ReplacementLibrary(OperatorLibrary library, List<VisualDiagnostic> diagnostics) {
        private ReplacementLibrary {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        private static ReplacementLibrary rejected(VisualDiagnostic diagnostic) {
            return new ReplacementLibrary(null, List.of(diagnostic));
        }

        private boolean valid() {
            return library != null && diagnostics.isEmpty();
        }
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

    private static VisualRuntimeAdapterActivationValidation adapterActivationValidationWithBlockingDiagnostic(
            VisualRuntimeAdapterActivationValidation validation,
            VisualDiagnostic diagnostic) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>(validation.diagnostics());
        diagnostics.add(diagnostic);
        return new VisualRuntimeAdapterActivationValidation(
                validation.schemaVersion(),
                validation.validatedAt(),
                false,
                false,
                "rejected",
                "error",
                diagnostic.message(),
                validation.activationId(),
                validation.bindingId(),
                validation.bindingRevision(),
                validation.operatorRef(),
                validation.operatorFingerprint(),
                validation.currentCatalogFingerprint(),
                validation.currentCatalogState(),
                validation.adapterKind(),
                validation.entrypoint(),
                validation.runtimeOwner(),
                validation.runtimeEnvironment(),
                validation.healthState(),
                validation.activatedBy(),
                validation.reason(),
                diagnostics
        );
    }

    private static VisualExecutableLoweringIntegrationValidation
            executableLoweringIntegrationValidationWithBlockingDiagnostic(
                    VisualExecutableLoweringIntegrationValidation validation,
                    VisualDiagnostic diagnostic) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>(validation.diagnostics());
        diagnostics.add(diagnostic);
        return new VisualExecutableLoweringIntegrationValidation(
                validation.schemaVersion(),
                validation.validatedAt(),
                false,
                false,
                "rejected",
                "error",
                diagnostic.message(),
                validation.integrationId(),
                validation.activationId(),
                validation.activationRevision(),
                validation.bindingId(),
                validation.bindingRevision(),
                validation.operatorRef(),
                validation.operatorFingerprint(),
                validation.currentCatalogFingerprint(),
                validation.currentCatalogState(),
                validation.adapterKind(),
                validation.entrypoint(),
                validation.runtimeEnvironment(),
                validation.loweringMode(),
                validation.executorKind(),
                validation.executorEntrypoint(),
                validation.executorOwner(),
                validation.integratedBy(),
                validation.reason(),
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
