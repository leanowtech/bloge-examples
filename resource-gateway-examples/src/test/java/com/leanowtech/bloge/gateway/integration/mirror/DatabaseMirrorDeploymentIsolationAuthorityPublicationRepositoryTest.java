package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationRepository.Reason;

import static com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationTestFixtures.fingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMirrorDeploymentIsolationAuthorityPublicationRepositoryTest {
    private final MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures =
            new MirrorDeploymentIsolationAuthorityPublicationTestFixtures();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        transactions = new DataSourceTransactionManager(database);
        repository = repository();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsAcrossRestartAndServesOnlyTheCurrentContentAddress() {
        var genesis = fixtures.publication(1, "");
        var stream = stream(genesis);
        var successor = fixtures.publication(2, genesis.publicationFingerprint());

        assertThat(repository.append(genesis)).isEqualTo(genesis);
        assertThat(repository.append(genesis)).isEqualTo(genesis);
        assertThat(repository.append(successor)).isEqualTo(successor);

        var restarted = repository();
        assertThat(restarted.latest(stream)).contains(successor);
        assertThat(restarted.current(stream, 2, successor.publicationFingerprint()))
                .contains(successor);
        assertThat(restarted.current(stream, 1, genesis.publicationFingerprint())).isEmpty();
        assertThat(restarted.floor(stream)).contains(
                new MirrorDeploymentIsolationAuthorityKeySetIntegrity.TrustedFloor(
                        MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID, 2,
                        successor.publicationFingerprint()));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_isolation_authority_publications", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rejectsBootstrapGapWrongPredecessorRollbackAndSameGenerationFork() {
        var fakePredecessor = fingerprint('7');
        assertReason(() -> repository.append(fixtures.publication(2, fakePredecessor)),
                Reason.BOOTSTRAP_GENERATION_INVALID);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_isolation_authority_trusted_floors", Integer.class))
                .isZero();

        var genesis = fixtures.publication(1, "");
        repository.append(genesis);
        assertReason(() -> repository.append(fixtures.publication(
                3, genesis.publicationFingerprint())), Reason.GENERATION_GAP);
        assertReason(() -> repository.append(fixtures.publication(2, fakePredecessor)),
                Reason.PREDECESSOR_MISMATCH);

        var successor = fixtures.publication(2, genesis.publicationFingerprint());
        repository.append(successor);
        assertReason(() -> repository.append(genesis), Reason.GENERATION_ROLLBACK);
        var fork = fixtures.publication(2, genesis.publicationFingerprint(),
                fixtures.scope("org-a"), fixtures.deployment("cluster-a"), fingerprint('8'));
        assertReason(() -> repository.append(fork), Reason.GENERATION_FORK);
    }

    @Test
    void locksImmutableDeploymentIdentityIntoTheFloorStream() {
        var genesis = fixtures.publication(1, "");
        repository.append(genesis);
        var driftedDeployment = fixtures.publication(2, genesis.publicationFingerprint(),
                fixtures.scope("org-a"), fixtures.deployment("cluster-b"),
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.POLICY);

        assertReason(() -> repository.append(driftedDeployment), Reason.IDENTITY_MISMATCH);
        assertThat(repository.latest(stream(genesis))).contains(genesis);
    }

    @Test
    void isolatesIdenticalLogicalIdsByTheCompleteEnterpriseScope() {
        var orgA = fixtures.publication(1, "");
        var orgB = fixtures.publication(1, "", fixtures.scope("org-b"),
                fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.POLICY);

        repository.append(orgA);
        repository.append(orgB);

        assertThat(repository.latest(stream(orgA))).contains(orgA);
        assertThat(repository.latest(stream(orgB))).contains(orgB);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_isolation_authority_trusted_floors", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void failsClosedWhenPersistedJsonOrFloorIndexIsDamaged() throws Exception {
        var genesis = fixtures.publication(1, "");
        repository.append(genesis);
        jdbc.update("""
                UPDATE mirror_isolation_authority_publications
                SET publication_json = REPLACE(publication_json, 'tenant-a', 'tenant-z')
                """);

        assertReason(() -> repository.latest(stream(genesis)), Reason.STORED_STATE_CORRUPT);

        jdbc.update("""
                UPDATE mirror_isolation_authority_publications SET publication_json = ?
                WHERE generation = 1
                """, fixtures.mapper.writeValueAsString(genesis));
        jdbc.update("DELETE FROM mirror_isolation_authority_publications WHERE generation = 1");
        assertReason(() -> repository.latest(stream(genesis)), Reason.STORED_STATE_CORRUPT);
        assertReason(() -> repository.append(fixtures.publication(
                2, genesis.publicationFingerprint())), Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void serializesCompetingSuccessorsSoExactlyOneFloorWins() throws Exception {
        var genesis = fixtures.publication(1, "");
        repository.append(genesis);
        var candidateA = fixtures.publication(2, genesis.publicationFingerprint(),
                fixtures.scope("org-a"), fixtures.deployment("cluster-a"), fingerprint('8'));
        var candidateB = fixtures.publication(2, genesis.publicationFingerprint(),
                fixtures.scope("org-a"), fixtures.deployment("cluster-a"), fingerprint('9'));
        var secondInstance = repository();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Object> first = appendAsync(repository, candidateA, ready, start);
        CompletableFuture<Object> second = appendAsync(secondInstance, candidateB, ready, start);
        ready.await();
        start.countDown();
        List<Object> outcomes = List.of(first.get(), second.get());

        assertThat(outcomes.stream()
                .filter(MirrorDeploymentIsolationAuthorityKeySetPublication.class::isInstance))
                .hasSize(1);
        assertThat(outcomes.stream()
                .filter(MirrorDeploymentIsolationAuthorityPublicationRepository.Violation.class
                        ::isInstance)
                .map(MirrorDeploymentIsolationAuthorityPublicationRepository.Violation.class::cast)
                .map(MirrorDeploymentIsolationAuthorityPublicationRepository.Violation::reason)
                .toList())
                .containsExactly(Reason.GENERATION_FORK);
        var head = repository.latest(stream(genesis)).orElseThrow();
        assertThat(head).isIn(candidateA, candidateB);
        assertThat(repository.floor(stream(genesis)).orElseThrow().publicationFingerprint())
                .isEqualTo(head.publicationFingerprint());
    }

    @Test
    void schemaStoresOnlyPublicTrustMaterialAndPayloadFreeCoordinates() {
        List<String> publicationColumns = columns("MIRROR_ISOLATION_AUTHORITY_PUBLICATIONS");
        List<String> floorColumns = columns("MIRROR_ISOLATION_AUTHORITY_TRUSTED_FLOORS");

        assertThat(publicationColumns).contains("TENANT_ID", "ORGANIZATION_ID", "PROJECT_ID",
                "ENVIRONMENT_ID", "REGION", "DEPLOYMENT_SCOPE_ID", "KEY_SET_ID", "GENERATION",
                "PUBLICATION_FINGERPRINT", "MATERIAL_FINGERPRINT", "PUBLICATION_JSON");
        assertThat(floorColumns).contains("CLUSTER_ID", "NAMESPACE_ID", "WORKLOAD_NAME",
                "SERVICE_ACCOUNT", "IMAGE_DIGEST", "FLOOR_GENERATION",
                "FLOOR_PUBLICATION_FINGERPRINT");
        assertThat(publicationColumns).noneMatch(DatabaseMirrorDeploymentIsolationAuthorityPublicationRepositoryTest
                ::payloadColumn);
        assertThat(floorColumns).noneMatch(DatabaseMirrorDeploymentIsolationAuthorityPublicationRepositoryTest
                ::payloadColumn);
    }

    private CompletableFuture<Object> appendAsync(
            DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository target,
            MirrorDeploymentIsolationAuthorityKeySetPublication publication,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                start.await();
                return target.append(publication);
            } catch (MirrorDeploymentIsolationAuthorityPublicationRepository.Violation rejected) {
                return rejected;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
    }

    private DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository repository() {
        var value = new DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository(
                jdbc, fixtures.mapper, fixtures.integrity, transactions);
        value.init();
        return value;
    }

    private static MirrorDeploymentIsolationAuthorityPublicationRepository.StreamIdentity stream(
            MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
        return MirrorDeploymentIsolationAuthorityPublicationRepository.StreamIdentity.from(
                publication);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION
                """, String.class, table);
    }

    private static boolean payloadColumn(String column) {
        return column.contains("PAYLOAD") || column.contains("FIXTURE")
                || column.contains("CONTEXT") || column.contains("SECRET");
    }

    private static void assertReason(Runnable action, Reason expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                MirrorDeploymentIsolationAuthorityPublicationRepository.Violation.class,
                failure -> assertThat(failure.reason()).isEqualTo(expected));
    }
}
