package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.checkpoint.TimerType;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.NodeResults;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.WaitOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates scheduled waits in the Java fluent API using both cron and deadline timers.
 *
 * <p>The example models a report-publishing workflow that pauses twice:
 * <ol>
 *   <li>a cron wait that opens the next business-window publication slot, and</li>
 *   <li>a deadline wait that represents the final archival cutoff.</li>
 * </ol>
 *
 * <p>The cron expression and deadline are intentionally long-lived so the sample can
 * simulate timer firing by saving runtime node output and calling {@code resume()},
 * rather than blocking until the real schedule arrives.</p>
 *
 * <h2>Graph layout</h2>
 * <pre>
 * prepareReport
 *      ↓
 * [SUSPEND waitBusinessWindow]  (cron)
 *      ↓
 * publishReport
 *      ↓
 * [SUSPEND waitArchiveDeadline] (deadline)
 *      ↓
 * archiveReport
 * </pre>
 */
@SuppressWarnings("preview")
public class ScheduledWaitExample {
    private static final String BUSINESS_WINDOW_CRON = "0 9 * * MON-FRI";
    private static final Instant ARCHIVE_DEADLINE = Instant.parse("2099-12-31T23:59:59Z");

    private static final WaitOperator WAIT_FOR_BUSINESS_WINDOW = new WaitOperator(BUSINESS_WINDOW_CRON);
    private static final WaitOperator WAIT_FOR_ARCHIVE_DEADLINE = new WaitOperator(ARCHIVE_DEADLINE);

    public record PrepareReportInput(String reportId, String owner) {}
    public record ReportPlan(String reportId, String owner) {}
    public record PublishInput(String reportId, String owner, String triggerSource, String firedAt) {}
    public record PublishResult(String publicationId, String publishedAt) {}
    public record ArchiveInput(String reportId, String publicationId, String triggerSource, String firedAt) {}
    public record ArchiveResult(String archiveId, String archivedAt) {}

    static final Operator<PrepareReportInput, ReportPlan> PREPARE_REPORT = (input, ctx) -> {
        Thread.sleep(30);
        System.out.printf("  [prepareReport] reportId=%s owner=%s%n", input.reportId(), input.owner());
        return new ReportPlan(input.reportId(), input.owner());
    };

