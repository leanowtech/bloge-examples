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
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void certifiableCommitPinsDurableAdmissionAndHoldsPermitThroughTransactionCompletion() {
        CapabilitySnapshot.Scope scope = MirrorPersistenceTestFixtures.scope("org-trust");
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(mapper, scope, "plan-trust", '4');
        MirrorDeploymentIsolationRunTrust.Admission admission =
                MirrorPersistenceTestFixtures.trustAdmission(scope);
        MirrorDeploymentIsolationRunTrust.Binding binding =
                MirrorPersistenceTestFixtures.trustBinding(scope);
        MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.certifiableEvidence(
                mapper, signer, plan, "run-trust", '5', "request-trust", fingerprint('6'),
                binding);
        MirrorRunRequestRepository.Registration registration =
                new MirrorRunRequestRepository.Registration(scope, "request-trust",
                        fingerprint('7'), bundle.evidence().requestContextFingerprint(),
                        plan.planId(), plan.planFingerprint(), NOW.plusSeconds(86_400),
                        MirrorRunRequestRepository.TrustDecision.certification(admission));
        MirrorRunRequestRepository.Claim claim = claim(registration, "owner-trust", NOW,
                NOW.plusSeconds(60), MirrorRunRequestRepository.TrustAttempt.from(admission));
        RecordingTrustAuthority authority = new RecordingTrustAuthority(binding);
        MirrorRunCommitService trustedCommit = new MirrorRunCommitService(
                evidence, requests, authority);
        databaseTime.set(NOW.plusSeconds(13));

        MirrorEvidenceBundle persisted = transactions.execute(status -> {
            MirrorEvidenceBundle value = trustedCommit.commit(
                    claim.lease(), bundle, observation());
            assertThat(authority.permitClosed).isFalse();
            return value;
        });

        assertThat(persisted).isEqualTo(bundle);
        assertThat(authority.permitClosed).isTrue();
        assertThat(evidence.find(scope, "run-trust")).contains(bundle);
    }

    @Test
    void certifiableCommitRejectsEvidenceThatDiffersFromDurableAdmission() {
        CapabilitySnapshot.Scope scope = MirrorPersistenceTestFixtures.scope("org-mismatch");
        MirrorPlan plan = MirrorPersistenceTestFixtures.plan(
                mapper, scope, "plan-trust-mismatch", '8');
        MirrorDeploymentIsolationRunTrust.Admission admission =
                MirrorPersistenceTestFixtures.trustAdmission(scope);
        MirrorDeploymentIsolationRunTrust.Binding expected =
                MirrorPersistenceTestFixtures.trustBinding(scope);
        MirrorArtifactRef changedDecision = new MirrorArtifactRef(
                MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                expected.decisionRef().id(), expected.decisionRef().revision() + 1,
                fingerprint('9'));
        MirrorArtifactRef changedStatus = new MirrorArtifactRef(
                MirrorDeploymentIsolationAttestationStatusPublication.ARTIFACT_KIND,
                expected.statusRef().id(), changedDecision.revision(), fingerprint('a'));
        MirrorDeploymentIsolationRunTrust.Binding mismatched =
                new MirrorDeploymentIsolationRunTrust.Binding("", changedDecision,
                        expected.authorityKeySetRef(), expected.attestationRef(), changedStatus,
                        expected.admittedSnapshotRef(), expected.committedSnapshotRef(),
                        expected.admittedAt(), expected.confirmedAt());
        MirrorEvidenceBundle bundle = MirrorPersistenceTestFixtures.certifiableEvidence(
                mapper, signer, plan, "run-trust-mismatch", 'b', "request-trust-mismatch",
                fingerprint('c'), mismatched);
        MirrorRunRequestRepository.Registration registration =
                new MirrorRunRequestRepository.Registration(scope,
                        bundle.evidence().requestId(), fingerprint('d'),
                        bundle.evidence().requestContextFingerprint(), plan.planId(),
                        plan.planFingerprint(), NOW.plusSeconds(86_400),
                        MirrorRunRequestRepository.TrustDecision.certification(admission));
        MirrorRunRequestRepository.Claim claim = claim(registration, "owner-mismatch", NOW,
                NOW.plusSeconds(60), MirrorRunRequestRepository.TrustAttempt.from(admission));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                new MirrorRunCommitService(evidence, requests,
                        new RecordingTrustAuthority(expected)).commit(
                        claim.lease(), bundle, observation())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable trust admission");
        assertThat(evidence.find(scope, bundle.evidence().runId())).isEmpty();
    }

    private MirrorRunRequestRepository.Claim claim(
            MirrorRunRequestRepository.Registration registration,
            String owner,
            Instant now,
            Instant expiresAt) {
        return claim(registration, owner, now, expiresAt, null);
    }

    private MirrorRunRequestRepository.Claim claim(
            MirrorRunRequestRepository.Registration registration,
            String owner,
            Instant now,
            Instant expiresAt,
            MirrorRunRequestRepository.TrustAttempt trustAttempt) {
        databaseTime.set(now);
        return transactions.execute(status -> requests.claim(
                registration, owner, Duration.between(now, expiresAt), trustAttempt));
    }

    private static MirrorOperationObservability.Observation observation() {
        return MirrorOperationObservability.noop().start(
                MirrorOperationAuditEvent.Operation.RUN_CREATE,
                MirrorPersistenceTestFixtures.identity("org-a"),
                "request-1", "plan-1", "");
    }

    private static String fingerprint(char material) {
        return MirrorPersistenceTestFixtures.fingerprint(material);
    }

    private static final class RecordingTrustAuthority
            implements MirrorDeploymentIsolationRunTrustAuthority {
        private final MirrorDeploymentIsolationRunTrust.Binding expected;
        private final AtomicBoolean permitClosed = new AtomicBoolean();

        private RecordingTrustAuthority(MirrorDeploymentIsolationRunTrust.Binding expected) {
            this.expected = expected;
        }

        @Override
        public MirrorDeploymentIsolationRunTrust.Admission admit(
                CapabilitySnapshot.Scope scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MirrorDeploymentIsolationRunTrust.Binding confirm(
                MirrorDeploymentIsolationRunTrust.Admission admission,
                Instant startedAt,
                Instant completedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommitPermit acquireCommitPermit(
                CapabilitySnapshot.Scope scope,
                MirrorDeploymentIsolationRunTrust.Binding binding) {
            if (!expected.equals(binding)) {
                throw new TrustException("RUN_TRUST_DECISION_CHANGED");
            }
            return () -> permitClosed.set(true);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
