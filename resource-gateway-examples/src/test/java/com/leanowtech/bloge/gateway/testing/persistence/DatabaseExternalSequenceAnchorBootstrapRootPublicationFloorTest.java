package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationFloor;
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

class DatabaseExternalSequenceAnchorBootstrapRootPublicationFloorTest {

    private static final String SCOPE = "stability-fleet";
    private static final String ROOT_SET = "external-notary-bootstrap-roots";

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private DatabaseExternalSequenceAnchorBootstrapRootPublicationFloor floor;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-bootstrap-root-floor-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        floor = repository(SCOPE, ROOT_SET);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void newReplicaMayPersistVerifiedHeadNAndCatchUpMultipleSuccessors() {
        var fifth = chain('a', 'b', 'c', 'd', 'e');
        floor.accept(fifth);

        var reconstructed = repository(SCOPE, ROOT_SET);
        reconstructed.accept(fifth);
        reconstructed.accept(chain('a', 'b', 'c', 'd', 'e', 'f', 'a', 'b'));

        assertThat(database.jdbc().queryForMap("""
                SELECT sequence, material_fingerprint, previous_material_fingerprint
                FROM rg_external_sequence_anchor_bootstrap_root_floors
                WHERE scope_id = ? AND root_set_id = ?
                """, SCOPE, ROOT_SET))
                .containsEntry("SEQUENCE", 8L)
                .containsEntry("MATERIAL_FINGERPRINT", fingerprint('b'))
                .containsEntry("PREVIOUS_MATERIAL_FINGERPRINT", fingerprint('a'));
        assertThat(reconstructed.durable()).isTrue();
    }

    @Test
    void initializedFloorRejectsRollbackSameSequenceForkAndForkedAncestry() {
        floor.accept(chain('a', 'b', 'c', 'd', 'e'));
        floor.accept(chain('a', 'b', 'c', 'd', 'e', 'f'));

        assertThatThrownBy(() -> floor.accept(chain('a', 'b', 'c', 'd', 'e')))
                .hasMessageContaining("rollback");
        assertThatThrownBy(() -> floor.accept(chain('a', 'b', 'c', 'd', 'e', 'a')))
                .hasMessageContaining("fork");
        assertThatThrownBy(() -> floor.accept(
                chain('a', 'b', 'c', 'd', 'e', 'a', 'b')))
                .hasMessageContaining("forked ancestry");
    }

    @Test
    void compositeIdentityIsolatedAndCorruptionFailsClosed() {
        floor.accept(chain('a', 'b', 'c'));
        var other = repository("other-fleet", ROOT_SET);
        other.accept(chain("other-fleet", ROOT_SET, 'a', 'b'));

        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_external_sequence_anchor_bootstrap_root_floors
                """, Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> floor.accept(
                chain("other-fleet", ROOT_SET, 'a', 'b')))
                .hasMessageContaining("identity does not match");

        database.jdbc().update("""
                UPDATE rg_external_sequence_anchor_bootstrap_root_floors
                SET record_fingerprint = ?
                WHERE scope_id = ? AND root_set_id = ?
                """, fingerprint('a'), SCOPE, ROOT_SET);
        assertThatThrownBy(() -> repository(SCOPE, ROOT_SET)
                .accept(chain('a', 'b', 'c')))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void competingFirstHeadsAtSameSequenceLinearizeToOneWinner() throws Exception {
        var left = chain('a', 'b', 'c', 'd', 'e', 'f', 'a');
        var right = chain('a', 'b', 'c', 'd', 'e', 'f', 'b');
        var leftFloor = repository(SCOPE, ROOT_SET);
        var rightFloor = repository(SCOPE, ROOT_SET);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<String> leftResult = workers.submit(() -> acceptAfter(
                    start, leftFloor, left));
            Future<String> rightResult = workers.submit(() -> acceptAfter(
                    start, rightFloor, right));
            start.countDown();

            assertThat(List.of(leftResult.get(), rightResult.get()))
                    .satisfiesExactlyInAnyOrder(
                            result -> assertThat(result).isEqualTo("SUCCESS"),
                            result -> assertThat(result).contains("rejected fork"));
        }
    }

    private DatabaseExternalSequenceAnchorBootstrapRootPublicationFloor repository(
            String scopeId, String rootSetId) {
        var result = new DatabaseExternalSequenceAnchorBootstrapRootPublicationFloor(
                database.jdbc(), objectMapper, scopeId, rootSetId,
                database.transactionManager());
        result.init();
        return result;
    }

    private static String acceptAfter(
            CountDownLatch start,
            ExternalSequenceAnchorBootstrapRootPublicationFloor target,
            ExternalSequenceAnchorBootstrapRootPublicationFloor.VerifiedChain chain)
            throws InterruptedException {
        start.await();
        try {
            target.accept(chain);
            return "SUCCESS";
        } catch (IllegalArgumentException rejected) {
            return rejected.getMessage();
        }
    }

    private static ExternalSequenceAnchorBootstrapRootPublicationFloor.VerifiedChain chain(
            char... fingerprints) {
        return chain(SCOPE, ROOT_SET, fingerprints);
    }

    private static ExternalSequenceAnchorBootstrapRootPublicationFloor.VerifiedChain chain(
            String scopeId, String rootSetId, char... fingerprints) {
        java.util.ArrayList<ExternalSequenceAnchorBootstrapRootPublicationFloor.Generation>
                generations = new java.util.ArrayList<>();
        for (int index = 0; index < fingerprints.length; index++) {
            generations.add(new ExternalSequenceAnchorBootstrapRootPublicationFloor.Generation(
                    ExternalSequenceAnchorBootstrapRootPublicationFloor.Generation.SCHEMA_VERSION,
                    scopeId, rootSetId, index + 1L, fingerprint(fingerprints[index]),
                    index == 0 ? "" : fingerprint(fingerprints[index - 1])));
        }
        return new ExternalSequenceAnchorBootstrapRootPublicationFloor.VerifiedChain(
                ExternalSequenceAnchorBootstrapRootPublicationFloor.VerifiedChain.SCHEMA_VERSION,
                scopeId, rootSetId, generations);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
