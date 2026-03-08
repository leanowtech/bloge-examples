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
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates parallel sub-graph execution in a loan approval pipeline.
 * <p>
 * Main graph: receiveApplication → [creditAssessment ∥ complianceCheck] → underwritingDecision
 *             → branch(approved/rejected) → generateApprovalLetter or generateRejectionNotice
 * <p>
 * Sub-graph A (credit-assessment): creditQuery → incomeVerification → debtRatioCalc → riskScoring
 * Sub-graph B (compliance-check): amlScreening → kycVerification → sanctionListCheck → complianceDetermination
 */
public class LoanApprovalSubGraphExample {

    // --- Main graph records ---

    public record LoanApplication(String applicationId, String applicantName, double requestedAmount,
                                  int termMonths, String employerId) {}
    public record ReceivedApplication(String applicationId, String applicantName, double requestedAmount,
                                      int termMonths, String employerId, String status) {}
    public record UnderwritingInput(String applicationId, double requestedAmount, String riskGrade,
                                    boolean compliancePassed) {}
    public record UnderwritingDecision(String applicationId, String decision, String reason,
                                       double approvedRate) {}
    public record ApprovalLetterInput(String applicationId, String applicantName, double approvedAmount,
                                      double rate, int termMonths) {}
    public record ApprovalLetter(String applicationId, String letterRef, String message) {}
    public record RejectionNoticeInput(String applicationId, String applicantName, String reason) {}
    public record RejectionNotice(String applicationId, String noticeRef, String message) {}

    // --- Credit assessment sub-graph records ---

    public record CreditQueryInput(String applicationId, String applicantName) {}
    public record CreditQueryResult(int creditScore, String bureau, int openAccounts) {}
    public record IncomeVerifyInput(String applicationId, String employerId) {}
    public record IncomeVerifyResult(boolean verified, double annualIncome, String verificationMethod) {}
    public record DebtRatioInput(double annualIncome, int openAccounts, double requestedAmount) {}
    public record DebtRatioResult(double debtToIncomeRatio, String assessment) {}
    public record RiskScoringInput(int creditScore, double debtToIncomeRatio, boolean incomeVerified) {}
    public record RiskScoringResult(String riskGrade, double compositeScore, String summary) {}

    // --- Compliance check sub-graph records ---

    public record AmlScreeningInput(String applicationId, String applicantName) {}
    public record AmlScreeningResult(boolean cleared, String matchType, String details) {}
    public record KycVerifyInput(String applicationId, String applicantName) {}
    public record KycVerifyResult(boolean verified, String documentType, String verificationId) {}
    public record SanctionCheckInput(String applicantName) {}
    public record SanctionCheckResult(boolean cleared, String listChecked, String timestamp) {}
    public record ComplianceInput(boolean amlCleared, boolean kycVerified, boolean sanctionCleared) {}
    public record ComplianceResult(boolean passed, String complianceGrade, List<String> findings) {}

