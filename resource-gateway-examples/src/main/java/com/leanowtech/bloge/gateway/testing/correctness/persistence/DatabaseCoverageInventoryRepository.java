package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;

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

/** PostgreSQL/H2 Coverage Inventory store with CAS, retained history and atomic outbox. */
public class DatabaseCoverageInventoryRepository implements CoverageInventoryRepository {

    private static final String HEAD_TABLE = "rg_coverage_inventory_heads";
    private static final String REVISION_TABLE = "rg_coverage_inventory_revisions";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DatabaseCoverageInventoryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Clock.systemUTC());
    }

    public DatabaseCoverageInventoryRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StoredCoverageInventory> findHead(
            EnterpriseScope scope,
            String inventoryId
    ) {
        return queryOne(HEAD_TABLE, exactScope(scope), exactId(inventoryId), 0);
    }

    @Override
    public Optional<StoredCoverageInventory> findRevision(
            EnterpriseScope scope,
            String inventoryId,
            long revision
    ) {
        if (revision < 1) return Optional.empty();
        return queryOne(REVISION_TABLE, exactScope(scope), exactId(inventoryId), revision);
    }

    @Override
    public List<StoredCoverageInventory> revisions(
            EnterpriseScope scope,
            String inventoryId
    ) {
        EnterpriseScope exactScope = exactScope(scope);
        String exactId = exactId(inventoryId);
        return jdbc.query("""
                        SELECT * FROM rg_coverage_inventory_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND inventory_id = ?
                        ORDER BY revision DESC
                        """,
                (result, row) -> readAndVerify(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional
    public Optional<StoredCoverageInventory> saveIfRevision(
            long expectedRevision,
            CoverageInventory candidate,
            PrincipalRef actor
    ) {
        if (candidate == null || actor == null || expectedRevision < 0
                || candidate.revision() != expectedRevision) {
            throw new IllegalArgumentException(
                    "Inventory, actor, and matching non-negative expected revision are required");
        }
        EnterpriseScope scope = exactScope(candidate.scope());
        String id = exactId(candidate.inventoryId());
        StoredCoverageInventory current = findHead(scope, id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.inventory().revision() != expectedRevision)) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        AuditMetadata metadata = current == null
                ? new AuditMetadata(now, now, actor, actor)
                : new AuditMetadata(
                        current.inventory().metadata().createdAt(), now,
                        current.inventory().metadata().createdBy(), actor);
        CoverageInventory persisted = candidate.persistedAs(expectedRevision + 1, metadata);
        StoredCoverageInventory stored = StoredCoverageInventory.verified(mapper, persisted);
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
        insertObligationIndex(stored);
        insertOutbox(stored);
        return Optional.of(stored);
    }

    private Optional<StoredCoverageInventory> queryOne(
            String table,
            EnterpriseScope scope,
            String inventoryId,
            long revision
    ) {
        String revisionClause = revision > 0 ? " AND revision = ?" : "";
        Object[] args = revision > 0
                ? append(scopeArgs(scope, inventoryId), revision)
                : scopeArgs(scope, inventoryId);
        return jdbc.query("""
                        SELECT * FROM %s
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND inventory_id = ?%s
                        """.formatted(table, revisionClause),
                (result, row) -> readAndVerify(result, scope, inventoryId), args)
                .stream().flatMap(Optional::stream).findFirst();
    }

    private Optional<StoredCoverageInventory> readAndVerify(
            ResultSet result,
            EnterpriseScope scope,
            String inventoryId
    ) throws SQLException {
        try {
            StoredCoverageInventory stored = mapper.readValue(
                    result.getString("canonical_json"), StoredCoverageInventory.class);
            CoverageInventory inventory = stored.inventory();
            String fingerprint = CorrectnessProtocolFingerprint.fingerprint(mapper, inventory);
            boolean valid = inventory.scope().equals(scope)
                    && inventory.inventoryId().equals(inventoryId)
                    && inventory.revision() == result.getLong("revision")
                    && stored.inventoryFingerprint().equals(result.getString("fingerprint"))
                    && stored.inventoryFingerprint().equals(fingerprint)
                    && inventory.target().kind().name().equals(result.getString("target_kind"))
                    && inventory.target().id().equals(result.getString("target_id"))
                    && inventory.target().revision() == result.getLong("target_revision")
                    && inventory.target().fingerprint().equals(
                            result.getString("target_fingerprint"))
                    && inventory.lifecycle().name().equals(result.getString("lifecycle"));
            if (!valid || !obligationIndexMatches(stored)) {
                throw new IllegalStateException("Stored Coverage Inventory integrity check failed");
            }
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Coverage Inventory revision", failure);
        }
    }

    private boolean obligationIndexMatches(StoredCoverageInventory stored) {
        CoverageInventory value = stored.inventory();
        List<IndexedObligation> rows = jdbc.query("""
                        SELECT obligation_id, obligation_fingerprint, dimension, risk,
                               owner_id, lifecycle, source
                        FROM rg_coverage_obligation_index
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND inventory_id = ? AND inventory_revision = ?
                        ORDER BY obligation_id
                        """,
                (result, row) -> new IndexedObligation(
                        result.getString("obligation_id"),
                        result.getString("obligation_fingerprint"),
                        result.getString("dimension"), result.getString("risk"),
                        result.getString("owner_id"), result.getString("lifecycle"),
                        result.getString("source")),
                value.scope().tenantId(), value.scope().organizationId(),
                value.scope().projectId(), value.scope().environment(), value.scope().region(),
                value.inventoryId(), value.revision());
        List<IndexedObligation> expected = value.obligations().stream()
                .map(obligation -> indexed(obligation)).toList();
        return rows.equals(expected);
    }

    private IndexedObligation indexed(CoverageObligation obligation) {
        return new IndexedObligation(
                obligation.obligationId(),
                CorrectnessProtocolFingerprint.obligationFingerprint(mapper, obligation),
                obligation.dimension().name(), obligation.risk().name(), obligation.owner().id(),
                obligation.lifecycle().name(), obligation.source().name());
    }

    private void insertHead(StoredCoverageInventory stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_coverage_inventory_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            inventory_id, revision, fingerprint, target_kind, target_id,
                            target_revision, target_fingerprint, lifecycle, canonical_json,
                            created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private int updateHead(
            StoredCoverageInventory stored,
            String json,
            long expectedRevision
    ) {
        CoverageInventory value = stored.inventory();
        return jdbc.update("""
                        UPDATE rg_coverage_inventory_heads
                        SET revision = ?, fingerprint = ?, target_kind = ?, target_id = ?,
                            target_revision = ?, target_fingerprint = ?, lifecycle = ?,
                            canonical_json = ?, updated_at = ?, updated_by = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND inventory_id = ?
                          AND revision = ?
                        """,
                value.revision(), stored.inventoryFingerprint(), value.target().kind().name(),
                value.target().id(), value.target().revision(), value.target().fingerprint(),
                value.lifecycle().name(), json, value.metadata().updatedAt(),
                value.metadata().updatedBy().id(), value.scope().tenantId(),
                value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), value.inventoryId(),
                expectedRevision);
    }

    private void insertRevision(StoredCoverageInventory stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_coverage_inventory_revisions (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            inventory_id, revision, fingerprint, target_kind, target_id,
                            target_revision, target_fingerprint, lifecycle, canonical_json,
                            created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private void insertObligationIndex(StoredCoverageInventory stored) {
        CoverageInventory value = stored.inventory();
        for (CoverageObligation obligation : value.obligations()) {
            IndexedObligation index = indexed(obligation);
            jdbc.update("""
                            INSERT INTO rg_coverage_obligation_index (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                inventory_id, inventory_revision, obligation_id,
                                obligation_fingerprint, dimension, risk, owner_id, lifecycle, source
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    value.scope().tenantId(), value.scope().organizationId(),
                    value.scope().projectId(), value.scope().environment(), value.scope().region(),
                    value.inventoryId(), value.revision(), index.id(), index.fingerprint(),
                    index.dimension(), index.risk(), index.ownerId(), index.lifecycle(),
                    index.source());
        }
    }

    private void insertOutbox(StoredCoverageInventory stored) {
        CoverageInventory value = stored.inventory();
        ExactAssetRef inventoryRef = new ExactAssetRef(
                "INVENTORY", value.inventoryId(), value.revision(),
                stored.inventoryFingerprint());
        String eventId = UUID.randomUUID().toString();
        String type;
        String json;
        if (value.lifecycle() == InventoryLifecycle.FROZEN) {
            CoverageInventoryFrozen event = new CoverageInventoryFrozen(
                    "", eventId, value.scope(), inventoryRef, value.target(),
                    value.derivationSources(), value.obligations().size(),
                    (int) value.obligations().stream()
                            .filter(item -> item.lifecycle() == ObligationLifecycle.WAIVED).count(),
                    value.freezeReview().reviewer().id(), value.metadata().updatedAt());
            type = CoverageInventoryFrozen.SCHEMA_VERSION;
            json = serializeEvent(event);
        } else {
            CoverageInventoryChanged event = new CoverageInventoryChanged(
                    "", eventId, value.scope(), inventoryRef, value.target(),
                    value.lifecycle().name(), value.obligations().size(),
                    value.metadata().updatedBy().id(), value.metadata().updatedAt());
            type = CoverageInventoryChanged.SCHEMA_VERSION;
            json = serializeEvent(event);
        }
        jdbc.update("""
                        INSERT INTO rg_correctness_outbox (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            event_id, aggregate_kind, aggregate_id, aggregate_revision,
                            event_type, event_json, occurred_at, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), eventId, "INVENTORY",
                value.inventoryId(), value.revision(), type, json, value.metadata().updatedAt());
    }

    private Object[] rowArgs(StoredCoverageInventory stored, String json) {
        CoverageInventory value = stored.inventory();
        return new Object[]{
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), value.inventoryId(),
                value.revision(), stored.inventoryFingerprint(), value.target().kind().name(),
                value.target().id(), value.target().revision(), value.target().fingerprint(),
                value.lifecycle().name(), json, value.metadata().createdAt(),
                value.metadata().updatedAt(), value.metadata().updatedBy().id()
        };
    }

    private String serialize(StoredCoverageInventory stored) {
        try {
            return mapper.writeValueAsString(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Coverage Inventory revision", failure);
        }
    }

    private String serializeEvent(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Coverage Inventory event", failure);
        }
    }

    private static EnterpriseScope exactScope(EnterpriseScope scope) {
        return Objects.requireNonNull(scope, "scope");
    }

    private static String exactId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("inventoryId is required");
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

    private record IndexedObligation(
            String id,
            String fingerprint,
            String dimension,
            String risk,
            String ownerId,
            String lifecycle,
            String source
    ) {}
}
