package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle.OracleLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL/H2 Business Oracle store with CAS, retained history and atomic outbox. */
public final class DatabaseBusinessOracleRepository implements BusinessOracleRepository {

    private static final String HEAD_TABLE = "rg_business_oracle_heads";
    private static final String REVISION_TABLE = "rg_business_oracle_revisions";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DatabaseBusinessOracleRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Clock.systemUTC());
    }

    public DatabaseBusinessOracleRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StoredBusinessOracle> findHead(EnterpriseScope scope, String oracleId) {
        return queryOne(HEAD_TABLE, exactScope(scope), exactId(oracleId), 0);
    }

    @Override
    public Optional<StoredBusinessOracle> findRevision(
            EnterpriseScope scope,
            String oracleId,
            long revision
    ) {
        if (revision < 1) return Optional.empty();
        return queryOne(REVISION_TABLE, exactScope(scope), exactId(oracleId), revision);
    }

    @Override
    public List<StoredBusinessOracle> revisions(EnterpriseScope scope, String oracleId) {
        EnterpriseScope exactScope = exactScope(scope);
        String exactId = exactId(oracleId);
        return jdbc.query("""
                        SELECT * FROM rg_business_oracle_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND oracle_id = ?
                        ORDER BY revision DESC
                        """,
                (result, row) -> readAndVerify(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional
    public Optional<StoredBusinessOracle> saveIfRevision(
            long expectedRevision,
            BusinessOracle candidate,
            PrincipalRef actor
    ) {
        if (candidate == null || actor == null || expectedRevision < 0
                || candidate.revision() != expectedRevision) {
            throw new IllegalArgumentException(
                    "Oracle, actor, and matching non-negative expected revision are required");
        }
        EnterpriseScope scope = exactScope(candidate.scope());
        String id = exactId(candidate.oracleId());
        StoredBusinessOracle current = findHead(scope, id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.oracle().revision() != expectedRevision)) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        AuditMetadata metadata = current == null
                ? new AuditMetadata(now, now, actor, actor)
                : new AuditMetadata(
                        current.oracle().metadata().createdAt(), now,
                        current.oracle().metadata().createdBy(), actor);
        BusinessOracle persisted = candidate.persistedAs(expectedRevision + 1, metadata);
        StoredBusinessOracle stored = StoredBusinessOracle.verified(mapper, persisted);
        String json = serialize(stored);

        if (current == null) {
            try {
                insertHead(stored, json);
            } catch (DuplicateKeyException concurrentCreate) {
                return Optional.empty();
            }
        } else if (updateHead(stored, json, expectedRevision) == 0) {
            return Optional.empty();
        }
        insertRevision(stored, json);
        insertOutbox(stored);
        return Optional.of(stored);
    }

    private Optional<StoredBusinessOracle> queryOne(
            String table,
            EnterpriseScope scope,
            String oracleId,
            long revision
    ) {
        String revisionClause = revision > 0 ? " AND revision = ?" : "";
        Object[] args = revision > 0
                ? append(scopeArgs(scope, oracleId), revision)
                : scopeArgs(scope, oracleId);
        return jdbc.query("""
                        SELECT * FROM %s
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND oracle_id = ?%s
                        """.formatted(table, revisionClause),
                (result, row) -> readAndVerify(result, scope, oracleId), args)
                .stream().flatMap(Optional::stream).findFirst();
    }

    private Optional<StoredBusinessOracle> readAndVerify(
            ResultSet result,
            EnterpriseScope scope,
            String oracleId
    ) throws SQLException {
        try {
            StoredBusinessOracle stored = mapper.readValue(
                    result.getString("canonical_json"), StoredBusinessOracle.class);
            BusinessOracle oracle = stored.oracle();
            String fingerprint = CorrectnessProtocolFingerprint.fingerprint(mapper, oracle);
            String basisFingerprint = CorrectnessProtocolFingerprint.derivedFingerprint(
                    mapper, oracle.basisRefs());
            boolean valid = oracle.scope().equals(scope)
                    && oracle.oracleId().equals(oracleId)
                    && oracle.revision() == result.getLong("revision")
                    && stored.oracleFingerprint().equals(result.getString("fingerprint"))
                    && stored.oracleFingerprint().equals(fingerprint)
                    && basisFingerprint.equals(result.getString("basis_fingerprint"))
                    && oracle.target().kind().name().equals(result.getString("target_kind"))
                    && oracle.target().id().equals(result.getString("target_id"))
                    && oracle.target().revision() == result.getLong("target_revision")
                    && oracle.target().fingerprint().equals(
                            result.getString("target_fingerprint"))
                    && oracle.lifecycle().name().equals(result.getString("lifecycle"))
                    && oracle.owner().id().equals(result.getString("owner_id"));
            if (!valid) {
                throw new IllegalStateException("Stored Business Oracle integrity check failed");
            }
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Business Oracle revision", failure);
        }
    }

    private void insertHead(StoredBusinessOracle stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_business_oracle_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            oracle_id, revision, fingerprint, basis_fingerprint, target_kind,
                            target_id, target_revision, target_fingerprint, lifecycle, owner_id,
                            canonical_json, created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private int updateHead(
            StoredBusinessOracle stored,
            String json,
            long expectedRevision
    ) {
        BusinessOracle value = stored.oracle();
        return jdbc.update("""
                        UPDATE rg_business_oracle_heads
                        SET revision = ?, fingerprint = ?, basis_fingerprint = ?,
                            target_kind = ?, target_id = ?, target_revision = ?,
                            target_fingerprint = ?, lifecycle = ?, owner_id = ?,
                            canonical_json = ?, updated_at = ?, updated_by = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND oracle_id = ?
                          AND revision = ?
                        """,
                value.revision(), stored.oracleFingerprint(),
                CorrectnessProtocolFingerprint.derivedFingerprint(mapper, value.basisRefs()),
                value.target().kind().name(), value.target().id(), value.target().revision(),
                value.target().fingerprint(), value.lifecycle().name(), value.owner().id(),
                json, value.metadata().updatedAt(), value.metadata().updatedBy().id(),
                value.scope().tenantId(), value.scope().organizationId(),
                value.scope().projectId(), value.scope().environment(), value.scope().region(),
                value.oracleId(), expectedRevision);
    }

    private void insertRevision(StoredBusinessOracle stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_business_oracle_revisions (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            oracle_id, revision, fingerprint, basis_fingerprint, target_kind,
                            target_id, target_revision, target_fingerprint, lifecycle, owner_id,
                            canonical_json, created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private void insertOutbox(StoredBusinessOracle stored) {
        BusinessOracle value = stored.oracle();
        ExactAssetRef oracleRef = new ExactAssetRef(
                "ORACLE", value.oracleId(), value.revision(), stored.oracleFingerprint());
        String eventId = UUID.randomUUID().toString();
        String type;
        Object event;
        if (value.lifecycle() == OracleLifecycle.APPROVED) {
            type = BusinessOracleApproved.SCHEMA_VERSION;
            event = new BusinessOracleApproved(
                    "", eventId, value.scope(), oracleRef, value.target(), value.owner().id(),
                    value.basisRefs().size(), value.approval().reviewer().id(),
                    value.metadata().updatedAt());
        } else {
            type = BusinessOracleChanged.SCHEMA_VERSION;
            event = new BusinessOracleChanged(
                    "", eventId, value.scope(), oracleRef, value.target(),
                    value.lifecycle().name(), value.owner().id(), value.basisRefs().size(),
                    value.assertionSetRefs().size(), value.metadata().updatedBy().id(),
                    value.metadata().updatedAt());
        }
        jdbc.update("""
                        INSERT INTO rg_correctness_outbox (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            event_id, aggregate_kind, aggregate_id, aggregate_revision,
                            event_type, event_json, occurred_at, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), eventId, "ORACLE",
                value.oracleId(), value.revision(), type, serializeEvent(event),
                value.metadata().updatedAt());
    }

    private Object[] rowArgs(StoredBusinessOracle stored, String json) {
        BusinessOracle value = stored.oracle();
        return new Object[]{
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), value.oracleId(),
                value.revision(), stored.oracleFingerprint(),
                CorrectnessProtocolFingerprint.derivedFingerprint(mapper, value.basisRefs()),
                value.target().kind().name(), value.target().id(), value.target().revision(),
                value.target().fingerprint(), value.lifecycle().name(), value.owner().id(), json,
                value.metadata().createdAt(), value.metadata().updatedAt(),
                value.metadata().updatedBy().id()
        };
    }

    private String serialize(StoredBusinessOracle stored) {
        try {
            return mapper.writeValueAsString(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Business Oracle revision", failure);
        }
    }

    private String serializeEvent(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Business Oracle event", failure);
        }
    }

    private static EnterpriseScope exactScope(EnterpriseScope scope) {
        return Objects.requireNonNull(scope, "scope");
    }

    private static String exactId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("oracleId is required");
        return normalized;
    }

    private static Object[] scopeArgs(EnterpriseScope scope, String id) {
        return new Object[]{scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), id};
    }

    private static Object[] append(Object[] source, Object value) {
        Object[] result = Arrays.copyOf(source, source.length + 1);
        result[source.length] = value;
        return result;
    }
}
