package com.leanowtech.bloge.examples.finance;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Loan-approval workflow that aggregates multi-source risk signals.
 *
 * <p>This example demonstrates parallel risk checks (credit, fraud, income, blacklist),
 * risk aggregation, and decision branching into approval, rejection, or manual review.
 *
 * <p>Graph layout:
 * <pre>
 * fetchApplication
 *   -> checkCredit + detectFraud + verifyIncome + checkBlacklist
 *   -> aggregateRisk
 *   -> makeDecision
 *      -> (approved) approveLoan
 *      -> (manual_review) manualReview
 *      -> (rejected) rejectLoan
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the graph with sample loan input.
 */
public class LoanApprovalExample {

    public record ApplicationQuery(String appId) {}
    public record Application(String appId, String userId, double amount, int termMonths) {}

    public record CreditInput(String userId) {}
    public record CreditScore(int score, String level) {}

    public record FraudInput(String userId, double amount) {}
    public record FraudResult(double riskScore, List<String> flags) {}

    public record IncomeInput(String userId) {}
    public record IncomeVerification(boolean verified, double annualIncome, String source) {}

    public record BlacklistInput(String userId) {}
    public record BlacklistCheck(boolean isBlacklisted, String reason) {}

    public record RiskInput(CreditScore credit, FraudResult fraud, IncomeVerification income, BlacklistCheck blacklist) {}
    public record RiskAggregation(double compositeScore, Map<String, String> details) {}

    public record DecisionInput(RiskAggregation risk, Application application) {}
    public record Decision(String decision, String reason) {}

    public record ApprovalInput(Application application, RiskAggregation risk) {}
    public record ApprovalResult(String loanId, double approvedAmount, double rate) {}

    public record RejectionInput(String reason, List<String> factors) {}
    public record RejectionResult(String reason, List<String> factors) {}

    public record ReviewInput(Application application, RiskAggregation risk) {}
    public record ManualReviewResult(String reviewId, String assignedTo, String reason) {}

