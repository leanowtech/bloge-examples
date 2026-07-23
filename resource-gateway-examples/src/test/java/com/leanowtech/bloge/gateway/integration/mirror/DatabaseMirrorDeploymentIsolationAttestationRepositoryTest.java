package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationRepository.Reason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMirrorDeploymentIsolationAttestationRepositoryTest {
    private final MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures =
            new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseMirrorDeploymentIsolationAttestationRepository repository;

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
    void persistsPinnedBootstrapAndContinuousSuccessorAcrossRestart() {
        var bootstrap = fixtures.bundle(7);
        var successor = fixtures.bundle(8);
        var stream = stream(bootstrap);

        assertThat(repository.append(bootstrap, 7)).isEqualTo(bootstrap);
        assertThat(repository.append(bootstrap, 7)).isEqualTo(bootstrap);
        assertThat(repository.append(successor, 7)).isEqualTo(successor);

        var restarted = repository();
        assertThat(restarted.current(stream)).contains(successor);
        assertThat(restarted.current(stream,
                MirrorDeploymentIsolationAttestationRepository.CurrentExpectation.from(successor)))
                .contains(successor);
        assertThat(restarted.current(stream,
                MirrorDeploymentIsolationAttestationRepository.CurrentExpectation.from(bootstrap)))
                .isEmpty();
        assertThat(count("mirror_isolation_attestations")).isEqualTo(2);
        assertThat(count("mirror_isolation_attestation_statuses")).isEqualTo(2);
    }

    @Test
    void rejectsTofuBootstrapRollbackGapAndSameRevisionFork() {
        assertReason(() -> repository.append(fixtures.bundle(6), 7),
                Reason.BOOTSTRAP_REVISION_MISMATCH);
        assertReason(() -> repository.append(fixtures.bundle(8), 7),
                Reason.BOOTSTRAP_REVISION_MISMATCH);
        assertThat(count("mirror_isolation_attestation_heads")).isZero();

        var bootstrap = fixtures.bundle(7);
        repository.append(bootstrap, 7);
        assertReason(() -> repository.append(fixtures.bundle(9), 7), Reason.REVISION_GAP);
        assertReason(() -> repository.append(fixtures.bundle(6), 7), Reason.REVISION_ROLLBACK);
        var fork = fixtures.bundle(7, fixtures.scope("org-a"),
                fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('8'));
        assertReason(() -> repository.append(fork, 7), Reason.REVISION_FORK);
    }

    @Test
    void appendsIrreversibleRevocationAndRecoversOnlyExactRetry() {
        var active = repository.append(fixtures.bundle(7), 7);
        var stream = stream(active);
        var expectation = expectation(active);
        Instant revokedAt = active.status().material().effectiveAt().plusSeconds(1);

        var revoked = repository.revoke(stream, expectation,
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.SECURITY_INCIDENT,
                revokedAt);

        assertThat(revoked.active()).isFalse();
        assertThat(revoked.status().material().statusRevision()).isEqualTo(2);
        assertThat(revoked.status().material().previousStatusFingerprint())
                .isEqualTo(active.status().statusFingerprint());
        assertThat(repository.revoke(stream, expectation,
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.SECURITY_INCIDENT,
                revokedAt.plusSeconds(1))).isEqualTo(revoked);
        assertReason(() -> repository.revoke(stream, expectation,
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.POLICY_DRIFT,
                revokedAt.plusSeconds(1)), Reason.STATUS_CONFLICT);
        assertThat(repository.append(active, 7)).isEqualTo(revoked);
        assertThat(repository.current(stream)).contains(revoked);
        assertThat(count("mirror_isolation_attestation_statuses")).isEqualTo(2);

        var successor = repository.append(fixtures.bundle(8), 7);
        assertThat(successor.active()).isTrue();
        assertThat(successor.status().material().statusRevision()).isEqualTo(1);
    }

    @Test
    void rejectsStaleRevocationExpectationAndBackwardTransitionTime() {
        var active = repository.append(fixtures.bundle(7), 7);
        var stale = new MirrorDeploymentIsolationAttestationRepository.CurrentExpectation(
                active.attestation().material().revision(),
                active.attestation().attestationFingerprint(), 1,
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('f'));

        assertReason(() -> repository.revoke(stream(active), stale,
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.OPERATOR_REVOKED,
                Instant.now()), Reason.STATUS_CONFLICT);
        assertReason(() -> repository.revoke(stream(active), expectation(active),
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.OPERATOR_REVOKED,
                active.status().material().effectiveAt().minusSeconds(1)),
                Reason.CANONICAL_INVALID);
        assertThat(repository.current(stream(active))).contains(active);
    }

    @Test
    void isolatesFullEnterpriseScopeAndLocksImmutableDeployment() {
        var orgA = fixtures.bundle(7);
        var orgB = fixtures.bundle(7, fixtures.scope("org-b"),
                fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('8'));
        repository.append(orgA, 7);
        repository.append(orgB, 7);

        assertThat(repository.current(stream(orgA))).contains(orgA);
        assertThat(repository.current(stream(orgB))).contains(orgB);
        var drifted = new MirrorDeploymentIsolationAttestationRepository.StreamIdentity(
                orgA.scope(), fixtures.deployment("cluster-b"),
                fixtures.KEY_SET_ID, fixtures.ATTESTATION_ID);
        assertReason(() -> repository.current(drifted), Reason.IDENTITY_MISMATCH);
        assertThat(count("mirror_isolation_attestation_heads")).isEqualTo(2);
    }

    @Test
    void failsClosedForDamagedBodyStatusAndHeadIndexes() throws Exception {
        var active = repository.append(fixtures.bundle(7), 7);
        jdbc.update("""
                UPDATE mirror_isolation_attestations
                SET attestation_json = REPLACE(attestation_json, 'cluster-a', 'cluster-z')
                """);
        assertReason(() -> repository.current(stream(active)), Reason.STORED_STATE_CORRUPT);

        jdbc.update("UPDATE mirror_isolation_attestations SET attestation_json = ?",
                fixtures.mapper.writeValueAsString(active.attestation()));
        jdbc.update("""
                UPDATE mirror_isolation_attestation_statuses SET status_state = 'REVOKED'
                """);
        assertReason(() -> repository.current(stream(active)), Reason.STORED_STATE_CORRUPT);

        jdbc.update("UPDATE mirror_isolation_attestation_statuses SET status_state = 'ACTIVE'");
        jdbc.update("DELETE FROM mirror_isolation_attestation_statuses");
        assertReason(() -> repository.current(stream(active)), Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void serializesCompetingSuccessorsSoOnlyOneFloorWins() throws Exception {
        var bootstrap = repository.append(fixtures.bundle(7), 7);
        var candidateA = fixtures.bundle(8, fixtures.scope("org-a"),
                fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('8'));
        var candidateB = fixtures.bundle(8, fixtures.scope("org-a"),
                fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('9'));
        var secondInstance = repository();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Object> first = appendAsync(repository, candidateA, ready, start);
        CompletableFuture<Object> second = appendAsync(secondInstance, candidateB, ready, start);
        ready.await();
        start.countDown();
        List<Object> outcomes = List.of(first.get(), second.get());

        assertThat(outcomes.stream()
                .filter(MirrorDeploymentIsolationAttestationBundle.class::isInstance))
                .hasSize(1);
        assertThat(outcomes.stream()
                .filter(MirrorDeploymentIsolationAttestationRepository.Violation.class::isInstance)
                .map(MirrorDeploymentIsolationAttestationRepository.Violation.class::cast)
                .map(MirrorDeploymentIsolationAttestationRepository.Violation::reason)
                .toList()).containsExactly(Reason.REVISION_FORK);
        assertThat(repository.current(stream(bootstrap)).orElseThrow())
                .isIn(candidateA, candidateB);
    }

    @Test
    void schemasStoreOnlyPublicProofAndPayloadFreeControlCoordinates() {
        List<String> heads = columns("MIRROR_ISOLATION_ATTESTATION_HEADS");
        List<String> attestations = columns("MIRROR_ISOLATION_ATTESTATIONS");
        List<String> statuses = columns("MIRROR_ISOLATION_ATTESTATION_STATUSES");

        assertThat(heads).contains("TENANT_ID", "DEPLOYMENT_SCOPE_ID", "KEY_SET_ID",
                "ATTESTATION_ID", "FLOOR_REVISION", "STATUS_REVISION", "STATUS_FINGERPRINT");
        assertThat(attestations).contains("ATTESTATION_FINGERPRINT", "MATERIAL_FINGERPRINT",
                "AUTHORITY_GENERATION", "AUTHORITY_PUBLICATION_FINGERPRINT");
        assertThat(statuses).contains("PREVIOUS_STATUS_FINGERPRINT", "STATUS_STATE",
                "STATUS_REASON", "EFFECTIVE_AT");
        assertThat(heads).noneMatch(DatabaseMirrorDeploymentIsolationAttestationRepositoryTest
                ::payloadColumn);
        assertThat(attestations).noneMatch(
                DatabaseMirrorDeploymentIsolationAttestationRepositoryTest::payloadColumn);
        assertThat(statuses).noneMatch(
                DatabaseMirrorDeploymentIsolationAttestationRepositoryTest::payloadColumn);
    }

    private CompletableFuture<Object> appendAsync(
            DatabaseMirrorDeploymentIsolationAttestationRepository target,
            MirrorDeploymentIsolationAttestationBundle candidate,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                start.await();
                return target.append(candidate, 7);
            } catch (MirrorDeploymentIsolationAttestationRepository.Violation rejected) {
                return rejected;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
    }

    private DatabaseMirrorDeploymentIsolationAttestationRepository repository() {
        var value = new DatabaseMirrorDeploymentIsolationAttestationRepository(
                jdbc, fixtures.mapper, fixtures.attestationIntegrity,
                fixtures.bundleIntegrity, transactions);
        value.init();
        return value;
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION
                """, String.class, table);
    }

    private static MirrorDeploymentIsolationAttestationRepository.StreamIdentity stream(
            MirrorDeploymentIsolationAttestationBundle bundle) {
        return MirrorDeploymentIsolationAttestationRepository.StreamIdentity.from(bundle);
    }

    private static MirrorDeploymentIsolationAttestationRepository.CurrentExpectation expectation(
            MirrorDeploymentIsolationAttestationBundle bundle) {
        return MirrorDeploymentIsolationAttestationRepository.CurrentExpectation.from(bundle);
    }

    private static boolean payloadColumn(String column) {
        return column.contains("PAYLOAD") || column.contains("FIXTURE")
                || column.contains("CONTEXT") || column.contains("SECRET")
                || column.contains("CREDENTIAL");
    }

    private static void assertReason(Runnable action, Reason expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                MirrorDeploymentIsolationAttestationRepository.Violation.class,
                failure -> assertThat(failure.reason()).isEqualTo(expected));
    }
}
