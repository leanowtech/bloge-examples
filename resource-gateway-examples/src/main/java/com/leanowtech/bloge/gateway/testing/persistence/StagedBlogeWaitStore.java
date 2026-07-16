package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.wait.ExecutionWait;
import com.leanowtech.bloge.core.runtime.wait.WaitStatus;
import com.leanowtech.bloge.core.runtime.wait.WaitStore;
import com.leanowtech.bloge.core.runtime.wait.WaitType;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.DurableErrorCode;
import com.leanowtech.bloge.durable.DurableStoreException;
import com.leanowtech.bloge.durable.store.TenantStoreSupport;
import com.leanowtech.bloge.durable.store.memory.InMemoryWaitStore;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
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
import java.util.function.Consumer;

/**
 * Execution-scoped {@link WaitStore} whose writes participate in the durable-test transaction.
 *
 * <p>Signal, timer, task, and retry waits are runtime state, not diagnostic metadata. This adapter
 * therefore keeps every mutation in a run-local overlay until the control-plane repository commits
 * the complete engine closure. Reads by execution and wait ID observe that overlay, while global
 * correlation and timer scans deliberately expose committed rows only so another dispatcher cannot
 * act on a wait whose control checkpoint may still roll back.</p>
 *
 * <p>The immutable {@link ExecutionWait} JSON is the persistence authority. Relational columns are
 * projections used for tenant-safe recovery and dispatch indexes. A prepared mutation can be
 * replayed idempotently, but it fails closed outside the repository transaction, on another
 * datasource, after its stage closes, or when a write targets another active execution.</p>
 */
public final class StagedBlogeWaitStore implements WaitStore {

    private static final String CLOSURE_SCHEMA_VERSION = "bloge.testWaitMutation.v1";
    private static final Comparator<ExecutionWait> BY_CREATED_AT =
            Comparator.comparing(ExecutionWait::createdAt)
                    .thenComparing(ExecutionWait::waitId);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final ExecutionStore executionStore;
    private final Object registryLock = new Object();
    private final ConcurrentHashMap<String, StagingArea> activeStages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeWaitOwners = new ConcurrentHashMap<>();

