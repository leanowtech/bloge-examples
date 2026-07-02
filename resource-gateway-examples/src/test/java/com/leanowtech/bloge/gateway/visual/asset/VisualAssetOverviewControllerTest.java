package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual asset overview APIs.
 */
class VisualAssetOverviewControllerTest {

    @Test
    void overviewAggregatesDraftPublicationAndCatalogReadiness() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithFingerprint(operator));
        VisualValidationResult validation = validator.validate(draft);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(draft, catalog);
        VisualGraphPublication publication = publications.create(VisualGraphPublication.design(
                draft,
                List.of(operator),
                validation,
                new DslGenerationResult(false, "", List.of()),
                dependencyReport
        ));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);

        VisualAssetOverview overview = controller.overview("", "", "");

        assertThat(overview.schemaVersion()).isEqualTo("bloge.visualAssetOverview.v1");
        assertThat(overview.scope().filtered()).isFalse();
        assertThat(overview.drafts().total()).isEqualTo(1);
        assertThat(overview.drafts().activeCount()).isEqualTo(1);
        assertThat(overview.drafts().validCount()).isEqualTo(1);
        assertThat(overview.drafts().graphReadinessStateCounts()).containsEntry("design-only", 1);
        assertThat(overview.drafts().publishableArtifactKindCounts()).containsEntry("DESIGN", 1);
        assertThat(overview.drafts().operatorRuntimeReadinessStateCounts()).containsEntry("design-only", 1);
        assertThat(GraphDraftSummary.from(drafts.history().getFirst(), draft, validation, dependencyReport)
                .actionReadiness().state())
                .isEqualTo("design-artifact-ready");
        assertThat(overview.publications().total()).isEqualTo(1);
        assertThat(overview.publications().designArtifactCount()).isEqualTo(1);
        assertThat(overview.publications().artifactKindCounts()).containsEntry("DESIGN", 1);
        assertThat(overview.publications().graphReadinessStateCounts()).containsEntry("design-only", 1);
        assertThat(VisualGraphPublicationSummary.from(publication).actionReadiness().state())
                .isEqualTo("design-artifact-ready");
        assertThat(overview.catalog().totalOperators()).isGreaterThanOrEqualTo(1);
        assertThat(overview.catalog().facets().runtimeReadinessStates()).containsEntry("design-only", 1);
        assertThat(overview.actionQueue().itemLimit()).isEqualTo(VisualAssetOverview.DEFAULT_ACTION_ITEM_LIMIT);
        assertThat(overview.actionQueue().unfilteredTotal()).isEqualTo(overview.actionQueue().total());
        assertThat(overview.actionQueue().offset()).isZero();
        assertThat(overview.actionQueue().hasMore()).isFalse();
        assertThat(overview.actionQueue().filter().filtered()).isFalse();
        assertThat(overview.actionQueue().actionTypeCounts())
                .containsEntry("TRACK_DESIGN_DRAFT", 1)
                .containsEntry("PLAN_PUBLICATION_RUNTIME_BINDING", 1)
                .containsEntry("TRACK_SCHEMA_ONLY_OPERATOR", 1);
        assertThat(overview.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::actionType)
                .contains("TRACK_DESIGN_DRAFT", "PLAN_PUBLICATION_RUNTIME_BINDING", "TRACK_SCHEMA_ONLY_OPERATOR");
        assertThat(overview.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::actionKey)
                .contains(
                        "TRACK_DESIGN_DRAFT|draft|%s|design-only|".formatted(draft.draftId()),
                        "PLAN_PUBLICATION_RUNTIME_BINDING|publication|%s|design-only|DESIGN"
                                .formatted(publication.publicationId()),
                        "TRACK_SCHEMA_ONLY_OPERATOR|operator|risk:eligibility|design-only|"
                );
    }

    @Test
    void overviewQueuesWarningAcknowledgementFromGraphActionReadiness() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithUnreachableDesignNode(operator));
        VisualValidationResult validation = validator.validate(draft);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(draft, catalog);
        VisualGraphPublication publication = publications.create(VisualGraphPublication.design(
                draft,
                List.of(operator),
                validation,
                new DslGenerationResult(false, "", List.of()),
                dependencyReport
        ));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);

        VisualAssetOverview overview = controller.overview("", "", "");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.actionReadiness().state()).isEqualTo("warning-ack-required");
        assertThat(GraphDraftSummary.from(drafts.history().getFirst(), draft, validation, dependencyReport)
                .actionReadiness().requiresAckWarnings()).isTrue();
        assertThat(VisualGraphPublicationSummary.from(publication).actionReadiness().requiresAckWarnings()).isTrue();
        assertThat(overview.actionQueue().actionTypeCounts())
                .containsEntry("ACK_DRAFT_WARNINGS", 1)
                .containsEntry("REVIEW_PUBLICATION_WARNING_EVIDENCE", 1);
        assertThat(overview.actionQueue().items())
                .filteredOn(item -> item.targetId().equals(draft.draftId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.actionType()).isEqualTo("ACK_DRAFT_WARNINGS");
                    assertThat(item.severity()).isEqualTo("warning");
                    assertThat(item.summary()).contains("publication requires warning acknowledgement");
                    assertThat(item.recommendedAction()).contains("Acknowledge warnings");
                });
        assertThat(overview.actionQueue().items())
                .filteredOn(item -> item.targetId().equals(publication.publicationId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.actionType()).isEqualTo("REVIEW_PUBLICATION_WARNING_EVIDENCE");
                    assertThat(item.severity()).isEqualTo("warning");
                    assertThat(item.artifactKind()).isEqualTo("DESIGN");
                });
    }

    @Test
    void overviewFiltersDraftsAndPublicationsByAuthoringScope() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithFingerprint(operator));
        VisualValidationResult validation = validator.validate(draft);
        publications.create(VisualGraphPublication.design(
                draft,
                List.of(operator),
                validation,
                new DslGenerationResult(false, "", List.of()),
                GraphDraftDependencyReport.from(draft, catalog)
        ));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);

        VisualAssetOverview included = controller.overview("tenant-a", "risk", "dev");
        VisualAssetOverview excluded = controller.overview("tenant-b", "risk", "dev");

        assertThat(included.drafts().total()).isEqualTo(1);
        assertThat(included.scope().tenantId()).isEqualTo("tenant-a");
        assertThat(included.scope().namespace()).isEqualTo("risk");
        assertThat(included.scope().environment()).isEqualTo("dev");
        assertThat(included.scope().filtered()).isTrue();
        assertThat(included.publications().total()).isEqualTo(1);
        assertThat(excluded.drafts().total()).isZero();
        assertThat(excluded.scope().tenantId()).isEqualTo("tenant-b");
        assertThat(excluded.scope().namespace()).isEqualTo("risk");
        assertThat(excluded.scope().environment()).isEqualTo("dev");
        assertThat(excluded.scope().filtered()).isTrue();
        assertThat(excluded.publications().total()).isZero();
    }

    @Test
    void overviewBoundsActionItemWindowWithoutChangingAggregateCounts() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithFingerprint(operator));
        VisualValidationResult validation = validator.validate(draft);
        publications.create(VisualGraphPublication.design(
                draft,
                List.of(operator),
                validation,
                new DslGenerationResult(false, "", List.of()),
                GraphDraftDependencyReport.from(draft, catalog)
        ));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);

        VisualAssetOverview limited = controller.overview("", "", "", 1);
        VisualAssetOverview aggregateOnly = controller.overview("", "", "", 0);
        VisualAssetOverview capped = controller.overview("", "", "", 1_000);
        int actionTotal = totalActionTypes(limited.actionQueue());

        assertThat(actionTotal).isGreaterThanOrEqualTo(3);
        assertThat(limited.actionQueue().total()).isEqualTo(actionTotal);
        assertThat(limited.actionQueue().unfilteredTotal()).isEqualTo(actionTotal);
        assertThat(limited.actionQueue().displayedCount()).isEqualTo(1);
        assertThat(limited.actionQueue().itemLimit()).isEqualTo(1);
        assertThat(limited.actionQueue().offset()).isZero();
        assertThat(limited.actionQueue().hasMore()).isTrue();
        assertThat(limited.actionQueue().items()).hasSize(1);
        assertThat(limited.actionQueue().actionTypeCounts())
                .containsEntry("TRACK_DESIGN_DRAFT", 1)
                .containsEntry("PLAN_PUBLICATION_RUNTIME_BINDING", 1)
                .containsEntry("TRACK_SCHEMA_ONLY_OPERATOR", 1);
        assertThat(aggregateOnly.actionQueue().total()).isEqualTo(actionTotal);
        assertThat(aggregateOnly.actionQueue().actionTypeCounts()).isEqualTo(limited.actionQueue().actionTypeCounts());
        assertThat(aggregateOnly.actionQueue().displayedCount()).isZero();
        assertThat(aggregateOnly.actionQueue().itemLimit()).isZero();
        assertThat(aggregateOnly.actionQueue().hasMore()).isTrue();
        assertThat(aggregateOnly.actionQueue().items()).isEmpty();
        assertThat(capped.actionQueue().total()).isEqualTo(actionTotal);
        assertThat(capped.actionQueue().itemLimit()).isEqualTo(VisualAssetOverview.MAX_ACTION_ITEM_LIMIT);
        assertThat(capped.actionQueue().displayedCount()).isEqualTo(actionTotal);
        assertThat(capped.actionQueue().hasMore()).isFalse();
    }

    @Test
    void overviewFiltersAndOffsetsActionQueueForControlPlaneQueries() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithFingerprint(operator));
        VisualValidationResult validation = validator.validate(draft);
        publications.create(VisualGraphPublication.design(
                draft,
                List.of(operator),
                validation,
                new DslGenerationResult(false, "", List.of()),
                GraphDraftDependencyReport.from(draft, catalog)
        ));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);
        int unfilteredTotal = controller.overview("", "", "").actionQueue().total();

        VisualAssetOverview operatorOnly = controller.overview(
                "",
                "",
                "",
                10,
                0,
                "info",
                "track_schema_only_operator",
                "operator"
        );
        VisualAssetOverview secondAction = controller.overview("", "", "", 1, 1, "", "", "");
        VisualAssetOverview beyondOperator = controller.overview(
                "",
                "",
                "",
                10,
                1,
                "",
                "TRACK_SCHEMA_ONLY_OPERATOR",
                "operator"
        );

        assertThat(operatorOnly.actionQueue().unfilteredTotal()).isEqualTo(unfilteredTotal);
        assertThat(operatorOnly.actionQueue().total()).isEqualTo(1);
        assertThat(operatorOnly.actionQueue().displayedCount()).isEqualTo(1);
        assertThat(operatorOnly.actionQueue().hasMore()).isFalse();
        assertThat(operatorOnly.actionQueue().filter().filtered()).isTrue();
        assertThat(operatorOnly.actionQueue().filter().severity()).isEqualTo("info");
        assertThat(operatorOnly.actionQueue().filter().actionType()).isEqualTo("TRACK_SCHEMA_ONLY_OPERATOR");
        assertThat(operatorOnly.actionQueue().filter().targetKind()).isEqualTo("operator");
        assertThat(operatorOnly.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::actionType)
                .containsExactly("TRACK_SCHEMA_ONLY_OPERATOR");
        assertThat(secondAction.actionQueue().unfilteredTotal()).isEqualTo(unfilteredTotal);
        assertThat(secondAction.actionQueue().total()).isEqualTo(unfilteredTotal);
        assertThat(secondAction.actionQueue().offset()).isEqualTo(1);
        assertThat(secondAction.actionQueue().displayedCount()).isEqualTo(1);
        assertThat(secondAction.actionQueue().hasMore()).isEqualTo(unfilteredTotal > 2);
        assertThat(beyondOperator.actionQueue().unfilteredTotal()).isEqualTo(unfilteredTotal);
        assertThat(beyondOperator.actionQueue().total()).isEqualTo(1);
        assertThat(beyondOperator.actionQueue().offset()).isEqualTo(1);
        assertThat(beyondOperator.actionQueue().displayedCount()).isZero();
        assertThat(beyondOperator.actionQueue().hasMore()).isFalse();
        assertThat(beyondOperator.actionQueue().items()).isEmpty();
    }

    private static int totalActionTypes(VisualAssetOverview.ActionQueue actionQueue) {
        return actionQueue.actionTypeCounts().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static GraphDraft draftWithFingerprint(OperatorDefinition operator) {
        return new GraphDraft(
                "",
                "",
                0,
                "visualPolicy",
                "tenant-a",
                "risk",
                "dev",
                "",
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ), List.of("score", "amount")),
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "Eligibility",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", operator.fingerprint())
        );
    }

    private static GraphDraft draftWithUnreachableDesignNode(OperatorDefinition operator) {
        return new GraphDraft(
                "",
                "",
                0,
                "visualPolicy",
                "tenant-a",
                "risk",
                "dev",
                "",
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ), List.of("score", "amount")),
                List.of(
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "Eligibility",
                                Map.of(
                                        "score", GraphDraft.Binding.contextPath("score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "unusedEligibility",
                                "risk:eligibility",
                                "Unused Eligibility",
                                Map.of(
                                        "score", GraphDraft.Binding.contextPath("score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of(
                        "eligibility", operator.fingerprint(),
                        "unusedEligibility", operator.fingerprint()
                )
        );
    }
}
