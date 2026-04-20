package com.leanowtech.bloge.examples.finance;

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
 * DSL version of loan approval orchestration.
 *
 * <p>This example compiles a loan-risk workflow from DSL and executes it with
 * registry-mapped Map operators.
 *
 * <p>Graph layout:
 * <pre>
 * fetchApplication
 *   -> checkCredit + detectFraud + verifyIncome + checkBlacklist
 *   -> aggregateRisk
 *   -> makeDecision
 *      -> approveLoan | manualReview | rejectLoan
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class LoanApprovalDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_APPLICATION = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of("appId", "APP-2024-001", "userId", "user-55", "amount", 150000.0, "termMonths", 36);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_CREDIT = (input, ctx) -> {
        Thread.sleep(80);
        return Map.of("score", 720, "level", "good");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DETECT_FRAUD = (input, ctx) -> {
        Thread.sleep(120);
        return Map.of("riskScore", 0.15, "flags", List.of());
    };

    static final Operator<Map<String, Object>, Map<String, Object>> VERIFY_INCOME = (input, ctx) -> {
        Thread.sleep(90);
        return Map.of("verified", true, "annualIncome", 85000.0, "source", "employer");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_BLACKLIST = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of("isBlacklisted", false, "reason", "clean");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> AGGREGATE_RISK = (input, ctx) -> {
        Thread.sleep(30);
        var credit = (Map<String, Object>) input.get("credit");
        var fraud = (Map<String, Object>) input.get("fraud");
        var income = (Map<String, Object>) input.get("income");
        var blacklist = (Map<String, Object>) input.get("blacklist");
        double creditWeight = (1.0 - ((Number) credit.get("score")).doubleValue() / 850.0) * 0.3;
        double fraudWeight = ((Number) fraud.get("riskScore")).doubleValue() * 0.3;
        double incomeWeight = (Boolean) income.get("verified") ? 0.0 : 0.2;
        double blacklistWeight = (Boolean) blacklist.get("isBlacklisted") ? 0.5 : 0.0;
        double compositeScore = creditWeight + fraudWeight + incomeWeight + blacklistWeight;
        return Map.of(
                "compositeScore", compositeScore,
                "details", Map.of(
                        "credit", (String) credit.get("level"),
                        "fraud", String.valueOf(fraud.get("riskScore")),
                        "income", (Boolean) income.get("verified") ? "verified" : "unverified",
                        "blacklist", (Boolean) blacklist.get("isBlacklisted") ? "flagged" : "clean"
                )
        );
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> MAKE_DECISION = (input, ctx) -> {
        Thread.sleep(20);
        var risk = (Map<String, Object>) input.get("risk");
        double score = ((Number) risk.get("compositeScore")).doubleValue();
        if (score < 0.5) {
            return Map.of("decision", "approved", "reason", "Low risk score: " + score);
        } else if (score > 0.8) {
            return Map.of("decision", "rejected", "reason", "High risk score: " + score);
        } else {
            return Map.of("decision", "manual_review", "reason", "Moderate risk score: " + score);
        }
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> APPROVE_LOAN = (input, ctx) -> {
        Thread.sleep(30);
        var application = (Map<String, Object>) input.get("application");
        return Map.of(
                "loanId", "LOAN-2024-001",
                "approvedAmount", ((Number) application.get("amount")).doubleValue(),
                "rate", 5.5
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> REJECT_LOAN = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "reason", input.get("reason"),
                "factors", List.of("risk_score_exceeded")
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> MANUAL_REVIEW = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "reviewId", "REV-001",
                "assignedTo", "Senior Underwriter",
                "reason", "Moderate risk — manual assessment required"
        );
    };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        // ── Operator Registrations ─────────────────────────────────────────────
        // FETCH_APPLICATION: reads ctx.appId → returns {appId, userId, amount, termMonths}
        registry.register("FetchApplicationOperator", FETCH_APPLICATION);
        // CHECK_CREDIT: reads userId → returns {score, level}; retries 3× exponential
        registry.register("CheckCreditOperator", CHECK_CREDIT);
        // DETECT_FRAUD: reads userId, amount → returns {riskScore, flags}
        registry.register("DetectFraudOperator", DETECT_FRAUD);
        // VERIFY_INCOME: reads userId → returns {verified, annualIncome, source}
        registry.register("VerifyIncomeOperator", VERIFY_INCOME);
        // CHECK_BLACKLIST: reads userId → returns {isBlacklisted, reason}
        registry.register("CheckBlacklistOperator", CHECK_BLACKLIST);
        // AGGREGATE_RISK: fan-in of credit+fraud+income+blacklist → returns {compositeScore, details}
        registry.register("AggregateRiskOperator", AGGREGATE_RISK);
        // MAKE_DECISION: reads risk.compositeScore → returns {decision, reason}
        registry.register("MakeDecisionOperator", MAKE_DECISION);
        // APPROVE_LOAN: reads application, risk → returns {loanId, approvedAmount, rate}
        registry.register("ApproveLoanOperator", APPROVE_LOAN);
        // REJECT_LOAN: reads reason → returns {reason, factors}
        registry.register("RejectLoanOperator", REJECT_LOAN);
        // MANUAL_REVIEW: reads application, risk → returns {reviewId, assignedTo, reason}
        registry.register("ManualReviewOperator", MANUAL_REVIEW);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph loanApproval {
                  ///  fetchApplication: reads ctx.appId → {appId, userId, amount, termMonths}
                  node fetchApplication : FetchApplicationOperator {
                    input { appId = ctx.appId }
                    timeout = 3s
                  }
                  ///  parallel fan-out: checkCredit, detectFraud, verifyIncome, checkBlacklist run concurrently
                  node checkCredit : CheckCreditOperator {
                    depends_on = [fetchApplication]
                    input { userId = fetchApplication.output.userId }
                    retry = { attempts: 3, backoff: 200ms, strategy: exponential }
                    fallback = { score: 0, level: "unknown" }
                  }
                  node detectFraud : DetectFraudOperator {
                    depends_on = [fetchApplication]
                    input {
                      userId = fetchApplication.output.userId
                      amount = fetchApplication.output.amount
                    }
                    timeout = 5s
                    fallback = { riskScore: 1.0, flags: ["service_unavailable"] }
                  }
                  node verifyIncome : VerifyIncomeOperator {
                    depends_on = [fetchApplication]
                    input { userId = fetchApplication.output.userId }
                    timeout = 5s
                  }
                  node checkBlacklist : CheckBlacklistOperator {
                    depends_on = [fetchApplication]
                    input { userId = fetchApplication.output.userId }
                    timeout = 3s
                  }
                  ///  parallel fan-in: all 4 risk checks run concurrently; aggregateRisk waits for all
                  node aggregateRisk : AggregateRiskOperator {
                    depends_on = [checkCredit, detectFraud, verifyIncome, checkBlacklist]
                    input {
                      credit    = checkCredit.output
                      fraud     = detectFraud.output
                      income    = verifyIncome.output
                      blacklist = checkBlacklist.output
                    }
                  }
                  ///  makeDecision: reads risk.compositeScore → {decision, reason}
                  node makeDecision : MakeDecisionOperator {
                    depends_on = [aggregateRisk]
                    input {
                      risk        = aggregateRisk.output
                      application = fetchApplication.output
                    }
                  }
                  ///  branch: routes to approveLoan, rejectLoan, or manualReview based on decision value
                  branch on makeDecision.output.decision {
                    "approved"  -> approveLoan
                    "rejected"  -> rejectLoan
                    otherwise   -> manualReview
                  }
                  ///  branch outcomes: only one of approveLoan/rejectLoan/manualReview will execute
                  node approveLoan : ApproveLoanOperator {
                    depends_on = [makeDecision]
                    input {
                      application = fetchApplication.output
                      risk        = aggregateRisk.output
                    }
                  }
                  node rejectLoan : RejectLoanOperator {
                    depends_on = [makeDecision]
                    input {
                      reason = makeDecision.output.reason
                    }
                  }
                  node manualReview : ManualReviewOperator {
                    depends_on = [makeDecision]
                    input {
                      application = fetchApplication.output
                      risk        = aggregateRisk.output
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
                "appId", "APP-2024-001"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Loan Approval Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map<String,Object> if typed access is needed
        if (result.getStatus("approveLoan") == NodeStatus.COMPLETED) {
            System.out.println("Loan approved: " + result.results().getRaw("approveLoan"));
        } else if (result.getStatus("rejectLoan") == NodeStatus.COMPLETED) {
            System.out.println("Loan rejected: " + result.results().getRaw("rejectLoan"));
        } else if (result.getStatus("manualReview") == NodeStatus.COMPLETED) {
            System.out.println("Manual review required: " + result.results().getRaw("manualReview"));
        }
    }
}
