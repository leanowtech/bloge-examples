package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.runtime.execution.ExecutionInstance;
import com.leanowtech.bloge.core.runtime.execution.ExecutionQuery;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.store.TenantStoreSupport;
import com.leanowtech.bloge.durable.store.memory.InMemoryExecutionStore;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Execution lifecycle store whose writes participate in the durable-test composite transaction.
 *
 * <p>Each execution must own an active {@link Stage}. The stage starts from the last committed
 * {@link ExecutionInstance}, delegates BLOGE lifecycle and lease semantics to its proven in-memory
 * store implementation, and keeps the resulting row process-local. {@link Stage#prepare} freezes
 * that row into a content-addressed, idempotently replayable mutation. Only the control-plane
 * repository may apply it, through the same transaction-bound JDBC facade used for the durable
 * test checkpoint.</p>
 *
 * <p>Global recovery and operations queries intentionally read committed rows only. An
 * uncommitted execution is private to its exact execution-scoped stage and therefore cannot be
 * claimed by another local worker or exposed by an operations scan.</p>
 */
public final class StagedBlogeExecutionStore implements ExecutionStore {

    private static final String CLOSURE_SCHEMA_VERSION = "bloge.testExecutionMutation.v1";
    private static final String HOT_ARCHIVE_STATE = "HOT";
    private static final String RECOVERY_PROJECTION_SELECT = """
            SELECT execution_id, tenant_id, namespace, business_key, graph_name, shard_id,
                   execution_status, execution_version, lease_until, updated_at, payload_json
            FROM rg_test_bloge_executions
            """;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, StagingArea> activeStages = new ConcurrentHashMap<>();

    /**
     * Creates an execution store backed by the test-runtime transaction datasource.
     *
     * @param jdbc transaction-capable test-runtime JDBC facade
     * @param objectMapper mapper used for immutable execution rows and closure fingerprints
     */
    public StagedBlogeExecutionStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Creates the isolated execution lifecycle table and its recovery indexes. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_bloge_executions (
                    execution_id VARCHAR(255) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    namespace VARCHAR(255) NOT NULL,
                    business_key VARCHAR(255),
                    graph_name VARCHAR(255),
                    shard_id VARCHAR(255),
                    execution_status VARCHAR(64) NOT NULL,
                    execution_version BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    payload_json CLOB NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_execution_recovery_idx
                ON rg_test_bloge_executions (execution_status, lease_until, shard_id)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_execution_scope_idx
                ON rg_test_bloge_executions (tenant_id, namespace, updated_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_execution_tenant_recovery_idx
                ON rg_test_bloge_executions (
                    tenant_id, namespace, execution_status, lease_until, shard_id, execution_id
                )
                """);
    }

    /**
     * Opens the only local lifecycle stage for an execution and seeds it from committed state.
     *
     * @param executionId trusted BLOGE execution identifier
     * @param timeSource run-scoped logical clock used by lifecycle and lease transitions
     * @return closeable execution stage
     */
    public Stage begin(String executionId, TimeSource timeSource) {
        String normalized = required(executionId, "executionId");
        StagingArea area = new StagingArea(normalized,
                Objects.requireNonNull(timeSource, "timeSource"), loadCommitted(normalized));
        if (activeStages.putIfAbsent(normalized, area) != null) {
            throw durability("A composite execution stage is already active for " + normalized);
        }
        return new Stage(area);
    }

    @Override
    public void create(ExecutionInstance executionInstance) {
        ExecutionInstance requiredInstance = Objects.requireNonNull(executionInstance, "executionInstance");
        stagingArea(requiredInstance.identity().executionId()).mutate(store -> {
            store.create(requiredInstance);
            return null;
        });
    }

    @Override
    public Optional<ExecutionInstance> get(String executionId) {
        String normalized = required(executionId, "executionId");
        StagingArea area = activeStages.get(normalized);
        return area == null ? loadCommitted(normalized) : area.get();
    }

    @Override
    public Optional<ExecutionInstance> findByIdempotencyKey(
            String executionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return get(executionId)
                .filter(execution -> idempotencyKey.equals(execution.lastSignalIdempotencyKey()));
    }

    @Override
    public OptionalLong getLeaseEpoch(String executionId) {
        return get(executionId).stream().mapToLong(ExecutionInstance::leaseEpoch).findFirst();
    }

    @Override
    public void updateStatus(String executionId, ExecutionStatus status, long expectedVersion) {
        stagingArea(executionId).mutate(store -> {
            store.updateStatus(executionId, status, expectedVersion);
            return null;
        });
    }

    @Override
    public void updateStatus(String executionId, ExecutionStatus status,
                             long expectedVersion, String reason) {
        updateStatus(executionId, status, expectedVersion);
    }

    @Override
    public void recordSignalIdempotencyKey(
            String executionId, String idempotencyKey, long expectedVersion) {
        stagingArea(executionId).mutate(store -> {
            store.recordSignalIdempotencyKey(executionId, idempotencyKey, expectedVersion);
            return null;
        });
    }

    @Override
    public Optional<ExecutionInstance> claimExecution(
            String executionId, String owner, Duration leaseDuration, long expectedVersion) {
        return stagingArea(executionId).mutate(store ->
                store.claimExecution(executionId, owner, leaseDuration, expectedVersion));
    }

    @Override
    public Optional<ExecutionInstance> renewLease(
            String executionId, String leaseToken, Duration extension) {
        return stagingArea(executionId).mutate(store ->
                store.renewLease(executionId, leaseToken, extension));
    }

    @Override
    public boolean releaseExecution(String executionId, String leaseToken) {
        return stagingArea(executionId).mutate(store ->
                store.releaseExecution(executionId, leaseToken));
    }

    @Override
    public List<ExecutionInstance> findExpiredClaims(Instant cutoff, int limit) {
        return findExpiredClaims(cutoff, limit, null);
    }

    /**
     * Returns a bounded, deterministic page of committed running executions with expired leases.
     *
     * <p>Tenant scope, optional shard, lifecycle status, cutoff, order, and limit are pushed into
     * SQL. Every selected recovery projection is then compared with authoritative JSON, including
     * proof that the execution still carries a lease token.</p>
     *
     * @param cutoff inclusive lease-expiry cutoff
     * @param limit requested page size; non-positive means 100 and values above 10,000 are capped
     * @param shardId optional worker shard; blank values mean all shards
     * @return verified expired executions ordered by lease time and execution id
     */
    @Override
    public List<ExecutionInstance> findExpiredClaims(Instant cutoff, int limit, String shardId) {
        Instant requiredCutoff = Objects.requireNonNull(cutoff, "cutoff");
        String normalizedShard = normalized(shardId);
        StringBuilder sql = new StringBuilder(RECOVERY_PROJECTION_SELECT)
                .append(" WHERE execution_status = 'RUNNING'")
                .append(" AND lease_until IS NOT NULL AND lease_until <= ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(Timestamp.from(requiredCutoff));
        TenantStoreSupport.currentTenant().ifPresent(tenant -> {
            sql.append(" AND tenant_id = ? AND namespace = ?");
            parameters.add(tenant.tenantId());
            parameters.add(tenant.namespace());
        });
        if (normalizedShard != null) {
            sql.append(" AND shard_id = ?");
            parameters.add(normalizedShard);
        }
        sql.append(" ORDER BY lease_until, execution_id LIMIT ?");
        parameters.add(normalizeLimit(limit));
        return jdbc.query(sql.toString(), this::mapVerifiedRecoveryProjection,
                parameters.toArray());
    }

    @Override
    public void incrementRecoveryAttempt(String executionId) {
        stagingArea(executionId).mutate(store -> {
            store.incrementRecoveryAttempt(executionId);
            return null;
        });
    }

    @Override
    public void resetRecoveryAttempts(String executionId) {
        stagingArea(executionId).mutate(store -> {
            store.resetRecoveryAttempts(executionId);
            return null;
        });
    }

    @Override
    public List<ExecutionInstance> queryExecutions(ExecutionQuery query) {
        List<ExecutionInstance> matching = filtered(Objects.requireNonNull(query, "query"));
        long requestedFrom = (long) query.page() * query.size();
        int from = (int) Math.min(requestedFrom, matching.size());
        int to = (int) Math.min(requestedFrom + query.size(), matching.size());
        return List.copyOf(matching.subList(from, to));
    }

    @Override
    public long countExecutions(ExecutionQuery query) {
        return filtered(Objects.requireNonNull(query, "query")).size();
    }

    @Override
    public Map<ExecutionStatus, Long> countExecutionsByStatus(ExecutionQuery query) {
        Map<ExecutionStatus, Long> counts = new EnumMap<>(ExecutionStatus.class);
        filtered(Objects.requireNonNull(query, "query")).forEach(execution ->
                counts.merge(execution.status(), 1L, Long::sum));
        return Map.copyOf(counts);
    }

    @Override
    public Optional<ExecutionInstance> findByBusinessKey(String tenantId, String businessKey) {
        return committedRows().stream()
                .filter(execution -> HOT_ARCHIVE_STATE.equals(execution.archiveState()))
                .filter(execution -> Objects.equals(tenantId, execution.identity().tenantId()))
                .filter(execution -> Objects.equals(businessKey, execution.identity().businessKey()))
                .sorted(Comparator.comparing(ExecutionInstance::updatedAt).reversed())
                .findFirst();
    }

    @Override
    public void delete(String executionId) {
        stagingArea(executionId).mutate(store -> {
            store.delete(executionId);
            return null;
        });
    }

    private List<ExecutionInstance> filtered(ExecutionQuery query) {
        return committedRows().stream()
                .filter(execution -> HOT_ARCHIVE_STATE.equals(execution.archiveState()))
                .filter(execution -> Objects.equals(query.tenantId(), execution.identity().tenantId()))
                .filter(execution -> Objects.equals(query.namespace(), execution.identity().namespace()))
                .filter(execution -> query.businessKey() == null
                        || Objects.equals(query.businessKey(), execution.identity().businessKey()))
                .filter(execution -> query.statuses().isEmpty()
                        || query.statuses().contains(execution.status()))
                .filter(execution -> query.graphName() == null
                        || Objects.equals(query.graphName(), execution.identity().graphName()))
                .filter(execution -> query.shardId() == null
                        || Objects.equals(query.shardId(), execution.identity().shardId()))
                .filter(execution -> query.createdAfter() == null
                        || !execution.createdAt().isBefore(query.createdAfter()))
                .filter(execution -> query.createdBefore() == null
                        || !execution.createdAt().isAfter(query.createdBefore()))
                .filter(execution -> query.updatedAfter() == null
                        || !execution.updatedAt().isBefore(query.updatedAfter()))
                .filter(execution -> query.updatedBefore() == null
                        || !execution.updatedAt().isAfter(query.updatedBefore()))
                .sorted(Comparator.comparing(ExecutionInstance::updatedAt).reversed())
                .toList();
    }

    private Optional<ExecutionInstance> loadCommitted(String executionId) {
        return jdbc.query("""
                        SELECT payload_json FROM rg_test_bloge_executions WHERE execution_id = ?
                        """, (resultSet, rowNumber) -> read(resultSet.getString("payload_json")),
                executionId).stream().findFirst()
                .filter(execution -> TenantStoreSupport.matchesCurrentTenant(execution.identity()));
    }

    private List<ExecutionInstance> committedRows() {
        return jdbc.query("SELECT payload_json FROM rg_test_bloge_executions",
                (resultSet, rowNumber) -> read(resultSet.getString("payload_json")));
    }

    private ExecutionInstance mapVerifiedRecoveryProjection(ResultSet resultSet, int rowNumber)
            throws SQLException {
        ExecutionInstance execution = read(resultSet.getString("payload_json"));
        if (!Objects.equals(resultSet.getString("execution_id"),
                    execution.identity().executionId())
                || !Objects.equals(resultSet.getString("tenant_id"),
                        execution.identity().tenantId())
                || !Objects.equals(resultSet.getString("namespace"),
                        execution.identity().namespace())
                || !Objects.equals(resultSet.getString("business_key"),
                        execution.identity().businessKey())
                || !Objects.equals(resultSet.getString("graph_name"),
                        execution.identity().graphName())
                || !Objects.equals(resultSet.getString("shard_id"),
                        execution.identity().shardId())
                || !Objects.equals(resultSet.getString("execution_status"),
                        execution.status().name())
                || resultSet.getLong("execution_version") != execution.version()
                || !Objects.equals(instant(resultSet, "lease_until"), execution.leaseUntil())
                || !Objects.equals(instant(resultSet, "updated_at"), execution.updatedAt())
                || execution.leaseToken() == null) {
            throw durability("Stored BLOGE execution recovery projection is corrupt");
        }
        return execution;
    }

    private ExecutionInstance read(String json) {
        try {
            return objectMapper.readValue(json, ExecutionInstance.class);
        } catch (JsonProcessingException corrupt) {
            throw durability("Stored BLOGE execution lifecycle is corrupt", corrupt);
        }
    }

    private String write(ExecutionInstance execution) {
        try {
            return objectMapper.writeValueAsString(execution);
        } catch (JsonProcessingException failure) {
            throw durability("Failed to serialize BLOGE execution lifecycle", failure);
        }
    }

    private StagingArea stagingArea(String executionId) {
        String normalized = required(executionId, "executionId");
        StagingArea area = activeStages.get(normalized);
        if (area == null) {
            throw durability("BLOGE execution mutation requires an active composite stage for "
                    + normalized);
        }
        return area;
    }

    private void apply(JdbcTemplate transactionJdbc, PendingSnapshot snapshot) {
        requireTransactionParticipant(transactionJdbc);
        if (!snapshot.mutated()) {
            return;
        }
        if (snapshot.execution().isEmpty()) {
            transactionJdbc.update(
                    "DELETE FROM rg_test_bloge_executions WHERE execution_id = ?",
                    snapshot.executionId());
            return;
        }
        ExecutionInstance execution = snapshot.execution().orElseThrow();
        int changed = transactionJdbc.update("""
                UPDATE rg_test_bloge_executions
                SET tenant_id = ?, namespace = ?, business_key = ?, graph_name = ?, shard_id = ?,
                    execution_status = ?, execution_version = ?, lease_until = ?, updated_at = ?,
                    payload_json = ?
                WHERE execution_id = ?
                """, execution.identity().tenantId(), execution.identity().namespace(),
                execution.identity().businessKey(), execution.identity().graphName(),
                execution.identity().shardId(), execution.status().name(), execution.version(),
                execution.leaseUntil(), execution.updatedAt(), write(execution), snapshot.executionId());
        if (changed == 0) {
            transactionJdbc.update("""
                    INSERT INTO rg_test_bloge_executions (
                        execution_id, tenant_id, namespace, business_key, graph_name, shard_id,
                        execution_status, execution_version, lease_until, updated_at, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.executionId(), execution.identity().tenantId(),
                    execution.identity().namespace(), execution.identity().businessKey(),
                    execution.identity().graphName(), execution.identity().shardId(),
                    execution.status().name(), execution.version(), execution.leaseUntil(),
                    execution.updatedAt(), write(execution));
        }
    }

    private void requireTransactionParticipant(JdbcTemplate transactionJdbc) {
        Objects.requireNonNull(transactionJdbc, "transactionJdbc");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw durability("BLOGE execution mutation requires an active test-runtime transaction");
        }
        if (transactionJdbc.getDataSource() != dataSource) {
            throw durability("BLOGE execution mutation used a different datasource");
        }
        if (!TransactionSynchronizationManager.hasResource(dataSource)) {
            throw durability("BLOGE execution datasource is not bound to the active transaction");
        }
    }

    /** One execution-scoped lifecycle stage. */
    public final class Stage implements AutoCloseable {
        private final StagingArea area;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Stage(StagingArea area) {
            this.area = area;
        }

        /**
         * Freezes the lifecycle row and binds it to one formal engine boundary.
         *
         * @param checkpointRef durable checkpoint reference associated with the engine boundary
         * @param nodeId graph node whose completion established the boundary
         * @param boundaryType formal engine boundary classification
         * @param boundarySequence monotonic boundary sequence within the execution
         * @param stateVersion engine state version captured by the checkpoint
         * @return immutable prepared mutation awaiting transactional commit or rollback
         */
        public PreparedMutation prepare(String checkpointRef,
                                        String nodeId,
                                        String boundaryType,
                                        long boundarySequence,
                                        long stateVersion) {
            if (closed.get()) {
                throw durability("Composite execution stage is closed");
            }
            PendingSnapshot snapshot = area.prepare();
            String fingerprint = ProtocolFingerprint.of(
                    objectMapper, snapshot.fingerprintMaterial());
            DurableTestExecutionCheckpoint.EngineState engineState =
                    new DurableTestExecutionCheckpoint.EngineState(
                            checkpointRef, nodeId, boundaryType, boundarySequence,
                            stateVersion, fingerprint);
            return new PreparedMutation(area, snapshot, engineState);
        }

        /** Discards uncommitted lifecycle state. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                area.close();
                activeStages.remove(area.executionId(), area);
            }
        }
    }

    /** Frozen lifecycle mutation accepted by the composite repository. */
    public final class PreparedMutation
            implements DurableTestExecutionCheckpointRepository.BoundEngineStateMutation {
        private final StagingArea owner;
        private final PendingSnapshot snapshot;
        private final DurableTestExecutionCheckpoint.EngineState engineState;

        private PreparedMutation(StagingArea owner,
                                 PendingSnapshot snapshot,
                                 DurableTestExecutionCheckpoint.EngineState engineState) {
            this.owner = owner;
            this.snapshot = snapshot;
            this.engineState = engineState;
        }

        @Override
        public String engineExecutionId() {
            return snapshot.executionId();
        }

        @Override
        public DurableTestExecutionCheckpoint.EngineState engineState() {
            return engineState;
        }

        @Override
        public void apply(JdbcTemplate transactionJdbc) {
            synchronized (owner) {
                if (activeStages.get(snapshot.executionId()) != owner) {
                    throw durability("Prepared BLOGE execution mutation belongs to a closed stage");
                }
                owner.requirePreparedOpen();
                StagedBlogeExecutionStore.this.apply(transactionJdbc, snapshot);
            }
        }
    }

    private static final class StagingArea {
        private final String executionId;
        private final InMemoryExecutionStore delegate;
        private boolean mutated;
        private boolean prepared;
        private boolean closed;

        private StagingArea(String executionId, TimeSource timeSource,
                            Optional<ExecutionInstance> committed) {
            this.executionId = executionId;
            this.delegate = new InMemoryExecutionStore(timeSource);
            committed.ifPresent(delegate::create);
        }

        synchronized <T> T mutate(Function<InMemoryExecutionStore, T> action) {
            requireMutable();
            T result = action.apply(delegate);
            mutated = true;
            return result;
        }

        synchronized Optional<ExecutionInstance> get() {
            if (closed) {
                return Optional.empty();
            }
            return delegate.get(executionId);
        }

        synchronized PendingSnapshot prepare() {
            requireMutable();
            prepared = true;
            return new PendingSnapshot(executionId, mutated, delegate.get(executionId));
        }

        synchronized void requirePreparedOpen() {
            if (closed || !prepared) {
                throw durability("Prepared BLOGE execution mutation belongs to a closed stage");
            }
        }

        synchronized void close() {
            closed = true;
        }

        synchronized void requireMutable() {
            if (closed) {
                throw durability("Composite execution stage is closed");
            }
            if (prepared) {
                throw durability("Composite execution stage is already prepared");
            }
        }

        String executionId() {
            return executionId;
        }
    }

    private record PendingSnapshot(
            String executionId,
            boolean mutated,
            Optional<ExecutionInstance> execution) {

        private Map<String, Object> fingerprintMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", CLOSURE_SCHEMA_VERSION);
            material.put("executionId", executionId);
            material.put("mutated", mutated);
            material.put("operation", execution.isPresent() ? "UPSERT" : "DELETE");
            material.put("execution", execution.orElse(null));
            return material;
        }
    }

    private static int normalizeLimit(int limit) {
        return Math.min(limit <= 0 ? 100 : limit, 10_000);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static DurabilityException durability(String message) {
        return new DurabilityException(message);
    }

    private static DurabilityException durability(String message, Throwable cause) {
        return new DurabilityException(message, cause);
    }
}
