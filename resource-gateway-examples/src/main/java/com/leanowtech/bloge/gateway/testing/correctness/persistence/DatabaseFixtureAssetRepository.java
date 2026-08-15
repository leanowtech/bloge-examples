package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL/H2 Fixture catalog with CAS, immutable history, usage index, and outbox. */
public class DatabaseFixtureAssetRepository implements FixtureAssetRepository {

    private static final String HEAD_TABLE = "rg_fixture_asset_heads";
    private static final String REVISION_TABLE = "rg_fixture_asset_revisions";
    private static final int MAX_EXACT_RESOLUTION = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DatabaseFixtureAssetRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Clock.systemUTC());
    }

    public DatabaseFixtureAssetRepository(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StoredFixtureAsset> findHead(EnterpriseScope scope, String fixtureAssetId) {
        return queryOne(HEAD_TABLE, exactScope(scope), exactId(fixtureAssetId), 0);
    }

    @Override
    public Optional<StoredFixtureAsset> findRevision(
            EnterpriseScope scope, String fixtureAssetId, long revision) {
        if (revision < 1) return Optional.empty();
        return queryOne(REVISION_TABLE, exactScope(scope), exactId(fixtureAssetId), revision);
    }

    @Override
    public List<StoredFixtureAsset> revisions(EnterpriseScope scope, String fixtureAssetId) {
        EnterpriseScope exactScope = exactScope(scope);
        String exactId = exactId(fixtureAssetId);
        return jdbc.query("""
                        SELECT * FROM rg_fixture_asset_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND fixture_asset_id = ?
                        ORDER BY revision DESC
                        """,
                (result, row) -> readAndVerify(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional
    public Optional<StoredFixtureAsset> saveIfRevision(
            long expectedRevision,
            FixtureAssetDescriptor candidate,
            PrincipalRef actor) {
        if (candidate == null || actor == null || expectedRevision < 0
                || candidate.revision() != expectedRevision) {
            throw new IllegalArgumentException(
                    "Fixture descriptor, actor, and matching expected revision are required");
        }
        EnterpriseScope scope = exactScope(candidate.scope());
        String id = exactId(candidate.fixtureAssetId());
        StoredFixtureAsset current = findHead(scope, id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.descriptor().revision() != expectedRevision)) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        AuditMetadata metadata = current == null
                ? new AuditMetadata(now, now, actor, actor)
                : new AuditMetadata(
                        current.descriptor().metadata().createdAt(), now,
                        current.descriptor().metadata().createdBy(), actor);
        FixtureAssetDescriptor persisted = candidate.persistedAs(expectedRevision + 1, metadata);
        StoredFixtureAsset stored = StoredFixtureAsset.verified(mapper, persisted);
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

    @Override
    public List<StoredFixtureAsset> resolveExact(
            EnterpriseScope scope, List<ExactAssetRef> fixtureAssetRefs) {
        EnterpriseScope exactScope = exactScope(scope);
        List<ExactAssetRef> refs = fixtureAssetRefs == null ? List.of()
                : fixtureAssetRefs.stream().distinct()
                        .sorted(Comparator.comparing(ExactAssetRef::id)
                                .thenComparingLong(ExactAssetRef::revision))
                        .toList();
        if (refs.size() > MAX_EXACT_RESOLUTION) {
            throw new IllegalArgumentException("Fixture exact-resolution limit exceeded");
        }
        List<StoredFixtureAsset> resolved = new ArrayList<>();
        for (ExactAssetRef ref : refs) {
            requireFixtureRef(ref);
            StoredFixtureAsset stored = findRevision(exactScope, ref.id(), ref.revision())
                    .orElseThrow(() -> new IllegalStateException(
                            "Exact Fixture descriptor revision was not found"));
            if (!stored.descriptorFingerprint().equals(ref.fingerprint())) {
                throw new IllegalStateException("Exact Fixture descriptor fingerprint drifted");
            }
            resolved.add(stored);
        }
        return List.copyOf(resolved);
    }

    @Override
    @Transactional
    public void replaceUsageForConsumer(
            EnterpriseScope scope,
            ExactAssetRef consumerRef,
            List<ExactAssetRef> fixtureAssetRefs) {
        EnterpriseScope exactScope = exactScope(scope);
        ExactAssetRef consumer = Objects.requireNonNull(consumerRef, "consumerRef");
        List<StoredFixtureAsset> fixtures = resolveExact(exactScope, fixtureAssetRefs);
        jdbc.update("""
                        DELETE FROM rg_fixture_usage_index
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND consumer_kind = ? AND consumer_id = ? AND consumer_revision = ?
                        """,
                exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                exactScope.environment(), exactScope.region(), consumer.kind(), consumer.id(),
                consumer.revision());
        for (StoredFixtureAsset fixture : fixtures) {
            ExactAssetRef ref = fixture.exactRef();
            jdbc.update("""
                            INSERT INTO rg_fixture_usage_index (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                fixture_asset_id, fixture_revision, fixture_fingerprint,
                                consumer_kind, consumer_id, consumer_revision, consumer_fingerprint
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                    exactScope.environment(), exactScope.region(), ref.id(), ref.revision(),
                    ref.fingerprint(), consumer.kind(), consumer.id(), consumer.revision(),
                    consumer.fingerprint());
        }
    }

    @Override
    public List<FixtureUsage> usages(
            EnterpriseScope scope, ExactAssetRef fixtureAssetRef, int limit) {
        EnterpriseScope exactScope = exactScope(scope);
        requireFixtureRef(fixtureAssetRef);
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Fixture usage limit must be between 1 and 1000");
        }
        resolveExact(exactScope, List.of(fixtureAssetRef));
        return jdbc.query("""
                        SELECT consumer_kind, consumer_id, consumer_revision, consumer_fingerprint
                        FROM rg_fixture_usage_index
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND fixture_asset_id = ? AND fixture_revision = ?
                          AND fixture_fingerprint = ?
                        ORDER BY consumer_kind, consumer_id, consumer_revision
                        LIMIT ?
                        """,
                (result, row) -> new FixtureUsage(
                        fixtureAssetRef,
                        new ExactAssetRef(
                                result.getString("consumer_kind"),
                                result.getString("consumer_id"),
                                result.getLong("consumer_revision"),
                                result.getString("consumer_fingerprint"))),
                exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                exactScope.environment(), exactScope.region(), fixtureAssetRef.id(),
                fixtureAssetRef.revision(), fixtureAssetRef.fingerprint(), limit);
    }

    private Optional<StoredFixtureAsset> queryOne(
            String table, EnterpriseScope scope, String fixtureAssetId, long revision) {
        String revisionClause = revision > 0 ? " AND revision = ?" : "";
        Object[] args = revision > 0
                ? append(scopeArgs(scope, fixtureAssetId), revision)
                : scopeArgs(scope, fixtureAssetId);
        return jdbc.query("""
                        SELECT * FROM %s
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND fixture_asset_id = ?%s
                        """.formatted(table, revisionClause),
                (result, row) -> readAndVerify(result, scope, fixtureAssetId), args)
                .stream().flatMap(Optional::stream).findFirst();
    }

    private Optional<StoredFixtureAsset> readAndVerify(
            ResultSet result, EnterpriseScope scope, String fixtureAssetId) throws SQLException {
        try {
            StoredFixtureAsset stored = mapper.readValue(
                    result.getString("canonical_json"), StoredFixtureAsset.class);
            FixtureAssetDescriptor value = stored.descriptor();
            String fingerprint = CorrectnessProtocolFingerprint.fingerprint(mapper, value);
            boolean valid = value.scope().equals(scope)
                    && value.fixtureAssetId().equals(fixtureAssetId)
                    && value.revision() == result.getLong("revision")
                    && stored.descriptorFingerprint().equals(result.getString("fingerprint"))
                    && stored.descriptorFingerprint().equals(fingerprint)
                    && value.schemaRef().id().equals(result.getString("schema_id"))
                    && value.schemaRef().revision() == result.getLong("schema_revision")
                    && value.schemaRef().fingerprint().equals(result.getString("schema_fingerprint"))
                    && value.variantKey().equals(result.getString("variant_key"))
                    && value.lifecycle().name().equals(result.getString("lifecycle"))
                    && value.classification().equals(result.getString("classification"))
                    && value.owner().id().equals(result.getString("owner_id"))
                    && value.materialRef().id().equals(result.getString("material_id"))
                    && value.materialRef().revision() == result.getLong("material_revision")
                    && value.materialRef().fingerprint().equals(
                            result.getString("material_fingerprint"));
            if (!valid) throw new IllegalStateException("Stored Fixture descriptor integrity check failed");
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Fixture descriptor revision", failure);
        }
    }

    private void insertHead(StoredFixtureAsset stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_fixture_asset_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            fixture_asset_id, revision, fingerprint, schema_id, schema_revision,
                            schema_fingerprint, variant_key, lifecycle, classification, owner_id,
                            material_id, material_revision, material_fingerprint,
                            canonical_json, created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private int updateHead(StoredFixtureAsset stored, String json, long expectedRevision) {
        FixtureAssetDescriptor value = stored.descriptor();
        return jdbc.update("""
                        UPDATE rg_fixture_asset_heads
                        SET revision = ?, fingerprint = ?, schema_id = ?, schema_revision = ?,
                            schema_fingerprint = ?, variant_key = ?, lifecycle = ?,
                            classification = ?, owner_id = ?, material_id = ?, material_revision = ?,
                            material_fingerprint = ?, canonical_json = ?, updated_at = ?, updated_by = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND fixture_asset_id = ?
                          AND revision = ?
                        """,
                value.revision(), stored.descriptorFingerprint(), value.schemaRef().id(),
                value.schemaRef().revision(), value.schemaRef().fingerprint(), value.variantKey(),
                value.lifecycle().name(), value.classification(), value.owner().id(),
                value.materialRef().id(), value.materialRef().revision(),
                value.materialRef().fingerprint(), json, value.metadata().updatedAt(),
                value.metadata().updatedBy().id(), value.scope().tenantId(),
                value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), value.fixtureAssetId(),
                expectedRevision);
    }

    private void insertRevision(StoredFixtureAsset stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_fixture_asset_revisions (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            fixture_asset_id, revision, fingerprint, schema_id, schema_revision,
                            schema_fingerprint, variant_key, lifecycle, classification, owner_id,
                            material_id, material_revision, material_fingerprint,
                            canonical_json, created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private void insertOutbox(StoredFixtureAsset stored) {
        FixtureAssetDescriptor value = stored.descriptor();
        FixtureAssetChanged event = new FixtureAssetChanged(
                "", UUID.randomUUID().toString(), value.scope(), stored.exactRef(),
                value.materialRef(), value.schemaRef().id(), value.schemaRef().fingerprint(),
                value.lifecycle().name(), value.classification(),
                value.metadata().updatedBy().id(), value.metadata().updatedAt());
        jdbc.update("""
                        INSERT INTO rg_correctness_outbox (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            event_id, aggregate_kind, aggregate_id, aggregate_revision,
                            event_type, event_json, occurred_at, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), event.eventId(),
                "FIXTURE_ASSET", value.fixtureAssetId(), value.revision(),
                FixtureAssetChanged.SCHEMA_VERSION, serialize(event), event.occurredAt());
    }

    private Object[] rowArgs(StoredFixtureAsset stored, String json) {
        FixtureAssetDescriptor value = stored.descriptor();
        return new Object[]{
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), value.fixtureAssetId(),
                value.revision(), stored.descriptorFingerprint(), value.schemaRef().id(),
                value.schemaRef().revision(), value.schemaRef().fingerprint(), value.variantKey(),
                value.lifecycle().name(), value.classification(), value.owner().id(),
                value.materialRef().id(), value.materialRef().revision(),
                value.materialRef().fingerprint(), json, value.metadata().createdAt(),
                value.metadata().updatedAt(), value.metadata().updatedBy().id()
        };
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Fixture catalog record", failure);
        }
    }

    private static void requireFixtureRef(ExactAssetRef ref) {
        if (ref == null || !"FIXTURE_ASSET".equals(ref.kind())) {
            throw new IllegalArgumentException("Exact FIXTURE_ASSET ref is required");
        }
    }

    private static EnterpriseScope exactScope(EnterpriseScope scope) {
        return Objects.requireNonNull(scope, "scope");
    }

    private static String exactId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("fixtureAssetId is required");
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
