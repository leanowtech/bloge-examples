package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleConflictException;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundleIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** JDBC fixture registry using immutable tenant/environment/id/revision keys. */
public final class DatabaseFixtureBundleRepository implements FixtureBundleRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DatabaseFixtureBundleRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_fixture_bundles (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    fixture_bundle_id VARCHAR(255) NOT NULL,
                    revision BIGINT NOT NULL,
                    fingerprint VARCHAR(80) NOT NULL,
                    bundle_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_by VARCHAR(255) NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, fixture_bundle_id, revision)
                )
                """);
    }

    @Override
    public StoredFixtureBundle create(StoredFixtureBundle fixture) {
        StoredFixtureBundleIntegrity.verify(objectMapper, fixture);
        Optional<StoredFixtureBundle> existing = find(fixture.tenantId(), fixture.environmentId(),
                fixture.fixtureBundleId(), fixture.revision());
        if (existing.isPresent()) {
            return equivalentOrConflict(existing.get(), fixture);
        }
        try {
            jdbc.update("""
                    INSERT INTO rg_test_fixture_bundles (
                        tenant_id, environment_id, fixture_bundle_id, revision, fingerprint,
                        bundle_json, created_at, created_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, fixture.tenantId(), fixture.environmentId(), fixture.fixtureBundleId(),
                    fixture.revision(), fixture.fingerprint(), write(fixture.bundle()),
                    Timestamp.from(fixture.createdAt()), fixture.createdBy());
            return fixture;
        } catch (DataIntegrityViolationException race) {
            return find(fixture.tenantId(), fixture.environmentId(), fixture.fixtureBundleId(), fixture.revision())
                    .map(value -> equivalentOrConflict(value, fixture))
                    .orElseThrow(() -> race);
        }
    }

    @Override
    public Optional<StoredFixtureBundle> find(String tenantId, String environmentId,
                                              String fixtureBundleId, long revision) {
        List<StoredFixtureBundle> results = jdbc.query("""
                        SELECT tenant_id, environment_id, fixture_bundle_id, revision, fingerprint,
                               bundle_json, created_at, created_by
                        FROM rg_test_fixture_bundles
                        WHERE tenant_id = ? AND environment_id = ?
                          AND fixture_bundle_id = ? AND revision = ?
                        """, (rs, row) -> new StoredFixtureBundle("",
                        rs.getString("tenant_id"), rs.getString("environment_id"),
                        rs.getString("fixture_bundle_id"), rs.getLong("revision"),
                        rs.getString("fingerprint"), read(rs.getString("bundle_json"), FixtureBundle.class),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("created_by")),
                tenantId, environmentId, fixtureBundleId, revision);
        return results.stream().map(stored -> StoredFixtureBundleIntegrity.verify(
                objectMapper, stored)).findFirst();
    }

    private static StoredFixtureBundle equivalentOrConflict(StoredFixtureBundle existing,
                                                              StoredFixtureBundle requested) {
        if (!existing.fingerprint().equals(requested.fingerprint())) {
            throw new FixtureBundleConflictException(
                    "Fixture revision already exists with different immutable content");
        }
        return existing;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize fixture bundle", failure);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored fixture bundle is corrupt", failure);
        }
    }
}
