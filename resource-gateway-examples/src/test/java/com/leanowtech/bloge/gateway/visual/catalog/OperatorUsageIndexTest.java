package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the server-side visual operator usage index.
 */
class OperatorUsageIndexTest {

    @Test
    void reportsStoredDraftAndPublicationFingerprintDrift() {
        OperatorDefinition frozenOperator = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition currentOperator = VisualCatalogTestSupport.eligibilityOperator("string");
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft storedDraft = drafts.save(draftUsingOperator(frozenOperator));
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        publications.create(publicationUsing(storedDraft, frozenOperator));

        OperatorUsageResponse response = new OperatorUsageIndex(
                drafts,
                publications,
                usageIndexCatalog(currentOperator)
        ).usage(frozenOperator.operatorRef());

        assertThat(response.schemaVersion()).isEqualTo("bloge.visualOperatorUsage.v1");
        assertThat(response.currentFingerprint()).isEqualTo(currentOperator.fingerprint());
        assertThat(response.drafts())
                .singleElement()
                .satisfies(usage -> {
                    assertThat(usage.draftId()).isEqualTo("draft-risk");
                    assertThat(usage.nodeId()).isEqualTo("eligibility");
                    assertThat(usage.savedFingerprint()).isEqualTo(frozenOperator.fingerprint());
                    assertThat(usage.fingerprintStatus()).isEqualTo("DRIFTED");
                    assertThat(usage.changedSurface()).contains("input port 'inputs' schema changed");
                    assertThat(usage.changeRisk()).isEqualTo("BREAKING_SCHEMA");
                    assertThat(usage.changeCategories()).containsExactly("BREAKING_SCHEMA");
                });
        assertThat(response.publications())
                .singleElement()
                .satisfies(usage -> {
                    assertThat(usage.publicationId()).isEqualTo("pub-risk");
                    assertThat(usage.frozenFingerprint()).isEqualTo(frozenOperator.fingerprint());
                    assertThat(usage.fingerprintStatus()).isEqualTo("DRIFTED");
                    assertThat(usage.changedSurface()).contains("input port 'inputs' schema changed");
                    assertThat(usage.changeRisk()).isEqualTo("BREAKING_SCHEMA");
                    assertThat(usage.changeCategories()).containsExactly("BREAKING_SCHEMA");
                });
    }

    @Test
    void reportsMissingCurrentOperatorForStoredUsage() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        drafts.save(draftUsingOperator("risk:retired", "sha256:retired"));
        OperatorUsageResponse response = new OperatorUsageIndex(
                drafts,
                new InMemoryVisualGraphPublicationRepository(),
                usageIndexCatalog()
        ).usage("risk:retired");

        assertThat(response.currentFingerprint()).isEmpty();
        assertThat(response.drafts())
                .singleElement()
                .extracting(OperatorDraftUsage::fingerprintStatus)
                .isEqualTo("OPERATOR_MISSING");
        assertThat(response.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.operatorUsage.operatorMissing");
                });
    }

    @Test
    void reportsMissingStoredDraftFingerprintSnapshot() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        drafts.save(draftUsingOperator("risk:eligibility", ""));
        OperatorUsageResponse response = new OperatorUsageIndex(
                drafts,
                new InMemoryVisualGraphPublicationRepository(),
                usageIndexCatalog(VisualCatalogTestSupport.eligibilityOperator("integer"))
        ).usage("risk:eligibility");

        assertThat(response.drafts())
                .singleElement()
                .extracting(OperatorDraftUsage::fingerprintStatus)
                .isEqualTo("SNAPSHOT_MISSING");
    }

    private static DefaultVisualOperatorCatalog usageIndexCatalog(OperatorDefinition... operators) {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        if (operators.length > 0) {
            libraries.upsert(new OperatorLibrary(
                    "bloge.visualOperatorLibrary.v1",
                    "risk-policy",
                    "Risk policy operators",
                    "1.0.0",
                    "risk-team",
                    "ACTIVE",
                    List.of(operators)
            ));
        }
        return new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );
    }

    private static GraphDraft draftUsingOperator(String operatorRef, String fingerprint) {
        Map<String, String> fingerprints = fingerprint == null || fingerprint.isBlank()
                ? Map.of()
                : Map.of("eligibility", fingerprint);
        return new GraphDraft(
                GraphDraft.SCHEMA_VERSION,
                "draft-risk",
                0,
                "riskGraph",
                "tenant-a",
                "risk",
                "prod",
                GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        operatorRef,
                        "Eligibility",
                        Map.of(),
                        Map.of(),
                        new GraphDraft.Position(120, 80)
                )),
                List.of(),
                Map.of(),
                GraphDraft.OutputSelection.empty(),
                fingerprints
        );
    }

    private static GraphDraft draftUsingOperator(OperatorDefinition operator) {
        return draftUsingOperator(operator.operatorRef(), operator.fingerprint())
                .withOperatorSnapshots(Map.of("eligibility", operator));
    }

    private static VisualGraphPublication publicationUsing(GraphDraft draft, OperatorDefinition operator) {
        return new VisualGraphPublication(
                "bloge.visualGraphPublication.v1",
                "pub-risk",
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                null,
                draft,
                List.of(operator),
                draft.operatorFingerprints(),
                draft.visualLayout(),
                "graph riskGraph {}",
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, "graph riskGraph {}", List.of())
        );
    }
}
