package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC vault for encrypted Fixture material. No startup DDL and no plaintext columns. */
public class DatabaseProtectedFixtureMaterialRepository implements FixtureMaterialRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseProtectedFixtureMaterialRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public Optional<StoredFixtureMaterial> saveIfRevision(
            long expectedRevision,
            StoredFixtureMaterial candidate,
            AccessAudit writeAudit) {
        if (expectedRevision < 0 || candidate == null || writeAudit == null
                || candidate.receipt().materialRef().revision() != expectedRevision + 1
                || !candidate.scope().equals(writeAudit.scope())
                || !candidate.receipt().materialRef().equals(writeAudit.materialRef())
                || !"WRITE".equals(writeAudit.action())) {
            throw new IllegalArgumentException("Fixture material CAS write coordinate is invalid");
        }
        EnterpriseScope scope = candidate.scope();
        String id = candidate.receipt().fixtureAssetId();
        if (latestRevision(scope, id) != expectedRevision) return Optional.empty();
        StoredFixtureMaterial stored = FixtureMaterialIntegrity.attach(mapper, candidate);
        Receipt receipt = stored.receipt();
        try {
            jdbc.update("""
                            INSERT INTO rg_fixture_material_v2_revisions (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                fixture_asset_id, revision, material_fingerprint, classification,
                                expires_at, state, receipt_json, protected_payload, record_fingerprint,
                                created_at, created_by
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                    scope.region(), id, receipt.materialRef().revision(),
                    receipt.materialRef().fingerprint(), receipt.classification(),
                    receipt.retention().expiresAt(), stored.state(), serialize(receipt),
                    stored.protectedPayload(), stored.recordFingerprint(), writeAudit.occurredAt(),
                    writeAudit.actorId());
        } catch (DuplicateKeyException conflict) {
            return Optional.empty();
        }
        insertAudit(writeAudit);
        return Optional.of(stored);
    }

    @Override
    public Optional<StoredFixtureMaterial> find(
            EnterpriseScope scope, String fixtureAssetId, long revision) {
        if (scope == null || fixtureAssetId == null || fixtureAssetId.isBlank() || revision < 1) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT * FROM rg_fixture_material_v2_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND fixture_asset_id = ? AND revision = ?
                        """,
                (result, row) -> readAndVerify(result, scope, fixtureAssetId.trim(), revision),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                scope.region(), fixtureAssetId.trim(), revision)
                .stream().flatMap(Optional::stream).findFirst();
    }

    @Override
    public long latestRevision(EnterpriseScope scope, String fixtureAssetId) {
        if (scope == null || fixtureAssetId == null || fixtureAssetId.isBlank()) return 0;
        Long revision = jdbc.queryForObject("""
                        SELECT COALESCE(MAX(revision), 0)
                        FROM rg_fixture_material_v2_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND fixture_asset_id = ?
                        """, Long.class,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                scope.region(), fixtureAssetId.trim());
        return revision == null ? 0 : revision;
    }

    /** Returns a bounded, integrity-verified metadata inventory without projecting ciphertext. */
    @Override
    public List<StoredFixtureMaterial> listAvailable(
            EnterpriseScope scope, String fixtureAssetIdPrefix, int limit) {
        if (scope == null || fixtureAssetIdPrefix == null || fixtureAssetIdPrefix.isBlank()
                || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Valid Fixture material inventory bounds are required");
        }
        String prefix = fixtureAssetIdPrefix.trim();
        return jdbc.query("""
                        SELECT * FROM rg_fixture_material_v2_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND fixture_asset_id LIKE ? AND state = 'AVAILABLE'
                        ORDER BY fixture_asset_id, revision
                        LIMIT ?
                        """,
                (result, row) -> {
                    StoredFixtureMaterial raw = readUnchecked(result);
                    return readAndVerify(result, scope, raw.receipt().fixtureAssetId(),
                            raw.receipt().materialRef().revision()).orElseThrow();
                }, scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                scope.region(), prefix + "%", limit);
    }

    /** Conditionally tombstones one exact expired revision and records the administrative action. */
    @Override
    @Transactional
    public boolean expireExactIfDue(
            EnterpriseScope scope,
            com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef materialRef,
            String expectedRecordFingerprint,
            Instant observedAt,
            AccessAudit reclaimAudit) {
        if (scope == null || materialRef == null || expectedRecordFingerprint == null
                || expectedRecordFingerprint.isBlank() || observedAt == null || reclaimAudit == null
                || !scope.equals(reclaimAudit.scope()) || !materialRef.equals(reclaimAudit.materialRef())
                || !"EXPIRE".equals(reclaimAudit.action())) {
            throw new IllegalArgumentException("Exact Fixture material reclaim coordinate is invalid");
        }
        StoredFixtureMaterial current = find(
                scope, materialRef.id(), materialRef.revision()).orElse(null);
        if (current == null || !current.receipt().materialRef().equals(materialRef)
                || !current.recordFingerprint().equals(expectedRecordFingerprint)
                || !StoredFixtureMaterial.AVAILABLE.equals(current.state())
                || current.receipt().retention().expiresAt().isAfter(observedAt)) {
            return false;
        }
        StoredFixtureMaterial tombstone = FixtureMaterialIntegrity.attach(mapper, current.expired());
        int changed = jdbc.update("""
                        UPDATE rg_fixture_material_v2_revisions
                        SET state = 'EXPIRED', protected_payload = NULL, record_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND fixture_asset_id = ?
                          AND revision = ? AND state = 'AVAILABLE' AND record_fingerprint = ?
                        """,
                tombstone.recordFingerprint(), scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), materialRef.id(), materialRef.revision(),
                expectedRecordFingerprint);
        if (changed == 1) insertAudit(reclaimAudit);
        return changed == 1;
    }

    @Override
    public void appendAccessAudit(AccessAudit audit) {
        insertAudit(Objects.requireNonNull(audit, "audit"));
    }

    @Override
    @Transactional
    public int expireDue(Instant observedAt, int limit) {
        if (observedAt == null || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Valid Fixture expiry bound is required");
        }
        var due = jdbc.query("""
                        SELECT * FROM rg_fixture_material_v2_revisions
                        WHERE state = 'AVAILABLE' AND expires_at <= ?
                        ORDER BY expires_at, tenant_id, fixture_asset_id, revision
                        LIMIT ?
                        """,
                (result, row) -> {
                    StoredFixtureMaterial raw = readUnchecked(result);
                    return readAndVerify(
                            result, raw.scope(), raw.receipt().fixtureAssetId(),
                            raw.receipt().materialRef().revision()).orElseThrow();
                }, observedAt, limit);
        int expired = 0;
        for (StoredFixtureMaterial current : due) {
            StoredFixtureMaterial tombstone = FixtureMaterialIntegrity.attach(
                    mapper, current.expired());
            int changed = jdbc.update("""
                            UPDATE rg_fixture_material_v2_revisions
                            SET state = 'EXPIRED', protected_payload = NULL, record_fingerprint = ?
                            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                              AND environment_id = ? AND region_id = ? AND fixture_asset_id = ?
                              AND revision = ? AND state = 'AVAILABLE'
                            """,
                    tombstone.recordFingerprint(), current.scope().tenantId(),
                    current.scope().organizationId(), current.scope().projectId(),
                    current.scope().environment(), current.scope().region(),
                    current.receipt().fixtureAssetId(), current.receipt().materialRef().revision());
            expired += changed;
        }
        return expired;
    }

    private Optional<StoredFixtureMaterial> readAndVerify(
            ResultSet result,
            EnterpriseScope scope,
            String fixtureAssetId,
            long revision) throws SQLException {
        StoredFixtureMaterial stored = readUnchecked(result);
        StoredFixtureMaterial verified = FixtureMaterialIntegrity.verify(mapper, stored);
        Receipt receipt = verified.receipt();
        boolean valid = verified.scope().equals(scope)
                && receipt.fixtureAssetId().equals(fixtureAssetId)
                && receipt.materialRef().revision() == revision
                && receipt.materialRef().fingerprint().equals(
                        result.getString("material_fingerprint"))
                && receipt.classification().equals(result.getString("classification"))
                && receipt.retention().expiresAt().equals(
                        result.getObject("expires_at", java.time.OffsetDateTime.class).toInstant())
                && verified.state().equals(result.getString("state"))
                && verified.recordFingerprint().equals(result.getString("record_fingerprint"));
        if (!valid) {
            throw new FixtureMaterialCommandException(
                    503, "RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID",
                    "Protected Fixture material columns failed integrity verification");
        }
        return Optional.of(verified);
    }

    private StoredFixtureMaterial readUnchecked(ResultSet result) throws SQLException {
        try {
            EnterpriseScope scope = new EnterpriseScope(
                    result.getString("tenant_id"), result.getString("organization_id"),
                    result.getString("project_id"), result.getString("environment_id"),
                    result.getString("region_id"));
            Receipt receipt = mapper.readValue(result.getString("receipt_json"), Receipt.class);
            String payload = result.getString("protected_payload");
            return new StoredFixtureMaterial(
                    "", scope, receipt, result.getString("state"),
                    StoredFixtureMaterial.AVAILABLE.equals(result.getString("state")),
                    payload == null ? "" : payload, result.getString("record_fingerprint"));
        } catch (JsonProcessingException failure) {
            throw new FixtureMaterialCommandException(
                    503, "RG.CORRECTNESS.FIXTURE_MATERIAL_INTEGRITY_INVALID",
                    "Protected Fixture material receipt could not be decoded");
        }
    }

    private void insertAudit(AccessAudit audit) {
        jdbc.update("""
                        INSERT INTO rg_fixture_material_access_audit (
                            access_id, tenant_id, organization_id, project_id, environment_id,
                            region_id, material_id, material_revision, material_fingerprint,
                            actor_id, purpose, action, outcome, correlation_id, occurred_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                audit.accessId(), audit.scope().tenantId(), audit.scope().organizationId(),
                audit.scope().projectId(), audit.scope().environment(), audit.scope().region(),
                audit.materialRef().id(), audit.materialRef().revision(),
                audit.materialRef().fingerprint(), audit.actorId(), audit.purpose(), audit.action(),
                audit.outcome(), audit.correlationId(), audit.occurredAt());
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Fixture material receipt", failure);
        }
    }

}
