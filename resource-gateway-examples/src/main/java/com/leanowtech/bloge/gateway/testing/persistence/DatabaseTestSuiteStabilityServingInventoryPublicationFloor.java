package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityServingInventoryPublicationFloor;
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
 * Database-clock monotonic floor for signed publication and witness chain heads.
 *
 * <p>A stable-scope lock serializes both first publication and every successor across replicas.
 * The stored chain head is whole-record fingerprinted. Only sequence one may establish a missing
 * floor; an existing floor accepts either the exact current generation or an exact next generation
 * naming both current fingerprints as predecessors.</p>
 */
public final class DatabaseTestSuiteStabilityServingInventoryPublicationFloor
        implements TestSuiteStabilityServingInventoryPublicationFloor {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String scopeId;
    private final TransactionTemplate mutations;

    /**
     * Creates one database-backed floor authority for an exact stable fleet scope.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical record fingerprint mapper
     * @param scopeId exact stable fleet scope
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseTestSuiteStabilityServingInventoryPublicationFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String scopeId,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.scopeId = normalized(scopeId);
        if (!IDENTIFIER.matcher(this.scopeId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid serving-inventory publication floor scope");
        }
        mutations = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates the stable-scope lock and whole-record-fingerprinted floor table. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_inventory_publication_floor_locks (
                    scope_id VARCHAR(255) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_serving_inventory_publication_floors (
                    scope_id VARCHAR(255) PRIMARY KEY,
                    sequence BIGINT NOT NULL,
                    publication_material_fingerprint VARCHAR(71) NOT NULL,
                    witness_material_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public void accept(Generation generation) {
        if (generation == null || !scopeId.equals(generation.scopeId())) {
            throw new IllegalArgumentException(
                    "Serving-inventory publication floor scope does not match");
        }
        mutations.executeWithoutResult(status -> {
            lockScope();
            FloorRecord current = current();
            if (current != null && !current.valid(objectMapper, scopeId)) {
                throw new IllegalStateException(
                        "Serving-inventory publication floor is corrupt");
            }
            if (current == null) {
                if (generation.sequence() != 1) {
                    throw new IllegalArgumentException(
                            "Serving-inventory publication floor must begin at sequence one");
                }
                persist(generation, databaseNow());
                return;
            }
            if (generation.sequence() < current.sequence()) {
                throw new IllegalArgumentException(
                        "Serving-inventory publication floor rejected rollback");
            }
            if (generation.sequence() == current.sequence()) {
                if (!current.publicationMaterialFingerprint().equals(
                        generation.publicationMaterialFingerprint())
                        || !current.witnessMaterialFingerprint().equals(
                        generation.witnessMaterialFingerprint())) {
                    throw new IllegalArgumentException(
                            "Serving-inventory publication floor rejected fork");
                }
                return;
            }
            if (generation.sequence() != current.sequence() + 1) {
                throw new IllegalArgumentException(
                        "Serving-inventory publication floor rejected sequence gap");
            }
            if (!current.publicationMaterialFingerprint().equals(
                    generation.previousPublicationFingerprint())
                    || !current.witnessMaterialFingerprint().equals(
                    generation.previousWitnessFingerprint())) {
                throw new IllegalArgumentException(
                        "Serving-inventory publication floor rejected predecessor mismatch");
            }
            persist(generation, databaseNow());
        });
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private void lockScope() {
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_inventory_publication_floor_locks
                    (scope_id) KEY (scope_id) VALUES (?)
                """, scopeId);
        jdbc.queryForObject("""
                SELECT scope_id
                FROM rg_test_suite_stability_inventory_publication_floor_locks
                WHERE scope_id = ? FOR UPDATE
                """, String.class, scopeId);
    }

    private FloorRecord current() {
        List<FloorRecord> rows = jdbc.query("""
                SELECT scope_id, sequence, publication_material_fingerprint,
                       witness_material_fingerprint, observed_at, record_fingerprint
                FROM rg_test_suite_stability_serving_inventory_publication_floors
                WHERE scope_id = ?
                """, this::row, scopeId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate serving-inventory publication floor");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void persist(Generation generation, Instant observedAt) {
        String fingerprint = recordFingerprint(generation.sequence(),
                generation.publicationMaterialFingerprint(),
                generation.witnessMaterialFingerprint(), observedAt);
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_serving_inventory_publication_floors (
                    scope_id, sequence, publication_material_fingerprint,
                    witness_material_fingerprint, observed_at, record_fingerprint
                ) KEY (scope_id) VALUES (?, ?, ?, ?, ?, ?)
                """, scopeId, generation.sequence(),
                generation.publicationMaterialFingerprint(),
                generation.witnessMaterialFingerprint(), Timestamp.from(observedAt), fingerprint);
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private FloorRecord row(ResultSet result, int rowNumber) throws SQLException {
        Timestamp observedAt = result.getTimestamp("observed_at");
        return new FloorRecord(result.getString("scope_id"), result.getLong("sequence"),
                result.getString("publication_material_fingerprint"),
                result.getString("witness_material_fingerprint"),
                observedAt == null ? null : observedAt.toInstant(),
                result.getString("record_fingerprint"));
    }

    private String recordFingerprint(
            long sequence,
            String publicationMaterialFingerprint,
            String witnessMaterialFingerprint,
            Instant observedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.testSuiteStabilityServingInventoryPublicationFloor.v1",
                "scopeId", scopeId,
                "sequence", sequence,
                "publicationMaterialFingerprint", publicationMaterialFingerprint,
                "witnessMaterialFingerprint", witnessMaterialFingerprint,
                "observedAt", observedAt.toString()));
    }

    private record FloorRecord(
            String scopeId,
            long sequence,
            String publicationMaterialFingerprint,
            String witnessMaterialFingerprint,
            Instant observedAt,
            String recordFingerprint) {

        private boolean valid(ObjectMapper objectMapper, String expectedScopeId) {
            try {
                if (!expectedScopeId.equals(scopeId)
                        || sequence < 1
                        || !FINGERPRINT.matcher(publicationMaterialFingerprint).matches()
                        || !FINGERPRINT.matcher(witnessMaterialFingerprint).matches()
                        || observedAt == null
                        || !FINGERPRINT.matcher(recordFingerprint).matches()) {
                    return false;
                }
                String expected = ProtocolFingerprint.of(objectMapper, Map.of(
                        "schemaVersion",
                        "bloge.testSuiteStabilityServingInventoryPublicationFloor.v1",
                        "scopeId", scopeId,
                        "sequence", sequence,
                        "publicationMaterialFingerprint", publicationMaterialFingerprint,
                        "witnessMaterialFingerprint", witnessMaterialFingerprint,
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
