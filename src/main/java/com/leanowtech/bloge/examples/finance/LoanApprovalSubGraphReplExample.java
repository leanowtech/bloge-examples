package com.leanowtech.bloge.examples.finance;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.parser.Parser;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class LoanApprovalSubGraphReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ReceiveApplicationOperator", LoanApprovalSubGraphDslExample.RECEIVE_APPLICATION);
        registry.register("UnderwritingDecisionOperator", LoanApprovalSubGraphDslExample.UNDERWRITING_DECISION);
        registry.register("GenerateApprovalLetterOperator", LoanApprovalSubGraphDslExample.GENERATE_APPROVAL_LETTER);
        registry.register("GenerateRejectionNoticeOperator", LoanApprovalSubGraphDslExample.GENERATE_REJECTION_NOTICE);
        registry.register("creditQuery", LoanApprovalSubGraphDslExample.CREDIT_QUERY);
        registry.register("incomeVerification", LoanApprovalSubGraphDslExample.INCOME_VERIFICATION);
        registry.register("debtRatioCalc", LoanApprovalSubGraphDslExample.DEBT_RATIO_CALC);
        registry.register("riskScoring", LoanApprovalSubGraphDslExample.RISK_SCORING);
        registry.register("amlScreening", LoanApprovalSubGraphDslExample.AML_SCREENING);
        registry.register("kycVerification", LoanApprovalSubGraphDslExample.KYC_VERIFICATION);
        registry.register("sanctionListCheck", LoanApprovalSubGraphDslExample.SANCTION_LIST_CHECK);
        registry.register("complianceDetermination", LoanApprovalSubGraphDslExample.COMPLIANCE_DETERMINATION);
        var compiler = new DslCompiler(registry);
        compiler.registerSubGraph("credit-assessment", LoanApprovalSubGraphDslExample.buildCreditAssessmentSubGraph());
        compiler.registerSubGraph("compliance-check", LoanApprovalSubGraphDslExample.buildComplianceCheckSubGraph());

        var tokens = new Lexer(DSL).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        return compiler.compile(ast);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String applicationId = ReplHelper.promptString(scanner, "applicationId", "LA-2025-00142");
        String applicantName = ReplHelper.promptString(scanner, "applicantName", "Jane Doe");
        double requestedAmount = ReplHelper.promptDouble(scanner, "requestedAmount", 275000.0);
        int termMonths = ReplHelper.promptInt(scanner, "termMonths", 30);
        String employerId = ReplHelper.promptString(scanner, "employerId", "EMP-ACME-99");
        return Map.of(
                "applicationId", applicationId,
                "applicantName", applicantName,
                "requestedAmount", requestedAmount,
                "termMonths", termMonths,
                "employerId", employerId
        );
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
                ReplHelper.header("Loan Approval Sub-Graph REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
