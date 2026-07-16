package com.leanowtech.bloge.gateway.testing.persistence;

import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.work.WorkItem;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shared relational scheduling projections for hot-path verification and anti-entropy repair.
 *
 * <p>The JSON lifecycle/work-item value remains authoritative. These records only duplicate fields
 * needed for indexed scheduling and therefore deliberately expose comparison, not business-state
 * mutation.</p>
 */
final class BlogeSchedulingProjection {

    private static final Set<String> EXECUTION_SECURITY_BOUNDARY = Set.of(
            "execution_id", "tenant_id", "namespace");
    private static final Set<String> WORK_ITEM_SECURITY_BOUNDARY = Set.of(
            "item_id", "execution_id", "tenant_id", "namespace_id");

    private BlogeSchedulingProjection() {
    }

    /** Reads one execution scheduling projection and its opaque authority snapshot. */
    static ExecutionProjection execution(ResultSet resultSet) throws SQLException {
        return new ExecutionProjection(
                resultSet.getString("execution_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("namespace"),
                resultSet.getString("business_key"),
                resultSet.getString("graph_name"),
                resultSet.getString("shard_id"),
                resultSet.getString("execution_status"),
                resultSet.getLong("execution_version"),
                instant(resultSet, "lease_until"),
                instant(resultSet, "updated_at"),
                resultSet.getString("payload_json"));
    }

    /** Reads one work-item scheduling projection and its opaque authority snapshot. */
    static WorkItemProjection workItem(ResultSet resultSet) throws SQLException {
        return new WorkItemProjection(
                resultSet.getString("item_id"),
                resultSet.getString("execution_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("namespace_id"),
                resultSet.getString("item_type"),
                resultSet.getString("shard_id"),
                resultSet.getInt("priority"),
                resultSet.getString("item_status"),
                resultSet.getString("claim_owner"),
                instant(resultSet, "claim_until"),
                instant(resultSet, "next_attempt_at"),
                instant(resultSet, "created_at"),
                resultSet.getString("payload_json"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** Complete indexed projection for one authoritative execution snapshot. */
    record ExecutionProjection(
            String executionId,
            String tenantId,
            String namespace,
            String businessKey,
            String graphName,
            String shardId,
            String status,
            long version,
            Instant leaseUntil,
            Instant updatedAt,
            String payloadJson) {

        /** Returns deterministic database column names whose values disagree with authority. */
        List<String> drift(ExecutionInstance authority) {
            List<String> columns = new ArrayList<>();
            add(columns, "execution_id", executionId, authority.identity().executionId());
            add(columns, "tenant_id", tenantId, authority.identity().tenantId());
            add(columns, "namespace", namespace, authority.identity().namespace());
            add(columns, "business_key", businessKey, authority.identity().businessKey());
            add(columns, "graph_name", graphName, authority.identity().graphName());
            add(columns, "shard_id", shardId, authority.identity().shardId());
            add(columns, "execution_status", status, authority.status().name());
            if (version != authority.version()) {
                columns.add("execution_version");
            }
            add(columns, "lease_until", leaseUntil, authority.leaseUntil());
            add(columns, "updated_at", updatedAt, authority.updatedAt());
            return List.copyOf(columns);
        }

        /** Returns whether drift can be repaired without changing row identity or security scope. */
        boolean repairable(List<String> columns) {
            return columns.stream().noneMatch(EXECUTION_SECURITY_BOUNDARY::contains);
        }
    }

    /** Complete indexed projection for one authoritative work-item snapshot. */
    record WorkItemProjection(
            String itemId,
            String executionId,
            String tenantId,
            String namespace,
            String itemType,
            String shardId,
            int priority,
            String status,
            String claimOwner,
            Instant claimUntil,
            Instant nextAttemptAt,
            Instant createdAt,
            String payloadJson) {

        /** Returns deterministic database column names whose values disagree with authority. */
        List<String> drift(WorkItem authority) {
            List<String> columns = new ArrayList<>();
            add(columns, "item_id", itemId, authority.itemId());
            add(columns, "execution_id", executionId, authority.identity().executionId());
            add(columns, "tenant_id", tenantId, authority.identity().tenantId());
            add(columns, "namespace_id", namespace, authority.identity().namespace());
            add(columns, "item_type", itemType, authority.itemType().name());
            add(columns, "shard_id", shardId, authority.identity().shardId());
            if (priority != authority.priority()) {
                columns.add("priority");
            }
            add(columns, "item_status", status, authority.status().name());
            add(columns, "claim_owner", claimOwner, authority.claimOwner());
            add(columns, "claim_until", claimUntil, authority.claimUntil());
            add(columns, "next_attempt_at", nextAttemptAt, authority.nextAttemptAt());
            add(columns, "created_at", createdAt, authority.createdAt());
            return List.copyOf(columns);
        }

        /** Returns whether drift can be repaired without changing ownership or security scope. */
        boolean repairable(List<String> columns) {
            return columns.stream().noneMatch(WORK_ITEM_SECURITY_BOUNDARY::contains);
        }
    }

    private static void add(List<String> columns, String column, Object projected, Object authority) {
        if (!Objects.equals(projected, authority)) {
            columns.add(column);
        }
    }
}
