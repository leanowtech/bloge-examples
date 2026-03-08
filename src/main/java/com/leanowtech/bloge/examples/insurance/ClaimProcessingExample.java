package com.leanowtech.bloge.examples.insurance;

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
 * Insurance claim-processing workflow with rule and risk evaluation.
 *
 * <p>This example demonstrates parallel policy/risk checks and branch-based outcomes
 * for automated approval, manual review, or rejection.
 *
 * <p>Graph layout:
 * <pre>
 * intakeClaim
 *   -> verifyPolicy + assessDamage + detectFraud
 *   -> evaluateClaim
 *   -> branchDecision
 *      -> approveClaim | manualReview | rejectClaim
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the graph with sample claim data.
 */
public class ClaimProcessingExample {

    public record ClaimQuery(String claimId) {}
    public record Claim(String claimId, String policyId, String customerId, String claimType,
                        double amount, String description, String incidentDate) {}

    public record PolicyInput(String policyId) {}
    public record PolicyValidation(boolean valid, String policyType, double coverageLimit, String expiryDate) {}

    public record DocumentInput(String claimId) {}
    public record DocumentReview(boolean complete, List<String> missingDocs, double authenticityScore) {}

    public record HistoryInput(String customerId) {}
    public record ClaimHistoryResult(int totalClaims, int lastYearClaims, double totalClaimedAmount, boolean hasDispute) {}

    public record RiskInput(PolicyValidation policy, DocumentReview documents, ClaimHistoryResult history) {}
    public record RiskAssessment(double riskScore, String riskLevel, List<String> flags) {}

    public record DecisionInput(RiskAssessment risk, Claim claim) {}
    public record ClaimDecisionResult(String decision, String reason) {}

    public record ApprovalInput(Claim claim, RiskAssessment risk) {}
    public record ApprovalResult(String approvalId, double approvedAmount, String payoutMethod) {}

    public record PayoutInput(String approvalId, double amount) {}
    public record PayoutSchedule(String payoutId, String scheduledDate, double amount) {}

    public record RejectionInput(Claim claim, RiskAssessment risk) {}
    public record RejectionResult(String reason, String appealDeadline) {}

    public record InvestigationInput(Claim claim, RiskAssessment risk) {}
    public record InvestigationResult(String caseId, String investigator, String priority) {}

