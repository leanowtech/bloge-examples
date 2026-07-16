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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private DurableTestExecutionCheckpoint expiredCheckpoint() {
        return withLifecycle(checkpoint(0, "checkpoint-0"),
                DurableTestExecutionCheckpoint.Status.SUSPENDED,
                "instance-a", 1, 0,
                Instant.parse("2000-01-01T00:00:00Z"),
                Instant.parse("2000-01-01T00:00:01Z"),
                Instant.parse("2000-01-01T00:00:02Z"));
    }
}
