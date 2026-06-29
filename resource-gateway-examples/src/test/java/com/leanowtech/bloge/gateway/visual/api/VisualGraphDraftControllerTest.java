package com.leanowtech.bloge.gateway.visual.api;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchService;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationResult;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph draft APIs.
 */
class VisualGraphDraftControllerTest {

    @Test
    void compileBlocksInvalidDraftBeforeDslGeneration() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                )
        ));

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).isBlank();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("ctx.score").contains("string").contains("integer");
                });
    }

    @Test
    void compileGeneratesDslAfterVisualValidationPasses() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.dsl()).contains("transform eligibility");
    }

    @Test
    void createStoresCurrentOperatorFingerprintSnapshot() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        GraphDraft stored = controller.create(draft);

        assertThat(stored.operatorFingerprints())
                .containsEntry("eligibility", catalog.find("risk:eligibility").orElseThrow().fingerprint());
    }

    @Test
    void patchStoredDraftAppliesExpectedRevisionAndIncrementsRevision() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "patchedPolicy")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isTrue();
        assertThat(response.getBody().draft().graphName()).isEqualTo("patchedPolicy");
        assertThat(response.getBody().draft().revision()).isEqualTo(stored.revision() + 1);
    }

    @Test
    void patchStoredDraftRejectsStaleRevision() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision() - 1, List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "stalePatch")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().draft().revision()).isEqualTo(stored.revision());
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.draft.revisionConflict");
    }

    @Test
    void revisionsReturnStoredDraftHistory() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        ResponseEntity<GraphDraftPatchResult> patched = controller.patch(first.draftId(),
                new GraphDraftPatchRequest(first.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "revisionTwo")
                )));
        GraphDraft second = patched.getBody().draft();

        ResponseEntity<List<GraphDraft>> response = controller.revisions(first.draftId());
        ResponseEntity<GraphDraft> firstRevision = controller.revision(first.draftId(), first.revision());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
                .extracting(GraphDraft::revision)
                .containsExactly(second.revision(), first.revision());
        assertThat(firstRevision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstRevision.getBody()).isEqualTo(first);
    }

    @Test
    void publishStoredDraftCreatesImmutablePublication() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts, publications);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        VisualGraphPublicationResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.published()).isTrue();
        assertThat(result.publication().publicationId()).isNotBlank();
        assertThat(result.publication().dsl()).contains("transform eligibility");
        assertThat(result.publication().operatorSnapshots())
                .extracting("operatorRef")
                .containsExactly("risk:eligibility");
        assertThat(result.publication().operatorFingerprints()).containsKey("eligibility");
        assertThat(publications.find(result.publication().publicationId())).contains(result.publication());
    }

    @Test
    void publishRejectsInvalidStoredDraft() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch");
        assertThat(publications.all()).isEmpty();
    }

    private static VisualGraphDraftController controllerWithEligibilityLibrary() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        return controllerWithCatalog(catalog, null);
    }

    private static VisualGraphDraftController controllerWithCatalog(DefaultVisualOperatorCatalog catalog,
                                                                    InMemoryGraphDraftRepository repository) {
        return controllerWithCatalog(catalog, repository, new InMemoryVisualGraphPublicationRepository());
    }

    private static VisualGraphDraftController controllerWithCatalog(DefaultVisualOperatorCatalog catalog,
                                                                    InMemoryGraphDraftRepository repository,
                                                                    InMemoryVisualGraphPublicationRepository publications) {
        return new VisualGraphDraftController(
                repository == null ? new InMemoryGraphDraftRepository() : repository,
                new GraphDraftValidator(catalog),
                new GraphDraftDslGenerator(catalog),
                null,
                catalog,
                publications,
                new GraphDraftPatchService(new ObjectMapper())
        );
    }

    private static GraphDraft eligibilityDraft(SchemaEnvelope inputSchema) {
        return new GraphDraft(
                "",
                "",
                0,
                "compileGate",
                "",
                "",
                "",
                "",
                inputSchema,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );
    }

    private static SchemaEnvelope graphInputSchema(Map<String, Object> properties) {
        return SchemaEnvelope.object(properties, properties.keySet().stream().toList());
    }
}
