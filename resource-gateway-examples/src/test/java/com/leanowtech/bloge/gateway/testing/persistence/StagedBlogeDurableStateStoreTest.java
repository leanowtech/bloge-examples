package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.context.TenantContextHolder;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.ScopedValue;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.exception.OptimisticLockException;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import com.leanowtech.bloge.core.runtime.wait.ExecutionWait;
import com.leanowtech.bloge.core.runtime.wait.WaitStatus;
import com.leanowtech.bloge.core.runtime.wait.WaitType;
import com.leanowtech.bloge.core.runtime.work.DeadLetterPolicy;
import com.leanowtech.bloge.core.runtime.work.WorkItem;
import com.leanowtech.bloge.core.runtime.work.WorkItemQuery;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.IndependentDurableTestEngineFactory;
import com.leanowtech.bloge.gateway.testing.runtime.InvocationRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagedBlogeDurableStateStoreTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;
    private DurableTestExecutionCheckpointIntegrity integrity;
    private DatabaseDurableTestExecutionCheckpointRepository repository;
    private StagedBlogeDurableStateStore stateStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:staged-bloge-state-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "", 6));
        integrity = new DurableTestExecutionCheckpointIntegrity(objectMapper);
        repository = new DatabaseDurableTestExecutionCheckpointRepository(
                database.jdbc(), database.transactionManager(), objectMapper, integrity);
        repository.init();
        stateStore = new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        stateStore.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void commitsExecutionLifecycleAndNodeCheckpointAsOneColdReadableClosure() {
        IndependentDurableTestEngineFactory factory = factory(stateStore);

        try (IndependentDurableTestEngineFactory.RunSession session = factory.openSession(
                "engine-a", new InvocationRecorder(objectMapper), executionOptions())) {
            var result = session.execute(graph(), new GraphContext());

            assertThat(result.isSuccess()).isTrue();
            assertThat(stateStore.executionStore().get("engine-a"))
                    .get().extracting(instance -> instance.status())
                    .isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(stateStore.checkpointStore().load(
                    "engine-a", CheckpointType.NODE_OUTPUT, "only")).isPresent();

            var mutation = session.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        StagedBlogeDurableStateStore coldStore =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        coldStore.init();
        assertThat(coldStore.executionStore().get("engine-a"))
                .get().extracting(instance -> instance.status())
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(coldStore.checkpointStore().load(
                "engine-a", CheckpointType.NODE_OUTPUT, "only")).isPresent();
    }

    @Test
    void commitsWaitAsPartOfTheColdReadableEngineClosure() {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.waitStore().create(wait(
                    "wait-approval", WaitType.WAIT_SIGNAL, "approval-key"));
            assertThat(stateStore.waitStore().findByExecution("engine-a"))
                    .singleElement()
                    .satisfies(executionWait -> {
                        assertThat(executionWait.waitType()).isEqualTo(WaitType.WAIT_SIGNAL);
                        assertThat(executionWait.waitKey()).isEqualTo("approval-key");
                        assertThat(executionWait.status()).isEqualTo(WaitStatus.WAITING);
                    });
            assertThat(stateStore.waitStore().findByType(
                    WaitType.WAIT_SIGNAL, WaitStatus.WAITING, 10)).isEmpty();

            var mutation = stage.prepare(
                    "checkpoint-suspend", "approval", "SUSPEND", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        StagedBlogeDurableStateStore coldStore =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        coldStore.init();
        assertThat(coldStore.waitStore().findByExecution("engine-a"))
                .singleElement()
                .satisfies(executionWait -> {
                    assertThat(executionWait.waitType()).isEqualTo(WaitType.WAIT_SIGNAL);
                    assertThat(executionWait.waitKey()).isEqualTo("approval-key");
                    assertThat(executionWait.status()).isEqualTo(WaitStatus.WAITING);
                });
        assertThat(coldStore.waitStore().findByTypeAndKey(
                WaitType.WAIT_SIGNAL, "approval-key", 10))
                .extracting(ExecutionWait::waitId)
                .containsExactly("wait-approval");
        assertThat(coldStore.waitStore().findByTypeAndKey(
                WaitType.WAIT_TIMER, "approval-key", 10)).isEmpty();
    }

    @Test
    void commitsWorkItemAsPartOfTheColdPollableEngineClosure() {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.workItemStore().create(workItem(
                    "item-timer", WorkItemType.TIMER_DUE));

            assertThat(stateStore.workItemStore().get("item-timer"))
                    .get().extracting(WorkItem::status)
                    .isEqualTo(WorkItemStatus.READY);
            assertThat(stateStore.workItemStore().pollReady(
                    WorkItemType.TIMER_DUE, null, 10)).isEmpty();

            var mutation = stage.prepare(
                    "checkpoint-work", "approval", "WORK_ITEM", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        StagedBlogeDurableStateStore coldStore =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        coldStore.init();
        assertThat(coldStore.workItemStore().get("item-timer"))
                .get().satisfies(item -> {
                    assertThat(item.identity()).isEqualTo(executionIdentity());
                    assertThat(item.itemType()).isEqualTo(WorkItemType.TIMER_DUE);
                    assertThat(item.status()).isEqualTo(WorkItemStatus.READY);
                });
        assertThat(coldStore.workItemStore().pollReady(
                WorkItemType.TIMER_DUE, null, 10))
                .extracting(WorkItem::itemId)
                .containsExactly("item-timer");
    }

    @Test
    void rejectsNonAtomicWorkItemBatchesAndIdentityDrift() {
        assertThatThrownBy(() -> stateStore.workItemStore().create(
                workItem("item-outside", WorkItemType.TIMER_DUE)))
                .isInstanceOf(DurabilityException.class)
                .hasMessageContaining("active composite stage");

        try (StagedBlogeDurableStateStore.Stage ignored = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());

            WorkItem duplicate = workItem("item-duplicate", WorkItemType.TIMER_DUE);
            assertThatThrownBy(() -> stateStore.workItemStore().createBatch(
                    List.of(duplicate, duplicate)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(stateStore.workItemStore().get("item-duplicate")).isEmpty();

            ExecutionIdentity otherExecution = new ExecutionIdentity(
                    "tenant-a", "test-runtime", null, "engine-b",
                    ExecutionType.GRAPH,
                    "controlled-durable-state", "1", SHA_A,
                    null, null, null, "run-b");
            assertThatThrownBy(() -> stateStore.workItemStore().createBatch(List.of(
                    workItem("item-local", WorkItemType.TIMER_DUE),
                    workItem("item-foreign", WorkItemType.TIMER_DUE, otherExecution))))
                    .isInstanceOf(DurabilityException.class)
                    .hasMessageContaining("cannot span multiple executions");
            assertThat(stateStore.workItemStore().get("item-local")).isEmpty();

            ExecutionIdentity drifted = new ExecutionIdentity(
                    "tenant-b", "test-runtime", null, "engine-a",
                    ExecutionType.GRAPH,
                    "controlled-durable-state", "1", SHA_A,
                    null, null, null, "run-a");
            assertThatThrownBy(() -> stateStore.workItemStore().create(
                    workItem("item-drifted", WorkItemType.TIMER_DUE, drifted)))
                    .isInstanceOf(DurabilityException.class)
                    .hasMessageContaining("identity does not match");

            ExecutionIdentity workerRouted = executionIdentity().withRouting(
                    executionIdentity().routeKey(), "payments-workers");
            stateStore.workItemStore().create(
                    workItem("item-worker-routed", WorkItemType.EXECUTE_NODE, workerRouted));
            assertThat(stateStore.workItemStore().get("item-worker-routed"))
                    .get().extracting(item -> item.identity().shardId())
                    .isEqualTo("payments-workers");
        }
    }

    @Test
    void acceptsEngineScopedAsyncCreatesButRejectsUnscopedThreadsAndHidesTheOverlay()
            throws Exception {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Throwable unscopedFailure = executor.submit(() -> {
                    try {
                        stateStore.workItemStore().create(
                                workItem("item-unscoped", WorkItemType.EXECUTE_NODE));
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                }).get();
                assertThat(unscopedFailure)
                        .isInstanceOf(DurabilityException.class)
                        .hasMessageContaining("engine execution scope");
                boolean asyncReaderSawSpeculativeItem = executor.submit(() -> {
                    return ScopedValue.where(
                            GraphEngine.graphContextScope(), new GraphContext()).call(() -> {
                        stateStore.workItemStore().create(
                                workItem("item-async", WorkItemType.EXECUTE_NODE));
                        return stateStore.workItemStore().get("item-async").isPresent();
                    });
                }).get();
                assertThat(asyncReaderSawSpeculativeItem).isFalse();
            }
            assertThat(stateStore.workItemStore().get("item-unscoped")).isEmpty();
            assertThat(stateStore.workItemStore().get("item-async")).isPresent();
            assertThat(stateStore.workItemStore().pollReady(
                    WorkItemType.EXECUTE_NODE, null, 10)).isEmpty();

            var mutation = stage.prepare(
                    "checkpoint-async", "approval", "WORK_ITEM", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        assertThat(stateStore.workItemStore().get("item-async")).isPresent();
        assertThat(stateStore.workItemStore().pollReady(
                WorkItemType.EXECUTE_NODE, null, 10))
                .extracting(WorkItem::itemId).containsExactly("item-async");
    }

    @Test
    void preservesClaimRenewDoneAndCommittedOnlyDispatch() throws Exception {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.workItemStore().create(
                    workItem("item-lifecycle", WorkItemType.TIMER_DUE));
            var mutation = stage.prepare(
                    "checkpoint-ready", "approval", "WORK_ITEM", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            assertThat(stateStore.workItemStore().pollReady(
                    WorkItemType.TIMER_DUE, null, 10))
                    .extracting(WorkItem::itemId)
                    .containsExactly("item-lifecycle");

            WorkItem claimed = stateStore.workItemStore().claim(
                    "item-lifecycle", "worker-a", Duration.ofMinutes(1), 0)
                    .orElseThrow();
            assertThat(claimed.status()).isEqualTo(WorkItemStatus.CLAIMED);
            assertThat(claimed.version()).isEqualTo(1);
            assertThat(stateStore.workItemStore().claim(
                    "item-lifecycle", "worker-b", Duration.ofMinutes(1), 0)).isEmpty();

            TenantContextHolder.callWith(new TenantContext("tenant-b", "test-runtime"), () -> {
                assertThat(stateStore.workItemStore().renewClaim(
                        "item-lifecycle", claimed.claimToken(), Duration.ofMinutes(1))).isEmpty();
                return null;
            });

            WorkItem renewed = stateStore.workItemStore().renewClaim(
                    "item-lifecycle", claimed.claimToken(), Duration.ofMinutes(1))
                    .orElseThrow();
            assertThat(renewed.version()).isEqualTo(2);
            assertThatThrownBy(() -> stateStore.workItemStore().markDone(
                    "item-lifecycle", "wrong-token", renewed.version()))
                    .isInstanceOf(OptimisticLockException.class);

            stateStore.workItemStore().markDone(
                    "item-lifecycle", renewed.claimToken(), renewed.version());
            assertThat(stateStore.workItemStore().get("item-lifecycle"))
                    .get().satisfies(item -> {
                        assertThat(item.status()).isEqualTo(WorkItemStatus.DONE);
                        assertThat(item.version()).isEqualTo(3);
                        assertThat(item.completedAt()).isNotNull();
                    });
            assertThat(stateStore.workItemStore().pollReady(
                    WorkItemType.TIMER_DUE, null, 10))
                    .extracting(WorkItem::itemId)
                    .containsExactly("item-lifecycle");

            var mutation = stage.prepare(
                    "checkpoint-done", "approval", "WORK_ITEM", 2, 1);
            repository.advance(control(1, mutation.engineState()),
                    new DurableTestExecutionCheckpointRepository.Fence(
                            "instance-a", 1, 0), mutation);
        }

        assertThat(stateStore.workItemStore().pollReady(
                WorkItemType.TIMER_DUE, null, 10)).isEmpty();
        assertThat(stateStore.workItemStore().get("item-lifecycle"))
                .get().extracting(WorkItem::status).isEqualTo(WorkItemStatus.DONE);
    }

    @Test
    void preservesRetryDeadLetterRestoreDiscardAndCancellationSemantics() {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.workItemStore().createBatch(List.of(
                    workItem("item-retry", WorkItemType.TIMER_DUE),
                    workItem("item-dead", WorkItemType.EVENT_MATCHED),
                    workItem("item-done", WorkItemType.TASK_RESUME)));
            var mutation = stage.prepare(
                    "checkpoint-items", "approval", "WORK_ITEM", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            WorkItem retryClaim = stateStore.workItemStore().claim(
                    "item-retry", "worker-a", Duration.ofMinutes(1), 0).orElseThrow();
            stateStore.workItemStore().markRetryWait(
                    "item-retry", retryClaim.claimToken(), Instant.EPOCH,
                    new DeadLetterPolicy.Classification(
                            DeadLetterPolicy.FailureClass.TRANSIENT,
                            "socket timeout", Duration.ofSeconds(5)),
                    retryClaim.version());
            assertThat(stateStore.workItemStore().get("item-retry"))
                    .get().satisfies(item -> {
                        assertThat(item.status()).isEqualTo(WorkItemStatus.RETRY_WAIT);
                        assertThat(item.retryCount()).isEqualTo(1);
                        assertThat(item.failureClass())
                                .isEqualTo(DeadLetterPolicy.FailureClass.TRANSIENT);
                        assertThat(item.lastError()).isEqualTo("socket timeout");
                    });

            stateStore.workItemStore().markDeadLetter(
                    "item-dead", "governance hold");
            assertThat(stateStore.workItemStore().restoreDeadLetter("item-dead").status())
                    .isEqualTo(WorkItemStatus.READY);
            stateStore.workItemStore().markDeadLetter(
                    "item-dead", "operator retired", "PERMANENT");
            assertThat(stateStore.workItemStore().discardDeadLetter("item-dead").status())
                    .isEqualTo(WorkItemStatus.CANCELLED);

            WorkItem doneClaim = stateStore.workItemStore().claim(
                    "item-done", "worker-b", Duration.ofMinutes(1), 0).orElseThrow();
            stateStore.workItemStore().markDone(
                    "item-done", doneClaim.claimToken(), doneClaim.version());
            assertThat(stateStore.workItemStore().cancelByExecution(
                    "engine-a", "execution cancelled")).isEqualTo(1);
            assertThat(stateStore.workItemStore().get("item-retry"))
                    .get().extracting(WorkItem::status).isEqualTo(WorkItemStatus.CANCELLED);
            assertThat(stateStore.workItemStore().get("item-done"))
                    .get().extracting(WorkItem::status).isEqualTo(WorkItemStatus.DONE);

            var mutation = stage.prepare(
                    "checkpoint-terminal", "approval", "WORK_ITEM", 2, 1);
            repository.advance(control(1, mutation.engineState()),
                    new DurableTestExecutionCheckpointRepository.Fence(
                            "instance-a", 1, 0), mutation);
        }

        assertThat(stateStore.workItemStore().get("item-dead"))
                .get().extracting(WorkItem::status).isEqualTo(WorkItemStatus.CANCELLED);
        assertThat(stateStore.workItemStore().countWorkItems(
                new WorkItemQuery(
                        "engine-a", null, Set.of(WorkItemStatus.CANCELLED),
                        null, null, null, null, 0, 10))).isEqualTo(2);
    }

    @Test
    void persistsExpiredClaimsFailedItemsAndQueryCounts() {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.workItemStore().createBatch(List.of(
                    workItem("item-expired", WorkItemType.TIMER_DUE),
                    workItem("item-failed", WorkItemType.EVENT_MATCHED),
                    workItem("item-simple-retry", WorkItemType.EXECUTION_RETRY)));
            var mutation = stage.prepare(
                    "checkpoint-created", "approval", "WORK_ITEM", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            WorkItem expired = stateStore.workItemStore().claim(
                    "item-expired", "worker-expired", Duration.ZERO, 0).orElseThrow();
            WorkItem failed = stateStore.workItemStore().claim(
                    "item-failed", "worker-failed", Duration.ofMinutes(1), 0).orElseThrow();
            stateStore.workItemStore().markFailed(
                    "item-failed", failed.claimToken(), failed.version());
            WorkItem retry = stateStore.workItemStore().claim(
                    "item-simple-retry", "worker-retry", Duration.ofMinutes(1), 0).orElseThrow();
            stateStore.workItemStore().markRetryWait(
                    "item-simple-retry", retry.claimToken(), Instant.EPOCH, retry.version());

            assertThat(stateStore.workItemStore().findExpiredClaims(
                    expired.claimUntil().plusSeconds(1), 10)).isEmpty();
            assertThat(stateStore.workItemStore().countWorkItems(new WorkItemQuery(
                    "engine-a", null, Set.of(WorkItemStatus.FAILED),
                    null, null, null, null, 0, 10))).isEqualTo(1);

            var mutation = stage.prepare(
                    "checkpoint-classified", "approval", "WORK_ITEM", 2, 1);
            repository.advance(control(1, mutation.engineState()),
                    new DurableTestExecutionCheckpointRepository.Fence(
                            "instance-a", 1, 0), mutation);
        }

        StagedBlogeDurableStateStore coldStore =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        coldStore.init();
        WorkItem claimed = coldStore.workItemStore().get("item-expired").orElseThrow();
        assertThat(coldStore.workItemStore().findExpiredClaims(
                claimed.claimUntil().plusSeconds(1), 10))
                .extracting(WorkItem::itemId)
                .containsExactly("item-expired");
        assertThat(coldStore.workItemStore().get("item-failed"))
                .get().extracting(WorkItem::status).isEqualTo(WorkItemStatus.FAILED);
        assertThat(coldStore.workItemStore().pollReady(
                WorkItemType.EXECUTION_RETRY, null, 10))
                .extracting(WorkItem::itemId)
                .containsExactly("item-simple-retry");
    }

    @Test
    void preventsACommittedWorkItemIdFromMovingToAnotherExecution() {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.workItemStore().create(
                    workItem("item-owned", WorkItemType.TIMER_DUE));
            var mutation = stage.prepare(
                    "checkpoint-owned-item", "approval", "WORK_ITEM", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        StagedBlogeDurableStateStore competing =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        competing.init();
        ExecutionIdentity otherIdentity = new ExecutionIdentity(
                "tenant-a", "test-runtime", null, "engine-b",
                ExecutionType.GRAPH,
                "controlled-durable-state", "1", SHA_A,
                null, null, null, "run-b");
        try (StagedBlogeDurableStateStore.Stage ignored = competing.begin(
                "engine-b", ExecutionServices.builder().build().timeSource())) {
            competing.executionStore().create(
                    ExecutionInstance.builder(otherIdentity)
                            .status(ExecutionStatus.SUSPENDED)
                            .build());

            assertThatThrownBy(() -> competing.workItemStore().create(
                    workItem("item-owned", WorkItemType.TIMER_DUE, otherIdentity)))
                    .isInstanceOf(DurabilityException.class)
                    .hasMessageContaining("already committed for execution engine-a");
        }

        assertThat(stateStore.workItemStore().get("item-owned"))
                .get().satisfies(item ->
                        assertThat(item.identity().executionId()).isEqualTo("engine-a"));
    }

    @Test
    void rejectsWaitWritesOutsideTheAggregateOrWithIdentityDrift() {
        assertThatThrownBy(() -> stateStore.waitStore().create(wait(
                "wait-outside", WaitType.WAIT_SIGNAL, "outside-key")))
                .isInstanceOf(DurabilityException.class)
                .hasMessageContaining("active composite stage");

        try (StagedBlogeDurableStateStore.Stage ignored = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            ExecutionIdentity drifted = new ExecutionIdentity(
                    "tenant-b", "test-runtime", null, "engine-a",
                    ExecutionType.GRAPH,
                    "controlled-durable-state", "1", SHA_A,
                    null, null, null, "run-a");
            ExecutionWait foreignWait = new ExecutionWait(
                    "wait-foreign", drifted, WaitType.WAIT_SIGNAL,
                    "approval", "foreign-key", null, "SIGNAL",
                    null, null, WaitStatus.WAITING, 0,
                    Instant.parse("2026-07-16T08:00:00Z"),
                    Instant.parse("2026-07-16T08:00:00Z"), null);

            assertThatThrownBy(() -> stateStore.waitStore().create(foreignWait))
                    .isInstanceOf(DurabilityException.class)
                    .hasMessageContaining("identity does not match");
        }
    }

    @Test
    void preventsACommittedWaitIdFromMovingToAnotherExecution() {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.waitStore().create(wait(
                    "wait-owned", WaitType.WAIT_SIGNAL, "owned-key"));
            var mutation = stage.prepare(
                    "checkpoint-owned", "approval", "SUSPEND", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        StagedBlogeDurableStateStore competing =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        competing.init();
        ExecutionIdentity otherIdentity = new ExecutionIdentity(
                "tenant-a", "test-runtime", null, "engine-b",
                ExecutionType.GRAPH,
                "controlled-durable-state", "1", SHA_A,
                null, null, null, "run-b");
        try (StagedBlogeDurableStateStore.Stage ignored = competing.begin(
                "engine-b", ExecutionServices.builder().build().timeSource())) {
            competing.executionStore().create(
                    ExecutionInstance.builder(otherIdentity)
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            ExecutionWait conflicting = new ExecutionWait(
                    "wait-owned", otherIdentity, WaitType.WAIT_SIGNAL,
                    "approval", "other-key", null, "SIGNAL",
                    null, null, WaitStatus.WAITING, 0,
                    Instant.parse("2026-07-16T08:00:00Z"),
                    Instant.parse("2026-07-16T08:00:00Z"), null);

            assertThatThrownBy(() -> competing.waitStore().create(conflicting))
                    .isInstanceOf(DurabilityException.class)
                    .hasMessageContaining("already committed for execution engine-a");
        }

        assertThat(stateStore.waitStore().get("wait-owned"))
                .get().satisfies(wait -> {
                    assertThat(wait.identity().executionId()).isEqualTo("engine-a");
                    assertThat(wait.waitKey()).isEqualTo("owned-key");
                });
    }

    @Test
    void rollsBackExecutionLifecycleWhenTheCompositeCheckpointCannotCommit() {
        IndependentDurableTestEngineFactory factory = factory(stateStore);

        try (IndependentDurableTestEngineFactory.RunSession session = factory.openSession(
                "engine-a", new InvocationRecorder(objectMapper), executionOptions())) {
            assertThat(session.execute(graph(), new GraphContext()).isSuccess()).isTrue();
            stateStore.waitStore().create(wait(
                    "wait-rollback", WaitType.WAIT_SIGNAL, "rollback-key"));
            stateStore.workItemStore().create(
                    workItem("item-rollback", WorkItemType.TIMER_DUE));
            var mutation = session.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);

            assertThatThrownBy(() -> repository.create(control(mutation.engineState()),
                    failingAfterApply(mutation)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("after complete BLOGE state");
        }

        assertThat(stateStore.executionStore().get("engine-a")).isEmpty();
        assertThat(stateStore.checkpointStore().loadAll("engine-a")).isEmpty();
        assertThat(stateStore.waitStore().findByExecution("engine-a")).isEmpty();
        assertThat(stateStore.workItemStore().get("item-rollback")).isEmpty();
        assertThat(repository.find("tenant-a", "test", "run-a")).isEmpty();
    }

    @Test
    void preservesWaitTransitionVersionsAndCommittedOnlyTimerScans() {
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.waitStore().create(wait(
                    "wait-timer", WaitType.WAIT_TIMER, "timer-key"));
            var mutation = stage.prepare(
                    "checkpoint-initial", "approval", "SUSPEND", 1, 0);
            repository.create(control(mutation.engineState()), mutation);
        }

        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.waitStore().timeout("wait-timer", 0);

            assertThat(stateStore.waitStore().get("wait-timer"))
                    .get().satisfies(wait -> {
                        assertThat(wait.status()).isEqualTo(WaitStatus.TIMED_OUT);
                        assertThat(wait.version()).isEqualTo(1);
                        assertThat(wait.resolvedAt()).isNotNull();
                    });
            assertThatThrownBy(() -> stateStore.waitStore().timeout("wait-timer", 0))
                    .isInstanceOf(OptimisticLockException.class);
            assertThat(stateStore.waitStore().findByType(
                    WaitType.WAIT_TIMER, WaitStatus.WAITING, 10))
                    .extracting(ExecutionWait::waitId)
                    .containsExactly("wait-timer");
            assertThat(stateStore.waitStore().findByType(
                    WaitType.WAIT_TIMER, WaitStatus.TIMED_OUT, 10)).isEmpty();

            var mutation = stage.prepare(
                    "checkpoint-timeout", "approval", "TIMER", 2, 1);
            repository.advance(control(1, mutation.engineState()),
                    new DurableTestExecutionCheckpointRepository.Fence(
                            "instance-a", 1, 0), mutation);
        }

        StagedBlogeDurableStateStore coldStore =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        coldStore.init();
        assertThat(coldStore.waitStore().findByType(
                WaitType.WAIT_TIMER, WaitStatus.WAITING, 10)).isEmpty();
        assertThat(coldStore.waitStore().findByType(
                WaitType.WAIT_TIMER, WaitStatus.TIMED_OUT, 10))
                .singleElement()
                .satisfies(wait -> {
                    assertThat(wait.waitId()).isEqualTo("wait-timer");
                    assertThat(wait.version()).isEqualTo(1);
                });
    }

    @Test
    void executionLifecycleMutationsRemainStagedAndEnforceBlogeVersionRules() {
        try (StagedBlogeDurableStateStore.Stage stage =
                     stateStore.begin("engine-a",
                             ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.RUNNING)
                            .version(0)
                            .build());
            stateStore.executionStore().updateStatus("engine-a", ExecutionStatus.SUSPENDED, 0);

            assertThat(stateStore.executionStore().get("engine-a"))
                    .get().satisfies(instance -> {
                        assertThat(instance.status()).isEqualTo(ExecutionStatus.SUSPENDED);
                        assertThat(instance.version()).isEqualTo(1);
                    });
            assertThatThrownBy(() -> stateStore.executionStore().updateStatus(
                    "engine-a", ExecutionStatus.COMPLETED, 0))
                    .isInstanceOf(OptimisticLockException.class);

            var mutation = stage.prepare(
                    "checkpoint-0", "only", "SUSPEND", 1, 1);
            repository.create(control(mutation.engineState()), mutation);
        }

        assertThat(stateStore.executionStore().get("engine-a"))
                .get().satisfies(instance -> {
                    assertThat(instance.status()).isEqualTo(ExecutionStatus.SUSPENDED);
                    assertThat(instance.version()).isEqualTo(1);
                });
    }

    @Test
    void aggregateFingerprintChangesWhenOnlyTheExecutionLifecycleChanges() {
        String runningFingerprint;
        try (StagedBlogeDurableStateStore.Stage stage =
                     stateStore.begin("engine-a",
                             ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.RUNNING)
                            .build());
            runningFingerprint = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0)
                    .engineState().closureFingerprint();
        }

        String suspendedFingerprint;
        try (StagedBlogeDurableStateStore.Stage stage =
                     stateStore.begin("engine-a",
                             ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            suspendedFingerprint = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0)
                    .engineState().closureFingerprint();
        }

        assertThat(suspendedFingerprint).isNotEqualTo(runningFingerprint);
    }

    @Test
    void aggregateFingerprintChangesWhenOnlyTheWaitStateChanges() {
        String waitingFingerprint;
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.waitStore().create(wait(
                    "wait-approval", WaitType.WAIT_SIGNAL, "approval-key"));
            waitingFingerprint = stage.prepare(
                    "checkpoint-0", "approval", "SUSPEND", 1, 0)
                    .engineState().closureFingerprint();
        }

        String resolvedFingerprint;
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.waitStore().create(wait(
                    "wait-approval", WaitType.WAIT_SIGNAL, "approval-key"));
            stateStore.waitStore().resolve("wait-approval", 0);
            resolvedFingerprint = stage.prepare(
                    "checkpoint-0", "approval", "SUSPEND", 1, 0)
                    .engineState().closureFingerprint();
        }

        assertThat(resolvedFingerprint).isNotEqualTo(waitingFingerprint);
    }

    @Test
    void aggregateFingerprintChangesWhenOnlyTheWorkItemStateChanges() {
        String readyFingerprint;
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.workItemStore().create(
                    workItem("item-fingerprint", WorkItemType.TIMER_DUE));
            readyFingerprint = stage.prepare(
                    "checkpoint-0", "approval", "WORK_ITEM", 1, 0)
                    .engineState().closureFingerprint();
        }

        String deadLetterFingerprint;
        try (StagedBlogeDurableStateStore.Stage stage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            stateStore.workItemStore().create(
                    workItem("item-fingerprint", WorkItemType.TIMER_DUE));
            stateStore.workItemStore().markDeadLetter(
                    "item-fingerprint", "fingerprint-state");
            deadLetterFingerprint = stage.prepare(
                    "checkpoint-0", "approval", "WORK_ITEM", 1, 0)
                    .engineState().closureFingerprint();
        }

        assertThat(deadLetterFingerprint).isNotEqualTo(readyFingerprint);
    }

    @Test
    void concurrentControlCasRollsBackTheLosingExecutionLifecycle() throws Exception {
        try (StagedBlogeDurableStateStore.Stage initialStage = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.RUNNING)
                            .build());
            stateStore.waitStore().create(wait(
                    "wait-race", WaitType.WAIT_SIGNAL, "race-key"));
            stateStore.workItemStore().create(
                    workItem("item-race", WorkItemType.TIMER_DUE));
            var initialMutation = initialStage.prepare(
                    "checkpoint-initial", "initial", "NODE_BOUNDARY", 1, 0);
            repository.create(control(0, initialMutation.engineState()), initialMutation);
        }
        StagedBlogeDurableStateStore competing =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        competing.init();
        CountDownLatch bothApplied = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (StagedBlogeDurableStateStore.Stage first = stateStore.begin(
                "engine-a", ExecutionServices.builder().build().timeSource());
             StagedBlogeDurableStateStore.Stage second = competing.begin(
                     "engine-a", ExecutionServices.builder().build().timeSource())) {
            stateStore.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.SUSPENDED)
                            .build());
            competing.executionStore().create(
                    ExecutionInstance.builder(executionIdentity())
                            .status(ExecutionStatus.COMPLETED)
                            .build());
            stateStore.waitStore().resolve("wait-race", 0);
            competing.waitStore().timeout("wait-race", 0);
            stateStore.workItemStore().markDeadLetter("item-race", "candidate-a");
            competing.workItemStore().markDeadLetter("item-race", "candidate-b");
            var firstMutation = first.prepare(
                    "checkpoint-a", "candidate-a", "SUSPEND", 2, 1);
            var secondMutation = second.prepare(
                    "checkpoint-b", "candidate-b", "NODE_BOUNDARY", 2, 1);

            try (var executor = Executors.newFixedThreadPool(2)) {
                var firstResult = executor.submit(() -> advanceCandidate(
                        firstMutation, bothApplied, release));
                var secondResult = executor.submit(() -> advanceCandidate(
                        secondMutation, bothApplied, release));
                assertThat(bothApplied.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat((firstResult.get() ? 1 : 0) + (secondResult.get() ? 1 : 0))
                        .isEqualTo(1);
            }
        }

        String winner = repository.find("tenant-a", "test", "run-a")
                .orElseThrow().engineState().nodeId();
        ExecutionStatus expected = "candidate-a".equals(winner)
                ? ExecutionStatus.SUSPENDED : ExecutionStatus.COMPLETED;
        assertThat(stateStore.executionStore().get("engine-a"))
                .get().extracting(instance -> instance.status()).isEqualTo(expected);
        WaitStatus expectedWait = "candidate-a".equals(winner)
                ? WaitStatus.RESOLVED : WaitStatus.TIMED_OUT;
        assertThat(stateStore.waitStore().get("wait-race"))
                .get().extracting(ExecutionWait::status).isEqualTo(expectedWait);
        assertThat(stateStore.workItemStore().get("item-race"))
                .get().extracting(WorkItem::lastError).isEqualTo(winner);
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_bloge_executions", Integer.class)).isEqualTo(1);
    }

    private IndependentDurableTestEngineFactory factory(StagedBlogeDurableStateStore store) {
        return new IndependentDurableTestEngineFactory(
                new DefaultOperatorRegistry(), new JacksonCheckpointCodec(objectMapper), store);
    }

    private Graph graph() {
        Operator<Void, String> operator = (ignored, context) -> "real";
        return new GraphBuilder("controlled-durable-state")
                .node("only", operator)
                .build();
    }

    private ExecutionOptions executionOptions() {
        return ExecutionOptions.builder()
                .operatorResolver(request ->
                        (Operator<Void, String>) (ignored, context) -> "fixture")
                .executionServices(ExecutionServices.builder()
                        .idGenerator(scope -> "fixture-id")
                        .build())
                .build();
    }

    private ExecutionWait wait(String waitId, WaitType waitType, String waitKey) {
        Instant now = Instant.parse("2026-07-16T08:00:00Z");
        ExecutionIdentity identity = stateStore.executionStore().get("engine-a")
                .map(ExecutionInstance::identity)
                .orElseGet(this::executionIdentity);
        return new ExecutionWait(
                waitId, identity, waitType, "approval", waitKey,
                now.plusSeconds(300), "SIGNAL", null, null, WaitStatus.WAITING,
                0, now, now, null);
    }

    private WorkItem workItem(String itemId, WorkItemType itemType) {
        ExecutionIdentity identity = stateStore.executionStore().get("engine-a")
                .map(ExecutionInstance::identity)
                .orElseGet(this::executionIdentity);
        return workItem(itemId, itemType, identity);
    }

    private WorkItem workItem(String itemId,
                              WorkItemType itemType,
                              ExecutionIdentity identity) {
        Instant now = Instant.parse("2026-07-16T08:00:00Z");
        return WorkItem.builder()
                .itemId(itemId)
                .executionIdentity(identity)
                .itemType(itemType)
                .nodeId("approval")
                .waitId("wait-approval")
                .priority(50)
                .status(WorkItemStatus.READY)
                .maxRetries(3)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private ExecutionIdentity executionIdentity() {
        return new ExecutionIdentity(
                "tenant-a", "test-runtime", null, "engine-a",
                ExecutionType.GRAPH,
                "controlled-durable-state", "1", SHA_A,
                null, null, null, "run-a");
    }

    private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation failingAfterApply(
            DurableTestExecutionCheckpointRepository.BoundEngineStateMutation delegate) {
        return new DurableTestExecutionCheckpointRepository.BoundEngineStateMutation() {
            @Override
            public String engineExecutionId() {
                return delegate.engineExecutionId();
            }

            @Override
            public DurableTestExecutionCheckpoint.EngineState engineState() {
                return delegate.engineState();
            }

            @Override
            public void apply(JdbcTemplate jdbc) {
                delegate.apply(jdbc);
                throw new IllegalStateException("failure after complete BLOGE state");
            }
        };
    }

    private boolean advanceCandidate(
            DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation,
            CountDownLatch entered,
            CountDownLatch release) {
        DurableTestExecutionCheckpointRepository.BoundEngineStateMutation coordinated =
                new DurableTestExecutionCheckpointRepository.BoundEngineStateMutation() {
                    @Override
                    public String engineExecutionId() {
                        return mutation.engineExecutionId();
                    }

                    @Override
                    public DurableTestExecutionCheckpoint.EngineState engineState() {
                        return mutation.engineState();
                    }

                    @Override
                    public void apply(JdbcTemplate jdbc) {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("concurrency test did not release");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(
                                    "concurrency test was interrupted", interrupted);
                        }
                        mutation.apply(jdbc);
                    }
                };
        try {
            repository.advance(control(1, mutation.engineState()),
                    new DurableTestExecutionCheckpointRepository.Fence(
                            "instance-a", 1, 0), coordinated);
            return true;
        } catch (com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException
                 expected) {
            return false;
        }
    }

    private DurableTestExecutionCheckpoint control(
            DurableTestExecutionCheckpoint.EngineState engineState) {
        return control(0, engineState);
    }

    private DurableTestExecutionCheckpoint control(
            long revision, DurableTestExecutionCheckpoint.EngineState engineState) {
        Instant now = Instant.parse("2026-07-16T08:00:00Z").plusSeconds(revision);
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION, "plan-a", SHA_A,
                "GRAPH_CONTRACT_TEST", SHA_B, SHA_C, List.of(), List.of(), List.of(),
                Map.of("unmatchedExternalEffect", "DENY"), List.of());
        ExecutionServiceStateSnapshot unsealedProvider = new ExecutionServiceStateSnapshot(
                ExecutionServiceStateSnapshot.SCHEMA_VERSION, SHA_A, SHA_B, now,
                Map.of(SHA_C, revision + 1), Map.of(), List.of(), true, List.of(), SHA_D);
        ExecutionServiceStateSnapshot provider = new ExecutionServiceStateSnapshot(
                unsealedProvider.schemaVersion(), unsealedProvider.planFingerprint(),
                unsealedProvider.bindingSetFingerprint(), unsealedProvider.logicalTime(),
                unsealedProvider.randomScopeCursors(), unsealedProvider.uuidScopeCursors(),
                unsealedProvider.usages(), unsealedProvider.restorable(),
                unsealedProvider.restoreGaps(), ProtocolFingerprint.of(
                objectMapper, unsealedProvider.fingerprintMaterial()));
        return integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                "run-a", "engine-a",
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        plan, new DurableTestExecutionCheckpoint.ExactFixtureRef(
                        "fixture-a", 3, SHA_C), "DENY_REAL",
                        new DurableTestExecutionCheckpoint.AuthoritySnapshot("FAIL_CLOSED", SHA_D),
                        new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                                "GRAPH", "credit-score", SHA_B)),
                new FixtureConsumptionStateSnapshot(
                        FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                        Map.of("rule-a", revision + 1), Map.of(SHA_A, revision + 1),
                        Map.of(SHA_B, revision + 1), ""),
                provider,
                engineState,
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.SUSPENDED, "instance-a", 1,
                        revision, Instant.parse("2026-07-16T08:00:00Z"), now,
                        now.plusSeconds(30)), ""));
    }
}
