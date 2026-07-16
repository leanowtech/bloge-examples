package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.work.DeadLetterPolicy;
import com.leanowtech.bloge.core.runtime.work.WorkItem;
import com.leanowtech.bloge.core.runtime.work.WorkItemQuery;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemStore;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.durable.store.TenantStoreSupport;
import com.leanowtech.bloge.durable.store.memory.InMemoryWorkItemStore;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
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
 * Execution-scoped {@link WorkItemStore} whose writes join the durable-test transaction.
 *
 * <p>Execution-local reads observe an uncommitted overlay. Global worker polling deliberately reads
 * committed rows only, so no worker can claim a job whose control checkpoint may still roll back.
 * The complete immutable {@link WorkItem} JSON is authoritative; relational columns are scheduling
 * projections and indexes.</p>
 *
 * <p>Every item must retain its execution's tenant, namespace, graph, route, lineage, and source
 * identity. The work-item shard is the sole permitted override because BLOGE uses that field as
 * the worker topic or dispatch partition; it is still fingerprinted and persisted with the item.</p>
 */
public final class StagedBlogeWorkItemStore implements WorkItemStore {

    private static final String CLOSURE_SCHEMA_VERSION = "bloge.testWorkItemMutation.v1";
    private static final Comparator<WorkItem> DISPATCH_ORDER =
            Comparator.comparingInt(WorkItem::priority).reversed()
                    .thenComparing(WorkItem::createdAt)
                    .thenComparing(WorkItem::itemId);

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final ExecutionStore executionStore;
    private final Object registryLock = new Object();
    private final ConcurrentHashMap<String, StagingArea> activeStages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeItemOwners = new ConcurrentHashMap<>();
    private final ThreadLocal<StagingArea> callerStage = new ThreadLocal<>();

