package com.leanowtech.bloge.gateway.testing.world.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL/H2 database-authoritative repository for the three Stage 1 governed assets. */
public final class DatabaseGovernedCatalogRepository implements GovernedCatalogRepository {
    private static final String GOVERNANCE_COLUMNS = """
            payload_origin, security_classification, retention_expires_at, access_policy_ref,
            approval_ref, governance_fingerprint
            """;
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS rg_world_catalog_heads (
                tenant_id VARCHAR(255) NOT NULL,
                kind VARCHAR(64) NOT NULL,
                asset_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL CHECK (revision > 0),
                fingerprint VARCHAR(80) NOT NULL CHECK (fingerprint ~ '^sha256:[a-f0-9]{64}$'),
                record_fingerprint VARCHAR(80) NOT NULL CHECK (record_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
                payload_origin VARCHAR(32) NOT NULL,
                security_classification VARCHAR(32) NOT NULL,
                retention_expires_at TIMESTAMP NULL,
                access_policy_ref VARCHAR(512) NOT NULL,
                approval_ref VARCHAR(512) NULL,
                governance_fingerprint VARCHAR(80) NOT NULL CHECK (governance_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
                CHECK (kind IN ('LOGICAL_RESOURCE_CONTRACT', 'RESOURCE_WORLD_MODEL', 'SCENARIO', 'FUNCTION_CONTROL')),
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
                payload_origin VARCHAR(32) NOT NULL,
                security_classification VARCHAR(32) NOT NULL,
                retention_expires_at TIMESTAMP NULL,
                access_policy_ref VARCHAR(512) NOT NULL,
                approval_ref VARCHAR(512) NULL,
                governance_fingerprint VARCHAR(80) NOT NULL CHECK (governance_fingerprint ~ '^sha256:[a-f0-9]{64}$'),
                CHECK (kind IN ('LOGICAL_RESOURCE_CONTRACT', 'RESOURCE_WORLD_MODEL', 'SCENARIO', 'FUNCTION_CONTROL')),
                canonical_json TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (tenant_id, kind, asset_id, revision)
            )
            """;

    private final JdbcTemplate jdbc;
    private final GovernedCatalogCodec codec;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DatabaseGovernedCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, new GovernedCatalogCodec(mapper));
    }

    public DatabaseGovernedCatalogRepository(JdbcTemplate jdbc, GovernedCatalogCodec codec) {
        this(jdbc, codec, Clock.systemUTC());
    }

    public DatabaseGovernedCatalogRepository(JdbcTemplate jdbc, GovernedCatalogCodec codec, Clock clock) {
        if (jdbc == null || jdbc.getDataSource() == null || codec == null || clock == null) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_DATABASE");
        }
        this.jdbc = jdbc;
        this.codec = codec;
        this.clock = clock;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /** Creates the migration-equivalent tables for embedded tests and local examples. */
    public void init() {
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_REVISIONS);
        ensureGovernanceSchema("rg_world_catalog_heads");
        ensureGovernanceSchema("rg_world_catalog_revisions");
        probeLegacyGovernance("rg_world_catalog_heads");
        probeLegacyGovernance("rg_world_catalog_revisions");
    }

    private void ensureGovernanceSchema(String table) {
        jdbc.execute("ALTER TABLE " + table
                + " ADD COLUMN IF NOT EXISTS payload_origin VARCHAR(32) NOT NULL DEFAULT 'SYNTHETIC'");
        jdbc.execute("ALTER TABLE " + table
                + " ADD COLUMN IF NOT EXISTS security_classification VARCHAR(32) NOT NULL DEFAULT 'PUBLIC'");
        jdbc.execute("ALTER TABLE " + table
                + " ADD COLUMN IF NOT EXISTS retention_expires_at TIMESTAMP NULL");
        jdbc.execute("ALTER TABLE " + table
                + " ADD COLUMN IF NOT EXISTS access_policy_ref VARCHAR(512) NOT NULL DEFAULT 'builtin:synthetic-public'");
        jdbc.execute("ALTER TABLE " + table
                + " ADD COLUMN IF NOT EXISTS approval_ref VARCHAR(512) NULL");
        jdbc.execute("ALTER TABLE " + table
                + " ADD COLUMN IF NOT EXISTS governance_fingerprint VARCHAR(80) NULL");
    }

    private void probeLegacyGovernance(String table) {
        Integer invalidRows = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %s
                 WHERE governance_fingerprint IS NULL
                    OR TRIM(governance_fingerprint) = ''
                    OR payload_origin IS NULL
                    OR TRIM(payload_origin) NOT IN ('SYNTHETIC', 'REDACTED', 'REAL')
                    OR security_classification IS NULL
                    OR TRIM(security_classification) NOT IN
                       ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')
                    OR access_policy_ref IS NULL
                    OR TRIM(access_policy_ref) = ''
                """.formatted(table), Integer.class);
        if (invalidRows != null && invalidRows > 0) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    @Override
    public GovernedResourceRef create(TrustedTenant tenant, GovernedCatalogKind kind, String id, Object value) {
        return create(tenant, kind, id, value, GovernedAssetMetadata.safeDefaults());
    }

    @Override
    public GovernedResourceRef create(TrustedTenant tenant, GovernedCatalogKind kind, String id,
                                      Object value, GovernedAssetMetadata metadata) {
        Prepared prepared = prepare(tenant, kind, id, 1, value, metadata);
        try {
            transactions.executeWithoutResult(status -> {
                int inserted = jdbc.update("""
                        INSERT INTO rg_world_catalog_heads
                            (tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint,
                             payload_origin, security_classification, retention_expires_at, access_policy_ref,
                             approval_ref, governance_fingerprint, canonical_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, prepared.ref().tenantId(), prepared.ref().kind().name(), prepared.ref().id(),
                        prepared.ref().revision(), prepared.ref().fingerprint(), prepared.recordFingerprint(),
                        prepared.metadata().payloadOrigin().name(), prepared.metadata().securityClassification().name(),
                        prepared.metadata().retentionExpiresAt(), prepared.metadata().accessPolicyRef(),
                        prepared.metadata().approvalRef(), prepared.metadata().governanceFingerprint(), prepared.json());
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
        CatalogMetadata current = selectStoredHead(expected.tenantId(), expected.kind(), expected.id(), false)
                .map(this::metadataFor)
                .orElseThrow(GovernedCatalogConflictException::new);
        return updateWithGovernance(expected, value, nextRevision,
                new GovernedAssetMetadata(current.governance()));
    }

    @Override
    public GovernedResourceRef update(GovernedResourceRef expected, Object value,
                                      GovernedAssetMetadata metadata) {
        if (expected == null || value == null || metadata == null || !expected.kind().accepts(value)) {
            throw new GovernedCatalogConflictException();
        }
        long nextRevision = Math.addExact(expected.revision(), 1);
        return updateWithGovernance(expected, value, nextRevision, metadata);
    }

    private GovernedResourceRef updateWithGovernance(GovernedResourceRef expected, Object value,
                                                     long nextRevision, GovernedAssetMetadata metadata) {
        Prepared prepared = prepare(new TrustedTenant(expected.tenantId()), expected.kind(), expected.id(),
                nextRevision, value, metadata);
        try {
            transactions.executeWithoutResult(status -> {
                CatalogRow current = selectHead(expected.tenantId(), expected.kind(), expected.id(), true,
                        this::resolveTrustedWorld).orElseThrow(GovernedCatalogConflictException::new);
                validateRow(current);
                if (!current.ref().equals(expected)) {
                    throw new GovernedCatalogConflictException();
                }
                insertRevision(prepared);
                int updated = jdbc.update("""
                        UPDATE rg_world_catalog_heads
                           SET revision = ?, fingerprint = ?, record_fingerprint = ?,
                               payload_origin = ?, security_classification = ?, retention_expires_at = ?,
                               access_policy_ref = ?, approval_ref = ?, governance_fingerprint = ?,
                               canonical_json = ?, updated_at = CURRENT_TIMESTAMP
                         WHERE tenant_id = ? AND kind = ? AND asset_id = ? AND revision = ? AND fingerprint = ?
                        """, prepared.ref().revision(), prepared.ref().fingerprint(), prepared.recordFingerprint(),
                        prepared.metadata().payloadOrigin().name(), prepared.metadata().securityClassification().name(),
                        prepared.metadata().retentionExpiresAt(), prepared.metadata().accessPolicyRef(),
                        prepared.metadata().approvalRef(), prepared.metadata().governanceFingerprint(), prepared.json(),
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
        return findExactInternal(ref, this::resolveTrustedWorld);
    }

    @Override
    public Optional<GovernedCatalogRevision> findExact(GovernedResourceRef ref,
                                                       GovernedCatalogDependencyResolver dependencyResolver) {
        return findExactInternal(ref, dependencyResolver);
    }

    /** Metadata projection: this query deliberately does not select canonical_json. */
    @Override
    public Optional<GovernedAssetMetadata> findMetadata(GovernedResourceRef ref) {
        if (ref == null) {
            return Optional.empty();
        }
        StoredRow selected = selectStoredMetadataHead(ref.tenantId(), ref.kind(), ref.id())
                .filter(row -> row.revision() == ref.revision())
                .orElseGet(() -> selectStoredMetadataRevision(ref.tenantId(), ref.kind(), ref.id(), ref.revision())
                        .orElse(null));
        if (selected == null || !selected.fingerprint().equals(ref.fingerprint())) {
            return Optional.empty();
        }
        return Optional.of(metadataFor(selected).metadata());
    }

    private ResourceWorldModel resolveTrustedWorld(GovernedResourceRef ref) {
        return findExact(ref)
                .filter(entry -> entry.value() instanceof ResourceWorldModel)
                .map(entry -> (ResourceWorldModel) entry.value())
                .orElseThrow(GovernedCatalogIntegrityException::new);
    }

    private Optional<GovernedCatalogRevision> findExactInternal(
            GovernedResourceRef ref, GovernedCatalogDependencyResolver dependencyResolver) {
        if (ref == null) {
            return Optional.empty();
        }
        if (dependencyResolver == null) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.DEPENDENCY_RESOLVER_REQUIRED");
        }
        Optional<StoredRow> head = selectStoredHead(ref.tenantId(), ref.kind(), ref.id(), false);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        StoredRow selected = head.get();
        if (selected.revision() != ref.revision()) {
            selected = selectStoredRevision(ref.tenantId(), ref.kind(), ref.id(), ref.revision())
                    .orElse(null);
            if (selected == null) {
                return Optional.empty();
            }
        }
        preflight(selected);
        if (!selected.fingerprint().equals(ref.fingerprint())) {
            return Optional.empty();
        }
        CatalogRow current = readRow(selected, dependencyResolver);
        validateRow(current);
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
        return jdbc.<CatalogRow>query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint,
                       payload_origin, security_classification, retention_expires_at, access_policy_ref,
                       approval_ref, governance_fingerprint, canonical_json
                  FROM rg_world_catalog_revisions
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                 ORDER BY revision DESC, fingerprint DESC
                """, (resultSet, rowNum) -> readRow(readStoredRow(resultSet), this::resolveTrustedWorld),
                tenant.value(), kind.name(), id)
                .stream().map(row -> {
                    validateRow(row);
                    return row.value();
                }).toList();
    }

    private Prepared prepare(TrustedTenant tenant, GovernedCatalogKind kind, String id,
                             long revision, Object value, GovernedAssetMetadata suppliedMetadata) {
        if (tenant == null || kind == null || id == null || id.isBlank() || revision <= 0
                || !kind.accepts(value)) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_ASSET");
        }
        GovernedAssetGovernance governance = suppliedMetadata == null
                ? GovernedAssetGovernance.safeDefaults() : suppliedMetadata.governance();
        governance.validateForWrite(clock.instant());
        String normalizedId = id.trim();
        String json = codec.encode(tenant, kind, normalizedId, revision, value);
        GovernedResourceRef ref = new GovernedResourceRef(tenant, kind, normalizedId, revision,
                codec.fingerprint(value));
        String seal = GovernedAssetMetadata.fingerprint(ref, governance);
        return new Prepared(ref, json, codec.recordFingerprint(json),
                new GovernedAssetMetadata(governance, seal), value);
    }

    private void insertRevision(Prepared prepared) {
        int inserted = jdbc.update("""
                INSERT INTO rg_world_catalog_revisions
                    (tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint,
                     payload_origin, security_classification, retention_expires_at, access_policy_ref,
                     approval_ref, governance_fingerprint, canonical_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, prepared.ref().tenantId(), prepared.ref().kind().name(), prepared.ref().id(),
                prepared.ref().revision(), prepared.ref().fingerprint(), prepared.recordFingerprint(),
                prepared.metadata().payloadOrigin().name(), prepared.metadata().securityClassification().name(),
                prepared.metadata().retentionExpiresAt(), prepared.metadata().accessPolicyRef(),
                prepared.metadata().approvalRef(), prepared.metadata().governanceFingerprint(), prepared.json());
        if (inserted != 1) {
            throw new GovernedCatalogConflictException();
        }
    }

    private Optional<CatalogRow> selectHead(String tenant, GovernedCatalogKind kind, String id,
                                             boolean forUpdate,
                                             GovernedCatalogDependencyResolver dependencyResolver) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint,
                       payload_origin, security_classification, retention_expires_at, access_policy_ref,
                       approval_ref, governance_fingerprint, canonical_json
                  FROM rg_world_catalog_heads
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """ + lock, (resultSet, rowNum) -> readRow(readStoredRow(resultSet), dependencyResolver),
                tenant, kind.name(), id).stream().findFirst();
    }

    private Optional<StoredRow> selectStoredHead(String tenant, GovernedCatalogKind kind, String id,
                                                  boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint,
                       payload_origin, security_classification, retention_expires_at, access_policy_ref,
                       approval_ref, governance_fingerprint, canonical_json
                  FROM rg_world_catalog_heads
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """ + lock, (resultSet, rowNum) -> readStoredRow(resultSet),
                tenant, kind.name(), id).stream().findFirst();
    }

    private Optional<StoredRow> selectStoredRevision(String tenant, GovernedCatalogKind kind, String id,
                                                       long revision) {
        return jdbc.query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, record_fingerprint,
                       payload_origin, security_classification, retention_expires_at, access_policy_ref,
                       approval_ref, governance_fingerprint, canonical_json
                  FROM rg_world_catalog_revisions
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ? AND revision = ?
                """, (resultSet, rowNum) -> readStoredRow(resultSet),
                tenant, kind.name(), id, revision).stream().findFirst();
    }

    private Optional<StoredRow> selectStoredMetadataHead(String tenant, GovernedCatalogKind kind, String id) {
        return jdbc.query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, """ + GOVERNANCE_COLUMNS + """
                  FROM rg_world_catalog_heads
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ?
                """, (resultSet, rowNum) -> readStoredRow(resultSet, false), tenant, kind.name(), id)
                .stream().findFirst();
    }

    private Optional<StoredRow> selectStoredMetadataRevision(String tenant, GovernedCatalogKind kind,
                                                              String id, long revision) {
        return jdbc.query("""
                SELECT tenant_id, kind, asset_id, revision, fingerprint, """ + GOVERNANCE_COLUMNS + """
                  FROM rg_world_catalog_revisions
                 WHERE tenant_id = ? AND kind = ? AND asset_id = ? AND revision = ?
                """, (resultSet, rowNum) -> readStoredRow(resultSet, false), tenant, kind.name(), id, revision)
                .stream().findFirst();
    }

    private StoredRow readStoredRow(ResultSet resultSet) throws SQLException {
        return readStoredRow(resultSet, true);
    }

    private StoredRow readStoredRow(ResultSet resultSet, boolean includePayload) throws SQLException {
        java.sql.Timestamp timestamp = resultSet.getTimestamp("retention_expires_at");
        return new StoredRow(resultSet.getString("tenant_id"), resultSet.getString("kind"),
                resultSet.getString("asset_id"), resultSet.getLong("revision"),
                resultSet.getString("fingerprint"), includePayload ? resultSet.getString("record_fingerprint") : null,
                resultSet.getString("payload_origin"), resultSet.getString("security_classification"),
                timestamp == null ? null : timestamp.toInstant(), resultSet.getString("access_policy_ref"),
                resultSet.getString("approval_ref"), resultSet.getString("governance_fingerprint"),
                includePayload ? resultSet.getString("canonical_json") : null);
    }

    private CatalogRow readRow(StoredRow stored, GovernedCatalogDependencyResolver dependencyResolver) {
        try {
            GovernedCatalogKind kind = preflight(stored);
            CatalogMetadata metadata = metadataFor(stored);
            GovernedResourceRef ref = new GovernedResourceRef(stored.tenant(), kind, stored.id(),
                    stored.revision(), stored.fingerprint());
            Object value = codec.decode(stored.json(), new TrustedTenant(stored.tenant()), kind,
                    stored.id(), stored.revision(), stored.fingerprint(), dependencyResolver::resolve);
            return new CatalogRow(ref, new GovernedCatalogRevision(ref, value, metadata.metadata()));
        } catch (GovernedCatalogDependencyAbortException exception) {
            throw exception;
        } catch (GovernedCatalogIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    private GovernedCatalogKind preflight(StoredRow stored) {
        try {
            if (stored.recordFingerprint() == null
                    || !stored.recordFingerprint().equals(codec.recordFingerprint(stored.json()))) {
                throw new GovernedCatalogIntegrityException();
            }
            GovernedCatalogKind kind = GovernedCatalogKind.valueOf(stored.rawKind());
            codec.preflight(stored.json(), stored.tenant(), kind, stored.id(), stored.revision(),
                    stored.fingerprint());
            return kind;
        } catch (GovernedCatalogIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    private CatalogMetadata metadataFor(StoredRow stored) {
        try {
            GovernedCatalogKind kind = GovernedCatalogKind.valueOf(stored.rawKind());
            GovernedResourceRef ref = new GovernedResourceRef(stored.tenant(), kind, stored.id(),
                    stored.revision(), stored.fingerprint());
            GovernedAssetGovernance governance = new GovernedAssetGovernance(
                    GovernedPayloadOrigin.valueOf(stored.payloadOrigin()),
                    GovernedSecurityClassification.valueOf(stored.securityClassification()),
                    stored.retentionExpiresAt(), stored.accessPolicyRef(), stored.approvalRef());
            String expected = GovernedAssetMetadata.fingerprint(ref, governance);
            if (stored.governanceFingerprint() == null
                    || !stored.governanceFingerprint().equals(expected)) {
                throw new GovernedCatalogIntegrityException();
            }
            return new CatalogMetadata(governance,
                    new GovernedAssetMetadata(ref, governance, stored.governanceFingerprint()));
        } catch (GovernedCatalogIntegrityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    private void validateRow(CatalogRow row) {
        if (row == null || row.ref() == null || row.value() == null
                || !row.ref().kind().accepts(row.value().value())
                || !row.ref().fingerprint().equals(codec.fingerprint(row.value().value()))
                || row.value().metadata() == null) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    private record Prepared(GovernedResourceRef ref, String json, String recordFingerprint,
                            GovernedAssetMetadata metadata, Object value) {
    }

    private record StoredRow(String tenant, String rawKind, String id, long revision,
                             String fingerprint, String recordFingerprint, String payloadOrigin,
                             String securityClassification, Instant retentionExpiresAt,
                             String accessPolicyRef, String approvalRef, String governanceFingerprint,
                             String json) {
    }

    private record CatalogMetadata(GovernedAssetGovernance governance, GovernedAssetMetadata metadata) {
    }

    private record CatalogRow(GovernedResourceRef ref, GovernedCatalogRevision value) {
    }
}
