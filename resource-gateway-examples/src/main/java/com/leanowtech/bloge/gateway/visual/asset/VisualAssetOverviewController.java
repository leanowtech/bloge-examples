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

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
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
        this.draftRepository = draftRepository;
        this.validator = validator;
        this.catalog = catalog;
        this.publicationRepository = publicationRepository;
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
