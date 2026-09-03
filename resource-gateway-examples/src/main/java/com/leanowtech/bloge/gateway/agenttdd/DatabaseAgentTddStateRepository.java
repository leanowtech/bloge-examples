package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * JDBC-backed Agent TDD overlay and idempotency repository.
 *
 * <p>The tables self-bootstrap only for the embedded H2 example. External databases must apply the
 * versioned migration first and are checked at startup without runtime DDL. Exact response JSON is
 * retained so a retried write cannot observe a later asset revision and mistake it for the original
 * result.</p>
 */
@Repository
public class DatabaseAgentTddStateRepository implements AgentTddStateRepository {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private boolean postgres;

    /** Creates the repository from the application JDBC boundary. */
    public DatabaseAgentTddStateRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Creates additive H2 tables or verifies that an external migration has already run. */
    @PostConstruct
    void init() {
        String product = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        postgres = product != null && "PostgreSQL".equalsIgnoreCase(product.trim());
        if (product == null || !"H2".equalsIgnoreCase(product.trim())) {
            verifyExternalSchema();
            return;
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_tdd_assets (
                    scope_key VARCHAR(1024) NOT NULL,
                    asset_kind VARCHAR(64) NOT NULL,
                    asset_ref VARCHAR(255) NOT NULL,
                    revision BIGINT NOT NULL,
                    fingerprint VARCHAR(255) NOT NULL,
                    state_json CLOB NOT NULL,
                    updated_at VARCHAR(64) NOT NULL,
                    PRIMARY KEY (scope_key, asset_kind, asset_ref)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_tdd_idempotency (
                    scope_key VARCHAR(1024) NOT NULL,
                    operation VARCHAR(128) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(255) NOT NULL,
                    response_json CLOB NOT NULL,
                    completed BOOLEAN DEFAULT TRUE NOT NULL,
                    created_at VARCHAR(64) NOT NULL,
                    PRIMARY KEY (scope_key, operation, idempotency_key)
                )
                """);
        jdbc.execute("ALTER TABLE agent_tdd_idempotency ADD COLUMN IF NOT EXISTS completed BOOLEAN DEFAULT TRUE NOT NULL");
    }

    private void verifyExternalSchema() {
        jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_tdd_assets
                 WHERE scope_key = ? AND asset_kind = ? AND asset_ref = ? AND revision < 0
                """, Long.class, "", "", "");
        jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_tdd_idempotency
                 WHERE scope_key = ? AND operation = ? AND idempotency_key = ? AND completed = FALSE
                """, Long.class, "", "");
    }

    @Override
    public Optional<AgentTddStoredAsset> find(String scopeKey, String kind, String assetRef) {
        List<AgentTddStoredAsset> rows = jdbc.query("""
                        SELECT revision, fingerprint, state_json, updated_at
                          FROM agent_tdd_assets
                         WHERE scope_key = ? AND asset_kind = ? AND asset_ref = ?
                        """, (rs, row) -> new AgentTddStoredAsset(scopeKey, kind, assetRef,
                        rs.getLong("revision"), rs.getString("fingerprint"),
                        read(rs.getString("state_json")), Instant.parse(rs.getString("updated_at"))),
                scopeKey, kind, assetRef);
        return rows.stream().findFirst();
    }

    @Override
    public List<AgentTddStoredAsset> list(String scopeKey, String kind) {
        return jdbc.query("""
                        SELECT asset_ref, revision, fingerprint, state_json, updated_at
                          FROM agent_tdd_assets
                         WHERE scope_key = ? AND asset_kind = ?
                         ORDER BY asset_ref
                        """, (rs, row) -> new AgentTddStoredAsset(scopeKey, kind,
                        rs.getString("asset_ref"), rs.getLong("revision"), rs.getString("fingerprint"),
                        read(rs.getString("state_json")), Instant.parse(rs.getString("updated_at"))),
                scopeKey, kind);
    }

    @Override
    @Transactional
    public AgentTddStoredAsset save(String scopeKey,
                                    String kind,
                                    String assetRef,
                                    JsonNode data) {
        long expectedRevision = find(scopeKey, kind, assetRef).map(AgentTddStoredAsset::revision).orElse(0L);
        return saveIfRevision(scopeKey, kind, assetRef, expectedRevision, data);
    }

    @Override
    @Transactional
    public AgentTddStoredAsset saveIfRevision(String scopeKey,
                                              String kind,
                                              String assetRef,
                                              long expectedRevision,
                                              JsonNode data) {
        long revision = expectedRevision + 1;
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, data, MAX_BYTES);
        Instant now = Instant.now();
        String json = write(data);
        if (expectedRevision == 0) {
            try {
                jdbc.update("""
                        INSERT INTO agent_tdd_assets
                            (scope_key, asset_kind, asset_ref, revision, fingerprint, state_json, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, scopeKey, kind, assetRef, revision, fingerprint, json, now.toString());
            } catch (DuplicateKeyException conflict) {
                throw staleRevision();
            }
        } else {
            int updated = jdbc.update("""
                    UPDATE agent_tdd_assets
                       SET revision = ?, fingerprint = ?, state_json = ?, updated_at = ?
                     WHERE scope_key = ? AND asset_kind = ? AND asset_ref = ? AND revision = ?
                    """, revision, fingerprint, json, now.toString(), scopeKey, kind, assetRef, expectedRevision);
            if (updated != 1) throw staleRevision();
        }
        return new AgentTddStoredAsset(scopeKey, kind, assetRef, revision, fingerprint, data, now);
    }

    /** Runs a multi-asset state transition in the caller-visible database transaction. */
    @Override
    @Transactional
    public <T> T executeAtomically(Supplier<T> action) {
        return action.get();
    }

    /** Locks one exact state row so a concurrent revision cannot commit before the evidence unit. */
    @Override
    @Transactional
    public AgentTddStoredAsset lockRevision(String scopeKey,
                                            String kind,
                                            String assetRef,
                                            long expectedRevision) {
        List<AgentTddStoredAsset> rows = jdbc.query("""
                        SELECT revision, fingerprint, state_json, updated_at
                          FROM agent_tdd_assets
                         WHERE scope_key = ? AND asset_kind = ? AND asset_ref = ?
                         FOR UPDATE
                        """, (rs, row) -> new AgentTddStoredAsset(scopeKey, kind, assetRef,
                        rs.getLong("revision"), rs.getString("fingerprint"),
                        read(rs.getString("state_json")), Instant.parse(rs.getString("updated_at"))),
                scopeKey, kind, assetRef);
        return rows.stream().filter(asset -> asset.revision() == expectedRevision).findFirst()
                .orElseThrow(DatabaseAgentTddStateRepository::staleRevision);
    }

    @Override
    public Optional<JsonNode> replay(String scopeKey,
                                     String operation,
                                     String idempotencyKey,
                                     String requestFingerprint) {
        List<Replay> rows = jdbc.query("""
                        SELECT request_fingerprint, response_json
                          FROM agent_tdd_idempotency
                         WHERE scope_key = ? AND operation = ? AND idempotency_key = ? AND completed = TRUE
                        """, (rs, row) -> new Replay(rs.getString("request_fingerprint"),
                        read(rs.getString("response_json"))), scopeKey, operation, idempotencyKey);
        if (rows.isEmpty()) return Optional.empty();
        Replay replay = rows.getFirst();
        if (!replay.fingerprint().equals(requestFingerprint)) {
            throw new AgentTddToolException("IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for different request material.");
        }
        return Optional.of(replay.response());
    }

    @Override
    public synchronized void record(String scopeKey,
                                    String operation,
                                    String idempotencyKey,
                                    String requestFingerprint,
                                    JsonNode response) {
        Optional<JsonNode> existing = replay(scopeKey, operation, idempotencyKey, requestFingerprint);
        if (existing.isPresent()) return;
        jdbc.update("""
                INSERT INTO agent_tdd_idempotency
                    (scope_key, operation, idempotency_key, request_fingerprint, response_json, completed, created_at)
                VALUES (?, ?, ?, ?, ?, TRUE, ?)
                """, scopeKey, operation, idempotencyKey, requestFingerprint,
                write(response), Instant.now().toString());
    }

    @Override
    @Transactional
    public JsonNode executeOnce(String scopeKey,
                                String operation,
                                String idempotencyKey,
                                String requestFingerprint,
                                Supplier<JsonNode> action) {
        Optional<JsonNode> replay = replay(scopeKey, operation, idempotencyKey, requestFingerprint);
        if (replay.isPresent()) return replay.get();
        if (!reserveIdempotency(scopeKey, operation, idempotencyKey, requestFingerprint)) {
            Optional<JsonNode> completed = replay(scopeKey, operation, idempotencyKey, requestFingerprint);
            if (completed.isPresent()) return completed.get();
            throw new AgentTddToolException("IDEMPOTENCY_CONFLICT",
                    "The idempotency request is already in progress.");
        }
        JsonNode response = action.get();
        int updated = jdbc.update("""
                UPDATE agent_tdd_idempotency
                   SET response_json = ?, completed = TRUE
                 WHERE scope_key = ? AND operation = ? AND idempotency_key = ?
                   AND request_fingerprint = ? AND completed = FALSE
                """, write(response), scopeKey, operation, idempotencyKey, requestFingerprint);
        if (updated != 1) {
            throw new AgentTddToolException("IDEMPOTENCY_CONFLICT",
                    "The idempotency reservation could not be completed.");
        }
        return response.deepCopy();
    }

    /**
     * Reserves a key without leaving a PostgreSQL transaction aborted after a concurrent insert.
     *
     * <p>PostgreSQL treats a caught unique-constraint exception as transaction-fatal, so its path
     * uses {@code ON CONFLICT DO NOTHING} and checks the affected row count. H2 does not support
     * that production syntax in its default mode and safely retains the embedded-demo exception
     * path.</p>
     */
    boolean reserveIdempotency(String scopeKey,
                               String operation,
                               String idempotencyKey,
                               String requestFingerprint) {
        String createdAt = Instant.now().toString();
        if (postgres) {
            return jdbc.update("""
                    INSERT INTO agent_tdd_idempotency
                        (scope_key, operation, idempotency_key, request_fingerprint,
                         response_json, completed, created_at)
                    VALUES (?, ?, ?, ?, ?, FALSE, ?)
                    ON CONFLICT (scope_key, operation, idempotency_key) DO NOTHING
                    """, scopeKey, operation, idempotencyKey, requestFingerprint,
                    "null", createdAt) == 1;
        }
        try {
            jdbc.update("""
                    INSERT INTO agent_tdd_idempotency
                        (scope_key, operation, idempotency_key, request_fingerprint,
                         response_json, completed, created_at)
                    VALUES (?, ?, ?, ?, ?, FALSE, ?)
                    """, scopeKey, operation, idempotencyKey, requestFingerprint,
                    "null", createdAt);
            return true;
        } catch (DuplicateKeyException conflict) {
            return false;
        }
    }

    private JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored Agent TDD JSON is corrupt.", failure);
        }
    }

    private String write(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Agent TDD JSON could not be stored.", failure);
        }
    }

    private record Replay(String fingerprint, JsonNode response) { }

    private static AgentTddToolException staleRevision() {
        return new AgentTddToolException("GATE_REJECTED", "Asset changed after the reviewed revision.");
    }
}
