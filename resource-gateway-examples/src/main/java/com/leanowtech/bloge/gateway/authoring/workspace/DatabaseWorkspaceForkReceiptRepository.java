package com.leanowtech.bloge.gateway.authoring.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;

/** JDBC-backed, scope-isolated Workspace fork idempotency receipts. */
public final class DatabaseWorkspaceForkReceiptRepository implements WorkspaceForkReceiptRepository {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_workspace_fork_receipts (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(255) NOT NULL,
                idempotency_key VARCHAR(160) NOT NULL,
                request_fingerprint VARCHAR(80) NOT NULL,
                receipt_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, idempotency_key
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseWorkspaceForkReceiptRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    public Optional<StoredWorkspaceForkReceipt> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String idempotencyKey) {
        return jdbc.query("""
                        SELECT request_fingerprint, receipt_json
                          FROM visual_workspace_fork_receipts
                         WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                           AND environment_id = ? AND region = ? AND idempotency_key = ?
                        """,
                (resultSet, row) -> new StoredWorkspaceForkReceipt(
                        resultSet.getString(1), decode(resultSet.getString(2))),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public StoredWorkspaceForkReceipt saveIfAbsent(
            ScenarioDraftSet.EnterpriseScope scope,
            String idempotencyKey,
            StoredWorkspaceForkReceipt receipt) {
        try {
            jdbc.update("""
                            INSERT INTO visual_workspace_fork_receipts (
                                tenant_id, organization_id, project_id,
                                environment_id, region, idempotency_key,
                                request_fingerprint, receipt_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), idempotencyKey,
                    receipt.requestFingerprint(), encode(receipt.receipt()));
            return receipt;
        } catch (DuplicateKeyException concurrentRetry) {
            return find(scope, idempotencyKey).orElseThrow(() -> concurrentRetry);
        }
    }

    private String encode(WorkspaceForkReceipt receipt) {
        try {
            return mapper.writeValueAsString(receipt);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Workspace fork receipt is not serializable", exception);
        }
    }

    private WorkspaceForkReceipt decode(String json) {
        try {
            return mapper.readValue(json, WorkspaceForkReceipt.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Workspace fork receipt is corrupt", exception);
        }
    }
}
