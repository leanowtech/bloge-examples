package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;

/** JDBC repository with scope isolation, request idempotency, and optimistic replacement. */
public final class DatabaseTableSuiteRunRepository implements TableSuiteRunRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_table_suite_runs (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(255) NOT NULL,
                batch_id VARCHAR(96) NOT NULL,
                request_id VARCHAR(128) NOT NULL,
                revision BIGINT NOT NULL,
                batch_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, batch_id
                ),
                UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, request_id
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Creates the durable batch table. */
    public DatabaseTableSuiteRunRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    public Optional<TableSuiteRunBatch> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String batchId) {
        return query(scope, "batch_id", batchId);
    }

    @Override
    public Optional<TableSuiteRunBatch> findByRequest(
            ScenarioDraftSet.EnterpriseScope scope,
            String requestId) {
        return query(scope, "request_id", requestId);
    }

    @Override
    public TableSuiteRunBatch create(TableSuiteRunBatch batch) {
        ScenarioDraftSet.EnterpriseScope scope = batch.scope();
        try {
            jdbc.update("""
                            INSERT INTO visual_table_suite_runs (
                                tenant_id, organization_id, project_id, environment_id, region,
                                batch_id, request_id, revision, batch_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), batch.batchId(), batch.requestId(),
                    batch.revision(), encode(batch));
            return batch;
        } catch (DuplicateKeyException duplicate) {
            return findByRequest(scope, batch.requestId()).orElseThrow(() -> duplicate);
        }
    }

    @Override
    public boolean replace(TableSuiteRunBatch batch, long expectedRevision) {
        ScenarioDraftSet.EnterpriseScope scope = batch.scope();
        return jdbc.update("""
                        UPDATE visual_table_suite_runs
                           SET revision = ?, batch_json = ?
                         WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                           AND environment_id = ? AND region = ? AND batch_id = ?
                           AND revision = ?
                        """,
                batch.revision(), encode(batch),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), batch.batchId(), expectedRevision) == 1;
    }

    private Optional<TableSuiteRunBatch> query(
            ScenarioDraftSet.EnterpriseScope scope,
            String coordinate,
            String value) {
        if (!"batch_id".equals(coordinate) && !"request_id".equals(coordinate)) {
            throw new IllegalArgumentException("Unsupported table-run coordinate");
        }
        String sql = """
                SELECT batch_json
                  FROM visual_table_suite_runs
                 WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                   AND environment_id = ? AND region = ? AND %s = ?
                """.formatted(coordinate);
        return jdbc.query(sql, (resultSet, row) -> decode(resultSet.getString(1)),
                        scope.tenantId(), scope.organizationId(), scope.projectId(),
                        scope.environment(), scope.region(), value)
                .stream().findFirst();
    }

    private String encode(TableSuiteRunBatch batch) {
        try {
            return mapper.writeValueAsString(batch);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Table suite batch is not serializable", exception);
        }
    }

    private TableSuiteRunBatch decode(String json) {
        try {
            return mapper.readValue(json, TableSuiteRunBatch.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored table suite batch is corrupt", exception);
        }
    }
}
