package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityServingInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityServingInventoryPublicationFloor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSecretAuthorityServingInventoryPublicationFloorTest {

    private static final String SCOPE = "test-secret-scope";
    private static final String DATABASE_SCOPE = "test-secret/" + SCOPE;

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private DatabaseTestSecretAuthorityServingInventoryPublicationFloor floor;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:test-secret-inventory-floor-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        floor = repository(SCOPE);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void successorAndIdempotencySurviveRepositoryReconstruction() {
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
                """, DATABASE_SCOPE))
                .containsEntry("SEQUENCE", 2L)
                .containsEntry("PUBLICATION_MATERIAL_FINGERPRINT", fingerprint('c'))
                .containsEntry("WITNESS_MATERIAL_FINGERPRINT", fingerprint('d'));
        assertThat(reconstructed.durable()).isTrue();
    }

    @Test
    void testSecretAndSuiteStabilityDomainsCannotCollide() {
        var suiteFloor = new DatabaseTestSuiteStabilityServingInventoryPublicationFloor(
                database.jdbc(), objectMapper, SCOPE, database.transactionManager());
        suiteFloor.init();
        floor.accept(generation(1, 'a', 'b', null));
        suiteFloor.accept(new TestSuiteStabilityServingInventoryPublicationFloor.Generation(
                TestSuiteStabilityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                SCOPE, 1, fingerprint('c'), fingerprint('d'), "", ""));

        assertThat(database.jdbc().queryForList("""
                SELECT scope_id
                FROM rg_test_suite_stability_serving_inventory_publication_floors
                ORDER BY scope_id
                """, String.class)).containsExactly(SCOPE, DATABASE_SCOPE);
    }

    @Test
    void rollbackForkGapAndBrokenPredecessorFailClosed() {
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
        var falsePrevious = generation(2, 'e', 'f', first);
        assertThatThrownBy(() -> floor.accept(generation(3, 'a', 'b', falsePrevious)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("predecessor mismatch");
    }

    @Test
    void wrongDomainScopeAndCorruptDurableRecordCannotAdvance() {
        var first = generation(1, 'a', 'b', null);
        floor.accept(first);
        assertThatThrownBy(() -> floor.accept(generation(
                "another-scope", 1, 'c', 'd', null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope does not match");

        database.jdbc().update("""
                UPDATE rg_test_suite_stability_serving_inventory_publication_floors
                SET record_fingerprint = ? WHERE scope_id = ?
                """, fingerprint('f'), DATABASE_SCOPE);
        assertThatThrownBy(() -> repository(SCOPE).accept(first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    private DatabaseTestSecretAuthorityServingInventoryPublicationFloor repository(
            String scope) {
        var repository = new DatabaseTestSecretAuthorityServingInventoryPublicationFloor(
                database.jdbc(), objectMapper, scope, database.transactionManager());
        repository.init();
        return repository;
    }

    private static TestSecretAuthorityServingInventoryPublicationFloor.Generation generation(
            long sequence,
            char publication,
            char witness,
            TestSecretAuthorityServingInventoryPublicationFloor.Generation predecessor) {
        return generation(SCOPE, sequence, publication, witness, predecessor);
    }

    private static TestSecretAuthorityServingInventoryPublicationFloor.Generation generation(
            String scope,
            long sequence,
            char publication,
            char witness,
            TestSecretAuthorityServingInventoryPublicationFloor.Generation predecessor) {
        return new TestSecretAuthorityServingInventoryPublicationFloor.Generation(
                TestSecretAuthorityServingInventoryPublicationFloor.Generation.SCHEMA_VERSION,
                scope, sequence, fingerprint(publication), fingerprint(witness),
                predecessor == null ? "" : predecessor.publicationMaterialFingerprint(),
                predecessor == null ? "" : predecessor.witnessMaterialFingerprint());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
