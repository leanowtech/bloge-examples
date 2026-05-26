package com.leanowtech.bloge.examples.durable;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.NodeResults;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.RemoteWorkerEnvelope;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.runtime.work.WorkItem;
import com.leanowtech.bloge.core.runtime.work.WorkItemNotifier;
import com.leanowtech.bloge.core.runtime.work.WorkItemQuery;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStore;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.dsl.compiler.RemoteWorkerOperatorFactories;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demonstrates DSL remote-worker execution dispatch.
 *
 * <p>The graph runs a local preparation node and then reaches a remote PDF rendering node declared
 * with {@code execution_mode = remote}. The remote node is compiled into a durable work-item bridge:
 * execution suspends locally while a JSON-friendly {@link RemoteWorkerEnvelope} is stored for an
 * external worker to poll and complete.</p>
 */
@SuppressWarnings({"preview", "unchecked"})
public final class RemoteWorkerExecutionDslExample {

    private static final String DSL_RESOURCE = "/bloge/remote/remote-report-rendering.bloge";
    public static final String NODE_PREPARE_REPORT = "prepareReport";
    public static final String NODE_RENDER_PDF = "renderPdf";
    public static final String WORKER_TOPIC = "workers.reporting.pdf";

    private RemoteWorkerExecutionDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> PREPARE_REPORT = (input, ctx) -> Map.of(
            "reportId", input.get("reportId"),
            "customerId", input.get("customerId"),
            "pageCount", 12,
            "format", "PDF"
    );

    /**
     * Result bundle returned after the graph reaches the remote worker boundary.
     *
     * @param graph compiled graph
         * @param suspendResult remote operator suspend result
     * @param workItemStore in-memory durable work-item store
     * @param notifier recording notifier invoked when the remote item is ready
     */
    public record RemoteDispatch(
            Graph graph,
            OperatorResult.Suspended<?> suspendResult,
            RecordingWorkItemStore workItemStore,
            RecordingWorkItemNotifier notifier
    ) {}

    /**
     * Worker-side projection of a claimed remote job.
     *
     * @param workItem claimed work item
     * @param envelope decoded worker envelope
     */
    public record ClaimedRemoteJob(WorkItem workItem, RemoteWorkerEnvelope envelope) {}

    /**
     * Compiles the remote-worker DSL graph with an in-memory durable dispatch bridge.
     *
     * @param registry operator registry containing only local operators
     * @param workItemStore store that receives remote work items
     * @param notifier notifier invoked after a remote item is created
     * @return compiled graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry,
                                   RecordingWorkItemStore workItemStore,
                                   RecordingWorkItemNotifier notifier) {
        registry.register("ReportPreparationOperator", PREPARE_REPORT);
        GraphLoader loader = new GraphLoader(registry);
        loader.compiler().withRemoteWorkerOperatorFactory(RemoteWorkerOperatorFactories.durable(
                workItemStore,
                notifier,
                JsonCodec.DEFAULT
        ));
        return loader.load(ExampleDslResources.readResource(DSL_RESOURCE));
    }

    /**
         * Executes the compiled local preparation node and remote dispatch boundary.
         *
         * <p>This intentionally drives the compiled remote embedded operator directly instead of
         * waiting on a full graph execution, so examples and tests can inspect the durable work item
         * immediately without sleeping until the remote suspend deadline.</p>
     *
     * @param reportId report identifier
     * @param customerId customer identifier
     * @return dispatch bundle containing the suspended result and work-item store
     */
    public static RemoteDispatch executeUntilDispatch(String reportId, String customerId) {
        var registry = new DefaultOperatorRegistry();
        var store = new RecordingWorkItemStore();
        var notifier = new RecordingWorkItemNotifier();
        Graph graph = buildGraph(registry, store, notifier);
        GraphContext graphContext = new GraphContext(Map.of(
                "reportId", reportId,
                "customerId", customerId,
                ReservedKeys.TENANT_ID, "tenant-demo",
                ReservedKeys.NAMESPACE, "examples",
                ReservedKeys.BUSINESS_KEY, reportId,
                ReservedKeys.GRAPH_VERSION, "1.0.0",
                ReservedKeys.SOURCE_REQUEST_ID, "req-" + reportId,
                "graphEngine.definitionKey", "remote-report-rendering",
                "graphEngine.versionId", "v1"
            ));
            String executionId = "exec-" + reportId;
            graphContext.put(ReservedKeys.EXECUTION_ID, executionId);

            try {
                var nodeResults = new NodeResults();
                Object prepareInput = graph.nodes().get(NODE_PREPARE_REPORT)
                    .inputAssembler()
                    .assemble(nodeResults, graphContext);
                Object prepareOutput = PREPARE_REPORT.execute((Map<String, Object>) prepareInput,
                    new OperatorContext(NODE_PREPARE_REPORT, graph.name(), graphContext, 0, executionId));
                nodeResults.put(NODE_PREPARE_REPORT, prepareOutput);

                Object remoteInput = graph.nodes().get(NODE_RENDER_PDF)
                    .inputAssembler()
                    .assemble(nodeResults, graphContext);
                SuspendableOperator<Object, Object> remoteOperator = (SuspendableOperator<Object, Object>) graph
                    .embeddedOperators()
                    .get(NODE_RENDER_PDF);
                OperatorResult<Object> operatorResult = remoteOperator.execute(remoteInput,
                    new OperatorContext(NODE_RENDER_PDF, graph.name(), graphContext, 0, executionId));
                OperatorResult.Suspended<?> suspended = (OperatorResult.Suspended<?>) operatorResult;
                return new RemoteDispatch(graph, suspended, store, notifier);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to dispatch remote worker node", exception);
            }
    }

