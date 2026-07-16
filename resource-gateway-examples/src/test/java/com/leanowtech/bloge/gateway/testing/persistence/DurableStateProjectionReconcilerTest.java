package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import com.leanowtech.bloge.core.runtime.work.WorkItem;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DurableStateProjectionReconcilerTest {

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;
    private StagedBlogeDurableStateStore stateStore;
    private DurableStateProjectionReconciler reconciler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:durable-projection-reconciler-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1",
                "sa", "", 4));
        stateStore = new StagedBlogeDurableStateStore(database.jdbc(), objectMapper);
        stateStore.init();
        reconciler = stateStore.projectionReconciler();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void repairsDerivedDriftAndReturnsPreviouslyHiddenCandidatesToHotScans() throws Exception {
        Instant cutoff = Instant.parse("2026-07-17T01:00:00Z");
        ExecutionInstance execution = execution("engine-repair", "tenant-a", "shard-a", cutoff);
        WorkItem item = workItem("item-repair", execution.identity(), cutoff);
        insertExecution(execution, "COMPLETED", "shard-hidden",
                objectMapper.writeValueAsString(execution));
        insertWorkItem(item, "DONE", "shard-hidden", 1,
                objectMapper.writeValueAsString(item));

        DurableStateProjectionReconciler.SweepResult result = reconciler.sweep(
                DurableStateProjectionReconciler.ScanCursor.start(), 10,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.drifted()).isEqualTo(2);
        assertThat(result.repaired()).isEqualTo(2);
        assertThat(result.unreadable()).isZero();
        assertThat(result.inspectedRows())
                .extracting(DurableStateProjectionReconciler.EntityKey::rowId)
                .containsExactly("engine-repair", "item-repair");
        assertThat(result.findings())
                .allSatisfy(finding -> {
                    assertThat(finding.repairable()).isTrue();
                    assertThat(finding.outcome())
                            .isEqualTo(DurableStateProjectionReconciler.Outcome.REPAIRED);
                });
        assertThat(result.findings()).filteredOn(finding ->
                        finding.entityType()
                                == DurableStateProjectionReconciler.EntityType.EXECUTION)
                .singleElement().satisfies(finding -> assertThat(finding.columns())
                        .containsExactly("shard_id", "execution_status"));
        assertThat(result.findings()).filteredOn(finding ->
                        finding.entityType()
                                == DurableStateProjectionReconciler.EntityType.WORK_ITEM)
                .singleElement().satisfies(finding -> assertThat(finding.columns())
                        .containsExactly("shard_id", "priority", "item_status"));
        assertThat(stateStore.executionStore().findExpiredClaims(cutoff, 10, "shard-a"))
                .extracting(value -> value.identity().executionId())
                .containsExactly("engine-repair");
        assertThat(stateStore.workItemStore().pollReady(
                WorkItemType.TIMER_DUE, "shard-a", 10))
                .extracting(WorkItem::itemId)
                .containsExactly("item-repair");
    }

    @Test
    void auditOnlyReportsRepairableDriftWithoutMutatingTheProjection() throws Exception {
        Instant cutoff = Instant.parse("2026-07-17T01:00:00Z");
        ExecutionInstance execution = execution("engine-audit", "tenant-a", "shard-a", cutoff);
        insertExecution(execution, "COMPLETED", "shard-a",
                objectMapper.writeValueAsString(execution));

        DurableStateProjectionReconciler.SweepResult result = reconciler.sweep(
                DurableStateProjectionReconciler.ScanCursor.start(), 10,
                DurableStateProjectionReconciler.RepairMode.AUDIT_ONLY);

        assertThat(result.drifted()).isEqualTo(1);
        assertThat(result.repaired()).isZero();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.repairable()).isTrue();
            assertThat(finding.outcome())
                    .isEqualTo(DurableStateProjectionReconciler.Outcome.DETECTED);
        });
        assertThat(database.jdbc().queryForObject("""
                SELECT execution_status FROM rg_test_bloge_executions WHERE execution_id = ?
                """, String.class, "engine-audit")).isEqualTo("COMPLETED");
        assertThat(stateStore.executionStore().findExpiredClaims(cutoff, 10, "shard-a")).isEmpty();
    }

    @Test
    void refusesSecurityScopeRepairAndContinuesAfterUnreadableAuthority() throws Exception {
        Instant cutoff = Instant.parse("2026-07-17T01:00:00Z");
        ExecutionInstance execution = execution("engine-scope", "tenant-a", "shard-a", cutoff);
        WorkItem item = workItem("item-scope", execution.identity(), cutoff);
        insertExecution(execution, "RUNNING", "shard-a", "not-json");
        insertWorkItem(item, "READY", "shard-a", item.priority(),
                objectMapper.writeValueAsString(item));
        database.jdbc().update("""
                UPDATE rg_test_bloge_work_items SET tenant_id = ? WHERE item_id = ?
                """, "tenant-b", item.itemId());

        DurableStateProjectionReconciler.SweepResult result = reconciler.sweep(
                DurableStateProjectionReconciler.ScanCursor.start(), 10,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.unreadable()).isEqualTo(1);
        assertThat(result.drifted()).isEqualTo(1);
        assertThat(result.repaired()).isZero();
        assertThat(result.findings()).filteredOn(finding ->
                        finding.kind()
                                == DurableStateProjectionReconciler.FindingKind.AUTHORITY_UNREADABLE)
                .singleElement().satisfies(finding -> {
                    assertThat(finding.entityType())
                            .isEqualTo(DurableStateProjectionReconciler.EntityType.EXECUTION);
                    assertThat(finding.columns()).isEmpty();
                    assertThat(finding.repairable()).isFalse();
                });
        assertThat(result.findings()).filteredOn(finding ->
                        finding.kind()
                                == DurableStateProjectionReconciler.FindingKind.PROJECTION_DRIFT)
                .singleElement().satisfies(finding -> {
                    assertThat(finding.columns()).containsExactly("tenant_id");
                    assertThat(finding.repairable()).isFalse();
                    assertThat(finding.outcome())
                            .isEqualTo(DurableStateProjectionReconciler.Outcome.DETECTED);
                });
        assertThat(database.jdbc().queryForObject("""
                SELECT tenant_id FROM rg_test_bloge_work_items WHERE item_id = ?
                """, String.class, item.itemId())).isEqualTo("tenant-b");
    }

    @Test
    void rejectsAStaleRepairWhenAuthorityChangesAfterTheScan() throws Exception {
        Instant cutoff = Instant.parse("2026-07-17T01:00:00Z");
        ExecutionInstance execution = execution("engine-raced", "tenant-a", "shard-a", cutoff);
        String payload = objectMapper.writeValueAsString(execution);
        insertExecution(execution, "COMPLETED", "shard-a", payload);
        AtomicBoolean authorityChanged = new AtomicBoolean();
        JdbcTemplate racingJdbc = new JdbcTemplate(database.jdbc().getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("UPDATE rg_test_bloge_executions")
                        && authorityChanged.compareAndSet(false, true)) {
                    database.jdbc().update("""
                            UPDATE rg_test_bloge_executions
                            SET payload_json = ? WHERE execution_id = ?
                            """, payload + " ", execution.identity().executionId());
                }
                return super.update(sql, args);
            }
        };
        DurableStateProjectionReconciler racing =
                new DurableStateProjectionReconciler(racingJdbc, objectMapper);

        DurableStateProjectionReconciler.SweepResult result = racing.sweep(
                DurableStateProjectionReconciler.ScanCursor.start(), 10,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);

        assertThat(result.drifted()).isEqualTo(1);
        assertThat(result.repaired()).isZero();
        assertThat(result.raced()).isEqualTo(1);
        assertThat(result.findings()).singleElement().satisfies(finding ->
                assertThat(finding.outcome())
                        .isEqualTo(DurableStateProjectionReconciler.Outcome.RACED));
        assertThat(database.jdbc().queryForObject("""
                SELECT execution_status FROM rg_test_bloge_executions WHERE execution_id = ?
                """, String.class, execution.identity().executionId())).isEqualTo("COMPLETED");
    }

    @Test
    void advancesIndependentBoundedCursorsPastPoisonRows() {
        Instant now = Instant.parse("2026-07-17T01:00:00Z");
        insertRawExecution("engine-1", "tenant-a", "shard-a", "RUNNING", now, "not-json");
        insertRawExecution("engine-2", "tenant-a", "shard-a", "RUNNING", now, "not-json");
        insertRawWorkItem("item-1", "engine-1", "tenant-a", "shard-a",
                "READY", 50, now, "not-json");
        insertRawWorkItem("item-2", "engine-2", "tenant-a", "shard-a",
                "READY", 50, now, "not-json");

        DurableStateProjectionReconciler.SweepResult first = reconciler.sweep(
                DurableStateProjectionReconciler.ScanCursor.start(), 1,
                DurableStateProjectionReconciler.RepairMode.AUDIT_ONLY);
        DurableStateProjectionReconciler.SweepResult second = reconciler.sweep(
                first.nextCursor(), 1, DurableStateProjectionReconciler.RepairMode.AUDIT_ONLY);

        assertThat(first.scanned()).isEqualTo(2);
        assertThat(first.nextCursor().afterExecutionId()).isEqualTo("engine-1");
        assertThat(first.nextCursor().afterWorkItemId()).isEqualTo("item-1");
        assertThat(second.scanned()).isEqualTo(2);
        assertThat(second.findings()).extracting(DurableStateProjectionReconciler.Finding::rowId)
                .containsExactly("engine-2", "item-2");
        assertThat(second.nextCursor()).isEqualTo(
                DurableStateProjectionReconciler.ScanCursor.start());
    }

    private ExecutionInstance execution(String executionId,
                                        String tenantId,
                                        String shardId,
                                        Instant cutoff) {
        ExecutionIdentity identity = new ExecutionIdentity(
                tenantId, "test-runtime", "business-key", executionId,
                ExecutionType.GRAPH, "controlled-durable-state", "1",
                "sha256:" + "a".repeat(64), "route-a", shardId, null, "run-a");
        return ExecutionInstance.builder(identity)
                .status(ExecutionStatus.RUNNING)
                .leaseOwner("worker-a")
                .leaseToken("lease-a")
                .leaseUntil(cutoff.minusSeconds(1))
                .version(3)
                .createdAt(cutoff.minusSeconds(60))
                .updatedAt(cutoff.minusSeconds(1))
                .build();
    }

    private WorkItem workItem(String itemId, ExecutionIdentity identity, Instant cutoff) {
        return WorkItem.builder()
                .itemId(itemId)
                .executionIdentity(identity)
                .itemType(WorkItemType.TIMER_DUE)
                .nodeId("approval")
                .waitId("wait-approval")
                .priority(50)
                .status(WorkItemStatus.READY)
                .maxRetries(3)
                .createdAt(cutoff.minusSeconds(30))
                .updatedAt(cutoff.minusSeconds(30))
                .build();
    }

    private void insertExecution(ExecutionInstance execution,
                                 String projectedStatus,
                                 String projectedShard,
                                 String payloadJson) {
        insertRawExecution(
                execution.identity().executionId(), execution.identity().tenantId(), projectedShard,
                projectedStatus, execution.leaseUntil(), payloadJson);
    }

    private void insertRawExecution(String executionId,
                                    String tenantId,
                                    String shardId,
                                    String status,
                                    Instant updatedAt,
                                    String payloadJson) {
        database.jdbc().update("""
                INSERT INTO rg_test_bloge_executions (
                    execution_id, tenant_id, namespace, business_key, graph_name, shard_id,
                    execution_status, execution_version, lease_until, updated_at, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, executionId, tenantId, "test-runtime", "business-key",
                "controlled-durable-state", shardId, status, 3,
                Timestamp.from(updatedAt), Timestamp.from(updatedAt), payloadJson);
    }

    private void insertWorkItem(WorkItem item,
                                String projectedStatus,
                                String projectedShard,
                                int projectedPriority,
                                String payloadJson) {
        insertRawWorkItem(
                item.itemId(), item.identity().executionId(), item.identity().tenantId(),
                projectedShard, projectedStatus, projectedPriority, item.createdAt(), payloadJson);
    }

    private void insertRawWorkItem(String itemId,
                                   String executionId,
                                   String tenantId,
                                   String shardId,
                                   String status,
                                   int priority,
                                   Instant createdAt,
                                   String payloadJson) {
        database.jdbc().update("""
                INSERT INTO rg_test_bloge_work_items (
                    item_id, execution_id, tenant_id, namespace_id, item_type, shard_id,
                    priority, item_status, claim_owner, claim_until, next_attempt_at,
                    created_at, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, itemId, executionId, tenantId, "test-runtime", WorkItemType.TIMER_DUE.name(),
                shardId, priority, status, null, null, null, Timestamp.from(createdAt), payloadJson);
    }
}
