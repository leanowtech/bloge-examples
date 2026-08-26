package com.leanowtech.bloge.gateway.testing.world.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/** PostgreSQL/H2 database-authoritative repository for the three Stage 1 governed assets. */
public final class DatabaseGovernedCatalogRepository implements GovernedCatalogRepository {
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS rg_world_catalog_heads (
                tenant_id VARCHAR(255) NOT NULL,
                kind VARCHAR(64) NOT NULL,
                asset_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL CHECK (revision > 0),
                fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
                record_fingerprint VARCHAR(80) NOT NULL CHECK (record_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
                CHECK (kind IN ('LOGICAL_RESOURCE_CONTRACT', 'RESOURCE_WORLD_MODEL', 'SCENARIO')),
                canonical_json TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (tenant_id, kind, asset_id)
            )
            """;
    private static final String CREATE_REVISIONS = """
            CREATE TABLE IF NOT EXISTS rg_world_catalog_revisions (
                tenant_id VARCHAR(255) NOT NULL,
                kind VARCHAR(64) NOT NULL,
                asset_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL CHECK (revision > 0),
                fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
                record_fingerprint VARCHAR(80) NOT NULL CHECK (record_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
                CHECK (kind IN ('LOGICAL_RESOURCE_CONTRACT', 'RESOURCE_WORLD_MODEL', 'SCENARIO')),
                canonical_json TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (tenant_id, kind, asset_id, revision)
            )
            """;

    private final JdbcTemplate jdbc;
    private final GovernedCatalogCodec codec;
    private final TransactionTemplate transactions;

    public DatabaseGovernedCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, new GovernedCatalogCodec(mapper));
    }

    public DatabaseGovernedCatalogRepository(JdbcTemplate jdbc, GovernedCatalogCodec codec) {
        if (jdbc == null || jdbc.getDataSource() == null || codec == null) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_DATABASE");
        }
        this.jdbc = jdbc;
        this.codec = codec;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /** Creates the migration-equivalent tables for embedded tests and local examples. */
    public void init() {
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_REVISIONS);
    }

    @Override
    public GovernedResourceRef create(TrustedTenant tenant, GovernedCatalogKind kind, String id, Object value) {
        Prepared prepared = prepare(tenant, kind, id, 1, value);
        try {
            transactions.executeWithoutResult(status -> {
                int inserted = jdbc.update("""
                        INSERT INTO rg_world_catalog_heads
                            (tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint, canonical_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, prepared.ref().tenantId(), prepared.ref().kind().name(), prepared.ref().id(),
                        prepared.ref().revision(), prepared.ref().fingerprint(), prepared.recordFingerprint(),
                        prepared.json());
                if (inserted != 1) {
                    throw new GovernedCatalogConflictException();
                }
                insertRevision(prepared);
            });
        } catch (DuplicateKeyException conflict) {
            throw new GovernedCatalogConflictException();
        }
        return prepared.ref();
    }

    @Override
    public GovernedResourceRef update(GovernedResourceRef expected, Object value) {
        if (expected == null || value == null || !expected.kind().accepts(value)) {
            throw new GovernedCatalogConflictException();
        }
        long nextRevision = Math.addExact(expected.revision(), 1);
        Prepared prepared = prepare(new TrustedTenant(expected.tenantId()), expected.kind(), expected.id(),
                nextRevision, value);
        try {
            transactions.executeWithoutResult(status -> {
                CatalogRow current = selectHead(expected.tenantId(), expected.kind(), expected.id(), true)
                        .orElseThrow(GovernedCatalogConflictException::new);
                validateRow(current);
                if (!current.ref().equals(expected)) {
                    throw new GovernedCatalogConflictException();
                }
                insertRevision(prepared);
                int updated = jdbc.update("""
                        UPDATE rg_world_catalog_heads
                           SET revision = ?, fingerprint = ?, record_fingerprint = ?, canonical_json = ?,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE tenant_id = ? AND kind = ? AND asset_id = ? AND revision = ? AND fingerprint = ?
                        """, prepared.ref().revision(), prepared.ref().fingerprint(), prepared.recordFingerprint(),
                        prepared.json(),
                        expected.tenantId(), expected.kind().name(), expected.id(), current.ref().revision(),
                        current.ref().fingerprint());
                if (updated != 1) {
                    throw new GovernedCatalogConflictException();
                }
            });
        } catch (DuplicateKeyException conflict) {
            throw new GovernedCatalogConflictException();
        }
        return prepared.ref();
    }

    @Override
    public Optional<GovernedCatalogRevision> findExact(GovernedResourceRef ref) {
        if (ref == null) {
            return Optional.empty();
        }
        Optional<CatalogRow> head = selectHead(ref.tenantId(), ref.kind(), ref.id(), false);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        CatalogRow current = head.get();
        validateRow(current);
        if (current.ref().revision() != ref.revision()) {
            current = selectRevision(ref.tenantId(), ref.kind(), ref.id(), ref.revision())
                    .orElse(null);
            if (current == null) {
                return Optional.empty();
            }
            validateRow(current);
        }
        if (current.ref().revision() != ref.revision()
                || !current.ref().fingerprint().equals(ref.fingerprint())) {
            return Optional.empty();
        }
        return Optional.of(current.value());
    }

    @Override
    public List<GovernedCatalogRevision> history(TrustedTenant tenant,
                                                 GovernedCatalogKind kind,
                                                 String id) {
        if (tenant == null || kind == null || id == null || id.isBlank()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint, canonical_json
                  FROM rg_world_catalog_revisions
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                 ORDER BY revision DESC, fingerprint DESC
                """, (resultSet, rowNum) -> readRow(resultSet.getString("tenant_id"),
                        resultSet.getString("kind"), resultSet.getString("asset_id"),
                        resultSet.getLong("revision"), resultSet.getString("fingerprint"),
                        resultSet.getString("record_fingerprint"),
                        resultSet.getString("canonical_json")), tenant.value(), kind.name(), id)
                .stream().map(row -> {
                    validateRow(row);
                    return row.value();
                }).toList();
    }

    private Prepared prepare(TrustedTenant tenant, GovernedCatalogKind kind, String id,
                             long revision, Object value) {
        if (tenant == null || kind == null || id == null || id.isBlank() || revision <= 0
                || !kind.accepts(value)) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_ASSET");
        }
        String normalizedId = id.trim();
        String json = codec.encode(tenant, kind, normalizedId, revision, value);
        return new Prepared(new GovernedResourceRef(tenant, kind, normalizedId, revision,
                codec.fingerprint(value)), json, codec.recordFingerprint(json), value);
    }

    private void insertRevision(Prepared prepared) {
        int inserted = jdbc.update("""
                INSERT INTO rg_world_catalog_revisions
                    (tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint, canonical_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, prepared.ref().tenantId(), prepared.ref().kind().name(), prepared.ref().id(),
                prepared.ref().revision(), prepared.ref().fingerprint(), prepared.recordFingerprint(),
                prepared.json());
        if (inserted != 1) {
            throw new GovernedCatalogConflictException();
        }
    }

    private Optional<CatalogRow> selectHead(String tenant, GovernedCatalogKind kind, String id,
                                             boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        List<CatalogRow> rows = jdbc.query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint, canonical_json
                  FROM rg_world_catalog_heads
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """ + lock, (resultSet, rowNum) -> readRow(resultSet.getString("tenant_id"),
                        resultSet.getString("kind"), resultSet.getString("asset_id"),
                        resultSet.getLong("revision"), resultSet.getString("fingerprint"),
                        resultSet.getString("record_fingerprint"),
                        resultSet.getString("canonical_json")), tenant, kind.name(), id);
        return rows.stream().findFirst();
    }

    private Optional<CatalogRow> selectRevision(String tenant, GovernedCatalogKind kind, String id,
                                                long revision) {
        List<CatalogRow> rows = jdbc.query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint, canonical_json
                  FROM rg_world_catalog_revisions
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ? AND revision = ?
                """, (resultSet, rowNum) -> readRow(resultSet.getString("tenant_id"),
                        resultSet.getString("kind"), resultSet.getString("asset_id"),
                        resultSet.getLong("revision"), resultSet.getString("fingerprint"),
                        resultSet.getString("record_fingerprint"),
                        resultSet.getString("canonical_json")), tenant, kind.name(), id, revision);
        return rows.stream().findFirst();
    }

    private CatalogRow readRow(String tenant, String rawKind, String id, long revision,
                               String fingerprint, String recordFingerprint, String json) {
        try {
            if (recordFingerprint == null || !recordFingerprint.equals(codec.recordFingerprint(json))) {
                throw new GovernedCatalogIntegrityException();
            }
            GovernedCatalogKind kind = GovernedCatalogKind.valueOf(rawKind);
            GovernedResourceRef ref = new GovernedResourceRef(tenant, kind, id, revision, fingerprint);
            Object value = codec.decode(json, new TrustedTenant(tenant), kind, id, revision, fingerprint,
                    worldRef -> findExact(worldRef).map(entry -> {
                        if (!(entry.value() instanceof ResourceWorldModel world)) {
                            throw new GovernedCatalogIntegrityException();
                        }
                        return world;
                    }).orElseThrow(GovernedCatalogIntegrityException::new));
            return new CatalogRow(ref, new GovernedCatalogRevision(ref, value));
        } catch (GovernedCatalogIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    private void validateRow(CatalogRow row) {
        if (row == null || row.ref() == null || row.value() == null
                || !row.ref().kind().accepts(row.value().value())
                || !row.ref().fingerprint().equals(codec.fingerprint(row.value().value()))) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    private record Prepared(GovernedResourceRef ref, String json, String recordFingerprint, Object value) {
    }

    private record CatalogRow(GovernedResourceRef ref, GovernedCatalogRevision value) {
    }
}
