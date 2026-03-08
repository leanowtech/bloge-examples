package com.leanowtech.bloge.examples.finance;

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

public class LoanApprovalReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("FetchApplicationOperator", LoanApprovalDslExample.FETCH_APPLICATION);
        registry.register("CheckCreditOperator", LoanApprovalDslExample.CHECK_CREDIT);
        registry.register("DetectFraudOperator", LoanApprovalDslExample.DETECT_FRAUD);
        registry.register("VerifyIncomeOperator", LoanApprovalDslExample.VERIFY_INCOME);
        registry.register("CheckBlacklistOperator", LoanApprovalDslExample.CHECK_BLACKLIST);
        registry.register("AggregateRiskOperator", LoanApprovalDslExample.AGGREGATE_RISK);
        registry.register("MakeDecisionOperator", LoanApprovalDslExample.MAKE_DECISION);
        registry.register("ApproveLoanOperator", LoanApprovalDslExample.APPROVE_LOAN);
        registry.register("RejectLoanOperator", LoanApprovalDslExample.REJECT_LOAN);
        registry.register("ManualReviewOperator", LoanApprovalDslExample.MANUAL_REVIEW);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String appId = ReplHelper.promptString(scanner, "appId", "APP-2024-001");
        return Map.of("appId", appId);
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
                ReplHelper.header("Loan Approval REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