    // --- Main graph operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"finance", "loan"},
            description = "Receives and validates the incoming loan application", owner = "lending-team")
    static final Operator<LoanApplication, ReceivedApplication> RECEIVE_APPLICATION = (input, ctx) -> {
        Thread.sleep(35);
        return new ReceivedApplication(input.applicationId(), input.applicantName(),
                input.requestedAmount(), input.termMonths(), input.employerId(), "RECEIVED");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"finance", "underwriting"},
            description = "Makes underwriting decision based on credit and compliance results", owner = "underwriting-team")
    static final Operator<UnderwritingInput, UnderwritingDecision> UNDERWRITING_DECISION = (input, ctx) -> {
        Thread.sleep(40);
        if (!input.compliancePassed()) {
            return new UnderwritingDecision(input.applicationId(), "rejected",
                    "Compliance check failed", 0.0);
        }
        double rate = switch (input.riskGrade()) {
            case "A" -> 4.5;
            case "B" -> 6.0;
            case "C" -> 8.5;
            default -> 0.0;
        };
        String decision = rate > 0.0 ? "approved" : "rejected";
        String reason = rate > 0.0
                ? "Risk grade " + input.riskGrade() + " — rate " + rate + "%"
                : "Unacceptable risk grade: " + input.riskGrade();
        return new UnderwritingDecision(input.applicationId(), decision, reason, rate);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"finance", "notification"},
            description = "Generates approval letter for approved loans", owner = "lending-team")
    static final Operator<ApprovalLetterInput, ApprovalLetter> GENERATE_APPROVAL_LETTER = (input, ctx) -> {
        Thread.sleep(25);
        return new ApprovalLetter(input.applicationId(), "APR-" + input.applicationId(),
                "Dear " + input.applicantName() + ", your loan of $" + input.approvedAmount()
                        + " at " + input.rate() + "% for " + input.termMonths() + " months has been approved.");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"finance", "notification"},
            description = "Generates rejection notice for declined loans", owner = "lending-team")
    static final Operator<RejectionNoticeInput, RejectionNotice> GENERATE_REJECTION_NOTICE = (input, ctx) -> {
        Thread.sleep(20);
        return new RejectionNotice(input.applicationId(), "REJ-" + input.applicationId(),
                "Dear " + input.applicantName() + ", your application has been declined. Reason: " + input.reason());
    };

    // --- Credit assessment sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"finance", "credit"},
            description = "Queries credit bureau for applicant credit history", owner = "risk-team")
    static final Operator<CreditQueryInput, CreditQueryResult> CREDIT_QUERY = (input, ctx) -> {
        Thread.sleep(80);
        return new CreditQueryResult(735, "Equifax", 4);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"finance", "income"},
            description = "Verifies applicant income with employer", owner = "risk-team")
    static final Operator<IncomeVerifyInput, IncomeVerifyResult> INCOME_VERIFICATION = (input, ctx) -> {
        Thread.sleep(70);
        return new IncomeVerifyResult(true, 92000.0, "employer-direct");
    };

    static final Operator<DebtRatioInput, DebtRatioResult> DEBT_RATIO_CALC = (input, ctx) -> {
        Thread.sleep(20);
        double ratio = (input.requestedAmount() / input.annualIncome()) * 100.0 / 30.0;
        String assessment = ratio < 0.36 ? "healthy" : ratio < 0.50 ? "moderate" : "high";
        return new DebtRatioResult(Math.round(ratio * 100.0) / 100.0, assessment);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"finance", "risk"},
            description = "Computes composite risk score from credit data", owner = "risk-team")
    static final Operator<RiskScoringInput, RiskScoringResult> RISK_SCORING = (input, ctx) -> {
        Thread.sleep(30);
        double score = (input.creditScore() / 850.0) * 0.5
                + (input.incomeVerified() ? 0.3 : 0.0)
                + (1.0 - Math.min(input.debtToIncomeRatio(), 1.0)) * 0.2;
        String grade = score >= 0.7 ? "A" : score >= 0.5 ? "B" : "C";
        return new RiskScoringResult(grade, Math.round(score * 100.0) / 100.0,
                "Credit " + input.creditScore() + ", DTI " + input.debtToIncomeRatio());
    };

    // --- Compliance check sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"finance", "compliance"},
            description = "Screens applicant against AML databases", owner = "compliance-team")
    static final Operator<AmlScreeningInput, AmlScreeningResult> AML_SCREENING = (input, ctx) -> {
        Thread.sleep(90);
        return new AmlScreeningResult(true, "none", "No AML matches found");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"finance", "compliance"},
            description = "Verifies applicant identity via KYC process", owner = "compliance-team")
    static final Operator<KycVerifyInput, KycVerifyResult> KYC_VERIFICATION = (input, ctx) -> {
        Thread.sleep(60);
        return new KycVerifyResult(true, "passport", "KYC-" + input.applicationId());
    };

    static final Operator<SanctionCheckInput, SanctionCheckResult> SANCTION_LIST_CHECK = (input, ctx) -> {
        Thread.sleep(50);
        return new SanctionCheckResult(true, "OFAC+EU", "2025-01-15T10:30:00Z");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"finance", "compliance"},
            description = "Determines overall compliance status from screening results", owner = "compliance-team")
    static final Operator<ComplianceInput, ComplianceResult> COMPLIANCE_DETERMINATION = (input, ctx) -> {
        Thread.sleep(25);
        boolean passed = input.amlCleared() && input.kycVerified() && input.sanctionCleared();
        String grade = passed ? "PASS" : "FAIL";
        List<String> findings = passed ? List.of() : List.of("One or more compliance checks failed");
        return new ComplianceResult(passed, grade, findings);
    };

    // --- Sub-graph construction ---

    public static Graph buildCreditAssessmentSubGraph() {
        return Graph.builder("credit-assessment")
                .node("creditQuery", CREDIT_QUERY)
                    .input((results, ctx) -> new CreditQueryInput(
                            ctx.get("applicationId", String.class),
                            ctx.get("applicantName", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.EXPONENTIAL)
                .node("incomeVerification", INCOME_VERIFICATION)
                    .dependsOn("creditQuery")
                    .input((results, ctx) -> new IncomeVerifyInput(
                            ctx.get("applicationId", String.class),
                            ctx.get("employerId", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .fallback(ex -> new IncomeVerifyResult(false, 0.0, "unavailable"))
                .node("debtRatioCalc", DEBT_RATIO_CALC)
                    .dependsOn("incomeVerification", "creditQuery")
                    .input((results, ctx) -> new DebtRatioInput(
                            results.get("incomeVerification", IncomeVerifyResult.class).annualIncome(),
                            results.get("creditQuery", CreditQueryResult.class).openAccounts(),
                            ctx.get("requestedAmount", Double.class)))
                .node("riskScoring", RISK_SCORING)
                    .dependsOn("debtRatioCalc", "creditQuery", "incomeVerification")
                    .input((results, ctx) -> new RiskScoringInput(
                            results.get("creditQuery", CreditQueryResult.class).creditScore(),
                            results.get("debtRatioCalc", DebtRatioResult.class).debtToIncomeRatio(),
                            results.get("incomeVerification", IncomeVerifyResult.class).verified()))
                .build();
    }

    public static Graph buildComplianceCheckSubGraph() {
        return Graph.builder("compliance-check")
                .node("amlScreening", AML_SCREENING)
                    .input((results, ctx) -> new AmlScreeningInput(
                            ctx.get("applicationId", String.class),
                            ctx.get("applicantName", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                .node("kycVerification", KYC_VERIFICATION)
                    .dependsOn("amlScreening")
                    .input((results, ctx) -> new KycVerifyInput(
                            ctx.get("applicationId", String.class),
                            ctx.get("applicantName", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("sanctionListCheck", SANCTION_LIST_CHECK)
                    .dependsOn("kycVerification")
                    .input((results, ctx) -> new SanctionCheckInput(
                            ctx.get("applicantName", String.class)))
                    .timeout(Duration.ofSeconds(3))
                    .fallback(ex -> new SanctionCheckResult(false, "unavailable", "service_down"))
                .node("complianceDetermination", COMPLIANCE_DETERMINATION)
                    .dependsOn("amlScreening", "kycVerification", "sanctionListCheck")
                    .input((results, ctx) -> new ComplianceInput(
                            results.get("amlScreening", AmlScreeningResult.class).cleared(),
                            results.get("kycVerification", KycVerifyResult.class).verified(),
                            results.get("sanctionListCheck", SanctionCheckResult.class).cleared()))
                .build();
    }

    @SuppressWarnings({"preview", "unchecked"})
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        registry.register("creditQuery", CREDIT_QUERY);
        registry.register("incomeVerification", INCOME_VERIFICATION);
        registry.register("debtRatioCalc", DEBT_RATIO_CALC);
        registry.register("riskScoring", RISK_SCORING);
        registry.register("amlScreening", AML_SCREENING);
        registry.register("kycVerification", KYC_VERIFICATION);
        registry.register("sanctionListCheck", SANCTION_LIST_CHECK);
        registry.register("complianceDetermination", COMPLIANCE_DETERMINATION);

        // Build sub-graphs
        Graph creditGraph = buildCreditAssessmentSubGraph();
        Graph complianceGraph = buildComplianceCheckSubGraph();

        // Wrap as SubGraphOperators
        SubGraphOperator creditSubGraph = new SubGraphOperator(creditGraph, registry);
        SubGraphOperator complianceSubGraph = new SubGraphOperator(complianceGraph, registry);

        // Build main graph
        Graph mainGraph = Graph.builder("loanApprovalPipeline")
                .node("receiveApplication", RECEIVE_APPLICATION)
                    .input((results, ctx) -> new LoanApplication(
                            ctx.get("applicationId", String.class),
                            ctx.get("applicantName", String.class),
                            ctx.get("requestedAmount", Double.class),
                            ctx.get("termMonths", Integer.class),
                            ctx.get("employerId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("creditAssessment", creditSubGraph)
                    .dependsOn("receiveApplication")
                    .input((results, ctx) -> {
                        var app = results.get("receiveApplication", ReceivedApplication.class);
                        return Map.of(
                                "applicationId", app.applicationId(),
                                "applicantName", app.applicantName(),
                                "requestedAmount", app.requestedAmount(),
                                "employerId", app.employerId());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("complianceCheck", complianceSubGraph)
                    .dependsOn("receiveApplication")
                    .input((results, ctx) -> {
                        var app = results.get("receiveApplication", ReceivedApplication.class);
                        return Map.of(
                                "applicationId", app.applicationId(),
                                "applicantName", app.applicantName());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("underwritingDecision", UNDERWRITING_DECISION)
                    .dependsOn("creditAssessment", "complianceCheck")
                    .input((results, ctx) -> {
                        var creditOut = (Map<String, Object>) results.getRaw("creditAssessment");
                        var riskResult = (RiskScoringResult) creditOut.get("riskScoring");
                        var complianceOut = (Map<String, Object>) results.getRaw("complianceCheck");
                        var complianceResult = (ComplianceResult) complianceOut.get("complianceDetermination");
                        return new UnderwritingInput(
                                ctx.get("applicationId", String.class),
                                ctx.get("requestedAmount", Double.class),
                                riskResult.riskGrade(),
                                complianceResult.passed());
                    })
                .node("generateApprovalLetter", GENERATE_APPROVAL_LETTER)
                    .dependsOn("underwritingDecision")
                    .input((results, ctx) -> {
                        var decision = results.get("underwritingDecision", UnderwritingDecision.class);
                        return new ApprovalLetterInput(
                                decision.applicationId(),
                                ctx.get("applicantName", String.class),
                                ctx.get("requestedAmount", Double.class),
                                decision.approvedRate(),
                                ctx.get("termMonths", Integer.class));
                    })
                .node("generateRejectionNotice", GENERATE_REJECTION_NOTICE)
                    .dependsOn("underwritingDecision")
                    .input((results, ctx) -> {
                        var decision = results.get("underwritingDecision", UnderwritingDecision.class);
                        return new RejectionNoticeInput(
                                decision.applicationId(),
                                ctx.get("applicantName", String.class),
                                decision.reason());
                    })
                .branch("underwritingDecision")
                    .on("decision")
                    .when(val -> "approved".equals(val), "generateApprovalLetter")
                    .otherwise("generateRejectionNotice")
                .build();

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "applicationId", "LA-2025-00142",
                "applicantName", "Jane Doe",
                "requestedAmount", 275000.0,
                "termMonths", 30,
                "employerId", "EMP-ACME-99"
        ));

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "receiveApplication", RECEIVE_APPLICATION,
                "creditAssessment", creditSubGraph,
                "complianceCheck", complianceSubGraph,
                "underwritingDecision", UNDERWRITING_DECISION,
                "generateApprovalLetter", GENERATE_APPROVAL_LETTER,
                "generateRejectionNotice", GENERATE_REJECTION_NOTICE
        ));

        // Print results
        System.out.println("\n═══ Loan Approval Pipeline Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-30s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("underwritingDecision") == NodeStatus.COMPLETED) {
            UnderwritingDecision decision = result.getOutput("underwritingDecision", UnderwritingDecision.class);
            System.out.println("Underwriting decision: " + decision);
        }

        if (result.getStatus("generateApprovalLetter") == NodeStatus.COMPLETED) {
            ApprovalLetter letter = result.getOutput("generateApprovalLetter", ApprovalLetter.class);
            System.out.println("Approval letter: " + letter);
        } else if (result.getStatus("generateRejectionNotice") == NodeStatus.COMPLETED) {
            RejectionNotice notice = result.getOutput("generateRejectionNotice", RejectionNotice.class);
            System.out.println("Rejection notice: " + notice);
        }

        if (result.getStatus("creditAssessment") == NodeStatus.COMPLETED) {
            System.out.println("Credit sub-graph output: " + result.results().getRaw("creditAssessment"));
        }

        if (result.getStatus("complianceCheck") == NodeStatus.COMPLETED) {
            System.out.println("Compliance sub-graph output: " + result.results().getRaw("complianceCheck"));
        }
    }
}
