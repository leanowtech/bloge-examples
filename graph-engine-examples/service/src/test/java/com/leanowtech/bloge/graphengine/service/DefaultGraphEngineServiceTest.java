package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.checkpoint.TaskStore;
import com.leanowtech.bloge.core.checkpoint.TimerSpec;
import com.leanowtech.bloge.core.checkpoint.TimerStatus;
import com.leanowtech.bloge.core.checkpoint.TimerType;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.RemoteWorkerEnvelope;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpoint;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.event.EventMatcher;
import com.leanowtech.bloge.core.runtime.event.EventMatcherQuery;
import com.leanowtech.bloge.core.runtime.event.EventMatcherStatus;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import com.leanowtech.bloge.core.runtime.registry.GraphMigrationPolicy;
import com.leanowtech.bloge.core.runtime.wait.ExecutionWait;
import com.leanowtech.bloge.core.runtime.wait.WaitStatus;
import com.leanowtech.bloge.core.runtime.wait.WaitType;
import com.leanowtech.bloge.core.runtime.work.WorkItem;
import com.leanowtech.bloge.core.runtime.work.WorkItemQuery;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.durable.StoreAdapters;
import com.leanowtech.bloge.durable.TaskInboxTaskStore;
import com.leanowtech.bloge.durable.UserTaskOperator;
import com.leanowtech.bloge.durable.control.ControlPlaneService;
import com.leanowtech.bloge.durable.control.DeadLetterEntry;
import com.leanowtech.bloge.durable.control.DeadLetterQuery;
import com.leanowtech.bloge.durable.control.ExecutionTransitionLogEntry;
import com.leanowtech.bloge.durable.control.ExecutionTransitionQuery;
import com.leanowtech.bloge.durable.store.memory.InMemoryEventMatcherStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryExecutionCheckpointStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryExecutionStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryGraphRegistryStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryTaskInboxStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryWaitStore;
import com.leanowtech.bloge.durable.store.memory.InMemoryWorkItemStore;
import com.leanowtech.bloge.graphengine.model.GraphAuditEntry;
import com.leanowtech.bloge.graphengine.model.GraphControlActionEntry;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphDeadLetter;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceDiagram;
import com.leanowtech.bloge.graphengine.model.GraphInstanceContext;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphDefinitionStatus;
import com.leanowtech.bloge.graphengine.model.GraphNodeState;
import com.leanowtech.bloge.graphengine.model.GraphNodeStatus;
import com.leanowtech.bloge.graphengine.model.GraphOperationsSnapshot;
import com.leanowtech.bloge.graphengine.model.GraphPendingSignal;
import com.leanowtech.bloge.graphengine.model.PagedResult;
import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerJob;
import com.leanowtech.bloge.graphengine.model.GraphRemoteWorkerRegistration;
import com.leanowtech.bloge.graphengine.model.OperatorPlaneConfig;
import com.leanowtech.bloge.graphengine.model.GraphTask;
import com.leanowtech.bloge.graphengine.model.GraphTaskStatus;
import com.leanowtech.bloge.graphengine.model.GraphTransitionEntry;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionDiagram;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.model.RemoteWorkerBinding;
import com.leanowtech.bloge.graphengine.model.VisualLayout;
import com.leanowtech.bloge.graphengine.service.command.ClaimTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.CompleteRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.CompleteTaskCommand;
import com.leanowtech.bloge.graphengine.service.command.CreateDefinitionCommand;
import com.leanowtech.bloge.graphengine.service.command.CreateDeploymentCommand;
import com.leanowtech.bloge.graphengine.service.command.CreateVersionCommand;
import com.leanowtech.bloge.graphengine.service.command.FailRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.HeartbeatRemoteWorkerJobCommand;
import com.leanowtech.bloge.graphengine.service.command.PollRemoteWorkerJobsCommand;
import com.leanowtech.bloge.graphengine.service.command.RegisterRemoteWorkerCommand;
import com.leanowtech.bloge.graphengine.service.command.SignalInstanceCommand;
import com.leanowtech.bloge.graphengine.service.command.StartInstanceCommand;
import com.leanowtech.bloge.graphengine.store.GraphDeadLetterQuery;
import com.leanowtech.bloge.graphengine.store.GraphEngineStores;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphDefinitionStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphDeploymentStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphInstanceStore;
import com.leanowtech.bloge.graphengine.store.memory.InMemoryGraphVersionStore;
import com.leanowtech.bloge.runtime.audit.AuditEntry;
import com.leanowtech.bloge.runtime.audit.AuditEventType;
import com.leanowtech.bloge.runtime.audit.InMemoryAuditJournalStore;
import com.leanowtech.bloge.runtime.engine.DurableGraphEngine;
import com.leanowtech.bloge.runtime.task.TaskInbox;
import com.leanowtech.bloge.runtime.task.TaskInboxQuery;
import com.leanowtech.bloge.runtime.task.TaskInboxStatus;
import com.leanowtech.bloge.session.durable.DurableSessionManager;
import com.leanowtech.bloge.session.durable.checkpoint.ExecutionCheckpointSessionStore;
import com.leanowtech.bloge.session.durable.checkpoint.SessionCheckpoint;
import com.leanowtech.bloge.state.checkpoint.ExecutionCheckpointStateMachineStore;
import com.leanowtech.bloge.state.checkpoint.StateMachineCheckpoint;
import com.leanowtech.bloge.runtime.timer.InMemoryTimerService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultGraphEngineServiceTest {

    private static final CheckpointCodec TEST_CODEC = new CheckpointCodec() {
        private final JsonCodec json = JsonCodec.DEFAULT;

        @Override
        public String serialize(Object value) {
            return json.serialize(value);
        }

        @Override
        public Object deserialize(String payload) {
            return json.deserialize(payload);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T deserialize(String payload, Class<T> type) {
            return (T) json.deserialize(payload);
        }
    };

    @Test
    void publishVersionStoresNamespacedRegistryDefinitionAndStartsInstance() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "order-echo",
                    "tenant-a",
                    "sales",
                    "Order echo",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph orderEcho {
                              node echo : echo {
                                input {
                                  orderId = ctx.orderId
                                }
                              }
                            }
                            """,
                    null,
                    GraphMigrationPolicy.PIN_VERSION
            ));

            GraphVersion published = fixture.service.publishVersion(version.versionId(), version.revision()).version();
            String runtimeName = String.valueOf(published.metadata().migrationHints().get("runtimeName"));

            assertTrue(fixture.graphRegistryStore.get(runtimeName, published.version()).isPresent());
            assertTrue(fixture.graphRegistryStore.get("orderEcho", published.version()).isEmpty());

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    null,
                    null,
                    "order-001",
                    "alice",
                    Map.of("orderId", "order-001")
            ));

            assertEquals(published.versionId(), started.instance().versionId());
            assertEquals(GraphInstanceStatus.COMPLETED, started.instance().status());
            assertEquals(1, fixture.metricsObserver.versionPublishedCount);
            assertEquals(1, fixture.metricsObserver.instanceStartedCount);
            assertEquals(List.of("GRAPH"), fixture.metricsObserver.instanceStartModes);
            assertEquals(List.of("COMPLETED"), fixture.metricsObserver.instanceCompletedStatuses);
        }
    }

    @Test
    void startInstanceUsesActiveDeploymentRouting() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "routeable-order",
                    "tenant-a",
                    "sales",
                    "Routeable order",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));

            GraphVersion version1 = publish(fixture, definition, "1.0.0");
            GraphVersion version2 = publish(fixture, definition, "2.0.0");

            fixture.service.createDeployment(new CreateDeploymentCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "prod",
                    new com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy.Pinned("2.0.0"),
                    null,
                    true
            ));

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    null,
                    "prod",
                    "route-key-1",
                    "alice",
                    Map.of("version", "ignored")
            ));

            assertEquals(version2.versionId(), started.instance().versionId());
            assertEquals(GraphInstanceStatus.COMPLETED, started.instance().status());
            assertTrue(version1.versionId() != null);
        }
    }

    @Test
    void completeTaskResumesDurableExecutionAndProjectsCompletedTask() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "approval-flow",
                    "tenant-a",
                    "sales",
                    "Approval flow",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph approvalFlow {
                              node approval : user {
                                input {
                                  title = "Approve order"
                                  candidateGroups = ["ops"]
                                  orderId = ctx.orderId
                                }
                              }

                              node done : echo {
                                depends_on = [approval]
                                input {
                                  approved = approval.output.approved
                                  orderId = approval.output.orderId
                                }
                              }
                            }
                            """,
                    null,
                    GraphMigrationPolicy.PIN_VERSION
            ));
            fixture.service.publishVersion(version.versionId(), version.revision());

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    null,
                    "approval-001",
                    "starter",
                    Map.of("orderId", "approval-001")
            ));

            assertEquals(GraphInstanceStatus.SUSPENDED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.SUSPENDED
            ));

            List<GraphTask> tasks = fixture.service.queryTasks(new TaskInboxQuery(
                    null,
                    null,
                    null,
                    Set.of(TaskInboxStatus.OPEN),
                    null,
                    null,
                    null,
                    started.instance().instanceId(),
                    0,
                    50
            ));
            assertEquals(1, tasks.size());
            GraphTask task = tasks.getFirst();
            assertEquals(GraphTaskStatus.OPEN, task.status());
            assertEquals("user-task", task.taskType());
            assertEquals(List.of(), task.candidateUsers());
            assertEquals(List.of("ops"), task.candidateGroups());
            assertNull(task.slaDeadline());

            GraphTask claimed = fixture.service.claimTask(new ClaimTaskCommand(task.taskId(), "reviewer"));
            assertEquals(GraphTaskStatus.CLAIMED, claimed.status());

            fixture.service.completeTask(new CompleteTaskCommand(
                    task.taskId(),
                    Map.of("approved", true, "orderId", "approval-001"),
                    "reviewer"
            ));

            assertEquals(GraphInstanceStatus.COMPLETED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.COMPLETED
            ));
            assertEquals(ExecutionStatus.COMPLETED, fixture.executionStore.get(started.instance().instanceId()).orElseThrow().status());
            assertEquals(GraphTaskStatus.COMPLETED, fixture.service.getTask(task.taskId()).status());
            assertEquals(1, fixture.metricsObserver.taskClaimedCount);
            assertEquals(1, fixture.metricsObserver.taskCompletedCount);
            assertEquals(List.of("COMPLETED"), fixture.metricsObserver.instanceCompletedStatuses);
        }
    }

    @Test
    void queryInstanceNodesProjectsUserTaskWaitsAndCompletedOutputs() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "approval-node-view",
                    "tenant-a",
                    "sales",
                    "Approval node view",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph approvalNodeView {
                              node approval : user {
                                input {
                                  title = "Approve order"
                                  candidateGroups = ["ops"]
                                  orderId = ctx.orderId
                                }
                              }

                              node done : echo {
                                depends_on = [approval]
                                input {
                                  approved = approval.output.approved
                                  orderId = approval.output.orderId
                                }
                              }
                            }
                            """,
                    null,
                    GraphMigrationPolicy.PIN_VERSION
            ));
            fixture.service.publishVersion(version.versionId(), version.revision());

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    null,
                    "approval-node-view-001",
                    "starter",
                    Map.of("orderId", "approval-node-view-001")
            ));

            assertEquals(GraphInstanceStatus.SUSPENDED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.SUSPENDED
            ));

            List<GraphNodeState> waitingNodes = fixture.service.queryInstanceNodes(started.instance().instanceId());
            assertEquals(List.of("approval", "done"), waitingNodes.stream().map(GraphNodeState::nodeId).toList());
            assertEquals(GraphNodeStatus.WAITING, waitingNodes.getFirst().status());
            assertEquals("WAIT_SIGNAL", waitingNodes.getFirst().waitType());
            assertEquals(GraphNodeStatus.NOT_STARTED, waitingNodes.get(1).status());

            GraphTask task = fixture.service.queryTasks(new TaskInboxQuery(
                    null,
                    null,
                    null,
                    Set.of(TaskInboxStatus.OPEN),
                    null,
                    null,
                    null,
                    started.instance().instanceId(),
                    0,
                    10
            )).getFirst();
            fixture.service.completeTask(new CompleteTaskCommand(
                    task.taskId(),
                    Map.of("approved", true, "orderId", "approval-node-view-001"),
                    "reviewer"
            ));

            assertEquals(GraphInstanceStatus.COMPLETED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.COMPLETED
            ));

            List<GraphNodeState> completedNodes = fixture.service.queryInstanceNodes(started.instance().instanceId());
            assertEquals(GraphNodeStatus.COMPLETED, completedNodes.getFirst().status());
            assertEquals(GraphNodeStatus.COMPLETED, completedNodes.get(1).status());
            assertNotNull(completedNodes.getFirst().completedAt());
            assertNotNull(completedNodes.get(1).completedAt());
        }
    }

    @Test
    void queryInstanceNodesAppliesStatusFilterPaginationAndBoundsWorkItemQueries() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "approval-node-page-view",
                    "tenant-a",
                    "sales",
                    "Approval node page view",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph approvalNodePageView {
                              node approval : user {
                                input {
                                  title = "Approve order"
                                  candidateGroups = ["ops"]
                                  orderId = ctx.orderId
                                }
                              }

                              node done : echo {
                                depends_on = [approval]
                                input {
                                  approved = approval.output.approved
                                }
                              }
                            }
                            """,
                    null,
                    GraphMigrationPolicy.PIN_VERSION
            ));
            fixture.service.publishVersion(version.versionId(), version.revision());
            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    null,
                    "approval-node-page-view-001",
                    "starter",
                    Map.of("orderId", "approval-node-page-view-001")
            ));

            assertEquals(GraphInstanceStatus.SUSPENDED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.SUSPENDED
            ));

            PagedResult<GraphNodeState> waitingNodes = fixture.service.queryInstanceNodes(
                    started.instance().instanceId(),
                    Set.of(GraphNodeStatus.WAITING),
                    0,
                    1
            );

            assertEquals(0, waitingNodes.page());
            assertEquals(1, waitingNodes.size());
            assertEquals(1, waitingNodes.total());
            assertEquals(1, waitingNodes.items().size());
            assertEquals("approval", waitingNodes.items().getFirst().nodeId());
            assertEquals(GraphNodeStatus.WAITING, waitingNodes.items().getFirst().status());
            assertEquals(6, fixture.workItemStore.lastQuery().size());
        }
    }

    @Test
    void queryPendingSignalsReturnsEmptyForRunningGraphInstances() {
        try (Fixture fixture = new Fixture(false)) {
            GraphInstance instance = fixture.createManagedGraphInstance("exec-pending-running-1", GraphInstanceStatus.RUNNING);

            assertEquals(List.of(), fixture.service.queryPendingSignals(instance.instanceId()));
        }
    }

    @Test
    void queryInstanceNodesProjectsLiveSessionPhases() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-node-view",
                    "tenant-a",
                    "sales",
                    "Session node view",
                    null, null, Map.of(), null, null
            ));
            publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionNodeView"));

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    null,
                    null,
                    "session-node-view-001",
                    "starter",
                    Map.of("orderId", "session-node-view-001")
            ));

            assertEquals(GraphInstanceStatus.SUSPENDED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.SUSPENDED
            ));

            List<GraphNodeState> nodes = fixture.service.queryInstanceNodes(started.instance().instanceId());

            assertEquals(List.of("awaitDecision", "approved", "rejected"),
                    nodes.stream().map(GraphNodeState::nodeId).toList());
            assertEquals(GraphNodeStatus.WAITING, nodes.getFirst().status());
            assertNull(nodes.getFirst().waitType());
            assertEquals(GraphNodeStatus.NOT_STARTED, nodes.get(1).status());
            assertEquals(GraphNodeStatus.NOT_STARTED, nodes.get(2).status());
        }
    }

    @Test
    void queryInstanceNodesSkipsCheckpointLoadWhenActiveSessionSnapshotExists() {
        CountingExecutionCheckpointStore checkpointStore = new CountingExecutionCheckpointStore();
        try (Fixture fixture = new Fixture(true, checkpointStore)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-node-live-shortcut",
                    "tenant-a",
                    "sales",
                    "Session node live shortcut",
                    null, null, Map.of(), null, null
            ));
            publishSession(fixture, definition, "1.0.0", slowSessionDsl("sessionNodeLiveShortcut"));

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    null,
                    null,
                    "session-node-live-shortcut-001",
                    "starter",
                    Map.of("orderId", "session-node-live-shortcut-001")
            ));

            DurableSessionManager sessionManager = sessionManager(fixture.service);
            assertTrue(sessionManager.query(started.instance().instanceId(), started.instance().initiator()).isPresent());
            checkpointStore.resetLoadCalls();

            List<GraphNodeState> nodes = fixture.service.queryInstanceNodes(started.instance().instanceId());

            assertEquals(List.of("work", "done"),
                    nodes.stream().map(GraphNodeState::nodeId).toList());
            assertEquals(GraphNodeStatus.WAITING, nodes.getFirst().status());
            assertEquals(0, checkpointStore.sessionSnapshotLoadCalls());
        }
    }

    @Test
    void queryInstanceNodesProjectsSessionPhasesFromCheckpointHistory() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-node-checkpoint",
                    "tenant-a",
                    "sales",
                    "Session node checkpoint",
                    null, null, Map.of(), null, null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", twoStepWaitingSessionDsl("sessionNodeCheckpoint"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-nodes-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant first = Instant.parse("2025-01-01T00:00:00Z");
            Instant second = first.plusSeconds(30);
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionNodeCheckpoint")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-node-checkpoint")
                    .currentPhaseId("secondDecision")
                    .currentPhaseRound(1)
                    .totalRounds(2)
                    .phaseVisitCount(Map.of("firstDecision", 1, "secondDecision", 1))
                    .phaseOutputs(Map.of("firstDecision", Map.of("decision", "review")))
                    .history(List.of(
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "firstDecision",
                                    1,
                                    Map.of("ready", true),
                                    Map.of("action", "review"),
                                    first
                            ),
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "secondDecision",
                                    1,
                                    Map.of("ready", false),
                                    Map.of("action", "review"),
                                    second
                            )
                    ))
                    .sharedState(Map.of("orderId", "exec-session-nodes-1"))
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(first.minusSeconds(5))
                    .lastTouchAt(second)
                    .checkpointedAt(second)
                    .build());

            List<GraphNodeState> nodes = fixture.service.queryInstanceNodes(instance.instanceId());

            assertEquals(List.of("firstDecision", "secondDecision", "approved", "rejected"),
                    nodes.stream().map(GraphNodeState::nodeId).toList());
            assertEquals(GraphNodeStatus.COMPLETED, nodes.get(0).status());
            assertEquals(first, nodes.get(0).startedAt());
            assertEquals(first, nodes.get(0).completedAt());
            assertEquals(GraphNodeStatus.WAITING, nodes.get(1).status());
            assertNull(nodes.get(1).waitType());
            assertEquals(second, nodes.get(1).startedAt());
            assertNull(nodes.get(1).completedAt());
            assertEquals(GraphNodeStatus.NOT_STARTED, nodes.get(2).status());
            assertEquals(GraphNodeStatus.NOT_STARTED, nodes.get(3).status());
        }
    }

    @Test
    void queryInstanceNodesProjectsStateMachineStatesFromCheckpoint() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "state-node-checkpoint",
                    "tenant-a",
                    "sales",
                    "State node checkpoint",
                    null, null, Map.of(), null, null
            ));
            GraphVersion version = publishStateMachine(fixture, definition, "1.0.0", """
                    state_machine orderLifecycle {
                      state draft [initial] {
                        on submit -> review
                      }

                      state review {
                        on approve -> approved
                      }

                      state approved [terminal] { }
                    }
                    """);
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-state-nodes-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.STATE_MACHINE,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant first = Instant.parse("2025-01-01T01:00:00Z");
            new ExecutionCheckpointStateMachineStore(fixture.checkpointStore).save(
                    StateMachineCheckpoint.builder(instance.instanceId(), "orderLifecycle")
                            .currentStateId("review")
                            .status(com.leanowtech.bloge.state.model.StateMachineStatus.WAITING_EVENT)
                            .totalTransitions(1)
                            .stateVisitCount(Map.of("draft", 1, "review", 1))
                            .stateOutputs(Map.of("draft", Map.of("submitted", true)))
                            .sharedContext(Map.of("orderId", "exec-state-nodes-1"))
                            .history(List.of(new com.leanowtech.bloge.state.model.StateExecutionRecord(
                                    "draft",
                                    "submit",
                                    Map.of("submitted", true),
                                    "review",
                                    first
                            )))
                            .startedAt(first.minusSeconds(10))
                            .lastTransitionAt(first)
                            .checkpointedAt(first)
                            .build()
            );

            List<GraphNodeState> nodes = fixture.service.queryInstanceNodes(instance.instanceId());

            assertEquals(List.of("draft", "review", "approved"),
                    nodes.stream().map(GraphNodeState::nodeId).toList());
            assertEquals(GraphNodeStatus.COMPLETED, nodes.get(0).status());
            assertEquals(first, nodes.get(0).startedAt());
            assertEquals(first, nodes.get(0).completedAt());
            assertEquals(GraphNodeStatus.WAITING, nodes.get(1).status());
            assertNull(nodes.get(1).waitType());
            assertEquals(GraphNodeStatus.NOT_STARTED, nodes.get(2).status());
        }
    }

    @Test
    void queryInstanceNodesMapsFailedStateMachineCurrentState() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "state-node-failed",
                    "tenant-a",
                    "sales",
                    "State node failed",
                    null, null, Map.of(), null, null
            ));
            GraphVersion version = publishStateMachine(fixture, definition, "1.0.0", """
                    state_machine failedLifecycle {
                      state draft [initial] {
                        on submit -> review
                      }

                      state review {
                        on approve -> approved
                      }

                      state approved [terminal] { }
                    }
                    """);
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-state-nodes-failed",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.STATE_MACHINE,
                    GraphInstanceStatus.FAILED
            );
            Instant first = Instant.parse("2025-01-01T02:00:00Z");
            new ExecutionCheckpointStateMachineStore(fixture.checkpointStore).save(
                    StateMachineCheckpoint.builder(instance.instanceId(), "failedLifecycle")
                            .currentStateId("review")
                            .status(com.leanowtech.bloge.state.model.StateMachineStatus.FAILED)
                            .totalTransitions(1)
                            .stateVisitCount(Map.of("draft", 1, "review", 1))
                            .stateOutputs(Map.of("draft", Map.of("submitted", true)))
                            .sharedContext(Map.of("orderId", "exec-state-nodes-failed"))
                            .history(List.of(new com.leanowtech.bloge.state.model.StateExecutionRecord(
                                    "draft",
                                    "submit",
                                    Map.of("submitted", true),
                                    "review",
                                    first
                            )))
                            .startedAt(first.minusSeconds(10))
                            .lastTransitionAt(first)
                            .checkpointedAt(first.plusSeconds(5))
                            .build()
            );

            List<GraphNodeState> nodes = fixture.service.queryInstanceNodes(instance.instanceId());

            assertEquals(GraphNodeStatus.FAILED, nodes.get(1).status());
            assertNull(nodes.get(1).waitType());
        }
    }

    @Test
    void queryInstanceNodesMapsTerminatedStateMachineCurrentStateAsCancelled() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "state-node-terminated",
                    "tenant-a",
                    "sales",
                    "State node terminated",
                    null, null, Map.of(), null, null
            ));
            GraphVersion version = publishStateMachine(fixture, definition, "1.0.0", """
                    state_machine terminatedLifecycle {
                      state draft [initial] {
                        on submit -> review
                      }

                      state review {
                        on approve -> approved
                      }

                      state approved [terminal] { }
                    }
                    """);
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-state-nodes-terminated",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.STATE_MACHINE,
                    GraphInstanceStatus.TERMINATED
            );
            Instant first = Instant.parse("2025-01-01T02:30:00Z");
            new ExecutionCheckpointStateMachineStore(fixture.checkpointStore).save(
                    StateMachineCheckpoint.builder(instance.instanceId(), "terminatedLifecycle")
                            .currentStateId("review")
                            .status(com.leanowtech.bloge.state.model.StateMachineStatus.TERMINATED)
                            .totalTransitions(1)
                            .stateVisitCount(Map.of("draft", 1, "review", 1))
                            .stateOutputs(Map.of("draft", Map.of("submitted", true)))
                            .sharedContext(Map.of("orderId", "exec-state-nodes-terminated"))
                            .history(List.of(new com.leanowtech.bloge.state.model.StateExecutionRecord(
                                    "draft",
                                    "submit",
                                    Map.of("submitted", true),
                                    "review",
                                    first
                            )))
                            .startedAt(first.minusSeconds(10))
                            .lastTransitionAt(first)
                            .checkpointedAt(first.plusSeconds(5))
                            .build()
            );

            List<GraphNodeState> nodes = fixture.service.queryInstanceNodes(instance.instanceId());

            assertEquals(GraphNodeStatus.CANCELLED, nodes.get(1).status());
            assertNull(nodes.get(1).waitType());
        }
    }

    @Test
    void getVersionDiagramReturnsStoredLayoutWithoutProjectionOverlay() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diagram-version",
                    "tenant-a",
                    "sales",
                    "Diagram version",
                    null, null, Map.of(), null, null
            ));
            GraphVersion created = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    "graph diagramVersion { node approval : echo { input { orderId = ctx.orderId } } }",
                    "{\"nodes\":[{\"id\":\"approval\"}]}",
                    GraphMigrationPolicy.PIN_VERSION
            ));

            GraphVersionDiagram diagram = fixture.service.getVersionDiagram(created.versionId());

            assertEquals(created.versionId(), diagram.versionId());
            assertEquals("1.0.0", diagram.version());
            assertEquals("{\"nodes\":[{\"id\":\"approval\"}]}", diagram.visualLayout());
        }
    }

    @Test
    void getVersionDiagramGeneratesDefaultLayoutWhenStoredLayoutIsMissing() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diagram-generated",
                    "tenant-a",
                    "sales",
                    "Generated diagram",
                    null, null, Map.of(), null, null
            ));
            GraphVersion created = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph generatedDiagram {
                              node fetch : echo {
                                input { orderId = ctx.orderId }
                              }
                              transform assemble {
                                result = fetch.output
                              }
                            }
                            """,
                    null,
                    GraphMigrationPolicy.PIN_VERSION
            ));

            GraphVersionDiagram diagram = fixture.service.getVersionDiagram(created.versionId());
            VisualLayout layout = (VisualLayout) JsonCodec.DEFAULT.deserialize(diagram.visualLayout());

            assertEquals("bloge.visualLayout.v1", layout.schemaVersion());
            assertEquals("generatedDiagram", layout.rootId());
            assertEquals(GraphExecutionMode.GRAPH, layout.executionMode());
            assertEquals(List.of("fetch", "assemble"), layout.nodes().stream().map(VisualLayout.Node::id).toList());
            assertTrue(layout.edges().stream().anyMatch(edge ->
                    "fetch".equals(edge.source()) && "assemble".equals(edge.target())));
        }
    }

    @Test
    void getVersionDiagramRegeneratesLayoutWhenStoredLayoutMissesCompiledNodes() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diagram-stale",
                    "tenant-a",
                    "sales",
                    "Stale diagram",
                    null, null, Map.of(), null, null
            ));
            GraphVersion created = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph staleDiagram {
                              node first : echo {
                                input { value = ctx.value }
                              }
                              node second : echo {
                                input { value = first.output }
                              }
                            }
                            """,
                    "{\"nodes\":[{\"id\":\"first\"}]}",
                    GraphMigrationPolicy.PIN_VERSION
            ));

            GraphVersionDiagram diagram = fixture.service.getVersionDiagram(created.versionId());
            VisualLayout layout = (VisualLayout) JsonCodec.DEFAULT.deserialize(diagram.visualLayout());

            assertEquals("bloge.visualLayout.v1", layout.schemaVersion());
            assertEquals(List.of("first", "second"), layout.nodes().stream().map(VisualLayout.Node::id).toList());
        }
    }

    @Test
    void getInstanceDiagramCombinesStoredLayoutWithCurrentNodeProjection() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diagram-instance",
                    "tenant-a",
                    "sales",
                    "Diagram instance",
                    null, null, Map.of(), null, null
            ));
            GraphVersion created = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph diagramInstance {
                              node approval : user {
                                input {
                                  title = "Approve"
                                  orderId = ctx.orderId
                                }
                              }
                            }
                            """,
                    "{\"nodes\":[{\"id\":\"approval\"}]}",
                    GraphMigrationPolicy.PIN_VERSION
            ));
            fixture.service.publishVersion(created.versionId(), created.revision());

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    null,
                    "diagram-instance-001",
                    "starter",
                    Map.of("orderId", "diagram-instance-001")
            ));
            fixture.awaitInstanceStatus(started.instance().instanceId(), GraphInstanceStatus.SUSPENDED);

            GraphInstanceDiagram diagram = fixture.service.getInstanceDiagram(started.instance().instanceId());
            List<GraphNodeState> nodes = fixture.service.queryInstanceNodes(started.instance().instanceId());

            assertEquals(started.instance().instanceId(), diagram.instanceId());
            assertEquals(created.versionId(), diagram.versionId());
            assertEquals("{\"nodes\":[{\"id\":\"approval\"}]}", diagram.visualLayout());
            assertEquals(nodes, diagram.nodeStates());
        }
    }

    @Test
    void queryPendingSignalsProjectsWaitingMatchersOptionalFlagsTimeoutsAndSchemas() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "pending-signals",
                    "tenant-a",
                    "sales",
                    "Pending signal flow",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishPendingSignals(fixture, definition, "1.0.0");
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-pending-signals-1",
                    definition,
                    version,
                    GraphInstanceStatus.SUSPENDED
            );
            ExecutionIdentity identity = fixture.identity(instance);
            Instant now = Instant.parse("2025-01-01T00:00:00Z");

            fixture.eventMatcherStore.create(new EventMatcher(
                    "matcher-approval",
                    identity,
                    "approval",
                    "approval.completed",
                    "orderId",
                    "approval-001",
                    false,
                    EventMatcherStatus.WAITING,
                    0,
                    now,
                    now,
                    null
            ));
            fixture.eventMatcherStore.create(new EventMatcher(
                    "matcher-review",
                    identity,
                    "review",
                    "review.completed",
                    "orderId",
                    "approval-001",
                    true,
                    EventMatcherStatus.WAITING,
                    0,
                    now.plusSeconds(5),
                    now.plusSeconds(5),
                    null
            ));
            fixture.eventMatcherStore.create(new EventMatcher(
                    "matcher-matched",
                    identity,
                    "review",
                    "review.completed",
                    "orderId",
                    "approval-001",
                    false,
                    EventMatcherStatus.MATCHED,
                    1,
                    now.plusSeconds(10),
                    now.plusSeconds(20),
                    now.plusSeconds(20)
            ));

            fixture.waitStore.create(new ExecutionWait(
                    "wait-approval",
                    identity,
                    WaitType.WAIT_SIGNAL,
                    "approval",
                    "orderId",
                    now.plusSeconds(300),
                    null,
                    null,
                    null,
                    WaitStatus.WAITING,
                    0,
                    now,
                    now,
                    null
            ));
            fixture.waitStore.create(new ExecutionWait(
                    "wait-review",
                    identity,
                    WaitType.WAIT_EVENT,
                    "review",
                    "orderId",
                    null,
                    null,
                    null,
                    null,
                    WaitStatus.WAITING,
                    0,
                    now.plusSeconds(5),
                    now.plusSeconds(5),
                    null
            ));

            List<GraphPendingSignal> signals = fixture.service.queryPendingSignals(instance.instanceId());

            assertEquals(2, signals.size());
            assertEquals("approval", signals.getFirst().nodeId());
            assertEquals("approval.completed", signals.getFirst().eventName());
            assertEquals("approval-001", signals.getFirst().expectedValue());
            assertFalse(signals.getFirst().optional());
            assertNotNull(signals.getFirst().signalSchema());
            assertEquals(
                    Map.of(
                            "kind", "structured",
                            "fields", List.of(
                                    Map.of(
                                            "name", "approval.completed",
                                            "type", "Object",
                                            "required", false,
                                            "nested", Map.of(
                                                    "kind", "structured",
                                                    "fields", List.of(Map.of(
                                                            "name", "status",
                                                            "type", "String",
                                                            "required", true
                                                    ))
                                            )
                                    )
                            )
                    ),
                    signals.getFirst().signalSchema().toMap()
            );
            assertEquals(now.plusSeconds(300), signals.getFirst().timeoutAt());
            assertEquals("review", signals.get(1).nodeId());
            assertTrue(signals.get(1).optional());
            assertNotNull(signals.get(1).signalSchema());
            assertEquals(
                    Map.of(
                            "kind", "structured",
                            "fields", List.of(
                                    Map.of(
                                            "name", "review.completed",
                                            "type", "Object",
                                            "required", false,
                                            "nested", Map.of(
                                                    "kind", "structured",
                                                    "fields", List.of(Map.of(
                                                            "name", "status",
                                                            "type", "String",
                                                            "required", true
                                                    ))
                                            )
                                    )
                            )
                    ),
                    signals.get(1).signalSchema().toMap()
            );
            assertNull(signals.get(1).timeoutAt());
        }
    }

    @Test
    void queryPendingSignalsCapsMatcherQuerySize() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "pending-signals-cap",
                    "tenant-a",
                    "sales",
                    "Pending signal cap",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishPendingSignals(fixture, definition, "1.0.0");
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-pending-cap-1",
                    definition,
                    version,
                    GraphInstanceStatus.SUSPENDED
            );

            assertEquals(List.of(), fixture.service.queryPendingSignals(instance.instanceId()));
            assertNotNull(fixture.eventMatcherStore.lastQuery());
            assertEquals(10_000, fixture.eventMatcherStore.lastQuery().size());
        }
    }

    @Test
    void queryPendingSignalsReturnsNullSchemaWhenVersionIsUnavailable() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "pending-signals-missing",
                    "tenant-a",
                    "sales",
                    "Pending signal missing version",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-pending-missing-1",
                    definition,
                    "missing-version",
                    GraphExecutionMode.GRAPH,
                    GraphInstanceStatus.SUSPENDED
            );
            ExecutionIdentity identity = fixture.identity(instance);
            Instant now = Instant.parse("2025-01-01T01:00:00Z");

            fixture.eventMatcherStore.create(new EventMatcher(
                    "matcher-missing",
                    identity,
                    "approval",
                    "approval.completed",
                    "orderId",
                    "approval-002",
                    false,
                    EventMatcherStatus.WAITING,
                    0,
                    now,
                    now,
                    null
            ));
            fixture.waitStore.create(new ExecutionWait(
                    "wait-missing",
                    identity,
                    WaitType.WAIT_SIGNAL,
                    "approval",
                    "orderId",
                    now.plusSeconds(60),
                    null,
                    null,
                    null,
                    WaitStatus.WAITING,
                    0,
                    now,
                    now,
                    null
            ));

            GraphPendingSignal signal = fixture.service.queryPendingSignals(instance.instanceId()).getFirst();

            assertNull(signal.signalSchema());
            assertEquals(now.plusSeconds(60), signal.timeoutAt());
        }
    }

    @Test
    void queryPendingSignalsRejectsSessionInstances() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "pending-signals-session",
                    "tenant-a",
                    "sales",
                    "Pending signal session",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishPendingSignals(fixture, definition, "1.0.0");
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-pending-session-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );

            GraphEngineServiceException error = org.junit.jupiter.api.Assertions.assertThrows(
                    GraphEngineServiceException.class,
                    () -> fixture.service.queryPendingSignals(instance.instanceId())
            );

            assertEquals(GraphEngineServiceErrorCode.UNSUPPORTED_EXECUTION_MODE, error.errorCode());
        }
    }

    @Test
    void queryPendingSignalsRejectsStateMachineInstances() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "pending-signals-state",
                    "tenant-a",
                    "sales",
                    "Pending signal state machine",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishPendingSignals(fixture, definition, "1.0.0");
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-pending-state-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.STATE_MACHINE,
                    GraphInstanceStatus.SUSPENDED
            );

            GraphEngineServiceException error = org.junit.jupiter.api.Assertions.assertThrows(
                    GraphEngineServiceException.class,
                    () -> fixture.service.queryPendingSignals(instance.instanceId())
            );

            assertEquals(GraphEngineServiceErrorCode.UNSUPPORTED_EXECUTION_MODE, error.errorCode());
        }
    }

    @Test
    void getInstanceContextReturnsGraphStartVariablesAndDecodedNodeOutputs() {
        try (Fixture fixture = new Fixture(false)) {
            GraphInstance instance = fixture.createManagedGraphInstance("exec-context-graph-1", GraphInstanceStatus.SUSPENDED);
            Instant now = Instant.now();
            fixture.checkpointStore.save(ExecutionCheckpoint.builder(
                            fixture.identity(instance),
                            CheckpointType.NODE_OUTPUT,
                            "approval")
                    .payload(TEST_CODEC.serialize(Map.of("approved", true, "orderId", "A-1")))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            GraphInstanceContext context = fixture.service.getInstanceContext(instance.instanceId());

            assertEquals(GraphExecutionMode.GRAPH, context.executionMode());
            assertEquals(Map.of("orderId", "A-1"), context.startVariables());
            assertEquals(Map.of("approved", true, "orderId", "A-1"), context.nodeOutputs().get("approval"));
            assertEquals(Map.of(), context.sharedState());
            assertEquals(Map.of(), context.phaseOutputs());
            assertEquals(Map.of(), context.stateOutputs());
            assertNotNull(context.snapshotAt());
        }
    }

    @Test
    void getInstanceContextSkipsGraphNodeOutputsThatCannotBeDecoded() {
        try (Fixture fixture = new Fixture(false)) {
            GraphInstance instance = fixture.createManagedGraphInstance("exec-context-graph-2", GraphInstanceStatus.SUSPENDED);
            Instant now = Instant.now();
            fixture.checkpointStore.save(ExecutionCheckpoint.builder(
                            fixture.identity(instance),
                            CheckpointType.NODE_OUTPUT,
                            "good")
                    .payload(TEST_CODEC.serialize(Map.of("approved", true)))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            fixture.checkpointStore.save(ExecutionCheckpoint.builder(
                            fixture.identity(instance),
                            CheckpointType.NODE_OUTPUT,
                            "bad")
                    .payload("{broken-json")
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            GraphInstanceContext context = fixture.service.getInstanceContext(instance.instanceId());

            assertEquals(Map.of("good", Map.of("approved", true)), context.nodeOutputs());
        }
    }

    @Test
    void getInstanceContextReturnsSessionSharedStateAndPhaseOutputs() {
        try (Fixture fixture = new Fixture(false)) {
            fixture.createManagedGraphInstance("seed-context-session", GraphInstanceStatus.SUSPENDED);
            GraphDefinition definition = fixture.graphDefinitionStore.getByKey("default", "default", "approval-flow")
                    .orElseThrow();
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-context-session-1",
                    definition,
                    "ver-1",
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant now = Instant.now();
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("support-flow")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session")
                    .currentPhaseId("review")
                    .currentPhaseRound(2)
                    .totalRounds(3)
                    .phaseVisitCount(Map.of("review", 3))
                    .phaseOutputs(Map.of("review", Map.of("decision", "approve")))
                    .history(List.of(new com.leanowtech.bloge.ext.model.RoundRecord(
                            "review",
                            1,
                            Map.of("payload", "input"),
                            Map.of("decision", "approve"),
                            now
                    )))
                    .sharedState(Map.of("ticketId", "T-1", "approved", true))
                    .contextData(Map.of("orderId", "A-1"))
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(now.minusSeconds(30))
                    .lastTouchAt(now)
                    .checkpointedAt(now)
                    .build());

            GraphInstanceContext context = fixture.service.getInstanceContext(instance.instanceId());

            assertEquals(GraphExecutionMode.SESSION, context.executionMode());
            assertEquals(Map.of("orderId", "A-1"), context.startVariables());
            assertEquals(Map.of("ticketId", "T-1", "approved", true), context.sharedState());
            assertEquals(Map.of("review", Map.of("decision", "approve")), context.phaseOutputs());
            assertEquals(Map.of(), context.nodeOutputs());
            assertEquals(Map.of(), context.stateOutputs());
        }
    }

    @Test
    void getInstanceContextReturnsStateMachineSharedStateAndStateOutputs() {
        try (Fixture fixture = new Fixture(false)) {
            fixture.createManagedGraphInstance("seed-context-state", GraphInstanceStatus.SUSPENDED);
            GraphDefinition definition = fixture.graphDefinitionStore.getByKey("default", "default", "approval-flow")
                    .orElseThrow();
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-context-state-1",
                    definition,
                    "ver-1",
                    GraphExecutionMode.STATE_MACHINE,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant now = Instant.now();
            new ExecutionCheckpointStateMachineStore(fixture.checkpointStore).save(
                    StateMachineCheckpoint.builder(instance.instanceId(), "orderLifecycle")
                            .currentStateId("review")
                            .status(com.leanowtech.bloge.state.model.StateMachineStatus.WAITING_EVENT)
                            .totalTransitions(2)
                            .stateVisitCount(Map.of("review", 1))
                            .stateOutputs(Map.of("review", Map.of("decision", "approve")))
                            .sharedContext(Map.of("orderId", "A-1", "region", "emea"))
                            .history(List.of(new com.leanowtech.bloge.state.model.StateExecutionRecord(
                                    "draft",
                                    "submit",
                                    Map.of("decision", "approve"),
                                    "review",
                                    now
                            )))
                            .startedAt(now.minusSeconds(45))
                            .lastTransitionAt(now)
                            .checkpointedAt(now)
                            .build()
            );

            GraphInstanceContext context = fixture.service.getInstanceContext(instance.instanceId());

            assertEquals(GraphExecutionMode.STATE_MACHINE, context.executionMode());
            assertEquals(Map.of("orderId", "A-1"), context.startVariables());
            assertEquals(Map.of("orderId", "A-1", "region", "emea"), context.sharedState());
            assertEquals(Map.of("review", Map.of("decision", "approve")), context.stateOutputs());
            assertEquals(Map.of(), context.nodeOutputs());
            assertEquals(Map.of(), context.phaseOutputs());
        }
    }

    @Test
    void getInstanceContextRejectsMissingGraphCheckpointRuntime() {
        try (Fixture fixture = new Fixture(false)) {
            GraphInstance instance = fixture.createManagedGraphInstance("exec-context-graph-missing", GraphInstanceStatus.SUSPENDED);
            DefaultGraphEngineService service = new DefaultGraphEngineService(
                    new GraphEngineStores(
                            fixture.graphDefinitionStore,
                            fixture.graphVersionStore,
                            fixture.graphDeploymentStore,
                            fixture.graphInstanceStore
                    ),
                    GraphEngineRuntimeSupport.builder()
                            .executionStore(fixture.executionStore)
                            .build()
            );
            try {
                GraphEngineServiceException error = assertThrows(
                        GraphEngineServiceException.class,
                        () -> service.getInstanceContext(instance.instanceId())
                );
                assertEquals(GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE, error.errorCode());
            } finally {
                service.close();
            }
        }
    }

    @Test
    void queryPendingSignalsRequiresWaitAndMatcherStores() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "pending-signals-runtime",
                    "tenant-a",
                    "sales",
                    "Pending signal runtime",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishPendingSignals(fixture, definition, "1.0.0");
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-pending-runtime-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.GRAPH,
                    GraphInstanceStatus.SUSPENDED
            );

            DefaultGraphEngineService service = new DefaultGraphEngineService(
                    new GraphEngineStores(
                            fixture.graphDefinitionStore,
                            fixture.graphVersionStore,
                            fixture.graphDeploymentStore,
                            fixture.graphInstanceStore
                    ),
                    GraphEngineRuntimeSupport.builder()
                            .operatorRegistry(fixture.registry)
                            .executionStore(fixture.executionStore)
                            .executionCheckpointStore(fixture.checkpointStore)
                            .build()
            );
            try {
                GraphEngineServiceException error = org.junit.jupiter.api.Assertions.assertThrows(
                        GraphEngineServiceException.class,
                        () -> service.queryPendingSignals(instance.instanceId())
                );
                assertEquals(GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE, error.errorCode());
            } finally {
                service.close();
            }
        }
    }

    @Test
    void signalInstanceCompletesSuspendedExecutionAndRecordsCompletionMetric() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "approval-signal",
                    "tenant-a",
                    "sales",
                    "Approval signal",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph approvalSignal {
                              node wait : suspend {}

                              node done : echo {
                                depends_on = [wait]
                                input {
                                  status = wait.output.status
                                }
                              }
                            }
                            """,
                    null,
                    GraphMigrationPolicy.PIN_VERSION
            ));
            fixture.service.publishVersion(version.versionId(), version.revision());

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    null,
                    "approval-signal-001",
                    "starter",
                    Map.of()
            ));

            assertEquals(GraphInstanceStatus.SUSPENDED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.SUSPENDED
            ));

            SignalInstanceResult signaled = fixture.service.signalInstance(new SignalInstanceCommand(
                    started.instance().instanceId(),
                    "wait",
                    "approval.completed",
                    Map.of(
                            "status", "approved"
                    ),
                    "gateway"
            ));

            assertEquals(GraphInstanceStatus.COMPLETED, signaled.instance().status());
            assertEquals(GraphInstanceStatus.COMPLETED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.COMPLETED
            ));
            assertEquals(List.of("COMPLETED"), fixture.metricsObserver.instanceCompletedStatuses);
        }
    }

    @Test
    void deprecateVersionMarksPublishedVersionDeprecated() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "deprecation-flow",
                    "tenant-a",
                    "sales",
                    "Deprecation flow",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));

            GraphVersion published = publish(fixture, definition, "1.0.0");
            GraphVersion deprecated = fixture.service.deprecateVersion(published.versionId(), published.revision());

            assertEquals(GraphVersionStatus.DEPRECATED, deprecated.status());
            assertEquals(GraphVersionStatus.DEPRECATED, fixture.service.getVersion(published.versionId()).status());
        }
    }

    @Test
    void cancelInstanceNeutralizesTimersWaitsTasksAndWorkItems() {
        try (Fixture fixture = new Fixture(false)) {
            Instant now = Instant.now();
            GraphInstance instance = fixture.createManagedGraphInstance("exec-cancel-1", GraphInstanceStatus.SUSPENDED);
            ExecutionIdentity identity = fixture.identity(instance);

            fixture.taskInboxStore.create(new TaskInbox(
                    "task-cancel-1",
                    identity,
                    "approval",
                    "USER_TASK",
                    null,
                    List.of("alice"),
                    List.of("ops"),
                    List.of(),
                    "Approve order",
                    "Review order",
                    Map.of("orderId", "A-1"),
                    now.plusSeconds(300),
                    5,
                    TaskInboxStatus.OPEN,
                    0,
                    now,
                    now,
                    null
            ));
            fixture.workItemStore.create(WorkItem.builder()
                    .itemId("work-cancel-1")
                    .executionIdentity(identity)
                    .itemType(WorkItemType.EVENT_MATCHED)
                    .nodeId("approval")
                    .waitId("wait-cancel-1")
                    .priority(5)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            fixture.waitStore.create(new ExecutionWait(
                    "wait-cancel-1",
                    identity,
                    WaitType.WAIT_SIGNAL,
                    "approval",
                    "approval-signal",
                    now.plusSeconds(300),
                    null,
                    null,
                    null,
                    WaitStatus.WAITING,
                    0,
                    now,
                    now,
                    null
            ));
            fixture.timerService.scheduleTimer(new TimerSpec(
                    "timer-cancel-1",
                    instance.instanceId(),
                    "approval",
                    TimerType.DELAY,
                    Duration.ofMinutes(5),
                    null,
                    null,
                    now.plus(Duration.ofMinutes(5)),
                    "approval-timeout",
                    null,
                    now,
                    TimerStatus.ACTIVE
            ));

            GraphInstance cancelled = fixture.service.cancelInstance(instance.instanceId(), "duplicate order", instance.revision());

            assertEquals(GraphInstanceStatus.CANCELLED, cancelled.status());
            assertEquals(ExecutionStatus.CANCELLED, fixture.executionStore.get(instance.instanceId()).orElseThrow().status());
            assertEquals(TaskInboxStatus.CANCELLED, fixture.taskInboxStore.get("task-cancel-1").orElseThrow().status());
            assertEquals(WorkItemStatus.CANCELLED, fixture.workItemStore.query(new WorkItemQuery(
                    instance.instanceId(),
                    WorkItemType.EVENT_MATCHED,
                    Set.of(WorkItemStatus.CANCELLED),
                    "shard-a",
                    null,
                    null,
                    null,
                    0,
                    10
            )).getFirst().status());
            assertTrue(fixture.waitStore.findByExecution(instance.instanceId()).isEmpty());
            assertTrue(fixture.timerService.loadActiveTimers(instance.instanceId()).isEmpty());
        }
    }

    @Test
    void getTaskProjectsCandidateUsersFromTaskInbox() {
        try (Fixture fixture = new Fixture(false)) {
            Instant now = Instant.now();
            GraphInstance instance = fixture.createManagedGraphInstance("exec-task-users-1", GraphInstanceStatus.SUSPENDED);
            ExecutionIdentity identity = fixture.identity(instance);

            fixture.taskInboxStore.create(new TaskInbox(
                    "task-users-1",
                    identity,
                    "approval",
                    "USER_TASK",
                    null,
                    List.of("alice", "bob"),
                    List.of("ops"),
                    List.of("reviewer"),
                    "Approve order",
                    "Review order",
                    Map.of("orderId", "A-1"),
                    now.plusSeconds(300),
                    5,
                    TaskInboxStatus.OPEN,
                    0,
                    now,
                    now,
                    null
            ));

            GraphTask task = fixture.service.getTask("task-users-1");

            assertEquals(List.of("alice", "bob"), task.candidateUsers());
            assertEquals(List.of("ops"), task.candidateGroups());
            assertEquals(List.of("reviewer"), task.candidateRoles());
        }
    }

    @Test
    void queryGovernanceViewsAndRetryDeadLetter() {
        try (Fixture fixture = new Fixture(false)) {
            Instant now = Instant.now();
            GraphInstance instance = fixture.createManagedGraphInstance("exec-govern-1", GraphInstanceStatus.RUNNING);
            ExecutionIdentity identity = fixture.identity(instance);

            fixture.auditJournalStore.append(new AuditEntry(
                    instance.instanceId(),
                    instance.definitionKey(),
                    "approval",
                    "user-task",
                    AuditEventType.NODE_FAILED,
                    "{\"orderId\":\"A-1\"}",
                    null,
                    "boom",
                    2,
                    Duration.ofSeconds(3),
                    now
            ));
            fixture.controlPlaneService.transitions.add(new ExecutionTransitionLogEntry(
                    "transition-1",
                    identity,
                    ExecutionStatus.RUNNING,
                    ExecutionStatus.CANCELLED,
                    0,
                    1,
                    "graph-engine-service",
                    "duplicate order",
                    now
            ));

            fixture.workItemStore.create(WorkItem.builder()
                    .itemId("dead-1")
                    .executionIdentity(identity)
                    .itemType(WorkItemType.EVENT_MATCHED)
                    .nodeId("approval")
                    .waitId("wait-dead-1")
                    .priority(5)
                    .nextAttemptAt(now.minusSeconds(30))
                    .payload("{\"orderId\":\"A-1\"}")
                    .lastError("boom")
                    .createdAt(now.minusSeconds(30))
                    .updatedAt(now.minusSeconds(30))
                    .build());
            fixture.workItemStore.markDeadLetter("dead-1", "manual intervention");
            fixture.controlPlaneService.deadLetters.add(new DeadLetterEntry(
                    "dead-1",
                    identity,
                    WorkItemType.EVENT_MATCHED,
                    "approval",
                    "wait-dead-1",
                    null,
                    5,
                    0,
                    3,
                    "{\"orderId\":\"A-1\"}",
                    null,
                    "boom",
                    "manual intervention",
                    now.minusSeconds(30),
                    now
            ));

            List<GraphAuditEntry> auditLog = fixture.service.queryInstanceAuditLog(instance.instanceId(), 0, 50);
            List<GraphTransitionEntry> transitions = fixture.service.queryInstanceTransitions(instance.instanceId(), 0, 50);
            List<GraphDeadLetter> deadLetters = fixture.service.queryDeadLetters(new GraphDeadLetterQuery(
                    null,
                    null,
                    null,
                    instance.instanceId(),
                    WorkItemType.EVENT_MATCHED,
                    "shard-a",
                    null,
                    0,
                    50
            ));

            assertEquals("approval", auditLog.getFirst().nodeId());
            assertEquals(GraphInstanceStatus.CANCELLED, transitions.getFirst().toStatus());
            assertEquals(instance.versionId(), deadLetters.getFirst().versionId());

            RecoveryActionEvidence evidence = new RecoveryActionEvidence(
                    "validated idempotent replay",
                    "RETRY_DEAD_LETTER",
                    "DEAD_LETTER_BACKLOG",
                    "ops-alice",
                    "INC-123"
            );
            RetryDeadLetterResult firstRetry = fixture.service.retryDeadLetterWithResult("dead-1", evidence);

            assertFalse(firstRetry.idempotentReplay());
            assertEquals(GraphControlActionEntry.AttemptStatus.SUCCEEDED, firstRetry.attemptStatus());
            assertEquals("RESTORED", firstRetry.status());
            assertEquals("INC-123", firstRetry.requestId());
            assertEquals(1, firstRetry.retriedItemCount());

            assertEquals(WorkItemStatus.READY, fixture.workItemStore.query(new WorkItemQuery(
                    instance.instanceId(),
                    WorkItemType.EVENT_MATCHED,
                    Set.of(WorkItemStatus.READY),
                    "shard-a",
                    null,
                    null,
                    null,
                    0,
                    10
            )).getFirst().status());
            assertTrue(fixture.workItemStore.query(new WorkItemQuery(
                    instance.instanceId(),
                    WorkItemType.EVENT_MATCHED,
                    Set.of(WorkItemStatus.DEAD_LETTER),
                    "shard-a",
                    null,
                    null,
                    null,
                    0,
                    10
            )).isEmpty());
            List<GraphAuditEntry> recoveryAudits = controlActionAudits(fixture, instance.instanceId());
            assertEquals(2, recoveryAudits.size());
            GraphAuditEntry attemptAudit = controlActionWithAttemptStatus(
                    recoveryAudits,
                    "ATTEMPTED"
            );
            GraphAuditEntry successAudit = controlActionWithAttemptStatus(
                    recoveryAudits,
                    "SUCCEEDED"
            );
            assertEquals("__control_retry_dead_letter__", attemptAudit.nodeId());
            assertEquals("__control_retry_dead_letter__", successAudit.nodeId());
            assertTrue(successAudit.inputJson().contains("validated idempotent replay"));
            assertTrue(successAudit.inputJson().contains("RETRY_DEAD_LETTER"));
            assertTrue(successAudit.inputJson().contains("INC-123"));
            assertTrue(attemptAudit.outputJson().contains("\"candidateItemCount\":1"));
            assertTrue(successAudit.outputJson().contains("\"status\":\"RESTORED\""));
            assertTrue(successAudit.outputJson().contains("\"restoredItemCount\":1"));

            List<GraphControlActionEntry> controlActions =
                    fixture.service.queryInstanceControlActions(instance.instanceId(), 0, 50);
            assertEquals(2, controlActions.size());
            assertEquals(
                    List.of(
                            GraphControlActionEntry.AttemptStatus.ATTEMPTED,
                            GraphControlActionEntry.AttemptStatus.SUCCEEDED
                    ),
                    controlActions.stream().map(GraphControlActionEntry::attemptStatus).toList()
            );
            GraphControlActionEntry succeeded = controlActions.get(1);
            assertEquals("RETRY_DEAD_LETTER", succeeded.actionCode());
            assertEquals("DEAD_LETTER_BACKLOG", succeeded.sourceIndicatorCode());
            assertEquals("ops-alice", succeeded.actor());
            assertEquals("INC-123", succeeded.requestId());
            assertEquals("dead-1", succeeded.itemId());
            assertEquals("approval", succeeded.targetNodeId());
            assertEquals(1, succeeded.restoredItemCount());
            assertEquals(List.of("dead-1"), succeeded.restoredItemIds());

            RetryDeadLetterResult replayed = fixture.service.retryDeadLetterWithResult("dead-1", evidence);

            assertTrue(replayed.idempotentReplay());
            assertEquals(GraphControlActionEntry.AttemptStatus.SUCCEEDED, replayed.attemptStatus());
            assertEquals("RESTORED", replayed.status());
            assertEquals("INC-123", replayed.requestId());
            assertEquals(1, replayed.retriedItemCount());
            assertEquals(2, controlActionAudits(fixture, instance.instanceId()).size());
        }
    }

    @Test
    void retryDeadLetterRecordsFailedControlActionWhenRestoreFails() {
        try (Fixture fixture = new Fixture(false)) {
            Instant now = Instant.now();
            GraphInstance instance = fixture.createManagedGraphInstance("exec-dead-letter-failure", GraphInstanceStatus.RUNNING);
            ExecutionIdentity identity = fixture.identity(instance);
            fixture.workItemStore.create(WorkItem.builder()
                    .itemId("dead-fail")
                    .executionIdentity(identity)
                    .itemType(WorkItemType.EVENT_MATCHED)
                    .nodeId("approval")
                    .waitId("wait-dead-fail")
                    .priority(5)
                    .nextAttemptAt(now.minusSeconds(30))
                    .payload("{\"orderId\":\"A-1\"}")
                    .lastError("boom")
                    .createdAt(now.minusSeconds(30))
                    .updatedAt(now.minusSeconds(30))
                    .build());
            fixture.workItemStore.markDeadLetter("dead-fail", "manual intervention");
            fixture.controlPlaneService.deadLetters.add(new DeadLetterEntry(
                    "dead-fail",
                    identity,
                    WorkItemType.EVENT_MATCHED,
                    "approval",
                    "wait-dead-fail",
                    null,
                    5,
                    0,
                    3,
                    "{\"orderId\":\"A-1\"}",
                    null,
                    "boom",
                    "manual intervention",
                    now.minusSeconds(30),
                    now
            ));
            fixture.workItemStore.failRestore("dead-fail", new IllegalStateException("restore store unavailable"));

            assertThrows(IllegalStateException.class, () -> fixture.service.retryDeadLetter(
                    "dead-fail",
                    new RecoveryActionEvidence(
                            "operator confirmed replay",
                            "RETRY_DEAD_LETTER",
                            "DEAD_LETTER_OLDEST_AGE",
                            "ops-alice",
                            "REQ-FAIL-1"
                    )
            ));

            List<GraphAuditEntry> recoveryAudits = controlActionAudits(fixture, instance.instanceId());
            assertEquals(2, recoveryAudits.size());
            GraphAuditEntry attemptAudit = controlActionWithAttemptStatus(recoveryAudits, "ATTEMPTED");
            GraphAuditEntry failureAudit = controlActionWithAttemptStatus(recoveryAudits, "FAILED");
            assertEquals("__control_retry_dead_letter__", attemptAudit.nodeId());
            assertEquals("__control_retry_dead_letter__", failureAudit.nodeId());
            assertTrue(failureAudit.inputJson().contains("REQ-FAIL-1"));
            assertTrue(failureAudit.outputJson().contains("\"failurePhase\":\"RESTORE\""));
            assertTrue(failureAudit.outputJson().contains("java.lang.IllegalStateException"));
            assertTrue(failureAudit.outputJson().contains("restore store unavailable"));
            assertTrue(failureAudit.outputJson().contains("\"restoredItemCount\":0"));

            List<GraphControlActionEntry> controlActions =
                    fixture.service.queryInstanceControlActions(instance.instanceId(), 0, 50);
            assertEquals(2, controlActions.size());
            GraphControlActionEntry failed = controlActions.get(1);
            assertEquals(GraphControlActionEntry.AttemptStatus.FAILED, failed.attemptStatus());
            assertEquals("REQ-FAIL-1", failed.requestId());
            assertEquals("RESTORE", failed.failurePhase());
            assertEquals(IllegalStateException.class.getName(), failed.failureClass());
            assertEquals("restore store unavailable", failed.failureMessage());
            assertEquals(0, failed.restoredItemCount());

            RetryDeadLetterResult replayed = fixture.service.retryDeadLetterWithResult(
                    "dead-fail",
                    new RecoveryActionEvidence(
                            "operator confirmed replay",
                            "RETRY_DEAD_LETTER",
                            "DEAD_LETTER_OLDEST_AGE",
                            "ops-alice",
                            "REQ-FAIL-1"
                    )
            );

            assertTrue(replayed.idempotentReplay());
            assertEquals(GraphControlActionEntry.AttemptStatus.FAILED, replayed.attemptStatus());
            assertEquals("FAILED", replayed.status());
            assertEquals("REQ-FAIL-1", replayed.requestId());
            assertEquals("RESTORE", replayed.failurePhase());
            assertEquals(IllegalStateException.class.getName(), replayed.failureClass());
            assertEquals("restore store unavailable", replayed.failureMessage());
            assertEquals(0, replayed.retriedItemCount());
            assertEquals(2, controlActionAudits(fixture, instance.instanceId()).size());
        }
    }

    @Test
    void queryInstanceControlActionsIgnoresNodeAuditAndToleratesMalformedPayloads() {
        try (Fixture fixture = new Fixture(false)) {
            Instant now = Instant.now();
            GraphInstance instance = fixture.createManagedGraphInstance("exec-control-actions-malformed", GraphInstanceStatus.RUNNING);
            fixture.auditJournalStore.append(AuditEntry.builder(
                            instance.instanceId(),
                            instance.definitionKey(),
                            "business-node",
                            AuditEventType.NODE_COMPLETE)
                    .operatorRef("echo")
                    .inputJson("{\"ok\":true}")
                    .outputJson("{\"ok\":true}")
                    .timestamp(now)
                    .build());
            fixture.auditJournalStore.append(AuditEntry.builder(
                            instance.instanceId(),
                            instance.definitionKey(),
                            "__control_retry_dead_letter__",
                            AuditEventType.CONTROL_ACTION)
                    .operatorRef("graph-engine-service")
                    .inputJson("{not-json")
                    .outputJson("{also-not-json")
                    .timestamp(now.plusSeconds(1))
                    .build());

            List<GraphControlActionEntry> controlActions =
                    fixture.service.queryInstanceControlActions(instance.instanceId(), 0, 50);

            assertEquals(1, controlActions.size());
            GraphControlActionEntry entry = controlActions.getFirst();
            assertEquals("__control_retry_dead_letter__", entry.nodeId());
            assertEquals(GraphControlActionEntry.AttemptStatus.UNKNOWN, entry.attemptStatus());
            assertNull(entry.actionCode());
            assertEquals("{not-json", entry.rawInputJson());
            assertEquals("{also-not-json", entry.rawOutputJson());
        }
    }

    @Test
    void operationsSnapshotIsOkForEmptyScope() {
        try (Fixture fixture = new Fixture()) {
            GraphOperationsSnapshot snapshot = fixture.service.queryOperationsSnapshot("default", "default");

            assertEquals(GraphOperationsSnapshot.Health.OK, snapshot.health());
            assertEquals(0, snapshot.sampledInstanceCount());
            assertEquals(0, snapshot.deadLetterCount());
            assertTrue(snapshot.actionItems().isEmpty());
            assertFalse(snapshot.truncated());
            assertTrue(snapshot.sloIndicators().stream()
                    .anyMatch(indicator -> indicator.code().equals("DEAD_LETTER_BACKLOG")
                            && indicator.health() == GraphOperationsSnapshot.Health.OK
                            && indicator.metricName().equals("ge.operations.dead_letters")));
            assertEquals(1, fixture.metricsObserver.operationsSnapshotCount);
            assertEquals(List.of("OK"), fixture.metricsObserver.operationsSnapshotHealthes);
            assertEquals(0, fixture.metricsObserver.lastOperationsDeadLetterCount);
            assertEquals(0, fixture.metricsObserver.lastOperationsFailedInstanceCount);
            assertEquals(0, fixture.metricsObserver.lastOperationsSuspendedInstanceCount);
        }
    }

    @Test
    void operationsSnapshotMarksDeadLettersCriticalWithRecoveryAction() {
        try (Fixture fixture = new Fixture()) {
            GraphInstance instance = fixture.createManagedGraphInstance("exec-ops-dead", GraphInstanceStatus.SUSPENDED);
            Instant now = Instant.now();
            fixture.controlPlaneService.deadLetters.add(new DeadLetterEntry(
                    "dead-ops-1",
                    fixture.identity(instance),
                    WorkItemType.EVENT_MATCHED,
                    "approval",
                    "wait-ops-1",
                    null,
                    5,
                    3,
                    3,
                    "{\"orderId\":\"A-1\"}",
                    null,
                    "retry budget exhausted",
                    "manual intervention",
                    now.minusSeconds(30),
                    now
            ));

            GraphOperationsSnapshot snapshot = fixture.service.queryOperationsSnapshot("default", "default");

            assertEquals(GraphOperationsSnapshot.Health.CRITICAL, snapshot.health());
            assertEquals(1, snapshot.deadLetterCount());
            assertEquals("dead-ops-1", snapshot.recentDeadLetters().getFirst().itemId());
            assertTrue(snapshot.actionItems().stream()
                    .anyMatch(item -> item.code().equals("DEAD_LETTERS_PRESENT")
                            && item.severity() == GraphOperationsSnapshot.Health.CRITICAL));
            GraphOperationsSnapshot.ActionItem deadLetterAction = snapshot.actionItems().stream()
                    .filter(item -> item.code().equals("DEAD_LETTERS_PRESENT"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("GE-RUNBOOK-DEAD-LETTER-REPLAY", deadLetterAction.runbookCode());
            assertEquals("runbook://graph-engine/dead-letter-replay", deadLetterAction.runbookHref());
            assertTrue(deadLetterAction.recoveryActions().stream()
                    .anyMatch(action -> action.code().equals("RETRY_DEAD_LETTER")
                            && action.method().equals("POST")
                            && action.apiHref().equals("/api/v1/dead-letters/{itemId}/retry")
                            && action.riskLevel() == GraphOperationsSnapshot.RiskLevel.MEDIUM
                            && action.requiresReason()));
            assertTrue(snapshot.sloIndicators().stream()
                    .anyMatch(indicator -> indicator.code().equals("DEAD_LETTER_BACKLOG")
                            && indicator.health() == GraphOperationsSnapshot.Health.CRITICAL
                            && indicator.metricName().equals("ge.operations.dead_letters")
                            && indicator.criticalThreshold().equals(1.0)
                            && indicator.actionCode().equals("DEAD_LETTERS_PRESENT")));
            assertEquals(List.of("CRITICAL"), fixture.metricsObserver.operationsSnapshotHealthes);
            assertEquals(1, fixture.metricsObserver.lastOperationsDeadLetterCount);
        }
    }

    @Test
    void operationsSnapshotMarksSuspendedInstancesWarning() {
        try (Fixture fixture = new Fixture()) {
            fixture.createManagedGraphInstance("exec-ops-waiting", GraphInstanceStatus.SUSPENDED);

            GraphOperationsSnapshot snapshot = fixture.service.queryOperationsSnapshot("default", "default");

            assertEquals(GraphOperationsSnapshot.Health.WARNING, snapshot.health());
            assertEquals(1, snapshot.instancesByStatus().get(GraphInstanceStatus.SUSPENDED));
            assertEquals(1, snapshot.activeInstanceCount());
            assertTrue(snapshot.actionItems().stream()
                    .anyMatch(item -> item.code().equals("SUSPENDED_INSTANCES_PRESENT")
                            && item.severity() == GraphOperationsSnapshot.Health.WARNING));
            GraphOperationsSnapshot.ActionItem suspendedAction = snapshot.actionItems().stream()
                    .filter(item -> item.code().equals("SUSPENDED_INSTANCES_PRESENT"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("GE-RUNBOOK-SUSPENDED-INSTANCE-TRIAGE", suspendedAction.runbookCode());
            assertTrue(suspendedAction.recoveryActions().stream()
                    .anyMatch(action -> action.code().equals("CHECK_PENDING_SIGNALS")
                            && action.method().equals("GET")
                            && action.apiHref().equals("/api/v1/instances/{instanceId}/pending-signals")
                            && action.riskLevel() == GraphOperationsSnapshot.RiskLevel.LOW));
            assertTrue(snapshot.sloIndicators().stream()
                    .anyMatch(indicator -> indicator.code().equals("SUSPENDED_INSTANCE_BACKLOG")
                            && indicator.health() == GraphOperationsSnapshot.Health.WARNING
                            && indicator.metricName().equals("ge.operations.suspended_instances")
                            && indicator.warningThreshold().equals(1.0)
                            && indicator.actionCode().equals("SUSPENDED_INSTANCES_PRESENT")));
            assertEquals(1, fixture.metricsObserver.lastOperationsSuspendedInstanceCount);
        }
    }

    @Test
    void operationsSnapshotReportsAgeBasedSloIndicatorsAndMetrics() {
        try (Fixture fixture = new Fixture()) {
            fixture.createManagedGraphInstance("seed-ops-age", GraphInstanceStatus.RUNNING);
            Instant staleSuspendedAt = Instant.now().minusSeconds(8_100);
            GraphInstance staleSuspended = new GraphInstance(
                    "exec-ops-stale",
                    "approval-flow",
                    "ver-1",
                    "default",
                    "default",
                    "business-exec-ops-stale",
                    GraphExecutionMode.GRAPH,
                    GraphInstanceStatus.SUSPENDED,
                    "starter",
                    Map.of("orderId", "A-1"),
                    0,
                    staleSuspendedAt.minusSeconds(60),
                    staleSuspendedAt,
                    null
            );
            fixture.graphInstanceStore.create(staleSuspended);
            Instant oldDeadLetteredAt = Instant.now().minusSeconds(1_900);
            fixture.controlPlaneService.deadLetters.add(new DeadLetterEntry(
                    "dead-ops-old",
                    fixture.identity(staleSuspended),
                    WorkItemType.EVENT_MATCHED,
                    "approval",
                    "wait-ops-old",
                    null,
                    5,
                    3,
                    3,
                    "{\"orderId\":\"A-1\"}",
                    null,
                    "retry budget exhausted",
                    "manual intervention",
                    oldDeadLetteredAt.minusSeconds(30),
                    oldDeadLetteredAt
            ));

            GraphOperationsSnapshot snapshot = fixture.service.queryOperationsSnapshot("default", "default");

            GraphOperationsSnapshot.SloIndicator deadLetterAge = snapshot.sloIndicators().stream()
                    .filter(indicator -> indicator.code().equals("DEAD_LETTER_OLDEST_AGE"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(GraphOperationsSnapshot.Health.CRITICAL, deadLetterAge.health());
            assertEquals("ge.operations.dead_letter_oldest_age_seconds", deadLetterAge.metricName());
            assertTrue(deadLetterAge.observedValue() >= 1_800);
            assertEquals("DEAD_LETTERS_PRESENT", deadLetterAge.actionCode());

            GraphOperationsSnapshot.SloIndicator suspendedAge = snapshot.sloIndicators().stream()
                    .filter(indicator -> indicator.code().equals("SUSPENDED_INSTANCE_OLDEST_AGE"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(GraphOperationsSnapshot.Health.CRITICAL, suspendedAge.health());
            assertEquals("ge.operations.suspended_oldest_age_seconds", suspendedAge.metricName());
            assertTrue(suspendedAge.observedValue() >= 7_200);
            assertEquals("SUSPENDED_INSTANCES_PRESENT", suspendedAge.actionCode());
            assertTrue(snapshot.actionItems().stream()
                    .anyMatch(item -> item.code().equals("SUSPENDED_INSTANCES_PRESENT")
                            && item.severity() == GraphOperationsSnapshot.Health.CRITICAL));
            assertTrue(fixture.metricsObserver.lastOperationsDeadLetterOldestAgeSeconds >= 1_800);
            assertTrue(fixture.metricsObserver.lastOperationsSuspendedOldestAgeSeconds >= 7_200);
        }
    }

    @Test
    void operationsSnapshotUsesRuntimeOperationsPolicyThresholds() {
        GraphOperationsPolicy policy = new GraphOperationsPolicy(
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                Duration.ofSeconds(30),
                Duration.ofSeconds(40)
        );
        try (Fixture fixture = new Fixture(false, new InMemoryExecutionCheckpointStore(), policy)) {
            fixture.createManagedGraphInstance("seed-ops-policy", GraphInstanceStatus.RUNNING);
            Instant staleSuspendedAt = Instant.now().minusSeconds(45);
            GraphInstance staleSuspended = new GraphInstance(
                    "exec-ops-policy-stale",
                    "approval-flow",
                    "ver-1",
                    "default",
                    "default",
                    "business-exec-ops-policy-stale",
                    GraphExecutionMode.GRAPH,
                    GraphInstanceStatus.SUSPENDED,
                    "starter",
                    Map.of("orderId", "A-1"),
                    0,
                    staleSuspendedAt.minusSeconds(60),
                    staleSuspendedAt,
                    null
            );
            fixture.graphInstanceStore.create(staleSuspended);
            Instant oldDeadLetteredAt = Instant.now().minusSeconds(25);
            fixture.controlPlaneService.deadLetters.add(new DeadLetterEntry(
                    "dead-ops-policy",
                    fixture.identity(staleSuspended),
                    WorkItemType.EVENT_MATCHED,
                    "approval",
                    "wait-ops-policy",
                    null,
                    5,
                    3,
                    3,
                    "{\"orderId\":\"A-1\"}",
                    null,
                    "retry budget exhausted",
                    "manual intervention",
                    oldDeadLetteredAt.minusSeconds(30),
                    oldDeadLetteredAt
            ));

            GraphOperationsSnapshot snapshot = fixture.service.queryOperationsSnapshot("default", "default");

            GraphOperationsSnapshot.SloIndicator deadLetterAge = snapshot.sloIndicators().stream()
                    .filter(indicator -> indicator.code().equals("DEAD_LETTER_OLDEST_AGE"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(GraphOperationsSnapshot.Health.CRITICAL, deadLetterAge.health());
            assertEquals(10.0, deadLetterAge.warningThreshold());
            assertEquals(20.0, deadLetterAge.criticalThreshold());

            GraphOperationsSnapshot.SloIndicator suspendedAge = snapshot.sloIndicators().stream()
                    .filter(indicator -> indicator.code().equals("SUSPENDED_INSTANCE_OLDEST_AGE"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(GraphOperationsSnapshot.Health.CRITICAL, suspendedAge.health());
            assertEquals(30.0, suspendedAge.warningThreshold());
            assertEquals(40.0, suspendedAge.criticalThreshold());
            assertTrue(snapshot.actionItems().stream()
                    .anyMatch(item -> item.code().equals("SUSPENDED_INSTANCES_PRESENT")
                            && item.severity() == GraphOperationsSnapshot.Health.CRITICAL));
        }
    }

    @Test
    void queryInstanceAuditLogProjectsSessionRoundsFromCheckpointHistory() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-audit",
                    "tenant-a",
                    "sales",
                    "Session audit",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", twoStepWaitingSessionDsl("sessionAudit"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-audit-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant first = Instant.parse("2025-01-01T02:00:00Z");
            Instant second = first.plusSeconds(30);
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionAudit")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-audit")
                    .currentPhaseId("secondDecision")
                    .currentPhaseRound(1)
                    .totalRounds(2)
                    .phaseVisitCount(Map.of("firstDecision", 1, "secondDecision", 1))
                    .phaseOutputs(Map.of("firstDecision", Map.of("decision", "review")))
                    .history(List.of(
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "firstDecision",
                                    1,
                                    Map.of("ready", true),
                                    Map.of("decision", "review"),
                                    first
                            ),
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "secondDecision",
                                    2,
                                    Map.of("ready", false),
                                    Map.of("decision", "approve"),
                                    second
                            )
                    ))
                    .sharedState(Map.of("orderId", "A-1"))
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(first.minusSeconds(5))
                    .lastTouchAt(second)
                    .checkpointedAt(second)
                    .build());

            List<GraphAuditEntry> auditLog = fixture.service.queryInstanceAuditLog(instance.instanceId(), 0, 50);

            assertEquals(2, auditLog.size());
            assertEquals("firstDecision", auditLog.getFirst().nodeId());
            assertEquals(AuditEventType.NODE_COMPLETE, auditLog.getFirst().eventType());
            assertEquals("{\"ready\":true}", auditLog.getFirst().inputJson());
            assertEquals("{\"decision\":\"review\"}", auditLog.getFirst().outputJson());
            // SESSION audit projections reuse retryAttempt for the within-phase round ordinal.
            assertEquals(1, auditLog.getFirst().retryAttempt());
            assertNull(auditLog.getFirst().elapsedMillis());
            assertEquals(first, auditLog.getFirst().recordedAt());
            assertEquals("secondDecision", auditLog.get(1).nodeId());
            assertEquals(2, auditLog.get(1).retryAttempt());
            assertEquals(instance.definitionKey(), auditLog.get(1).definitionKey());
        }
    }

    @Test
    void queryInstanceAuditLogPaginatesSessionRounds() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-audit-pages",
                    "tenant-a",
                    "sales",
                    "Session audit pages",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionAuditPages"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-audit-page-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant base = Instant.parse("2025-01-01T03:00:00Z");
            List<com.leanowtech.bloge.ext.model.RoundRecord> history = new ArrayList<>();
            for (int index = 1; index <= 15; index++) {
                history.add(new com.leanowtech.bloge.ext.model.RoundRecord(
                        "review",
                        index,
                        Map.of("index", index),
                        Map.of("done", index),
                        base.plusSeconds(index)
                ));
            }
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionAuditPages")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-audit-pages")
                    .currentPhaseId("review")
                    .currentPhaseRound(15)
                    .totalRounds(15)
                    .phaseVisitCount(Map.of("review", 15))
                    .phaseOutputs(Map.of("review", Map.of("done", 15)))
                    .history(history)
                    .sharedState(Map.of("orderId", "A-1"))
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(base.minusSeconds(5))
                    .lastTouchAt(base.plusSeconds(15))
                    .checkpointedAt(base.plusSeconds(15))
                    .build());

            List<GraphAuditEntry> firstPage = fixture.service.queryInstanceAuditLog(instance.instanceId(), 0, 5);
            List<GraphAuditEntry> thirdPage = fixture.service.queryInstanceAuditLog(instance.instanceId(), 2, 5);

            assertEquals(List.of(1, 2, 3, 4, 5), firstPage.stream().map(GraphAuditEntry::retryAttempt).toList());
            assertEquals(List.of(11, 12, 13, 14, 15), thirdPage.stream().map(GraphAuditEntry::retryAttempt).toList());
        }
    }

    @Test
    void queryInstanceAuditLogReturnsEmptyForSessionWithoutHistory() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-audit-empty",
                    "tenant-a",
                    "sales",
                    "Session audit empty",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionAuditEmpty"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-audit-empty-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant now = Instant.parse("2025-01-01T04:00:00Z");
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionAuditEmpty")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-audit-empty")
                    .currentPhaseId("review")
                    .currentPhaseRound(0)
                    .totalRounds(0)
                    .phaseVisitCount(Map.of())
                    .phaseOutputs(Map.of())
                    .history(List.of())
                    .sharedState(Map.of())
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(now.minusSeconds(5))
                    .lastTouchAt(now)
                    .checkpointedAt(now)
                    .build());

            assertEquals(List.of(), fixture.service.queryInstanceAuditLog(instance.instanceId(), 0, 50));
        }
    }

    @Test
    void queryInstanceAuditLogRejectsMissingSessionRuntime() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-audit-runtime",
                    "tenant-a",
                    "sales",
                    "Session audit runtime",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionAuditRuntime"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-audit-runtime-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            DefaultGraphEngineService service = new DefaultGraphEngineService(
                    new GraphEngineStores(
                            fixture.graphDefinitionStore,
                            fixture.graphVersionStore,
                            fixture.graphDeploymentStore,
                            fixture.graphInstanceStore
                    ),
                    GraphEngineRuntimeSupport.builder()
                            .executionStore(fixture.executionStore)
                            .build()
            );
            try {
                GraphEngineServiceException error = assertThrows(
                        GraphEngineServiceException.class,
                        () -> service.queryInstanceAuditLog(instance.instanceId(), 0, 50)
                );
                assertEquals(GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE, error.errorCode());
            } finally {
                service.close();
            }
        }
    }

    @Test
    void queryInstanceAuditLogNullsSessionPayloadsWhenJsonSerializationFails() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-audit-json",
                    "tenant-a",
                    "sales",
                    "Session audit json",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionAuditJson"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-audit-json-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant now = Instant.parse("2025-01-01T05:00:00Z");
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionAuditJson")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-audit-json")
                    .currentPhaseId("review")
                    .currentPhaseRound(1)
                    .totalRounds(1)
                    .phaseVisitCount(Map.of("review", 1))
                    .phaseOutputs(Map.of("review", Map.of()))
                    .history(List.of(new com.leanowtech.bloge.ext.model.RoundRecord(
                            "review",
                            1,
                            Map.of("input", "value"),
                            Map.of("output", "value"),
                            now
                    )))
                    .sharedState(Map.of())
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(now.minusSeconds(5))
                    .lastTouchAt(now)
                    .checkpointedAt(now)
                    .build());

            DefaultGraphEngineService service = new DefaultGraphEngineService(
                    new GraphEngineStores(
                            fixture.graphDefinitionStore,
                            fixture.graphVersionStore,
                            fixture.graphDeploymentStore,
                            fixture.graphInstanceStore
                    ),
                    GraphEngineRuntimeSupport.builder()
                            .executionStore(fixture.executionStore)
                            .executionCheckpointStore(fixture.checkpointStore)
                            .jsonCodec(new JsonCodec() {
                                @Override
                                public String serialize(Object value) {
                                    throw new IllegalArgumentException("boom");
                                }

                                @Override
                                public Object deserialize(String json) {
                                    return JsonCodec.DEFAULT.deserialize(json);
                                }
                            })
                            .build()
            );
            try {
                GraphAuditEntry auditEntry = service.queryInstanceAuditLog(instance.instanceId(), 0, 50).getFirst();

                assertNull(auditEntry.inputJson());
                assertNull(auditEntry.outputJson());
            } finally {
                service.close();
            }
        }
    }

    @Test
    void queryInstanceTransitionsUsesControlPlaneEntriesForSessionInstances() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-transitions-cp",
                    "tenant-a",
                    "sales",
                    "Session transitions cp",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionTransitionsCp"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-transition-cp-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            ExecutionIdentity identity = fixture.identity(instance);
            Instant now = Instant.parse("2025-01-01T06:00:00Z");
            fixture.controlPlaneService.transitions.add(new ExecutionTransitionLogEntry(
                    "transition-session-1",
                    identity,
                    ExecutionStatus.RUNNING,
                    ExecutionStatus.SUSPENDED,
                    3,
                    4,
                    "control-plane",
                    "awaiting signal",
                    now
            ));

            List<GraphTransitionEntry> transitions = fixture.service.queryInstanceTransitions(instance.instanceId(), 0, 50);

            assertEquals(1, transitions.size());
            assertEquals("transition-session-1", transitions.getFirst().transitionId());
            assertEquals(GraphInstanceStatus.SUSPENDED, transitions.getFirst().toStatus());
            assertEquals("control-plane", transitions.getFirst().transitionSource());
        }
    }

    @Test
    void queryInstanceTransitionsSynthesizesSessionHistoryFromCheckpointWhenControlPlaneEmpty() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-transitions-synth",
                    "tenant-a",
                    "sales",
                    "Session transitions synth",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", twoStepWaitingSessionDsl("sessionTransitionsSynth"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-transition-synth-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant base = Instant.parse("2025-01-01T06:10:00Z");
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionTransitionsSynth")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-transition-synth")
                    .currentPhaseId("secondDecision")
                    .currentPhaseRound(3)
                    .totalRounds(3)
                    .phaseVisitCount(Map.of("firstDecision", 1, "secondDecision", 2))
                    .phaseOutputs(Map.of("firstDecision", Map.of("decision", "review")))
                    .history(List.of(
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "firstDecision",
                                    1,
                                    Map.of("step", 1),
                                    Map.of("decision", "review"),
                                    base
                            ),
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "secondDecision",
                                    1,
                                    Map.of("step", 2),
                                    Map.of("decision", "rework"),
                                    base.plusSeconds(30)
                            ),
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "secondDecision",
                                    2,
                                    Map.of("step", 3),
                                    Map.of("decision", "approved"),
                                    base.plusSeconds(60)
                            )
                    ))
                    .sharedState(Map.of("orderId", "A-1"))
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(base.minusSeconds(10))
                    .lastTouchAt(base.plusSeconds(60))
                    .checkpointedAt(base.plusSeconds(60))
                    .build());

            List<GraphTransitionEntry> transitions = fixture.service.queryInstanceTransitions(instance.instanceId(), 0, 50);

            assertEquals(5, transitions.size());
            assertEquals("session-synth-" + instance.instanceId() + "-0", transitions.getFirst().transitionId());
            assertEquals(GraphInstanceStatus.RUNNING, transitions.getFirst().fromStatus());
            assertEquals(GraphInstanceStatus.SUSPENDED, transitions.getFirst().toStatus());
            assertEquals("session-checkpoint-synthesis", transitions.getFirst().transitionSource());
            assertEquals(
                    List.of(
                            GraphInstanceStatus.SUSPENDED,
                            GraphInstanceStatus.RUNNING,
                            GraphInstanceStatus.SUSPENDED,
                            GraphInstanceStatus.RUNNING,
                            GraphInstanceStatus.SUSPENDED
                    ),
                    transitions.stream().map(GraphTransitionEntry::toStatus).toList()
            );
            assertEquals(
                    List.of(base, base, base.plusSeconds(30), base.plusSeconds(30), base.plusSeconds(60)),
                    transitions.stream().map(GraphTransitionEntry::createdAt).toList()
            );
        }
    }

    @Test
    void queryInstanceTransitionsUsesPreviousRoundTimestampForResumeTransitions() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-transitions-duration",
                    "tenant-a",
                    "sales",
                    "Session transitions duration",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", twoStepWaitingSessionDsl("sessionTransitionsDuration"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-transition-duration-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant base = Instant.parse("2025-01-01T06:12:00Z");
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionTransitionsDuration")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-transition-duration")
                    .currentPhaseId("secondDecision")
                    .currentPhaseRound(1)
                    .totalRounds(2)
                    .phaseVisitCount(Map.of("firstDecision", 1, "secondDecision", 1))
                    .phaseOutputs(Map.of("firstDecision", Map.of("decision", "review")))
                    .history(List.of(
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "firstDecision",
                                    1,
                                    Map.of("step", 1),
                                    Map.of("decision", "review"),
                                    base
                            ),
                            new com.leanowtech.bloge.ext.model.RoundRecord(
                                    "secondDecision",
                                    1,
                                    Map.of("step", 2),
                                    Map.of("decision", "approved"),
                                    base.plusSeconds(30)
                            )
                    ))
                    .sharedState(Map.of("orderId", "A-1"))
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(base.minusSeconds(10))
                    .lastTouchAt(base.plusSeconds(30))
                    .checkpointedAt(base.plusSeconds(30))
                    .build());

            List<GraphTransitionEntry> transitions = fixture.service.queryInstanceTransitions(instance.instanceId(), 0, 50);

            GraphTransitionEntry resumeTransition = transitions.get(1);
            GraphTransitionEntry suspendTransition = transitions.get(2);

            assertEquals(GraphInstanceStatus.SUSPENDED, resumeTransition.fromStatus());
            assertEquals(GraphInstanceStatus.RUNNING, resumeTransition.toStatus());
            assertEquals(base, resumeTransition.createdAt());
            assertEquals(GraphInstanceStatus.RUNNING, suspendTransition.fromStatus());
            assertEquals(GraphInstanceStatus.SUSPENDED, suspendTransition.toStatus());
            assertEquals(base.plusSeconds(30), suspendTransition.createdAt());
            assertTrue(suspendTransition.createdAt().isAfter(resumeTransition.createdAt()));
        }
    }

    @Test
    void queryInstanceTransitionsUsesSynthesisWhenControlPlaneIsUnavailable() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-transitions-runtime",
                    "tenant-a",
                    "sales",
                    "Session transitions runtime",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionTransitionsRuntime"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-transition-runtime-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant now = Instant.parse("2025-01-01T06:20:00Z");
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionTransitionsRuntime")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-transition-runtime")
                    .currentPhaseId("review")
                    .currentPhaseRound(1)
                    .totalRounds(1)
                    .phaseVisitCount(Map.of("review", 1))
                    .phaseOutputs(Map.of("review", Map.of("decision", "review")))
                    .history(List.of(new com.leanowtech.bloge.ext.model.RoundRecord(
                            "review",
                            1,
                            Map.of("input", true),
                            Map.of("decision", "review"),
                            now
                    )))
                    .sharedState(Map.of())
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(now.minusSeconds(5))
                    .lastTouchAt(now)
                    .checkpointedAt(now)
                    .build());
            DefaultGraphEngineService service = new DefaultGraphEngineService(
                    new GraphEngineStores(
                            fixture.graphDefinitionStore,
                            fixture.graphVersionStore,
                            fixture.graphDeploymentStore,
                            fixture.graphInstanceStore
                    ),
                    GraphEngineRuntimeSupport.builder()
                            .executionStore(fixture.executionStore)
                            .executionCheckpointStore(fixture.checkpointStore)
                            .build()
            );
            try {
                List<GraphTransitionEntry> transitions = service.queryInstanceTransitions(instance.instanceId(), 0, 50);

                assertEquals(1, transitions.size());
                assertEquals(GraphInstanceStatus.SUSPENDED, transitions.getFirst().toStatus());
            } finally {
                service.close();
            }
        }
    }

    @Test
    void queryInstanceTransitionsRejectsMissingSessionRuntimeWhenSynthesisUnavailable() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-transitions-missing-runtime",
                    "tenant-a",
                    "sales",
                    "Session transitions missing runtime",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionTransitionsMissingRuntime"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-transition-missing-runtime-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            DefaultGraphEngineService service = new DefaultGraphEngineService(
                    new GraphEngineStores(
                            fixture.graphDefinitionStore,
                            fixture.graphVersionStore,
                            fixture.graphDeploymentStore,
                            fixture.graphInstanceStore
                    ),
                    GraphEngineRuntimeSupport.builder()
                            .executionStore(fixture.executionStore)
                            .build()
            );
            try {
                GraphEngineServiceException error = assertThrows(
                        GraphEngineServiceException.class,
                        () -> service.queryInstanceTransitions(instance.instanceId(), 0, 50)
                );
                assertEquals(GraphEngineServiceErrorCode.RUNTIME_UNAVAILABLE, error.errorCode());
            } finally {
                service.close();
            }
        }
    }

    @Test
    void queryInstanceTransitionsDoesNotSynthesizeLaterEmptyPagesWhenControlPlaneExists() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-transitions-page",
                    "tenant-a",
                    "sales",
                    "Session transitions page",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionTransitionsPage"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-transition-page-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant now = Instant.parse("2025-01-01T06:30:00Z");
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionTransitionsPage")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-transition-page")
                    .currentPhaseId("review")
                    .currentPhaseRound(1)
                    .totalRounds(1)
                    .phaseVisitCount(Map.of("review", 1))
                    .phaseOutputs(Map.of("review", Map.of("decision", "review")))
                    .history(List.of(new com.leanowtech.bloge.ext.model.RoundRecord(
                            "review",
                            1,
                            Map.of("input", true),
                            Map.of("decision", "review"),
                            now
                    )))
                    .sharedState(Map.of())
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(now.minusSeconds(5))
                    .lastTouchAt(now)
                    .checkpointedAt(now)
                    .build());

            // Once page 0 is owned by the control plane, later empty pages remain authoritative
            // and must not be backfilled from checkpoint synthesis.
            assertEquals(List.of(), fixture.service.queryInstanceTransitions(instance.instanceId(), 1, 50));
        }
    }

    @Test
    void queryInstanceTransitionsSynthesisIsNotRepeatedOnSubsequentPages() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-transitions-fallback-pages",
                    "tenant-a",
                    "sales",
                    "Session transitions fallback pages",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionTransitionsFallbackPages"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-transition-fallback-pages-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.SUSPENDED
            );
            Instant now = Instant.parse("2025-01-01T06:35:00Z");
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionTransitionsFallbackPages")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-transition-fallback-pages")
                    .currentPhaseId("review")
                    .currentPhaseRound(1)
                    .totalRounds(1)
                    .phaseVisitCount(Map.of("review", 1))
                    .phaseOutputs(Map.of("review", Map.of("decision", "review")))
                    .history(List.of(new com.leanowtech.bloge.ext.model.RoundRecord(
                            "review",
                            1,
                            Map.of("input", true),
                            Map.of("decision", "review"),
                            now
                    )))
                    .sharedState(Map.of())
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.SUSPENDED)
                    .startedAt(now.minusSeconds(5))
                    .lastTouchAt(now)
                    .checkpointedAt(now)
                    .build());

            List<GraphTransitionEntry> firstPage = fixture.service.queryInstanceTransitions(instance.instanceId(), 0, 50);
            List<GraphTransitionEntry> secondPage = fixture.service.queryInstanceTransitions(instance.instanceId(), 1, 50);

            assertEquals(1, firstPage.size());
            assertEquals("session-checkpoint-synthesis", firstPage.getFirst().transitionSource());
            assertEquals(List.of(), secondPage);
        }
    }

    @Test
    void queryInstanceTransitionsSynthesizesCancelledTerminalStatusFromSessionCheckpoint() {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "session-transitions-cancelled",
                    "tenant-a",
                    "sales",
                    "Session transitions cancelled",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", waitingSessionDsl("sessionTransitionsCancelled"));
            GraphInstance instance = fixture.createManagedGraphInstance(
                    "exec-session-transition-cancelled-1",
                    definition,
                    version.versionId(),
                    GraphExecutionMode.SESSION,
                    GraphInstanceStatus.RUNNING
            );
            Instant now = Instant.parse("2025-01-01T06:40:00Z");
            new ExecutionCheckpointSessionStore(fixture.checkpointStore).save(SessionCheckpoint.builder()
                    .sessionId(instance.instanceId())
                    .sessionGraphName("sessionTransitionsCancelled")
                    .namespace(instance.namespace())
                    .ownerId(instance.initiator())
                    .sessionGraphHash("hash-session-transition-cancelled")
                    .currentPhaseId("review")
                    .currentPhaseRound(1)
                    .totalRounds(1)
                    .phaseVisitCount(Map.of("review", 1))
                    .phaseOutputs(Map.of("review", Map.of("decision", "review")))
                    .history(List.of(new com.leanowtech.bloge.ext.model.RoundRecord(
                            "review",
                            1,
                            Map.of("input", true),
                            Map.of("decision", "review"),
                            now.minusSeconds(30)
                    )))
                    .sharedState(Map.of("__termination_reason__", "__graph_engine_cancel__:manual stop"))
                    .contextData(Map.of())
                    .status(com.leanowtech.bloge.ext.model.SessionStatus.TERMINATED)
                    .startedAt(now.minusSeconds(60))
                    .lastTouchAt(now)
                    .checkpointedAt(now)
                    .build());

            GraphTransitionEntry finalTransition = fixture.service.queryInstanceTransitions(instance.instanceId(), 0, 50).getLast();

            assertEquals(GraphInstanceStatus.CANCELLED, finalTransition.toStatus());
            assertEquals("session-checkpoint-synthesis", finalTransition.transitionSource());
        }
    }

    @Test
    void registerRemoteWorkerReturnsActiveDeploymentAssignments() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "risk-flow",
                    "tenant-a",
                    "sales",
                    "Risk flow",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));

            fixture.service.createDeployment(new CreateDeploymentCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "production",
                    new com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy.Latest(),
                    new OperatorPlaneConfig(
                            true,
                            List.of(),
                            Map.of("RiskAssessment", new RemoteWorkerBinding(
                                    "worker-ai",
                                    "workers.ai",
                                    null,
                                    Map.of("tier", "ml")
                            ))
                    ),
                    true
            ));

            GraphRemoteWorkerRegistration registration = com.leanowtech.bloge.core.context.TenantContextHolder.callWith(
                    new com.leanowtech.bloge.core.context.TenantContext("tenant-a", "sales"),
                    () -> fixture.service.registerRemoteWorker(new RegisterRemoteWorkerCommand("worker-ai", "workers.ai"))
            );

            assertEquals("worker-ai", registration.workerId());
            assertEquals("workers.ai", registration.workerTopic());
            assertEquals(1, registration.assignments().size());
            assertEquals("RiskAssessment", registration.assignments().getFirst().operatorRef());
        }
    }

    @Test
    void pollAndCompleteRemoteWorkerJobResumesExecution() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "remote-approval",
                    "tenant-a",
                    "sales",
                    "Remote approval",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph remoteApproval {
                              node riskCheck : supportClassifier {
                                execution_mode = remote
                                worker_topic = "workers.ai"
                                input {
                                  orderId = ctx.orderId
                                }
                              }

                              node done : echo {
                                depends_on = [riskCheck]
                                input {
                                  approved = riskCheck.output.approved
                                  orderId = riskCheck.output.orderId
                                }
                              }
                            }
                            """,
                    null,
                    GraphMigrationPolicy.PIN_VERSION
            ));
            fixture.service.publishVersion(version.versionId(), version.revision());

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    null,
                    "remote-approval-001",
                    "starter",
                    Map.of("orderId", "remote-approval-001")
            ));

            assertEquals(GraphInstanceStatus.SUSPENDED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.SUSPENDED
            ));

            List<GraphRemoteWorkerJob> jobs = fixture.service.pollRemoteWorkerJobs(new PollRemoteWorkerJobsCommand(
                    "worker-ai",
                    "workers.ai",
                    1,
                    Duration.ofMinutes(5)
            ));

            assertEquals(1, jobs.size());
            GraphRemoteWorkerJob job = jobs.getFirst();
            assertEquals("supportClassifier", job.envelope().operatorRef());
            assertEquals("workers.ai", job.envelope().workerTopic());
            assertNotNull(job.claimToken());

            fixture.service.completeRemoteWorkerJob(new CompleteRemoteWorkerJobCommand(
                    job.itemId(),
                    job.claimToken(),
                    job.revision(),
                    Map.of("approved", true, "orderId", "remote-approval-001")
            ));

            assertEquals(GraphInstanceStatus.COMPLETED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.COMPLETED
            ));
            assertEquals(WorkItemStatus.DONE, fixture.workItemStore.get(job.itemId()).orElseThrow().status());
            assertEquals(List.of("COMPLETED"), fixture.metricsObserver.instanceCompletedStatuses);
        }
    }

    @Test
    void queryInstanceNodesPrefersWorkItemStateAndRetryRestoresDeadLetters() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "remote-node-view",
                    "tenant-a",
                    "sales",
                    "Remote node view",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion version = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph remoteNodeView {
                              node riskCheck : supportClassifier {
                                execution_mode = remote
                                worker_topic = "workers.ai"
                                retry = { attempts: 3, backoff: 1s, strategy: fixed }
                                input {
                                  orderId = ctx.orderId
                                }
                              }

                              node done : echo {
                                depends_on = [riskCheck]
                                input {
                                  approved = riskCheck.output.approved
                                  orderId = riskCheck.output.orderId
                                }
                              }
                            }
                            """,
                    null,
                    GraphMigrationPolicy.PIN_VERSION
            ));
            fixture.service.publishVersion(version.versionId(), version.revision());

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    null,
                    "remote-node-view-001",
                    "starter",
                    Map.of("orderId", "remote-node-view-001")
            ));

            assertEquals(GraphInstanceStatus.SUSPENDED, fixture.awaitInstanceStatus(
                    started.instance().instanceId(),
                    GraphInstanceStatus.SUSPENDED
            ));

            List<GraphNodeState> queuedNodes = fixture.service.queryInstanceNodes(started.instance().instanceId());
            assertEquals(GraphNodeStatus.PENDING, queuedNodes.getFirst().status());
            assertEquals(GraphNodeStatus.NOT_STARTED, queuedNodes.get(1).status());

            GraphRemoteWorkerJob job = fixture.service.pollRemoteWorkerJobs(new PollRemoteWorkerJobsCommand(
                    "worker-ai",
                    "workers.ai",
                    1,
                    Duration.ofMinutes(5)
            )).getFirst();

            List<GraphNodeState> runningNodes = fixture.service.queryInstanceNodes(started.instance().instanceId());
            assertEquals(GraphNodeStatus.RUNNING, runningNodes.getFirst().status());

            fixture.workItemStore.markRetryWait(
                    job.itemId(),
                    job.claimToken(),
                    Instant.now().plusSeconds(30),
                    job.revision()
            );

            List<GraphNodeState> retryingNodes = fixture.service.queryInstanceNodes(started.instance().instanceId());
            assertEquals(GraphNodeStatus.RETRYING, retryingNodes.getFirst().status());

            fixture.workItemStore.markDeadLetter(job.itemId(), "retry budget exhausted");

            List<GraphNodeState> deadLetteredNodes = fixture.service.queryInstanceNodes(started.instance().instanceId());
            assertEquals(GraphNodeStatus.DEAD_LETTERED, deadLetteredNodes.getFirst().status());
            assertEquals("retry budget exhausted", deadLetteredNodes.getFirst().lastError());

            RetryInstanceResult retried = fixture.service.retryInstance(
                    started.instance().instanceId(),
                    Set.of("riskCheck"),
                    fixture.service.getInstance(started.instance().instanceId()).revision(),
                    new RecoveryActionEvidence(
                            "remote worker fixed",
                            "RETRY_INSTANCE_DEAD_LETTERS",
                            "FAILED_INSTANCE_BACKLOG",
                            "ops-bot",
                            "REQ-7788"
                    )
            );

            assertEquals(1, retried.retriedItemCount());
            List<GraphNodeState> retriedNodes = fixture.service.queryInstanceNodes(started.instance().instanceId());
            assertEquals(GraphNodeStatus.PENDING, retriedNodes.getFirst().status());
            List<GraphAuditEntry> recoveryAudits = controlActionAudits(fixture, started.instance().instanceId());
            assertEquals(2, recoveryAudits.size());
            GraphAuditEntry attemptAudit = controlActionWithAttemptStatus(recoveryAudits, "ATTEMPTED");
            GraphAuditEntry successAudit = controlActionWithAttemptStatus(recoveryAudits, "SUCCEEDED");
            assertEquals("__control_retry_instance__", attemptAudit.nodeId());
            assertEquals("__control_retry_instance__", successAudit.nodeId());
            assertTrue(successAudit.inputJson().contains("remote worker fixed"));
            assertTrue(successAudit.inputJson().contains("RETRY_INSTANCE_DEAD_LETTERS"));
            assertTrue(successAudit.inputJson().contains("REQ-7788"));
            assertTrue(attemptAudit.outputJson().contains("\"candidateItemCount\":1"));
            assertTrue(successAudit.outputJson().contains("\"status\":\"RESTORED\""));
            assertTrue(successAudit.outputJson().contains("\"restoredItemCount\":1"));

            List<GraphControlActionEntry> controlActions =
                    fixture.service.queryInstanceControlActions(started.instance().instanceId(), 0, 50);
            assertEquals(2, controlActions.size());
            GraphControlActionEntry attempted = controlActions.getFirst();
            GraphControlActionEntry succeeded = controlActions.get(1);
            assertEquals(GraphControlActionEntry.AttemptStatus.ATTEMPTED, attempted.attemptStatus());
            assertEquals(GraphControlActionEntry.AttemptStatus.SUCCEEDED, succeeded.attemptStatus());
            assertEquals("RETRY_INSTANCE_DEAD_LETTERS", succeeded.actionCode());
            assertEquals("REQ-7788", succeeded.requestId());
            assertEquals(List.of("riskCheck"), succeeded.requestedNodeIds());
            assertEquals(1, succeeded.restoredItemCount());
            assertEquals(List.of(job.itemId()), succeeded.restoredItemIds());

            RetryInstanceResult replayed = fixture.service.retryInstance(
                    started.instance().instanceId(),
                    Set.of("riskCheck"),
                    -1,
                    new RecoveryActionEvidence(
                            "remote worker fixed",
                            "RETRY_INSTANCE_DEAD_LETTERS",
                            "FAILED_INSTANCE_BACKLOG",
                            "ops-bot",
                            "REQ-7788"
                    )
            );

            assertTrue(replayed.idempotentReplay());
            assertEquals(GraphControlActionEntry.AttemptStatus.SUCCEEDED, replayed.attemptStatus());
            assertEquals("RESTORED", replayed.status());
            assertEquals("REQ-7788", replayed.requestId());
            assertEquals(1, replayed.retriedItemCount());
            assertEquals(2, controlActionAudits(fixture, started.instance().instanceId()).size());
        }
    }

    @Test
    void retryInstanceRecordsFailedControlActionWithPartialRestoreCount() {
        try (Fixture fixture = new Fixture(false)) {
            Instant now = Instant.now();
            GraphInstance instance = fixture.createManagedGraphInstance("exec-instance-retry-failure", GraphInstanceStatus.SUSPENDED);
            ExecutionIdentity identity = fixture.identity(instance);
            fixture.workItemStore.create(remoteWorkerItem(
                    "remote-retry-1",
                    identity,
                    WorkItemStatus.DEAD_LETTER,
                    3,
                    3,
                    null,
                    0,
                    now
            ));
            fixture.workItemStore.create(remoteWorkerItem(
                    "remote-retry-2",
                    identity,
                    WorkItemStatus.DEAD_LETTER,
                    3,
                    3,
                    null,
                    0,
                    now.plusSeconds(1)
            ));
            fixture.workItemStore.failRestore("remote-retry-2", new IllegalStateException("restore loop failed"));

            assertThrows(IllegalStateException.class, () -> fixture.service.retryInstance(
                    instance.instanceId(),
                    Set.of("riskCheck"),
                    fixture.service.getInstance(instance.instanceId()).revision(),
                    new RecoveryActionEvidence(
                            "second item should fail",
                            "RETRY_INSTANCE_DEAD_LETTERS",
                            "FAILED_INSTANCE_BACKLOG",
                            "ops-bot",
                            "REQ-PARTIAL"
                    )
            ));

            assertEquals(WorkItemStatus.READY, fixture.workItemStore.get("remote-retry-1").orElseThrow().status());
            assertEquals(WorkItemStatus.DEAD_LETTER, fixture.workItemStore.get("remote-retry-2").orElseThrow().status());
            List<GraphAuditEntry> recoveryAudits = controlActionAudits(fixture, instance.instanceId());
            assertEquals(2, recoveryAudits.size());
            GraphAuditEntry attemptAudit = controlActionWithAttemptStatus(recoveryAudits, "ATTEMPTED");
            GraphAuditEntry failureAudit = controlActionWithAttemptStatus(recoveryAudits, "FAILED");
            assertEquals("__control_retry_instance__", attemptAudit.nodeId());
            assertEquals("__control_retry_instance__", failureAudit.nodeId());
            assertTrue(attemptAudit.outputJson().contains("\"candidateItemCount\":2"));
            assertTrue(failureAudit.inputJson().contains("REQ-PARTIAL"));
            assertTrue(failureAudit.outputJson().contains("\"failurePhase\":\"RESTORE\""));
            assertTrue(failureAudit.outputJson().contains("restore loop failed"));
            assertTrue(failureAudit.outputJson().contains("\"candidateItemCount\":2"));
            assertTrue(failureAudit.outputJson().contains("\"restoredItemCount\":1"));
            assertTrue(failureAudit.outputJson().contains("remote-retry-1"));

            List<GraphControlActionEntry> controlActions =
                    fixture.service.queryInstanceControlActions(instance.instanceId(), 0, 50);
            assertEquals(2, controlActions.size());
            GraphControlActionEntry failed = controlActions.get(1);
            assertEquals(GraphControlActionEntry.AttemptStatus.FAILED, failed.attemptStatus());
            assertEquals("REQ-PARTIAL", failed.requestId());
            assertEquals("RESTORE", failed.failurePhase());
            assertEquals(2, failed.candidateItemCount());
            assertEquals(List.of("remote-retry-1", "remote-retry-2"), failed.candidateItemIds());
            assertEquals(1, failed.restoredItemCount());
            assertEquals(List.of("remote-retry-1"), failed.restoredItemIds());

            RetryInstanceResult replayed = fixture.service.retryInstance(
                    instance.instanceId(),
                    Set.of("riskCheck"),
                    -1,
                    new RecoveryActionEvidence(
                            "second item should fail",
                            "RETRY_INSTANCE_DEAD_LETTERS",
                            "FAILED_INSTANCE_BACKLOG",
                            "ops-bot",
                            "REQ-PARTIAL"
                    )
            );

            assertTrue(replayed.idempotentReplay());
            assertEquals(GraphControlActionEntry.AttemptStatus.FAILED, replayed.attemptStatus());
            assertEquals("FAILED", replayed.status());
            assertEquals("REQ-PARTIAL", replayed.requestId());
            assertEquals("RESTORE", replayed.failurePhase());
            assertEquals(1, replayed.retriedItemCount());
            assertEquals(2, controlActionAudits(fixture, instance.instanceId()).size());
            assertEquals(WorkItemStatus.READY, fixture.workItemStore.get("remote-retry-1").orElseThrow().status());
            assertEquals(WorkItemStatus.DEAD_LETTER, fixture.workItemStore.get("remote-retry-2").orElseThrow().status());
        }
    }

    @Test
    void heartbeatRemoteWorkerJobExtendsClaim() {
        try (Fixture fixture = new Fixture(false)) {
            GraphInstance instance = fixture.createManagedGraphInstance("exec-remote-heartbeat", GraphInstanceStatus.SUSPENDED);
            ExecutionIdentity identity = fixture.identity(instance);
            Instant now = Instant.now();
            fixture.workItemStore.create(remoteWorkerItem(
                    "remote-heartbeat-1",
                    identity,
                    WorkItemStatus.READY,
                    0,
                    4,
                    null,
                    0,
                    now
            ));

            GraphRemoteWorkerJob claimed = fixture.service.pollRemoteWorkerJobs(new PollRemoteWorkerJobsCommand(
                    "worker-ai",
                    "workers.ai",
                    1,
                    Duration.ofMinutes(1)
            )).getFirst();

            GraphRemoteWorkerJob renewed = fixture.service.heartbeatRemoteWorkerJob(new HeartbeatRemoteWorkerJobCommand(
                    claimed.itemId(),
                    claimed.claimToken(),
                    Duration.ofMinutes(2)
            ));

            assertTrue(renewed.claimUntil().isAfter(claimed.claimUntil()));
            assertTrue(renewed.revision() > claimed.revision());
        }
    }

    @Test
    void failRemoteWorkerJobSchedulesRetryWhenAttemptsRemain() {
        try (Fixture fixture = new Fixture(false)) {
            GraphInstance instance = fixture.createManagedGraphInstance("exec-remote-retry", GraphInstanceStatus.SUSPENDED);
            ExecutionIdentity identity = fixture.identity(instance);
            Instant now = Instant.now();
            fixture.workItemStore.create(remoteWorkerItem(
                    "remote-retry-1",
                    identity,
                    WorkItemStatus.READY,
                    0,
                    4,
                    null,
                    0,
                    now
            ));

            GraphRemoteWorkerJob claimed = fixture.service.pollRemoteWorkerJobs(new PollRemoteWorkerJobsCommand(
                    "worker-ai",
                    "workers.ai",
                    1,
                    Duration.ofMinutes(5)
            )).getFirst();

            fixture.service.failRemoteWorkerJob(new FailRemoteWorkerJobCommand(
                    claimed.itemId(),
                    claimed.claimToken(),
                    claimed.revision(),
                    "upstream timeout"
            ));

            WorkItem stored = fixture.workItemStore.get(claimed.itemId()).orElseThrow();
            assertEquals(WorkItemStatus.RETRY_WAIT, stored.status());
            assertEquals(1, stored.retryCount());
            assertNotNull(stored.nextAttemptAt());
        }
    }

    @Test
    void failRemoteWorkerJobDeadLettersWhenRetryBudgetIsExhausted() {
        try (Fixture fixture = new Fixture(false)) {
            GraphInstance instance = fixture.createManagedGraphInstance("exec-remote-dead", GraphInstanceStatus.SUSPENDED);
            ExecutionIdentity identity = fixture.identity(instance);
            Instant now = Instant.now();
            fixture.workItemStore.create(remoteWorkerItem(
                    "remote-dead-1",
                    identity,
                    WorkItemStatus.READY,
                    3,
                    4,
                    null,
                    0,
                    now
            ));

            GraphRemoteWorkerJob claimed = fixture.service.pollRemoteWorkerJobs(new PollRemoteWorkerJobsCommand(
                    "worker-ai",
                    "workers.ai",
                    1,
                    Duration.ofMinutes(5)
            )).getFirst();

            fixture.service.failRemoteWorkerJob(new FailRemoteWorkerJobCommand(
                    claimed.itemId(),
                    claimed.claimToken(),
                    claimed.revision(),
                    "model crashed"
            ));

            WorkItem stored = fixture.workItemStore.get(claimed.itemId()).orElseThrow();
            assertEquals(WorkItemStatus.DEAD_LETTER, stored.status());
            assertEquals("model crashed", stored.lastError());
        }
    }

    @Test
    void diffVersionsDetectsSourceAndMetadataChanges() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diff-test",
                    "tenant-a",
                    "sales",
                    "Diff test",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion v1 = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph diffTest {
                              node echo : echo {
                                input {
                                  orderId = ctx.orderId
                                }
                              }
                            }
                            """,
                    null,
                    null
            ));
            GraphVersion v2 = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "2.0.0",
                    """
                            graph diffTest {
                              node step1 : echo {
                                input {
                                  orderId = ctx.orderId
                                }
                              }
                              node step2 : echo {
                                input {
                                  result = step1.orderId
                                }
                              }
                            }
                            """,
                    null,
                    null
            ));

            GraphVersionDiff diff = fixture.service.diffVersions(v1.versionId(), v2.versionId());

            assertFalse(diff.sourceEqual());
            assertFalse(diff.unifiedDiff().isEmpty());
            assertEquals("1.0.0", diff.left().version());
            assertEquals("2.0.0", diff.right().version());
            assertEquals(v1.versionId(), diff.left().versionId());
            assertEquals(v2.versionId(), diff.right().versionId());
            assertTrue(diff.left().valid());
            assertEquals(0, diff.left().errorCount());
            assertNotNull(diff.metadataDiff());
        }
    }

    @Test
    void diffVersionsWithIdenticalSourceReportsSourceEqual() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diff-identical",
                    "tenant-a",
                    "sales",
                    "Diff identical",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            String source = """
                    graph diffIdentical {
                      node echo : echo {
                        input {
                          orderId = ctx.orderId
                        }
                      }
                    }
                    """;
            GraphVersion v1 = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    source,
                    null,
                    null
            ));
            GraphVersion v2 = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "2.0.0",
                    source,
                    null,
                    null
            ));

            GraphVersionDiff diff = fixture.service.diffVersions(v1.versionId(), v2.versionId());

            assertTrue(diff.sourceEqual());
            assertTrue(diff.unifiedDiff().isEmpty());
            assertTrue(diff.metadataDiff().unchanged());
        }
    }

    @Test
    void diffVersionsIncludesSchemaCompatibilitySummary() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diff-schema",
                    "tenant-a",
                    "sales",
                    "Diff schema",
                    null,
                    null,
                    Map.of(),
                    null,
                    null
            ));
            GraphVersion v1 = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "1.0.0",
                    """
                            graph diffSchema {
                              input {
                                orderId: String
                              }
                              output {
                                accepted: Boolean?
                              }
                              node echo : echo {
                                input {
                                  orderId = ctx.orderId
                                }
                              }
                            }
                            """,
                    null,
                    null
            ));
            GraphVersion v2 = fixture.service.createVersion(new CreateVersionCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    "2.0.0",
                    """
                            graph diffSchema {
                              input {
                                orderId: String
                                priority: String?
                              }
                              output {
                                accepted: Boolean?
                                reviewState: String?
                              }
                              node echo : echo {
                                input {
                                  orderId = ctx.orderId
                                }
                              }
                            }
                            """,
                    null,
                    null
            ));

            GraphVersionDiff diff = fixture.service.diffVersions(v1.versionId(), v2.versionId());

            assertTrue(diff.metadataDiff().inputSchemaChanged());
            assertTrue(diff.metadataDiff().outputSchemaChanged());
            assertTrue(diff.metadataDiff().inputCompatibility() instanceof SchemaCompatibility.FullyCompatible);
            assertTrue(diff.metadataDiff().outputCompatibility() instanceof SchemaCompatibility.FullyCompatible);
            assertTrue(diff.metadataDiff().summary().stream()
                    .anyMatch(line -> line.startsWith("Input schema compatibility:")));
            assertTrue(diff.metadataDiff().summary().stream()
                    .anyMatch(line -> line.startsWith("Output schema compatibility:")));
        }
    }

    @Test
    void diffVersionsRejectsCrossDefinitionComparison() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition def1 = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diff-cross-a",
                    "tenant-a",
                    "sales",
                    "Cross A",
                    null, null, Map.of(), null, null
            ));
            GraphDefinition def2 = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "diff-cross-b",
                    "tenant-a",
                    "sales",
                    "Cross B",
                    null, null, Map.of(), null, null
            ));
            String source = """
                    graph crossDiff {
                      node echo : echo {
                        input { orderId = ctx.orderId }
                      }
                    }
                    """;
            GraphVersion v1 = fixture.service.createVersion(new CreateVersionCommand(
                    def1.definitionKey(), def1.tenantId(), def1.namespace(),
                    "1.0.0", source, null, null
            ));
            GraphVersion v2 = fixture.service.createVersion(new CreateVersionCommand(
                    def2.definitionKey(), def2.tenantId(), def2.namespace(),
                    "1.0.0", source, null, null
            ));

            GraphEngineServiceException exception = org.junit.jupiter.api.Assertions.assertThrows(
                    GraphEngineServiceException.class,
                    () -> fixture.service.diffVersions(v1.versionId(), v2.versionId())
            );
            assertEquals(GraphEngineServiceErrorCode.VALIDATION_FAILED, exception.errorCode());
        }
    }

    @Test
    void startSessionInstanceRefreshesProjectionFromDurableExecutionStatus() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "instant-session",
                    "tenant-a",
                    "sales",
                    "Instant session",
                    null, null, Map.of(), null, null
            ));
            GraphVersion version = publishSession(fixture, definition, "1.0.0", """
                    session instantSession {
                      idle_timeout = 5m
                      max_rounds = 2

                      phase done {
                        node finalize : echo {
                          input {
                            status = "completed"
                          }
                        }
                      }
                    }
                    """);

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    null,
                    null,
                    "session-001",
                    "alice",
                    Map.of("orderId", "session-001")
            ));

            assertEquals(version.versionId(), started.instance().versionId());
            assertEquals(GraphExecutionMode.SESSION, started.instance().executionMode());
            assertEquals(GraphInstanceStatus.COMPLETED, started.instance().status());
            assertEquals(
                    GraphInstanceStatus.COMPLETED,
                    fixture.service.getInstance(started.instance().instanceId()).status()
            );
        }
    }

    @Test
    void signalSessionInstanceUsesDurableSessionManagerForSuspendedSessions() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "approval-session",
                    "tenant-a",
                    "sales",
                    "Approval session",
                    null, null, Map.of(), null, null
            ));
            publishSession(fixture, definition, "1.0.0", waitingSessionDsl("approvalSession"));

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    null,
                    null,
                    "session-002",
                    "alice",
                    Map.of("orderId", "session-002")
            ));

            assertEquals(
                    GraphInstanceStatus.SUSPENDED,
                    fixture.awaitInstanceStatus(started.instance().instanceId(), GraphInstanceStatus.SUSPENDED)
            );

            SignalInstanceResult signaled = fixture.service.signalInstance(new SignalInstanceCommand(
                    started.instance().instanceId(),
                    null,
                    null,
                    Map.of("ready", true, "action", "approve"),
                    "alice"
            ));

            assertEquals(GraphInstanceStatus.COMPLETED, signaled.instance().status());
            assertEquals(
                    GraphInstanceStatus.COMPLETED,
                    fixture.service.getInstance(started.instance().instanceId()).status()
            );
        }
    }

    @Test
    void signalSessionInstanceWaitsForResuspendedProjection() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "review-session",
                    "tenant-a",
                    "sales",
                    "Review session",
                    null, null, Map.of(), null, null
            ));
            publishSession(fixture, definition, "1.0.0", twoStepWaitingSessionDsl("reviewSession"));

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    null,
                    null,
                    "session-003",
                    "alice",
                    Map.of("orderId", "session-003")
            ));

            assertEquals(
                    GraphInstanceStatus.SUSPENDED,
                    fixture.awaitInstanceStatus(started.instance().instanceId(), GraphInstanceStatus.SUSPENDED)
            );

            SignalInstanceResult signaled = fixture.service.signalInstance(new SignalInstanceCommand(
                    started.instance().instanceId(),
                    null,
                    null,
                    Map.of("ready", true, "action", "review"),
                    "alice"
            ));

            assertEquals(GraphInstanceStatus.SUSPENDED, signaled.instance().status());
            assertEquals(
                    GraphInstanceStatus.SUSPENDED,
                    fixture.service.getInstance(started.instance().instanceId()).status()
            );
        }
    }

    @Test
    void cancelSessionInstanceUsesDurableTerminationPath() {
        try (Fixture fixture = new Fixture()) {
            GraphDefinition definition = fixture.service.createDefinition(new CreateDefinitionCommand(
                    "cancel-session",
                    "tenant-a",
                    "sales",
                    "Cancel session",
                    null, null, Map.of(), null, null
            ));
            publishSession(fixture, definition, "1.0.0", waitingSessionDsl("cancelSession"));

            StartInstanceResult started = fixture.service.startInstance(new StartInstanceCommand(
                    definition.definitionKey(),
                    definition.tenantId(),
                    definition.namespace(),
                    null,
                    null,
                    "session-003",
                    "alice",
                    Map.of("orderId", "session-003")
            ));

            assertEquals(
                    GraphInstanceStatus.SUSPENDED,
                    fixture.awaitInstanceStatus(started.instance().instanceId(), GraphInstanceStatus.SUSPENDED)
            );
            GraphInstance current = fixture.service.getInstance(started.instance().instanceId());

            GraphInstance cancelled = fixture.service.cancelInstance(
                    current.instanceId(),
                    "user cancelled",
                    current.revision()
            );

            assertEquals(GraphInstanceStatus.CANCELLED, cancelled.status());
        }
    }

    private static GraphVersion publish(Fixture fixture, GraphDefinition definition, String version) {
        GraphVersion created = fixture.service.createVersion(new CreateVersionCommand(
                definition.definitionKey(),
                definition.tenantId(),
                definition.namespace(),
                version,
                """
                        graph routeableOrder {
                          node echo : echo {
                            input {
                              version = ctx.version
                            }
                          }
                        }
                        """,
                null,
                GraphMigrationPolicy.PIN_VERSION
        ));
        return fixture.service.publishVersion(created.versionId(), created.revision()).version();
    }

    private static GraphVersion publishSession(Fixture fixture,
                                               GraphDefinition definition,
                                               String version,
                                               String source) {
        GraphVersion created = fixture.service.createVersion(new CreateVersionCommand(
                definition.definitionKey(),
                definition.tenantId(),
                definition.namespace(),
                version,
                source,
                null,
                GraphMigrationPolicy.PIN_VERSION
        ));
        return fixture.service.publishVersion(created.versionId(), created.revision()).version();
    }

    private static GraphVersion publishPendingSignals(Fixture fixture, GraphDefinition definition, String version) {
        GraphVersion created = fixture.service.createVersion(new CreateVersionCommand(
                definition.definitionKey(),
                definition.tenantId(),
                definition.namespace(),
                version,
                """
                        graph pendingSignals {
                          await approval {
                            event "approval.completed" where orderId = ctx.orderId {
                              signal_schema {
                                status: String
                              }
                            }
                          }

                          await review {
                            event "review.completed" where orderId = ctx.orderId {
                              signal_schema {
                                status: String
                              }
                            }
                          }
                        }
                        """,
                null,
                GraphMigrationPolicy.PIN_VERSION
        ));
        return fixture.service.publishVersion(created.versionId(), created.revision()).version();
    }

    private static GraphVersion publishStateMachine(Fixture fixture,
                                                    GraphDefinition definition,
                                                    String version,
                                                    String source) {
        GraphVersion created = fixture.service.createVersion(new CreateVersionCommand(
                definition.definitionKey(),
                definition.tenantId(),
                definition.namespace(),
                version,
                source,
                null,
                GraphMigrationPolicy.PIN_VERSION
        ));
        return fixture.service.publishVersion(created.versionId(), created.revision()).version();
    }

    private static String waitingSessionDsl(String sessionName) {
        return """
                session %s {
                  idle_timeout = 5m
                  max_rounds = 4

                  phase awaitDecision {
                    max_rounds = 3
                    yield_on = [capture]
                    round {
                      node capture : echo {
                        input {
                          ready = ctx.round.input.ready
                          action = ctx.round.input.action
                        }
                      }
                    }
                    until capture.output.ready == true
                    then {
                      capture.output.action == "approve" -> approved
                      otherwise -> rejected
                    }
                  }

                  phase approved {
                    node finalize : echo {
                      input {
                        status = "approved"
                      }
                    }
                  }

                  phase rejected {
                    node finalize : echo {
                      input {
                        status = "rejected"
                      }
                    }
                  }
                }
                """.formatted(sessionName);
    }

    private static String slowSessionDsl(String sessionName) {
        return """
                session %s {
                  idle_timeout = 5m
                  max_rounds = 1

                  phase work {
                    round {
                      node slow : slowEcho {
                        input {
                          ready = true
                        }
                      }
                    }
                    until slow.output.ready == true
                    then {
                      otherwise -> done
                    }
                  }

                  phase done {
                    node finalize : echo {
                      input {
                        status = "completed"
                      }
                    }
                  }
                }
                """.formatted(sessionName);
    }

    private static String twoStepWaitingSessionDsl(String sessionName) {
        return """
                session %s {
                  idle_timeout = 5m
                  max_rounds = 4

                  phase firstDecision {
                    max_rounds = 3
                    yield_on = [capture]
                    round {
                      node capture : echo {
                        input {
                          ready = ctx.round.input.ready
                          action = ctx.round.input.action
                        }
                      }
                    }
                    until capture.output.ready == true
                    then {
                      capture.output.action == "approve" -> approved
                      otherwise -> secondDecision
                    }
                  }

                  phase secondDecision {
                    max_rounds = 3
                    yield_on = [capture]
                    round {
                      node capture : echo {
                        input {
                          ready = ctx.round.input.ready
                          action = ctx.round.input.action
                        }
                      }
                    }
                    until capture.output.ready == true
                    then {
                      capture.output.action == "approve" -> approved
                      otherwise -> rejected
                    }
                  }

                  phase approved {
                    node finalize : echo {
                      input {
                        status = "approved"
                      }
                    }
                  }

                  phase rejected {
                    node finalize : echo {
                      input {
                        status = "rejected"
                      }
                    }
                  }
                }
                """.formatted(sessionName);
    }

    private static WorkItem remoteWorkerItem(String itemId,
                                             ExecutionIdentity identity,
                                             WorkItemStatus status,
                                             int retryCount,
                                             int maxRetries,
                                             String claimToken,
                                             long version,
                                             Instant now) {
        ExecutionIdentity remoteIdentity = identity.withRouting(identity.routeKey(), "workers.ai");
        RemoteWorkerEnvelope envelope = new RemoteWorkerEnvelope(
                "supportClassifier",
                "workers.ai",
                Map.of("orderId", remoteIdentity.businessKey()),
                new RemoteWorkerEnvelope.RetryPolicy(3, Duration.ofSeconds(2), BackoffStrategy.EXPONENTIAL),
                new RemoteWorkerEnvelope.ExecutionContext(
                        remoteIdentity.graphName(),
                        remoteIdentity.executionId(),
                        "riskCheck",
                        remoteIdentity.graphName(),
                        remoteIdentity.graphVersion(),
                        remoteIdentity.tenantId(),
                        remoteIdentity.namespace(),
                        remoteIdentity.businessKey()
                ),
                now
        );
        return WorkItem.builder()
                .itemId(itemId)
                .executionIdentity(remoteIdentity)
                .itemType(WorkItemType.EXECUTE_NODE)
                .nodeId("riskCheck")
                .priority(40)
                .status(status)
                .claimOwner(status == WorkItemStatus.CLAIMED ? "worker-ai" : null)
                .claimToken(status == WorkItemStatus.CLAIMED ? claimToken : null)
                .claimUntil(status == WorkItemStatus.CLAIMED ? now.plusSeconds(300) : null)
                .retryCount(retryCount)
                .maxRetries(maxRetries)
                .payload(JsonCodec.DEFAULT.serialize(envelope.toMap()))
                .version(version)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static List<GraphAuditEntry> controlActionAudits(Fixture fixture, String instanceId) {
        return fixture.service.queryInstanceAuditLog(instanceId, 0, 50).stream()
                .filter(entry -> entry.eventType() == AuditEventType.CONTROL_ACTION)
                .toList();
    }

    private static GraphAuditEntry controlActionWithAttemptStatus(List<GraphAuditEntry> entries, String attemptStatus) {
        return entries.stream()
                .filter(entry -> entry.outputJson().contains("\"attemptStatus\":\"" + attemptStatus + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing control action audit with attemptStatus=" + attemptStatus));
    }

    private static final class RecordingMetricsObserver implements GraphEngineMetricsObserver {
        private final List<String> instanceCompletedStatuses = new ArrayList<>();
        private final List<String> instanceStartModes = new ArrayList<>();
        private final List<String> operationsSnapshotHealthes = new ArrayList<>();
        private int versionPublishedCount;
        private int instanceStartedCount;
        private int taskClaimedCount;
        private int taskCompletedCount;
        private int operationsSnapshotCount;
        private int lastOperationsDeadLetterCount;
        private int lastOperationsFailedInstanceCount;
        private int lastOperationsSuspendedInstanceCount;
        private int lastOperationsDeadLetterOldestAgeSeconds;
        private int lastOperationsSuspendedOldestAgeSeconds;

        @Override
        public void onVersionPublished(String definitionKey, String tenantId, String namespace) {
            versionPublishedCount++;
        }

        @Override
        public void onInstanceStarted(String definitionKey, String tenantId, String namespace, String executionMode) {
            instanceStartedCount++;
            instanceStartModes.add(executionMode);
        }

        @Override
        public void onInstanceCompleted(String definitionKey, String tenantId, String namespace,
                                        String executionMode, String status) {
            instanceCompletedStatuses.add(status);
        }

        @Override
        public void onTaskClaimed(String definitionKey, String tenantId, String namespace, String nodeId) {
            taskClaimedCount++;
        }

        @Override
        public void onTaskCompleted(String definitionKey, String tenantId, String namespace, String nodeId) {
            taskCompletedCount++;
        }

        @Override
        public void onOperationsSnapshot(String tenantId, String namespace, String health,
                                         int deadLetterCount, int failedInstanceCount,
                                         int suspendedInstanceCount, int activeDeploymentCount,
                                         boolean truncated, boolean controlPlaneAvailable) {
            operationsSnapshotCount++;
            operationsSnapshotHealthes.add(health);
            lastOperationsDeadLetterCount = deadLetterCount;
            lastOperationsFailedInstanceCount = failedInstanceCount;
            lastOperationsSuspendedInstanceCount = suspendedInstanceCount;
        }

        @Override
        public void onOperationsSnapshot(String tenantId, String namespace, String health,
                                         int deadLetterCount, int failedInstanceCount,
                                         int suspendedInstanceCount, int activeDeploymentCount,
                                         boolean truncated, boolean controlPlaneAvailable,
                                         int deadLetterOldestAgeSeconds,
                                         int suspendedOldestAgeSeconds) {
            onOperationsSnapshot(
                    tenantId,
                    namespace,
                    health,
                    deadLetterCount,
                    failedInstanceCount,
                    suspendedInstanceCount,
                    activeDeploymentCount,
                    truncated,
                    controlPlaneAvailable
            );
            lastOperationsDeadLetterOldestAgeSeconds = deadLetterOldestAgeSeconds;
            lastOperationsSuspendedOldestAgeSeconds = suspendedOldestAgeSeconds;
        }
    }

    private static DurableSessionManager sessionManager(DefaultGraphEngineService service) {
        try {
            Field field = DefaultGraphEngineService.class.getDeclaredField("sessionManager");
            field.setAccessible(true);
            return (DurableSessionManager) field.get(service);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to access DefaultGraphEngineService.sessionManager", exception);
        }
    }

    private static final class CountingExecutionCheckpointStore extends InMemoryExecutionCheckpointStore {
        private final AtomicInteger sessionSnapshotLoadCalls = new AtomicInteger();

        @Override
        public java.util.Optional<ExecutionCheckpoint> load(String executionId,
                                                            CheckpointType checkpointType,
                                                            String nodeId) {
            if (checkpointType == CheckpointType.EXTENSION_SNAPSHOT
                    && ExecutionCheckpointSessionStore.SNAPSHOT_NODE_ID.equals(nodeId)) {
                sessionSnapshotLoadCalls.incrementAndGet();
            }
            return super.load(executionId, checkpointType, nodeId);
        }

        private int sessionSnapshotLoadCalls() {
            return sessionSnapshotLoadCalls.get();
        }

        private void resetLoadCalls() {
            sessionSnapshotLoadCalls.set(0);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final InMemoryExecutionStore executionStore = new InMemoryExecutionStore();
        private final InMemoryExecutionCheckpointStore checkpointStore;
        private final InMemoryWaitStore waitStore = new InMemoryWaitStore();
        private final RecordingWorkItemStore workItemStore = new RecordingWorkItemStore();
        private final RecordingEventMatcherStore eventMatcherStore = new RecordingEventMatcherStore();
        private final InMemoryTaskInboxStore taskInboxStore = new InMemoryTaskInboxStore();
        private final InMemoryGraphRegistryStore graphRegistryStore = new InMemoryGraphRegistryStore();
        private final InMemoryAuditJournalStore auditJournalStore = new InMemoryAuditJournalStore();
        private final RecordingControlPlaneService controlPlaneService = new RecordingControlPlaneService();
        private final InMemoryTimerService timerService = new InMemoryTimerService();
        private final InMemoryGraphDefinitionStore graphDefinitionStore = new InMemoryGraphDefinitionStore();
        private final InMemoryGraphVersionStore graphVersionStore = new InMemoryGraphVersionStore();
        private final InMemoryGraphDeploymentStore graphDeploymentStore = new InMemoryGraphDeploymentStore();
        private final InMemoryGraphInstanceStore graphInstanceStore = new InMemoryGraphInstanceStore();
        private final DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        private final RecordingMetricsObserver metricsObserver = new RecordingMetricsObserver();
        private final TaskStore baseTaskStore = new TaskInboxTaskStore(taskInboxStore, executionStore);
        private final DurableGraphEngine durableEngine;
        private final DefaultGraphEngineService service;

        private Fixture() {
            this(true);
        }

        private Fixture(boolean withDurableEngine) {
            this(withDurableEngine, new InMemoryExecutionCheckpointStore());
        }

        private Fixture(boolean withDurableEngine, InMemoryExecutionCheckpointStore checkpointStore) {
            this(withDurableEngine, checkpointStore, null);
        }

        private Fixture(boolean withDurableEngine,
                        InMemoryExecutionCheckpointStore checkpointStore,
                        GraphOperationsPolicy operationsPolicy) {
            this.checkpointStore = checkpointStore;
            registry.register("echo", (Operator<Object, Object>) (input, ctx) -> input);
            registry.register("slowEcho", (Operator<Object, Object>) (input, ctx) -> {
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while simulating a live session snapshot", interruptedException);
                }
                return input;
            });
            registry.registerRaw("suspend", (SuspendableOperator<Void, Object>) (input, ctx) ->
                    OperatorResult.suspend("signal-ready"));
            UserTaskOperator userTaskOperator = new UserTaskOperator(baseTaskStore, "user-task", null, List.of("ops"));
            registry.registerRaw("user", userTaskOperator);
            registry.registerRaw("user-task", userTaskOperator);
            durableEngine = withDurableEngine
                    ? DurableGraphEngine.builder()
                    .registry(registry)
                    .executionStore(executionStore)
                    .executionCheckpointStore(checkpointStore)
                    .waitStore(waitStore)
                    .workItemStore(workItemStore)
                    .checkpointCodec(TEST_CODEC)
                    .eventCorrelationStore(StoreAdapters.wrapEventMatcherStore(
                            eventMatcherStore,
                            checkpointStore,
                            executionStore,
                            TEST_CODEC,
                            null
                    ))
                    .graphRegistryStore(graphRegistryStore)
                    .addGraphDefinitionCodec(GraphEngineDslCodecs.graphDefinitionCodec(
                            JsonCodec.DEFAULT,
                            operatorRegistry -> new com.leanowtech.bloge.dsl.compiler.DslCompiler(
                                    operatorRegistry,
                                    com.leanowtech.bloge.core.spi.SecretProvider.NONE,
                                    JsonCodec.DEFAULT
                            ).withEventMatcherStore(
                                    eventMatcherStore,
                                    checkpointStore,
                                    executionStore
                            ).withRemoteWorkerOperatorFactory(
                                    com.leanowtech.bloge.dsl.compiler.RemoteWorkerOperatorFactories.durable(
                                            workItemStore,
                                            null,
                                            JsonCodec.DEFAULT
                                    )
                            )
                    ))
                    .listeners(List.of())
                    .interceptors(List.of())
                    .inMemorySuspendTtl(Duration.ofMillis(200))
                    .build()
                    : null;

            GraphEngineStores stores = new GraphEngineStores(
                    graphDefinitionStore,
                    graphVersionStore,
                    graphDeploymentStore,
                    graphInstanceStore
            );
            GraphEngineRuntimeSupport.Builder runtimeSupportBuilder = GraphEngineRuntimeSupport.builder()
                    .operatorRegistry(registry)
                    .graphRegistryStore(graphRegistryStore)
                    .executionStore(executionStore)
                    .executionCheckpointStore(checkpointStore)
                    .eventMatcherStore(eventMatcherStore)
                    .waitStore(waitStore)
                    .taskInboxStore(taskInboxStore)
                    .taskStore(baseTaskStore)
                    .auditJournalStore(auditJournalStore)
                    .workItemStore(workItemStore)
                    .checkpointCodec(TEST_CODEC)
                    .controlPlaneService(controlPlaneService)
                    .timerService(timerService)
                    .metricsObserver(metricsObserver);
            if (durableEngine != null) {
                runtimeSupportBuilder.durableGraphEngine(durableEngine);
            }
            if (operationsPolicy != null) {
                runtimeSupportBuilder.operationsPolicy(operationsPolicy);
            }
            service = new DefaultGraphEngineService(stores, runtimeSupportBuilder.build());
        }

        private GraphInstanceStatus awaitInstanceStatus(String instanceId, GraphInstanceStatus expectedStatus) {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
            GraphInstanceStatus lastSeen = null;
            while (Instant.now().isBefore(deadline)) {
                lastSeen = service.getInstance(instanceId).status();
                if (lastSeen == expectedStatus) {
                    return lastSeen;
                }
                sleep();
            }
            throw new AssertionError("Expected instance status " + expectedStatus + " but observed " + lastSeen);
        }

        private void sleep() {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", interruptedException);
            }
        }

        private GraphInstance createManagedGraphInstance(String instanceId, GraphInstanceStatus status) {
            Instant now = Instant.now();
            if (graphVersionStore.get("ver-1").isEmpty()) {
                graphVersionStore.create(new GraphVersion(
                        "ver-1",
                        "def-approval-flow",
                        "1.0.0",
                        "hash-ver-1",
                        "graph approvalFlow {}",
                        null,
                        null,
                        "artifact-ver-1",
                        GraphMigrationPolicy.PIN_VERSION,
                        GraphVersionStatus.PUBLISHED,
                        0,
                        now,
                    now,
                    now
                ));
            }
            GraphDefinition definition = graphDefinitionStore.getByKey("default", "default", "approval-flow")
                    .orElseGet(() -> {
                        GraphDefinition created = new GraphDefinition(
                                "def-approval-flow",
                                "approval-flow",
                                "default",
                                "default",
                                "Approval flow",
                                null,
                                null,
                                Map.of(),
                                null,
                                null,
                                GraphDefinitionStatus.ACTIVE,
                                0,
                                now,
                                now
                        );
                        graphDefinitionStore.create(created);
                        return created;
                    });
            return createManagedGraphInstance(instanceId, definition, "ver-1", GraphExecutionMode.GRAPH, status);
        }

        private GraphInstance createManagedGraphInstance(String instanceId,
                                                         GraphDefinition definition,
                                                         GraphVersion version,
                                                         GraphInstanceStatus status) {
            return createManagedGraphInstance(
                    instanceId,
                    definition,
                    version.versionId(),
                    GraphExecutionMode.GRAPH,
                    status
            );
        }

        private GraphInstance createManagedGraphInstance(String instanceId,
                                                         GraphDefinition definition,
                                                         String versionId,
                                                         GraphExecutionMode executionMode,
                                                         GraphInstanceStatus status) {
            Instant now = Instant.now();
            GraphInstance instance = new GraphInstance(
                    instanceId,
                    definition.definitionKey(),
                    versionId,
                    definition.tenantId(),
                    definition.namespace(),
                    "business-" + instanceId,
                    executionMode,
                    status,
                    "starter",
                    Map.of("orderId", "A-1"),
                    0,
                    now,
                    now,
                    status.terminal() ? now : null
            );
            graphInstanceStore.create(instance);
            executionStore.create(ExecutionInstance.builder(identity(instance))
                    .status(toExecutionStatus(status))
                    .createdAt(now)
                    .updatedAt(now)
                    .completedAt(status.terminal() ? now : null)
                    .build());
            return instance;
        }

        private ExecutionIdentity identity(GraphInstance instance) {
            return new ExecutionIdentity(
                    instance.tenantId(),
                    instance.namespace(),
                    instance.businessKey(),
                    instance.instanceId(),
                    ExecutionType.GRAPH,
                    instance.definitionKey(),
                    instance.versionId(),
                    "hash-" + instance.versionId(),
                    null,
                    "shard-a",
                    null,
                    null
            );
        }

        private static ExecutionStatus toExecutionStatus(GraphInstanceStatus status) {
            return switch (status) {
                case RUNNING -> ExecutionStatus.RUNNING;
                case COMPLETED -> ExecutionStatus.COMPLETED;
                case FAILED -> ExecutionStatus.FAILED;
                case SUSPENDED -> ExecutionStatus.SUSPENDED;
                case CANCELLED -> ExecutionStatus.CANCELLED;
                case TERMINATED -> ExecutionStatus.TERMINATED;
            };
        }

        @Override
        public void close() {
            service.close();
            if (durableEngine != null) {
                durableEngine.close();
            }
        }
    }

    private static final class RecordingEventMatcherStore extends InMemoryEventMatcherStore {
        private volatile EventMatcherQuery lastQuery;

        @Override
        public List<EventMatcher> query(EventMatcherQuery query) {
            lastQuery = query;
            return super.query(query);
        }

        private EventMatcherQuery lastQuery() {
            return lastQuery;
        }
    }

    private static final class RecordingWorkItemStore extends InMemoryWorkItemStore {
        private volatile WorkItemQuery lastQuery;
        private volatile String restoreFailureItemId;
        private volatile RuntimeException restoreFailure;

        @Override
        public List<WorkItem> query(WorkItemQuery query) {
            lastQuery = query;
            return super.query(query);
        }

        @Override
        public WorkItem restoreDeadLetter(String itemId) {
            RuntimeException failure = restoreFailure;
            if (restoreFailureItemId != null && restoreFailureItemId.equals(itemId) && failure != null) {
                throw failure;
            }
            return super.restoreDeadLetter(itemId);
        }

        private void failRestore(String itemId, RuntimeException failure) {
            restoreFailureItemId = itemId;
            restoreFailure = failure;
        }

        private WorkItemQuery lastQuery() {
            return lastQuery;
        }
    }

    private static final class RecordingControlPlaneService implements ControlPlaneService {
        private final List<ExecutionTransitionLogEntry> transitions = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<DeadLetterEntry> deadLetters = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public List<ExecutionInstance> queryExecutions(com.leanowtech.bloge.core.runtime.execution.ExecutionQuery query) {
            return List.of();
        }

        @Override
        public List<TaskInbox> queryTasks(TaskInboxQuery query) {
            return List.of();
        }

        @Override
        public List<WorkItem> queryWorkItems(WorkItemQuery query) {
            return List.of();
        }

        @Override
        public List<ExecutionTransitionLogEntry> queryExecutionTransitions(ExecutionTransitionQuery query) {
            return transitions.stream()
                    .filter(entry -> entry.identity().executionId().equals(query.executionId()))
                    .skip((long) query.page() * query.size())
                    .limit(query.size())
                    .toList();
        }

        @Override
        public List<DeadLetterEntry> queryDeadLetters(DeadLetterQuery query) {
            return deadLetters.stream()
                    .filter(entry -> query.tenantId() == null || query.tenantId().equals(entry.identity().tenantId()))
                    .filter(entry -> query.namespace() == null || query.namespace().equals(entry.identity().namespace()))
                    .filter(entry -> query.itemId() == null || query.itemId().equals(entry.itemId()))
                    .filter(entry -> query.executionId() == null || query.executionId().equals(entry.identity().executionId()))
                    .filter(entry -> query.itemType() == null || query.itemType() == entry.itemType())
                    .filter(entry -> query.shardId() == null || query.shardId().equals(entry.identity().shardId()))
                    .filter(entry -> query.deadLetteredAfter() == null || !entry.deadLetteredAt().isBefore(query.deadLetteredAfter()))
                    .skip((long) query.page() * query.size())
                    .limit(query.size())
                    .toList();
        }
    }
}