    /**
     * Claims the first ready remote job for the example worker topic.
     *
     * @param dispatch dispatch bundle returned by {@link #executeUntilDispatch(String, String)}
     * @param workerId worker identity claiming the job
     * @return claimed job and decoded worker envelope
     */
    public static ClaimedRemoteJob pollFirstJob(RemoteDispatch dispatch, String workerId) {
        WorkItem ready = dispatch.workItemStore()
                .pollReady(WorkItemType.EXECUTE_NODE, WORKER_TOPIC, 1)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No remote work item is ready"));
        WorkItem claimed = dispatch.workItemStore()
                .claim(ready.itemId(), workerId, Duration.ofMinutes(5), ready.version())
                .orElseThrow(() -> new IllegalStateException("Remote work item could not be claimed"));
        return new ClaimedRemoteJob(claimed, decodeEnvelope(claimed));
    }

    /**
     * Produces the JSON-friendly output a PDF worker would send back to the control plane.
     *
     * @param envelope decoded worker envelope
     * @return worker output payload
     */
    public static Map<String, Object> renderPdf(RemoteWorkerEnvelope envelope) {
        Map<String, Object> input = (Map<String, Object>) envelope.input();
        return Map.of(
                "documentUrl", "s3://reports/%s.pdf".formatted(input.get("reportId")),
                "checksum", "sha256:%s:%s".formatted(input.get("reportId"), input.get("pageCount")),
                "pageCount", input.get("pageCount")
        );
    }

    /**
     * Decodes the remote worker envelope stored in a durable work item.
     *
     * @param workItem remote work item
     * @return typed remote worker envelope
     */
    public static RemoteWorkerEnvelope decodeEnvelope(WorkItem workItem) {
        return RemoteWorkerEnvelope.fromValue(JsonCodec.DEFAULT.deserialize(workItem.payload()));
    }

    /**
     * Minimal in-memory store used by the example and tests.
     */
    public static final class RecordingWorkItemStore implements WorkItemStore {
        private final Map<String, WorkItem> items = new ConcurrentHashMap<>();

        @Override
        public void create(WorkItem workItem) {
            items.put(workItem.itemId(), workItem);
        }

        @Override
        public void createBatch(List<WorkItem> workItems) {
            workItems.forEach(this::create);
        }

        @Override
        public Optional<WorkItem> get(String itemId) {
            return Optional.ofNullable(items.get(itemId));
        }

        @Override
        public List<WorkItem> pollReady(WorkItemType workItemType, String shardId, int limit) {
            return items.values().stream()
                    .filter(item -> item.itemType() == workItemType)
                    .filter(item -> item.status() == WorkItemStatus.READY)
                    .filter(item -> shardId == null || shardId.equals(item.identity().shardId()))
                    .sorted(Comparator.comparing(WorkItem::createdAt))
                    .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                    .toList();
        }

