package com.leanowtech.bloge.examples.antipatterns;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;
import com.leanowtech.bloge.lint.LintDiagnostic;
import com.leanowtech.bloge.lint.LintRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Compares an unbounded blocking call with a timeout-guarded alternative.
 */
public final class MissingTimeoutExample {

    public record ReportRequest(String reportId) {
    }

    public record ExternalReport(String payload, boolean timedOut) {
    }

    public record ReportSummary(String reportId, String payload, boolean timedOut) {
    }

    static final Operator<ReportRequest, ExternalReport> LOAD_REPORT = (input, ctx) -> {
        Thread.sleep(250);
        return new ExternalReport("Fetched analytics payload for " + input.reportId(), false);
    };

    static final Operator<ExternalReport, ReportSummary> PUBLISH_REPORT = (input, ctx) ->
            new ReportSummary(ctx.graphContext().get("reportId", String.class), input.payload(), input.timedOut());

    private MissingTimeoutExample() {
    }

    /**
     * Bad graph with no timeout on the slow external call.
     */
    public static Graph buildWithoutTimeout() {
        return Graph.builder("missingTimeoutBad")
                .node("loadReport", LOAD_REPORT)
                    .input((results, ctx) -> new ReportRequest(ctx.get("reportId", String.class)))
                .node("publishReport", PUBLISH_REPORT)
                    .dependsOn("loadReport")
                    .input((results, ctx) -> results.get("loadReport", ExternalReport.class))
                    .timeout(Duration.ofSeconds(1))
                .build();
    }

    /**
     * Corrected graph that fails fast and substitutes a manual-review payload.
     */
    public static Graph buildWithTimeout() {
        return Graph.builder("missingTimeoutGood")
                .node("loadReport", LOAD_REPORT)
                    .input((results, ctx) -> new ReportRequest(ctx.get("reportId", String.class)))
                    .timeout(Duration.ofMillis(50))
                    .fallback(() -> new ExternalReport("Deferred to manual review after timeout", true))
                .node("publishReport", PUBLISH_REPORT)
                    .dependsOn("loadReport")
                    .input((results, ctx) -> results.get("loadReport", ExternalReport.class))
                    .timeout(Duration.ofSeconds(1))
                .build();
    }

    public static GraphResult executeWithoutTimeout(String reportId) {
        return execute(buildWithoutTimeout(), reportId);
    }

    public static GraphResult executeWithTimeout(String reportId) {
        return execute(buildWithTimeout(), reportId);
    }

    /**
     * Lints the DSL companion that intentionally omits a timeout.
     */
    public static List<LintDiagnostic> lintDslExample() {
        return new LintRunner().lintSource(ExampleDslResources.readResource("/bloge/antipatterns/missing-timeout.bloge"));
    }

    private static GraphResult execute(Graph graph, String reportId) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(
                graph,
                new GraphContext(Map.of("reportId", reportId)),
                Map.of(
                        "loadReport", LOAD_REPORT,
                        "publishReport", PUBLISH_REPORT
                )
        );
    }

    public static void main(String[] args) {
        GraphResult slow = executeWithoutTimeout("REPORT-1");
        GraphResult guarded = executeWithTimeout("REPORT-1");
        System.out.println("Slow graph elapsed: " + slow.elapsed().toMillis() + "ms");
        System.out.println("Guarded graph elapsed: " + guarded.elapsed().toMillis() + "ms");
    }
}
