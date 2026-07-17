package com.leanowtech.bloge.gateway.testing.persistence;

import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestRuntimeSloControlPlaneTest {

    private TestRuntimeDatabase database;
    private DatabaseTestRuntimeSloControlPlane controlPlane;
    private Instant now;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:test-runtime-slo-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "", 2));
        createTables();
        controlPlane = new DatabaseTestRuntimeSloControlPlane(
                database.jdbc(), database.transactionManager());
        now = database.jdbc().queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void observesOutcomeQueuesAndStorageWithoutReadingPayloadColumns() {
        insertRun("PASSED", now.minusSeconds(30), now.plusSeconds(3600));
        insertRun("PASSED", now.minusSeconds(60), now.minusSeconds(1));
        insertRun("EVIDENCE_INCOMPLETE", now.minusSeconds(90), now.plusSeconds(3600));
        insertRun("EXECUTION_FAILED", now.minusSeconds(3600), now.plusSeconds(3600));

        insertSuite("PASSED", now.minusSeconds(30), now.plusSeconds(60), now.plusSeconds(3600));
        insertSuite("PARTIAL", now.minusSeconds(40), now.plusSeconds(60), now.minusSeconds(1));
        insertSuite("RUNNING", now.minusSeconds(80), now.plusSeconds(60), now.plusSeconds(3600));
        insertSuite("RUNNING", now.minusSeconds(120), now.minusSeconds(5), now.plusSeconds(3600));

        insertCreation("PENDING", now.minusSeconds(40), now.plusSeconds(60));
        insertCreation("PENDING", now.minusSeconds(100), now.minusSeconds(5));
        insertCreation("COMMITTED", now.minusSeconds(200), now.minusSeconds(100));

        insertDurable("ACTIVE", now.minusSeconds(30), now.plusSeconds(60));
        insertDurable("RESUMING", now.minusSeconds(100), now.minusSeconds(5));
        insertDurable("SUSPENDED", now.minusSeconds(200), now.minusSeconds(100));
        insertDurable("TERMINAL", now.minusSeconds(300), now.minusSeconds(200));
        insertDurable("CONTROL_PLAN_UNAVAILABLE", now.minusSeconds(300), now.minusSeconds(200));

        insertEngine("RUNNING");
        insertEngine("COMPLETED");

        insertWork("READY", null, null, now.minusSeconds(50));
        insertWork("RETRY_WAIT", null, now.minusSeconds(1), now.minusSeconds(60));
        insertWork("RETRY_WAIT", null, now.plusSeconds(60), now.minusSeconds(70));
        insertWork("CLAIMED", now.minusSeconds(1), null, now.minusSeconds(80));
        insertWork("DONE", null, null, now.minusSeconds(90));
        insertWork("DEAD_LETTER", null, null, now.minusSeconds(100));
        insertWorkerDeferral("AUTHORIZATION_DENIED", 3,
                now.minusSeconds(100), now.plusSeconds(100));
        insertWorkerDeferral("AUTHORIZATION_CONFLICT", 2,
                now.minusSeconds(80), now.minusSeconds(1));
        insertWorkerDeferral("LEGACY_PROTOCOL", 7,
                now.minusSeconds(50), now.plusSeconds(50));
        insertWorkerQuarantine("scope-a", "run-a", "AUTHORIZATION_DENIED", 32,
                now.minusSeconds(200), "CLAIMED", now.plusSeconds(60));
        insertWorkerQuarantine("scope-a", "run-b", "LEGACY_PROTOCOL", 40,
                now.minusSeconds(150), "CLAIMED", now.minusSeconds(1));
        database.jdbc().update(
                "INSERT INTO rg_test_durable_worker_quarantine_history DEFAULT VALUES");

        DatabaseTestRuntimeSloControlPlane.OperationalSnapshot snapshot =
                controlPlane.operationalSnapshot(Duration.ofMinutes(15));

        assertThat(snapshot.observedAt()).isAfterOrEqualTo(now);
        assertThat(snapshot.executionSamples()).isEqualTo(3);
        assertThat(snapshot.incompleteExecutions()).isEqualTo(1);
        assertThat(snapshot.executionOutcomes())
                .containsEntry(TestRunEvidence.Status.PASSED, 2L)
                .containsEntry(TestRunEvidence.Status.EXECUTION_FAILED, 0L);
        assertThat(snapshot.suiteSamples()).isEqualTo(2);
        assertThat(snapshot.incompleteSuites()).isEqualTo(1);
        assertThat(snapshot.suiteOutcomes())
                .containsEntry(TestSuiteRunEvidence.Status.PASSED, 1L)
                .containsEntry(TestSuiteRunEvidence.Status.PARTIAL, 1L)
                .containsEntry(TestSuiteRunEvidence.Status.RUNNING, 0L);
        assertThat(snapshot.suiteRuns().depth()).isEqualTo(2);
        assertThat(snapshot.suiteRuns().expiredClaims()).isEqualTo(1);
        assertThat(snapshot.durableCreations().depth()).isEqualTo(2);
        assertThat(snapshot.durableCreations().expiredClaims()).isEqualTo(1);
        assertThat(snapshot.durableExecutions().depth()).isEqualTo(3);
        assertThat(snapshot.durableExecutions().expiredClaims()).isEqualTo(1);
        assertThat(snapshot.durableExecutions().oldestActivityAt())
                .isAfter(now.minusSeconds(110)).isBefore(now.minusSeconds(90));
        assertThat(snapshot.workItems().depth()).isEqualTo(3);
        assertThat(snapshot.workItems().expiredClaims()).isEqualTo(1);
        assertThat(snapshot.durableExecutionStates())
                .containsEntry(DurableTestExecutionCheckpoint.Status.SUSPENDED, 1L)
                .containsEntry(DurableTestExecutionCheckpoint.Status.TERMINAL, 1L);
        assertThat(snapshot.engineExecutionStates())
                .containsEntry(ExecutionStatus.RUNNING, 1L)
                .containsEntry(ExecutionStatus.COMPLETED, 1L);
        assertThat(snapshot.workItemStates())
                .containsEntry(WorkItemStatus.RETRY_WAIT, 2L)
                .containsEntry(WorkItemStatus.DONE, 1L);
        assertThat(snapshot.workerCandidateDeferrals().totalRecords()).isEqualTo(3);
        assertThat(snapshot.workerCandidateDeferrals().activeRecords()).isEqualTo(2);
        assertThat(snapshot.workerCandidateDeferrals().retryDueRecords()).isOne();
        assertThat(snapshot.workerCandidateDeferrals().activeByReason())
                .containsEntry(DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferralReason.AUTHORIZATION_DENIED, 1L)
                .containsEntry(DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferralReason.AUTHORIZATION_CONFLICT, 0L);
        assertThat(snapshot.workerCandidateDeferrals().maximumActiveConsecutiveFailures())
                .isEqualTo(7);
        assertThat(snapshot.workerCandidateDeferrals().oldestActiveObservedAt())
                .isAfter(now.minusSeconds(110)).isBefore(now.minusSeconds(90));
        assertThat(snapshot.workerCandidateQuarantines().totalRecords()).isEqualTo(2);
        assertThat(snapshot.workerCandidateQuarantines().totalByReason())
                .containsEntry(DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferralReason.AUTHORIZATION_DENIED, 1L)
                .containsEntry(DurableTestExecutionCheckpointRepository
                        .WorkerCandidateDeferralReason.LEGACY_PROTOCOL, 1L);
        assertThat(snapshot.workerCandidateQuarantines().maximumConsecutiveFailures())
                .isEqualTo(40);
        assertThat(snapshot.workerCandidateQuarantines().oldestQuarantinedAt())
                .isAfter(now.minusSeconds(210)).isBefore(now.minusSeconds(190));
        assertThat(snapshot.workerCandidateQuarantines().totalByMaintenanceState())
                .containsEntry(DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineState.AVAILABLE, 1L)
                .containsEntry(DatabaseDurableWorkerQuarantineControlPlane
                        .QuarantineState.CLAIMED, 1L);
        assertThat(snapshot.workerCandidateQuarantines().expiredClaimRecords()).isOne();
        assertThat(snapshot.workerCandidateQuarantines().historyRecords()).isOne();
        assertThat(snapshot.storage()).isEqualTo(
                new DatabaseTestRuntimeSloControlPlane.StorageSnapshot(4, 1, 4, 1, 2, 2));
    }

    @Test
    void rejectsUnknownLifecycleProjectionAndInvalidObservationWindow() {
        insertRun("UNKNOWN_STATUS", now.minusSeconds(1), now.plusSeconds(60));

        assertThatThrownBy(() -> controlPlane.operationalSnapshot(Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed vocabulary")
                .hasMessageNotContaining("UNKNOWN_STATUS");
        assertThatThrownBy(() -> controlPlane.operationalSnapshot(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcomeLookback");
        assertThatThrownBy(() -> controlPlane.operationalSnapshot(Duration.ofDays(366)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("365 days");
    }

    private void createTables() {
        database.jdbc().execute("""
                CREATE TABLE rg_test_run_records (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
                    status VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_json CLOB
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_suite_run_records (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
                    status VARCHAR(64) NOT NULL,
                    last_checkpoint_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_json CLOB
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_durable_creation_commands (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
                    state VARCHAR(32) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_durable_execution_checkpoints (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
                    status VARCHAR(64) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_bloge_executions (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
                    execution_status VARCHAR(64) NOT NULL
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_bloge_work_items (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
                    item_status VARCHAR(64) NOT NULL,
                    claim_until TIMESTAMP WITH TIME ZONE,
                    next_attempt_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    payload_json CLOB
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_durable_worker_candidate_deferrals (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
                    reason VARCHAR(64) NOT NULL,
                    consecutive_failures BIGINT NOT NULL,
                    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    retry_after TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_durable_worker_candidate_quarantines (
                    scope_key VARCHAR(80) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    reason VARCHAR(64) NOT NULL,
                    consecutive_failures BIGINT NOT NULL,
                    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (scope_key, run_id)
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_durable_worker_quarantine_controls (
                    scope_key VARCHAR(80) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    control_state VARCHAR(32) NOT NULL,
                    claim_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (scope_key, run_id)
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_durable_worker_quarantine_history (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY
                )
                """);
    }

    private void insertRun(String status, Instant createdAt, Instant expiresAt) {
        database.jdbc().update("""
                INSERT INTO rg_test_run_records (status, created_at, expires_at, record_json)
                VALUES (?, ?, ?, 'secret-payload-that-must-not-be-read')
                """, status, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    private void insertSuite(
            String status,
            Instant lastCheckpointAt,
            Instant leaseExpiresAt,
            Instant expiresAt) {
        database.jdbc().update("""
                INSERT INTO rg_test_suite_run_records (
                    status, last_checkpoint_at, lease_expires_at, expires_at, record_json
                ) VALUES (?, ?, ?, ?, 'secret-payload-that-must-not-be-read')
                """, status, Timestamp.from(lastCheckpointAt), Timestamp.from(leaseExpiresAt),
                Timestamp.from(expiresAt));
    }

    private void insertCreation(String state, Instant updatedAt, Instant leaseExpiresAt) {
        database.jdbc().update("""
                INSERT INTO rg_test_durable_creation_commands (
                    state, updated_at, lease_expires_at
                ) VALUES (?, ?, ?)
                """, state, Timestamp.from(updatedAt), Timestamp.from(leaseExpiresAt));
    }

    private void insertDurable(String status, Instant updatedAt, Instant leaseExpiresAt) {
        database.jdbc().update("""
                INSERT INTO rg_test_durable_execution_checkpoints (
                    status, updated_at, lease_expires_at
                ) VALUES (?, ?, ?)
                """, status, Timestamp.from(updatedAt), Timestamp.from(leaseExpiresAt));
    }

    private void insertEngine(String status) {
        database.jdbc().update("""
                INSERT INTO rg_test_bloge_executions (execution_status) VALUES (?)
                """, status);
    }

    private void insertWork(
            String status,
            Instant claimUntil,
            Instant nextAttemptAt,
            Instant createdAt) {
        database.jdbc().update("""
                INSERT INTO rg_test_bloge_work_items (
                    item_status, claim_until, next_attempt_at, created_at, payload_json
                ) VALUES (?, ?, ?, ?, 'secret-payload-that-must-not-be-read')
                """, status, timestamp(claimUntil), timestamp(nextAttemptAt), Timestamp.from(createdAt));
    }

    private void insertWorkerDeferral(
            String reason,
            long consecutiveFailures,
            Instant firstObservedAt,
            Instant retryAfter) {
        database.jdbc().update("""
                INSERT INTO rg_test_durable_worker_candidate_deferrals (
                    reason, consecutive_failures, first_observed_at, retry_after
                ) VALUES (?, ?, ?, ?)
                """, reason, consecutiveFailures, Timestamp.from(firstObservedAt),
                Timestamp.from(retryAfter));
    }

    private void insertWorkerQuarantine(
            String scopeKey,
            String runId,
            String reason,
            long consecutiveFailures,
            Instant quarantinedAt,
            String controlState,
            Instant claimUntil) {
        database.jdbc().update("""
                INSERT INTO rg_test_durable_worker_candidate_quarantines (
                    scope_key, run_id, reason, consecutive_failures, quarantined_at
                ) VALUES (?, ?, ?, ?, ?)
                """, scopeKey, runId, reason, consecutiveFailures,
                Timestamp.from(quarantinedAt));
        database.jdbc().update("""
                INSERT INTO rg_test_durable_worker_quarantine_controls (
                    scope_key, run_id, control_state, claim_until
                ) VALUES (?, ?, ?, ?)
                """, scopeKey, runId, controlState, Timestamp.from(claimUntil));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