        @Override
        public Optional<WorkItem> claim(String itemId, String owner, Duration leaseDuration, long expectedVersion) {
            WorkItem current = items.get(itemId);
            if (current == null || current.status() != WorkItemStatus.READY || current.version() != expectedVersion) {
                return Optional.empty();
            }
            WorkItem claimed = current.toBuilder()
                    .status(WorkItemStatus.CLAIMED)
                    .claimOwner(owner)
                    .claimToken("lease-" + UUID.randomUUID())
                    .claimUntil(Instant.now().plus(leaseDuration))
                    .version(current.version() + 1)
                    .updatedAt(Instant.now())
                    .build();
            items.put(itemId, claimed);
            return Optional.of(claimed);
        }

        @Override
        public Optional<WorkItem> renewClaim(String itemId, String leaseToken, Duration extension) {
            WorkItem current = items.get(itemId);
            if (current == null || current.status() != WorkItemStatus.CLAIMED
                    || !leaseToken.equals(current.claimToken())) {
                return Optional.empty();
            }
            WorkItem renewed = current.toBuilder()
                    .claimUntil(Instant.now().plus(extension))
                    .version(current.version() + 1)
                    .updatedAt(Instant.now())
                    .build();
            items.put(itemId, renewed);
            return Optional.of(renewed);
        }

        @Override
        public void markDone(String itemId, String leaseToken, long expectedVersion) {
            transitionClaimed(itemId, leaseToken, expectedVersion, WorkItemStatus.DONE, null);
        }

        @Override
        public void markRetryWait(String itemId, String leaseToken, Instant nextAttemptAt, long expectedVersion) {
            transitionClaimed(itemId, leaseToken, expectedVersion, WorkItemStatus.RETRY_WAIT, nextAttemptAt);
        }

        @Override
        public void markFailed(String itemId, String leaseToken, long expectedVersion) {
            transitionClaimed(itemId, leaseToken, expectedVersion, WorkItemStatus.FAILED, null);
        }

        @Override
        public void markDeadLetter(String itemId, String reason) {
            WorkItem current = items.get(itemId);
            if (current == null) {
                throw new IllegalArgumentException("Unknown work item: " + itemId);
            }
            items.put(itemId, current.toBuilder()
                    .status(WorkItemStatus.DEAD_LETTER)
                    .lastError(reason)
                    .version(current.version() + 1)
                    .updatedAt(Instant.now())
                    .build());
        }

        @Override
        public List<WorkItem> findExpiredClaims(Instant cutoff, int limit) {
            return items.values().stream()
                    .filter(item -> item.status() == WorkItemStatus.CLAIMED)
                    .filter(item -> item.claimUntil() != null && item.claimUntil().isBefore(cutoff))
                    .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                    .toList();
        }

        @Override
        public List<WorkItem> query(WorkItemQuery query) {
            return List.copyOf(items.values());
        }

        /**
         * Returns created items in deterministic order.
         *
         * @return stored work items
         */
        public List<WorkItem> createdItems() {
            return items.values().stream()
                    .sorted(Comparator.comparing(WorkItem::createdAt))
                    .toList();
        }

        private void transitionClaimed(String itemId,
                                       String leaseToken,
                                       long expectedVersion,
                                       WorkItemStatus status,
                                       Instant nextAttemptAt) {
            WorkItem current = items.get(itemId);
            if (current == null || current.version() != expectedVersion || !leaseToken.equals(current.claimToken())) {
                throw new IllegalStateException("Work item lease is not current: " + itemId);
            }
            WorkItem updated = current.toBuilder()
                    .status(status)
                    .nextAttemptAt(nextAttemptAt)
                    .version(current.version() + 1)
                    .updatedAt(Instant.now())
                    .completedAt(status == WorkItemStatus.DONE ? Instant.now() : null)
                    .build();
            items.put(itemId, updated);
        }
    }

    /**
     * Records ready notifications so examples can assert that dispatch hooks fired.
     */
    public static final class RecordingWorkItemNotifier implements WorkItemNotifier {
        private final List<String> itemIds = new ArrayList<>();

        @Override
        public void onWorkItemReady(WorkItem workItem) {
            itemIds.add(workItem.itemId());
        }

        /**
         * Returns the item IDs observed by the notifier.
         *
         * @return notified item IDs
         */
        public List<String> itemIds() {
            return List.copyOf(itemIds);
        }
    }
}