package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.JavaOperatorInventoryProjector;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryImpactReview;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryImportReadiness;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryProfile;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRevision;
import com.leanowtech.bloge.gateway.visual.catalog.ResourceVirtualOperatorProjector;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.VisualGraphPublicationOperatorProjector;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationSummary;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualExecutableLoweringIntegrationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualRuntimeRolloutObservationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegration;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableLoweringIntegrationValidation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableReadinessEvidenceRefreshResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableReadinessRecomputePreview;
import com.leanowtech.bloge.gateway.visual.runtime.VisualExecutableReadinessRecomputeResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationValidation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeBindingDeactivationResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeRolloutObservation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeRolloutObservationValidation;
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
        assertThat(GraphDraftSummary.from(drafts.history().getFirst(), draft, validation, dependencyReport)
                .operatorLibraryIdsByOperatorRef())
                .containsEntry("risk:eligibility", library.libraryId());
        assertThat(overview.publications().total()).isEqualTo(1);
        assertThat(overview.publications().designArtifactCount()).isEqualTo(1);
        assertThat(overview.publications().artifactKindCounts()).containsEntry("DESIGN", 1);
        assertThat(overview.publications().graphReadinessStateCounts()).containsEntry("design-only", 1);
        assertThat(overview.publications().operatorLibraryIdCounts()).containsEntry(library.libraryId(), 1);
        assertThat(VisualGraphPublicationSummary.from(publication).actionReadiness().state())
                .isEqualTo("design-artifact-ready");
        assertThat(VisualGraphPublicationSummary.from(publication).operatorLibraryIdCounts())
                .containsEntry(library.libraryId(), 1);
        assertThat(VisualGraphPublicationSummary.from(publication).operatorLibraryIdsByOperatorRef())
                .containsEntry("risk:eligibility", library.libraryId());
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
    void runtimeBindingRoutingFallsBackToFrozenOperatorLibraryOwnersWhenCatalogMapIsMissing() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft draft = drafts.save(draftWithFingerprint(operator)
                .withOperatorSnapshots(Map.of("eligibility", operator)));
        VisualValidationResult validation = validator.validate(draft);
        GraphDraftDependencyReport dependencyReport = GraphDraftDependencyReport.from(draft, catalog);
        GraphDraftSummary draftSummary =
                GraphDraftSummary.from(drafts.history().getFirst(), draft, validation, dependencyReport);
        VisualGraphPublication publication = publications.create(VisualGraphPublication.design(
                draft,
                List.of(operator),
                validation,
                new DslGenerationResult(false, "", List.of()),
                dependencyReport
        ));
        VisualGraphPublicationSummary publicationSummary = VisualGraphPublicationSummary.from(publication);
        String targetCatalogOwner = "target-risk-library";

        VisualRuntimeBindingRequirements fallbackIndex = VisualRuntimeBindingRequirements.from(
                List.of(draftSummary),
                List.of(publicationSummary),
                Map.of(),
                "",
                "",
                "",
                10,
                0,
                "",
                "",
                library.libraryId(),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
        VisualAssetOverview fallbackOverview = VisualAssetOverview.from(
                List.of(draftSummary),
                List.of(publicationSummary),
                List.of(),
                List.of(),
                Map.of(),
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
        VisualRuntimeBindingRequirements overriddenIndex = VisualRuntimeBindingRequirements.from(
                List.of(draftSummary),
                List.of(publicationSummary),
                Map.of("risk:eligibility", targetCatalogOwner),
                "",
                "",
                "",
                10,
                0,
                "",
                "",
                targetCatalogOwner,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
        VisualAssetOverview overriddenOverview = VisualAssetOverview.from(
                List.of(draftSummary),
                List.of(publicationSummary),
                List.of(),
                List.of(),
                Map.of("risk:eligibility", targetCatalogOwner),
                "",
                "",
                "",
                10,
                0,
                "",
                "",
                "",
                "",
                targetCatalogOwner
        );

        assertThat(draftSummary.operatorLibraryIdsByOperatorRef())
                .containsEntry("risk:eligibility", library.libraryId());
        assertThat(publicationSummary.operatorLibraryIdsByOperatorRef())
                .containsEntry("risk:eligibility", library.libraryId());
        assertThat(fallbackIndex.filter().operatorLibraryId()).isEqualTo(library.libraryId());
        assertThat(fallbackIndex.total()).isEqualTo(2);
        assertThat(fallbackIndex.operatorLibraryIdCounts()).containsEntry(library.libraryId(), 2);
        assertThat(fallbackIndex.items())
                .extracting(VisualRuntimeBindingRequirements.RequirementItem::operatorLibraryId)
                .containsOnly(library.libraryId());
        assertThat(fallbackOverview.actionQueue().filter().operatorLibraryId()).isEqualTo(library.libraryId());
        assertThat(fallbackOverview.actionQueue().total()).isEqualTo(2);
        assertThat(fallbackOverview.actionQueue().operatorLibraryIdCounts()).containsEntry(library.libraryId(), 2);
        assertThat(fallbackOverview.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::operatorLibraryId)
                .containsOnly(library.libraryId());
        assertThat(overriddenIndex.filter().operatorLibraryId()).isEqualTo(targetCatalogOwner);
        assertThat(overriddenIndex.total()).isEqualTo(2);
        assertThat(overriddenIndex.operatorLibraryIdCounts()).containsEntry(targetCatalogOwner, 2);
        assertThat(overriddenIndex.items())
                .extracting(VisualRuntimeBindingRequirements.RequirementItem::operatorLibraryId)
                .containsOnly(targetCatalogOwner);
        assertThat(overriddenOverview.actionQueue().filter().operatorLibraryId()).isEqualTo(targetCatalogOwner);
        assertThat(overriddenOverview.actionQueue().total()).isEqualTo(2);
        assertThat(overriddenOverview.actionQueue().operatorLibraryIdCounts()).containsEntry(targetCatalogOwner, 2);
        assertThat(overriddenOverview.actionQueue().items())
                .extracting(VisualAssetOverview.ActionItem::operatorLibraryId)
                .containsOnly(targetCatalogOwner);
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
        String publicationRequirementKey =
                "RUNTIME_BINDING|publication|%s|eligibility|executable-lowering|risk:eligibility|DESIGN"
                        .formatted(publication.publicationId());
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
        VisualRuntimeBindingRequirements designArtifacts = controller.runtimeBindingRequirements(
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
                "",
                "",
                "",
                "design"
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
        VisualRuntimeBindingHandoffBundle designHandoffBundle = controller.runtimeBindingHandoffBundle(
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
                "",
                "",
                "",
                "DESIGN"
        );
        VisualRuntimeBindingHandoffReview currentReview = controller.reviewRuntimeBindingHandoffBundle(handoffBundle)
                .getBody();
        VisualRuntimeBindingHandoffReview designReview =
                controller.reviewRuntimeBindingHandoffBundle(designHandoffBundle).getBody();
        VisualRuntimeBindingHandoffBundle sameMaterialDifferentExportTime = new VisualRuntimeBindingHandoffBundle(
                handoffBundle.schemaVersion(),
                Instant.EPOCH,
                handoffBundle.sourceIndexSchemaVersion(),
                Instant.EPOCH,
                "",
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
                handoffBundle.operatorContracts(),
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
                handoffBundle.operatorContracts(),
                handoffBundle.requirements()
        );
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract = handoffBundle.operatorContracts()
                .getFirst();
        VisualRuntimeBindingHandoffBundle tamperedContractBundle = new VisualRuntimeBindingHandoffBundle(
                handoffBundle.schemaVersion(),
                handoffBundle.exportedAt(),
                handoffBundle.sourceIndexSchemaVersion(),
                handoffBundle.sourceIndexGeneratedAt(),
                handoffBundle.bundleFingerprint(),
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
                List.of(new VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot(
                        contract.operatorRef(),
                        contract.operatorVersion(),
                        "sha256:tampered",
                        contract.operatorLibraryId(),
                        contract.display(),
                        contract.source(),
                        contract.ports(),
                        contract.configSchema(),
                        contract.capabilities(),
                        contract.policy(),
                        contract.lowering(),
                        contract.runtimeReadiness()
                )),
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
        var tamperedContractResponse =
                controller.reviewRuntimeBindingHandoffBundle(tamperedContractBundle);
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
        assertThat(designArtifacts.filter().filtered()).isTrue();
        assertThat(designArtifacts.filter().artifactKind()).isEqualTo("DESIGN");
        assertThat(designArtifacts.total()).isEqualTo(1);
        assertThat(designArtifacts.unfilteredTotal()).isEqualTo(2);
        assertThat(designArtifacts.targetKindCounts()).containsEntry("publication", 1);
        assertThat(designArtifacts.artifactKindCounts()).containsEntry("DESIGN", 1);
        assertThat(designArtifacts.items()).singleElement().satisfies(item -> {
            assertThat(item.requirementKey()).isEqualTo(publicationRequirementKey);
            assertThat(item.targetKind()).isEqualTo("publication");
            assertThat(item.targetId()).isEqualTo(publication.publicationId());
            assertThat(item.artifactKind()).isEqualTo("DESIGN");
        });
        assertThat(handoffBundle.schemaVersion()).isEqualTo(VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION);
        assertThat(handoffBundle.bundleFingerprint()).startsWith("sha256:");
        assertThat(handoffBundle.bundleFingerprint()).hasSize(71);
        assertThat(handoffBundle.bundleFingerprintVerified()).isTrue();
        assertThat(designHandoffBundle.filter().artifactKind()).isEqualTo("DESIGN");
        assertThat(designHandoffBundle.total()).isEqualTo(1);
        assertThat(designHandoffBundle.unfilteredTotal()).isEqualTo(2);
        assertThat(designHandoffBundle.requirementKeys()).containsExactly(publicationRequirementKey);
        assertThat(designHandoffBundle.artifactKindCounts()).containsEntry("DESIGN", 1);
        assertThat(designHandoffBundle.requirements()).singleElement()
                .extracting(VisualRuntimeBindingRequirements.RequirementItem::artifactKind)
                .isEqualTo("DESIGN");
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
        assertThat(tamperedContractBundle.computedBundleFingerprint()).isNotEqualTo(handoffBundle.bundleFingerprint());
        assertThat(tamperedContractBundle.bundleFingerprintVerified()).isFalse();
        assertThat(tamperedContractResponse.getStatusCode().value()).isEqualTo(400);
        assertThat(tamperedContractResponse.getBody()).isNotNull();
        assertThat(tamperedContractResponse.getBody().diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("visual.runtimeBindingHandoff.fingerprintMismatch");
            assertThat(diagnostic.target()).isEqualTo("/bundleFingerprint");
            assertThat(diagnostic.metadata()).containsEntry("actual", handoffBundle.bundleFingerprint())
                    .containsEntry("expected", tamperedContractBundle.computedBundleFingerprint());
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
        assertThat(handoffBundle.operatorContracts()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.operatorRef()).isEqualTo("risk:eligibility");
            assertThat(snapshot.operatorVersion()).isEqualTo("1.0.0");
            assertThat(snapshot.fingerprint()).isEqualTo(operator.fingerprint());
            assertThat(snapshot.operatorLibraryId()).isEqualTo("risk-policy-design");
            assertThat(snapshot.display().name()).isEqualTo("Eligibility");
            assertThat(snapshot.source().kind()).isEqualTo("user-library");
            assertThat(snapshot.source().libraryId()).isEqualTo("risk-policy-design");
            assertThat(snapshot.lowering().mode()).isEqualTo("design");
            assertThat(snapshot.runtimeReadiness().state()).isEqualTo("DESIGN_ONLY");
            assertThat(snapshot.runtimeReadiness().artifactKinds()).containsExactly("DESIGN");
            assertThat(snapshot.ports().inputs()).singleElement().satisfies(port -> {
                assertThat(port.name()).isEqualTo("inputs");
                assertThat(port.schema().properties()).containsKeys("score", "amount");
                assertThat(port.schema().required()).containsExactly("score", "amount");
            });
            assertThat(snapshot.ports().outputs()).singleElement().satisfies(port ->
                    assertThat(port.schema().properties()).containsKeys("eligible", "ruleId"));
            assertThat(snapshot.configSchema().format()).isEqualTo(SchemaEnvelope.JSON_SCHEMA);
        });
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
        assertThat(currentReview.exportedOperatorContractCount()).isEqualTo(1);
        assertThat(currentReview.currentWindowTotal()).isEqualTo(1);
        assertThat(currentReview.currentWindowDisplayedCount()).isEqualTo(1);
        assertThat(currentReview.matchedCount()).isEqualTo(1);
        assertThat(currentReview.driftedCount()).isZero();
        assertThat(currentReview.missingCount()).isZero();
        assertThat(currentReview.newCurrentWindowCount()).isZero();
        assertThat(currentReview.exportedRequirementKeys()).containsExactly(draftRequirementKey);
        assertThat(currentReview.currentWindowRequirementKeys()).containsExactly(draftRequirementKey);
        assertThat(currentReview.statusCounts()).containsEntry("current", 1);
        assertThat(currentReview.exportedWindowDistribution().requirementCount()).isEqualTo(1);
        assertThat(currentReview.exportedWindowDistribution().operatorRefCounts())
                .containsEntry("risk:eligibility", 1);
        assertThat(currentReview.exportedWindowDistribution().operatorLibraryIdCounts())
                .containsEntry("risk-policy-design", 1);
        assertThat(currentReview.exportedWindowDistribution().handoffLaneCounts())
                .containsEntry("operator-platform", 1);
        assertThat(currentReview.currentWindowDistribution().requirementCount()).isEqualTo(1);
        assertThat(currentReview.currentWindowDistribution().operatorLibraryIdCounts())
                .containsEntry("risk-policy-design", 1);
        assertThat(currentReview.currentWindowDistribution().handoffKindCounts())
                .containsEntry("operator-implementation", 1);
        assertThat(currentReview.newCurrentWindowDistribution().requirementCount()).isZero();
        assertThat(currentReview.items()).singleElement().satisfies(item -> {
            assertThat(item.requirementKey()).isEqualTo(draftRequirementKey);
            assertThat(item.status()).isEqualTo("current");
            assertThat(item.changedFields()).isEmpty();
            assertThat(item.currentRequirement()).isNotNull();
        });
        assertThat(designReview).isNotNull();
        assertThat(designReview.state()).isEqualTo("current");
        assertThat(designReview.filter().artifactKind()).isEqualTo("DESIGN");
        assertThat(designReview.exportedRequirementKeys()).containsExactly(publicationRequirementKey);
        assertThat(designReview.currentWindowRequirementKeys()).containsExactly(publicationRequirementKey);
        assertThat(designReview.currentWindowDistribution().artifactKindCounts())
                .containsEntry("DESIGN", 1);
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
        assertThat(staleReview.exportedOperatorContractCount()).isEqualTo(1);
        assertThat(staleReview.currentWindowTotal()).isZero();
        assertThat(staleReview.matchedCount()).isZero();
        assertThat(staleReview.driftedCount()).isZero();
        assertThat(staleReview.missingCount()).isEqualTo(1);
        assertThat(staleReview.statusCounts()).containsEntry("missing", 1);
        assertThat(staleReview.exportedWindowDistribution().requirementCount()).isEqualTo(1);
        assertThat(staleReview.exportedWindowDistribution().operatorLibraryIdCounts())
                .containsEntry("risk-policy-design", 1);
        assertThat(staleReview.currentWindowDistribution().requirementCount()).isZero();
        assertThat(staleReview.newCurrentWindowDistribution().requirementCount()).isZero();
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
        assertThat(driftedReview.exportedWindowDistribution().handoffTargetCounts())
                .containsEntry("legacy-risk-owner", 1);
        assertThat(driftedReview.currentWindowDistribution().handoffTargetCounts())
                .containsEntry("risk:eligibility", 1);
        assertThat(driftedReview.currentWindowDistribution().operatorLibraryIdCounts())
                .containsEntry("risk-policy-design", 1);
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
    void runtimeBindingHandoffReviewDetectsStaleOperatorContractSnapshots() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingHandoffBundle handoffBundle = fixture.handoffBundle();
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot exportedContract = fixture.contract();
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot staleContract =
                new VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot(
                        exportedContract.operatorRef(),
                        exportedContract.operatorVersion(),
                        "sha256:exported-stale-contract",
                        exportedContract.operatorLibraryId(),
                        exportedContract.display(),
                        exportedContract.source(),
                        exportedContract.ports(),
                        exportedContract.configSchema(),
                        exportedContract.capabilities(),
                        exportedContract.policy(),
                        exportedContract.lowering(),
                        exportedContract.runtimeReadiness()
                );
        VisualRuntimeBindingHandoffBundle staleContractBundle = new VisualRuntimeBindingHandoffBundle(
                handoffBundle.schemaVersion(),
                handoffBundle.exportedAt(),
                handoffBundle.sourceIndexSchemaVersion(),
                handoffBundle.sourceIndexGeneratedAt(),
                "",
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
                List.of(staleContract),
                handoffBundle.requirements()
        );

        VisualRuntimeBindingHandoffReview review = fixture.controller()
                .reviewRuntimeBindingHandoffBundle(staleContractBundle)
                .getBody();

        assertThat(staleContractBundle.bundleFingerprintVerified()).isTrue();
        assertThat(staleContractBundle.bundleFingerprint()).isNotEqualTo(handoffBundle.bundleFingerprint());
        assertThat(review).isNotNull();
        assertThat(review.reviewable()).isTrue();
        assertThat(review.state()).isEqualTo("stale");
        assertThat(review.matchedCount()).isEqualTo(1);
        assertThat(review.driftedCount()).isZero();
        assertThat(review.missingCount()).isZero();
        assertThat(review.operatorContractMatchedCount()).isZero();
        assertThat(review.operatorContractDriftedCount()).isEqualTo(1);
        assertThat(review.operatorContractMissingCount()).isZero();
        assertThat(review.operatorContractNewCurrentWindowCount()).isZero();
        assertThat(review.operatorContractStatusCounts()).containsEntry("drifted", 1);
        assertThat(review.operatorContractFieldChangeCategoryCounts()).containsEntry("operator-contract", 1);
        assertThat(review.operatorContractItems()).singleElement().satisfies(item -> {
            assertThat(item.operatorRef()).isEqualTo(exportedContract.operatorRef());
            assertThat(item.status()).isEqualTo("drifted");
            assertThat(item.changedFields()).containsExactly("fingerprint");
            assertThat(item.exportedContract()).isNotNull();
            assertThat(item.currentContract()).isNotNull();
            assertThat(item.currentContract().fingerprint()).isEqualTo(exportedContract.fingerprint());
            assertThat(item.fieldChanges()).singleElement().satisfies(change -> {
                assertThat(change.field()).isEqualTo("fingerprint");
                assertThat(change.category()).isEqualTo("operator-contract");
                assertThat(change.exportedValue()).isEqualTo("sha256:exported-stale-contract");
                assertThat(change.currentValue()).isEqualTo(exportedContract.fingerprint());
            });
        });
    }

    @Test
    void runtimeBindingHandoffReviewSummarizesNewCurrentWindowRouting() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        GraphDraft firstDraft = drafts.save(draftWithFingerprint(operator));
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

        GraphDraft secondDraft = drafts.save(draftWithFingerprint(operator));
        VisualRuntimeBindingHandoffReview review = controller.reviewRuntimeBindingHandoffBundle(handoffBundle)
                .getBody();

        assertThat(firstDraft.draftId()).isNotEqualTo(secondDraft.draftId());
        assertThat(review).isNotNull();
        assertThat(review.reviewable()).isTrue();
        assertThat(review.state()).isEqualTo("stale");
        assertThat(review.exportedRequirementCount()).isEqualTo(1);
        assertThat(review.currentWindowTotal()).isEqualTo(2);
        assertThat(review.currentWindowDisplayedCount()).isEqualTo(2);
        assertThat(review.matchedCount()).isEqualTo(1);
        assertThat(review.newCurrentWindowCount()).isEqualTo(1);
        assertThat(review.newCurrentWindowRequirementKeys()).singleElement()
                .satisfies(key -> assertThat(key).contains(secondDraft.draftId()));
        assertThat(review.exportedWindowDistribution().requirementCount()).isEqualTo(1);
        assertThat(review.currentWindowDistribution().requirementCount()).isEqualTo(2);
        assertThat(review.currentWindowDistribution().operatorRefCounts())
                .containsEntry("risk:eligibility", 2);
        assertThat(review.currentWindowDistribution().operatorLibraryIdCounts())
                .containsEntry("risk-policy-design", 2);
        assertThat(review.currentWindowDistribution().handoffLaneCounts())
                .containsEntry("operator-platform", 2);
        assertThat(review.newCurrentWindowDistribution().requirementCount()).isEqualTo(1);
        assertThat(review.newCurrentWindowDistribution().operatorRefCounts())
                .containsEntry("risk:eligibility", 1);
        assertThat(review.newCurrentWindowDistribution().operatorLibraryIdCounts())
                .containsEntry("risk-policy-design", 1);
        assertThat(review.newCurrentWindowDistribution().bindingKindCounts())
                .containsEntry("executable-lowering", 1);
        assertThat(review.newCurrentWindowDistribution().handoffKindCounts())
                .containsEntry("operator-implementation", 1);
        assertThat(review.newCurrentWindowDistribution().handoffTargetCounts())
                .containsEntry("risk:eligibility", 1);
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
        assertThat(response.getBody().exportedOperatorContractCount()).isZero();
        assertThat(response.getBody().diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.error()).isTrue();
            assertThat(diagnostic.code()).isEqualTo("visual.runtimeBindingHandoff.schemaVersionUnsupported");
            assertThat(diagnostic.metadata()).containsEntry("expected", VisualRuntimeBindingHandoffBundle.SCHEMA_VERSION);
        });
    }

    @Test
    void runtimeBindingImplementationValidationAcceptsCurrentHandoffContract() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                drafts,
                validator,
                catalog,
                new InMemoryVisualGraphPublicationRepository()
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
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract =
                handoffBundle.operatorContracts().getFirst();

        var response = controller.validateRuntimeBindingImplementation(
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        contract.operatorRef(),
                        contract.fingerprint(),
                        handoffBundle.bundleFingerprint(),
                        handoffBundle.requirementKeys(),
                        contract,
                        completeImplementation()
                ));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().schemaVersion())
                .isEqualTo(VisualRuntimeBindingImplementationValidation.SCHEMA_VERSION);
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().bindable()).isTrue();
        assertThat(response.getBody().state()).isEqualTo("ready-to-bind");
        assertThat(response.getBody().level()).isEqualTo("success");
        assertThat(response.getBody().operatorRef()).isEqualTo("risk:eligibility");
        assertThat(response.getBody().operatorFingerprint()).isEqualTo(operator.fingerprint());
        assertThat(response.getBody().contractFingerprint()).isEqualTo(operator.fingerprint());
        assertThat(response.getBody().currentCatalogFingerprint()).isEqualTo(operator.fingerprint());
        assertThat(response.getBody().currentCatalogState()).isEqualTo("current");
        assertThat(response.getBody().sourceHandoffBundleFingerprint()).isEqualTo(handoffBundle.bundleFingerprint());
        assertThat(response.getBody().implementation().adapterKind()).isEqualTo("native");
        assertThat(response.getBody().diagnostics()).isEmpty();
    }

    @Test
    void runtimeBindingImplementationValidationRequiresReviewForStaleSourceRequirementKeys() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                drafts,
                validator,
                catalog,
                new InMemoryVisualGraphPublicationRepository()
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
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract =
                handoffBundle.operatorContracts().getFirst();
        String staleRequirementKey = "RUNTIME_BINDING|draft|missing|eligibility|executable-lowering|"
                + "risk:eligibility|";

        var response = controller.validateRuntimeBindingImplementation(
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        contract.operatorRef(),
                        contract.fingerprint(),
                        handoffBundle.bundleFingerprint(),
                        List.of(staleRequirementKey),
                        contract,
                        completeImplementation()
                ));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("requires-review");
        assertThat(response.getBody().diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code())
                    .isEqualTo("visual.runtimeBindingImplementation.sourceRequirementKeyStale");
            assertThat(diagnostic.target()).isEqualTo("/sourceRequirementKeys/0");
            assertThat(diagnostic.metadata()).containsEntry("requirementKey", staleRequirementKey);
        });
    }

    @Test
    void runtimeBindingImplementationValidationRejectsSourceRequirementKeyForDifferentOperator() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                drafts,
                validator,
                catalog,
                new InMemoryVisualGraphPublicationRepository()
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
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract =
                handoffBundle.operatorContracts().getFirst();
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot otherOperatorContract =
                new VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot(
                        "risk:other",
                        contract.operatorVersion(),
                        contract.fingerprint(),
                        contract.operatorLibraryId(),
                        contract.display(),
                        contract.source(),
                        contract.ports(),
                        contract.configSchema(),
                        contract.capabilities(),
                        contract.policy(),
                        contract.lowering(),
                        contract.runtimeReadiness()
                );

        var response = controller.validateRuntimeBindingImplementation(
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        otherOperatorContract.operatorRef(),
                        otherOperatorContract.fingerprint(),
                        handoffBundle.bundleFingerprint(),
                        handoffBundle.requirementKeys(),
                        otherOperatorContract,
                        completeImplementation("risk-other-native-v1")
                ));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isFalse();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("rejected");
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.sourceRequirementOperatorMismatch"
                                .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/sourceRequirementKeys/0");
                    assertThat(diagnostic.metadata())
                            .containsEntry("actualOperatorRef", "risk:eligibility")
                            .containsEntry("expectedOperatorRef", "risk:other")
                            .containsEntry("nodeId", "eligibility");
                });
    }

    @Test
    void runtimeBindingImplementationValidationRejectsContractFingerprintMismatch() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                drafts,
                validator,
                catalog,
                new InMemoryVisualGraphPublicationRepository()
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
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract =
                handoffBundle.operatorContracts().getFirst();

        var response = controller.validateRuntimeBindingImplementation(
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        contract.operatorRef(),
                        "sha256:tampered",
                        handoffBundle.bundleFingerprint(),
                        handoffBundle.requirementKeys(),
                        contract,
                        completeImplementation()
                ));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isFalse();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("rejected");
        assertThat(response.getBody().diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("visual.runtimeBindingImplementation.fingerprintMismatch");
            assertThat(diagnostic.target()).isEqualTo("/operatorFingerprint");
            assertThat(diagnostic.metadata()).containsEntry("actual", "sha256:tampered")
                    .containsEntry("expected", operator.fingerprint());
        });
    }

    @Test
    void runtimeBindingImplementationValidationRejectsBreakingCatalogContractDiff() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        fixture.libraries().upsert(VisualCatalogTestSupport.designOnlyEligibilityLibrary("string"));

        var response = fixture.controller().validateRuntimeBindingImplementation(
                implementationRequest(fixture, completeImplementation()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isFalse();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("rejected");
        assertThat(response.getBody().currentCatalogState()).isEqualTo("drifted");
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains(
                        "visual.runtimeBindingImplementation.catalogFingerprintDrift",
                        "visual.runtimeBindingImplementation.contractDiffBreaking"
                );
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.contractDiffBreaking".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.metadata()).containsEntry("category", "breaking");
                    assertThat(String.valueOf(diagnostic.metadata().get("fields")))
                            .contains("ports.inputs.inputs.schema");
                    assertThat(String.valueOf(diagnostic.metadata().get("schemaCompatibilityIssues")))
                            .contains("ports.inputs.inputs.schema")
                            .contains("score");
                });

        var submit = fixture.controller().submitRuntimeBindingImplementation(
                implementationRequest(fixture, completeImplementation()));

        assertThat(submit.getStatusCode().value()).isEqualTo(400);
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "")).isEmpty();
    }

    @Test
    void runtimeBindingImplementationValidationRequiresReviewForCompatibleSchemaDrift() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        fixture.libraries().upsert(VisualCatalogTestSupport.designOnlyEligibilityLibrary("number"));

        var response = fixture.controller().validateRuntimeBindingImplementation(
                implementationRequest(fixture, completeImplementation()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("requires-review");
        assertThat(response.getBody().currentCatalogState()).isEqualTo("drifted");
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains(
                        "visual.runtimeBindingImplementation.catalogFingerprintDrift",
                        "visual.runtimeBindingImplementation.contractDiffCompatible"
                )
                .doesNotContain("visual.runtimeBindingImplementation.contractDiffBreaking");
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.contractDiffCompatible".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.metadata()).containsEntry("category", "compatible");
                    assertThat(String.valueOf(diagnostic.metadata().get("fields")))
                            .contains("ports.inputs.inputs.schema");
                    assertThat(diagnostic.metadata()).doesNotContainKey("schemaCompatibilityIssues");
                });

        var submit = fixture.controller().submitRuntimeBindingImplementation(
                implementationRequest(fixture, completeImplementation()));

        assertThat(submit.getStatusCode().value()).isEqualTo(201);
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "requires-review"))
                .hasSize(1);
    }

    @Test
    void runtimeBindingImplementationValidationRequiresReviewForNonBreakingContractDiffs() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        fixture.libraries().upsert(nonBreakingRuntimeDriftEligibilityLibrary());

        var response = fixture.controller().validateRuntimeBindingImplementation(
                implementationRequest(fixture, completeImplementation()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("requires-review");
        assertThat(response.getBody().currentCatalogState()).isEqualTo("drifted");
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains(
                        "visual.runtimeBindingImplementation.catalogFingerprintDrift",
                        "visual.runtimeBindingImplementation.contractDiffCompatible",
                        "visual.runtimeBindingImplementation.contractDiffRuntime",
                        "visual.runtimeBindingImplementation.contractDiffGovernance",
                        "visual.runtimeBindingImplementation.contractDiffMetadata"
                )
                .doesNotContain("visual.runtimeBindingImplementation.contractDiffBreaking");
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.contractDiffCompatible".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.metadata()).containsEntry("category", "compatible");
                    assertThat(String.valueOf(diagnostic.metadata().get("fields")))
                            .contains("ports.inputs.region.added", "ports.outputs.audit.added");
                });
    }

    @Test
    void runtimeBindingImplementationValidationRequiresReviewForMissingEvidence() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                drafts,
                validator,
                catalog,
                new InMemoryVisualGraphPublicationRepository()
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
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract =
                handoffBundle.operatorContracts().getFirst();

        var response = controller.validateRuntimeBindingImplementation(
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        contract.operatorRef(),
                        contract.fingerprint(),
                        handoffBundle.bundleFingerprint(),
                        handoffBundle.requirementKeys(),
                        contract,
                        new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                                "risk-eligibility-native-v1",
                                "native",
                                "com.acme.risk.RiskEligibilityOperator",
                                "risk-platform",
                                List.of("request-response"),
                                List.of(),
                                List.of(),
                                "",
                                "")
                ));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("requires-review");
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains(
                        "visual.runtimeBindingImplementation.testEvidenceMissing",
                        "visual.runtimeBindingImplementation.rollbackTargetMissing",
                        "visual.runtimeBindingImplementation.rolloutPlanMissing"
                );
    }

    @Test
    void runtimeBindingImplementationValidationRejectsInvalidRolloutPlan() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();

        var response = fixture.controller().validateRuntimeBindingImplementation(
                implementationRequest(fixture,
                        completeImplementationWithRollout(
                                "risk-eligibility-native-invalid-rollout",
                                new VisualRuntimeBindingImplementationValidation.RolloutPlan(
                                        "spray-and-pray",
                                        75,
                                        50,
                                        "",
                                        "",
                                        List.of(),
                                        ""))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isFalse();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("rejected");
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains(
                        "visual.runtimeBindingImplementation.rolloutStrategyUnsupported",
                        "visual.runtimeBindingImplementation.rolloutTrafficWindowInvalid",
                        "visual.runtimeBindingImplementation.rolloutRollbackSignalMissing",
                        "visual.runtimeBindingImplementation.rolloutRollbackWindowMissing"
                );
    }

    @Test
    void runtimeBindingImplementationValidationRequiresReviewForHighRiskImmediateRollout() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();

        var response = fixture.controller().validateRuntimeBindingImplementation(
                implementationRequest(fixture,
                        remoteWorkerImplementationWithRollout(
                                "risk-eligibility-worker-immediate",
                                new VisualRuntimeBindingImplementationValidation.RolloutPlan(
                                        "immediate",
                                        100,
                                        100,
                                        "error-rate > 2%",
                                        "PT30M",
                                        List.of(),
                                        ""))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().valid()).isTrue();
        assertThat(response.getBody().bindable()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("requires-review");
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains(
                        "visual.runtimeBindingImplementation.rolloutImmediateHighRisk",
                        "visual.runtimeBindingImplementation.rolloutEvidenceMissing"
                );
    }

    @Test
    void runtimeBindingImplementationSubmitPersistsAcceptedProposal() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                drafts,
                validator,
                catalog,
                new InMemoryVisualGraphPublicationRepository()
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
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract =
                handoffBundle.operatorContracts().getFirst();

        VisualRuntimeBindingImplementationValidation.Request request =
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        contract.operatorRef(),
                        contract.fingerprint(),
                        handoffBundle.bundleFingerprint(),
                        handoffBundle.requirementKeys(),
                        contract,
                        completeImplementation()
                );

        var response = controller.submitRuntimeBindingImplementation(request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeBindingImplementationBinding.class);
        VisualRuntimeBindingImplementationBinding binding =
                (VisualRuntimeBindingImplementationBinding) response.getBody();
        assertThat(binding.schemaVersion())
                .isEqualTo(VisualRuntimeBindingImplementationBinding.SCHEMA_VERSION);
        assertThat(binding.bindingId()).isEqualTo("risk-eligibility-native-v1");
        assertThat(binding.revision()).isEqualTo(1);
        assertThat(binding.state()).isEqualTo("ready-to-bind");
        assertThat(binding.operatorRef()).isEqualTo("risk:eligibility");
        assertThat(binding.operatorFingerprint()).isEqualTo(operator.fingerprint());
        assertThat(binding.sourceHandoffBundleFingerprint()).isEqualTo(handoffBundle.bundleFingerprint());
        assertThat(binding.sourceRequirementKeys()).containsExactlyElementsOf(handoffBundle.requirementKeys());
        assertThat(binding.validation().valid()).isTrue();
        assertThat(binding.validation().bindable()).isTrue();
        assertThat(binding.implementation().rolloutPlan().strategy()).isEqualTo("canary");
        assertThat(binding.implementation().rolloutPlan().initialTrafficPercent()).isEqualTo(5);
        assertThat(controller.runtimeBindingImplementationBindings("", "")).singleElement().isEqualTo(binding);
        assertThat(controller.runtimeBindingImplementationBindings("risk:eligibility", "ready-to-bind"))
                .singleElement()
                .isEqualTo(binding);
        assertThat(controller.runtimeBindingImplementationBindings("risk:missing", "")).isEmpty();

        var replay = controller.submitRuntimeBindingImplementation(request);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(binding);

        var duplicate = controller.submitRuntimeBindingImplementation(
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        contract.operatorRef(),
                        contract.fingerprint(),
                        handoffBundle.bundleFingerprint(),
                        handoffBundle.requirementKeys(),
                        contract,
                        completeImplementation("risk-eligibility-native-v1",
                                "com.acme.risk.AlternateEligibilityOperator")
                ));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody()).isInstanceOf(VisualRuntimeBindingImplementationValidation.class);
        VisualRuntimeBindingImplementationValidation duplicateValidation =
                (VisualRuntimeBindingImplementationValidation) duplicate.getBody();
        assertThat(duplicateValidation.valid()).isFalse();
        assertThat(duplicateValidation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.bindingIdDuplicate");
        assertThat(controller.runtimeBindingImplementationBindings("", "")).singleElement().isEqualTo(binding);
    }

    @Test
    void runtimeBindingImplementationSubmitRejectsInvalidProposalWithoutPersisting() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(library);
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                drafts,
                validator,
                catalog,
                new InMemoryVisualGraphPublicationRepository()
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
        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract =
                handoffBundle.operatorContracts().getFirst();

        var response = controller.submitRuntimeBindingImplementation(
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        contract.operatorRef(),
                        "sha256:tampered",
                        handoffBundle.bundleFingerprint(),
                        handoffBundle.requirementKeys(),
                        contract,
                        completeImplementation()
                ));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeBindingImplementationValidation.class);
        VisualRuntimeBindingImplementationValidation validation =
                (VisualRuntimeBindingImplementationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.fingerprintMismatch");
        assertThat(controller.runtimeBindingImplementationBindings("", "")).isEmpty();
    }

    @Test
    void runtimeBindingImplementationSubmitRejectsInvalidImplementationVersionWithoutPersisting() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();

        var response = fixture.controller().submitRuntimeBindingImplementation(
                implementationRequest(fixture,
                        completeImplementationWithVersion(
                                "risk-eligibility-native-v1",
                                "v1",
                                "",
                                "")));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeBindingImplementationValidation.class);
        VisualRuntimeBindingImplementationValidation validation =
                (VisualRuntimeBindingImplementationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.implementationVersionInvalid");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "")).isEmpty();
    }

    @Test
    void runtimeBindingImplementationSubmitRejectsMissingReimplementationBaseWithoutPersisting() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();

        var response = fixture.controller().submitRuntimeBindingImplementation(
                implementationRequest(fixture,
                        completeImplementationWithVersion(
                                "risk-eligibility-native-v2",
                                "1.1.0",
                                "risk-eligibility-native-v1",
                                "compatible")));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeBindingImplementationValidation.class);
        VisualRuntimeBindingImplementationValidation validation =
                (VisualRuntimeBindingImplementationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.state()).isEqualTo("rejected");
        assertThat(validation.diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.reimplementationBaseNotFound"
                                .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.metadata())
                        .containsEntry("reimplementationOfBindingId", "risk-eligibility-native-v1"));
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "")).isEmpty();
    }

    @Test
    void runtimeBindingImplementationSubmitRejectsNonForwardReimplementationVersion() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding base = submitImplementation(
                fixture,
                completeImplementationWithVersion("risk-eligibility-native-v1", "1.2.0", "", ""));
        assertThat(fixture.controller().bindRuntimeBindingImplementation(base.bindingId(), transitionRequest("", true))
                .getStatusCode().value()).isEqualTo(200);

        var response = fixture.controller().submitRuntimeBindingImplementation(
                implementationRequest(fixture,
                        completeImplementationWithVersion(
                                "risk-eligibility-native-v2",
                                "1.2.0",
                                base.bindingId(),
                                "compatible")));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeBindingImplementationValidation.class);
        VisualRuntimeBindingImplementationValidation validation =
                (VisualRuntimeBindingImplementationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.state()).isEqualTo("rejected");
        assertThat(validation.diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.implementationVersionNotForward"
                                .equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.metadata())
                        .containsEntry("reimplementationOfBindingId", base.bindingId())
                        .containsEntry("baseImplementationVersion", "1.2.0")
                        .containsEntry("implementationVersion", "1.2.0")
                        .containsEntry("reimplementationStrategy", "compatible"));
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "bound")).singleElement()
                .isEqualTo(fixture.implementationRepository().find(base.bindingId()).orElseThrow());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "")).hasSize(1);
    }

    @Test
    void runtimeBindingImplementationSubmitPersistsCompatibleMajorChangeAsReviewRequired() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding base = submitImplementation(
                fixture,
                completeImplementationWithVersion("risk-eligibility-native-v1", "1.2.0", "", ""));
        assertThat(fixture.controller().bindRuntimeBindingImplementation(base.bindingId(), transitionRequest("", true))
                .getStatusCode().value()).isEqualTo(200);

        var response = fixture.controller().submitRuntimeBindingImplementation(
                implementationRequest(fixture,
                        completeImplementationWithVersion(
                                "risk-eligibility-native-v2",
                                "2.0.0",
                                base.bindingId(),
                                "compatible")));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeBindingImplementationBinding.class);
        VisualRuntimeBindingImplementationBinding stored =
                (VisualRuntimeBindingImplementationBinding) response.getBody();
        assertThat(stored.state()).isEqualTo("requires-review");
        assertThat(stored.validation().valid()).isTrue();
        assertThat(stored.validation().bindable()).isFalse();
        assertThat(stored.implementation().implementationVersion()).isEqualTo("2.0.0");
        assertThat(stored.implementation().reimplementationOfBindingId()).isEqualTo(base.bindingId());
        assertThat(stored.implementation().reimplementationStrategy()).isEqualTo("compatible");
        assertThat(stored.validation().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.compatibleReimplementationMajorChanged");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "")).hasSize(2);
    }

    @Test
    void runtimeBindingImplementationSubmitReturnsConflictWhenRepositoryWriteFails() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingSubmitBindingCreateRepository("risk-eligibility-native-v1"),
                new InMemoryVisualExecutableLoweringIntegrationRepository());

        var response = fixture.controller().submitRuntimeBindingImplementation(
                implementationRequest(fixture, completeImplementation("risk-eligibility-native-v1")));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeBindingImplementationValidation.class);
        VisualRuntimeBindingImplementationValidation validation =
                (VisualRuntimeBindingImplementationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.state()).isEqualTo("rejected");
        assertThat(validation.level()).isEqualTo("error");
        assertThat(validation.diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.persistenceFailed".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/implementation/bindingId");
                    assertThat(diagnostic.metadata())
                            .containsEntry("bindingId", "risk-eligibility-native-v1")
                            .containsEntry("operatorRef", "risk:eligibility")
                            .containsEntry("exceptionType", "IllegalStateException");
                });
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "")).isEmpty();
    }

    @Test
    void runtimeBindingImplementationBindActivatesReadyProposal() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );

        var response = fixture.controller().bindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", true)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isTrue();
        assertThat(response.getBody().binding()).isNotNull();
        assertThat(response.getBody().binding().state()).isEqualTo("bound");
        assertThat(response.getBody().binding().revision()).isEqualTo(2);
        assertThat(response.getBody().binding().lifecycleEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("bound");
            assertThat(event.fromState()).isEqualTo("ready-to-bind");
            assertThat(event.toState()).isEqualTo("bound");
            assertThat(event.actor()).isEqualTo("runtime-platform");
            assertThat(event.reason()).isEqualTo("Implementation evidence reviewed.");
        });
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .isEqualTo(response.getBody().binding());
    }

    @Test
    void runtimeBindingImplementationBindRejectsStaleExpectedRevision() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );

        var response = fixture.controller().bindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", true, stored.revision() + 1)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isFalse();
        assertThat(response.getBody().binding()).isEqualTo(stored);
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.revisionConflict".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/expectedRevision");
                    assertThat(diagnostic.metadata())
                            .containsEntry("bindingId", stored.bindingId())
                            .containsEntry("expectedRevision", stored.revision() + 1)
                            .containsEntry("actualRevision", stored.revision());
                });
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "ready-to-bind"))
                .singleElement()
                .isEqualTo(stored);
    }

    @Test
    void runtimeBindingImplementationBindRequiresReviewAcknowledgement() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                incompleteImplementation("risk-eligibility-native-review")
        );

        var rejected = fixture.controller().bindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", false)
        );

        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(rejected.getBody()).isNotNull();
        assertThat(rejected.getBody().accepted()).isFalse();
        assertThat(rejected.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.reviewAcknowledgementMissing");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "requires-review"))
                .singleElement()
                .isEqualTo(stored);

        var accepted = fixture.controller().bindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", true)
        );

        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(accepted.getBody()).isNotNull();
        assertThat(accepted.getBody().binding().state()).isEqualTo("bound");
    }

    @Test
    void runtimeBindingImplementationBindReturnsFailedWhenRepositoryWriteFails() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingBindImplementationRepository("risk-eligibility-native-v1"),
                new InMemoryVisualExecutableLoweringIntegrationRepository());
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );

        var response = fixture.controller().bindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", true)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("failed");
        assertThat(response.getBody().binding()).isEqualTo(stored);
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.bindPersistenceFailed".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/bindingId");
                    assertThat(diagnostic.metadata())
                            .containsEntry("bindingId", stored.bindingId())
                            .containsEntry("operatorRef", "risk:eligibility")
                            .containsEntry("fromState", "ready-to-bind")
                            .containsEntry("toState", "bound")
                            .containsEntry("exceptionType", "IllegalStateException");
                });
        assertThat(fixture.implementationRepository().find(stored.bindingId()))
                .get()
                .isEqualTo(stored);
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .isEmpty();
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "ready-to-bind"))
                .singleElement()
                .isEqualTo(stored);
    }

    @Test
    void runtimeBindingImplementationBindRejectsSecondActiveBinding() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding first = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding second = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v2")
        );
        assertThat(fixture.controller().bindRuntimeBindingImplementation(first.bindingId(), transitionRequest("", true))
                .getStatusCode().value()).isEqualTo(200);

        var response = fixture.controller().bindRuntimeBindingImplementation(
                second.bindingId(),
                transitionRequest("", true)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.activeBindingExists");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "bound")).hasSize(1);
    }

    @Test
    void runtimeBindingImplementationBindExactReplayIsIdempotent() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationTransitionRequest request = transitionRequest("", true, stored.revision());

        var accepted = fixture.controller().bindRuntimeBindingImplementation(stored.bindingId(), request);
        var replay = fixture.controller().bindRuntimeBindingImplementation(stored.bindingId(), request);
        var differentReason = fixture.controller().bindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", true, "A different runtime review should not replay.")
        );

        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().accepted()).isTrue();
        assertThat(replay.getBody().binding()).isEqualTo(accepted.getBody().binding());
        assertThat(replay.getBody().binding().revision()).isEqualTo(2);
        assertThat(replay.getBody().binding().lifecycleEvents()).hasSize(1);
        assertThat(differentReason.getStatusCode().value()).isEqualTo(409);
        assertThat(differentReason.getBody()).isNotNull();
        assertThat(differentReason.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.alreadyBound");
    }

    @Test
    void runtimeBindingImplementationSupersedeSwitchesActiveBinding() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding first = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding second = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v2")
        );
        fixture.controller().bindRuntimeBindingImplementation(first.bindingId(), transitionRequest("", true));

        var response = fixture.controller().supersedeRuntimeBindingImplementation(
                first.bindingId(),
                transitionRequest(second.bindingId(), true)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isTrue();
        assertThat(response.getBody().binding().state()).isEqualTo("superseded");
        assertThat(response.getBody().binding().supersededByBindingId()).isEqualTo(second.bindingId());
        assertThat(response.getBody().replacementBinding().state()).isEqualTo("bound");
        assertThat(response.getBody().replacementBinding().supersedesBindingId()).isEqualTo(first.bindingId());
        assertThat(response.getBody().replacementBinding().lifecycleEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("bound");
            assertThat(event.relatedBindingId()).isEqualTo(first.bindingId());
        });
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "bound"))
                .singleElement()
                .isEqualTo(response.getBody().replacementBinding());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("", "superseded"))
                .singleElement()
                .isEqualTo(response.getBody().binding());
    }

    @Test
    void runtimeBindingImplementationSupersedeExactReplayIsIdempotent() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding first = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding second = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v2")
        );
        VisualRuntimeBindingImplementationBinding boundFirst = fixture.controller()
                .bindRuntimeBindingImplementation(first.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeBindingImplementationTransitionRequest request =
                transitionRequest(second.bindingId(), true, "Implementation evidence reviewed.",
                        boundFirst.revision(), second.revision());

        var accepted = fixture.controller().supersedeRuntimeBindingImplementation(first.bindingId(), request);
        var replay = fixture.controller().supersedeRuntimeBindingImplementation(first.bindingId(), request);
        var differentReason = fixture.controller().supersedeRuntimeBindingImplementation(
                first.bindingId(),
                transitionRequest(second.bindingId(), true, "A different supersede review should not replay.")
        );

        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().accepted()).isTrue();
        assertThat(replay.getBody().binding()).isEqualTo(accepted.getBody().binding());
        assertThat(replay.getBody().replacementBinding()).isEqualTo(accepted.getBody().replacementBinding());
        assertThat(replay.getBody().binding().lifecycleEvents()).hasSize(2);
        assertThat(replay.getBody().replacementBinding().lifecycleEvents()).hasSize(1);
        assertThat(differentReason.getStatusCode().value()).isEqualTo(409);
        assertThat(differentReason.getBody()).isNotNull();
        assertThat(differentReason.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.currentNotBound");
    }

    @Test
    void runtimeBindingImplementationSupersedeRejectsStaleExpectedRevision() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding first = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding second = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v2")
        );
        VisualRuntimeBindingImplementationBinding boundFirst = fixture.controller()
                .bindRuntimeBindingImplementation(first.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();

        var response = fixture.controller().supersedeRuntimeBindingImplementation(
                first.bindingId(),
                transitionRequest(second.bindingId(), true, "Implementation evidence reviewed.",
                        boundFirst.revision() - 1, second.revision())
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isFalse();
        assertThat(response.getBody().binding()).isEqualTo(boundFirst);
        assertThat(response.getBody().replacementBinding()).isEqualTo(second);
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.revisionConflict".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/expectedRevision");
                    assertThat(diagnostic.metadata())
                            .containsEntry("bindingId", boundFirst.bindingId())
                            .containsEntry("expectedRevision", boundFirst.revision() - 1)
                            .containsEntry("actualRevision", boundFirst.revision());
                });
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .isEqualTo(boundFirst);
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "ready-to-bind"))
                .singleElement()
                .isEqualTo(second);
    }

    @Test
    void runtimeBindingImplementationSupersedeRejectsStaleExpectedReplacementRevision() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding first = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding second = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v2")
        );
        VisualRuntimeBindingImplementationBinding boundFirst = fixture.controller()
                .bindRuntimeBindingImplementation(first.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();

        var response = fixture.controller().supersedeRuntimeBindingImplementation(
                first.bindingId(),
                transitionRequest(second.bindingId(), true, "Implementation evidence reviewed.",
                        boundFirst.revision(), second.revision() + 1)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isFalse();
        assertThat(response.getBody().binding()).isEqualTo(boundFirst);
        assertThat(response.getBody().replacementBinding()).isEqualTo(second);
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.revisionConflict".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/expectedReplacementRevision");
                    assertThat(diagnostic.metadata())
                            .containsEntry("bindingId", second.bindingId())
                            .containsEntry("expectedRevision", second.revision() + 1)
                            .containsEntry("actualRevision", second.revision());
                });
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .isEqualTo(boundFirst);
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "ready-to-bind"))
                .singleElement()
                .isEqualTo(second);
    }

    @Test
    void runtimeBindingImplementationSupersedeCompensatesFailedCurrentUpdate() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingSupersedeImplementationRepository("risk-eligibility-native-v1"),
                new InMemoryVisualExecutableLoweringIntegrationRepository());
        VisualRuntimeBindingImplementationBinding first = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding second = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v2")
        );
        assertThat(fixture.controller()
                .bindRuntimeBindingImplementation(first.bindingId(), transitionRequest("", true))
                .getStatusCode().value())
                .isEqualTo(200);

        var response = fixture.controller().supersedeRuntimeBindingImplementation(
                first.bindingId(),
                transitionRequest(second.bindingId(), true)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("failed");
        assertThat(response.getBody().binding().bindingId()).isEqualTo(first.bindingId());
        assertThat(response.getBody().binding().state()).isEqualTo("bound");
        assertThat(response.getBody().replacementBinding().bindingId()).isEqualTo(second.bindingId());
        assertThat(response.getBody().replacementBinding().state()).isEqualTo("ready-to-bind");
        assertThat(response.getBody().replacementBinding().supersedesBindingId()).isBlank();
        assertThat(response.getBody().replacementBinding().lifecycleEvents())
                .extracting(VisualRuntimeBindingImplementationBinding.LifecycleEvent::eventType)
                .containsExactly("bound", "restored");
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.supersedeCurrentUpdateFailed")
                .doesNotContain("visual.runtimeBindingImplementation.supersedeCompensationFailed");

        assertThat(fixture.implementationRepository().find(first.bindingId()))
                .get()
                .extracting(VisualRuntimeBindingImplementationBinding::state)
                .isEqualTo("bound");
        assertThat(fixture.implementationRepository().find(second.bindingId()))
                .get()
                .extracting(VisualRuntimeBindingImplementationBinding::state)
                .isEqualTo("ready-to-bind");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(first.bindingId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "ready-to-bind"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(second.bindingId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "superseded"))
                .isEmpty();
    }

    @Test
    void runtimeBindingImplementationUnbindDeactivatesRuntimeEvidenceAndReopensBindingGap() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();
        OperatorDefinition operator = fixture.catalog().find("risk:eligibility").orElseThrow();
        assertThat(fixture.catalog()
                .executablePromotionProjections(
                        OperatorCatalogQuery.all(),
                        fixture.catalog().runtimeBindingProjections(OperatorCatalogQuery.all(), List.of(operator)))
                .getFirst()
                .promotionState())
                .isEqualTo("readiness-recompute-required");

        var response = fixture.controller().unbindRuntimeBindingImplementation(
                binding.bindingId(),
                transitionRequest("", true)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        VisualRuntimeBindingDeactivationResult result = response.getBody();
        assertThat(result.schemaVersion()).isEqualTo(VisualRuntimeBindingDeactivationResult.SCHEMA_VERSION);
        assertThat(result.accepted()).isTrue();
        assertThat(result.binding().state()).isEqualTo("unbound");
        assertThat(result.binding().revision()).isEqualTo(3);
        assertThat(result.binding().lifecycleEvents()).last().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("unbound");
            assertThat(event.fromState()).isEqualTo("bound");
            assertThat(event.toState()).isEqualTo("unbound");
            assertThat(event.actor()).isEqualTo("runtime-platform");
        });
        assertThat(result.deactivatedActivation()).isNotNull();
        assertThat(result.deactivatedActivation().activationId()).isEqualTo(activation.activationId());
        assertThat(result.deactivatedActivation().state()).isEqualTo("inactive");
        assertThat(result.deactivatedActivation().revision()).isEqualTo(2);
        assertThat(result.deactivatedActivation().evidence())
                .extracting(VisualRuntimeAdapterActivation.Evidence::kind)
                .contains("health-check", "binding-unbind");
        assertThat(result.deactivatedIntegration()).isNotNull();
        assertThat(result.deactivatedIntegration().integrationId()).isEqualTo(integration.integrationId());
        assertThat(result.deactivatedIntegration().state()).isEqualTo("inactive");
        assertThat(result.deactivatedIntegration().revision()).isEqualTo(2);
        assertThat(result.deactivatedIntegration().evidence())
                .extracting(VisualExecutableLoweringIntegration.Evidence::kind)
                .contains("executor-test", "binding-unbind");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .isEmpty();
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "unbound"))
                .singleElement()
                .isEqualTo(result.binding());
        assertThat(fixture.controller().runtimeAdapterActivations("", "risk:eligibility", "active"))
                .isEmpty();
        assertThat(fixture.controller().runtimeAdapterActivations("", "risk:eligibility", "inactive"))
                .singleElement()
                .isEqualTo(result.deactivatedActivation());
        assertThat(fixture.controller().executableLoweringIntegrations("", "risk:eligibility", "active"))
                .isEmpty();
        assertThat(fixture.controller().executableLoweringIntegrations("", "risk:eligibility", "inactive"))
                .singleElement()
                .isEqualTo(result.deactivatedIntegration());

        var runtimeProjection = fixture.catalog()
                .runtimeBindingProjections(OperatorCatalogQuery.all(), List.of(operator))
                .getFirst();
        assertThat(runtimeProjection.projectionState()).isEqualTo("binding-required");
        assertThat(runtimeProjection.activeBindingId()).isBlank();
        assertThat(runtimeProjection.activeAdapterActivationId()).isBlank();
        assertThat(fixture.catalog()
                .executablePromotionProjections(OperatorCatalogQuery.all(), List.of(runtimeProjection))
                .getFirst()
                .promotionState())
                .isEqualTo("binding-required");
    }

    @Test
    void runtimeBindingImplementationUnbindRejectsStaleExpectedRevision() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();

        var response = fixture.controller().unbindRuntimeBindingImplementation(
                binding.bindingId(),
                transitionRequest("", true, "Implementation evidence reviewed.", binding.revision() - 1, 0)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isFalse();
        assertThat(response.getBody().binding()).isEqualTo(binding);
        assertThat(response.getBody().diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeBindingImplementation.revisionConflict".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/expectedRevision");
                    assertThat(diagnostic.metadata())
                            .containsEntry("bindingId", binding.bindingId())
                            .containsEntry("expectedRevision", binding.revision() - 1)
                            .containsEntry("actualRevision", binding.revision());
                });
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .isEqualTo(binding);
    }

    @Test
    void runtimeBindingImplementationUnbindExactReplayIsIdempotent() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"));
        VisualRuntimeBindingImplementationTransitionRequest request = transitionRequest("", true, binding.revision());

        var accepted = fixture.controller().unbindRuntimeBindingImplementation(binding.bindingId(), request);
        var replay = fixture.controller().unbindRuntimeBindingImplementation(binding.bindingId(), request);
        var differentReason = fixture.controller().unbindRuntimeBindingImplementation(
                binding.bindingId(),
                transitionRequest("", true, "A different unbind review should not replay.")
        );

        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().accepted()).isTrue();
        assertThat(replay.getBody().binding()).isEqualTo(accepted.getBody().binding());
        assertThat(replay.getBody().binding().revision()).isEqualTo(3);
        assertThat(replay.getBody().deactivatedActivation())
                .isEqualTo(accepted.getBody().deactivatedActivation());
        assertThat(replay.getBody().deactivatedIntegration())
                .isEqualTo(accepted.getBody().deactivatedIntegration());
        assertThat(replay.getBody().deactivatedActivation().revision()).isEqualTo(2);
        assertThat(replay.getBody().deactivatedIntegration().revision()).isEqualTo(2);
        assertThat(differentReason.getStatusCode().value()).isEqualTo(409);
        assertThat(differentReason.getBody()).isNotNull();
        assertThat(differentReason.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.bindingNotBoundForUnbind");
    }

    @Test
    void runtimeBindingImplementationUnbindRestoresRuntimeEvidenceWhenBindingUpdateFails() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingUnbindImplementationRepository("risk-eligibility-native-v1"),
                new InMemoryVisualExecutableLoweringIntegrationRepository());
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();

        var response = fixture.controller().unbindRuntimeBindingImplementation(
                binding.bindingId(),
                transitionRequest("", true)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        VisualRuntimeBindingDeactivationResult result = response.getBody();
        assertThat(result.accepted()).isFalse();
        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.binding().bindingId()).isEqualTo(binding.bindingId());
        assertThat(result.binding().state()).isEqualTo("bound");
        assertThat(result.deactivatedActivation()).isNotNull();
        assertThat(result.deactivatedActivation().activationId()).isEqualTo(activation.activationId());
        assertThat(result.deactivatedActivation().state()).isEqualTo("active");
        assertThat(result.deactivatedActivation().revision()).isEqualTo(3);
        assertThat(result.deactivatedActivation().evidence())
                .extracting(VisualRuntimeAdapterActivation.Evidence::kind)
                .contains("binding-unbind", "unbind-compensation");
        assertThat(result.deactivatedIntegration()).isNotNull();
        assertThat(result.deactivatedIntegration().integrationId()).isEqualTo(integration.integrationId());
        assertThat(result.deactivatedIntegration().state()).isEqualTo("active");
        assertThat(result.deactivatedIntegration().revision()).isEqualTo(3);
        assertThat(result.deactivatedIntegration().evidence())
                .extracting(VisualExecutableLoweringIntegration.Evidence::kind)
                .contains("binding-unbind", "unbind-compensation");
        assertThat(result.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingDeactivation.bindingUnbindFailed")
                .doesNotContain("visual.runtimeBindingDeactivation.compensationFailed");

        assertThat(fixture.implementationRepository().find(binding.bindingId()))
                .get()
                .extracting(VisualRuntimeBindingImplementationBinding::state)
                .isEqualTo("bound");
        assertThat(fixture.adapterActivationRepository().find(activation.activationId()))
                .get()
                .isEqualTo(result.deactivatedActivation());
        assertThat(fixture.executableLoweringIntegrationRepository().find(integration.integrationId()))
                .get()
                .isEqualTo(result.deactivatedIntegration());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(binding.bindingId());
        assertThat(fixture.controller().runtimeAdapterActivations("", "risk:eligibility", "active"))
                .singleElement()
                .extracting(VisualRuntimeAdapterActivation::activationId)
                .isEqualTo(activation.activationId());
        assertThat(fixture.controller().runtimeAdapterActivations("", "risk:eligibility", "inactive"))
                .isEmpty();
        assertThat(fixture.controller().executableLoweringIntegrations("", "risk:eligibility", "active"))
                .singleElement()
                .extracting(VisualExecutableLoweringIntegration::integrationId)
                .isEqualTo(integration.integrationId());
        assertThat(fixture.controller().executableLoweringIntegrations("", "risk:eligibility", "inactive"))
                .isEmpty();

        OperatorDefinition operator = fixture.catalog().find("risk:eligibility").orElseThrow();
        var runtimeProjection = fixture.catalog()
                .runtimeBindingProjections(OperatorCatalogQuery.all(), List.of(operator))
                .getFirst();
        assertThat(runtimeProjection.projectionState()).isEqualTo("adapter-active");
        assertThat(runtimeProjection.activeBindingId()).isEqualTo(binding.bindingId());
        assertThat(runtimeProjection.activeAdapterActivationId()).isEqualTo(activation.activationId());
        assertThat(fixture.catalog()
                .executablePromotionProjections(OperatorCatalogQuery.all(), List.of(runtimeProjection))
                .getFirst()
                .promotionState())
                .isEqualTo("readiness-recompute-required");
    }

    @Test
    void runtimeBindingImplementationUnbindRequiresGovernanceAndBoundState() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );

        var missingGovernance = fixture.controller().unbindRuntimeBindingImplementation(
                stored.bindingId(),
                new VisualRuntimeBindingImplementationTransitionRequest(
                        VisualRuntimeBindingImplementationTransitionRequest.SCHEMA_VERSION,
                        "",
                        "",
                        "visual-canvas-test",
                        "Unbind runtime implementation evidence.",
                        true,
                        "")
        );

        assertThat(missingGovernance.getStatusCode().value()).isEqualTo(400);
        assertThat(missingGovernance.getBody()).isNotNull();
        assertThat(missingGovernance.getBody().accepted()).isFalse();
        assertThat(missingGovernance.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.actorMissing");

        var notBound = fixture.controller().unbindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", true)
        );

        assertThat(notBound.getStatusCode().value()).isEqualTo(409);
        assertThat(notBound.getBody()).isNotNull();
        assertThat(notBound.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeBindingImplementation.bindingNotBoundForUnbind");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "ready-to-bind"))
                .singleElement()
                .isEqualTo(stored);
    }

    @Test
    void runtimeAdapterActivationSubmitPersistsHealthyBoundAdapter() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        var boundResponse = fixture.controller().bindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", true)
        );
        assertThat(boundResponse.getStatusCode().value()).isEqualTo(200);
        VisualRuntimeBindingImplementationBinding binding = boundResponse.getBody().binding();
        VisualRuntimeAdapterActivationValidation.Request request =
                adapterActivationRequest(binding, "risk-eligibility-prod-active");

        var validation = fixture.controller().validateRuntimeAdapterActivation(request);
        var response = fixture.controller().submitRuntimeAdapterActivation(request);

        assertThat(validation.getStatusCode().value()).isEqualTo(200);
        assertThat(validation.getBody()).isNotNull();
        assertThat(validation.getBody().valid()).isTrue();
        assertThat(validation.getBody().activatable()).isTrue();
        assertThat(validation.getBody().state()).isEqualTo("ready-to-activate");
        assertThat(validation.getBody().bindingId()).isEqualTo(binding.bindingId());
        assertThat(validation.getBody().bindingRevision()).isEqualTo(binding.revision());
        assertThat(validation.getBody().currentCatalogState()).isEqualTo("current");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeAdapterActivation.class);
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) response.getBody();
        assertThat(activation.schemaVersion()).isEqualTo(VisualRuntimeAdapterActivation.SCHEMA_VERSION);
        assertThat(activation.activationId()).isEqualTo("risk-eligibility-prod-active");
        assertThat(activation.bindingId()).isEqualTo(binding.bindingId());
        assertThat(activation.bindingRevision()).isEqualTo(binding.revision());
        assertThat(activation.operatorRef()).isEqualTo("risk:eligibility");
        assertThat(activation.operatorFingerprint()).isEqualTo(binding.operatorFingerprint());
        assertThat(activation.adapterKind()).isEqualTo("native");
        assertThat(activation.entrypoint()).isEqualTo("com.acme.risk.RiskEligibilityOperator");
        assertThat(activation.runtimeEnvironment()).isEqualTo("prod");
        assertThat(activation.healthState()).isEqualTo("healthy");
        assertThat(activation.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.kind()).isEqualTo("health-check");
            assertThat(evidence.ref()).isEqualTo("deployment:risk-eligibility-native-v1");
        });
        assertThat(fixture.controller().runtimeAdapterActivations("", "", ""))
                .singleElement()
                .isEqualTo(activation);
        assertThat(fixture.controller().runtimeAdapterActivations(binding.bindingId(), "risk:eligibility", "active"))
                .singleElement()
                .isEqualTo(activation);

        var replay = fixture.controller().submitRuntimeAdapterActivation(request);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(activation);

        var duplicate = fixture.controller().submitRuntimeAdapterActivation(
                adapterActivationRequest(binding, "risk-eligibility-prod-active",
                        "Runtime deployment changed after the first submission."));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody()).isInstanceOf(VisualRuntimeAdapterActivationValidation.class);
        VisualRuntimeAdapterActivationValidation duplicateValidation =
                (VisualRuntimeAdapterActivationValidation) duplicate.getBody();
        assertThat(duplicateValidation.valid()).isFalse();
        assertThat(duplicateValidation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeAdapterActivation.activationIdDuplicate");
    }

    @Test
    void runtimeAdapterActivationSubmitRejectsUnboundImplementation() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );

        var response = fixture.controller().submitRuntimeAdapterActivation(
                adapterActivationRequest(stored, "risk-eligibility-prod-active")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeAdapterActivationValidation.class);
        VisualRuntimeAdapterActivationValidation validation =
                (VisualRuntimeAdapterActivationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeAdapterActivation.bindingNotBound");
        assertThat(fixture.controller().runtimeAdapterActivations("", "", "")).isEmpty();
    }

    @Test
    void runtimeAdapterActivationSubmitReturnsConflictWhenRepositoryWriteFails() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new InMemoryVisualRuntimeBindingImplementationRepository(),
                new FailingSubmitAdapterActivationRepository("risk-eligibility-prod-active"),
                new InMemoryVisualExecutableLoweringIntegrationRepository());
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();

        var response = fixture.controller().submitRuntimeAdapterActivation(
                adapterActivationRequest(binding, "risk-eligibility-prod-active"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeAdapterActivationValidation.class);
        VisualRuntimeAdapterActivationValidation validation =
                (VisualRuntimeAdapterActivationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.state()).isEqualTo("rejected");
        assertThat(validation.level()).isEqualTo("error");
        assertThat(validation.diagnostics())
                .filteredOn(diagnostic ->
                        "visual.runtimeAdapterActivation.persistenceFailed".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/activationId");
                    assertThat(diagnostic.metadata())
                            .containsEntry("activationId", "risk-eligibility-prod-active")
                            .containsEntry("bindingId", binding.bindingId())
                            .containsEntry("operatorRef", "risk:eligibility")
                .containsEntry("exceptionType", "IllegalStateException");
                });
        assertThat(fixture.controller().runtimeAdapterActivations("", "", "")).isEmpty();
    }

    @Test
    void runtimeRolloutObservationSubmitPersistsHealthyCanaryFeedback() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualRuntimeRolloutObservationValidation.Request request =
                rolloutObservationRequest(activation, "risk-rollout-canary-5",
                        VisualRuntimeRolloutObservation.STATE_HEALTHY, 5, false, "");

        var validation = fixture.controller().validateRuntimeRolloutObservation(request);
        var response = fixture.controller().submitRuntimeRolloutObservation(request);

        assertThat(validation.getStatusCode().value()).isEqualTo(200);
        assertThat(validation.getBody()).isNotNull();
        assertThat(validation.getBody().valid()).isTrue();
        assertThat(validation.getBody().recordable()).isTrue();
        assertThat(validation.getBody().state()).isEqualTo("ready-to-record");
        assertThat(validation.getBody().level()).isEqualTo("success");
        assertThat(validation.getBody().activationId()).isEqualTo(activation.activationId());
        assertThat(validation.getBody().bindingId()).isEqualTo(binding.bindingId());
        assertThat(validation.getBody().currentCatalogState()).isEqualTo("current");
        assertThat(validation.getBody().rolloutStrategy()).isEqualTo("canary");
        assertThat(validation.getBody().trafficPercent()).isEqualTo(5);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeRolloutObservation.class);
        VisualRuntimeRolloutObservation observation = (VisualRuntimeRolloutObservation) response.getBody();
        assertThat(observation.schemaVersion()).isEqualTo(VisualRuntimeRolloutObservation.SCHEMA_VERSION);
        assertThat(observation.observationId()).isEqualTo("risk-rollout-canary-5");
        assertThat(observation.activationId()).isEqualTo(activation.activationId());
        assertThat(observation.activationRevision()).isEqualTo(activation.revision());
        assertThat(observation.bindingId()).isEqualTo(binding.bindingId());
        assertThat(observation.bindingRevision()).isEqualTo(binding.revision());
        assertThat(observation.operatorRef()).isEqualTo("risk:eligibility");
        assertThat(observation.operatorFingerprint()).isEqualTo(binding.operatorFingerprint());
        assertThat(observation.rolloutStrategy()).isEqualTo("canary");
        assertThat(observation.trafficPercent()).isEqualTo(5);
        assertThat(observation.state()).isEqualTo(VisualRuntimeRolloutObservation.STATE_HEALTHY);
        assertThat(observation.level()).isEqualTo("success");
        assertThat(observation.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.kind()).isEqualTo("canary-metric");
            assertThat(evidence.ref()).isEqualTo("rollout:risk-rollout-canary-5");
        });
        assertThat(fixture.controller().runtimeRolloutObservations("", "", "", ""))
                .singleElement()
                .isEqualTo(observation);
        assertThat(fixture.controller().runtimeRolloutObservations(
                activation.activationId(), binding.bindingId(), "risk:eligibility", "healthy"))
                .singleElement()
                .isEqualTo(observation);

        var replay = fixture.controller().submitRuntimeRolloutObservation(request);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(observation);

        var duplicate = fixture.controller().submitRuntimeRolloutObservation(
                rolloutObservationRequest(activation, "risk-rollout-canary-5",
                        VisualRuntimeRolloutObservation.STATE_COMPLETED, 100, false, ""));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody()).isInstanceOf(VisualRuntimeRolloutObservationValidation.class);
        VisualRuntimeRolloutObservationValidation duplicateValidation =
                (VisualRuntimeRolloutObservationValidation) duplicate.getBody();
        assertThat(duplicateValidation.valid()).isFalse();
        assertThat(duplicateValidation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.runtimeRolloutObservation.observationIdDuplicate");
    }

    @Test
    void runtimeRolloutObservationRecordsRollbackAsRecordableErrorFact() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualRuntimeRolloutObservationValidation.Request request =
                rolloutObservationRequest(activation, "risk-rollout-rollback-1",
                        VisualRuntimeRolloutObservation.STATE_ROLLED_BACK, 0, true,
                        "error-rate > 2% or golden regression failure");

        var validation = fixture.controller().validateRuntimeRolloutObservation(request);
        var response = fixture.controller().submitRuntimeRolloutObservation(request);

        assertThat(validation.getStatusCode().value()).isEqualTo(200);
        assertThat(validation.getBody()).isNotNull();
        assertThat(validation.getBody().valid()).isTrue();
        assertThat(validation.getBody().recordable()).isTrue();
        assertThat(validation.getBody().state()).isEqualTo("ready-to-record");
        assertThat(validation.getBody().level()).isEqualTo("error");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeRolloutObservation.class);
        VisualRuntimeRolloutObservation observation = (VisualRuntimeRolloutObservation) response.getBody();
        assertThat(observation.state()).isEqualTo(VisualRuntimeRolloutObservation.STATE_ROLLED_BACK);
        assertThat(observation.level()).isEqualTo("error");
        assertThat(observation.rollbackTriggered()).isTrue();
        assertThat(observation.rollbackSignal()).contains("error-rate");
        assertThat(fixture.controller().runtimeRolloutObservations(
                activation.activationId(), binding.bindingId(), "risk:eligibility", "rolled-back"))
                .singleElement()
                .isEqualTo(observation);
    }

    @Test
    void runtimeRolloutObservationRejectsPlanMismatchAndInvalidTraffic() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualRuntimeRolloutObservationValidation.Request request =
                rolloutObservationRequest(activation, "risk-rollout-invalid",
                        "immediate", VisualRuntimeRolloutObservation.STATE_HEALTHY, 101, false, "");

        var response = fixture.controller().submitRuntimeRolloutObservation(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeRolloutObservationValidation.class);
        VisualRuntimeRolloutObservationValidation validation =
                (VisualRuntimeRolloutObservationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.recordable()).isFalse();
        assertThat(validation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains(
                        "visual.runtimeRolloutObservation.rolloutStrategyMismatch",
                        "visual.runtimeRolloutObservation.trafficPercentInvalid");
        assertThat(fixture.controller().runtimeRolloutObservations("", "", "", "")).isEmpty();
    }

    @Test
    void executableLoweringIntegrationSubmitPersistsBridgeEvidenceWithoutClosingReadiness() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        var boundResponse = fixture.controller().bindRuntimeBindingImplementation(
                stored.bindingId(),
                transitionRequest("", true)
        );
        assertThat(boundResponse.getStatusCode().value()).isEqualTo(200);
        VisualRuntimeBindingImplementationBinding binding = boundResponse.getBody().binding();
        VisualRuntimeAdapterActivationValidation.Request activationRequest =
                adapterActivationRequest(binding, "risk-eligibility-prod-active");
        var activationResponse = fixture.controller().submitRuntimeAdapterActivation(activationRequest);
        assertThat(activationResponse.getStatusCode().value()).isEqualTo(201);
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) activationResponse.getBody();
        VisualExecutableLoweringIntegrationValidation.Request request =
                executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1");

        var validation = fixture.controller().validateExecutableLoweringIntegration(request);
        var response = fixture.controller().submitExecutableLoweringIntegration(request);

        assertThat(validation.getStatusCode().value()).isEqualTo(200);
        assertThat(validation.getBody()).isNotNull();
        assertThat(validation.getBody().valid()).isTrue();
        assertThat(validation.getBody().integratable()).isTrue();
        assertThat(validation.getBody().state()).isEqualTo("ready-to-integrate");
        assertThat(validation.getBody().activationId()).isEqualTo(activation.activationId());
        assertThat(validation.getBody().activationRevision()).isEqualTo(activation.revision());
        assertThat(validation.getBody().currentCatalogState()).isEqualTo("current");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(VisualExecutableLoweringIntegration.class);
        VisualExecutableLoweringIntegration integration =
                (VisualExecutableLoweringIntegration) response.getBody();
        assertThat(integration.schemaVersion()).isEqualTo(VisualExecutableLoweringIntegration.SCHEMA_VERSION);
        assertThat(integration.integrationId()).isEqualTo("risk-eligibility-lowering-v1");
        assertThat(integration.activationId()).isEqualTo(activation.activationId());
        assertThat(integration.bindingId()).isEqualTo(binding.bindingId());
        assertThat(integration.operatorRef()).isEqualTo("risk:eligibility");
        assertThat(integration.loweringMode()).isEqualTo("native");
        assertThat(integration.executorKind()).isEqualTo("bloge-operator-registry");
        assertThat(integration.executorEntrypoint()).isEqualTo("operator:risk:eligibility");
        assertThat(fixture.controller().executableLoweringIntegrations("", "", ""))
                .singleElement()
                .isEqualTo(integration);
        assertThat(fixture.controller().executableLoweringIntegrations(
                activation.activationId(), "risk:eligibility", "active"))
                .singleElement()
                .isEqualTo(integration);

        var replay = fixture.controller().submitExecutableLoweringIntegration(request);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(integration);

        var duplicate = fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(
                        activation,
                        "risk-eligibility-lowering-v1",
                        "native",
                        "operator:risk:eligibility-v2"));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody()).isInstanceOf(VisualExecutableLoweringIntegrationValidation.class);
        VisualExecutableLoweringIntegrationValidation duplicateValidation =
                (VisualExecutableLoweringIntegrationValidation) duplicate.getBody();
        assertThat(duplicateValidation.valid()).isFalse();
        assertThat(duplicateValidation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableLoweringIntegration.integrationIdDuplicate");
    }

    @Test
    void executableLoweringIntegrationRejectsMissingActivation() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();

        var response = fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(
                        new VisualRuntimeAdapterActivation(
                                VisualRuntimeAdapterActivation.SCHEMA_VERSION,
                                "missing-activation",
                                1,
                                VisualRuntimeAdapterActivation.STATE_ACTIVE,
                                "success",
                                "missing-binding",
                                1,
                                "risk:eligibility",
                                fixture.contract().fingerprint(),
                                "native",
                                "com.acme.risk.RiskEligibilityOperator",
                                "risk-platform",
                                "prod",
                                VisualRuntimeAdapterActivation.HEALTH_HEALTHY,
                                "runtime-platform",
                                "visual-canvas-test",
                                "Missing activation.",
                                List.of(),
                                Instant.EPOCH,
                                Instant.EPOCH
                        ),
                        "risk-eligibility-lowering-missing"
                )
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(VisualExecutableLoweringIntegrationValidation.class);
        VisualExecutableLoweringIntegrationValidation validation =
                (VisualExecutableLoweringIntegrationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableLoweringIntegration.activationMissing");
        assertThat(fixture.controller().executableLoweringIntegrations("", "", "")).isEmpty();
    }

    @Test
    void executableLoweringIntegrationSubmitReturnsConflictWhenRepositoryWriteFails() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingSubmitLoweringIntegrationRepository("risk-eligibility-lowering-v1"));
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();

        var response = fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isInstanceOf(VisualExecutableLoweringIntegrationValidation.class);
        VisualExecutableLoweringIntegrationValidation validation =
                (VisualExecutableLoweringIntegrationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.state()).isEqualTo("rejected");
        assertThat(validation.level()).isEqualTo("error");
        assertThat(validation.diagnostics())
                .filteredOn(diagnostic ->
                        "visual.executableLoweringIntegration.persistenceFailed".equals(diagnostic.code()))
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.target()).isEqualTo("/integrationId");
                    assertThat(diagnostic.metadata())
                            .containsEntry("integrationId", "risk-eligibility-lowering-v1")
                            .containsEntry("activationId", activation.activationId())
                            .containsEntry("bindingId", binding.bindingId())
                            .containsEntry("operatorRef", "risk:eligibility")
                            .containsEntry("exceptionType", "IllegalStateException");
                });
        assertThat(fixture.controller().executableLoweringIntegrations("", "", "")).isEmpty();
    }

    @Test
    void executableLoweringIntegrationRejectsDesignLoweringMode() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();

        var response = fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(
                        activation,
                        "risk-eligibility-design-lowering",
                        "design",
                        "bloge-operator-registry",
                        "operator:risk:eligibility")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(VisualExecutableLoweringIntegrationValidation.class);
        VisualExecutableLoweringIntegrationValidation validation =
                (VisualExecutableLoweringIntegrationValidation) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableLoweringIntegration.designLoweringUnsupported");
        assertThat(fixture.controller().executableLoweringIntegrations("", "", "")).isEmpty();
    }

    @Test
    void executableReadinessRecomputePreviewBuildsCandidateSurfaceWithoutMutatingCatalog() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();

        var response = fixture.controller().previewExecutableReadinessRecompute("risk:eligibility");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        VisualExecutableReadinessRecomputePreview preview = response.getBody();
        assertThat(preview.schemaVersion()).isEqualTo(VisualExecutableReadinessRecomputePreview.SCHEMA_VERSION);
        assertThat(preview.recomputable()).isTrue();
        assertThat(preview.state()).isEqualTo("ready-to-apply");
        assertThat(preview.level()).isEqualTo("success");
        assertThat(preview.operatorRef()).isEqualTo("risk:eligibility");
        assertThat(preview.operatorLibraryId()).isEqualTo("risk-policy-design");
        assertThat(preview.currentOperatorFingerprint()).isEqualTo(fixture.contract().fingerprint());
        assertThat(preview.currentRuntimeReadinessState()).isEqualTo("DESIGN_ONLY");
        assertThat(preview.currentLoweringMode()).isEqualTo("design");
        assertThat(preview.activeBindingId()).isEqualTo(binding.bindingId());
        assertThat(preview.activeAdapterActivationId()).isEqualTo(activation.activationId());
        assertThat(preview.activeExecutableLoweringIntegrationId()).isEqualTo(integration.integrationId());
        assertThat(preview.candidateOperator()).isNotNull();
        assertThat(preview.candidateOperatorFingerprint()).isEqualTo(preview.candidateOperator().fingerprint());
        assertThat(preview.candidateOperatorFingerprint()).isNotEqualTo(preview.currentOperatorFingerprint());
        assertThat(preview.candidateRuntimeReadinessState()).isEqualTo("RUNTIME_EXECUTABLE");
        assertThat(preview.candidateLoweringMode()).isEqualTo("native");
        assertThat(preview.candidateLoweringOperatorRef()).isEqualTo("operator:risk:eligibility");
        assertThat(preview.candidateOperator().runtimeReadiness().executable()).isTrue();
        assertThat(preview.candidateOperator().runtimeReadiness().artifactKinds()).containsExactly("EXECUTABLE");
        assertThat(preview.candidateOperator().lowering().parameters())
                .containsEntry("runtimeBindingId", binding.bindingId())
                .containsEntry("adapterActivationId", activation.activationId())
                .containsEntry("executableLoweringIntegrationId", integration.integrationId())
                .containsEntry("executorEntrypoint", "operator:risk:eligibility");
        assertThat(preview.diagnostics()).isEmpty();
        assertThat(fixture.catalog().find("risk:eligibility").orElseThrow().runtimeReadiness().state())
                .isEqualTo("DESIGN_ONLY");
        assertThat(fixture.catalog().find("risk:eligibility").orElseThrow().lowering().mode())
                .isEqualTo("design");
    }

    @Test
    void executableReadinessRecomputePreviewBuildsExternalLoweringCandidate() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                remoteWorkerImplementation("risk-eligibility-worker-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        var integrationResponse = fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(
                        activation,
                        "risk-eligibility-worker-lowering",
                        "remote-worker",
                        "worker-dispatcher",
                        "worker:risk.eligibility"
                ));
        assertThat(integrationResponse.getStatusCode().value()).isEqualTo(201);

        var response = fixture.controller().previewExecutableReadinessRecompute("risk:eligibility");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        VisualExecutableReadinessRecomputePreview preview = response.getBody();
        assertThat(preview.recomputable()).isTrue();
        assertThat(preview.state()).isEqualTo("ready-to-apply");
        assertThat(preview.level()).isEqualTo("warning");
        assertThat(preview.currentRuntimeReadinessState()).isEqualTo("DESIGN_ONLY");
        assertThat(preview.activeExecutableLoweringIntegrationId())
                .isEqualTo("risk-eligibility-worker-lowering");
        assertThat(preview.candidateOperator()).isNotNull();
        assertThat(preview.candidateRuntimeReadinessState()).isEqualTo("EXTERNAL_RUNTIME_BOUND");
        assertThat(preview.candidateLoweringMode()).isEqualTo("remote-worker");
        assertThat(preview.candidateLoweringOperatorRef()).isBlank();
        assertThat(preview.candidateOperator().runtimeReadiness().executable()).isFalse();
        assertThat(preview.candidateOperator().runtimeReadiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(preview.candidateOperator().lowering().parameters())
                .containsEntry("runtimeBindingApplyKind", "server-recomputed-executable-readiness")
                .containsEntry("runtimeBindingId", binding.bindingId())
                .containsEntry("adapterActivationId", activation.activationId())
                .containsEntry("executableLoweringIntegrationId", "risk-eligibility-worker-lowering")
                .containsEntry("executorKind", "worker-dispatcher")
                .containsEntry("executorEntrypoint", "worker:risk.eligibility");
        assertThat(preview.diagnostics()).isEmpty();
    }

    @Test
    void executableReadinessRecomputeApplyGovernanceGatesAndWritesLibraryRevision() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();

        var ackRequired = fixture.controller().applyExecutableReadinessRecompute(
                "risk:eligibility",
                false,
                "",
                "",
                "",
                ""
        );
        var missingGovernance = fixture.controller().applyExecutableReadinessRecompute(
                "risk:eligibility",
                true,
                "",
                "visual-canvas-test",
                "Promote risk eligibility executable readiness.",
                ""
        );
        var appliedResponse = fixture.controller().applyExecutableReadinessRecompute(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Promote risk eligibility executable readiness.",
                "Runtime implementation, activation, and executor bridge evidence were reviewed."
        );

        assertThat(ackRequired.getStatusCode().value()).isEqualTo(409);
        assertThat(ackRequired.getBody()).isNotNull();
        assertThat(ackRequired.getBody().applied()).isFalse();
        assertThat(ackRequired.getBody().state()).isEqualTo("ack-required");
        assertThat(ackRequired.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessRecompute.ackWarningsRequired");
        assertThat(missingGovernance.getStatusCode().value()).isEqualTo(400);
        assertThat(missingGovernance.getBody()).isNotNull();
        assertThat(missingGovernance.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessRecompute.governanceEvidenceMissing");
        assertThat(appliedResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(appliedResponse.getBody()).isNotNull();
        VisualExecutableReadinessRecomputeResult result = appliedResponse.getBody();
        assertThat(result.schemaVersion()).isEqualTo(VisualExecutableReadinessRecomputeResult.SCHEMA_VERSION);
        assertThat(result.applied()).isTrue();
        assertThat(result.state()).isEqualTo("applied");
        assertThat(result.operatorRef()).isEqualTo("risk:eligibility");
        assertThat(result.operatorLibraryId()).isEqualTo("risk-policy-design");
        assertThat(result.currentOperatorFingerprint()).isEqualTo(fixture.contract().fingerprint());
        assertThat(result.candidateOperatorFingerprint()).isNotEqualTo(result.currentOperatorFingerprint());
        assertThat(result.preview().activeBindingId()).isEqualTo(binding.bindingId());
        assertThat(result.preview().activeAdapterActivationId()).isEqualTo(activation.activationId());
        assertThat(result.preview().activeExecutableLoweringIntegrationId()).isEqualTo(integration.integrationId());
        assertThat(result.storedLibrary()).isNotNull();
        assertThat(result.storedRevision()).isNotNull();
        assertThat(result.libraryRevision()).isEqualTo(2);
        assertThat(result.storedRevision().action()).isEqualTo("REPLACE");
        assertThat(result.storedRevision().revisionMetadata().actor()).isEqualTo("runtime-platform");
        assertThat(result.storedRevision().revisionMetadata().changeSource()).isEqualTo("visual-canvas-test");
        assertThat(result.storedRevision().revisionMetadata().reason())
                .contains("Runtime implementation, activation, and executor bridge evidence were reviewed.");
        assertThat(fixture.libraries().revisions("risk-policy-design")).hasSize(2);
        OperatorDefinition trustedOperator = fixture.catalog().find("risk:eligibility").orElseThrow();
        assertThat(trustedOperator.fingerprint()).isEqualTo(result.candidateOperatorFingerprint());
        assertThat(trustedOperator.runtimeReadiness().state()).isEqualTo("RUNTIME_EXECUTABLE");
        assertThat(trustedOperator.lowering().mode()).isEqualTo("native");
        assertThat(trustedOperator.lowering().operatorRef()).isEqualTo("operator:risk:eligibility");

        var afterApplyPreview = fixture.controller().previewExecutableReadinessRecompute("risk:eligibility");
        assertThat(afterApplyPreview.getStatusCode().value()).isEqualTo(200);
        assertThat(afterApplyPreview.getBody()).isNotNull();
        assertThat(afterApplyPreview.getBody().recomputable()).isFalse();
        assertThat(afterApplyPreview.getBody().state()).isEqualTo("blocked");
        assertThat(afterApplyPreview.getBody().currentRuntimeReadinessState()).isEqualTo("RUNTIME_EXECUTABLE");
        assertThat(afterApplyPreview.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessRecompute.promotionStateNotReady");
    }

    @Test
    void executableReadinessRecomputeApplyReturnsFailedWhenLibraryRevisionWriteFails() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingExecutableReadinessApplyOperatorLibraryRegistry("risk-policy-design"));
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"));
        VisualExecutableReadinessRecomputePreview preview = fixture.controller()
                .previewExecutableReadinessRecompute("risk:eligibility")
                .getBody();

        var response = fixture.controller().applyExecutableReadinessRecompute(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Promote risk eligibility executable readiness.",
                "Runtime implementation, activation, and executor bridge evidence were reviewed.",
                preview.currentOperatorFingerprint(),
                preview.candidateOperatorFingerprint()
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        VisualExecutableReadinessRecomputeResult result = response.getBody();
        assertThat(result.applied()).isFalse();
        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.level()).isEqualTo("error");
        assertThat(result.libraryRevision()).isZero();
        assertThat(result.storedLibrary()).isNull();
        assertThat(result.storedRevision()).isNull();
        assertThat(result.preview().candidateOperatorFingerprint()).isEqualTo(preview.candidateOperatorFingerprint());
        assertThat(result.diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.executableReadinessRecompute.libraryRevisionWriteFailed");
        VisualDiagnostic diagnostic = result.diagnostics().getFirst();
        assertThat(diagnostic.target()).isEqualTo("/operatorLibraryId");
        assertThat(diagnostic.metadata())
                .containsEntry("operatorRef", "risk:eligibility")
                .containsEntry("operatorLibraryId", "risk-policy-design")
                .containsEntry("exceptionType", "IllegalStateException");
        assertThat(fixture.libraries().revisions("risk-policy-design")).hasSize(1);
        OperatorDefinition trustedOperator = fixture.catalog().find("risk:eligibility").orElseThrow();
        assertThat(trustedOperator.fingerprint()).isEqualTo(preview.currentOperatorFingerprint());
        assertThat(trustedOperator.runtimeReadiness().state()).isEqualTo("DESIGN_ONLY");
    }

    @Test
    void executableReadinessRecomputeApplyRejectsStaleExpectedFingerprints() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"));
        VisualExecutableReadinessRecomputePreview preview = fixture.controller()
                .previewExecutableReadinessRecompute("risk:eligibility")
                .getBody();

        var staleCurrent = fixture.controller().applyExecutableReadinessRecompute(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Promote stale preview.",
                "Caller reviewed an older current operator fingerprint.",
                "sha256:stale-current",
                preview.candidateOperatorFingerprint()
        );
        var staleCandidate = fixture.controller().applyExecutableReadinessRecompute(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Promote stale preview.",
                "Caller reviewed an older candidate operator fingerprint.",
                preview.currentOperatorFingerprint(),
                "sha256:stale-candidate"
        );

        assertThat(staleCurrent.getStatusCode().value()).isEqualTo(409);
        assertThat(staleCurrent.getBody()).isNotNull();
        assertThat(staleCurrent.getBody().applied()).isFalse();
        assertThat(staleCurrent.getBody().state()).isEqualTo("stale");
        assertThat(staleCurrent.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessRecompute.expectedCurrentOperatorFingerprintMismatch");
        assertThat(staleCandidate.getStatusCode().value()).isEqualTo(409);
        assertThat(staleCandidate.getBody()).isNotNull();
        assertThat(staleCandidate.getBody().applied()).isFalse();
        assertThat(staleCandidate.getBody().state()).isEqualTo("stale");
        assertThat(staleCandidate.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessRecompute.expectedCandidateOperatorFingerprintMismatch");
        assertThat(fixture.libraries().revisions("risk-policy-design")).hasSize(1);
        assertThat(fixture.catalog().find("risk:eligibility").orElseThrow().fingerprint())
                .isEqualTo(preview.currentOperatorFingerprint());
    }

    @Test
    void executableReadinessRecomputeApplyWritesExternalLoweringRevisionWithoutLocalExecution() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                remoteWorkerImplementation("risk-eligibility-worker-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(
                        activation,
                        "risk-eligibility-worker-lowering",
                        "remote-worker",
                        "worker-dispatcher",
                        "worker:risk.eligibility"
                ));

        var response = fixture.controller().applyExecutableReadinessRecompute(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Try executable readiness recompute.",
                "Executor bridge was reviewed."
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        VisualExecutableReadinessRecomputeResult result = response.getBody();
        assertThat(result.applied()).isTrue();
        assertThat(result.state()).isEqualTo("applied");
        assertThat(result.libraryRevision()).isEqualTo(2);
        assertThat(result.preview().candidateRuntimeReadinessState()).isEqualTo("EXTERNAL_RUNTIME_BOUND");
        assertThat(result.preview().candidateLoweringMode()).isEqualTo("remote-worker");
        assertThat(result.preview().candidateLoweringOperatorRef()).isBlank();
        assertThat(fixture.libraries().revisions("risk-policy-design")).hasSize(2);
        OperatorDefinition trustedOperator = fixture.catalog().find("risk:eligibility").orElseThrow();
        assertThat(trustedOperator.fingerprint()).isEqualTo(result.candidateOperatorFingerprint());
        assertThat(trustedOperator.runtimeReadiness().state()).isEqualTo("EXTERNAL_RUNTIME_BOUND");
        assertThat(trustedOperator.runtimeReadiness().executable()).isFalse();
        assertThat(trustedOperator.lowering().mode()).isEqualTo("remote-worker");
        assertThat(trustedOperator.lowering().operatorRef()).isBlank();
        assertThat(trustedOperator.lowering().parameters())
                .containsEntry("runtimeBindingApplyKind", "server-recomputed-executable-readiness")
                .containsEntry("executorKind", "worker-dispatcher")
                .containsEntry("executorEntrypoint", "worker:risk.eligibility");

        var runtimeProjection = fixture.catalog()
                .runtimeBindingProjections(OperatorCatalogQuery.all(), List.of(trustedOperator))
                .getFirst();
        assertThat(runtimeProjection.projectionState()).isEqualTo("binding-drifted");
        assertThat(runtimeProjection.executable()).isFalse();
        var promotionProjection = fixture.catalog()
                .executablePromotionProjections(OperatorCatalogQuery.all(), List.of(runtimeProjection))
                .getFirst();
        assertThat(promotionProjection.promotionState()).isEqualTo("binding-drifted");
        assertThat(promotionProjection.executableNow()).isFalse();
    }

    @Test
    void executableReadinessEvidenceRefreshGovernanceGatesAndRebindsPostApplyEvidenceChain() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();
        VisualExecutableReadinessRecomputeResult applied = fixture.controller()
                .applyExecutableReadinessRecompute(
                        "risk:eligibility",
                        true,
                        "runtime-platform",
                        "visual-canvas-test",
                        "Promote risk eligibility executable readiness.",
                        "Runtime implementation, activation, and executor bridge evidence were reviewed.")
                .getBody();

        var ackRequired = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
        var missingGovernance = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "",
                "visual-canvas-test",
                "Refresh evidence.",
                "",
                "",
                "",
                ""
        );
        var refreshedResponse = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh post-apply runtime evidence.",
                "Executable operator revision was applied; runtime evidence should point at the current fingerprint.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh"
        );

        assertThat(ackRequired.getStatusCode().value()).isEqualTo(409);
        assertThat(ackRequired.getBody()).isNotNull();
        assertThat(ackRequired.getBody().state()).isEqualTo("ack-required");
        assertThat(ackRequired.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.ackWarningsRequired");
        assertThat(missingGovernance.getStatusCode().value()).isEqualTo(400);
        assertThat(missingGovernance.getBody()).isNotNull();
        assertThat(missingGovernance.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.governanceEvidenceMissing");
        assertThat(refreshedResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(refreshedResponse.getBody()).isNotNull();
        VisualExecutableReadinessEvidenceRefreshResult refreshed = refreshedResponse.getBody();
        assertThat(refreshed.schemaVersion())
                .isEqualTo(VisualExecutableReadinessEvidenceRefreshResult.SCHEMA_VERSION);
        assertThat(refreshed.refreshed()).isTrue();
        assertThat(refreshed.state()).isEqualTo("refreshed");
        assertThat(refreshed.operatorRef()).isEqualTo("risk:eligibility");
        assertThat(refreshed.previousOperatorFingerprint()).isEqualTo(binding.operatorFingerprint());
        assertThat(refreshed.currentOperatorFingerprint()).isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.changeCategories()).contains("RUNTIME_BINDING");
        assertThat(refreshed.sourceBinding().bindingId()).isEqualTo(binding.bindingId());
        assertThat(refreshed.sourceBinding().state()).isEqualTo("superseded");
        assertThat(refreshed.sourceBinding().supersededByBindingId())
                .isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(refreshed.refreshedBinding().bindingId()).isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(refreshed.refreshedBinding().state()).isEqualTo("bound");
        assertThat(refreshed.refreshedBinding().supersedesBindingId()).isEqualTo(binding.bindingId());
        assertThat(refreshed.refreshedBinding().operatorFingerprint())
                .isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.refreshedBinding().operatorContract().fingerprint())
                .isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.sourceActivation().activationId()).isEqualTo(activation.activationId());
        assertThat(refreshed.refreshedActivation().activationId())
                .isEqualTo("risk-eligibility-prod-active-refresh");
        assertThat(refreshed.refreshedActivation().bindingId())
                .isEqualTo(refreshed.refreshedBinding().bindingId());
        assertThat(refreshed.refreshedActivation().bindingRevision())
                .isEqualTo(refreshed.refreshedBinding().revision());
        assertThat(refreshed.refreshedActivation().operatorFingerprint())
                .isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.refreshedActivation().evidence())
                .extracting(VisualRuntimeAdapterActivation.Evidence::kind)
                .contains("post-apply-refresh");
        assertThat(refreshed.sourceIntegration().integrationId()).isEqualTo(integration.integrationId());
        assertThat(refreshed.refreshedIntegration().integrationId())
                .isEqualTo("risk-eligibility-lowering-v1-refresh");
        assertThat(refreshed.refreshedIntegration().activationId())
                .isEqualTo(refreshed.refreshedActivation().activationId());
        assertThat(refreshed.refreshedIntegration().bindingId())
                .isEqualTo(refreshed.refreshedBinding().bindingId());
        assertThat(refreshed.refreshedIntegration().operatorFingerprint())
                .isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.refreshedIntegration().evidence())
                .extracting(VisualExecutableLoweringIntegration.Evidence::kind)
                .contains("post-apply-refresh");

        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "superseded"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(binding.bindingId());
        var afterRefreshPreview = fixture.controller().previewExecutableReadinessRecompute("risk:eligibility");
        assertThat(afterRefreshPreview.getStatusCode().value()).isEqualTo(200);
        assertThat(afterRefreshPreview.getBody()).isNotNull();
        assertThat(afterRefreshPreview.getBody().state()).isEqualTo("not-required");
        assertThat(afterRefreshPreview.getBody().currentRuntimeReadinessState()).isEqualTo("RUNTIME_EXECUTABLE");
        assertThat(afterRefreshPreview.getBody().activeBindingId())
                .isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(afterRefreshPreview.getBody().activeAdapterActivationId())
                .isEqualTo("risk-eligibility-prod-active-refresh");
    }

    @Test
    void executableReadinessEvidenceRefreshCompensatesFailedIntegrationCreate() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingRefreshLoweringIntegrationRepository("risk-eligibility-lowering-v1-refresh"));
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();
        VisualExecutableReadinessRecomputeResult applied = fixture.controller()
                .applyExecutableReadinessRecompute(
                        "risk:eligibility",
                        true,
                        "runtime-platform",
                        "visual-canvas-test",
                        "Promote risk eligibility executable readiness.",
                        "Runtime implementation, activation, and executor bridge evidence were reviewed.")
                .getBody();

        var failedRefresh = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh post-apply runtime evidence.",
                "Executable operator revision was applied; runtime evidence should point at the current fingerprint.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh"
        );

        assertThat(failedRefresh.getStatusCode().value()).isEqualTo(409);
        assertThat(failedRefresh.getBody()).isNotNull();
        VisualExecutableReadinessEvidenceRefreshResult result = failedRefresh.getBody();
        assertThat(result.refreshed()).isFalse();
        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.previousOperatorFingerprint()).isEqualTo(binding.operatorFingerprint());
        assertThat(result.currentOperatorFingerprint()).isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(result.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.integrationCreateFailed")
                .doesNotContain("visual.executableReadinessEvidenceRefresh.compensationFailed");
        assertThat(result.sourceBinding().bindingId()).isEqualTo(binding.bindingId());
        assertThat(result.sourceBinding().state()).isEqualTo("bound");
        assertThat(result.sourceBinding().supersededByBindingId()).isBlank();
        assertThat(result.refreshedBinding().bindingId()).isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(result.refreshedBinding().state()).isEqualTo("failed");
        assertThat(result.refreshedBinding().supersedesBindingId()).isEqualTo(binding.bindingId());
        assertThat(result.sourceActivation().activationId()).isEqualTo(activation.activationId());
        assertThat(result.refreshedActivation().activationId())
                .isEqualTo("risk-eligibility-prod-active-refresh");
        assertThat(result.refreshedActivation().state()).isEqualTo(VisualRuntimeAdapterActivation.STATE_FAILED);
        assertThat(result.sourceIntegration().integrationId()).isEqualTo(integration.integrationId());
        assertThat(result.refreshedIntegration()).isNull();

        VisualRuntimeBindingImplementationBinding restoredSource =
                fixture.implementationRepository().find(binding.bindingId()).orElseThrow();
        VisualRuntimeBindingImplementationBinding failedRefreshed =
                fixture.implementationRepository().find("risk-eligibility-native-v1-refresh").orElseThrow();
        VisualRuntimeAdapterActivation failedActivation =
                fixture.adapterActivationRepository().find("risk-eligibility-prod-active-refresh").orElseThrow();
        assertThat(restoredSource.bound()).isTrue();
        assertThat(restoredSource.supersededByBindingId()).isBlank();
        assertThat(failedRefreshed.failed()).isTrue();
        assertThat(failedActivation.state()).isEqualTo(VisualRuntimeAdapterActivation.STATE_FAILED);
        assertThat(fixture.adapterActivationRepository().findActiveByBindingId(binding.bindingId()))
                .get()
                .extracting(VisualRuntimeAdapterActivation::activationId)
                .isEqualTo(activation.activationId());
        assertThat(fixture.adapterActivationRepository().findActiveByBindingId(failedRefreshed.bindingId()))
                .isEmpty();
        assertThat(fixture.executableLoweringIntegrationRepository()
                .find("risk-eligibility-lowering-v1-refresh"))
                .isEmpty();
        assertThat(fixture.executableLoweringIntegrationRepository()
                .findActiveByActivationId(activation.activationId()))
                .get()
                .extracting(VisualExecutableLoweringIntegration::integrationId)
                .isEqualTo(integration.integrationId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(binding.bindingId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "failed"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "superseded"))
                .isEmpty();

        OperatorDefinition trustedOperator = fixture.catalog().find("risk:eligibility").orElseThrow();
        var runtimeProjection = fixture.catalog()
                .runtimeBindingProjections(OperatorCatalogQuery.all(), List.of(trustedOperator))
                .getFirst();
        assertThat(runtimeProjection.projectionState()).isEqualTo("binding-drifted");
        assertThat(runtimeProjection.activeBindingId()).isEqualTo(binding.bindingId());
        assertThat(runtimeProjection.activeAdapterActivationId()).isEqualTo(activation.activationId());
    }

    @Test
    void executableReadinessEvidenceRefreshReturnsFailedWhenRefreshedBindingCreateFails() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingRefreshBindingCreateRepository("risk-eligibility-native-v1-refresh"),
                new InMemoryVisualExecutableLoweringIntegrationRepository());
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();
        VisualExecutableReadinessRecomputeResult applied = fixture.controller()
                .applyExecutableReadinessRecompute(
                        "risk:eligibility",
                        true,
                        "runtime-platform",
                        "visual-canvas-test",
                        "Promote risk eligibility executable readiness.",
                        "Runtime implementation, activation, and executor bridge evidence were reviewed.")
                .getBody();

        var failedRefresh = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh post-apply runtime evidence.",
                "Executable operator revision was applied; runtime evidence should point at the current fingerprint.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh"
        );

        assertThat(failedRefresh.getStatusCode().value()).isEqualTo(409);
        assertThat(failedRefresh.getBody()).isNotNull();
        VisualExecutableReadinessEvidenceRefreshResult result = failedRefresh.getBody();
        assertThat(result.refreshed()).isFalse();
        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.previousOperatorFingerprint()).isEqualTo(binding.operatorFingerprint());
        assertThat(result.currentOperatorFingerprint()).isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(result.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.bindingCreateFailed")
                .doesNotContain("visual.executableReadinessEvidenceRefresh.compensationFailed");
        assertThat(result.sourceBinding().bindingId()).isEqualTo(binding.bindingId());
        assertThat(result.sourceBinding().state()).isEqualTo("bound");
        assertThat(result.refreshedBinding()).isNull();
        assertThat(result.sourceActivation().activationId()).isEqualTo(activation.activationId());
        assertThat(result.refreshedActivation()).isNull();
        assertThat(result.sourceIntegration().integrationId()).isEqualTo(integration.integrationId());
        assertThat(result.refreshedIntegration()).isNull();
        assertThat(fixture.implementationRepository().find("risk-eligibility-native-v1-refresh")).isEmpty();
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(binding.bindingId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "superseded"))
                .isEmpty();
    }

    @Test
    void executableReadinessEvidenceRefreshCompensatesFailedBindingTransition() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new FailingSupersedeImplementationRepository("risk-eligibility-native-v1"),
                new InMemoryVisualExecutableLoweringIntegrationRepository());
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();
        VisualExecutableReadinessRecomputeResult applied = fixture.controller()
                .applyExecutableReadinessRecompute(
                        "risk:eligibility",
                        true,
                        "runtime-platform",
                        "visual-canvas-test",
                        "Promote risk eligibility executable readiness.",
                        "Runtime implementation, activation, and executor bridge evidence were reviewed.")
                .getBody();

        var failedRefresh = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh post-apply runtime evidence.",
                "Executable operator revision was applied; runtime evidence should point at the current fingerprint.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh"
        );

        assertThat(failedRefresh.getStatusCode().value()).isEqualTo(409);
        assertThat(failedRefresh.getBody()).isNotNull();
        VisualExecutableReadinessEvidenceRefreshResult result = failedRefresh.getBody();
        assertThat(result.refreshed()).isFalse();
        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.previousOperatorFingerprint()).isEqualTo(binding.operatorFingerprint());
        assertThat(result.currentOperatorFingerprint()).isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(result.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.bindingTransitionFailed")
                .doesNotContain("visual.executableReadinessEvidenceRefresh.compensationFailed");
        assertThat(result.sourceBinding().bindingId()).isEqualTo(binding.bindingId());
        assertThat(result.sourceBinding().state()).isEqualTo("bound");
        assertThat(result.sourceBinding().supersededByBindingId()).isBlank();
        assertThat(result.refreshedBinding().bindingId()).isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(result.refreshedBinding().state()).isEqualTo("failed");
        assertThat(result.refreshedBinding().supersedesBindingId()).isEqualTo(binding.bindingId());
        assertThat(result.sourceActivation().activationId()).isEqualTo(activation.activationId());
        assertThat(result.refreshedActivation()).isNull();
        assertThat(result.sourceIntegration().integrationId()).isEqualTo(integration.integrationId());
        assertThat(result.refreshedIntegration()).isNull();
        assertThat(fixture.adapterActivationRepository().find("risk-eligibility-prod-active-refresh"))
                .isEmpty();
        assertThat(fixture.executableLoweringIntegrationRepository()
                .find("risk-eligibility-lowering-v1-refresh"))
                .isEmpty();
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(binding.bindingId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "failed"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "superseded"))
                .isEmpty();
    }

    @Test
    void executableReadinessEvidenceRefreshCompensatesFailedActivationCreate() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture(
                new InMemoryVisualRuntimeBindingImplementationRepository(),
                new FailingRefreshAdapterActivationRepository("risk-eligibility-prod-active-refresh"),
                new InMemoryVisualExecutableLoweringIntegrationRepository());
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"))
                .getBody();
        VisualExecutableReadinessRecomputeResult applied = fixture.controller()
                .applyExecutableReadinessRecompute(
                        "risk:eligibility",
                        true,
                        "runtime-platform",
                        "visual-canvas-test",
                        "Promote risk eligibility executable readiness.",
                        "Runtime implementation, activation, and executor bridge evidence were reviewed.")
                .getBody();

        var failedRefresh = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh post-apply runtime evidence.",
                "Executable operator revision was applied; runtime evidence should point at the current fingerprint.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh"
        );

        assertThat(failedRefresh.getStatusCode().value()).isEqualTo(409);
        assertThat(failedRefresh.getBody()).isNotNull();
        VisualExecutableReadinessEvidenceRefreshResult result = failedRefresh.getBody();
        assertThat(result.refreshed()).isFalse();
        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.previousOperatorFingerprint()).isEqualTo(binding.operatorFingerprint());
        assertThat(result.currentOperatorFingerprint()).isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(result.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.activationCreateFailed")
                .doesNotContain("visual.executableReadinessEvidenceRefresh.compensationFailed");
        assertThat(result.sourceBinding().bindingId()).isEqualTo(binding.bindingId());
        assertThat(result.sourceBinding().state()).isEqualTo("bound");
        assertThat(result.sourceBinding().supersededByBindingId()).isBlank();
        assertThat(result.refreshedBinding().bindingId()).isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(result.refreshedBinding().state()).isEqualTo("failed");
        assertThat(result.sourceActivation().activationId()).isEqualTo(activation.activationId());
        assertThat(result.refreshedActivation()).isNull();
        assertThat(result.sourceIntegration().integrationId()).isEqualTo(integration.integrationId());
        assertThat(result.refreshedIntegration()).isNull();
        assertThat(fixture.adapterActivationRepository().find("risk-eligibility-prod-active-refresh"))
                .isEmpty();
        assertThat(fixture.executableLoweringIntegrationRepository()
                .find("risk-eligibility-lowering-v1-refresh"))
                .isEmpty();
        assertThat(fixture.adapterActivationRepository().findActiveByBindingId(binding.bindingId()))
                .get()
                .extracting(VisualRuntimeAdapterActivation::activationId)
                .isEqualTo(activation.activationId());
        assertThat(fixture.executableLoweringIntegrationRepository()
                .findActiveByActivationId(activation.activationId()))
                .get()
                .extracting(VisualExecutableLoweringIntegration::integrationId)
                .isEqualTo(integration.integrationId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(binding.bindingId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "failed"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo("risk-eligibility-native-v1-refresh");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "superseded"))
                .isEmpty();
    }

    @Test
    void executableReadinessEvidenceRefreshRejectsStaleExpectedFingerprints() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"));
        VisualExecutableReadinessRecomputeResult applied = fixture.controller()
                .applyExecutableReadinessRecompute(
                        "risk:eligibility",
                        true,
                        "runtime-platform",
                        "visual-canvas-test",
                        "Promote risk eligibility executable readiness.",
                        "Runtime implementation, activation, and executor bridge evidence were reviewed.")
                .getBody();

        var stalePrevious = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh stale evidence.",
                "Caller reviewed an older source evidence fingerprint.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh",
                "sha256:stale-previous",
                applied.candidateOperatorFingerprint()
        );
        var staleCurrent = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh stale evidence.",
                "Caller reviewed an older current operator fingerprint.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh",
                binding.operatorFingerprint(),
                "sha256:stale-current"
        );

        assertThat(stalePrevious.getStatusCode().value()).isEqualTo(409);
        assertThat(stalePrevious.getBody()).isNotNull();
        assertThat(stalePrevious.getBody().refreshed()).isFalse();
        assertThat(stalePrevious.getBody().state()).isEqualTo("stale");
        assertThat(stalePrevious.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.expectedPreviousOperatorFingerprintMismatch");
        assertThat(staleCurrent.getStatusCode().value()).isEqualTo(409);
        assertThat(staleCurrent.getBody()).isNotNull();
        assertThat(staleCurrent.getBody().refreshed()).isFalse();
        assertThat(staleCurrent.getBody().state()).isEqualTo("stale");
        assertThat(staleCurrent.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.expectedCurrentOperatorFingerprintMismatch");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(binding.bindingId());
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "superseded"))
                .isEmpty();
    }

    @Test
    void executableReadinessEvidenceRefreshRebindsExternalLoweringWithoutRecomputeLoop() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                remoteWorkerImplementation("risk-eligibility-worker-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-worker-active"))
                .getBody();
        VisualExecutableLoweringIntegration integration = (VisualExecutableLoweringIntegration) fixture.controller()
                .submitExecutableLoweringIntegration(
                        executableLoweringIntegrationRequest(
                                activation,
                                "risk-eligibility-worker-lowering",
                                "remote-worker",
                                "worker-dispatcher",
                                "worker:risk.eligibility"))
                .getBody();
        VisualExecutableReadinessRecomputeResult applied = fixture.controller()
                .applyExecutableReadinessRecompute(
                        "risk:eligibility",
                        true,
                        "runtime-platform",
                        "visual-canvas-test",
                        "Promote risk eligibility external runtime binding.",
                        "Remote worker implementation, activation, and executor bridge evidence were reviewed.")
                .getBody();

        var refreshedResponse = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh external runtime evidence.",
                "External runtime-bound operator revision was applied; evidence should point at the current fingerprint.",
                "risk-eligibility-worker-v1-refresh",
                "risk-eligibility-worker-active-refresh",
                "risk-eligibility-worker-lowering-refresh"
        );

        assertThat(refreshedResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(refreshedResponse.getBody()).isNotNull();
        VisualExecutableReadinessEvidenceRefreshResult refreshed = refreshedResponse.getBody();
        assertThat(refreshed.refreshed()).isTrue();
        assertThat(refreshed.currentOperatorFingerprint()).isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.sourceBinding().bindingId()).isEqualTo(binding.bindingId());
        assertThat(refreshed.sourceActivation().activationId()).isEqualTo(activation.activationId());
        assertThat(refreshed.sourceIntegration().integrationId()).isEqualTo(integration.integrationId());
        assertThat(refreshed.refreshedBinding().operatorFingerprint())
                .isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.refreshedActivation().operatorFingerprint())
                .isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.refreshedIntegration().operatorFingerprint())
                .isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(refreshed.refreshedIntegration().loweringMode()).isEqualTo("remote-worker");
        assertThat(refreshed.refreshedIntegration().executorKind()).isEqualTo("worker-dispatcher");
        assertThat(refreshed.refreshedIntegration().executorEntrypoint()).isEqualTo("worker:risk.eligibility");

        OperatorDefinition trustedOperator = fixture.catalog().find("risk:eligibility").orElseThrow();
        assertThat(trustedOperator.runtimeReadiness().state()).isEqualTo("EXTERNAL_RUNTIME_BOUND");
        assertThat(trustedOperator.runtimeReadiness().executable()).isFalse();
        var runtimeProjection = fixture.catalog()
                .runtimeBindingProjections(OperatorCatalogQuery.all(), List.of(trustedOperator))
                .getFirst();
        assertThat(runtimeProjection.projectionState()).isEqualTo("external-runtime-bound");
        assertThat(runtimeProjection.executable()).isFalse();
        assertThat(runtimeProjection.activeBindingId()).isEqualTo("risk-eligibility-worker-v1-refresh");
        assertThat(runtimeProjection.activeAdapterActivationId()).isEqualTo("risk-eligibility-worker-active-refresh");
        var promotionProjection = fixture.catalog()
                .executablePromotionProjections(OperatorCatalogQuery.all(), List.of(runtimeProjection))
                .getFirst();
        assertThat(promotionProjection.promotionState()).isEqualTo("external-runtime-bound");
        assertThat(promotionProjection.promotionReady()).isTrue();
        assertThat(promotionProjection.executableNow()).isFalse();
        assertThat(promotionProjection.activeExecutableLoweringIntegrationId())
                .isEqualTo("risk-eligibility-worker-lowering-refresh");

        var afterRefreshPreview = fixture.controller().previewExecutableReadinessRecompute("risk:eligibility");
        assertThat(afterRefreshPreview.getStatusCode().value()).isEqualTo(200);
        assertThat(afterRefreshPreview.getBody()).isNotNull();
        assertThat(afterRefreshPreview.getBody().state()).isEqualTo("not-required");
        assertThat(afterRefreshPreview.getBody().currentRuntimeReadinessState()).isEqualTo("EXTERNAL_RUNTIME_BOUND");
        assertThat(afterRefreshPreview.getBody().activeBindingId())
                .isEqualTo("risk-eligibility-worker-v1-refresh");
    }

    @Test
    void executableReadinessEvidenceRefreshBlocksBeforeExecutableApply() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"));

        var response = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Refresh evidence too early.",
                "Operator readiness has not been applied yet.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().refreshed()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("blocked");
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.operatorNotExecutable");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(binding.bindingId());
    }

    @Test
    void executableReadinessEvidenceRefreshDoesNotReportCurrentForIncompleteCurrentEvidenceChain() {
        RuntimeBindingImplementationFixture fixture = runtimeBindingImplementationFixture();
        VisualRuntimeBindingImplementationBinding stored = submitImplementation(
                fixture,
                completeImplementation("risk-eligibility-native-v1")
        );
        VisualRuntimeBindingImplementationBinding binding = fixture.controller()
                .bindRuntimeBindingImplementation(stored.bindingId(), transitionRequest("", true))
                .getBody()
                .binding();
        VisualRuntimeAdapterActivation activation = (VisualRuntimeAdapterActivation) fixture.controller()
                .submitRuntimeAdapterActivation(adapterActivationRequest(binding, "risk-eligibility-prod-active"))
                .getBody();
        fixture.controller().submitExecutableLoweringIntegration(
                executableLoweringIntegrationRequest(activation, "risk-eligibility-lowering-v1"));
        VisualExecutableReadinessRecomputeResult applied = fixture.controller()
                .applyExecutableReadinessRecompute(
                        "risk:eligibility",
                        true,
                        "runtime-platform",
                        "visual-canvas-test",
                        "Promote risk eligibility executable readiness.",
                        "Runtime implementation, activation, and executor bridge evidence were reviewed.")
                .getBody();
        OperatorDefinition currentOperator = fixture.catalog().find("risk:eligibility").orElseThrow();
        VisualRuntimeBindingImplementationValidation.Request currentBindingRequest =
                new VisualRuntimeBindingImplementationValidation.Request(
                        VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                        currentOperator.operatorRef(),
                        currentOperator.fingerprint(),
                        fixture.handoffBundle().bundleFingerprint(),
                        fixture.handoffBundle().requirementKeys(),
                        VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot.from(currentOperator),
                        completeImplementation("risk-eligibility-native-current-only"));
        Object submittedCurrent = fixture.controller().submitRuntimeBindingImplementation(currentBindingRequest)
                .getBody();
        assertThat(submittedCurrent).isInstanceOf(VisualRuntimeBindingImplementationBinding.class);
        VisualRuntimeBindingImplementationBinding currentBinding =
                (VisualRuntimeBindingImplementationBinding) submittedCurrent;
        assertThat(fixture.controller()
                .supersedeRuntimeBindingImplementation(binding.bindingId(),
                        transitionRequest(currentBinding.bindingId(), true))
                .getStatusCode().value())
                .isEqualTo(200);

        var response = fixture.controller().refreshExecutableReadinessEvidence(
                "risk:eligibility",
                true,
                "runtime-platform",
                "visual-canvas-test",
                "Retry evidence refresh after partial control-plane mutation.",
                "Binding points at the current operator fingerprint, but runtime evidence is incomplete.",
                "risk-eligibility-native-v1-refresh",
                "risk-eligibility-prod-active-refresh",
                "risk-eligibility-lowering-v1-refresh"
        );

        assertThat(currentOperator.fingerprint()).isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().refreshed()).isFalse();
        assertThat(response.getBody().state()).isEqualTo("blocked");
        assertThat(response.getBody().sourceBinding().bindingId()).isEqualTo(currentBinding.bindingId());
        assertThat(response.getBody().currentOperatorFingerprint())
                .isEqualTo(applied.candidateOperatorFingerprint());
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.executableReadinessEvidenceRefresh.activationMissing");
        assertThat(fixture.controller().runtimeBindingImplementationBindings("risk:eligibility", "bound"))
                .singleElement()
                .extracting(VisualRuntimeBindingImplementationBinding::bindingId)
                .isEqualTo(currentBinding.bindingId());
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

    private record RuntimeBindingImplementationFixture(
            VisualAssetOverviewController controller,
            DefaultVisualOperatorCatalog catalog,
            InMemoryOperatorLibraryRegistry libraries,
            InMemoryVisualRuntimeBindingImplementationRepository implementationRepository,
            InMemoryVisualRuntimeAdapterActivationRepository adapterActivationRepository,
            InMemoryVisualExecutableLoweringIntegrationRepository executableLoweringIntegrationRepository,
            InMemoryVisualRuntimeRolloutObservationRepository rolloutObservationRepository,
            VisualRuntimeBindingHandoffBundle handoffBundle,
            VisualRuntimeBindingHandoffBundle.OperatorContractSnapshot contract
    ) {
    }

    private static final class FailingSubmitBindingCreateRepository
            extends InMemoryVisualRuntimeBindingImplementationRepository {
        private final String failingBindingId;

        private FailingSubmitBindingCreateRepository(String failingBindingId) {
            this.failingBindingId = failingBindingId;
        }

        @Override
        public VisualRuntimeBindingImplementationBinding create(
                VisualRuntimeBindingImplementationBinding binding) {
            if (binding != null && failingBindingId.equals(binding.bindingId())) {
                throw new IllegalStateException("Injected runtime binding implementation persistence failure.");
            }
            return super.create(binding);
        }
    }

    private static final class FailingSubmitAdapterActivationRepository
            extends InMemoryVisualRuntimeAdapterActivationRepository {
        private final String failingActivationId;

        private FailingSubmitAdapterActivationRepository(String failingActivationId) {
            this.failingActivationId = failingActivationId;
        }

        @Override
        public VisualRuntimeAdapterActivation create(VisualRuntimeAdapterActivation activation) {
            if (activation != null && failingActivationId.equals(activation.activationId())) {
                throw new IllegalStateException("Injected runtime adapter activation persistence failure.");
            }
            return super.create(activation);
        }
    }

    private static final class FailingSubmitLoweringIntegrationRepository
            extends InMemoryVisualExecutableLoweringIntegrationRepository {
        private final String failingIntegrationId;

        private FailingSubmitLoweringIntegrationRepository(String failingIntegrationId) {
            this.failingIntegrationId = failingIntegrationId;
        }

        @Override
        public VisualExecutableLoweringIntegration create(VisualExecutableLoweringIntegration integration) {
            if (integration != null && failingIntegrationId.equals(integration.integrationId())) {
                throw new IllegalStateException("Injected executable lowering integration persistence failure.");
            }
            return super.create(integration);
        }
    }

    private static final class FailingRefreshLoweringIntegrationRepository
            extends InMemoryVisualExecutableLoweringIntegrationRepository {
        private final String failingIntegrationId;

        private FailingRefreshLoweringIntegrationRepository(String failingIntegrationId) {
            this.failingIntegrationId = failingIntegrationId;
        }

        @Override
        public VisualExecutableLoweringIntegration create(VisualExecutableLoweringIntegration integration) {
            if (integration != null && failingIntegrationId.equals(integration.integrationId())) {
                throw new IllegalStateException("Injected executable lowering integration persistence failure.");
            }
            return super.create(integration);
        }
    }

    private static final class FailingRefreshAdapterActivationRepository
            extends InMemoryVisualRuntimeAdapterActivationRepository {
        private final String failingActivationId;

        private FailingRefreshAdapterActivationRepository(String failingActivationId) {
            this.failingActivationId = failingActivationId;
        }

        @Override
        public VisualRuntimeAdapterActivation create(VisualRuntimeAdapterActivation activation) {
            if (activation != null && failingActivationId.equals(activation.activationId())) {
                throw new IllegalStateException("Injected runtime adapter activation persistence failure.");
            }
            return super.create(activation);
        }
    }

    private static final class FailingRefreshBindingCreateRepository
            extends InMemoryVisualRuntimeBindingImplementationRepository {
        private final String failingBindingId;

        private FailingRefreshBindingCreateRepository(String failingBindingId) {
            this.failingBindingId = failingBindingId;
        }

        @Override
        public VisualRuntimeBindingImplementationBinding create(
                VisualRuntimeBindingImplementationBinding binding) {
            if (binding != null && failingBindingId.equals(binding.bindingId())) {
                throw new IllegalStateException("Injected runtime binding implementation persistence failure.");
            }
            return super.create(binding);
        }
    }

    private static final class FailingSupersedeImplementationRepository
            extends InMemoryVisualRuntimeBindingImplementationRepository {
        private final String failingBindingId;

        private FailingSupersedeImplementationRepository(String failingBindingId) {
            this.failingBindingId = failingBindingId;
        }

        @Override
        public VisualRuntimeBindingImplementationBinding update(
                VisualRuntimeBindingImplementationBinding binding) {
            if (binding != null
                    && failingBindingId.equals(binding.bindingId())
                    && VisualRuntimeBindingImplementationBinding.STATE_SUPERSEDED.equals(binding.state())) {
                throw new IllegalStateException("Injected supersede current binding persistence failure.");
            }
            return super.update(binding);
        }
    }

    private static final class FailingBindImplementationRepository
            extends InMemoryVisualRuntimeBindingImplementationRepository {
        private final String failingBindingId;

        private FailingBindImplementationRepository(String failingBindingId) {
            this.failingBindingId = failingBindingId;
        }

        @Override
        public VisualRuntimeBindingImplementationBinding update(
                VisualRuntimeBindingImplementationBinding binding) {
            if (binding != null
                    && failingBindingId.equals(binding.bindingId())
                    && VisualRuntimeBindingImplementationBinding.STATE_BOUND.equals(binding.state())) {
                throw new IllegalStateException("Injected bind binding persistence failure.");
            }
            return super.update(binding);
        }
    }

    private static final class FailingUnbindImplementationRepository
            extends InMemoryVisualRuntimeBindingImplementationRepository {
        private final String failingBindingId;

        private FailingUnbindImplementationRepository(String failingBindingId) {
            this.failingBindingId = failingBindingId;
        }

        @Override
        public VisualRuntimeBindingImplementationBinding update(
                VisualRuntimeBindingImplementationBinding binding) {
            if (binding != null
                    && failingBindingId.equals(binding.bindingId())
                    && VisualRuntimeBindingImplementationBinding.STATE_UNBOUND.equals(binding.state())) {
                throw new IllegalStateException("Injected unbind binding persistence failure.");
            }
            return super.update(binding);
        }
    }

    private static final class FailingExecutableReadinessApplyOperatorLibraryRegistry
            extends InMemoryOperatorLibraryRegistry {
        private final String failingLibraryId;

        private FailingExecutableReadinessApplyOperatorLibraryRegistry(String failingLibraryId) {
            this.failingLibraryId = failingLibraryId;
        }

        @Override
        public OperatorLibrary upsert(OperatorLibrary library, OperatorLibraryRevision.RevisionMetadata metadata) {
            if (library != null
                    && failingLibraryId.equals(library.libraryId())
                    && !revisions(library.libraryId()).isEmpty()) {
                throw new IllegalStateException("Injected operator library revision persistence failure.");
            }
            return super.upsert(library, metadata);
        }
    }

    private static RuntimeBindingImplementationFixture runtimeBindingImplementationFixture() {
        return runtimeBindingImplementationFixture(
                new InMemoryVisualRuntimeBindingImplementationRepository(),
                new InMemoryVisualExecutableLoweringIntegrationRepository());
    }

    private static RuntimeBindingImplementationFixture runtimeBindingImplementationFixture(
            InMemoryOperatorLibraryRegistry libraries) {
        return runtimeBindingImplementationFixture(
                new InMemoryVisualRuntimeBindingImplementationRepository(),
                new InMemoryVisualRuntimeAdapterActivationRepository(),
                new InMemoryVisualExecutableLoweringIntegrationRepository(),
                libraries);
    }

    private static RuntimeBindingImplementationFixture runtimeBindingImplementationFixture(
            InMemoryVisualExecutableLoweringIntegrationRepository executableLoweringIntegrationRepository) {
        return runtimeBindingImplementationFixture(
                new InMemoryVisualRuntimeBindingImplementationRepository(),
                executableLoweringIntegrationRepository);
    }

    private static RuntimeBindingImplementationFixture runtimeBindingImplementationFixture(
            InMemoryVisualRuntimeBindingImplementationRepository implementationRepository,
            InMemoryVisualExecutableLoweringIntegrationRepository executableLoweringIntegrationRepository) {
        return runtimeBindingImplementationFixture(
                implementationRepository,
                new InMemoryVisualRuntimeAdapterActivationRepository(),
                executableLoweringIntegrationRepository);
    }

    private static RuntimeBindingImplementationFixture runtimeBindingImplementationFixture(
            InMemoryVisualRuntimeBindingImplementationRepository implementationRepository,
            InMemoryVisualRuntimeAdapterActivationRepository adapterActivationRepository,
            InMemoryVisualExecutableLoweringIntegrationRepository executableLoweringIntegrationRepository) {
        return runtimeBindingImplementationFixture(
                implementationRepository,
                adapterActivationRepository,
                executableLoweringIntegrationRepository,
                new InMemoryOperatorLibraryRegistry());
    }

    private static RuntimeBindingImplementationFixture runtimeBindingImplementationFixture(
            InMemoryVisualRuntimeBindingImplementationRepository implementationRepository,
            InMemoryVisualRuntimeAdapterActivationRepository adapterActivationRepository,
            InMemoryVisualExecutableLoweringIntegrationRepository executableLoweringIntegrationRepository,
            InMemoryOperatorLibraryRegistry libraries) {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        libraries.upsert(library);
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        InMemoryVisualRuntimeRolloutObservationRepository rolloutObservationRepository =
                new InMemoryVisualRuntimeRolloutObservationRepository();
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries,
                JavaOperatorInventoryProjector.forRegistry(null),
                publications,
                new VisualGraphPublicationOperatorProjector(),
                implementationRepository,
                adapterActivationRepository,
                executableLoweringIntegrationRepository
        );
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();
        drafts.save(draftWithFingerprint(operator));
        VisualAssetOverviewController controller = new VisualAssetOverviewController(
                drafts,
                validator,
                catalog,
                publications,
                implementationRepository,
                adapterActivationRepository,
                executableLoweringIntegrationRepository,
                rolloutObservationRepository,
                libraries
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
        return new RuntimeBindingImplementationFixture(
                controller,
                catalog,
                libraries,
                implementationRepository,
                adapterActivationRepository,
                executableLoweringIntegrationRepository,
                rolloutObservationRepository,
                handoffBundle,
                handoffBundle.operatorContracts().getFirst()
        );
    }

    private static VisualRuntimeBindingImplementationBinding submitImplementation(
            RuntimeBindingImplementationFixture fixture,
            VisualRuntimeBindingImplementationValidation.ImplementationMetadata implementation) {
        var response = fixture.controller().submitRuntimeBindingImplementation(
                implementationRequest(fixture, implementation));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isInstanceOf(VisualRuntimeBindingImplementationBinding.class);
        return (VisualRuntimeBindingImplementationBinding) response.getBody();
    }

    private static VisualRuntimeBindingImplementationValidation.Request implementationRequest(
            RuntimeBindingImplementationFixture fixture,
            VisualRuntimeBindingImplementationValidation.ImplementationMetadata implementation) {
        return new VisualRuntimeBindingImplementationValidation.Request(
                VisualRuntimeBindingImplementationValidation.REQUEST_SCHEMA_VERSION,
                fixture.contract().operatorRef(),
                fixture.contract().fingerprint(),
                fixture.handoffBundle().bundleFingerprint(),
                fixture.handoffBundle().requirementKeys(),
                fixture.contract(),
                implementation
        );
    }

    private static OperatorLibrary nonBreakingRuntimeDriftEligibilityLibrary() {
        OperatorDefinition base = VisualCatalogTestSupport.designOnlyEligibilityOperator("integer");
        SchemaEnvelope stringSchema = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of("type", "string")
        );
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                "1.0.1",
                new OperatorDefinition.Display("Remote eligibility",
                        "Evaluates eligibility through a reviewed remote worker binding.",
                        List.of("risk", "policy", "worker")),
                new OperatorDefinition.Source("remote-worker", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(
                                base.ports().inputs().getFirst(),
                                new OperatorDefinition.Port("region", stringSchema, false, "Optional routing region.")
                        ),
                        List.of(
                                base.ports().outputs().getFirst(),
                                new OperatorDefinition.Port("audit", stringSchema, false, "Optional audit marker.")
                        )
                ),
                base.configSchema(),
                new OperatorDefinition.Capabilities("READ_EXTERNAL", "IDEMPOTENT", false, true, true),
                new OperatorDefinition.Policy(List.of("tenant-a"), List.of("risk"), List.of("prod")),
                new OperatorDefinition.Lowering("remote-worker", "", Map.of(
                        "workerTopic", "workers.risk.eligibility"
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy-design",
                "Risk policy design operators",
                "1.0.1",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static VisualRuntimeBindingImplementationTransitionRequest transitionRequest(String replacementBindingId,
                                                                                         boolean ackReview) {
        return transitionRequest(replacementBindingId, ackReview, "Implementation evidence reviewed.");
    }

    private static VisualRuntimeBindingImplementationTransitionRequest transitionRequest(String replacementBindingId,
                                                                                         boolean ackReview,
                                                                                         String reason) {
        return transitionRequest(replacementBindingId, ackReview, reason, 0, 0);
    }

    private static VisualRuntimeBindingImplementationTransitionRequest transitionRequest(String replacementBindingId,
                                                                                         boolean ackReview,
                                                                                         long expectedRevision) {
        return transitionRequest(replacementBindingId, ackReview, "Implementation evidence reviewed.",
                expectedRevision, 0);
    }

    private static VisualRuntimeBindingImplementationTransitionRequest transitionRequest(
            String replacementBindingId,
            boolean ackReview,
            String reason,
            long expectedRevision,
            long expectedReplacementRevision) {
        return new VisualRuntimeBindingImplementationTransitionRequest(
                VisualRuntimeBindingImplementationTransitionRequest.SCHEMA_VERSION,
                "runtime-platform",
                reason,
                "visual-canvas-test",
                "Bind runtime implementation evidence.",
                ackReview,
                replacementBindingId,
                expectedRevision,
                expectedReplacementRevision
        );
    }

    private static VisualRuntimeAdapterActivationValidation.Request adapterActivationRequest(
            VisualRuntimeBindingImplementationBinding binding,
            String activationId) {
        return adapterActivationRequest(binding, activationId, "Runtime deployment is healthy.");
    }

    private static VisualRuntimeAdapterActivationValidation.Request adapterActivationRequest(
            VisualRuntimeBindingImplementationBinding binding,
            String activationId,
            String reason) {
        return new VisualRuntimeAdapterActivationValidation.Request(
                VisualRuntimeAdapterActivationValidation.REQUEST_SCHEMA_VERSION,
                activationId,
                binding.bindingId(),
                binding.revision(),
                binding.operatorRef(),
                binding.operatorFingerprint(),
                binding.implementation().adapterKind(),
                binding.implementation().entrypoint(),
                binding.implementation().runtimeOwner(),
                "prod",
                VisualRuntimeAdapterActivation.HEALTH_HEALTHY,
                "runtime-platform",
                "visual-canvas-test",
                reason,
                List.of(new VisualRuntimeAdapterActivation.Evidence(
                        "health-check",
                        "deployment:risk-eligibility-native-v1",
                        "Readiness probe is healthy."))
        );
    }

    private static VisualRuntimeRolloutObservationValidation.Request rolloutObservationRequest(
            VisualRuntimeAdapterActivation activation,
            String observationId,
            String observationState,
            int trafficPercent,
            boolean rollbackTriggered,
            String rollbackSignal) {
        return rolloutObservationRequest(
                activation,
                observationId,
                "canary",
                observationState,
                trafficPercent,
                rollbackTriggered,
                rollbackSignal);
    }

    private static VisualRuntimeRolloutObservationValidation.Request rolloutObservationRequest(
            VisualRuntimeAdapterActivation activation,
            String observationId,
            String rolloutStrategy,
            String observationState,
            int trafficPercent,
            boolean rollbackTriggered,
            String rollbackSignal) {
        return new VisualRuntimeRolloutObservationValidation.Request(
                VisualRuntimeRolloutObservationValidation.REQUEST_SCHEMA_VERSION,
                observationId,
                activation.activationId(),
                activation.revision(),
                activation.bindingId(),
                activation.bindingRevision(),
                activation.operatorRef(),
                activation.operatorFingerprint(),
                rolloutStrategy,
                trafficPercent,
                trafficPercent >= 100 ? "full-ramp" : "canary",
                observationState,
                rollbackTriggered,
                rollbackSignal,
                "runtime-platform",
                "visual-canvas-test",
                rollbackTriggered ? "Rollback trigger was observed." : "Rollout observation was collected.",
                List.of(new VisualRuntimeRolloutObservation.Evidence(
                        rollbackTriggered ? "rollback-event" : "canary-metric",
                        "rollout:%s".formatted(observationId),
                        rollbackTriggered
                                ? "Runtime rollout emitted rollback evidence."
                                : "Runtime rollout canary metrics stayed within guardrails.")),
                Instant.parse("2026-07-04T00:00:00Z")
        );
    }

    private static VisualExecutableLoweringIntegrationValidation.Request executableLoweringIntegrationRequest(
            VisualRuntimeAdapterActivation activation,
            String integrationId) {
        return executableLoweringIntegrationRequest(
                activation,
                integrationId,
                "native",
                "operator:risk:eligibility"
        );
    }

    private static VisualExecutableLoweringIntegrationValidation.Request executableLoweringIntegrationRequest(
            VisualRuntimeAdapterActivation activation,
            String integrationId,
            String loweringMode,
            String executorEntrypoint) {
        return executableLoweringIntegrationRequest(
                activation,
                integrationId,
                loweringMode,
                "bloge-operator-registry",
                executorEntrypoint
        );
    }

    private static VisualExecutableLoweringIntegrationValidation.Request executableLoweringIntegrationRequest(
            VisualRuntimeAdapterActivation activation,
            String integrationId,
            String loweringMode,
            String executorKind,
            String executorEntrypoint) {
        return new VisualExecutableLoweringIntegrationValidation.Request(
                VisualExecutableLoweringIntegrationValidation.REQUEST_SCHEMA_VERSION,
                integrationId,
                activation.activationId(),
                activation.revision(),
                activation.bindingId(),
                activation.bindingRevision(),
                activation.operatorRef(),
                activation.operatorFingerprint(),
                activation.adapterKind(),
                activation.entrypoint(),
                activation.runtimeEnvironment(),
                loweringMode,
                executorKind,
                executorEntrypoint,
                "operator-platform",
                "runtime-platform",
                "visual-canvas-test",
                "BLOGE executor bridge is available.",
                List.of(new VisualExecutableLoweringIntegration.Evidence(
                        "executor-test",
                        "executor-suite:risk-eligibility-native-v1",
                        "Executor bridge suite passed."))
        );
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata completeImplementation() {
        return completeImplementation("risk-eligibility-native-v1");
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata completeImplementation(
            String bindingId) {
        return completeImplementation(bindingId, "com.acme.risk.RiskEligibilityOperator");
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata completeImplementation(
            String bindingId,
            String entrypoint) {
        return new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                bindingId,
                "native",
                entrypoint,
                "risk-platform",
                List.of("request-response"),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "test",
                        "golden-suite:risk-eligibility",
                        "Golden suite passed against the exported operator contract."
                )),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "approval",
                        "change-approval:RB-42",
                        "Runtime owner approved policy and deployment scope."
                )),
                "deployment:risk-eligibility-native-v0",
                canaryRolloutPlan(),
                ""
        );
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata completeImplementationWithRollout(
            String bindingId,
            VisualRuntimeBindingImplementationValidation.RolloutPlan rolloutPlan) {
        return new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                bindingId,
                "native",
                "com.acme.risk.RiskEligibilityOperator",
                "risk-platform",
                "",
                "",
                "",
                List.of("request-response"),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "test",
                        "golden-suite:risk-eligibility",
                        "Golden suite passed against the exported operator contract."
                )),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "approval",
                        "change-approval:RB-42",
                        "Runtime owner approved policy and deployment scope."
                )),
                "deployment:risk-eligibility-native-v0",
                rolloutPlan,
                ""
        );
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata completeImplementationWithVersion(
            String bindingId,
            String implementationVersion,
            String reimplementationOfBindingId,
            String reimplementationStrategy) {
        return new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                bindingId,
                "native",
                "com.acme.risk.RiskEligibilityOperator",
                "risk-platform",
                implementationVersion,
                reimplementationOfBindingId,
                reimplementationStrategy,
                List.of("request-response"),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "test",
                        "golden-suite:risk-eligibility",
                        "Golden suite passed against the exported operator contract."
                )),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "approval",
                        "change-approval:RB-42",
                        "Runtime owner approved policy and deployment scope."
                )),
                "deployment:risk-eligibility-native-v0",
                canaryRolloutPlan(),
                ""
        );
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata remoteWorkerImplementation(
            String bindingId) {
        return new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                bindingId,
                "remote-worker",
                "workers.risk.eligibility",
                "risk-worker-platform",
                List.of("remote-worker"),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "test",
                        "worker-suite:risk-eligibility",
                        "Worker contract suite passed against the exported operator contract."
                )),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "approval",
                        "change-approval:RB-WORKER-42",
                        "Runtime owner approved worker topic, policy, and deployment scope."
                )),
                "deployment:risk-eligibility-worker-v0",
                canaryRolloutPlan(),
                ""
        );
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata remoteWorkerImplementationWithRollout(
            String bindingId,
            VisualRuntimeBindingImplementationValidation.RolloutPlan rolloutPlan) {
        return new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                bindingId,
                "remote-worker",
                "workers.risk.eligibility",
                "risk-worker-platform",
                "",
                "",
                "",
                List.of("remote-worker"),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "test",
                        "worker-suite:risk-eligibility",
                        "Worker contract suite passed against the exported operator contract."
                )),
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "approval",
                        "change-approval:RB-WORKER-42",
                        "Runtime owner approved worker topic, policy, and deployment scope."
                )),
                "deployment:risk-eligibility-worker-v0",
                rolloutPlan,
                ""
        );
    }

    private static VisualRuntimeBindingImplementationValidation.ImplementationMetadata incompleteImplementation(
            String bindingId) {
        return new VisualRuntimeBindingImplementationValidation.ImplementationMetadata(
                bindingId,
                "native",
                "com.acme.risk.RiskEligibilityOperator",
                "risk-platform",
                List.of("request-response"),
                List.of(),
                List.of(),
                "",
                ""
        );
    }

    private static VisualRuntimeBindingImplementationValidation.RolloutPlan canaryRolloutPlan() {
        return new VisualRuntimeBindingImplementationValidation.RolloutPlan(
                "canary",
                5,
                100,
                "error-rate > 2% or golden regression failure",
                "PT30M",
                List.of(new VisualRuntimeBindingImplementationValidation.Evidence(
                        "rollout-plan",
                        "change-window:RB-42",
                        "Start at 5% traffic and ramp to 100% after SLO and golden checks pass."
                )),
                "");
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
