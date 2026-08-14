package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseAuthoritativeOutcomeSourceCheckpointRepositoryTest {
    private static final AuthoritativeOutcomeSourceCheckpointRepository.Policy POLICY =
            new AuthoritativeOutcomeSourceCheckpointRepository.Policy(
                    Duration.ofSeconds(30), Duration.ofSeconds(2),
                    Duration.ofMinutes(1), Duration.ofSeconds(10), 3);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-08-03T00:00:00Z"));
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseAuthoritativeOutcomeSourceCheckpointRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions = new DataSourceTransactionManager(database);
        repository = new DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
                jdbc, mapper, transactions, now::get);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void registersLiveBaselineAndRecoversExactReplayAfterRestart() {
        var registration = AuthoritativeOutcomeSourceTestFixtures.liveRegistration();
        var admitted = repository.registerLive(registration);
        DatabaseAuthoritativeOutcomeSourceCheckpointRepository restarted =
                new DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
                        jdbc, mapper, transactions, now::get);
        restarted.init();

        assertThat(admitted.idempotentReplay()).isFalse();
        assertThat(admitted.snapshot().committedSequence()).isZero();
        assertThat(restarted.registerLive(registration).idempotentReplay()).isTrue();
        assertThat(restarted.find(registration.key()))
                .get()
                .extracting(AuthoritativeOutcomeSourceCheckpointRepository.Snapshot::status)
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.ACTIVE);
    }

    @Test
    void stagesBeforeApplyAndCommitsOnlyTheExactPage() {
        var registration = AuthoritativeOutcomeSourceTestFixtures.liveRegistration();
        repository.registerLive(registration);
        var claim = claim("worker-a");
        AuthoritativeOutcomeSourcePage page =
                AuthoritativeOutcomeSourceTestFixtures.livePage(mapper);

        var staged = repository.stage(claim.lease(), page);
        DatabaseAuthoritativeOutcomeSourceCheckpointRepository restarted =
                new DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
                        jdbc, mapper, transactions, now::get);
        restarted.init();

        assertThat(staged.hasStagedPage()).isTrue();
        assertThat(restarted.find(registration.key()))
                .get()
                .extracting(AuthoritativeOutcomeSourceCheckpointRepository.Snapshot::stagedPageFingerprint)
                .isEqualTo(page.pageFingerprint());
        assertThatThrownBy(() -> repository.commit(
                claim.lease(), AuthoritativeOutcomeSourceTestFixtures.fingerprint('f'), POLICY))
                .isInstanceOf(AuthoritativeOutcomeSourceCheckpointRepository.Violation.class)
                .extracting("reason")
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Reason.PAGE_CONFLICT);

        var committed = repository.commit(claim.lease(), page.pageFingerprint(), POLICY);
        assertThat(committed.committedSequence()).isEqualTo(1);
        assertThat(committed.committedCursorRef()).isEqualTo(page.nextCursorRef());
        assertThat(committed.hasStagedPage()).isFalse();
        assertThat(committed.status())
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.ACTIVE);
    }

    @Test
    void retryPreservesTheStagedPageAndExpiredLeaseCanBeTakenOver() {
        repository.registerLive(AuthoritativeOutcomeSourceTestFixtures.liveRegistration());
        var first = claim("worker-a");
        AuthoritativeOutcomeSourcePage page =
                AuthoritativeOutcomeSourceTestFixtures.livePage(mapper);
        repository.stage(first.lease(), page);
        var failed = repository.fail(
                first.lease(), "RG.MIRROR.OUTCOME_SOURCE.SOURCE_UNAVAILABLE", true, POLICY);

        assertThat(failed.status())
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.ACTIVE);
        assertThat(failed.hasStagedPage()).isTrue();
        now.set(failed.nextEligibleAt());
        var second = claim("worker-b");
        assertThat(second.stagedPage()).isEqualTo(page);
        assertThat(second.lease().epoch()).isGreaterThan(first.lease().epoch());
        assertThatThrownBy(() -> repository.heartbeat(first.lease(), POLICY))
                .isInstanceOf(AuthoritativeOutcomeSourceCheckpointRepository.Violation.class)
                .extracting("reason")
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Reason.LEASE_LOST);
    }

    @Test
    void backfillUsesAnIndependentCursorAndCanTerminateWithoutMovingLive() {
        var live = repository.registerLive(
                AuthoritativeOutcomeSourceTestFixtures.liveRegistration()).snapshot();
        var command = AuthoritativeOutcomeSourceTestFixtures.backfill(mapper);
        var backfill = repository.registerBackfill(command);

        assertThat(backfill.snapshot().key().streamKind())
                .isEqualTo(AuthoritativeOutcomeSourcePage.StreamKind.BACKFILL);
        assertThat(backfill.snapshot().controlCommandRef()).isEqualTo(command.artifactRef());
        var claimed = claim("worker-backfill");
        var completed = repository.release(
                claimed.lease(),
                AuthoritativeOutcomeSourceCheckpointRepository.Release.STREAM_COMPLETE,
                POLICY);

        assertThat(completed.status())
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Status.COMPLETE);
        assertThat(repository.find(live.key())).contains(live);
    }

    @Test
    void externalRevocationFencesEveryStreamAndInvalidatesOldLease() {
        repository.registerLive(AuthoritativeOutcomeSourceTestFixtures.liveRegistration());
        repository.registerBackfill(AuthoritativeOutcomeSourceTestFixtures.backfill(mapper));
        var liveLease = claim("worker-a").lease();

        var command = AuthoritativeOutcomeSourceTestFixtures.revoke(mapper);
        var revoked = repository.revokeGeneration(command);

        assertThat(revoked.affectedStreamCount()).isEqualTo(2);
        assertThat(repository.revokeGeneration(command).idempotentReplay()).isTrue();
        assertThatThrownBy(() -> repository.heartbeat(liveLease, POLICY))
                .isInstanceOf(AuthoritativeOutcomeSourceCheckpointRepository.Violation.class)
                .extracting("reason")
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Reason.LEASE_LOST);
        assertThat(repository.claimNext(
                AuthoritativeOutcomeSourceTestFixtures.scope().region(),
                AuthoritativeOutcomeSourceTestFixtures.scope().environmentId(),
                "worker-c", POLICY).outcome())
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Claim.Outcome.NO_WORK);
    }

    @Test
    void partitionLockAllowsOnlyOneReplicaToClaimTheStream() throws Exception {
        repository.registerLive(AuthoritativeOutcomeSourceTestFixtures.liveRegistration());
        DatabaseAuthoritativeOutcomeSourceCheckpointRepository replica =
                new DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
                        jdbc, mapper, transactions, now::get);
        replica.init();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<AuthoritativeOutcomeSourceCheckpointRepository.Claim> first =
                    executor.submit(() -> repository.claimNext(
                            AuthoritativeOutcomeSourceTestFixtures.scope().region(),
                            AuthoritativeOutcomeSourceTestFixtures.scope().environmentId(),
                            "worker-a", POLICY));
            Future<AuthoritativeOutcomeSourceCheckpointRepository.Claim> second =
                    executor.submit(() -> replica.claimNext(
                            AuthoritativeOutcomeSourceTestFixtures.scope().region(),
                            AuthoritativeOutcomeSourceTestFixtures.scope().environmentId(),
                            "worker-b", POLICY));

            assertThat(List.of(first.get().outcome(), second.get().outcome()))
                    .containsExactlyInAnyOrder(
                            AuthoritativeOutcomeSourceCheckpointRepository.Claim.Outcome.ACQUIRED,
                            AuthoritativeOutcomeSourceCheckpointRepository.Claim.Outcome.NO_WORK);
        }
    }

    @Test
    void directSnapshotMutationFailsClosedOnReadAndRestart() {
        var key = AuthoritativeOutcomeSourceTestFixtures.liveRegistration().key();
        repository.registerLive(AuthoritativeOutcomeSourceTestFixtures.liveRegistration());
        jdbc.update("""
                UPDATE mirror_outcome_source_checkpoints
                SET committed_sequence = 99
                WHERE connector_id = ?
                """, key.connectorId());

        assertThatThrownBy(() -> repository.find(key))
                .isInstanceOf(AuthoritativeOutcomeSourceCheckpointRepository.Violation.class)
                .extracting("reason")
                .isEqualTo(AuthoritativeOutcomeSourceCheckpointRepository.Reason.STORAGE_INVALID);
        DatabaseAuthoritativeOutcomeSourceCheckpointRepository restarted =
                new DatabaseAuthoritativeOutcomeSourceCheckpointRepository(
                        jdbc, mapper, transactions, now::get);
        assertThatThrownBy(restarted::init)
                .isInstanceOf(AuthoritativeOutcomeSourceCheckpointRepository.Violation.class);
    }

    private AuthoritativeOutcomeSourceCheckpointRepository.Claim claim(String owner) {
        return repository.claimNext(
                AuthoritativeOutcomeSourceTestFixtures.scope().region(),
                AuthoritativeOutcomeSourceTestFixtures.scope().environmentId(), owner, POLICY);
    }
}
