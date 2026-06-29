package com.leanowtech.bloge.gateway.visual.codegen;

import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for lowering visual drafts to BLOGE DSL.
 */
class GraphDraftDslGeneratorTest {

    @Test
    void lowersResourceVirtualOperatorToHttpResourceDsl() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
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
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                                Map.of("timeout", "3s", "retryAttempts", 1),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "response",
                                "bloge:transform",
                                "",
                                Map.of(
                                        "applicant", GraphDraft.Binding.nodePath("fetchApplicant", ""),
                                        "score", GraphDraft.Binding.nodePath("fetchApplicant", "score")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("fetch-to-response", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", ""),
                        new GraphDraft.Endpoint("response", "inputs", ""))),
                Map.of(),
                new GraphDraft.OutputSelection("response", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("node fetchApplicant : httpResource");
        assertThat(result.dsl()).contains("resourceId = \"loan-applicant-service.getProfile\"");
        assertThat(result.dsl()).contains("params = { applicantId: ctx.applicantId }");
        assertThat(result.dsl()).contains("score = fetchApplicant.output.payload.score");
    }

    @Test
    void lowersUserProvidedTransformOperator() {
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "eligibilityGraph",
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
                new GraphDraft.OutputSelection("eligibility", "")
        );

        DslGenerationResult result = generator.generate(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.dsl()).contains("transform eligibility");
        assertThat(result.dsl()).contains("eligible = ctx.score >= 700 && ctx.amount <= 300000");
        assertThat(result.dsl()).contains("ruleId = \"ELIGIBILITY_V1\"");
    }
}