    /**
     * Creates a staged wait store bound to the same datasource and lifecycle store as the aggregate.
     *
     * @param jdbc transaction-capable test-runtime JDBC facade
     * @param objectMapper mapper for full wait snapshots and closure fingerprints
     * @param executionStore staged execution store used for BLOGE lease fencing
     */
    public StagedBlogeWaitStore(JdbcTemplate jdbc,
                                ObjectMapper objectMapper,
                                ExecutionStore executionStore) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
    }

    /** Creates the wait authority table and committed-only dispatch indexes. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_bloge_waits (
                    wait_id VARCHAR(512) PRIMARY KEY,
                    execution_id VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    wait_type VARCHAR(64) NOT NULL,
                    wait_key VARCHAR(512) NOT NULL,
                    wait_status VARCHAR(64) NOT NULL,
                    timeout_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    payload_json CLOB NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_wait_execution_idx
                ON rg_test_bloge_waits (execution_id, created_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_wait_dispatch_idx
                ON rg_test_bloge_waits (wait_type, wait_status, timeout_at, created_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_wait_correlation_idx
                ON rg_test_bloge_waits (wait_type, wait_key, wait_status, created_at)
                """);
    }

    /**
     * Opens the only local wait stage for an execution and seeds it from committed rows.
     *
     * @param executionId trusted BLOGE execution identifier
     * @param timeSource run-scoped logical clock used by wait transitions
     * @return closeable wait stage
     */
    public Stage begin(String executionId, TimeSource timeSource) {
        String normalized = required(executionId, "executionId");
        StagingArea area = new StagingArea(normalized,
                Objects.requireNonNull(timeSource, "timeSource"), executionStore,
                loadCommitted(normalized));
        synchronized (registryLock) {
            if (activeStages.containsKey(normalized)) {
                throw durability("A composite wait stage is already active for " + normalized);
            }
            for (String waitId : area.claimedWaitIds()) {
                String owner = activeWaitOwners.get(waitId);
                if (owner != null) {
                    throw durability("Wait " + waitId + " is already owned by active execution " + owner);
                }
            }
            activeStages.put(normalized, area);
            area.claimedWaitIds().forEach(waitId -> activeWaitOwners.put(waitId, normalized));
        }
        return new Stage(area);
    }

    @Override
    public void create(ExecutionWait executionWait) {
        ExecutionWait requiredWait = Objects.requireNonNull(executionWait, "executionWait");
        String executionId = required(requiredWait.identity().executionId(), "executionId");
        StagingArea area = stagingArea(executionId);
        var owningExecution = executionStore.get(executionId).orElseThrow(() ->
                new DurableStoreException(
                        DurableErrorCode.NOT_FOUND, "Execution not found: " + executionId));
        if (!Objects.equals(owningExecution.identity(), requiredWait.identity())) {
            throw durability("Wait identity does not match execution lifecycle for " + executionId);
        }
        loadCommittedByIdUnscoped(requiredWait.waitId()).ifPresent(existing -> {
            if (!Objects.equals(existing.identity().executionId(), executionId)) {
                throw durability("Wait " + requiredWait.waitId()
                        + " is already committed for execution "
                        + existing.identity().executionId());
            }
        });
        boolean newlyClaimed;
        synchronized (registryLock) {
            String owner = activeWaitOwners.get(requiredWait.waitId());
            if (owner != null && !Objects.equals(owner, executionId)) {
                throw durability("Wait " + requiredWait.waitId()
                        + " is already owned by active execution " + owner);
            }
            newlyClaimed = owner == null;
            if (newlyClaimed) {
                activeWaitOwners.put(requiredWait.waitId(), executionId);
                area.claim(requiredWait.waitId());
            }
        }
        try {
            area.mutate(store -> store.create(requiredWait));
        } catch (RuntimeException | Error failure) {
            if (newlyClaimed) {
                synchronized (registryLock) {
                    activeWaitOwners.remove(requiredWait.waitId(), executionId);
                    area.releaseClaim(requiredWait.waitId());
                }
            }
            throw failure;
        }
    }

    @Override
    public Optional<ExecutionWait> get(String waitId) {
        String normalized = required(waitId, "waitId");
        StagingArea area = activeAreaForWait(normalized);
        return area == null ? loadCommittedById(normalized) : area.get(normalized);
    }

    @Override
    public List<ExecutionWait> findByExecution(String executionId) {
        String normalized = required(executionId, "executionId");
        StagingArea area = activeStages.get(normalized);
        return area == null ? loadCommitted(normalized) : area.view();
    }

    /** Returns committed waits only so dispatchers cannot observe an uncommitted correlation. */
    @Override
    public List<ExecutionWait> findByTypeAndKey(WaitType waitType, String waitKey, int limit) {
        WaitType requiredType = Objects.requireNonNull(waitType, "waitType");
        String requiredKey = required(waitKey, "waitKey");
        return committedRows().stream()
                .filter(wait -> TenantStoreSupport.matchesCurrentTenant(wait.identity()))
                .filter(wait -> wait.waitType() == requiredType)
                .filter(wait -> Objects.equals(requiredKey, wait.waitKey()))
                .filter(wait -> wait.status() == WaitStatus.WAITING)
                .sorted(BY_CREATED_AT)
                .limit(normalizeLimit(limit))
                .toList();
    }

    /** Returns committed waits only so timer recovery cannot schedule speculative state. */
    @Override
    public List<ExecutionWait> findByType(WaitType waitType, WaitStatus status, int limit) {
        WaitType requiredType = Objects.requireNonNull(waitType, "waitType");
        WaitStatus requiredStatus = Objects.requireNonNull(status, "status");
        return committedRows().stream()
                .filter(wait -> TenantStoreSupport.matchesCurrentTenant(wait.identity()))
                .filter(wait -> wait.waitType() == requiredType)
                .filter(wait -> wait.status() == requiredStatus)
                .sorted(BY_CREATED_AT)
                .limit(normalizeLimit(limit))
                .toList();
    }

    @Override
    public void resolve(String waitId, long expectedVersion) {
        mutateExisting(waitId, store -> store.resolve(waitId, expectedVersion));
    }

    @Override
    public void timeout(String waitId, long expectedVersion) {
        mutateExisting(waitId, store -> store.timeout(waitId, expectedVersion));
    }

    @Override
    public void delete(String waitId) {
        String normalized = required(waitId, "waitId");
        StagingArea area = activeAreaForWait(normalized);
        if (area == null) {
            Optional<ExecutionWait> committed = loadCommittedById(normalized);
            if (committed.isEmpty()) {
                return;
            }
            area = stagingArea(committed.orElseThrow().identity().executionId());
        }
        StagingArea target = area;
        target.mutate(store -> store.delete(normalized));
    }

    @Override
    public void deleteByExecution(String executionId) {
        stagingArea(required(executionId, "executionId"))
                .mutate(store -> store.deleteByExecution(executionId));
    }

    private void mutateExisting(String waitId, Consumer<InMemoryWaitStore> mutation) {
        String normalized = required(waitId, "waitId");
        StagingArea area = activeAreaForWait(normalized);
        if (area == null) {
            Optional<ExecutionWait> committed = loadCommittedById(normalized);
            if (committed.isEmpty()) {
                throw new DurableStoreException(
                        DurableErrorCode.NOT_FOUND, "Wait not found: " + normalized);
            }
            area = stagingArea(committed.orElseThrow().identity().executionId());
        }
        area.mutate(mutation);
    }

    private StagingArea activeAreaForWait(String waitId) {
        synchronized (registryLock) {
            String executionId = activeWaitOwners.get(waitId);
            return executionId == null ? null : activeStages.get(executionId);
        }
    }

    private StagingArea stagingArea(String executionId) {
        StagingArea area = activeStages.get(required(executionId, "executionId"));
        if (area == null) {
            throw durability("BLOGE wait mutation requires an active composite stage for "
                    + executionId);
        }
        return area;
    }

    private Optional<ExecutionWait> loadCommittedById(String waitId) {
        return loadCommittedByIdUnscoped(waitId)
                .filter(wait -> TenantStoreSupport.matchesCurrentTenant(wait.identity()));
    }

    private Optional<ExecutionWait> loadCommittedByIdUnscoped(String waitId) {
        return jdbc.query("""
                        SELECT payload_json FROM rg_test_bloge_waits WHERE wait_id = ?
                        """, (resultSet, rowNumber) -> read(resultSet.getString("payload_json")),
                waitId).stream().findFirst();
    }

    private List<ExecutionWait> loadCommitted(String executionId) {
        return jdbc.query("""
                        SELECT payload_json FROM rg_test_bloge_waits
                        WHERE execution_id = ? ORDER BY created_at, wait_id
                        """, (resultSet, rowNumber) -> read(resultSet.getString("payload_json")),
                executionId).stream()
                .filter(wait -> TenantStoreSupport.matchesCurrentTenant(wait.identity()))
                .toList();
    }

    private List<ExecutionWait> committedRows() {
        return jdbc.query("SELECT payload_json FROM rg_test_bloge_waits",
                (resultSet, rowNumber) -> read(resultSet.getString("payload_json")));
    }

    private ExecutionWait read(String json) {
        try {
            return objectMapper.readValue(json, ExecutionWait.class);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw durability("Stored BLOGE wait is corrupt", corrupt);
        }
    }

    private String write(ExecutionWait wait) {
        try {
            return objectMapper.writeValueAsString(wait);
        } catch (JsonProcessingException failure) {
            throw durability("Failed to serialize BLOGE wait", failure);
        }
    }

    private void apply(JdbcTemplate transactionJdbc, PendingSnapshot snapshot) {
        requireTransactionParticipant(transactionJdbc);
        if (!snapshot.mutated()) {
            return;
        }
        snapshot.deletedWaitIds().forEach(waitId -> transactionJdbc.update(
                "DELETE FROM rg_test_bloge_waits WHERE wait_id = ? AND execution_id = ?",
                waitId, snapshot.executionId()));
        snapshot.upserts().forEach(wait -> upsert(transactionJdbc, wait));
    }

    private void upsert(JdbcTemplate transactionJdbc, ExecutionWait wait) {
        int changed = transactionJdbc.update("""
                UPDATE rg_test_bloge_waits
                SET tenant_id = ?, wait_type = ?, wait_key = ?,
                    wait_status = ?, timeout_at = ?, created_at = ?, payload_json = ?
                WHERE wait_id = ? AND execution_id = ?
                """, wait.identity().tenantId(), wait.waitType().name(),
                wait.waitKey(), wait.status().name(), timestamp(wait.timeoutAt()),
                Timestamp.from(wait.createdAt()), write(wait), wait.waitId(),
                wait.identity().executionId());
        if (changed == 0) {
            transactionJdbc.update("""
                    INSERT INTO rg_test_bloge_waits (
                        wait_id, execution_id, tenant_id, wait_type, wait_key, wait_status,
                        timeout_at, created_at, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, wait.waitId(), wait.identity().executionId(), wait.identity().tenantId(),
                    wait.waitType().name(), wait.waitKey(), wait.status().name(),
                    timestamp(wait.timeoutAt()), Timestamp.from(wait.createdAt()), write(wait));
        }
    }

    private void requireTransactionParticipant(JdbcTemplate transactionJdbc) {
        Objects.requireNonNull(transactionJdbc, "transactionJdbc");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw durability("BLOGE wait mutation requires an active test-runtime transaction");
        }
        if (transactionJdbc.getDataSource() != dataSource) {
            throw durability("BLOGE wait mutation used a different datasource");
        }
        if (!TransactionSynchronizationManager.hasResource(dataSource)) {
            throw durability("BLOGE wait datasource is not bound to the active transaction");
        }
    }

    private static Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static long normalizeLimit(int limit) {
        return limit <= 0 ? 100L : limit;
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

    /** One execution-scoped wait overlay. */
    public final class Stage implements AutoCloseable {
        private final StagingArea area;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Stage(StagingArea area) {
            this.area = area;
        }

        /**
         * Freezes the exact wait operation set for one formal engine-state boundary.
         *
         * @param checkpointRef stable control-plane checkpoint reference
         * @param nodeId boundary node identifier
         * @param boundaryType supported durable boundary type
         * @param boundarySequence positive monotonic boundary sequence
         * @param stateVersion non-negative engine state version
         * @return idempotently replayable wait mutation and its component fingerprint
         */
        public PreparedMutation prepare(String checkpointRef,
                                        String nodeId,
                                        String boundaryType,
                                        long boundarySequence,
                                        long stateVersion) {
            if (closed.get()) {
                throw durability("Composite wait stage is closed");
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

        /** Drops all process-local wait state that was not committed through the repository. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (registryLock) {
                    area.close();
                    activeStages.remove(area.executionId(), area);
                    area.claimedWaitIds().forEach(waitId ->
                            activeWaitOwners.remove(waitId, area.executionId()));
                }
            }
        }
    }

    /** Frozen wait mutation accepted only by the composite repository transaction. */
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
                    throw durability("Prepared BLOGE wait mutation belongs to a closed stage");
                }
                owner.requirePreparedOpen();
                StagedBlogeWaitStore.this.apply(transactionJdbc, snapshot);
            }
        }
    }

    private static final class StagingArea {
        private final String executionId;
        private final InMemoryWaitStore delegate;
        private final Set<String> initialWaitIds;
        private final Set<String> claimedWaitIds;
        private boolean mutated;
        private boolean prepared;
        private boolean closed;

        private StagingArea(String executionId,
                            TimeSource timeSource,
                            ExecutionStore executionStore,
                            List<ExecutionWait> committed) {
            this.executionId = executionId;
            this.delegate = new InMemoryWaitStore(timeSource, executionStore);
            committed.forEach(delegate::create);
            this.initialWaitIds = committed.stream()
                    .map(ExecutionWait::waitId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            this.claimedWaitIds = new LinkedHashSet<>(initialWaitIds);
        }

        synchronized void mutate(Consumer<InMemoryWaitStore> mutation) {
            requireMutable();
            mutation.accept(delegate);
            mutated = true;
        }

        synchronized Optional<ExecutionWait> get(String waitId) {
            return closed ? Optional.empty() : delegate.get(waitId);
        }

        synchronized List<ExecutionWait> view() {
            if (closed) {
                return List.of();
            }
            return delegate.findByExecution(executionId).stream().sorted(BY_CREATED_AT).toList();
        }

        synchronized PendingSnapshot prepare() {
            requireMutable();
            prepared = true;
            List<ExecutionWait> finalWaits = delegate.findByExecution(executionId).stream()
                    .sorted(BY_CREATED_AT)
                    .toList();
            Set<String> finalIds = finalWaits.stream()
                    .map(ExecutionWait::waitId)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> deleted = initialWaitIds.stream()
                    .filter(waitId -> !finalIds.contains(waitId))
                    .sorted()
                    .toList();
            return new PendingSnapshot(executionId, mutated,
                    mutated ? finalWaits : List.of(), deleted);
        }

        synchronized void claim(String waitId) {
            claimedWaitIds.add(waitId);
        }

        synchronized void releaseClaim(String waitId) {
            claimedWaitIds.remove(waitId);
        }

        synchronized Set<String> claimedWaitIds() {
            return Set.copyOf(claimedWaitIds);
        }

        synchronized String executionId() {
            return executionId;
        }

        synchronized void requirePreparedOpen() {
            if (closed || !prepared) {
                throw durability("Prepared BLOGE wait mutation belongs to a closed stage");
            }
        }

        synchronized void close() {
            closed = true;
        }

        private void requireMutable() {
            if (closed) {
                throw durability("Composite wait stage is closed");
            }
            if (prepared) {
                throw durability("Composite wait stage is already prepared");
            }
        }
    }

    private record PendingSnapshot(String executionId,
                                   boolean mutated,
                                   List<ExecutionWait> upserts,
                                   List<String> deletedWaitIds) {
        private PendingSnapshot {
            upserts = List.copyOf(upserts);
            deletedWaitIds = List.copyOf(deletedWaitIds);
        }

        Map<String, Object> fingerprintMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", CLOSURE_SCHEMA_VERSION);
            material.put("engineExecutionId", executionId);
            material.put("mutated", mutated);
            material.put("upserts", new ArrayList<>(upserts));
            material.put("deletedWaitIds", new ArrayList<>(deletedWaitIds));
            return material;
        }
    }
}
