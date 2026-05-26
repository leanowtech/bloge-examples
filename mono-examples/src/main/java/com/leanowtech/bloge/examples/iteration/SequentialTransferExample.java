package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates sequential ForEach execution for bank transfers.
 * <p>
 * Main graph: fetchTransfers → processTransfers (forEach sequential) → auditReport
 * <p>
 * Sub-graph per item: riskCheck → executeTransfer → recordLedger
 * <p>
 * Sequential mode ensures each transfer is fully processed before the next begins,
 * maintaining account balance consistency.
 */
@SuppressWarnings("preview")
public class SequentialTransferExample {

    // --- Records ---

    public record Transfer(String fromAccount, String toAccount, double amount, String currency) {}
    public record RiskResult(boolean approved, String riskLevel, String reason) {}
    public record TransferResult(String transferId, String status, double amount) {}
    public record LedgerEntry(String transferId, String fromAccount, String toAccount, double amount, int index) {}
    public record AuditReport(int totalTransfers, int approved, int rejected, double totalAmount) {}

    // --- Operators ---

    static final Operator<Map<String, Object>, List<Transfer>> FETCH_TRANSFERS = (input, ctx) -> {
        Thread.sleep(30);
        return List.of(
                new Transfer("ACC-001", "ACC-002", 1500.00, "USD"),
                new Transfer("ACC-001", "ACC-003", 250.75, "USD"),
                new Transfer("ACC-004", "ACC-001", 3200.00, "USD")
        );
    };

    static final Operator<Map<String, Object>, RiskResult> RISK_CHECK = (input, ctx) -> {
        Thread.sleep(20);
        double amount = ((Number) input.get("amount")).doubleValue();
        String riskLevel = amount > 2000 ? "HIGH" : "LOW";
        boolean approved = amount <= 5000;
        return new RiskResult(approved, riskLevel, approved ? "Within limits" : "Exceeds maximum");
    };

    static final Operator<Map<String, Object>, TransferResult> EXECUTE_TRANSFER = (input, ctx) -> {
        Thread.sleep(40);
        @SuppressWarnings("unchecked")
        var transfer = (Transfer) input.get("transfer");
        String transferId = "TXN-" + UUID.randomUUID().toString().substring(0, 8);
        return new TransferResult(transferId, "COMPLETED", transfer.amount());
    };

    static final Operator<Map<String, Object>, LedgerEntry> RECORD_LEDGER = (input, ctx) -> {
        Thread.sleep(15);
        @SuppressWarnings("unchecked")
        var transfer = (Transfer) input.get("transfer");
        @SuppressWarnings("unchecked")
        var execution = (TransferResult) input.get("execution");
        int index = ((Number) input.get("index")).intValue();
        return new LedgerEntry(execution.transferId(), transfer.fromAccount(), transfer.toAccount(),
                transfer.amount(), index);
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, AuditReport> AUDIT_REPORT = (input, ctx) -> {
        Thread.sleep(10);
        var transferResults = (List<Map<String, Object>>) input.get("transferResults");
        int total = transferResults.size();
        int approved = 0;
        double totalAmount = 0.0;
        for (var itemResult : transferResults) {
            var execResult = (TransferResult) itemResult.get("executeTransfer");
            if (execResult != null && "COMPLETED".equals(execResult.status())) {
                approved++;
                totalAmount += execResult.amount();
            }
        }
        return new AuditReport(total, approved, total - approved, totalAmount);
    };

    // --- Sub-graph construction ---

    public static Graph buildSubGraph() {
        return Graph.builder("processTransfers__subgraph__")
                .node("riskCheck", RISK_CHECK)
                    .input((results, ctx) -> {
                        var transfer = (Transfer) ctx.get("__item__", Transfer.class);
                        int index = ctx.get("__itemIndex__", Integer.class);
                        return Map.of(
                                "amount", transfer.amount(),
                                "fromAccount", transfer.fromAccount(),
                                "toAccount", transfer.toAccount(),
                                "index", index
                        );
                    })
                .node("executeTransfer", EXECUTE_TRANSFER)
                    .dependsOn("riskCheck")
                    .input((results, ctx) -> {
                        var transfer = (Transfer) ctx.get("__item__", Transfer.class);
                        return Map.of(
                                "transfer", transfer,
                                "riskResult", results.get("riskCheck", RiskResult.class)
                        );
                    })
                .node("recordLedger", RECORD_LEDGER)
                    .dependsOn("executeTransfer")
                    .input((results, ctx) -> {
                        var transfer = (Transfer) ctx.get("__item__", Transfer.class);
                        int index = ctx.get("__itemIndex__", Integer.class);
                        return Map.of(
                                "transfer", transfer,
                                "execution", results.get("executeTransfer", TransferResult.class),
                                "index", index
                        );
                    })
                .build();
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        var listener = new LoggingListener();

        // Build sub-graph and wrap as ForEachOperator (sequential = true)
        Graph subGraph = buildSubGraph();

        // Register sub-graph operators using their operatorRef from the sub-graph
        registry.register(subGraph.nodes().get("riskCheck").operatorRef(), RISK_CHECK);
        registry.register(subGraph.nodes().get("executeTransfer").operatorRef(), EXECUTE_TRANSFER);
        registry.register(subGraph.nodes().get("recordLedger").operatorRef(), RECORD_LEDGER);

        var forEachOp = ForEachOperator.builder(subGraph, registry)
            .sequential(true)
            .listeners(List.of(listener))
            .build();

        // Build main graph
        Graph mainGraph = Graph.builder("sequentialTransfer")
                .node("fetchTransfers", FETCH_TRANSFERS)
                    .input((results, ctx) -> Map.of(
                            "accountId", ctx.get("accountId", String.class)
                    ))
                .node("processTransfers", forEachOp)
                    .dependsOn("fetchTransfers")
                    .input((results, ctx) -> {
                        var fetchResult = results.get("fetchTransfers", List.class);
                        return new ArrayList<>(fetchResult);
                    })
                .node("auditReport", AUDIT_REPORT)
                    .dependsOn("processTransfers")
                    .input((results, ctx) -> {
                        var forEachResults = (List<Map<String, Object>>) results.getRaw("processTransfers");
                        return Map.of("transferResults", forEachResults);
                    })
                .build();

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of("accountId", "ACC-001"));

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "fetchTransfers", FETCH_TRANSFERS,
                "processTransfers", forEachOp,
                "auditReport", AUDIT_REPORT
        ));

        // Print results
        System.out.println("\n═══ Sequential Transfer Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("processTransfers") == NodeStatus.COMPLETED) {
            var forEachResults = (List<Map<String, Object>>) result.results().getRaw("processTransfers");
            System.out.println("Transfer results (" + forEachResults.size() + " items):");
            for (int i = 0; i < forEachResults.size(); i++) {
                System.out.printf("  [%d] %s%n", i, forEachResults.get(i));
            }
        }
        System.out.println();

        if (result.getStatus("auditReport") == NodeStatus.COMPLETED) {
            AuditReport report = result.getOutput("auditReport", AuditReport.class);
            System.out.println("Audit report: " + report);
        }
    }
}
