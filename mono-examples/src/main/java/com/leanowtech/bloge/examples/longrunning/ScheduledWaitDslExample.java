package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DSL version of the scheduled-wait example.
 *
 * <p>Loads {@code /bloge/scheduled-wait.bloge}, executes until the cron wait suspends,
 * simulates the cron fire, resumes into the deadline wait, then simulates the deadline
 * fire and resumes to completion.</p>
 */
@SuppressWarnings("preview")
public class ScheduledWaitDslExample {
    static final Operator<Map<String, Object>, Map<String, Object>> PREPARE_REPORT = (input, ctx) -> {
        Thread.sleep(30);
        System.out.printf("  [prepareReport] reportId=%s owner=%s%n", input.get("reportId"), input.get("owner"));
        return Map.of("reportId", input.get("reportId"), "owner", input.get("owner"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> PUBLISH_REPORT = (input, ctx) -> {
        Thread.sleep(25);
        System.out.printf("  [publishReport] trigger=%s firedAt=%s%n", input.get("triggerSource"), input.get("firedAt"));
        return Map.of(
                "publicationId", "PUB-DSL-" + UUID.randomUUID().toString().substring(0, 8),
                "publishedAt", Instant.now().toString()
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ARCHIVE_REPORT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.printf("  [archiveReport] trigger=%s publicationId=%s%n", input.get("triggerSource"), input.get("publicationId"));
        return Map.of(
                "archiveId", "ARC-DSL-" + UUID.randomUUID().toString().substring(0, 8),
                "archivedAt", Instant.now().toString()
        );
    };

    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        registry.register("PrepareReportOperator", PREPARE_REPORT);
        registry.register("PublishReportOperator", PUBLISH_REPORT);
        registry.register("ArchiveReportOperator", ARCHIVE_REPORT);

        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();
        var loader = new GraphLoader(registry).withSourceLocation("classpath:/bloge/scheduled-wait.bloge");

        Graph graph = loader.load(readDslResource("/bloge/scheduled-wait.bloge"));

        var ctx = new GraphContext(Map.of(
                "reportId", "RPT-DSL-2026-WEEKLY-OPS",
                "owner", "ops@example.com"
        ));

        System.out.println("\n═══ Phase 1 (DSL): Execute until cron wait suspends ═══");
        GraphResult phase1 = engine.execute(graph, ctx);
        printResult(phase1);

        String executionId = phase1.executionId();

        System.out.println("\n═══ Phase 2 (DSL): Simulate cron business window ═══");
        runtime.saveNodeOutput(
                executionId,
                "scheduledWait",
                "waitBusinessWindow",
                LongRunningRuntimeExampleSupport.payload(
                        "triggeredBy", "cron",
                        "firedAt", Instant.now().toString(),
                        "cron", "0 9 * * MON-FRI"
                )
        );
        GraphResult phase2 = engine.resume(graph, executionId, ctx);
        printResult(phase2);

        System.out.println("\n═══ Phase 3 (DSL): Simulate archival deadline ═══");
        runtime.saveNodeOutput(
                executionId,
                "scheduledWait",
                "waitArchiveDeadline",
                LongRunningRuntimeExampleSupport.payload(
                        "triggeredBy", "deadline",
                        "firedAt", Instant.now().toString(),
                        "deadline", "2099-12-31T23:59:59Z"
                )
        );
        GraphResult phase3 = engine.resume(graph, executionId, ctx);
        printResult(phase3);

        if (phase3.getStatus("archiveReport") == NodeStatus.COMPLETED) {
            System.out.println("Archive result: " + phase3.results().getRaw("archiveReport"));
        }
    }

    private static String readDslResource(String resourcePath) throws IOException {
        try (InputStream inputStream = ScheduledWaitDslExample.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing DSL resource: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void printResult(GraphResult result) {
        System.out.printf("Suspended: %s  executionId: %s%n", result.isSuspended(), result.executionId());
        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-22s → %s%n", entry.getKey(), entry.getValue());
        }
    }

}
