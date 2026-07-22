package com.leanowtech.bloge.gateway.integration.mirror;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * H2-backed payload-free request coordinator for protected mirror executions.
 *
 * <p>The complete enterprise scope and request id form the primary key. Every state-changing
 * operation is serialized by a row lock. The database clock is the sole lease-time authority;
 * terminal writes also compare the lease owner, epoch, original expiry, and database coordination time.
 * Lease expiry therefore revokes authority without requiring a takeover first or trusting replica
 * wall clocks. Clock samples use an independent short database connection after authority-row
 * locking because H2 freezes {@code CURRENT_TIMESTAMP} inside one transaction. The configured
 * datasource must therefore allow a transaction connection and a clock connection concurrently.
 * The schema intentionally has no JSON/CLOB or business-value column, making accidental request
 * payload persistence structurally impossible at this boundary.</p>
 */
public class DatabaseMirrorRunRequestRepository implements MirrorRunRequestRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mirror_run_requests (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                request_id VARCHAR(512) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                context_fingerprint VARCHAR(71) NOT NULL,
                plan_id VARCHAR(512) NOT NULL,
                plan_fingerprint VARCHAR(71) NOT NULL,
                status VARCHAR(32) NOT NULL,
                lease_owner VARCHAR(512) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at VARCHAR(64) NOT NULL,
                run_id VARCHAR(512) NOT NULL,
                evidence_bundle_fingerprint VARCHAR(71) NOT NULL,
                last_failure_code VARCHAR(256) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                retain_until VARCHAR(64) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region, request_id
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO mirror_run_requests (
                tenant_id, organization_id, project_id, environment_id, region,
                request_id, request_fingerprint, context_fingerprint, plan_id, plan_fingerprint,
                status, lease_owner, lease_epoch, lease_expires_at, run_id,
                evidence_bundle_fingerprint, last_failure_code, created_at, updated_at, retain_until
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 1, ?, '', '', '', ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT request_id, request_fingerprint, context_fingerprint, plan_id,
                   plan_fingerprint, status, lease_owner, lease_epoch, lease_expires_at,
                   run_id, evidence_bundle_fingerprint, last_failure_code,
                   created_at, updated_at, retain_until
            FROM mirror_run_requests
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
            """;
    private static final String SELECT_EXACT_FOR_UPDATE = SELECT_EXACT + " FOR UPDATE";
    private static final String TAKE_OVER = """
            UPDATE mirror_run_requests
            SET lease_owner = ?, lease_epoch = ?, lease_expires_at = ?,
                last_failure_code = '', updated_at = ?, retain_until = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
              AND status = 'ACTIVE' AND lease_epoch = ?
            """;
    private static final String COMPLETE = """
            UPDATE mirror_run_requests
            SET status = 'COMPLETED', run_id = ?, evidence_bundle_fingerprint = ?,
                last_failure_code = '', updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;
    private static final String RELEASE = """
            UPDATE mirror_run_requests
            SET lease_expires_at = ?, last_failure_code = ?, updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND request_id = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;

    private final JdbcTemplate jdbc;
    private final Supplier<Instant> coordinationClock;

    /** @param jdbc application JDBC boundary owned by the isolated mirror composition */
    public DatabaseMirrorRunRequestRepository(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    /** Package-private deterministic database-clock seam for repository concurrency tests. */
    DatabaseMirrorRunRequestRepository(
            JdbcTemplate jdbc, Supplier<Instant> coordinationClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.coordinationClock = coordinationClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(coordinationClock, "coordinationClock");
    }

    /** Creates the payload-free coordination table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public Claim claim(
            Registration registration,
            String leaseOwner,
            Duration leaseDuration) {
        Objects.requireNonNull(registration, "registration");
        String owner = required(leaseOwner, "leaseOwner");
        Duration duration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Optional<State> existing = locked(registration.scope(), registration.requestId());
        Instant observedAt = coordinationNow();
        requireFutureRetention(registration, observedAt);
        Instant expiresAt = observedAt.plus(duration);
        if (existing.isEmpty()) {
            try {
                insert(registration, owner, observedAt, expiresAt);
                State state = locked(registration.scope(), registration.requestId()).orElseThrow();
                return acquired(state);
            } catch (DuplicateKeyException concurrentInsert) {
                existing = locked(registration.scope(), registration.requestId());
                if (existing.isEmpty()) {
                    throw concurrentInsert;
                }
                observedAt = coordinationNow();
                requireFutureRetention(registration, observedAt);
                expiresAt = observedAt.plus(duration);
            }
        }

        State state = existing.orElseThrow();
        requireSameRegistration(state.registration(), registration);
        if (state.status() == Status.COMPLETED) {
            return new Claim(Outcome.COMPLETED, state, null, 0);
        }
        if (state.leaseExpiresAt().isAfter(observedAt)) {
            return new Claim(Outcome.IN_PROGRESS, state, null,
                    retryAfterSeconds(observedAt, state.leaseExpiresAt()));
        }

        long nextEpoch = Math.addExact(state.leaseEpoch(), 1);
        CapabilitySnapshot.Scope scope = registration.scope();
        int updated = jdbc.update(TAKE_OVER, owner, nextEpoch, expiresAt.toString(),
                observedAt.toString(), later(state.registration().retainUntil(),
                        registration.retainUntil()).toString(),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), registration.requestId(),
                state.leaseEpoch());
        if (updated != 1) {
            throw new IllegalStateException("mirror request lease changed while row was locked");
        }
        return acquired(locked(scope, registration.requestId()).orElseThrow());
    }

    @Override
    @Transactional
    public boolean complete(
            Lease lease,
            String runId,
            String evidenceBundleFingerprint) {
        Objects.requireNonNull(lease, "lease");
        CapabilitySnapshot.Scope scope = lease.scope();
        Optional<State> current = locked(scope, lease.requestId());
        Instant at = coordinationNow();
        if (current.isEmpty()
                || current.get().status() != Status.ACTIVE
                || !current.get().leaseOwner().equals(lease.leaseOwner())
                || current.get().leaseEpoch() != lease.leaseEpoch()
                || !at.isBefore(current.get().leaseExpiresAt())) {
            return false;
        }
        return jdbc.update(COMPLETE, required(runId, "runId"),
                required(evidenceBundleFingerprint, "evidenceBundleFingerprint"), at.toString(),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), lease.requestId(), lease.leaseOwner(),
                lease.leaseEpoch(), current.get().leaseExpiresAt().toString()) == 1;
    }

    @Override
    @Transactional
    public boolean release(Lease lease, String failureCode) {
        Objects.requireNonNull(lease, "lease");
        String code = bounded(failureCode, 256);
        CapabilitySnapshot.Scope scope = lease.scope();
        Optional<State> current = locked(scope, lease.requestId());
        if (current.isEmpty()
                || current.get().status() != Status.ACTIVE
                || !current.get().leaseOwner().equals(lease.leaseOwner())
                || current.get().leaseEpoch() != lease.leaseEpoch()) {
            return false;
        }
        Instant at = coordinationNow();
        return jdbc.update(RELEASE, at.toString(), code, at.toString(),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), lease.requestId(), lease.leaseOwner(),
                lease.leaseEpoch(), current.get().leaseExpiresAt().toString()) == 1;
    }

    @Override
    public Optional<State> find(CapabilitySnapshot.Scope scope, String requestId) {
        Objects.requireNonNull(scope, "scope");
        return query(SELECT_EXACT, scope, required(requestId, "requestId"));
    }

    private Optional<State> locked(CapabilitySnapshot.Scope scope, String requestId) {
        return query(SELECT_EXACT_FOR_UPDATE, scope, requestId);
    }

    private Optional<State> query(
            String sql, CapabilitySnapshot.Scope scope, String requestId) {
        List<State> states = jdbc.query(sql, (rs, rowNumber) -> state(rs, scope),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), requestId);
        return states.stream().findFirst();
    }

    private static State state(ResultSet rs, CapabilitySnapshot.Scope scope) throws SQLException {
        Registration registration = new Registration(scope, rs.getString("request_id"),
                rs.getString("request_fingerprint"), rs.getString("context_fingerprint"),
                rs.getString("plan_id"), rs.getString("plan_fingerprint"),
                Instant.parse(rs.getString("retain_until")));
        return new State(registration, Status.valueOf(rs.getString("status")),
                rs.getString("lease_owner"), rs.getLong("lease_epoch"),
                Instant.parse(rs.getString("lease_expires_at")), rs.getString("run_id"),
                rs.getString("evidence_bundle_fingerprint"),
                rs.getString("last_failure_code"), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private void insert(
            Registration registration, String owner, Instant now, Instant leaseExpiresAt) {
        CapabilitySnapshot.Scope scope = registration.scope();
        jdbc.update(INSERT, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), registration.requestId(),
                registration.requestFingerprint(), registration.contextFingerprint(),
                registration.planId(), registration.planFingerprint(), owner,
                leaseExpiresAt.toString(), now.toString(), now.toString(),
                registration.retainUntil().toString());
    }

    private static Claim acquired(State state) {
        return new Claim(Outcome.ACQUIRED, state, new Lease(state.registration().scope(),
                state.registration().requestId(), state.leaseOwner(), state.leaseEpoch()), 0);
    }

    private static void requireSameRegistration(
            Registration existing, Registration requested) {
        if (!existing.scope().equals(requested.scope())
                || !existing.requestId().equals(requested.requestId())
                || !existing.requestFingerprint().equals(requested.requestFingerprint())
                || !existing.contextFingerprint().equals(requested.contextFingerprint())
                || !existing.planId().equals(requested.planId())
                || !existing.planFingerprint().equals(requested.planFingerprint())) {
            throw new MirrorRunRequestConflictException();
        }
    }

    private static void requireFutureRetention(Registration registration, Instant observedAt) {
        if (!registration.retainUntil().isAfter(observedAt)) {
            throw new IllegalArgumentException(
                    "retention boundary must be later than database coordination time");
        }
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static long retryAfterSeconds(Instant observedAt, Instant leaseExpiresAt) {
        Duration remaining = Duration.between(observedAt, leaseExpiresAt);
        long wholeSeconds = remaining.getSeconds();
        return remaining.getNano() == 0
                ? Math.max(1, wholeSeconds)
                : Math.addExact(wholeSeconds, 1);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private Instant coordinationNow() {
        return Objects.requireNonNull(coordinationClock.get(),
                "mirror request database clock returned null");
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        DataSource dataSource = independentDataSource(Objects.requireNonNull(
                jdbc.getDataSource(), "mirror request database datasource is unavailable"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT CURRENT_TIMESTAMP")) {
            if (!result.next()) {
                throw new IllegalStateException("mirror request database clock returned no row");
            }
            Timestamp value = result.getTimestamp(1);
            if (value == null) {
                throw new IllegalStateException("mirror request database clock returned null");
            }
            return value.toInstant();
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "mirror request database clock is unavailable", unavailable);
        }
    }

    private static DataSource independentDataSource(DataSource source) {
        DataSource current = source;
        while (current instanceof DelegatingDataSource delegating) {
            DataSource target = delegating.getTargetDataSource();
            if (target == null || target == current) {
                break;
            }
            current = target;
        }
        return current;
    }
}
