package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
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

/** PostgreSQL/H2 Assertion Set store with CAS, retained history and atomic outbox. */
public class DatabaseAssertionSetRepository implements AssertionSetRepository {

    private static final String HEAD_TABLE = "rg_assertion_set_heads";
    private static final String REVISION_TABLE = "rg_assertion_set_revisions";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DatabaseAssertionSetRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Clock.systemUTC());
    }

    public DatabaseAssertionSetRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StoredAssertionSet> findHead(
            EnterpriseScope scope,
            String assertionSetId
    ) {
        return queryOne(HEAD_TABLE, exactScope(scope), exactId(assertionSetId), 0);
    }

    @Override
    public Optional<StoredAssertionSet> findRevision(
            EnterpriseScope scope,
            String assertionSetId,
            long revision
    ) {
        if (revision < 1) return Optional.empty();
        return queryOne(REVISION_TABLE, exactScope(scope), exactId(assertionSetId), revision);
    }

    @Override
    public List<StoredAssertionSet> revisions(
            EnterpriseScope scope,
            String assertionSetId
    ) {
        EnterpriseScope exactScope = exactScope(scope);
        String exactId = exactId(assertionSetId);
        return jdbc.query("""
                        SELECT * FROM rg_assertion_set_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND assertion_set_id = ?
                        ORDER BY revision DESC
                        """,
                (result, row) -> readAndVerify(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).toList();
    }

    @Override
    public AssertionTargetSummary summarize(EnterpriseScope scope, ExactTargetRef target) {
        EnterpriseScope exactScope = exactScope(scope);
        ExactTargetRef exactTarget = Objects.requireNonNull(target, "target");
        return jdbc.queryForObject("""
                        SELECT COUNT(*) AS total,
                               SUM(CASE WHEN lifecycle = 'DRAFT' THEN 1 ELSE 0 END) AS draft,
                               SUM(CASE WHEN lifecycle = 'VALID' THEN 1 ELSE 0 END) AS valid,
                               SUM(CASE WHEN lifecycle = 'STALE' THEN 1 ELSE 0 END) AS stale,
                               SUM(CASE WHEN compatibility_supported = FALSE THEN 1 ELSE 0 END)
                                   AS unsupported
                        FROM rg_assertion_set_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND target_kind = ? AND target_id = ?
                          AND target_revision = ? AND target_fingerprint = ?
                        """,
                (result, row) -> new AssertionTargetSummary(
                        result.getInt("total"), result.getInt("draft"),
                        result.getInt("valid"), result.getInt("stale"),
                        result.getInt("unsupported")),
                exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                exactScope.environment(), exactScope.region(), exactTarget.kind().name(),
                exactTarget.id(), exactTarget.revision(), exactTarget.fingerprint());
    }

    @Override
    @Transactional
    public Optional<StoredAssertionSet> saveIfRevision(
            EnterpriseScope scope,
            long expectedRevision,
            AssertionSet candidate,
            PrincipalRef actor
    ) {
        if (scope == null || candidate == null || actor == null || expectedRevision < 0
                || candidate.revision() != expectedRevision) {
            throw new IllegalArgumentException(
                    "Scope, Assertion Set, actor, and matching expected revision are required");
        }
        EnterpriseScope exactScope = exactScope(scope);
        String id = exactId(candidate.assertionSetId());
        StoredAssertionSet current = findHead(exactScope, id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null
                        && current.assertionSet().revision() != expectedRevision)) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        AuditMetadata metadata = current == null
                ? new AuditMetadata(now, now, actor, actor)
                : new AuditMetadata(
                        current.assertionSet().metadata().createdAt(), now,
                        current.assertionSet().metadata().createdBy(), actor);
        AssertionSet persisted = candidate.persistedAs(expectedRevision + 1, metadata);
        StoredAssertionSet stored = StoredAssertionSet.verified(mapper, exactScope, persisted);
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

    private Optional<StoredAssertionSet> queryOne(
            String table,
            EnterpriseScope scope,
            String assertionSetId,
            long revision
    ) {
        String revisionClause = revision > 0 ? " AND revision = ?" : "";
        Object[] args = revision > 0
                ? append(scopeArgs(scope, assertionSetId), revision)
                : scopeArgs(scope, assertionSetId);
        return jdbc.query("""
                        SELECT * FROM %s
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND assertion_set_id = ?%s
                        """.formatted(table, revisionClause),
                (result, row) -> readAndVerify(result, scope, assertionSetId), args)
                .stream().flatMap(Optional::stream).findFirst();
    }

    private Optional<StoredAssertionSet> readAndVerify(
            ResultSet result,
            EnterpriseScope scope,
            String assertionSetId
    ) throws SQLException {
        try {
            StoredAssertionSet stored = mapper.readValue(
                    result.getString("canonical_json"), StoredAssertionSet.class);
            AssertionSet value = stored.assertionSet();
            String fingerprint = CorrectnessProtocolFingerprint.fingerprint(mapper, value);
            boolean valid = stored.scope().equals(scope)
                    && value.assertionSetId().equals(assertionSetId)
                    && value.revision() == result.getLong("revision")
                    && stored.assertionSetFingerprint().equals(result.getString("fingerprint"))
                    && stored.assertionSetFingerprint().equals(fingerprint)
                    && value.target().kind().name().equals(result.getString("target_kind"))
                    && value.target().id().equals(result.getString("target_id"))
                    && value.target().revision() == result.getLong("target_revision")
                    && value.target().fingerprint().equals(
                            result.getString("target_fingerprint"))
                    && value.oracleRef().id().equals(result.getString("oracle_id"))
                    && value.oracleRef().revision() == result.getLong("oracle_revision")
                    && value.oracleRef().fingerprint().equals(
                            result.getString("oracle_fingerprint"))
                    && value.lifecycle().name().equals(result.getString("lifecycle"))
                    && value.compatibility().supported()
                            == result.getBoolean("compatibility_supported");
            if (!valid) {
                throw new IllegalStateException("Stored Assertion Set integrity check failed");
            }
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Assertion Set revision", failure);
        }
    }

    private void insertHead(StoredAssertionSet stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_assertion_set_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            assertion_set_id, revision, fingerprint, target_kind, target_id,
                            target_revision, target_fingerprint, oracle_id, oracle_revision,
                            oracle_fingerprint, lifecycle, compatibility_supported, canonical_json,
                            created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private int updateHead(
            StoredAssertionSet stored,
            String json,
            long expectedRevision
    ) {
        AssertionSet value = stored.assertionSet();
        EnterpriseScope scope = stored.scope();
        return jdbc.update("""
                        UPDATE rg_assertion_set_heads
                        SET revision = ?, fingerprint = ?, target_kind = ?, target_id = ?,
                            target_revision = ?, target_fingerprint = ?, oracle_id = ?,
                            oracle_revision = ?, oracle_fingerprint = ?, lifecycle = ?,
                            compatibility_supported = ?, canonical_json = ?, updated_at = ?,
                            updated_by = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND assertion_set_id = ?
                          AND revision = ?
                        """,
                value.revision(), stored.assertionSetFingerprint(), value.target().kind().name(),
                value.target().id(), value.target().revision(), value.target().fingerprint(),
                value.oracleRef().id(), value.oracleRef().revision(),
                value.oracleRef().fingerprint(), value.lifecycle().name(),
                value.compatibility().supported(), json, value.metadata().updatedAt(),
                value.metadata().updatedBy().id(), scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environment(), scope.region(), value.assertionSetId(),
                expectedRevision);
    }

    private void insertRevision(StoredAssertionSet stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_assertion_set_revisions (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            assertion_set_id, revision, fingerprint, target_kind, target_id,
                            target_revision, target_fingerprint, oracle_id, oracle_revision,
                            oracle_fingerprint, lifecycle, compatibility_supported, canonical_json,
                            created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private void insertOutbox(StoredAssertionSet stored) {
        AssertionSet value = stored.assertionSet();
        EnterpriseScope scope = stored.scope();
        String eventId = UUID.randomUUID().toString();
        ExactAssetRef assertionSetRef = new ExactAssetRef(
                "ASSERTION_SET", value.assertionSetId(), value.revision(),
                stored.assertionSetFingerprint());
        AssertionSetChanged event = new AssertionSetChanged(
                "", eventId, scope, assertionSetRef, value.target(), value.oracleRef(),
                value.lifecycle().name(), value.assertions().size(),
                value.compatibility().supported(), value.compatibility().evaluatorVersion(),
                value.metadata().updatedBy().id(), value.metadata().updatedAt());
        jdbc.update("""
                        INSERT INTO rg_correctness_outbox (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            event_id, aggregate_kind, aggregate_id, aggregate_revision,
                            event_type, event_json, occurred_at, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                scope.region(), eventId, "ASSERTION_SET", value.assertionSetId(), value.revision(),
                AssertionSetChanged.SCHEMA_VERSION, serializeEvent(event),
                value.metadata().updatedAt());
    }

    private Object[] rowArgs(StoredAssertionSet stored, String json) {
        EnterpriseScope scope = stored.scope();
        AssertionSet value = stored.assertionSet();
        return new Object[]{
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                scope.region(), value.assertionSetId(), value.revision(),
                stored.assertionSetFingerprint(), value.target().kind().name(),
                value.target().id(), value.target().revision(), value.target().fingerprint(),
                value.oracleRef().id(), value.oracleRef().revision(),
                value.oracleRef().fingerprint(), value.lifecycle().name(),
                value.compatibility().supported(), json, value.metadata().createdAt(),
                value.metadata().updatedAt(), value.metadata().updatedBy().id()
        };
    }

    private String serialize(StoredAssertionSet stored) {
        try {
            return mapper.writeValueAsString(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Assertion Set revision", failure);
        }
    }

    private String serializeEvent(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Assertion Set event", failure);
        }
    }

    private static EnterpriseScope exactScope(EnterpriseScope scope) {
        return Objects.requireNonNull(scope, "scope");
    }

    private static String exactId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("assertionSetId is required");
        }
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
