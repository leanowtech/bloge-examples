package com.leanowtech.bloge.examples.longrunning;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import java.time.Instant;
import java.util.Map;
/**
 * DSL version of the loan-disbursement AND-mode await example.
 *
 * <p>Loads the graph from an inline DSL string that mirrors
 * {@code loan-disbursement-wait.bloge}.  The {@code awaitDocumentsAndPayment}
 * node represents the {@code await ... mode = and} DSL block; it returns a
 * suspended result until both events have been received.
 */
@SuppressWarnings("preview")
public class LoanDisbursementWaitDslExample {
    static final Operator<Map<String, Object>, Map<String, Object>> LOAD_APPROVED_LOAN = (input, ctx) -> {
        Thread.sleep(30);
        System.out.println("  [loadApprovedLoan]   principal=50000 account=ACC-2024-001");
        return Map.of("loanId", input.get("loanId"), "principal", 50_000.0,
                "account", "ACC-2024-001", "terms", "30-year fixed 6.5%");
    };
    static final Operator<Map<String, Object>, Map<String, Object>> SEND_AGREEMENT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [sendAgreement]      DocuSign envelope sent");
        return Map.of("envelopeId", "ENV-DSL-001", "sentAt", Instant.now().toString());
    };
    static final Operator<Map<String, Object>, Map<String, Object>> REQUEST_CLEARING = (input, ctx) -> {
        Thread.sleep(25);
        System.out.println("  [requestClearing]    bank clearing request sent");
        return Map.of("clearingRef", "CLR-DSL-001", "requestedAt", Instant.now().toString());
    };
    /** AND-mode: returns a suspended result until both document.signed AND payment.cleared arrive. */
    static final SuspendableOperator<Map<String, Object>, Map<String, Object>> AWAIT_DOCUMENTS_AND_PAYMENT = (input, ctx) -> {
        String loanId = ctx.graphContext().get("loanId", String.class);
        System.out.println("  [awaitDocumentsAndPayment] SUSPENDING — AND-mode loanId=" + loanId);
        return OperatorResult.suspend("await:loan:" + loanId, null, java.time.Duration.ofSeconds(2));
    };
    static final Operator<Map<String, Object>, Map<String, Object>> DISBURSE_LOAN = (input, ctx) -> {
        Thread.sleep(50);
        System.out.printf("  [disburseLoan]       amount=%.0f disbursed%n", input.get("amount"));
        return Map.of("disbursementId", "DISB-DSL-001", "disbursedAt", Instant.now().toString());
    };
    static final Operator<Map<String, Object>, Map<String, Object>> GENERATE_STATEMENT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [generateLoanStatement] archived");
        return Map.of("statementId", "STMT-DSL-001", "archivedAt", Instant.now().toString());
    };
    static final Operator<Map<String, Object>, Map<String, Object>> CANCEL_LOAN = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [cancelLoan] reason=" + input.get("reason"));
        return Map.of("cancelledAt", Instant.now().toString());
    };
    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        registry.register("LoadApprovedLoanOperator",        LOAD_APPROVED_LOAN);
        registry.register("SendAgreementOperator",           SEND_AGREEMENT);
        registry.register("RequestClearingOperator",         REQUEST_CLEARING);
        // Represents: await awaitDocumentsAndPayment { mode = and event "document.signed" ... event "payment.cleared" ... }
        registry.registerRaw("AwaitDocumentsAndPaymentOperator", AWAIT_DOCUMENTS_AND_PAYMENT);
        registry.register("DisburseLoanOperator",            DISBURSE_LOAN);
        registry.register("GenerateLoanStatementOperator",   GENERATE_STATEMENT);
        registry.register("CancelLoanOperator",              CANCEL_LOAN);
        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();
        var loader = new GraphLoader(registry);
        String dsl = """
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
        Graph graph = loader.load(dsl);
        var ctx = new GraphContext(Map.of(
                "loanId",     "LOAN-DSL-0042",
                "customerId", "CUST-BORROWER-DSL"
        ));
        System.out.println("\n═══ Phase 1 (DSL): Execute loan disbursement ═══");
        GraphResult phase1 = engine.execute(graph, ctx);
        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        for (var e : phase1.statusMap().entrySet()) {
            System.out.printf("  %-30s → %s%n", e.getKey(), e.getValue());
        }
        String execId = phase1.executionId();
        String loanId = ctx.get("loanId", String.class);
        runtime.registerAndCorrelation(execId, "awaitDocumentsAndPayment",
                LongRunningRuntimeExampleSupport.event("document.signed", "loanId", loanId),
                LongRunningRuntimeExampleSupport.event("payment.cleared", "loanId", loanId));
        // Simulate: first event received (document signed)
        System.out.println("\n═══ Phase 2a (DSL): Borrower signs document ═══");
        Thread.sleep(100);
        engine.publishEvent("document.signed", loanId,
                LongRunningRuntimeExampleSupport.payload(
                        "loanId", loanId,
                        "signedAt", Instant.now().toString(),
                        "envelopeId", "ENV-DSL-001"
                ));
        System.out.println("Event 1/2 received: document.signed — still waiting for payment.cleared");
        // Simulate: second event received (payment cleared)
        System.out.println("\n═══ Phase 2b (DSL): Bank clears payment ═══");
        Thread.sleep(100);
        engine.publishEvent("payment.cleared", loanId,
                LongRunningRuntimeExampleSupport.payload(
                        "loanId", loanId,
                        "clearedAt", Instant.now().toString(),
                        "amount", 50_000.0
                ));
        System.out.println("Event 2/2 received: payment.cleared — AND correlation MATCHED");
        // Persist merged event state as completed runtime node output
        runtime.saveNodeOutput(execId, "loanDisbursementWait", "awaitDocumentsAndPayment",
                LongRunningRuntimeExampleSupport.payload(
                        "status", "ready",
                        "documentSigned", true,
                        "paymentCleared", true
                ));
        System.out.println("\n═══ Phase 3 (DSL): Resume loan disbursement ═══");
        GraphResult phase3 = engine.resume(graph, execId, ctx);
        System.out.println("\n═══ Final DSL Result ═══");
        System.out.println("Success: " + phase3.isSuccess());
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-30s → %s%n", e.getKey(), e.getValue());
        }
        if (phase3.getStatus("generateLoanStatement") == NodeStatus.COMPLETED) {
            System.out.println("Loan statement: " + phase3.results().getRaw("generateLoanStatement"));
        }
    }
}
