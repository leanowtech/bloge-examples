package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import jakarta.annotation.PostConstruct;
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

/** Database-clock-coordinated H2/PostgreSQL Proposal simulation repository. */
public class DatabaseCapabilityProposalSimulationRepository
        implements CapabilityProposalSimulationRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS rg_bm_proposal_simulation (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                proposal_id VARCHAR(512) NOT NULL,
                proposal_revision BIGINT NOT NULL,
                simulation_id VARCHAR(512) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                status VARCHAR(32) NOT NULL,
                lease_owner VARCHAR(512) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at VARCHAR(64) NOT NULL,
                result_json TEXT,
                last_failure_code VARCHAR(256) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region,
                             proposal_id, proposal_revision),
                UNIQUE (tenant_id, organization_id, project_id, environment_id, region,
                        simulation_id)
            )
            """;
    private static final String SELECT = """
            SELECT proposal_id, proposal_revision, simulation_id, request_fingerprint,
                   status, lease_owner, lease_epoch, lease_expires_at, result_json,
                   last_failure_code, created_at, updated_at
            FROM rg_bm_proposal_simulation
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND proposal_id = ? AND proposal_revision = ?
            """;
    private static final String SELECT_FOR_UPDATE = SELECT + " FOR UPDATE";
    private static final String INSERT = """
            INSERT INTO rg_bm_proposal_simulation (
                tenant_id, organization_id, project_id, environment_id, region,
                proposal_id, proposal_revision, simulation_id, request_fingerprint,
                status, lease_owner, lease_epoch, lease_expires_at, result_json,
                last_failure_code, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 1, ?, NULL, '', ?, ?)
            ON CONFLICT DO NOTHING
            """;
    private static final String TAKE_OVER = """
            UPDATE rg_bm_proposal_simulation
            SET lease_owner = ?, lease_epoch = ?, lease_expires_at = ?,
                last_failure_code = '', updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND proposal_id = ? AND proposal_revision = ?
              AND status = 'ACTIVE' AND lease_epoch = ?
            """;
    private static final String COMPLETE = """
            UPDATE rg_bm_proposal_simulation
            SET status = 'COMPLETED', result_json = ?, last_failure_code = '', updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND proposal_id = ? AND proposal_revision = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;
    private static final String RENEW = """
            UPDATE rg_bm_proposal_simulation
            SET lease_expires_at = ?, updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND proposal_id = ? AND proposal_revision = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;
    private static final String RELEASE = """
            UPDATE rg_bm_proposal_simulation
            SET lease_expires_at = ?, last_failure_code = ?, updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND proposal_id = ? AND proposal_revision = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseCapabilityProposalSimulationRepository(
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public Claim claim(
            Registration registration, String leaseOwner, Duration leaseDuration) {
        validate(registration);
        String owner = required(leaseOwner, "leaseOwner");
        Duration duration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Optional<State> existing = locked(registration.scope(), registration.proposalId(),
                registration.proposalRevision());
        Instant now = databaseNow();
        Instant expiresAt = now.plus(duration);
        if (existing.isEmpty()) {
            if (insert(registration, owner, now, expiresAt) == 1) {
                return acquired(locked(registration.scope(), registration.proposalId(),
                        registration.proposalRevision()).orElseThrow());
            }
            existing = locked(registration.scope(), registration.proposalId(),
                    registration.proposalRevision());
            if (existing.isEmpty()) {
                throw new IllegalStateException(
                        "Proposal simulation command disappeared after insert arbitration");
            }
            now = databaseNow();
            expiresAt = now.plus(duration);
        }
        State state = existing.orElseThrow();
        requireSame(state.registration(), registration);
        if (state.status() == Status.COMPLETED) {
            return new Claim(Outcome.COMPLETED, state, null, 0);
        }
        if (state.leaseExpiresAt().isAfter(now)) {
            long retry = Math.max(1, Duration.between(now, state.leaseExpiresAt()).toSeconds());
            return new Claim(Outcome.IN_PROGRESS, state, null, retry);
        }
        long epoch = Math.addExact(state.leaseEpoch(), 1);
        CapabilitySnapshot.Scope scope = registration.scope();
        int updated = jdbc.update(TAKE_OVER, owner, epoch, expiresAt.toString(), now.toString(),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), registration.proposalId(), registration.proposalRevision(),
                state.leaseEpoch());
        if (updated != 1) {
            throw new IllegalStateException("Proposal simulation lease changed while locked");
        }
        return acquired(locked(scope, registration.proposalId(),
                registration.proposalRevision()).orElseThrow());
    }

    @Override
    @Transactional
    public boolean renew(Lease lease, Duration leaseDuration) {
        Objects.requireNonNull(lease, "lease");
        Duration duration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        State current = locked(lease.scope(), lease.proposalId(), lease.proposalRevision())
                .orElse(null);
        Instant now = databaseNow();
        if (!owns(current, lease) || !now.isBefore(current.leaseExpiresAt())) {
            return false;
        }
        Instant expiresAt = now.plus(duration);
        CapabilitySnapshot.Scope scope = lease.scope();
        return jdbc.update(RENEW, expiresAt.toString(), now.toString(), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(), scope.region(),
                lease.proposalId(), lease.proposalRevision(), lease.leaseOwner(),
                lease.leaseEpoch(), current.leaseExpiresAt().toString()) == 1;
    }

    @Override
    @Transactional
    public boolean complete(Lease lease, StoredCapabilityProposalSimulation result) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(result, "result");
        State current = locked(lease.scope(), lease.proposalId(), lease.proposalRevision())
                .orElse(null);
        Instant now = databaseNow();
        if (!owns(current, lease) || !now.isBefore(current.leaseExpiresAt())) {
            return false;
        }
        CapabilitySnapshot.Scope scope = lease.scope();
        return jdbc.update(COMPLETE, serialize(result), now.toString(), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(), scope.region(),
                lease.proposalId(), lease.proposalRevision(), lease.leaseOwner(),
                lease.leaseEpoch(), current.leaseExpiresAt().toString()) == 1;
    }

    @Override
    @Transactional
    public boolean release(Lease lease, String failureCode) {
        Objects.requireNonNull(lease, "lease");
        State current = locked(lease.scope(), lease.proposalId(), lease.proposalRevision())
                .orElse(null);
        if (!owns(current, lease)) {
            return false;
        }
        Instant now = databaseNow();
        CapabilitySnapshot.Scope scope = lease.scope();
        return jdbc.update(RELEASE, now.toString(), bounded(failureCode, 256), now.toString(),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), lease.proposalId(), lease.proposalRevision(), lease.leaseOwner(),
                lease.leaseEpoch(), current.leaseExpiresAt().toString()) == 1;
    }

    @Override
    public Optional<State> find(
            CapabilitySnapshot.Scope scope, String proposalId, long proposalRevision) {
        return query(SELECT, Objects.requireNonNull(scope, "scope"),
                required(proposalId, "proposalId"), proposalRevision);
    }

    private Optional<State> locked(
            CapabilitySnapshot.Scope scope, String proposalId, long proposalRevision) {
        return query(SELECT_FOR_UPDATE, scope, proposalId, proposalRevision);
    }

    private Optional<State> query(
            String sql, CapabilitySnapshot.Scope scope, String proposalId, long revision) {
        List<State> values = jdbc.query(sql, (rs, row) -> state(scope, rs),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), proposalId, revision);
        return values.stream().findFirst();
    }

    private State state(CapabilitySnapshot.Scope scope, ResultSet rs) throws SQLException {
        Registration registration = new Registration(scope, rs.getString("simulation_id"),
                rs.getString("proposal_id"), rs.getLong("proposal_revision"),
                rs.getString("request_fingerprint"));
        String json = rs.getString("result_json");
        StoredCapabilityProposalSimulation result = json == null || json.isBlank()
                ? null : deserialize(json);
        return new State(registration, Status.valueOf(rs.getString("status")),
                rs.getString("lease_owner"), rs.getLong("lease_epoch"),
                Instant.parse(rs.getString("lease_expires_at")), result,
                rs.getString("last_failure_code"), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private int insert(
            Registration registration, String owner, Instant now, Instant expiresAt) {
        CapabilitySnapshot.Scope scope = registration.scope();
        return jdbc.update(INSERT, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), registration.proposalId(),
                registration.proposalRevision(), registration.simulationId(),
                registration.requestFingerprint(), owner, expiresAt.toString(), now.toString(),
                now.toString());
    }

    private static Claim acquired(State state) {
        Registration registration = state.registration();
        Lease lease = new Lease(registration.scope(), registration.proposalId(),
                registration.proposalRevision(), registration.simulationId(), state.leaseOwner(),
                state.leaseEpoch());
        return new Claim(Outcome.ACQUIRED, state, lease, 0);
    }

    private static boolean owns(State state, Lease lease) {
        return state != null && state.status() == Status.ACTIVE
                && state.registration().simulationId().equals(lease.simulationId())
                && state.leaseOwner().equals(lease.leaseOwner())
                && state.leaseEpoch() == lease.leaseEpoch();
    }

    private static void requireSame(Registration stored, Registration requested) {
        if (!stored.equals(requested)) {
            throw new IllegalArgumentException(
                    "Proposal revision or simulation id is bound to different command material");
        }
    }

    private static void validate(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(registration.scope(), "scope");
        required(registration.simulationId(), "simulationId");
        required(registration.proposalId(), "proposalId");
        if (registration.proposalRevision() < 1
                || !registration.requestFingerprint().matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Proposal simulation registration is invalid");
        }
    }

    private String serialize(StoredCapabilityProposalSimulation value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Proposal simulation result serialization failed", failure);
        }
    }

    private StoredCapabilityProposalSimulation deserialize(String value) {
        try {
            return mapper.readValue(value, StoredCapabilityProposalSimulation.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored Proposal simulation is unreadable", failure);
        }
    }

    private Instant databaseNow() {
        DataSource source = Objects.requireNonNull(
                jdbc.getDataSource(), "Proposal simulation datasource is unavailable");
        while (source instanceof DelegatingDataSource delegating
                && delegating.getTargetDataSource() != null
                && delegating.getTargetDataSource() != source) {
            source = delegating.getTargetDataSource();
        }
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT CURRENT_TIMESTAMP")) {
            if (!result.next()) {
                throw new IllegalStateException("Proposal simulation database clock returned no row");
            }
            Timestamp timestamp = result.getTimestamp(1);
            if (timestamp == null) {
                throw new IllegalStateException("Proposal simulation database clock returned null");
            }
            return timestamp.toInstant();
        } catch (SQLException failure) {
            throw new IllegalStateException("Proposal simulation database clock is unavailable", failure);
        }
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String bounded(String value, int maximum) {
        String exact = value == null ? "" : value.trim();
        return exact.length() <= maximum ? exact : exact.substring(0, maximum);
    }
}
