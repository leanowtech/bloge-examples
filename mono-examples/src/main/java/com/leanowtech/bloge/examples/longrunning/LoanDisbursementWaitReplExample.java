package com.leanowtech.bloge.examples.longrunning;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.time.Instant;
import java.util.Map;
import java.util.Scanner;

public class LoanDisbursementWaitReplExample {

    private static final String DSL = """

                graph loanDisbursementWait {

                  node loadApprovedLoan : LoadApprovedLoanOperator {
                    input { loanId = ctx.loanId }
                    timeout = 5s
                  }

                  node sendAgreement : SendAgreementOperator {
                    depends_on = [loadApprovedLoan]
                    input {
                      loanId     = ctx.loanId
                      customerId = ctx.customerId
                      terms      = loadApprovedLoan.output.terms
                    }
                    timeout = 10s
                  }

                  node requestClearing : RequestClearingOperator {
                    depends_on = [loadApprovedLoan]
                    input {
                      loanId  = ctx.loanId
                      amount  = loadApprovedLoan.output.principal
                      account = loadApprovedLoan.output.account
                    }
                    timeout = 10s
                  }

                  /// Represents: await awaitDocumentsAndPayment { mode = and
                  ///   event "document.signed" where loanId = ctx.loanId
                  ///   event "payment.cleared" where loanId = ctx.loanId
                  ///   timeout = 7d
                  /// }
                  node awaitDocumentsAndPayment : AwaitDocumentsAndPaymentOperator {
                    depends_on = [sendAgreement, requestClearing]
                    input {
                      envelopeId  = sendAgreement.output.envelopeId
                      clearingRef = requestClearing.output.clearingRef
                    }
                  }

                  branch on awaitDocumentsAndPayment.output.status {
                    "ready"   -> disburseLoan
                    otherwise -> cancelLoan
                  }

                  node disburseLoan : DisburseLoanOperator {
                    depends_on = [awaitDocumentsAndPayment]
                    input {
                      loanId     = ctx.loanId
                      customerId = ctx.customerId
                      amount     = loadApprovedLoan.output.principal
                      documents  = awaitDocumentsAndPayment.output
                    }
                  }

                  node generateLoanStatement : GenerateLoanStatementOperator {
                    depends_on = [disburseLoan]
                    input {
                      loanId     = ctx.loanId
                      disbursedAt = disburseLoan.output.disbursedAt
                      amount      = loadApprovedLoan.output.principal
                    }
                  }

                  node cancelLoan : CancelLoanOperator {
                    depends_on = [awaitDocumentsAndPayment]
                    input {
                      loanId     = ctx.loanId
                      customerId = ctx.customerId
                      reason     = awaitDocumentsAndPayment.output.reason
                    }
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("LoadApprovedLoanOperator", LoanDisbursementWaitDslExample.LOAD_APPROVED_LOAN);
        registry.register("SendAgreementOperator", LoanDisbursementWaitDslExample.SEND_AGREEMENT);
        registry.register("RequestClearingOperator", LoanDisbursementWaitDslExample.REQUEST_CLEARING);
        registry.registerRaw("AwaitDocumentsAndPaymentOperator", LoanDisbursementWaitDslExample.AWAIT_DOCUMENTS_AND_PAYMENT);
        registry.register("DisburseLoanOperator", LoanDisbursementWaitDslExample.DISBURSE_LOAN);
        registry.register("GenerateLoanStatementOperator", LoanDisbursementWaitDslExample.GENERATE_STATEMENT);
        registry.register("CancelLoanOperator", LoanDisbursementWaitDslExample.CANCEL_LOAN);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String loanId = ReplHelper.promptString(scanner, "loanId", "LOAN-DSL-0042");
        String customerId = ReplHelper.promptString(scanner, "customerId", "CUST-BORROWER-DSL");
        return Map.of(
                "loanId", loanId,
                "customerId", customerId
        );
    }

    public static void main(String[] args) throws Exception {
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Loan Disbursement Wait REPL");
                Map<String, Object> values = promptContext(scanner);

                var registry = new DefaultOperatorRegistry();
                Graph graph = buildGraph(registry);
                var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
                var engine = runtime.engine();

                GraphResult phase1 = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(phase1);

                if (phase1.isSuspended() || phase1.getStatus("awaitDocumentsAndPayment") == NodeStatus.SUSPENDED) {
                    String loanId = String.valueOf(values.get("loanId"));
                    runtime.registerAndCorrelation(phase1.executionId(), "awaitDocumentsAndPayment",
                            LongRunningRuntimeExampleSupport.event("document.signed", "loanId", loanId),
                            LongRunningRuntimeExampleSupport.event("payment.cleared", "loanId", loanId));
                    System.out.print("Press Enter to simulate document signed");
                    scanner.nextLine();
                    engine.publishEvent("document.signed", loanId,
                            LongRunningRuntimeExampleSupport.payload(
                                    "loanId", loanId,
                                    "signedAt", Instant.now().toString(),
                                    "envelopeId", "ENV-DSL-001"
                            ));
                    System.out.print("Press Enter to simulate payment cleared");
                    scanner.nextLine();
                    engine.publishEvent("payment.cleared", loanId,
                            LongRunningRuntimeExampleSupport.payload(
                                    "loanId", loanId,
                                    "clearedAt", Instant.now().toString(),
                                    "amount", 50_000.0
                            ));

                    runtime.saveNodeOutput(phase1.executionId(), "loanDisbursementWait", "awaitDocumentsAndPayment",
                            LongRunningRuntimeExampleSupport.payload(
                                    "status", "ready",
                                    "documentSigned", true,
                                    "paymentCleared", true
                            ));

                    GraphResult phase2 = engine.resume(graph, phase1.executionId(), new GraphContext(values));
                    ReplHelper.printResult(phase2);
                }

                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
