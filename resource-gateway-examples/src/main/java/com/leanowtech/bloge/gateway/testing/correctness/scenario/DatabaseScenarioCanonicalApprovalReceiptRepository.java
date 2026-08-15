package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** JDBC Scenario approval receipt store that excludes raw keys and business payloads. */
public final class DatabaseScenarioCanonicalApprovalReceiptRepository
        implements ScenarioCanonicalApprovalReceiptRepository {

    private static final String COMMAND_KIND = "SCENARIO_CANONICAL_APPROVE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseScenarioCanonicalApprovalReceiptRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<ScenarioCanonicalApprovalReceipt> find(
            EnterpriseScope scope,
            String idempotencyKeyFingerprint
    ) {
        Objects.requireNonNull(scope, "scope");
        String key = exact(idempotencyKeyFingerprint);
        return jdbc.query("""
                        SELECT * FROM rg_correctness_command_receipts
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND command_kind = ? AND idempotency_key_fingerprint = ?
                        """,
                (result, row) -> readAndVerify(result, scope, key),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), COMMAND_KIND, key)
                .stream().findFirst();
    }

    @Override
    public boolean saveIfAbsent(ScenarioCanonicalApprovalReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        var setRef = receipt.caseRef().scenarioDraftSetRef();
        try {
            jdbc.update("""
                            INSERT INTO rg_correctness_command_receipts (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                command_kind, idempotency_key_fingerprint, request_fingerprint,
                                result_kind, result_id, result_revision, result_fingerprint,
                                actor_id, receipt_json, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SCENARIO_DRAFT_SET',
                                      ?, ?, ?, ?, ?, ?)
                            """,
                    receipt.scope().tenantId(), receipt.scope().organizationId(),
                    receipt.scope().projectId(), receipt.scope().environment(),
                    receipt.scope().region(), COMMAND_KIND,
                    receipt.idempotencyKeyFingerprint(), receipt.requestFingerprint(),
                    setRef.id(), setRef.revision(), setRef.fingerprint(),
                    receipt.actorId(), encode(receipt), receipt.createdAt());
            return true;
        } catch (DuplicateKeyException concurrentReplay) {
            return false;
        }
    }

    private ScenarioCanonicalApprovalReceipt readAndVerify(
            ResultSet result,
            EnterpriseScope scope,
            String key
    ) throws SQLException {
        ScenarioCanonicalApprovalReceipt receipt = decode(result.getString("receipt_json"));
        var setRef = receipt.caseRef().scenarioDraftSetRef();
        if (!receipt.scope().equals(scope)
                || !receipt.idempotencyKeyFingerprint().equals(key)
                || !COMMAND_KIND.equals(result.getString("command_kind"))
                || !receipt.requestFingerprint().equals(result.getString("request_fingerprint"))
                || !"SCENARIO_DRAFT_SET".equals(result.getString("result_kind"))
                || !setRef.id().equals(result.getString("result_id"))
                || setRef.revision() != result.getLong("result_revision")
                || !setRef.fingerprint().equals(result.getString("result_fingerprint"))
                || !receipt.actorId().equals(result.getString("actor_id"))) {
            throw new IllegalStateException("Scenario approval receipt integrity check failed");
        }
        return receipt;
    }

    private ScenarioCanonicalApprovalReceipt decode(String json) {
        try {
            return mapper.readValue(json, ScenarioCanonicalApprovalReceipt.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Scenario approval receipt", failure);
        }
    }

    private String encode(ScenarioCanonicalApprovalReceipt receipt) {
        try {
            return mapper.writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Scenario approval receipt", failure);
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
