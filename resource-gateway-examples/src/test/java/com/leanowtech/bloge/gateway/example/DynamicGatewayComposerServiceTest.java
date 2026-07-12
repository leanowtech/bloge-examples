package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.test.MockOperator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for browser-submitted gateway DSL execution.
 */
class DynamicGatewayComposerServiceTest {

    private static final String DECISION_TABLE_DSL = """
            graph customLoanPolicy {
              decision_table loanPolicy(
                score  = ctx.score,
                amount = ctx.amount
              ) hit=unique -> { decision: String, rate: Decimal, maxTerm: Int, reviewLane: String, ruleId: String } {
                rule (score: score >= 760, amount: amount <= 500000)       -> { decision: "approved", rate: 3.5,  maxTerm: 360, reviewLane: "auto-approve",       ruleId: "R1" }
                rule (score: 700 <= score < 760, amount: amount <= 300000) -> { decision: "approved", rate: 4.5,  maxTerm: 300, reviewLane: "standard",           ruleId: "R2" }
                rule (score: 650 <= score < 700, amount: amount <= 200000) -> { decision: "manual_review", rate: 5.75, maxTerm: 240, reviewLane: "senior-underwriter", ruleId: "R3" }
                otherwise                                                  -> { decision: "declined", rate: 0.0,  maxTerm: 0,   reviewLane: "decline",            ruleId: "R4" }
              }

              transform response {
                applicant       = { score: ctx.score, segment: ctx.segment }
                requestedAmount = ctx.amount
                policy          = loanPolicy.output
              }
            }
            """;

    private final DynamicGatewayComposerService service =
            new DynamicGatewayComposerService(MockOperator.returning(null));

    @Test
    void runsSubmittedDecisionTableAndReturnsVisualModels() {
        DynamicGraphRunResponse response = service.run(new DynamicGraphRunRequest(
                DECISION_TABLE_DSL,
                Map.of("score", 670, "amount", 180_000, "segment", "existing"),
                "response"
        ));

        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.graphName()).isEqualTo("customLoanPolicy");
        assertThat(response.outputNode()).isEqualTo("response");
        assertThat(response.layout()).isNotNull();
        assertThat(response.layout().rootId()).isEqualTo("customLoanPolicy");
        assertThat(response.layout().nodes())
                .extracting(ExampleVisualLayout.Node::kind)
                .contains("decision-table", "transform");

        assertThat(response.decisionTable()).isNotNull();
        assertThat(response.decisionTable().hitPolicy()).isEqualTo("unique");
        assertThat(response.decisionTable().rows())
                .extracting(GatewayDecisionTable.Row::id)
                .containsExactly("R1", "R2", "R3", "R4");

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.output();
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) output.get("policy");
        assertThat(policy)
                .containsEntry("decision", "manual_review")
                .containsEntry("ruleId", "R3");
        assertThat(response.nodeAttempts()).containsKeys("loanPolicy", "response");
        assertThat(response.nodeAttempts().get("loanPolicy")).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo("SUCCESS");
            assertThat(attempt.input()).isEqualTo(Map.of("score", 670, "amount", 180_000));
            assertThat(attempt.output()).isEqualTo(policy);
            assertThat(attempt.startedAt()).isNotNull();
        });
    }

    @Test
    void returnsDiagnosticsWhenSubmittedDslDoesNotCompile() {
        DynamicGraphRunResponse response = service.run(new DynamicGraphRunRequest(
                "graph broken { decision_table }",
                Map.of(),
                ""
        ));

        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.diagnostics()).isNotEmpty();
        assertThat(response.errors()).isNotEmpty();
    }
}
