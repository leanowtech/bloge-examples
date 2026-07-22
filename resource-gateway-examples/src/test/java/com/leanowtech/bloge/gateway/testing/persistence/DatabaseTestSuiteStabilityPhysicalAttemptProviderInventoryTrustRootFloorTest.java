package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor;
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

class DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloorTest {

    private static final String SCOPE = "stability-fleet";
    private static final String SET_ID = "inventory-dual-roots";

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor floor;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:inventory-trust-root-floor-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        floor = repository(SCOPE, SET_ID);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void reconstructionPreservesIdempotencyAndExactSuccessor() {
        var first = generation(1, 'a', null);
        floor.accept(first);

        var reconstructed = repository(SCOPE, SET_ID);
        reconstructed.accept(first);
        reconstructed.accept(generation(2, 'b', first));

        assertThat(database.jdbc().queryForMap("""
                SELECT sequence, material_fingerprint
                FROM rg_test_suite_stability_physical_provider_inventory_trust_root_floors
                WHERE scope_id = ? AND trust_root_set_id = ?
                """, SCOPE, SET_ID))
                .containsEntry("SEQUENCE", 2L)
                .containsEntry("MATERIAL_FINGERPRINT", fingerprint('b'));
        assertThat(reconstructed.durable()).isTrue();
    }

    @Test
    void genesisRollbackForkGapAndWrongPredecessorFailClosed() {
        var first = generation(1, 'a', null);
        assertThatThrownBy(() -> floor.accept(generation(2, 'b', first)))
                .hasMessageContaining("begin at sequence one");
        floor.accept(first);
        var second = generation(2, 'b', first);
        floor.accept(second);

        assertThatThrownBy(() -> floor.accept(first)).hasMessageContaining("rollback");
        assertThatThrownBy(() -> floor.accept(generation(2, 'c', first)))
                .hasMessageContaining("fork");
        assertThatThrownBy(() -> floor.accept(generation(4, 'd', second)))
                .hasMessageContaining("sequence gap");
        var falseSecond = generation(2, 'c', first);
        assertThatThrownBy(() -> floor.accept(generation(3, 'd', falseSecond)))
                .hasMessageContaining("predecessor mismatch");
    }

    @Test
    void compositeScopeAndSetIdentityRemainIsolated() {
        var otherScope = repository("stability-fleet-b", SET_ID);
        var otherSet = repository(SCOPE, "inventory-dual-roots-b");
        floor.accept(generation(SCOPE, SET_ID, 1, 'a', null));
        otherScope.accept(generation("stability-fleet-b", SET_ID, 1, 'b', null));
        otherSet.accept(generation(SCOPE, "inventory-dual-roots-b", 1, 'c', null));

        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_physical_provider_inventory_trust_root_floors
                """, Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> floor.accept(
                generation(SCOPE, "inventory-dual-roots-b", 1, 'c', null)))
                .hasMessageContaining("identity does not match");
    }

    @Test
    void corruptionAndStoreOutageFailClosed() {
        var first = generation(1, 'a', null);
        floor.accept(first);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_physical_provider_inventory_trust_root_floors
                SET record_fingerprint = ?
                WHERE scope_id = ? AND trust_root_set_id = ?
                """, fingerprint('f'), SCOPE, SET_ID);
        assertThatThrownBy(() -> repository(SCOPE, SET_ID).accept(first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");

        database.close();
        database = null;
        assertThatThrownBy(() -> floor.accept(first)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void competingSuccessorsLinearizeToOneWinner() throws Exception {
        var first = generation(1, 'a', null);
        floor.accept(first);
        var left = generation(2, 'b', first);
        var right = generation(2, 'c', first);
        var leftFloor = repository(SCOPE, SET_ID);
        var rightFloor = repository(SCOPE, SET_ID);
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
        } finally {
            workers.shutdownNow();
        }
    }

    private DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor repository(
            String scopeId, String setId) {
        var result = new DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor(
                database.jdbc(), objectMapper, scopeId, setId, database.transactionManager());
        result.init();
        return result;
    }

    private static String acceptAfter(
            CountDownLatch start,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor target,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation generation)
            throws InterruptedException {
        start.await();
        try {
            target.accept(generation);
            return "SUCCESS";
        } catch (IllegalArgumentException rejected) {
            return rejected.getMessage();
        }
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation generation(
            long sequence,
            char material,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation predecessor) {
        return generation(SCOPE, SET_ID, sequence, material, predecessor);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation generation(
            String scopeId,
            String setId,
            long sequence,
            char material,
            TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation predecessor) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryTrustRootFloor.Generation.SCHEMA_VERSION,
                scopeId, setId, sequence, fingerprint(material),
                predecessor == null ? "" : predecessor.materialFingerprint());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
