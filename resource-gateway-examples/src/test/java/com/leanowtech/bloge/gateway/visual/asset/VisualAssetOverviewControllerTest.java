package com.leanowtech.bloge.gateway.visual.asset;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
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
        assertThat(overview.publications().total()).isEqualTo(1);
        assertThat(overview.publications().designArtifactCount()).isEqualTo(1);
        assertThat(overview.publications().artifactKindCounts()).containsEntry("DESIGN", 1);
        assertThat(overview.publications().graphReadinessStateCounts()).containsEntry("design-only", 1);
        assertThat(overview.catalog().totalOperators()).isGreaterThanOrEqualTo(1);
        assertThat(overview.catalog().facets().runtimeReadinessStates()).containsEntry("design-only", 1);
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
}