    static final Operator<ClaimQuery, Claim> FETCH_CLAIM = (input, ctx) -> {
        Thread.sleep(40);
        return new Claim("CLM-2024-0042", "POL-10088", "CUST-5521", "auto_accident",
                25000.0, "Rear-end collision on highway", "2024-01-15");
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"insurance", "policy"},
            description = "Validates policy status and coverage limits", owner = "policy-team")
    static final Operator<PolicyInput, PolicyValidation> VALIDATE_POLICY = (input, ctx) -> {
        Thread.sleep(60);
        return new PolicyValidation(true, "comprehensive", 50000.0, "2025-06-30");
    };

    static final Operator<DocumentInput, DocumentReview> REVIEW_DOCUMENTS = (input, ctx) -> {
        Thread.sleep(100);
        return new DocumentReview(true, List.of(), 0.95);
    };

    static final Operator<HistoryInput, ClaimHistoryResult> CHECK_CLAIM_HISTORY = (input, ctx) -> {
        Thread.sleep(50);
        return new ClaimHistoryResult(3, 1, 15000.0, false);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"insurance", "risk"},
            description = "Assesses claim risk from policy, documents, and history", owner = "claims-team")
    static final Operator<RiskInput, RiskAssessment> ASSESS_RISK = (input, ctx) -> {
        Thread.sleep(40);
        double score = 0.0;
        if (!input.policy().valid()) score += 0.4;
        if (!input.documents().complete()) score += 0.3;
        if (input.history().hasDispute()) score += 0.2;
        score += input.history().lastYearClaims() * 0.05;
        return new RiskAssessment(score, score < 0.4 ? "low" : score < 0.7 ? "medium" : "high", List.of());
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"insurance", "decision"},
            description = "Makes claim approval, rejection, or investigation decision", owner = "claims-team")
    static final Operator<DecisionInput, ClaimDecisionResult> CLAIM_DECISION = (input, ctx) -> {
        Thread.sleep(20);
        double riskScore = input.risk().riskScore();
        if (riskScore < 0.4) return new ClaimDecisionResult("approved", "Low risk claim approved");
        if (riskScore > 0.7) return new ClaimDecisionResult("rejected", "High risk claim rejected");
        return new ClaimDecisionResult("investigate", "Medium risk requires investigation");
    };

    static final Operator<ApprovalInput, ApprovalResult> APPROVE_CLAIM = (input, ctx) -> {
        Thread.sleep(30);
        double approvedAmount = Math.min(input.claim().amount(), 50000.0);
        return new ApprovalResult("APR-2024-001", approvedAmount, "bank_transfer");
    };

    static final Operator<PayoutInput, PayoutSchedule> SCHEDULE_PAYOUT = (input, ctx) -> {
        Thread.sleep(30);
        return new PayoutSchedule("PAY-2024-001", "2024-02-15", input.amount());
    };

    static final Operator<RejectionInput, RejectionResult> REJECT_CLAIM = (input, ctx) -> {
        Thread.sleep(20);
        return new RejectionResult(input.risk().riskLevel() + " risk - claim denied", "2024-04-15");
    };

    static final Operator<InvestigationInput, InvestigationResult> INVESTIGATE_CLAIM = (input, ctx) -> {
        Thread.sleep(20);
        return new InvestigationResult("INV-2024-001", "Senior Adjuster Park", "medium");
    };

    public static Graph buildGraph() {
        var builder = Graph.builder("claimProcessing")
                .node("fetchClaim", FETCH_CLAIM)
                    .input((results, ctx) -> new ClaimQuery(ctx.get("claimId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("validatePolicy", VALIDATE_POLICY)
                    .dependsOn("fetchClaim")
                    .input((results, ctx) -> new PolicyInput(
                            results.get("fetchClaim", Claim.class).policyId()))
                    .timeout(Duration.ofSeconds(3))
                .node("reviewDocuments", REVIEW_DOCUMENTS)
                    .dependsOn("fetchClaim")
                    .input((results, ctx) -> new DocumentInput(
                            results.get("fetchClaim", Claim.class).claimId()))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.EXPONENTIAL)
                .node("checkClaimHistory", CHECK_CLAIM_HISTORY)
                    .dependsOn("fetchClaim")
                    .input((results, ctx) -> new HistoryInput(
                            results.get("fetchClaim", Claim.class).customerId()))
                    .timeout(Duration.ofSeconds(3))
                .node("assessRisk", ASSESS_RISK)
                    .dependsOn("validatePolicy", "reviewDocuments", "checkClaimHistory")
                    .input((results, ctx) -> new RiskInput(
                            results.get("validatePolicy", PolicyValidation.class),
                            results.get("reviewDocuments", DocumentReview.class),
                            results.get("checkClaimHistory", ClaimHistoryResult.class)))
                .node("claimDecision", CLAIM_DECISION)
                    .dependsOn("assessRisk")
                    .input((results, ctx) -> new DecisionInput(
                            results.get("assessRisk", RiskAssessment.class),
                            results.get("fetchClaim", Claim.class)))
                .node("approveClaim", APPROVE_CLAIM)
                    .dependsOn("claimDecision")
                    .input((results, ctx) -> new ApprovalInput(
                            results.get("fetchClaim", Claim.class),
                            results.get("assessRisk", RiskAssessment.class)))
                .node("schedulePayout", SCHEDULE_PAYOUT)
                    .dependsOn("approveClaim")
                    .input((results, ctx) -> new PayoutInput(
                            results.get("approveClaim", ApprovalResult.class).approvalId(),
                            results.get("approveClaim", ApprovalResult.class).approvedAmount()))
                .node("rejectClaim", REJECT_CLAIM)
                    .dependsOn("claimDecision")
                    .input((results, ctx) -> new RejectionInput(
                            results.get("fetchClaim", Claim.class),
                            results.get("assessRisk", RiskAssessment.class)))
                .node("investigateClaim", INVESTIGATE_CLAIM)
                    .dependsOn("claimDecision")
                    .input((results, ctx) -> new InvestigationInput(
                            results.get("fetchClaim", Claim.class),
                            results.get("assessRisk", RiskAssessment.class)))
                .branch("claimDecision")
                    .on("decision")
                    .when(val -> "approved".equals(val), "approveClaim")
                    .when(val -> "rejected".equals(val), "rejectClaim")
                    .otherwise("investigateClaim");

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
                "claimId", "CLM-2024-0042"
        ));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "fetchClaim", FETCH_CLAIM,
                "validatePolicy", VALIDATE_POLICY,
                "reviewDocuments", REVIEW_DOCUMENTS,
                "checkClaimHistory", CHECK_CLAIM_HISTORY,
                "assessRisk", ASSESS_RISK,
                "claimDecision", CLAIM_DECISION,
                "approveClaim", APPROVE_CLAIM,
                "schedulePayout", SCHEDULE_PAYOUT,
                "rejectClaim", REJECT_CLAIM,
                "investigateClaim", INVESTIGATE_CLAIM
        ));

        System.out.println("\n═══ Claim Processing Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("approveClaim") == NodeStatus.COMPLETED) {
            ApprovalResult approval = result.getOutput("approveClaim", ApprovalResult.class);
            System.out.println("Claim approved: " + approval);
            if (result.getStatus("schedulePayout") == NodeStatus.COMPLETED) {
                PayoutSchedule payout = result.getOutput("schedulePayout", PayoutSchedule.class);
                System.out.println("Payout scheduled: " + payout);
            }
        } else if (result.getStatus("rejectClaim") == NodeStatus.COMPLETED) {
            RejectionResult rejection = result.getOutput("rejectClaim", RejectionResult.class);
            System.out.println("Claim rejected: " + rejection);
        } else if (result.getStatus("investigateClaim") == NodeStatus.COMPLETED) {
            InvestigationResult investigation = result.getOutput("investigateClaim", InvestigationResult.class);
            System.out.println("Claim under investigation: " + investigation);
        }
    }
}
