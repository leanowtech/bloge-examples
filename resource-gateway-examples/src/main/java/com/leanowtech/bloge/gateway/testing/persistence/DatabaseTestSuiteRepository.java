package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuiteIntegrity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteIntegrityException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC suite registry with isolated legacy and enterprise-scoped immutable generations. */
public final class DatabaseTestSuiteRepository implements TestSuiteRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteProtocolCodec codec;

    /** Creates a suite repository over the independent test-runtime database. */
    public DatabaseTestSuiteRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.codec = new TestSuiteProtocolCodec(this.objectMapper);
    }

    /** Creates the legacy table and the additive v2 full-enterprise-scope table. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suites (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    suite_id VARCHAR(255) NOT NULL,
                    revision BIGINT NOT NULL,
                    fingerprint VARCHAR(80) NOT NULL,
                    suite_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_by VARCHAR(255) NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, suite_id, revision)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suites_v2 (
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region VARCHAR(64) NOT NULL,
                    suite_id VARCHAR(255) NOT NULL,
                    revision BIGINT NOT NULL,
                    fingerprint VARCHAR(80) NOT NULL,
                    binding_fingerprint VARCHAR(80) NOT NULL,
                    suite_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_by VARCHAR(255) NOT NULL,
                    PRIMARY KEY (
                        tenant_id, organization_id, project_id, environment_id, region,
                        suite_id, revision
                    )
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public StoredTestSuite create(StoredTestSuite suite) {
        StoredTestSuite requested = StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper, suite);
        if (requested.enterpriseScoped()) {
            return create(requested.scope(), requested);
        }
        Optional<StoredTestSuite> existing = find(requested.tenantId(), requested.environmentId(),
                requested.suiteId(), requested.revision());
        if (existing.isPresent()) {
            return equivalentOrConflict(existing.get(), requested);
        }
        try {
            jdbc.update("""
                    INSERT INTO rg_test_suites (
                        tenant_id, environment_id, suite_id, revision, fingerprint,
                        suite_json, created_at, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, requested.tenantId(), requested.environmentId(), requested.suiteId(),
                    requested.revision(), requested.fingerprint(), codec.write(requested.suite()),
                    Timestamp.from(requested.createdAt()), requested.createdBy());
            return requested;
        } catch (DataIntegrityViolationException race) {
            return find(requested.tenantId(), requested.environmentId(), requested.suiteId(),
                    requested.revision())
                    .map(value -> equivalentOrConflict(value, requested))
                    .orElseThrow(() -> race);
        }
    }

    /** {@inheritDoc} */
    @Override
    public StoredTestSuite create(
            TestingArtifactScope scope, StoredTestSuite suite) {
        StoredTestSuite requested = StoredTestSuiteIntegrity.verifiedSnapshot(
                objectMapper, suite, scope, suite.suiteId(), suite.revision());
        Optional<StoredTestSuite> existing =
                find(scope, requested.suiteId(), requested.revision());
        if (existing.isPresent()) {
            return equivalentOrConflict(existing.get(), requested);
        }
        try {
            jdbc.update("""
                    INSERT INTO rg_test_suites_v2 (
                        tenant_id, organization_id, project_id, environment_id, region,
                        suite_id, revision, fingerprint, binding_fingerprint,
                        suite_json, created_at, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), requested.suiteId(),
                    requested.revision(), requested.fingerprint(),
                    bindingFingerprint(scope, requested.suiteId(), requested.revision(),
                            requested.fingerprint()),
                    codec.write(requested.suite()),
                    Timestamp.from(requested.createdAt()), requested.createdBy());
            return requested;
        } catch (DataIntegrityViolationException race) {
            return find(scope, requested.suiteId(), requested.revision())
                    .map(value -> equivalentOrConflict(value, requested))
                    .orElseThrow(() -> race);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<StoredTestSuite> find(String tenantId, String environmentId,
                                          String suiteId, long revision) {
        List<StoredTestSuite> results = jdbc.query("""
                        SELECT tenant_id, environment_id, suite_id, revision, fingerprint,
                               suite_json, created_at, created_by
                        FROM rg_test_suites
                        WHERE tenant_id = ? AND environment_id = ?
                          AND suite_id = ? AND revision = ?
                        """, (rs, row) -> {
                    try {
                        return new StoredTestSuite("", rs.getString("tenant_id"),
                                rs.getString("environment_id"), rs.getString("suite_id"),
                                rs.getLong("revision"), rs.getString("fingerprint"),
                                codec.read(rs.getString("suite_json")),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getString("created_by"));
                    } catch (RuntimeException corrupt) {
                        throw new TestSuiteIntegrityException(corrupt);
                    }
                },
                tenantId, environmentId, suiteId, revision);
        return results.stream().findFirst().map(value ->
                StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper, value,
                        tenantId, environmentId, suiteId, revision));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<StoredTestSuite> find(
            TestingArtifactScope scope, String suiteId, long revision) {
        Objects.requireNonNull(scope, "scope");
        List<StoredTestSuite> results = jdbc.query("""
                        SELECT tenant_id, organization_id, project_id, environment_id, region,
                               suite_id, revision, fingerprint, binding_fingerprint,
                               suite_json, created_at, created_by
                        FROM rg_test_suites_v2
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ?
                          AND suite_id = ? AND revision = ?
                        """, (rs, row) -> {
                    try {
                        StoredTestSuite stored = new StoredTestSuite("", rs.getString("tenant_id"),
                                rs.getString("organization_id"), rs.getString("project_id"),
                                rs.getString("environment_id"), rs.getString("region"),
                                rs.getString("suite_id"), rs.getLong("revision"),
                                rs.getString("fingerprint"),
                                codec.read(rs.getString("suite_json")),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getString("created_by"));
                        String binding = bindingFingerprint(
                                stored.scope(), stored.suiteId(), stored.revision(),
                                stored.fingerprint());
                        if (!Objects.equals(binding, rs.getString("binding_fingerprint"))) {
                            throw new TestSuiteIntegrityException();
                        }
                        return stored;
                    } catch (RuntimeException corrupt) {
                        throw new TestSuiteIntegrityException(corrupt);
                    }
                }, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), suiteId, revision);
        return results.stream().findFirst().map(value ->
                StoredTestSuiteIntegrity.verifiedSnapshot(
                        objectMapper, value, scope, suiteId, revision));
    }

    private StoredTestSuite equivalentOrConflict(StoredTestSuite existing,
                                                 StoredTestSuite requested) {
        if (!existing.fingerprint().equals(requested.fingerprint())) {
            throw new TestSuiteConflictException(
                    "Test-suite revision already exists with different immutable content");
        }
        return StoredTestSuiteIntegrity.verifiedSnapshot(objectMapper, existing, requested);
    }

    private String bindingFingerprint(
            TestingArtifactScope scope, String suiteId, long revision, String fingerprint) {
        return ProtocolFingerprint.of(objectMapper, java.util.Map.of(
                "scope", scope,
                "suiteId", suiteId,
                "revision", revision,
                "contentFingerprint", fingerprint));
    }

}
