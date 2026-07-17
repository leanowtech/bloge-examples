package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseDurableWorkerQuarantineControlPlaneTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;
    private DatabaseDurableTestExecutionCheckpointRepository repository;
    private DatabaseDurableWorkerQuarantineControlPlane controlPlane;
    private DurableTestExecutionCheckpointIntegrity integrity;
    private WorkerQuarantineClaimTokenProtector tokenProtector;
    private WorkerQuarantineRequestKeyProtector requestKeyProtector;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:worker-quarantine-control-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        integrity = new DurableTestExecutionCheckpointIntegrity(objectMapper);
        repository = new DatabaseDurableTestExecutionCheckpointRepository(
                database.jdbc(), database.transactionManager(), objectMapper, integrity);
        repository.init();
        tokenProtector = tokenProtector("key-v1", "key-v1", 1);
        requestKeyProtector = requestKeyProtector(
                "request-key-v1", Map.of("request-key-v1", keyBytes(11)));
        controlPlane = new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                tokenProtector, requestKeyProtector);
        controlPlane.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void listsOnlyIntegrityVerifiedQuarantinesInsideTheExactWorkerScope() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();

        List<DatabaseDurableWorkerQuarantineControlPlane.QuarantineRecord> records =
                controlPlane.quarantines(workerScope(), true, 10);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.runId()).isEqualTo(checkpoint.runId());
            assertThat(record.checkpointFingerprint())
                    .isEqualTo(checkpoint.checkpointFingerprint());
            assertThat(record.reason()).isEqualTo(
                    DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                            .AUTHORIZATION_DENIED);
            assertThat(record.state()).isEqualTo(
                    DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.AVAILABLE);
            assertThat(record.claimOwner()).isEmpty();
            assertThat(record.version()).isZero();
        });
        assertThat(controlPlane.quarantines(new DurableTestExecutionCheckpointRepository
                .WorkerAcquisitionScope("tenant-other", "org-a", "project-a", "test"),
                false, 10)).isEmpty();
    }

    @Test
    void claimsOnceAndExactlyReplaysTheServerFenceWithoutAnotherAuditMutation() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        AtomicInteger auditMutations = new AtomicInteger();

        var claimed = controlPlane.claim(
                workerScope(), key, "operator-a", "claim-1", Duration.ofMinutes(2),
                ignored -> jdbc -> auditMutations.incrementAndGet());
        var replayed = controlPlane.claim(
                workerScope(), key, "operator-a", "claim-1", Duration.ofMinutes(2),
                ignored -> jdbc -> auditMutations.incrementAndGet());

        assertThat(claimed.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.CLAIMED);
        assertThat(replayed.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.IDEMPOTENT_REPLAY);
        assertThat(replayed.claim()).isEqualTo(claimed.claim());
        assertThat(claimed.claim().ownerId()).isEqualTo("operator-a");
        assertThat(claimed.claim().claimToken()).isNotBlank();
        assertThat(claimed.claim().version()).isOne();
        assertThat(auditMutations).hasValue(1);
        Map<String, Object> storedCommand = database.jdbc().queryForMap("""
                SELECT result_claim_token, result_claim_token_envelope
                FROM rg_test_durable_worker_quarantine_claim_commands
                WHERE client_request_id = ?
                """, "claim-1");
        assertThat(storedCommand.get("RESULT_CLAIM_TOKEN")).isEqualTo("");
        assertThat(storedCommand.get("RESULT_CLAIM_TOKEN_ENVELOPE").toString())
                .startsWith("v1.key-v1.")
                .doesNotContain(claimed.claim().claimToken());
        Map<String, Object> storedControl = database.jdbc().queryForMap("""
                SELECT claim_token, claim_token_key_id, claim_token_mac, record_version
                FROM rg_test_durable_worker_quarantine_controls
                WHERE run_id = ?
                """, checkpoint.runId());
        assertThat(storedControl.get("CLAIM_TOKEN")).isEqualTo("");
        assertThat(storedControl.get("CLAIM_TOKEN_KEY_ID")).isEqualTo("key-v1");
        assertThat(storedControl.get("CLAIM_TOKEN_MAC").toString())
                .startsWith("v1.")
                .doesNotContain(claimed.claim().claimToken());
        assertThat(storedControl.get("RECORD_VERSION")).isEqualTo(2);
        assertThat(controlPlane.quarantines(workerScope(), true, 10)).isEmpty();
        assertThat(controlPlane.quarantines(workerScope(), false, 10))
                .singleElement().satisfies(record -> {
                    assertThat(record.state()).isEqualTo(
                            DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.CLAIMED);
                    assertThat(record.claimOwner()).isEqualTo("operator-a");
                    assertThat(record.version()).isOne();
                });
    }

    @Test
    void exactReplayRewrapsAnOldKeyEnvelopeWithoutChangingTheServerFence() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-rotation",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop());
        WorkerQuarantineClaimTokenProtector rotatedProtector =
                new WorkerQuarantineClaimTokenProtector("key-v2",
                        Map.of("key-v1", keyBytes(1), "key-v2", keyBytes(2)),
                        new SecureRandom());
        DatabaseDurableWorkerQuarantineControlPlane rotated =
                new DatabaseDurableWorkerQuarantineControlPlane(
                        database.jdbc(), database.transactionManager(), objectMapper,
                        rotatedProtector, requestKeyProtector);
        rotated.init();
        assertThat(database.jdbc().queryForObject("""
                SELECT result_claim_token_envelope
                FROM rg_test_durable_worker_quarantine_claim_commands
                WHERE client_request_id = ?
                """, String.class, "claim-rotation"))
                .startsWith("v1.key-v2.");

        var replayed = rotated.claim(workerScope(), key, "operator-a", "claim-rotation",
                Duration.ofMinutes(2), ignored -> {
                    throw new AssertionError("key rotation replay must not write another audit");
                });

        assertThat(replayed.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.IDEMPOTENT_REPLAY);
        assertThat(replayed.claim()).isEqualTo(claimed.claim());
        assertThat(database.jdbc().queryForObject("""
                SELECT result_claim_token_envelope
                FROM rg_test_durable_worker_quarantine_claim_commands
                WHERE client_request_id = ?
                """, String.class, "claim-rotation"))
                .startsWith("v1.key-v2.")
                .doesNotContain(claimed.claim().claimToken());
        Map<String, Object> rotatedControl = database.jdbc().queryForMap("""
                SELECT claim_token, claim_token_key_id, claim_token_mac, record_version
                FROM rg_test_durable_worker_quarantine_controls
                WHERE run_id = ?
                """, checkpoint.runId());
        assertThat(rotatedControl.get("CLAIM_TOKEN")).isEqualTo("");
        assertThat(rotatedControl.get("CLAIM_TOKEN_KEY_ID")).isEqualTo("key-v2");
        assertThat(rotatedControl.get("CLAIM_TOKEN_MAC").toString())
                .startsWith("v1.")
                .doesNotContain(claimed.claim().claimToken());
        assertThat(rotated.resolve(workerScope(), claimed.claim(), "release-after-rotation",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "ROTATED_KEY", ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .ResolutionDisposition.RESOLVED);
    }

    @Test
    void startupRefusesAPlaintextCommandWhoseLegacyFingerprintIsInvalid() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-corrupt-legacy",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_claim_commands
                SET result_claim_token = ?, result_claim_token_envelope = '',
                    record_fingerprint = ?
                WHERE client_request_id = ?
                """, claimed.claim().claimToken(), SHA_A, "claim-corrupt-legacy");
        DatabaseDurableWorkerQuarantineControlPlane upgraded =
                new DatabaseDurableWorkerQuarantineControlPlane(
                        database.jdbc(), database.transactionManager(), objectMapper,
                        tokenProtector, requestKeyProtector);

        assertThatThrownBy(upgraded::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim command is corrupt")
                .hasMessageNotContaining(claimed.claim().claimToken());
        assertThat(database.jdbc().queryForObject("""
                SELECT result_claim_token
                FROM rg_test_durable_worker_quarantine_claim_commands
                WHERE client_request_id = ?
                """, String.class, "claim-corrupt-legacy"))
                .isEqualTo(claimed.claim().claimToken());
    }

    @Test
    void tamperedClaimTokenEnvelopeFailsClosedWithoutReturningTheToken() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-tampered",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_claim_commands
                SET result_claim_token_envelope = result_claim_token_envelope || 'A'
                WHERE client_request_id = ?
                """, "claim-tampered");

        assertThatThrownBy(() -> controlPlane.claim(workerScope(), key, "operator-a",
                "claim-tampered", Duration.ofMinutes(2),
                ignored -> TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim command is corrupt")
                .hasMessageNotContaining(claimed.claim().claimToken());
    }

    @Test
    void startupMigratesAValidLegacyPlaintextCommandAndPreservesExactReplay() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-legacy",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop());
        Map<String, Object> stored = database.jdbc().queryForMap("""
                SELECT request_fingerprint, result_version, result_claim_until, created_at
                FROM rg_test_durable_worker_quarantine_claim_commands
                WHERE client_request_id = ?
                """, "claim-legacy");
        long version = ((Number) stored.get("RESULT_VERSION")).longValue();
        Instant claimUntil = ((OffsetDateTime) stored.get("RESULT_CLAIM_UNTIL")).toInstant();
        Instant createdAt = ((OffsetDateTime) stored.get("CREATED_AT")).toInstant();
        String requestFingerprint = stored.get("REQUEST_FINGERPRINT").toString();
        String legacyFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineClaimCommand.v1"),
                Map.entry("scope", workerScope()),
                Map.entry("clientRequestId", "claim-legacy"),
                Map.entry("key", key), Map.entry("ownerId", "operator-a"),
                Map.entry("claimDurationSeconds", 120L),
                Map.entry("requestFingerprint", requestFingerprint),
                Map.entry("resultClaimToken", claimed.claim().claimToken()),
                Map.entry("resultVersion", version),
                Map.entry("resultClaimUntil", claimUntil),
                Map.entry("createdAt", createdAt)));
        String scopeKey = database.jdbc().queryForObject("""
                SELECT scope_key
                FROM rg_test_durable_worker_quarantine_controls
                WHERE run_id = ?
                """, String.class, checkpoint.runId());
        String legacyControlFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineControl.v1"),
                Map.entry("scopeKey", scopeKey),
                Map.entry("runId", checkpoint.runId()),
                Map.entry("checkpointFingerprint", checkpoint.checkpointFingerprint()),
                Map.entry("state", "CLAIMED"), Map.entry("claimOwner", "operator-a"),
                Map.entry("claimToken", claimed.claim().claimToken()),
                Map.entry("claimUntil", claimUntil), Map.entry("version", version)));
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_claim_commands
                SET result_claim_token = ?, result_claim_token_envelope = '',
                    record_fingerprint = ?
                WHERE client_request_id = ?
                """, claimed.claim().claimToken(), legacyFingerprint, "claim-legacy");
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_controls
                SET claim_token = ?, claim_token_key_id = '', claim_token_mac = '',
                    record_version = 1, record_fingerprint = ?
                WHERE run_id = ?
                """, claimed.claim().claimToken(), legacyControlFingerprint,
                checkpoint.runId());

        DatabaseDurableWorkerQuarantineControlPlane upgraded =
                new DatabaseDurableWorkerQuarantineControlPlane(
                        database.jdbc(), database.transactionManager(), objectMapper,
                        tokenProtector, requestKeyProtector);
        upgraded.init();
        var replayed = upgraded.claim(workerScope(), key, "operator-a", "claim-legacy",
                Duration.ofMinutes(2), ignored -> {
                    throw new AssertionError("legacy migration must preserve the command receipt");
                });

        assertThat(replayed.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.IDEMPOTENT_REPLAY);
        assertThat(replayed.claim()).isEqualTo(claimed.claim());
        Map<String, Object> migrated = database.jdbc().queryForMap("""
                SELECT result_claim_token, result_claim_token_envelope
                FROM rg_test_durable_worker_quarantine_claim_commands
                WHERE client_request_id = ?
                """, "claim-legacy");
        assertThat(migrated.get("RESULT_CLAIM_TOKEN")).isEqualTo("");
        assertThat(migrated.get("RESULT_CLAIM_TOKEN_ENVELOPE").toString())
                .startsWith("v1.key-v1.")
                .doesNotContain(claimed.claim().claimToken());
        Map<String, Object> migratedControl = database.jdbc().queryForMap("""
                SELECT claim_token, claim_token_key_id, claim_token_mac, record_version
                FROM rg_test_durable_worker_quarantine_controls
                WHERE run_id = ?
                """, checkpoint.runId());
        assertThat(migratedControl.get("CLAIM_TOKEN")).isEqualTo("");
        assertThat(migratedControl.get("CLAIM_TOKEN_KEY_ID")).isEqualTo("key-v1");
        assertThat(migratedControl.get("CLAIM_TOKEN_MAC").toString())
                .startsWith("v1.")
                .doesNotContain(claimed.claim().claimToken());
        assertThat(migratedControl.get("RECORD_VERSION")).isEqualTo(2);
        assertThat(upgraded.resolve(workerScope(), claimed.claim(), "release-migrated-control",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "MIGRATED_CONTROL", ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .ResolutionDisposition.RESOLVED);
    }

    @Test
    void startupUpgradesALegacyAvailableControlWithoutRequiringAClaimToken() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-available-v1",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        controlPlane.resolve(workerScope(), claim, "release-available-v1",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "AVAILABLE_V1", ignored -> TestRuntimeTransactionMutation.noop());
        Map<String, Object> control = database.jdbc().queryForMap("""
                SELECT scope_key, control_version
                FROM rg_test_durable_worker_quarantine_controls
                WHERE run_id = ?
                """, checkpoint.runId());
        long version = ((Number) control.get("CONTROL_VERSION")).longValue();
        String legacyFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineControl.v1"),
                Map.entry("scopeKey", control.get("SCOPE_KEY").toString()),
                Map.entry("runId", checkpoint.runId()),
                Map.entry("checkpointFingerprint", checkpoint.checkpointFingerprint()),
                Map.entry("state", "AVAILABLE"), Map.entry("claimOwner", ""),
                Map.entry("claimToken", ""), Map.entry("claimUntil", Instant.EPOCH),
                Map.entry("version", version)));
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_controls
                SET record_version = 1, record_fingerprint = ?
                WHERE run_id = ?
                """, legacyFingerprint, checkpoint.runId());
        DatabaseDurableWorkerQuarantineControlPlane upgraded =
                new DatabaseDurableWorkerQuarantineControlPlane(
                        database.jdbc(), database.transactionManager(), objectMapper,
                        tokenProtector, requestKeyProtector);

        upgraded.init();

        Map<String, Object> migrated = database.jdbc().queryForMap("""
                SELECT claim_token, claim_token_key_id, claim_token_mac, record_version
                FROM rg_test_durable_worker_quarantine_controls
                WHERE run_id = ?
                """, checkpoint.runId());
        assertThat(migrated.get("CLAIM_TOKEN")).isEqualTo("");
        assertThat(migrated.get("CLAIM_TOKEN_KEY_ID")).isEqualTo("");
        assertThat(migrated.get("CLAIM_TOKEN_MAC")).isEqualTo("");
        assertThat(migrated.get("RECORD_VERSION")).isEqualTo(2);
        assertThat(upgraded.quarantines(workerScope(), true, 10)).singleElement()
                .extracting(DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineRecord::state)
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineState.AVAILABLE);
    }

    @Test
    void activeFenceRotationFailsAtomicallyWhenItsRecoverableClaimCommandIsMissing() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-no-command",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                DELETE FROM rg_test_durable_worker_quarantine_claim_commands
                WHERE client_request_id = ?
                """, "claim-no-command");
        WorkerQuarantineClaimTokenProtector rotatedProtector =
                new WorkerQuarantineClaimTokenProtector("key-v2",
                        Map.of("key-v1", keyBytes(1), "key-v2", keyBytes(2)),
                        new SecureRandom());
        DatabaseDurableWorkerQuarantineControlPlane rotated =
                new DatabaseDurableWorkerQuarantineControlPlane(
                        database.jdbc(), database.transactionManager(), objectMapper,
                        rotatedProtector, requestKeyProtector);

        assertThatThrownBy(rotated::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no unique claim command")
                .hasMessageNotContaining(claimed.claim().claimToken());
        Map<String, Object> unchanged = database.jdbc().queryForMap("""
                SELECT claim_token, claim_token_key_id, record_version
                FROM rg_test_durable_worker_quarantine_controls
                WHERE run_id = ?
                """, checkpoint.runId());
        assertThat(unchanged.get("CLAIM_TOKEN")).isEqualTo("");
        assertThat(unchanged.get("CLAIM_TOKEN_KEY_ID")).isEqualTo("key-v1");
        assertThat(unchanged.get("RECORD_VERSION")).isEqualTo(2);
    }

    @Test
    void rotationCanonicalizesAnExpiredClaimWhoseReplayCommandWasAlreadyRetainedAway()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        controlPlane.claim(workerScope(), key, "operator-a", "claim-expired-no-command",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());
        Thread.sleep(1_100);
        database.jdbc().update("""
                DELETE FROM rg_test_durable_worker_quarantine_claim_commands
                WHERE client_request_id = ?
                """, "claim-expired-no-command");
        WorkerQuarantineClaimTokenProtector rotatedProtector =
                new WorkerQuarantineClaimTokenProtector("key-v2",
                        Map.of("key-v1", keyBytes(1), "key-v2", keyBytes(2)),
                        new SecureRandom());
        DatabaseDurableWorkerQuarantineControlPlane rotated =
                new DatabaseDurableWorkerQuarantineControlPlane(
                        database.jdbc(), database.transactionManager(), objectMapper,
                        rotatedProtector, requestKeyProtector);

        rotated.init();

        Map<String, Object> normalized = database.jdbc().queryForMap("""
                SELECT control_state, claim_owner, claim_token, claim_token_key_id,
                       claim_token_mac, claim_until, control_version, record_version
                FROM rg_test_durable_worker_quarantine_controls
                WHERE run_id = ?
                """, checkpoint.runId());
        assertThat(normalized.get("CONTROL_STATE")).isEqualTo("AVAILABLE");
        assertThat(normalized.get("CLAIM_OWNER")).isEqualTo("");
        assertThat(normalized.get("CLAIM_TOKEN")).isEqualTo("");
        assertThat(normalized.get("CLAIM_TOKEN_KEY_ID")).isEqualTo("");
        assertThat(normalized.get("CLAIM_TOKEN_MAC")).isEqualTo("");
        assertThat(((OffsetDateTime) normalized.get("CLAIM_UNTIL")).toInstant())
                .isEqualTo(Instant.EPOCH);
        assertThat(normalized.get("CONTROL_VERSION")).isEqualTo(1L);
        assertThat(normalized.get("RECORD_VERSION")).isEqualTo(2);
        assertThat(rotated.quarantines(workerScope(), true, 10)).singleElement()
                .extracting(DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineRecord::state)
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineState.AVAILABLE);
    }

    @Test
    void tamperedActiveFenceMacFailsClosedWithoutDisclosingTheBearerToken() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-mac-tamper",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_controls
                SET claim_token_mac = claim_token_mac || 'A'
                WHERE run_id = ?
                """, checkpoint.runId());

        assertThatThrownBy(() -> controlPlane.quarantines(workerScope(), false, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control is corrupt")
                .hasMessageNotContaining(claimed.claim().claimToken());
        assertThatThrownBy(() -> controlPlane.resolve(workerScope(), claimed.claim(),
                "resolve-mac-tamper",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "MAC_TAMPER", ignored -> TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control is corrupt")
                .hasMessageNotContaining(claimed.claim().claimToken());
    }

    @Test
    void expiredClaimCanBeTakenOverAndThePreviousFenceCannotResolve() throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var expired = controlPlane.claim(
                workerScope(), key, "operator-a", "claim-expiring", Duration.ofSeconds(1),
                ignored -> TestRuntimeTransactionMutation.noop()).claim();

        Thread.sleep(1_200);

        var successor = controlPlane.claim(
                workerScope(), key, "operator-b", "claim-takeover", Duration.ofMinutes(2),
                ignored -> TestRuntimeTransactionMutation.noop());
        assertThat(successor.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.CLAIMED);
        assertThat(successor.claim().ownerId()).isEqualTo("operator-b");
        assertThat(successor.claim().version()).isEqualTo(2);
        assertThat(successor.claim().claimToken()).isNotEqualTo(expired.claimToken());
        assertThat(controlPlane.resolve(workerScope(), expired, "resolve-expired",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "EXPIRED_OWNER", ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                .ResolutionDisposition.FENCE_REJECTED);
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).singleElement()
                .satisfies(record -> {
                    assertThat(record.claimOwner()).isEqualTo("operator-b");
                    assertThat(record.version()).isEqualTo(2);
                });
    }

    @Test
    void concurrentExactCommandRetriesReplayAfterWaitingForTheCheckpointLock()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        AtomicInteger claimAudits = new AtomicInteger();
        DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaimResult firstClaim;
        DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaimResult secondClaim;

        try (var executor = Executors.newFixedThreadPool(3)) {
            CountDownLatch checkpointLocked = new CountDownLatch(1);
            CountDownLatch releaseCheckpoint = new CountDownLatch(1);
            var blocker = holdCheckpointLock(
                    executor, checkpointLocked, releaseCheckpoint);
            assertThat(checkpointLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch callersReady = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                callersReady.countDown();
                start.await(5, TimeUnit.SECONDS);
                return controlPlane.claim(workerScope(), key, "operator-a",
                        "claim-concurrent-retry", Duration.ofMinutes(2),
                        ignored -> jdbc -> claimAudits.incrementAndGet());
            });
            var second = executor.submit(() -> {
                callersReady.countDown();
                start.await(5, TimeUnit.SECONDS);
                return controlPlane.claim(workerScope(), key, "operator-a",
                        "claim-concurrent-retry", Duration.ofMinutes(2),
                        ignored -> jdbc -> claimAudits.incrementAndGet());
            });
            assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Thread.sleep(100);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();

            releaseCheckpoint.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            firstClaim = first.get(5, TimeUnit.SECONDS);
            secondClaim = second.get(5, TimeUnit.SECONDS);
        }

        assertThat(List.of(firstClaim.disposition(), secondClaim.disposition()))
                .containsExactlyInAnyOrder(
                        DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.CLAIMED,
                        DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition
                                .IDEMPOTENT_REPLAY);
        assertThat(firstClaim.claim()).isEqualTo(secondClaim.claim());
        assertThat(claimAudits).hasValue(1);

        var approval = approve(firstClaim.claim(), "checker-a", "approve-concurrent");

        AtomicInteger resolutionAudits = new AtomicInteger();
        DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardResult firstResolution;
        DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardResult secondResolution;
        try (var executor = Executors.newFixedThreadPool(3)) {
            CountDownLatch checkpointLocked = new CountDownLatch(1);
            CountDownLatch releaseCheckpoint = new CountDownLatch(1);
            var blocker = holdCheckpointLock(
                    executor, checkpointLocked, releaseCheckpoint);
            assertThat(checkpointLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch callersReady = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                callersReady.countDown();
                start.await(5, TimeUnit.SECONDS);
                return controlPlane.discard(workerScope(), firstClaim.claim(),
                        approval.approvalId(), "discard-concurrent-retry", "AUTHORIZED_RETRY",
                        ignored -> jdbc -> resolutionAudits.incrementAndGet());
            });
            var second = executor.submit(() -> {
                callersReady.countDown();
                start.await(5, TimeUnit.SECONDS);
                return controlPlane.discard(workerScope(), firstClaim.claim(),
                        approval.approvalId(), "discard-concurrent-retry", "AUTHORIZED_RETRY",
                        ignored -> jdbc -> resolutionAudits.incrementAndGet());
            });
            assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Thread.sleep(100);
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();

            releaseCheckpoint.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            firstResolution = first.get(5, TimeUnit.SECONDS);
            secondResolution = second.get(5, TimeUnit.SECONDS);
        }

        assertThat(List.of(firstResolution.disposition(), secondResolution.disposition()))
                .containsExactlyInAnyOrder(
                        DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardDisposition
                                .DISCARDED,
                        DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardDisposition
                                .IDEMPOTENT_REPLAY);
        assertThat(firstResolution.receipt()).isEqualTo(secondResolution.receipt());
        assertThat(resolutionAudits).hasValue(1);
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).isEmpty();
        assertThat(controlPlane.discardHistory(workerScope(), 10)).hasSize(1);
    }

    @Test
    void oneApprovalCanAuthorizeOnlyOneOfTwoDifferentConcurrentDiscardCommands()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-racing-discards",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        var approval = approve(claim, "checker-a", "approve-racing-discards");
        AtomicInteger audits = new AtomicInteger();
        DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardResult firstResult;
        DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardResult secondResult;

        try (var executor = Executors.newFixedThreadPool(3)) {
            CountDownLatch checkpointLocked = new CountDownLatch(1);
            CountDownLatch releaseCheckpoint = new CountDownLatch(1);
            var blocker = holdCheckpointLock(executor, checkpointLocked, releaseCheckpoint);
            assertThat(checkpointLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch callersReady = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                callersReady.countDown();
                start.await(5, TimeUnit.SECONDS);
                return controlPlane.discard(workerScope(), claim, approval.approvalId(),
                        "discard-race-a", "AUTHORIZED_RETRY",
                        ignored -> jdbc -> audits.incrementAndGet());
            });
            var second = executor.submit(() -> {
                callersReady.countDown();
                start.await(5, TimeUnit.SECONDS);
                return controlPlane.discard(workerScope(), claim, approval.approvalId(),
                        "discard-race-b", "AUTHORIZED_RETRY",
                        ignored -> jdbc -> audits.incrementAndGet());
            });
            assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            releaseCheckpoint.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            firstResult = first.get(5, TimeUnit.SECONDS);
            secondResult = second.get(5, TimeUnit.SECONDS);
        }

        assertThat(List.of(firstResult.disposition(), secondResult.disposition()))
                .containsExactlyInAnyOrder(
                        DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardDisposition
                                .DISCARDED,
                        DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardDisposition
                                .FENCE_REJECTED);
        assertThat(audits).hasValue(1);
        assertThat(controlPlane.discardHistory(workerScope(), 10)).hasSize(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_durable_worker_quarantine_discards
                """, Integer.class)).isOne();
    }

    @Test
    void releasesAnExactLiveClaimAndReplaysATokenFreeImmutableReceipt() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(
                workerScope(), key, "operator-a", "claim-release", Duration.ofMinutes(2),
                ignored -> TestRuntimeTransactionMutation.noop()).claim();
        AtomicInteger auditMutations = new AtomicInteger();

        var released = controlPlane.resolve(workerScope(), claim, "release-1",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "DEPENDENCY_POLICY_FIXED",
                ignored -> jdbc -> auditMutations.incrementAndGet());
        var replayed = controlPlane.resolve(workerScope(), claim, "release-1",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "DEPENDENCY_POLICY_FIXED",
                ignored -> jdbc -> auditMutations.incrementAndGet());

        assertThat(released.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionDisposition.RESOLVED);
        assertThat(replayed.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionDisposition
                        .IDEMPOTENT_REPLAY);
        assertThat(replayed.receipt()).isEqualTo(released.receipt());
        assertThat(released.receipt().version()).isEqualTo(2);
        assertThat(released.receipt().receiptFingerprint()).startsWith("sha256:");
        assertThat(released.receipt().toString()).doesNotContain(claim.claimToken());
        assertThat(auditMutations).hasValue(1);
        assertThat(controlPlane.quarantines(workerScope(), true, 10))
                .singleElement().satisfies(record -> {
                    assertThat(record.state()).isEqualTo(
                            DatabaseDurableWorkerQuarantineControlPlane.QuarantineState.AVAILABLE);
                    assertThat(record.version()).isEqualTo(2);
                });
        assertThat(controlPlane.history(workerScope(), 10)).singleElement().satisfies(record -> {
            assertThat(record.action()).isEqualTo(
                    DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE);
            assertThat(record.reasonCode()).isEqualTo("DEPENDENCY_POLICY_FIXED");
            assertThat(record.receiptFingerprint())
                    .isEqualTo(released.receipt().receiptFingerprint());
            assertThat(record.toString()).doesNotContain(claim.claimToken());
        });
    }

    @Test
    void discardsOnlyTheExactQuarantineAndRetainsReplayableHistoricalEvidence() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-discard",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();

        var approval = approve(claim, "checker-a", "approve-discard");
        var discarded = controlPlane.discard(workerScope(), claim, approval.approvalId(),
                "discard-1", "AUTHORIZED_RETRY",
                ignored -> TestRuntimeTransactionMutation.noop());
        var replayed = controlPlane.discard(workerScope(), claim, approval.approvalId(),
                "discard-1", "AUTHORIZED_RETRY", ignored -> {
                    throw new AssertionError("idempotent replay must not request another audit");
                });

        assertThat(discarded.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardDisposition.DISCARDED);
        assertThat(replayed.receipt()).isEqualTo(discarded.receipt());
        assertThat(discarded.receipt().ownerId()).isEqualTo("operator-a");
        assertThat(discarded.receipt().approverId()).isEqualTo("checker-a");
        assertThat(discarded.receipt().approvalFingerprint())
                .isEqualTo(approval.approvalFingerprint());
        assertThat(discarded.receipt().toString()).doesNotContain(claim.claimToken());
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).isEmpty();
        assertThat(controlPlane.discardHistory(workerScope(), 10)).singleElement()
                .satisfies(record -> {
                    assertThat(record.ownerId()).isEqualTo("operator-a");
                    assertThat(record.approverId()).isEqualTo("checker-a");
                    assertThat(record.approvalId()).isEqualTo(approval.approvalId());
                    assertThat(record.toString()).doesNotContain(claim.claimToken());
                });
        assertThat(repository.findExpiredRecoveryCandidates(
                new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                        workerScope(), 1)).candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.activeQuarantine()).isEmpty());
    }

    @Test
    void directDiscardAndSelfApprovalAreRejectedWithoutMutatingTheQuarantine() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-approval-required",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();

        assertThat(controlPlane.resolve(workerScope(), claim, "legacy-direct-discard",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.DISCARD,
                "AUTHORIZED_RETRY", ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                .ResolutionDisposition.APPROVAL_REQUIRED);
        assertThat(controlPlane.approveDiscard(workerScope(), key, claim.ownerId(),
                claim.version(), claim.claimUntil(), claim.ownerId(), "self-approval",
                "AUTHORIZED_RETRY", Duration.ofMinutes(1),
                ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .DiscardApprovalDisposition.SELF_APPROVAL);

        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_durable_worker_quarantine_discard_approvals
                """, Integer.class)).isZero();
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).singleElement()
                .extracting(DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineRecord::claimOwner)
                .isEqualTo("operator-a");
    }

    @Test
    void approvalExactlyReplaysRejectsIntentDriftAndMustMatchDiscardReason() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-approval-replay",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        AtomicInteger approvalAudits = new AtomicInteger();

        var approved = controlPlane.approveDiscard(workerScope(), key, claim.ownerId(),
                claim.version(), claim.claimUntil(), "checker-a", "approval-replay",
                "AUTHORIZED_RETRY", Duration.ofMinutes(1),
                ignored -> jdbc -> approvalAudits.incrementAndGet());
        var replayed = controlPlane.approveDiscard(workerScope(), key, claim.ownerId(),
                claim.version(), claim.claimUntil(), "checker-a", "approval-replay",
                "AUTHORIZED_RETRY", Duration.ofMinutes(1),
                ignored -> jdbc -> approvalAudits.incrementAndGet());
        var drifted = controlPlane.approveDiscard(workerScope(), key, claim.ownerId(),
                claim.version(), claim.claimUntil(), "checker-a", "approval-replay",
                "AUTHORIZED_RETRY", Duration.ofSeconds(30),
                ignored -> TestRuntimeTransactionMutation.noop());

        assertThat(approved.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.DiscardApprovalDisposition.APPROVED);
        assertThat(replayed.disposition()).isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                .DiscardApprovalDisposition.IDEMPOTENT_REPLAY);
        assertThat(replayed.approval()).isEqualTo(approved.approval());
        assertThat(drifted.disposition()).isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                .DiscardApprovalDisposition.IDEMPOTENCY_CONFLICT);
        assertThat(approvalAudits).hasValue(1);

        assertThat(controlPlane.discard(workerScope(), claim, approved.approval().approvalId(),
                "discard-wrong-reason", "DIFFERENT_REASON",
                ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .ApprovedDiscardDisposition.APPROVAL_REJECTED);
        assertThat(controlPlane.discard(workerScope(), claim, approved.approval().approvalId(),
                "discard-correct-reason", "AUTHORIZED_RETRY",
                ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .ApprovedDiscardDisposition.DISCARDED);
        assertThat(database.jdbc().queryForObject("""
                SELECT approval_state
                FROM rg_test_durable_worker_quarantine_discard_approvals
                WHERE approval_id = ?
                """, String.class, approved.approval().approvalId())).isEqualTo("CONSUMED");
    }

    @Test
    void expiredApprovalCannotAuthorizeDiscardEvenWhileTheMakerClaimRemainsLive()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-expiring-approval",
                Duration.ofSeconds(5), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        var approval = controlPlane.approveDiscard(workerScope(), key, claim.ownerId(),
                claim.version(), claim.claimUntil(), "checker-a", "approval-expiring",
                "AUTHORIZED_RETRY", Duration.ofSeconds(1),
                ignored -> TestRuntimeTransactionMutation.noop()).approval();

        Thread.sleep(1_200);

        assertThat(controlPlane.discard(workerScope(), claim, approval.approvalId(),
                "discard-expired-approval", "AUTHORIZED_RETRY",
                ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .ApprovedDiscardDisposition.APPROVAL_REJECTED);
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).singleElement()
                .satisfies(record -> assertThat(record.claimOwner()).isEqualTo("operator-a"));
        assertThat(controlPlane.discardHistory(workerScope(), 10)).isEmpty();
    }

    @Test
    void approvalAndDiscardAuditFailuresRollbackEveryAuthorityAndReceiptMutation() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-two-person-rollback",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();

        assertThatThrownBy(() -> controlPlane.approveDiscard(workerScope(), key,
                claim.ownerId(), claim.version(), claim.claimUntil(), "checker-a",
                "approval-rollback", "AUTHORIZED_RETRY", Duration.ofMinutes(1),
                ignored -> jdbc -> {
                    throw new IllegalStateException("approval audit unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approval audit unavailable");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_durable_worker_quarantine_discard_approvals
                """, Integer.class)).isZero();

        var approval = approve(claim, "checker-a", "approval-rollback");
        assertThatThrownBy(() -> controlPlane.discard(workerScope(), claim,
                approval.approvalId(), "discard-rollback", "AUTHORIZED_RETRY",
                ignored -> jdbc -> {
                    throw new IllegalStateException("discard audit unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discard audit unavailable");
        assertThat(database.jdbc().queryForObject("""
                SELECT approval_state
                FROM rg_test_durable_worker_quarantine_discard_approvals
                WHERE approval_id = ?
                """, String.class, approval.approvalId())).isEqualTo("APPROVED");
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).hasSize(1);
        assertThat(controlPlane.discardHistory(workerScope(), 10)).isEmpty();

        assertThat(controlPlane.discard(workerScope(), claim, approval.approvalId(),
                "discard-rollback", "AUTHORIZED_RETRY",
                ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .ApprovedDiscardDisposition.DISCARDED);
    }

    @Test
    void auditFailureRollsBackClaimsAndResolutionsIncludingTheirIdempotencyReceipts() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());

        assertThatThrownBy(() -> controlPlane.claim(workerScope(), key, "operator-a",
                "claim-rollback", Duration.ofMinutes(2), ignored -> jdbc -> {
                    throw new IllegalStateException("claim audit unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim audit unavailable");
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-rollback",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop());
        assertThat(claimed.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.CLAIMED);

        assertThatThrownBy(() -> controlPlane.resolve(workerScope(), claimed.claim(),
                "release-rollback",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "AUDIT_RETRY", ignored -> jdbc -> {
                    throw new IllegalStateException("resolution audit unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolution audit unavailable");
        assertThat(controlPlane.history(workerScope(), 10)).isEmpty();
        var resolved = controlPlane.resolve(workerScope(), claimed.claim(), "release-rollback",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "AUDIT_RETRY", ignored -> TestRuntimeTransactionMutation.noop());
        assertThat(resolved.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionDisposition.RESOLVED);
    }

    @Test
    void rejectsIdempotencyDriftAndForgedResolutionFencesWithoutChangingTheQueue() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-conflict",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop());

        assertThat(controlPlane.claim(workerScope(), key, "operator-a", "claim-conflict",
                Duration.ofMinutes(3), ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                .ClaimDisposition.IDEMPOTENCY_CONFLICT);
        var forged = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaim(
                key, "operator-a", "forged-token", claimed.claim().version(),
                claimed.claim().claimUntil());
        assertThat(controlPlane.resolve(workerScope(), forged, "forged-resolution",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "FORGED", ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .ResolutionDisposition.FENCE_REJECTED);
        assertThat(controlPlane.history(workerScope(), 10)).isEmpty();
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).singleElement()
                .extracting(DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineRecord::claimOwner)
                .isEqualTo("operator-a");
    }

    @Test
    void checkpointTransitionInvalidatesAClaimBeforeManualResolution() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-stale",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();

        repository.claimExpiredLease(new DurableTestExecutionCheckpointRepository.LeaseClaim(
                "tenant-a", "test", checkpoint.runId(),
                new DurableTestExecutionCheckpointRepository.Fence(
                        checkpoint.lifecycle().ownerId(), checkpoint.lifecycle().leaseEpoch(),
                        checkpoint.lifecycle().revision()), checkpoint.checkpointFingerprint(),
                "replacement-owner", Duration.ofMinutes(2)));

        assertThat(controlPlane.resolve(workerScope(), claim, "discard-stale",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "STALE", ignored -> TestRuntimeTransactionMutation.noop()).disposition())
                .isEqualTo(DatabaseDurableWorkerQuarantineControlPlane
                        .ResolutionDisposition.STALE_CHECKPOINT);
        assertThat(controlPlane.history(workerScope(), 10)).isEmpty();
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).isEmpty();
    }

    @Test
    void claimWaitsForAConcurrentCheckpointTransitionAndRejectsTheOldFingerprint()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        CountDownLatch transitionWritten = new CountDownLatch(1);
        CountDownLatch releaseTransition = new CountDownLatch(1);
        AtomicReference<DurableTestExecutionCheckpoint> successor = new AtomicReference<>();
        TransactionTemplate transition = new TransactionTemplate(database.transactionManager());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var transitioning = executor.submit(() -> transition.executeWithoutResult(ignored -> {
                successor.set(repository.claimExpiredLease(
                        new DurableTestExecutionCheckpointRepository.LeaseClaim(
                                "tenant-a", "test", checkpoint.runId(),
                                new DurableTestExecutionCheckpointRepository.Fence(
                                        checkpoint.lifecycle().ownerId(),
                                        checkpoint.lifecycle().leaseEpoch(),
                                        checkpoint.lifecycle().revision()),
                                checkpoint.checkpointFingerprint(), "replacement-owner",
                                Duration.ofMinutes(2))));
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

            var claiming = executor.submit(() -> controlPlane.claim(
                    workerScope(), key, "operator-a", "claim-transition-race",
                    Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()));
            Thread.sleep(100);
            assertThat(claiming.isDone()).isFalse();

            releaseTransition.countDown();
            transitioning.get(5, TimeUnit.SECONDS);
            assertThat(claiming.get(5, TimeUnit.SECONDS).disposition()).isEqualTo(
                    DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition
                            .STALE_CHECKPOINT);
        }

        assertThat(successor.get()).isNotNull();
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).isEmpty();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_durable_worker_quarantine_claim_commands",
                Integer.class)).isZero();
    }

    @Test
    void tamperedMaintenanceAndHistoryRecordsFailClosedAtPublicReadBoundaries() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-tamper",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_controls
                SET control_version = 99 WHERE run_id = 'run-a'
                """);

        assertThatThrownBy(() -> controlPlane.quarantines(workerScope(), false, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quarantine control is corrupt");

        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_controls
                SET control_version = 1 WHERE run_id = 'run-a'
                """);
        controlPlane.resolve(workerScope(), claim, "release-tamper",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "FIXED", ignored -> TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_history
                SET reason_code = 'TAMPERED' WHERE run_id = 'run-a'
                """);

        assertThatThrownBy(() -> controlPlane.history(workerScope(), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quarantine history is corrupt");
    }

    @Test
    void tamperedDiscardApprovalFailsClosedBeforeMutation() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-approval-tamper",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        var approval = approve(claim, "checker-a", "approve-tamper");
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_discard_approvals
                SET approver_id = 'operator-a'
                WHERE approval_id = ?
                """, approval.approvalId());

        assertThatThrownBy(() -> controlPlane.discard(
                workerScope(), claim, approval.approvalId(), "discard-tampered-approval",
                "AUTHORIZED_RETRY", ignored -> TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discard approval is corrupt");
        assertThat(controlPlane.quarantines(workerScope(), false, 10)).hasSize(1);
        assertThat(controlPlane.discardHistory(workerScope(), 10)).isEmpty();
    }

    @Test
    void tamperedApprovedDiscardHistoryFailsClosedAtPublicReadBoundary() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-history-tamper",
                Duration.ofMinutes(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        var approval = approve(claim, "checker-a", "approve-history-tamper");
        controlPlane.discard(workerScope(), claim, approval.approvalId(),
                "discard-history-tamper", "AUTHORIZED_RETRY",
                ignored -> TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_discard_history
                SET approver_id = 'operator-b'
                WHERE approval_id = ?
                """, approval.approvalId());

        assertThatThrownBy(() -> controlPlane.discardHistory(workerScope(), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discard history is corrupt");
    }

    @Test
    void retentionCryptographicallyErasesClaimReplayButReservesItsRequestIdentity()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claimed = controlPlane.claim(workerScope(), key, "operator-a", "claim-retained",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());
        String token = claimed.claim().claimToken();
        Thread.sleep(1_100);

        var lease = controlPlane.acquireRetentionLease().orElseThrow();
        var retained = controlPlane.retainClaimedPage(lease, Duration.ZERO,
                Duration.ZERO, Duration.ofDays(1), 10);

        assertThat(retained.status()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.RetentionStatus.COMPLETED);
        assertThat(retained.result().tombstoned()).isOne();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_test_durable_worker_quarantine_claim_commands
                """, Long.class)).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = 'CLAIM'
                """, Long.class)).isOne();
        assertThat(database.jdbc().queryForObject("""
                SELECT request_fingerprint || source_record_fingerprint || record_fingerprint
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = 'CLAIM'
                """, String.class)).doesNotContain(token);
        Map<String, Object> storedTombstone = database.jdbc().queryForMap("""
                SELECT request_key_id, request_key, record_version
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = 'CLAIM'
                """);
        assertThat(storedTombstone.get("REQUEST_KEY_ID")).isEqualTo("request-key-v1");
        assertThat(storedTombstone.get("REQUEST_KEY").toString())
                .startsWith("v1.").doesNotContain("claim-retained");
        assertThat(storedTombstone.get("RECORD_VERSION")).isEqualTo(2);
        assertThat(database.jdbc().queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'RG_TEST_DURABLE_WORKER_QUARANTINE_REQUEST_TOMBSTONES'
                """, String.class))
                .contains("REQUEST_KEY_ID", "REQUEST_KEY", "RECORD_VERSION")
                .doesNotContain("CLIENT_REQUEST_ID");

        assertThat(controlPlane.claim(workerScope(), key, "operator-a", "claim-retained",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition
                        .REPLAY_WINDOW_EXPIRED);
        assertThat(controlPlane.claim(workerScope(), key, "operator-a", "claim-retained",
                Duration.ofSeconds(2), ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition
                        .IDEMPOTENCY_CONFLICT);
        assertThat(controlPlane.retentionSnapshot()).satisfies(snapshot -> {
            assertThat(snapshot.totalTombstoned()).isOne();
            assertThat(snapshot.tombstoneRecords()).isOne();
            assertThat(snapshot.lastSuccessAt()).isNotNull();
        });
    }

    @Test
    void requestIndexRotationReadsTheOldGenerationAndLazilyRekeysTheTombstone()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        controlPlane.claim(workerScope(), key, "operator-a", "claim-index-rotation",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());
        Thread.sleep(1_100);
        controlPlane.retainClaimedPage(controlPlane.acquireRetentionLease().orElseThrow(),
                Duration.ZERO, Duration.ZERO, Duration.ofDays(1), 10);
        String oldIndex = database.jdbc().queryForObject("""
                SELECT request_key
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = 'CLAIM'
                """, String.class);
        WorkerQuarantineRequestKeyProtector rotatedIndexProtector = requestKeyProtector(
                "request-key-v2", Map.of(
                        "request-key-v1", keyBytes(11), "request-key-v2", keyBytes(12)));
        var rotated = new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                tokenProtector, rotatedIndexProtector);
        rotated.init();

        var replay = rotated.claim(workerScope(), key, "operator-a", "claim-index-rotation",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());

        assertThat(replay.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition
                        .REPLAY_WINDOW_EXPIRED);
        Map<String, Object> rekeyed = database.jdbc().queryForMap("""
                SELECT request_key_id, request_key, record_version
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = 'CLAIM'
                """);
        assertThat(rekeyed.get("REQUEST_KEY_ID")).isEqualTo("request-key-v2");
        assertThat(rekeyed.get("REQUEST_KEY")).isNotEqualTo(oldIndex);
        assertThat(rekeyed.get("RECORD_VERSION")).isEqualTo(2);
    }

    @Test
    void duplicateRequestIndexGenerationsFailClosedBeforeCommandCanRun()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        String requestId = "claim-index-duplicate";
        controlPlane.claim(workerScope(), key, "operator-a", requestId,
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());
        Thread.sleep(1_100);
        controlPlane.retainClaimedPage(controlPlane.acquireRetentionLease().orElseThrow(),
                Duration.ZERO, Duration.ZERO, Duration.ofDays(1), 10);
        Map<String, Object> current = database.jdbc().queryForMap("""
                SELECT scope_key, request_fingerprint, source_record_fingerprint,
                       source_completed_at, tombstoned_at, expires_at
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = 'CLAIM'
                """);
        String scopeKey = current.get("SCOPE_KEY").toString();
        WorkerQuarantineRequestKeyProtector rotatedIndexProtector = requestKeyProtector(
                "request-key-v2", Map.of(
                        "request-key-v1", keyBytes(11), "request-key-v2", keyBytes(12)));
        var activeIndex = rotatedIndexProtector.protect("CLAIM", scopeKey, requestId);
        Instant sourceCompletedAt = ((OffsetDateTime) current.get("SOURCE_COMPLETED_AT")).toInstant();
        Instant tombstonedAt = ((OffsetDateTime) current.get("TOMBSTONED_AT")).toInstant();
        Instant expiresAt = ((OffsetDateTime) current.get("EXPIRES_AT")).toInstant();
        String duplicateFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineRequestTombstone.v2"),
                Map.entry("requestKind", "CLAIM"), Map.entry("scopeKey", scopeKey),
                Map.entry("requestKeyId", activeIndex.keyId()),
                Map.entry("requestKey", activeIndex.value()),
                Map.entry("requestFingerprint", current.get("REQUEST_FINGERPRINT")),
                Map.entry("sourceRecordFingerprint", current.get("SOURCE_RECORD_FINGERPRINT")),
                Map.entry("sourceCompletedAt", sourceCompletedAt),
                Map.entry("tombstonedAt", tombstonedAt), Map.entry("expiresAt", expiresAt),
                Map.entry("recordVersion", 2)));
        database.jdbc().update("""
                INSERT INTO rg_test_durable_worker_quarantine_request_tombstones (
                    request_kind, scope_key, request_key_id, request_key, request_fingerprint,
                    source_record_fingerprint, source_completed_at, tombstoned_at,
                    expires_at, record_version, record_fingerprint
                ) VALUES ('CLAIM', ?, ?, ?, ?, ?, ?, ?, ?, 2, ?)
                """, scopeKey, activeIndex.keyId(), activeIndex.value(),
                current.get("REQUEST_FINGERPRINT"), current.get("SOURCE_RECORD_FINGERPRINT"),
                Timestamp.from(sourceCompletedAt), Timestamp.from(tombstonedAt),
                Timestamp.from(expiresAt), duplicateFingerprint);
        var rotated = new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                tokenProtector, rotatedIndexProtector);
        rotated.init();

        assertThatThrownBy(() -> rotated.claim(workerScope(), key, "operator-a", requestId,
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request tombstone is not unique")
                .hasMessageNotContaining(requestId);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_test_durable_worker_quarantine_claim_commands
                """, Integer.class)).isZero();
    }

    @Test
    void startupRejectsRemovalOfARequestIndexKeyStillReferencedByATombstone()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        controlPlane.claim(workerScope(), key, "operator-a", "claim-index-missing-key",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());
        Thread.sleep(1_100);
        controlPlane.retainClaimedPage(controlPlane.acquireRetentionLease().orElseThrow(),
                Duration.ZERO, Duration.ZERO, Duration.ofDays(1), 10);
        WorkerQuarantineRequestKeyProtector missingOldKey = requestKeyProtector(
                "request-key-v2", Map.of("request-key-v2", keyBytes(12)));
        var rotated = new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                tokenProtector, missingOldKey);

        assertThatThrownBy(rotated::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request tombstone key generation is unavailable")
                .hasMessageNotContaining("claim-index-missing-key");
    }

    @Test
    void retiredRequestIndexKeyDoesNotBlockPurgingItsAlreadyExpiredTombstone()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        controlPlane.claim(workerScope(), key, "operator-a", "claim-index-expired-key",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());
        Thread.sleep(1_100);
        controlPlane.retainClaimedPage(controlPlane.acquireRetentionLease().orElseThrow(),
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), 10);
        Thread.sleep(1_100);
        WorkerQuarantineRequestKeyProtector withoutRetiredKey = requestKeyProtector(
                "request-key-v2", Map.of("request-key-v2", keyBytes(12)));
        var rotated = new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                tokenProtector, withoutRetiredKey);

        rotated.init();
        var retained = rotated.retainClaimedPage(
                rotated.acquireRetentionLease().orElseThrow(), Duration.ZERO,
                Duration.ZERO, Duration.ofDays(1), 10);

        assertThat(retained.result().tombstonesPurged()).isOne();
        assertThat(rotated.retentionSnapshot().tombstoneRecords()).isZero();
    }

    @Test
    void legacyUnkeyedRequestTombstoneIsRecognizedAndUpgradedOnExactAccess()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        String requestId = "claim-index-legacy";
        controlPlane.claim(workerScope(), key, "operator-a", requestId,
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());
        Thread.sleep(1_100);
        controlPlane.retainClaimedPage(controlPlane.acquireRetentionLease().orElseThrow(),
                Duration.ZERO, Duration.ZERO, Duration.ofDays(1), 10);
        Map<String, Object> current = database.jdbc().queryForMap("""
                SELECT scope_key, request_fingerprint, source_record_fingerprint,
                       source_completed_at, tombstoned_at, expires_at
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = 'CLAIM'
                """);
        String scopeKey = current.get("SCOPE_KEY").toString();
        String legacyRequestKey = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineRequestKey.v1"),
                Map.entry("requestKind", "CLAIM"), Map.entry("scopeKey", scopeKey),
                Map.entry("clientRequestId", requestId)));
        Instant sourceCompletedAt = ((OffsetDateTime) current.get("SOURCE_COMPLETED_AT")).toInstant();
        Instant tombstonedAt = ((OffsetDateTime) current.get("TOMBSTONED_AT")).toInstant();
        Instant expiresAt = ((OffsetDateTime) current.get("EXPIRES_AT")).toInstant();
        String legacyFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineRequestTombstone.v1"),
                Map.entry("requestKind", "CLAIM"), Map.entry("scopeKey", scopeKey),
                Map.entry("requestKey", legacyRequestKey),
                Map.entry("requestFingerprint", current.get("REQUEST_FINGERPRINT")),
                Map.entry("sourceRecordFingerprint", current.get("SOURCE_RECORD_FINGERPRINT")),
                Map.entry("sourceCompletedAt", sourceCompletedAt),
                Map.entry("tombstonedAt", tombstonedAt), Map.entry("expiresAt", expiresAt)));
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_request_tombstones
                SET request_key_id = '', request_key = ?, record_version = 1,
                    record_fingerprint = ?
                WHERE request_kind = 'CLAIM'
                """, legacyRequestKey, legacyFingerprint);
        var upgraded = new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper,
                tokenProtector, requestKeyProtector);
        upgraded.init();

        var replay = upgraded.claim(workerScope(), key, "operator-a", requestId,
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());

        assertThat(replay.disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition
                        .REPLAY_WINDOW_EXPIRED);
        Map<String, Object> migrated = database.jdbc().queryForMap("""
                SELECT request_key_id, request_key, record_version
                FROM rg_test_durable_worker_quarantine_request_tombstones
                WHERE request_kind = 'CLAIM'
                """);
        assertThat(migrated.get("REQUEST_KEY_ID")).isEqualTo("request-key-v1");
        assertThat(migrated.get("REQUEST_KEY").toString()).startsWith("v1.");
        assertThat(migrated.get("REQUEST_KEY")).isNotEqualTo(legacyRequestKey);
        assertThat(migrated.get("RECORD_VERSION")).isEqualTo(2);
    }

    @Test
    void corruptedRequestTombstoneFailsClosedBeforeIdempotencyClassification() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-tombstone-tamper",
                Duration.ofMinutes(1), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        controlPlane.resolve(workerScope(), claim, "release-tombstone-tamper",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "AUTHORIZED_RETRY", ignored -> TestRuntimeTransactionMutation.noop());
        var lease = controlPlane.acquireRetentionLease().orElseThrow();
        controlPlane.retainClaimedPage(lease, Duration.ZERO, Duration.ZERO,
                Duration.ofDays(1), 10);
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_request_tombstones
                SET request_fingerprint = ?
                WHERE request_kind = 'RESOLUTION'
                """, SHA_B);

        assertThatThrownBy(() -> controlPlane.resolve(workerScope(), claim,
                "release-tombstone-tamper",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "AUTHORIZED_RETRY", ignored -> TestRuntimeTransactionMutation.noop()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request tombstone is corrupt");
    }

    @Test
    void retentionTombstonesResolutionAndPurgesItsHistoryAsOnePage() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-release-retained",
                Duration.ofMinutes(1), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        var resolved = controlPlane.resolve(workerScope(), claim, "release-retained",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "AUTHORIZED_RETRY", ignored -> TestRuntimeTransactionMutation.noop());

        var lease = controlPlane.acquireRetentionLease().orElseThrow();
        var retained = controlPlane.retainClaimedPage(lease, Duration.ZERO,
                Duration.ZERO, Duration.ofDays(1), 10);

        assertThat(retained.result().tombstoned()).isOne();
        assertThat(retained.result().historiesPurged()).isOne();
        assertThat(controlPlane.history(workerScope(), 10)).isEmpty();
        assertThat(controlPlane.resolve(workerScope(), claim, "release-retained",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "AUTHORIZED_RETRY", ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionDisposition
                        .REPLAY_WINDOW_EXPIRED);
        assertThat(controlPlane.resolve(workerScope(), claim, "release-retained",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "DIFFERENT_REASON", ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionDisposition
                        .IDEMPOTENCY_CONFLICT);
        assertThat(resolved.receipt()).isNotNull();
    }

    @Test
    void retentionBoundsApprovalDiscardAndTwoPersonHistoryWithoutRequestResurrection()
            throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-discard-retained",
                Duration.ofSeconds(2), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        var approval = controlPlane.approveDiscard(workerScope(), key, claim.ownerId(),
                claim.version(), claim.claimUntil(), "checker-a", "approval-retained",
                "AUTHORIZED_RETRY", Duration.ofSeconds(2),
                ignored -> TestRuntimeTransactionMutation.noop()).approval();
        var discarded = controlPlane.discard(workerScope(), claim, approval.approvalId(),
                "discard-retained", "AUTHORIZED_RETRY",
                ignored -> TestRuntimeTransactionMutation.noop());
        Thread.sleep(2_100);

        var lease = controlPlane.acquireRetentionLease().orElseThrow();
        var retained = controlPlane.retainClaimedPage(lease, Duration.ZERO,
                Duration.ZERO, Duration.ofDays(1), 10);

        assertThat(retained.result().tombstoned()).isEqualTo(3);
        assertThat(retained.result().historiesPurged()).isOne();
        assertThat(controlPlane.discardHistory(workerScope(), 10)).isEmpty();
        assertThat(controlPlane.approveDiscard(workerScope(), key, claim.ownerId(),
                claim.version(), claim.claimUntil(), "checker-a", "approval-retained",
                "AUTHORIZED_RETRY", Duration.ofSeconds(2),
                ignored -> TestRuntimeTransactionMutation.noop()).disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.DiscardApprovalDisposition
                        .REPLAY_WINDOW_EXPIRED);
        assertThat(controlPlane.discard(workerScope(), claim, approval.approvalId(),
                "discard-retained", "AUTHORIZED_RETRY",
                ignored -> TestRuntimeTransactionMutation.noop()).disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ApprovedDiscardDisposition
                        .REPLAY_WINDOW_EXPIRED);
        assertThat(discarded.receipt()).isNotNull();
    }

    @Test
    void retentionLeaseIsExclusiveAndRejectsTheSupersededReplicaFence() throws Exception {
        var first = new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper, tokenProtector,
                requestKeyProtector,
                "retention-replica-a", Duration.ofSeconds(1));
        first.init();
        var firstLease = first.acquireRetentionLease().orElseThrow();
        var second = new DatabaseDurableWorkerQuarantineControlPlane(
                database.jdbc(), database.transactionManager(), objectMapper, tokenProtector,
                requestKeyProtector,
                "retention-replica-b", Duration.ofSeconds(1));
        second.init();

        assertThat(second.acquireRetentionLease()).isEmpty();
        Thread.sleep(1_100);
        var successor = second.acquireRetentionLease().orElseThrow();

        assertThat(successor.epoch()).isGreaterThan(firstLease.epoch());
        assertThatThrownBy(() -> first.retainClaimedPage(firstLease, Duration.ZERO,
                Duration.ZERO, Duration.ofDays(1), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention fence is stale or expired");
    }

    @Test
    void retentionAuthorityTamperingFailsClosedBeforeLeaseOrSnapshotUse() {
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_retention
                SET total_tombstoned = total_tombstoned + 1
                """);

        assertThatThrownBy(controlPlane::retentionSnapshot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention authority is corrupt");
        assertThatThrownBy(controlPlane::acquireRetentionLease)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention authority is corrupt");
    }

    @Test
    void retentionRollsBackTombstonesHistoryAndCountersWhenOneSourceIsCorrupt() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        var claim = controlPlane.claim(workerScope(), key, "operator-a", "claim-retention-tamper",
                Duration.ofMinutes(1), ignored -> TestRuntimeTransactionMutation.noop()).claim();
        controlPlane.resolve(workerScope(), claim, "release-retention-tamper",
                DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                "AUTHORIZED_RETRY", ignored -> TestRuntimeTransactionMutation.noop());
        database.jdbc().update("""
                UPDATE rg_test_durable_worker_quarantine_resolutions
                SET result_version = result_version + 1
                WHERE client_request_id = 'release-retention-tamper'
                """);
        var lease = controlPlane.acquireRetentionLease().orElseThrow();

        assertThatThrownBy(() -> controlPlane.retainClaimedPage(lease, Duration.ZERO,
                Duration.ZERO, Duration.ofDays(1), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolution command is corrupt");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_test_durable_worker_quarantine_resolutions
                """, Long.class)).isOne();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_test_durable_worker_quarantine_history
                """, Long.class)).isOne();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_durable_worker_quarantine_request_tombstones
                """, Long.class)).isZero();
        assertThat(controlPlane.retentionSnapshot()).satisfies(snapshot -> {
            assertThat(snapshot.totalTombstoned()).isZero();
            assertThat(snapshot.totalHistoryPurged()).isZero();
            assertThat(snapshot.lastSuccessAt()).isNull();
        });
    }

    @Test
    void retentionAppliesThePageBoundIndependentlyToCommandsAndHistory() {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        for (int index = 0; index < 3; index++) {
            var claim = controlPlane.claim(workerScope(), key, "operator-a",
                    "claim-page-" + index, Duration.ofMinutes(1),
                    ignored -> TestRuntimeTransactionMutation.noop()).claim();
            controlPlane.resolve(workerScope(), claim, "release-page-" + index,
                    DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.RELEASE,
                    "AUTHORIZED_RETRY", ignored -> TestRuntimeTransactionMutation.noop());
        }

        var lease = controlPlane.acquireRetentionLease().orElseThrow();
        var firstPage = controlPlane.retainClaimedPage(lease, Duration.ZERO,
                Duration.ZERO, Duration.ofDays(1), 2);

        assertThat(firstPage.result().tombstoned()).isEqualTo(2);
        assertThat(firstPage.result().historiesPurged()).isEqualTo(2);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_test_durable_worker_quarantine_resolutions
                """, Long.class)).isOne();
        assertThat(controlPlane.history(workerScope(), 10)).hasSize(1);
    }

    @Test
    void expiredTombstoneIsPurgedBeforeTheRequestIdentityCanBeReused() throws Exception {
        DurableTestExecutionCheckpoint checkpoint = createQuarantine();
        var key = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                checkpoint.runId(), checkpoint.checkpointFingerprint());
        controlPlane.claim(workerScope(), key, "operator-a", "claim-reusable",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop());
        Thread.sleep(1_100);
        var firstLease = controlPlane.acquireRetentionLease().orElseThrow();
        controlPlane.retainClaimedPage(firstLease, Duration.ZERO, Duration.ZERO,
                Duration.ofSeconds(1), 10);

        assertThat(controlPlane.claim(workerScope(), key, "operator-a", "claim-reusable",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition
                        .REPLAY_WINDOW_EXPIRED);
        Thread.sleep(1_100);
        var secondLease = controlPlane.acquireRetentionLease().orElseThrow();
        var purged = controlPlane.retainClaimedPage(secondLease, Duration.ZERO,
                Duration.ZERO, Duration.ofSeconds(1), 10);

        assertThat(purged.result().tombstonesPurged()).isOne();
        assertThat(controlPlane.claim(workerScope(), key, "operator-a", "claim-reusable",
                Duration.ofSeconds(1), ignored -> TestRuntimeTransactionMutation.noop())
                .disposition()).isEqualTo(
                DatabaseDurableWorkerQuarantineControlPlane.ClaimDisposition.CLAIMED);
    }

    private DurableTestExecutionCheckpoint createQuarantine() {
        DurableTestExecutionCheckpoint checkpoint = expiredCheckpoint();
        repository.create(checkpoint, boundNoop(checkpoint));
        DurableTestExecutionCheckpointRepository.RecoveryCandidate candidate =
                repository.findExpiredRecoveryCandidates(
                        new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                                workerScope(), 1)).candidates().getFirst();
        repository.acquireWorkerCommandIdempotently(
                new DurableTestExecutionCheckpointRepository.WorkerAcquisitionCommand(
                        "create-quarantine", SHA_A, workerScope()), Optional.empty(),
                Optional.of(candidate.progress()), List.of(
                        new DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral(
                                candidate.progress(), checkpoint.checkpointFingerprint(),
                                DurableTestExecutionCheckpointRepository
                                        .WorkerCandidateDeferralReason.AUTHORIZATION_DENIED,
                                Duration.ofSeconds(5), Duration.ofMinutes(5), 1)),
                TestRuntimeTransactionMutation.noop());
        return checkpoint;
    }

    private DatabaseDurableWorkerQuarantineControlPlane.DiscardApproval approve(
            DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaim claim,
            String approver,
            String requestId) {
        return controlPlane.approveDiscard(workerScope(), claim.key(), claim.ownerId(),
                claim.version(), claim.claimUntil(), approver, requestId,
                "AUTHORIZED_RETRY", Duration.ofMinutes(1),
                ignored -> TestRuntimeTransactionMutation.noop()).approval();
    }

    private static WorkerQuarantineClaimTokenProtector tokenProtector(
            String activeKeyId, String keyId, int fill) {
        return new WorkerQuarantineClaimTokenProtector(
                activeKeyId, Map.of(keyId, keyBytes(fill)), new SecureRandom());
    }

    private static WorkerQuarantineRequestKeyProtector requestKeyProtector(
            String activeKeyId, Map<String, byte[]> keys) {
        return new WorkerQuarantineRequestKeyProtector(activeKeyId, keys);
    }

    private static byte[] keyBytes(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private java.util.concurrent.Future<?> holdCheckpointLock(
            java.util.concurrent.ExecutorService executor,
            CountDownLatch locked,
            CountDownLatch release) {
        TransactionTemplate transaction = new TransactionTemplate(database.transactionManager());
        return executor.submit(() -> transaction.executeWithoutResult(ignored -> {
            String found = database.jdbc().queryForObject("""
                    SELECT run_id FROM rg_test_durable_execution_checkpoints
                    WHERE tenant_id = 'tenant-a' AND environment_id = 'test'
                      AND run_id = 'run-a'
                    FOR UPDATE
                    """, String.class);
            if (!"run-a".equals(found)) {
                throw new IllegalStateException("checkpoint lock target was not found");
            }
            locked.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("checkpoint lock release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("checkpoint lock interrupted", interrupted);
            }
        }));
    }

    private DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope workerScope() {
        return new DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope(
                "tenant-a", "org-a", "project-a", "test");
    }

    private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation boundNoop(
            DurableTestExecutionCheckpoint checkpoint) {
        return new DurableTestExecutionCheckpointRepository.BoundEngineStateMutation() {
            @Override
            public String engineExecutionId() {
                return checkpoint.engineExecutionId();
            }

            @Override
            public DurableTestExecutionCheckpoint.EngineState engineState() {
                return checkpoint.engineState();
            }

            @Override
            public void apply(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            }
        };
    }

    private DurableTestExecutionCheckpoint expiredCheckpoint() {
        Instant now = Instant.parse("2000-01-01T00:00:01Z");
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION, "plan-a", SHA_A,
                "GRAPH_CONTRACT_TEST", SHA_B, SHA_C, List.of(), List.of(), List.of(),
                Map.of("unmatchedExternalEffect", "DENY"), List.of());
        ExecutionServiceStateSnapshot unsealedProvider = new ExecutionServiceStateSnapshot(
                ExecutionServiceStateSnapshot.SCHEMA_VERSION, SHA_A, SHA_B, now,
                Map.of(SHA_C, 1L), Map.of(), List.of(), true, List.of(), SHA_D);
        ExecutionServiceStateSnapshot provider = new ExecutionServiceStateSnapshot(
                unsealedProvider.schemaVersion(), unsealedProvider.planFingerprint(),
                unsealedProvider.bindingSetFingerprint(), unsealedProvider.logicalTime(),
                unsealedProvider.randomScopeCursors(), unsealedProvider.uuidScopeCursors(),
                unsealedProvider.usages(), unsealedProvider.restorable(),
                unsealedProvider.restoreGaps(),
                ProtocolFingerprint.of(objectMapper, unsealedProvider.fingerprintMaterial()));
        return integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                "run-a", "engine-a",
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        plan, new DurableTestExecutionCheckpoint.ExactFixtureRef(
                        "fixture-a", 3, SHA_C), "DENY_REAL",
                        new DurableTestExecutionCheckpoint.AuthoritySnapshot(
                                "FAIL_CLOSED", SHA_D),
                        new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                                "GRAPH", "credit-score", SHA_B)),
                new FixtureConsumptionStateSnapshot(
                        FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                        Map.of("rule-a", 1L), Map.of(SHA_A, 1L), Map.of(SHA_B, 1L), ""),
                provider,
                new DurableTestExecutionCheckpoint.EngineState(
                        "checkpoint-0", "fetch", "NODE_BOUNDARY", 1, 0,
                        ProtocolFingerprint.ofText("engine-checkpoint-0")),
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.SUSPENDED, "instance-a", 1, 0,
                        Instant.parse("2000-01-01T00:00:00Z"), now,
                        Instant.parse("2000-01-01T00:00:02Z")), ""));
    }
}
