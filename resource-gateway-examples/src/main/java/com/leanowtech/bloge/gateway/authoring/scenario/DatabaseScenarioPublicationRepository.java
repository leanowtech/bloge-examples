package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * H2-backed publication saga repository with full-scope keys and immutable transition history.
 */
public class DatabaseScenarioPublicationRepository implements ScenarioPublicationRepository {

    private static final int MAX_REPORT_BYTES = 1_048_576;
    private static final String CREATE_CURRENT = """
            CREATE TABLE IF NOT EXISTS visual_scenario_publications (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(128) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                publication_id VARCHAR(255) NOT NULL,
                state_version BIGINT NOT NULL,
                stored_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    publication_id
                )
            )
            """;
    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS visual_scenario_publication_history (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(128) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                publication_id VARCHAR(255) NOT NULL,
                state_version BIGINT NOT NULL,
                stored_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    publication_id, state_version
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc application database access
     * @param objectMapper canonical report serializer
     */
    public DatabaseScenarioPublicationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Creates current-state and immutable-history tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_CURRENT);
        jdbc.execute(CREATE_HISTORY);
    }

    @Override
    public Optional<StoredScenarioPublication> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String publicationId) {
        return jdbc.query("""
                        SELECT stored_json
                        FROM visual_scenario_publications
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND publication_id = ?
                        """,
                (rs, row) -> read(rs.getString("stored_json")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), normalized(publicationId))
                .stream().flatMap(Optional::stream).findFirst();
    }

    @Override
    public List<StoredScenarioPublication> history(
            ScenarioDraftSet.EnterpriseScope scope,
            String publicationId) {
        return jdbc.query("""
                        SELECT stored_json
                        FROM visual_scenario_publication_history
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND publication_id = ?
                        ORDER BY state_version
                        """,
                (rs, row) -> read(rs.getString("stored_json")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), normalized(publicationId))
                .stream().flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional
    public Optional<StoredScenarioPublication> saveIfVersion(
            long expectedStateVersion,
            ScenarioPublicationReport report) {
        if (report == null || expectedStateVersion < 0) {
            throw new IllegalArgumentException("Publication report and non-negative state version are required");
        }
        ScenarioDraftSet.EnterpriseScope scope = report.scope();
        String id = report.publicationId();
        StoredScenarioPublication current = find(scope, id).orElse(null);
        if ((current == null && expectedStateVersion != 0)
                || (current != null && current.stateVersion() != expectedStateVersion)) {
            return Optional.empty();
        }
        if (current != null && !current.report().source().equals(report.source())) {
            throw new IllegalArgumentException("Publication identity cannot change its source coordinate");
        }
        long nextVersion = expectedStateVersion + 1;
        String fingerprint = ProtocolFingerprint.ofBounded(
                objectMapper, report, MAX_REPORT_BYTES);
        StoredScenarioPublication stored = new StoredScenarioPublication(
                "", nextVersion, fingerprint, report);
        String json = serialize(stored);
        if (current == null) {
            try {
                jdbc.update("""
                                INSERT INTO visual_scenario_publications (
                                    tenant_id, organization_id, project_id, environment_id,
                                    region_id, publication_id, state_version, stored_json
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        scope.tenantId(), scope.organizationId(), scope.projectId(),
                        scope.environment(), scope.region(), id, nextVersion, json);
            } catch (DuplicateKeyException concurrentCreate) {
                return Optional.empty();
            }
        } else {
            int updated = jdbc.update("""
                            UPDATE visual_scenario_publications
                            SET state_version = ?, stored_json = ?
                            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                              AND environment_id = ? AND region_id = ? AND publication_id = ?
                              AND state_version = ?
                            """,
                    nextVersion, json,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), id, expectedStateVersion);
            if (updated == 0) {
                return Optional.empty();
            }
        }
        jdbc.update("""
                        INSERT INTO visual_scenario_publication_history (
                            tenant_id, organization_id, project_id, environment_id,
                            region_id, publication_id, state_version, stored_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), id, nextVersion, json);
        return Optional.of(stored);
    }

    private Optional<StoredScenarioPublication> read(String json) {
        try {
            StoredScenarioPublication stored =
                    objectMapper.readValue(json, StoredScenarioPublication.class);
            String fingerprint = ProtocolFingerprint.ofBounded(
                    objectMapper, stored.report(), MAX_REPORT_BYTES);
            return fingerprint.equals(stored.fingerprint()) ? Optional.of(stored) : Optional.empty();
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private String serialize(StoredScenarioPublication value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize Scenario publication report", failure);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
