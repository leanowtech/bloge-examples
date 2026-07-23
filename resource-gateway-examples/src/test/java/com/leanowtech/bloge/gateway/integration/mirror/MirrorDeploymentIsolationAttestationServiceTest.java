package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MirrorDeploymentIsolationAttestationServiceTest {
    private final MirrorDeploymentIsolationAttestationRepositoryTestFixtures fixtures =
            new MirrorDeploymentIsolationAttestationRepositoryTestFixtures();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository authorityRepository;
    private DatabaseMirrorDeploymentIsolationAttestationRepository attestationRepository;
    private MirrorDeploymentIsolationAuthorityKeySetPublication authorityPublication;
    private RecordingAudit audit;
    private MirrorDeploymentIsolationAttestationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        transactions = new DataSourceTransactionManager(database);
        authorityRepository = new DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository(
                jdbc, fixtures.mapper, fixtures.authorityIntegrity, transactions);
        authorityRepository.init();
        attestationRepository = new DatabaseMirrorDeploymentIsolationAttestationRepository(
                jdbc, fixtures.mapper, fixtures.attestationIntegrity,
                fixtures.bundleIntegrity, transactions);
        attestationRepository.init();
        authorityPublication = fixtures.authorityPublication();
        authorityRepository.append(authorityPublication);
        audit = new RecordingAudit();
        service = service(authorityRepository, fixtures.activeClock, audit);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void ingestsReadsAndIrreversiblyRevokesWithPayloadFreeAudit() {
        var attestation = fixtures.attestation(7, fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('2'));

        var active = service.ingest("deployment:staging", fixtures.KEY_SET_ID,
                attestation, admin());
        var read = service.current("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID, reader());
        var request = new MirrorDeploymentIsolationAttestationRevocationRequest("",
                active.attestation().material().revision(),
                active.attestation().attestationFingerprint(),
                active.status().material().statusRevision(),
                active.status().statusFingerprint(),
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.SECURITY_INCIDENT);
        var revoked = service.revoke("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID, request, admin());

        assertThat(active.active()).isTrue();
        assertThat(read).isEqualTo(active);
        assertThat(revoked.active()).isFalse();
        assertThat(service.current("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID, reader())).isEqualTo(revoked);
        assertThat(audit.events).extracting(MirrorOperationAuditEvent::operation)
                .containsExactly(
                        MirrorOperationAuditEvent.Operation.ISOLATION_ATTESTATION_INGEST,
                        MirrorOperationAuditEvent.Operation.ISOLATION_ATTESTATION_READ,
                        MirrorOperationAuditEvent.Operation.ISOLATION_ATTESTATION_REVOKE,
                        MirrorOperationAuditEvent.Operation.ISOLATION_ATTESTATION_READ);
        assertThat(audit.events).allSatisfy(event -> {
            assertThat(event.outcome()).isEqualTo(MirrorOperationAuditEvent.Outcome.SUCCEEDED);
            assertThat(event.reasonCode()).isBlank();
            assertThat(event.requestId()).isEqualTo(fixtures.ATTESTATION_ID);
        });
    }

    @Test
    void activeReadFailsClosedAfterAuthorityGenerationAdvances() {
        var active = ingest();
        var successor = fixtures.authorityPublication(
                2, authorityPublication.publicationFingerprint());
        authorityRepository.append(successor);

        assertProblem(() -> service.current("deployment:staging", fixtures.KEY_SET_ID,
                        fixtures.ATTESTATION_ID, reader()),
                409, "RG.MIRROR.ISOLATION_ATTESTATION_AUTHORITY_SUPERSEDED");
        assertThat(attestationRepository.current(
                MirrorDeploymentIsolationAttestationRepository.StreamIdentity.from(active)))
                .contains(active);
    }

    @Test
    void revokedDenialStillDistributesWhenAuthorityStoreIsUnavailable() {
        var active = ingest();
        var request = new MirrorDeploymentIsolationAttestationRevocationRequest("",
                active.attestation().material().revision(),
                active.attestation().attestationFingerprint(), 1,
                active.status().statusFingerprint(),
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.OPERATOR_REVOKED);
        var revoked = service.revoke("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID, request, admin());
        MirrorDeploymentIsolationAuthorityPublicationRepository unavailable =
                mock(MirrorDeploymentIsolationAuthorityPublicationRepository.class);
        var denialService = service(unavailable, fixtures.expiredClock, new RecordingAudit());

        assertThat(denialService.current("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID, reader())).isEqualTo(revoked);
        verifyNoInteractions(unavailable);
    }

    @Test
    void activeReadRejectsExpiredAttestationEvenWhileStoredBodyRemainsImmutable() {
        var active = ingest();
        var expiredService = service(authorityRepository, fixtures.expiredClock,
                new RecordingAudit());

        assertProblem(() -> expiredService.current("deployment:staging", fixtures.KEY_SET_ID,
                        fixtures.ATTESTATION_ID, reader()),
                410, "RG.MIRROR.ISOLATION_ATTESTATION_EXPIRED");
        assertThat(attestationRepository.current(
                MirrorDeploymentIsolationAttestationRepository.StreamIdentity.from(active)))
                .contains(active);
    }

    @Test
    void deniesMissingBootstrapPolicyWrongPurposeAndDeploymentDrift() {
        var unavailable = new MirrorDeploymentIsolationAttestationService(
                attestationRepository,
                MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.unavailable(),
                authorityRepository, fixtures.authorityPolicyProvider(),
                fixtures.authorityIntegrity, fixtures.attestationIntegrity,
                fixtures.bundleIntegrity, MirrorOperationObservability.noop(),
                fixtures.activeClock);
        var attestation = fixtures.attestation(7, fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('2'));

        assertProblem(() -> unavailable.ingest("deployment:staging", fixtures.KEY_SET_ID,
                        attestation, admin()),
                503, "RG.MIRROR.ISOLATION_ATTESTATION_POLICY_UNAVAILABLE");
        assertProblem(() -> service.ingest("deployment:staging", fixtures.KEY_SET_ID,
                        attestation, reader()),
                403, "RG.MIRROR.ISOLATION_ATTESTATION_PURPOSE_REQUIRED");
        var drifted = fixtures.attestation(7, fixtures.deployment("cluster-b"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('2'));
        assertProblem(() -> service.ingest("deployment:staging", fixtures.KEY_SET_ID,
                        drifted, admin()),
                403, "RG.MIRROR.ISOLATION_ATTESTATION_SCOPE_MISMATCH");
    }

    @Test
    void exactReadNeverBecomesAHistoricalDowngradeLookup() {
        var active = ingest();
        var successorAttestation = fixtures.attestation(
                8, fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('2'));
        var successor = service.ingest("deployment:staging", fixtures.KEY_SET_ID,
                successorAttestation, admin());

        assertProblem(() -> service.current("deployment:staging", fixtures.KEY_SET_ID,
                        fixtures.ATTESTATION_ID,
                        MirrorDeploymentIsolationAttestationRepository.CurrentExpectation.from(
                                active), reader()),
                404, "RG.MIRROR.ISOLATION_ATTESTATION_NOT_FOUND");
        assertThat(service.current("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID,
                MirrorDeploymentIsolationAttestationRepository.CurrentExpectation.from(successor),
                reader())).isEqualTo(successor);
    }

    @Test
    void samplesOneTrustedInstantForEachActiveAdmissionOrRead() {
        var clock = new CountingClock(fixtures.activeClock.instant());
        service = service(authorityRepository, clock, new RecordingAudit());

        ingest();
        assertThat(clock.reads()).isOne();

        service.current("deployment:staging", fixtures.KEY_SET_ID,
                fixtures.ATTESTATION_ID, reader());
        assertThat(clock.reads()).isEqualTo(2);
    }

    private MirrorDeploymentIsolationAttestationBundle ingest() {
        var attestation = fixtures.attestation(7, fixtures.deployment("cluster-a"),
                MirrorDeploymentIsolationAttestationRepositoryTestFixtures.fingerprint('2'));
        return service.ingest("deployment:staging", fixtures.KEY_SET_ID,
                attestation, admin());
    }

    private MirrorDeploymentIsolationAttestationService service(
            MirrorDeploymentIsolationAuthorityPublicationRepository authorities,
            Clock clock,
            RecordingAudit targetAudit) {
        return new MirrorDeploymentIsolationAttestationService(
                attestationRepository, fixtures.admissionPolicyProvider(), authorities,
                fixtures.authorityPolicyProvider(), fixtures.authorityIntegrity,
                fixtures.attestationIntegrity, fixtures.bundleIntegrity,
                new MirrorOperationObservability(
                        targetAudit, MirrorOperationTelemetry.noop(), () -> 0), clock);
    }

    private static IntegrationRequestContext admin() {
        return identity("MIRROR_TRUST_ADMIN");
    }

    private static IntegrationRequestContext reader() {
        return identity("MIRROR_TRUST_DISTRIBUTION");
    }

    private static IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "staging",
                "ap-southeast-1", "SERVICE", "mirror-agent", "", purpose,
                "corr-attestation", Set.of(), "RESTRICTED", "");
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().details()).isEmpty();
                });
    }

    private static final class RecordingAudit implements MirrorOperationAuditRepository {
        private final List<MirrorOperationAuditEvent> events = new ArrayList<>();

        @Override
        public MirrorOperationAuditEvent append(MirrorOperationAuditEvent event) {
            events.add(event);
            return event;
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope, int limit) {
            return List.copyOf(events);
        }
    }

    private static final class CountingClock extends Clock {
        private final Instant instant;
        private final AtomicInteger reads = new AtomicInteger();

        private CountingClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return fixturesZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            reads.incrementAndGet();
            return instant;
        }

        private int reads() {
            return reads.get();
        }

        private static ZoneId fixturesZone() {
            return ZoneId.of("UTC");
        }
    }
}
