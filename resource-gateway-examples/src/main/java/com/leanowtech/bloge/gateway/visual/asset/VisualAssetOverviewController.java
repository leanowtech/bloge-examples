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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
                                        @RequestParam(defaultValue = "") String actionTargetKind) {
        List<GraphDraftSummary> draftSummaries = draftRepository.history().stream()
                .map(this::draftSummary)
                .filter(summary -> matchesDraftScope(summary, tenantId, namespace, environment))
                .toList();
        List<VisualGraphPublicationSummary> publicationSummaries = publicationRepository.all().stream()
                .map(VisualGraphPublicationSummary::from)
                .filter(summary -> matchesPublicationScope(summary, tenantId, namespace, environment))
                .toList();
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
                tenantId,
                namespace,
                environment,
                actionLimit,
                actionOffset,
                actionSeverity,
                actionType,
                actionTargetKind
        );
    }

    VisualAssetOverview overview(String tenantId, String namespace, String environment) {
        return overview(tenantId, namespace, environment, VisualAssetOverview.DEFAULT_ACTION_ITEM_LIMIT);
    }

    VisualAssetOverview overview(String tenantId, String namespace, String environment, int actionLimit) {
        return overview(tenantId, namespace, environment, actionLimit, 0, "", "", "");
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
