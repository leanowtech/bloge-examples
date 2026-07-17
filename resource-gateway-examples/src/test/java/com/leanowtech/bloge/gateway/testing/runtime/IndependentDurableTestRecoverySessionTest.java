package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.wait.WaitStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndependentDurableTestRecoverySessionTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);

    @Test
    @Timeout(15)
    void coldSignalsCommittedSuspensionAndAtomicallyPublishesTheTerminalAggregate()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = database("cold-signal")) {
            Fixture fixture = fixture(database, mapper);
            AtomicInteger realAfterCalls = new AtomicInteger();
            Graph graph = graph(realAfterCalls);
            DurableTestExecutionCheckpoint claimed = persistSuspendedExecution(fixture, graph);

            AtomicInteger controlledAfterCalls = new AtomicInteger();
            ExecutionOptions recoveryOptions = ExecutionOptions.builder()
                    .operatorResolver(request -> "after".equals(request.site().nodeId())
                            ? (Operator<Object, String>) (input, context) -> {
                                controlledAfterCalls.incrementAndGet();
                                return "controlled:" + input;
                            }
                            : request.defaultOperator())
                    .executionServices(ExecutionServices.builder().build())
                    .build();
            InvocationRecorder recorder = new InvocationRecorder(mapper);

            try (IndependentDurableTestEngineFactory.RecoverySession session =
                         fixture.factory().openRecoverySession(
                                 claimed, recorder, recoveryOptions)) {
                IndependentDurableTestEngineFactory.RecoveryBoundary boundary =
                        session.signalAndAwait(
                                graph, "wait", "approved");

                assertThat(boundary.executionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
                assertThat(boundary.boundaryType()).isEqualTo("NODE_BOUNDARY");
                assertThat(boundary.nodeId()).isEqualTo("wait");
                assertThat(boundary.stateVersion())
                        .isGreaterThan(claimed.engineState().stateVersion());
                assertThat(controlledAfterCalls).hasValue(1);
                assertThat(realAfterCalls).hasValue(0);

                IndependentDurableTestEngineFactory.PreparedRecovery prepared =
                        session.prepare("checkpoint-terminal");
                DurableTestExecutionCheckpoint terminal = terminal(
                        claimed, prepared, fixture.integrity());
                fixture.repository().advance(terminal,
                        new DurableTestExecutionCheckpointRepository.Fence(
                                claimed.lifecycle().ownerId(),
                                claimed.lifecycle().leaseEpoch(),
                                claimed.lifecycle().revision()),
                        prepared.engineStateMutation());
            }

            assertThat(fixture.store().executionStore().get("engine-a"))
                    .get().extracting(execution -> execution.status())
                    .isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(fixture.store().waitStore().findByExecution("engine-a")).isEmpty();
            assertThat(fixture.repository().find("tenant-a", "test", "run-a"))
                    .get().satisfies(checkpoint -> {
                        assertThat(checkpoint.lifecycle().status())
                                .isEqualTo(DurableTestExecutionCheckpoint.Status.TERMINAL);
                        assertThat(checkpoint.engineState().boundaryType())
                                .isEqualTo("NODE_BOUNDARY");
                    });
        }
    }

    @Test
    @Timeout(15)
    void durableOperatorStartGateDefersTheExactSubjectUntilColdSignal() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = database("operator-start-gate")) {
            Fixture fixture = fixture(database, mapper);
            AtomicInteger subjectCalls = new AtomicInteger();
            Operator<Object, Object> subject = (input, context) -> {
                subjectCalls.incrementAndGet();
                return input;
            };
            Graph graph = OperatorMicroGraphRunner.durableMicroGraph(
                    "operator-a", subject);
            DurableTestExecutionCheckpoint claimed = persistSuspendedExecution(
                    fixture, graph,
                    new GraphContext(Map.of("operatorInput", Map.of("name", "Ada"))),
                    OperatorMicroGraphRunner.DURABLE_START_NODE_ID,
                    "durable-operator-start");

            assertThat(subjectCalls).hasValue(0);
            ExecutionOptions recoveryOptions = ExecutionOptions.builder()
                    .executionServices(ExecutionServices.builder().build())
                    .build();
            try (IndependentDurableTestEngineFactory.RecoverySession session =
                         fixture.factory().openRecoverySession(
                                 claimed, new InvocationRecorder(mapper), recoveryOptions)) {
                var boundary = session.signalAndAwait(
                        graph, OperatorMicroGraphRunner.DURABLE_START_NODE_ID, Map.of());

                assertThat(boundary.executionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
                assertThat(subjectCalls).hasValue(1);
                assertThat(session.prepare("checkpoint-operator-terminal")
                        .engineStateMutation().engineState().stateVersion())
                        .isGreaterThan(claimed.engineState().stateVersion());
            }
        }
    }

    @Test
    void rejectsAControlCheckpointThatHasNotEnteredResuming() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = database("wrong-state")) {
            Fixture fixture = fixture(database, mapper);
            DurableTestExecutionCheckpoint checkpoint = control(
                    fixture.integrity(), DurableTestExecutionCheckpoint.Status.SUSPENDED,
                    new DurableTestExecutionCheckpoint.EngineState(
                            "checkpoint-a", "wait", "SUSPEND", 1, 1, SHA_D));

            assertThatThrownBy(() -> fixture.factory().openRecoverySession(
                    checkpoint, new InvocationRecorder(mapper),
                    ExecutionOptions.builder()
                            .executionServices(ExecutionServices.builder().build())
                            .build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("RESUMING");
        }
    }

    @Test
    @Timeout(15)
    void rejectsAControlCheckpointWhoseEngineVersionDoesNotMatchCommittedState()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = database("engine-version-drift")) {
            Fixture fixture = fixture(database, mapper);
            Graph graph = graph(new AtomicInteger());
            DurableTestExecutionCheckpoint claimed = persistSuspendedExecution(fixture, graph);
            DurableTestExecutionCheckpoint.EngineState current = claimed.engineState();
            DurableTestExecutionCheckpoint drifted = control(
                    fixture.integrity(), DurableTestExecutionCheckpoint.Status.RESUMING,
                    new DurableTestExecutionCheckpoint.EngineState(
                            current.checkpointRef(), current.nodeId(), current.boundaryType(),
                            current.boundarySequence(), current.stateVersion() + 1,
                            current.closureFingerprint()));

            assertThatThrownBy(() -> {
                try (var ignored = fixture.factory().openRecoverySession(
                        drifted, new InvocationRecorder(mapper),
                        ExecutionOptions.builder()
                                .executionServices(ExecutionServices.builder().build())
                                .build())) {
                    // Opening the session must reject before recovery can mutate staged state.
                }
            }).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("version");
        }
    }

    @Test
    @Timeout(15)
    void rejectsAControlCheckpointThatDoesNotDescribeASuspendBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = database("boundary-type-drift")) {
            Fixture fixture = fixture(database, mapper);
            Graph graph = graph(new AtomicInteger());
            DurableTestExecutionCheckpoint claimed = persistSuspendedExecution(fixture, graph);
            DurableTestExecutionCheckpoint.EngineState current = claimed.engineState();
            DurableTestExecutionCheckpoint drifted = control(
                    fixture.integrity(), DurableTestExecutionCheckpoint.Status.RESUMING,
                    new DurableTestExecutionCheckpoint.EngineState(
                            current.checkpointRef(), current.nodeId(), "NODE_BOUNDARY",
                            current.boundarySequence(), current.stateVersion(),
                            current.closureFingerprint()));

            assertThatThrownBy(() -> {
                try (var ignored = fixture.factory().openRecoverySession(
                        drifted, new InvocationRecorder(mapper),
                        ExecutionOptions.builder()
                                .executionServices(ExecutionServices.builder().build())
                                .build())) {
                    // Opening the session must reject before recovery can mutate staged state.
                }
            }).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("suspend boundary");
        }
    }

    @Test
    @Timeout(15)
    void rejectsASignalNodeThatDiffersFromTheClaimedBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = database("boundary-node-drift")) {
            Fixture fixture = fixture(database, mapper);
            Graph graph = graph(new AtomicInteger());
            DurableTestExecutionCheckpoint claimed = persistSuspendedExecution(fixture, graph);
            DurableTestExecutionCheckpoint.EngineState current = claimed.engineState();
            DurableTestExecutionCheckpoint drifted = control(
                    fixture.integrity(), DurableTestExecutionCheckpoint.Status.RESUMING,
                    new DurableTestExecutionCheckpoint.EngineState(
                            current.checkpointRef(), "another-wait", current.boundaryType(),
                            current.boundarySequence(), current.stateVersion(),
                            current.closureFingerprint()));

            try (IndependentDurableTestEngineFactory.RecoverySession session =
                         fixture.factory().openRecoverySession(
                                 drifted, new InvocationRecorder(mapper),
                                 ExecutionOptions.builder()
                                         .executionServices(ExecutionServices.builder().build())
                                         .build())) {
                assertThatThrownBy(() -> session.signalAndAwait(graph, "wait", "approved"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("boundary node");
            }
        }
    }

    @Test
    @Timeout(15)
    void closingWithoutPrepareRollsBackCompletedSynchronousRecovery() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = database("unprepared-rollback")) {
            Fixture fixture = fixture(database, mapper);
            Graph graph = graph(new AtomicInteger());
            DurableTestExecutionCheckpoint claimed = persistSuspendedExecution(fixture, graph);
            ExecutionOptions controlledRecovery = ExecutionOptions.builder()
                    .operatorResolver(request -> "after".equals(request.site().nodeId())
                            ? (Operator<Object, String>) (input, context) -> "controlled"
                            : request.defaultOperator())
                    .executionServices(ExecutionServices.builder().build())
                    .build();

            try (IndependentDurableTestEngineFactory.RecoverySession session =
                         fixture.factory().openRecoverySession(
                                 claimed, new InvocationRecorder(mapper), controlledRecovery)) {
                assertThat(session.signalAndAwait(graph, "wait", "approved")
                        .executionStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            }

            assertThat(fixture.store().executionStore().get("engine-a"))
                    .get().extracting(execution -> execution.status())
                    .isEqualTo(ExecutionStatus.SUSPENDED);
            assertThat(fixture.store().waitStore().findByExecution("engine-a"))
                    .singleElement()
                    .satisfies(wait -> {
                        assertThat(wait.nodeId()).isEqualTo("wait");
                        assertThat(wait.status()).isEqualTo(WaitStatus.WAITING);
                    });
            assertThat(fixture.repository().find("tenant-a", "test", "run-a"))
                    .get().extracting(checkpoint -> checkpoint.lifecycle().status())
                    .isEqualTo(DurableTestExecutionCheckpoint.Status.RESUMING);
        }
    }

    private static Fixture fixture(TestRuntimeDatabase database, ObjectMapper mapper) {
        StagedBlogeDurableStateStore store =
                new StagedBlogeDurableStateStore(database.jdbc(), mapper);
        store.init();
        DurableTestExecutionCheckpointIntegrity integrity =
                new DurableTestExecutionCheckpointIntegrity(mapper);
        DatabaseDurableTestExecutionCheckpointRepository repository =
                new DatabaseDurableTestExecutionCheckpointRepository(
                        database.jdbc(), database.transactionManager(), mapper, integrity);
        repository.init();
        IndependentDurableTestEngineFactory factory =
                new IndependentDurableTestEngineFactory(
                        new DefaultOperatorRegistry(), new JacksonCheckpointCodec(mapper), store);
        return new Fixture(store, repository, integrity, factory, mapper);
    }

    private static Graph graph(AtomicInteger realAfterCalls) {
        SuspendableOperator<Void, String> wait = (input, context) ->
                OperatorResult.suspend("approval-key");
        Operator<Object, String> after = (input, context) -> {
            realAfterCalls.incrementAndGet();
            return "real";
        };
        return new GraphBuilder("controlled-cold-signal")
                .suspendNode("wait", wait)
                .node("after", after).dependsOn("wait")
                .build();
    }

    private static DurableTestExecutionCheckpoint persistSuspendedExecution(
            Fixture fixture, Graph graph) throws Exception {
        return persistSuspendedExecution(fixture, graph,
                new GraphContext(Map.of("requestId", "request-a")),
                "wait", "approval-key");
    }

    private static DurableTestExecutionCheckpoint persistSuspendedExecution(
            Fixture fixture,
            Graph graph,
            GraphContext context,
            String suspendedNodeId,
            String suspendKey) throws Exception {
        ExecutionServices services = ExecutionServices.builder()
                .idGenerator(scope -> "engine-a")
                .build();
        ExecutionOptions options = ExecutionOptions.builder()
                .executionServices(services)
                .build();
        InvocationRecorder recorder = new InvocationRecorder(fixture.mapper());
        try (IndependentDurableTestEngineFactory.RunSession session =
                     fixture.factory().openSession("engine-a", recorder, options)) {
            var result = session.execute(graph, context);
            assertThat(result.isSuspended()).isTrue();
            assertThat(result.suspendedNodes()).containsExactlyEntriesOf(
                    Map.of(suspendedNodeId, suspendKey));
            long stateVersion = fixture.store().executionStore().get("engine-a")
                    .orElseThrow().version();
            var mutation = session.prepare(
                    "checkpoint-suspended", suspendedNodeId, "SUSPEND", 1, stateVersion);
            DurableTestExecutionCheckpoint control = control(
                    fixture.integrity(), DurableTestExecutionCheckpoint.Status.RESUMING,
                    mutation.engineState());
            fixture.repository().create(control, mutation);
            return control;
        }
    }

    private static DurableTestExecutionCheckpoint terminal(
            DurableTestExecutionCheckpoint current,
            IndependentDurableTestEngineFactory.PreparedRecovery prepared,
            DurableTestExecutionCheckpointIntegrity integrity) {
        Instant updatedAt = current.lifecycle().updatedAt().plusSeconds(1);
        return integrity.seal(new DurableTestExecutionCheckpoint(
                current.schemaVersion(), current.scope(), current.runId(),
                current.engineExecutionId(), current.dependencies(),
                prepared.fixtureConsumptionState(), current.executionServiceState(),
                prepared.engineStateMutation().engineState(),
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.TERMINAL,
                        current.lifecycle().ownerId(), current.lifecycle().leaseEpoch(),
                        current.lifecycle().revision() + 1,
                        current.lifecycle().createdAt(), updatedAt,
                        current.lifecycle().leaseExpiresAt().plusSeconds(1)), ""));
    }

    private static DurableTestExecutionCheckpoint control(
            DurableTestExecutionCheckpointIntegrity integrity,
            DurableTestExecutionCheckpoint.Status status,
            DurableTestExecutionCheckpoint.EngineState engineState) {
        Instant now = Instant.parse("2026-07-16T08:00:00Z");
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION, "plan-a", SHA_A,
                "GRAPH_CONTRACT_TEST", SHA_B, SHA_C,
                List.of(), List.of(), List.of(),
                Map.of("unmatchedExternalEffect", "DENY"), List.of());
        ExecutionServiceStateSnapshot providerMaterial =
                new ExecutionServiceStateSnapshot(
                        ExecutionServiceStateSnapshot.SCHEMA_VERSION,
                        SHA_A, SHA_B, now, Map.of(), Map.of(), List.of(),
                        true, List.of(), SHA_D);
        ExecutionServiceStateSnapshot provider = new ExecutionServiceStateSnapshot(
                providerMaterial.schemaVersion(), providerMaterial.planFingerprint(),
                providerMaterial.bindingSetFingerprint(), providerMaterial.logicalTime(),
                providerMaterial.randomScopeCursors(), providerMaterial.uuidScopeCursors(),
                providerMaterial.usages(), providerMaterial.restorable(),
                providerMaterial.restoreGaps(), ProtocolFingerprint.of(
                new ObjectMapper().findAndRegisterModules(),
                providerMaterial.fingerprintMaterial()));
        return integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                "run-a", "engine-a",
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        plan,
                        new DurableTestExecutionCheckpoint.ExactFixtureRef(
                                "fixture-a", 1, SHA_C),
                        "DENY_REAL",
                        new DurableTestExecutionCheckpoint.AuthoritySnapshot(
                                "FAIL_CLOSED", SHA_D),
                        new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                                "GRAPH", "controlled-cold-signal", SHA_B)),
                new FixtureConsumptionStateSnapshot(
                        FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                        Map.of(), Map.of(), Map.of(), ""),
                provider, engineState,
                new DurableTestExecutionCheckpoint.Lifecycle(
                        status, "recovery-a", 2, 0, now, now,
                        now.plusSeconds(120)), ""));
    }

    private static TestRuntimeDatabase database(String name) {
        return new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:durable-recovery-" + name + "-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1",
                "sa", "", 3));
    }

    private record Fixture(
            StagedBlogeDurableStateStore store,
            DatabaseDurableTestExecutionCheckpointRepository repository,
            DurableTestExecutionCheckpointIntegrity integrity,
            IndependentDurableTestEngineFactory factory,
            ObjectMapper mapper) {
    }
}
