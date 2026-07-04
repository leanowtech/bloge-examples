package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for schema-aware operator fit discovery.
 */
class OperatorFitCandidateServiceTest {

    @Test
    void fitCandidatesDiscoverTypelessArrayTargetPaths() {
        OperatorLibrary library = VisualCatalogTestSupport.listSchemaCompatibilityLibrary(
                Map.of("items", Map.of("type", "integer")),
                Map.of(
                        "prefixItems", List.of(Map.of("type", "integer")),
                        "items", Map.of("type", "string")));
        OperatorFitCandidateService service = new OperatorFitCandidateService(
                VisualCatalogTestSupport.catalogWithLibrary(library));
        GraphDraft draft = new GraphDraft(
                GraphDraft.SCHEMA_VERSION,
                "draft-fit-typeless-array",
                0,
                "typelessArrayFit",
                "",
                "",
                "",
                GraphDraft.STATUS_DRAFT,
                null,
                List.of(new GraphDraft.DraftNode(
                        "listFacts",
                        "risk:listFacts",
                        "List facts",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                GraphDraft.OutputSelection.empty()
        );

        OperatorFitCatalogResponse result = service.candidates(new OperatorFitCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("listFacts", "output", "items.0"),
                OperatorCatalogQuery.all(),
                "input",
                true,
                20,
                0
        ));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.sourceSchemaType()).isEqualTo("integer");
        assertThat(result.fitCandidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.operator().operatorRef()).isEqualTo("risk:listConsumer");
                    assertThat(candidate.accepted()).isTrue();
                    assertThat(candidate.targets())
                            .anySatisfy(target -> {
                                assertThat(target.targetPath()).isEqualTo("items.0");
                                assertThat(target.accepted()).isTrue();
                                assertThat(target.sourceSchemaType()).isEqualTo("integer");
                                assertThat(target.targetSchemaType()).isEqualTo("integer");
                            })
                            .anySatisfy(target -> {
                                assertThat(target.targetPath()).isEqualTo("items.1");
                                assertThat(target.accepted()).isFalse();
                                assertThat(target.targetSchemaType()).isEqualTo("string");
                                assertThat(target.message()).contains("source type integer cannot feed target type string");
                            });
                });
    }
}
