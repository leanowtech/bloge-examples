package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityServingInventoryTrustRootFloor;
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
 * Database-clock durable floor for atomic serving-inventory dual trust-root publications.
 *
 * <p>A composite scope/key-set lock serializes genesis and every successor across replicas. The
 * complete stored head is fingerprinted independently of the signed remote material. A missing
 * floor accepts only sequence one; an existing floor accepts only the exact current generation or
 * the exact next generation naming the current fingerprint as predecessor.</p>
 */
public final class DatabaseTestSuiteStabilityServingInventoryTrustRootFloor
        implements TestSuiteStabilityServingInventoryTrustRootFloor {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String RECORD_SCHEMA =
            "bloge.testSuiteStabilityServingInventoryTrustRootFloor.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String scopeId;
    private final String trustRootSetId;
    private final TransactionTemplate mutations;

    /**
     * Creates one durable floor for an exact fleet scope and managed dual key-set identity.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical whole-record fingerprint mapper
     * @param scopeId exact stable fleet scope
     * @param trustRootSetId exact managed trust-root set
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseTestSuiteStabilityServingInventoryTrustRootFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String scopeId,
            String trustRootSetId,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.scopeId = normalized(scopeId);
        this.trustRootSetId = normalized(trustRootSetId);
        if (!IDENTIFIER.matcher(this.scopeId).matches()
                || !IDENTIFIER.matcher(this.trustRootSetId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid serving-inventory trust-root floor identity");
        }
        mutations = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates the composite lock and whole-record-fingerprinted floor tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_inventory_trust_root_floor_locks (
                    scope_id VARCHAR(255) NOT NULL,
                    trust_root_set_id VARCHAR(255) NOT NULL,
                    PRIMARY KEY (scope_id, trust_root_set_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_inventory_trust_root_floors (
                    scope_id VARCHAR(255) NOT NULL,
                    trust_root_set_id VARCHAR(255) NOT NULL,
                    sequence BIGINT NOT NULL,
                    material_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (scope_id, trust_root_set_id)
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public void accept(Generation generation) {
        if (generation == null || !scopeId.equals(generation.scopeId())
                || !trustRootSetId.equals(generation.trustRootSetId())) {
            throw new IllegalArgumentException(
                    "Serving-inventory trust-root floor identity does not match");
        }
        mutations.executeWithoutResult(status -> {
            lockIdentity();
            FloorRecord current = current();
            if (current != null && !current.valid(objectMapper, scopeId, trustRootSetId)) {
                throw new IllegalStateException(
                        "Serving-inventory trust-root floor is corrupt");
            }
            if (current == null) {
                if (generation.sequence() != 1) {
                    throw new IllegalArgumentException(
                            "Serving-inventory trust-root floor must begin at sequence one");
                }
                persist(generation, databaseNow());
                return;
            }
            if (generation.sequence() < current.sequence()) {
                throw new IllegalArgumentException(
                        "Serving-inventory trust-root floor rejected rollback");
            }
            if (generation.sequence() == current.sequence()) {
                if (!current.materialFingerprint().equals(generation.materialFingerprint())) {
                    throw new IllegalArgumentException(
                            "Serving-inventory trust-root floor rejected fork");
                }
                return;
            }
            if (generation.sequence() != current.sequence() + 1) {
                throw new IllegalArgumentException(
                        "Serving-inventory trust-root floor rejected sequence gap");
            }
            if (!current.materialFingerprint().equals(
                    generation.previousMaterialFingerprint())) {
                throw new IllegalArgumentException(
                        "Serving-inventory trust-root floor rejected predecessor mismatch");
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
                MERGE INTO rg_test_suite_stability_inventory_trust_root_floor_locks
                    (scope_id, trust_root_set_id) KEY (scope_id, trust_root_set_id)
                    VALUES (?, ?)
                """, scopeId, trustRootSetId);
        jdbc.queryForObject("""
                SELECT scope_id
                FROM rg_test_suite_stability_inventory_trust_root_floor_locks
                WHERE scope_id = ? AND trust_root_set_id = ? FOR UPDATE
                """, String.class, scopeId, trustRootSetId);
    }

    private FloorRecord current() {
        List<FloorRecord> rows = jdbc.query("""
                SELECT scope_id, trust_root_set_id, sequence, material_fingerprint,
                       observed_at, record_fingerprint
                FROM rg_test_suite_stability_inventory_trust_root_floors
                WHERE scope_id = ? AND trust_root_set_id = ?
                """, this::row, scopeId, trustRootSetId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate serving-inventory trust-root floor");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void persist(Generation generation, Instant observedAt) {
        String recordFingerprint = recordFingerprint(
                generation.sequence(), generation.materialFingerprint(), observedAt);
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_inventory_trust_root_floors (
                    scope_id, trust_root_set_id, sequence, material_fingerprint,
                    observed_at, record_fingerprint
                ) KEY (scope_id, trust_root_set_id) VALUES (?, ?, ?, ?, ?, ?)
                """, scopeId, trustRootSetId, generation.sequence(),
                generation.materialFingerprint(), Timestamp.from(observedAt), recordFingerprint);
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private FloorRecord row(ResultSet result, int rowNumber) throws SQLException {
        Timestamp observedAt = result.getTimestamp("observed_at");
        return new FloorRecord(result.getString("scope_id"),
                result.getString("trust_root_set_id"), result.getLong("sequence"),
                result.getString("material_fingerprint"),
                observedAt == null ? null : observedAt.toInstant(),
                result.getString("record_fingerprint"));
    }

    private String recordFingerprint(
            long sequence, String materialFingerprint, Instant observedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", RECORD_SCHEMA,
                "scopeId", scopeId,
                "trustRootSetId", trustRootSetId,
                "sequence", sequence,
                "materialFingerprint", materialFingerprint,
                "observedAt", observedAt.toString()));
    }

    private record FloorRecord(
            String scopeId,
            String trustRootSetId,
            long sequence,
            String materialFingerprint,
            Instant observedAt,
            String recordFingerprint) {

        private boolean valid(
                ObjectMapper objectMapper, String expectedScopeId, String expectedSetId) {
            try {
                if (!expectedScopeId.equals(scopeId) || !expectedSetId.equals(trustRootSetId)
                        || sequence < 1
                        || !FINGERPRINT.matcher(materialFingerprint).matches()
                        || observedAt == null
                        || !FINGERPRINT.matcher(recordFingerprint).matches()) {
                    return false;
                }
                String expected = ProtocolFingerprint.of(objectMapper, Map.of(
                        "schemaVersion", RECORD_SCHEMA,
                        "scopeId", scopeId,
                        "trustRootSetId", trustRootSetId,
                        "sequence", sequence,
                        "materialFingerprint", materialFingerprint,
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
