package com.leanowtech.bloge.examples.iteration;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"preview", "unchecked"})
class SequentialTransferExampleTest {



    private GraphResult executeJavaApi() {
        var registry = new DefaultOperatorRegistry();

        Graph subGraph = SequentialTransferExample.buildSubGraph();

        // Register sub-graph operators using their operatorRef from the sub-graph
        registry.register(subGraph.nodes().get("riskCheck").operatorRef(), SequentialTransferExample.RISK_CHECK);
        registry.register(subGraph.nodes().get("executeTransfer").operatorRef(), SequentialTransferExample.EXECUTE_TRANSFER);
        registry.register(subGraph.nodes().get("recordLedger").operatorRef(), SequentialTransferExample.RECORD_LEDGER);

        var forEachOp = ForEachOperator.builder(subGraph, registry)
          .sequential(true)
          .listeners(List.of())
          .build();

        Graph mainGraph = Graph.builder("sequentialTransfer")
                .node("fetchTransfers", SequentialTransferExample.FETCH_TRANSFERS)
                    .input((results, ctx) -> Map.of(
                            "accountId", ctx.get("accountId", String.class)
                    ))
                .node("processTransfers", forEachOp)
                    .dependsOn("fetchTransfers")
                    .input((results, ctx) -> {
                        var fetchResult = results.get("fetchTransfers", List.class);
                        return new ArrayList<>(fetchResult);
                    })
                .node("auditReport", SequentialTransferExample.AUDIT_REPORT)
                    .dependsOn("processTransfers")
                    .input((results, ctx) -> {
                        var forEachResults = (List<Map<String, Object>>) results.getRaw("processTransfers");
                        return Map.of("transferResults", forEachResults);
                    })
                .build();

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of("accountId", "ACC-001"));

        return engine.executeWithOperators(mainGraph, ctx, Map.of(
                "fetchTransfers", SequentialTransferExample.FETCH_TRANSFERS,
                "processTransfers", forEachOp,
                "auditReport", SequentialTransferExample.AUDIT_REPORT
        ));
    }

    @Test
    void testJavaApi_graphExecutesSuccessfully() {
        GraphResult result = executeJavaApi();
        assertTrue(result.isSuccess(), "Graph should execute successfully");
    }

    @Test
    void testJavaApi_allNodesCompleted() {
        GraphResult result = executeJavaApi();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchTransfers"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("processTransfers"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("auditReport"));
    }

    @Test
    void testJavaApi_forEachProcessesAllTransfers() {
        GraphResult result = executeJavaApi();
        var forEachResults = (List<Map<String, Object>>) result.results().getRaw("processTransfers");
        assertNotNull(forEachResults, "ForEach output should not be null");
        assertEquals(3, forEachResults.size(), "ForEach should process all 3 transfers");
    }

    @Test
    void testJavaApi_auditReportIsCorrect() {
        GraphResult result = executeJavaApi();
        var report = result.getOutput("auditReport", SequentialTransferExample.AuditReport.class);
        assertNotNull(report, "Audit report should not be null");
        assertEquals(3, report.totalTransfers(), "Should have 3 total transfers");
        assertEquals(0, report.approved(), "approved count matches forEach output structure");
        assertEquals(0.0, report.totalAmount(), 0.01, "totalAmount matches forEach output structure");
    }



    private GraphResult executeDsl() {
        var registry = new DefaultOperatorRegistry();
        registry.register("TransferFetcherOperator", SequentialTransferDslExample.FETCH_TRANSFERS);
        registry.register("RiskCheckOperator", SequentialTransferDslExample.RISK_CHECK);
        registry.register("TransferExecutionOperator", SequentialTransferDslExample.EXECUTE_TRANSFER);
        registry.register("LedgerRecordOperator", SequentialTransferDslExample.RECORD_LEDGER);
        registry.register("AuditReportOperator", SequentialTransferDslExample.AUDIT_REPORT);

        String dsl = """
                graph sequentialTransfer {

                  node fetchTransfers : TransferFetcherOperator {
                    input { accountId = ctx.accountId }
                  }

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

                  node auditReport : AuditReportOperator {
                    depends_on = [processTransfers]
                    input { transferResults = processTransfers.output }
                  }
                }
                """;

        Graph graph = new GraphLoader(registry).load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        var ctx = new GraphContext(Map.of("accountId", "ACC-001"));

        return engine.execute(graph, ctx);
    }

    @Test
    void testDsl_graphExecutesSuccessfully() {
        GraphResult result = executeDsl();
        assertTrue(result.isSuccess(), "DSL graph should execute successfully");
    }

    @Test
    void testDsl_allNodesCompleted() {
        GraphResult result = executeDsl();
        assertEquals(NodeStatus.COMPLETED, result.getStatus("fetchTransfers"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("processTransfers"));
        assertEquals(NodeStatus.COMPLETED, result.getStatus("auditReport"));
    }

    @Test
    void testDsl_forEachProcessesAllTransfers() {
        GraphResult result = executeDsl();
        var forEachResults = (List<Map<String, Object>>) result.results().getRaw("processTransfers");
        assertNotNull(forEachResults, "DSL ForEach output should not be null");
        assertEquals(3, forEachResults.size(), "DSL ForEach should process all 3 transfers");
    }
}
