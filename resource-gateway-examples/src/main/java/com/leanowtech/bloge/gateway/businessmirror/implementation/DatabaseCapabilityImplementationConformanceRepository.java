package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
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

/** Database-clock-coordinated H2/PostgreSQL implementation-conformance repository. */
public class DatabaseCapabilityImplementationConformanceRepository
        implements CapabilityImplementationConformanceRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS rg_bm_implementation_conformance (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                binding_id VARCHAR(512) NOT NULL,
                binding_revision BIGINT NOT NULL,
                conformance_id VARCHAR(512) NOT NULL,
                proposal_id VARCHAR(512) NOT NULL,
                proposal_revision BIGINT NOT NULL,
                binding_fingerprint VARCHAR(71) NOT NULL,
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
                             binding_id, binding_revision),
                UNIQUE (tenant_id, organization_id, project_id, environment_id, region,
                        conformance_id)
            )
            """;
    private static final String SELECT = """
            SELECT binding_id, binding_revision, conformance_id, proposal_id, proposal_revision,
                   binding_fingerprint, request_fingerprint, status, lease_owner, lease_epoch,
                   lease_expires_at, result_json, last_failure_code, created_at, updated_at
            FROM rg_bm_implementation_conformance
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND binding_id = ? AND binding_revision = ?
            """;
    private static final String SELECT_FOR_UPDATE = SELECT + " FOR UPDATE";
    private static final String INSERT = """
            INSERT INTO rg_bm_implementation_conformance (
                tenant_id, organization_id, project_id, environment_id, region,
                binding_id, binding_revision, conformance_id, proposal_id, proposal_revision,
                binding_fingerprint, request_fingerprint, status, lease_owner, lease_epoch,
                lease_expires_at, result_json, last_failure_code, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 1, ?, NULL, '', ?, ?)
            ON CONFLICT DO NOTHING
            """;
    private static final String TAKE_OVER = """
            UPDATE rg_bm_implementation_conformance
            SET lease_owner = ?, lease_epoch = ?, lease_expires_at = ?,
                last_failure_code = '', updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND binding_id = ? AND binding_revision = ?
              AND status = 'ACTIVE' AND lease_epoch = ?
            """;
    private static final String RENEW = """
            UPDATE rg_bm_implementation_conformance
            SET lease_expires_at = ?, updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND binding_id = ? AND binding_revision = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;
    private static final String COMPLETE = """
            UPDATE rg_bm_implementation_conformance
            SET status = 'COMPLETED', result_json = ?, last_failure_code = '', updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND binding_id = ? AND binding_revision = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;
    private static final String RELEASE = """
            UPDATE rg_bm_implementation_conformance
            SET lease_expires_at = ?, last_failure_code = ?, updated_at = ?
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND binding_id = ? AND binding_revision = ?
              AND status = 'ACTIVE' AND lease_owner = ? AND lease_epoch = ?
              AND lease_expires_at = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseCapabilityImplementationConformanceRepository(
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
    public Claim claim(Registration registration, String leaseOwner, Duration leaseDuration) {
        validate(registration);
        String owner = required(leaseOwner, "leaseOwner");
        Duration duration = positive(leaseDuration);
        MirrorArtifactRef binding = registration.implementationBindingRef();
        Optional<State> existing = locked(registration.scope(), binding.id(), binding.revision());
        Instant now = databaseNow();
        Instant expiresAt = now.plus(duration);
        if (existing.isEmpty()) {
            if (insert(registration, owner, now, expiresAt) == 1) {
                return acquired(locked(registration.scope(), binding.id(), binding.revision())
                        .orElseThrow());
            }
            existing = locked(registration.scope(), binding.id(), binding.revision());
            if (existing.isEmpty()) {
                throw new IllegalStateException(
                        "Implementation conformance disappeared after insert arbitration");
            }
            now = databaseNow();
            expiresAt = now.plus(duration);
        }
        State state = existing.orElseThrow();
        if (!state.registration().equals(registration)) {
            throw new IllegalArgumentException(
                    "Implementation binding or conformance id identifies different material");
        }
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
                scope.region(), binding.id(), binding.revision(), state.leaseEpoch());
        if (updated != 1) {
            throw new IllegalStateException("Implementation conformance lease changed while locked");
        }
        return acquired(locked(scope, binding.id(), binding.revision()).orElseThrow());
    }

    @Override
    @Transactional
    public boolean renew(Lease lease, Duration leaseDuration) {
        Objects.requireNonNull(lease, "lease");
        Duration duration = positive(leaseDuration);
        State current = locked(lease.scope(), lease.bindingId(), lease.bindingRevision())
                .orElse(null);
        Instant now = databaseNow();
        if (!owns(current, lease) || !now.isBefore(current.leaseExpiresAt())) {
            return false;
        }
        Instant expiresAt = now.plus(duration);
        CapabilitySnapshot.Scope scope = lease.scope();
        return jdbc.update(RENEW, expiresAt.toString(), now.toString(), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(), scope.region(),
                lease.bindingId(), lease.bindingRevision(), lease.leaseOwner(), lease.leaseEpoch(),
                current.leaseExpiresAt().toString()) == 1;
    }

    @Override
    @Transactional
    public boolean complete(Lease lease, StoredCapabilityImplementationConformance result) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(result, "result");
        State current = locked(lease.scope(), lease.bindingId(), lease.bindingRevision())
                .orElse(null);
        Instant now = databaseNow();
        if (!owns(current, lease) || !now.isBefore(current.leaseExpiresAt())) {
            return false;
        }
        CapabilitySnapshot.Scope scope = lease.scope();
        return jdbc.update(COMPLETE, serialize(result), now.toString(), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(), scope.region(),
                lease.bindingId(), lease.bindingRevision(), lease.leaseOwner(), lease.leaseEpoch(),
                current.leaseExpiresAt().toString()) == 1;
    }

    @Override
    @Transactional
    public boolean release(Lease lease, String failureCode) {
        Objects.requireNonNull(lease, "lease");
        State current = locked(lease.scope(), lease.bindingId(), lease.bindingRevision())
                .orElse(null);
        if (!owns(current, lease)) {
            return false;
        }
        Instant now = databaseNow();
        CapabilitySnapshot.Scope scope = lease.scope();
        return jdbc.update(RELEASE, now.toString(), bounded(failureCode, 256), now.toString(),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), lease.bindingId(), lease.bindingRevision(), lease.leaseOwner(),
                lease.leaseEpoch(), current.leaseExpiresAt().toString()) == 1;
    }

    @Override
    public Optional<State> find(
            CapabilitySnapshot.Scope scope, String bindingId, long bindingRevision) {
        return query(SELECT, Objects.requireNonNull(scope, "scope"),
                required(bindingId, "bindingId"), bindingRevision);
    }

    private Optional<State> locked(
            CapabilitySnapshot.Scope scope, String bindingId, long bindingRevision) {
        return query(SELECT_FOR_UPDATE, scope, bindingId, bindingRevision);
    }

    private Optional<State> query(
            String sql, CapabilitySnapshot.Scope scope, String bindingId, long bindingRevision) {
        List<State> values = jdbc.query(sql, (rs, row) -> state(scope, rs),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), bindingId, bindingRevision);
        return values.stream().findFirst();
    }

    private State state(CapabilitySnapshot.Scope scope, ResultSet rs) throws SQLException {
        MirrorArtifactRef bindingRef = new MirrorArtifactRef(
                "PROPOSAL_IMPLEMENTATION_BINDING", rs.getString("binding_id"),
                rs.getLong("binding_revision"), rs.getString("binding_fingerprint"));
        Registration registration = new Registration(scope, rs.getString("conformance_id"),
                rs.getString("proposal_id"), rs.getLong("proposal_revision"), bindingRef,
                rs.getString("request_fingerprint"));
        String json = rs.getString("result_json");
        StoredCapabilityImplementationConformance result = json == null || json.isBlank()
                ? null : deserialize(json);
        return new State(registration, Status.valueOf(rs.getString("status")),
                rs.getString("lease_owner"), rs.getLong("lease_epoch"),
                Instant.parse(rs.getString("lease_expires_at")), result,
                rs.getString("last_failure_code"), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private int insert(Registration registration, String owner, Instant now, Instant expiresAt) {
        CapabilitySnapshot.Scope scope = registration.scope();
        MirrorArtifactRef binding = registration.implementationBindingRef();
        return jdbc.update(INSERT, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), binding.id(), binding.revision(),
                registration.conformanceId(), registration.proposalId(),
                registration.proposalRevision(), binding.fingerprint(),
                registration.requestFingerprint(), owner, expiresAt.toString(), now.toString(),
                now.toString());
    }

    private static Claim acquired(State state) {
        Registration registration = state.registration();
        MirrorArtifactRef binding = registration.implementationBindingRef();
        Lease lease = new Lease(registration.scope(), registration.conformanceId(), binding.id(),
                binding.revision(), state.leaseOwner(), state.leaseEpoch());
        return new Claim(Outcome.ACQUIRED, state, lease, 0);
    }

    private static boolean owns(State state, Lease lease) {
        return state != null && state.status() == Status.ACTIVE
                && state.registration().conformanceId().equals(lease.conformanceId())
                && state.leaseOwner().equals(lease.leaseOwner())
                && state.leaseEpoch() == lease.leaseEpoch();
    }

    private static void validate(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(registration.scope(), "scope");
        required(registration.conformanceId(), "conformanceId");
        required(registration.proposalId(), "proposalId");
        MirrorArtifactRef binding = Objects.requireNonNull(
                registration.implementationBindingRef(), "implementationBindingRef");
        if (!"PROPOSAL_IMPLEMENTATION_BINDING".equals(binding.kind())
                || registration.proposalRevision() < 1
                || !registration.requestFingerprint().matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("implementation conformance registration is invalid");
        }
    }

    private String serialize(StoredCapabilityImplementationConformance value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("implementation conformance serialization failed", failure);
        }
    }

    private StoredCapabilityImplementationConformance deserialize(String value) {
        try {
            return mapper.readValue(value, StoredCapabilityImplementationConformance.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("stored implementation conformance is unreadable", failure);
        }
    }

    private Instant databaseNow() {
        DataSource source = Objects.requireNonNull(
                jdbc.getDataSource(), "implementation conformance datasource is unavailable");
        while (source instanceof DelegatingDataSource delegating
                && delegating.getTargetDataSource() != null
                && delegating.getTargetDataSource() != source) {
            source = delegating.getTargetDataSource();
        }
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT CURRENT_TIMESTAMP")) {
            if (!result.next() || result.getTimestamp(1) == null) {
                throw new IllegalStateException("implementation conformance clock returned no value");
            }
            Timestamp timestamp = result.getTimestamp(1);
            return timestamp.toInstant();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "implementation conformance database clock is unavailable", failure);
        }
    }

    private static Duration positive(Duration value) {
        Duration exact = Objects.requireNonNull(value, "leaseDuration");
        if (exact.isZero() || exact.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return exact;
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
