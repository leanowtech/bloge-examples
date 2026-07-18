package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityServingInventoryPublicationFloor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityServingInventoryPublicationFloorTest {

    private static final String SCOPE = "stability-fleet";

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private DatabaseTestSuiteStabilityServingInventoryPublicationFloor floor;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:serving-inventory-floor-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        floor = repository(SCOPE);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void firstGenerationIdempotencyAndSuccessorSurviveRepositoryReconstruction() {
        var first = generation(1, 'a', 'b', null);
        floor.accept(first);

        var reconstructed = repository(SCOPE);
        reconstructed.accept(first);
        reconstructed.accept(generation(2, 'c', 'd', first));

        assertThat(database.jdbc().queryForMap("""
                SELECT sequence, publication_material_fingerprint,
                       witness_material_fingerprint
                FROM rg_test_suite_stability_serving_inventory_publication_floors
                WHERE scope_id = ?
                """, SCOPE)).containsEntry("SEQUENCE", 2L)
                .containsEntry("PUBLICATION_MATERIAL_FINGERPRINT", fingerprint('c'))
                .containsEntry("WITNESS_MATERIAL_FINGERPRINT", fingerprint('d'));
        assertThat(reconstructed.durable()).isTrue();
    }

    @Test
    void missingFloorRequiresSequenceOneAndGenerationRequiresExactPredecessorShape() {
        var syntheticFirst = generation(1, 'a', 'b', null);

        assertThatThrownBy(() -> floor.accept(generation(2, 'c', 'd', syntheticFirst)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("begin at sequence one");
        assertThatThrownBy(() -> new TestSuiteStabilityServingInventoryPublicationFloor.Generation(
                TestSuiteStabilityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                SCOPE, 1, fingerprint('a'), fingerprint('b'),
                fingerprint('c'), fingerprint('d')))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityServingInventoryPublicationFloor.Generation(
                TestSuiteStabilityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                SCOPE, 2, fingerprint('a'), fingerprint('b'), "", ""))
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
        var falsePredecessor = generation(2, 'e', 'f', first);
        assertThatThrownBy(() -> floor.accept(generation(3, 'a', 'b', falsePredecessor)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predecessor mismatch");
    }

    @Test
    void corruptFloorCannotBeReadOrOverwritten() {
        var first = generation(1, 'a', 'b', null);
        floor.accept(first);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_serving_inventory_publication_floors
                SET record_fingerprint = ? WHERE scope_id = ?
                """, fingerprint('f'), SCOPE);

        assertThatThrownBy(() -> repository(SCOPE).accept(first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
        assertThat(database.jdbc().queryForObject("""
                SELECT publication_material_fingerprint
                FROM rg_test_suite_stability_serving_inventory_publication_floors
                WHERE scope_id = ?
                """, String.class, SCOPE)).isEqualTo(fingerprint('a'));
    }

    @Test
    void stableScopesRemainIsolated() {
        var other = repository("stability-fleet-b");
        floor.accept(generation(SCOPE, 1, 'a', 'b', null));
        other.accept(generation("stability-fleet-b", 1, 'c', 'd', null));

        assertThat(database.jdbc().queryForList("""
                SELECT scope_id FROM
                    rg_test_suite_stability_serving_inventory_publication_floors
                ORDER BY scope_id
                """, String.class)).containsExactly(SCOPE, "stability-fleet-b");
        assertThatThrownBy(() -> floor.accept(
                generation("stability-fleet-b", 1, 'c', 'd', null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope does not match");
    }

    @Test
    void concurrentCompetingSuccessorsLinearizeToExactlyOneWinner() throws Exception {
        var first = generation(1, 'a', 'b', null);
        floor.accept(first);
        var leftFloor = repository(SCOPE);
        var rightFloor = repository(SCOPE);
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
                    FROM rg_test_suite_stability_serving_inventory_publication_floors
                    WHERE scope_id = ?
                    """, String.class, SCOPE))
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

    private DatabaseTestSuiteStabilityServingInventoryPublicationFloor repository(
            String scopeId) {
        var repository = new DatabaseTestSuiteStabilityServingInventoryPublicationFloor(
                database.jdbc(), objectMapper, scopeId, database.transactionManager());
        repository.init();
        return repository;
    }

    private static String acceptAfter(
            CountDownLatch start,
            TestSuiteStabilityServingInventoryPublicationFloor target,
            TestSuiteStabilityServingInventoryPublicationFloor.Generation generation)
            throws InterruptedException {
        start.await();
        try {
            target.accept(generation);
            return "SUCCESS";
        } catch (IllegalArgumentException rejected) {
            return rejected.getMessage();
        }
    }

    private static TestSuiteStabilityServingInventoryPublicationFloor.Generation generation(
            long sequence,
            char publication,
            char witness,
            TestSuiteStabilityServingInventoryPublicationFloor.Generation predecessor) {
        return generation(SCOPE, sequence, publication, witness, predecessor);
    }

    private static TestSuiteStabilityServingInventoryPublicationFloor.Generation generation(
            String scopeId,
            long sequence,
            char publication,
            char witness,
            TestSuiteStabilityServingInventoryPublicationFloor.Generation predecessor) {
        return new TestSuiteStabilityServingInventoryPublicationFloor.Generation(
                TestSuiteStabilityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                scopeId, sequence, fingerprint(publication), fingerprint(witness),
                predecessor == null ? "" : predecessor.publicationMaterialFingerprint(),
                predecessor == null ? "" : predecessor.witnessMaterialFingerprint());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
