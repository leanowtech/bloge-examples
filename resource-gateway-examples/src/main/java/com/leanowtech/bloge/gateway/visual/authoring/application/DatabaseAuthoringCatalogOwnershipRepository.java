package com.leanowtech.bloge.gateway.visual.authoring.application;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-unique canonical library ownership authority for multi-replica commits.
 */
public class DatabaseAuthoringCatalogOwnershipRepository
        implements AuthoringCatalogOwnershipRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_library_authoring_catalog_ownership (
                library_id VARCHAR(255) PRIMARY KEY,
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                claimed_by VARCHAR(255) NOT NULL,
                claimed_at VARCHAR(64) NOT NULL
            )
            """;

    private final JdbcTemplate jdbc;

    public DatabaseAuthoringCatalogOwnershipRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    public Optional<Ownership> find(String libraryId) {
        return jdbc.query("""
                        SELECT library_id, tenant_id, organization_id, project_id,
                               environment_id, region, claimed_by, claimed_at
                        FROM visual_library_authoring_catalog_ownership
                        WHERE library_id = ?
                        """,
                (resultSet, rowNumber) -> new Ownership(
                        new AuthoringScope(
                                resultSet.getString("tenant_id"),
                                resultSet.getString("organization_id"),
                                resultSet.getString("project_id"),
                                resultSet.getString("environment_id"),
                                resultSet.getString("region")),
                        resultSet.getString("library_id"),
                        resultSet.getString("claimed_by"),
                        Instant.parse(resultSet.getString("claimed_at"))),
                normalized(libraryId)).stream().findFirst();
    }

    @Override
    @Transactional
    public Ownership claim(
            AuthoringScope scope,
            String libraryId,
            String actor,
            Instant claimedAt) {
        Ownership candidate = new Ownership(scope, libraryId, actor, claimedAt);
        try {
            jdbc.update("""
                            INSERT INTO visual_library_authoring_catalog_ownership (
                                library_id, tenant_id, organization_id, project_id,
                                environment_id, region, claimed_by, claimed_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    candidate.libraryId(),
                    candidate.scope().tenantId(),
                    candidate.scope().organizationId(),
                    candidate.scope().projectId(),
                    candidate.scope().environmentId(),
                    candidate.scope().region(),
                    candidate.claimedBy(),
                    candidate.claimedAt().toString());
            return candidate;
        } catch (DuplicateKeyException duplicate) {
            Ownership existing = find(candidate.libraryId())
                    .orElseThrow(() -> new AuthoringCatalogOwnershipConflictException(duplicate));
            if (!existing.scope().equals(candidate.scope())) {
                throw new AuthoringCatalogOwnershipConflictException(duplicate);
            }
            return existing;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
