package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * H2-backed append-only capability snapshot repository with scope and revision fencing.
 *
 * <p>The compound primary key includes tenant, organization, project, environment, region,
 * capability id, and revision. Every write verifies the attached canonical fingerprint and every
 * read verifies it again after deserialization. Corrupt or drifted rows fail closed.</p>
 */
public class DatabaseCapabilitySnapshotRepository implements CapabilitySnapshotRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS capability_snapshots (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                capability_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                fingerprint VARCHAR(71) NOT NULL,
                lifecycle VARCHAR(32) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                snapshot_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    capability_id, revision
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO capability_snapshots (
                tenant_id, organization_id, project_id, environment_id, region,
                capability_id, revision, fingerprint, lifecycle, created_at, snapshot_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT snapshot_json FROM capability_snapshots
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND capability_id = ? AND revision = ?
            """;
    private static final String SELECT_LATEST = """
            SELECT snapshot_json FROM capability_snapshots
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND capability_id = ?
            ORDER BY revision DESC
            FETCH FIRST 1 ROW ONLY
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /**
     * Creates a durable repository.
     *
     * @param jdbc application JDBC boundary
     * @param mapper capability protocol mapper
     */
    public DatabaseCapabilitySnapshotRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Creates the append-only storage table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public CapabilitySnapshot create(CapabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        CapabilitySnapshotIntegrity.verify(mapper, snapshot);
        CapabilitySnapshotLifecycle.validateLifecycleState(snapshot);
        Optional<CapabilitySnapshot> exact = find(snapshot.scope(), snapshot.capabilityId(), snapshot.revision());
        if (exact.isPresent()) {
            return sameOrConflict(exact.get(), snapshot);
        }
        Optional<CapabilitySnapshot> latest = findLatest(snapshot.scope(), snapshot.capabilityId());
        if (latest.isEmpty()) {
            if (snapshot.revision() != 1 || snapshot.lifecycle() != CapabilitySnapshot.Lifecycle.DRAFT) {
                throw new IllegalArgumentException(
                        "first capability snapshot revision must be revision 1 in DRAFT lifecycle");
            }
        } else {
            CapabilitySnapshotLifecycle.validateAppend(latest.get(), snapshot);
        }
        try {
            CapabilitySnapshot.Scope scope = snapshot.scope();
            jdbc.update(INSERT, scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), snapshot.capabilityId(), snapshot.revision(),
                    snapshot.fingerprint(), snapshot.lifecycle().name(), snapshot.createdAt().toString(),
                    mapper.writeValueAsString(snapshot));
            return snapshot;
        } catch (DuplicateKeyException duplicate) {
            CapabilitySnapshot existing = find(snapshot.scope(), snapshot.capabilityId(), snapshot.revision())
                    .orElseThrow(() -> duplicate);
            return sameOrConflict(existing, snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Capability snapshot cannot be serialized", exception);
        }
    }

    @Override
    public Optional<CapabilitySnapshot> find(CapabilitySnapshot.Scope scope,
                                             String capabilityId,
                                             long revision) {
        Objects.requireNonNull(scope, "scope");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return query(SELECT_EXACT, scope, capabilityId, revision);
    }

    @Override
    public Optional<CapabilitySnapshot> findLatest(CapabilitySnapshot.Scope scope, String capabilityId) {
        Objects.requireNonNull(scope, "scope");
        return query(SELECT_LATEST, scope, capabilityId);
    }

    private Optional<CapabilitySnapshot> query(String sql,
                                               CapabilitySnapshot.Scope scope,
                                               String capabilityId,
                                               Object... trailing) {
        String id = required(capabilityId, "capabilityId");
        Object[] arguments = new Object[6 + trailing.length];
        arguments[0] = scope.tenantId();
        arguments[1] = scope.organizationId();
        arguments[2] = scope.projectId();
        arguments[3] = scope.environmentId();
        arguments[4] = scope.region();
        arguments[5] = id;
        System.arraycopy(trailing, 0, arguments, 6, trailing.length);
        List<CapabilitySnapshot> values = jdbc.query(sql, (rs, rowNumber) -> deserialize(
                rs.getString("snapshot_json")), arguments);
        return values.stream().findFirst();
    }

    private CapabilitySnapshot deserialize(String value) {
        try {
            CapabilitySnapshot snapshot = mapper.readValue(value, CapabilitySnapshot.class);
            CapabilitySnapshotIntegrity.verify(mapper, snapshot);
            CapabilitySnapshotLifecycle.validateLifecycleState(snapshot);
            return snapshot;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Stored capability snapshot failed integrity validation", exception);
        }
    }

    private static CapabilitySnapshot sameOrConflict(CapabilitySnapshot existing,
                                                     CapabilitySnapshot requested) {
        if (existing.fingerprint().equals(requested.fingerprint())) {
            return existing;
        }
        throw new IllegalArgumentException(
                "Capability snapshot revision already exists with different content");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