    /**
     * Creates a staged work-item store over the aggregate's datasource and lifecycle authority.
     *
     * @param jdbc transaction-capable test-runtime JDBC facade
     * @param objectMapper mapper for full work-item snapshots and closure fingerprints
     * @param executionStore staged execution store used for lifecycle identity and lease fencing
     */
    public StagedBlogeWorkItemStore(JdbcTemplate jdbc,
                                    ObjectMapper objectMapper,
                                    ExecutionStore executionStore) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
    }

    /** Creates the work-item authority table and dispatch/recovery indexes. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_bloge_work_items (
                    item_id VARCHAR(512) PRIMARY KEY,
                    execution_id VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    namespace_id VARCHAR(255) NOT NULL,
                    item_type VARCHAR(64) NOT NULL,
                    shard_id VARCHAR(255),
                    priority INTEGER NOT NULL,
                    item_status VARCHAR(64) NOT NULL,
                    claim_owner VARCHAR(512),
                    claim_until TIMESTAMP WITH TIME ZONE,
                    next_attempt_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    payload_json CLOB NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_work_dispatch_idx
                ON rg_test_bloge_work_items
                    (item_type, item_status, shard_id, priority, next_attempt_at, created_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_work_claim_idx
                ON rg_test_bloge_work_items (item_status, claim_until, created_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_bloge_work_execution_idx
                ON rg_test_bloge_work_items (execution_id, created_at)
                """);
    }

    /**
     * Opens one execution-local work-item overlay seeded from committed rows.
     *
     * @param executionId trusted BLOGE execution identifier
     * @param timeSource run-scoped logical clock used by work-item transitions
     * @return closeable work-item stage
     */
    public Stage begin(String executionId, TimeSource timeSource) {
        String normalized = required(executionId, "executionId");
        if (callerStage.get() != null) {
            throw durability("A composite work-item stage is already bound to this thread");
        }
        StagingArea area = new StagingArea(normalized,
                Objects.requireNonNull(timeSource, "timeSource"), executionStore,
                loadCommitted(normalized));
        synchronized (registryLock) {
            if (activeStages.containsKey(normalized)) {
                throw durability("A composite work-item stage is already active for " + normalized);
            }
            for (String itemId : area.claimedItemIds()) {
                String owner = activeItemOwners.get(itemId);
                if (owner != null) {
                    throw durability("Work item " + itemId
                            + " is already owned by active execution " + owner);
                }
            }
            activeStages.put(normalized, area);
            area.claimedItemIds().forEach(itemId -> activeItemOwners.put(itemId, normalized));
        }
        callerStage.set(area);
        return new Stage(area);
    }

    @Override
    public void create(WorkItem workItem) {
        WorkItem requiredItem = Objects.requireNonNull(workItem, "workItem");
        String executionId = required(requiredItem.identity().executionId(), "executionId");
        StagingArea area = creationArea(executionId);
        validateCreate(area, requiredItem);
        boolean newlyClaimed = claimItemId(area, requiredItem.itemId(), executionId);
        try {
            area.create(requiredItem);
        } catch (RuntimeException | Error failure) {
            releaseClaim(area, requiredItem.itemId(), executionId, newlyClaimed);
            throw failure;
        }
    }

    @Override
    public void createBatch(List<WorkItem> workItems) {
        List<WorkItem> requiredItems = List.copyOf(
                Objects.requireNonNull(workItems, "workItems"));
        if (requiredItems.isEmpty()) {
            return;
        }
        String executionId = required(requiredItems.getFirst().identity().executionId(), "executionId");
        if (requiredItems.stream().anyMatch(item ->
                !Objects.equals(executionId, item.identity().executionId()))) {
            throw durability("A work-item batch cannot span multiple executions");
        }
        StagingArea area = creationArea(executionId);
        Set<String> itemIds = new LinkedHashSet<>();
        requiredItems.forEach(item -> {
            if (!itemIds.add(item.itemId())) {
                throw durability("A work-item batch contains duplicate item id " + item.itemId());
            }
            validateCreate(area, item);
        });
        Set<String> newlyClaimed = claimItemIds(area, itemIds, executionId);
        try {
            area.createBatch(requiredItems);
        } catch (RuntimeException | Error failure) {
            newlyClaimed.forEach(itemId -> releaseClaim(
                    area, itemId, executionId, true));
            throw failure;
        }
    }

    @Override
    public Optional<WorkItem> get(String itemId) {
        String normalized = required(itemId, "itemId");
        StagingArea area = callerStage.get();
        return area != null && area.contains(normalized)
                ? area.get(normalized)
                : loadCommittedById(normalized);
    }

    /** Returns committed jobs only, excluding speculative jobs in active execution stages. */
    @Override
    public List<WorkItem> pollReady(WorkItemType workItemType, String shardId, int limit) {
        WorkItemType requiredType = Objects.requireNonNull(workItemType, "workItemType");
        Instant now = SystemTimeSource.INSTANCE.now();
        return committedRows().stream()
                .filter(item -> TenantStoreSupport.matchesCurrentTenant(item.identity()))
                .filter(item -> item.itemType() == requiredType)
                .filter(item -> shardId == null || Objects.equals(shardId, item.identity().shardId()))
                .filter(item -> isClaimable(item, now))
                .sorted(DISPATCH_ORDER)
                .limit(normalizeLimit(limit))
                .toList();
    }

    @Override
    public Optional<WorkItem> claim(String itemId, String owner,
                                    Duration leaseDuration, long expectedVersion) {
        StagingArea area = mutationArea(itemId);
        return area.matchesCurrentTenant(itemId)
                ? area.claim(itemId, owner, leaseDuration, expectedVersion)
                : Optional.empty();
    }

    @Override
    public Optional<WorkItem> renewClaim(String itemId, String leaseToken, Duration extension) {
        StagingArea area = mutationArea(itemId);
        return area.matchesCurrentTenant(itemId)
                ? area.renewClaim(itemId, leaseToken, extension)
                : Optional.empty();
    }

    @Override
    public void markDone(String itemId, String leaseToken, long expectedVersion) {
        requiredMutationArea(itemId).markDone(itemId, leaseToken, expectedVersion);
    }

    @Override
    public void markRetryWait(String itemId, String leaseToken,
                              Instant nextAttemptAt, long expectedVersion) {
        requiredMutationArea(itemId).markRetryWait(
                itemId, leaseToken, nextAttemptAt, expectedVersion);
    }

    @Override
    public void markRetryWait(String itemId, String leaseToken, Instant nextAttemptAt,
                              DeadLetterPolicy.Classification classification,
                              long expectedVersion) {
        requiredMutationArea(itemId).markRetryWait(
                itemId, leaseToken, nextAttemptAt, classification, expectedVersion);
    }

    @Override
    public void markFailed(String itemId, String leaseToken, long expectedVersion) {
        requiredMutationArea(itemId).markFailed(itemId, leaseToken, expectedVersion);
    }

    @Override
    public void markDeadLetter(String itemId, String reason) {
        requiredMutationArea(itemId).markDeadLetter(itemId, reason);
    }

    @Override
    public void markDeadLetter(String itemId, String reason, String failureClass) {
        requiredMutationArea(itemId).markDeadLetter(itemId, reason, failureClass);
    }

    @Override
    public WorkItem restoreDeadLetter(String itemId) {
        return requiredMutationArea(itemId).restoreDeadLetter(itemId);
    }

    @Override
    public WorkItem discardDeadLetter(String itemId) {
        return requiredMutationArea(itemId).discardDeadLetter(itemId);
    }

    @Override
    public int cancelByExecution(String executionId, String reason) {
        return creationArea(executionId).cancelByExecution(executionId, reason);
    }

    @Override
    public List<WorkItem> findExpiredClaims(Instant cutoff, int limit) {
        Instant requiredCutoff = Objects.requireNonNull(cutoff, "cutoff");
        return committedRows().stream()
                .filter(item -> TenantStoreSupport.matchesCurrentTenant(item.identity()))
                .filter(item -> item.status() == WorkItemStatus.CLAIMED)
                .filter(item -> item.claimUntil() != null
                        && !item.claimUntil().isAfter(requiredCutoff))
                .sorted(Comparator.comparing(WorkItem::claimUntil)
                        .thenComparing(WorkItem::itemId))
                .limit(normalizeLimit(limit))
                .toList();
    }

    @Override
    public List<WorkItem> query(WorkItemQuery query) {
        WorkItemQuery requiredQuery = Objects.requireNonNull(query, "query");
        List<WorkItem> source;
        StagingArea area = callerStage.get();
        if (requiredQuery.executionId() != null && area != null
                && Objects.equals(requiredQuery.executionId(), area.executionId())) {
            source = area.view();
        } else {
            source = committedRows();
        }
        List<WorkItem> filtered = filter(source, requiredQuery).stream()
                .sorted(DISPATCH_ORDER)
                .toList();
        int start = Math.min(requiredQuery.page() * requiredQuery.size(), filtered.size());
        int end = Math.min(start + requiredQuery.size(), filtered.size());
        return filtered.subList(start, end);
    }

    @Override
    public long countWorkItems(WorkItemQuery query) {
        WorkItemQuery requiredQuery = Objects.requireNonNull(query, "query");
        StagingArea area = callerStage.get();
        List<WorkItem> source = requiredQuery.executionId() != null && area != null
                && Objects.equals(requiredQuery.executionId(), area.executionId())
                ? area.view()
                : committedRows();
        return filter(source, requiredQuery).size();
    }

    private List<WorkItem> filter(List<WorkItem> source, WorkItemQuery query) {
        return source.stream()
                .filter(item -> TenantStoreSupport.matchesCurrentTenant(item.identity()))
                .filter(item -> query.executionId() == null
                        || Objects.equals(query.executionId(), item.identity().executionId()))
                .filter(item -> query.itemType() == null || query.itemType() == item.itemType())
                .filter(item -> query.statuses().isEmpty() || query.statuses().contains(item.status()))
                .filter(item -> query.shardId() == null
                        || Objects.equals(query.shardId(), item.identity().shardId()))
                .filter(item -> query.claimOwner() == null
                        || Objects.equals(query.claimOwner(), item.claimOwner()))
                .filter(item -> query.nextAttemptBefore() == null
                        || item.nextAttemptAt() != null
                        && !item.nextAttemptAt().isAfter(query.nextAttemptBefore()))
                .filter(item -> query.createdAfter() == null
                        || !item.createdAt().isBefore(query.createdAfter()))
                .toList();
    }

    private void validateCreate(StagingArea area, WorkItem item) {
        var owningExecution = executionStore.get(area.executionId())
                .orElseThrow(() -> durability("Execution not found: " + area.executionId()));
        if (!sameLifecycleIdentity(owningExecution.identity(), item.identity())) {
            throw durability("Work-item identity does not match execution lifecycle for "
                    + area.executionId());
        }
        if (area.contains(item.itemId())) {
            throw durability("Work item already exists: " + item.itemId());
        }
        loadCommittedByIdUnscoped(item.itemId()).ifPresent(existing -> {
            if (!Objects.equals(existing.identity().executionId(), area.executionId())) {
                throw durability("Work item " + item.itemId()
                        + " is already committed for execution "
                        + existing.identity().executionId());
            }
            throw durability("Work item already exists: " + item.itemId());
        });
    }

    private boolean claimItemId(StagingArea area, String itemId, String executionId) {
        synchronized (registryLock) {
            String owner = activeItemOwners.get(itemId);
            if (owner != null && !Objects.equals(owner, executionId)) {
                throw durability("Work item " + itemId
                        + " is already owned by active execution " + owner);
            }
            if (owner == null) {
                activeItemOwners.put(itemId, executionId);
                area.claim(itemId);
                return true;
            }
            return false;
        }
    }

    private Set<String> claimItemIds(StagingArea area,
                                     Set<String> itemIds,
                                     String executionId) {
        synchronized (registryLock) {
            itemIds.forEach(itemId -> {
                String owner = activeItemOwners.get(itemId);
                if (owner != null && !Objects.equals(owner, executionId)) {
                    throw durability("Work item " + itemId
                            + " is already owned by active execution " + owner);
                }
            });
            Set<String> newlyClaimed = new LinkedHashSet<>();
            itemIds.forEach(itemId -> {
                if (activeItemOwners.putIfAbsent(itemId, executionId) == null) {
                    area.claim(itemId);
                    newlyClaimed.add(itemId);
                }
            });
            return Set.copyOf(newlyClaimed);
        }
    }

    private void releaseClaim(StagingArea area,
                              String itemId,
                              String executionId,
                              boolean newlyClaimed) {
        if (newlyClaimed) {
            synchronized (registryLock) {
                activeItemOwners.remove(itemId, executionId);
                area.releaseClaim(itemId);
            }
        }
    }

    private StagingArea mutationArea(String itemId) {
        String normalized = required(itemId, "itemId");
        StagingArea area = callerStage.get();
        if (area == null) {
            throw durability("BLOGE work-item mutation requires an active composite stage");
        }
        if (!area.contains(normalized)) {
            throw durability("Work item not found in the active execution stage: " + normalized);
        }
        return area;
    }

    private StagingArea requiredMutationArea(String itemId) {
        StagingArea area = mutationArea(itemId);
        if (!area.matchesCurrentTenant(itemId)) {
            throw durability("Work item is outside the active tenant scope: " + itemId);
        }
        return area;
    }

    private StagingArea stagingArea(String executionId) {
        String normalized = required(executionId, "executionId");
        StagingArea area = callerStage.get();
        if (area == null || !Objects.equals(normalized, area.executionId())) {
            throw durability("BLOGE work-item mutation requires an active composite stage for "
                    + normalized);
        }
        return area;
    }

    private StagingArea creationArea(String executionId) {
        String normalized = required(executionId, "executionId");
        StagingArea area = callerStage.get();
        if (area != null) {
            if (Objects.equals(normalized, area.executionId())) {
                return area;
            }
            throw durability("The caller stage is bound to a different execution");
        }
        area = activeStages.get(normalized);
        if (area == null) {
            throw durability("BLOGE work-item mutation requires an active composite stage for "
                    + normalized);
        }
        if (!GraphEngine.graphContextScope().isBound()) {
            throw durability("Async BLOGE work-item enqueue requires an engine execution scope");
        }
        return area;
    }

    private Optional<WorkItem> loadCommittedById(String itemId) {
        return loadCommittedByIdUnscoped(itemId)
                .filter(item -> TenantStoreSupport.matchesCurrentTenant(item.identity()));
    }

    private Optional<WorkItem> loadCommittedByIdUnscoped(String itemId) {
        return jdbc.query("""
                        SELECT payload_json FROM rg_test_bloge_work_items WHERE item_id = ?
                        """, (resultSet, rowNumber) -> read(resultSet.getString("payload_json")),
                itemId).stream().findFirst();
    }

    private List<WorkItem> loadCommitted(String executionId) {
        return jdbc.query("""
                        SELECT payload_json FROM rg_test_bloge_work_items
                        WHERE execution_id = ? ORDER BY priority DESC, created_at, item_id
                        """, (resultSet, rowNumber) -> read(resultSet.getString("payload_json")),
                executionId).stream()
                .filter(item -> TenantStoreSupport.matchesCurrentTenant(item.identity()))
                .toList();
    }

    private List<WorkItem> committedRows() {
        return jdbc.query("SELECT payload_json FROM rg_test_bloge_work_items",
                (resultSet, rowNumber) -> read(resultSet.getString("payload_json")));
    }

    private WorkItem read(String json) {
        try {
            return objectMapper.readValue(json, WorkItem.class);
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw durability("Stored BLOGE work item is corrupt", corrupt);
        }
    }

    private String write(WorkItem item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (JsonProcessingException failure) {
            throw durability("Failed to serialize BLOGE work item", failure);
        }
    }

    private void apply(JdbcTemplate transactionJdbc, PendingSnapshot snapshot) {
        requireTransactionParticipant(transactionJdbc);
        if (!snapshot.mutated()) {
            return;
        }
        snapshot.deletedItemIds().forEach(itemId -> transactionJdbc.update(
                "DELETE FROM rg_test_bloge_work_items WHERE item_id = ? AND execution_id = ?",
                itemId, snapshot.executionId()));
        snapshot.upserts().forEach(item -> upsert(transactionJdbc, item));
    }

    private void upsert(JdbcTemplate transactionJdbc, WorkItem item) {
        int changed = transactionJdbc.update("""
                UPDATE rg_test_bloge_work_items
                SET tenant_id = ?, namespace_id = ?, item_type = ?, shard_id = ?, priority = ?,
                    item_status = ?, claim_owner = ?, claim_until = ?, next_attempt_at = ?,
                    created_at = ?, payload_json = ?
                WHERE item_id = ? AND execution_id = ?
                """, item.identity().tenantId(), item.identity().namespace(), item.itemType().name(),
                item.identity().shardId(), item.priority(), item.status().name(), item.claimOwner(),
                timestamp(item.claimUntil()), timestamp(item.nextAttemptAt()),
                Timestamp.from(item.createdAt()), write(item), item.itemId(),
                item.identity().executionId());
        if (changed == 0) {
            transactionJdbc.update("""
                    INSERT INTO rg_test_bloge_work_items (
                        item_id, execution_id, tenant_id, namespace_id, item_type, shard_id,
                        priority, item_status, claim_owner, claim_until, next_attempt_at,
                        created_at, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, item.itemId(), item.identity().executionId(), item.identity().tenantId(),
                    item.identity().namespace(), item.itemType().name(), item.identity().shardId(),
                    item.priority(), item.status().name(), item.claimOwner(),
                    timestamp(item.claimUntil()), timestamp(item.nextAttemptAt()),
                    Timestamp.from(item.createdAt()), write(item));
        }
    }

    private void requireTransactionParticipant(JdbcTemplate transactionJdbc) {
        Objects.requireNonNull(transactionJdbc, "transactionJdbc");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw durability("BLOGE work-item mutation requires an active test-runtime transaction");
        }
        if (transactionJdbc.getDataSource() != dataSource) {
            throw durability("BLOGE work-item mutation used a different datasource");
        }
        if (!TransactionSynchronizationManager.hasResource(dataSource)) {
            throw durability("BLOGE work-item datasource is not bound to the active transaction");
        }
    }

    private static boolean isClaimable(WorkItem item, Instant now) {
        return item.status() == WorkItemStatus.READY
                || item.status() == WorkItemStatus.RETRY_WAIT
                && (item.nextAttemptAt() == null || !item.nextAttemptAt().isAfter(now))
                || item.status() == WorkItemStatus.CLAIMED
                && (item.claimUntil() == null || !item.claimUntil().isAfter(now));
    }

    private static boolean sameLifecycleIdentity(ExecutionIdentity lifecycle,
                                                 ExecutionIdentity workItem) {
        ExecutionIdentity normalizedWorkItem = workItem.withRouting(
                workItem.routeKey(), lifecycle.shardId());
        return Objects.equals(lifecycle, normalizedWorkItem);
    }

    private static Timestamp timestamp(Instant instant) {
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

    /** One execution-scoped work-item overlay. */
    public final class Stage implements AutoCloseable {
        private final StagingArea area;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Stage(StagingArea area) {
            this.area = area;
        }

        /**
         * Freezes the exact work-item operation set for one formal engine-state boundary.
         *
         * @param checkpointRef stable control-plane checkpoint reference
         * @param nodeId boundary node identifier
         * @param boundaryType supported durable boundary type
         * @param boundarySequence positive monotonic boundary sequence
         * @param stateVersion non-negative engine state version
         * @return idempotently replayable work-item mutation and component fingerprint
         */
        public PreparedMutation prepare(String checkpointRef,
                                        String nodeId,
                                        String boundaryType,
                                        long boundarySequence,
                                        long stateVersion) {
            if (closed.get()) {
                throw durability("Composite work-item stage is closed");
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

        /** Drops all process-local work-item state not committed through the repository. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (callerStage.get() != area) {
                    closed.set(false);
                    throw durability("Composite work-item stage must close on its owner thread");
                }
                synchronized (registryLock) {
                    area.close();
                    activeStages.remove(area.executionId(), area);
                    area.claimedItemIds().forEach(itemId ->
                            activeItemOwners.remove(itemId, area.executionId()));
                }
                callerStage.remove();
            }
        }
    }

    /** Frozen work-item mutation accepted only by the composite repository transaction. */
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
                    throw durability("Prepared BLOGE work-item mutation belongs to a closed stage");
                }
                owner.requirePreparedOpen();
                StagedBlogeWorkItemStore.this.apply(transactionJdbc, snapshot);
            }
        }
    }

    private static final class StagingArea {
        private final String executionId;
        private final InMemoryWorkItemStore delegate;
        private final Map<String, WorkItem> items = new LinkedHashMap<>();
        private final Set<String> initialItemIds;
        private final Set<String> claimedItemIds;
        private boolean mutated;
        private boolean prepared;
        private boolean closed;

        private StagingArea(String executionId,
                            TimeSource timeSource,
                            ExecutionStore executionStore,
                            List<WorkItem> committed) {
            this.executionId = executionId;
            this.delegate = new InMemoryWorkItemStore(timeSource, executionStore);
            committed.forEach(item -> {
                delegate.create(item);
                items.put(item.itemId(), item);
            });
            this.initialItemIds = committed.stream()
                    .map(WorkItem::itemId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            this.claimedItemIds = new LinkedHashSet<>(initialItemIds);
        }

        synchronized void create(WorkItem item) {
            requireMutable();
            if (items.containsKey(item.itemId())) {
                throw durability("Work item already exists: " + item.itemId());
            }
            delegate.create(item);
            items.put(item.itemId(), item);
            mutated = true;
        }

        synchronized void createBatch(List<WorkItem> batch) {
            requireMutable();
            if (batch.stream().map(WorkItem::itemId).anyMatch(items::containsKey)) {
                throw durability("A work-item batch would replace an existing item");
            }
            delegate.createBatch(batch);
            batch.forEach(item -> items.put(item.itemId(), item));
            mutated = true;
        }

        synchronized Optional<WorkItem> get(String itemId) {
            if (closed) {
                return Optional.empty();
            }
            WorkItem item = items.get(itemId);
            return item != null && TenantStoreSupport.matchesCurrentTenant(item.identity())
                    ? Optional.of(item)
                    : Optional.empty();
        }

        synchronized List<WorkItem> view() {
            if (closed) {
                return List.of();
            }
            return items.values().stream()
                    .filter(item -> TenantStoreSupport.matchesCurrentTenant(item.identity()))
                    .sorted(DISPATCH_ORDER)
                    .toList();
        }

        synchronized boolean contains(String itemId) {
            return !closed && items.containsKey(itemId);
        }

        synchronized boolean matchesCurrentTenant(String itemId) {
            WorkItem item = items.get(itemId);
            return item != null && TenantStoreSupport.matchesCurrentTenant(item.identity());
        }

        synchronized Optional<WorkItem> claim(String itemId,
                                              String owner,
                                              Duration leaseDuration,
                                              long expectedVersion) {
            requireMutable();
            Optional<WorkItem> claimed = delegate.claim(
                    itemId, owner, leaseDuration, expectedVersion);
            claimed.ifPresent(item -> recordMutation(itemId, item));
            return claimed;
        }

        synchronized Optional<WorkItem> renewClaim(String itemId,
                                                   String leaseToken,
                                                   Duration extension) {
            requireMutable();
            Optional<WorkItem> renewed = delegate.renewClaim(itemId, leaseToken, extension);
            renewed.ifPresent(item -> recordMutation(itemId, item));
            return renewed;
        }

        synchronized void markDone(String itemId, String leaseToken, long expectedVersion) {
            requireMutable();
            delegate.markDone(itemId, leaseToken, expectedVersion);
            recordDelegateMutation(itemId);
        }

        synchronized void markRetryWait(String itemId,
                                        String leaseToken,
                                        Instant nextAttemptAt,
                                        long expectedVersion) {
            requireMutable();
            delegate.markRetryWait(itemId, leaseToken, nextAttemptAt, expectedVersion);
            recordDelegateMutation(itemId);
        }

        synchronized void markRetryWait(String itemId,
                                        String leaseToken,
                                        Instant nextAttemptAt,
                                        DeadLetterPolicy.Classification classification,
                                        long expectedVersion) {
            requireMutable();
            delegate.markRetryWait(
                    itemId, leaseToken, nextAttemptAt, classification, expectedVersion);
            recordDelegateMutation(itemId);
        }

        synchronized void markFailed(String itemId, String leaseToken, long expectedVersion) {
            requireMutable();
            delegate.markFailed(itemId, leaseToken, expectedVersion);
            recordDelegateMutation(itemId);
        }

        synchronized void markDeadLetter(String itemId, String reason) {
            requireMutable();
            delegate.markDeadLetter(itemId, reason);
            recordDelegateMutation(itemId);
        }

        synchronized void markDeadLetter(String itemId, String reason, String failureClass) {
            requireMutable();
            delegate.markDeadLetter(itemId, reason, failureClass);
            recordDelegateMutation(itemId);
        }

        synchronized WorkItem restoreDeadLetter(String itemId) {
            requireMutable();
            WorkItem restored = delegate.restoreDeadLetter(itemId);
            recordMutation(itemId, restored);
            return restored;
        }

        synchronized WorkItem discardDeadLetter(String itemId) {
            requireMutable();
            WorkItem discarded = delegate.discardDeadLetter(itemId);
            recordMutation(itemId, discarded);
            return discarded;
        }

        synchronized int cancelByExecution(String executionId, String reason) {
            requireMutable();
            int cancelled = delegate.cancelByExecution(executionId, reason);
            if (cancelled > 0) {
                List.copyOf(items.keySet()).forEach(this::recordDelegateMutation);
            }
            return cancelled;
        }

        synchronized PendingSnapshot prepare() {
            requireMutable();
            prepared = true;
            List<WorkItem> finalItems = items.values().stream().sorted(DISPATCH_ORDER).toList();
            Set<String> finalIds = finalItems.stream()
                    .map(WorkItem::itemId)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> deleted = initialItemIds.stream()
                    .filter(itemId -> !finalIds.contains(itemId))
                    .sorted()
                    .toList();
            return new PendingSnapshot(executionId, mutated,
                    mutated ? finalItems : List.of(), deleted);
        }

        synchronized void claim(String itemId) {
            claimedItemIds.add(itemId);
        }

        synchronized void releaseClaim(String itemId) {
            claimedItemIds.remove(itemId);
        }

        synchronized Set<String> claimedItemIds() {
            return Set.copyOf(claimedItemIds);
        }

        synchronized Set<String> initialItemIds() {
            return Set.copyOf(initialItemIds);
        }

        synchronized String executionId() {
            return executionId;
        }

        private void recordDelegateMutation(String itemId) {
            WorkItem updated = delegate.get(itemId)
                    .orElseThrow(() -> durability(
                            "Work item disappeared after a staged transition: " + itemId));
            recordMutation(itemId, updated);
        }

        private void recordMutation(String itemId, WorkItem updated) {
            WorkItem previous = items.put(itemId, updated);
            mutated = mutated || !Objects.equals(previous, updated);
        }

        synchronized void requirePreparedOpen() {
            if (closed || !prepared) {
                throw durability("Prepared BLOGE work-item mutation belongs to a closed stage");
            }
        }

        synchronized void close() {
            closed = true;
        }

        private void requireMutable() {
            if (closed) {
                throw durability("Composite work-item stage is closed");
            }
            if (prepared) {
                throw durability("Composite work-item stage is already prepared");
            }
        }
    }

    private record PendingSnapshot(String executionId,
                                   boolean mutated,
                                   List<WorkItem> upserts,
                                   List<String> deletedItemIds) {
        private PendingSnapshot {
            upserts = List.copyOf(upserts);
            deletedItemIds = List.copyOf(deletedItemIds);
        }

        Map<String, Object> fingerprintMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", CLOSURE_SCHEMA_VERSION);
            material.put("engineExecutionId", executionId);
            material.put("mutated", mutated);
            material.put("upserts", new ArrayList<>(upserts));
            material.put("deletedItemIds", new ArrayList<>(deletedItemIds));
            return material;
        }
    }
}
