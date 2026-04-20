package com.leanowtech.bloge.examples.finance;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.parser.Parser;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DSL version of the loan approval pipeline with parallel sub-graphs.
 * <p>
 * Sub-graphs are built via Java API and registered with the DslCompiler,
 * then referenced in DSL using {@code subgraph("name")} syntax.
 */
@SuppressWarnings("preview")
public class LoanApprovalSubGraphDslExample {

    // --- Main graph operators (Map-based for DSL) ---

    static final Operator<Map<String, Object>, Map<String, Object>> RECEIVE_APPLICATION = (input, ctx) -> {
        Thread.sleep(35);
        return Map.of(
                "applicationId", input.get("applicationId"),
                "applicantName", input.get("applicantName"),
                "requestedAmount", input.get("requestedAmount"),
                "termMonths", input.get("termMonths"),
                "employerId", input.get("employerId"),
                "status", "RECEIVED");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> UNDERWRITING_DECISION = (input, ctx) -> {
        Thread.sleep(40);
        var credit = (Map<String, Object>) input.get("credit");
        var compliance = (Map<String, Object>) input.get("compliance");
        String riskGrade = (String) credit.get("riskGrade");
        boolean compliancePassed = (Boolean) compliance.get("passed");
        if (!compliancePassed) {
            return Map.of(
                    "applicationId", input.get("applicationId"),
                    "decision", "rejected",
                    "reason", "Compliance check failed",
                    "approvedRate", 0.0);
        }
        double rate = switch (riskGrade) {
            case "A" -> 4.5;
            case "B" -> 6.0;
            case "C" -> 8.5;
            default -> 0.0;
        };
        String decision = rate > 0.0 ? "approved" : "rejected";
        String reason = rate > 0.0
                ? "Risk grade " + riskGrade + " — rate " + rate + "%"
                : "Unacceptable risk grade: " + riskGrade;
        return Map.of(
                "applicationId", input.get("applicationId"),
                "decision", decision,
                "reason", reason,
                "approvedRate", rate);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERATE_APPROVAL_LETTER = (input, ctx) -> {
        Thread.sleep(25);
        String appId = (String) input.get("applicationId");
        String name = (String) input.get("applicantName");
        Number amount = (Number) input.get("requestedAmount");
        Number rate = (Number) input.get("approvedRate");
        Number term = (Number) input.get("termMonths");
        return Map.of(
                "applicationId", appId,
                "letterRef", "APR-" + appId,
                "message", "Dear " + name + ", your loan of $" + amount
                        + " at " + rate + "% for " + term + " months has been approved.");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERATE_REJECTION_NOTICE = (input, ctx) -> {
        Thread.sleep(20);
        String appId = (String) input.get("applicationId");
        String name = (String) input.get("applicantName");
        return Map.of(
                "applicationId", appId,
                "noticeRef", "REJ-" + appId,
                "message", "Dear " + name + ", your application has been declined. Reason: " + input.get("reason"));
    };

    // --- Credit assessment sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> CREDIT_QUERY = (input, ctx) -> {
        Thread.sleep(80);
        return Map.of("creditScore", 735, "bureau", "Equifax", "openAccounts", 4);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> INCOME_VERIFICATION = (input, ctx) -> {
        Thread.sleep(70);
        return Map.of("verified", true, "annualIncome", 92000.0, "verificationMethod", "employer-direct");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DEBT_RATIO_CALC = (input, ctx) -> {
        Thread.sleep(20);
        double annualIncome = ((Number) input.get("annualIncome")).doubleValue();
        double requestedAmount = ((Number) input.get("requestedAmount")).doubleValue();
        double ratio = (requestedAmount / annualIncome) * 100.0 / 30.0;
        String assessment = ratio < 0.36 ? "healthy" : ratio < 0.50 ? "moderate" : "high";
        return Map.of("debtToIncomeRatio", Math.round(ratio * 100.0) / 100.0, "assessment", assessment);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> RISK_SCORING = (input, ctx) -> {
        Thread.sleep(30);
        int creditScore = ((Number) input.get("creditScore")).intValue();
        double dti = ((Number) input.get("debtToIncomeRatio")).doubleValue();
        boolean incomeVerified = (Boolean) input.get("incomeVerified");
        double score = (creditScore / 850.0) * 0.5
                + (incomeVerified ? 0.3 : 0.0)
                + (1.0 - Math.min(dti, 1.0)) * 0.2;
        String grade = score >= 0.7 ? "A" : score >= 0.5 ? "B" : "C";
        return Map.of("riskGrade", grade, "compositeScore", Math.round(score * 100.0) / 100.0,
                "summary", "Credit " + creditScore + ", DTI " + dti);
    };

    // --- Compliance check sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> AML_SCREENING = (input, ctx) -> {
        Thread.sleep(90);
        return Map.of("cleared", true, "matchType", "none", "details", "No AML matches found");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> KYC_VERIFICATION = (input, ctx) -> {
        Thread.sleep(60);
        return Map.of("verified", true, "documentType", "passport",
                "verificationId", "KYC-" + input.get("applicationId"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SANCTION_LIST_CHECK = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of("cleared", true, "listChecked", "OFAC+EU", "timestamp", "2025-01-15T10:30:00Z");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> COMPLIANCE_DETERMINATION = (input, ctx) -> {
        Thread.sleep(25);
        boolean aml = (Boolean) input.get("amlCleared");
        boolean kyc = (Boolean) input.get("kycVerified");
        boolean sanction = (Boolean) input.get("sanctionCleared");
        boolean passed = aml && kyc && sanction;
        String grade = passed ? "PASS" : "FAIL";
        return Map.of("passed", passed, "complianceGrade", grade,
                "findings", passed ? List.of() : List.of("One or more compliance checks failed"));
    };

    // --- Sub-graph construction (Java API, Map-based) ---

    public static Graph buildCreditAssessmentSubGraph() {
        return Graph.builder("credit-assessment")
                .node("creditQuery", CREDIT_QUERY)
                    .input((results, ctx) -> Map.of(
                            "applicationId", ctx.get("applicationId", String.class),
                            "applicantName", ctx.get("applicantName", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.EXPONENTIAL)
                .node("incomeVerification", INCOME_VERIFICATION)
                    .dependsOn("creditQuery")
                    .input((results, ctx) -> Map.of(
                            "applicationId", ctx.get("applicationId", String.class),
                            "employerId", ctx.get("employerId", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("debtRatioCalc", DEBT_RATIO_CALC)
                    .dependsOn("incomeVerification", "creditQuery")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var income = (Map<String, Object>) results.getRaw("incomeVerification");
                        @SuppressWarnings("unchecked")
                        var credit = (Map<String, Object>) results.getRaw("creditQuery");
                        return Map.of(
                                "annualIncome", income.get("annualIncome"),
                                "openAccounts", credit.get("openAccounts"),
                                "requestedAmount", ctx.get("requestedAmount", Double.class));
                    })
                .node("riskScoring", RISK_SCORING)
                    .dependsOn("debtRatioCalc", "creditQuery", "incomeVerification")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var credit = (Map<String, Object>) results.getRaw("creditQuery");
                        @SuppressWarnings("unchecked")
                        var dti = (Map<String, Object>) results.getRaw("debtRatioCalc");
                        @SuppressWarnings("unchecked")
                        var income = (Map<String, Object>) results.getRaw("incomeVerification");
                        return Map.of(
                                "creditScore", credit.get("creditScore"),
                                "debtToIncomeRatio", dti.get("debtToIncomeRatio"),
                                "incomeVerified", income.get("verified"));
                    })
                .build();
    }

    public static Graph buildComplianceCheckSubGraph() {
        return Graph.builder("compliance-check")
                .node("amlScreening", AML_SCREENING)
                    .input((results, ctx) -> Map.of(
                            "applicationId", ctx.get("applicationId", String.class),
                            "applicantName", ctx.get("applicantName", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                .node("kycVerification", KYC_VERIFICATION)
                    .dependsOn("amlScreening")
                    .input((results, ctx) -> Map.of(
                            "applicationId", ctx.get("applicationId", String.class),
                            "applicantName", ctx.get("applicantName", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("sanctionListCheck", SANCTION_LIST_CHECK)
                    .dependsOn("kycVerification")
                    .input((results, ctx) -> Map.of(
                            "applicantName", ctx.get("applicantName", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("complianceDetermination", COMPLIANCE_DETERMINATION)
                    .dependsOn("amlScreening", "kycVerification", "sanctionListCheck")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var aml = (Map<String, Object>) results.getRaw("amlScreening");
                        @SuppressWarnings("unchecked")
                        var kyc = (Map<String, Object>) results.getRaw("kycVerification");
                        @SuppressWarnings("unchecked")
                        var sanction = (Map<String, Object>) results.getRaw("sanctionListCheck");
                        return Map.of(
                                "amlCleared", aml.get("cleared"),
                                "kycVerified", kyc.get("verified"),
                                "sanctionCleared", sanction.get("cleared"));
                    })
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // Register main graph operators
        // RECEIVE_APPLICATION: reads ctx fields → returns {applicationId, applicantName, requestedAmount, termMonths, employerId}
        registry.register("ReceiveApplicationOperator", RECEIVE_APPLICATION);
        // UNDERWRITING_DECISION: reads credit.riskGrade, compliance.passed → returns {decision, approvedRate, reason}
        registry.register("UnderwritingDecisionOperator", UNDERWRITING_DECISION);
        // GENERATE_APPROVAL_LETTER: reads applicationId, applicantName, amount, rate → returns {letterRef, message}
        registry.register("GenerateApprovalLetterOperator", GENERATE_APPROVAL_LETTER);
        // GENERATE_REJECTION_NOTICE: reads applicationId, applicantName, reason → returns {noticeRef, message}
        registry.register("GenerateRejectionNoticeOperator", GENERATE_REJECTION_NOTICE);

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        // CREDIT_QUERY: reads applicationId → returns {creditScore, bureau, openAccounts}
        registry.register("creditQuery", CREDIT_QUERY);
        // INCOME_VERIFICATION: reads applicationId, employerId → returns {verified, annualIncome, verificationMethod}
        registry.register("incomeVerification", INCOME_VERIFICATION);
        // DEBT_RATIO_CALC: reads annualIncome, requestedAmount, openAccounts → returns {debtToIncomeRatio, assessment}
        registry.register("debtRatioCalc", DEBT_RATIO_CALC);
        // RISK_SCORING: reads creditScore, debtToIncomeRatio, incomeVerified → returns {riskGrade, compositeScore}
        registry.register("riskScoring", RISK_SCORING);
        // AML_SCREENING: reads applicationId, applicantName → returns {cleared, matchType, details}
        registry.register("amlScreening", AML_SCREENING);
        // KYC_VERIFICATION: reads applicationId, applicantName → returns {verified, documentType, verificationId}
        registry.register("kycVerification", KYC_VERIFICATION);
        // SANCTION_LIST_CHECK: reads applicantName → returns {cleared, listChecked, timestamp}
        registry.register("sanctionListCheck", SANCTION_LIST_CHECK);
        // COMPLIANCE_DETERMINATION: fan-in of aml+kyc+sanction → returns {passed, complianceGrade, findings}
        registry.register("complianceDetermination", COMPLIANCE_DETERMINATION);

        // Build sub-graphs via Java API
        Graph creditGraph = buildCreditAssessmentSubGraph();
        Graph complianceGraph = buildComplianceCheckSubGraph();

        // Compile main graph from DSL with registered sub-graphs
        // register sub-graphs before loading main DSL; registerSubGraph() must precede compile()
        var compiler = new DslCompiler(registry);
        // sub-graph last-node output becomes the sub-graph node's output in the parent graph
        compiler.registerSubGraph("credit-assessment", creditGraph);
        compiler.registerSubGraph("compliance-check", complianceGraph);

        String dsl = """
                graph loanApprovalPipeline {
                  ///  receiveApplication: reads ctx fields → {applicationId, applicantName, requestedAmount, termMonths, employerId}
                  node receiveApplication : ReceiveApplicationOperator {
                    input {
                      applicationId   = ctx.applicationId
                      applicantName   = ctx.applicantName
                      requestedAmount = ctx.requestedAmount
                      termMonths      = ctx.termMonths
                      employerId      = ctx.employerId
                    }
                    timeout = 3s
                  }
                  ///  parallel sub-graphs: creditAssessment and complianceCheck run concurrently after receiveApplication
                  node creditAssessment : subgraph("credit-assessment") {
                    depends_on = [receiveApplication]
                    input {
                      applicationId   = receiveApplication.output.applicationId
                      applicantName   = receiveApplication.output.applicantName
                      requestedAmount = receiveApplication.output.requestedAmount
                      employerId      = receiveApplication.output.employerId
                    }
                    timeout = 30s
                  }
                  ///  complianceCheck: runs compliance-check sub-graph; last node is complianceDetermination
                  node complianceCheck : subgraph("compliance-check") {
                    depends_on = [receiveApplication]
                    input {
                      applicationId = receiveApplication.output.applicationId
                      applicantName = receiveApplication.output.applicantName
                    }
                    timeout = 30s
                  }
                  ///  underwritingDecision: fan-in of creditAssessment+complianceCheck → {decision, approvedRate, reason}
                  node underwritingDecision : UnderwritingDecisionOperator {
                    depends_on = [creditAssessment, complianceCheck]
                    input {
                      applicationId = receiveApplication.output.applicationId
                      credit        = creditAssessment.output.riskScoring
                      compliance    = complianceCheck.output.complianceDetermination
                    }
                  }
                  ///  branch outcomes: only one of generateApprovalLetter/generateRejectionNotice will execute
                  node generateApprovalLetter : GenerateApprovalLetterOperator {
                    depends_on = [underwritingDecision]
                    input {
                      applicationId   = underwritingDecision.output.applicationId
                      applicantName   = ctx.applicantName
                      requestedAmount = ctx.requestedAmount
                      approvedRate    = underwritingDecision.output.approvedRate
                      termMonths      = ctx.termMonths
                    }
                  }
                  node generateRejectionNotice : GenerateRejectionNoticeOperator {
                    depends_on = [underwritingDecision]
                    input {
                      applicationId = underwritingDecision.output.applicationId
                      applicantName = ctx.applicantName
                      reason        = underwritingDecision.output.reason
                    }
                  }
                  ///  branch: approved → generateApprovalLetter; otherwise → generateRejectionNotice
                  branch on underwritingDecision.output.decision {
                    "approved" -> generateApprovalLetter
                    otherwise  -> generateRejectionNotice
                  }
                }
                """;

        var tokens = new Lexer(dsl).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        // compile DSL; operators resolved by PascalCase name; sub-graphs resolved by registered name
        Graph graph = compiler.compile(ast);

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

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        // Print results
        System.out.println("\n═══ DSL Loan Approval Pipeline Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-30s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map<String,Object> if typed access is needed
        if (result.getStatus("underwritingDecision") == NodeStatus.COMPLETED) {
            System.out.println("Underwriting decision: " + result.results().getRaw("underwritingDecision"));
        }

        if (result.getStatus("generateApprovalLetter") == NodeStatus.COMPLETED) {
            System.out.println("Approval letter: " + result.results().getRaw("generateApprovalLetter"));
        } else if (result.getStatus("generateRejectionNotice") == NodeStatus.COMPLETED) {
            System.out.println("Rejection notice: " + result.results().getRaw("generateRejectionNotice"));
        }

        if (result.getStatus("creditAssessment") == NodeStatus.COMPLETED) {
            System.out.println("Credit sub-graph output: " + result.results().getRaw("creditAssessment"));
        }

        if (result.getStatus("complianceCheck") == NodeStatus.COMPLETED) {
            System.out.println("Compliance sub-graph output: " + result.results().getRaw("complianceCheck"));
        }
    }
}
