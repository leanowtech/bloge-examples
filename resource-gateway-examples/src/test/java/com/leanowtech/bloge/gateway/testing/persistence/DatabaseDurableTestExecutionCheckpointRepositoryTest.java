package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatRequest;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatResponse;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryHeartbeatService;
import com.leanowtech.bloge.gateway.testing.api.DurableTestRecoveryPrincipal;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEvent;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryAuthorization;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryTerminalReceipt;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseDurableTestExecutionCheckpointRepositoryTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);

    private TestRuntimeDatabase database;
    private DatabaseDurableTestExecutionCheckpointRepository repository;
    private DurableTestExecutionCheckpointIntegrity integrity;
    private DatabaseTestSecurityEventRepository securityEvents;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:durable-control-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "", 4));
        integrity = new DurableTestExecutionCheckpointIntegrity(mapper);
        repository = new DatabaseDurableTestExecutionCheckpointRepository(
                database.jdbc(), database.transactionManager(), mapper, integrity);
        repository.init();
        securityEvents = new DatabaseTestSecurityEventRepository(database.jdbc(), mapper);
        securityEvents.init();
        database.jdbc().execute("""
                CREATE TABLE rg_test_engine_state (
                    candidate_id VARCHAR(255) PRIMARY KEY,
                    engine_execution_id VARCHAR(255) NOT NULL,
                    state_version BIGINT NOT NULL
                )
                """);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void reservesOnePayloadFreeCreationIdentityAndReportsConcurrentPreparation() {
        var first = repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-a", "run-created", "engine-created",
                Duration.ofMinutes(2)));
        var concurrent = repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-b", "run-discarded", "engine-discarded",
                Duration.ofMinutes(2)));

        assertThat(first.acquired()).isTrue();
        assertThat(first.reservation().state())
                .isEqualTo(DurableTestExecutionCheckpointRepository.InitialCreationState.PENDING);
        assertThat(first.reservation().runId()).isEqualTo("run-created");
        assertThat(first.reservation().engineExecutionId()).isEqualTo("engine-created");
        assertThat(first.reservation().leaseEpoch()).isEqualTo(1);
        assertThat(concurrent.acquired()).isFalse();
        assertThat(concurrent.reservation()).isEqualTo(first.reservation());
        assertThat(repository.findInitialCreationResult(
                "tenant-a", "test", "create-1", SHA_A)).isEmpty();

        Map<String, Object> stored = database.jdbc().queryForMap("""
                SELECT request_fingerprint, authorization_fingerprint, run_id,
                       engine_execution_id, state, result_checkpoint_json
                FROM rg_test_durable_creation_commands WHERE client_request_id = ?
                """, "create-1");
        assertThat(stored).containsEntry("REQUEST_FINGERPRINT", SHA_A)
                .containsEntry("AUTHORIZATION_FINGERPRINT", SHA_B)
                .containsEntry("RUN_ID", "run-created")
                .containsEntry("ENGINE_EXECUTION_ID", "engine-created")
                .containsEntry("STATE", "PENDING")
                .containsEntry("RESULT_CHECKPOINT_JSON", null);
    }

    @Test
    void creationReservationRejectsScopedKeyReuseForDifferentIntent() {
        repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-a", "run-created", "engine-created",
                Duration.ofMinutes(2)));

        assertThatThrownBy(() -> repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_C, SHA_B, "creator-b", "run-other", "engine-other",
                Duration.ofMinutes(2))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);
        assertThatThrownBy(() -> repository.findInitialCreationResult(
                "tenant-a", "test", "create-1", SHA_C))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void expiredCreationReservationIsFencedWithoutChangingAssignedIdentities()
            throws InterruptedException {
        var first = repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-a", "run-created", "engine-created",
                Duration.ofSeconds(1)));
        Thread.sleep(1_100);

        var acquired = repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-b", "run-discarded", "engine-discarded",
                Duration.ofMinutes(2)));

        assertThat(acquired.acquired()).isTrue();
        assertThat(acquired.reservation().ownerId()).isEqualTo("creator-b");
        assertThat(acquired.reservation().leaseEpoch()).isEqualTo(2);
        assertThat(acquired.reservation().runId()).isEqualTo(first.reservation().runId());
        assertThat(acquired.reservation().engineExecutionId())
                .isEqualTo(first.reservation().engineExecutionId());
        assertThat(acquired.reservation().recordFingerprint())
                .isNotEqualTo(first.reservation().recordFingerprint());
    }

    @Test
    void creationHeartbeatExtendsDatabaseFenceAndOnlySuccessorCanCommit()
            throws InterruptedException {
        var acquired = repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-a", "run-created", "engine-created",
                Duration.ofSeconds(3)));
        Thread.sleep(25);

        var renewed = repository.heartbeatInitialCreation(
                acquired.reservation(), Duration.ofSeconds(3));

        assertThat(renewed.ownerId()).isEqualTo(acquired.reservation().ownerId());
        assertThat(renewed.leaseEpoch()).isEqualTo(acquired.reservation().leaseEpoch());
        assertThat(renewed.runId()).isEqualTo(acquired.reservation().runId());
        assertThat(renewed.engineExecutionId())
                .isEqualTo(acquired.reservation().engineExecutionId());
        assertThat(renewed.updatedAt()).isAfter(acquired.reservation().updatedAt());
        assertThat(renewed.leaseExpiresAt())
                .isAfter(acquired.reservation().leaseExpiresAt());
        assertThat(renewed.recordFingerprint())
                .isNotEqualTo(acquired.reservation().recordFingerprint());

        DurableTestExecutionCheckpoint staleCheckpoint =
                initialCheckpoint(acquired.reservation());
        assertThatThrownBy(() -> repository.commitInitialCreation(
                acquired.reservation(), staleCheckpoint, boundNoop(staleCheckpoint),
                TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);

        DurableTestExecutionCheckpoint currentCheckpoint = initialCheckpoint(renewed);
        assertThat(repository.commitInitialCreation(
                renewed, currentCheckpoint, boundNoop(currentCheckpoint),
                TestRuntimeTransactionMutation.noop()).checkpoint())
                .isEqualTo(currentCheckpoint);
    }

    @Test
    void creationHeartbeatCannotReviveExpiredOrTerminalReservation()
            throws InterruptedException {
        var expiring = repository.reserveInitialCreation(creationCommand(
                "create-expired", SHA_A, SHA_B, "creator-a", "run-expired",
                "engine-expired", Duration.ofSeconds(1)));
        Thread.sleep(1_100);

        assertThatThrownBy(() -> repository.heartbeatInitialCreation(
                expiring.reservation(), Duration.ofSeconds(3)))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.LEASE_EXPIRED);

        var pending = repository.reserveInitialCreation(creationCommand(
                "create-rejected", SHA_A, SHA_B, "creator-a", "run-rejected",
                "engine-rejected", Duration.ofSeconds(3)));
        repository.rejectInitialCreation(
                pending.reservation(), "INITIAL_BOUNDARY_UNSUPPORTED",
                TestRuntimeTransactionMutation.noop());

        assertThatThrownBy(() -> repository.heartbeatInitialCreation(
                pending.reservation(), Duration.ofSeconds(3)))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
    }

    @Test
    void creationCheckpointEngineAggregateAndAuditCommitOrRollBackTogether() {
        var acquired = repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-a", "run-created", "engine-created",
                Duration.ofMinutes(2)));
        DurableTestExecutionCheckpoint initial = initialCheckpoint(acquired.reservation());
        TestSecurityEvent event = new TestSecurityEvent(0, Instant.now(), "correlation-a",
                "tenant-a", "test", "runner", "DURABLE_EXECUTION_CREATE", "ALLOWED",
                "RG.TEST.DURABLE_CREATE_AUTHORIZED", Map.of("runId", "run-created"));
        TestRuntimeTransactionMutation audit = securityEvents.boundAppend(event);

        assertThatThrownBy(() -> repository.commitInitialCreation(
                acquired.reservation(), initial,
                mutation(initial, jdbc -> jdbc.update(
                        "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                        "initial-create", initial.engineExecutionId(),
                        initial.engineState().stateVersion())), jdbc -> {
                    audit.apply(jdbc);
                    throw new IllegalStateException("injected creation audit failure");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creation audit failure");
        assertThat(repository.find("tenant-a", "test", "run-created")).isEmpty();
        assertThat(engineCandidateCount("initial-create")).isZero();
        assertThat(securityEvents.recent(10)).isEmpty();

        var committed = repository.commitInitialCreation(
                acquired.reservation(), initial,
                mutation(initial, jdbc -> jdbc.update(
                        "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                        "initial-create", initial.engineExecutionId(),
                        initial.engineState().stateVersion())), audit);

        assertThat(committed.reservation().state())
                .isEqualTo(DurableTestExecutionCheckpointRepository.InitialCreationState.COMMITTED);
        assertThat(committed.checkpoint()).isEqualTo(initial);
        assertThat(committed.idempotentReplay()).isFalse();
        assertThat(repository.find("tenant-a", "test", "run-created")).contains(initial);
        assertThat(engineCandidateCount("initial-create")).isEqualTo(1);
        assertThat(securityEvents.recent(10)).singleElement();
    }

    @Test
    void reservesOnePayloadFreeRecoverySequenceIntentAndReplaysItExactly() {
        DurableTestExecutionCheckpointRepository.RecoverySequenceCommand command =
                recoverySequenceCommand("sequence-a", SHA_A, "run-a", 3);
        TestSecurityEvent event = new TestSecurityEvent(
                0, Instant.now(), "correlation-a", "tenant-a", "test", "runner-a",
                "DURABLE_RECOVERY_SEQUENCE", "ALLOWED",
                "RG.TEST.DURABLE_RECOVERY_SEQUENCE_AUTHORIZED",
                Map.of("runId", "run-a", "clientRequestId", "sequence-a"));

        var first = repository.reserveRecoverySequenceIdempotently(
                command, securityEvents.boundAppend(event));
        var replay = repository.reserveRecoverySequenceIdempotently(
                command, securityEvents.boundAppend(event));

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.command()).isEqualTo(first.command());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.recordFingerprint()).isEqualTo(first.recordFingerprint());
        Map<String, Object> stored = database.jdbc().queryForMap("""
                SELECT * FROM rg_test_durable_recovery_sequences
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, "tenant-a", "test", "sequence-a");
        assertThat(stored).containsEntry("REQUEST_FINGERPRINT", SHA_A)
                .containsEntry("RUN_ID", "run-a")
                .containsEntry("SIGNAL_COUNT", 3);
        assertThat(stored.keySet()).noneMatch(column ->
                column.contains("PAYLOAD") || column.contains("SIGNAL_DATA"));
        assertThat(securityEvents.recent(10)).hasSize(2);
    }

    @Test
    void recoverySequenceRejectsLateSignalIntentDriftBeforeAnyChildCommand() {
        repository.reserveRecoverySequenceIdempotently(
                recoverySequenceCommand("sequence-a", SHA_A, "run-a", 3),
                TestRuntimeTransactionMutation.noop());

        assertThatThrownBy(() -> repository.reserveRecoverySequenceIdempotently(
                recoverySequenceCommand("sequence-a", SHA_B, "run-a", 3),
                TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason
                        .IDEMPOTENCY_CONFLICT);
        assertThatThrownBy(() -> repository.reserveRecoverySequenceIdempotently(
                recoverySequenceCommand("sequence-a", SHA_A, "run-a", 4),
                TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason
                        .IDEMPOTENCY_CONFLICT);
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_recovery_sequences", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void recoverySequenceReservationAndCompanionAuditRollBackTogether() {
        assertThatThrownBy(() -> repository.reserveRecoverySequenceIdempotently(
                recoverySequenceCommand("sequence-a", SHA_A, "run-a", 2), jdbc -> {
                    securityEvents.boundAppend(new TestSecurityEvent(
                            0, Instant.now(), "correlation-a", "tenant-a", "test",
                            "runner-a", "DURABLE_RECOVERY_SEQUENCE", "ALLOWED",
                            "RG.TEST.DURABLE_RECOVERY_SEQUENCE_AUTHORIZED",
                            Map.of("runId", "run-a"))).apply(jdbc);
                    throw new IllegalStateException("injected sequence audit failure");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sequence audit failure");

        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_recovery_sequences", Integer.class))
                .isZero();
        assertThat(securityEvents.recent(10)).isEmpty();
    }

    @Test
    void recoverySequenceReplayFailsClosedWhenStoredIntentIsTampered() {
        DurableTestExecutionCheckpointRepository.RecoverySequenceCommand command =
                recoverySequenceCommand("sequence-a", SHA_A, "run-a", 2);
        repository.reserveRecoverySequenceIdempotently(
                command, TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_recovery_sequences SET signal_count = 3
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, "tenant-a", "test", "sequence-a");

        assertThatThrownBy(() -> repository.reserveRecoverySequenceIdempotently(
                command, TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason
                        .INVALID_TRANSITION);
    }

    @Test
    void committedCreationReplaysOriginalInitialCheckpointWithoutReapplyingEngineMutation() {
        var acquired = repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-a", "run-created", "engine-created",
                Duration.ofMinutes(2)));
        DurableTestExecutionCheckpoint initial = initialCheckpoint(acquired.reservation());
        repository.commitInitialCreation(acquired.reservation(), initial,
                boundNoop(initial), TestRuntimeTransactionMutation.noop());
        AtomicBoolean replayMutationRan = new AtomicBoolean();

        var replay = repository.commitInitialCreation(
                acquired.reservation(), initial,
                mutation(initial, ignored -> replayMutationRan.set(true)),
                TestRuntimeTransactionMutation.noop());
        var lookedUp = repository.findInitialCreationResult(
                "tenant-a", "test", "create-1", SHA_A).orElseThrow();

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.checkpoint()).isEqualTo(initial);
        assertThat(lookedUp.idempotentReplay()).isTrue();
        assertThat(lookedUp.checkpoint()).isEqualTo(initial);
        assertThat(replayMutationRan).isFalse();
    }

    @Test
    void deterministicCreationRejectionIsImmutableAndPayloadFree() {
        var acquired = repository.reserveInitialCreation(creationCommand(
                "create-1", SHA_A, SHA_B, "creator-a", "run-created", "engine-created",
                Duration.ofMinutes(2)));

        var rejected = repository.rejectInitialCreation(
                acquired.reservation(), "INITIAL_BOUNDARY_UNSUPPORTED",
                TestRuntimeTransactionMutation.noop());
        var replay = repository.rejectInitialCreation(
                acquired.reservation(), "INITIAL_BOUNDARY_UNSUPPORTED",
                TestRuntimeTransactionMutation.noop());

        assertThat(rejected.reservation().state())
                .isEqualTo(DurableTestExecutionCheckpointRepository.InitialCreationState.REJECTED);
        assertThat(rejected.reservation().rejectionCode())
                .isEqualTo("INITIAL_BOUNDARY_UNSUPPORTED");
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.checkpoint()).isNull();
        assertThatThrownBy(() -> repository.rejectInitialCreation(
                acquired.reservation(), "DIFFERENT_REASON",
                TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void createsAndAdvancesControlAndEngineStateInOneTransaction() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, mutation(initial, jdbc -> jdbc.update(
                "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                "initial", initial.engineExecutionId(), 0)));

        DurableTestExecutionCheckpoint next = checkpoint(1, "checkpoint-1");
        repository.advance(next, new DurableTestExecutionCheckpointRepository.Fence(
                        "instance-a", 1, 0),
                mutation(next, jdbc -> jdbc.update(
                        "UPDATE rg_test_engine_state SET state_version = ? WHERE candidate_id = ?",
                        1, "initial")));

        assertThat(repository.find("tenant-a", "test", "run-a")).contains(next);
        assertThat(repository.findByEngineExecutionId("tenant-a", "test", "engine-a"))
                .contains(next);
        assertThat(repository.find("tenant-b", "test", "run-a")).isEmpty();
        assertThat(repository.find("tenant-a", "staging", "run-a")).isEmpty();
        assertThat(database.jdbc().queryForObject(
                "SELECT state_version FROM rg_test_engine_state WHERE candidate_id = 'initial'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void upgradedRepositoryPreservesAndReadsLegacyV1RowsWithoutInventingATarget() {
        DurableTestExecutionCheckpoint current = checkpoint(0, "checkpoint-0");
        DurableTestExecutionCheckpoint legacy = integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION_V1,
                current.scope(), current.runId(), current.engineExecutionId(),
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        current.dependencies().plan(), current.dependencies().fixture(),
                        current.dependencies().sideEffectPolicy(),
                        current.dependencies().identitySnapshot()),
                current.fixtureConsumptionState(), current.executionServiceState(),
                current.engineState(), current.lifecycle(), ""));

        repository.create(legacy, boundNoop(legacy));

        assertThat(repository.find("tenant-a", "test", "run-a")).contains(legacy);
        Map<String, Object> indexedTarget = database.jdbc().queryForMap("""
                SELECT target_kind, target_id, target_fingerprint
                FROM rg_test_durable_execution_checkpoints WHERE run_id = ?
                """, legacy.runId());
        assertThat(indexedTarget).containsOnly(
                org.assertj.core.data.MapEntry.entry("TARGET_KIND", null),
                org.assertj.core.data.MapEntry.entry("TARGET_ID", null),
                org.assertj.core.data.MapEntry.entry("TARGET_FINGERPRINT", null));
    }

    @Test
    void equivalentCreateIsIdempotentAndGlobalEngineIdentityCannotCrossTenant() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, boundNoop(initial));
        AtomicBoolean duplicateMutationRan = new AtomicBoolean();

        assertThat(repository.create(initial,
                mutation(initial, ignored -> duplicateMutationRan.set(true))))
                .isEqualTo(initial);
        assertThat(duplicateMutationRan).isFalse();

        DurableTestExecutionCheckpoint crossTenant = integrity.seal(
                new DurableTestExecutionCheckpoint(initial.schemaVersion(),
                        new DurableTestExecutionCheckpoint.Scope(
                                "tenant-b", "org-b", "project-b", "test", "runner-b"),
                        "run-b", initial.engineExecutionId(), initial.dependencies(),
                        initial.fixtureConsumptionState(), initial.executionServiceState(),
                        initial.engineState(), new DurableTestExecutionCheckpoint.Lifecycle(
                        initial.lifecycle().status(), "instance-b", 1, 0,
                        initial.lifecycle().createdAt(), initial.lifecycle().updatedAt(),
                        initial.lifecycle().leaseExpiresAt()), ""));
        assertThatThrownBy(() -> repository.create(crossTenant, boundNoop(crossTenant)))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error).reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.DUPLICATE_IDENTITY);
        assertThat(repository.findByEngineExecutionId("tenant-b", "test", "engine-a")).isEmpty();
        assertThat(repository.findByEngineExecutionId("tenant-a", "test", "engine-a"))
                .contains(initial);
    }

    @Test
    void rollsBackBothSidesWhenEngineMutationOrCheckpointCasFails() {
        DurableTestExecutionCheckpoint failedCreate = checkpoint(0, "checkpoint-create-failure");
        assertThatThrownBy(() -> repository.create(failedCreate, mutation(failedCreate, jdbc -> {
            jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                    "failed-create", failedCreate.engineExecutionId(), 0);
            throw new IllegalStateException("injected engine-store failure");
        }))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected engine-store failure");
        assertThat(repository.find("tenant-a", "test", "run-a")).isEmpty();
        assertThat(engineCandidateCount("failed-create")).isZero();

        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, boundNoop(initial));
        DurableTestExecutionCheckpoint next = checkpoint(1, "checkpoint-1");
        assertThatThrownBy(() -> repository.advance(next,
                new DurableTestExecutionCheckpointRepository.Fence("stale-owner", 1, 0),
                mutation(next, jdbc -> jdbc.update(
                        "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                        "stale", next.engineExecutionId(), 1))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error).reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
        assertThat(engineCandidateCount("stale")).isZero();
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(initial);
    }

    @Test
    void concurrentCheckpointCasCommitsExactlyOneEngineMutation() throws Exception {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, boundNoop(initial));
        CountDownLatch bothMutationsEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> advanceCandidate("candidate-a", bothMutationsEntered, release));
            var second = executor.submit(() -> advanceCandidate("candidate-b", bothMutationsEntered, release));
            assertThat(bothMutationsEntered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        }

        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_engine_state WHERE candidate_id IN ('candidate-a', 'candidate-b')",
                Integer.class)).isEqualTo(1);
        assertThat(repository.find("tenant-a", "test", "run-a")).get()
                .extracting(value -> value.lifecycle().revision()).isEqualTo(1L);
    }

    @Test
    void failsClosedWhenStoredJsonAndIndexedIdentityDiverge() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, boundNoop(initial));
        database.jdbc().update("""
                UPDATE rg_test_durable_execution_checkpoints
                SET plan_fingerprint = ? WHERE run_id = ?
                """, SHA_D, initial.runId());

        assertThatThrownBy(() -> repository.find("tenant-a", "test", "run-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void failsClosedWhenIndexedTargetLocatorDivergesFromTheSealedClosure() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, boundNoop(initial));
        database.jdbc().update("""
                UPDATE rg_test_durable_execution_checkpoints
                SET target_id = ? WHERE run_id = ?
                """, "other-graph", initial.runId());

        assertThatThrownBy(() -> repository.find("tenant-a", "test", "run-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void rejectsCursorRewindBeforeAnyEngineMutation() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, boundNoop(initial));
        DurableTestExecutionCheckpoint validNext = checkpoint(1, "checkpoint-1");
        DurableTestExecutionCheckpoint rewound = integrity.seal(
                validNext.withFixtureConsumptionState(new FixtureConsumptionStateSnapshot(
                        FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                        Map.of("rule-a", 0L), Map.of(), Map.of(), ""))
                        .withCheckpointFingerprint(""));

        assertThatThrownBy(() -> repository.advance(rewound,
                new DurableTestExecutionCheckpointRepository.Fence("instance-a", 1, 0),
                mutation(rewound, jdbc -> jdbc.update(
                        "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                        "rewound", rewound.engineExecutionId(), 1))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error).reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.INVALID_TRANSITION);
        assertThat(engineCandidateCount("rewound")).isZero();
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(initial);
    }

    @Test
    void claimsAnExpiredLeaseWithANewFenceWithoutChangingTheRecoveryClosure() {
        DurableTestExecutionCheckpoint initial = withLifecycle(
                checkpoint(0, "checkpoint-0"),
                DurableTestExecutionCheckpoint.Status.SUSPENDED,
                "instance-a", 1, 0,
                Instant.parse("2000-01-01T00:00:00Z"),
                Instant.parse("2000-01-01T00:00:01Z"),
                Instant.parse("2000-01-01T00:00:02Z"));
        repository.create(initial, boundNoop(initial));

        DurableTestExecutionCheckpoint claimed = repository.claimExpiredLease(
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        "tenant-a", "TEST", "run-a",
                        new DurableTestExecutionCheckpointRepository.Fence(
                                "instance-a", 1, 0),
                        initial.checkpointFingerprint(), "instance-b", Duration.ofMinutes(2)));

        assertThat(claimed.lifecycle().status())
                .isEqualTo(DurableTestExecutionCheckpoint.Status.RESUMING);
        assertThat(claimed.lifecycle().ownerId()).isEqualTo("instance-b");
        assertThat(claimed.lifecycle().leaseEpoch()).isEqualTo(2);
        assertThat(claimed.lifecycle().revision()).isEqualTo(1);
        assertThat(claimed.lifecycle().updatedAt()).isAfter(initial.lifecycle().leaseExpiresAt());
        assertThat(Duration.between(claimed.lifecycle().updatedAt(),
                claimed.lifecycle().leaseExpiresAt())).isEqualTo(Duration.ofMinutes(2));
        assertThat(claimed.dependencies()).isEqualTo(initial.dependencies());
        assertThat(claimed.fixtureConsumptionState())
                .isEqualTo(initial.fixtureConsumptionState());
        assertThat(claimed.executionServiceState()).isEqualTo(initial.executionServiceState());
        assertThat(claimed.engineState()).isEqualTo(initial.engineState());
        assertThat(claimed.checkpointFingerprint())
                .isNotEqualTo(initial.checkpointFingerprint());
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(claimed);

        assertThatThrownBy(() -> repository.advance(initial,
                new DurableTestExecutionCheckpointRepository.Fence("instance-a", 1, 0),
                boundNoop(initial)))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error).reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
    }

    @Test
    void rejectsAnActiveLeaseUsingTheDatabaseClock() {
        DurableTestExecutionCheckpoint active = withLifecycle(
                checkpoint(0, "checkpoint-0"),
                DurableTestExecutionCheckpoint.Status.SUSPENDED,
                "instance-a", 1, 0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"),
                Instant.parse("9999-01-01T00:00:00Z"));
        repository.create(active, boundNoop(active));

        assertThatThrownBy(() -> claim(active, "tenant-a", "instance-b"))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error).reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.LEASE_ACTIVE);
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(active);
    }

    @ParameterizedTest
    @EnumSource(value = DurableTestExecutionCheckpoint.Status.class,
            names = {"TERMINAL", "CONTROL_PLAN_UNAVAILABLE"})
    void rejectsTerminalAndUnavailableRecoveryLifecycles(
            DurableTestExecutionCheckpoint.Status status) {
        DurableTestExecutionCheckpoint blocked = withLifecycle(
                checkpoint(0, "checkpoint-0"), status,
                "instance-a", 1, 0,
                Instant.parse("2000-01-01T00:00:00Z"),
                Instant.parse("2000-01-01T00:00:01Z"),
                Instant.parse("2000-01-01T00:00:02Z"));
        repository.create(blocked, boundNoop(blocked));

        assertThatThrownBy(() -> claim(blocked, "tenant-a", "instance-b"))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.NOT_RESUMABLE);
    }

    @Test
    void treatsCrossScopeAndStaleClaimsAsTheSameFailClosedConflict() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));

        DurableTestExecutionCheckpointRepository.LeaseClaim crossTenant =
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        "tenant-b", "test", "run-a",
                        new DurableTestExecutionCheckpointRepository.Fence(
                                "instance-a", 1, 0),
                        expired.checkpointFingerprint(), "instance-b", Duration.ofMinutes(2));
        DurableTestExecutionCheckpointRepository.LeaseClaim staleFingerprint =
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        "tenant-a", "test", "run-a",
                        new DurableTestExecutionCheckpointRepository.Fence(
                                "instance-a", 1, 0),
                        SHA_B, "instance-b", Duration.ofMinutes(2));

        for (DurableTestExecutionCheckpointRepository.LeaseClaim claim :
                List.of(crossTenant, staleFingerprint)) {
            assertThatThrownBy(() -> repository.claimExpiredLease(claim))
                    .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                    .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                            .reason())
                    .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
        }
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(expired);
    }

    @Test
    void concurrentRepositoryInstancesGrantExactlyOneExpiredLease() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DatabaseDurableTestExecutionCheckpointRepository competing =
                new DatabaseDurableTestExecutionCheckpointRepository(
                        database.jdbc(), database.transactionManager(),
                        new ObjectMapper().findAndRegisterModules(), integrity);
        competing.init();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimCandidate(
                    repository, expired, "instance-b", start));
            var second = executor.submit(() -> claimCandidate(
                    competing, expired, "instance-c", start));
            start.countDown();

            assertThat((first.get() ? 1 : 0) + (second.get() ? 1 : 0)).isEqualTo(1);
        }

        DurableTestExecutionCheckpoint winner = repository.find(
                "tenant-a", "test", "run-a").orElseThrow();
        assertThat(winner.lifecycle().ownerId()).isIn("instance-b", "instance-c");
        assertThat(winner.lifecycle().leaseEpoch()).isEqualTo(2);
        assertThat(winner.lifecycle().revision()).isEqualTo(1);
        assertThat(winner.lifecycle().status())
                .isEqualTo(DurableTestExecutionCheckpoint.Status.RESUMING);
    }

    @Test
    void durableResumeCommandReturnsTheOriginalClaimOnAnAmbiguousRetry() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b");

        DurableTestExecutionCheckpointRepository.LeaseClaimResult first =
                repository.claimExpiredLeaseIdempotently(command);
        DurableTestExecutionCheckpoint advanced = advanceAfterClaim(first.checkpoint());
        DurableTestExecutionCheckpointRepository.LeaseClaimResult retry =
                repository.claimExpiredLeaseIdempotently(command);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(retry.idempotentReplay()).isTrue();
        assertThat(retry.checkpoint()).isEqualTo(first.checkpoint());
        assertThat(repository.find("tenant-a", "test", "run-a"))
                .contains(advanced);
    }

    @Test
    void ownerClaimAtomicallyIssuesAnAuthorizationBoundWorkerDispatch() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b");

        DurableTestExecutionCheckpointRepository.LeaseClaimResult result =
                repository.claimExpiredLeaseIdempotently(command);
        DurableTestExecutionCheckpoint claimed = result.checkpoint();

        assertThat(result.dispatch().authorization()).isEqualTo(command.authorization());
        assertThat(result.dispatch()).satisfies(dispatch -> {
            assertThat(dispatch.runId()).isEqualTo(claimed.runId());
            assertThat(dispatch.engineExecutionId()).isEqualTo(claimed.engineExecutionId());
            assertThat(dispatch.ownerId()).isEqualTo(claimed.lifecycle().ownerId());
            assertThat(dispatch.leaseEpoch()).isEqualTo(claimed.lifecycle().leaseEpoch());
            assertThat(dispatch.revision()).isEqualTo(claimed.lifecycle().revision());
            assertThat(dispatch.checkpointFingerprint())
                    .isEqualTo(claimed.checkpointFingerprint());
            dispatch.requireValid(new ObjectMapper().findAndRegisterModules());
        });
        assertThat(repository.findRecoveryDispatch(
                "tenant-a", "test", "run-a",
                new DurableTestExecutionCheckpointRepository.Fence(
                        claimed.lifecycle().ownerId(), claimed.lifecycle().leaseEpoch(),
                        claimed.lifecycle().revision()), claimed.checkpointFingerprint()))
                .contains(result.dispatch());
        assertThat(repository.findRecoveryDispatch(
                "tenant-b", "test", "run-a",
                new DurableTestExecutionCheckpointRepository.Fence(
                        claimed.lifecycle().ownerId(), claimed.lifecycle().leaseEpoch(),
                        claimed.lifecycle().revision()), claimed.checkpointFingerprint()))
                .isEmpty();
    }

    @Test
    void recoveryHeartbeatUsesDatabaseClockAndRotatesOneLiveFenceIdempotently() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand command =
                heartbeatCommand(claimed.dispatch(), "heartbeat-request-1", SHA_C,
                        Duration.ofMinutes(3));

        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult first =
                repository.heartbeatRecoveryLeaseIdempotently(command);
        DurableTestExecutionCheckpoint renewed = first.checkpoint();

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(renewed.lifecycle().status())
                .isEqualTo(DurableTestExecutionCheckpoint.Status.RESUMING);
        assertThat(renewed.lifecycle().ownerId())
                .isEqualTo(claimed.checkpoint().lifecycle().ownerId());
        assertThat(renewed.lifecycle().leaseEpoch())
                .isEqualTo(claimed.checkpoint().lifecycle().leaseEpoch());
        assertThat(renewed.lifecycle().revision())
                .isEqualTo(claimed.checkpoint().lifecycle().revision() + 1);
        assertThat(Duration.between(renewed.lifecycle().updatedAt(),
                renewed.lifecycle().leaseExpiresAt())).isEqualTo(Duration.ofMinutes(3));
        assertThat(renewed.dependencies()).isEqualTo(claimed.checkpoint().dependencies());
        assertThat(renewed.fixtureConsumptionState())
                .isEqualTo(claimed.checkpoint().fixtureConsumptionState());
        assertThat(renewed.executionServiceState())
                .isEqualTo(claimed.checkpoint().executionServiceState());
        assertThat(renewed.engineState()).isEqualTo(claimed.checkpoint().engineState());
        assertThat(first.dispatch().authorization()).isEqualTo(claimed.dispatch().authorization());
        assertThat(first.dispatch().dispatchFingerprint())
                .isNotEqualTo(claimed.dispatch().dispatchFingerprint());
        first.dispatch().requireValid(new ObjectMapper().findAndRegisterModules(), renewed);
        assertThat(claimed.dispatch().agreesWith(renewed)).isFalse();
        assertThat(repository.findRecoveryDispatch(
                "tenant-a", "test", "run-a",
                new DurableTestExecutionCheckpointRepository.Fence(
                        renewed.lifecycle().ownerId(), renewed.lifecycle().leaseEpoch(),
                        renewed.lifecycle().revision()), renewed.checkpointFingerprint()))
                .contains(first.dispatch());

        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult replay =
                repository.heartbeatRecoveryLeaseIdempotently(command);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.checkpoint()).isEqualTo(first.checkpoint());
        assertThat(replay.dispatch()).isEqualTo(first.dispatch());
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(renewed);
    }

    @Test
    void authenticatedHeartbeatResolvesHiddenDispatchAndCommitsSemanticAuditAtomically() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        IntegrationRequestContext identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD",
                "worker-a", "dispatcher-a", "TEST_EXECUTION", "correlation-a",
                Set.of("quality"), "CONFIDENTIAL", "grant-a");
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(resumeCommand(
                        expired, "resume-request-1", SHA_D, "instance-b",
                        Duration.ofMinutes(2),
                        DurableTestRecoveryPrincipal.fingerprint(mapper, identity)));
        DurableTestRecoveryHeartbeatService service =
                new DurableTestRecoveryHeartbeatService(
                        repository, securityEvents, mapper, Duration.ofMinutes(3));
        DurableTestRecoveryHeartbeatRequest request =
                new DurableTestRecoveryHeartbeatRequest(
                        "", "public-heartbeat-1",
                        new DurableTestRecoveryHeartbeatRequest.Fence(
                                claimed.checkpoint().lifecycle().ownerId(),
                                claimed.checkpoint().lifecycle().leaseEpoch(),
                                claimed.checkpoint().lifecycle().revision()),
                        claimed.checkpoint().checkpointFingerprint());

        DurableTestRecoveryHeartbeatResponse first =
                service.heartbeat("run-a", request, identity);
        DurableTestRecoveryHeartbeatResponse replay =
                service.heartbeat("run-a", request, identity);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.checkpointFingerprint())
                .isEqualTo(first.checkpointFingerprint());
        assertThat(repository.find("tenant-a", "test", "run-a")).get()
                .satisfies(checkpoint -> {
                    assertThat(checkpoint.lifecycle().revision()).isEqualTo(first.revision());
                    assertThat(checkpoint.checkpointFingerprint())
                            .isEqualTo(first.checkpointFingerprint());
                });
        assertThat(securityEvents.recent(10))
                .extracting(TestSecurityEvent::reasonCode)
                .contains("RG.TEST.DURABLE_HEARTBEAT_AUTHORIZED",
                        "RG.TEST.DURABLE_HEARTBEAT_IDEMPOTENT_REPLAY");
    }

    @Test
    void recoveryHeartbeatRejectsAStaleDispatchAfterTheFenceRotates() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        repository.heartbeatRecoveryLeaseIdempotently(heartbeatCommand(
                claimed.dispatch(), "heartbeat-request-1", SHA_C, Duration.ofMinutes(3)));

        assertThatThrownBy(() -> repository.heartbeatRecoveryLeaseIdempotently(
                heartbeatCommand(claimed.dispatch(), "heartbeat-request-2", SHA_B,
                        Duration.ofMinutes(3))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
    }

    @Test
    void recoveryHeartbeatRejectsAnExpiredDispatchWithoutRevivingItsOwner() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(resumeCommand(
                        expired, "resume-request-1", SHA_D, "instance-b",
                        Duration.ofSeconds(1)));
        TimeUnit.MILLISECONDS.sleep(1100);

        assertThatThrownBy(() -> repository.heartbeatRecoveryLeaseIdempotently(
                heartbeatCommand(claimed.dispatch(), "heartbeat-request-expired", SHA_C,
                        Duration.ofMinutes(3))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.LEASE_EXPIRED);
        assertThat(repository.find("tenant-a", "test", "run-a"))
                .contains(claimed.checkpoint());
    }

    @Test
    void recoveryHeartbeatRejectsAValidButUnissuedDispatch() {
        DurableTestExecutionCheckpoint resuming = withLifecycle(
                checkpoint(0, "checkpoint-0"), DurableTestExecutionCheckpoint.Status.RESUMING,
                "instance-b", 2, 0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"),
                Instant.parse("9999-01-01T00:00:00Z"));
        repository.create(resuming, boundNoop(resuming));
        DurableTestRecoveryDispatch unissued = DurableTestRecoveryDispatch.issue(
                new ObjectMapper().findAndRegisterModules(), resumeCommand(
                        resuming, "unused-resume", SHA_D, "instance-c").authorization(),
                resuming);

        assertThatThrownBy(() -> repository.heartbeatRecoveryLeaseIdempotently(
                heartbeatCommand(unissued, "heartbeat-request-unissued", SHA_C,
                        Duration.ofMinutes(3))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason
                        .UNRECOGNIZED_DISPATCH);
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(resuming);
    }

    @Test
    void recoveryHeartbeatAndCompanionAuditRollBackAsOneTransaction() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand command =
                heartbeatCommand(claimed.dispatch(), "heartbeat-request-1", SHA_C,
                        Duration.ofMinutes(3));

        assertThatThrownBy(() -> repository.heartbeatRecoveryLeaseIdempotently(command, jdbc -> {
            jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                    "heartbeat-audit", claimed.checkpoint().engineExecutionId(), 1);
            throw new IllegalStateException("injected heartbeat audit failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heartbeat audit failure");
        assertThat(repository.find("tenant-a", "test", "run-a"))
                .contains(claimed.checkpoint());
        assertThat(engineCandidateCount("heartbeat-audit")).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_recovery_heartbeats", Integer.class))
                .isZero();

        assertThat(repository.heartbeatRecoveryLeaseIdempotently(command).idempotentReplay())
                .isFalse();
    }

    @Test
    void recoveryHeartbeatRejectsIdempotencyDriftAndStoredSuccessorTampering() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand command =
                heartbeatCommand(claimed.dispatch(), "heartbeat-request-1", SHA_C,
                        Duration.ofMinutes(3));
        repository.heartbeatRecoveryLeaseIdempotently(command);

        assertThatThrownBy(() -> repository.heartbeatRecoveryLeaseIdempotently(
                heartbeatCommand(claimed.dispatch(), "heartbeat-request-1", SHA_C,
                        Duration.ofMinutes(4))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);

        database.jdbc().update("""
                UPDATE rg_test_durable_recovery_heartbeats
                SET result_dispatch_json = ?
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, "{}", "tenant-a", "test", "heartbeat-request-1");
        assertThatThrownBy(() -> repository.heartbeatRecoveryLeaseIdempotently(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery heartbeat dispatch is corrupt");
    }

    @Test
    void concurrentRepositoryInstancesCommitOneHeartbeatAndReplayItsSuccessor() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DatabaseDurableTestExecutionCheckpointRepository competing =
                new DatabaseDurableTestExecutionCheckpointRepository(
                        database.jdbc(), database.transactionManager(),
                        new ObjectMapper().findAndRegisterModules(), integrity);
        competing.init();
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand command =
                heartbeatCommand(claimed.dispatch(), "heartbeat-request-1", SHA_C,
                        Duration.ofMinutes(3));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> idempotentHeartbeatCandidate(
                    repository, command, start));
            var second = executor.submit(() -> idempotentHeartbeatCandidate(
                    competing, command, start));
            start.countDown();
            List<DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult> results =
                    List.of(first.get(), second.get());

            assertThat(results).extracting(
                    DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult
                            ::idempotentReplay)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results).extracting(result -> result.dispatch().dispatchFingerprint())
                    .containsOnly(results.getFirst().dispatch().dispatchFingerprint());
        }
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_recovery_heartbeats", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void validatesRecoveryHeartbeatIdentityAndDurationBeforePersistence() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestRecoveryDispatch dispatch = repository.claimExpiredLeaseIdempotently(
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b")).dispatch();

        assertThatThrownBy(() -> heartbeatCommand(
                dispatch, "", SHA_C, Duration.ofMinutes(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientRequestId");
        assertThatThrownBy(() -> heartbeatCommand(
                dispatch, "heartbeat request", SHA_C, Duration.ofMinutes(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientRequestId");
        assertThatThrownBy(() -> heartbeatCommand(
                dispatch, "heartbeat-request-1", "not-a-fingerprint", Duration.ofMinutes(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint");
        assertThatThrownBy(() -> heartbeatCommand(
                dispatch, "heartbeat-request-1", SHA_C, Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
        assertThatThrownBy(() -> heartbeatCommand(
                dispatch, "heartbeat-request-1", SHA_C,
                Duration.ofHours(1).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
    }

    @Test
    void recoveryStepAtomicallyCommitsSuspensionReleasesLeaseAndReplaysWithoutMutation() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpoint.EngineState suspendedEngine =
                suspendedEngineState(claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.RecoveryStepCommand command = stepCommand(
                claimed, "step-request-1", SHA_B,
                DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED,
                suspendedEngine);
        AtomicBoolean mutationRan = new AtomicBoolean();

        DurableTestExecutionCheckpointRepository.RecoveryStepResult first =
                repository.advanceRecoveryStepIdempotently(
                        command,
                        mutation(claimed.checkpoint().engineExecutionId(), suspendedEngine,
                                jdbc -> {
                                    mutationRan.set(true);
                                    jdbc.update(
                                            "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                            "step-suspended",
                                            claimed.checkpoint().engineExecutionId(),
                                            suspendedEngine.stateVersion());
                                }),
                        TestRuntimeTransactionMutation.noop());

        assertThat(mutationRan).isTrue();
        assertThat(first.idempotentReplay()).isFalse();
        assertThat(first.outcome()).isEqualTo(
                DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED);
        assertThat(first.terminalReceipt()).isNull();
        assertThat(first.checkpoint().lifecycle().status())
                .isEqualTo(DurableTestExecutionCheckpoint.Status.SUSPENDED);
        assertThat(first.checkpoint().lifecycle().leaseExpiresAt())
                .isEqualTo(first.checkpoint().lifecycle().updatedAt());
        assertThat(first.checkpoint().lifecycle().revision())
                .isEqualTo(claimed.checkpoint().lifecycle().revision() + 1);
        assertThat(repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 10)).candidates())
                .extracting(candidate -> candidate.checkpoint().checkpointFingerprint())
                .contains(first.checkpoint().checkpointFingerprint());

        assertThat(repository.findRecoveryStepResult(
                "tenant-a", "test", "step-request-1", SHA_B)).get()
                .satisfies(replay -> {
                    assertThat(replay.idempotentReplay()).isTrue();
                    assertThat(replay.checkpoint()).isEqualTo(first.checkpoint());
                    assertThat(replay.terminalReceipt()).isNull();
                });
        mutationRan.set(false);
        DurableTestExecutionCheckpointRepository.RecoveryStepResult replay =
                repository.advanceRecoveryStepIdempotently(
                        command,
                        mutation(claimed.checkpoint().engineExecutionId(), suspendedEngine,
                                ignored -> mutationRan.set(true)),
                        TestRuntimeTransactionMutation.noop());
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(mutationRan).isFalse();
        assertThat(engineCandidateCount("step-suspended")).isEqualTo(1);
    }

    @Test
    void recoveryStepTerminalOutcomeRetainsPromotionBlockingReceipt() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpoint.EngineState terminalEngine =
                terminalEngineState(claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.RecoveryStepCommand command = stepCommand(
                claimed, "step-request-terminal", SHA_B,
                DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.COMPLETED,
                terminalEngine);

        DurableTestExecutionCheckpointRepository.RecoveryStepResult result =
                repository.advanceRecoveryStepIdempotently(
                        command,
                        mutation(claimed.checkpoint().engineExecutionId(), terminalEngine,
                                ignored -> { }),
                        TestRuntimeTransactionMutation.noop());

        assertThat(result.checkpoint().lifecycle().status())
                .isEqualTo(DurableTestExecutionCheckpoint.Status.TERMINAL);
        assertThat(result.terminalReceipt()).isNotNull().satisfies(receipt -> {
            assertThat(receipt.executionOutcome())
                    .isEqualTo(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED);
            assertThat(receipt.evidenceStatus()).isEqualTo("EVIDENCE_INCOMPLETE");
            receipt.requireValid(new ObjectMapper().findAndRegisterModules(),
                    claimed.dispatch(), result.checkpoint());
        });
        assertThat(repository.findRecoveryStepResult(
                "tenant-a", "test", "step-request-terminal", SHA_B)).get()
                .extracting(DurableTestExecutionCheckpointRepository.RecoveryStepResult
                        ::idempotentReplay)
                .isEqualTo(true);
    }

    @Test
    void recoveryStepAndCompanionAuditRollBackAsOneTransaction() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpoint.EngineState suspendedEngine =
                suspendedEngineState(claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.RecoveryStepCommand command = stepCommand(
                claimed, "step-request-rollback", SHA_B,
                DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED,
                suspendedEngine);

        assertThatThrownBy(() -> repository.advanceRecoveryStepIdempotently(
                command,
                mutation(claimed.checkpoint().engineExecutionId(), suspendedEngine,
                        jdbc -> jdbc.update(
                                "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                "step-rollback", claimed.checkpoint().engineExecutionId(),
                                suspendedEngine.stateVersion())),
                ignored -> {
                    throw new IllegalStateException("injected recovery-step audit failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery-step audit failure");

        assertThat(repository.find("tenant-a", "test", "run-a"))
                .contains(claimed.checkpoint());
        assertThat(engineCandidateCount("step-rollback")).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_recovery_steps", Integer.class))
                .isZero();
    }

    @Test
    void recoveryStepRejectsIntentDriftAndStoredEvidenceGapTampering() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpoint.EngineState suspendedEngine =
                suspendedEngineState(claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.RecoveryStepCommand command = stepCommand(
                claimed, "step-request-tamper", SHA_B,
                DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED,
                suspendedEngine);
        repository.advanceRecoveryStepIdempotently(
                command,
                mutation(claimed.checkpoint().engineExecutionId(), suspendedEngine,
                        ignored -> { }),
                TestRuntimeTransactionMutation.noop());

        DurableTestExecutionCheckpointRepository.RecoveryStepCommand changed =
                new DurableTestExecutionCheckpointRepository.RecoveryStepCommand(
                        command.clientRequestId(), command.requestFingerprint(),
                        command.expectedDispatch(), command.outcome(), command.engineState(),
                        command.fixtureConsumptionState(), command.executionServiceState(),
                        List.of("DIFFERENT_EVIDENCE_GAP"));
        assertThatThrownBy(() -> repository.advanceRecoveryStepIdempotently(
                changed,
                mutation(claimed.checkpoint().engineExecutionId(), suspendedEngine,
                        ignored -> { }),
                TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason
                        .IDEMPOTENCY_CONFLICT);

        database.jdbc().update("""
                UPDATE rg_test_durable_recovery_steps SET evidence_gaps_json = '[]'
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, "tenant-a", "test", "step-request-tamper");
        assertThatThrownBy(() -> repository.findRecoveryStepResult(
                "tenant-a", "test", "step-request-tamper", SHA_B))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence gaps are corrupt");
    }

    @Test
    void recoveryStepRejectsExpiredAndValidButUnissuedDispatches() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(resumeCommand(
                        expired, "resume-request-1", SHA_D, "instance-b",
                        Duration.ofSeconds(1)));
        DurableTestExecutionCheckpoint.EngineState suspendedEngine =
                suspendedEngineState(claimed.checkpoint());
        TimeUnit.MILLISECONDS.sleep(1_100);

        assertThatThrownBy(() -> repository.advanceRecoveryStepIdempotently(
                stepCommand(claimed, "step-request-expired", SHA_B,
                        DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED,
                        suspendedEngine),
                mutation(claimed.checkpoint().engineExecutionId(), suspendedEngine,
                        ignored -> { }),
                TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.LEASE_EXPIRED);

        DurableTestRecoveryDispatch unissued = DurableTestRecoveryDispatch.issue(
                new ObjectMapper().findAndRegisterModules(), resumeCommand(
                        claimed.checkpoint(), "uncommitted-resume", SHA_D, "instance-b",
                        Duration.ofMinutes(2), SHA_A).authorization(), claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.LeaseClaimResult synthetic =
                new DurableTestExecutionCheckpointRepository.LeaseClaimResult(
                        claimed.checkpoint(), unissued, false);
        DurableTestExecutionCheckpoint.EngineState unissuedEngine =
                suspendedEngineState(claimed.checkpoint());

        assertThatThrownBy(() -> repository.advanceRecoveryStepIdempotently(
                stepCommand(synthetic, "step-request-unissued", SHA_B,
                        DurableTestExecutionCheckpointRepository.RecoveryStepOutcome.SUSPENDED,
                        unissuedEngine),
                mutation(claimed.checkpoint().engineExecutionId(), unissuedEngine,
                        ignored -> { }),
                TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason
                        .UNRECOGNIZED_DISPATCH);
    }

    @Test
    void recoveryTerminalCommandAtomicallyCommitsEngineStateAndBlockingEvidenceReceipt() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpoint.EngineState terminalEngine =
                terminalEngineState(claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand command =
                terminalCommand(claimed, "terminal-request-1", SHA_B, terminalEngine);
        AtomicBoolean mutationRan = new AtomicBoolean();

        DurableTestExecutionCheckpointRepository.RecoveryTerminalResult first =
                repository.terminalizeRecoveryIdempotently(command,
                        mutation(claimed.checkpoint().engineExecutionId(), terminalEngine, jdbc -> {
                            mutationRan.set(true);
                            jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                    "terminal", claimed.checkpoint().engineExecutionId(),
                                    terminalEngine.stateVersion());
                        }));

        assertThat(mutationRan).isTrue();
        assertThat(first.idempotentReplay()).isFalse();
        assertThat(first.checkpoint().lifecycle().status())
                .isEqualTo(DurableTestExecutionCheckpoint.Status.TERMINAL);
        assertThat(first.checkpoint().lifecycle().revision())
                .isEqualTo(claimed.checkpoint().lifecycle().revision() + 1);
        assertThat(first.checkpoint().engineState()).isEqualTo(terminalEngine);
        assertThat(first.checkpoint().dependencies()).isEqualTo(claimed.checkpoint().dependencies());
        assertThat(first.receipt()).satisfies(receipt -> {
            assertThat(receipt.executionOutcome())
                    .isEqualTo(DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED);
            assertThat(receipt.evidenceStatus()).isEqualTo("EVIDENCE_INCOMPLETE");
            assertThat(receipt.evidenceGapCodes())
                    .containsExactly("PRE_CHECKPOINT_TRACE_UNAVAILABLE");
            receipt.requireValid(new ObjectMapper().findAndRegisterModules(),
                    claimed.dispatch(), first.checkpoint());
        });
        assertThat(repository.findRecoveryTerminalResult(
                "tenant-a", "test", "terminal-request-1", SHA_B)).get()
                .satisfies(replay -> {
                    assertThat(replay.idempotentReplay()).isTrue();
                    assertThat(replay.checkpoint()).isEqualTo(first.checkpoint());
                    assertThat(replay.receipt()).isEqualTo(first.receipt());
                });

        mutationRan.set(false);
        DurableTestExecutionCheckpointRepository.RecoveryTerminalResult replay =
                repository.terminalizeRecoveryIdempotently(command,
                        mutation(claimed.checkpoint().engineExecutionId(), terminalEngine,
                                ignored -> mutationRan.set(true)));
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(mutationRan).isFalse();
        assertThat(engineCandidateCount("terminal")).isEqualTo(1);
    }

    @Test
    void recoveryTerminalCommandAndCompanionEvidenceRollBackAsOneTransaction() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpoint.EngineState terminalEngine =
                terminalEngineState(claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand command =
                terminalCommand(claimed, "terminal-request-1", SHA_B, terminalEngine);

        assertThatThrownBy(() -> repository.terminalizeRecoveryIdempotently(
                command,
                mutation(claimed.checkpoint().engineExecutionId(), terminalEngine,
                        jdbc -> jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                "terminal", claimed.checkpoint().engineExecutionId(),
                                terminalEngine.stateVersion())),
                ignored -> {
                    throw new IllegalStateException("injected terminal evidence failure");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal evidence failure");

        assertThat(repository.find("tenant-a", "test", "run-a"))
                .contains(claimed.checkpoint());
        assertThat(engineCandidateCount("terminal")).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_recovery_terminals", Integer.class))
                .isZero();
    }

    @Test
    void recoveryTerminalCommandRejectsIntentDriftAndStoredReceiptTampering() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DurableTestExecutionCheckpoint.EngineState terminalEngine =
                terminalEngineState(claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand command =
                terminalCommand(claimed, "terminal-request-1", SHA_B, terminalEngine);
        repository.terminalizeRecoveryIdempotently(command,
                mutation(claimed.checkpoint().engineExecutionId(), terminalEngine,
                        ignored -> { }));

        DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand changed =
                new DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand(
                        command.clientRequestId(), command.requestFingerprint(),
                        command.expectedDispatch(),
                        DurableTestRecoveryTerminalReceipt.ExecutionOutcome.FAILED,
                        command.terminalEngineState(), command.fixtureConsumptionState(),
                        command.executionServiceState(), command.evidenceGapCodes());
        assertThatThrownBy(() -> repository.terminalizeRecoveryIdempotently(
                changed, mutation(claimed.checkpoint().engineExecutionId(), terminalEngine,
                        ignored -> { })))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);

        database.jdbc().update("""
                UPDATE rg_test_durable_recovery_terminals
                SET result_receipt_json = ?
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, "{}", "tenant-a", "test", "terminal-request-1");
        assertThatThrownBy(() -> repository.findRecoveryTerminalResult(
                "tenant-a", "test", "terminal-request-1", SHA_B))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal receipt is corrupt");
    }

    @Test
    void recoveryTerminalCommandRequiresExplicitEvidenceGaps() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                new DurableTestExecutionCheckpointRepository.LeaseClaimResult(
                        withLifecycle(expired, DurableTestExecutionCheckpoint.Status.RESUMING,
                                "instance-b", 2, 1,
                                expired.lifecycle().createdAt(),
                                expired.lifecycle().updatedAt().plusSeconds(1),
                                Instant.parse("9999-01-01T00:00:00Z")),
                        DurableTestRecoveryDispatch.issue(
                                new ObjectMapper().findAndRegisterModules(),
                                resumeCommand(expired, "resume-request-1", SHA_D,
                                        "instance-b").authorization(),
                                withLifecycle(expired,
                                        DurableTestExecutionCheckpoint.Status.RESUMING,
                                        "instance-b", 2, 1,
                                        expired.lifecycle().createdAt(),
                                        expired.lifecycle().updatedAt().plusSeconds(1),
                                        Instant.parse("9999-01-01T00:00:00Z"))), false);
        DurableTestExecutionCheckpoint.EngineState terminalEngine =
                terminalEngineState(claimed.checkpoint());

        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository
                .RecoveryTerminalCommand("terminal-request-1", SHA_B, claimed.dispatch(),
                DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED,
                terminalEngine, claimed.checkpoint().fixtureConsumptionState(),
                claimed.checkpoint().executionServiceState(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence gap");
    }

    @Test
    void recoveryTerminalCommandRejectsAStaleDispatchAfterHeartbeatRotation() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        repository.heartbeatRecoveryLeaseIdempotently(heartbeatCommand(
                claimed.dispatch(), "heartbeat-request-1", SHA_C, Duration.ofMinutes(3)));
        DurableTestExecutionCheckpoint.EngineState terminalEngine =
                terminalEngineState(claimed.checkpoint());

        assertThatThrownBy(() -> repository.terminalizeRecoveryIdempotently(
                terminalCommand(claimed, "terminal-request-stale", SHA_B, terminalEngine),
                mutation(claimed.checkpoint().engineExecutionId(), terminalEngine,
                        jdbc -> jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                "terminal-stale", claimed.checkpoint().engineExecutionId(),
                                terminalEngine.stateVersion()))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
        assertThat(engineCandidateCount("terminal-stale")).isZero();
    }

    @Test
    void recoveryTerminalCommandRejectsAnExpiredDispatchBeforeEngineMutation() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(resumeCommand(
                        expired, "resume-request-1", SHA_D, "instance-b",
                        Duration.ofSeconds(1)));
        DurableTestExecutionCheckpoint.EngineState terminalEngine =
                terminalEngineState(claimed.checkpoint());
        TimeUnit.MILLISECONDS.sleep(1100);

        assertThatThrownBy(() -> repository.terminalizeRecoveryIdempotently(
                terminalCommand(claimed, "terminal-request-expired", SHA_B, terminalEngine),
                mutation(claimed.checkpoint().engineExecutionId(), terminalEngine,
                        jdbc -> jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                "terminal-expired", claimed.checkpoint().engineExecutionId(),
                                terminalEngine.stateVersion()))))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.LEASE_EXPIRED);
        assertThat(engineCandidateCount("terminal-expired")).isZero();
    }

    @Test
    void recoveryTerminalCommandRejectsAValidButUnissuedDispatch() {
        DurableTestExecutionCheckpoint resuming = withLifecycle(
                checkpoint(0, "checkpoint-0"), DurableTestExecutionCheckpoint.Status.RESUMING,
                "instance-b", 2, 0,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"),
                Instant.parse("9999-01-01T00:00:00Z"));
        repository.create(resuming, boundNoop(resuming));
        DurableTestRecoveryDispatch unissued = DurableTestRecoveryDispatch.issue(
                new ObjectMapper().findAndRegisterModules(), resumeCommand(
                        resuming, "unused-resume", SHA_D, "instance-c").authorization(),
                resuming);
        DurableTestExecutionCheckpointRepository.LeaseClaimResult syntheticClaim =
                new DurableTestExecutionCheckpointRepository.LeaseClaimResult(
                        resuming, unissued, false);
        DurableTestExecutionCheckpoint.EngineState terminalEngine = terminalEngineState(resuming);

        assertThatThrownBy(() -> repository.terminalizeRecoveryIdempotently(
                terminalCommand(syntheticClaim, "terminal-request-unissued", SHA_B,
                        terminalEngine),
                mutation(resuming.engineExecutionId(), terminalEngine, ignored -> { })))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason
                        .UNRECOGNIZED_DISPATCH);
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(resuming);
    }

    @Test
    void concurrentRepositoryInstancesCommitOneTerminalMutationAndReplayItsReceipt()
            throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed =
                repository.claimExpiredLeaseIdempotently(
                        resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));
        DatabaseDurableTestExecutionCheckpointRepository competing =
                new DatabaseDurableTestExecutionCheckpointRepository(
                        database.jdbc(), database.transactionManager(),
                        new ObjectMapper().findAndRegisterModules(), integrity);
        competing.init();
        DurableTestExecutionCheckpoint.EngineState terminalEngine =
                terminalEngineState(claimed.checkpoint());
        DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand command =
                terminalCommand(claimed, "terminal-request-1", SHA_B, terminalEngine);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> idempotentTerminalCandidate(
                    repository, command, terminalEngine, start));
            var second = executor.submit(() -> idempotentTerminalCandidate(
                    competing, command, terminalEngine, start));
            start.countDown();
            List<DurableTestExecutionCheckpointRepository.RecoveryTerminalResult> results =
                    List.of(first.get(), second.get());

            assertThat(results).extracting(
                    DurableTestExecutionCheckpointRepository.RecoveryTerminalResult
                            ::idempotentReplay)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results).extracting(result -> result.receipt().receiptFingerprint())
                    .containsOnly(results.getFirst().receipt().receiptFingerprint());
        }
        assertThat(engineCandidateCount("terminal-concurrent")).isEqualTo(1);
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_recovery_terminals", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void recoveryDispatchReplayRejectsAuthorizationAndStoredDispatchDrift() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b");
        repository.claimExpiredLeaseIdempotently(command);

        DurableTestRecoveryAuthorization changedAuthorization =
                DurableTestRecoveryAuthorization.issue(
                        new ObjectMapper().findAndRegisterModules(),
                        expired.checkpointFingerprint(), SHA_A, SHA_B, SHA_A, SHA_C,
                        SHA_D, SHA_A, SHA_B, "GRAPH_CONTRACT_TEST", "DENY_REAL");
        assertThatThrownBy(() -> repository.claimExpiredLeaseIdempotently(
                new DurableTestExecutionCheckpointRepository.ResumeLeaseCommand(
                        command.clientRequestId(), command.requestFingerprint(), command.claim(),
                        changedAuthorization)))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);

        database.jdbc().update("""
                UPDATE rg_test_durable_resume_commands
                SET result_dispatch_json = ?
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, "{}", "tenant-a", "test", "resume-request-1");
        assertThatThrownBy(() -> repository.claimExpiredLeaseIdempotently(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recovery dispatch result is corrupt");
    }

    @Test
    void durableResumeCommandCanBeLookedUpWithoutConsultingOrMutatingTheLiveCheckpoint() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b");

        assertThat(repository.findLeaseClaimResult(
                "tenant-a", "test", "resume-request-1", SHA_D)).isEmpty();
        DurableTestExecutionCheckpointRepository.LeaseClaimResult first =
                repository.claimExpiredLeaseIdempotently(command);
        DurableTestExecutionCheckpoint advanced = advanceAfterClaim(first.checkpoint());

        assertThat(repository.findLeaseClaimResult(
                "tenant-a", "test", "resume-request-1", SHA_D)).get()
                .satisfies(replay -> {
                    assertThat(replay.idempotentReplay()).isTrue();
                    assertThat(replay.checkpoint()).isEqualTo(first.checkpoint());
                });
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(advanced);

        assertThatThrownBy(() -> repository.findLeaseClaimResult(
                "tenant-a", "test", "resume-request-1", SHA_C))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);
        assertThat(repository.findLeaseClaimResult(
                "tenant-b", "test", "resume-request-1", SHA_D)).isEmpty();
    }

    @Test
    void ownerClaimAndSemanticAuditCommitOrRollBackAsOneTransaction() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b");
        TestSecurityEvent event = new TestSecurityEvent(0,
                Instant.parse("2026-07-16T08:00:00Z"), "correlation-a", "tenant-a", "test",
                "recovery-worker", "DURABLE_OWNER_CLAIM", "ALLOWED",
                "RG.TEST.DURABLE_OWNER_CLAIM_AUTHORIZED", Map.of("runId", "run-a"));
        TestRuntimeTransactionMutation boundAudit = securityEvents.boundAppend(event);

        assertThatThrownBy(() -> repository.claimExpiredLeaseIdempotently(command, jdbc -> {
            boundAudit.apply(jdbc);
            throw new IllegalStateException("injected audit commit failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit commit failure");
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(expired);
        assertThat(repository.findLeaseClaimResult(
                "tenant-a", "test", "resume-request-1", SHA_D)).isEmpty();
        assertThat(securityEvents.recent(10)).isEmpty();

        DurableTestExecutionCheckpointRepository.LeaseClaimResult committed =
                repository.claimExpiredLeaseIdempotently(command, boundAudit);

        assertThat(committed.idempotentReplay()).isFalse();
        assertThat(securityEvents.recent(10)).singleElement().satisfies(stored -> {
            assertThat(stored.eventType()).isEqualTo("DURABLE_OWNER_CLAIM");
            assertThat(stored.outcome()).isEqualTo("ALLOWED");
            assertThat(stored.facts()).containsEntry("runId", "run-a");
        });

        TestSecurityEvent replayEvent = new TestSecurityEvent(0,
                Instant.parse("2026-07-16T08:01:00Z"), "correlation-b", "tenant-a", "test",
                "recovery-worker", "DURABLE_OWNER_CLAIM", "ALLOWED",
                "RG.TEST.DURABLE_OWNER_CLAIM_REPLAY_AUTHORIZED", Map.of("runId", "run-a"));
        DurableTestExecutionCheckpointRepository.LeaseClaimResult replay =
                repository.claimExpiredLeaseIdempotently(
                        command, securityEvents.boundAppend(replayEvent));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(securityEvents.recent(10)).extracting(TestSecurityEvent::reasonCode)
                .containsExactly("RG.TEST.DURABLE_OWNER_CLAIM_REPLAY_AUTHORIZED",
                        "RG.TEST.DURABLE_OWNER_CLAIM_AUTHORIZED");
    }

    @Test
    void durableResumeCommandRejectsIdempotencyKeyReuseForDifferentIntent() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        repository.claimExpiredLeaseIdempotently(
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b"));

        assertThatThrownBy(() -> repository.claimExpiredLeaseIdempotently(
                resumeCommand(expired, "resume-request-1", SHA_C, "instance-b")))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);
        assertThatThrownBy(() -> repository.claimExpiredLeaseIdempotently(
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-c")))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void concurrentRepositoryInstancesReplayOneDurableResumeCommand() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DatabaseDurableTestExecutionCheckpointRepository competing =
                new DatabaseDurableTestExecutionCheckpointRepository(
                        database.jdbc(), database.transactionManager(),
                        new ObjectMapper().findAndRegisterModules(), integrity);
        competing.init();
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> idempotentClaimCandidate(repository, command, start));
            var second = executor.submit(() -> idempotentClaimCandidate(competing, command, start));
            start.countDown();
            List<DurableTestExecutionCheckpointRepository.LeaseClaimResult> results =
                    List.of(first.get(), second.get());

            assertThat(results).extracting(
                    DurableTestExecutionCheckpointRepository.LeaseClaimResult::idempotentReplay)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results).extracting(result -> result.checkpoint().checkpointFingerprint())
                    .containsOnly(results.getFirst().checkpoint().checkpointFingerprint());
        }
    }

    @Test
    void durableResumeCommandIsScopeIsolatedAndVerifiesStoredResultIntegrity() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b");
        repository.claimExpiredLeaseIdempotently(command);

        DurableTestExecutionCheckpointRepository.LeaseClaim crossTenantClaim =
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        "tenant-b", "test", expired.runId(), command.claim().expectedFence(),
                        expired.checkpointFingerprint(), "instance-b", Duration.ofMinutes(2));
        assertThatThrownBy(() -> repository.claimExpiredLeaseIdempotently(
                new DurableTestExecutionCheckpointRepository.ResumeLeaseCommand(
                        "resume-request-1", SHA_D, crossTenantClaim,
                        command.authorization())))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);

        database.jdbc().update("""
                UPDATE rg_test_durable_resume_commands
                SET result_checkpoint_json = ?
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, "{}", "tenant-a", "test", "resume-request-1");
        assertThatThrownBy(() -> repository.claimExpiredLeaseIdempotently(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resume command result is corrupt");
    }

    @Test
    void durableResumeCommandRejectsIndexedIntentDriftAsStorageCorruption() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b");
        repository.claimExpiredLeaseIdempotently(command);

        database.jdbc().update("""
                UPDATE rg_test_durable_resume_commands
                SET claimant_owner_id = ?
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, "instance-z", "tenant-a", "test", "resume-request-1");

        assertThatThrownBy(() -> repository.claimExpiredLeaseIdempotently(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resume command record is corrupt");
    }

    @Test
    void validatesDurableResumeCommandIdentityBeforePersistence() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        DurableTestExecutionCheckpointRepository.LeaseClaim claim =
                resumeCommand(expired, "resume-request-1", SHA_D, "instance-b").claim();

        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository.ResumeLeaseCommand(
                "", SHA_D, claim, resumeCommand(
                        expired, "valid", SHA_D, "instance-b").authorization()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientRequestId");
        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository.ResumeLeaseCommand(
                "resume request with spaces", SHA_D, claim, resumeCommand(
                        expired, "valid", SHA_D, "instance-b").authorization()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientRequestId");
        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository.ResumeLeaseCommand(
                "resume-request-1", "not-a-fingerprint", claim, resumeCommand(
                        expired, "valid", SHA_D, "instance-b").authorization()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint");
    }

    @Test
    void validatesLeaseClaimScopeFingerprintAndDurationBeforePersistence() {
        var fence = new DurableTestExecutionCheckpointRepository.Fence("instance-a", 1, 0);

        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository.LeaseClaim(
                "tenant-a", "production", "run-a", fence, SHA_A,
                "instance-b", Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("test or staging");
        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository.LeaseClaim(
                "tenant-a", "test", "run-a", fence, "not-a-fingerprint",
                "instance-b", Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical SHA-256");
        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository.LeaseClaim(
                "tenant-a", "test", "run-a", fence, SHA_A,
                "instance-b", Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository.LeaseClaim(
                "tenant-a", "test", "run-a", fence, SHA_A,
                "instance-b", Duration.ofHours(1).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
        assertThatThrownBy(() -> new DurableTestExecutionCheckpointRepository.LeaseClaim(
                "tenant-a", "test", "run-a", fence, SHA_A,
                "instance-b", Duration.ofSeconds(1).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
    }

    @Test
    void scansOnlyExpiredCandidatesInExactScopeWithStableOldestFirstLimit() {
        DurableTestExecutionCheckpoint oldest = identifiedCheckpoint(
                expiredCheckpoint(), "run-oldest", "engine-oldest", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:01Z"));
        DurableTestExecutionCheckpoint next = identifiedCheckpoint(
                expiredCheckpoint(), "run-next", "engine-next", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:02Z"));
        DurableTestExecutionCheckpoint later = identifiedCheckpoint(
                expiredCheckpoint(), "run-later", "engine-later", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:03Z"));
        DurableTestExecutionCheckpoint otherProject = identifiedCheckpoint(
                expiredCheckpoint(), "run-other", "engine-other", "org-a", "project-other",
                Instant.parse("2000-01-01T00:00:01Z"));
        DurableTestExecutionCheckpoint live = identifiedCheckpoint(
                expiredCheckpoint(), "run-live", "engine-live", "org-a", "project-a",
                Instant.now().plusSeconds(3_600));
        for (DurableTestExecutionCheckpoint checkpoint :
                List.of(later, otherProject, next, live, oldest)) {
            repository.create(checkpoint, boundNoop(checkpoint));
        }

        DurableTestExecutionCheckpointRepository.RecoveryCandidatePage page =
                repository.findExpiredRecoveryCandidates(
                        new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                                workerScope(), 2));

        assertThat(page.candidates()).extracting(candidate -> candidate.checkpoint().runId())
                .containsExactly("run-oldest", "run-next");
    }

    @Test
    void cyclicCursorMakesWorkBeyondAnIneligiblePrefixReachableAndWrapsOnce() {
        DurableTestExecutionCheckpoint oldest = identifiedCheckpoint(
                expiredCheckpoint(), "run-oldest", "engine-oldest", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:01Z"));
        DurableTestExecutionCheckpoint next = identifiedCheckpoint(
                expiredCheckpoint(), "run-next", "engine-next", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:02Z"));
        DurableTestExecutionCheckpoint later = identifiedCheckpoint(
                expiredCheckpoint(), "run-later", "engine-later", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:03Z"));
        for (DurableTestExecutionCheckpoint checkpoint : List.of(later, next, oldest)) {
            repository.create(checkpoint, boundNoop(checkpoint));
        }

        var firstPage = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 2));
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-cycle-1", SHA_A), Optional.empty(),
                Optional.of(firstPage.candidates().getLast().progress()),
                TestRuntimeTransactionMutation.noop());

        var secondPage = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 2));

        assertThat(secondPage.candidates())
                .extracting(candidate -> candidate.checkpoint().runId())
                .containsExactly("run-later", "run-oldest");
        assertThat(secondPage.candidates())
                .extracting(candidate -> candidate.progress().nextCycleEpoch())
                .containsExactly(0L, 1L);
    }

    @Test
    void recordsDeterministicCandidateBackoffAndReturnsItOnTheNextCyclicScan() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        var observation = new DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral(
                candidate.progress(), expired.checkpointFingerprint(),
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .AUTHORIZATION_DENIED,
                Duration.ofSeconds(5), Duration.ofMinutes(5));

        var result = repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-1", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(observation),
                TestRuntimeTransactionMutation.noop());
        var nextPage = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 1));

        assertThat(result.outcome()).isEqualTo(
                DurableTestExecutionCheckpointRepository.WorkerAcquisitionOutcome.NO_WORK);
        assertThat(nextPage.candidates()).singleElement().satisfies(deferred -> {
            assertThat(deferred.checkpoint().runId()).isEqualTo(expired.runId());
            assertThat(deferred.activeDeferral()).isPresent();
            assertThat(deferred.activeDeferral().orElseThrow().reason())
                    .isEqualTo(DurableTestExecutionCheckpointRepository
                            .WorkerCandidateDeferralReason.AUTHORIZATION_DENIED);
            assertThat(deferred.activeDeferral().orElseThrow().consecutiveFailures()).isOne();
            assertThat(deferred.activeDeferral().orElseThrow().retryAfter())
                    .isAfter(result.observedAt());
        });
    }

    @Test
    void projectsMultipleActiveCandidateBackoffsInOneBoundedPage() {
        DurableTestExecutionCheckpoint oldest = identifiedCheckpoint(
                expiredCheckpoint(), "run-oldest", "engine-oldest", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:01Z"));
        DurableTestExecutionCheckpoint next = identifiedCheckpoint(
                expiredCheckpoint(), "run-next", "engine-next", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:02Z"));
        repository.create(oldest, boundNoop(oldest));
        repository.create(next, boundNoop(next));
        var firstPage = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 2));
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-page", SHA_A), Optional.empty(),
                Optional.of(firstPage.candidates().getLast().progress()),
                firstPage.candidates().stream().map(candidate -> workerDeferral(
                        candidate,
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_DENIED)).toList(),
                TestRuntimeTransactionMutation.noop());

        var projected = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 2));

        assertThat(projected.candidates())
                .extracting(candidate -> candidate.checkpoint().runId())
                .containsExactly("run-oldest", "run-next");
        assertThat(projected.candidates())
                .allSatisfy(candidate -> assertThat(candidate.activeDeferral()).isPresent());
    }

    @Test
    void activeCandidateBackoffCannotBeCountedAgainBeforeItsRetryDeadline() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var first = workerCandidate();
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-first", SHA_A), Optional.empty(),
                Optional.of(first.progress()), List.of(workerDeferral(first,
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_DENIED)),
                TestRuntimeTransactionMutation.noop());
        var active = workerCandidate();

        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-active", SHA_B), Optional.empty(),
                Optional.of(active.progress()), List.of(workerDeferral(active,
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_DENIED)),
                TestRuntimeTransactionMutation.noop());

        assertThat(database.jdbc().queryForObject("""
                SELECT consecutive_failures
                FROM rg_test_durable_worker_candidate_deferrals
                WHERE run_id = 'run-a'
                """, Long.class)).isOne();
    }

    @Test
    void sameCandidateFailureUsesDatabaseTimedExponentialBackoffWithACap() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));

        for (int observation = 1; observation <= 3; observation++) {
            var candidate = workerCandidate();
            assertThat(candidate.activeDeferral()).isEmpty();
            var deferral = new DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral(
                    candidate.progress(), candidate.checkpoint().checkpointFingerprint(),
                    DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                            .AUTHORIZATION_CONFLICT,
                    Duration.ofSeconds(1), Duration.ofSeconds(2));
            repository.acquireWorkerCommandIdempotently(
                    workerCommand("worker-deferral-exponential-" + observation,
                            observation == 1 ? SHA_A : observation == 2 ? SHA_B : SHA_C),
                    Optional.empty(), Optional.of(candidate.progress()), List.of(deferral),
                    TestRuntimeTransactionMutation.noop());

            var stored = database.jdbc().queryForObject("""
                            SELECT consecutive_failures, last_observed_at, retry_after
                            FROM rg_test_durable_worker_candidate_deferrals
                            WHERE run_id = 'run-a'
                            """, (rs, row) -> Map.entry(
                            rs.getLong("consecutive_failures"),
                            Duration.between(
                                    rs.getTimestamp("last_observed_at").toInstant(),
                                    rs.getTimestamp("retry_after").toInstant())));
            assertThat(stored).isNotNull();
            assertThat(stored.getKey()).isEqualTo((long) observation);
            assertThat(stored.getValue())
                    .isEqualTo(Duration.ofSeconds(observation == 1 ? 1 : 2));
            if (observation < 3) {
                Thread.sleep(observation == 1 ? 1_100 : 2_100);
            }
        }
    }

    @Test
    void checkpointFingerprintChangeImmediatelyInvalidatesHistoricalBackoff() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-before-change", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(workerDeferral(candidate,
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_DENIED)),
                TestRuntimeTransactionMutation.noop());
        DurableTestExecutionCheckpoint claimed = repository.claimExpiredLease(
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        "tenant-a", "test", expired.runId(),
                        new DurableTestExecutionCheckpointRepository.Fence(
                                expired.lifecycle().ownerId(), expired.lifecycle().leaseEpoch(),
                                expired.lifecycle().revision()),
                        expired.checkpointFingerprint(), "replacement-owner",
                        Duration.ofSeconds(1)));
        Thread.sleep(1_100);

        var changed = workerCandidate();

        assertThat(changed.checkpoint().checkpointFingerprint())
                .isEqualTo(claimed.checkpointFingerprint())
                .isNotEqualTo(expired.checkpointFingerprint());
        assertThat(changed.activeDeferral()).isEmpty();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_deferrals",
                Integer.class)).isZero();
    }

    @Test
    void staleConcurrentScanCannotCreateOrAmplifyCandidateBackoff() {
        DurableTestExecutionCheckpoint oldest = identifiedCheckpoint(
                expiredCheckpoint(), "run-oldest", "engine-oldest", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:01Z"));
        DurableTestExecutionCheckpoint next = identifiedCheckpoint(
                expiredCheckpoint(), "run-next", "engine-next", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:02Z"));
        repository.create(oldest, boundNoop(oldest));
        repository.create(next, boundNoop(next));
        var shared = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 2));
        var stale = shared.candidates().getFirst();

        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-winner", SHA_A), Optional.empty(),
                Optional.of(shared.candidates().getLast().progress()), List.of(),
                TestRuntimeTransactionMutation.noop());
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-stale", SHA_B), Optional.empty(),
                Optional.of(stale.progress()), List.of(workerDeferral(stale,
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_CONFLICT)),
                TestRuntimeTransactionMutation.noop());

        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_deferrals",
                Integer.class)).isZero();
    }

    @Test
    void rejectsTamperedCandidateBackoffBeforeItCanSuppressAuthorization() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-tamper", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(workerDeferral(candidate,
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_DENIED)),
                TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_candidate_deferrals
                SET consecutive_failures = 99
                WHERE run_id = 'run-a'
                """);

        assertThatThrownBy(this::workerCandidate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate deferral is corrupt");
    }

    @Test
    void companionAuditFailureRollsBackCandidateBackoffCursorAndNoWorkResult() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();

        assertThatThrownBy(() -> repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-deferral-rollback", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(workerDeferral(candidate,
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_CONFLICT)),
                ignored -> {
                    throw new IllegalStateException("audit unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit unavailable");

        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_deferrals",
                Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_scan_cursors", Integer.class))
                .isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_acquisitions", Integer.class))
                .isZero();
    }

    @Test
    void deterministicCandidateFailureAtThresholdBecomesPermanentExactCheckpointQuarantine() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        var observation = new DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral(
                candidate.progress(), expired.checkpointFingerprint(),
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .AUTHORIZATION_DENIED,
                Duration.ofSeconds(5), Duration.ofMinutes(5), 1);

        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-quarantine-threshold", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(observation),
                TestRuntimeTransactionMutation.noop());
        var projected = workerCandidate();

        assertThat(projected.activeDeferral()).isEmpty();
        assertThat(projected.activeQuarantine()).isPresent();
        assertThat(projected.activeQuarantine().orElseThrow().reason())
                .isEqualTo(DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferralReason.AUTHORIZATION_DENIED);
        assertThat(projected.activeQuarantine().orElseThrow().consecutiveFailures()).isOne();
        assertThat(projected.activeQuarantine().orElseThrow().quarantineThreshold()).isOne();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_deferrals",
                Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_quarantines",
                Integer.class)).isOne();
    }

    @Test
    void staleConcurrentScanCannotQuarantineCandidate() {
        DurableTestExecutionCheckpoint oldest = identifiedCheckpoint(
                expiredCheckpoint(), "run-oldest", "engine-oldest", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:01Z"));
        DurableTestExecutionCheckpoint next = identifiedCheckpoint(
                expiredCheckpoint(), "run-next", "engine-next", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:02Z"));
        repository.create(oldest, boundNoop(oldest));
        repository.create(next, boundNoop(next));
        var shared = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 2));
        var stale = shared.candidates().getFirst();

        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-quarantine-winner", SHA_A), Optional.empty(),
                Optional.of(shared.candidates().getLast().progress()), List.of(),
                TestRuntimeTransactionMutation.noop());
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-quarantine-stale", SHA_B), Optional.empty(),
                Optional.of(stale.progress()), List.of(new DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferral(
                        stale.progress(), stale.checkpoint().checkpointFingerprint(),
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_CONFLICT,
                        Duration.ofSeconds(5), Duration.ofMinutes(5), 1)),
                TestRuntimeTransactionMutation.noop());

        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_quarantines",
                Integer.class)).isZero();
    }

    @Test
    void workerSelectionCannotBypassAnExactCheckpointQuarantine() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-quarantine-create", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(new DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferral(
                        candidate.progress(), expired.checkpointFingerprint(),
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_DENIED,
                        Duration.ofSeconds(5), Duration.ofMinutes(5), 1)),
                TestRuntimeTransactionMutation.noop());
        var quarantined = workerCandidate();

        assertThatThrownBy(() -> repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-quarantine-bypass", SHA_B),
                Optional.of(workerSelection(expired, "worker-bypass")),
                Optional.of(quarantined.progress()), List.of(),
                TestRuntimeTransactionMutation.noop()))
                .isInstanceOfSatisfying(
                        com.leanowtech.bloge.gateway.testing.api
                                .DurableTestExecutionCheckpointConflictException.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(
                                com.leanowtech.bloge.gateway.testing.api
                                        .DurableTestExecutionCheckpointConflictException.Reason
                                        .NOT_RESUMABLE));
        assertThat(repository.find("tenant-a", "test", expired.runId()).orElseThrow()
                .checkpointFingerprint()).isEqualTo(expired.checkpointFingerprint());
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_quarantines",
                Integer.class)).isOne();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_acquisitions",
                Integer.class)).isOne();
    }

    @Test
    void checkpointFingerprintChangeInvalidatesHistoricalCandidateQuarantine() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-quarantine-before-change", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(new DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferral(
                        candidate.progress(), expired.checkpointFingerprint(),
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .LEGACY_PROTOCOL,
                        Duration.ofSeconds(5), Duration.ofMinutes(5), 1)),
                TestRuntimeTransactionMutation.noop());

        repository.claimExpiredLease(
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        "tenant-a", "test", expired.runId(),
                        new DurableTestExecutionCheckpointRepository.Fence(
                                expired.lifecycle().ownerId(), expired.lifecycle().leaseEpoch(),
                                expired.lifecycle().revision()),
                        expired.checkpointFingerprint(), "replacement-owner",
                        Duration.ofSeconds(1)));
        Thread.sleep(1_100);

        assertThat(workerCandidate().activeQuarantine()).isEmpty();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_quarantines",
                Integer.class)).isZero();
    }

    @Test
    void concurrentCheckpointTransitionCannotLeaveAStaleCandidateQuarantine() throws Exception {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        var observation = new DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral(
                candidate.progress(), expired.checkpointFingerprint(),
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .AUTHORIZATION_DENIED,
                Duration.ofSeconds(5), Duration.ofMinutes(5), 1);
        CountDownLatch transitionWritten = new CountDownLatch(1);
        CountDownLatch releaseTransition = new CountDownLatch(1);
        AtomicReference<DurableTestExecutionCheckpoint> claimed = new AtomicReference<>();
        TransactionTemplate transition = new TransactionTemplate(database.transactionManager());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var transitioning = executor.submit(() -> transition.executeWithoutResult(ignored -> {
                claimed.set(repository.claimExpiredLease(
                        new DurableTestExecutionCheckpointRepository.LeaseClaim(
                                "tenant-a", "test", expired.runId(),
                                new DurableTestExecutionCheckpointRepository.Fence(
                                        expired.lifecycle().ownerId(),
                                        expired.lifecycle().leaseEpoch(),
                                        expired.lifecycle().revision()),
                                expired.checkpointFingerprint(), "replacement-owner",
                                Duration.ofSeconds(1))));
                transitionWritten.countDown();
                try {
                    if (!releaseTransition.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("transition release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("transition interrupted", interrupted);
                }
            }));
            assertThat(transitionWritten.await(5, TimeUnit.SECONDS)).isTrue();
            var scanning = executor.submit(() -> repository.acquireWorkerCommandIdempotently(
                    workerCommand("worker-quarantine-transition-race", SHA_A), Optional.empty(),
                    Optional.of(candidate.progress()), List.of(observation),
                    TestRuntimeTransactionMutation.noop()));
            Thread.sleep(100);
            assertThat(scanning.isDone()).isFalse();

            releaseTransition.countDown();
            transitioning.get(5, TimeUnit.SECONDS);
            assertThat(scanning.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(
                    DurableTestExecutionCheckpointRepository.WorkerAcquisitionOutcome.NO_WORK);
        }

        assertThat(claimed.get()).isNotNull();
        assertThat(repository.find("tenant-a", "test", expired.runId()).orElseThrow()
                .checkpointFingerprint()).isEqualTo(claimed.get().checkpointFingerprint());
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_quarantines",
                Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_deferrals",
                Integer.class)).isZero();
    }

    @Test
    void rejectsTamperedCandidateQuarantineBeforeItCanSuppressAuthorization() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-quarantine-tamper", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(new DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferral(
                        candidate.progress(), expired.checkpointFingerprint(),
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .AUTHORIZATION_DENIED,
                        Duration.ofSeconds(5), Duration.ofMinutes(5), 1)),
                TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_candidate_quarantines
                SET consecutive_failures = 99
                WHERE run_id = 'run-a'
                """);

        assertThatThrownBy(this::workerCandidate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate quarantine is corrupt");
    }

    @Test
    void companionAuditFailureRollsBackCandidateQuarantineCursorAndNoWorkResult() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        var observation = new DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral(
                candidate.progress(), expired.checkpointFingerprint(),
                DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                        .AUTHORIZATION_CONFLICT,
                Duration.ofSeconds(5), Duration.ofMinutes(5), 1);

        assertThatThrownBy(() -> repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-quarantine-rollback", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), List.of(observation), ignored -> {
                    throw new IllegalStateException("audit unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit unavailable");

        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_candidate_quarantines",
                Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_scan_cursors", Integer.class))
                .isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_acquisitions", Integer.class))
                .isZero();
    }

    @Test
    void staleConcurrentScanProgressCannotRegressTheCommittedCursor() {
        DurableTestExecutionCheckpoint oldest = identifiedCheckpoint(
                expiredCheckpoint(), "run-oldest", "engine-oldest", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:01Z"));
        DurableTestExecutionCheckpoint next = identifiedCheckpoint(
                expiredCheckpoint(), "run-next", "engine-next", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:02Z"));
        DurableTestExecutionCheckpoint later = identifiedCheckpoint(
                expiredCheckpoint(), "run-later", "engine-later", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:03Z"));
        for (DurableTestExecutionCheckpoint checkpoint : List.of(later, next, oldest)) {
            repository.create(checkpoint, boundNoop(checkpoint));
        }
        var sharedSnapshot = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 2));

        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-cursor-winner", SHA_A), Optional.empty(),
                Optional.of(sharedSnapshot.candidates().getLast().progress()),
                TestRuntimeTransactionMutation.noop());
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-cursor-stale", SHA_B), Optional.empty(),
                Optional.of(sharedSnapshot.candidates().getFirst().progress()),
                TestRuntimeTransactionMutation.noop());

        var afterRace = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 1));
        assertThat(afterRace.candidates()).singleElement()
                .extracting(candidate -> candidate.checkpoint().runId())
                .isEqualTo("run-later");
    }

    @Test
    void idempotentReplayCannotApplyProgressFromAChangedQueueObservation() {
        DurableTestExecutionCheckpoint oldest = identifiedCheckpoint(
                expiredCheckpoint(), "run-oldest", "engine-oldest", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:01Z"));
        DurableTestExecutionCheckpoint next = identifiedCheckpoint(
                expiredCheckpoint(), "run-next", "engine-next", "org-a", "project-a",
                Instant.parse("2000-01-01T00:00:02Z"));
        repository.create(next, boundNoop(next));
        repository.create(oldest, boundNoop(oldest));
        var command = workerCommand("worker-cursor-replay", SHA_A);
        var firstPage = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 1));
        repository.acquireWorkerCommandIdempotently(
                command, Optional.empty(),
                Optional.of(firstPage.candidates().getFirst().progress()),
                TestRuntimeTransactionMutation.noop());
        var changedObservation = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 1));

        var replay = repository.acquireWorkerCommandIdempotently(
                command, Optional.empty(),
                Optional.of(changedObservation.candidates().getFirst().progress()),
                TestRuntimeTransactionMutation.noop());
        var afterReplay = repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 1));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(afterReplay.candidates()).singleElement()
                .extracting(candidate -> candidate.checkpoint().runId())
                .isEqualTo("run-next");
    }

    @Test
    void rejectsAnInitialCursorTokenThatClaimsToHaveWrapped() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        var invalid = new DurableTestExecutionCheckpointRepository.WorkerScanProgress(
                workerScope(), candidate.progress().expectedCursorFingerprint(), 1,
                candidate.progress().nextLeaseExpiresAt(), candidate.progress().nextUpdatedAt(),
                candidate.progress().nextRunId());

        assertThatThrownBy(() -> repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-invalid-wrap", SHA_A), Optional.empty(),
                Optional.of(invalid), TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle zero");
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_scan_cursors", Integer.class))
                .isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_acquisitions", Integer.class))
                .isZero();
    }

    @Test
    void rejectsTamperedWorkerScanCursorScopeProjectionBeforeReturningCandidates() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var candidate = workerCandidate();
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-cursor-tamper", SHA_A), Optional.empty(),
                Optional.of(candidate.progress()), TestRuntimeTransactionMutation.noop());

        database.jdbc().update("""
                UPDATE rg_test_durable_worker_scan_cursors
                SET project_id = 'project-tampered'
                WHERE tenant_id = 'tenant-a' AND environment_id = 'test'
                """);

        assertThatThrownBy(this::workerCandidate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scan cursor is corrupt");
    }

    @Test
    void persistsNoWorkAsAnImmutableDatabaseTimedOutcomeEvenWhenWorkAppearsLater() {
        var command = workerCommand("worker-poll-1", SHA_A);

        var first = repository.acquireWorkerCommandIdempotently(
                command, Optional.empty(), Optional.empty(),
                TestRuntimeTransactionMutation.noop());

        assertThat(first.outcome()).isEqualTo(
                DurableTestExecutionCheckpointRepository.WorkerAcquisitionOutcome.NO_WORK);
        assertThat(first.idempotentReplay()).isFalse();
        assertThat(first.checkpoint()).isNull();
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));

        var replay = repository.acquireWorkerCommandIdempotently(
                command, Optional.of(workerSelection(expired, "worker-a")),
                Optional.of(workerProgress(expired)),
                TestRuntimeTransactionMutation.noop());

        assertThat(replay.outcome()).isEqualTo(
                DurableTestExecutionCheckpointRepository.WorkerAcquisitionOutcome.NO_WORK);
        assertThat(replay.observedAt()).isEqualTo(first.observedAt());
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(repository.find("tenant-a", "test", "run-a"))
                .contains(expired);
    }

    @Test
    void atomicallyClaimsWorkerAssignmentDispatchResultAndCompanionAudit() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var command = workerCommand("worker-poll-1", SHA_A);
        var candidate = workerCandidate();

        var acquired = repository.acquireWorkerCommandIdempotently(
                command, Optional.of(workerSelection(expired, "worker-a")),
                Optional.of(candidate.progress()),
                jdbc -> jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                        "worker-audit", "engine-a", 1));

        assertThat(acquired.outcome()).isEqualTo(
                DurableTestExecutionCheckpointRepository.WorkerAcquisitionOutcome.ACQUIRED);
        assertThat(acquired.checkpoint().lifecycle().status())
                .isEqualTo(DurableTestExecutionCheckpoint.Status.RESUMING);
        assertThat(acquired.checkpoint().lifecycle().ownerId()).isEqualTo("worker-a");
        acquired.dispatch().requireValid(
                new ObjectMapper().findAndRegisterModules(), acquired.checkpoint());
        assertThat(engineCandidateCount("worker-audit")).isOne();

        var replay = repository.findWorkerAcquisitionResult(
                workerScope(), "worker-poll-1", SHA_A).orElseThrow();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.checkpoint()).isEqualTo(acquired.checkpoint());
        assertThat(replay.dispatch()).isEqualTo(acquired.dispatch());
    }

    @Test
    void companionAuditFailureRollsBackWorkerClaimDispatchAndCommandResult() {
        DurableTestExecutionCheckpoint expired = expiredCheckpoint();
        repository.create(expired, boundNoop(expired));
        var command = workerCommand("worker-poll-1", SHA_A);
        var candidate = workerCandidate();

        assertThatThrownBy(() -> repository.acquireWorkerCommandIdempotently(
                command, Optional.of(workerSelection(expired, "worker-a")),
                Optional.of(candidate.progress()),
                ignored -> {
                    throw new IllegalStateException("audit unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit unavailable");

        assertThat(repository.find("tenant-a", "test", "run-a")).contains(expired);
        assertThat(repository.findWorkerAcquisitionResult(
                workerScope(), "worker-poll-1", SHA_A)).isEmpty();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_acquisitions", Integer.class))
                .isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_scan_cursors", Integer.class))
                .isZero();
    }

    @Test
    void rejectsWorkerAcquisitionRecordTamperingAndScopedKeyReuse() {
        repository.acquireWorkerCommandIdempotently(
                workerCommand("worker-poll-1", SHA_A), Optional.empty(),
                Optional.empty(),
                TestRuntimeTransactionMutation.noop());

        assertThatThrownBy(() -> repository.findWorkerAcquisitionResult(
                workerScope(), "worker-poll-1", SHA_B))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(
                        DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT);

        database.jdbc().update("""
                UPDATE rg_test_durable_worker_acquisitions SET outcome = 'ACQUIRED'
                WHERE client_request_id = 'worker-poll-1'
                """);
        assertThatThrownBy(() -> repository.findWorkerAcquisitionResult(
                workerScope(), "worker-poll-1", SHA_A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("record is corrupt");
    }

    @Test
    void sameWorkerPollKeyIsIndependentAcrossProjectsInOneTenant() {
        var projectA = workerCommand("worker-poll-shared", SHA_A);
        var projectBScope = new DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope(
                "tenant-a", "org-a", "project-b", "test");
        var projectB = new DurableTestExecutionCheckpointRepository.WorkerAcquisitionCommand(
                "worker-poll-shared", SHA_B, projectBScope);

        var first = repository.acquireWorkerCommandIdempotently(
                projectA, Optional.empty(), Optional.empty(),
                TestRuntimeTransactionMutation.noop());
        var second = repository.acquireWorkerCommandIdempotently(
                projectB, Optional.empty(), Optional.empty(),
                TestRuntimeTransactionMutation.noop());

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(second.idempotentReplay()).isFalse();
        assertThat(repository.findWorkerAcquisitionResult(
                workerScope(), "worker-poll-shared", SHA_A)).isPresent();
        assertThat(repository.findWorkerAcquisitionResult(
                projectBScope, "worker-poll-shared", SHA_B)).isPresent();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_acquisitions", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rejectsLeaseFenceCounterOverflowWithoutChangingTheCheckpoint() {
        DurableTestExecutionCheckpoint exhausted = withLifecycle(
                checkpoint(0, "checkpoint-0"),
                DurableTestExecutionCheckpoint.Status.SUSPENDED,
                "instance-a", Long.MAX_VALUE, 0,
                Instant.parse("2000-01-01T00:00:00Z"),
                Instant.parse("2000-01-01T00:00:01Z"),
                Instant.parse("2000-01-01T00:00:02Z"));
        repository.create(exhausted, boundNoop(exhausted));

        assertThatThrownBy(() -> claim(exhausted, "tenant-a", "instance-b"))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error)
                        .reason())
                .isEqualTo(
                        DurableTestExecutionCheckpointConflictException.Reason.INVALID_TRANSITION);
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(exhausted);
    }

    private boolean advanceCandidate(String candidate, CountDownLatch entered, CountDownLatch release) {
        try {
            DurableTestExecutionCheckpoint candidateCheckpoint =
                    checkpoint(1, "checkpoint-" + candidate);
            repository.advance(candidateCheckpoint,
                    new DurableTestExecutionCheckpointRepository.Fence("instance-a", 1, 0),
                    mutation(candidateCheckpoint, jdbc -> {
                        jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                candidate, "engine-a", 1);
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("concurrency test did not release");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("concurrency test interrupted", interrupted);
                        }
                    }));
            return true;
        } catch (DurableTestExecutionCheckpointConflictException expected) {
            assertThat(expected.reason())
                    .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
            return false;
        }
    }

    private int engineCandidateCount(String candidate) {
        return database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_engine_state WHERE candidate_id = ?",
                Integer.class, candidate);
    }

    private DurableTestExecutionCheckpoint claim(
            DurableTestExecutionCheckpoint checkpoint,
            String tenantId,
            String claimant) {
        return repository.claimExpiredLease(
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        tenantId, "test", checkpoint.runId(),
                        new DurableTestExecutionCheckpointRepository.Fence(
                                checkpoint.lifecycle().ownerId(),
                                checkpoint.lifecycle().leaseEpoch(),
                                checkpoint.lifecycle().revision()),
                        checkpoint.checkpointFingerprint(), claimant, Duration.ofMinutes(2)));
    }

    private boolean claimCandidate(
            DatabaseDurableTestExecutionCheckpointRepository candidateRepository,
            DurableTestExecutionCheckpoint checkpoint,
            String claimant,
            CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent claim did not start");
            }
            candidateRepository.claimExpiredLease(
                    new DurableTestExecutionCheckpointRepository.LeaseClaim(
                            "tenant-a", "test", checkpoint.runId(),
                            new DurableTestExecutionCheckpointRepository.Fence(
                                    "instance-a", 1, 0),
                            checkpoint.checkpointFingerprint(), claimant,
                            Duration.ofMinutes(2)));
            return true;
        } catch (DurableTestExecutionCheckpointConflictException expected) {
            assertThat(expected.reason())
                    .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent claim interrupted", interrupted);
        }
    }

    private DurableTestExecutionCheckpointRepository.LeaseClaimResult idempotentClaimCandidate(
            DatabaseDurableTestExecutionCheckpointRepository candidateRepository,
            DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command,
            CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent command did not start");
            }
            return candidateRepository.claimExpiredLeaseIdempotently(command);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent command interrupted", interrupted);
        }
    }

    private DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult
            idempotentHeartbeatCandidate(
                    DatabaseDurableTestExecutionCheckpointRepository candidateRepository,
                    DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand command,
                    CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent heartbeat did not start");
            }
            return candidateRepository.heartbeatRecoveryLeaseIdempotently(command);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent heartbeat interrupted", interrupted);
        }
    }

    private DurableTestExecutionCheckpointRepository.RecoveryTerminalResult
            idempotentTerminalCandidate(
                    DatabaseDurableTestExecutionCheckpointRepository candidateRepository,
                    DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand command,
                    DurableTestExecutionCheckpoint.EngineState terminalEngine,
                    CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent terminal command did not start");
            }
            return candidateRepository.terminalizeRecoveryIdempotently(command,
                    mutation(command.expectedDispatch().engineExecutionId(), terminalEngine,
                            jdbc -> jdbc.update(
                                    "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                    "terminal-concurrent",
                                    command.expectedDispatch().engineExecutionId(),
                                    terminalEngine.stateVersion())));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "concurrent terminal command interrupted", interrupted);
        }
    }

    private DurableTestExecutionCheckpointRepository.ResumeLeaseCommand resumeCommand(
            DurableTestExecutionCheckpoint checkpoint,
            String clientRequestId,
            String requestFingerprint,
            String claimant) {
        return resumeCommand(checkpoint, clientRequestId, requestFingerprint, claimant,
                Duration.ofMinutes(2));
    }

    private DurableTestExecutionCheckpointRepository.ResumeLeaseCommand resumeCommand(
            DurableTestExecutionCheckpoint checkpoint,
            String clientRequestId,
            String requestFingerprint,
            String claimant,
            Duration leaseDuration) {
        return resumeCommand(checkpoint, clientRequestId, requestFingerprint, claimant,
                leaseDuration, SHA_D);
    }

    private DurableTestExecutionCheckpointRepository.ResumeLeaseCommand resumeCommand(
            DurableTestExecutionCheckpoint checkpoint,
            String clientRequestId,
            String requestFingerprint,
            String claimant,
            Duration leaseDuration,
            String principalFingerprint) {
        return new DurableTestExecutionCheckpointRepository.ResumeLeaseCommand(
                clientRequestId, requestFingerprint,
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        checkpoint.scope().tenantId(), checkpoint.scope().environmentId(),
                        checkpoint.runId(), new DurableTestExecutionCheckpointRepository.Fence(
                        checkpoint.lifecycle().ownerId(), checkpoint.lifecycle().leaseEpoch(),
                        checkpoint.lifecycle().revision()), checkpoint.checkpointFingerprint(),
                        claimant, leaseDuration),
                DurableTestRecoveryAuthorization.issue(
                        new ObjectMapper().findAndRegisterModules(),
                        checkpoint.checkpointFingerprint(), principalFingerprint,
                        checkpoint.dependencies().target().fingerprint(),
                        checkpoint.dependencies().plan().planFingerprint(),
                        checkpoint.dependencies().fixture().fingerprint(),
                        ProtocolFingerprint.of(new ObjectMapper().findAndRegisterModules(),
                                checkpoint.dependencies().plan().replayDependencies()),
                        checkpoint.executionServiceState().snapshotFingerprint(),
                        checkpoint.dependencies().identitySnapshot().fingerprint(),
                        checkpoint.dependencies().plan().authorizedPurpose(),
                        checkpoint.dependencies().sideEffectPolicy()));
    }

    private DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand heartbeatCommand(
            DurableTestRecoveryDispatch dispatch,
            String clientRequestId,
            String requestFingerprint,
            Duration leaseDuration) {
        return new DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand(
                clientRequestId, requestFingerprint, dispatch, leaseDuration);
    }

    private DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand terminalCommand(
            DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed,
            String clientRequestId,
            String requestFingerprint,
            DurableTestExecutionCheckpoint.EngineState terminalEngine) {
        return new DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand(
                clientRequestId, requestFingerprint, claimed.dispatch(),
                DurableTestRecoveryTerminalReceipt.ExecutionOutcome.COMPLETED,
                terminalEngine, claimed.checkpoint().fixtureConsumptionState(),
                claimed.checkpoint().executionServiceState(),
                List.of("PRE_CHECKPOINT_TRACE_UNAVAILABLE"));
    }

    private DurableTestExecutionCheckpointRepository.RecoveryStepCommand stepCommand(
            DurableTestExecutionCheckpointRepository.LeaseClaimResult claimed,
            String clientRequestId,
            String requestFingerprint,
            DurableTestExecutionCheckpointRepository.RecoveryStepOutcome outcome,
            DurableTestExecutionCheckpoint.EngineState engineState) {
        return new DurableTestExecutionCheckpointRepository.RecoveryStepCommand(
                clientRequestId, requestFingerprint, claimed.dispatch(), outcome, engineState,
                claimed.checkpoint().fixtureConsumptionState(),
                claimed.checkpoint().executionServiceState(),
                List.of("PRE_CHECKPOINT_TRACE_UNAVAILABLE",
                        "RECOVERY_SIGNAL_PAYLOAD_OMITTED"));
    }

    private DurableTestExecutionCheckpoint.EngineState suspendedEngineState(
            DurableTestExecutionCheckpoint checkpoint) {
        DurableTestExecutionCheckpoint.EngineState current = checkpoint.engineState();
        return new DurableTestExecutionCheckpoint.EngineState(
                "checkpoint-suspended-next", "approval-next", "SUSPEND",
                current.boundarySequence() + 1, current.stateVersion() + 1,
                ProtocolFingerprint.ofText("suspended-engine-state"));
    }

    private DurableTestExecutionCheckpoint.EngineState terminalEngineState(
            DurableTestExecutionCheckpoint checkpoint) {
        DurableTestExecutionCheckpoint.EngineState current = checkpoint.engineState();
        return new DurableTestExecutionCheckpoint.EngineState(
                "checkpoint-terminal", current.nodeId(), "NODE_BOUNDARY",
                current.boundarySequence() + 1, current.stateVersion() + 1,
                ProtocolFingerprint.ofText("terminal-engine-state"));
    }

    private DurableTestExecutionCheckpoint advanceAfterClaim(
            DurableTestExecutionCheckpoint claimed) {
        var lifecycle = claimed.lifecycle();
        var engineState = claimed.engineState();
        DurableTestExecutionCheckpoint advanced = integrity.seal(
                new DurableTestExecutionCheckpoint(
                        claimed.schemaVersion(), claimed.scope(), claimed.runId(),
                        claimed.engineExecutionId(), claimed.dependencies(),
                        claimed.fixtureConsumptionState(), claimed.executionServiceState(),
                        new DurableTestExecutionCheckpoint.EngineState(
                                "checkpoint-after-resume", engineState.nodeId(),
                                engineState.boundaryType(), engineState.boundarySequence() + 1,
                                engineState.stateVersion() + 1,
                                ProtocolFingerprint.ofText("engine-after-resume")),
                        new DurableTestExecutionCheckpoint.Lifecycle(
                                DurableTestExecutionCheckpoint.Status.ACTIVE,
                                lifecycle.ownerId(), lifecycle.leaseEpoch(),
                                lifecycle.revision() + 1, lifecycle.createdAt(),
                                lifecycle.updatedAt().plusSeconds(1),
                                lifecycle.leaseExpiresAt().plusSeconds(1)), ""));
        return repository.advance(advanced,
                new DurableTestExecutionCheckpointRepository.Fence(
                        lifecycle.ownerId(), lifecycle.leaseEpoch(), lifecycle.revision()),
                boundNoop(advanced));
    }

    private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation boundNoop(
            DurableTestExecutionCheckpoint checkpoint) {
        return mutation(checkpoint, ignored -> { });
    }

    private DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope workerScope() {
        return new DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope(
                "tenant-a", "org-a", "project-a", "test");
    }

    private DurableTestExecutionCheckpointRepository.WorkerAcquisitionCommand workerCommand(
            String clientRequestId, String requestFingerprint) {
        return new DurableTestExecutionCheckpointRepository.WorkerAcquisitionCommand(
                clientRequestId, requestFingerprint, workerScope());
    }

    private DurableTestExecutionCheckpointRepository.RecoveryCandidate workerCandidate() {
        return repository.findExpiredRecoveryCandidates(
                        new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                                workerScope(), 1))
                .candidates().getFirst();
    }

    private DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral workerDeferral(
            DurableTestExecutionCheckpointRepository.RecoveryCandidate candidate,
            DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason reason) {
        return new DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral(
                candidate.progress(), candidate.checkpoint().checkpointFingerprint(), reason,
                Duration.ofSeconds(5), Duration.ofMinutes(5));
    }

    private DurableTestExecutionCheckpointRepository.WorkerScanProgress workerProgress(
            DurableTestExecutionCheckpoint checkpoint) {
        return new DurableTestExecutionCheckpointRepository.WorkerScanProgress(
                workerScope(), SHA_A, 0, checkpoint.lifecycle().leaseExpiresAt(),
                checkpoint.lifecycle().updatedAt(), checkpoint.runId());
    }

    private DurableTestExecutionCheckpointRepository.WorkerAcquisitionSelection workerSelection(
            DurableTestExecutionCheckpoint checkpoint, String ownerId) {
        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand resume =
                resumeCommand(checkpoint, "authorization-only", SHA_D, ownerId);
        return new DurableTestExecutionCheckpointRepository.WorkerAcquisitionSelection(
                resume.claim(), resume.authorization());
    }

    private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation(
            DurableTestExecutionCheckpoint checkpoint,
            Consumer<JdbcTemplate> action) {
        return mutation(checkpoint.engineExecutionId(), checkpoint.engineState(), action);
    }

    private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation mutation(
            String engineExecutionId,
            DurableTestExecutionCheckpoint.EngineState engineState,
            Consumer<JdbcTemplate> action) {
        return new DurableTestExecutionCheckpointRepository.BoundEngineStateMutation() {
            @Override
            public String engineExecutionId() {
                return engineExecutionId;
            }

            @Override
            public DurableTestExecutionCheckpoint.EngineState engineState() {
                return engineState;
            }

            @Override
            public void apply(JdbcTemplate jdbc) {
                action.accept(jdbc);
            }
        };
    }

    private DurableTestExecutionCheckpointRepository.RecoverySequenceCommand
    recoverySequenceCommand(
            String clientRequestId,
            String requestFingerprint,
            String runId,
            int signalCount) {
        return new DurableTestExecutionCheckpointRepository.RecoverySequenceCommand(
                clientRequestId, requestFingerprint,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                runId, signalCount);
    }

    private DurableTestExecutionCheckpointRepository.InitialCreationCommand creationCommand(
            String clientRequestId,
            String requestFingerprint,
            String authorizationFingerprint,
            String ownerId,
            String runId,
            String engineExecutionId,
            Duration leaseDuration) {
        return new DurableTestExecutionCheckpointRepository.InitialCreationCommand(
                clientRequestId, requestFingerprint, authorizationFingerprint,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                runId, engineExecutionId, ownerId, leaseDuration);
    }

    private DurableTestExecutionCheckpoint initialCheckpoint(
            DurableTestExecutionCheckpointRepository.InitialCreationReservation reservation) {
        DurableTestExecutionCheckpoint base = checkpoint(0, "checkpoint-base");
        return integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION, reservation.scope(),
                reservation.runId(), reservation.engineExecutionId(), base.dependencies(),
                base.fixtureConsumptionState(), base.executionServiceState(),
                new DurableTestExecutionCheckpoint.EngineState(
                        "checkpoint-initial", "approval", "SUSPEND", 1, 2,
                        ProtocolFingerprint.ofText("initial-engine-closure")),
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.SUSPENDED,
                        reservation.ownerId(), reservation.leaseEpoch(), 0,
                        reservation.createdAt(), reservation.updatedAt(),
                        reservation.leaseExpiresAt()), ""));
    }

    private DurableTestExecutionCheckpoint checkpoint(long revision, String checkpointRef) {
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
                new ObjectMapper().findAndRegisterModules(), unsealedProvider.fingerprintMaterial()));
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
                new DurableTestExecutionCheckpoint.EngineState(
                        checkpointRef, "fetch", "NODE_BOUNDARY", revision + 1,
                        revision, ProtocolFingerprint.ofText("engine-" + checkpointRef)),
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.SUSPENDED, "instance-a", 1,
                        revision, Instant.parse("2026-07-16T08:00:00Z"), now,
                        now.plusSeconds(30)), ""));
    }

    private DurableTestExecutionCheckpoint withLifecycle(
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestExecutionCheckpoint.Status status,
            String ownerId,
            long leaseEpoch,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant leaseExpiresAt) {
        return integrity.seal(new DurableTestExecutionCheckpoint(
                checkpoint.schemaVersion(), checkpoint.scope(), checkpoint.runId(),
                checkpoint.engineExecutionId(), checkpoint.dependencies(),
                checkpoint.fixtureConsumptionState(), checkpoint.executionServiceState(),
                checkpoint.engineState(), new DurableTestExecutionCheckpoint.Lifecycle(
                status, ownerId, leaseEpoch, revision, createdAt, updatedAt, leaseExpiresAt), ""));
    }

    private DurableTestExecutionCheckpoint identifiedCheckpoint(
            DurableTestExecutionCheckpoint checkpoint,
            String runId,
            String engineExecutionId,
            String organizationId,
            String projectId,
            Instant leaseExpiresAt) {
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
        return integrity.seal(new DurableTestExecutionCheckpoint(
                checkpoint.schemaVersion(), new DurableTestExecutionCheckpoint.Scope(
                checkpoint.scope().tenantId(), organizationId, projectId,
                checkpoint.scope().environmentId(), checkpoint.scope().actorId()),
                runId, engineExecutionId, checkpoint.dependencies(),
                checkpoint.fixtureConsumptionState(), checkpoint.executionServiceState(),
                checkpoint.engineState(), new DurableTestExecutionCheckpoint.Lifecycle(
                lifecycle.status(), lifecycle.ownerId(), lifecycle.leaseEpoch(),
                lifecycle.revision(), lifecycle.createdAt(), lifecycle.updatedAt(),
                leaseExpiresAt), ""));
    }

    private DurableTestExecutionCheckpoint expiredCheckpoint() {
        return withLifecycle(checkpoint(0, "checkpoint-0"),
                DurableTestExecutionCheckpoint.Status.SUSPENDED,
                "instance-a", 1, 0,
                Instant.parse("2000-01-01T00:00:00Z"),
                Instant.parse("2000-01-01T00:00:01Z"),
                Instant.parse("2000-01-01T00:00:02Z"));
    }
}
