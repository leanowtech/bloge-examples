package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MirrorRunCommitServiceTest {
    private static final Instant NOW = MirrorPersistenceTestFixtures.COMPILED_AT;

    private EmbeddedDatabase database;
    private TransactionTemplate transactions;
    private DatabaseMirrorRunRequestRepository requests;
    private DatabaseMirrorEvidenceRepository evidence;
    private MirrorRunCommitService commits;
    private ObjectMapper mapper;
    private InMemoryVisualEvidenceSigner signer;
    private AtomicReference<Instant> databaseTime;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        mapper = new ObjectMapper().findAndRegisterModules();
        signer = new InMemoryVisualEvidenceSigner();
        databaseTime = new AtomicReference<>(NOW);
        var integrity = new MirrorEvidenceIntegrityService(mapper, signer,
                java.time.Clock.fixed(NOW.plusSeconds(30), java.time.ZoneOffset.UTC));
        requests = new DatabaseMirrorRunRequestRepository(jdbc, databaseTime::get);
        evidence = new DatabaseMirrorEvidenceRepository(jdbc, mapper, integrity);
        requests.init();
        evidence.init();
        commits = new MirrorRunCommitService(evidence, requests);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void staleLeaseIsRejectedAndCurrentLeaseCommitsBothRecords() {
        CapabilitySnapshot.Scope scope = MirrorPersistenceTestFixtures.scope("org-a");
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(mapper, scope, "plan-1", 'a');
        MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.evidence(
                mapper, signer, plan, "run-1", 'b', "request-1",
                MirrorPersistenceTestFixtures.fingerprint('1'));
        var registration = new MirrorRunRequestRepository.Registration(scope, "request-1",
                MirrorPersistenceTestFixtures.fingerprint('c'),
                bundle.evidence().requestContextFingerprint(), plan.planId(),
                plan.planFingerprint(), NOW.plusSeconds(86_400));
        var first = claim(registration, "owner-a", NOW, NOW.plusSeconds(10));
        var current = claim(registration, "owner-b", NOW.plusSeconds(10),
                NOW.plusSeconds(60));

        databaseTime.set(NOW.plusSeconds(11));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                commits.commit(first.lease(), bundle, observation())))
                .isInstanceOf(MirrorRunLeaseLostException.class);
        assertThat(evidence.find(scope, "run-1")).isEmpty();
        assertThat(requests.find(scope, "request-1")).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MirrorRunRequestRepository.Status.ACTIVE);
            assertThat(state.leaseEpoch()).isEqualTo(2);
        });

        MirrorEvidenceBundle wrongRequest = MirrorPersistenceTestFixtures.evidence(
                mapper, signer, plan, "run-wrong", 'c', "another-request",
                bundle.evidence().requestContextFingerprint());
        databaseTime.set(NOW.plusSeconds(12));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                commits.commit(current.lease(), wrongRequest, observation())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(evidence.find(scope, "run-wrong")).isEmpty();

        databaseTime.set(NOW.plusSeconds(13));
        MirrorEvidenceBundle persisted = transactions.execute(status ->
                commits.commit(current.lease(), bundle, observation()));

        assertThat(persisted).isEqualTo(bundle);
        assertThat(evidence.find(scope, "run-1")).contains(bundle);
        assertThat(requests.find(scope, "request-1")).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MirrorRunRequestRepository.Status.COMPLETED);
            assertThat(state.runId()).isEqualTo("run-1");
            assertThat(state.evidenceBundleFingerprint())
                    .isEqualTo(bundle.bundleFingerprint());
        });
    }

    @Test
    void takeoverBetweenPrecheckAndFencedCompletionRollsBackEvidenceInsert() {
        CapabilitySnapshot.Scope scope = MirrorPersistenceTestFixtures.scope("org-a");
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(mapper, scope, "plan-race", 'd');
        MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.evidence(
                mapper, signer, plan, "run-race", 'e');
        var registration = new MirrorRunRequestRepository.Registration(scope,
                bundle.evidence().requestId(), MirrorPersistenceTestFixtures.fingerprint('f'),
                bundle.evidence().requestContextFingerprint(), plan.planId(),
                plan.planFingerprint(), NOW.plusSeconds(86_400));
        var lease = new MirrorRunRequestRepository.Lease(scope,
                registration.requestId(), "owner-race", 7);
        var state = new MirrorRunRequestRepository.State(registration,
                MirrorRunRequestRepository.Status.ACTIVE, lease.leaseOwner(), lease.leaseEpoch(),
                NOW.plusSeconds(60), "", "", "", NOW, NOW);
        MirrorRunRequestRepository raced = mock(MirrorRunRequestRepository.class);
        when(raced.find(scope, registration.requestId())).thenReturn(Optional.of(state));
        when(raced.complete(lease, bundle.evidence().runId(), bundle.bundleFingerprint()))
                .thenReturn(false);
        MirrorRunCommitService racedCommit = new MirrorRunCommitService(evidence, raced);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                racedCommit.commit(lease, bundle, observation())))
                .isInstanceOf(MirrorRunLeaseLostException.class);

        assertThat(evidence.find(scope, "run-race")).isEmpty();
    }

    @Test
    void expiredLeaseCannotPublishEvidenceEvenBeforeAnotherWorkerTakesOver() {
        CapabilitySnapshot.Scope scope = MirrorPersistenceTestFixtures.scope("org-a");
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(mapper, scope, "plan-expired", '1');
        MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.evidence(
                mapper, signer, plan, "run-expired", '2');
        var registration = new MirrorRunRequestRepository.Registration(scope,
                bundle.evidence().requestId(), MirrorPersistenceTestFixtures.fingerprint('3'),
                bundle.evidence().requestContextFingerprint(), plan.planId(),
                plan.planFingerprint(), NOW.plusSeconds(86_400));
        var claim = claim(registration, "owner-expired", NOW, NOW.plusSeconds(10));

        databaseTime.set(NOW.plusSeconds(10));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                commits.commit(claim.lease(), bundle, observation())))
                .isInstanceOf(MirrorRunLeaseLostException.class);

        assertThat(evidence.find(scope, "run-expired")).isEmpty();
        assertThat(requests.find(scope, registration.requestId())).get()
                .extracting(MirrorRunRequestRepository.State::status)
                .isEqualTo(MirrorRunRequestRepository.Status.ACTIVE);
    }

    private MirrorRunRequestRepository.Claim claim(
            MirrorRunRequestRepository.Registration registration,
            String owner,
            Instant now,
            Instant expiresAt) {
        databaseTime.set(now);
        return transactions.execute(status -> requests.claim(
                registration, owner, Duration.between(now, expiresAt)));
    }

    private static MirrorOperationObservability.Observation observation() {
        return MirrorOperationObservability.noop().start(
                MirrorOperationAuditEvent.Operation.RUN_CREATE,
                MirrorPersistenceTestFixtures.identity("org-a"),
                "request-1", "plan-1", "");
    }
}
