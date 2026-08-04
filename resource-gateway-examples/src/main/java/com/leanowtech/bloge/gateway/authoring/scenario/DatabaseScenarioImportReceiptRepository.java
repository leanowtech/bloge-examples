package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;

/** JDBC receipt store that never persists the imported source snapshot. */
public final class DatabaseScenarioImportReceiptRepository implements ScenarioImportReceiptRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_scenario_import_receipts (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(255) NOT NULL,
                plan_fingerprint VARCHAR(80) NOT NULL,
                result_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, plan_fingerprint
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Creates the bounded payload-free receipt table. */
    public DatabaseScenarioImportReceiptRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    public Optional<ScenarioImportMaterializationResult> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String planFingerprint) {
        return jdbc.query("""
                        SELECT result_json
                          FROM visual_scenario_import_receipts
                         WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                           AND environment_id = ? AND region = ? AND plan_fingerprint = ?
                        """,
                (resultSet, row) -> decode(resultSet.getString(1)),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), planFingerprint)
                .stream().findFirst();
    }

    @Override
    public ScenarioImportMaterializationResult saveIfAbsent(
            ScenarioDraftSet.EnterpriseScope scope,
            String planFingerprint,
            ScenarioImportMaterializationResult result) {
        try {
            jdbc.update("""
                            INSERT INTO visual_scenario_import_receipts (
                                tenant_id, organization_id, project_id,
                                environment_id, region, plan_fingerprint, result_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), planFingerprint, encode(result));
            return result;
        } catch (DuplicateKeyException concurrentRetry) {
            return find(scope, planFingerprint).orElseThrow(() -> concurrentRetry);
        }
    }

    private String encode(ScenarioImportMaterializationResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Scenario import result is not serializable", exception);
        }
    }

    private ScenarioImportMaterializationResult decode(String json) {
        try {
            return mapper.readValue(json, ScenarioImportMaterializationResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Scenario import result is corrupt", exception);
        }
    }
}
