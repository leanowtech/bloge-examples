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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationAuthorityPublicationServiceTest {
    private final MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures =
            new MirrorDeploymentIsolationAuthorityPublicationTestFixtures();
    private EmbeddedDatabase database;
    private DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository repository;
    private RecordingAudit audit;
    private MirrorDeploymentIsolationAuthorityPublicationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        repository = new DatabaseMirrorDeploymentIsolationAuthorityPublicationRepository(
                jdbc, fixtures.mapper, fixtures.integrity,
                new DataSourceTransactionManager(database));
        repository.init();
        audit = new RecordingAudit();
        service = service(fixtures.provider(), fixtures.activeClock);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void publishesAndReverifiesLatestAndCurrentWithPayloadFreeAudit() {
        var publication = fixtures.publication(1, "");

        assertThat(service.publish(publication, identity(
                MirrorDeploymentIsolationAuthorityPublicationService.PUBLISH_PURPOSE, "org-a")))
                .isEqualTo(publication);
        assertThat(service.latest(
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.DEPLOYMENT_SCOPE_ID,
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID,
                identity(MirrorDeploymentIsolationAuthorityPublicationService
                        .DISTRIBUTION_READ_PURPOSE, "org-a")))
                .isEqualTo(publication);
        assertThat(service.current(
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.DEPLOYMENT_SCOPE_ID,
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID,
                1, publication.publicationFingerprint(),
                identity("MIRROR_REHEARSAL", "org-a")))
                .isEqualTo(publication);

        assertThat(audit.events)
                .extracting(MirrorOperationAuditEvent::operation,
                        MirrorOperationAuditEvent::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation.AUTHORITY_KEY_SET_PUBLISH,
                                MirrorOperationAuditEvent.Outcome.SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation.AUTHORITY_KEY_SET_READ,
                                MirrorOperationAuditEvent.Outcome.SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation.AUTHORITY_KEY_SET_READ,
                                MirrorOperationAuditEvent.Outcome.SUCCEEDED));
        assertThat(audit.events).allSatisfy(event -> {
            assertThat(event.requestId()).isEqualTo(
                    MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID);
            assertThat(event.planId()).isEqualTo(
                    MirrorDeploymentIsolationAuthorityPublicationTestFixtures.DEPLOYMENT_SCOPE_ID);
            assertThat(event.runId()).isEqualTo(publication.publicationFingerprint());
            assertThat(event.toString()).doesNotContain("encodedPublicKey", "signature");
        });
    }

    @Test
    void rejectsRollbackAgainstTheDurableFloorBeforeRepositoryMutation() {
        var genesis = fixtures.publication(1, "");
        var successor = fixtures.publication(2, genesis.publicationFingerprint());
        IntegrationRequestContext publisher = identity(
                MirrorDeploymentIsolationAuthorityPublicationService.PUBLISH_PURPOSE, "org-a");
        service.publish(genesis, publisher);
        service.publish(successor, publisher);

        assertProblem(() -> service.publish(genesis, publisher), 409,
                "RG.MIRROR.AUTHORITY_PUBLICATION_CHAIN_CONFLICT");
        assertThat(repository.latest(
                MirrorDeploymentIsolationAuthorityPublicationRepository.StreamIdentity.from(
                        genesis))).contains(successor);
    }

    @Test
    void rejectsCrossScopePublicationAndHidesCrossScopeReads() {
        var publication = fixtures.publication(1, "");

        assertProblem(() -> service.publish(publication, identity(
                        MirrorDeploymentIsolationAuthorityPublicationService.PUBLISH_PURPOSE,
                        "org-b")),
                403, "RG.MIRROR.AUTHORITY_PUBLICATION_SCOPE_MISMATCH");
        service.publish(publication, identity(
                MirrorDeploymentIsolationAuthorityPublicationService.PUBLISH_PURPOSE, "org-a"));
        assertProblem(() -> service.latest(
                        MirrorDeploymentIsolationAuthorityPublicationTestFixtures
                                .DEPLOYMENT_SCOPE_ID,
                        MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID,
                        identity(MirrorDeploymentIsolationAuthorityPublicationService
                                .DISTRIBUTION_READ_PURPOSE, "org-b")),
                404, "RG.MIRROR.AUTHORITY_PUBLICATION_NOT_FOUND");
    }

    @Test
    void failsClosedWhenLocalTrustIsUnavailableOrUsesUnrelatedRoots() {
        var publication = fixtures.publication(1, "");
        IntegrationRequestContext publisher = identity(
                MirrorDeploymentIsolationAuthorityPublicationService.PUBLISH_PURPOSE, "org-a");
        var unavailable = service(
                MirrorDeploymentIsolationAuthorityTrustPolicyProvider.unavailable(),
                fixtures.activeClock);

        assertProblem(() -> unavailable.publish(publication, publisher), 503,
                "RG.MIRROR.AUTHORITY_TRUST_UNAVAILABLE");

        var unrelatedFixtures = new MirrorDeploymentIsolationAuthorityPublicationTestFixtures();
        var wrongRoots = new MirrorDeploymentIsolationAuthorityTrustPolicyProvider.TrustPolicy(
                fixtures.binding(), unrelatedFixtures.roots());
        var wrongProvider = provider(wrongRoots);
        assertProblem(() -> service(wrongProvider, fixtures.activeClock)
                        .publish(publication, publisher),
                403, "RG.MIRROR.AUTHORITY_PUBLICATION_POLICY_REJECTED");
    }

    @Test
    void rejectsTamperedCanonicalMaterialWithoutPersistingIt() {
        var publication = fixtures.publication(1, "");
        var tampered = new MirrorDeploymentIsolationAuthorityKeySetPublication("",
                MirrorDeploymentIsolationAuthorityPublicationTestFixtures.fingerprint('f'),
                publication.materialFingerprint(), publication.material(),
                publication.signatures());

        assertProblem(() -> service.publish(tampered, identity(
                        MirrorDeploymentIsolationAuthorityPublicationService.PUBLISH_PURPOSE,
                        "org-a")),
                400, "RG.MIRROR.AUTHORITY_PUBLICATION_INVALID");
        assertThat(repository.latest(
                MirrorDeploymentIsolationAuthorityPublicationRepository.StreamIdentity.from(
                        publication))).isEmpty();
    }

    @Test
    void stopsServingAnExpiredFloorEvenThoughHistoryRemainsDurable() {
        var publication = fixtures.publication(1, "");
        service.publish(publication, identity(
                MirrorDeploymentIsolationAuthorityPublicationService.PUBLISH_PURPOSE, "org-a"));
        var expired = service(fixtures.provider(), Clock.fixed(
                fixtures.expiresAt, ZoneOffset.UTC));

        assertProblem(() -> expired.latest(
                        MirrorDeploymentIsolationAuthorityPublicationTestFixtures
                                .DEPLOYMENT_SCOPE_ID,
                        MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID,
                        identity(MirrorDeploymentIsolationAuthorityPublicationService
                                .DISTRIBUTION_READ_PURPOSE, "org-a")),
                410, "RG.MIRROR.AUTHORITY_PUBLICATION_EXPIRED");
        assertThat(repository.floor(
                MirrorDeploymentIsolationAuthorityPublicationRepository.StreamIdentity.from(
                        publication))).isPresent();
    }

    @Test
    void invalidPurposeEnvironmentAndContentAddressFailWithStableSanitizedProblems() {
        var publication = fixtures.publication(1, "");
        assertProblem(() -> service.publish(publication,
                        identity("MIRROR_REHEARSAL", "org-a")),
                403, "RG.MIRROR.AUTHORITY_PURPOSE_REQUIRED");
        assertProblem(() -> service.publish(publication,
                        identity(MirrorDeploymentIsolationAuthorityPublicationService
                                .PUBLISH_PURPOSE, "org-a", "production")),
                403, "RG.MIRROR.AUTHORITY_ENVIRONMENT_FORBIDDEN");
        assertProblem(() -> service.current(
                        MirrorDeploymentIsolationAuthorityPublicationTestFixtures
                                .DEPLOYMENT_SCOPE_ID,
                        MirrorDeploymentIsolationAuthorityPublicationTestFixtures.KEY_SET_ID,
                        0, "customer-secret", identity(
                                MirrorDeploymentIsolationAuthorityPublicationService
                                        .DISTRIBUTION_READ_PURPOSE, "org-a")),
                400, "RG.MIRROR.AUTHORITY_PUBLICATION_REF_INVALID");
        assertThat(audit.events.toString()).doesNotContain("customer-secret");
    }

    private MirrorDeploymentIsolationAuthorityPublicationService service(
            MirrorDeploymentIsolationAuthorityTrustPolicyProvider provider,
            Clock clock) {
        return new MirrorDeploymentIsolationAuthorityPublicationService(
                repository, provider, fixtures.integrity,
                new MirrorOperationObservability(audit, MirrorOperationTelemetry.noop(), () -> 0),
                clock);
    }

    private static MirrorDeploymentIsolationAuthorityTrustPolicyProvider provider(
            MirrorDeploymentIsolationAuthorityTrustPolicyProvider.TrustPolicy policy) {
        return new MirrorDeploymentIsolationAuthorityTrustPolicyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Optional<TrustPolicy> resolve(
                    CapabilitySnapshot.Scope scope, String deploymentScopeId, String keySetId) {
                return Optional.of(policy);
            }
        };
    }

    private static IntegrationRequestContext identity(String purpose, String organizationId) {
        return identity(purpose, organizationId, "staging");
    }

    private static IntegrationRequestContext identity(
            String purpose, String organizationId, String environmentId) {
        return new IntegrationRequestContext("tenant-a", organizationId, "project-a",
                environmentId, "ap-southeast-1", "SERVICE", "trust-agent", "", purpose,
                "corr-authority-test", Set.of("security"), "RESTRICTED", "");
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
            MirrorOperationAuditEvent stored = event.persisted(
                    events.size() + 1L, Instant.parse("2030-01-01T00:00:00Z"));
            events.add(stored);
            return stored;
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope, int limit) {
            return events.stream().filter(event -> event.tenantId().equals(scope.tenantId())
                    && event.organizationId().equals(scope.organizationId())
                    && event.projectId().equals(scope.projectId())
                    && event.environmentId().equals(scope.environmentId())
                    && event.region().equals(scope.region())).limit(limit).toList();
        }
    }
}
