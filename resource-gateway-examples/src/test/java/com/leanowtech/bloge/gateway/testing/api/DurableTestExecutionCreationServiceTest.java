package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionIntent;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.Kind;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestCreationRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.GovernedExecutionServices;
import com.leanowtech.bloge.gateway.testing.runtime.IndependentDurableTestEngineFactory;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorMicroGraphRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DurableTestExecutionCreationServiceTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);

    private ObjectMapper mapper;
    private DurableTestExecutionCheckpointRepository checkpoints;
    private DurableTestRecoveryAuthorizer authorizer;
    private DurableTestCreationRuntime runtime;
    private TestSecurityEventRepository securityEvents;
    private DurableTestCreationLeaseCoordinator leases;
    private TestRuntimeAdmissionGate admissions;
    private AdmissionGuard admissionGuard;
    private DurableTestExecutionCreationService service;
    private TestValues values;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        checkpoints = mock(DurableTestExecutionCheckpointRepository.class);
        authorizer = mock(DurableTestRecoveryAuthorizer.class);
        runtime = mock(DurableTestCreationRuntime.class);
        securityEvents = mock(TestSecurityEventRepository.class);
        when(securityEvents.boundAppend(any())).thenReturn(TestRuntimeTransactionMutation.noop());
        admissions = mock(TestRuntimeAdmissionGate.class);
        admissionGuard = mock(AdmissionGuard.class);
        when(admissions.admit(any(), any())).thenReturn(admissionGuard);
        leases = DurableTestCreationLeaseCoordinator.passive(
                "creator-instance", Duration.ofMinutes(2));
        service = new DurableTestExecutionCreationService(
                checkpoints, authorizer, runtime,
                new DurableTestExecutionCheckpointIntegrity(mapper), securityEvents, mapper,
                leases, admissions);
        values = new TestValues(mapper);
    }

    @AfterEach
    void tearDown() {
        leases.close();
    }

    @Test
    void replaysCommittedCommandBeforeRereadingMutableDependencies() {
        var committed = values.committedResult(true);
        when(checkpoints.findInitialCreationResult(
                any(), any(), any(), any())).thenReturn(Optional.of(committed));

        DurableTestExecutionCreateResponse response = service.create(
                values.request(Map.of("customerId", "c-1")), identity());

        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.execution().runId()).isEqualTo("run-created");
        assertThat(response.execution().status()).isEqualTo("SUSPENDED");
        verifyNoInteractions(authorizer, runtime);
        verifyNoInteractions(admissions);
        verify(securityEvents, never()).boundAppend(any());
    }

    @Test
    void freezesExecutesAndAtomicallyCommitsAnAcquiredCreation() {
        DurableTestExecutionCreateRequest request = values.request(
                Map.of("customerId", "c-1"));
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(authorizer.authorizeCreation(request, identity()))
                .thenReturn(values.authorized());
        when(checkpoints.reserveInitialCreation(any()))
                .thenReturn(values.pendingResult(true));
        when(runtime.prepare(any(), any(), any(), any()))
                .thenReturn(values.prepared());
        when(checkpoints.commitInitialCreation(any(), any(), any(), any()))
                .thenAnswer(invocation -> values.committedResult(
                        invocation.getArgument(1), false));

        DurableTestExecutionCreateResponse response = service.create(request, identity());

        assertThat(response.idempotentReplay()).isFalse();
        assertThat(response.execution()).satisfies(execution -> {
            assertThat(execution.runId()).isEqualTo("run-created");
            assertThat(execution.engineExecutionId()).isEqualTo("engine-created");
            assertThat(execution.status()).isEqualTo("SUSPENDED");
            assertThat(execution.engineBoundary().nodeId()).isEqualTo("approval");
            assertThat(execution.fixture().fingerprint()).isEqualTo(SHA_C);
        });
        ArgumentCaptor<DurableTestExecutionCheckpoint> checkpoint =
                ArgumentCaptor.forClass(DurableTestExecutionCheckpoint.class);
        verify(checkpoints).commitInitialCreation(any(), checkpoint.capture(), any(), any());
        assertThat(checkpoint.getValue()).satisfies(value -> {
            assertThat(value.dependencies()).isEqualTo(values.dependencies());
            assertThat(value.lifecycle().revision()).isZero();
            assertThat(value.lifecycle().ownerId()).isEqualTo("creator-instance");
            assertThat(value.engineState().boundaryType()).isEqualTo("SUSPEND");
        });
        verify(securityEvents).boundAppend(any());
        ArgumentCaptor<AdmissionIntent> admission = ArgumentCaptor.forClass(AdmissionIntent.class);
        verify(admissions).admit(any(), admission.capture());
        assertThat(admission.getValue()).satisfies(intent -> {
            assertThat(intent.kind()).isEqualTo(Kind.DURABLE_CREATION);
            assertThat(intent.stableRequestKey()).isEqualTo("create-1");
            assertThat(intent.dependencyRefs()).containsExactly("resource-a");
        });
        verify(admissionGuard).checkpoint();
        verify(admissionGuard).close();
    }

    @Test
    void createsOperatorTargetThroughTheSameAdmissionLeaseAndAtomicCommitKernel() {
        DurableOperatorTestExecutionCreateRequest request = values.operatorRequest(
                "operator-a", Map.of("name", "Ada"));
        var operatorCreation = new DurableTestRecoveryAuthorizer.AuthorizedOperatorCreation(
                values.operatorAuthorized(), Map.of(
                "operatorInput", Map.of("name", "Ada", "typed", true)));
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(authorizer.authorizeOperatorCreation("operator-a", request, identity()))
                .thenReturn(operatorCreation);
        when(checkpoints.reserveInitialCreation(any()))
                .thenReturn(values.pendingResult(true));
        when(runtime.prepare(any(), any(), any(), any()))
                .thenReturn(values.prepared(
                        OperatorMicroGraphRunner.DURABLE_START_NODE_ID));
        when(checkpoints.commitInitialCreation(any(), any(), any(), any()))
                .thenAnswer(invocation -> values.committedResult(
                        invocation.getArgument(1), false));

        DurableTestExecutionCreateResponse response = service.createOperator(
                "operator-a", request, identity());

        assertThat(response.execution().target()).satisfies(target -> {
            assertThat(target.kind()).isEqualTo("OPERATOR");
            assertThat(target.id()).isEqualTo("operator-a");
            assertThat(target.fingerprint()).isEqualTo(SHA_B);
        });
        assertThat(response.execution().authorizedPurpose()).isEqualTo("OPERATOR_UNIT_TEST");
        verify(runtime).prepare(any(), org.mockito.ArgumentMatchers.same(
                        operatorCreation.authorization()),
                org.mockito.ArgumentMatchers.eq(operatorCreation.context()), any());
        ArgumentCaptor<DurableTestExecutionCheckpoint> checkpoint =
                ArgumentCaptor.forClass(DurableTestExecutionCheckpoint.class);
        verify(checkpoints).commitInitialCreation(any(), checkpoint.capture(), any(), any());
        assertThat(checkpoint.getValue().dependencies().target().kind()).isEqualTo("OPERATOR");
        assertThat(checkpoint.getValue().engineState().nodeId())
                .isEqualTo(OperatorMicroGraphRunner.DURABLE_START_NODE_ID);
        ArgumentCaptor<AdmissionIntent> admission = ArgumentCaptor.forClass(AdmissionIntent.class);
        verify(admissions).admit(any(), admission.capture());
        assertThat(admission.getValue().operatorRefs())
                .contains("operator-a")
                .hasSize(2);
        assertThat(admission.getValue().dependencyRefs()).containsExactly("resource-a");
        verify(admissionGuard).checkpoint();
        verify(admissionGuard).close();
    }

    @Test
    void replaysDurableOperatorCreationBeforeRereadingTheOperatorRegistry() {
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenReturn(Optional.of(values.committedResult(true)));

        DurableTestExecutionCreateResponse response = service.createOperator(
                "operator-a", values.operatorRequest("operator-a", Map.of("name", "Ada")),
                identity());

        assertThat(response.idempotentReplay()).isTrue();
        verifyNoInteractions(authorizer, runtime, admissions);
    }

    @Test
    void rejectsDurableOperatorPathDriftAndControlInputBeforePersistence() {
        DurableOperatorTestExecutionCreateRequest pathDrift = values.operatorRequest(
                "operator-b", Map.of("name", "Ada"));
        assertThatThrownBy(() -> service.createOperator("operator-a", pathDrift, identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_OPERATOR_CREATE_TARGET_INVALID");
                });
        DurableOperatorTestExecutionCreateRequest controlInput = values.operatorRequest(
                "operator-a", Map.of("testMode", true));
        assertThatThrownBy(() -> service.createOperator("operator-a", controlInput, identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.TEST.CONTROL_IN_BUSINESS_CONTEXT"));

        verifyNoInteractions(checkpoints, authorizer, runtime, admissions);
    }

    @Test
    void commitsTheLatestHeartbeatFenceInsteadOfTheOriginalReservation() throws Exception {
        activateHeartbeats();
        DurableTestExecutionCreateRequest request = values.request(Map.of());
        CountDownLatch heartbeat = new CountDownLatch(1);
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(authorizer.authorizeCreation(request, identity()))
                .thenReturn(values.authorized());
        when(checkpoints.reserveInitialCreation(any()))
                .thenReturn(values.pendingResult(true));
        when(checkpoints.heartbeatInitialCreation(any(), any())).thenAnswer(invocation -> {
            heartbeat.countDown();
            return values.renewed(invocation.getArgument(0));
        });
        when(runtime.prepare(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(heartbeat.await(2, TimeUnit.SECONDS)).isTrue();
            return values.prepared();
        });
        when(checkpoints.commitInitialCreation(any(), any(), any(), any()))
                .thenAnswer(invocation -> values.committedResult(
                        invocation.getArgument(1), false));

        service.create(request, identity());

        ArgumentCaptor<DurableTestExecutionCheckpointRepository.InitialCreationReservation>
                committedFence = ArgumentCaptor.forClass(
                DurableTestExecutionCheckpointRepository.InitialCreationReservation.class);
        ArgumentCaptor<DurableTestExecutionCheckpoint> checkpoint =
                ArgumentCaptor.forClass(DurableTestExecutionCheckpoint.class);
        verify(checkpoints).commitInitialCreation(
                committedFence.capture(), checkpoint.capture(), any(), any());
        assertThat(committedFence.getValue().recordFingerprint())
                .isNotEqualTo(values.pending.recordFingerprint());
        assertThat(checkpoint.getValue().lifecycle().updatedAt())
                .isEqualTo(committedFence.getValue().updatedAt());
        assertThat(checkpoint.getValue().lifecycle().leaseExpiresAt())
                .isEqualTo(committedFence.getValue().leaseExpiresAt());
    }

    @Test
    void discardsPreparedStateWhenHeartbeatMakesOwnershipUncertain() throws Exception {
        activateHeartbeats();
        DurableTestExecutionCreateRequest request = values.request(Map.of());
        CountDownLatch heartbeat = new CountDownLatch(1);
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(authorizer.authorizeCreation(request, identity()))
                .thenReturn(values.authorized());
        when(checkpoints.reserveInitialCreation(any()))
                .thenReturn(values.pendingResult(true));
        when(checkpoints.heartbeatInitialCreation(any(), any())).thenAnswer(invocation -> {
            heartbeat.countDown();
            throw new DurableTestExecutionCheckpointConflictException(
                    DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE,
                    "lost");
        });
        when(runtime.prepare(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(heartbeat.await(2, TimeUnit.SECONDS)).isTrue();
            return values.prepared();
        });

        assertThatThrownBy(() -> service.create(request, identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_CREATE_LEASE_LOST");
                    assertThat(failure.problem().details())
                            .containsEntry("runId", "run-created")
                            .hasSize(1);
                });
        verify(checkpoints, never()).commitInitialCreation(any(), any(), any(), any());
        verify(checkpoints, never()).rejectInitialCreation(any(), any(), any());
        verify(securityEvents, never()).boundAppend(any());
    }

    @Test
    void reportsLiveSameIntentReservationWithoutStartingAnotherEngine() {
        DurableTestExecutionCreateRequest request = values.request(Map.of());
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(authorizer.authorizeCreation(request, identity()))
                .thenReturn(values.authorized());
        when(checkpoints.reserveInitialCreation(any()))
                .thenReturn(values.pendingResult(false));

        assertThatThrownBy(() -> service.create(request, identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_CREATE_IN_PROGRESS");
                    assertThat(failure.problem().details())
                            .containsEntry("runId", "run-created");
                });
        verifyNoInteractions(runtime);
    }

    @Test
    void persistsAndReplaysDeterministicInitialBoundaryRejection() {
        DurableTestExecutionCreateRequest request = values.request(Map.of());
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(authorizer.authorizeCreation(request, identity()))
                .thenReturn(values.authorized());
        when(checkpoints.reserveInitialCreation(any()))
                .thenReturn(values.pendingResult(true));
        when(runtime.prepare(any(), any(), any(), any())).thenThrow(
                new IndependentDurableTestEngineFactory.InitialBoundaryRejectedException(
                        "INITIAL_BOUNDARY_NOT_SUSPENDED",
                        "initial execution terminated"));
        when(checkpoints.rejectInitialCreation(any(), any(), any()))
                .thenReturn(values.rejectedResult("INITIAL_BOUNDARY_NOT_SUSPENDED", false));

        assertThatThrownBy(() -> service.create(request, identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_INITIAL_BOUNDARY_NOT_SUSPENDED");
                });
        verify(checkpoints).rejectInitialCreation(any(),
                org.mockito.ArgumentMatchers.eq("INITIAL_BOUNDARY_NOT_SUSPENDED"), any());
        verify(checkpoints, never()).commitInitialCreation(any(), any(), any(), any());
    }

    @Test
    void returnsStoredRejectionBeforeReauthorization() {
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenReturn(Optional.of(values.rejectedResult(
                        "INITIAL_SIGNAL_BOUNDARY_AMBIGUOUS", true)));

        assertThatThrownBy(() -> service.create(values.request(Map.of()), identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_INITIAL_SIGNAL_BOUNDARY_AMBIGUOUS");
                });
        verifyNoInteractions(authorizer, runtime);
    }

    @Test
    void rejectsControlFieldsBeforeFingerprintingOrPersistence() {
        DurableTestExecutionCreateRequest request = values.request(
                Map.of("fixtureBundleRef", Map.of("id", "forged")));

        assertThatThrownBy(() -> service.create(request, identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.CONTROL_IN_BUSINESS_CONTEXT");
                });
        verifyNoInteractions(checkpoints, authorizer, runtime);
    }

    @Test
    void mapsRepositoryIdempotencyConflictWithoutLeakingStoredIntent() {
        when(checkpoints.findInitialCreationResult(any(), any(), any(), any()))
                .thenThrow(new DurableTestExecutionCheckpointConflictException(
                        DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT,
                        "different hidden request"));

        assertThatThrownBy(() -> service.create(values.request(Map.of()), identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_CREATE_IDEMPOTENCY_CONFLICT");
                    assertThat(failure.problem().details()).isEmpty();
                });
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD",
                "runner", "", "TEST_EXECUTION", "correlation-a",
                Set.of("test-operators"), "CONFIDENTIAL", "");
    }

    private void activateHeartbeats() {
        leases.close();
        leases = new DurableTestCreationLeaseCoordinator(
                checkpoints, "creator-instance", Duration.ofSeconds(3),
                Duration.ofMillis(10));
        service = new DurableTestExecutionCreationService(
                checkpoints, authorizer, runtime,
                new DurableTestExecutionCheckpointIntegrity(mapper), securityEvents, mapper,
                leases, admissions);
    }

    private static final class TestValues {
        private final ObjectMapper mapper;
        private final DurableTestExecutionCheckpointIntegrity integrity;
        private final DurableTestExecutionCheckpoint.ControlDependencies dependencies;
        private final ExecutionServiceStateSnapshot serviceState;
        private final FixtureConsumptionStateSnapshot fixtureState;
        private final DurableTestExecutionCheckpoint.EngineState engineState;
        private final DurableTestExecutionCheckpointRepository.InitialCreationReservation pending;
        private final DurableTestRecoveryAuthorizer.AuthorizedCreation authorized;

        private TestValues(ObjectMapper mapper) {
            this.mapper = mapper;
            this.integrity = new DurableTestExecutionCheckpointIntegrity(mapper);
            EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                    EffectiveExecutionPlan.SCHEMA_VERSION, "plan-a", SHA_A,
                    "GRAPH_CONTRACT_TEST", SHA_B, SHA_C, List.of(), List.of(), List.of(),
                    Map.of("unmatchedExternalEffect", "DENY"), List.of());
            dependencies = new DurableTestExecutionCheckpoint.ControlDependencies(
                    plan, new DurableTestExecutionCheckpoint.ExactFixtureRef(
                    "fixture-a", 1, SHA_C), "DENY_REAL",
                    new DurableTestExecutionCheckpoint.AuthoritySnapshot("FAIL_CLOSED", SHA_D),
                    new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                            "GRAPH", "graph-a", SHA_B));
            ExecutionServiceStateSnapshot material = new ExecutionServiceStateSnapshot(
                    ExecutionServiceStateSnapshot.SCHEMA_VERSION, SHA_A, SHA_B,
                    Instant.parse("2026-07-17T00:00:00Z"), Map.of(), Map.of(), List.of(),
                    true, List.of(), SHA_D);
            serviceState = new ExecutionServiceStateSnapshot(
                    material.schemaVersion(), material.planFingerprint(),
                    material.bindingSetFingerprint(), material.logicalTime(),
                    material.randomScopeCursors(), material.uuidScopeCursors(), material.usages(),
                    material.restorable(), material.restoreGaps(),
                    ProtocolFingerprint.of(mapper, material.fingerprintMaterial()));
            fixtureState = new FixtureConsumptionStateSnapshot(
                    FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                    Map.of(), Map.of(), Map.of(), "");
            engineState = new DurableTestExecutionCheckpoint.EngineState(
                    "initial-run-created", "approval", "SUSPEND", 1, 2,
                    ProtocolFingerprint.ofText("engine-created-state"));
            Instant now = Instant.parse("2026-07-17T00:00:00Z");
            pending = new DurableTestExecutionCheckpointRepository.InitialCreationReservation(
                    DurableTestExecutionCheckpointRepository.InitialCreationReservation.SCHEMA_VERSION,
                    new DurableTestExecutionCheckpoint.Scope(
                            "tenant-a", "org-a", "project-a", "test", "runner"),
                    "create-1", SHA_A, SHA_D, "run-created", "engine-created",
                    "creator-instance", 1, now, now, now.plusSeconds(120),
                    DurableTestExecutionCheckpointRepository.InitialCreationState.PENDING,
                    "", "", ProtocolFingerprint.ofText("pending-record"));
            Operator<Object, Object> wait = (input, context) -> input;
            CompiledExecutionControl control = new CompiledExecutionControl(
                    plan, Map.of(), List.of(), null, null,
                    mock(GovernedExecutionServices.class));
            authorized = new DurableTestRecoveryAuthorizer.AuthorizedCreation(
                    new GraphBuilder("graph-a").node("approval", wait).build(), control,
                    Set.of("resource-a"),
                    dependencies, SHA_D);
        }

        private DurableTestExecutionCreateRequest request(Map<String, Object> context) {
            return new DurableTestExecutionCreateRequest(
                    "", "create-1", new TestExecutionApiRequest.Target(
                    "GRAPH", "graph-a", SHA_B), "GRAPH_CONTRACT_TEST", context,
                    new TestExecutionApiRequest.FixtureBundleRef("fixture-a", 1, SHA_C));
        }

        private DurableTestRecoveryAuthorizer.AuthorizedCreation authorized() {
            return authorized;
        }

        private DurableOperatorTestExecutionCreateRequest operatorRequest(
                String operatorRef, Object input) {
            return new DurableOperatorTestExecutionCreateRequest(
                    "", "create-1", new TestExecutionApiRequest.Target(
                    "OPERATOR", operatorRef, SHA_B), "OPERATOR_UNIT_TEST", input,
                    new TestExecutionApiRequest.FixtureBundleRef("fixture-a", 1, SHA_C));
        }

        private DurableTestRecoveryAuthorizer.AuthorizedCreation operatorAuthorized() {
            EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                    EffectiveExecutionPlan.SCHEMA_VERSION, "plan-operator", SHA_A,
                    "OPERATOR_UNIT_TEST", SHA_B, SHA_C, List.of(), List.of(), List.of(),
                    Map.of("unmatchedExternalEffect", "DENY"), List.of());
            DurableTestExecutionCheckpoint.ControlDependencies operatorDependencies =
                    new DurableTestExecutionCheckpoint.ControlDependencies(
                            plan, new DurableTestExecutionCheckpoint.ExactFixtureRef(
                            "fixture-a", 1, SHA_C), "DENY_REAL",
                            new DurableTestExecutionCheckpoint.AuthoritySnapshot(
                                    "FAIL_CLOSED", SHA_D),
                            new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                                    "OPERATOR", "operator-a", SHA_B));
            Operator<Object, Object> operator = (input, context) -> input;
            var graph = OperatorMicroGraphRunner.durableMicroGraph("operator-a", operator);
            InvocationInventory inventory = new InvocationInventoryBuilder(
                    new DefaultOperatorRegistry()).build(graph, SHA_B);
            CompiledExecutionControl control = new CompiledExecutionControl(
                    plan, Map.of(), List.of(), inventory, null,
                    mock(GovernedExecutionServices.class));
            return new DurableTestRecoveryAuthorizer.AuthorizedCreation(
                    graph, control, Set.of("resource-a"), operatorDependencies, SHA_D);
        }

        private DurableTestExecutionCheckpoint.ControlDependencies dependencies() {
            return dependencies;
        }

        private DurableTestCreationRuntime.PreparedCreation prepared() {
            return prepared("approval");
        }

        private DurableTestCreationRuntime.PreparedCreation prepared(String nodeId) {
            return new DurableTestCreationRuntime.PreparedCreation(
                    mutation(nodeId), fixtureState, serviceState, () -> { });
        }

        private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation() {
            return mutation("approval");
        }

        private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation(
                String nodeId) {
            DurableTestExecutionCheckpoint.EngineState selectedEngineState =
                    "approval".equals(nodeId) ? engineState
                            : new DurableTestExecutionCheckpoint.EngineState(
                            "initial-run-created", nodeId, "SUSPEND", 1, 2,
                            ProtocolFingerprint.ofText("engine-created-state-" + nodeId));
            return new DurableTestExecutionCheckpointRepository.BoundEngineStateMutation() {
                @Override
                public String engineExecutionId() {
                    return "engine-created";
                }

                @Override
                public DurableTestExecutionCheckpoint.EngineState engineState() {
                    return selectedEngineState;
                }

                @Override
                public void apply(org.springframework.jdbc.core.JdbcTemplate jdbc) {
                }
            };
        }

        private DurableTestExecutionCheckpointRepository.InitialCreationReservationResult
                pendingResult(boolean acquired) {
            return new DurableTestExecutionCheckpointRepository.InitialCreationReservationResult(
                    pending, null, acquired, false);
        }

        private DurableTestExecutionCheckpointRepository.InitialCreationReservation renewed(
                DurableTestExecutionCheckpointRepository.InitialCreationReservation current) {
            return new DurableTestExecutionCheckpointRepository.InitialCreationReservation(
                    current.schemaVersion(), current.scope(), current.clientRequestId(),
                    current.requestFingerprint(), current.authorizationFingerprint(),
                    current.runId(), current.engineExecutionId(), current.ownerId(),
                    current.leaseEpoch(), current.createdAt(), current.updatedAt().plusSeconds(1),
                    current.leaseExpiresAt().plusSeconds(1), current.state(), "", "",
                    ProtocolFingerprint.ofText("renewed-creation"));
        }

        private DurableTestExecutionCheckpointRepository.InitialCreationReservationResult
                committedResult(boolean replay) {
            DurableTestExecutionCheckpoint checkpoint = checkpoint();
            return committedResult(checkpoint, replay);
        }

        private DurableTestExecutionCheckpointRepository.InitialCreationReservationResult
                committedResult(DurableTestExecutionCheckpoint checkpoint, boolean replay) {
            var committed = new DurableTestExecutionCheckpointRepository.InitialCreationReservation(
                    pending.schemaVersion(), pending.scope(), pending.clientRequestId(),
                    pending.requestFingerprint(), pending.authorizationFingerprint(),
                    pending.runId(), pending.engineExecutionId(), pending.ownerId(),
                    pending.leaseEpoch(), pending.createdAt(), pending.updatedAt(),
                    pending.leaseExpiresAt(),
                    DurableTestExecutionCheckpointRepository.InitialCreationState.COMMITTED,
                    "", checkpoint.checkpointFingerprint(),
                    ProtocolFingerprint.ofText("committed-record"));
            return new DurableTestExecutionCheckpointRepository.InitialCreationReservationResult(
                    committed, checkpoint, false, replay);
        }

        private DurableTestExecutionCheckpointRepository.InitialCreationReservationResult
                rejectedResult(String reasonCode, boolean replay) {
            var rejected = new DurableTestExecutionCheckpointRepository.InitialCreationReservation(
                    pending.schemaVersion(), pending.scope(), pending.clientRequestId(),
                    pending.requestFingerprint(), pending.authorizationFingerprint(),
                    pending.runId(), pending.engineExecutionId(), pending.ownerId(),
                    pending.leaseEpoch(), pending.createdAt(), pending.updatedAt(),
                    pending.leaseExpiresAt(),
                    DurableTestExecutionCheckpointRepository.InitialCreationState.REJECTED,
                    reasonCode, "", ProtocolFingerprint.ofText("rejected-" + reasonCode));
            return new DurableTestExecutionCheckpointRepository.InitialCreationReservationResult(
                    rejected, null, false, replay);
        }

        private DurableTestExecutionCheckpoint checkpoint() {
            return integrity.seal(new DurableTestExecutionCheckpoint(
                    DurableTestExecutionCheckpoint.SCHEMA_VERSION, pending.scope(),
                    pending.runId(), pending.engineExecutionId(), dependencies,
                    fixtureState, serviceState, engineState,
                    new DurableTestExecutionCheckpoint.Lifecycle(
                            DurableTestExecutionCheckpoint.Status.SUSPENDED,
                            pending.ownerId(), pending.leaseEpoch(), 0,
                            pending.createdAt(), pending.updatedAt(), pending.leaseExpiresAt()), ""));
        }
    }
}
