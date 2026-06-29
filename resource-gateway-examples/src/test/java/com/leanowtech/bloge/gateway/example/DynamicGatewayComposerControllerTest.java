package com.leanowtech.bloge.gateway.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.test.MockOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web contract tests for the custom graph composer endpoint.
 */
class DynamicGatewayComposerControllerTest {

    private static final String DECISION_TABLE_DSL = """
            graph customLoanPolicy {
              decision_table loanPolicy(score = ctx.score, amount = ctx.amount) hit=unique -> { decision: String, rate: Decimal, maxTerm: Int, reviewLane: String, ruleId: String } {
                rule (score: score >= 760, amount: amount <= 500000)       -> { decision: "approved", rate: 3.5,  maxTerm: 360, reviewLane: "auto-approve",       ruleId: "R1" }
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DynamicGatewayComposerService service =
                new DynamicGatewayComposerService(MockOperator.returning(null));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DynamicGatewayComposerController(service)
        ).build();
    }

    @Test
    void runEndpointCompilesAndExecutesSubmittedGraph() throws Exception {
        DynamicGraphRunRequest request = new DynamicGraphRunRequest(
                DECISION_TABLE_DSL,
                Map.of("score", 670, "amount", 180_000, "segment", "existing"),
                "response"
        );

        mockMvc.perform(post("/api/gateway/examples/compose/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compiled").value(true))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.graphName").value("customLoanPolicy"))
                .andExpect(jsonPath("$.output.policy.ruleId").value("R3"))
                .andExpect(jsonPath("$.decisionTable.rows.length()").value(3))
                .andExpect(jsonPath("$.layout.nodes[0].kind").value("decision-table"));
    }
}
