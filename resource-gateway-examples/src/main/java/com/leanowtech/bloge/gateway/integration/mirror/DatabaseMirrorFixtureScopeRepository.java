package com.leanowtech.bloge.gateway.integration.mirror;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** H2-backed append-only full-scope authorization index for mirror fixture revisions. */
public class DatabaseMirrorFixtureScopeRepository implements MirrorFixtureScopeRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mirror_fixture_scope_bindings (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                fixture_bundle_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                fixture_fingerprint VARCHAR(71) NOT NULL,
                bound_at VARCHAR(64) NOT NULL,
                bound_by VARCHAR(255) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    fixture_bundle_id, revision
                )
            )
            """;

    private final JdbcTemplate jdbc;

    /** Creates the payload-free scope index over the isolated mirror database boundary. */
    public DatabaseMirrorFixtureScopeRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Creates the append-only table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public MirrorFixtureScopeBinding create(MirrorFixtureScopeBinding binding) {
        Objects.requireNonNull(binding, "binding");
        CapabilitySnapshot.Scope scope = binding.scope();
        MirrorArtifactRef ref = binding.fixtureBundleRef();
        Optional<MirrorFixtureScopeBinding> existing = find(scope, ref.id(), ref.revision());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), binding);
        }
        try {
            jdbc.update("""
                    INSERT INTO mirror_fixture_scope_bindings (
                        tenant_id, organization_id, project_id, environment_id, region,
                        fixture_bundle_id, revision, fixture_fingerprint, bound_at, bound_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), ref.id(), ref.revision(),
                    ref.fingerprint(), binding.boundAt().toString(), binding.boundBy());
            return binding;
        } catch (DuplicateKeyException duplicate) {
            return find(scope, ref.id(), ref.revision())
                    .map(existingBinding -> sameOrConflict(existingBinding, binding))
                    .orElseThrow(() -> duplicate);
        }
    }

    @Override
    public Optional<MirrorFixtureScopeBinding> find(
            CapabilitySnapshot.Scope scope, String fixtureBundleId, long revision) {
        Objects.requireNonNull(scope, "scope");
        String id = required(fixtureBundleId, "fixtureBundleId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        List<MirrorFixtureScopeBinding> results = jdbc.query("""
                        SELECT fixture_bundle_id, revision, fixture_fingerprint, bound_at, bound_by
                        FROM mirror_fixture_scope_bindings
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ?
                          AND fixture_bundle_id = ? AND revision = ?
                        """, (rs, rowNumber) -> new MirrorFixtureScopeBinding(scope,
                        new MirrorArtifactRef("FIXTURE_BUNDLE",
                                rs.getString("fixture_bundle_id"), rs.getLong("revision"),
                                rs.getString("fixture_fingerprint")),
                        java.time.Instant.parse(rs.getString("bound_at")),
                        rs.getString("bound_by")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), id, revision);
        return results.stream().findFirst();
    }

    private static MirrorFixtureScopeBinding sameOrConflict(
            MirrorFixtureScopeBinding existing,
            MirrorFixtureScopeBinding requested) {
        if (existing.fixtureBundleRef().equals(requested.fixtureBundleRef())) {
            return existing;
        }
        throw new IllegalArgumentException(
                "Mirror fixture scope already binds different immutable content");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
