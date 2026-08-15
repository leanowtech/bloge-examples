package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** JDBC Oracle approval receipt store that excludes raw keys and review comments. */
public final class DatabaseOracleApprovalReceiptRepository
        implements OracleApprovalReceiptRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseOracleApprovalReceiptRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<OracleApprovalReceipt> find(
            EnterpriseScope scope,
            String idempotencyKeyFingerprint
    ) {
        Objects.requireNonNull(scope, "scope");
        String key = exact(idempotencyKeyFingerprint);
        return jdbc.query("""
                        SELECT * FROM rg_correctness_command_receipts
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND command_kind = 'ORACLE_APPROVE'
                          AND idempotency_key_fingerprint = ?
                        """,
                (result, row) -> readAndVerify(result, scope, key),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), key).stream().findFirst();
    }

    @Override
    public boolean saveIfAbsent(OracleApprovalReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        try {
            jdbc.update("""
                            INSERT INTO rg_correctness_command_receipts (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                command_kind, idempotency_key_fingerprint, request_fingerprint,
                                result_kind, result_id, result_revision, result_fingerprint,
                                actor_id, receipt_json, created_at
                            ) VALUES (?, ?, ?, ?, ?, 'ORACLE_APPROVE', ?, ?,
                                      'ORACLE', ?, ?, ?, ?, ?, ?)
                            """,
                    receipt.scope().tenantId(), receipt.scope().organizationId(),
                    receipt.scope().projectId(), receipt.scope().environment(),
                    receipt.scope().region(), receipt.idempotencyKeyFingerprint(),
                    receipt.requestFingerprint(), receipt.oracleRef().id(),
                    receipt.oracleRef().revision(), receipt.oracleRef().fingerprint(),
                    receipt.actorId(), encode(receipt), receipt.createdAt());
            return true;
        } catch (DuplicateKeyException concurrentReplay) {
            return false;
        }
    }

    private OracleApprovalReceipt readAndVerify(
            ResultSet result,
            EnterpriseScope scope,
            String key
    ) throws SQLException {
        OracleApprovalReceipt receipt = decode(result.getString("receipt_json"));
        if (!receipt.scope().equals(scope)
                || !receipt.idempotencyKeyFingerprint().equals(key)
                || !"ORACLE_APPROVE".equals(result.getString("command_kind"))
                || !receipt.requestFingerprint().equals(result.getString("request_fingerprint"))
                || !"ORACLE".equals(result.getString("result_kind"))
                || !receipt.oracleRef().id().equals(result.getString("result_id"))
                || receipt.oracleRef().revision() != result.getLong("result_revision")
                || !receipt.oracleRef().fingerprint().equals(
                        result.getString("result_fingerprint"))
                || !receipt.actorId().equals(result.getString("actor_id"))) {
            throw new IllegalStateException("Oracle approval receipt integrity check failed");
        }
        return receipt;
    }

    private OracleApprovalReceipt decode(String json) {
        try {
            return mapper.readValue(json, OracleApprovalReceipt.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Oracle approval receipt", failure);
        }
    }

    private String encode(OracleApprovalReceipt receipt) {
        try {
            return mapper.writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Oracle approval receipt", failure);
        }
    }

    private static String exact(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact idempotency key fingerprint is required");
        }
        return normalized;
    }
}
