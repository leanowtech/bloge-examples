package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph publication API.
 */
class VisualGraphPublicationControllerTest {

    @Test
    void listAndGetPublications() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner(),
                new InMemoryVisualGraphRunRepository());

        assertThat(controller.list()).containsExactly(stored);
        assertThat(controller.get(stored.publicationId()))
                .extracting(ResponseEntity::getBody)
                .isEqualTo(stored);
    }

    @Test
    void getReturnsNotFoundForUnknownPublication() {
        VisualGraphPublicationController controller =
                new VisualGraphPublicationController(new InMemoryVisualGraphPublicationRepository(), runner(),
                        new InMemoryVisualGraphRunRepository());

        assertThat(controller.get("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void runPublicationDelegatesToRunner() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        CapturingRunService runner = new CapturingRunService();
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner, runs);

        ResponseEntity<VisualGraphRunResponse> response = controller.run(stored.publicationId(),
                new VisualStoredDraftRunRequest(Map.of("score", 720), "eligibility"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().runId()).isNotBlank();
        assertThat(runner.publication).isEqualTo(stored);
        assertThat(runner.context).containsEntry("score", 720);
        assertThat(runner.outputNode).isEqualTo("eligibility");
        VisualGraphRunRecord record = runs.find(response.getBody().runId()).orElseThrow();
        assertThat(record.sourceKind()).isEqualTo(VisualGraphRunRecord.SOURCE_PUBLICATION);
        assertThat(record.publicationId()).isEqualTo(stored.publicationId());
        assertThat(record.draftId()).isEqualTo(stored.draftId());
    }

    private static VisualGraphRunService runner() {
        return new CapturingRunService();
    }

    private static VisualGraphPublication publication() {
        OperatorDefinition operator = VisualCatalogTestSupport.eligibilityOperator("integer");
        GraphDraft draft = new GraphDraft(
                "",
                "draft-1",
                1,
                "visualPolicy",
                "",
                "",
                "",
                "",
                null,
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
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", operator.fingerprint())
        );
        return VisualGraphPublication.from(
                draft,
                List.of(operator),
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, "graph visualPolicy {}", List.of())
        );
    }

    private static class CapturingRunService extends VisualGraphRunService {
        private VisualGraphPublication publication;
        private Map<String, Object> context;
        private String outputNode;

        CapturingRunService() {
            super(null, null, null);
        }

        @Override
        public VisualGraphRunResponse run(VisualGraphPublication publication,
                                          Map<String, Object> context,
                                          String outputNode) {
            this.publication = publication;
            this.context = context;
            this.outputNode = outputNode;
            return new VisualGraphRunResponse(
                    true,
                    true,
                    true,
                    publication.graphName(),
                    outputNode,
                    Map.of("ok", true),
                    Map.of(),
                    Map.of(),
                    1,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    publication.dsl()
            );
        }
    }
}
