package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloorTest {

    private static final String SCOPE = "tenant-a/staging";
    private static final String FLEET = "recovery-fleet-a";

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            floor;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:recovery-inventory-floor-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        floor = repository(SCOPE, FLEET);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void firstGenerationReplayAndSuccessorSurviveRepositoryReconstruction() {
        var first = generation(1, 'a', 'b', null);
        floor.accept(first);

        var reconstructed = repository(SCOPE, FLEET);
        reconstructed.accept(first);
        reconstructed.accept(generation(2, 'c', 'd', first));

        assertThat(database.jdbc().queryForMap("""
                SELECT sequence, inventory_generation, inventory_material_fingerprint,
                       publication_material_fingerprint, witness_material_fingerprint,
                       publication_state
                FROM rg_ext_anchor_recovery_inventory_floors
                WHERE deployment_scope_id = ? AND fleet_id = ?
                """, SCOPE, FLEET)).containsEntry("SEQUENCE", 2L)
                .containsEntry("INVENTORY_GENERATION", 8L)
                .containsEntry("INVENTORY_MATERIAL_FINGERPRINT", fingerprint('2'))
                .containsEntry("PUBLICATION_MATERIAL_FINGERPRINT", fingerprint('c'))
                .containsEntry("WITNESS_MATERIAL_FINGERPRINT", fingerprint('d'))
                .containsEntry("PUBLICATION_STATE", "ACTIVE");
        assertThat(reconstructed.durable()).isTrue();
        assertThat(reconstructed.externallyAnchored()).isFalse();
    }

    @Test
    void missingFloorRequiresSequenceOneAndCandidateRequiresExactPredecessorShape() {
        var syntheticFirst = generation(1, 'a', 'b', null);

        assertThatThrownBy(() -> floor.accept(generation(2, 'c', 'd', syntheticFirst)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("begin at sequence one");
        assertThatThrownBy(() -> generation(SCOPE, FLEET, 1, 'a', 'b',
                fingerprint('c'), fingerprint('d')))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generation(SCOPE, FLEET, 2, 'a', 'b', "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rollbackForkGapAndBrokenPredecessorsFailClosed() {
        var first = generation(1, 'a', 'b', null);
        var second = generation(2, 'c', 'd', first);
        floor.accept(first);
        floor.accept(second);

        assertThatThrownBy(() -> floor.accept(first))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rollback");
        assertThatThrownBy(() -> floor.accept(generation(2, 'e', 'f', first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fork");
        assertThatThrownBy(() -> floor.accept(generation(4, 'e', 'f', second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence gap");
        assertThatThrownBy(() -> floor.accept(generation(SCOPE, FLEET, 3, 'e', 'f',
                fingerprint('e'), fingerprint('f'))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predecessor mismatch");
    }

    @Test
    void validPublicationChainCannotHideInventoryRollbackOrSameGenerationDrift() {
        var first = generation(SCOPE, FLEET, 1, 7, '1', 'a', 'b', State.ACTIVE,
                "", "");
        floor.accept(first);

        assertThatThrownBy(() -> floor.accept(generation(SCOPE, FLEET, 2,
                6, '0', 'c', 'd', State.ACTIVE,
                first.publicationMaterialFingerprint(),
                first.witnessMaterialFingerprint())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory rollback");
        assertThatThrownBy(() -> floor.accept(generation(SCOPE, FLEET, 2,
                7, '2', 'c', 'd', State.ACTIVE,
                first.publicationMaterialFingerprint(),
                first.witnessMaterialFingerprint())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory fork");
    }

    @Test
    void revokedInventoryCannotReactivateUntilANewInventoryGenerationIsPublished() {
        var active = generation(SCOPE, FLEET, 1, 7, '1', 'a', 'b', State.ACTIVE,
                "", "");
        var revoked = generation(SCOPE, FLEET, 2, 7, '1', 'c', 'd', State.REVOKED,
                active.publicationMaterialFingerprint(), active.witnessMaterialFingerprint());
        floor.accept(active);
        floor.accept(revoked);

        assertThatThrownBy(() -> floor.accept(generation(SCOPE, FLEET, 3,
                7, '1', 'e', 'f', State.ACTIVE,
                revoked.publicationMaterialFingerprint(),
                revoked.witnessMaterialFingerprint())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reactivation");

        floor.accept(generation(SCOPE, FLEET, 3, 8, '2', 'e', 'f', State.ACTIVE,
                revoked.publicationMaterialFingerprint(), revoked.witnessMaterialFingerprint()));
        assertThat(database.jdbc().queryForMap("""
                SELECT inventory_generation, publication_state
                FROM rg_ext_anchor_recovery_inventory_floors
                WHERE deployment_scope_id = ? AND fleet_id = ?
                """, SCOPE, FLEET))
                .containsEntry("INVENTORY_GENERATION", 8L)
                .containsEntry("PUBLICATION_STATE", "ACTIVE");
    }

    @Test
    void legacyFloorRequiresExactHeadReplayBeforeHydratingNestedInventoryState() {
        database.jdbc().execute("DROP TABLE rg_ext_anchor_recovery_inventory_floors");
        database.jdbc().execute("""
                CREATE TABLE rg_ext_anchor_recovery_inventory_floors (
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
        Instant observedAt = Instant.parse("2026-07-21T03:00:00Z");
        var current = generation(1, 'a', 'b', null);
        String legacyRecord = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.v1",
                "deploymentScopeId", SCOPE,
                "fleetId", FLEET,
                "sequence", 1L,
                "publicationMaterialFingerprint", current.publicationMaterialFingerprint(),
                "witnessMaterialFingerprint", current.witnessMaterialFingerprint(),
                "observedAt", observedAt.toString()));
        database.jdbc().update("""
                INSERT INTO rg_ext_anchor_recovery_inventory_floors (
                    deployment_scope_id, fleet_id, sequence,
                    publication_material_fingerprint, witness_material_fingerprint,
                    observed_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, SCOPE, FLEET, 1L, current.publicationMaterialFingerprint(),
                current.witnessMaterialFingerprint(), Timestamp.from(observedAt), legacyRecord);
        var upgraded = repository(SCOPE, FLEET);

        assertThatThrownBy(() -> upgraded.accept(generation(2, 'c', 'd', current)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact legacy-head replay");

        upgraded.accept(current);
        upgraded.accept(generation(2, 'c', 'd', current));

        assertThat(database.jdbc().queryForMap("""
                SELECT sequence, inventory_generation, inventory_material_fingerprint,
                       publication_state
                FROM rg_ext_anchor_recovery_inventory_floors
                WHERE deployment_scope_id = ? AND fleet_id = ?
                """, SCOPE, FLEET))
                .containsEntry("SEQUENCE", 2L)
                .containsEntry("INVENTORY_GENERATION", 8L)
                .containsEntry("INVENTORY_MATERIAL_FINGERPRINT", fingerprint('2'))
                .containsEntry("PUBLICATION_STATE", "ACTIVE");
    }

    @Test
    void corruptFloorCannotBeReplayedOrOverwritten() {
        var first = generation(1, 'a', 'b', null);
        floor.accept(first);
        database.jdbc().update("""
                UPDATE rg_ext_anchor_recovery_inventory_floors
                SET record_fingerprint = ?
                WHERE deployment_scope_id = ? AND fleet_id = ?
                """, fingerprint('f'), SCOPE, FLEET);

        assertThatThrownBy(() -> repository(SCOPE, FLEET).accept(first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
        assertThat(database.jdbc().queryForObject("""
                SELECT publication_material_fingerprint
                FROM rg_ext_anchor_recovery_inventory_floors
                WHERE deployment_scope_id = ? AND fleet_id = ?
                """, String.class, SCOPE, FLEET)).isEqualTo(fingerprint('a'));
    }

    @Test
    void deploymentScopesAndFleetIdsRemainIndependentAndCrossUseIsRejected() {
        var otherScope = repository("tenant-b/staging", FLEET);
        var otherFleet = repository(SCOPE, "recovery-fleet-b");
        floor.accept(generation(SCOPE, FLEET, 1, 'a', 'b', "", ""));
        otherScope.accept(generation("tenant-b/staging", FLEET, 1,
                'c', 'd', "", ""));
        otherFleet.accept(generation(SCOPE, "recovery-fleet-b", 1,
                'e', 'f', "", ""));

        assertThat(database.jdbc().queryForList("""
                SELECT deployment_scope_id || ':' || fleet_id
                FROM rg_ext_anchor_recovery_inventory_floors
                ORDER BY deployment_scope_id, fleet_id
                """, String.class)).containsExactly(
                "tenant-a/staging:recovery-fleet-a",
                "tenant-a/staging:recovery-fleet-b",
                "tenant-b/staging:recovery-fleet-a");
        assertThatThrownBy(() -> floor.accept(generation(
                "tenant-b/staging", FLEET, 1, 'c', 'd', "", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope does not match");
    }

    @Test
    void concurrentCompetingSuccessorsLinearizeToExactlyOneWinner() throws Exception {
        var first = generation(1, 'a', 'b', null);
        floor.accept(first);
        var leftFloor = repository(SCOPE, FLEET);
        var rightFloor = repository(SCOPE, FLEET);
        var left = generation(2, 'c', 'd', first);
        var right = generation(2, 'e', 'f', first);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> leftResult = workers.submit(
                    () -> acceptAfter(start, leftFloor, left));
            Future<String> rightResult = workers.submit(
                    () -> acceptAfter(start, rightFloor, right));
            start.countDown();

            assertThat(List.of(leftResult.get(), rightResult.get()))
                    .satisfiesExactlyInAnyOrder(
                            result -> assertThat(result).isEqualTo("SUCCESS"),
                            result -> assertThat(result).contains("rejected fork"));
            assertThat(database.jdbc().queryForObject("""
                    SELECT publication_material_fingerprint
                    FROM rg_ext_anchor_recovery_inventory_floors
                    WHERE deployment_scope_id = ? AND fleet_id = ?
                    """, String.class, SCOPE, FLEET))
                    .isIn(left.publicationMaterialFingerprint(),
                            right.publicationMaterialFingerprint());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void closedDatabaseFailsWithoutPretendingToAdvance() {
        database.close();
        database = null;

        assertThatThrownBy(() -> floor.accept(generation(1, 'a', 'b', null)))
                .isInstanceOf(RuntimeException.class);
    }

    private DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            repository(String deploymentScopeId, String fleetId) {
        var repository = new
                DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor(
                database.jdbc(), objectMapper, deploymentScopeId, fleetId,
                database.transactionManager());
        repository.init();
        return repository;
    }

    private static String acceptAfter(
            CountDownLatch start,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor target,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.Generation
                    generation) throws InterruptedException {
        start.await();
        try {
            target.accept(generation);
            return "SUCCESS";
        } catch (IllegalArgumentException rejected) {
            return rejected.getMessage();
        }
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            .Generation generation(
            long sequence,
            char publication,
            char witness,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.Generation
                    predecessor) {
        return generation(SCOPE, FLEET, sequence, publication, witness,
                predecessor == null ? "" : predecessor.publicationMaterialFingerprint(),
                predecessor == null ? "" : predecessor.witnessMaterialFingerprint());
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            .Generation generation(
            String deploymentScopeId,
            String fleetId,
            long sequence,
            char publication,
            char witness,
            String previousPublication,
            String previousWitness) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                .Generation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                        .Generation.SCHEMA_VERSION,
                deploymentScopeId, fleetId, sequence, sequence + 6,
                fingerprint((char) ('0' + sequence)), fingerprint(publication),
                fingerprint(witness), State.ACTIVE, previousPublication, previousWitness);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
            .Generation generation(
            String deploymentScopeId,
            String fleetId,
            long sequence,
            long inventoryGeneration,
            char inventory,
            char publication,
            char witness,
            State state,
            String previousPublication,
            String previousWitness) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                .Generation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                        .Generation.SCHEMA_VERSION,
                deploymentScopeId, fleetId, sequence, inventoryGeneration,
                fingerprint(inventory), fingerprint(publication), fingerprint(witness), state,
                previousPublication, previousWitness);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
