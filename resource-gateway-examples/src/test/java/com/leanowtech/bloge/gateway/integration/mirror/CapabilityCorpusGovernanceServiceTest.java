package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
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

class CapabilityCorpusGovernanceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityObservationIntegrity observationIntegrity =
            new CapabilityObservationIntegrity(mapper);
    private final CapabilityObservationAdmissionIntegrity admissionIntegrity =
            new CapabilityObservationAdmissionIntegrity(mapper);
    private final CapabilityCorpusIntegrity corpusIntegrity =
            new CapabilityCorpusIntegrity(mapper);
    private EmbeddedDatabase database;
    private DatabaseCapabilityObservationRepository observations;
    private DatabaseCapabilityObservationReviewRepository reviews;
    private DatabaseCapabilityCorpusRepository corpora;
    private CapabilityObservationRepository.StoredObservation admitted;
    private CapabilityObservationRepository.StoredObservation quarantined;
    private MutablePolicyProvider policies;
    private MutableSourceVerifier sourceVerifier;
    private RecordingAudit audit;
    private CapabilityCorpusGovernanceService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        observations = new DatabaseCapabilityObservationRepository(
                jdbc, mapper, observationIntegrity, admissionIntegrity);
        observations.init();
        reviews = new DatabaseCapabilityObservationReviewRepository(
                jdbc, mapper, corpusIntegrity);
        reviews.init();
        corpora = new DatabaseCapabilityCorpusRepository(
                jdbc, mapper, corpusIntegrity);
        corpora.init();
        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        admitted = CapabilityCorpusTestFixtures.admitted(
                mapper, scope, "observation-admitted");
        quarantined = CapabilityCorpusTestFixtures.quarantined(
                mapper, scope, "observation-quarantined");
        observations.append(admitted);
        observations.append(quarantined);
        policies = new MutablePolicyProvider(
                CapabilityCorpusTestFixtures.policy(admitted, 1, 10_000, 1));
        sourceVerifier = new MutableSourceVerifier(
                CapabilityCorpusSourceVerifier.VerificationResult.verified());
        audit = new RecordingAudit();
        now = admitted.admission().decidedAt().plusSeconds(2);
        service = new CapabilityCorpusGovernanceService(
                observations,
                reviews,
                corpora,
                policies,
                sourceVerifier,
                corpusIntegrity,
                new MirrorOperationObservability(
                        audit, MirrorOperationTelemetry.noop(), () -> 0),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void reviewsQuarantineAndExactRetryIgnoresMutablePolicy() {
        CapabilityObservationReviewRequest request =
                CapabilityCorpusTestFixtures.reviewRequest(quarantined);
        IntegrationRequestContext identity = identity(
                Set.of("corpus-reviewers"));

        CapabilityObservationReview first =
                service.reviewQuarantine(request, identity);
        policies.available = false;
        CapabilityObservationReview retried =
                service.reviewQuarantine(request, identity);

        assertThat(retried).isEqualTo(first);
        assertThat(first.observationRef())
                .isEqualTo(quarantined.envelope().artifactRef());
        assertThat(quarantined.admission().state())
                .isEqualTo(CapabilityObservationAdmission.State.QUARANTINED);
        assertThat(policies.calls).hasValue(1);
        assertThat(audit.events)
                .extracting(MirrorOperationAuditEvent::operation)
                .containsOnly(MirrorOperationAuditEvent.Operation.OBSERVATION_REVIEW);
    }

    @Test
    void rejectsReviewOfAdmittedObservationAndUnauthorizedReviewer() {
        CapabilityObservationReviewRequest admittedReview =
                new CapabilityObservationReviewRequest(
                        "",
                        admitted.envelope().artifactRef(),
                        admitted.admission().artifactRef(),
                        CapabilityObservationReviewRequest.Disposition
                                .CONFIRMED_QUARANTINE,
                        CapabilityObservationTestFixtures.ref(
                                "GOVERNANCE_REVIEW_TICKET",
                                "ticket-admitted",
                                1,
                                '4'),
                        "SHOULD_NOT_REVIEW");

        assertProblem(
                () -> service.reviewQuarantine(
                        admittedReview, identity(Set.of("corpus-reviewers"))),
                409,
                "RG.MIRROR.OBSERVATION_NOT_QUARANTINED");
        assertProblem(
                () -> service.reviewQuarantine(
                        CapabilityCorpusTestFixtures.reviewRequest(quarantined),
                        identity(Set.of("unrelated-group"))),
                403,
                "RG.MIRROR.OBSERVATION_REVIEW_FORBIDDEN");
    }

    @Test
    void createsEligibleCandidateAndExactRetryDoesNotReconsultProviders() {
        CapabilityCorpusCandidateRequest request =
                CapabilityCorpusTestFixtures.candidateRequest(
                        "support-corpus", 1, null, List.of(admitted));

        CapabilityCorpusRevision first =
                service.createCandidate(request, identity(Set.of("curators")));
        policies.available = false;
        sourceVerifier.available = false;
        CapabilityCorpusRevision retried =
                service.createCandidate(request, identity(Set.of("curators")));

        assertThat(retried).isEqualTo(first);
        assertThat(first.riskSummary().eligibility())
                .isEqualTo(CapabilityCorpusRevision.Eligibility.ELIGIBLE);
        assertThat(first.sources()).hasSize(1);
        assertThat(policies.calls).hasValue(1);
        assertThat(sourceVerifier.calls).hasValue(1);
    }

    @Test
    void persistsBlockedCandidateButNeverPublishesIt() {
        policies.policy = CapabilityCorpusTestFixtures.policy(
                admitted, 2, 10_000, 1);
        CapabilityCorpusRevision blocked = service.createCandidate(
                CapabilityCorpusTestFixtures.candidateRequest(
                        "blocked-corpus", 1, null, List.of(admitted)),
                identity(Set.of("curators")));

        assertThat(blocked.riskSummary().eligibility())
                .isEqualTo(CapabilityCorpusRevision.Eligibility.BLOCKED);
        assertThat(blocked.riskSummary().reasons())
                .contains(
                        CapabilityCorpusRevision.RiskReason
                                .INSUFFICIENT_SAMPLE_COUNT);
        assertProblem(
                () -> service.publish(
                        publishRequest(blocked, 1, null),
                        identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_CANDIDATE_INELIGIBLE");
        assertThat(corpora.findLatestPublication(
                blocked.scope(), blocked.corpusId())).isEmpty();
    }

    @Test
    void publishesEligibleCurrentCandidateAfterSecondSourceVerification() {
        CapabilityCorpusRevision candidate = service.createCandidate(
                CapabilityCorpusTestFixtures.candidateRequest(
                        "publish-corpus", 1, null, List.of(admitted)),
                identity(Set.of("curators")));
        assertThat(sourceVerifier.calls).hasValue(1);

        CapabilityCorpusPublishRequest request =
                publishRequest(candidate, 1, null);
        CapabilityCorpusPublication publication = service.publish(
                request, identity(Set.of("corpus-publishers")));
        assertThat(sourceVerifier.calls).hasValue(2);
        policies.available = false;
        sourceVerifier.available = false;

        CapabilityCorpusPublication retried = service.publish(
                request, identity(Set.of("corpus-publishers")));
        assertThat(retried).isEqualTo(publication);
        assertThat(sourceVerifier.calls).hasValue(2);
        assertThat(corpora.findLatestPublication(
                publication.scope(), publication.corpusId()))
                .contains(publication);
    }

    @Test
    void sourceRejectionAndAuthorityOutageCreateNoCandidate() {
        CapabilityCorpusCandidateRequest request =
                CapabilityCorpusTestFixtures.candidateRequest(
                        "rejected-corpus", 1, null, List.of(admitted));
        sourceVerifier.result =
                CapabilityCorpusSourceVerifier.VerificationResult.rejected(
                        "PAYLOAD_TOMBSTONED");

        assertProblem(
                () -> service.createCandidate(
                        request, identity(Set.of("curators"))),
                409,
                "RG.MIRROR.CORPUS_SOURCE_REJECTED");
        assertThat(corpora.findLatestRevision(
                admitted.envelope().material().scope(), "rejected-corpus"))
                .isEmpty();

        sourceVerifier.available = false;
        assertProblem(
                () -> service.createCandidate(
                        request, identity(Set.of("curators"))),
                503,
                "RG.MIRROR.CORPUS_SOURCE_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void refusesToPublishStaleCandidateHeadOrDriftedPolicy() {
        CapabilityCorpusRevision revision1 = service.createCandidate(
                CapabilityCorpusTestFixtures.candidateRequest(
                        "moving-corpus", 1, null, List.of(admitted)),
                identity(Set.of("curators")));
        CapabilityCorpusRevision revision2 = service.createCandidate(
                CapabilityCorpusTestFixtures.candidateRequest(
                        "moving-corpus",
                        2,
                        revision1.artifactRef(),
                        List.of(admitted)),
                identity(Set.of("curators")));

        assertProblem(
                () -> service.publish(
                        publishRequest(revision1, 1, null),
                        identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_CANDIDATE_HEAD_CHANGED");

        policies.policy = new CapabilityCorpusGovernancePolicyProvider.GovernancePolicy(
                policies.policy.scope(),
                policies.policy.capabilityRef(),
                CapabilityObservationTestFixtures.ref(
                        "CORPUS_GOVERNANCE_POLICY",
                        "new-policy",
                        3,
                        '8'),
                policies.policy.publicationPolicyRef(),
                policies.policy.quarantineReviewerGroups(),
                policies.policy.publisherGroups(),
                policies.policy.minimumSamples(),
                policies.policy.maximumSamples(),
                policies.policy.maximumDuplicateBasisPoints(),
                policies.policy.minimumProducerKeys(),
                policies.policy.minimumServingHorizon());
        assertProblem(
                () -> service.publish(
                        publishRequest(revision2, 1, null),
                        identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_POLICY_DRIFTED");
    }

    @Test
    void staleCandidateAndPublicationPredecessorsFailClosed() {
        CapabilityCorpusRevision revision1 = service.createCandidate(
                CapabilityCorpusTestFixtures.candidateRequest(
                        "fenced-corpus", 1, null, List.of(admitted)),
                identity(Set.of("curators")));
        CapabilityCorpusPublication publication1 = service.publish(
                publishRequest(revision1, 1, null),
                identity(Set.of("corpus-publishers")));

        assertProblem(
                () -> service.createCandidate(
                        CapabilityCorpusTestFixtures.candidateRequest(
                                "fenced-corpus",
                                2,
                                new MirrorArtifactRef(
                                        CapabilityCorpusRevision.ARTIFACT_KIND,
                                        "fenced-corpus",
                                        1,
                                        CapabilityObservationTestFixtures
                                                .fingerprint('4')),
                                List.of(admitted)),
                        identity(Set.of("curators"))),
                409,
                "RG.MIRROR.CORPUS_REVISION_HEAD_CONFLICT");

        CapabilityCorpusRevision revision2 = service.createCandidate(
                CapabilityCorpusTestFixtures.candidateRequest(
                        "fenced-corpus",
                        2,
                        revision1.artifactRef(),
                        List.of(admitted)),
                identity(Set.of("curators")));
        assertProblem(
                () -> service.publish(
                        publishRequest(
                                revision2,
                                2,
                                new MirrorArtifactRef(
                                        CapabilityCorpusPublication.ARTIFACT_KIND,
                                        "fenced-corpus",
                                        1,
                                        CapabilityObservationTestFixtures
                                                .fingerprint('5'))),
                        identity(Set.of("corpus-publishers"))),
                409,
                "RG.MIRROR.CORPUS_PUBLICATION_HEAD_CONFLICT");
        assertThat(publication1.revision()).isEqualTo(1);
    }

    private CapabilityCorpusPublishRequest publishRequest(
            CapabilityCorpusRevision revision,
            long publicationRevision,
            MirrorArtifactRef predecessor) {
        return new CapabilityCorpusPublishRequest(
                "",
                revision.corpusId(),
                publicationRevision,
                predecessor,
                revision.artifactRef(),
                CapabilityObservationTestFixtures.ref(
                        "GOVERNANCE_REVIEW_TICKET",
                        "ticket-" + revision.corpusId() + "-" + publicationRevision,
                        1,
                        '7'),
                "OWNER_APPROVED");
    }

    private static IntegrationRequestContext identity(Set<String> groups) {
        return CapabilityCorpusTestFixtures.identity("org-a", groups);
    }

    private static void assertProblem(
            Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                IntegrationProblemException.class,
                failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().details()).isEmpty();
                });
    }

    private static final class MutablePolicyProvider
            implements CapabilityCorpusGovernancePolicyProvider {
        private volatile boolean available = true;
        private volatile GovernancePolicy policy;
        private final AtomicInteger calls = new AtomicInteger();

        private MutablePolicyProvider(GovernancePolicy policy) {
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
            return Optional.ofNullable(policy);
        }
    }

    private static final class MutableSourceVerifier
            implements CapabilityCorpusSourceVerifier {
        private volatile boolean available = true;
        private volatile VerificationResult result;
        private final AtomicInteger calls = new AtomicInteger();

        private MutableSourceVerifier(VerificationResult result) {
            this.result = result;
        }

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
            return result;
        }
    }

    private static final class RecordingAudit
            implements MirrorOperationAuditRepository {
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
}
