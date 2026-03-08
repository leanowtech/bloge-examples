package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates the long-running <em>loan disbursement</em> pattern using
 * AND-mode runtime event matching (both document signing AND payment clearing required).
 *
 * <p>After a loan is approved, the system suspends at {@code awaitDocumentsAndPayment}
 * waiting for <em>both</em> events:
 * <ul>
 *   <li>{@code "document.signed"} — borrower completes e-signature</li>
 *   <li>{@code "payment.cleared"} — source account is cleared</li>
 * </ul>
 * Only when both events have been received does the correlation resolve and
 * downstream disbursement runs.
 *
 * <h2>Graph layout</h2>
 * <pre>
 * loadApprovedLoan → [sendAgreement ∥ requestClearing]
 *                            ↓         ↓
 *                  [SUSPEND awaitDocumentsAndPayment]  (AND-mode correlation)
 *                                  ↓
 *                    branch → disburseLoan → generateLoanStatement
 *                           → cancelLoan
 * </pre>
 *
 * <h2>Long-running lifecycle</h2>
 * <ol>
 *   <li>Execute → suspends at {@code awaitDocumentsAndPayment}.</li>
 *   <li>E-signature platform POSTs {@code "document.signed"} → first event received.</li>
 *   <li>Bank clearing POSTs {@code "payment.cleared"} → second event received.</li>
 *   <li>Both received → correlation transitions to MATCHED → save merged runtime node output
 *       → {@code resume()} runs downstream disbursement.</li>
 * </ol>
 */
@SuppressWarnings("preview")
public class LoanDisbursementWaitExample {

    // ── Records ───────────────────────────────────────────────────────────────

    public record LoanQuery(String loanId) {}
    public record LoanTerms(String loanId, double principal, String account, String terms) {}

    public record AgreementInput(String loanId, String customerId, LoanTerms terms) {}
    public record AgreementResult(String envelopeId, String sentAt) {}

    public record ClearingRequest(String loanId, double amount, String account) {}
    public record ClearingResult(String clearingRef, String requestedAt) {}

    public record DisburseInput(String loanId, String customerId, double amount,
                                Map<String, Object> events) {}
    public record DisburseResult(String disbursementId, String disbursedAt) {}

    public record StatementInput(String loanId, String disbursedAt, double amount) {}
    public record StatementResult(String statementId, String archivedAt) {}

    public record CancelInput(String loanId, String customerId, String reason) {}
    public record CancelResult(String cancelledAt) {}

    // ── Operators ─────────────────────────────────────────────────────────────

    static final Operator<LoanQuery, LoanTerms> LOAD_APPROVED_LOAN = (input, ctx) -> {
        Thread.sleep(30);
        System.out.println("  [loadApprovedLoan]   loanId=" + input.loanId() + " principal=50,000");
        return new LoanTerms(input.loanId(), 50_000.0, "ACC-2024-001", "30-year fixed 6.5%");
    };

    static final Operator<AgreementInput, AgreementResult> SEND_AGREEMENT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [sendAgreement]      envelope sent via DocuSign");
        return new AgreementResult("ENV-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString());
    };

    static final Operator<ClearingRequest, ClearingResult> REQUEST_CLEARING = (input, ctx) -> {
        Thread.sleep(25);
        System.out.println("  [requestClearing]    clearing request sent to bank");
        return new ClearingResult("CLR-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString());
    };

    /**
     * AND-mode await: suspends until BOTH {@code document.signed} and
     * {@code payment.cleared} events have been received for this {@code loanId}.
     */
    @SuppressWarnings("unchecked")
    static final SuspendableOperator<Map<String, Object>, Map<String, Object>> AWAIT_DOCUMENTS_AND_PAYMENT = (input, ctx) -> {
        String loanId = ctx.graphContext().get("loanId", String.class);
        System.out.println("  [awaitDocumentsAndPayment] SUSPENDING — AND-mode waiting for both events, loanId=" + loanId);
        return OperatorResult.suspend("await:loan:" + loanId, null, Duration.ofSeconds(2));
    };

    @SuppressWarnings("unchecked")
    static final Operator<DisburseInput, DisburseResult> DISBURSE_LOAN = (input, ctx) -> {
        Thread.sleep(50);
        System.out.printf("  [disburseLoan]       amount=%.0f disbursed to %s%n", input.amount(), "ACC-BORROWER");
        return new DisburseResult("DISB-001", Instant.now().toString());
    };

    static final Operator<StatementInput, StatementResult> GENERATE_STATEMENT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [generateLoanStatement] statement generated and archived");
        return new StatementResult("STMT-001", Instant.now().toString());
    };

