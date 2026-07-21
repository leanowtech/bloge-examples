package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor;
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
 * Database-clock monotonic floor for recovery-fleet publication and witness chain heads.
 *
 * <p>A stable deployment-scope and fleet lock serializes first publication and every successor
 * across replicas. The stored chain head is whole-record fingerprinted. Only sequence one may
 * establish a missing floor; an existing floor accepts either its exact current generation or the
 * exact next generation naming both current fingerprints as predecessors.</p>
 */
public final class DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
        implements ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String RECORD_SCHEMA =
            "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String deploymentScopeId;
    private final String fleetId;
    private final TransactionTemplate mutations;

    /**
     * Creates one database-backed floor authority for an exact durable recovery fleet.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical record fingerprint mapper
     * @param deploymentScopeId stable deployment scope
     * @param fleetId stable durable recovery fleet identity
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String deploymentScopeId,
            String fleetId,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.deploymentScopeId = normalized(deploymentScopeId);
        this.fleetId = normalized(fleetId);
        if (!IDENTIFIER.matcher(this.deploymentScopeId).matches()
                || !IDENTIFIER.matcher(this.fleetId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid recovery-fleet inventory publication floor scope");
        }
        mutations = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates the stable-fleet lock and whole-record-fingerprinted floor table. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_ext_anchor_recovery_inventory_floor_locks (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    fleet_id VARCHAR(255) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, fleet_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_ext_anchor_recovery_inventory_floors (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    fleet_id VARCHAR(255) NOT NULL,
                    sequence BIGINT NOT NULL,
                    publication_material_fingerprint VARCHAR(71) NOT NULL,
                    witness_material_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, fleet_id)
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public void accept(Generation generation) {
        if (generation == null
                || !deploymentScopeId.equals(generation.deploymentScopeId())
                || !fleetId.equals(generation.fleetId())) {
            throw new IllegalArgumentException(
                    "Recovery-fleet inventory publication floor scope does not match");
        }
        mutations.executeWithoutResult(status -> {
            lockScope();
            FloorRecord current = current();
            if (current != null && !current.valid(
                    objectMapper, deploymentScopeId, fleetId)) {
                throw new IllegalStateException(
                        "Recovery-fleet inventory publication floor is corrupt");
            }
            if (current == null) {
                if (generation.sequence() != 1) {
                    throw new IllegalArgumentException(
                            "Recovery-fleet inventory publication floor must begin at sequence one");
                }
                persist(generation, databaseNow());
                return;
            }
            if (generation.sequence() < current.sequence()) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory publication floor rejected rollback");
            }
            if (generation.sequence() == current.sequence()) {
                if (!current.publicationMaterialFingerprint().equals(
                        generation.publicationMaterialFingerprint())
                        || !current.witnessMaterialFingerprint().equals(
                        generation.witnessMaterialFingerprint())) {
                    throw new IllegalArgumentException(
                            "Recovery-fleet inventory publication floor rejected fork");
                }
                return;
            }
            if (generation.sequence() != current.sequence() + 1) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory publication floor rejected sequence gap");
            }
            if (!current.publicationMaterialFingerprint().equals(
                    generation.previousPublicationFingerprint())
                    || !current.witnessMaterialFingerprint().equals(
                    generation.previousWitnessFingerprint())) {
                throw new IllegalArgumentException(
                        "Recovery-fleet inventory publication floor rejected predecessor mismatch");
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
                MERGE INTO rg_ext_anchor_recovery_inventory_floor_locks
                    (deployment_scope_id, fleet_id)
                    KEY (deployment_scope_id, fleet_id) VALUES (?, ?)
                """, deploymentScopeId, fleetId);
        jdbc.queryForObject("""
                SELECT fleet_id
                FROM rg_ext_anchor_recovery_inventory_floor_locks
                WHERE deployment_scope_id = ? AND fleet_id = ? FOR UPDATE
                """, String.class, deploymentScopeId, fleetId);
    }

    private FloorRecord current() {
        List<FloorRecord> rows = jdbc.query("""
                SELECT deployment_scope_id, fleet_id, sequence,
                       publication_material_fingerprint,
                       witness_material_fingerprint, observed_at, record_fingerprint
                FROM rg_ext_anchor_recovery_inventory_floors
                WHERE deployment_scope_id = ? AND fleet_id = ?
                """, this::row, deploymentScopeId, fleetId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate recovery-fleet inventory publication floor");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void persist(Generation generation, Instant observedAt) {
        String fingerprint = recordFingerprint(generation.sequence(),
                generation.publicationMaterialFingerprint(),
                generation.witnessMaterialFingerprint(), observedAt);
        jdbc.update("""
                MERGE INTO rg_ext_anchor_recovery_inventory_floors (
                    deployment_scope_id, fleet_id, sequence,
                    publication_material_fingerprint,
                    witness_material_fingerprint, observed_at, record_fingerprint
                ) KEY (deployment_scope_id, fleet_id) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, deploymentScopeId, fleetId, generation.sequence(),
                generation.publicationMaterialFingerprint(),
                generation.witnessMaterialFingerprint(), Timestamp.from(observedAt),
                fingerprint);
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private FloorRecord row(ResultSet result, int rowNumber) throws SQLException {
        Timestamp observedAt = result.getTimestamp("observed_at");
        return new FloorRecord(result.getString("deployment_scope_id"),
                result.getString("fleet_id"), result.getLong("sequence"),
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
                "schemaVersion", RECORD_SCHEMA,
                "deploymentScopeId", deploymentScopeId,
                "fleetId", fleetId,
                "sequence", sequence,
                "publicationMaterialFingerprint", publicationMaterialFingerprint,
                "witnessMaterialFingerprint", witnessMaterialFingerprint,
                "observedAt", observedAt.toString()));
    }

    private record FloorRecord(
            String deploymentScopeId,
            String fleetId,
            long sequence,
            String publicationMaterialFingerprint,
            String witnessMaterialFingerprint,
            Instant observedAt,
            String recordFingerprint) {

        private boolean valid(
                ObjectMapper objectMapper,
                String expectedDeploymentScopeId,
                String expectedFleetId) {
            try {
                if (!expectedDeploymentScopeId.equals(deploymentScopeId)
                        || !expectedFleetId.equals(fleetId)
                        || sequence < 1
                        || !FINGERPRINT.matcher(publicationMaterialFingerprint).matches()
                        || !FINGERPRINT.matcher(witnessMaterialFingerprint).matches()
                        || observedAt == null
                        || !FINGERPRINT.matcher(recordFingerprint).matches()) {
                    return false;
                }
                String expected = ProtocolFingerprint.of(objectMapper, Map.of(
                        "schemaVersion", RECORD_SCHEMA,
                        "deploymentScopeId", deploymentScopeId,
                        "fleetId", fleetId,
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
