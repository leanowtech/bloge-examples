package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DSL foreach example for sequential money-transfer processing.
 *
 * <p>This example demonstrates sequential {@code foreach} processing to preserve
 * transfer ordering and consistency across account operations.
 *
 * <p>Graph layout:
 * <pre>
 * fetchTransfers
 *   -> foreach processTransfers (sequential): riskCheck -> executeTransfer -> auditLog
 *   -> buildReport
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class SequentialTransferDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_TRANSFERS = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of("transfers", List.of(
                Map.of("fromAccount", "ACC-001", "toAccount", "ACC-002", "amount", 1500.00, "currency", "USD"),
                Map.of("fromAccount", "ACC-001", "toAccount", "ACC-003", "amount", 250.75, "currency", "USD"),
                Map.of("fromAccount", "ACC-004", "toAccount", "ACC-001", "amount", 3200.00, "currency", "USD")
        ));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> RISK_CHECK = (input, ctx) -> {
        Thread.sleep(20);
        double amount = ((Number) input.get("amount")).doubleValue();
        String riskLevel = amount > 2000 ? "HIGH" : "LOW";
        boolean approved = amount <= 5000;
        return Map.of("approved", approved, "riskLevel", riskLevel,
                "reason", approved ? "Within limits" : "Exceeds maximum");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> EXECUTE_TRANSFER = (input, ctx) -> {
        Thread.sleep(40);
        @SuppressWarnings("unchecked")
        var transfer = (Map<String, Object>) input.get("transfer");
        String transferId = "TXN-" + UUID.randomUUID().toString().substring(0, 8);
        return Map.of("transferId", transferId, "status", "COMPLETED",
                "amount", ((Number) transfer.get("amount")).doubleValue());
    };

    static final Operator<Map<String, Object>, Map<String, Object>> RECORD_LEDGER = (input, ctx) -> {
        Thread.sleep(15);
        @SuppressWarnings("unchecked")
        var transfer = (Map<String, Object>) input.get("transfer");
        @SuppressWarnings("unchecked")
        var execution = (Map<String, Object>) input.get("execution");
        int index = ((Number) input.get("index")).intValue();
        return Map.of(
                "transferId", execution.get("transferId"),
                "fromAccount", transfer.get("fromAccount"),
                "toAccount", transfer.get("toAccount"),
                "amount", transfer.get("amount"),
                "index", index
        );
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> AUDIT_REPORT = (input, ctx) -> {
        Thread.sleep(10);
        var transferResults = (List<Map<String, Object>>) input.get("transferResults");
        int total = transferResults.size();
        int approved = 0;
        double totalAmount = 0.0;
        for (var itemResult : transferResults) {
            var execResult = (Map<String, Object>) itemResult.get("executeTransfer");
            if (execResult != null && "COMPLETED".equals(execResult.get("status"))) {
                approved++;
                totalAmount += ((Number) execResult.get("amount")).doubleValue();
            }
        }
        return Map.of("totalTransfers", total, "approved", approved,
                "rejected", total - approved, "totalAmount", totalAmount);
    };

    public static void main(String[] args) {
        // ── Operator Registrations ─────────────────────────────────────────────
        var registry = new DefaultOperatorRegistry();
        // TransferFetcherOperator: reads ctx.accountId → returns {transfers}
        registry.register("TransferFetcherOperator", FETCH_TRANSFERS);
        // RiskCheckOperator: reads amount, fromAccount, toAccount, index → returns {approved, riskLevel, reason}
        registry.register("RiskCheckOperator", RISK_CHECK);
        // TransferExecutionOperator: reads transfer, riskResult → returns {transferId, status, amount}
        registry.register("TransferExecutionOperator", EXECUTE_TRANSFER);
        // LedgerRecordOperator: reads transfer, execution, index → returns {transferId, fromAccount, toAccount, amount, index}
        registry.register("LedgerRecordOperator", RECORD_LEDGER);
        // AuditReportOperator: reads transferResults (foreach output list) → returns {totalTransfers, approved, rejected, totalAmount}
        registry.register("AuditReportOperator", AUDIT_REPORT);

        String dsl = """
                graph sequentialTransfer {

                  /// Fetches the list of pending bank transfers
                  node fetchTransfers : TransferFetcherOperator {
                    input { accountId = ctx.accountId }
                  }

                  /// foreach sequential mode: process each transfer one-at-a-time to maintain balance consistency
                  /// transfer.amount, transfer.fromAccount, transfer.toAccount — deep field access on current transfer
                  /// idx — used for audit logging
                  foreach processTransfers : (transfer, idx) in fetchTransfers.output.transfers sequential {
                    node riskCheck : RiskCheckOperator {
                      input {
                        amount      = transfer.amount
                        fromAccount = transfer.fromAccount
                        toAccount   = transfer.toAccount
                        index       = idx
                      }
                    }
                    node executeTransfer : TransferExecutionOperator {
                      depends_on = [riskCheck]
                      input {
                        transfer = transfer
                        riskResult = riskCheck.output
                      }
                    }
                    node recordLedger : LedgerRecordOperator {
                      depends_on = [executeTransfer]
                      input {
                        transfer = transfer
                        execution = executeTransfer.output
                        index = idx
                      }
                    }
                  }

                  /// Produces a final audit report of all processed transfers
                  node auditReport : AuditReportOperator {
                    depends_on = [processTransfers]
                    input { transferResults = processTransfers.output }
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        Graph graph = new GraphLoader(registry).load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of("accountId", "ACC-001"));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Sequential Transfer Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("processTransfers") == NodeStatus.COMPLETED) {
            System.out.println("ForEach output: " + result.results().getRaw("processTransfers"));
        }

        if (result.getStatus("auditReport") == NodeStatus.COMPLETED) {
            System.out.println("Audit report: " + result.results().getRaw("auditReport"));
        }
    }
}
