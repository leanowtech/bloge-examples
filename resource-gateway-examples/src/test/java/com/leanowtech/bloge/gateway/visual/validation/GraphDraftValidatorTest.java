package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph validation.
 */
class GraphDraftValidatorTest {

    @Test
    void reportsMissingRequiredResourceInput() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResource());
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "loanPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "fetchApplicant",
                        "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("fetchApplicant", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.required");
                    assertThat(diagnostic.target()).contains("applicantId");
                });
    }

    @Test
    void rejectsNodePathBindingWhenOutputTypeDoesNotMatchTargetInputSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("string")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "typedBinding",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", GraphDraft.Binding.nodePath("fetchApplicant", "score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("facts", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score"))),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("integer").contains("string");
                });
    }
}
