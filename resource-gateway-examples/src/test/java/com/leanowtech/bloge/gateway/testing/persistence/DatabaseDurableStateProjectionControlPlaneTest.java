package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseDurableStateProjectionControlPlaneTest {

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:durable-projection-control-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1",
                "sa", "", 4));
        StagedBlogeDurableStateStore stateStore =
                new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        stateStore.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void atomicallyRepairsFindingsAndResumesFromThePersistedCursorOnAnotherReplica()
            throws Exception {
        Instant now = Instant.parse("2026-07-17T01:00:00Z");
        ExecutionInstance first = execution("engine-a", now);
        ExecutionInstance second = execution("engine-b", now);
        insertExecution(first, "COMPLETED", objectMapper.writeValueAsString(first));
        insertExecution(second, ExecutionStatus.RUNNING.name(),
                objectMapper.writeValueAsString(second));
        DatabaseDurableStateProjectionControlPlane firstReplica = controlPlane(
                database.jdbc(), "replica-a");

        TransactionTemplate callerTransaction = new TransactionTemplate(
                database.transactionManager());
        DatabaseDurableStateProjectionControlPlane.SweepAttempt firstAttempt =
                callerTransaction.execute(status -> {
                    DatabaseDurableStateProjectionControlPlane.SweepAttempt attempt =
                            firstReplica.reconcilePage(1,
                                    DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);
                    status.setRollbackOnly();
                    return attempt;
                });

        assertThat(firstAttempt.status()).isEqualTo(
                DatabaseDurableStateProjectionControlPlane.SweepStatus.COMPLETED);
        assertThat(firstAttempt.result()).isNotNull();
        assertThat(firstAttempt.result().repaired()).isEqualTo(1);
        assertThat(firstReplica.snapshot().cursor().afterExecutionId()).isEqualTo("engine-a");
        assertThat(firstReplica.findings(10)).singleElement().satisfies(finding -> {
            assertThat(finding.status()).isEqualTo(
                    DatabaseDurableStateProjectionControlPlane.FindingStatus.RESOLVED);
            assertThat(finding.resolution()).isEqualTo(
                    DatabaseDurableStateProjectionControlPlane.Resolution.AUTO_REPAIRED);
            assertThat(finding.columns()).containsExactly("execution_status");
        });
        assertThat(database.jdbc().queryForObject("""
                SELECT execution_status FROM rg_test_bloge_executions WHERE execution_id = ?
                """, String.class, "engine-a")).isEqualTo(ExecutionStatus.RUNNING.name());

        DatabaseDurableStateProjectionControlPlane secondReplica = controlPlane(
                database.jdbc(), "replica-b");
        DatabaseDurableStateProjectionControlPlane.SweepAttempt secondAttempt =
                secondReplica.reconcilePage(1,
                        DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);

        assertThat(secondAttempt.result().scanned()).isEqualTo(1);
        assertThat(secondAttempt.result().findings()).isEmpty();
        assertThat(secondReplica.snapshot().cursor())
                .isEqualTo(DurableStateProjectionReconciler.ScanCursor.start());
    }

    @Test
    void claimAndResolutionRequireTheExactLiveDatabaseClockFence() throws Exception {
        insertRawExecution("engine-poison", "not-json");
        DatabaseDurableStateProjectionControlPlane controlPlane = controlPlane(
                database.jdbc(), "replica-a");
        controlPlane.reconcilePage(10,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);
        DurableStateProjectionReconciler.EntityKey key =
                new DurableStateProjectionReconciler.EntityKey(
                        DurableStateProjectionReconciler.EntityType.EXECUTION,
                        "engine-poison");

        assertThat(controlPlane.actionableFindings(10)).singleElement().satisfies(finding -> {
            assertThat(finding.key()).isEqualTo(key);
            assertThat(finding.status()).isEqualTo(
                    DatabaseDurableStateProjectionControlPlane.FindingStatus.OPEN);
            assertThat(finding.kind()).isEqualTo(
                    DurableStateProjectionReconciler.FindingKind.AUTHORITY_UNREADABLE);
            assertThat(finding.columns()).isEmpty();
            assertThat(finding.toString()).doesNotContain("not-json", "payload");
        });
        DatabaseDurableStateProjectionControlPlane.FindingClaim claim = controlPlane
                .claimFinding(key, "operator-a", Duration.ofMinutes(2))
                .orElseThrow();
        assertThat(controlPlane.findings(10).getFirst().toString())
                .doesNotContain(claim.claimToken());

        assertThat(controlPlane.claimFinding(key, "operator-b", Duration.ofMinutes(2)))
                .isEmpty();
        database.jdbc().update("""
                UPDATE rg_test_bloge_projection_findings
                SET claim_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE entity_type = 'EXECUTION' AND row_id = 'engine-poison'
                """);
        DatabaseDurableStateProjectionControlPlane.FindingClaim successor = controlPlane
                .claimFinding(key, "operator-b", Duration.ofMinutes(2))
                .orElseThrow();
        assertThat(successor.version()).isGreaterThan(claim.version());
        assertThat(controlPlane.resolveFinding(claim,
                DatabaseDurableStateProjectionControlPlane.Resolution.MANUALLY_REPAIRED))
                .isFalse();
        ExecutionInstance changedAuthority = execution(
                "engine-poison", Instant.parse("2026-07-17T01:00:00Z"));
        database.jdbc().update("""
                UPDATE rg_test_bloge_executions SET tenant_id = ?, payload_json = ?
                WHERE execution_id = ?
                """, "tenant-b", objectMapper.writeValueAsString(changedAuthority),
                "engine-poison");
        controlPlane.reconcilePage(10,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);
        assertThat(controlPlane.resolveFinding(successor,
                DatabaseDurableStateProjectionControlPlane.Resolution.MANUALLY_REPAIRED))
                .isFalse();
        assertThat(controlPlane.actionableFindings(10)).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(
                    DurableStateProjectionReconciler.FindingKind.PROJECTION_DRIFT);
            assertThat(finding.columns()).containsExactly("tenant_id");
            assertThat(finding.version()).isGreaterThan(successor.version());
        });
        DatabaseDurableStateProjectionControlPlane.FindingClaim current = controlPlane
                .claimFinding(key, "operator-c", Duration.ofMinutes(2))
                .orElseThrow();
        DatabaseDurableStateProjectionControlPlane.FindingClaim forged =
                new DatabaseDurableStateProjectionControlPlane.FindingClaim(
                        current.key(), current.ownerId(), "wrong-token", current.version(),
                        current.claimUntil());
        assertThat(controlPlane.resolveFinding(forged,
                DatabaseDurableStateProjectionControlPlane.Resolution.MANUALLY_REPAIRED))
                .isFalse();
        assertThat(controlPlane.resolveFinding(current,
                DatabaseDurableStateProjectionControlPlane.Resolution.MANUALLY_REPAIRED))
                .isTrue();
        assertThat(controlPlane.resolveFinding(current,
                DatabaseDurableStateProjectionControlPlane.Resolution.MANUALLY_REPAIRED))
                .isFalse();
        assertThat(controlPlane.actionableFindings(10)).isEmpty();
    }

    @Test
    void rollsBackProjectionRepairFindingAndCursorWhenPersistenceFails() throws Exception {
        Instant now = Instant.parse("2026-07-17T01:00:00Z");
        ExecutionInstance execution = execution("engine-rollback", now);
        insertExecution(execution, "COMPLETED", objectMapper.writeValueAsString(execution));
        AtomicBoolean failFindingWrite = new AtomicBoolean(true);
        JdbcTemplate failingJdbc = new JdbcTemplate(database.jdbc().getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO rg_test_bloge_projection_findings")
                        && failFindingWrite.getAndSet(false)) {
                    throw new IllegalStateException("injected finding persistence failure");
                }
                return super.update(sql, args);
            }
        };
        DatabaseDurableStateProjectionControlPlane controlPlane = controlPlane(
                failingJdbc, "replica-a");

        assertThatThrownBy(() -> controlPlane.reconcilePage(10,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected finding persistence failure");

        assertThat(database.jdbc().queryForObject("""
                SELECT execution_status FROM rg_test_bloge_executions WHERE execution_id = ?
                """, String.class, "engine-rollback")).isEqualTo("COMPLETED");
        assertThat(controlPlane.snapshot().cursor())
                .isEqualTo(DurableStateProjectionReconciler.ScanCursor.start());
        assertThat(controlPlane.snapshot().leaseOwner()).isBlank();
        assertThat(controlPlane.findings(10)).isEmpty();
    }

    @Test
    void onlyOneReplicaOwnsTheSweepAndAnExpiredFenceCannotCheckpoint() {
        DatabaseDurableStateProjectionControlPlane firstReplica = controlPlane(
                database.jdbc(), "replica-a");
        DatabaseDurableStateProjectionControlPlane secondReplica = controlPlane(
                database.jdbc(), "replica-b");
        DatabaseDurableStateProjectionControlPlane.SweepLease stale = firstReplica
                .acquireSweepLease().orElseThrow();

        assertThat(secondReplica.acquireSweepLease()).isEmpty();
        database.jdbc().update("""
                UPDATE rg_test_bloge_projection_sweep
                SET lease_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE job_name = 'bloge-scheduling-projection'
                """);
        DatabaseDurableStateProjectionControlPlane.SweepLease winner = secondReplica
                .acquireSweepLease().orElseThrow();

        assertThat(winner.epoch()).isGreaterThan(stale.epoch());
        assertThatThrownBy(() -> firstReplica.reconcileClaimedPage(stale, 10,
                DurableStateProjectionReconciler.RepairMode.AUDIT_ONLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fence");
        assertThat(secondReplica.snapshot().leaseOwner()).isEqualTo("replica-b");
    }

    @Test
    void aConsistentRecheckResolvesAnEarlierUnrepairableFinding() throws Exception {
        insertRawExecution("engine-recheck", "not-json");
        DatabaseDurableStateProjectionControlPlane controlPlane = controlPlane(
                database.jdbc(), "replica-a");
        controlPlane.reconcilePage(10,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);
        ExecutionInstance authority = execution("engine-recheck",
                Instant.parse("2026-07-17T01:00:00Z"));
        database.jdbc().update("""
                UPDATE rg_test_bloge_executions
                SET payload_json = ?, business_key = ?, graph_name = ?, shard_id = ?,
                    execution_status = ?, execution_version = ?, lease_until = ?, updated_at = ?
                WHERE execution_id = ?
                """, objectMapper.writeValueAsString(authority),
                authority.identity().businessKey(), authority.identity().graphName(),
                authority.identity().shardId(), authority.status().name(), authority.version(),
                Timestamp.from(authority.leaseUntil()), Timestamp.from(authority.updatedAt()),
                authority.identity().executionId());

        controlPlane.reconcilePage(10,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);

        assertThat(controlPlane.actionableFindings(10)).isEmpty();
        assertThat(controlPlane.findings(10)).singleElement().satisfies(finding -> {
            assertThat(finding.status()).isEqualTo(
                    DatabaseDurableStateProjectionControlPlane.FindingStatus.RESOLVED);
            assertThat(finding.resolution()).isEqualTo(
                    DatabaseDurableStateProjectionControlPlane.Resolution.CONSISTENT_ON_RECHECK);
            assertThat(finding.occurrences()).isEqualTo(1);
        });
    }

    private DatabaseDurableStateProjectionControlPlane controlPlane(
            JdbcTemplate jdbc, String ownerId) {
        DatabaseDurableStateProjectionControlPlane controlPlane =
                new DatabaseDurableStateProjectionControlPlane(
                        jdbc, database.transactionManager(), objectMapper, ownerId,
                        Duration.ofMinutes(2));
        controlPlane.init();
        return controlPlane;
    }

    private ExecutionInstance execution(String executionId, Instant now) {
        ExecutionIdentity identity = new ExecutionIdentity(
                "tenant-a", "test-runtime", "business-key", executionId,
                ExecutionType.GRAPH, "controlled-durable-state", "1",
                "sha256:" + "a".repeat(64), "route-a", "shard-a", null, "run-a");
        return ExecutionInstance.builder(identity)
                .status(ExecutionStatus.RUNNING)
                .leaseOwner("worker-a")
                .leaseToken("lease-a")
                .leaseUntil(now.minusSeconds(1))
                .version(3)
                .createdAt(now.minusSeconds(60))
                .updatedAt(now.minusSeconds(1))
                .build();
    }

    private void insertExecution(ExecutionInstance execution,
                                 String projectedStatus,
                                 String payloadJson) {
        database.jdbc().update("""
                INSERT INTO rg_test_bloge_executions (
                    execution_id, tenant_id, namespace, business_key, graph_name, shard_id,
                    execution_status, execution_version, lease_until, updated_at, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, execution.identity().executionId(), execution.identity().tenantId(),
                execution.identity().namespace(), execution.identity().businessKey(),
                execution.identity().graphName(), execution.identity().shardId(), projectedStatus,
                execution.version(), Timestamp.from(execution.leaseUntil()),
                Timestamp.from(execution.updatedAt()), payloadJson);
    }

    private void insertRawExecution(String executionId, String payloadJson) {
        Instant now = Instant.parse("2026-07-17T01:00:00Z");
        database.jdbc().update("""
                INSERT INTO rg_test_bloge_executions (
                    execution_id, tenant_id, namespace, business_key, graph_name, shard_id,
                    execution_status, execution_version, lease_until, updated_at, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, executionId, "tenant-a", "test-runtime", "business-key",
                "controlled-durable-state", "shard-a", ExecutionStatus.RUNNING.name(), 3,
                Timestamp.from(now.minusSeconds(1)), Timestamp.from(now.minusSeconds(1)),
                payloadJson);
    }
}
