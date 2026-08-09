package com.leanowtech.bloge.gateway.visual.draft;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** JDBC-backed, restart-safe Graph draft save command journal. */
public final class DatabaseGraphDraftSaveReceiptRepository implements GraphDraftSaveReceiptRepository {

    private static final String CREATE_LOCK_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_graph_draft_save_locks (
                tenant_id VARCHAR(255) NOT NULL,
                namespace_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                idempotency_key VARCHAR(160) NOT NULL,
                PRIMARY KEY (tenant_id, namespace_id, environment_id, idempotency_key)
            )
            """;
    private static final String CREATE_RECEIPT_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_graph_draft_save_receipts (
                tenant_id VARCHAR(255) NOT NULL,
                namespace_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                idempotency_key VARCHAR(160) NOT NULL,
                request_fingerprint VARCHAR(80) NOT NULL,
                draft_id VARCHAR(255) NOT NULL,
                draft_revision BIGINT NOT NULL,
                completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (tenant_id, namespace_id, environment_id, idempotency_key)
            )
            """;

    private final JdbcTemplate jdbc;
    private final GraphDraftRepository graphDrafts;

    public DatabaseGraphDraftSaveReceiptRepository(
            JdbcTemplate jdbc,
            GraphDraftRepository graphDrafts) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.graphDrafts = Objects.requireNonNull(graphDrafts, "graphDrafts");
        jdbc.execute(CREATE_LOCK_TABLE);
        jdbc.execute(CREATE_RECEIPT_TABLE);
    }

    @Override
    public <T> T withCommandLock(
            GraphDraftSaveScope scope,
            String idempotencyKey,
            Supplier<T> operation) {
        jdbc.update("""
                        MERGE INTO visual_graph_draft_save_locks (
                            tenant_id, namespace_id, environment_id, idempotency_key
                        ) KEY (tenant_id, namespace_id, environment_id, idempotency_key)
                        VALUES (?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.namespace(), scope.environment(), idempotencyKey);
        return operation.get();
    }

    @Override
    public Optional<StoredGraphDraftSaveReceipt> find(
            GraphDraftSaveScope scope,
            String idempotencyKey) {
        return jdbc.query("""
                        SELECT request_fingerprint, draft_id, draft_revision, completed_at
                          FROM visual_graph_draft_save_receipts
                         WHERE tenant_id = ? AND namespace_id = ?
                           AND environment_id = ? AND idempotency_key = ?
                        """,
                (resultSet, row) -> restore(
                        resultSet.getString("request_fingerprint"),
                        resultSet.getString("draft_id"),
                        resultSet.getLong("draft_revision"),
                        resultSet.getTimestamp("completed_at").toInstant()),
                scope.tenantId(), scope.namespace(), scope.environment(), idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public void save(
            GraphDraftSaveScope scope,
            String idempotencyKey,
            StoredGraphDraftSaveReceipt receipt) {
        jdbc.update("""
                        INSERT INTO visual_graph_draft_save_receipts (
                            tenant_id, namespace_id, environment_id, idempotency_key,
                            request_fingerprint, draft_id, draft_revision, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.namespace(), scope.environment(), idempotencyKey,
                receipt.requestFingerprint(), receipt.draft().draftId(), receipt.draft().revision(),
                receipt.completedAt());
    }

    private StoredGraphDraftSaveReceipt restore(
            String requestFingerprint,
            String draftId,
            long draftRevision,
            java.time.Instant completedAt) {
        GraphDraft draft = graphDrafts.findRevision(draftId, draftRevision)
                .orElseThrow(() -> new IllegalStateException(
                        "Stored Graph draft save receipt references a missing revision"));
        return new StoredGraphDraftSaveReceipt(
                StoredGraphDraftSaveReceipt.SCHEMA_VERSION,
                requestFingerprint,
                draft,
                completedAt);
    }
}
