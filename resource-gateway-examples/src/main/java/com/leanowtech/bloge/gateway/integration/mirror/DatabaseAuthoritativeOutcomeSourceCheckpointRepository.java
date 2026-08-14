package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** JDBC durable checkpoint, staged-page, backfill, and generation-revocation authority. */
public final class DatabaseAuthoritativeOutcomeSourceCheckpointRepository
        implements AuthoritativeOutcomeSourceCheckpointRepository {
    private static final int MAXIMUM_SNAPSHOT_BYTES = 512 * 1024;
    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_source_checkpoint_locks (
                region VARCHAR(96) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                PRIMARY KEY (region, environment_id)
            )
            """;
    private static final String CREATE_COMMANDS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_source_commands (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                command_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                command_fingerprint VARCHAR(71) NOT NULL,
                connector_id VARCHAR(512) NOT NULL,
                connector_generation BIGINT NOT NULL,
                command_type VARCHAR(32) NOT NULL,
                affected_stream_count INTEGER NOT NULL,
                command_json TEXT NOT NULL,
                admitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, command_id, revision
                )
            )
            """;
    private static final String CREATE_CHECKPOINTS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_source_checkpoints (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                connector_id VARCHAR(512) NOT NULL,
                connector_generation BIGINT NOT NULL,
                stream_kind VARCHAR(32) NOT NULL,
                stream_id VARCHAR(512) NOT NULL,
                status VARCHAR(32) NOT NULL,
                committed_sequence BIGINT NOT NULL,
                committed_page_fingerprint VARCHAR(71) NOT NULL,
                committed_cursor_fingerprint VARCHAR(71) NOT NULL,
                event_time_through TIMESTAMP WITH TIME ZONE NOT NULL,
                staged_page_fingerprint VARCHAR(71) NOT NULL,
                staged_page_json TEXT NOT NULL,
                attempt_count BIGINT NOT NULL,
                consecutive_failures INTEGER NOT NULL,
                next_eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
                lease_owner VARCHAR(512) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                failure_code VARCHAR(255) NOT NULL,
                record_fingerprint VARCHAR(71) NOT NULL,
                snapshot_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, connector_id,
                    connector_generation, stream_kind, stream_id
                )
            )
            """;
    private static final String CREATE_SCHEDULE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_mirror_outcome_source_schedule
            ON mirror_outcome_source_checkpoints (
                region, environment_id, status,
                next_eligible_at, lease_expires_at,
                connector_id, connector_generation, stream_kind, stream_id
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Supplier<Instant> coordinationClock;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;
    private final TransactionTemplate lockInitialization;

    /** Creates a production checkpoint repository using the database clock. */
    public DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this(jdbc, mapper, transactionManager, null);
    }

    /** Deterministic database-clock seam for lease and retry tests. */
    DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.coordinationClock = coordinationClock;
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        mutations = transaction(manager, TransactionDefinition.PROPAGATION_REQUIRED,
                TransactionDefinition.ISOLATION_READ_COMMITTED, false);
        reads = transaction(manager, TransactionDefinition.PROPAGATION_REQUIRED,
                TransactionDefinition.ISOLATION_REPEATABLE_READ, true);
        lockInitialization = transaction(manager, TransactionDefinition.PROPAGATION_NESTED,
                TransactionDefinition.ISOLATION_READ_COMMITTED, false);
    }

    /** Creates additive tables and verifies every persisted checkpoint and staged page. */
    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_COMMANDS);
        jdbc.execute(CREATE_CHECKPOINTS);
        jdbc.execute(CREATE_SCHEDULE_INDEX);
        List<Stored> stored = jdbc.query(
                "SELECT * FROM mirror_outcome_source_checkpoints",
                (rs, row) -> decode(rs));
        stored.forEach(this::verifyStored);
    }

    @Override
    public Admission registerLive(Registration registration) {
        Registration exact = Objects.requireNonNull(registration, "registration");
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            lock(exact.key().scope().region(), exact.key().scope().environmentId());
            Optional<Stored> existing = findStored(exact.key(), true);
            if (existing.isPresent()) {
                Snapshot value = existing.orElseThrow().snapshot();
                if (!value.baselinePageFingerprint().equals(
                        exact.baselinePageFingerprint())
                        || !value.baselineCursorRef().equals(exact.baselineCursorRef())
                        || value.controlCommandRef() != null) {
                    throw violation(Reason.CONTENT_CONFLICT);
                }
                return new Admission(value, true);
            }
            Snapshot initial = initial(
                    exact.key(), null, exact.baselinePageFingerprint(),
                    exact.baselineCursorRef(), now);
            insert(initial, "", "");
            return new Admission(initial, false);
        }), "live source registration returned null");
    }

    @Override
    public Admission registerBackfill(
            AuthoritativeOutcomeConnectorControlCommand command) {
        AuthoritativeOutcomeConnectorControlCommand exact = verifiedCommand(
                command,
                AuthoritativeOutcomeConnectorControlCommand.CommandType.BACKFILL);
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            lock(exact.scope().region(), exact.scope().environmentId());
            CommandAdmission commandAdmission = admitCommand(exact, now, 0);
            StreamKey key = new StreamKey(
                    exact.scope(), exact.connectorId(), exact.connectorGeneration(),
                    AuthoritativeOutcomeSourcePage.StreamKind.BACKFILL, exact.streamId());
            Optional<Stored> existing = findStored(key, true);
            if (existing.isPresent()) {
                Snapshot value = existing.orElseThrow().snapshot();
                if (!exact.artifactRef().equals(value.controlCommandRef())
                        || !exact.baselinePageFingerprint().equals(
                        value.baselinePageFingerprint())
                        || !exact.baselineCursorRef().equals(value.baselineCursorRef())) {
                    throw violation(Reason.CONTENT_CONFLICT);
                }
                return new Admission(value, true);
            }
            if (generationRevoked(exact.scope(), exact.connectorId(),
                    exact.connectorGeneration())) {
                throw violation(Reason.GENERATION_REVOKED);
            }
            Snapshot initial = initial(
                    key, exact.artifactRef(), exact.baselinePageFingerprint(),
                    exact.baselineCursorRef(), now);
            insert(initial, "", "");
            return new Admission(initial, commandAdmission.replayed());
        }), "backfill source registration returned null");
    }

    @Override
    public Revocation revokeGeneration(
            AuthoritativeOutcomeConnectorControlCommand command) {
        AuthoritativeOutcomeConnectorControlCommand exact = verifiedCommand(
                command,
                AuthoritativeOutcomeConnectorControlCommand.CommandType.REVOKE_GENERATION);
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            lock(exact.scope().region(), exact.scope().environmentId());
            Optional<StoredCommand> previous = findCommand(
                    exact.scope(), exact.commandId(), exact.revision());
            if (previous.isPresent()) {
                StoredCommand stored = previous.orElseThrow();
                if (!stored.command().commandFingerprint().equals(
                        exact.commandFingerprint())) {
                    throw violation(Reason.CONTENT_CONFLICT);
                }
                return new Revocation(
                        exact.artifactRef(), stored.affectedStreamCount(), true);
            }
            List<Stored> streams = findGeneration(
                    exact.scope(), exact.connectorId(), exact.connectorGeneration(), true);
            int affected = 0;
            for (Stored stored : streams) {
                if (stored.snapshot().status() != Status.REVOKED) {
                    Snapshot revoked = copy(
                            stored.snapshot(), Status.REVOKED,
                            stored.snapshot().committedSequence(),
                            stored.snapshot().committedPageFingerprint(),
                            stored.snapshot().committedCursorRef(),
                            stored.snapshot().committedWatermarkRef(),
                            stored.snapshot().eventTimeThrough(), "",
                            stored.snapshot().attemptCount(),
                            stored.snapshot().consecutiveFailures(),
                            now, stored.snapshot().leaseEpoch() + 1,
                            Instant.EPOCH, "", now);
                    update(stored, revoked, "", "");
                    affected++;
                }
            }
            admitCommand(exact, now, affected);
            return new Revocation(exact.artifactRef(), affected, false);
        }), "connector generation revocation returned null");
    }

    @Override
    public Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
            Policy policy) {
        String exactRegion = required(region, "region");
        String exactEnvironment = required(environmentId, "environmentId");
        String exactOwner = required(ownerId, "ownerId");
        if (!OWNER_ID.matcher(exactOwner).matches()) {
            throw new IllegalArgumentException("ownerId is invalid");
        }
        Policy exactPolicy = Objects.requireNonNull(policy, "policy");
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            lock(exactRegion, exactEnvironment);
            List<Stored> candidates = jdbc.query("""
                    SELECT * FROM mirror_outcome_source_checkpoints
                    WHERE region = ? AND environment_id = ?
                      AND (status = 'ACTIVE'
                           OR (status = 'RUNNING' AND lease_expires_at <= ?))
                      AND next_eligible_at <= ?
                    ORDER BY next_eligible_at, connector_id,
                             connector_generation, stream_kind, stream_id
                    """, (rs, row) -> decode(rs), exactRegion, exactEnvironment,
                    Timestamp.from(now), Timestamp.from(now));
            if (candidates.isEmpty()) {
                return Claim.noWork(now);
            }
            Stored current = candidates.getFirst();
            Snapshot before = current.snapshot();
            long epoch = before.leaseEpoch() + 1;
            Instant expiresAt = now.plus(exactPolicy.leaseDuration());
            Snapshot running = copy(
                    before, Status.RUNNING, before.committedSequence(),
                    before.committedPageFingerprint(), before.committedCursorRef(),
                    before.committedWatermarkRef(), before.eventTimeThrough(),
                    before.stagedPageFingerprint(), before.attemptCount() + 1,
                    before.consecutiveFailures(), before.nextEligibleAt(), epoch,
                    expiresAt, before.failureCode(), now);
            update(current, running, exactOwner,
                    current.stagedPage() == null ? "" : encode(current.stagedPage()));
            Lease lease = new Lease(running.key(), exactOwner, epoch, expiresAt);
            return new Claim(
                    Claim.Outcome.ACQUIRED, now, running, current.stagedPage(), lease);
        }), "source checkpoint claim returned null");
    }

    @Override
    public Lease heartbeat(Lease lease, Policy policy) {
        Lease exact = Objects.requireNonNull(lease, "lease");
        Policy exactPolicy = Objects.requireNonNull(policy, "policy");
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            Stored current = requireLease(exact, now);
            Instant expiresAt = now.plus(exactPolicy.leaseDuration());
            Snapshot renewed = copy(
                    current.snapshot(), Status.RUNNING,
                    current.snapshot().committedSequence(),
                    current.snapshot().committedPageFingerprint(),
                    current.snapshot().committedCursorRef(),
                    current.snapshot().committedWatermarkRef(),
                    current.snapshot().eventTimeThrough(),
                    current.snapshot().stagedPageFingerprint(),
                    current.snapshot().attemptCount(),
                    current.snapshot().consecutiveFailures(),
                    current.snapshot().nextEligibleAt(), exact.epoch(), expiresAt,
                    current.snapshot().failureCode(), now);
            update(current, renewed, exact.ownerId(), stagedJson(current));
            return new Lease(exact.key(), exact.ownerId(), exact.epoch(), expiresAt);
        }), "source checkpoint heartbeat returned null");
    }

    @Override
    public Snapshot stage(Lease lease, AuthoritativeOutcomeSourcePage page) {
        Lease exactLease = Objects.requireNonNull(lease, "lease");
        AuthoritativeOutcomeSourcePage exactPage = Objects.requireNonNull(page, "page");
        exactPage.verify(mapper);
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            Stored current = requireLease(exactLease, now);
            requireContinuation(current.snapshot(), exactPage);
            if (current.stagedPage() != null) {
                if (!current.stagedPage().pageFingerprint().equals(
                        exactPage.pageFingerprint())) {
                    throw violation(Reason.PAGE_CONFLICT);
                }
                return current.snapshot();
            }
            Snapshot staged = copy(
                    current.snapshot(), Status.RUNNING,
                    current.snapshot().committedSequence(),
                    current.snapshot().committedPageFingerprint(),
                    current.snapshot().committedCursorRef(),
                    current.snapshot().committedWatermarkRef(),
                    current.snapshot().eventTimeThrough(), exactPage.pageFingerprint(),
                    current.snapshot().attemptCount(),
                    current.snapshot().consecutiveFailures(),
                    current.snapshot().nextEligibleAt(), exactLease.epoch(),
                    current.snapshot().leaseExpiresAt(), "", now);
            update(current, staged, exactLease.ownerId(), encode(exactPage));
            return staged;
        }), "source page stage returned null");
    }

    @Override
    public Snapshot commit(Lease lease, String pageFingerprint, Policy policy) {
        Lease exactLease = Objects.requireNonNull(lease, "lease");
        String exactFingerprint = required(pageFingerprint, "pageFingerprint");
        Objects.requireNonNull(policy, "policy");
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            Stored current = requireLease(exactLease, now);
            AuthoritativeOutcomeSourcePage page = current.stagedPage();
            if (page == null || !exactFingerprint.equals(page.pageFingerprint())) {
                throw violation(Reason.PAGE_CONFLICT);
            }
            requireContinuation(current.snapshot(), page);
            Snapshot committed = copy(
                    current.snapshot(), Status.ACTIVE, page.sequence(),
                    page.pageFingerprint(), page.nextCursorRef(),
                    page.watermark().watermarkRef(),
                    page.watermark().eventTimeThrough(), "",
                    current.snapshot().attemptCount(), 0, now,
                    exactLease.epoch(), Instant.EPOCH, "", now);
            update(current, committed, "", "");
            return committed;
        }), "source page commit returned null");
    }

    @Override
    public Snapshot release(Lease lease, Release release, Policy policy) {
        Lease exactLease = Objects.requireNonNull(lease, "lease");
        Release exactRelease = Objects.requireNonNull(release, "release");
        Policy exactPolicy = Objects.requireNonNull(policy, "policy");
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            Stored current = requireLease(exactLease, now);
            if (current.stagedPage() != null) {
                throw violation(Reason.PAGE_CONFLICT);
            }
            if (exactRelease == Release.STREAM_COMPLETE
                    && current.snapshot().key().streamKind()
                    != AuthoritativeOutcomeSourcePage.StreamKind.BACKFILL) {
                throw violation(Reason.TERMINAL_STREAM);
            }
            Status status = exactRelease == Release.STREAM_COMPLETE
                    ? Status.COMPLETE : Status.ACTIVE;
            Instant next = status == Status.ACTIVE
                    ? now.plus(exactPolicy.idleDelay()) : now;
            Snapshot released = copy(
                    current.snapshot(), status,
                    current.snapshot().committedSequence(),
                    current.snapshot().committedPageFingerprint(),
                    current.snapshot().committedCursorRef(),
                    current.snapshot().committedWatermarkRef(),
                    current.snapshot().eventTimeThrough(), "",
                    current.snapshot().attemptCount(), 0, next,
                    exactLease.epoch(), Instant.EPOCH, "", now);
            update(current, released, "", "");
            return released;
        }), "source checkpoint release returned null");
    }

    @Override
    public Snapshot fail(
            Lease lease, String failureCode, boolean retryable, Policy policy) {
        Lease exactLease = Objects.requireNonNull(lease, "lease");
        String exactCode = required(failureCode, "failureCode");
        if (!FAILURE_CODE.matcher(exactCode).matches()) {
            throw new IllegalArgumentException("failureCode is invalid");
        }
        Policy exactPolicy = Objects.requireNonNull(policy, "policy");
        return requireResult(mutations.execute(ignored -> {
            Instant now = dbNow();
            Stored current = requireLease(exactLease, now);
            int failures = current.snapshot().consecutiveFailures() + 1;
            boolean retry = retryable
                    && failures < exactPolicy.maximumConsecutiveFailures();
            Status status = retry ? Status.ACTIVE : Status.QUARANTINED;
            Instant next = retry
                    ? now.plus(exactPolicy.retryDelay(failures)) : now;
            Snapshot failed = copy(
                    current.snapshot(), status,
                    current.snapshot().committedSequence(),
                    current.snapshot().committedPageFingerprint(),
                    current.snapshot().committedCursorRef(),
                    current.snapshot().committedWatermarkRef(),
                    current.snapshot().eventTimeThrough(),
                    current.snapshot().stagedPageFingerprint(),
                    current.snapshot().attemptCount(), failures, next,
                    exactLease.epoch(), Instant.EPOCH, exactCode, now);
            update(current, failed, "", stagedJson(current));
            return failed;
        }), "source checkpoint failure returned null");
    }

    @Override
    public Optional<Snapshot> find(StreamKey key) {
        StreamKey exact = Objects.requireNonNull(key, "key");
        return reads.execute(ignored -> findStored(exact, false).map(Stored::snapshot));
    }

    @Override
    public Instant observedAt() {
        return dbNow();
    }

    @Override
    public boolean durable() {
        return true;
    }

    private Snapshot initial(
            StreamKey key,
            MirrorArtifactRef controlCommandRef,
            String baselinePageFingerprint,
            MirrorArtifactRef baselineCursorRef,
            Instant now) {
        return new Snapshot(
                key, controlCommandRef, baselinePageFingerprint, baselineCursorRef,
                0, baselinePageFingerprint, baselineCursorRef, null,
                Instant.EPOCH, Status.ACTIVE, "", 0, 0, now, 0,
                Instant.EPOCH, "", now, now);
    }

    private void requireContinuation(Snapshot checkpoint, AuthoritativeOutcomeSourcePage page) {
        StreamKey key = checkpoint.key();
        if (!key.scope().equals(page.scope())
                || !key.connectorId().equals(page.connectorId())
                || key.connectorGeneration() != page.connectorGeneration()
                || key.streamKind() != page.streamKind()
                || !key.streamId().equals(page.streamId())
                || page.sequence() != checkpoint.committedSequence() + 1
                || !page.previousPageFingerprint().equals(
                checkpoint.committedPageFingerprint())
                || !page.previousCursorRef().equals(checkpoint.committedCursorRef())
                || page.watermark().eventTimeThrough().isBefore(
                checkpoint.eventTimeThrough())
                || (key.streamKind() == AuthoritativeOutcomeSourcePage.StreamKind.BACKFILL
                && !Objects.equals(page.controlCommandRef(), checkpoint.controlCommandRef()))) {
            throw violation(Reason.PAGE_CONFLICT);
        }
    }

    private Stored requireLease(Lease lease, Instant now) {
        lock(lease.key().scope().region(), lease.key().scope().environmentId());
        Stored current = findStored(lease.key(), true)
                .orElseThrow(() -> violation(Reason.NOT_FOUND));
        if (current.snapshot().status() != Status.RUNNING
                || !current.leaseOwner().equals(lease.ownerId())
                || current.snapshot().leaseEpoch() != lease.epoch()
                || !current.snapshot().leaseExpiresAt().equals(lease.expiresAt())
                || !now.isBefore(lease.expiresAt())) {
            throw violation(Reason.LEASE_LOST);
        }
        return current;
    }

    private AuthoritativeOutcomeConnectorControlCommand verifiedCommand(
            AuthoritativeOutcomeConnectorControlCommand command,
            AuthoritativeOutcomeConnectorControlCommand.CommandType type) {
        AuthoritativeOutcomeConnectorControlCommand exact = Objects.requireNonNull(
                command, "command");
        exact.verify(mapper);
        if (exact.commandType() != type || !exact.authoritySeal().signed()) {
            throw violation(Reason.COMMAND_INVALID);
        }
        return exact;
    }

    private CommandAdmission admitCommand(
            AuthoritativeOutcomeConnectorControlCommand command,
            Instant now,
            int affected) {
        Optional<StoredCommand> previous = findCommand(
                command.scope(), command.commandId(), command.revision());
        if (previous.isPresent()) {
            if (!previous.orElseThrow().command().commandFingerprint().equals(
                    command.commandFingerprint())) {
                throw violation(Reason.CONTENT_CONFLICT);
            }
            return new CommandAdmission(true);
        }
        CapabilitySnapshot.Scope scope = command.scope();
        jdbc.update("""
                INSERT INTO mirror_outcome_source_commands (
                    tenant_id, organization_id, project_id, environment_id, region,
                    command_id, revision, command_fingerprint, connector_id,
                    connector_generation, command_type, affected_stream_count,
                    command_json, admitted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), command.commandId(), command.revision(),
                command.commandFingerprint(), command.connectorId(),
                command.connectorGeneration(), command.commandType().name(), affected,
                encode(command), Timestamp.from(now));
        return new CommandAdmission(false);
    }

    private Optional<StoredCommand> findCommand(
            CapabilitySnapshot.Scope scope, String commandId, long revision) {
        List<StoredCommand> values = jdbc.query("""
                SELECT command_json, affected_stream_count
                FROM mirror_outcome_source_commands
                WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                  AND environment_id = ? AND region = ?
                  AND command_id = ? AND revision = ?
                """, (rs, row) -> new StoredCommand(
                        decode(rs.getString("command_json"),
                                AuthoritativeOutcomeConnectorControlCommand.class),
                        rs.getInt("affected_stream_count")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), commandId, revision);
        return values.stream().findFirst();
    }

    private boolean generationRevoked(
            CapabilitySnapshot.Scope scope, String connectorId, long generation) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mirror_outcome_source_commands
                WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                  AND environment_id = ? AND region = ?
                  AND connector_id = ? AND connector_generation = ?
                  AND command_type = 'REVOKE_GENERATION'
                """, Integer.class, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), connectorId, generation);
        return count != null && count > 0;
    }

    private List<Stored> findGeneration(
            CapabilitySnapshot.Scope scope,
            String connectorId,
            long generation,
            boolean forUpdate) {
        String sql = """
                SELECT * FROM mirror_outcome_source_checkpoints
                WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                  AND environment_id = ? AND region = ?
                  AND connector_id = ? AND connector_generation = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> decode(rs),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), connectorId, generation);
    }

    private Optional<Stored> findStored(StreamKey key, boolean forUpdate) {
        CapabilitySnapshot.Scope scope = key.scope();
        String sql = """
                SELECT * FROM mirror_outcome_source_checkpoints
                WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                  AND environment_id = ? AND region = ?
                  AND connector_id = ? AND connector_generation = ?
                  AND stream_kind = ? AND stream_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        List<Stored> values = jdbc.query(sql, (rs, row) -> decode(rs),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), key.connectorId(),
                key.connectorGeneration(), key.streamKind().name(), key.streamId());
        return values.stream().findFirst();
    }

    private void insert(Snapshot snapshot, String leaseOwner, String stagedJson) {
        Encoded encoded = encoded(snapshot);
        StreamKey key = snapshot.key();
        CapabilitySnapshot.Scope scope = key.scope();
        jdbc.update("""
                INSERT INTO mirror_outcome_source_checkpoints (
                    tenant_id, organization_id, project_id, environment_id, region,
                    connector_id, connector_generation, stream_kind, stream_id,
                    status, committed_sequence, committed_page_fingerprint,
                    committed_cursor_fingerprint, event_time_through,
                    staged_page_fingerprint, staged_page_json,
                    attempt_count, consecutive_failures, next_eligible_at,
                    lease_owner, lease_epoch, lease_expires_at, failure_code,
                    record_fingerprint, snapshot_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), key.connectorId(),
                key.connectorGeneration(), key.streamKind().name(), key.streamId(),
                snapshot.status().name(), snapshot.committedSequence(),
                snapshot.committedPageFingerprint(), snapshot.committedCursorRef().fingerprint(),
                Timestamp.from(snapshot.eventTimeThrough()), snapshot.stagedPageFingerprint(),
                stagedJson, snapshot.attemptCount(), snapshot.consecutiveFailures(),
                Timestamp.from(snapshot.nextEligibleAt()), leaseOwner, snapshot.leaseEpoch(),
                Timestamp.from(snapshot.leaseExpiresAt()), snapshot.failureCode(),
                encoded.fingerprint(), encoded.json());
    }

    private void update(
            Stored previous, Snapshot snapshot, String leaseOwner, String stagedJson) {
        Encoded encoded = encoded(snapshot);
        StreamKey key = snapshot.key();
        CapabilitySnapshot.Scope scope = key.scope();
        int changed = jdbc.update("""
                UPDATE mirror_outcome_source_checkpoints SET
                    status = ?, committed_sequence = ?, committed_page_fingerprint = ?,
                    committed_cursor_fingerprint = ?, event_time_through = ?,
                    staged_page_fingerprint = ?, staged_page_json = ?,
                    attempt_count = ?, consecutive_failures = ?, next_eligible_at = ?,
                    lease_owner = ?, lease_epoch = ?, lease_expires_at = ?, failure_code = ?,
                    record_fingerprint = ?, snapshot_json = ?
                WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                  AND environment_id = ? AND region = ? AND connector_id = ?
                  AND connector_generation = ? AND stream_kind = ? AND stream_id = ?
                  AND record_fingerprint = ?
                """, snapshot.status().name(), snapshot.committedSequence(),
                snapshot.committedPageFingerprint(), snapshot.committedCursorRef().fingerprint(),
                Timestamp.from(snapshot.eventTimeThrough()), snapshot.stagedPageFingerprint(),
                stagedJson, snapshot.attemptCount(), snapshot.consecutiveFailures(),
                Timestamp.from(snapshot.nextEligibleAt()), leaseOwner, snapshot.leaseEpoch(),
                Timestamp.from(snapshot.leaseExpiresAt()), snapshot.failureCode(),
                encoded.fingerprint(), encoded.json(), scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), key.connectorId(),
                key.connectorGeneration(), key.streamKind().name(), key.streamId(),
                previous.recordFingerprint());
        if (changed != 1) {
            throw violation(Reason.LEASE_LOST);
        }
    }

    private Stored decode(ResultSet rs) throws SQLException {
        Snapshot snapshot = decode(rs.getString("snapshot_json"), Snapshot.class);
        String stagedJson = rs.getString("staged_page_json");
        AuthoritativeOutcomeSourcePage page = stagedJson == null || stagedJson.isBlank()
                ? null : decode(stagedJson, AuthoritativeOutcomeSourcePage.class);
        Stored stored = new Stored(
                snapshot, rs.getString("lease_owner"), page,
                rs.getString("record_fingerprint"));
        verifyStored(stored);
        if (!snapshot.status().name().equals(rs.getString("status"))
                || snapshot.committedSequence() != rs.getLong("committed_sequence")
                || !snapshot.committedPageFingerprint().equals(
                rs.getString("committed_page_fingerprint"))
                || !snapshot.committedCursorRef().fingerprint().equals(
                rs.getString("committed_cursor_fingerprint"))
                || !snapshot.eventTimeThrough().equals(
                rs.getTimestamp("event_time_through").toInstant())
                || !snapshot.stagedPageFingerprint().equals(
                rs.getString("staged_page_fingerprint"))
                || snapshot.attemptCount() != rs.getLong("attempt_count")
                || snapshot.consecutiveFailures() != rs.getInt("consecutive_failures")
                || snapshot.leaseEpoch() != rs.getLong("lease_epoch")) {
            throw violation(Reason.STORAGE_INVALID);
        }
        return stored;
    }

    private void verifyStored(Stored stored) {
        Encoded encoded = encoded(stored.snapshot());
        if (!encoded.fingerprint().equals(stored.recordFingerprint())
                || (stored.stagedPage() != null)
                != stored.snapshot().hasStagedPage()) {
            throw violation(Reason.STORAGE_INVALID);
        }
        if (stored.stagedPage() != null) {
            stored.stagedPage().verify(mapper);
            if (!stored.stagedPage().pageFingerprint().equals(
                    stored.snapshot().stagedPageFingerprint())) {
                throw violation(Reason.STORAGE_INVALID);
            }
        }
    }

    private Encoded encoded(Snapshot snapshot) {
        String json = encode(snapshot);
        return new Encoded(json, ProtocolFingerprint.ofBounded(
                mapper, snapshot, MAXIMUM_SNAPSHOT_BYTES));
    }

    private void lock(String region, String environmentId) {
        try {
            lockInitialization.executeWithoutResult(ignored -> jdbc.update("""
                    INSERT INTO mirror_outcome_source_checkpoint_locks (region, environment_id)
                    VALUES (?, ?)
                    """, region, environmentId));
        } catch (DuplicateKeyException ignored) {
            // A concurrent process created the stable partition lock row.
        }
        jdbc.queryForObject("""
                SELECT region FROM mirror_outcome_source_checkpoint_locks
                WHERE region = ? AND environment_id = ? FOR UPDATE
                """, String.class, region, environmentId);
    }

    private Instant dbNow() {
        if (coordinationClock != null) {
            return Objects.requireNonNull(coordinationClock.get(), "coordinationClock");
        }
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private Snapshot copy(
            Snapshot value, Status status, long committedSequence,
            String committedPageFingerprint, MirrorArtifactRef committedCursorRef,
            MirrorArtifactRef committedWatermarkRef, Instant eventTimeThrough,
            String stagedPageFingerprint, long attemptCount, int consecutiveFailures,
            Instant nextEligibleAt, long leaseEpoch, Instant leaseExpiresAt,
            String failureCode, Instant updatedAt) {
        return new Snapshot(
                value.key(), value.controlCommandRef(), value.baselinePageFingerprint(),
                value.baselineCursorRef(), committedSequence, committedPageFingerprint,
                committedCursorRef, committedWatermarkRef, eventTimeThrough, status,
                stagedPageFingerprint, attemptCount, consecutiveFailures, nextEligibleAt,
                leaseEpoch, leaseExpiresAt, failureCode, value.createdAt(), updatedAt);
    }

    private String stagedJson(Stored stored) {
        return stored.stagedPage() == null ? "" : encode(stored.stagedPage());
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw violation(Reason.STORAGE_INVALID);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw violation(Reason.STORAGE_INVALID);
        }
    }

    private static TransactionTemplate transaction(
            PlatformTransactionManager manager,
            int propagation,
            int isolation,
            boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(propagation);
        template.setIsolationLevel(isolation);
        template.setReadOnly(readOnly);
        return template;
    }

    private static String required(String value, String field) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (exact.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return exact;
    }

    private static Violation violation(Reason reason) {
        return new Violation(reason);
    }

    private static <T> T requireResult(T value, String message) {
        return Objects.requireNonNull(value, message);
    }

    private record Stored(
            Snapshot snapshot,
            String leaseOwner,
            AuthoritativeOutcomeSourcePage stagedPage,
            String recordFingerprint) {
    }

    private record StoredCommand(
            AuthoritativeOutcomeConnectorControlCommand command,
            int affectedStreamCount) {
    }

    private record CommandAdmission(boolean replayed) {
    }

    private record Encoded(String json, String fingerprint) {
    }
}
