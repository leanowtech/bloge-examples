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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCorpusTrajectoryGovernanceServiceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final CapabilityCorpusIntegrity integrity =
            new CapabilityCorpusIntegrity(mapper);
    private EmbeddedDatabase database;
    private DatabaseCapabilityObservationRepository observations;
    private DatabaseCapabilityCorpusRepository corpora;
    private DatabaseCapabilityCorpusTrajectoryRepository trajectories;
    private CapabilityCorpusRevision revision;
    private CapabilityCorpusPublication publication;
    private List<CapabilityObservationRepository.StoredObservation> sources;
    private MirrorArtifactRef retryPolicyRef;
    private MutableCorpusPolicyProvider corpusPolicies;
    private MutableRetryPolicyProvider retryPolicies;
    private MutableSourceVerifier sourceVerifier;
    private RecordingAudit audit;
    private CapabilityCorpusTrajectoryGovernanceService service;
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
        trajectories = new DatabaseCapabilityCorpusTrajectoryRepository(
                jdbc, mapper, integrity);
        trajectories.init();

        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        InMemoryVisualEvidenceSigner signer =
                new InMemoryVisualEvidenceSigner();
        Instant occurredAt = Instant.now().minusSeconds(5);
        CapabilityObservationRepository.StoredObservation first =
                CapabilityCorpusTestFixtures.trajectoryObservation(
                        mapper,
                        signer,
                        capability,
                        "trajectory-attempt-1",
                        occurredAt,
                        1,
                        true,
                        true);
        CapabilityObservationRepository.StoredObservation second =
                CapabilityCorpusTestFixtures.trajectoryObservation(
                        mapper,
                        signer,
                        capability,
                        "trajectory-attempt-2",
                        occurredAt.plusMillis(250),
                        2,
                        false,
                        true);
        sources = List.of(first, second);
        sources.forEach(observations::append);
        now = sources.stream()
                .map(source -> source.admission().decidedAt())
                .max(Instant::compareTo)
                .orElseThrow()
                .plusSeconds(2);
        revision = CapabilityCorpusTestFixtures.revision(
                mapper, sources, "trajectory-corpus", now.minusSeconds(2));
        publication = CapabilityCorpusTestFixtures.publication(
                mapper, revision, 1, null, now.minusSeconds(1));
        corpora.appendRevision(revision);
        corpora.appendPublication(publication);

        retryPolicyRef = CapabilityObservationTestFixtures.ref(
                "RETRY_POLICY", "support-retry-policy", 3, '7');
        corpusPolicies = new MutableCorpusPolicyProvider(
                CapabilityCorpusTestFixtures.policy(
                        first, 1, 10_000, 1));
        retryPolicies = new MutableRetryPolicyProvider(
                new CapabilityRetryPolicyProvider.RetryPolicy(
                        scope,
                        first.envelope().material().capabilityRef(),
                        retryPolicyRef,
                        3,
                        Set.of("TRANSIENT_UPSTREAM"),
                        Set.of("UPSTREAM_TIMEOUT")));
        sourceVerifier = new MutableSourceVerifier();
        audit = new RecordingAudit();
        service = new CapabilityCorpusTrajectoryGovernanceService(
                observations,
                corpora,
                trajectories,
                corpusPolicies,
                retryPolicies,
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
    void publishesExplicitRetrySequenceAndRecoversExactRetry() {
        CapabilityCorpusTrajectoryPublishRequest request =
                CapabilityCorpusTestFixtures.trajectoryRequest(
                        publication, sources, retryPolicyRef);
        IntegrationRequestContext identity = identity(
                Set.of("corpus-publishers"));

        CapabilityCorpusTrajectoryPublication first =
                service.publish(request, identity);
        corpusPolicies.available = false;
        retryPolicies.available = false;
        sourceVerifier.available = false;
        CapabilityCorpusTrajectoryPublication retried =
                service.publish(request, identity);

        assertThat(retried).isEqualTo(first);
        assertThat(first.requestFingerprint()).isEqualTo(
                sources.getFirst().envelope().material()
                        .request().payloadRef().fingerprint());
        assertThat(first.attempts()).hasSize(2);
        assertThat(first.retryPolicyRef()).isEqualTo(retryPolicyRef);
        assertThat(corpusPolicies.calls).hasValue(1);
        assertThat(retryPolicies.calls).hasValue(1);
        assertThat(sourceVerifier.calls).hasValue(2);
        assertThat(audit.events)
                .extracting(MirrorOperationAuditEvent::operation)
                .containsOnly(
                        MirrorOperationAuditEvent.Operation
                                .CORPUS_TRAJECTORY_PUBLISH);
    }

    @Test
    void rejectsSequenceWhoseIntermediateAttemptIsNotRetryable() {
        CapabilityObservationRepository.StoredObservation successfulFirst =
                replacementFirst(false, true);
        replaceSources(List.of(successfulFirst, sources.getLast()));

        assertProblem(
                () -> service.publish(
                        CapabilityCorpusTestFixtures.trajectoryRequest(
                                publication, sources, retryPolicyRef),
                        identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_INVALID");
        assertThat(trajectories.findLatest(
                publication.scope(), "support-timeout-trajectory")).isEmpty();
    }

    @Test
    void rejectsSourceWithoutTrajectoryGrant() {
        CapabilityObservationRepository.StoredObservation unauthorized =
                replacementFirst(true, false);
        replaceSources(List.of(unauthorized, sources.getLast()));

        assertProblem(
                () -> service.publish(
                        CapabilityCorpusTestFixtures.trajectoryRequest(
                                publication, sources, retryPolicyRef),
                        identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_TRAJECTORY_USE_NOT_AUTHORIZED");
    }

    @Test
    void failsClosedForStaleCorpusAndUnavailableRetryAuthority() {
        CapabilityCorpusPublication next =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        2,
                        publication.artifactRef(),
                        now);
        corpora.appendPublication(next);
        CapabilityCorpusTrajectoryPublishRequest stale =
                CapabilityCorpusTestFixtures.trajectoryRequest(
                        publication, sources, retryPolicyRef);

        assertProblem(
                () -> service.publish(
                        stale, identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_PUBLICATION_STALE");

        retryPolicies.available = false;
        CapabilityCorpusTrajectoryPublishRequest current =
                CapabilityCorpusTestFixtures.trajectoryRequest(
                        next, sources, retryPolicyRef);
        assertProblem(
                () -> service.publish(
                        current, identity(Set.of("corpus-publishers"))),
                503,
                "RG.MIRROR.RETRY_POLICY_UNAVAILABLE");
    }

    private CapabilityObservationRepository.StoredObservation replacementFirst(
            boolean retryableError,
            boolean trajectoryUse) {
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(
                        mapper, publication.scope());
        return CapabilityCorpusTestFixtures.trajectoryObservation(
                mapper,
                new InMemoryVisualEvidenceSigner(),
                capability,
                "replacement-attempt-1",
                sources.getFirst().envelope().material().occurredAt(),
                1,
                retryableError,
                trajectoryUse);
    }

    private void replaceSources(
            List<CapabilityObservationRepository.StoredObservation>
                    replacements) {
        observations.append(replacements.getFirst());
        sources = List.copyOf(replacements);
        revision = CapabilityCorpusTestFixtures.revision(
                mapper, sources, "replacement-corpus", now.minusSeconds(2));
        publication = CapabilityCorpusTestFixtures.publication(
                mapper, revision, 1, null, now.minusSeconds(1));
        corpora.appendRevision(revision);
        corpora.appendPublication(publication);
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
        private final GovernancePolicy policy;
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

    private static final class MutableRetryPolicyProvider
            implements CapabilityRetryPolicyProvider {
        private volatile boolean available = true;
        private final RetryPolicy policy;
        private final AtomicInteger calls = new AtomicInteger();

        private MutableRetryPolicyProvider(RetryPolicy policy) {
            this.policy = policy;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<RetryPolicy> resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef capabilityRef) {
            calls.incrementAndGet();
            return Optional.of(policy);
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