    static final Operator<ApplicationQuery, Application> FETCH_APPLICATION = (input, ctx) -> {
        Thread.sleep(40);
        return new Application("APP-2024-001", "user-55", 150000.0, 36);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"risk", "finance"},
            description = "Checks applicant credit score via credit bureau", owner = "risk-team")
    static final Operator<CreditInput, CreditScore> CHECK_CREDIT = (input, ctx) -> {
        Thread.sleep(80);
        return new CreditScore(720, "good");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"risk", "fraud"},
            description = "Detects potential fraud signals for the applicant", owner = "risk-team")
    static final Operator<FraudInput, FraudResult> DETECT_FRAUD = (input, ctx) -> {
        Thread.sleep(120);
        return new FraudResult(0.15, List.of());
    };

    static final Operator<IncomeInput, IncomeVerification> VERIFY_INCOME = (input, ctx) -> {
        Thread.sleep(90);
        return new IncomeVerification(true, 85000.0, "employer");
    };

    static final Operator<BlacklistInput, BlacklistCheck> CHECK_BLACKLIST = (input, ctx) -> {
        Thread.sleep(50);
        return new BlacklistCheck(false, "clean");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"risk", "finance"},
            description = "Aggregates risk signals into a composite risk score", owner = "risk-team")
    static final Operator<RiskInput, RiskAggregation> AGGREGATE_RISK = (input, ctx) -> {
        Thread.sleep(30);
        double creditWeight = (1.0 - input.credit().score() / 850.0) * 0.3;
        double fraudWeight = input.fraud().riskScore() * 0.3;
        double incomeWeight = input.income().verified() ? 0.0 : 0.2;
        double blacklistWeight = input.blacklist().isBlacklisted() ? 0.5 : 0.0;
        double compositeScore = creditWeight + fraudWeight + incomeWeight + blacklistWeight;
        return new RiskAggregation(compositeScore, Map.of(
                "credit", input.credit().level(),
                "fraud", String.valueOf(input.fraud().riskScore()),
                "income", input.income().verified() ? "verified" : "unverified",
                "blacklist", input.blacklist().isBlacklisted() ? "flagged" : "clean"
        ));
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"decision", "finance"},
            description = "Makes approve/reject/review decision based on risk", owner = "underwriting-team")
    static final Operator<DecisionInput, Decision> MAKE_DECISION = (input, ctx) -> {
        Thread.sleep(20);
        double score = input.risk().compositeScore();
        if (score < 0.5) {
            return new Decision("approved", "Low risk score: " + score);
        } else if (score > 0.8) {
            return new Decision("rejected", "High risk score: " + score);
        } else {
            return new Decision("manual_review", "Moderate risk score: " + score);
        }
    };

    static final Operator<ApprovalInput, ApprovalResult> APPROVE_LOAN = (input, ctx) -> {
        Thread.sleep(30);
        return new ApprovalResult("LOAN-2024-001", input.application().amount(), 5.5);
    };

    static final Operator<RejectionInput, RejectionResult> REJECT_LOAN = (input, ctx) -> {
        Thread.sleep(20);
        return new RejectionResult(input.reason(), input.factors());
    };

    static final Operator<ReviewInput, ManualReviewResult> MANUAL_REVIEW = (input, ctx) -> {
        Thread.sleep(20);
        return new ManualReviewResult("REV-001", "Senior Underwriter",
                "Moderate risk — manual assessment required");
    };

    public static Graph buildGraph() {
        var builder = Graph.builder("loanApproval")
                .node("fetchApplication", FETCH_APPLICATION)
                    .input((results, ctx) -> new ApplicationQuery(ctx.get("appId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("checkCredit", CHECK_CREDIT)
                    .dependsOn("fetchApplication")
                    .input((results, ctx) -> new CreditInput(
                            results.get("fetchApplication", Application.class).userId()))
                    .retry(3, Duration.ofMillis(200), BackoffStrategy.EXPONENTIAL)
                    .fallback(ex -> new CreditScore(0, "unknown"))
                .node("detectFraud", DETECT_FRAUD)
                    .dependsOn("fetchApplication")
                    .input((results, ctx) -> new FraudInput(
                            results.get("fetchApplication", Application.class).userId(),
                            results.get("fetchApplication", Application.class).amount()))
                    .timeout(Duration.ofSeconds(5))
                    .fallback(ex -> new FraudResult(1.0, List.of("service_unavailable")))
                .node("verifyIncome", VERIFY_INCOME)
                    .dependsOn("fetchApplication")
                    .input((results, ctx) -> new IncomeInput(
                            results.get("fetchApplication", Application.class).userId()))
                    .timeout(Duration.ofSeconds(5))
                .node("checkBlacklist", CHECK_BLACKLIST)
                    .dependsOn("fetchApplication")
                    .input((results, ctx) -> new BlacklistInput(
                            results.get("fetchApplication", Application.class).userId()))
                    .timeout(Duration.ofSeconds(3))
                .node("aggregateRisk", AGGREGATE_RISK)
                    .dependsOn("checkCredit", "detectFraud", "verifyIncome", "checkBlacklist")
                    .input((results, ctx) -> new RiskInput(
                            results.get("checkCredit", CreditScore.class),
                            results.get("detectFraud", FraudResult.class),
                            results.get("verifyIncome", IncomeVerification.class),
                            results.get("checkBlacklist", BlacklistCheck.class)))
                .node("makeDecision", MAKE_DECISION)
                    .dependsOn("aggregateRisk")
                    .input((results, ctx) -> new DecisionInput(
                            results.get("aggregateRisk", RiskAggregation.class),
                            results.get("fetchApplication", Application.class)))
                .node("approveLoan", APPROVE_LOAN)
                    .dependsOn("makeDecision")
                    .input((results, ctx) -> new ApprovalInput(
                            results.get("fetchApplication", Application.class),
                            results.get("aggregateRisk", RiskAggregation.class)))
                .node("rejectLoan", REJECT_LOAN)
                    .dependsOn("makeDecision")
                    .input((results, ctx) -> new RejectionInput(
                            results.get("makeDecision", Decision.class).reason(),
                            List.of("risk_score_exceeded")))
                .node("manualReview", MANUAL_REVIEW)
                    .dependsOn("makeDecision")
                    .input((results, ctx) -> new ReviewInput(
                            results.get("fetchApplication", Application.class),
                            results.get("aggregateRisk", RiskAggregation.class)))
                .branch("makeDecision")
                    .on("decision")
                    .when(val -> "approved".equals(val), "approveLoan")
                    .when(val -> "rejected".equals(val), "rejectLoan")
                    .otherwise("manualReview");

        return builder.build();
    }

    @SuppressWarnings("preview")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();

        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "appId", "APP-2024-001"
        ));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "fetchApplication", FETCH_APPLICATION,
                "checkCredit", CHECK_CREDIT,
                "detectFraud", DETECT_FRAUD,
                "verifyIncome", VERIFY_INCOME,
                "checkBlacklist", CHECK_BLACKLIST,
                "aggregateRisk", AGGREGATE_RISK,
                "makeDecision", MAKE_DECISION,
                "approveLoan", APPROVE_LOAN,
                "rejectLoan", REJECT_LOAN,
                "manualReview", MANUAL_REVIEW
        ));

        System.out.println("\n═══ Loan Approval Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("approveLoan") == NodeStatus.COMPLETED) {
            ApprovalResult approval = result.getOutput("approveLoan", ApprovalResult.class);
            System.out.println("Loan approved: " + approval);
        } else if (result.getStatus("rejectLoan") == NodeStatus.COMPLETED) {
            RejectionResult rejection = result.getOutput("rejectLoan", RejectionResult.class);
            System.out.println("Loan rejected: " + rejection);
        } else if (result.getStatus("manualReview") == NodeStatus.COMPLETED) {
            ManualReviewResult review = result.getOutput("manualReview", ManualReviewResult.class);
            System.out.println("Manual review required: " + review);
        }
    }
}
