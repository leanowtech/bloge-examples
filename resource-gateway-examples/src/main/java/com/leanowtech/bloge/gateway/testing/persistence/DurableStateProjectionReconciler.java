package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.work.WorkItem;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bounded system-level anti-entropy scanner for BLOGE execution and work-item scheduling indexes.
 *
 * <p>The scanner walks committed rows by primary-key cursor rather than by the scheduling
 * projections under audit. It can therefore discover rows hidden by corrupted tenant, shard,
 * status, or time columns. JSON remains authoritative. {@link RepairMode#REPAIR_DERIVED} performs
 * a compare-and-set repair only when row identity, execution ownership, tenant, and namespace still
 * agree; security-boundary drift and unreadable authority are reported but never moved or guessed.</p>
 *
 * <p>This type is an internal maintenance primitive. It does not authorize a caller, expose
 * payloads, acquire work, or replace a durable multi-replica maintenance cursor.</p>
 */
public final class DurableStateProjectionReconciler {

    private static final int MAX_PAGE_SIZE = 1000;
    private static final String EXECUTION_SELECT = """
            SELECT execution_id, tenant_id, namespace, business_key, graph_name, shard_id,
                   execution_status, execution_version, lease_until, updated_at, payload_json
            FROM rg_test_bloge_executions
            """;
    private static final String WORK_ITEM_SELECT = """
            SELECT item_id, execution_id, tenant_id, namespace_id, item_type, shard_id,
                   priority, item_status, claim_owner, claim_until, next_attempt_at,
                   created_at, payload_json
            FROM rg_test_bloge_work_items
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Creates a scanner over the isolated test-runtime datasource.
     *
     * @param jdbc JDBC facade owning the committed BLOGE state tables
     * @param objectMapper mapper for authoritative lifecycle and work-item snapshots
     */
    public DurableStateProjectionReconciler(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Inspects one bounded page from each state table and optionally repairs safe derived drift.
     *
     * <p>A malformed row becomes an isolated finding; SQL/storage failure still escapes so the
     * scheduler can retain its previous cursor and retry. When a table reaches the end of its key
     * range, that component cursor resets to the beginning for the next sweep.</p>
     *
     * @param cursor independent execution and work-item keyset positions
     * @param pageSizePerEntity requested rows per table, normalized to 1..1000
     * @param repairMode audit-only or safe derived-projection repair
     * @return payload-free findings, counters, and the next independent cursors
     */
    public SweepResult sweep(ScanCursor cursor,
                             int pageSizePerEntity,
                             RepairMode repairMode) {
        ScanCursor current = cursor == null ? ScanCursor.start() : cursor;
        RepairMode mode = Objects.requireNonNull(repairMode, "repairMode");
        int pageSize = Math.max(1, Math.min(pageSizePerEntity, MAX_PAGE_SIZE));
        Page<BlogeSchedulingProjection.ExecutionProjection> executions =
                executionPage(current.afterExecutionId(), pageSize);
        Page<BlogeSchedulingProjection.WorkItemProjection> workItems =
                workItemPage(current.afterWorkItemId(), pageSize);
        Accumulator result = new Accumulator();
        executions.rows().forEach(row -> inspectExecution(row, mode, result));
        workItems.rows().forEach(row -> inspectWorkItem(row, mode, result));
        return result.result(new ScanCursor(executions.nextAfterId(), workItems.nextAfterId()));
    }

    private Page<BlogeSchedulingProjection.ExecutionProjection> executionPage(
            String afterId, int pageSize) {
        List<BlogeSchedulingProjection.ExecutionProjection> rows;
        if (afterId.isBlank()) {
            rows = jdbc.query(EXECUTION_SELECT + " ORDER BY execution_id LIMIT ?",
                    (resultSet, rowNumber) -> BlogeSchedulingProjection.execution(resultSet),
                    pageSize + 1);
        } else {
            rows = jdbc.query(EXECUTION_SELECT
                            + " WHERE execution_id > ? ORDER BY execution_id LIMIT ?",
                    (resultSet, rowNumber) -> BlogeSchedulingProjection.execution(resultSet),
                    afterId, pageSize + 1);
        }
        return page(rows, pageSize, BlogeSchedulingProjection.ExecutionProjection::executionId);
    }

    private Page<BlogeSchedulingProjection.WorkItemProjection> workItemPage(
            String afterId, int pageSize) {
        List<BlogeSchedulingProjection.WorkItemProjection> rows;
        if (afterId.isBlank()) {
            rows = jdbc.query(WORK_ITEM_SELECT + " ORDER BY item_id LIMIT ?",
                    (resultSet, rowNumber) -> BlogeSchedulingProjection.workItem(resultSet),
                    pageSize + 1);
        } else {
            rows = jdbc.query(WORK_ITEM_SELECT
                            + " WHERE item_id > ? ORDER BY item_id LIMIT ?",
                    (resultSet, rowNumber) -> BlogeSchedulingProjection.workItem(resultSet),
                    afterId, pageSize + 1);
        }
        return page(rows, pageSize, BlogeSchedulingProjection.WorkItemProjection::itemId);
    }

    private void inspectExecution(BlogeSchedulingProjection.ExecutionProjection projection,
                                  RepairMode mode,
                                  Accumulator accumulator) {
        accumulator.scanned++;
        accumulator.inspectedRows.add(new EntityKey(
                EntityType.EXECUTION, projection.executionId()));
        ExecutionInstance authority;
        try {
            authority = objectMapper.readValue(projection.payloadJson(), ExecutionInstance.class);
        } catch (JsonProcessingException unreadable) {
            accumulator.unreadable++;
            accumulator.findings.add(Finding.unreadable(
                    EntityType.EXECUTION, projection.executionId()));
            return;
        }
        List<String> drift = projection.drift(authority);
        if (drift.isEmpty()) {
            accumulator.consistent++;
            return;
        }
        accumulator.drifted++;
        boolean repairable = projection.repairable(drift);
        Outcome outcome = Outcome.DETECTED;
        if (repairable && mode == RepairMode.REPAIR_DERIVED) {
            if (repairExecution(projection, authority)) {
                accumulator.repaired++;
                outcome = Outcome.REPAIRED;
            } else {
                accumulator.raced++;
                outcome = Outcome.RACED;
            }
        }
        accumulator.findings.add(new Finding(
                EntityType.EXECUTION, projection.executionId(), FindingKind.PROJECTION_DRIFT,
                drift, repairable, outcome));
    }

    private void inspectWorkItem(BlogeSchedulingProjection.WorkItemProjection projection,
                                 RepairMode mode,
                                 Accumulator accumulator) {
        accumulator.scanned++;
        accumulator.inspectedRows.add(new EntityKey(
                EntityType.WORK_ITEM, projection.itemId()));
        WorkItem authority;
        try {
            authority = objectMapper.readValue(projection.payloadJson(), WorkItem.class);
        } catch (JsonProcessingException unreadable) {
            accumulator.unreadable++;
            accumulator.findings.add(Finding.unreadable(
                    EntityType.WORK_ITEM, projection.itemId()));
            return;
        }
        List<String> drift = projection.drift(authority);
        if (drift.isEmpty()) {
            accumulator.consistent++;
            return;
        }
        accumulator.drifted++;
        boolean repairable = projection.repairable(drift);
        Outcome outcome = Outcome.DETECTED;
        if (repairable && mode == RepairMode.REPAIR_DERIVED) {
            if (repairWorkItem(projection, authority)) {
                accumulator.repaired++;
                outcome = Outcome.REPAIRED;
            } else {
                accumulator.raced++;
                outcome = Outcome.RACED;
            }
        }
        accumulator.findings.add(new Finding(
                EntityType.WORK_ITEM, projection.itemId(), FindingKind.PROJECTION_DRIFT,
                drift, repairable, outcome));
    }

    private boolean repairExecution(
            BlogeSchedulingProjection.ExecutionProjection projection,
            ExecutionInstance authority) {
        int changed = jdbc.update("""
                UPDATE rg_test_bloge_executions
                SET business_key = ?, graph_name = ?, shard_id = ?, execution_status = ?,
                    execution_version = ?, lease_until = ?, updated_at = ?
                WHERE execution_id = ? AND tenant_id = ? AND namespace = ? AND payload_json = ?
                """, authority.identity().businessKey(), authority.identity().graphName(),
                authority.identity().shardId(), authority.status().name(), authority.version(),
                timestamp(authority.leaseUntil()), Timestamp.from(authority.updatedAt()),
                projection.executionId(), projection.tenantId(), projection.namespace(),
                projection.payloadJson());
        return changed == 1;
    }

    private boolean repairWorkItem(BlogeSchedulingProjection.WorkItemProjection projection,
                                   WorkItem authority) {
        int changed = jdbc.update("""
                UPDATE rg_test_bloge_work_items
                SET item_type = ?, shard_id = ?, priority = ?, item_status = ?, claim_owner = ?,
                    claim_until = ?, next_attempt_at = ?, created_at = ?
                WHERE item_id = ? AND execution_id = ? AND tenant_id = ? AND namespace_id = ?
                  AND payload_json = ?
                """, authority.itemType().name(), authority.identity().shardId(),
                authority.priority(), authority.status().name(), authority.claimOwner(),
                timestamp(authority.claimUntil()), timestamp(authority.nextAttemptAt()),
                Timestamp.from(authority.createdAt()), projection.itemId(), projection.executionId(),
                projection.tenantId(), projection.namespace(), projection.payloadJson());
        return changed == 1;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static <T> Page<T> page(List<T> fetched,
                                    int pageSize,
                                    java.util.function.Function<T, String> id) {
        boolean hasMore = fetched.size() > pageSize;
        List<T> rows = hasMore ? List.copyOf(fetched.subList(0, pageSize)) : List.copyOf(fetched);
        String next = hasMore ? id.apply(rows.getLast()) : "";
        return new Page<>(rows, next);
    }

    /** Controls whether a sweep observes drift or also repairs safe derived columns. */
    public enum RepairMode {
        /** Detects and reports drift without changing any projection. */
        AUDIT_ONLY,
        /** Repairs derived drift only when identity, scope, ownership, and authority still match. */
        REPAIR_DERIVED;

        /**
         * Parses a configuration value without silently weakening an invalid repair policy.
         *
         * @param value configured enum name
         * @return parsed repair mode
         */
        public static RepairMode parse(String value) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("Projection reconciliation mode is required");
            }
            return valueOf(normalized);
        }
    }

    /** State table represented by a finding. */
    public enum EntityType {
        /** BLOGE execution lifecycle and lease projection. */
        EXECUTION,
        /** BLOGE asynchronous work-item scheduling projection. */
        WORK_ITEM
    }

    /** Stable finding classification that never includes authority payload. */
    public enum FindingKind {
        /** One or more readable authority fields disagree with indexed relational columns. */
        PROJECTION_DRIFT,
        /** The committed authority JSON cannot be decoded and therefore cannot be repaired. */
        AUTHORITY_UNREADABLE
    }

    /** Result of processing one detected finding. */
    public enum Outcome {
        /** Drift was observed but no mutation was attempted. */
        DETECTED,
        /** A safe authority-snapshot compare-and-set rebuilt the derived projection. */
        REPAIRED,
        /** The authority snapshot changed before repair and the stale update was rejected. */
        RACED
    }

    /**
     * Payload-free identity of one committed authority row inspected by a sweep.
     *
     * @param entityType execution or work-item authority table
     * @param rowId committed primary key, never a business payload
     */
    public record EntityKey(EntityType entityType, String rowId) {
        /** Requires a complete, non-blank internal row identity. */
        public EntityKey {
            entityType = Objects.requireNonNull(entityType, "entityType");
            rowId = Objects.requireNonNull(rowId, "rowId").trim();
            if (rowId.isBlank()) {
                throw new IllegalArgumentException("Projection entity row ID is required");
            }
        }
    }

    /**
     * Independent keyset positions for the two authority tables.
     *
     * @param afterExecutionId last execution primary key from the previous partial page
     * @param afterWorkItemId last work-item primary key from the previous partial page
     */
    public record ScanCursor(String afterExecutionId, String afterWorkItemId) {
        /** Normalizes null or blank component positions to the start of that table. */
        public ScanCursor {
            afterExecutionId = normalized(afterExecutionId);
            afterWorkItemId = normalized(afterWorkItemId);
        }

        /**
         * Creates a cursor positioned before the first row of both tables.
         *
         * @return cursor that starts both table scans at their first primary key
         */
        public static ScanCursor start() {
            return new ScanCursor("", "");
        }

        private static String normalized(String value) {
            return value == null || value.isBlank() ? "" : value;
        }
    }

    /**
     * Payload-free discrepancy emitted by one sweep.
     *
     * @param entityType affected authority table
     * @param rowId committed row primary key
     * @param kind projection mismatch or unreadable authority
     * @param columns deterministic mismatched column names, never values
     * @param repairable whether automatic repair preserves identity and security scope
     * @param outcome detection, successful repair, or compare-and-set race
     */
    public record Finding(
            EntityType entityType,
            String rowId,
            FindingKind kind,
            List<String> columns,
            boolean repairable,
            Outcome outcome) {
        /** Copies mutable column collections and requires complete classification metadata. */
        public Finding {
            entityType = Objects.requireNonNull(entityType, "entityType");
            rowId = Objects.requireNonNull(rowId, "rowId");
            kind = Objects.requireNonNull(kind, "kind");
            columns = columns == null ? List.of() : List.copyOf(columns);
            outcome = Objects.requireNonNull(outcome, "outcome");
        }

        private static Finding unreadable(EntityType entityType, String rowId) {
            return new Finding(entityType, rowId, FindingKind.AUTHORITY_UNREADABLE,
                    List.of(), false, Outcome.DETECTED);
        }
    }

    /**
     * Aggregate result for one bounded two-table sweep.
     *
     * @param nextCursor next independent keyset positions; blank components restart at table head
     * @param scanned total rows decoded or attempted
     * @param consistent rows whose projection matched authority
     * @param drifted readable rows with one or more mismatched columns
     * @param unreadable rows whose authority JSON could not be decoded
     * @param repaired drifted rows repaired by compare-and-set
     * @param raced repair attempts rejected after concurrent state change
     * @param findings payload-free discrepancies in deterministic table/key order
     * @param inspectedRows payload-free row identities inspected by this page, including rows that
     *                      are now consistent
     */
    public record SweepResult(
            ScanCursor nextCursor,
            int scanned,
            int consistent,
            int drifted,
            int unreadable,
            int repaired,
            int raced,
            List<Finding> findings,
            List<EntityKey> inspectedRows) {
        /** Copies result collections and requires a concrete continuation cursor. */
        public SweepResult {
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
            findings = findings == null ? List.of() : List.copyOf(findings);
            inspectedRows = inspectedRows == null ? List.of() : List.copyOf(inspectedRows);
        }
    }

    private record Page<T>(List<T> rows, String nextAfterId) {
    }

    private static final class Accumulator {
        private int scanned;
        private int consistent;
        private int drifted;
        private int unreadable;
        private int repaired;
        private int raced;
        private final List<Finding> findings = new ArrayList<>();
        private final List<EntityKey> inspectedRows = new ArrayList<>();

        private SweepResult result(ScanCursor cursor) {
            return new SweepResult(cursor, scanned, consistent, drifted, unreadable,
                    repaired, raced, findings, inspectedRows);
        }
    }
}