    static final Operator<PublishInput, PublishResult> PUBLISH_REPORT = (input, ctx) -> {
        Thread.sleep(25);
        System.out.printf("  [publishReport] trigger=%s firedAt=%s%n", input.triggerSource(), input.firedAt());
        return new PublishResult("PUB-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString());
    };

    static final Operator<ArchiveInput, ArchiveResult> ARCHIVE_REPORT = (input, ctx) -> {
        Thread.sleep(20);
        System.out.printf("  [archiveReport] trigger=%s publicationId=%s%n", input.triggerSource(), input.publicationId());
        return new ArchiveResult("ARC-" + UUID.randomUUID().toString().substring(0, 8), Instant.now().toString());
    };

    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();

        Graph graph = Graph.builder("scheduledWait")
                .node("prepareReport", PREPARE_REPORT)
                    .input((results, ctx) -> new PrepareReportInput(
                            ctx.get("reportId", String.class),
                            ctx.get("owner", String.class)))
                .suspendNode("waitBusinessWindow", WAIT_FOR_BUSINESS_WINDOW)
                    .dependsOn("prepareReport")
                    .input((results, ctx) -> Map.of("status", "pending-cron"))
                    .meta(ReservedKeys.TIMER_TYPE, TimerType.CRON.name())
                    .meta(ReservedKeys.TIMER_CRON, BUSINESS_WINDOW_CRON)
                .node("publishReport", PUBLISH_REPORT)
                    .dependsOn("waitBusinessWindow")
                    .input((results, ctx) -> {
                        ReportPlan plan = results.get("prepareReport", ReportPlan.class);
                        return new PublishInput(
                                plan.reportId(),
                                plan.owner(),
                                timerField(results, "waitBusinessWindow", "triggeredBy", "cron"),
                                timerField(results, "waitBusinessWindow", "firedAt", "N/A")
                        );
                    })
                .suspendNode("waitArchiveDeadline", WAIT_FOR_ARCHIVE_DEADLINE)
                    .dependsOn("publishReport")
                    .input((results, ctx) -> Map.of("status", "pending-deadline"))
                    .meta(ReservedKeys.TIMER_TYPE, TimerType.DEADLINE.name())
                    .meta(ReservedKeys.TIMER_DEADLINE, ARCHIVE_DEADLINE.toString())
                .node("archiveReport", ARCHIVE_REPORT)
                    .dependsOn("waitArchiveDeadline")
                    .input((results, ctx) -> {
                        ReportPlan plan = results.get("prepareReport", ReportPlan.class);
                        PublishResult publication = results.get("publishReport", PublishResult.class);
                        return new ArchiveInput(
                                plan.reportId(),
                                publication.publicationId(),
                                timerField(results, "waitArchiveDeadline", "triggeredBy", "deadline"),
                                timerField(results, "waitArchiveDeadline", "firedAt", "N/A")
                        );
                    })
                .build();

        var ctx = new GraphContext(Map.of(
                "reportId", "RPT-2026-WEEKLY-OPS",
                "owner", "ops@example.com"
        ));

        System.out.println("\n═══ Phase 1: Execute until cron wait suspends ═══");
        GraphResult phase1 = engine.executeWithOperators(graph, ctx, Map.of(
                "prepareReport", PREPARE_REPORT,
                "waitBusinessWindow", WAIT_FOR_BUSINESS_WINDOW,
                "publishReport", PUBLISH_REPORT,
                "waitArchiveDeadline", WAIT_FOR_ARCHIVE_DEADLINE,
                "archiveReport", ARCHIVE_REPORT
        ));
        printResult(phase1);

        String executionId = phase1.executionId();

        System.out.println("\n═══ Phase 2: Simulate cron business window ═══");
        runtime.saveNodeOutput(
                executionId,
                "scheduledWait",
                "waitBusinessWindow",
                LongRunningRuntimeExampleSupport.payload(
                        "triggeredBy", "cron",
                        "firedAt", Instant.now().toString(),
                        "cron", BUSINESS_WINDOW_CRON
                )
        );
        registry.register("prepareReport", PREPARE_REPORT);
        registry.registerRaw("waitBusinessWindow", WAIT_FOR_BUSINESS_WINDOW);
        registry.register("publishReport", PUBLISH_REPORT);
        registry.registerRaw("waitArchiveDeadline", WAIT_FOR_ARCHIVE_DEADLINE);
        registry.register("archiveReport", ARCHIVE_REPORT);

        GraphResult phase2 = engine.resume(graph, executionId, ctx);
        printResult(phase2);

        System.out.println("\n═══ Phase 3: Simulate archival deadline ═══");
        runtime.saveNodeOutput(
                executionId,
                "scheduledWait",
                "waitArchiveDeadline",
                LongRunningRuntimeExampleSupport.payload(
                        "triggeredBy", "deadline",
                        "firedAt", Instant.now().toString(),
                        "deadline", ARCHIVE_DEADLINE.toString()
                )
        );

        GraphResult phase3 = engine.resume(graph, executionId, ctx);
        printResult(phase3);

        if (phase3.getStatus("archiveReport") == NodeStatus.COMPLETED) {
            ArchiveResult archive = phase3.getOutput("archiveReport", ArchiveResult.class);
            System.out.println("Archive completed: " + archive);
        }
    }

    private static void printResult(GraphResult result) {
        System.out.printf("Suspended: %s  executionId: %s%n", result.isSuspended(), result.executionId());
        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-22s → %s%n", entry.getKey(), entry.getValue());
        }
    }

    private static String timerField(NodeResults results, String nodeId, String key, String fallback) {
        Object raw = results.getRaw(nodeId);
        if (raw instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof String stringValue) {
                return stringValue;
            }
        }
        return fallback;
    }
}
