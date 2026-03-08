package com.leanowtech.bloge.examples.insurance;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class ClaimProcessingReplExample {

    private static final String DSL = """

                graph claimProcessing {
                  ///  fetchClaim: reads ctx.claimId → {claimId, policyId, customerId, claimType, amount, incidentDate}
                  node fetchClaim : FetchClaimOperator {
                    input { claimId = ctx.claimId }
                    timeout = 3s
                  }
                  ///  parallel fan-out: validatePolicy, reviewDocuments, checkClaimHistory run concurrently
                  node validatePolicy : ValidatePolicyOperator {
                    depends_on = [fetchClaim]
                    input { policyId = fetchClaim.output.policyId }
                    timeout = 3s
                  }
                  node reviewDocuments : ReviewDocumentsOperator {
                    depends_on = [fetchClaim]
                    input { claimId = fetchClaim.output.claimId }
                    retry = { attempts: 2, backoff: 300ms, strategy: exponential }
                  }
                  node checkClaimHistory : CheckClaimHistoryOperator {
                    depends_on = [fetchClaim]
                    input { customerId = fetchClaim.output.customerId }
                    timeout = 3s
                  }
                  ///  parallel fan-in: all 3 checks run concurrently; assessRisk waits for all
                  node assessRisk : AssessRiskOperator {
                    depends_on = [validatePolicy, reviewDocuments, checkClaimHistory]
                    input {
                      policy    = validatePolicy.output
                      documents = reviewDocuments.output
                      history   = checkClaimHistory.output
                    }
                  }
                  ///  claimDecision: reads risk.riskScore → {decision, reason}
                  node claimDecision : ClaimDecisionOperator {
                    depends_on = [assessRisk]
                    input {
                      risk  = assessRisk.output
                      claim = fetchClaim.output
                    }
                  }
                  ///  branch on decision: approved → approveClaim, rejected → rejectClaim, otherwise → investigateClaim
                  branch on claimDecision.output.decision {
                    "approved"  -> approveClaim
                    "rejected"  -> rejectClaim
                    otherwise   -> investigateClaim
                  }
                  ///  branch outcomes: only one of approveClaim/rejectClaim/investigateClaim will execute
                  node approveClaim : ApproveClaimOperator {
                    depends_on = [claimDecision]
                    input {
                      claim = fetchClaim.output
                      risk  = assessRisk.output
                    }
                  }
                  node schedulePayout : SchedulePayoutOperator {
                    depends_on = [approveClaim]
                    input {
                      approvalId = approveClaim.output.approvalId
                      amount     = approveClaim.output.approvedAmount
                    }
                  }
                  node rejectClaim : RejectClaimOperator {
                    depends_on = [claimDecision]
                    input {
                      claim = fetchClaim.output
                      risk  = assessRisk.output
                    }
                  }
                  node investigateClaim : InvestigateClaimOperator {
                    depends_on = [claimDecision]
                    input {
                      claim = fetchClaim.output
                      risk  = assessRisk.output
                    }
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchClaimOperator", ClaimProcessingDslExample.FETCH_CLAIM);
        registry.register("ValidatePolicyOperator", ClaimProcessingDslExample.VALIDATE_POLICY);
        registry.register("ReviewDocumentsOperator", ClaimProcessingDslExample.REVIEW_DOCUMENTS);
        registry.register("CheckClaimHistoryOperator", ClaimProcessingDslExample.CHECK_CLAIM_HISTORY);
        registry.register("AssessRiskOperator", ClaimProcessingDslExample.ASSESS_RISK);
        registry.register("ClaimDecisionOperator", ClaimProcessingDslExample.CLAIM_DECISION);
        registry.register("ApproveClaimOperator", ClaimProcessingDslExample.APPROVE_CLAIM);
        registry.register("SchedulePayoutOperator", ClaimProcessingDslExample.SCHEDULE_PAYOUT);
        registry.register("RejectClaimOperator", ClaimProcessingDslExample.REJECT_CLAIM);
        registry.register("InvestigateClaimOperator", ClaimProcessingDslExample.INVESTIGATE_CLAIM);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String claimId = ReplHelper.promptString(scanner, "claimId", "CLM-2024-0042");
        return Map.of("claimId", claimId);
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();

        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Claim Processing REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
