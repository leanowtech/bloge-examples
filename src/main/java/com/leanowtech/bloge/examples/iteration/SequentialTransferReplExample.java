package com.leanowtech.bloge.examples.iteration;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class SequentialTransferReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("TransferFetcherOperator", SequentialTransferDslExample.FETCH_TRANSFERS);
        registry.register("RiskCheckOperator", SequentialTransferDslExample.RISK_CHECK);
        registry.register("TransferExecutionOperator", SequentialTransferDslExample.EXECUTE_TRANSFER);
        registry.register("LedgerRecordOperator", SequentialTransferDslExample.RECORD_LEDGER);
        registry.register("AuditReportOperator", SequentialTransferDslExample.AUDIT_REPORT);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String accountId = ReplHelper.promptString(scanner, "accountId", "ACC-001");
        return Map.of("accountId", accountId);
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
                ReplHelper.header("Sequential Transfer REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
