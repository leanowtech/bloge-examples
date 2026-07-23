package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCorpusClusterGovernanceServiceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final CapabilityCorpusIntegrity integrity =
            new CapabilityCorpusIntegrity(mapper);
    private EmbeddedDatabase database;
    private DatabaseCapabilityObservationRepository observations;
    private DatabaseCapabilityCorpusRepository corpora;
    private DatabaseCapabilityCorpusClusterRepository clusters;
    private CapabilitySnapshot capability;
    private List<CapabilityObservationRepository.StoredObservation> sources;
    private CapabilityCorpusRevision revision;
    private CapabilityCorpusPublication publication;
    private CapabilityCorpusClusterValidation validation;
    private MutableCorpusPolicyProvider corpusPolicies;
    private MutableClusterPolicyProvider clusterPolicies;
    private MutableValidationAuthority validations;
    private MutableSourceVerifier sourceVerifier;
    private RecordingAudit audit;
    private CapabilityCorpusClusterGovernanceService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        observations = new DatabaseCapabilityObservationRepository(
                jdbc,
                mapper,
                new CapabilityObservationIntegrity(mapper),
                new CapabilityObservationAdmissionIntegrity(mapper));
        observations.init();
        corpora = new DatabaseCapabilityCorpusRepository(
                jdbc, mapper, integrity);
        corpora.init();
        clusters = new DatabaseCapabilityCorpusClusterRepository(
                jdbc, mapper, integrity);
        clusters.init();

        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        now = Instant.now().plusSeconds(3);
        sources = clusterSources("cluster", true);
        installCorpus("cluster-corpus", sources);
        corpusPolicies = new MutableCorpusPolicyProvider(
                CapabilityCorpusTestFixtures.policy(
                        sources.getFirst(), 1, 10_000, 1));
        clusterPolicies = new MutableClusterPolicyProvider(
                clusterPolicy(0.85d, ignored -> true));
        validations = new MutableValidationAuthority(validation);
        sourceVerifier = new MutableSourceVerifier();
        audit = new RecordingAudit();
        service = new CapabilityCorpusClusterGovernanceService(
                observations,
                corpora,
                clusters,
                corpusPolicies,
                clusterPolicies,
                validations,
                sourceVerifier,
                integrity,
                new MirrorOperationObservability(
                        audit, MirrorOperationTelemetry.noop(), () -> 0),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void publishesValidatedClusterAndRecoversExactRetryBeforeAuthorities() {
        CapabilityCorpusClusterPublishRequest request =
                CapabilityCorpusTestFixtures.clusterRequest(
                        publication, validation);
        IntegrationRequestContext identity =
                identity(Set.of("corpus-publishers"));

        CapabilityCorpusClusterPublication first =
                service.publish(request, identity);
        corpusPolicies.available = false;
        clusterPolicies.available = false;
        validations.available = false;
        sourceVerifier.available = false;
        CapabilityCorpusClusterPublication retried =
                service.publish(request, identity);

        assertThat(retried).isEqualTo(first);
        assertThat(first.members()).hasSize(3);
        assertThat(first.identityMode())
                .isEqualTo(CapabilityCorpusClusterValidation.IdentityMode
                        .REQUEST_PROJECTION);
        assertThat(first.validationRef()).isEqualTo(validation.artifactRef());
        assertThat(first.confidence().lowerBound()).isGreaterThan(0.85d);
        assertThat(corpusPolicies.calls).hasValue(1);
        assertThat(clusterPolicies.calls).hasValue(1);
        assertThat(validations.calls).hasValue(1);
        assertThat(sourceVerifier.calls).hasValue(3);
        assertThat(audit.events)
                .extracting(MirrorOperationAuditEvent::operation)
                .containsOnly(
                        MirrorOperationAuditEvent.Operation
                                .CORPUS_CLUSTER_PUBLISH);
    }

    @Test
    void rejectsSourcesWithoutClusterModelingGrant() {
        sources = clusterSources("unauthorized", false);
        installCorpus("unauthorized-corpus", sources);
        corpusPolicies.policy = CapabilityCorpusTestFixtures.policy(
                sources.getFirst(), 1, 10_000, 1);
        clusterPolicies.policy = clusterPolicy(0.85d, ignored -> true);
        validations.validation = validation;

        assertProblem(
                () -> service.publish(
                        CapabilityCorpusTestFixtures.clusterRequest(
                                publication, validation),
                        identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_CLUSTER_USE_NOT_AUTHORIZED");
        assertThat(clusters.findLatest(
                publication.scope(), "support-customer-cluster")).isEmpty();
    }

    @Test
    void rejectsLowConfidenceAndOwnerDisallowedProjectionPaths() {
        clusterPolicies.policy = clusterPolicy(0.95d, ignored -> true);
        CapabilityCorpusClusterPublishRequest request =
                CapabilityCorpusTestFixtures.clusterRequest(
                        publication, validation);

        assertProblem(
                () -> service.publish(
                        request, identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_CLUSTER_CONFIDENCE_REJECTED");

        clusterPolicies.policy = clusterPolicy(0.85d, ignored -> false);
        assertProblem(
                () -> service.publish(
                        request, identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_CLUSTER_PATH_POLICY_REJECTED");
    }

    @Test
    void rejectsExpiredValidationAndUnavailableValidationAuthority() {
        validations.validation = expired(validation);
        CapabilityCorpusClusterPublishRequest expired =
                CapabilityCorpusTestFixtures.clusterRequest(
                        publication, validations.validation);

        assertProblem(
                () -> service.publish(
                        expired, identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_CLUSTER_VALIDATION_EXPIRED");

        validations.available = false;
        CapabilityCorpusClusterPublishRequest current =
                CapabilityCorpusTestFixtures.clusterRequest(
                        publication, validation);
        assertProblem(
                () -> service.publish(
                        current, identity(Set.of("corpus-publishers"))),
                503,
                "RG.MIRROR.CORPUS_CLUSTER_VALIDATION_UNAVAILABLE");
    }

    @Test
    void rejectsStaleCorpusPublicationBeforeConsultingValidation() {
        CapabilityCorpusPublication next =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        2,
                        publication.artifactRef(),
                        now.minusMillis(1));
        corpora.appendPublication(next);
        CapabilityCorpusClusterPublishRequest stale =
                CapabilityCorpusTestFixtures.clusterRequest(
                        publication, validation);

        assertProblem(
                () -> service.publish(
                        stale, identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_PUBLICATION_STALE");
        assertThat(validations.calls).hasValue(0);
    }

    @Test
    void rejectsCapabilityDriftInsideOneClusterLineage() {
        CapabilityCorpusClusterPublishRequest firstRequest =
                CapabilityCorpusTestFixtures.clusterRequest(
                        publication, validation);
        CapabilityCorpusClusterPublication first = service.publish(
                firstRequest, identity(Set.of("corpus-publishers")));
        CapabilityCorpusClusterPublishRequest drifted =
                new CapabilityCorpusClusterPublishRequest(
                        "",
                        first.clusterId(),
                        2,
                        first.artifactRef(),
                        CapabilityObservationTestFixtures.ref(
                                "CAPABILITY",
                                "graph:another-capability",
                                1,
                                '9'),
                        firstRequest.corpusPublicationRef(),
                        firstRequest.clusterPolicyRef(),
                        firstRequest.validationRef(),
                        firstRequest.reviewTicketRef(),
                        firstRequest.reasonCode());

        assertProblem(
                () -> service.publish(
                        drifted,
                        identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_CLUSTER_HEAD_CONFLICT");
        assertThat(clusters.findLatest(
                publication.scope(), first.clusterId()))
                .contains(first);
    }

    private List<CapabilityObservationRepository.StoredObservation>
            clusterSources(String prefix, boolean clusterUse) {
        InMemoryVisualEvidenceSigner signer =
                new InMemoryVisualEvidenceSigner();
        Instant occurredAt = now.minusSeconds(10);
        List<CapabilityObservationRepository.StoredObservation> values =
                List.of(
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                prefix + "-001", occurredAt, clusterUse),
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                prefix + "-002",
                                occurredAt.plusMillis(100), clusterUse),
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                prefix + "-003",
                                occurredAt.plusMillis(200), clusterUse));
        values.forEach(observations::append);
        return values;
    }

    private void installCorpus(
            String corpusId,
            List<CapabilityObservationRepository.StoredObservation> values) {
        revision = CapabilityCorpusTestFixtures.revision(
                mapper, values, corpusId, now.minusSeconds(2));
        publication = CapabilityCorpusTestFixtures.publication(
                mapper, revision, 1, null, now.minusSeconds(1));
        corpora.appendRevision(revision);
        corpora.appendPublication(publication);
        validation = CapabilityCorpusTestFixtures.clusterValidation(
                mapper,
                publication,
                revision,
                values,
                now.minusMillis(500));
    }

    private CapabilityCorpusClusterPolicyProvider.ClusterPolicy clusterPolicy(
            double minimumLowerBound,
            java.util.function.Predicate<CapabilityCorpusClusterValidation>
                    validationPolicy) {
        return new CapabilityCorpusClusterPolicyProvider.ClusterPolicy(
                publication.scope(),
                revision.capabilityRef(),
                CapabilityObservationTestFixtures.ref(
                        "CORPUS_CLUSTER_POLICY",
                        "support-cluster-policy",
                        3,
                        'b'),
                3,
                3,
                20,
                500,
                minimumLowerBound,
                Duration.ofHours(1),
                identity -> identity.groups().contains("corpus-publishers"),
                validationPolicy);
    }

    private CapabilityCorpusClusterValidation expired(
            CapabilityCorpusClusterValidation value) {
        return integrity.sealClusterValidation(
                new CapabilityCorpusClusterValidation(
                        value.schemaVersion(),
                        CapabilityObservationTestFixtures.fingerprint('0'),
                        value.scope(),
                        value.validationId() + "-expired",
                        value.revision(),
                        value.capabilityRef(),
                        value.corpusPublicationRef(),
                        value.corpusRevisionRef(),
                        value.representativeSource(),
                        value.members(),
                        value.matchRequestPointers(),
                        value.identityMode(),
                        value.identityProjections(),
                        value.distinctIdentityCount(),
                        value.holdout(),
                        value.confidence(),
                        value.identityCoverageComplete(),
                        value.validatedBy(),
                        now.minusSeconds(10),
                        now.minusSeconds(1)));
    }

    private static IntegrationRequestContext identity(Set<String> groups) {
        return CapabilityCorpusTestFixtures.identity("org-a", groups);
    }

    private static void assertProblem(
            Runnable action,
            int status,
            String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class,
                failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().details()).isEmpty();
                });
    }

    private static final class MutableCorpusPolicyProvider
            implements CapabilityCorpusGovernancePolicyProvider {
        private volatile boolean available = true;
        private volatile GovernancePolicy policy;
        private final AtomicInteger calls = new AtomicInteger();

        private MutableCorpusPolicyProvider(GovernancePolicy policy) {
            this.policy = policy;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<GovernancePolicy> resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef capabilityRef) {
            calls.incrementAndGet();
            return Optional.of(policy);
        }
    }

    private static final class MutableClusterPolicyProvider
            implements CapabilityCorpusClusterPolicyProvider {
        private volatile boolean available = true;
        private volatile ClusterPolicy policy;
        private final AtomicInteger calls = new AtomicInteger();

        private MutableClusterPolicyProvider(ClusterPolicy policy) {
            this.policy = policy;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<ClusterPolicy> resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef capabilityRef) {
            calls.incrementAndGet();
            return Optional.of(policy);
        }
    }

    private static final class MutableValidationAuthority
            implements CapabilityCorpusClusterValidationAuthority {
        private volatile boolean available = true;
        private volatile CapabilityCorpusClusterValidation validation;
        private final AtomicInteger calls = new AtomicInteger();

        private MutableValidationAuthority(
                CapabilityCorpusClusterValidation validation) {
            this.validation = validation;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<CapabilityCorpusClusterValidation> resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef validationRef) {
            calls.incrementAndGet();
            return validation.artifactRef().equals(validationRef)
                    ? Optional.of(validation) : Optional.empty();
        }
    }

    private static final class MutableSourceVerifier
            implements CapabilityCorpusSourceVerifier {
        private volatile boolean available = true;
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public VerificationResult verify(
                CapabilityObservationRepository.StoredObservation source,
                CapabilityCorpusGovernancePolicyProvider.GovernancePolicy policy,
                Instant verificationTime) {
            calls.incrementAndGet();
            return VerificationResult.verified();
        }
    }

    private static final class RecordingAudit
            implements MirrorOperationAuditRepository {
        private final List<MirrorOperationAuditEvent> events =
                new ArrayList<>();

        @Override
        public MirrorOperationAuditEvent append(
                MirrorOperationAuditEvent event) {
            events.add(event);
            return event;
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope, int limit) {
            return List.copyOf(events);
        }
    }
}
