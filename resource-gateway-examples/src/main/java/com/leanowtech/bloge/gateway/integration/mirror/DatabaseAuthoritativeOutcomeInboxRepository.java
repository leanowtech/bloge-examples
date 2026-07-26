package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JDBC append-only authoritative outcome inbox with database-time scheduling and epoch fencing.
 *
 * <p>Every key contains complete enterprise scope. A region/environment lock serializes head
 * admission, claim, expiry recovery, and successor publication across replicas. Lock-row creation
 * uses a nested savepoint so a concurrent PostgreSQL unique-key conflict cannot poison the outer
 * transaction. Observation JSON is immutable; the head JSON is a rebuildable projection guarded
 * by a canonical fingerprint and duplicated indexes. Lifecycle events form a per-observation
 * content-addressed predecessor chain.</p>
 *
 * <p>External business-authority I/O never occurs while a database lock is held. Admission and
 * worker execution perform full verification before entering repository mutations; storage reads
 * repeat protocol, content-address, Resource Gateway signature, and signed-time verification
 * locally. The worker repeats the external authority boundary after claim.</p>
 */
public final class DatabaseAuthoritativeOutcomeInboxRepository
        implements AuthoritativeOutcomeInboxRepository {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final int RECOVERY_LIMIT = 1_000;
    private static final Runnable NO_INITIALIZATION_PROBE =
            () -> {
            };

    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_inbox_locks (
                region VARCHAR(96) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                PRIMARY KEY (region, environment_id)
            )
            """;
    private static final String CREATE_OBSERVATIONS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_observations (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                observation_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                observation_fingerprint VARCHAR(71) NOT NULL,
                predecessor_fingerprint VARCHAR(71) NOT NULL,
                inventory_id VARCHAR(512) NOT NULL,
                inventory_revision BIGINT NOT NULL,
                inventory_fingerprint VARCHAR(71) NOT NULL,
                unit_id VARCHAR(512) NOT NULL,
                cohort_id VARCHAR(512) NOT NULL,
                cohort_revision BIGINT NOT NULL,
                cohort_fingerprint VARCHAR(71) NOT NULL,
                reconciliation VARCHAR(32) NOT NULL,
                reconciled_at TIMESTAMP WITH TIME ZONE NOT NULL,
                attested_at TIMESTAMP WITH TIME ZONE NOT NULL,
                observation_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, observation_id, revision
                ),
                CONSTRAINT uq_mirror_outcome_observation_fingerprint UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, observation_fingerprint
                )
            )
            """;
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_inbox_heads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                observation_id VARCHAR(512) NOT NULL,
                current_revision BIGINT NOT NULL,
                current_observation_fingerprint VARCHAR(71) NOT NULL,
                inventory_id VARCHAR(512) NOT NULL,
                inventory_revision BIGINT NOT NULL,
                inventory_fingerprint VARCHAR(71) NOT NULL,
                unit_id VARCHAR(512) NOT NULL,
                cohort_id VARCHAR(512) NOT NULL,
                cohort_revision BIGINT NOT NULL,
                cohort_fingerprint VARCHAR(71) NOT NULL,
                reconciliation VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                attempt_count BIGINT NOT NULL,
                consecutive_failures INTEGER NOT NULL,
                next_eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
                lease_owner VARCHAR(512) NOT NULL,
                lease_owner_fingerprint VARCHAR(71) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                failure_code VARCHAR(255) NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                terminal_at TIMESTAMP WITH TIME ZONE,
                record_fingerprint VARCHAR(71) NOT NULL,
                last_event_fingerprint VARCHAR(71) NOT NULL,
                entry_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, observation_id
                )
            )
            """;
    private static final String CREATE_SCHEDULE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_mirror_outcome_inbox_schedule
            ON mirror_outcome_inbox_heads (
                region, environment_id, status,
                next_eligible_at, lease_expires_at,
                created_at, observation_id
            )
            """;
    private static final String CREATE_LIFECYCLE = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_inbox_lifecycle (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                observation_id VARCHAR(512) NOT NULL,
                event_ordinal BIGINT NOT NULL,
                occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                transition VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                observation_revision BIGINT NOT NULL,
                observation_fingerprint VARCHAR(71) NOT NULL,
                predecessor_observation_fingerprint VARCHAR(71) NOT NULL,
                reconciliation VARCHAR(32) NOT NULL,
                attempt_count BIGINT NOT NULL,
                consecutive_failures INTEGER NOT NULL,
                lease_epoch BIGINT NOT NULL,
                owner_fingerprint VARCHAR(71) NOT NULL,
                failure_code VARCHAR(255) NOT NULL,
                previous_event_fingerprint VARCHAR(71) NOT NULL,
                event_fingerprint VARCHAR(71) NOT NULL,
                event_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, observation_id, event_ordinal
                ),
                CONSTRAINT uq_mirror_outcome_lifecycle_fingerprint UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, event_fingerprint
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuthoritativeOutcomeObservationIntegrity integrity;
    private final Supplier<Instant> coordinationClock;
    private final TransactionTemplate mutations;
    private final TransactionTemplate lockRowInitialization;
    private final Runnable beforeLockRowInsert;

    /**
     * Creates a production repository using the application database clock.
     *
     * @param jdbc transaction-aware JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity local and complete observation verifier
     * @param transactionManager nested-savepoint manager for the same datasource
     */
    public DatabaseAuthoritativeOutcomeInboxRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeObservationIntegrity integrity,
            PlatformTransactionManager transactionManager) {
        this(
                jdbc,
                mapper,
                integrity,
                transactionManager,
                null,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic database-clock seam for lease, retry, and ageing tests. */
    DatabaseAuthoritativeOutcomeInboxRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeObservationIntegrity integrity,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock) {
        this(
                jdbc,
                mapper,
                integrity,
                transactionManager,
                coordinationClock,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic clock and lock-row race seam for target-database certification. */
    DatabaseAuthoritativeOutcomeInboxRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AuthoritativeOutcomeObservationIntegrity integrity,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock,
            Runnable beforeLockRowInsert) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.coordinationClock = coordinationClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(
                coordinationClock, "coordinationClock");
        this.beforeLockRowInsert = Objects.requireNonNull(
                beforeLockRowInsert, "beforeLockRowInsert");
        DataSourceTransactionManager transactions =
                requireSavepointTransactions(
                        this.jdbc, transactionManager);
        mutations = new TransactionTemplate(transactions);
        mutations.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
        mutations.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        lockRowInitialization =
                new TransactionTemplate(transactions);
        lockRowInitialization.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NESTED);
        lockRowInitialization.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates append-only evidence, mutable head, lifecycle, and partition-lock tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_OBSERVATIONS);
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_SCHEDULE_INDEX);
        jdbc.execute(CREATE_LIFECYCLE);
    }

    @Override
    public Admission append(
            AuthoritativeOutcomeObservation observation,
            String expectedPredecessorFingerprint) {
        AuthoritativeOutcomeObservation exact =
                integrity.verify(
                        Objects.requireNonNull(
                                observation, "observation"));
        String expected = optionalFingerprint(
                expectedPredecessorFingerprint);
        return Objects.requireNonNull(
                mutations.execute(ignored -> {
                    lockPartition(exact.scope().region(),
                            exact.scope().environmentId());
                    Instant now = coordinationNow();
                    return appendExternal(
                            exact, expected, now);
                }),
                "outcome admission returned null");
    }

    @Override
    public Optional<AuthoritativeOutcomeObservation>
    findObservation(
            CapabilitySnapshot.Scope scope,
            String observationId,
            long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "revision must be positive");
        }
        return findStoredObservation(
                Objects.requireNonNull(scope, "scope"),
                identifier(observationId),
                revision).map(StoredObservation::observation);
    }

    @Override
    public Optional<AuthoritativeOutcomeObservation>
    findLatestObservation(
            CapabilitySnapshot.Scope scope,
            String observationId) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = identifier(observationId);
        return findStoredHead(exactScope, exactId, false)
                .flatMap(head -> findStoredObservation(
                        exactScope,
                        exactId,
                        head.entry().currentRevision()))
                .map(StoredObservation::observation);
    }

    @Override
    public Optional<AuthoritativeOutcomeInboxEntry> findEntry(
            CapabilitySnapshot.Scope scope,
            String observationId) {
        return findStoredHead(
                Objects.requireNonNull(scope, "scope"),
                identifier(observationId),
                false).map(StoredHead::entry);
    }

    @Override
    public Instant observedAt() {
        return coordinationNow();
    }

    @Override
    public Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
            AuthoritativeOutcomeInboxPolicy policy) {
        String exactRegion = partition(
                region, "region", 96)
                .toLowerCase(Locale.ROOT);
        String exactEnvironment = partition(
                environmentId, "environmentId", 255)
                .toLowerCase(Locale.ROOT);
        String exactOwner = owner(ownerId);
        AuthoritativeOutcomeInboxPolicy controls =
                Objects.requireNonNull(policy, "policy");
        return Objects.requireNonNull(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactRegion, exactEnvironment);
                    Instant now = coordinationNow();
                    recoverExpired(
                            exactRegion,
                            exactEnvironment,
                            now,
                            controls);
                    for (int skipped = 0;
                         skipped < RECOVERY_LIMIT;
                         skipped++) {
                        Optional<StoredHead> candidate =
                                selectNext(
                                        exactRegion,
                                        exactEnvironment,
                                        now);
                        if (candidate.isEmpty()) {
                            return Claim.noWork(now);
                        }
                        StoredHead head = candidate.orElseThrow();
                        StoredObservation current =
                                currentObservation(head);
                        if (!safePlus(
                                current.observation()
                                        .attributionWindow()
                                        .closesAt(),
                                controls.maximumPendingAge())
                                .isAfter(now)) {
                            quarantine(
                                    head,
                                    now,
                                    "RG.MIRROR.OUTCOME.RECONCILIATION_EXPIRED",
                                    TransitionOwner.none());
                            continue;
                        }
                        long epoch = Math.addExact(
                                head.entry().leaseEpoch(), 1L);
                        Instant expiresAt = safePlus(
                                now, controls.leaseDuration());
                        String ownerFingerprint =
                                ReadOnlyShadowJobIntegrity
                                        .ownerFingerprint(
                                                mapper, exactOwner);
                        AuthoritativeOutcomeInboxEntry running =
                                new AuthoritativeOutcomeInboxEntry(
                                        "",
                                        head.entry().scope(),
                                        head.entry().observationId(),
                                        head.entry().currentRevision(),
                                        head.entry()
                                                .currentObservationFingerprint(),
                                        head.entry().inventoryRef(),
                                        head.entry().unitId(),
                                        head.entry().cohortRef(),
                                        head.entry().reconciliation(),
                                        AuthoritativeOutcomeInboxEntry
                                                .Status.RUNNING,
                                        Math.addExact(
                                                head.entry().attemptCount(),
                                                1L),
                                        head.entry()
                                                .consecutiveFailures(),
                                        Instant.EPOCH,
                                        ownerFingerprint,
                                        epoch,
                                        expiresAt,
                                        "",
                                        head.entry().createdAt(),
                                        now,
                                        null,
                                        "").seal(mapper);
                        StoredHead stored = updateHead(
                                head,
                                running,
                                exactOwner,
                                AuthoritativeOutcomeInboxLifecycleEvent
                                        .Transition.CLAIMED,
                                head.entry()
                                        .currentObservationFingerprint());
                        Lease lease = new Lease(
                                running.scope(),
                                running.observationId(),
                                running.currentRevision(),
                                running.currentObservationFingerprint(),
                                exactOwner,
                                epoch,
                                expiresAt);
                        return new Claim(
                                Claim.Outcome.ACQUIRED,
                                now,
                                stored.entry(),
                                current.observation(),
                                lease);
                    }
                    return Claim.noWork(now);
                }),
                "outcome claim returned null");
    }

    @Override
    public Heartbeat heartbeat(
            Lease lease,
            AuthoritativeOutcomeInboxPolicy policy) {
        Lease exactLease = Objects.requireNonNull(
                lease, "lease");
        AuthoritativeOutcomeInboxPolicy controls =
                Objects.requireNonNull(policy, "policy");
        return Objects.requireNonNull(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactLease.scope().region(),
                            exactLease.scope().environmentId());
                    Instant now = coordinationNow();
                    StoredHead current =
                            requireLease(exactLease, now);
                    Instant expiresAt = safePlus(
                            now, controls.leaseDuration());
                    AuthoritativeOutcomeInboxEntry renewed =
                            new AuthoritativeOutcomeInboxEntry(
                                    "",
                                    current.entry().scope(),
                                    current.entry().observationId(),
                                    current.entry().currentRevision(),
                                    current.entry()
                                            .currentObservationFingerprint(),
                                    current.entry().inventoryRef(),
                                    current.entry().unitId(),
                                    current.entry().cohortRef(),
                                    current.entry().reconciliation(),
                                    AuthoritativeOutcomeInboxEntry
                                            .Status.RUNNING,
                                    current.entry().attemptCount(),
                                    current.entry()
                                            .consecutiveFailures(),
                                    Instant.EPOCH,
                                    current.entry()
                                            .leaseOwnerFingerprint(),
                                    current.entry().leaseEpoch(),
                                    expiresAt,
                                    "",
                                    current.entry().createdAt(),
                                    now,
                                    null,
                                    "").seal(mapper);
                    StoredHead stored = updateHead(
                            current,
                            renewed,
                            exactLease.ownerId(),
                            AuthoritativeOutcomeInboxLifecycleEvent
                                    .Transition.HEARTBEAT,
                            renewed.currentObservationFingerprint());
                    return new Heartbeat(
                            stored.entry(),
                            new Lease(
                                    exactLease.scope(),
                                    exactLease.observationId(),
                                    exactLease.observationRevision(),
                                    exactLease.observationFingerprint(),
                                    exactLease.ownerId(),
                                    exactLease.epoch(),
                                    expiresAt));
                }),
                "outcome heartbeat returned null");
    }

    @Override
    public AuthoritativeOutcomeInboxEntry publishSuccessor(
            Lease lease,
            AuthoritativeOutcomeObservation successor,
            AuthoritativeOutcomeInboxPolicy policy) {
        Lease exactLease = Objects.requireNonNull(
                lease, "lease");
        AuthoritativeOutcomeObservation exactSuccessor =
                integrity.verify(
                        Objects.requireNonNull(
                                successor, "successor"));
        AuthoritativeOutcomeInboxPolicy controls =
                Objects.requireNonNull(policy, "policy");
        if (exactSuccessor.revision()
                != exactLease.observationRevision() + 1
                || !exactSuccessor.observationId().equals(
                exactLease.observationId())
                || !exactSuccessor.scope().equals(
                exactLease.scope())) {
            throw new Violation(Reason.SUCCESSOR_INVALID);
        }
        return Objects.requireNonNull(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactLease.scope().region(),
                            exactLease.scope().environmentId());
                    Instant now = coordinationNow();
                    Optional<StoredObservation> replay =
                            findStoredObservation(
                                    exactLease.scope(),
                                    exactLease.observationId(),
                                    exactSuccessor.revision());
                    if (replay.isPresent()) {
                        if (!replay.orElseThrow()
                                .observation()
                                .equals(exactSuccessor)
                                || !replay.orElseThrow()
                                .predecessorFingerprint()
                                .equals(exactLease
                                        .observationFingerprint())) {
                            throw new Violation(
                                    Reason.CONTENT_CONFLICT);
                        }
                        StoredHead head = findStoredHead(
                                exactLease.scope(),
                                exactLease.observationId(),
                                true).orElseThrow(() ->
                                new Violation(
                                        Reason.STORED_STATE_CORRUPT));
                        if (head.entry().currentRevision()
                                != exactSuccessor.revision()
                                || !head.entry()
                                .currentObservationFingerprint()
                                .equals(exactSuccessor
                                        .observationFingerprint())) {
                            throw new Violation(
                                    Reason.LINEAGE_CONFLICT);
                        }
                        return head.entry();
                    }
                    StoredHead current =
                            requireLease(exactLease, now);
                    StoredObservation predecessor =
                            currentObservation(current);
                    requireSuccessor(
                            predecessor.observation(),
                            exactSuccessor);
                    insertObservation(
                            exactSuccessor,
                            exactLease.observationFingerprint());
                    AuthoritativeOutcomeInboxEntry advanced =
                            advancedEntry(
                                    current.entry(),
                                    exactSuccessor,
                                    now,
                                    controls.pollingInterval());
                    return updateHead(
                            current,
                            advanced,
                            exactLease.ownerId(),
                            AuthoritativeOutcomeInboxLifecycleEvent
                                    .Transition.SUCCESSOR_APPENDED,
                            exactLease.observationFingerprint())
                            .entry();
                }),
                "outcome successor publication returned null");
    }

    @Override
    public AuthoritativeOutcomeInboxEntry noChange(
            Lease lease,
            AuthoritativeOutcomeInboxPolicy policy) {
        Lease exactLease = Objects.requireNonNull(
                lease, "lease");
        AuthoritativeOutcomeInboxPolicy controls =
                Objects.requireNonNull(policy, "policy");
        return Objects.requireNonNull(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactLease.scope().region(),
                            exactLease.scope().environmentId());
                    Instant now = coordinationNow();
                    StoredHead current =
                            requireLease(exactLease, now);
                    AuthoritativeOutcomeInboxEntry queued =
                            activeEntry(
                                    current.entry(),
                                    AuthoritativeOutcomeInboxEntry
                                            .Status.QUEUED,
                                    0,
                                    safePlus(
                                            now,
                                            controls.pollingInterval()),
                                    "",
                                    now);
                    return updateHead(
                            current,
                            queued,
                            exactLease.ownerId(),
                            AuthoritativeOutcomeInboxLifecycleEvent
                                    .Transition.NO_CHANGE,
                            queued.currentObservationFingerprint())
                            .entry();
                }),
                "outcome no-change transition returned null");
    }

    @Override
    public AuthoritativeOutcomeInboxEntry fail(
            Lease lease,
            String failureCode,
            boolean retryable,
            AuthoritativeOutcomeInboxPolicy policy) {
        Lease exactLease = Objects.requireNonNull(
                lease, "lease");
        String code = failureCode(failureCode);
        AuthoritativeOutcomeInboxPolicy controls =
                Objects.requireNonNull(policy, "policy");
        return Objects.requireNonNull(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactLease.scope().region(),
                            exactLease.scope().environmentId());
                    Instant now = coordinationNow();
                    StoredHead current =
                            requireLease(exactLease, now);
                    int failures = Math.addExact(
                            current.entry()
                                    .consecutiveFailures(),
                            1);
                    StoredObservation observation =
                            currentObservation(current);
                    boolean withinAge = safePlus(
                            observation.observation()
                                    .attributionWindow()
                                    .closesAt(),
                            controls.maximumPendingAge())
                            .isAfter(now);
                    if (retryable
                            && failures
                            < controls.maximumConsecutiveFailures()
                            && withinAge) {
                        AuthoritativeOutcomeInboxEntry queued =
                                activeEntry(
                                        current.entry(),
                                        AuthoritativeOutcomeInboxEntry
                                                .Status.QUEUED,
                                        failures,
                                        safePlus(
                                                now,
                                                controls.retryDelay(
                                                        failures)),
                                        code,
                                        now);
                        return updateHead(
                                current,
                                queued,
                                exactLease.ownerId(),
                                AuthoritativeOutcomeInboxLifecycleEvent
                                        .Transition.RETRY_SCHEDULED,
                                queued.currentObservationFingerprint())
                                .entry();
                    }
                    return quarantine(
                            current,
                            now,
                            withinAge
                                    ? code
                                    : "RG.MIRROR.OUTCOME.RECONCILIATION_EXPIRED",
                            new TransitionOwner(
                                    exactLease.ownerId()))
                            .entry();
                }),
                "outcome failure transition returned null");
    }

    @Override
    public List<AuthoritativeOutcomeInboxLifecycleEvent>
    lifecycle(
            CapabilitySnapshot.Scope scope,
            String observationId,
            long afterOrdinal,
            int limit) {
        if (afterOrdinal < 0
                || limit < 1
                || limit > 1_000) {
            throw new IllegalArgumentException(
                    "lifecycle cursor or limit is invalid");
        }
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = identifier(observationId);
        List<AuthoritativeOutcomeInboxLifecycleEvent> values =
                jdbc.query("""
                        SELECT *
                        FROM mirror_outcome_inbox_lifecycle
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND observation_id = ?
                          AND event_ordinal > ?
                        ORDER BY event_ordinal
                        FETCH FIRST ? ROWS ONLY
                        """,
                        this::mapLifecycle,
                        exactScope.tenantId(),
                        exactScope.organizationId(),
                        exactScope.projectId(),
                        exactScope.environmentId(),
                        exactScope.region(),
                        exactId,
                        afterOrdinal,
                        limit);
        verifyLifecyclePage(
                exactScope, exactId, afterOrdinal, values);
        return List.copyOf(values);
    }

    private Admission appendExternal(
            AuthoritativeOutcomeObservation observation,
            String expected,
            Instant now) {
        Optional<StoredObservation> existing =
                findStoredObservation(
                        observation.scope(),
                        observation.observationId(),
                        observation.revision());
        if (existing.isPresent()) {
            StoredObservation stored =
                    existing.orElseThrow();
            if (!stored.observation().equals(observation)
                    || !stored.predecessorFingerprint()
                    .equals(expected)) {
                throw new Violation(
                        Reason.CONTENT_CONFLICT);
            }
            StoredHead head = findStoredHead(
                    observation.scope(),
                    observation.observationId(),
                    true).orElseThrow(() ->
                    new Violation(
                            Reason.STORED_STATE_CORRUPT));
            if (head.entry().currentRevision()
                    < observation.revision()) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return new Admission(
                    head.entry(), true);
        }
        Optional<StoredHead> previous =
                findStoredHead(
                        observation.scope(),
                        observation.observationId(),
                        true);
        if (previous.isEmpty()) {
            if (observation.revision() != 1
                    || !expected.isBlank()) {
                throw new Violation(
                        Reason.LINEAGE_CONFLICT);
            }
            insertObservation(observation, "");
            AuthoritativeOutcomeInboxEntry entry =
                    initialEntry(observation, now);
            String eventFingerprint = appendLifecycle(
                    entry,
                    AuthoritativeOutcomeInboxLifecycleEvent
                            .Transition.OBSERVATION_APPENDED,
                    "",
                    "",
                    "");
            insertHead(entry, "", eventFingerprint);
            return new Admission(entry, false);
        }
        StoredHead head = previous.orElseThrow();
        if (observation.revision()
                != head.entry().currentRevision() + 1
                || !expected.equals(
                head.entry()
                        .currentObservationFingerprint())) {
            throw new Violation(
                    Reason.LINEAGE_CONFLICT);
        }
        AuthoritativeOutcomeObservation predecessor =
                currentObservation(head).observation();
        requireSuccessor(predecessor, observation);
        insertObservation(observation, expected);
        AuthoritativeOutcomeInboxEntry advanced =
                advancedEntry(
                        head.entry(),
                        observation,
                        now,
                        Duration.ZERO);
        StoredHead stored = updateHead(
                head,
                advanced,
                "",
                AuthoritativeOutcomeInboxLifecycleEvent
                        .Transition.OBSERVATION_APPENDED,
                expected);
        return new Admission(stored.entry(), false);
    }

    private AuthoritativeOutcomeInboxEntry initialEntry(
            AuthoritativeOutcomeObservation observation,
            Instant now) {
        boolean pending = observation.reconciliation()
                == AuthoritativeOutcomeObservation
                .Reconciliation.PENDING;
        return new AuthoritativeOutcomeInboxEntry(
                "",
                observation.scope(),
                observation.observationId(),
                observation.revision(),
                observation.observationFingerprint(),
                observation.inventoryRef(),
                observation.unitId(),
                observation.selectionProof().cohortRef(),
                observation.reconciliation(),
                pending
                        ? AuthoritativeOutcomeInboxEntry
                        .Status.QUEUED
                        : AuthoritativeOutcomeInboxEntry
                        .Status.SETTLED,
                0,
                0,
                pending ? now : Instant.EPOCH,
                "",
                0,
                Instant.EPOCH,
                "",
                now,
                now,
                pending ? null : now,
                "").seal(mapper);
    }

    private AuthoritativeOutcomeInboxEntry advancedEntry(
            AuthoritativeOutcomeInboxEntry before,
            AuthoritativeOutcomeObservation successor,
            Instant now,
            Duration pendingDelay) {
        boolean pending = successor.reconciliation()
                == AuthoritativeOutcomeObservation
                .Reconciliation.PENDING;
        return new AuthoritativeOutcomeInboxEntry(
                "",
                before.scope(),
                before.observationId(),
                successor.revision(),
                successor.observationFingerprint(),
                before.inventoryRef(),
                before.unitId(),
                before.cohortRef(),
                successor.reconciliation(),
                pending
                        ? AuthoritativeOutcomeInboxEntry
                        .Status.QUEUED
                        : AuthoritativeOutcomeInboxEntry
                        .Status.SETTLED,
                before.attemptCount(),
                0,
                pending
                        ? safePlus(now, pendingDelay)
                        : Instant.EPOCH,
                "",
                Math.addExact(before.leaseEpoch(), 1L),
                Instant.EPOCH,
                "",
                before.createdAt(),
                now,
                pending ? null : now,
                "").seal(mapper);
    }

    private AuthoritativeOutcomeInboxEntry activeEntry(
            AuthoritativeOutcomeInboxEntry before,
            AuthoritativeOutcomeInboxEntry.Status status,
            int failures,
            Instant nextEligibleAt,
            String failureCode,
            Instant now) {
        return new AuthoritativeOutcomeInboxEntry(
                "",
                before.scope(),
                before.observationId(),
                before.currentRevision(),
                before.currentObservationFingerprint(),
                before.inventoryRef(),
                before.unitId(),
                before.cohortRef(),
                before.reconciliation(),
                status,
                before.attemptCount(),
                failures,
                nextEligibleAt,
                "",
                before.leaseEpoch(),
                Instant.EPOCH,
                failureCode,
                before.createdAt(),
                now,
                null,
                "").seal(mapper);
    }

    private StoredHead quarantine(
            StoredHead current,
            Instant now,
            String failureCode,
            TransitionOwner owner) {
        AuthoritativeOutcomeInboxEntry quarantined =
                new AuthoritativeOutcomeInboxEntry(
                        "",
                        current.entry().scope(),
                        current.entry().observationId(),
                        current.entry().currentRevision(),
                        current.entry()
                                .currentObservationFingerprint(),
                        current.entry().inventoryRef(),
                        current.entry().unitId(),
                        current.entry().cohortRef(),
                        current.entry().reconciliation(),
                        AuthoritativeOutcomeInboxEntry
                                .Status.QUARANTINED,
                        current.entry().attemptCount(),
                        Math.max(
                                1,
                                current.entry()
                                        .consecutiveFailures()),
                        Instant.EPOCH,
                        "",
                        Math.addExact(
                                current.entry().leaseEpoch(),
                                1L),
                        Instant.EPOCH,
                        failureCode(failureCode),
                        current.entry().createdAt(),
                        now,
                        now,
                        "").seal(mapper);
        return updateHead(
                current,
                quarantined,
                owner.ownerId(),
                AuthoritativeOutcomeInboxLifecycleEvent
                        .Transition.QUARANTINED,
                quarantined.currentObservationFingerprint());
    }

    private void recoverExpired(
            String region,
            String environmentId,
            Instant now,
            AuthoritativeOutcomeInboxPolicy policy) {
        List<StoredHead> expired = jdbc.query("""
                SELECT *
                FROM mirror_outcome_inbox_heads
                WHERE region = ? AND environment_id = ?
                  AND status = 'RUNNING'
                  AND lease_expires_at <= ?
                ORDER BY lease_expires_at, created_at, observation_id
                FETCH FIRST ? ROWS ONLY
                FOR UPDATE
                """,
                this::mapHead,
                region,
                environmentId,
                timestamp(now),
                RECOVERY_LIMIT);
        for (StoredHead current : expired) {
            int failures = Math.addExact(
                    current.entry().consecutiveFailures(),
                    1);
            if (failures
                    >= policy.maximumConsecutiveFailures()) {
                quarantine(
                        current,
                        now,
                        "RG.MIRROR.OUTCOME.LEASE_EXPIRED",
                        TransitionOwner.none());
            } else {
                AuthoritativeOutcomeInboxEntry queued =
                        activeEntry(
                                current.entry(),
                                AuthoritativeOutcomeInboxEntry
                                        .Status.QUEUED,
                                failures,
                                safePlus(
                                        now,
                                        policy.retryDelay(failures)),
                                "RG.MIRROR.OUTCOME.LEASE_EXPIRED",
                                now);
                updateHead(
                        current,
                        queued,
                        "",
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.LEASE_EXPIRED,
                        queued.currentObservationFingerprint());
            }
        }
    }

    private StoredHead requireLease(
            Lease lease,
            Instant now) {
        StoredHead current = findStoredHead(
                lease.scope(),
                lease.observationId(),
                true).orElseThrow(() ->
                new Violation(
                        Reason.OBSERVATION_NOT_FOUND));
        if (current.entry().status()
                != AuthoritativeOutcomeInboxEntry.Status.RUNNING
                || current.entry().currentRevision()
                != lease.observationRevision()
                || !current.entry()
                .currentObservationFingerprint()
                .equals(lease.observationFingerprint())
                || current.entry().leaseEpoch()
                != lease.epoch()
                || !current.leaseOwner().equals(
                lease.ownerId())
                || !current.entry().leaseExpiresAt()
                .equals(lease.expiresAt())
                || !lease.expiresAt().isAfter(now)) {
            throw new Violation(Reason.LEASE_LOST);
        }
        return current;
    }

    private void requireSuccessor(
            AuthoritativeOutcomeObservation predecessor,
            AuthoritativeOutcomeObservation successor) {
        if (successor.revision()
                != predecessor.revision() + 1
                || !successor.observationId().equals(
                predecessor.observationId())
                || !successor.scope().equals(
                predecessor.scope())
                || !successor.inventoryRef().equals(
                predecessor.inventoryRef())
                || !successor.unitId().equals(
                predecessor.unitId())
                || !successor.scenarioCaseRef().equals(
                predecessor.scenarioCaseRef())
                || !successor.targetCapabilityRef().equals(
                predecessor.targetCapabilityRef())
                || !successor.outcomeDefinitionRef().equals(
                predecessor.outcomeDefinitionRef())
                || !successor.attributionPolicyRef().equals(
                predecessor.attributionPolicyRef())
                || !successor.authoritySetRef().equals(
                predecessor.authoritySetRef())
                || !successor.selectionProof().equals(
                predecessor.selectionProof())
                || !successor.subjectFingerprint().equals(
                predecessor.subjectFingerprint())
                || !successor.attributionKeyFingerprint()
                .equals(predecessor
                        .attributionKeyFingerprint())
                || !successor.modelOutcomeFingerprint()
                .equals(predecessor
                        .modelOutcomeFingerprint())
                || !successor.attributionWindow().equals(
                predecessor.attributionWindow())
                || !successor.reconciledAt().isAfter(
                predecessor.reconciledAt())
                || !successor.attestedAt().isAfter(
                predecessor.attestedAt())
                || !watermarksAdvance(
                predecessor, successor)) {
            throw new Violation(
                    Reason.SUCCESSOR_INVALID);
        }
    }

    private static boolean watermarksAdvance(
            AuthoritativeOutcomeObservation predecessor,
            AuthoritativeOutcomeObservation successor) {
        if (predecessor.authorityWatermarks().size()
                != successor.authorityWatermarks().size()) {
            return false;
        }
        boolean changed = false;
        for (int index = 0;
             index < predecessor
                     .authorityWatermarks().size();
             index++) {
            AuthoritativeOutcomeObservation.AuthorityWatermark
                    before = predecessor
                    .authorityWatermarks().get(index);
            AuthoritativeOutcomeObservation.AuthorityWatermark
                    after = successor
                    .authorityWatermarks().get(index);
            if (!before.authorityId().equals(
                    after.authorityId())
                    || after.eventTimeThrough().isBefore(
                    before.eventTimeThrough())
                    || after.publishedAt().isBefore(
                    before.publishedAt())) {
                return false;
            }
            changed |= !before.equals(after);
        }
        return changed
                || !predecessor.authorityFacts().equals(
                successor.authorityFacts());
    }

    private void insertObservation(
            AuthoritativeOutcomeObservation observation,
            String predecessorFingerprint) {
        CapabilitySnapshot.Scope scope =
                observation.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_outcome_observations (
                                tenant_id, organization_id, project_id,
                                environment_id, region, observation_id,
                                revision, observation_fingerprint,
                                predecessor_fingerprint, inventory_id,
                                inventory_revision, inventory_fingerprint,
                                unit_id, cohort_id, cohort_revision,
                                cohort_fingerprint, reconciliation,
                                reconciled_at, attested_at, observation_json
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?
                            )
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    observation.observationId(),
                    observation.revision(),
                    observation.observationFingerprint(),
                    predecessorFingerprint,
                    observation.inventoryRef().id(),
                    observation.inventoryRef().revision(),
                    observation.inventoryRef().fingerprint(),
                    observation.unitId(),
                    observation.selectionProof()
                            .cohortRef().id(),
                    observation.selectionProof()
                            .cohortRef().revision(),
                    observation.selectionProof()
                            .cohortRef().fingerprint(),
                    observation.reconciliation().name(),
                    timestamp(observation.reconciledAt()),
                    timestamp(observation.attestedAt()),
                    mapper.writeValueAsString(observation));
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        } catch (DuplicateKeyException conflict) {
            StoredObservation stored =
                    findStoredObservation(
                            scope,
                            observation.observationId(),
                            observation.revision())
                            .orElseThrow(() -> conflict);
            if (!stored.observation().equals(observation)
                    || !stored.predecessorFingerprint()
                    .equals(predecessorFingerprint)) {
                throw new Violation(
                        Reason.CONTENT_CONFLICT);
            }
        }
    }

    private void insertHead(
            AuthoritativeOutcomeInboxEntry entry,
            String leaseOwner,
            String lastEventFingerprint) {
        CapabilitySnapshot.Scope scope = entry.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_outcome_inbox_heads (
                                tenant_id, organization_id, project_id,
                                environment_id, region, observation_id,
                                current_revision,
                                current_observation_fingerprint,
                                inventory_id, inventory_revision,
                                inventory_fingerprint, unit_id,
                                cohort_id, cohort_revision,
                                cohort_fingerprint, reconciliation,
                                status, attempt_count,
                                consecutive_failures, next_eligible_at,
                                lease_owner, lease_owner_fingerprint,
                                lease_epoch, lease_expires_at, failure_code,
                                created_at, updated_at, terminal_at,
                                record_fingerprint,
                                last_event_fingerprint, entry_json
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                            )
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    entry.observationId(),
                    entry.currentRevision(),
                    entry.currentObservationFingerprint(),
                    entry.inventoryRef().id(),
                    entry.inventoryRef().revision(),
                    entry.inventoryRef().fingerprint(),
                    entry.unitId(),
                    entry.cohortRef().id(),
                    entry.cohortRef().revision(),
                    entry.cohortRef().fingerprint(),
                    entry.reconciliation().name(),
                    entry.status().name(),
                    entry.attemptCount(),
                    entry.consecutiveFailures(),
                    timestamp(entry.nextEligibleAt()),
                    leaseOwner,
                    entry.leaseOwnerFingerprint(),
                    entry.leaseEpoch(),
                    timestamp(entry.leaseExpiresAt()),
                    entry.failureCode(),
                    timestamp(entry.createdAt()),
                    timestamp(entry.updatedAt()),
                    entry.terminalAt() == null
                            ? null
                            : timestamp(entry.terminalAt()),
                    entry.recordFingerprint(),
                    requiredFingerprint(lastEventFingerprint),
                    mapper.writeValueAsString(entry));
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private StoredHead updateHead(
            StoredHead before,
            AuthoritativeOutcomeInboxEntry after,
            String leaseOwner,
            AuthoritativeOutcomeInboxLifecycleEvent.Transition
                    transition,
            String predecessorObservationFingerprint) {
        CapabilitySnapshot.Scope scope = after.scope();
        String exactOwner =
                after.status()
                        == AuthoritativeOutcomeInboxEntry
                        .Status.RUNNING
                        ? owner(leaseOwner)
                        : "";
        try {
            String eventFingerprint = appendLifecycle(
                    after,
                    transition,
                    predecessorObservationFingerprint,
                    leaseOwner,
                    before.lastEventFingerprint());
            int changed = jdbc.update("""
                            UPDATE mirror_outcome_inbox_heads
                            SET current_revision = ?,
                                current_observation_fingerprint = ?,
                                reconciliation = ?, status = ?,
                                attempt_count = ?,
                                consecutive_failures = ?,
                                next_eligible_at = ?, lease_owner = ?,
                                lease_owner_fingerprint = ?,
                                lease_epoch = ?, lease_expires_at = ?,
                                failure_code = ?, updated_at = ?,
                                terminal_at = ?, record_fingerprint = ?,
                                last_event_fingerprint = ?, entry_json = ?
                            WHERE tenant_id = ? AND organization_id = ?
                              AND project_id = ? AND environment_id = ?
                              AND region = ? AND observation_id = ?
                              AND record_fingerprint = ?
                            """,
                    after.currentRevision(),
                    after.currentObservationFingerprint(),
                    after.reconciliation().name(),
                    after.status().name(),
                    after.attemptCount(),
                    after.consecutiveFailures(),
                    timestamp(after.nextEligibleAt()),
                    exactOwner,
                    after.leaseOwnerFingerprint(),
                    after.leaseEpoch(),
                    timestamp(after.leaseExpiresAt()),
                    after.failureCode(),
                    timestamp(after.updatedAt()),
                    after.terminalAt() == null
                            ? null
                            : timestamp(after.terminalAt()),
                    after.recordFingerprint(),
                    eventFingerprint,
                    mapper.writeValueAsString(after),
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    after.observationId(),
                    before.entry().recordFingerprint());
            if (changed != 1) {
                throw new Violation(
                        Reason.LEASE_LOST);
            }
            return new StoredHead(
                    after,
                    exactOwner,
                    eventFingerprint);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private String appendLifecycle(
            AuthoritativeOutcomeInboxEntry entry,
            AuthoritativeOutcomeInboxLifecycleEvent.Transition
                    transition,
            String predecessorObservationFingerprint,
            String ownerId,
            String previousEventFingerprint) {
        CapabilitySnapshot.Scope scope = entry.scope();
        long ordinal = nextEventOrdinal(
                scope, entry.observationId());
        String previous = ordinal == 1
                ? "" : requiredFingerprint(
                previousEventFingerprint);
        AuthoritativeOutcomeInboxLifecycleEvent event =
                new AuthoritativeOutcomeInboxLifecycleEvent(
                        "",
                        scope,
                        entry.observationId(),
                        ordinal,
                        transition,
                        entry.status(),
                        entry.currentRevision(),
                        entry.currentObservationFingerprint(),
                        optionalFingerprint(
                                predecessorObservationFingerprint),
                        entry.reconciliation(),
                        entry.attemptCount(),
                        entry.consecutiveFailures(),
                        entry.leaseEpoch(),
                        ReadOnlyShadowJobIntegrity
                                .ownerFingerprint(
                                        mapper, ownerId),
                        entry.updatedAt(),
                        entry.failureCode(),
                        previous,
                        "").seal(mapper);
        try {
            jdbc.update("""
                            INSERT INTO mirror_outcome_inbox_lifecycle (
                                tenant_id, organization_id, project_id,
                                environment_id, region, observation_id,
                                event_ordinal, occurred_at, transition,
                                status, observation_revision,
                                observation_fingerprint,
                                predecessor_observation_fingerprint,
                                reconciliation, attempt_count,
                                consecutive_failures, lease_epoch,
                                owner_fingerprint, failure_code,
                                previous_event_fingerprint,
                                event_fingerprint, event_json
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?, ?
                            )
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    entry.observationId(),
                    event.eventOrdinal(),
                    timestamp(event.occurredAt()),
                    event.transition().name(),
                    event.status().name(),
                    event.observationRevision(),
                    event.observationFingerprint(),
                    event.predecessorObservationFingerprint(),
                    event.reconciliation().name(),
                    event.attemptCount(),
                    event.consecutiveFailures(),
                    event.leaseEpoch(),
                    event.ownerFingerprint(),
                    event.failureCode(),
                    event.previousEventFingerprint(),
                    event.eventFingerprint(),
                    mapper.writeValueAsString(event));
            return event.eventFingerprint();
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private long nextEventOrdinal(
            CapabilitySnapshot.Scope scope,
            String observationId) {
        Long maximum = jdbc.queryForObject("""
                        SELECT MAX(event_ordinal)
                        FROM mirror_outcome_inbox_lifecycle
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND observation_id = ?
                        """,
                Long.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                observationId);
        return Math.addExact(
                maximum == null ? 0L : maximum, 1L);
    }

    private String latestLifecycleFingerprint(
            CapabilitySnapshot.Scope scope,
            String observationId) {
        List<String> values = jdbc.queryForList("""
                        SELECT event_fingerprint
                        FROM mirror_outcome_inbox_lifecycle
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND observation_id = ?
                        ORDER BY event_ordinal DESC
                        FETCH FIRST 1 ROW ONLY
                        """,
                String.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                observationId);
        return values.stream().findFirst().orElse("");
    }

    private Optional<StoredHead> selectNext(
            String region,
            String environmentId,
            Instant now) {
        return one(jdbc.query("""
                SELECT *
                FROM mirror_outcome_inbox_heads
                WHERE region = ? AND environment_id = ?
                  AND status = 'QUEUED'
                  AND next_eligible_at <= ?
                ORDER BY next_eligible_at, created_at, observation_id
                FETCH FIRST 1 ROW ONLY
                FOR UPDATE
                """,
                this::mapHead,
                region,
                environmentId,
                timestamp(now)));
    }

    private Optional<StoredHead> findStoredHead(
            CapabilitySnapshot.Scope scope,
            String observationId,
            boolean forUpdate) {
        return one(jdbc.query("""
                SELECT *
                FROM mirror_outcome_inbox_heads
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND observation_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                this::mapHead,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                observationId));
    }

    private Optional<StoredObservation>
    findStoredObservation(
            CapabilitySnapshot.Scope scope,
            String observationId,
            long revision) {
        return one(jdbc.query("""
                SELECT *
                FROM mirror_outcome_observations
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND observation_id = ?
                  AND revision = ?
                """,
                this::mapObservation,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                observationId,
                revision));
    }

    private StoredObservation currentObservation(
            StoredHead head) {
        return findStoredObservation(
                head.entry().scope(),
                head.entry().observationId(),
                head.entry().currentRevision())
                .filter(value ->
                        value.observation()
                                .observationFingerprint()
                                .equals(head.entry()
                                        .currentObservationFingerprint()))
                .orElseThrow(() ->
                        new Violation(
                                Reason.STORED_STATE_CORRUPT));
    }

    private StoredObservation mapObservation(
            ResultSet row,
            int ignored) throws SQLException {
        try {
            AuthoritativeOutcomeObservation observation =
                    integrity.verifyLocally(
                            mapper.readValue(
                                    row.getString(
                                            "observation_json"),
                                    AuthoritativeOutcomeObservation
                                            .class));
            CapabilitySnapshot.Scope scope =
                    observation.scope();
            MirrorArtifactRef cohort =
                    observation.selectionProof().cohortRef();
            if (!scope.tenantId().equals(
                    row.getString("tenant_id"))
                    || !scope.organizationId().equals(
                    row.getString("organization_id"))
                    || !scope.projectId().equals(
                    row.getString("project_id"))
                    || !scope.environmentId().equals(
                    row.getString("environment_id"))
                    || !scope.region().equals(
                    row.getString("region"))
                    || !observation.observationId().equals(
                    row.getString("observation_id"))
                    || observation.revision()
                    != row.getLong("revision")
                    || !observation.observationFingerprint()
                    .equals(row.getString(
                            "observation_fingerprint"))
                    || !observation.inventoryRef().id().equals(
                    row.getString("inventory_id"))
                    || observation.inventoryRef().revision()
                    != row.getLong("inventory_revision")
                    || !observation.inventoryRef().fingerprint()
                    .equals(row.getString(
                            "inventory_fingerprint"))
                    || !observation.unitId().equals(
                    row.getString("unit_id"))
                    || !cohort.id().equals(
                    row.getString("cohort_id"))
                    || cohort.revision()
                    != row.getLong("cohort_revision")
                    || !cohort.fingerprint().equals(
                    row.getString("cohort_fingerprint"))
                    || !observation.reconciliation().name()
                    .equals(row.getString(
                            "reconciliation"))
                    || !observation.reconciledAt().equals(
                    instant(row, "reconciled_at"))
                    || !observation.attestedAt().equals(
                    instant(row, "attested_at"))) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            String predecessor = optionalFingerprint(
                    row.getString(
                            "predecessor_fingerprint"));
            if ((observation.revision() == 1)
                    != predecessor.isBlank()) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return new StoredObservation(
                    observation, predecessor);
        } catch (Violation invalid) {
            throw invalid;
        } catch (JsonProcessingException
                 | RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private StoredHead mapHead(
            ResultSet row,
            int ignored) throws SQLException {
        try {
            AuthoritativeOutcomeInboxEntry entry =
                    mapper.readValue(
                            row.getString("entry_json"),
                            AuthoritativeOutcomeInboxEntry.class);
            entry.verify(mapper);
            CapabilitySnapshot.Scope scope =
                    entry.scope();
            if (!scope.tenantId().equals(
                    row.getString("tenant_id"))
                    || !scope.organizationId().equals(
                    row.getString("organization_id"))
                    || !scope.projectId().equals(
                    row.getString("project_id"))
                    || !scope.environmentId().equals(
                    row.getString("environment_id"))
                    || !scope.region().equals(
                    row.getString("region"))
                    || !entry.observationId().equals(
                    row.getString("observation_id"))
                    || entry.currentRevision()
                    != row.getLong("current_revision")
                    || !entry.currentObservationFingerprint()
                    .equals(row.getString(
                            "current_observation_fingerprint"))
                    || !entry.inventoryRef().id().equals(
                    row.getString("inventory_id"))
                    || entry.inventoryRef().revision()
                    != row.getLong("inventory_revision")
                    || !entry.inventoryRef().fingerprint()
                    .equals(row.getString(
                            "inventory_fingerprint"))
                    || !entry.unitId().equals(
                    row.getString("unit_id"))
                    || !entry.cohortRef().id().equals(
                    row.getString("cohort_id"))
                    || entry.cohortRef().revision()
                    != row.getLong("cohort_revision")
                    || !entry.cohortRef().fingerprint()
                    .equals(row.getString(
                            "cohort_fingerprint"))
                    || !entry.reconciliation().name().equals(
                    row.getString("reconciliation"))
                    || !entry.status().name().equals(
                    row.getString("status"))
                    || entry.attemptCount()
                    != row.getLong("attempt_count")
                    || entry.consecutiveFailures()
                    != row.getInt("consecutive_failures")
                    || !entry.nextEligibleAt().equals(
                    instant(row, "next_eligible_at"))
                    || !entry.leaseOwnerFingerprint().equals(
                    row.getString(
                            "lease_owner_fingerprint"))
                    || entry.leaseEpoch()
                    != row.getLong("lease_epoch")
                    || !entry.leaseExpiresAt().equals(
                    instant(row, "lease_expires_at"))
                    || !entry.failureCode().equals(
                    row.getString("failure_code"))
                    || !entry.createdAt().equals(
                    instant(row, "created_at"))
                    || !entry.updatedAt().equals(
                    instant(row, "updated_at"))
                    || !Objects.equals(
                    entry.terminalAt(),
                    nullableInstant(row, "terminal_at"))
                    || !entry.recordFingerprint().equals(
                    row.getString("record_fingerprint"))) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            String owner = row.getString("lease_owner");
            String indexedEventFingerprint =
                    requiredFingerprint(
                            row.getString(
                                    "last_event_fingerprint"));
            String storedEventFingerprint =
                    latestLifecycleFingerprint(
                            scope, entry.observationId());
            if ((entry.status()
                    == AuthoritativeOutcomeInboxEntry
                    .Status.RUNNING)
                    != (owner != null && !owner.isBlank())
                    || !entry.leaseOwnerFingerprint().equals(
                    ReadOnlyShadowJobIntegrity
                            .ownerFingerprint(
                                    mapper, owner))
                    || !indexedEventFingerprint.equals(
                    storedEventFingerprint)) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return new StoredHead(
                    entry,
                    owner == null ? "" : owner,
                    indexedEventFingerprint);
        } catch (Violation invalid) {
            throw invalid;
        } catch (JsonProcessingException
                 | RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private AuthoritativeOutcomeInboxLifecycleEvent
    mapLifecycle(
            ResultSet row,
            int ignored) throws SQLException {
        try {
            AuthoritativeOutcomeInboxLifecycleEvent event =
                    mapper.readValue(
                            row.getString("event_json"),
                            AuthoritativeOutcomeInboxLifecycleEvent
                                    .class);
            event.verify(mapper);
            CapabilitySnapshot.Scope scope = event.scope();
            if (!scope.tenantId().equals(
                    row.getString("tenant_id"))
                    || !scope.organizationId().equals(
                    row.getString("organization_id"))
                    || !scope.projectId().equals(
                    row.getString("project_id"))
                    || !scope.environmentId().equals(
                    row.getString("environment_id"))
                    || !scope.region().equals(
                    row.getString("region"))
                    || !event.observationId().equals(
                    row.getString("observation_id"))
                    || event.eventOrdinal()
                    != row.getLong("event_ordinal")
                    || !event.occurredAt().equals(
                    instant(row, "occurred_at"))
                    || !event.transition().name().equals(
                    row.getString("transition"))
                    || !event.status().name().equals(
                    row.getString("status"))
                    || event.observationRevision()
                    != row.getLong(
                    "observation_revision")
                    || !event.observationFingerprint().equals(
                    row.getString(
                            "observation_fingerprint"))
                    || !event
                    .predecessorObservationFingerprint()
                    .equals(row.getString(
                            "predecessor_observation_fingerprint"))
                    || !event.reconciliation().name().equals(
                    row.getString("reconciliation"))
                    || event.attemptCount()
                    != row.getLong("attempt_count")
                    || event.consecutiveFailures()
                    != row.getInt("consecutive_failures")
                    || event.leaseEpoch()
                    != row.getLong("lease_epoch")
                    || !event.ownerFingerprint().equals(
                    row.getString("owner_fingerprint"))
                    || !event.failureCode().equals(
                    row.getString("failure_code"))
                    || !event.previousEventFingerprint()
                    .equals(row.getString(
                            "previous_event_fingerprint"))
                    || !event.eventFingerprint().equals(
                    row.getString("event_fingerprint"))) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return event;
        } catch (Violation invalid) {
            throw invalid;
        } catch (JsonProcessingException
                 | RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private void verifyLifecyclePage(
            CapabilitySnapshot.Scope scope,
            String observationId,
            long afterOrdinal,
            List<AuthoritativeOutcomeInboxLifecycleEvent> values) {
        if (values.isEmpty()) {
            return;
        }
        long expectedOrdinal = Math.addExact(
                afterOrdinal, 1L);
        String expectedPrevious;
        if (afterOrdinal == 0) {
            expectedPrevious = "";
        } else {
            List<String> previous = jdbc.queryForList("""
                            SELECT event_fingerprint
                            FROM mirror_outcome_inbox_lifecycle
                            WHERE tenant_id = ? AND organization_id = ?
                              AND project_id = ? AND environment_id = ?
                              AND region = ? AND observation_id = ?
                              AND event_ordinal = ?
                            """,
                    String.class,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    observationId,
                    afterOrdinal);
            expectedPrevious = one(previous)
                    .orElseThrow(() ->
                            new Violation(
                                    Reason.STORED_STATE_CORRUPT));
        }
        for (AuthoritativeOutcomeInboxLifecycleEvent event
                : values) {
            if (!event.scope().equals(scope)
                    || !event.observationId().equals(
                    observationId)
                    || event.eventOrdinal()
                    != expectedOrdinal
                    || !event.previousEventFingerprint()
                    .equals(expectedPrevious)) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            expectedOrdinal = Math.addExact(
                    expectedOrdinal, 1L);
            expectedPrevious = event.eventFingerprint();
        }
    }

    private void lockPartition(
            String region,
            String environmentId) {
        Long existing = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_outcome_inbox_locks
                        WHERE region = ? AND environment_id = ?
                        """,
                Long.class,
                region,
                environmentId);
        if (existing == null || existing == 0) {
            beforeLockRowInsert.run();
            try {
                lockRowInitialization.executeWithoutResult(
                        ignored -> jdbc.update("""
                                        INSERT INTO mirror_outcome_inbox_locks (
                                            region, environment_id
                                        ) VALUES (?, ?)
                                        """,
                                region,
                                environmentId));
            } catch (DuplicateKeyException exists) {
                // A concurrent initializer won; savepoint rollback keeps the caller usable.
            }
        }
        jdbc.queryForObject("""
                        SELECT region
                        FROM mirror_outcome_inbox_locks
                        WHERE region = ? AND environment_id = ?
                        FOR UPDATE
                        """,
                String.class,
                region,
                environmentId);
    }

    private Instant coordinationNow() {
        return Objects.requireNonNull(
                coordinationClock.get(),
                "database clock returned null");
    }

    private static Instant databaseNow(
            JdbcTemplate jdbc) {
        Timestamp value = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP",
                Timestamp.class);
        return Objects.requireNonNull(
                value,
                "database clock returned null").toInstant();
    }

    private static DataSourceTransactionManager
    requireSavepointTransactions(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        if (!(Objects.requireNonNull(
                transactionManager,
                "transactionManager")
                instanceof DataSourceTransactionManager exact)
                || !exact.isNestedTransactionAllowed()
                || exact.getDataSource()
                != jdbc.getDataSource()) {
            throw new IllegalArgumentException(
                    "Outcome inbox requires one nested-savepoint "
                            + "DataSourceTransactionManager for its JdbcTemplate");
        }
        return exact;
    }

    private static Timestamp timestamp(
            Instant value) {
        return Timestamp.from(
                Objects.requireNonNull(value, "value"));
    }

    private static Instant instant(
            ResultSet row,
            String column) throws SQLException {
        return Objects.requireNonNull(
                row.getTimestamp(column),
                column).toInstant();
    }

    private static Instant nullableInstant(
            ResultSet row,
            String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant safePlus(
            Instant value,
            Duration duration) {
        try {
            return value.plus(duration);
        } catch (RuntimeException overflow) {
            throw new IllegalArgumentException(
                    "authoritative outcome inbox time bound overflow",
                    overflow);
        }
    }

    private static String owner(String value) {
        String exact = value == null ? "" : value.trim();
        if (!OWNER_ID.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "ownerId is invalid");
        }
        return exact;
    }

    private static String failureCode(String value) {
        String exact = value == null ? "" : value.trim();
        if (!FAILURE_CODE.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "failureCode is invalid");
        }
        return exact;
    }

    private static String identifier(String value) {
        String exact = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "identifier is invalid");
        }
        return exact;
    }

    private static String partition(
            String value,
            String field,
            int maximum) {
        String exact = value == null ? "" : value.trim();
        if (exact.length() > maximum
                || !IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isBlank()
                && !exact.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "fingerprint is invalid");
        }
        return exact;
    }

    private static String requiredFingerprint(
            String value) {
        String exact = optionalFingerprint(value);
        if (exact.isBlank()) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return exact;
    }

    private static <T> Optional<T> one(
            List<T> values) {
        if (values.size() > 1) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return values.stream().findFirst();
    }

    private record StoredObservation(
            AuthoritativeOutcomeObservation observation,
            String predecessorFingerprint
    ) {
        private StoredObservation {
            observation = Objects.requireNonNull(
                    observation, "observation");
            predecessorFingerprint =
                    predecessorFingerprint == null
                            ? ""
                            : predecessorFingerprint;
        }
    }

    private record StoredHead(
            AuthoritativeOutcomeInboxEntry entry,
            String leaseOwner,
            String lastEventFingerprint
    ) {
        private StoredHead {
            entry = Objects.requireNonNull(entry, "entry");
            leaseOwner = leaseOwner == null ? "" : leaseOwner;
            lastEventFingerprint =
                    lastEventFingerprint == null
                            ? ""
                            : lastEventFingerprint;
        }
    }

    private record TransitionOwner(String ownerId) {
        private TransitionOwner {
            ownerId = ownerId == null ? "" : ownerId;
        }

        private static TransitionOwner none() {
            return new TransitionOwner("");
        }
    }
}
