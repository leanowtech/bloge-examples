package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC Package store using portable H2/PostgreSQL DDL and full enterprise-scope keys. */
public class DatabaseDomainCapabilityPackageDraftRepository
        implements DomainCapabilityPackageDraftRepository {
    private static final int MAXIMUM_BYTES = 8 * 1_048_576;
    private static final String CREATE_CURRENT = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_drafts (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                package_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                stored_json TEXT NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, package_id)
            )
            """;
    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_draft_revisions (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                package_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                stored_json TEXT NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, package_id, revision)
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseDomainCapabilityPackageDraftRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_CURRENT);
        jdbc.execute(CREATE_HISTORY);
    }

    @Override
    public Optional<StoredDomainCapabilityPackageDraft> find(
            CapabilitySnapshot.Scope scope, String packageId) {
        return queryOne("""
                SELECT revision, stored_json FROM business_mirror_package_drafts
                WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                  AND environment_id = ? AND region_id = ? AND package_id = ?
                """, scope, packageId, 0);
    }

    @Override
    public Optional<StoredDomainCapabilityPackageDraft> findRevision(
            CapabilitySnapshot.Scope scope, String packageId, long revision) {
        if (revision < 1) {
            return Optional.empty();
        }
        return queryOne("""
                SELECT revision, stored_json FROM business_mirror_package_draft_revisions
                WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                  AND environment_id = ? AND region_id = ? AND package_id = ? AND revision = ?
                """, scope, packageId, revision);
    }

    @Override
    public List<StoredDomainCapabilityPackageDraft> revisions(
            CapabilitySnapshot.Scope scope, String packageId) {
        CapabilitySnapshot.Scope exact = requireScope(scope);
        String id = normalized(packageId);
        return jdbc.query("""
                        SELECT revision, stored_json FROM business_mirror_package_draft_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        ORDER BY revision DESC
                        """,
                (rs, row) -> read(rs.getString("stored_json"), exact, id, rs.getLong("revision")),
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), id).stream().flatMap(Optional::stream).toList();
    }

    @Override
    public List<StoredDomainCapabilityPackageDraft> list(
            CapabilitySnapshot.Scope scope, String afterPackageId, int limit) {
        CapabilitySnapshot.Scope exact = requireScope(scope);
        int bounded = Math.max(1, Math.min(201, limit));
        return jdbc.query("""
                        SELECT package_id, revision, stored_json FROM business_mirror_package_drafts
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id > ?
                        ORDER BY package_id ASC LIMIT ?
                        """,
                (rs, row) -> read(rs.getString("stored_json"), exact,
                        rs.getString("package_id"), rs.getLong("revision")),
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), normalized(afterPackageId), bounded)
                .stream().flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional
    public Optional<StoredDomainCapabilityPackageDraft> saveIfRevision(
            long expectedRevision, DomainCapabilityPackageDraft candidate, String actor) {
        if (candidate == null || expectedRevision < 0 || candidate.revision() != expectedRevision) {
            throw new IllegalArgumentException("Package and matching non-negative expected revision are required");
        }
        CapabilitySnapshot.Scope scope = candidate.scope();
        String id = candidate.packageId();
        StoredDomainCapabilityPackageDraft current = find(scope, id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.revision() != expectedRevision)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        DomainCapabilityPackageDraft storedDraft = candidate.withRevision(expectedRevision + 1);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, storedDraft, MAXIMUM_BYTES);
        StoredDomainCapabilityPackageDraft stored = new StoredDomainCapabilityPackageDraft(
                StoredDomainCapabilityPackageDraft.SCHEMA_VERSION, fingerprint, storedDraft,
                current == null ? now : current.createdAt(), now, required(actor, "actor"));
        String json = serialize(stored);
        if (current == null) {
            try {
                jdbc.update("""
                                INSERT INTO business_mirror_package_drafts (
                                    tenant_id, organization_id, project_id, environment_id, region_id,
                                    package_id, revision, stored_json
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                        scope.region(), id, stored.revision(), json);
            } catch (DuplicateKeyException concurrentCreate) {
                return Optional.empty();
            }
        } else {
            int updated = jdbc.update("""
                            UPDATE business_mirror_package_drafts SET revision = ?, stored_json = ?
                            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                              AND environment_id = ? AND region_id = ? AND package_id = ? AND revision = ?
                            """,
                    stored.revision(), json, scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), id, expectedRevision);
            if (updated == 0) {
                return Optional.empty();
            }
        }
        jdbc.update("""
                        INSERT INTO business_mirror_package_draft_revisions (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            package_id, revision, stored_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), id, stored.revision(), json);
        return Optional.of(stored);
    }

    private Optional<StoredDomainCapabilityPackageDraft> queryOne(
            String sql, CapabilitySnapshot.Scope scope, String packageId, long revision) {
        CapabilitySnapshot.Scope exact = requireScope(scope);
        String id = normalized(packageId);
        Object[] coordinates = revision > 0
                ? new Object[]{exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), id, revision}
                : new Object[]{exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), id};
        return jdbc.query(sql,
                        (rs, row) -> read(rs.getString("stored_json"), exact, id, rs.getLong("revision")),
                        coordinates)
                .stream().flatMap(Optional::stream).findFirst();
    }

    private Optional<StoredDomainCapabilityPackageDraft> read(
            String json, CapabilitySnapshot.Scope scope, String packageId, long revision) {
        try {
            StoredDomainCapabilityPackageDraft stored =
                    mapper.readValue(json, StoredDomainCapabilityPackageDraft.class);
            String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                    mapper, stored.draft(), MAXIMUM_BYTES);
            if (!stored.scope().equals(scope) || !stored.packageId().equals(packageId)
                    || stored.revision() != revision || !stored.draftFingerprint().equals(fingerprint)) {
                throw new IllegalStateException("Stored Package projection integrity check failed");
            }
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode stored Package revision", failure);
        }
    }

    private String serialize(StoredDomainCapabilityPackageDraft stored) {
        try {
            return mapper.writeValueAsString(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Package revision", failure);
        }
    }

    private static CapabilitySnapshot.Scope requireScope(CapabilitySnapshot.Scope scope) {
        return java.util.Objects.requireNonNull(scope, "scope");
    }

    private static String required(String value, String field) {
        String exact = normalized(value);
        if (exact.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
