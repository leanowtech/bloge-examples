package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Database-clock fixed-partition recovery fleet coordinator shared by every service replica.
 *
 * <p>A fleet identity lock linearizes inventory generation acceptance and short assignment
 * mutations. Partition work never executes while that lock is held. Each acquired row carries a
 * fleet epoch, monotonically increasing partition lease epoch, random token, and exact expiry
 * revision. A newer inventory generation increments the fleet epoch and clears every owner,
 * immediately fencing older renewals and completions while preserving each partition's last
 * committed lane cursor.</p>
 *
 * <p>The database clock is the sole lease authority. Expired assignments may be taken over but
 * stale workers cannot resurrect or complete them. Whole-record fingerprints fail closed on local
 * state corruption. Fixed partitions permit bounded cross-replica parallelism; a durable cyclic
 * partition cursor and per-partition lane cursor prevent process restarts from resetting fairness.</p>
 */
public final class DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
        implements ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator {

    private static final String FLEET_RECORD_SCHEMA =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetRecord.v1";
    private static final String PARTITION_RECORD_SCHEMA =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetPartitionRecord.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern TOKEN = Pattern.compile("[a-f0-9]{32}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate mutations;

    /**
     * Creates a coordinator on the isolated test-runtime database.
     *
     * @param jdbc JDBC facade whose database clock is authoritative for leases
     * @param objectMapper canonical whole-record fingerprint mapper
     * @param transactionManager manager bound to the same datasource as {@code jdbc}
     */
    public DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.mutations = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        this.mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates fleet lock, manifest, and fixed-partition state tables idempotently. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_external_sequence_anchor_bootstrap_root_recovery_fleet_locks (
                    fleet_id VARCHAR(255) NOT NULL PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_external_sequence_anchor_bootstrap_root_recovery_fleets (
                    fleet_id VARCHAR(255) NOT NULL PRIMARY KEY,
                    inventory_generation BIGINT NOT NULL,
                    inventory_fingerprint VARCHAR(71) NOT NULL,
                    partition_count INTEGER NOT NULL,
                    fleet_epoch BIGINT NOT NULL,
                    last_partition_id INTEGER NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_external_sequence_anchor_bootstrap_root_recovery_fleet_partitions (
                    fleet_id VARCHAR(255) NOT NULL,
                    partition_id INTEGER NOT NULL,
                    cursor_scope_id VARCHAR(255),
                    cursor_root_set_id VARCHAR(255),
                    lease_epoch BIGINT NOT NULL,
                    lease_owner VARCHAR(255),
                    lease_token VARCHAR(32),
                    lease_command_id VARCHAR(32),
                    lease_duration_seconds BIGINT,
                    lease_expires_at TIMESTAMP WITH TIME ZONE,
                    last_completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (fleet_id, partition_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    rg_external_bootstrap_recovery_fleet_partition_lease_idx
                ON rg_external_sequence_anchor_bootstrap_root_recovery_fleet_partitions
                    (fleet_id, lease_expires_at, partition_id)
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Acquisition acquire(AcquisitionCommand command) {
        AcquisitionCommand safe = Objects.requireNonNull(command, "command");
        return Objects.requireNonNull(mutations.execute(status -> acquireLocked(safe)),
                "acquisition");
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Lease> renew(Lease lease) {
        Lease safe = Objects.requireNonNull(lease, "lease");
        return Objects.requireNonNull(mutations.execute(status -> renewLocked(safe)),
                "renewal");
    }

    /** {@inheritDoc} */
    @Override
    public CompletionStatus complete(Lease lease, LaneKey lastAttempted) {
        Lease safe = Objects.requireNonNull(lease, "lease");
        if (lastAttempted != null && !safe.owns(lastAttempted)) {
            throw new IllegalArgumentException(
                    "Recovery fleet completion cursor belongs to another partition");
        }
        return Objects.requireNonNull(
                mutations.execute(status -> completeLocked(safe, lastAttempted)),
                "completion");
    }

    /** {@inheritDoc} */
    @Override
    public AbandonStatus abandon(Lease lease) {
        Lease safe = Objects.requireNonNull(lease, "lease");
        return Objects.requireNonNull(
                mutations.execute(status -> abandonLocked(safe)), "abandonment");
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private Acquisition acquireLocked(AcquisitionCommand command) {
        FleetManifest requested = command.manifest();
        lockFleet(requested.fleetId());
        Instant now = databaseNow();
        StoredFleet fleet = currentFleet(requested.fleetId());
        if (fleet == null) {
            fleet = initialize(requested, now);
        } else {
            requireValid(fleet);
            fleet = acceptManifest(fleet, requested, now);
        }
        List<StoredPartition> partitions = currentPartitions(fleet);
        StoredPartition replay = activeCommand(partitions, command, now);
        if (replay != null) {
            return Acquisition.acquired(lease(fleet, replay));
        }
        StoredPartition available = nextAvailable(fleet, partitions, now);
        if (available == null) {
            return Acquisition.busy();
        }
        if (available.leaseEpoch() == Long.MAX_VALUE) {
            throw new IllegalStateException("Recovery fleet partition lease epoch exhausted");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = now.plus(command.leaseDurationSeconds(), ChronoUnit.SECONDS);
        StoredPartition acquired = available.acquire(
                available.leaseEpoch() + 1L, command.workerId(), token, command.commandId(),
                command.leaseDurationSeconds(), expiresAt, now);
        persistPartition(acquired);
        StoredFleet advanced = fleet.withLastPartition(acquired.partitionId(), now, "");
        advanced = advanced.withRecordFingerprint(fleetFingerprint(advanced));
        persistFleet(advanced);
        return Acquisition.acquired(lease(advanced, acquired));
    }

    private Optional<Lease> renewLocked(Lease lease) {
        lockFleet(lease.manifest().fleetId());
        Instant now = databaseNow();
        StoredFleet fleet = currentFleet(lease.manifest().fleetId());
        if (fleet == null) {
            return Optional.empty();
        }
        requireValid(fleet);
        if (lease.partitionId() >= fleet.partitionCount()) {
            return Optional.empty();
        }
        StoredPartition partition = partition(fleet, lease.partitionId());
        if (!matches(fleet, partition, lease) || !partition.leaseExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        Instant expiresAt = now.plus(lease.leaseDurationSeconds(), ChronoUnit.SECONDS);
        StoredPartition renewed = partition.renew(expiresAt, now);
        persistPartition(renewed);
        return Optional.of(lease(fleet, renewed));
    }

    private CompletionStatus completeLocked(Lease lease, LaneKey lastAttempted) {
        lockFleet(lease.manifest().fleetId());
        Instant now = databaseNow();
        StoredFleet fleet = currentFleet(lease.manifest().fleetId());
        if (fleet == null) {
            return CompletionStatus.FENCED;
        }
        requireValid(fleet);
        if (lease.partitionId() >= fleet.partitionCount()) {
            return CompletionStatus.FENCED;
        }
        StoredPartition partition = partition(fleet, lease.partitionId());
        if (!matches(fleet, partition, lease) || !partition.leaseExpiresAt().isAfter(now)) {
            return CompletionStatus.FENCED;
        }
        StoredPartition completed = partition.complete(lastAttempted, now);
        persistPartition(completed);
        return CompletionStatus.COMPLETED;
    }

    private AbandonStatus abandonLocked(Lease lease) {
        lockFleet(lease.manifest().fleetId());
        Instant now = databaseNow();
        StoredFleet fleet = currentFleet(lease.manifest().fleetId());
        if (fleet == null) {
            return AbandonStatus.FENCED;
        }
        requireValid(fleet);
        if (lease.partitionId() >= fleet.partitionCount()) {
            return AbandonStatus.FENCED;
        }
        StoredPartition partition = partition(fleet, lease.partitionId());
        if (!matches(fleet, partition, lease) || !partition.leaseExpiresAt().isAfter(now)) {
            return AbandonStatus.FENCED;
        }
        persistPartition(partition.abandon(now));
        return AbandonStatus.ABANDONED;
    }

    private StoredFleet initialize(FleetManifest manifest, Instant now) {
        StoredFleet fleet = new StoredFleet(manifest.fleetId(), manifest.inventoryGeneration(),
                manifest.inventoryFingerprint(), manifest.partitionCount(), 1L, -1, now, "");
        fleet = fleet.withRecordFingerprint(fleetFingerprint(fleet));
        insertFleet(fleet);
        for (int partitionId = 0; partitionId < manifest.partitionCount(); partitionId++) {
            StoredPartition partition = StoredPartition.empty(
                    manifest.fleetId(), partitionId, now);
            insertPartition(partition.withRecordFingerprint(partitionFingerprint(partition)));
        }
        return fleet;
    }

    private StoredFleet acceptManifest(
            StoredFleet fleet, FleetManifest requested, Instant now) {
        if (fleet.partitionCount() != requested.partitionCount()) {
            throw new IllegalArgumentException(
                    "Recovery fleet partition topology is immutable; use a new fleetId");
        }
        if (requested.inventoryGeneration() < fleet.inventoryGeneration()) {
            throw new IllegalArgumentException("Recovery fleet inventory generation rolled back");
        }
        if (requested.inventoryGeneration() == fleet.inventoryGeneration()) {
            if (!requested.inventoryFingerprint().equals(fleet.inventoryFingerprint())) {
                throw new IllegalArgumentException(
                        "Recovery fleet inventory drifted at the same generation");
            }
            return fleet;
        }
        if (fleet.fleetEpoch() == Long.MAX_VALUE) {
            throw new IllegalStateException("Recovery fleet generation epoch exhausted");
        }
        List<StoredPartition> partitions = currentPartitions(fleet);
        for (StoredPartition partition : partitions) {
            StoredPartition fenced = partition.fence(now);
            persistPartition(fenced);
        }
        StoredFleet advanced = new StoredFleet(fleet.fleetId(),
                requested.inventoryGeneration(), requested.inventoryFingerprint(),
                fleet.partitionCount(), fleet.fleetEpoch() + 1L, fleet.lastPartitionId(), now, "");
        advanced = advanced.withRecordFingerprint(fleetFingerprint(advanced));
        persistFleet(advanced);
        return advanced;
    }

    private StoredPartition nextAvailable(
            StoredFleet fleet, List<StoredPartition> partitions, Instant now) {
        for (int offset = 1; offset <= fleet.partitionCount(); offset++) {
            int partitionId = Math.floorMod(fleet.lastPartitionId() + offset,
                    fleet.partitionCount());
            StoredPartition candidate = partitions.get(partitionId);
            if (candidate.leaseExpiresAt() == null
                    || !candidate.leaseExpiresAt().isAfter(now)) {
                return candidate;
            }
        }
        return null;
    }

    private StoredPartition activeCommand(
            List<StoredPartition> partitions, AcquisitionCommand command, Instant now) {
        for (StoredPartition partition : partitions) {
            if (!command.commandId().equals(partition.leaseCommandId())
                    || partition.leaseExpiresAt() == null
                    || !partition.leaseExpiresAt().isAfter(now)) {
                continue;
            }
            if (!command.workerId().equals(partition.leaseOwner())
                    || command.leaseDurationSeconds() != partition.leaseDurationSeconds()) {
                throw new IllegalArgumentException(
                        "Recovery fleet acquisition command replay drifted");
            }
            return partition;
        }
        return null;
    }

    private boolean matches(StoredFleet fleet, StoredPartition partition, Lease lease) {
        FleetManifest manifest = lease.manifest();
        return fleet.fleetId().equals(manifest.fleetId())
                && fleet.inventoryGeneration() == manifest.inventoryGeneration()
                && fleet.inventoryFingerprint().equals(manifest.inventoryFingerprint())
                && fleet.partitionCount() == manifest.partitionCount()
                && fleet.fleetEpoch() == lease.fleetEpoch()
                && partition.leaseEpoch() == lease.leaseEpoch()
                && Objects.equals(partition.leaseOwner(), lease.workerId())
                && Objects.equals(partition.leaseToken(), lease.leaseToken())
                && Objects.equals(partition.leaseCommandId(), lease.commandId())
                && partition.leaseDurationSeconds() == lease.leaseDurationSeconds()
                && Objects.equals(partition.leaseExpiresAt(), lease.leaseExpiresAt());
    }

    private Lease lease(StoredFleet fleet, StoredPartition partition) {
        FleetManifest manifest = new FleetManifest(FleetManifest.SCHEMA_VERSION,
                fleet.fleetId(), fleet.inventoryGeneration(), fleet.inventoryFingerprint(),
                fleet.partitionCount());
        return new Lease(Lease.SCHEMA_VERSION, manifest, partition.partitionId(),
                fleet.fleetEpoch(), partition.leaseEpoch(), partition.leaseToken(),
                partition.leaseOwner(), partition.leaseCommandId(),
                partition.leaseDurationSeconds(), partition.leaseExpiresAt(), partition.cursor());
    }

    private void lockFleet(String fleetId) {
        jdbc.update("""
                MERGE INTO rg_external_sequence_anchor_bootstrap_root_recovery_fleet_locks
                    (fleet_id) KEY (fleet_id) VALUES (?)
                """, fleetId);
        jdbc.queryForObject("""
                SELECT fleet_id
                FROM rg_external_sequence_anchor_bootstrap_root_recovery_fleet_locks
                WHERE fleet_id = ? FOR UPDATE
                """, String.class, fleetId);
    }

    private StoredFleet currentFleet(String fleetId) {
        List<StoredFleet> rows = jdbc.query("""
                SELECT fleet_id, inventory_generation, inventory_fingerprint, partition_count,
                       fleet_epoch, last_partition_id, updated_at, record_fingerprint
                FROM rg_external_sequence_anchor_bootstrap_root_recovery_fleets
                WHERE fleet_id = ?
                """, this::fleetRow, fleetId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate recovery fleet state");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<StoredPartition> currentPartitions(StoredFleet fleet) {
        List<StoredPartition> rows = new ArrayList<>(jdbc.query("""
                SELECT fleet_id, partition_id, cursor_scope_id, cursor_root_set_id,
                       lease_epoch, lease_owner, lease_token, lease_command_id,
                       lease_duration_seconds, lease_expires_at, last_completed_at,
                       updated_at, record_fingerprint
                FROM rg_external_sequence_anchor_bootstrap_root_recovery_fleet_partitions
                WHERE fleet_id = ? ORDER BY partition_id
                """, this::partitionRow, fleet.fleetId()));
        rows.sort(Comparator.comparingInt(StoredPartition::partitionId));
        Set<Integer> identifiers = new HashSet<>();
        if (rows.size() != fleet.partitionCount()
                || rows.stream().anyMatch(row -> !row.valid(objectMapper)
                || !fleet.fleetId().equals(row.fleetId())
                || row.partitionId() < 0 || row.partitionId() >= fleet.partitionCount()
                || row.cursor() != null
                && ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.partitionFor(
                        row.cursor(), fleet.partitionCount()) != row.partitionId()
                || !identifiers.add(row.partitionId()))) {
            throw new IllegalStateException("Recovery fleet partition state is corrupt");
        }
        for (int expected = 0; expected < rows.size(); expected++) {
            if (rows.get(expected).partitionId() != expected) {
                throw new IllegalStateException("Recovery fleet partition state is corrupt");
            }
        }
        return List.copyOf(rows);
    }

    private StoredPartition partition(StoredFleet fleet, int partitionId) {
        List<StoredPartition> partitions = currentPartitions(fleet);
        return partitions.get(partitionId);
    }

    private void requireValid(StoredFleet fleet) {
        if (!fleet.valid(objectMapper)) {
            throw new IllegalStateException("Recovery fleet state is corrupt");
        }
    }

    private void insertFleet(StoredFleet fleet) {
        jdbc.update("""
                INSERT INTO rg_external_sequence_anchor_bootstrap_root_recovery_fleets (
                    fleet_id, inventory_generation, inventory_fingerprint, partition_count,
                    fleet_epoch, last_partition_id, updated_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, fleet.fleetId(), fleet.inventoryGeneration(), fleet.inventoryFingerprint(),
                fleet.partitionCount(), fleet.fleetEpoch(), fleet.lastPartitionId(),
                timestamp(fleet.updatedAt()), fleet.recordFingerprint());
    }

    private void persistFleet(StoredFleet fleet) {
        int updated = jdbc.update("""
                UPDATE rg_external_sequence_anchor_bootstrap_root_recovery_fleets
                SET inventory_generation = ?, inventory_fingerprint = ?, partition_count = ?,
                    fleet_epoch = ?, last_partition_id = ?, updated_at = ?,
                    record_fingerprint = ? WHERE fleet_id = ?
                """, fleet.inventoryGeneration(), fleet.inventoryFingerprint(),
                fleet.partitionCount(), fleet.fleetEpoch(), fleet.lastPartitionId(),
                timestamp(fleet.updatedAt()), fleet.recordFingerprint(), fleet.fleetId());
        if (updated != 1) {
            throw new IllegalStateException("Recovery fleet state disappeared while locked");
        }
    }

    private void insertPartition(StoredPartition partition) {
        jdbc.update("""
                INSERT INTO
                    rg_external_sequence_anchor_bootstrap_root_recovery_fleet_partitions (
                    fleet_id, partition_id, cursor_scope_id, cursor_root_set_id, lease_epoch,
                    lease_owner, lease_token, lease_command_id, lease_duration_seconds,
                    lease_expires_at, last_completed_at, updated_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, partition.fleetId(), partition.partitionId(), partition.cursorScopeId(),
                partition.cursorRootSetId(), partition.leaseEpoch(), partition.leaseOwner(),
                partition.leaseToken(), partition.leaseCommandId(),
                nullableLong(partition.leaseDurationSeconds()),
                timestamp(partition.leaseExpiresAt()),
                timestamp(partition.lastCompletedAt()), timestamp(partition.updatedAt()),
                partition.recordFingerprint());
    }

    private void persistPartition(StoredPartition partition) {
        StoredPartition signed = partition.withRecordFingerprint(
                partitionFingerprint(partition));
        int updated = jdbc.update("""
                UPDATE rg_external_sequence_anchor_bootstrap_root_recovery_fleet_partitions
                SET cursor_scope_id = ?, cursor_root_set_id = ?, lease_epoch = ?,
                    lease_owner = ?, lease_token = ?, lease_command_id = ?,
                    lease_duration_seconds = ?, lease_expires_at = ?, last_completed_at = ?,
                    updated_at = ?, record_fingerprint = ?
                WHERE fleet_id = ? AND partition_id = ?
                """, signed.cursorScopeId(), signed.cursorRootSetId(), signed.leaseEpoch(),
                signed.leaseOwner(), signed.leaseToken(), signed.leaseCommandId(),
                nullableLong(signed.leaseDurationSeconds()),
                timestamp(signed.leaseExpiresAt()),
                timestamp(signed.lastCompletedAt()), timestamp(signed.updatedAt()),
                signed.recordFingerprint(), signed.fleetId(), signed.partitionId());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Recovery fleet partition disappeared while locked");
        }
    }

    private StoredFleet fleetRow(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredFleet(rs.getString("fleet_id"),
                rs.getLong("inventory_generation"), rs.getString("inventory_fingerprint"),
                rs.getInt("partition_count"), rs.getLong("fleet_epoch"),
                rs.getInt("last_partition_id"), instant(rs, "updated_at"),
                rs.getString("record_fingerprint"));
    }

    private StoredPartition partitionRow(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredPartition(rs.getString("fleet_id"), rs.getInt("partition_id"),
                rs.getString("cursor_scope_id"), rs.getString("cursor_root_set_id"),
                rs.getLong("lease_epoch"), rs.getString("lease_owner"),
                rs.getString("lease_token"), rs.getString("lease_command_id"),
                nullableLong(rs, "lease_duration_seconds"), instant(rs, "lease_expires_at"),
                instant(rs, "last_completed_at"), instant(rs, "updated_at"),
                rs.getString("record_fingerprint"));
    }

    private String fleetFingerprint(StoredFleet fleet) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", FLEET_RECORD_SCHEMA),
                Map.entry("fleetId", fleet.fleetId()),
                Map.entry("inventoryGeneration", fleet.inventoryGeneration()),
                Map.entry("inventoryFingerprint", fleet.inventoryFingerprint()),
                Map.entry("partitionCount", fleet.partitionCount()),
                Map.entry("fleetEpoch", fleet.fleetEpoch()),
                Map.entry("lastPartitionId", fleet.lastPartitionId()),
                Map.entry("updatedAt", fleet.updatedAt().toString())));
    }

    private String partitionFingerprint(StoredPartition partition) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", PARTITION_RECORD_SCHEMA),
                Map.entry("fleetId", partition.fleetId()),
                Map.entry("partitionId", partition.partitionId()),
                Map.entry("cursorScopeId", text(partition.cursorScopeId())),
                Map.entry("cursorRootSetId", text(partition.cursorRootSetId())),
                Map.entry("leaseEpoch", partition.leaseEpoch()),
                Map.entry("leaseOwner", text(partition.leaseOwner())),
                Map.entry("leaseToken", text(partition.leaseToken())),
                Map.entry("leaseCommandId", text(partition.leaseCommandId())),
                Map.entry("leaseDurationSeconds", partition.leaseDurationSeconds()),
                Map.entry("leaseExpiresAt", time(partition.leaseExpiresAt())),
                Map.entry("lastCompletedAt", time(partition.lastCompletedAt())),
                Map.entry("updatedAt", partition.updatedAt().toString())));
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static long nullableLong(ResultSet rs, String column) throws SQLException {
        Long value = rs.getObject(column, Long.class);
        return value == null ? 0L : value;
    }

    private static Long nullableLong(long value) {
        return value == 0L ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String time(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record StoredFleet(
            String fleetId,
            long inventoryGeneration,
            String inventoryFingerprint,
            int partitionCount,
            long fleetEpoch,
            int lastPartitionId,
            Instant updatedAt,
            String recordFingerprint) {

        private StoredFleet withLastPartition(
                int partitionId, Instant changedAt, String fingerprint) {
            return new StoredFleet(fleetId, inventoryGeneration, inventoryFingerprint,
                    partitionCount, fleetEpoch, partitionId, changedAt, fingerprint);
        }

        private StoredFleet withRecordFingerprint(String fingerprint) {
            return new StoredFleet(fleetId, inventoryGeneration, inventoryFingerprint,
                    partitionCount, fleetEpoch, lastPartitionId, updatedAt, fingerprint);
        }

        private boolean valid(ObjectMapper mapper) {
            try {
                if (!IDENTIFIER.matcher(fleetId).matches() || inventoryGeneration < 1L
                        || !FINGERPRINT.matcher(inventoryFingerprint).matches()
                        || partitionCount < 1 || partitionCount > MAXIMUM_PARTITIONS
                        || fleetEpoch < 1L || lastPartitionId < -1
                        || lastPartitionId >= partitionCount || updatedAt == null
                        || !FINGERPRINT.matcher(recordFingerprint).matches()) {
                    return false;
                }
                return ProtocolFingerprint.of(mapper, Map.ofEntries(
                        Map.entry("schemaVersion", FLEET_RECORD_SCHEMA),
                        Map.entry("fleetId", fleetId),
                        Map.entry("inventoryGeneration", inventoryGeneration),
                        Map.entry("inventoryFingerprint", inventoryFingerprint),
                        Map.entry("partitionCount", partitionCount),
                        Map.entry("fleetEpoch", fleetEpoch),
                        Map.entry("lastPartitionId", lastPartitionId),
                        Map.entry("updatedAt", updatedAt.toString())))
                        .equals(recordFingerprint);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private record StoredPartition(
            String fleetId,
            int partitionId,
            String cursorScopeId,
            String cursorRootSetId,
            long leaseEpoch,
            String leaseOwner,
            String leaseToken,
            String leaseCommandId,
            long leaseDurationSeconds,
            Instant leaseExpiresAt,
            Instant lastCompletedAt,
            Instant updatedAt,
            String recordFingerprint) {

        private static StoredPartition empty(
                String fleetId, int partitionId, Instant now) {
            return new StoredPartition(fleetId, partitionId, null, null, 0L,
                    null, null, null, 0L, null, null, now, "");
        }

        private StoredPartition acquire(
                long epoch,
                String owner,
                String token,
                String commandId,
                long durationSeconds,
                Instant expiresAt,
                Instant changedAt) {
            return new StoredPartition(fleetId, partitionId, cursorScopeId, cursorRootSetId,
                    epoch, owner, token, commandId, durationSeconds, expiresAt,
                    lastCompletedAt, changedAt, "");
        }

        private StoredPartition renew(Instant expiresAt, Instant changedAt) {
            return new StoredPartition(fleetId, partitionId, cursorScopeId, cursorRootSetId,
                    leaseEpoch, leaseOwner, leaseToken, leaseCommandId, leaseDurationSeconds,
                    expiresAt, lastCompletedAt, changedAt, "");
        }

        private StoredPartition complete(LaneKey cursor, Instant changedAt) {
            return new StoredPartition(fleetId, partitionId,
                    cursor == null ? cursorScopeId : cursor.scopeId(),
                    cursor == null ? cursorRootSetId : cursor.rootSetId(),
                    leaseEpoch, null, null, null, 0L, null, changedAt, changedAt, "");
        }

        private StoredPartition abandon(Instant changedAt) {
            return new StoredPartition(fleetId, partitionId, cursorScopeId, cursorRootSetId,
                    leaseEpoch, null, null, null, 0L, null, lastCompletedAt, changedAt, "");
        }

        private StoredPartition fence(Instant changedAt) {
            return new StoredPartition(fleetId, partitionId, cursorScopeId, cursorRootSetId,
                    leaseEpoch, null, null, null, 0L, null, lastCompletedAt, changedAt, "");
        }

        private StoredPartition withRecordFingerprint(String fingerprint) {
            return new StoredPartition(fleetId, partitionId, cursorScopeId, cursorRootSetId,
                    leaseEpoch, leaseOwner, leaseToken, leaseCommandId, leaseDurationSeconds,
                    leaseExpiresAt, lastCompletedAt, updatedAt, fingerprint);
        }

        private LaneKey cursor() {
            return cursorScopeId == null ? null : new LaneKey(cursorScopeId, cursorRootSetId);
        }

        private boolean valid(ObjectMapper mapper) {
            try {
                boolean cursorShape = cursorScopeId == null && cursorRootSetId == null
                        || cursorScopeId != null && cursorRootSetId != null;
                boolean leaseShape = leaseOwner == null && leaseToken == null
                        && leaseCommandId == null && leaseDurationSeconds == 0L
                        && leaseExpiresAt == null
                        || leaseOwner != null && leaseToken != null && leaseCommandId != null
                        && leaseDurationSeconds >= 3L && leaseDurationSeconds <= 300L
                        && leaseExpiresAt != null;
                if (!IDENTIFIER.matcher(fleetId).matches() || partitionId < 0
                        || leaseEpoch < 0L || !cursorShape || !leaseShape
                        || cursorScopeId != null && (!IDENTIFIER.matcher(cursorScopeId).matches()
                        || !IDENTIFIER.matcher(cursorRootSetId).matches())
                        || leaseOwner != null && (!IDENTIFIER.matcher(leaseOwner).matches()
                        || !TOKEN.matcher(leaseToken).matches()
                        || !TOKEN.matcher(leaseCommandId).matches())
                        || updatedAt == null || !FINGERPRINT.matcher(recordFingerprint).matches()) {
                    return false;
                }
                return ProtocolFingerprint.of(mapper, Map.ofEntries(
                        Map.entry("schemaVersion", PARTITION_RECORD_SCHEMA),
                        Map.entry("fleetId", fleetId),
                        Map.entry("partitionId", partitionId),
                        Map.entry("cursorScopeId", text(cursorScopeId)),
                        Map.entry("cursorRootSetId", text(cursorRootSetId)),
                        Map.entry("leaseEpoch", leaseEpoch),
                        Map.entry("leaseOwner", text(leaseOwner)),
                        Map.entry("leaseToken", text(leaseToken)),
                        Map.entry("leaseCommandId", text(leaseCommandId)),
                        Map.entry("leaseDurationSeconds", leaseDurationSeconds),
                        Map.entry("leaseExpiresAt", time(leaseExpiresAt)),
                        Map.entry("lastCompletedAt", time(lastCompletedAt)),
                        Map.entry("updatedAt", updatedAt.toString())))
                        .equals(recordFingerprint);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }
}
