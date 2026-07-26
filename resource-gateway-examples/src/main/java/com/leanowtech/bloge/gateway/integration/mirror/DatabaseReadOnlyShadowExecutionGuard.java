package com.leanowtech.bloge.gateway.integration.mirror;

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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Database-authoritative cross-replica pressure and circuit guard for read-only Shadow work.
 *
 * <p>Every acquisition for one stable guard-policy id is serialized through the same scoped
 * state row. State-row initialization uses a nested savepoint transaction so a concurrent
 * unique-key conflict cannot poison the caller transaction on PostgreSQL or consume an extra
 * pooled connection. The row owns fixed-window start accounting, active concurrency, consecutive
 * failure state, circuit cool-down, and the single half-open probe. Per-execution rows own a
 * random token and monotonically increasing epoch so an expired process cannot renew or complete
 * a replacement lease.</p>
 *
 * <p>The stable policy id deliberately spans grant revisions. A newer signed policy generation
 * may replace the current generation only after all older leases are inactive; rate and circuit
 * history survive that replacement. This prevents policy rotation or multiple sampling grants
 * from multiplying one external system's physical budget.</p>
 */
public final class DatabaseReadOnlyShadowExecutionGuard
        implements ReadOnlyShadowExecutionGuard {
    private static final Pattern TOKEN =
            Pattern.compile("[A-Za-z0-9-]{16,128}");
    private static final String POLICY_KIND =
            "SHADOW_EXECUTION_GUARD_POLICY";
    private static final Runnable NO_INITIALIZATION_PROBE =
            () -> {
            };

    private static final String CREATE_STATES = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_execution_guard_states (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                guard_policy_id VARCHAR(512) NOT NULL,
                guard_policy_revision BIGINT NOT NULL,
                guard_policy_fingerprint VARCHAR(71) NOT NULL,
                baseline_binding_id VARCHAR(512) NOT NULL,
                baseline_binding_revision BIGINT NOT NULL,
                baseline_binding_fingerprint VARCHAR(71) NOT NULL,
                maximum_concurrent INTEGER NOT NULL,
                maximum_starts_per_window INTEGER NOT NULL,
                start_window_millis BIGINT NOT NULL,
                circuit_failure_threshold INTEGER NOT NULL,
                circuit_cool_down_millis BIGINT NOT NULL,
                circuit_state VARCHAR(32) NOT NULL,
                consecutive_failures INTEGER NOT NULL,
                circuit_opened_at TIMESTAMP WITH TIME ZONE,
                half_open_execution_id VARCHAR(512) NOT NULL,
                window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                starts_in_window INTEGER NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, guard_policy_id
                )
            )
            """;
    private static final String CREATE_LEASES = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_execution_guard_leases (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                guard_policy_id VARCHAR(512) NOT NULL,
                execution_id VARCHAR(512) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                guard_policy_revision BIGINT NOT NULL,
                guard_policy_fingerprint VARCHAR(71) NOT NULL,
                baseline_binding_fingerprint VARCHAR(71) NOT NULL,
                status VARCHAR(32) NOT NULL,
                lease_token VARCHAR(128) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                maximum_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                logical_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                acquired_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                terminal_at TIMESTAMP WITH TIME ZONE,
                failure_reason VARCHAR(96) NOT NULL,
                half_open_probe BOOLEAN NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, guard_policy_id, execution_id
                )
            )
            """;
    private static final String CREATE_ACTIVE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_mirror_shadow_guard_active
            ON mirror_shadow_execution_guard_leases (
                tenant_id, organization_id, project_id,
                environment_id, region, guard_policy_id,
                status, lease_expires_at
            )
            """;
    private static final String SELECT_STATE = """
            SELECT *
            FROM mirror_shadow_execution_guard_states
            WHERE tenant_id = ?
              AND organization_id = ?
              AND project_id = ?
              AND environment_id = ?
              AND region = ?
              AND guard_policy_id = ?
            """;
    private static final String SELECT_LEASE = """
            SELECT *
            FROM mirror_shadow_execution_guard_leases
            WHERE tenant_id = ?
              AND organization_id = ?
              AND project_id = ?
              AND environment_id = ?
              AND region = ?
              AND guard_policy_id = ?
              AND execution_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Supplier<Instant> coordinationClock;
    private final Supplier<String> tokenSupplier;
    private final TransactionTemplate mutations;
    private final TransactionTemplate stateInitialization;
    private final Runnable beforeStateInsert;

    /**
     * Creates a shared guard using the application database clock and random lease tokens.
     *
     * @param jdbc transaction-aware JDBC boundary
     * @param mapper canonical immutable-request fingerprint boundary
     * @param transactionManager JDBC manager for the same datasource with nested savepoints enabled
     * @throws IllegalArgumentException when the manager cannot provide the required savepoint scope
     */
    public DatabaseReadOnlyShadowExecutionGuard(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this(
                jdbc,
                mapper,
                transactionManager,
                null,
                () -> UUID.randomUUID().toString(),
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic database-clock and token seam used by concurrency and fencing tests. */
    DatabaseReadOnlyShadowExecutionGuard(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock,
            Supplier<String> tokenSupplier) {
        this(
                jdbc,
                mapper,
                transactionManager,
                coordinationClock,
                tokenSupplier,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic clock, token, and pre-insert race seams for database certification. */
    DatabaseReadOnlyShadowExecutionGuard(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock,
            Supplier<String> tokenSupplier,
            Runnable beforeStateInsert) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.coordinationClock = coordinationClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(
                coordinationClock, "coordinationClock");
        this.tokenSupplier = Objects.requireNonNull(
                tokenSupplier, "tokenSupplier");
        this.beforeStateInsert = Objects.requireNonNull(
                beforeStateInsert, "beforeStateInsert");
        DataSourceTransactionManager exactTransactions =
                requireSavepointTransactions(
                        this.jdbc,
                        transactionManager);
        mutations = new TransactionTemplate(
                exactTransactions);
        mutations.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
        mutations.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        stateInitialization = new TransactionTemplate(
                exactTransactions);
        stateInitialization.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NESTED);
        stateInitialization.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates payload-free shared state and fenced lease tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_STATES);
        jdbc.execute(CREATE_LEASES);
        jdbc.execute(CREATE_ACTIVE_INDEX);
    }

    @Override
    public boolean ready() {
        try {
            Long states = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM mirror_shadow_execution_guard_states
                    """,
                    Long.class);
            Long leases = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM mirror_shadow_execution_guard_leases
                    """,
                    Long.class);
            return states != null
                    && states >= 0
                    && leases != null
                    && leases >= 0;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public Lease acquire(
            ReadOnlyShadowDataPlane.Permit permit,
            ReadOnlyShadowAccessAuthority.Admission admission) {
        ReadOnlyShadowDataPlane.Permit exactPermit =
                Objects.requireNonNull(permit, "permit");
        ReadOnlyShadowAccessAuthority.Admission exactAdmission =
                Objects.requireNonNull(
                        admission, "admission");
        if (!exactPermit.request().scope().equals(
                exactAdmission.scope())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .EXECUTION_ID_CONFLICT);
        }
        return mutate(() -> acquireInside(
                exactPermit, exactAdmission));
    }

    private Lease acquireInside(
            ReadOnlyShadowDataPlane.Permit permit,
            ReadOnlyShadowAccessAuthority.Admission admission) {
        Instant now = coordinationNow();
        Instant maximumExpiresAt = minimum(
                permit.deadlineAt(),
                admission.validUntil());
        if (!permit.deadlineAt().isAfter(now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .DEADLINE_EXCEEDED);
        }
        if (!maximumExpiresAt.isAfter(now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .GRANT_REVOKED);
        }
        Instant jobLeaseExpiresAt;
        try {
            jobLeaseExpiresAt =
                    permit.control().leaseExpiresAt();
        } catch (RuntimeException lost) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .LEASE_LOST);
        }
        Instant leaseExpiresAt = minimum(
                Objects.requireNonNull(
                        jobLeaseExpiresAt,
                        "job lease expiry"),
                maximumExpiresAt);
        if (!leaseExpiresAt.isAfter(now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .LEASE_LOST);
        }

        ReadOnlyShadowSamplingGrantAuthority.Grant grant =
                admission.samplingGrant();
        MirrorArtifactRef policyRef =
                grant.guardPolicyRef();
        Key key = new Key(
                grant.guardScope(),
                policyRef.id());
        MirrorArtifactRef baselineRef =
                permit.request().baselineBindingRef();
        ensureState(
                key,
                policyRef,
                baselineRef,
                grant.limits(),
                now);
        GuardState state = lockState(key);
        state = reconcileExpired(
                key, state, now);
        state = reconcilePolicy(
                key,
                state,
                policyRef,
                baselineRef,
                grant.limits(),
                now);
        deleteExpiredTerminalLeases(
                key, now);

        String requestFingerprint =
                ReadOnlyShadowJobIntegrity
                        .requestFingerprint(
                                mapper,
                                permit.request());
        Optional<LeaseRow> previous =
                lockLease(
                        key,
                        permit.executionId());
        if (previous.isPresent()
                && !previous.get()
                .requestFingerprint()
                .equals(requestFingerprint)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .EXECUTION_ID_CONFLICT);
        }
        if (previous.isPresent()
                && (previous.get().status()
                == LeaseStatus.ACTIVE
                || previous.get().status()
                == LeaseStatus.SUCCEEDED)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .LEASE_LOST);
        }

        state = rollWindowIfRequired(
                key, state, now);
        ProbeDecision probe =
                decideCircuit(
                        key,
                        state,
                        permit.executionId(),
                        now);
        state = probe.state();
        int active = activeLeaseCount(
                key, now);
        if (active
                >= state.limits()
                .maximumConcurrent()) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .BUDGET_EXHAUSTED);
        }
        boolean newLogicalExecution =
                previous.isEmpty();
        if (newLogicalExecution
                && state.startsInWindow()
                >= state.limits()
                .maximumStartsPerWindow()) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .BUDGET_EXHAUSTED);
        }

        String token = token();
        long epoch = previous
                .map(LeaseRow::leaseEpoch)
                .orElse(0L) + 1L;
        Instant logicalStartedAt = previous
                .map(LeaseRow::logicalStartedAt)
                .orElse(now);
        upsertLease(
                key,
                permit.executionId(),
                requestFingerprint,
                policyRef,
                baselineRef,
                token,
                epoch,
                leaseExpiresAt,
                maximumExpiresAt,
                logicalStartedAt,
                now,
                probe.halfOpen());
        if (newLogicalExecution) {
            updateWindowStarts(
                    key,
                    state.startsInWindow() + 1,
                    now);
        }
        return new DatabaseLease(
                key,
                permit.executionId(),
                token,
                epoch,
                maximumExpiresAt);
    }

    private GuardState reconcilePolicy(
            Key key,
            GuardState state,
            MirrorArtifactRef policyRef,
            MirrorArtifactRef baselineRef,
            Limits limits,
            Instant now) {
        if (state.policyRef().equals(policyRef)
                && state.baselineRef().equals(
                baselineRef)
                && state.limits().equals(limits)) {
            return state;
        }
        if (policyRef.revision()
                <= state.policyRef().revision()) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .GRANT_REVOKED);
        }
        if (activeLeaseCount(key, now) != 0) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .BUDGET_EXHAUSTED);
        }
        CircuitState circuitState =
                state.circuitState();
        Instant openedAt =
                state.circuitOpenedAt();
        if (circuitState == CircuitState.CLOSED
                && state.consecutiveFailures()
                >= limits.circuitFailureThreshold()) {
            circuitState = CircuitState.OPEN;
            openedAt = now;
        }
        jdbc.update("""
                        UPDATE mirror_shadow_execution_guard_states
                        SET guard_policy_revision = ?,
                            guard_policy_fingerprint = ?,
                            baseline_binding_id = ?,
                            baseline_binding_revision = ?,
                            baseline_binding_fingerprint = ?,
                            maximum_concurrent = ?,
                            maximum_starts_per_window = ?,
                            start_window_millis = ?,
                            circuit_failure_threshold = ?,
                            circuit_cool_down_millis = ?,
                            circuit_state = ?,
                            circuit_opened_at = ?,
                            half_open_execution_id = '',
                            updated_at = ?
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                        """,
                key.after(
                        policyRef.revision(),
                        policyRef.fingerprint(),
                        baselineRef.id(),
                        baselineRef.revision(),
                        baselineRef.fingerprint(),
                        limits.maximumConcurrent(),
                        limits.maximumStartsPerWindow(),
                        limits.startWindow().toMillis(),
                        limits.circuitFailureThreshold(),
                        limits.circuitCoolDown().toMillis(),
                        circuitState.name(),
                        nullableTimestamp(openedAt),
                        timestamp(now)));
        return lockState(key);
    }

    private GuardState reconcileExpired(
            Key key,
            GuardState state,
            Instant now) {
        List<LeaseRow> expired = jdbc.query(
                SELECT_LEASES_BY_STATUS_AND_EXPIRY,
                this::mapLease,
                key.parameters(
                        LeaseStatus.ACTIVE.name(),
                        timestamp(now)));
        if (!expired.isEmpty()) {
            jdbc.update("""
                            UPDATE mirror_shadow_execution_guard_leases
                            SET status = 'EXPIRED',
                                updated_at = ?,
                                terminal_at = ?,
                                failure_reason = 'LEASE_LOST'
                            WHERE tenant_id = ?
                              AND organization_id = ?
                              AND project_id = ?
                              AND environment_id = ?
                              AND region = ?
                              AND guard_policy_id = ?
                              AND status = 'ACTIVE'
                              AND lease_expires_at <= ?
                            """,
                    key.afterWithSuffix(
                            new Object[]{
                                    timestamp(now),
                                    timestamp(now)
                            },
                            timestamp(now)));
        }
        boolean lostHalfOpenProbe = expired.stream()
                .anyMatch(LeaseRow::halfOpenProbe);
        if (state.circuitState()
                == CircuitState.HALF_OPEN) {
            boolean probeStillActive =
                    activeHalfOpenProbeExists(
                            key,
                            state.halfOpenExecutionId(),
                            now);
            if (lostHalfOpenProbe
                    || !probeStillActive) {
                updateCircuit(
                        key,
                        CircuitState.OPEN,
                        Math.max(
                                state.consecutiveFailures(),
                                state.limits()
                                        .circuitFailureThreshold()),
                        now,
                        "",
                        now);
                return lockState(key);
            }
        }
        if (state.circuitState()
                == CircuitState.CLOSED
                && !expired.isEmpty()) {
            int failures = Math.min(
                    Integer.MAX_VALUE,
                    state.consecutiveFailures()
                            + expired.size());
            if (failures >= state.limits()
                    .circuitFailureThreshold()) {
                updateCircuit(
                        key,
                        CircuitState.OPEN,
                        failures,
                        now,
                        "",
                        now);
            } else {
                updateCircuit(
                        key,
                        CircuitState.CLOSED,
                        failures,
                        null,
                        "",
                        now);
            }
            return lockState(key);
        }
        return state;
    }

    private ProbeDecision decideCircuit(
            Key key,
            GuardState state,
            String executionId,
            Instant now) {
        if (state.circuitState()
                == CircuitState.CLOSED) {
            return new ProbeDecision(
                    state, false);
        }
        if (state.circuitState()
                == CircuitState.HALF_OPEN) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .CIRCUIT_OPEN);
        }
        Instant openedAt = Objects.requireNonNull(
                state.circuitOpenedAt(),
                "open circuit has no timestamp");
        Instant probeEligibleAt = openedAt.plus(
                state.limits()
                        .circuitCoolDown());
        if (now.isBefore(probeEligibleAt)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .CIRCUIT_OPEN);
        }
        updateCircuit(
                key,
                CircuitState.HALF_OPEN,
                state.consecutiveFailures(),
                openedAt,
                executionId,
                now);
        return new ProbeDecision(
                lockState(key), true);
    }

    private GuardState rollWindowIfRequired(
            Key key,
            GuardState state,
            Instant now) {
        if (now.isBefore(
                state.windowStartedAt().plus(
                        state.limits()
                                .startWindow()))) {
            return state;
        }
        jdbc.update("""
                        UPDATE mirror_shadow_execution_guard_states
                        SET window_started_at = ?,
                            starts_in_window = 0,
                            updated_at = ?
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                        """,
                key.after(
                        timestamp(now),
                        timestamp(now)));
        return lockState(key);
    }

    private void renew(
            Key key,
            String executionId,
            String token,
            long epoch,
            Instant maximumExpiresAt,
            Instant requestedExpiresAt) {
        Instant requested = Objects.requireNonNull(
                requestedExpiresAt,
                "leaseExpiresAt");
        mutate(() -> {
            Instant now = coordinationNow();
            Instant replacement = minimum(
                    requested,
                    maximumExpiresAt);
            LeaseRow row = requireActiveLease(
                    key,
                    executionId,
                    token,
                    epoch,
                    now);
            if (!replacement.isAfter(now)
                    || replacement.isBefore(
                    row.leaseExpiresAt())) {
                throw failure(
                        ReadOnlyShadowDataPlane.FailureReason
                                .LEASE_LOST);
            }
            int changed = jdbc.update("""
                            UPDATE mirror_shadow_execution_guard_leases
                            SET lease_expires_at = ?,
                                updated_at = ?
                            WHERE tenant_id = ?
                              AND organization_id = ?
                              AND project_id = ?
                              AND environment_id = ?
                              AND region = ?
                              AND guard_policy_id = ?
                              AND execution_id = ?
                              AND lease_token = ?
                              AND lease_epoch = ?
                              AND status = 'ACTIVE'
                            """,
                    key.afterWithSuffix(
                            new Object[]{
                                    timestamp(replacement),
                                    timestamp(now)
                            },
                            executionId,
                            token,
                            epoch));
            requireOne(changed);
            return Boolean.TRUE;
        });
    }

    private void succeeded(
            Key key,
            String executionId,
            String token,
            long epoch) {
        mutate(() -> {
            Instant now = coordinationNow();
            GuardState state = lockState(key);
            LeaseRow lease = requireActiveLease(
                    key,
                    executionId,
                    token,
                    epoch,
                    now);
            terminalLease(
                    key,
                    executionId,
                    token,
                    epoch,
                    LeaseStatus.SUCCEEDED,
                    "",
                    now);
            if (lease.halfOpenProbe()
                    && state.circuitState()
                    == CircuitState.HALF_OPEN
                    && executionId.equals(
                    state.halfOpenExecutionId())) {
                updateCircuit(
                        key,
                        CircuitState.CLOSED,
                        0,
                        null,
                        "",
                        now);
            } else if (!lease.halfOpenProbe()
                    && state.circuitState()
                    == CircuitState.CLOSED) {
                updateCircuit(
                        key,
                        CircuitState.CLOSED,
                        0,
                        null,
                        "",
                        now);
            }
            return Boolean.TRUE;
        });
    }

    private void failed(
            Key key,
            String executionId,
            String token,
            long epoch,
            ReadOnlyShadowDataPlane.FailureReason reason) {
        ReadOnlyShadowDataPlane.FailureReason exact =
                Objects.requireNonNull(reason, "reason");
        mutate(() -> {
            Instant now = coordinationNow();
            GuardState state = lockState(key);
            LeaseRow lease = requireActiveLease(
                    key,
                    executionId,
                    token,
                    epoch,
                    now);
            terminalLease(
                    key,
                    executionId,
                    token,
                    epoch,
                    LeaseStatus.FAILED,
                    exact.name(),
                    now);
            if (lease.halfOpenProbe()) {
                updateCircuit(
                        key,
                        CircuitState.OPEN,
                        Math.max(
                                state.consecutiveFailures(),
                                state.limits()
                                        .circuitFailureThreshold()),
                        now,
                        "",
                        now);
            } else if (countsTowardCircuit(exact)) {
                int failures = Math.min(
                        Integer.MAX_VALUE,
                        state.consecutiveFailures() + 1);
                if (failures >= state.limits()
                        .circuitFailureThreshold()) {
                    updateCircuit(
                            key,
                            CircuitState.OPEN,
                            failures,
                            now,
                            "",
                            now);
                } else {
                    updateCircuit(
                            key,
                            CircuitState.CLOSED,
                            failures,
                            null,
                            "",
                            now);
                }
            }
            return Boolean.TRUE;
        });
    }

    private void ensureState(
            Key key,
            MirrorArtifactRef policyRef,
            MirrorArtifactRef baselineRef,
            Limits limits,
            Instant now) {
        Long existing = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mirror_shadow_execution_guard_states
                WHERE tenant_id = ?
                  AND organization_id = ?
                  AND project_id = ?
                  AND environment_id = ?
                  AND region = ?
                  AND guard_policy_id = ?
                """,
                Long.class,
                key.parameters());
        if (existing == null || existing == 0) {
            beforeStateInsert.run();
            try {
                stateInitialization.executeWithoutResult(ignored ->
                        jdbc.update("""
                                        INSERT INTO mirror_shadow_execution_guard_states (
                                            tenant_id, organization_id, project_id,
                                            environment_id, region, guard_policy_id,
                                            guard_policy_revision, guard_policy_fingerprint,
                                            baseline_binding_id, baseline_binding_revision,
                                            baseline_binding_fingerprint,
                                            maximum_concurrent, maximum_starts_per_window,
                                            start_window_millis, circuit_failure_threshold,
                                            circuit_cool_down_millis, circuit_state,
                                            consecutive_failures, circuit_opened_at,
                                            half_open_execution_id, window_started_at,
                                            starts_in_window, updated_at
                                        ) VALUES (
                                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                            ?, ?, ?, ?, ?, 'CLOSED', 0, NULL,
                                            '', ?, 0, ?
                                        )
                                        """,
                                key.scopeParametersWith(
                                        key.policyId(),
                                        policyRef.revision(),
                                        policyRef.fingerprint(),
                                        baselineRef.id(),
                                        baselineRef.revision(),
                                        baselineRef.fingerprint(),
                                        limits.maximumConcurrent(),
                                        limits.maximumStartsPerWindow(),
                                        limits.startWindow().toMillis(),
                                        limits.circuitFailureThreshold(),
                                        limits.circuitCoolDown().toMillis(),
                                        timestamp(now),
                                        timestamp(now))));
            } catch (DuplicateKeyException exists) {
                // A concurrent initializer won; rollback to the savepoint keeps the caller usable.
            }
        }
    }

    private GuardState lockState(
            Key key) {
        List<GuardState> rows = jdbc.query(
                SELECT_STATE + " FOR UPDATE",
                this::mapState,
                key.parameters());
        if (rows.size() != 1) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        return rows.getFirst();
    }

    private Optional<LeaseRow> lockLease(
            Key key,
            String executionId) {
        List<LeaseRow> rows = jdbc.query(
                SELECT_LEASE + " FOR UPDATE",
                this::mapLease,
                key.parameters(executionId));
        if (rows.size() > 1) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        return rows.stream().findFirst();
    }

    private LeaseRow requireActiveLease(
            Key key,
            String executionId,
            String token,
            long epoch,
            Instant now) {
        LeaseRow row = lockLease(
                key, executionId)
                .orElseThrow(() -> failure(
                        ReadOnlyShadowDataPlane.FailureReason
                                .LEASE_LOST));
        if (row.status() != LeaseStatus.ACTIVE
                || !row.token().equals(token)
                || row.leaseEpoch() != epoch
                || !row.leaseExpiresAt().isAfter(now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .LEASE_LOST);
        }
        return row;
    }

    private void upsertLease(
            Key key,
            String executionId,
            String requestFingerprint,
            MirrorArtifactRef policyRef,
            MirrorArtifactRef baselineRef,
            String token,
            long epoch,
            Instant leaseExpiresAt,
            Instant maximumExpiresAt,
            Instant logicalStartedAt,
            Instant now,
            boolean halfOpen) {
        int changed = jdbc.update("""
                        UPDATE mirror_shadow_execution_guard_leases
                        SET guard_policy_revision = ?,
                            guard_policy_fingerprint = ?,
                            baseline_binding_fingerprint = ?,
                            status = 'ACTIVE',
                            lease_token = ?,
                            lease_epoch = ?,
                            lease_expires_at = ?,
                            maximum_expires_at = ?,
                            acquired_at = ?,
                            updated_at = ?,
                            terminal_at = NULL,
                            failure_reason = '',
                            half_open_probe = ?
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                          AND execution_id = ?
                        """,
                key.afterWithSuffix(
                        new Object[]{
                                policyRef.revision(),
                                policyRef.fingerprint(),
                                baselineRef.fingerprint(),
                                token,
                                epoch,
                                timestamp(leaseExpiresAt),
                                timestamp(maximumExpiresAt),
                                timestamp(now),
                                timestamp(now),
                                halfOpen
                        },
                        executionId));
        if (changed == 1) {
            return;
        }
        jdbc.update("""
                        INSERT INTO mirror_shadow_execution_guard_leases (
                            tenant_id, organization_id, project_id,
                            environment_id, region, guard_policy_id,
                            execution_id, request_fingerprint,
                            guard_policy_revision, guard_policy_fingerprint,
                            baseline_binding_fingerprint, status,
                            lease_token, lease_epoch, lease_expires_at,
                            maximum_expires_at, logical_started_at,
                            acquired_at, updated_at, terminal_at,
                            failure_reason, half_open_probe
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            'ACTIVE', ?, ?, ?, ?, ?, ?, ?, NULL, '', ?
                        )
                        """,
                key.scopeParametersWith(
                        key.policyId(),
                        executionId,
                        requestFingerprint,
                        policyRef.revision(),
                        policyRef.fingerprint(),
                        baselineRef.fingerprint(),
                        token,
                        epoch,
                        timestamp(leaseExpiresAt),
                        timestamp(maximumExpiresAt),
                        timestamp(logicalStartedAt),
                        timestamp(now),
                        timestamp(now),
                        halfOpen));
    }

    private void terminalLease(
            Key key,
            String executionId,
            String token,
            long epoch,
            LeaseStatus status,
            String failureReason,
            Instant now) {
        int changed = jdbc.update("""
                        UPDATE mirror_shadow_execution_guard_leases
                        SET status = ?,
                            updated_at = ?,
                            terminal_at = ?,
                            failure_reason = ?
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                          AND execution_id = ?
                          AND lease_token = ?
                          AND lease_epoch = ?
                          AND status = 'ACTIVE'
                        """,
                key.afterWithSuffix(
                        new Object[]{
                                status.name(),
                                timestamp(now),
                                timestamp(now),
                                failureReason
                        },
                        executionId,
                        token,
                        epoch));
        requireOne(changed);
    }

    private void updateCircuit(
            Key key,
            CircuitState state,
            int failures,
            Instant openedAt,
            String halfOpenExecutionId,
            Instant now) {
        int changed = jdbc.update("""
                        UPDATE mirror_shadow_execution_guard_states
                        SET circuit_state = ?,
                            consecutive_failures = ?,
                            circuit_opened_at = ?,
                            half_open_execution_id = ?,
                            updated_at = ?
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                        """,
                key.after(
                        state.name(),
                        failures,
                        nullableTimestamp(openedAt),
                        halfOpenExecutionId,
                        timestamp(now)));
        requireOne(changed);
    }

    private void updateWindowStarts(
            Key key,
            int starts,
            Instant now) {
        int changed = jdbc.update("""
                        UPDATE mirror_shadow_execution_guard_states
                        SET starts_in_window = ?,
                            updated_at = ?
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                        """,
                key.after(
                        starts,
                        timestamp(now)));
        requireOne(changed);
    }

    private int activeLeaseCount(
            Key key,
            Instant now) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_shadow_execution_guard_leases
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                          AND status = 'ACTIVE'
                          AND lease_expires_at > ?
                        """,
                Long.class,
                key.parameters(
                        timestamp(now)));
        if (count == null
                || count > Integer.MAX_VALUE) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        return count.intValue();
    }

    private boolean activeHalfOpenProbeExists(
            Key key,
            String executionId,
            Instant now) {
        if (executionId == null
                || executionId.isBlank()) {
            return false;
        }
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_shadow_execution_guard_leases
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                          AND execution_id = ?
                          AND status = 'ACTIVE'
                          AND half_open_probe = TRUE
                          AND lease_expires_at > ?
                        """,
                Long.class,
                key.parameters(
                        executionId,
                        timestamp(now)));
        return count != null && count == 1;
    }

    private void deleteExpiredTerminalLeases(
            Key key,
            Instant now) {
        jdbc.update("""
                        DELETE FROM mirror_shadow_execution_guard_leases
                        WHERE tenant_id = ?
                          AND organization_id = ?
                          AND project_id = ?
                          AND environment_id = ?
                          AND region = ?
                          AND guard_policy_id = ?
                          AND status <> 'ACTIVE'
                          AND maximum_expires_at <= ?
                        """,
                key.parameters(
                        timestamp(now)));
    }

    private GuardState mapState(
            ResultSet row,
            int index) throws SQLException {
        return new GuardState(
                new MirrorArtifactRef(
                        POLICY_KIND,
                        row.getString("guard_policy_id"),
                        row.getLong(
                                "guard_policy_revision"),
                        row.getString(
                                "guard_policy_fingerprint")),
                new MirrorArtifactRef(
                        "SHADOW_BASELINE_BINDING",
                        row.getString(
                                "baseline_binding_id"),
                        row.getLong(
                                "baseline_binding_revision"),
                        row.getString(
                                "baseline_binding_fingerprint")),
                new Limits(
                        row.getInt(
                                "maximum_concurrent"),
                        row.getInt(
                                "maximum_starts_per_window"),
                        java.time.Duration.ofMillis(
                                row.getLong(
                                        "start_window_millis")),
                        row.getInt(
                                "circuit_failure_threshold"),
                        java.time.Duration.ofMillis(
                                row.getLong(
                                        "circuit_cool_down_millis"))),
                enumValue(
                        CircuitState.class,
                        row.getString("circuit_state")),
                row.getInt(
                        "consecutive_failures"),
                nullableInstant(
                        row,
                        "circuit_opened_at"),
                row.getString(
                        "half_open_execution_id"),
                instant(
                        row,
                        "window_started_at"),
                row.getInt(
                        "starts_in_window"));
    }

    private LeaseRow mapLease(
            ResultSet row,
            int index) throws SQLException {
        return new LeaseRow(
                row.getString("execution_id"),
                row.getString(
                        "request_fingerprint"),
                enumValue(
                        LeaseStatus.class,
                        row.getString("status")),
                row.getString("lease_token"),
                row.getLong("lease_epoch"),
                instant(
                        row,
                        "lease_expires_at"),
                instant(
                        row,
                        "maximum_expires_at"),
                instant(
                        row,
                        "logical_started_at"),
                row.getBoolean(
                        "half_open_probe"));
    }

    private String token() {
        String value = Objects.requireNonNull(
                tokenSupplier.get(),
                "token supplier returned null").trim();
        if (!TOKEN.matcher(value).matches()) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        return value;
    }

    private Instant coordinationNow() {
        return Objects.requireNonNull(
                coordinationClock.get(),
                "database clock returned null");
    }

    private <T> T mutate(
            Supplier<T> action) {
        try {
            return Objects.requireNonNull(
                    mutations.execute(
                            status -> action.get()),
                    "guard mutation returned null");
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }

    private static boolean countsTowardCircuit(
            ReadOnlyShadowDataPlane.FailureReason reason) {
        return switch (reason) {
            case BASELINE_SOURCE_UNAVAILABLE,
                 CANDIDATE_RUNTIME_UNAVAILABLE,
                 SOURCE_VERIFICATION_FAILED,
                 WRITE_CAPABILITY_DETECTED,
                 WRITE_ATTEMPT_DETECTED,
                 NORMALIZATION_FAILED -> true;
            default -> false;
        };
    }

    private static void requireOne(
            int changed) {
        if (changed != 1) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .LEASE_LOST);
        }
    }

    private static Instant minimum(
            Instant first,
            Instant second) {
        Instant left = Objects.requireNonNull(
                first, "first");
        Instant right = Objects.requireNonNull(
                second, "second");
        return left.isBefore(right)
                ? left : right;
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
                    "Shadow execution guard requires one nested-savepoint "
                            + "DataSourceTransactionManager for its JdbcTemplate");
        }
        return exact;
    }

    private static Timestamp timestamp(
            Instant value) {
        return Timestamp.from(
                Objects.requireNonNull(value, "value"));
    }

    private static Timestamp nullableTimestamp(
            Instant value) {
        return value == null
                ? null : Timestamp.from(value);
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
        Timestamp value =
                row.getTimestamp(column);
        return value == null
                ? null : value.toInstant();
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value) {
        try {
            return Enum.valueOf(
                    type,
                    Objects.requireNonNull(
                            value, "enum value"));
        } catch (RuntimeException invalid) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }

    private static ReadOnlyShadowDataPlane.Failure failure(
            ReadOnlyShadowDataPlane.FailureReason reason) {
        return new ReadOnlyShadowDataPlane.Failure(
                reason);
    }

    private static final String
            SELECT_LEASES_BY_STATUS_AND_EXPIRY = """
            SELECT *
            FROM mirror_shadow_execution_guard_leases
            WHERE tenant_id = ?
              AND organization_id = ?
              AND project_id = ?
              AND environment_id = ?
              AND region = ?
              AND guard_policy_id = ?
              AND status = ?
              AND lease_expires_at <= ?
            FOR UPDATE
            """;

    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private enum LeaseStatus {
        ACTIVE,
        SUCCEEDED,
        FAILED,
        EXPIRED
    }

    private record GuardState(
            MirrorArtifactRef policyRef,
            MirrorArtifactRef baselineRef,
            Limits limits,
            CircuitState circuitState,
            int consecutiveFailures,
            Instant circuitOpenedAt,
            String halfOpenExecutionId,
            Instant windowStartedAt,
            int startsInWindow) {
        private GuardState {
            policyRef = Objects.requireNonNull(
                    policyRef, "policyRef");
            baselineRef = Objects.requireNonNull(
                    baselineRef, "baselineRef");
            limits = Objects.requireNonNull(
                    limits, "limits");
            circuitState = Objects.requireNonNull(
                    circuitState, "circuitState");
            halfOpenExecutionId =
                    halfOpenExecutionId == null
                            ? "" : halfOpenExecutionId;
            windowStartedAt = Objects.requireNonNull(
                    windowStartedAt, "windowStartedAt");
            if (consecutiveFailures < 0
                    || startsInWindow < 0
                    || (circuitState
                    == CircuitState.OPEN
                    && circuitOpenedAt == null)
                    || (circuitState
                    == CircuitState.HALF_OPEN
                    && circuitOpenedAt == null)
                    || (circuitState
                    == CircuitState.HALF_OPEN
                    && halfOpenExecutionId.isBlank())) {
                throw failure(
                        ReadOnlyShadowDataPlane.FailureReason
                                .ADMISSION_AUTHORITY_UNAVAILABLE);
            }
        }
    }

    private record LeaseRow(
            String executionId,
            String requestFingerprint,
            LeaseStatus status,
            String token,
            long leaseEpoch,
            Instant leaseExpiresAt,
            Instant maximumExpiresAt,
            Instant logicalStartedAt,
            boolean halfOpenProbe) {
        private LeaseRow {
            executionId = Objects.requireNonNull(
                    executionId, "executionId");
            requestFingerprint = Objects.requireNonNull(
                    requestFingerprint,
                    "requestFingerprint");
            status = Objects.requireNonNull(
                    status, "status");
            token = Objects.requireNonNull(
                    token, "token");
            leaseExpiresAt = Objects.requireNonNull(
                    leaseExpiresAt, "leaseExpiresAt");
            maximumExpiresAt = Objects.requireNonNull(
                    maximumExpiresAt,
                    "maximumExpiresAt");
            logicalStartedAt = Objects.requireNonNull(
                    logicalStartedAt,
                    "logicalStartedAt");
            if (!TOKEN.matcher(token).matches()
                    || !requestFingerprint.matches(
                    "sha256:[a-f0-9]{64}")
                    || leaseEpoch < 1
                    || leaseExpiresAt.isAfter(
                    maximumExpiresAt)) {
                throw failure(
                        ReadOnlyShadowDataPlane.FailureReason
                                .ADMISSION_AUTHORITY_UNAVAILABLE);
            }
        }
    }

    private record ProbeDecision(
            GuardState state,
            boolean halfOpen) {
    }

    private record Key(
            CapabilitySnapshot.Scope scope,
            String policyId) {
        private Key {
            scope = Objects.requireNonNull(
                    scope, "scope");
            policyId = Objects.requireNonNull(
                    policyId, "policyId");
        }

        private Object[] parameters(
                Object... suffix) {
            return scopeParametersWith(
                    prepend(policyId, suffix));
        }

        private Object[] scopeParametersWith(
                Object... suffix) {
            Object[] result =
                    new Object[5 + suffix.length];
            result[0] = scope.tenantId();
            result[1] = scope.organizationId();
            result[2] = scope.projectId();
            result[3] = scope.environmentId();
            result[4] = scope.region();
            System.arraycopy(
                    suffix,
                    0,
                    result,
                    5,
                    suffix.length);
            return result;
        }

        private Object[] after(
                Object... prefix) {
            return concatenate(
                    prefix,
                    parameters());
        }

        private Object[] afterWithSuffix(
                Object[] prefix,
                Object... suffix) {
            return concatenate(
                    prefix,
                    parameters(suffix));
        }

        private static Object[] prepend(
                Object first,
                Object[] rest) {
            Object[] result =
                    new Object[rest.length + 1];
            result[0] = first;
            System.arraycopy(
                    rest,
                    0,
                    result,
                    1,
                    rest.length);
            return result;
        }

        private static Object[] concatenate(
                Object[] first,
                Object[] second) {
            Object[] result =
                    new Object[first.length
                            + second.length];
            System.arraycopy(
                    first,
                    0,
                    result,
                    0,
                    first.length);
            System.arraycopy(
                    second,
                    0,
                    result,
                    first.length,
                    second.length);
            return result;
        }
    }

    private final class DatabaseLease
            implements Lease {
        private final Key key;
        private final String executionId;
        private final String token;
        private final long epoch;
        private final Instant maximumExpiresAt;
        private boolean terminal;
        private boolean closed;

        private DatabaseLease(
                Key key,
                String executionId,
                String token,
                long epoch,
                Instant maximumExpiresAt) {
            this.key = key;
            this.executionId = executionId;
            this.token = token;
            this.epoch = epoch;
            this.maximumExpiresAt =
                    maximumExpiresAt;
        }

        @Override
        public synchronized void renew(
                Instant leaseExpiresAt) {
            requireOpen();
            DatabaseReadOnlyShadowExecutionGuard.this
                    .renew(
                            key,
                            executionId,
                            token,
                            epoch,
                            maximumExpiresAt,
                            leaseExpiresAt);
        }

        @Override
        public synchronized void succeeded() {
            requireOpen();
            DatabaseReadOnlyShadowExecutionGuard.this
                    .succeeded(
                            key,
                            executionId,
                            token,
                            epoch);
            terminal = true;
        }

        @Override
        public synchronized void failed(
                ReadOnlyShadowDataPlane
                        .FailureReason reason) {
            requireOpen();
            DatabaseReadOnlyShadowExecutionGuard.this
                    .failed(
                            key,
                            executionId,
                            token,
                            epoch,
                            reason);
            terminal = true;
        }

        @Override
        public synchronized void close() {
            closed = true;
        }

        private void requireOpen() {
            if (closed || terminal) {
                throw failure(
                        ReadOnlyShadowDataPlane
                                .FailureReason.LEASE_LOST);
            }
        }
    }
}
