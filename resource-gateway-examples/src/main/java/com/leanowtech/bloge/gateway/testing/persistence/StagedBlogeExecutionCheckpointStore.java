package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpoint;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BLOGE checkpoint store whose mutations commit only through the composite durable-test boundary.
 *
 * <p>An engine execution must first open one {@link Stage}. Writes, deletes, and payload rewrites
 * remain process-local while the stage is active, and reads for that execution observe the staged
 * overlay. {@link Stage#prepare(String, String, String, long, long)} freezes the exact operation
 * set, computes its content fingerprint, and returns an {@link PreparedMutation} that can be passed
 * to {@link com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository}.
 * Applying the mutation outside an active transaction or through another datasource fails closed.
 * Closing a stage always discards its in-memory overlay; only a successfully committed repository
 * transaction makes the rows visible afterward. Prepared operations are content-addressed and
 * idempotent, so a caller may retry the same mutation after a transient transaction rollback.</p>
 *
 * <p>This store intentionally permits at most one local stage per engine execution. Distributed
 * exclusivity remains the responsibility of the durable checkpoint owner/lease fence.</p>
 */
public final class StagedBlogeExecutionCheckpointStore implements ExecutionCheckpointStore {

    private static final String CLOSURE_SCHEMA_VERSION = "bloge.testCheckpointMutation.v1";
    private static final Comparator<ExecutionCheckpoint> BY_UPDATED_AT =
            Comparator.comparing(ExecutionCheckpoint::updatedAt)
                    .thenComparing(checkpoint -> checkpoint.checkpointType().name())
                    .thenComparing(ExecutionCheckpoint::nodeId)
                    .thenComparing(checkpoint -> normalizedIteration(checkpoint.iterationKey()));
    private static final Comparator<CheckpointKey> BY_KEY =
            Comparator.comparing(CheckpointKey::executionId)
                    .thenComparing(CheckpointKey::checkpointType)
                    .thenComparing(CheckpointKey::nodeId)
                    .thenComparing(CheckpointKey::iterationKey);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, StagingArea> activeStages = new ConcurrentHashMap<>();

    /**
     * @param jdbc test-runtime JDBC facade used for committed reads
     * @param objectMapper mapper for execution identity persistence and closure fingerprints
     */
    public StagedBlogeExecutionCheckpointStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Creates the isolated BLOGE checkpoint table. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_bloge_execution_checkpoints (
                    execution_id VARCHAR(255) NOT NULL,
                    checkpoint_type VARCHAR(64) NOT NULL,
                    node_id VARCHAR(255) NOT NULL,
                    iteration_key VARCHAR(255) NOT NULL,
                    identity_json CLOB NOT NULL,
                    payload CLOB,
                    payload_ref CLOB,
                    operator_fingerprint VARCHAR(255),
                    schema_version VARCHAR(64) NOT NULL,
                    checkpoint_version BIGINT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (execution_id, checkpoint_type, node_id, iteration_key)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_checkpoint_execution_idx
                ON rg_test_bloge_execution_checkpoints (execution_id, updated_at)
                """);
    }

    /**
     * Opens the only local mutation stage for one BLOGE execution.
     *
     * @param executionId BLOGE execution identifier
     * @return closeable stage; closing without a successful composite commit discards all writes
     * @throws DurabilityException when another local stage already owns the execution
     */
    public Stage begin(String executionId) {
        String normalized = required(executionId, "executionId");
        StagingArea area = new StagingArea(normalized);
        if (activeStages.putIfAbsent(normalized, area) != null) {
            throw durability("A composite checkpoint stage is already active for " + normalized);
        }
        return new Stage(area);
    }

    @Override
    public void save(ExecutionCheckpoint checkpoint) {
        ExecutionCheckpoint requiredCheckpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        stagingArea(requiredCheckpoint.identity().executionId()).save(requiredCheckpoint);
    }

    @Override
    public void saveBatch(List<ExecutionCheckpoint> checkpoints) {
        List<ExecutionCheckpoint> requiredCheckpoints = List.copyOf(
                Objects.requireNonNull(checkpoints, "checkpoints"));
        if (requiredCheckpoints.isEmpty()) {
            return;
        }
        String executionId = requiredCheckpoints.getFirst().identity().executionId();
        if (requiredCheckpoints.stream().anyMatch(checkpoint ->
                !Objects.equals(executionId, checkpoint.identity().executionId()))) {
            throw durability("A checkpoint batch cannot span BLOGE executions");
        }
        stagingArea(executionId).saveBatch(requiredCheckpoints);
    }

    @Override
    public Optional<ExecutionCheckpoint> load(
            String executionId, CheckpointType checkpointType, String nodeId) {
        Objects.requireNonNull(checkpointType, "checkpointType");
        String requiredNode = required(nodeId, "nodeId");
        return view(required(executionId, "executionId")).stream()
                .filter(checkpoint -> checkpoint.checkpointType() == checkpointType)
                .filter(checkpoint -> Objects.equals(requiredNode, checkpoint.nodeId()))
                .max(BY_UPDATED_AT);
    }

    @Override
    public List<ExecutionCheckpoint> loadAll(String executionId) {
        return view(required(executionId, "executionId")).stream().sorted(BY_UPDATED_AT).toList();
    }

    @Override
    public List<ExecutionCheckpoint> loadByType(
            String executionId, CheckpointType checkpointType) {
        Objects.requireNonNull(checkpointType, "checkpointType");
        return view(required(executionId, "executionId")).stream()
                .filter(checkpoint -> checkpoint.checkpointType() == checkpointType)
                .sorted(BY_UPDATED_AT)
                .toList();
    }

    /**
     * Returns committed rows only. Global maintenance scans never expose process-local stages.
     */
    @Override
    public List<ExecutionCheckpoint> loadPage(int page, int size) {
        if (page < 0 || size <= 0) {
            throw new IllegalArgumentException("page must be non-negative and size must be positive");
        }
        return jdbc.query(selectColumns() + """
                        ORDER BY execution_id, checkpoint_type, node_id, iteration_key
                        LIMIT ? OFFSET ?
                        """, this::mapRow, size, (long) page * size);
    }

    @Override
    public void updatePayload(String executionId,
                              CheckpointType checkpointType,
                              String nodeId,
                              String iterationKey,
                              String payload) {
        String requiredExecution = required(executionId, "executionId");
        CheckpointKey key = new CheckpointKey(requiredExecution,
                Objects.requireNonNull(checkpointType, "checkpointType").name(),
                required(nodeId, "nodeId"), normalizedIteration(iterationKey));
        ExecutionCheckpoint current = view(requiredExecution).stream()
                .filter(checkpoint -> CheckpointKey.of(checkpoint).equals(key))
                .findFirst()
                .orElseThrow(() -> durability("Checkpoint not found for staged payload rewrite"));
        stagingArea(requiredExecution).save(current.toBuilder().payload(
                Objects.requireNonNull(payload, "payload")).build());
    }

    @Override
    public void delete(String executionId, String nodeId) {
        stagingArea(required(executionId, "executionId")).deleteNode(required(nodeId, "nodeId"));
    }

    @Override
    public void deleteAll(String executionId) {
        stagingArea(required(executionId, "executionId")).deleteAll();
    }

    private List<ExecutionCheckpoint> view(String executionId) {
        List<ExecutionCheckpoint> committed = jdbc.query(selectColumns() + """
                        WHERE execution_id = ?
                        """, this::mapRow, executionId);
        StagingArea area = activeStages.get(executionId);
        return area == null ? committed : area.overlay(committed);
    }

    private StagingArea stagingArea(String executionId) {
        StagingArea area = activeStages.get(executionId);
        if (area == null) {
            throw durability("BLOGE checkpoint mutation requires an active composite checkpoint stage for "
                    + executionId);
        }
        return area;
    }

    private void apply(JdbcTemplate transactionJdbc, PendingSnapshot snapshot) {
        requireTransactionParticipant(transactionJdbc);
        if (snapshot.deleteAll()) {
            transactionJdbc.update(
                    "DELETE FROM rg_test_bloge_execution_checkpoints WHERE execution_id = ?",
                    snapshot.executionId());
        } else {
            snapshot.deletedNodes().forEach(nodeId -> transactionJdbc.update("""
                    DELETE FROM rg_test_bloge_execution_checkpoints
                    WHERE execution_id = ? AND node_id = ?
                    """, snapshot.executionId(), nodeId));
        }
        snapshot.upserts().forEach(checkpoint -> upsert(transactionJdbc, checkpoint));
    }

    private void requireTransactionParticipant(JdbcTemplate transactionJdbc) {
        Objects.requireNonNull(transactionJdbc, "transactionJdbc");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw durability("BLOGE checkpoint mutation requires an active test-runtime transaction");
        }
        if (transactionJdbc.getDataSource() != dataSource) {
            throw durability("BLOGE checkpoint mutation used a different datasource");
        }
        if (!TransactionSynchronizationManager.hasResource(dataSource)) {
            throw durability("BLOGE checkpoint datasource is not bound to the active transaction");
        }
    }

    private void upsert(JdbcTemplate transactionJdbc, ExecutionCheckpoint checkpoint) {
        CheckpointKey key = CheckpointKey.of(checkpoint);
        int changed = transactionJdbc.update("""
                UPDATE rg_test_bloge_execution_checkpoints
                SET identity_json = ?, payload = ?, payload_ref = ?, operator_fingerprint = ?,
                    schema_version = ?, checkpoint_version = ?, created_at = ?, updated_at = ?
                WHERE execution_id = ? AND checkpoint_type = ? AND node_id = ? AND iteration_key = ?
                """, writeIdentity(checkpoint.identity()), checkpoint.payload(), checkpoint.payloadRef(),
                checkpoint.operatorFingerprint(), checkpoint.schemaVersion(), checkpoint.version(),
                Timestamp.from(checkpoint.createdAt()), Timestamp.from(checkpoint.updatedAt()),
                key.executionId(), key.checkpointType(), key.nodeId(), key.iterationKey());
        if (changed == 0) {
            transactionJdbc.update("""
                    INSERT INTO rg_test_bloge_execution_checkpoints (
                        execution_id, checkpoint_type, node_id, iteration_key, identity_json,
                        payload, payload_ref, operator_fingerprint, schema_version,
                        checkpoint_version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key.executionId(), key.checkpointType(), key.nodeId(), key.iterationKey(),
                    writeIdentity(checkpoint.identity()), checkpoint.payload(), checkpoint.payloadRef(),
                    checkpoint.operatorFingerprint(), checkpoint.schemaVersion(), checkpoint.version(),
                    Timestamp.from(checkpoint.createdAt()), Timestamp.from(checkpoint.updatedAt()));
        }
    }

    private String selectColumns() {
        return """
                SELECT execution_id, checkpoint_type, node_id, iteration_key, identity_json,
                       payload, payload_ref, operator_fingerprint, schema_version,
                       checkpoint_version, created_at, updated_at
                FROM rg_test_bloge_execution_checkpoints
                """;
    }

    private ExecutionCheckpoint mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            ExecutionIdentity identity = objectMapper.readValue(
                    resultSet.getString("identity_json"), ExecutionIdentity.class);
            String iterationKey = resultSet.getString("iteration_key");
            return new ExecutionCheckpoint(
                    identity,
                    CheckpointType.valueOf(resultSet.getString("checkpoint_type")),
                    resultSet.getString("node_id"),
                    iterationKey.isEmpty() ? null : iterationKey,
                    resultSet.getString("payload"),
                    resultSet.getString("payload_ref"),
                    resultSet.getString("operator_fingerprint"),
                    resultSet.getString("schema_version"),
                    resultSet.getLong("checkpoint_version"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"));
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new SQLException("Stored BLOGE test checkpoint is corrupt", corrupt);
        }
    }

    private String writeIdentity(ExecutionIdentity identity) {
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException failure) {
            throw new DurabilityException("Failed to serialize BLOGE execution identity", failure);
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalizedIteration(String iterationKey) {
        return iterationKey == null ? "" : iterationKey;
    }

    private static DurabilityException durability(String message) {
        return new DurabilityException(message);
    }

    /** One execution-scoped mutable stage. */
    public final class Stage implements AutoCloseable {
        private final StagingArea area;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Stage(StagingArea area) {
            this.area = area;
        }

        /**
         * Freezes the staged operation set and binds it to one formal engine boundary.
         *
         * @param checkpointRef stable checkpoint reference stored in the control closure
         * @param nodeId boundary node identifier
         * @param boundaryType supported durable boundary type
         * @param boundarySequence positive monotonic boundary sequence
         * @param stateVersion non-negative engine state version
         * @return idempotently replayable mutation and its content-addressed engine-state value
         */
        public PreparedMutation prepare(String checkpointRef,
                                        String nodeId,
                                        String boundaryType,
                                        long boundarySequence,
                                        long stateVersion) {
            if (closed.get()) {
                throw durability("Composite checkpoint stage is closed");
            }
            PendingSnapshot snapshot = area.prepare();
            String fingerprint = ProtocolFingerprint.of(objectMapper, snapshot.fingerprintMaterial());
            DurableTestExecutionCheckpoint.EngineState engineState =
                    new DurableTestExecutionCheckpoint.EngineState(
                            checkpointRef, nodeId, boundaryType, boundarySequence,
                            stateVersion, fingerprint);
            return new PreparedMutation(area, snapshot, engineState);
        }

        /** Discards the local overlay. Committed rows, if any, remain in the database. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                area.close();
                activeStages.remove(area.executionId(), area);
            }
        }
    }

    /** Immutable, idempotently replayable BLOGE mutation accepted by the composite repository. */
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

        /** @return exact BLOGE execution identity covered by the frozen operation set */
        @Override
        public String engineExecutionId() {
            return snapshot.executionId();
        }

        /** @return engine-state closure whose fingerprint covers this exact operation set */
        @Override
        public DurableTestExecutionCheckpoint.EngineState engineState() {
            return engineState;
        }

        /** Applies the frozen rows idempotently through the transaction-bound JDBC facade. */
        @Override
        public void apply(JdbcTemplate transactionJdbc) {
            synchronized (owner) {
                if (activeStages.get(snapshot.executionId()) != owner) {
                    throw durability("Prepared BLOGE checkpoint mutation belongs to a closed stage");
                }
                owner.requirePreparedOpen();
                StagedBlogeExecutionCheckpointStore.this.apply(transactionJdbc, snapshot);
            }
        }
    }

    private static final class StagingArea {
        private final String executionId;
        private final Set<String> deletedNodes = new LinkedHashSet<>();
        private final Map<CheckpointKey, ExecutionCheckpoint> upserts = new LinkedHashMap<>();
        private boolean deleteAll;
        private boolean prepared;
        private boolean closed;

        private StagingArea(String executionId) {
            this.executionId = executionId;
        }

        synchronized void save(ExecutionCheckpoint checkpoint) {
            requireMutable();
            requireExecution(checkpoint);
            upserts.put(CheckpointKey.of(checkpoint), checkpoint);
        }

        synchronized void saveBatch(List<ExecutionCheckpoint> checkpoints) {
            requireMutable();
            checkpoints.forEach(this::requireExecution);
            checkpoints.forEach(checkpoint -> upserts.put(CheckpointKey.of(checkpoint), checkpoint));
        }

        synchronized void deleteNode(String nodeId) {
            requireMutable();
            deletedNodes.add(nodeId);
            upserts.entrySet().removeIf(entry -> Objects.equals(nodeId, entry.getKey().nodeId()));
        }

        synchronized void deleteAll() {
            requireMutable();
            deleteAll = true;
            deletedNodes.clear();
            upserts.clear();
        }

        synchronized List<ExecutionCheckpoint> overlay(List<ExecutionCheckpoint> committed) {
            if (closed) {
                return committed;
            }
            Map<CheckpointKey, ExecutionCheckpoint> view = new LinkedHashMap<>();
            if (!deleteAll) {
                committed.stream()
                        .filter(checkpoint -> !deletedNodes.contains(checkpoint.nodeId()))
                        .forEach(checkpoint -> view.put(CheckpointKey.of(checkpoint), checkpoint));
            }
            view.putAll(upserts);
            return List.copyOf(view.values());
        }

        synchronized PendingSnapshot prepare() {
            requireMutable();
            prepared = true;
            List<ExecutionCheckpoint> frozenUpserts = upserts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(BY_KEY))
                    .map(Map.Entry::getValue)
                    .toList();
            return new PendingSnapshot(executionId, deleteAll,
                    deletedNodes.stream().sorted().toList(), frozenUpserts);
        }

        synchronized void close() {
            closed = true;
            deletedNodes.clear();
            upserts.clear();
        }

        synchronized void requirePreparedOpen() {
            if (closed || !prepared) {
                throw durability("Prepared BLOGE checkpoint mutation belongs to a closed stage");
            }
        }

        String executionId() {
            return executionId;
        }

        private void requireMutable() {
            if (closed) {
                throw durability("Composite checkpoint stage is closed");
            }
            if (prepared) {
                throw durability("Composite checkpoint stage was already prepared");
            }
        }

        private void requireExecution(ExecutionCheckpoint checkpoint) {
            if (!Objects.equals(executionId, checkpoint.identity().executionId())) {
                throw durability("Staged BLOGE checkpoint belongs to another execution");
            }
        }
    }

    private record PendingSnapshot(String executionId,
                                   boolean deleteAll,
                                   List<String> deletedNodes,
                                   List<ExecutionCheckpoint> upserts) {
        private Map<String, Object> fingerprintMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", CLOSURE_SCHEMA_VERSION);
            material.put("executionId", executionId);
            material.put("deleteAll", deleteAll);
            material.put("deletedNodes", deletedNodes);
            material.put("upserts", upserts);
            return material;
        }
    }

    private record CheckpointKey(String executionId,
                                 String checkpointType,
                                 String nodeId,
                                 String iterationKey) {
        private static CheckpointKey of(ExecutionCheckpoint checkpoint) {
            return new CheckpointKey(
                    checkpoint.identity().executionId(), checkpoint.checkpointType().name(),
                    checkpoint.nodeId(), normalizedIteration(checkpoint.iterationKey()));
        }
    }
}
