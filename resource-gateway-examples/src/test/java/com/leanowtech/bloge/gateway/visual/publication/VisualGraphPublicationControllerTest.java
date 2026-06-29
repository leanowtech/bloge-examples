package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
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
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository);

        assertThat(controller.list()).containsExactly(stored);
        assertThat(controller.get(stored.publicationId()))
                .extracting(ResponseEntity::getBody)
                .isEqualTo(stored);
    }

    @Test
    void getReturnsNotFoundForUnknownPublication() {
        VisualGraphPublicationController controller =
                new VisualGraphPublicationController(new InMemoryVisualGraphPublicationRepository());

        assertThat(controller.get("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
}
