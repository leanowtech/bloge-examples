package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationFloor;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Database-clock durable floor for fully replayed external-anchor bootstrap-root chain heads.
 *
 * <p>A composite identity lock serializes first observation and every successor across replicas.
 * An empty floor accepts any head because the caller has already replayed its complete genesis
 * history. Once initialized, only the exact current head or its contiguous exact successor is
 * accepted. The stored record has an independent whole-record fingerprint so local corruption
 * cannot be mistaken for a signed remote fact.</p>
 */
public final class DatabaseExternalSequenceAnchorBootstrapRootPublicationFloor
        implements ExternalSequenceAnchorBootstrapRootPublicationFloor {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String RECORD_SCHEMA =
            "bloge.externalSequenceAnchorBootstrapRootFloor.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String scopeId;
    private final String rootSetId;
    private final TransactionTemplate mutations;

    /**
     * Creates one durable floor for an exact fleet and bootstrap-root chain.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical whole-record fingerprint mapper
     * @param scopeId stable Resource Gateway fleet scope
     * @param rootSetId exact managed bootstrap-root chain identity
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseExternalSequenceAnchorBootstrapRootPublicationFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String scopeId,
            String rootSetId,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.scopeId = normalized(scopeId);
        this.rootSetId = normalized(rootSetId);
        if (!IDENTIFIER.matcher(this.scopeId).matches()
                || !IDENTIFIER.matcher(this.rootSetId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid external bootstrap-root floor identity");
        }
        this.mutations = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        this.mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates the composite lock and whole-record-fingerprinted floor tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_external_sequence_anchor_bootstrap_root_floor_locks (
                    scope_id VARCHAR(255) NOT NULL,
                    root_set_id VARCHAR(255) NOT NULL,
                    PRIMARY KEY (scope_id, root_set_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_external_sequence_anchor_bootstrap_root_floors (
                    scope_id VARCHAR(255) NOT NULL,
                    root_set_id VARCHAR(255) NOT NULL,
                    sequence BIGINT NOT NULL,
                    material_fingerprint VARCHAR(71) NOT NULL,
                    previous_material_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (scope_id, root_set_id)
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public void accept(VerifiedChain chain) {
        if (chain == null || !scopeId.equals(chain.scopeId())
                || !rootSetId.equals(chain.rootSetId())) {
            throw new IllegalArgumentException(
                    "External bootstrap-root floor identity does not match");
        }
        Generation generation = chain.head();
        mutations.executeWithoutResult(status -> {
            lockIdentity();
            FloorRecord current = current();
            if (current != null && !current.valid(objectMapper, scopeId, rootSetId)) {
                throw new IllegalStateException("External bootstrap-root floor is corrupt");
            }
            if (current == null) {
                persist(generation, databaseNow());
                return;
            }
            if (generation.sequence() < current.sequence()) {
                throw new IllegalArgumentException(
                        "External bootstrap-root floor rejected rollback");
            }
            if (generation.sequence() == current.sequence()) {
                if (!current.materialFingerprint().equals(
                        generation.materialFingerprint())) {
                    throw new IllegalArgumentException(
                            "External bootstrap-root floor rejected fork");
                }
                return;
            }
            Generation ancestor = chain.generations().get(
                    Math.toIntExact(current.sequence() - 1));
            if (!current.materialFingerprint().equals(ancestor.materialFingerprint())) {
                throw new IllegalArgumentException(
                        "External bootstrap-root floor rejected forked ancestry");
            }
            persist(generation, databaseNow());
        });
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private void lockIdentity() {
        jdbc.update("""
                MERGE INTO rg_external_sequence_anchor_bootstrap_root_floor_locks
                    (scope_id, root_set_id) KEY (scope_id, root_set_id)
                    VALUES (?, ?)
                """, scopeId, rootSetId);
        jdbc.queryForObject("""
                SELECT scope_id
                FROM rg_external_sequence_anchor_bootstrap_root_floor_locks
                WHERE scope_id = ? AND root_set_id = ? FOR UPDATE
                """, String.class, scopeId, rootSetId);
    }

    private FloorRecord current() {
        List<FloorRecord> rows = jdbc.query("""
                SELECT scope_id, root_set_id, sequence, material_fingerprint,
                       previous_material_fingerprint, observed_at, record_fingerprint
                FROM rg_external_sequence_anchor_bootstrap_root_floors
                WHERE scope_id = ? AND root_set_id = ?
                """, this::row, scopeId, rootSetId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate external bootstrap-root floor");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void persist(Generation generation, Instant observedAt) {
        String recordFingerprint = recordFingerprint(generation, observedAt);
        jdbc.update("""
                MERGE INTO rg_external_sequence_anchor_bootstrap_root_floors (
                    scope_id, root_set_id, sequence, material_fingerprint,
                    previous_material_fingerprint, observed_at, record_fingerprint
                ) KEY (scope_id, root_set_id) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, scopeId, rootSetId, generation.sequence(),
                generation.materialFingerprint(), generation.previousMaterialFingerprint(),
                Timestamp.from(observedAt), recordFingerprint);
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private FloorRecord row(ResultSet result, int rowNumber) throws SQLException {
        Timestamp observedAt = result.getTimestamp("observed_at");
        return new FloorRecord(result.getString("scope_id"),
                result.getString("root_set_id"), result.getLong("sequence"),
                result.getString("material_fingerprint"),
                result.getString("previous_material_fingerprint"),
                observedAt == null ? null : observedAt.toInstant(),
                result.getString("record_fingerprint"));
    }

    private String recordFingerprint(Generation generation, Instant observedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", RECORD_SCHEMA,
                "scopeId", scopeId,
                "rootSetId", rootSetId,
                "sequence", generation.sequence(),
                "materialFingerprint", generation.materialFingerprint(),
                "previousMaterialFingerprint", generation.previousMaterialFingerprint(),
                "observedAt", observedAt.toString()));
    }

    private record FloorRecord(
            String scopeId,
            String rootSetId,
            long sequence,
            String materialFingerprint,
            String previousMaterialFingerprint,
            Instant observedAt,
            String recordFingerprint) {

        private boolean valid(
                ObjectMapper objectMapper, String expectedScopeId, String expectedRootSetId) {
            try {
                boolean predecessorShape = sequence == 1
                        && previousMaterialFingerprint.isEmpty()
                        || sequence > 1
                        && FINGERPRINT.matcher(previousMaterialFingerprint).matches();
                if (!expectedScopeId.equals(scopeId)
                        || !expectedRootSetId.equals(rootSetId)
                        || sequence < 1
                        || !FINGERPRINT.matcher(materialFingerprint).matches()
                        || !predecessorShape || observedAt == null
                        || !FINGERPRINT.matcher(recordFingerprint).matches()) {
                    return false;
                }
                String expected = ProtocolFingerprint.of(objectMapper, Map.of(
                        "schemaVersion", RECORD_SCHEMA,
                        "scopeId", scopeId,
                        "rootSetId", rootSetId,
                        "sequence", sequence,
                        "materialFingerprint", materialFingerprint,
                        "previousMaterialFingerprint", previousMaterialFingerprint,
                        "observedAt", observedAt.toString()));
                return expected.equals(recordFingerprint);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
