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
                .containsEntry("PLAN_DRAFT_RUNTIME_BINDING", 1)
                .containsEntry("TRACK_DESIGN_DRAFT", 1)
                .containsEntry("PLAN_PUBLICATION_RUNTIME_BINDING", 1)
                .containsEntry("TRACK_SCHEMA_ONLY_OPERATOR", 1);
        assertThat(overview.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::actionType)
                .contains("PLAN_DRAFT_RUNTIME_BINDING",
                        "TRACK_DESIGN_DRAFT",
                        "PLAN_PUBLICATION_RUNTIME_BINDING",
                        "TRACK_SCHEMA_ONLY_OPERATOR");
        assertThat(overview.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::actionKey)
                .contains(
                        "PLAN_DRAFT_RUNTIME_BINDING|draft|%s|eligibility|executable-lowering|risk:eligibility|"
                                .formatted(draft.draftId()),
                        "TRACK_DESIGN_DRAFT|draft|%s|design-only|".formatted(draft.draftId()),
                        "PLAN_PUBLICATION_RUNTIME_BINDING|publication|%s|eligibility|executable-lowering|risk:eligibility|DESIGN"
                                .formatted(publication.publicationId()),
                        "TRACK_SCHEMA_ONLY_OPERATOR|operator|risk:eligibility|design-only|"
                );
        assertThat(overview.actionQueue().items())
                .filteredOn(item -> item.actionType().equals("PLAN_DRAFT_RUNTIME_BINDING"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.targetLabel()).contains("eligibility");
                    assertThat(item.summary()).contains("executable-lowering").contains("risk:eligibility");
                    assertThat(item.handoffLane()).isEqualTo("operator-platform");
                    assertThat(item.handoffKind()).isEqualTo("operator-implementation");
                    assertThat(item.handoffTarget()).isEqualTo("risk:eligibility");
                    assertThat(item.recommendedAction()).contains("EXECUTABLE promotion");
                });
    }

    @Test
    void runtimeBindingRequirementsIndexesDraftAndPublicationGapsForControlPlanes() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithFingerprint(operator));
        VisualValidationResult validation = validator.validate(draft);
        VisualGraphPublication publication = publications.create(VisualGraphPublication.design(
                draft,
                List.of(operator),
                validation,
                new DslGenerationResult(false, "", List.of()),
                GraphDraftDependencyReport.from(draft, catalog)
        ));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);

        VisualRuntimeBindingRequirements firstPage = controller.runtimeBindingRequirements(
                "",
                "",
                "",
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
                ""
        );
        String draftRequirementKey = "RUNTIME_BINDING|draft|%s|eligibility|executable-lowering|risk:eligibility|"
                .formatted(draft.draftId());
        VisualRuntimeBindingRequirements byRequirementKey = controller.runtimeBindingRequirements(
                "",
                "",
                "",
                10,
                0,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                draftRequirementKey
        );
        VisualRuntimeBindingRequirements draftOnly = controller.runtimeBindingRequirements(
                "",
                "",
                "",
                10,
                0,
                "draft",
                "executable-lowering",
                "operator-platform",
                "operator-implementation",
                "risk:eligibility",
                "",
                "",
                "",
                ""
        );
        VisualRuntimeBindingHandoffBundle handoffBundle = controller.runtimeBindingHandoffBundle(
                "",
                "",
                "",
                10,
                0,
                "draft",
                "executable-lowering",
                "operator-platform",
                "operator-implementation",
                "risk:eligibility",
                "",
                "",
                "",
                ""
        );
        VisualRuntimeBindingHandoffReview currentReview = controller.reviewRuntimeBindingHandoffBundle(handoffBundle)
                .getBody();
        VisualRuntimeBindingRequirements excludedScope =
                controller.runtimeBindingRequirements("tenant-b", "risk", "dev");

        assertThat(firstPage.schemaVersion()).isEqualTo(VisualRuntimeBindingRequirements.SCHEMA_VERSION);
        assertThat(firstPage.scope().filtered()).isFalse();
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.unfilteredTotal()).isEqualTo(2);
        assertThat(firstPage.displayedCount()).isEqualTo(1);
        assertThat(firstPage.itemLimit()).isEqualTo(1);
        assertThat(firstPage.offset()).isZero();
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.targetKindCounts()).containsEntry("draft", 1)
                .containsEntry("publication", 1);
        assertThat(firstPage.bindingKindCounts()).containsEntry("executable-lowering", 2);
        assertThat(firstPage.handoffLaneCounts()).containsEntry("operator-platform", 2);
        assertThat(firstPage.handoffKindCounts()).containsEntry("operator-implementation", 2);
        assertThat(firstPage.handoffTargetCounts()).containsEntry("risk:eligibility", 2);
        assertThat(firstPage.sourceKindCounts()).containsEntry("user-library", 2);
        assertThat(firstPage.loweringModeCounts()).containsEntry("design", 2);
        assertThat(firstPage.readinessStateCounts()).containsEntry("design-only", 2);
        assertThat(firstPage.artifactKindCounts()).containsEntry("DESIGN", 1);
        assertThat(firstPage.items()).singleElement().satisfies(item -> {
            assertThat(item.requirementKey()).isEqualTo(
                    draftRequirementKey);
            assertThat(item.targetKind()).isEqualTo("draft");
            assertThat(item.targetLabel()).contains("visualPolicy").contains("eligibility");
            assertThat(item.operatorRef()).isEqualTo("risk:eligibility");
            assertThat(item.bindingKind()).isEqualTo("executable-lowering");
            assertThat(item.bindingTarget()).isEqualTo("risk:eligibility");
            assertThat(item.handoffLane()).isEqualTo("operator-platform");
            assertThat(item.handoffKind()).isEqualTo("operator-implementation");
            assertThat(item.handoffTarget()).isEqualTo("risk:eligibility");
            assertThat(item.recommendedAction()).contains("EXECUTABLE promotion");
        });
        assertThat(draftOnly.filter().filtered()).isTrue();
        assertThat(draftOnly.filter().targetKind()).isEqualTo("draft");
        assertThat(draftOnly.filter().bindingKind()).isEqualTo("executable-lowering");
        assertThat(draftOnly.filter().handoffLane()).isEqualTo("operator-platform");
        assertThat(draftOnly.filter().handoffKind()).isEqualTo("operator-implementation");
        assertThat(draftOnly.filter().handoffTarget()).isEqualTo("risk:eligibility");
        assertThat(draftOnly.total()).isEqualTo(1);
        assertThat(draftOnly.unfilteredTotal()).isEqualTo(2);
        assertThat(draftOnly.items()).singleElement()
                .extracting(VisualRuntimeBindingRequirements.RequirementItem::targetId)
                .isEqualTo(draft.draftId());
        assertThat(byRequirementKey.filter().filtered()).isTrue();
        assertThat(byRequirementKey.filter().requirementKey()).isEqualTo(draftRequirementKey);
        assertThat(byRequirementKey.total()).isEqualTo(1);
        assertThat(byRequirementKey.unfilteredTotal()).isEqualTo(2);
        assertThat(byRequirementKey.items()).singleElement()
                .extracting(VisualRuntimeBindingRequirements.RequirementItem::requirementKey)
                .isEqualTo(draftRequirementKey);
        assertThat(handoffBundle.schemaVersion()).isEqualTo(VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION);
        assertThat(handoffBundle.sourceIndexSchemaVersion())
                .isEqualTo(VisualRuntimeBindingRequirements.SCHEMA_VERSION);
        assertThat(handoffBundle.filter().targetKind()).isEqualTo("draft");
        assertThat(handoffBundle.filter().bindingKind()).isEqualTo("executable-lowering");
        assertThat(handoffBundle.filter().handoffLane()).isEqualTo("operator-platform");
        assertThat(handoffBundle.filter().handoffKind()).isEqualTo("operator-implementation");
        assertThat(handoffBundle.filter().handoffTarget()).isEqualTo("risk:eligibility");
        assertThat(handoffBundle.total()).isEqualTo(1);
        assertThat(handoffBundle.unfilteredTotal()).isEqualTo(2);
        assertThat(handoffBundle.displayedCount()).isEqualTo(1);
        assertThat(handoffBundle.hasMore()).isFalse();
        assertThat(handoffBundle.requirementKeys()).containsExactly(draftRequirementKey);
        assertThat(handoffBundle.handoffLaneCounts()).containsEntry("operator-platform", 1);
        assertThat(handoffBundle.handoffKindCounts()).containsEntry("operator-implementation", 1);
        assertThat(handoffBundle.handoffTargetCounts()).containsEntry("risk:eligibility", 1);
        assertThat(handoffBundle.requirements()).singleElement().satisfies(item -> {
            assertThat(item.requirementKey()).isEqualTo(draftRequirementKey);
            assertThat(item.targetKind()).isEqualTo("draft");
            assertThat(item.targetId()).isEqualTo(draft.draftId());
            assertThat(item.operatorRef()).isEqualTo("risk:eligibility");
        });
        assertThat(currentReview).isNotNull();
        assertThat(currentReview.schemaVersion()).isEqualTo(VisualRuntimeBindingHandoffReview.SCHEMA_VERSION);
        assertThat(currentReview.reviewable()).isTrue();
        assertThat(currentReview.state()).isEqualTo("current");
        assertThat(currentReview.level()).isEqualTo("success");
        assertThat(currentReview.sourceBundleSchemaVersion())
                .isEqualTo(VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION);
        assertThat(currentReview.exportedRequirementCount()).isEqualTo(1);
        assertThat(currentReview.currentWindowTotal()).isEqualTo(1);
        assertThat(currentReview.currentWindowDisplayedCount()).isEqualTo(1);
        assertThat(currentReview.matchedCount()).isEqualTo(1);
        assertThat(currentReview.driftedCount()).isZero();
        assertThat(currentReview.missingCount()).isZero();
        assertThat(currentReview.newCurrentWindowCount()).isZero();
        assertThat(currentReview.exportedRequirementKeys()).containsExactly(draftRequirementKey);
        assertThat(currentReview.currentWindowRequirementKeys()).containsExactly(draftRequirementKey);
        assertThat(currentReview.statusCounts()).containsEntry("current", 1);
        assertThat(currentReview.items()).singleElement().satisfies(item -> {
            assertThat(item.requirementKey()).isEqualTo(draftRequirementKey);
            assertThat(item.status()).isEqualTo("current");
            assertThat(item.changedFields()).isEmpty();
            assertThat(item.currentRequirement()).isNotNull();
        });
        assertThat(firstPage.items()).noneMatch(item -> item.targetId().equals(publication.publicationId()));
        assertThat(excludedScope.scope().filtered()).isTrue();
        assertThat(excludedScope.total()).isZero();
        assertThat(excludedScope.unfilteredTotal()).isZero();
    }

    @Test
    void runtimeBindingHandoffReviewDetectsMissingRequirementKeys() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);
        VisualRuntimeBindingHandoffBundle handoffBundle = controller.runtimeBindingHandoffBundle(
                "",
                "",
                "",
                10,
                0,
                "draft",
                "executable-lowering",
                "operator-platform",
                "operator-implementation",
                "risk:eligibility",
                "",
                "",
                "",
                ""
        );

        drafts.delete(draft.draftId());
        VisualRuntimeBindingHandoffReview staleReview = controller.reviewRuntimeBindingHandoffBundle(handoffBundle)
                .getBody();

        assertThat(staleReview).isNotNull();
        assertThat(staleReview.reviewable()).isTrue();
        assertThat(staleReview.state()).isEqualTo("stale");
        assertThat(staleReview.level()).isEqualTo("warning");
        assertThat(staleReview.exportedRequirementCount()).isEqualTo(1);
        assertThat(staleReview.currentWindowTotal()).isZero();
        assertThat(staleReview.matchedCount()).isZero();
        assertThat(staleReview.driftedCount()).isZero();
        assertThat(staleReview.missingCount()).isEqualTo(1);
        assertThat(staleReview.statusCounts()).containsEntry("missing", 1);
        assertThat(staleReview.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("missing");
            assertThat(item.exportedRequirement()).isNotNull();
            assertThat(item.currentRequirement()).isNull();
            assertThat(item.message()).contains("no longer present");
        });
    }

    @Test
    void runtimeBindingHandoffReviewExplainsDriftedRequirementFields() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);
        VisualRuntimeBindingHandoffBundle handoffBundle = controller.runtimeBindingHandoffBundle(
                "",
                "",
                "",
                10,
                0,
                "draft",
                "executable-lowering",
                "operator-platform",
                "operator-implementation",
                "risk:eligibility",
                "",
                "",
                "",
                ""
        );
        VisualRuntimeBindingRequirements.RequirementItem exported = handoffBundle.requirements().getFirst();
        VisualRuntimeBindingRequirements.RequirementItem staleRequirement =
                new VisualRuntimeBindingRequirements.RequirementItem(
                        exported.requirementKey(),
                        exported.targetKind(),
                        exported.targetId(),
                        exported.targetLabel() + " stale snapshot",
                        exported.graphName(),
                        exported.tenantId(),
                        exported.namespace(),
                        exported.environment(),
                        exported.artifactKind(),
                        exported.updatedAt(),
                        exported.nodeId(),
                        exported.operatorRef(),
                        exported.readinessState(),
                        exported.requirementState(),
                        exported.level(),
                        exported.sourceKind(),
                        exported.loweringMode(),
                        exported.bindingKind(),
                        exported.bindingTarget(),
                        exported.handoffLane(),
                        exported.handoffKind(),
                        "legacy-risk-owner",
                        exported.title(),
                        exported.summary(),
                        "Legacy runtime-plane action"
                );
        VisualRuntimeBindingHandoffBundle staleBundle = new VisualRuntimeBindingHandoffBundle(
                handoffBundle.schemaVersion(),
                handoffBundle.exportedAt(),
                handoffBundle.sourceIndexSchemaVersion(),
                handoffBundle.sourceIndexGeneratedAt(),
                handoffBundle.scope(),
                handoffBundle.filter(),
                handoffBundle.total(),
                handoffBundle.unfilteredTotal(),
                handoffBundle.displayedCount(),
                handoffBundle.itemLimit(),
                handoffBundle.offset(),
                handoffBundle.hasMore(),
                handoffBundle.requirementKeys(),
                handoffBundle.targetKindCounts(),
                handoffBundle.bindingKindCounts(),
                handoffBundle.handoffLaneCounts(),
                handoffBundle.handoffKindCounts(),
                handoffBundle.handoffTargetCounts(),
                handoffBundle.sourceKindCounts(),
                handoffBundle.loweringModeCounts(),
                handoffBundle.readinessStateCounts(),
                handoffBundle.artifactKindCounts(),
                List.of(staleRequirement)
        );

        VisualRuntimeBindingHandoffReview driftedReview = controller.reviewRuntimeBindingHandoffBundle(staleBundle)
                .getBody();

        assertThat(driftedReview).isNotNull();
        assertThat(driftedReview.reviewable()).isTrue();
        assertThat(driftedReview.state()).isEqualTo("stale");
        assertThat(driftedReview.driftedCount()).isEqualTo(1);
        assertThat(driftedReview.missingCount()).isZero();
        assertThat(driftedReview.statusCounts()).containsEntry("drifted", 1);
        assertThat(driftedReview.fieldChangeCategoryCounts())
                .containsEntry("asset-metadata", 1)
                .containsEntry("runtime-binding", 2);
        assertThat(driftedReview.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("drifted");
            assertThat(item.changedFields()).containsExactly("targetLabel", "handoffTarget", "recommendedAction");
            assertThat(item.fieldChanges())
                    .extracting(VisualRuntimeBindingHandoffReview.FieldChange::field)
                    .containsExactly("targetLabel", "handoffTarget", "recommendedAction");
            assertThat(item.fieldChanges())
                    .filteredOn(change -> change.field().equals("handoffTarget"))
                    .singleElement()
                    .satisfies(change -> {
                        assertThat(change.category()).isEqualTo("runtime-binding");
                        assertThat(change.exportedValue()).isEqualTo("legacy-risk-owner");
                        assertThat(change.currentValue()).isEqualTo("risk:eligibility");
                    });
        });
    }

    @Test
    void runtimeBindingHandoffReviewRejectsUnsupportedBundleVersion() {
        VisualRuntimeBindingHandoffBundle unsupported = new VisualRuntimeBindingHandoffBundle(
                "bloge.visualRuntimeBindingHandoff.future",
                null,
                "",
                null,
                VisualAssetOverview.AuthoringScope.all(),
                VisualRuntimeBindingRequirements.RequirementFilter.all(),
                0,
                0,
                0,
                10,
                0,
                false,
                List.of("RUNTIME_BINDING|draft|missing|node|executable-lowering|risk:eligibility|"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of()
        );
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                new InMemoryGraphDraftRepository(),
                new GraphDraftValidator(VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"))),
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer")),
                new InMemoryVisualGraphPublicationRepository()
        );

        var response = controller.reviewRuntimeBindingHandoffBundle(unsupported);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().reviewable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("invalid-bundle");
        assertThat(response.getBody().diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.error()).isTrue();
            assertThat(diagnostic.code()).isEqualTo("visual.runtimeBindingHandoff.schemaVersionUnsupported");
            assertThat(diagnostic.metadata()).containsEntry("expected", VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION);
        });
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
                .containsEntry("PLAN_DRAFT_RUNTIME_BINDING", 1)
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