    static final Operator<CancelInput, CancelResult> CANCEL_LOAN = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [cancelLoan]         reason=" + input.reason());
        return new CancelResult(Instant.now().toString());
    };

    // ── Main ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();

        // ── Build graph ───────────────────────────────────────────────────────
        Graph graph = Graph.builder("loanDisbursementWait")
                .node("loadApprovedLoan", LOAD_APPROVED_LOAN)
                    .input((results, ctx) -> new LoanQuery(ctx.get("loanId", String.class)))
                .node("sendAgreement", SEND_AGREEMENT)
                    .dependsOn("loadApprovedLoan")
                    .input((results, ctx) -> new AgreementInput(
                            ctx.get("loanId", String.class),
                            ctx.get("customerId", String.class),
                            results.get("loadApprovedLoan", LoanTerms.class)))
                .node("requestClearing", REQUEST_CLEARING)
                    .dependsOn("loadApprovedLoan")
                    .input((results, ctx) -> {
                        LoanTerms terms = results.get("loadApprovedLoan", LoanTerms.class);
                        return new ClearingRequest(terms.loanId(), terms.principal(), terms.account());
                    })
                .suspendNode("awaitDocumentsAndPayment", AWAIT_DOCUMENTS_AND_PAYMENT)
                    .dependsOn("sendAgreement", "requestClearing")
                    .input((results, ctx) -> Map.of(
                            "envelopeId",  results.get("sendAgreement", AgreementResult.class).envelopeId(),
                            "clearingRef", results.get("requestClearing", ClearingResult.class).clearingRef()))
                .branch("awaitDocumentsAndPayment")
                    .on("status")
                    .when(v -> "ready".equals(v), "disburseLoan")
                    .otherwise("cancelLoan")
                .node("disburseLoan", DISBURSE_LOAN)
                    .dependsOn("awaitDocumentsAndPayment")
                    .input((results, ctx) -> {
                        LoanTerms terms = results.get("loadApprovedLoan", LoanTerms.class);
                        // awaitDocumentsAndPayment may be a Map when loaded from checkpoint
                        Object events = results.getRaw("awaitDocumentsAndPayment");
                        return new DisburseInput(terms.loanId(),
                                ctx.get("customerId", String.class), terms.principal(),
                                events instanceof Map<?,?> m ? (Map<String,Object>)(Object)m : Map.of());
                    })
                .node("generateLoanStatement", GENERATE_STATEMENT)
                    .dependsOn("disburseLoan")
                    .input((results, ctx) -> {
                        LoanTerms terms = results.get("loadApprovedLoan", LoanTerms.class);
                        DisburseResult disburse = results.get("disburseLoan", DisburseResult.class);
                        return new StatementInput(terms.loanId(), disburse.disbursedAt(), terms.principal());
                    })
                .node("cancelLoan", CANCEL_LOAN)
                    .dependsOn("awaitDocumentsAndPayment")
                    .input((results, ctx) -> {
                        Object raw = results.getRaw("awaitDocumentsAndPayment");
                        String reason = "expired";
                        if (raw instanceof Map<?,?> m) { Object r = m.get("reason"); if (r instanceof String s) reason = s; }
                        return new CancelInput(ctx.get("loanId", String.class),
                                ctx.get("customerId", String.class), reason);
                    })
                .build();

        var ctx = new GraphContext(Map.of(
                "loanId",     "LOAN-2024-0042",
                "customerId", "CUST-BORROWER-01"
        ));

        // ── Phase 1: execute until suspension ────────────────────────────────
        System.out.println("\n═══ Phase 1: Execute loan disbursement until suspension ═══");
        GraphResult phase1 = engine.executeWithOperators(graph, ctx, Map.of(
                "loadApprovedLoan",         LOAD_APPROVED_LOAN,
                "sendAgreement",            SEND_AGREEMENT,
                "requestClearing",          REQUEST_CLEARING,
                "awaitDocumentsAndPayment", AWAIT_DOCUMENTS_AND_PAYMENT,
                "disburseLoan",             DISBURSE_LOAN,
                "generateLoanStatement",    GENERATE_STATEMENT,
                "cancelLoan",              CANCEL_LOAN
        ));

        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        for (var e : phase1.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", e.getKey(), e.getValue());
        }

        String execId = phase1.executionId();
        String loanId = ctx.get("loanId", String.class);

        // Register AND-mode correlation for both events
        runtime.registerAndCorrelation(execId, "awaitDocumentsAndPayment",
                LongRunningRuntimeExampleSupport.event("document.signed", "loanId", loanId),
                LongRunningRuntimeExampleSupport.event("payment.cleared", "loanId", loanId));

        // ── Phase 2a: first event — document signed ───────────────────────────
        System.out.println("\n═══ Phase 2a: Borrower signs the document ═══");
        Thread.sleep(100);
        engine.publishEvent("document.signed", loanId,
                Map.of("loanId", loanId, "signedAt", Instant.now().toString(), "envelopeId", "ENV-SIGNED"));
        // Correlation is not yet MATCHED (still waiting for payment.cleared)
        System.out.println("Event 1/2 received: document.signed — AND correlation still waiting...");

        // ── Phase 2b: second event — payment cleared ──────────────────────────
        System.out.println("\n═══ Phase 2b: Bank confirms payment cleared ═══");
        Thread.sleep(100);
        engine.publishEvent("payment.cleared", loanId,
                Map.of("loanId", loanId, "clearedAt", Instant.now().toString(), "amount", 50_000.0));
        System.out.println("Event 2/2 received: payment.cleared — AND correlation now MATCHED");

        // Persist the combined event data as runtime node output
        runtime.saveNodeOutput(execId, "loanDisbursementWait", "awaitDocumentsAndPayment",
                LongRunningRuntimeExampleSupport.payload(
                        "status", "ready",
                        "documentSigned", true,
                        "paymentCleared", true
                ));

        // ── Phase 3: resume ────────────────────────────────────────────────────
        System.out.println("\n═══ Phase 3: Resume loan disbursement ═══");
        registry.register("loadApprovedLoan",         LOAD_APPROVED_LOAN);
        registry.register("sendAgreement",            SEND_AGREEMENT);
        registry.register("requestClearing",          REQUEST_CLEARING);
        registry.registerRaw("awaitDocumentsAndPayment", AWAIT_DOCUMENTS_AND_PAYMENT);
        registry.register("disburseLoan",             DISBURSE_LOAN);
        registry.register("generateLoanStatement",    GENERATE_STATEMENT);
        registry.register("cancelLoan",               CANCEL_LOAN);

        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n═══ Final Result ═══");
        System.out.println("Success: " + phase3.isSuccess());
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", e.getKey(), e.getValue());
        }
        if (phase3.getStatus("generateLoanStatement") == NodeStatus.COMPLETED) {
            StatementResult stmt = phase3.getOutput("generateLoanStatement", StatementResult.class);
            System.out.println("Loan statement: " + stmt);
        }
    }
}
