package com.leanowtech.bloge.examples.insurance;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.util.List;
import java.util.Map;

/**
 * DSL version of insurance claim-processing orchestration.
 *
 * <p>This example compiles claim orchestration from DSL and executes it through
 * registry-bound Map operators.
 *
 * <p>Graph layout:
 * <pre>
 * intakeClaim
 *   -> verifyPolicy + assessDamage + detectFraud
 *   -> evaluateClaim
 *   -> approveClaim | manualReview | rejectClaim
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class ClaimProcessingDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_CLAIM = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of(
                "claimId", "CLM-2024-0042",
                "policyId", "POL-10088",
                "customerId", "CUST-5521",
                "claimType", "auto_accident",
                "amount", 25000.0,
                "description", "Rear-end collision on highway",
                "incidentDate", "2024-01-15"
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> VALIDATE_POLICY = (input, ctx) -> {
        Thread.sleep(60);
        return Map.of("valid", true, "policyType", "comprehensive", "coverageLimit", 50000.0, "expiryDate", "2025-06-30");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> REVIEW_DOCUMENTS = (input, ctx) -> {
        Thread.sleep(100);
        return Map.of("complete", true, "missingDocs", List.of(), "authenticityScore", 0.95);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_CLAIM_HISTORY = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of("totalClaims", 3, "lastYearClaims", 1, "totalClaimedAmount", 15000.0, "hasDispute", false);
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> ASSESS_RISK = (input, ctx) -> {
        Thread.sleep(40);
        var policy = (Map<String, Object>) input.get("policy");
        var documents = (Map<String, Object>) input.get("documents");
        var history = (Map<String, Object>) input.get("history");
        double score = 0.0;
        if (!Boolean.TRUE.equals(policy.get("valid"))) score += 0.4;
        if (!Boolean.TRUE.equals(documents.get("complete"))) score += 0.3;
        if (Boolean.TRUE.equals(history.get("hasDispute"))) score += 0.2;
        score += ((Number) history.get("lastYearClaims")).intValue() * 0.05;
        String level = score < 0.4 ? "low" : score < 0.7 ? "medium" : "high";
        return Map.of("riskScore", score, "riskLevel", level, "flags", List.of());
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CLAIM_DECISION = (input, ctx) -> {
        Thread.sleep(20);
        var risk = (Map<String, Object>) input.get("risk");
        double riskScore = ((Number) risk.get("riskScore")).doubleValue();
        if (riskScore < 0.4) return Map.of("decision", "approved", "reason", "Low risk claim approved");
        if (riskScore > 0.7) return Map.of("decision", "rejected", "reason", "High risk claim rejected");
        return Map.of("decision", "investigate", "reason", "Medium risk requires investigation");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> APPROVE_CLAIM = (input, ctx) -> {
        Thread.sleep(30);
        var claim = (Map<String, Object>) input.get("claim");
        double approvedAmount = Math.min(((Number) claim.get("amount")).doubleValue(), 50000.0);
        return Map.of("approvalId", "APR-2024-001", "approvedAmount", approvedAmount, "payoutMethod", "bank_transfer");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SCHEDULE_PAYOUT = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of(
                "payoutId", "PAY-2024-001",
                "scheduledDate", "2024-02-15",
                "amount", ((Number) input.get("amount")).doubleValue()
        );
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> REJECT_CLAIM = (input, ctx) -> {
        Thread.sleep(20);
        var risk = (Map<String, Object>) input.get("risk");
        return Map.of("reason", risk.get("riskLevel") + " risk - claim denied", "appealDeadline", "2024-04-15");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> INVESTIGATE_CLAIM = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of("caseId", "INV-2024-001", "investigator", "Senior Adjuster Park", "priority", "medium");
    };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        // ── Operator Registrations ─────────────────────────────────────────────
        // FETCH_CLAIM: reads ctx.claimId → returns {claimId, policyId, customerId, claimType, amount, incidentDate}
        registry.register("FetchClaimOperator", FETCH_CLAIM);
        // VALIDATE_POLICY: reads policyId → returns {valid, policyType, coverageLimit, expiryDate}
        registry.register("ValidatePolicyOperator", VALIDATE_POLICY);
        // REVIEW_DOCUMENTS: reads claimId → returns {complete, missingDocs, authenticityScore}; retries 2×
        registry.register("ReviewDocumentsOperator", REVIEW_DOCUMENTS);
        // CHECK_CLAIM_HISTORY: reads customerId → returns {totalClaims, lastYearClaims, totalClaimedAmount, hasDispute}
        registry.register("CheckClaimHistoryOperator", CHECK_CLAIM_HISTORY);
        // ASSESS_RISK: fan-in of validatePolicy+reviewDocuments+checkClaimHistory → returns {riskScore, riskLevel, flags}
        registry.register("AssessRiskOperator", ASSESS_RISK);
        // CLAIM_DECISION: reads risk.riskScore → returns {decision, reason}
        registry.register("ClaimDecisionOperator", CLAIM_DECISION);
        // APPROVE_CLAIM: reads claim, risk → returns {approvalId, approvedAmount, payoutMethod}
        registry.register("ApproveClaimOperator", APPROVE_CLAIM);
        // SCHEDULE_PAYOUT: reads approvalId, amount → returns {payoutId, scheduledDate, amount}
        registry.register("SchedulePayoutOperator", SCHEDULE_PAYOUT);
        // REJECT_CLAIM: reads claim, risk → returns {reason, appealDeadline}
        registry.register("RejectClaimOperator", REJECT_CLAIM);
        // INVESTIGATE_CLAIM: reads claim, risk → returns {caseId, investigator, priority}
        registry.register("InvestigateClaimOperator", INVESTIGATE_CLAIM);

        var loader = new GraphLoader(registry);

        String dsl = """
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

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "claimId", "CLM-2024-0042"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Claim Processing Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map<String,Object> if typed access is needed
        if (result.getStatus("approveClaim") == NodeStatus.COMPLETED) {
            System.out.println("Claim approved: " + result.results().getRaw("approveClaim"));
            if (result.getStatus("schedulePayout") == NodeStatus.COMPLETED) {
                System.out.println("Payout scheduled: " + result.results().getRaw("schedulePayout"));
            }
        } else if (result.getStatus("rejectClaim") == NodeStatus.COMPLETED) {
            System.out.println("Claim rejected: " + result.results().getRaw("rejectClaim"));
        } else if (result.getStatus("investigateClaim") == NodeStatus.COMPLETED) {
            System.out.println("Claim under investigation: " + result.results().getRaw("investigateClaim"));
        }
    }
}
