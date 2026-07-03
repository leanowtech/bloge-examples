package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryImpactReview;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryImportReadiness;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryProfile;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual asset overview APIs.
 */
class VisualAssetOverviewControllerTest {

    @Test
    void overviewAggregatesDraftPublicationAndCatalogReadiness() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
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
        assertThat(overview.drafts().operatorLibraryIdCounts()).containsEntry(library.libraryId(), 1);
        assertThat(overview.drafts().operatorRuntimeReadinessStateCounts()).containsEntry("design-only", 1);
        assertThat(GraphDraftSummary.from(drafts.history().getFirst(), draft, validation, dependencyReport)
                .actionReadiness().state())
                .isEqualTo("design-artifact-ready");
        assertThat(GraphDraftSummary.from(drafts.history().getFirst(), draft, validation, dependencyReport)
                .operatorLibraryIdCounts())
                .containsEntry(library.libraryId(), 1);
        assertThat(overview.publications().total()).isEqualTo(1);
        assertThat(overview.publications().designArtifactCount()).isEqualTo(1);
        assertThat(overview.publications().artifactKindCounts()).containsEntry("DESIGN", 1);
        assertThat(overview.publications().graphReadinessStateCounts()).containsEntry("design-only", 1);
        assertThat(overview.publications().operatorLibraryIdCounts()).containsEntry(library.libraryId(), 1);
        assertThat(VisualGraphPublicationSummary.from(publication).actionReadiness().state())
                .isEqualTo("design-artifact-ready");
        assertThat(VisualGraphPublicationSummary.from(publication).operatorLibraryIdCounts())
                .containsEntry(library.libraryId(), 1);
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
        assertThat(overview.actionQueue().operatorRefCounts())
                .containsEntry("risk:eligibility", 3);
        assertThat(overview.actionQueue().operatorLibraryIdCounts())
                .containsEntry(library.libraryId(), 3);
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
                    assertThat(item.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(item.operatorLibraryId()).isEqualTo(library.libraryId());
                    assertThat(item.targetLabel()).contains("eligibility");
                    assertThat(item.summary()).contains("executable-lowering").contains("risk:eligibility");
                    assertThat(item.handoffLane()).isEqualTo("operator-platform");
                    assertThat(item.handoffKind()).isEqualTo("operator-implementation");
                    assertThat(item.handoffTarget()).isEqualTo("risk:eligibility");
                    assertThat(item.recommendedAction()).contains("EXECUTABLE promotion");
                });
    }

    @Test
    void runtimeBindingRequirementsKeepOperatorLibraryImportRoutingSemantics() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorLibraryImportReadiness importReadiness = OperatorLibraryImportReadiness.from(
                true,
                List.of(),
                OperatorLibraryImpactReview.empty(),
                OperatorLibraryProfile.from(library),
                library
        );
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller =
                new VisualAssetOverviewController(drafts, validator, catalog, publications);
        VisualRuntimeBindingRequirements workspaceIndex = controller.runtimeBindingRequirements("", "", "");
        OperatorLibraryImportReadiness.RuntimeBindingRequirement importedRequirement =
                importReadiness.runtimeBindingRequirements().getFirst();

        assertThat(importReadiness.runtimeBindingRequirementCount()).isEqualTo(1);
        assertThat(workspaceIndex.total()).isEqualTo(1);
        assertThat(workspaceIndex.unfilteredTotal()).isEqualTo(1);
        assertThat(workspaceIndex.operatorRefCounts()).containsEntry(importedRequirement.operatorRef(), 1);
        assertThat(workspaceIndex.operatorLibraryIdCounts()).containsEntry(library.libraryId(), 1);
        assertThat(workspaceIndex.bindingKindCounts()).isEqualTo(importReadiness.bindingKindCounts());
        assertThat(workspaceIndex.handoffLaneCounts()).isEqualTo(importReadiness.handoffLaneCounts());
        assertThat(workspaceIndex.handoffKindCounts()).isEqualTo(importReadiness.handoffKindCounts());
        assertThat(workspaceIndex.handoffTargetCounts()).isEqualTo(importReadiness.handoffTargetCounts());
        assertThat(workspaceIndex.sourceKindCounts()).isEqualTo(importReadiness.sourceKindCounts());
        assertThat(workspaceIndex.loweringModeCounts()).isEqualTo(importReadiness.loweringModeCounts());
        assertThat(workspaceIndex.readinessStateCounts()).isEqualTo(importReadiness.readinessStateCounts());
        assertThat(workspaceIndex.items()).singleElement().satisfies(item -> {
            assertThat(item.targetKind()).isEqualTo("draft");
            assertThat(item.targetId()).isEqualTo(draft.draftId());
            assertThat(item.nodeId()).isEqualTo("eligibility");
            assertThat(item.operatorRef()).isEqualTo(importedRequirement.operatorRef());
            assertThat(item.operatorLibraryId()).isEqualTo(library.libraryId());
            assertThat(item.requirementState()).isEqualTo(importedRequirement.state());
            assertThat(item.level()).isEqualTo(importedRequirement.level());
            assertThat(item.sourceKind()).isEqualTo(importedRequirement.sourceKind());
            assertThat(item.loweringMode()).isEqualTo(importedRequirement.loweringMode());
            assertThat(item.bindingKind()).isEqualTo(importedRequirement.bindingKind());
            assertThat(item.bindingTarget()).isEqualTo(importedRequirement.bindingTarget());
            assertThat(item.handoffLane()).isEqualTo(importedRequirement.handoffLane());
            assertThat(item.handoffKind()).isEqualTo(importedRequirement.handoffKind());
            assertThat(item.handoffTarget()).isEqualTo(importedRequirement.handoffTarget());
            assertThat(item.title()).isEqualTo(importedRequirement.title());
            assertThat(item.summary()).isEqualTo(importedRequirement.summary());
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
        VisualRuntimeBindingRequirements byOperatorRef = controller.runtimeBindingRequirements(
                "",
                "",
                "",
                10,
                0,
                "",
                "risk:eligibility",
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
        VisualRuntimeBindingRequirements byOperatorLibrary = controller.runtimeBindingRequirements(
                "",
                "",
                "",
                10,
                0,
                "",
                "",
                "risk-policy-design",
                "",
                "",
                "",
                "",
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
        VisualRuntimeBindingHandoffBundle sameMaterialDifferentExportTime = new VisualRuntimeBindingHandoffBundle(
                handoffBundle.schemaVersion(),
                Instant.EPOCH,
                handoffBundle.sourceIndexSchemaVersion(),
                Instant.EPOCH,
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
                handoffBundle.operatorRefCounts(),
                handoffBundle.operatorLibraryIdCounts(),
                handoffBundle.bindingKindCounts(),
                handoffBundle.handoffLaneCounts(),
                handoffBundle.handoffKindCounts(),
                handoffBundle.handoffTargetCounts(),
                handoffBundle.sourceKindCounts(),
                handoffBundle.loweringModeCounts(),
                handoffBundle.readinessStateCounts(),
                handoffBundle.artifactKindCounts(),
                handoffBundle.requirements()
        );
        VisualRuntimeBindingHandoffBundle mismatchedFingerprintBundle = new VisualRuntimeBindingHandoffBundle(
                handoffBundle.schemaVersion(),
                handoffBundle.exportedAt(),
                handoffBundle.sourceIndexSchemaVersion(),
                handoffBundle.sourceIndexGeneratedAt(),
                "sha256:forged",
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
                handoffBundle.operatorRefCounts(),
                handoffBundle.operatorLibraryIdCounts(),
                handoffBundle.bindingKindCounts(),
                handoffBundle.handoffLaneCounts(),
                handoffBundle.handoffKindCounts(),
                handoffBundle.handoffTargetCounts(),
                handoffBundle.sourceKindCounts(),
                handoffBundle.loweringModeCounts(),
                handoffBundle.readinessStateCounts(),
                handoffBundle.artifactKindCounts(),
                handoffBundle.requirements()
        );
        VisualRuntimeBindingHandoffBundle legacyFingerprintBundle = new VisualRuntimeBindingHandoffBundle(
                handoffBundle.schemaVersion(),
                handoffBundle.exportedAt(),
                handoffBundle.sourceIndexSchemaVersion(),
                handoffBundle.sourceIndexGeneratedAt(),
                legacyHandoffFingerprint(handoffBundle),
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
                handoffBundle.requirements()
        );
        var mismatchedFingerprintResponse =
                controller.reviewRuntimeBindingHandoffBundle(mismatchedFingerprintBundle);
        var legacyFingerprintResponse =
                controller.reviewRuntimeBindingHandoffBundle(legacyFingerprintBundle);
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
        assertThat(firstPage.operatorRefCounts()).containsEntry("risk:eligibility", 2);
        assertThat(firstPage.operatorLibraryIdCounts()).containsEntry("risk-policy-design", 2);
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
            assertThat(item.operatorLibraryId()).isEqualTo("risk-policy-design");
            assertThat(item.bindingKind()).isEqualTo("executable-lowering");
            assertThat(item.bindingTarget()).isEqualTo("risk:eligibility");
            assertThat(item.handoffLane()).isEqualTo("operator-platform");
            assertThat(item.handoffKind()).isEqualTo("operator-implementation");
            assertThat(item.handoffTarget()).isEqualTo("risk:eligibility");
            assertThat(item.recommendedAction()).contains("EXECUTABLE promotion");
        });
        assertThat(draftOnly.filter().filtered()).isTrue();
        assertThat(draftOnly.filter().targetKind()).isEqualTo("draft");
        assertThat(draftOnly.filter().operatorRef()).isEmpty();
        assertThat(draftOnly.filter().bindingKind()).isEqualTo("executable-lowering");
        assertThat(draftOnly.filter().handoffLane()).isEqualTo("operator-platform");
        assertThat(draftOnly.filter().handoffKind()).isEqualTo("operator-implementation");
        assertThat(draftOnly.filter().handoffTarget()).isEqualTo("risk:eligibility");
        assertThat(draftOnly.total()).isEqualTo(1);
        assertThat(draftOnly.unfilteredTotal()).isEqualTo(2);
        assertThat(draftOnly.items()).singleElement()
                .extracting(VisualRuntimeBindingRequirements.RequirementItem::targetId)
                .isEqualTo(draft.draftId());
        assertThat(byOperatorRef.filter().filtered()).isTrue();
        assertThat(byOperatorRef.filter().operatorRef()).isEqualTo("risk:eligibility");
        assertThat(byOperatorRef.total()).isEqualTo(2);
        assertThat(byOperatorRef.unfilteredTotal()).isEqualTo(2);
        assertThat(byOperatorRef.operatorRefCounts()).containsEntry("risk:eligibility", 2);
        assertThat(byOperatorLibrary.filter().filtered()).isTrue();
        assertThat(byOperatorLibrary.filter().operatorLibraryId()).isEqualTo("risk-policy-design");
        assertThat(byOperatorLibrary.total()).isEqualTo(2);
        assertThat(byOperatorLibrary.unfilteredTotal()).isEqualTo(2);
        assertThat(byOperatorLibrary.operatorLibraryIdCounts()).containsEntry("risk-policy-design", 2);
        assertThat(byOperatorLibrary.items())
                .extracting(VisualRuntimeBindingRequirements.RequirementItem::operatorLibraryId)
                .containsOnly("risk-policy-design");
        assertThat(byRequirementKey.filter().filtered()).isTrue();
        assertThat(byRequirementKey.filter().requirementKey()).isEqualTo(draftRequirementKey);
        assertThat(byRequirementKey.total()).isEqualTo(1);
        assertThat(byRequirementKey.unfilteredTotal()).isEqualTo(2);
        assertThat(byRequirementKey.items()).singleElement()
                .extracting(VisualRuntimeBindingRequirements.RequirementItem::requirementKey)
                .isEqualTo(draftRequirementKey);
        assertThat(handoffBundle.schemaVersion()).isEqualTo(VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION);
        assertThat(handoffBundle.bundleFingerprint()).startsWith("sha256:");
        assertThat(handoffBundle.bundleFingerprint()).hasSize(71);
        assertThat(handoffBundle.bundleFingerprintVerified()).isTrue();
        assertThat(sameMaterialDifferentExportTime.bundleFingerprint()).isEqualTo(handoffBundle.bundleFingerprint());
        assertThat(mismatchedFingerprintBundle.bundleFingerprint()).isEqualTo("sha256:forged");
        assertThat(mismatchedFingerprintBundle.computedBundleFingerprint()).isEqualTo(handoffBundle.bundleFingerprint());
        assertThat(mismatchedFingerprintBundle.bundleFingerprintVerified()).isFalse();
        assertThat(mismatchedFingerprintResponse.getStatusCode().value()).isEqualTo(400);
        assertThat(mismatchedFingerprintResponse.getBody()).isNotNull();
        assertThat(mismatchedFingerprintResponse.getBody().reviewable()).isFalse();
        assertThat(mismatchedFingerprintResponse.getBody().sourceBundleFingerprint()).isEqualTo("sha256:forged");
        assertThat(mismatchedFingerprintResponse.getBody().diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("visual.runtimeBindingHandoff.fingerprintMismatch");
            assertThat(diagnostic.target()).isEqualTo("/bundleFingerprint");
            assertThat(diagnostic.metadata()).containsEntry("actual", "sha256:forged")
                    .containsEntry("expected", handoffBundle.bundleFingerprint());
        });
        assertThat(legacyFingerprintBundle.bundleFingerprint()).isNotEqualTo(handoffBundle.bundleFingerprint());
        assertThat(legacyFingerprintBundle.bundleFingerprintVerified()).isTrue();
        assertThat(legacyFingerprintResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(legacyFingerprintResponse.getBody()).isNotNull();
        assertThat(legacyFingerprintResponse.getBody().reviewable()).isTrue();
        assertThat(legacyFingerprintResponse.getBody().state()).isEqualTo("current");
        assertThat(handoffBundle.sourceIndexSchemaVersion())
                .isEqualTo(VisualRuntimeBindingRequirements.SCHEMA_VERSION);
        assertThat(handoffBundle.filter().targetKind()).isEqualTo("draft");
        assertThat(handoffBundle.filter().operatorRef()).isEmpty();
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
        assertThat(handoffBundle.operatorRefCounts()).containsEntry("risk:eligibility", 1);
        assertThat(handoffBundle.operatorLibraryIdCounts()).containsEntry("risk-policy-design", 1);
        assertThat(handoffBundle.handoffKindCounts()).containsEntry("operator-implementation", 1);
        assertThat(handoffBundle.handoffTargetCounts()).containsEntry("risk:eligibility", 1);
        assertThat(handoffBundle.requirements()).singleElement().satisfies(item -> {
            assertThat(item.requirementKey()).isEqualTo(draftRequirementKey);
            assertThat(item.targetKind()).isEqualTo("draft");
            assertThat(item.targetId()).isEqualTo(draft.draftId());
            assertThat(item.operatorRef()).isEqualTo("risk:eligibility");
            assertThat(item.operatorLibraryId()).isEqualTo("risk-policy-design");
        });
        assertThat(currentReview).isNotNull();
        assertThat(currentReview.schemaVersion()).isEqualTo(VisualRuntimeBindingHandoffReview.SCHEMA_VERSION);
        assertThat(currentReview.reviewable()).isTrue();
        assertThat(currentReview.state()).isEqualTo("current");
        assertThat(currentReview.level()).isEqualTo("success");
        assertThat(currentReview.sourceBundleSchemaVersion())
                .isEqualTo(VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION);
        assertThat(currentReview.sourceBundleFingerprint()).isEqualTo(handoffBundle.bundleFingerprint());
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
                        exported.operatorLibraryId(),
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

        assertThat(staleBundle.bundleFingerprint()).startsWith("sha256:");
        assertThat(staleBundle.bundleFingerprint()).isNotEqualTo(handoffBundle.bundleFingerprint());
        assertThat(driftedReview).isNotNull();
        assertThat(driftedReview.reviewable()).isTrue();
        assertThat(driftedReview.state()).isEqualTo("stale");
        assertThat(driftedReview.sourceBundleFingerprint()).isEqualTo(staleBundle.bundleFingerprint());
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
        assertThat(response.getBody().sourceBundleFingerprint()).isEqualTo(unsupported.bundleFingerprint());
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
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
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
        VisualAssetOverview byOperatorRef = controller.overview(
                "",
                "",
                "",
                10,
                0,
                "",
                "",
                "",
                "risk:eligibility"
        );
        VisualAssetOverview byOperatorLibrary = controller.overview(
                "",
                "",
                "",
                10,
                0,
                "",
                "",
                "",
                "",
                library.libraryId()
        );

        assertThat(operatorOnly.actionQueue().unfilteredTotal()).isEqualTo(unfilteredTotal);
        assertThat(operatorOnly.actionQueue().total()).isEqualTo(1);
        assertThat(operatorOnly.actionQueue().displayedCount()).isEqualTo(1);
        assertThat(operatorOnly.actionQueue().hasMore()).isFalse();
        assertThat(operatorOnly.actionQueue().filter().filtered()).isTrue();
        assertThat(operatorOnly.actionQueue().filter().severity()).isEqualTo("info");
        assertThat(operatorOnly.actionQueue().filter().actionType()).isEqualTo("TRACK_SCHEMA_ONLY_OPERATOR");
        assertThat(operatorOnly.actionQueue().filter().targetKind()).isEqualTo("operator");
        assertThat(operatorOnly.actionQueue().filter().operatorRef()).isEmpty();
        assertThat(operatorOnly.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::actionType)
                .containsExactly("TRACK_SCHEMA_ONLY_OPERATOR");
        assertThat(byOperatorRef.actionQueue().unfilteredTotal()).isEqualTo(unfilteredTotal);
        assertThat(byOperatorRef.actionQueue().total()).isEqualTo(3);
        assertThat(byOperatorRef.actionQueue().filter().operatorRef()).isEqualTo("risk:eligibility");
        assertThat(byOperatorRef.actionQueue().filter().operatorLibraryId()).isEmpty();
        assertThat(byOperatorRef.actionQueue().operatorRefCounts()).containsEntry("risk:eligibility", 3);
        assertThat(byOperatorRef.actionQueue().operatorLibraryIdCounts()).containsEntry(library.libraryId(), 3);
        assertThat(byOperatorRef.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::operatorRef)
                .containsOnly("risk:eligibility");
        assertThat(byOperatorRef.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::operatorLibraryId)
                .containsOnly(library.libraryId());
        assertThat(byOperatorRef.actionQueue().actionTypeCounts())
                .containsEntry("PLAN_DRAFT_RUNTIME_BINDING", 1)
                .containsEntry("PLAN_PUBLICATION_RUNTIME_BINDING", 1)
                .containsEntry("TRACK_SCHEMA_ONLY_OPERATOR", 1);
        assertThat(byOperatorLibrary.actionQueue().unfilteredTotal()).isEqualTo(unfilteredTotal);
        assertThat(byOperatorLibrary.actionQueue().total()).isEqualTo(3);
        assertThat(byOperatorLibrary.actionQueue().filter().operatorRef()).isEmpty();
        assertThat(byOperatorLibrary.actionQueue().filter().operatorLibraryId()).isEqualTo(library.libraryId());
        assertThat(byOperatorLibrary.actionQueue().operatorRefCounts()).containsEntry("risk:eligibility", 3);
        assertThat(byOperatorLibrary.actionQueue().operatorLibraryIdCounts()).containsEntry(library.libraryId(), 3);
        assertThat(byOperatorLibrary.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::operatorLibraryId)
                .containsOnly(library.libraryId());
        assertThat(byOperatorLibrary.actionQueue().actionTypeCounts())
                .containsEntry("PLAN_DRAFT_RUNTIME_BINDING", 1)
                .containsEntry("PLAN_PUBLICATION_RUNTIME_BINDING", 1)
                .containsEntry("TRACK_SCHEMA_ONLY_OPERATOR", 1);
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

    private static String legacyHandoffFingerprint(VisualRuntimeBindingHandoffBundle bundle) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", bundle.schemaVersion());
        material.put("sourceIndexSchemaVersion", bundle.sourceIndexSchemaVersion());
        material.put("scope", bundle.scope());
        material.put("filter", legacyFilterMaterial(bundle.filter()));
        material.put("total", bundle.total());
        material.put("unfilteredTotal", bundle.unfilteredTotal());
        material.put("displayedCount", bundle.displayedCount());
        material.put("itemLimit", bundle.itemLimit());
        material.put("offset", bundle.offset());
        material.put("hasMore", bundle.hasMore());
        material.put("requirementKeys", bundle.requirementKeys());
        material.put("targetKindCounts", bundle.targetKindCounts());
        material.put("bindingKindCounts", bundle.bindingKindCounts());
        material.put("handoffLaneCounts", bundle.handoffLaneCounts());
        material.put("handoffKindCounts", bundle.handoffKindCounts());
        material.put("handoffTargetCounts", bundle.handoffTargetCounts());
        material.put("sourceKindCounts", bundle.sourceKindCounts());
        material.put("loweringModeCounts", bundle.loweringModeCounts());
        material.put("readinessStateCounts", bundle.readinessStateCounts());
        material.put("artifactKindCounts", bundle.artifactKindCounts());
        material.put("requirements", legacyRequirementMaterials(bundle.requirements()));
        return VisualBundleFingerprint.fromMaterial(material);
    }

    private static List<Map<String, Object>> legacyRequirementMaterials(
            List<VisualRuntimeBindingRequirements.RequirementItem> requirements) {
        return requirements.stream()
                .map(VisualAssetOverviewControllerTest::legacyRequirementMaterial)
                .toList();
    }

    private static Map<String, Object> legacyRequirementMaterial(
            VisualRuntimeBindingRequirements.RequirementItem item) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("requirementKey", item.requirementKey());
        material.put("targetKind", item.targetKind());
        material.put("targetId", item.targetId());
        material.put("targetLabel", item.targetLabel());
        material.put("graphName", item.graphName());
        material.put("tenantId", item.tenantId());
        material.put("namespace", item.namespace());
        material.put("environment", item.environment());
        material.put("artifactKind", item.artifactKind());
        material.put("updatedAt", item.updatedAt());
        material.put("nodeId", item.nodeId());
        material.put("operatorRef", item.operatorRef());
        material.put("readinessState", item.readinessState());
        material.put("requirementState", item.requirementState());
        material.put("level", item.level());
        material.put("sourceKind", item.sourceKind());
        material.put("loweringMode", item.loweringMode());
        material.put("bindingKind", item.bindingKind());
        material.put("bindingTarget", item.bindingTarget());
        material.put("handoffLane", item.handoffLane());
        material.put("handoffKind", item.handoffKind());
        material.put("handoffTarget", item.handoffTarget());
        material.put("title", item.title());
        material.put("summary", item.summary());
        material.put("recommendedAction", item.recommendedAction());
        return material;
    }

    private static Map<String, Object> legacyFilterMaterial(
            VisualRuntimeBindingRequirements.RequirementFilter filter) {
        VisualRuntimeBindingRequirements.RequirementFilter safeFilter = filter == null
                ? VisualRuntimeBindingRequirements.RequirementFilter.all()
                : filter;
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("targetKind", safeFilter.targetKind());
        material.put("bindingKind", safeFilter.bindingKind());
        material.put("handoffLane", safeFilter.handoffLane());
        material.put("handoffKind", safeFilter.handoffKind());
        material.put("handoffTarget", safeFilter.handoffTarget());
        material.put("sourceKind", safeFilter.sourceKind());
        material.put("loweringMode", safeFilter.loweringMode());
        material.put("readinessState", safeFilter.readinessState());
        material.put("requirementKey", safeFilter.requirementKey());
        material.put("filtered", safeFilter.filtered());
        return material;
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
