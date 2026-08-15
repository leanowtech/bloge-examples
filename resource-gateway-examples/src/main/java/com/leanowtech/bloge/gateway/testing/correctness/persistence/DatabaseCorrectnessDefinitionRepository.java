package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL/H2 JDBC implementation with database-enforced optimistic concurrency. */
public class DatabaseCorrectnessDefinitionRepository
        implements CorrectnessDefinitionRepository {

    private static final String HEAD_TABLE = "rg_correctness_definition_heads";
    private static final String REVISION_TABLE = "rg_correctness_definition_revisions";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DatabaseCorrectnessDefinitionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Clock.systemUTC());
    }

    public DatabaseCorrectnessDefinitionRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StoredCorrectnessDefinition> findHead(
            EnterpriseScope scope,
            String definitionId
    ) {
        return queryOne(HEAD_TABLE, exactScope(scope), exactId(definitionId), 0);
    }

    @Override
    public Optional<StoredCorrectnessDefinition> findRevision(
            EnterpriseScope scope,
            String definitionId,
            long revision
    ) {
        if (revision < 1) return Optional.empty();
        return queryOne(REVISION_TABLE, exactScope(scope), exactId(definitionId), revision);
    }

    @Override
    public List<StoredCorrectnessDefinition> revisions(
            EnterpriseScope scope,
            String definitionId
    ) {
        EnterpriseScope exactScope = exactScope(scope);
        String exactId = exactId(definitionId);
        return jdbc.query("""
                        SELECT * FROM rg_correctness_definition_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND definition_id = ?
                        ORDER BY revision DESC
                        """,
                (result, row) -> readAndVerify(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional
    public Optional<StoredCorrectnessDefinition> saveIfRevision(
            long expectedRevision,
            CorrectnessDefinition candidate,
            PrincipalRef actor
    ) {
        if (candidate == null || actor == null || expectedRevision < 0
                || candidate.revision() != expectedRevision) {
            throw new IllegalArgumentException(
                    "Definition, actor, and matching non-negative expected revision are required");
        }
        EnterpriseScope scope = exactScope(candidate.scope());
        String id = exactId(candidate.definitionId());
        StoredCorrectnessDefinition current = findHead(scope, id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.definition().revision() != expectedRevision)) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        AuditMetadata metadata = current == null
                ? new AuditMetadata(now, now, actor, actor)
                : new AuditMetadata(
                        current.definition().metadata().createdAt(), now,
                        current.definition().metadata().createdBy(), actor);
        CorrectnessDefinition persisted = candidate.persistedAs(expectedRevision + 1, metadata);
        StoredCorrectnessDefinition stored = StoredCorrectnessDefinition.verified(mapper, persisted);
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

    private Optional<StoredCorrectnessDefinition> queryOne(
            String table,
            EnterpriseScope scope,
            String definitionId,
            long revision
    ) {
        String revisionClause = revision > 0 ? " AND revision = ?" : "";
        Object[] args = revision > 0
                ? append(scopeArgs(scope, definitionId), revision)
                : scopeArgs(scope, definitionId);
        return jdbc.query("""
                        SELECT * FROM %s
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND definition_id = ?%s
                        """.formatted(table, revisionClause),
                (result, row) -> readAndVerify(result, scope, definitionId), args)
                .stream().flatMap(Optional::stream).findFirst();
    }

    private Optional<StoredCorrectnessDefinition> readAndVerify(
            ResultSet result,
            EnterpriseScope scope,
            String definitionId
    ) throws SQLException {
        try {
            StoredCorrectnessDefinition stored = mapper.readValue(
                    result.getString("canonical_json"), StoredCorrectnessDefinition.class);
            CorrectnessDefinition definition = stored.definition();
            String fingerprint = CorrectnessProtocolFingerprint.fingerprint(mapper, definition);
            boolean valid = definition.scope().equals(scope)
                    && definition.definitionId().equals(definitionId)
                    && definition.revision() == result.getLong("revision")
                    && stored.definitionFingerprint().equals(result.getString("fingerprint"))
                    && stored.definitionFingerprint().equals(fingerprint)
                    && definition.target().kind().name().equals(result.getString("target_kind"))
                    && definition.target().id().equals(result.getString("target_id"))
                    && definition.target().revision() == result.getLong("target_revision")
                    && definition.target().fingerprint().equals(
                            result.getString("target_fingerprint"))
                    && definition.lifecycle().name().equals(result.getString("lifecycle"))
                    && definition.owner().id().equals(result.getString("owner_id"));
            if (!valid) {
                throw new IllegalStateException("Stored Correctness Definition integrity check failed");
            }
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Correctness Definition revision", failure);
        }
    }

    private void insertHead(StoredCorrectnessDefinition stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_correctness_definition_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            definition_id, revision, fingerprint, target_kind, target_id,
                            target_revision, target_fingerprint, lifecycle, owner_id,
                            canonical_json, created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private int updateHead(
            StoredCorrectnessDefinition stored,
            String json,
            long expectedRevision
    ) {
        CorrectnessDefinition value = stored.definition();
        return jdbc.update("""
                        UPDATE rg_correctness_definition_heads
                        SET revision = ?, fingerprint = ?, target_kind = ?, target_id = ?,
                            target_revision = ?, target_fingerprint = ?, lifecycle = ?, owner_id = ?,
                            canonical_json = ?, updated_at = ?, updated_by = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND definition_id = ?
                          AND revision = ?
                        """,
                value.revision(), stored.definitionFingerprint(), value.target().kind().name(),
                value.target().id(), value.target().revision(), value.target().fingerprint(),
                value.lifecycle().name(), value.owner().id(), json,
                value.metadata().updatedAt(), value.metadata().updatedBy().id(),
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), value.definitionId(),
                expectedRevision);
    }

    private void insertRevision(StoredCorrectnessDefinition stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_correctness_definition_revisions (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            definition_id, revision, fingerprint, target_kind, target_id,
                            target_revision, target_fingerprint, lifecycle, owner_id,
                            canonical_json, created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rowArgs(stored, json));
    }

    private void insertOutbox(StoredCorrectnessDefinition stored) {
        CorrectnessDefinition value = stored.definition();
        CorrectnessDefinitionChanged event = new CorrectnessDefinitionChanged(
                "", UUID.randomUUID().toString(), value.scope(),
                new ExactAssetRef(
                        "DEFINITION", value.definitionId(), value.revision(),
                        stored.definitionFingerprint()),
                value.target(), value.lifecycle().name(), value.metadata().updatedBy().id(),
                value.metadata().updatedAt());
        jdbc.update("""
                        INSERT INTO rg_correctness_outbox (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            event_id, aggregate_kind, aggregate_id, aggregate_revision,
                            event_type, event_json, occurred_at, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), event.eventId(), "DEFINITION",
                value.definitionId(), value.revision(), CorrectnessDefinitionChanged.SCHEMA_VERSION,
                serializeEvent(event), event.occurredAt());
    }

    private Object[] rowArgs(StoredCorrectnessDefinition stored, String json) {
        CorrectnessDefinition value = stored.definition();
        return new Object[]{
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), value.definitionId(),
                value.revision(), stored.definitionFingerprint(), value.target().kind().name(),
                value.target().id(), value.target().revision(), value.target().fingerprint(),
                value.lifecycle().name(), value.owner().id(), json,
                value.metadata().createdAt(), value.metadata().updatedAt(),
                value.metadata().updatedBy().id()
        };
    }

    private String serialize(StoredCorrectnessDefinition stored) {
        try {
            return mapper.writeValueAsString(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Correctness Definition revision", failure);
        }
    }

    private String serializeEvent(CorrectnessDefinitionChanged event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Definition changed event", failure);
        }
    }

    private static EnterpriseScope exactScope(EnterpriseScope scope) {
        return Objects.requireNonNull(scope, "scope");
    }

    private static String exactId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("definitionId is required");
        return normalized;
    }

    private static Object[] scopeArgs(EnterpriseScope scope, String id) {
        return new Object[]{scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), id};
    }

    private static Object[] append(Object[] source, Object value) {
        Object[] result = java.util.Arrays.copyOf(source, source.length + 1);
        result[source.length] = value;
        return result;
    }
}
