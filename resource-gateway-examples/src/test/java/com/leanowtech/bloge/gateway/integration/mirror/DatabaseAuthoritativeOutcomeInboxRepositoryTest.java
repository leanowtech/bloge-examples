package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseAuthoritativeOutcomeInboxRepositoryTest {
    private static final AuthoritativeOutcomeInboxPolicy POLICY =
            new AuthoritativeOutcomeInboxPolicy(
                    Duration.ofSeconds(10),
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(10),
                    3,
                    Duration.ofDays(30));

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(
                    DomainFidelityTestFixtures.NOW);
    private final MutableClock clock =
            new MutableClock(now);

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AuthoritativeOutcomeObservationIntegrity integrity;
    private DatabaseAuthoritativeOutcomeInboxRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions =
                new DataSourceTransactionManager(database);
        integrity = new AuthoritativeOutcomeObservationIntegrity(
                mapper,
                InMemoryVisualEvidenceSigner.usingClock(clock),
                alwaysTrusted(),
                clock);
        repository =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        jdbc,
                        mapper,
                        integrity,
                        transactions,
                        now::get);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void appendsOneInitialRevisionAndRecoversExactReplayAfterRestart() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        AuthoritativeOutcomeInboxRepository.Admission admitted =
                repository.append(pending, "");
        DatabaseAuthoritativeOutcomeInboxRepository restarted =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        jdbc,
                        mapper,
                        integrity,
                        transactions,
                        now::get);
        restarted.init();

        assertThat(admitted.idempotentReplay()).isFalse();
        assertThat(admitted.entry().status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.QUEUED);
        assertThat(restarted.append(
                pending, "").idempotentReplay()).isTrue();
        assertThat(restarted.findObservation(
                pending.scope(),
                pending.observationId(),
                1)).contains(pending);
        assertThat(restarted.findLatestObservation(
                pending.scope(),
                pending.observationId())).contains(pending);
        assertThat(restarted.lifecycle(
                pending.scope(),
                pending.observationId(),
                0,
                10))
                .extracting(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.OBSERVATION_APPENDED);
    }

    @Test
    void serializesConcurrentInitialAdmissionAsOneExactReplay()
            throws Exception {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Callable<AuthoritativeOutcomeInboxRepository.Admission>
                    submit = () -> repository.append(
                    pending, "");
            Future<AuthoritativeOutcomeInboxRepository.Admission>
                    first = executor.submit(submit);
            Future<AuthoritativeOutcomeInboxRepository.Admission>
                    second = executor.submit(submit);

            assertThat(List.of(
                    first.get().idempotentReplay(),
                    second.get().idempotentReplay()))
                    .containsExactlyInAnyOrder(
                            false, true);
        }
    }

    @Test
    void appendsContinuousSuccessorAndKeepsHistoricalRevision() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        advance(Duration.ofDays(1));
        AuthoritativeOutcomeObservation matched =
                integrity.sign(successor(
                        pending,
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH,
                        List.of(
                                AuthoritativeOutcomeTestFixtures
                                        .fact(
                                                "settlement-ledger",
                                                "settlement-002",
                                                'a',
                                                'e',
                                                true)),
                        List.of(
                                AuthoritativeOutcomeTestFixtures
                                        .watermark(
                                                "settlement-ledger",
                                                AuthoritativeOutcomeTestFixtures
                                                        .WINDOW_CLOSES_AT)),
                        now.get().minusSeconds(1)));

        AuthoritativeOutcomeInboxRepository.Admission admitted =
                repository.append(
                        matched,
                        pending.observationFingerprint());

        assertThat(admitted.entry().status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.SETTLED);
        assertThat(admitted.entry().reconciliation()).isEqualTo(
                AuthoritativeOutcomeObservation
                        .Reconciliation.MATCH);
        assertThat(repository.findObservation(
                pending.scope(),
                pending.observationId(),
                1)).contains(pending);
        assertThat(repository.findObservation(
                pending.scope(),
                pending.observationId(),
                2)).contains(matched);
        assertThat(repository.append(
                matched,
                pending.observationFingerprint())
                .idempotentReplay()).isTrue();
    }

    @Test
    void rejectsSkippedRevisionWrongPredecessorAndImmutableCoordinateDrift() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        advance(Duration.ofDays(1));
        AuthoritativeOutcomeObservation matched =
                integrity.sign(successor(
                        pending,
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH,
                        List.of(
                                AuthoritativeOutcomeTestFixtures
                                        .fact(
                                                "settlement-ledger",
                                                "settlement-003",
                                                'a',
                                                'e',
                                                true)),
                        List.of(
                                AuthoritativeOutcomeTestFixtures
                                        .watermark(
                                                "settlement-ledger",
                                                AuthoritativeOutcomeTestFixtures
                                                        .WINDOW_CLOSES_AT)),
                        now.get().minusSeconds(1)));

        assertReason(
                () -> repository.append(
                        matched,
                        AuthoritativeOutcomeTestFixtures
                                .fingerprint('f')),
                AuthoritativeOutcomeInboxRepository
                        .Reason.LINEAGE_CONFLICT);

        AuthoritativeOutcomeObservation drift =
                replaceOutcomeDefinition(
                        matched,
                        AuthoritativeOutcomeTestFixtures.ref(
                                "OUTCOME_DEFINITION",
                                "different-definition",
                                'f'));
        assertReason(
                () -> repository.append(
                        integrity.sign(drift),
                        pending.observationFingerprint()),
                AuthoritativeOutcomeInboxRepository
                        .Reason.SUCCESSOR_INVALID);
    }

    @Test
    void pollsNoChangeWithoutConsumingFailureBudget() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        AuthoritativeOutcomeInboxRepository.Claim claim =
                claim("worker-a");

        AuthoritativeOutcomeInboxEntry queued =
                repository.noChange(
                        claim.lease(), POLICY);

        assertThat(queued.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.QUEUED);
        assertThat(queued.consecutiveFailures()).isZero();
        assertThat(claim("worker-a").outcome()).isEqualTo(
                AuthoritativeOutcomeInboxRepository
                        .Claim.Outcome.NO_WORK);
        advance(POLICY.pollingInterval());
        assertThat(claim("worker-a").outcome()).isEqualTo(
                AuthoritativeOutcomeInboxRepository
                        .Claim.Outcome.ACQUIRED);
    }

    @Test
    void recoversExpiredLeaseAndFencesTheOldWorker() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        AuthoritativeOutcomeInboxRepository.Claim first =
                claim("worker-a");

        advance(POLICY.leaseDuration()
                .plusMillis(1));
        assertThat(claim("worker-b").outcome()).isEqualTo(
                AuthoritativeOutcomeInboxRepository
                        .Claim.Outcome.NO_WORK);
        advance(POLICY.initialRetryDelay());
        AuthoritativeOutcomeInboxRepository.Claim second =
                claim("worker-b");

        assertThat(second.lease().epoch())
                .isGreaterThan(first.lease().epoch());
        assertReason(
                () -> repository.noChange(
                        first.lease(), POLICY),
                AuthoritativeOutcomeInboxRepository
                        .Reason.LEASE_LOST);
        assertThat(repository.noChange(
                second.lease(), POLICY).status())
                .isEqualTo(
                        AuthoritativeOutcomeInboxEntry
                                .Status.QUEUED);
        assertThat(repository.lifecycle(
                pending.scope(),
                pending.observationId(),
                0,
                20))
                .extracting(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.OBSERVATION_APPENDED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.CLAIMED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.LEASE_EXPIRED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.CLAIMED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.NO_CHANGE);
    }

    @Test
    void quarantinesAfterBoundedConsecutiveFailuresWithoutChangingOutcome() {
        AuthoritativeOutcomeInboxPolicy twoFailures =
                new AuthoritativeOutcomeInboxPolicy(
                        POLICY.leaseDuration(),
                        POLICY.pollingInterval(),
                        POLICY.initialRetryDelay(),
                        POLICY.maximumRetryDelay(),
                        2,
                        POLICY.maximumPendingAge());
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        AuthoritativeOutcomeInboxRepository.Claim first =
                repository.claimNext(
                        pending.scope().region(),
                        pending.scope().environmentId(),
                        "worker-a",
                        twoFailures);
        AuthoritativeOutcomeInboxEntry retry =
                repository.fail(
                        first.lease(),
                        "RG.MIRROR.OUTCOME.AUTHORITY_UNAVAILABLE",
                        true,
                        twoFailures);
        advance(twoFailures.initialRetryDelay());
        AuthoritativeOutcomeInboxRepository.Claim second =
                repository.claimNext(
                        pending.scope().region(),
                        pending.scope().environmentId(),
                        "worker-b",
                        twoFailures);

        AuthoritativeOutcomeInboxEntry quarantined =
                repository.fail(
                        second.lease(),
                        "RG.MIRROR.OUTCOME.AUTHORITY_UNAVAILABLE",
                        true,
                        twoFailures);

        assertThat(retry.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.QUEUED);
        assertThat(quarantined.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry
                        .Status.QUARANTINED);
        assertThat(quarantined.reconciliation()).isEqualTo(
                AuthoritativeOutcomeObservation
                        .Reconciliation.PENDING);
        assertThat(quarantined.failureCode()).isEqualTo(
                "RG.MIRROR.OUTCOME.AUTHORITY_UNAVAILABLE");
    }

    @Test
    void publishesWorkerSuccessorAtomicallyAndRecognizesCommitReplay() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        AuthoritativeOutcomeInboxRepository.Claim claim =
                claim("worker-a");
        advance(Duration.ofSeconds(1));
        AuthoritativeOutcomeObservation matched =
                integrity.sign(successor(
                        pending,
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH,
                        List.of(
                                AuthoritativeOutcomeTestFixtures
                                        .fact(
                                                "settlement-ledger",
                                                "settlement-004",
                                                'a',
                                                'e',
                                                true)),
                        List.of(
                                AuthoritativeOutcomeTestFixtures
                                        .watermark(
                                                "settlement-ledger",
                                                AuthoritativeOutcomeTestFixtures
                                                        .WINDOW_CLOSES_AT)),
                        now.get().minusSeconds(1)));

        AuthoritativeOutcomeInboxEntry settled =
                repository.publishSuccessor(
                        claim.lease(),
                        matched,
                        POLICY);
        AuthoritativeOutcomeInboxEntry replay =
                repository.publishSuccessor(
                        claim.lease(),
                        matched,
                        POLICY);

        assertThat(settled.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.SETTLED);
        assertThat(replay).isEqualTo(settled);
        assertThat(repository.lifecycle(
                pending.scope(),
                pending.observationId(),
                0,
                20))
                .extracting(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.OBSERVATION_APPENDED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.CLAIMED,
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.SUCCESSOR_APPENDED);
    }

    @Test
    void externallyAppendedSuccessorFencesAnInFlightWorker() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        AuthoritativeOutcomeInboxRepository.Claim claim =
                claim("worker-a");
        advance(Duration.ofSeconds(1));
        AuthoritativeOutcomeObservation matched =
                integrity.sign(successor(
                        pending,
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH,
                        List.of(
                                AuthoritativeOutcomeTestFixtures
                                        .fact(
                                                "settlement-ledger",
                                                "settlement-external",
                                                'a',
                                                'e',
                                                true)),
                        List.of(
                                AuthoritativeOutcomeTestFixtures
                                        .watermark(
                                                "settlement-ledger",
                                                AuthoritativeOutcomeTestFixtures
                                                        .WINDOW_CLOSES_AT)),
                        now.get().minusMillis(1)));

        AuthoritativeOutcomeInboxEntry advanced =
                repository.append(
                        matched,
                        pending.observationFingerprint())
                        .entry();

        assertThat(advanced.status()).isEqualTo(
                AuthoritativeOutcomeInboxEntry.Status.SETTLED);
        assertReason(
                () -> repository.noChange(
                        claim.lease(), POLICY),
                AuthoritativeOutcomeInboxRepository
                        .Reason.LEASE_LOST);
    }

    @Test
    void headReadDetectsDeletedLifecycleTail() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        AuthoritativeOutcomeInboxRepository.Claim claim =
                claim("worker-a");
        repository.noChange(claim.lease(), POLICY);
        CapabilitySnapshot.Scope scope = pending.scope();
        Long lastOrdinal = jdbc.queryForObject("""
                        SELECT MAX(event_ordinal)
                        FROM mirror_outcome_inbox_lifecycle
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND observation_id = ?
                        """,
                Long.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                pending.observationId());
        jdbc.update("""
                        DELETE FROM mirror_outcome_inbox_lifecycle
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND observation_id = ?
                          AND event_ordinal = ?
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                pending.observationId(),
                lastOrdinal);

        assertReason(
                () -> repository.findEntry(
                        scope, pending.observationId()),
                AuthoritativeOutcomeInboxRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void detectsTamperedHeadAndObservationStorage() {
        AuthoritativeOutcomeObservation pending =
                integrity.sign(pending());
        repository.append(pending, "");
        CapabilitySnapshot.Scope scope = pending.scope();

        jdbc.update("""
                        UPDATE mirror_outcome_inbox_heads
                        SET consecutive_failures = 99
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND observation_id = ?
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                pending.observationId());

        assertReason(
                () -> repository.findEntry(
                        scope, pending.observationId()),
                AuthoritativeOutcomeInboxRepository
                        .Reason.STORED_STATE_CORRUPT);

        jdbc.update("""
                        UPDATE mirror_outcome_inbox_heads
                        SET consecutive_failures = 0
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND observation_id = ?
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                pending.observationId());
        jdbc.update("""
                        UPDATE mirror_outcome_observations
                        SET unit_id = 'tampered-unit'
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND observation_id = ?
                          AND revision = 1
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                pending.observationId());

        assertReason(
                () -> repository.findObservation(
                        scope,
                        pending.observationId(),
                        1),
                AuthoritativeOutcomeInboxRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void rejectsTransactionManagerWithoutNestedSavepoints() {
        transactions.setNestedTransactionAllowed(false);

        assertThatThrownBy(() ->
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        jdbc,
                        mapper,
                        integrity,
                        transactions,
                        now::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "nested-savepoint DataSourceTransactionManager");
    }

    private AuthoritativeOutcomeInboxRepository.Claim claim(
            String ownerId) {
        CapabilitySnapshot.Scope scope =
                DomainFidelityTestFixtures.scope("support");
        return repository.claimNext(
                scope.region(),
                scope.environmentId(),
                ownerId,
                POLICY);
    }

    private void advance(Duration duration) {
        now.updateAndGet(value ->
                value.plus(duration));
    }

    private static AuthoritativeOutcomeObservation pending() {
        return AuthoritativeOutcomeTestFixtures.observation(
                AuthoritativeOutcomeObservation
                        .Reconciliation.PENDING,
                List.of(),
                List.of(
                        AuthoritativeOutcomeTestFixtures
                                .watermark(
                                        "settlement-ledger",
                                        AuthoritativeOutcomeTestFixtures
                                                .WINDOW_CLOSES_AT
                                                .minusSeconds(1))));
    }

    private static AuthoritativeOutcomeObservation successor(
            AuthoritativeOutcomeObservation previous,
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation,
            List<AuthoritativeOutcomeObservation.AuthorityFact>
                    facts,
            List<AuthoritativeOutcomeObservation.AuthorityWatermark>
                    watermarks,
            Instant reconciledAt) {
        return new AuthoritativeOutcomeObservation(
                previous.schemaVersion(),
                previous.observationId(),
                previous.revision() + 1,
                "",
                previous.scope(),
                previous.inventoryRef(),
                previous.unitId(),
                previous.scenarioCaseRef(),
                previous.targetCapabilityRef(),
                previous.outcomeDefinitionRef(),
                previous.attributionPolicyRef(),
                previous.authoritySetRef(),
                previous.selectionProof(),
                previous.subjectFingerprint(),
                previous.attributionKeyFingerprint(),
                previous.modelOutcomeFingerprint(),
                previous.attributionWindow(),
                reconciledAt,
                reconciledAt,
                watermarks,
                facts,
                reconciliation,
                facts.stream().allMatch(
                        AuthoritativeOutcomeObservation
                                .AuthorityFact::evidenceComplete),
                VisualRunEvidenceSeal.unsigned());
    }

    private static AuthoritativeOutcomeObservation
    replaceOutcomeDefinition(
            AuthoritativeOutcomeObservation source,
            MirrorArtifactRef definition) {
        return new AuthoritativeOutcomeObservation(
                source.schemaVersion(),
                source.observationId(),
                source.revision(),
                "",
                source.scope(),
                source.inventoryRef(),
                source.unitId(),
                source.scenarioCaseRef(),
                source.targetCapabilityRef(),
                definition,
                source.attributionPolicyRef(),
                source.authoritySetRef(),
                source.selectionProof(),
                source.subjectFingerprint(),
                source.attributionKeyFingerprint(),
                source.modelOutcomeFingerprint(),
                source.attributionWindow(),
                source.reconciledAt(),
                source.attestedAt(),
                source.authorityWatermarks(),
                source.authorityFacts(),
                source.reconciliation(),
                source.evidenceComplete(),
                VisualRunEvidenceSeal.unsigned());
    }

    private static AuthoritativeOutcomeAuthorityVerifier
    alwaysTrusted() {
        return new AuthoritativeOutcomeAuthorityVerifier() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void verify(
                    AuthoritativeOutcomeObservation observation) {
                assertThat(observation.authorityWatermarks())
                        .isNotEmpty();
            }
        };
    }

    private static void assertReason(
            Runnable action,
            AuthoritativeOutcomeInboxRepository.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        AuthoritativeOutcomeInboxRepository
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(reason);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(
                AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException(
                        "test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
