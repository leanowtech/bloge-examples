package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCorpusServingServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityObservationIntegrity observationIntegrity =
            new CapabilityObservationIntegrity(mapper);
    private final CapabilityObservationAdmissionIntegrity admissionIntegrity =
            new CapabilityObservationAdmissionIntegrity(mapper);
    private final CapabilityCorpusIntegrity corpusIntegrity =
            new CapabilityCorpusIntegrity(mapper);
    private EmbeddedDatabase database;
    private DatabaseCapabilityObservationRepository observations;
    private DatabaseCapabilityCorpusRepository corpora;
    private DatabaseCapabilityCorpusTrajectoryRepository trajectories;
    private DatabaseCapabilityCorpusClusterRepository clusters;
    private MutablePolicyProvider policies;
    private MutableRetryPolicyProvider retryPolicies;
    private MutableClusterPolicyProvider clusterPolicies;
    private MutableClusterValidationAuthority clusterValidations;
    private MutableSourceVerifier sourceVerifier;
    private MutablePayloadAuthority payloadAuthority;
    private MutableServingGenerationAuthority servingGenerationAuthority;
    private CapabilityCorpusServingService service;
    private CapabilitySnapshot.Scope scope;
    private CapabilityObservationRepository.StoredObservation source;
    private CapabilityCorpusRevision revision;
    private CapabilityCorpusPublication publication;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        observations = new DatabaseCapabilityObservationRepository(
                jdbc, mapper, observationIntegrity, admissionIntegrity);
        observations.init();
        corpora = new DatabaseCapabilityCorpusRepository(
                jdbc, mapper, corpusIntegrity);
        corpora.init();
        trajectories = new DatabaseCapabilityCorpusTrajectoryRepository(
                jdbc, mapper, corpusIntegrity);
        trajectories.init();
        clusters = new DatabaseCapabilityCorpusClusterRepository(
                jdbc, mapper, corpusIntegrity);
        clusters.init();
        scope = CapabilityObservationTestFixtures.scope("org-a");
        source = observation(
                "observation-exact", Map.of("customerId", "C-1"),
                Map.of("customerId", "C-recorded"));
        observations.append(source);
        now = source.admission().decidedAt().plusSeconds(2);
        policies = new MutablePolicyProvider(
                CapabilityCorpusTestFixtures.policy(source, 1, 10_000, 1));
        retryPolicies = new MutableRetryPolicyProvider(
                retryPolicy(source.envelope().material().capabilityRef(), 1));
        clusterPolicies = new MutableClusterPolicyProvider(
                clusterPolicy(source.envelope().material().capabilityRef()));
        clusterValidations = new MutableClusterValidationAuthority();
        sourceVerifier = new MutableSourceVerifier(
                CapabilityCorpusSourceVerifier.VerificationResult.verified());
        payloadAuthority = new MutablePayloadAuthority();
        payloadAuthority.payloads.put(
                source.envelope().material().response().payloadRef(),
                mapper.writeValueAsBytes(Map.of("customerId", "C-recorded")));
        revision = revision("customer-corpus", List.of(source));
        publication = CapabilityCorpusTestFixtures.publication(
                mapper, revision, 1, null, now.minusSeconds(1));
        corpora.appendRevision(revision);
        corpora.appendPublication(publication);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryVisualEvidenceSigner generationSigner =
                InMemoryVisualEvidenceSigner.usingClock(clock);
        MirrorServingGenerationIntegrity generationIntegrity =
                new MirrorServingGenerationIntegrity(mapper);
        servingGenerationAuthority =
                new MutableServingGenerationAuthority(
                        generationIntegrity, generationSigner, now);
        var generationKey = generationSigner.resolveKeySet()
                .keySet().keys().getFirst();
        MirrorServingGenerationTrustProvider generationTrust =
                MirrorServingGenerationTrustProvider.fixed(
                        new MirrorServingGenerationTrustProvider.AuthorityKey(
                                MutableServingGenerationAuthority.AUTHORITY_ID,
                                generationKey.keyId(),
                                generationKey.algorithm(),
                                generationKey.encodedPublicKey(),
                                now.minus(Duration.ofMinutes(1)),
                                now.plus(Duration.ofHours(2)),
                                MirrorServingGenerationTrustProvider.KeyState.ACTIVE));
        MirrorServingGenerationService generationService =
                new MirrorServingGenerationService(
                        servingGenerationAuthority, generationTrust,
                        generationIntegrity, mapper, clock);
        service = new CapabilityCorpusServingService(
                corpora, observations, trajectories, clusters,
                policies, retryPolicies, clusterPolicies, clusterValidations,
                sourceVerifier, payloadAuthority, corpusIntegrity,
                generationService, mapper, clock);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void resolvesLatestPublicationIntoPayloadSafeExactOutcomes() {
        ResolvedCorpusPayloads resolved = service.resolve(
                fixture(publication),
                scope,
                executionPolicy(),
                now.plus(Duration.ofHours(1)),
                identity());

        assertThat(resolved.capabilityRefs())
                .containsExactly(source.envelope().material().capabilityRef());
        ResolvedCorpusPayloads.CapabilityCorpus corpus = resolved
                .bindSites(Map.of("/root/loadCustomer#PRIMARY",
                        source.envelope().material().capabilityRef()))
                .forSite("/root/loadCustomer#PRIMARY").orElseThrow();
        ResolvedCorpusPayloads.Sample sample = corpus.find(
                source.envelope().material().request().payloadRef().fingerprint())
                .orElseThrow();
        assertThat(sample.toRule(mapper).behavior().value())
                .isEqualTo(Map.of("customerId", "C-recorded"));
        assertThat(sample.artifactRefs()).contains(
                publication.artifactRef(),
                revision.artifactRef(),
                source.envelope().artifactRef(),
                source.admission().artifactRef(),
                source.envelope().material().dataUseGrant().grantRef());
        assertThat(resolved.toString()).doesNotContain("C-recorded");
        assertThat(sample.toString()).doesNotContain("C-recorded");
        assertThat(resolved.servingGenerationToken()).isPresent();
        resolved.admitRun();
        assertThat(service.ready()).isTrue();
        assertThatThrownBy(payloadAuthority.lastMaterialization::canonicalJson)
                .isInstanceOf(IllegalStateException.class);
        resolved.close();
    }

    @Test
    void resolverReadinessExposesIndependentAuthorityFailures() {
        assertThat(service.ready()).isTrue();
        assertThat(service.trajectoryReady()).isTrue();
        assertThat(service.clusterReady()).isTrue();

        retryPolicies.available = false;

        assertThat(service.ready()).isTrue();
        assertThat(service.trajectoryReady()).isFalse();
        assertThat(service.clusterReady()).isTrue();

        clusterValidations.available = false;

        assertThat(service.ready()).isTrue();
        assertThat(service.clusterReady()).isFalse();

        policies.available = false;

        assertThat(service.ready()).isFalse();
        assertThat(service.trajectoryReady()).isFalse();
        assertThat(service.clusterReady()).isFalse();
    }

    @Test
    void servingGenerationAuthorityRejectionAndOutageFailClosed() {
        servingGenerationAuthority.reject = true;

        assertProblem(
                () -> service.resolve(
                        fixture(publication), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity()),
                409, "RG.MIRROR.SERVING_GENERATION_REJECTED");

        servingGenerationAuthority.reject = false;
        servingGenerationAuthority.available = false;

        assertThat(service.ready()).isFalse();
        assertProblem(
                () -> service.resolve(
                        fixture(publication), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity()),
                503,
                "RG.MIRROR.SERVING_GENERATION_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void stalePublicationAndDeletedSourceFailClosed() {
        CapabilityCorpusPublication successor =
                CapabilityCorpusTestFixtures.publication(
                        mapper, revision, 2, publication.artifactRef(), now);
        corpora.appendPublication(successor);

        assertProblem(() -> service.resolve(
                        fixture(publication), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity()),
                409, "RG.MIRROR.CORPUS_PUBLICATION_STALE");

        sourceVerifier.result =
                CapabilityCorpusSourceVerifier.VerificationResult.rejected(
                        "PAYLOAD_TOMBSTONED");
        assertProblem(() -> service.resolve(
                        fixture(successor), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity()),
                409, "RG.MIRROR.CORPUS_SOURCE_UNUSABLE");
    }

    @Test
    void payloadAuthorityOutageAndContentDriftFailClosedWithoutPayloadDisclosure()
            throws Exception {
        payloadAuthority.available = false;
        assertProblem(() -> service.resolve(
                        fixture(publication), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity()),
                503, "RG.MIRROR.CORPUS_PAYLOAD_AUTHORITY_UNAVAILABLE");

        payloadAuthority.available = true;
        byte[] wrong = mapper.writeValueAsBytes(Map.of("customerId", "C-tampered"));
        payloadAuthority.payloads.put(
                source.envelope().material().response().payloadRef(), wrong);
        assertThatThrownBy(() -> service.resolve(
                fixture(publication), scope, executionPolicy(),
                now.plus(Duration.ofHours(1)), identity()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status()).isEqualTo(503);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.CORPUS_PAYLOAD_INTEGRITY_INVALID");
                            assertThat(failure.getMessage())
                                    .doesNotContain("C-tampered");
                        });
    }

    @Test
    void conflictingOutcomesForOneExactRequestRejectTheWholeGeneration()
            throws Exception {
        CapabilityObservationRepository.StoredObservation conflict = observation(
                "observation-conflict", Map.of("customerId", "C-1"),
                Map.of("customerId", "C-other"));
        observations.append(conflict);
        payloadAuthority.payloads.put(
                conflict.envelope().material().response().payloadRef(),
                mapper.writeValueAsBytes(Map.of("customerId", "C-other")));
        CapabilityCorpusRevision conflictingRevision = revision(
                "conflicting-corpus", List.of(source, conflict));
        CapabilityCorpusPublication conflictingPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper, conflictingRevision, 1, null, now.minusSeconds(1));
        corpora.appendRevision(conflictingRevision);
        corpora.appendPublication(conflictingPublication);

        assertProblem(() -> service.resolve(
                        fixture(conflictingPublication), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity()),
                409, "RG.MIRROR.CORPUS_EXACT_CONFLICT");
    }

    @Test
    void currentPolicyDriftInvalidatesAnOtherwiseIntactPublication() {
        CapabilityCorpusGovernancePolicyProvider.GovernancePolicy current =
                policies.policy;
        policies.policy =
                new CapabilityCorpusGovernancePolicyProvider.GovernancePolicy(
                        current.scope(),
                        current.capabilityRef(),
                        CapabilityObservationTestFixtures.ref(
                                "CORPUS_GOVERNANCE_POLICY",
                                "support-corpus-policy",
                                3,
                                '7'),
                        current.publicationPolicyRef(),
                        current.quarantineReviewerGroups(),
                        current.publisherGroups(),
                        current.minimumSamples(),
                        current.maximumSamples(),
                        current.maximumDuplicateBasisPoints(),
                        current.minimumProducerKeys(),
                        current.minimumServingHorizon());

        assertProblem(() -> service.resolve(
                        fixture(publication), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity()),
                409, "RG.MIRROR.CORPUS_POLICY_DRIFT");
        assertThat(payloadAuthority.calls).hasValue(0);
    }

    @Test
    void nonRetryableErrorsRemainExactButRetryableAttemptsRequireTrajectories()
            throws Exception {
        CapabilityObservationRepository.StoredObservation terminalError =
                errorObservation("observation-terminal-error", false);
        observations.append(terminalError);
        CapabilityCorpusRevision terminalRevision =
                revision("terminal-error-corpus", List.of(terminalError));
        CapabilityCorpusPublication terminalPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper, terminalRevision, 1, null, now.minusSeconds(1));
        corpora.appendRevision(terminalRevision);
        corpora.appendPublication(terminalPublication);

        ResolvedCorpusPayloads.Sample terminalSample = service.resolve(
                        fixture(terminalPublication), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity())
                .bindSites(Map.of(
                        "/root/loadCustomer#PRIMARY",
                        terminalError.envelope().material().capabilityRef()))
                .forSite("/root/loadCustomer#PRIMARY")
                .orElseThrow()
                .find(terminalError.envelope().material().request()
                        .payloadRef().fingerprint())
                .orElseThrow();

        assertThat(terminalSample.error()).isTrue();
        assertThat(terminalSample.toRule(mapper).behavior())
                .satisfies(behavior -> {
                    assertThat(behavior.kind())
                            .isEqualTo(
                                    com.leanowtech.bloge.gateway.testing.domain
                                            .FixtureRule.BehaviorKind.THROW);
                    assertThat(behavior.errorCode()).isEqualTo("CUSTOMER_NOT_FOUND");
                    assertThat(behavior.errorType()).isEqualTo("BUSINESS");
                });
        assertThat(payloadAuthority.calls).hasValue(0);

        CapabilityObservationRepository.StoredObservation retryableError =
                errorObservation("observation-retryable-error", true);
        observations.append(retryableError);
        CapabilityCorpusRevision retryableRevision =
                revision("retryable-error-corpus", List.of(retryableError));
        CapabilityCorpusPublication retryablePublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper, retryableRevision, 1, null, now.minusSeconds(1));
        corpora.appendRevision(retryableRevision);
        corpora.appendPublication(retryablePublication);

        assertProblem(() -> service.resolve(
                        fixture(retryablePublication), scope, executionPolicy(),
                        now.plus(Duration.ofHours(1)), identity()),
                409, "RG.MIRROR.CORPUS_RETRYABLE_ERROR_UNSUPPORTED");
    }

    @Test
    void resolvesGovernedRetryTrajectoryAndRechecksCurrentRetryPolicy()
            throws Exception {
        Object request = Map.of("customerId", "C-retry");
        Instant firstAt = Instant.now().minusSeconds(1);
        CapabilityObservationRepository.StoredObservation first =
                trajectoryObservation(
                        "observation-retry-1",
                        request,
                        null,
                        new CapabilityObservationEnvelope.NormalizedError(
                                "TRANSIENT",
                                "UPSTREAM_TIMEOUT",
                                true,
                                "sha256:" + "d".repeat(64)),
                        "trace-retry",
                        "span-retry-1",
                        1,
                        firstAt);
        CapabilityObservationRepository.StoredObservation second =
                trajectoryObservation(
                        "observation-retry-2",
                        request,
                        Map.of("customerId", "C-recovered"),
                        null,
                        "trace-retry",
                        "span-retry-2",
                        2,
                        firstAt.plusMillis(10));
        observations.append(first);
        observations.append(second);
        payloadAuthority.payloads.put(
                second.envelope().material().response().payloadRef(),
                mapper.writeValueAsBytes(
                        Map.of("customerId", "C-recovered")));
        CapabilityCorpusRevision retryRevision = revision(
                "retry-corpus", List.of(first, second));
        CapabilityCorpusPublication retryPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper, retryRevision, 1, null, now.minusSeconds(1));
        corpora.appendRevision(retryRevision);
        corpora.appendPublication(retryPublication);
        CapabilityCorpusTrajectoryPublishRequest requestCommand =
                CapabilityCorpusTestFixtures.trajectoryRequest(
                        retryPublication,
                        List.of(first, second),
                        retryPolicies.policy.policyRef());
        CapabilityCorpusTrajectoryPublication trajectory =
                CapabilityCorpusTestFixtures.trajectoryPublication(
                        mapper,
                        retryPublication,
                        retryRevision,
                        requestCommand,
                        null,
                        now.minusMillis(500));
        trajectories.append(trajectory);

        ResolvedCorpusPayloads.CapabilityCorpus resolved = service.resolve(
                        fixture(retryPublication, trajectory),
                        scope,
                        executionPolicy(),
                        now.plus(Duration.ofHours(1)),
                        identity())
                .bindSites(Map.of(
                        "/root/loadCustomer#PRIMARY",
                        first.envelope().material().capabilityRef()))
                .forSite("/root/loadCustomer#PRIMARY")
                .orElseThrow();

        assertThat(resolved.samples()).isEmpty();
        assertThat(resolved.trajectories()).singleElement()
                .satisfies(value -> {
                    assertThat(value.publicationRef())
                            .isEqualTo(trajectory.artifactRef());
                    assertThat(value.attempts()).hasSize(2);
                    assertThat(value.attempt(1).orElseThrow().error()).isTrue();
                    assertThat(value.attempt(2).orElseThrow().toRule(mapper)
                            .behavior().value())
                            .isEqualTo(Map.of(
                                    "customerId", "C-recovered"));
                    assertThat(value.attempt(2).orElseThrow().artifactRefs())
                            .contains(
                                    trajectory.artifactRef(),
                                    trajectory.retryPolicyRef(),
                                    trajectory.reviewTicketRef(),
                                    second.envelope().artifactRef());
                });
        assertThat(payloadAuthority.calls).hasValue(1);

        retryPolicies.policy = retryPolicy(
                first.envelope().material().capabilityRef(), 2);
        payloadAuthority.calls.set(0);
        assertProblem(() -> service.resolve(
                        fixture(retryPublication, trajectory),
                        scope,
                        executionPolicy(),
                        now.plus(Duration.ofHours(1)),
                        identity()),
                409, "RG.MIRROR.CORPUS_TRAJECTORY_RETRY_POLICY_DRIFT");
        assertThat(payloadAuthority.calls).hasValue(0);

        retryPolicies.policy = retryPolicy(
                first.envelope().material().capabilityRef(), 1);
        CapabilityCorpusTrajectoryPublishRequest successorCommand =
                new CapabilityCorpusTrajectoryPublishRequest(
                        "",
                        requestCommand.trajectoryId(),
                        2,
                        trajectory.artifactRef(),
                        requestCommand.capabilityRef(),
                        requestCommand.corpusPublicationRef(),
                        requestCommand.retryPolicyRef(),
                        requestCommand.attempts(),
                        requestCommand.reviewTicketRef(),
                        requestCommand.reasonCode());
        CapabilityCorpusTrajectoryPublication successor =
                CapabilityCorpusTestFixtures.trajectoryPublication(
                        mapper,
                        retryPublication,
                        retryRevision,
                        successorCommand,
                        trajectory.artifactRef(),
                        now.minusMillis(250));
        trajectories.append(successor);

        assertProblem(() -> service.resolve(
                        fixture(retryPublication, trajectory),
                        scope,
                        executionPolicy(),
                        now.plus(Duration.ofHours(1)),
                        identity()),
                409, "RG.MIRROR.CORPUS_TRAJECTORY_STALE");
        assertThat(payloadAuthority.calls).hasValue(0);

        sourceVerifier.result =
                CapabilityCorpusSourceVerifier.VerificationResult.rejected(
                        "GRANT_REVOKED");
        assertProblem(() -> service.resolve(
                        fixture(retryPublication, successor),
                        scope,
                        executionPolicy(),
                        now.plus(Duration.ofHours(1)),
                        identity()),
                409, "RG.MIRROR.CORPUS_SOURCE_UNUSABLE");
        assertThat(payloadAuthority.calls).hasValue(0);
    }

    @Test
    void resolvesValidatedClusterAndProjectsTheCurrentRequestIdentity()
            throws Exception {
        ClusterServingFixture installed = installCluster();
        payloadAuthority.calls.set(0);

        ResolvedCorpusPayloads.CapabilityCorpus resolved = service.resolve(
                        fixture(
                                installed.publication(),
                                installed.clusterPublication()),
                        scope,
                        executionPolicy(),
                        now.plus(Duration.ofMinutes(30)),
                        identity())
                .bindSites(Map.of(
                        "/root/loadCustomer#PRIMARY",
                        installed.revision().capabilityRef()))
                .forSite("/root/loadCustomer#PRIMARY")
                .orElseThrow();
        Map<String, Object> currentRequest = Map.of(
                "channel", "web",
                "operation", "lookup",
                "customerId", "C-current");
        ResolvedCorpusPayloads.ClusterResolution match =
                resolved.findCluster(
                                ProtocolFingerprint.of(
                                        mapper, currentRequest),
                                currentRequest,
                                mapper)
                        .orElseThrow();

        assertThat(match.sample().toRule(mapper).behavior().value())
                .isEqualTo(Map.of(
                        "customer", Map.of("id", "C-current"),
                        "tier", "gold"));
        assertThat(match.confidence())
                .isEqualTo(installed.clusterPublication().confidence());
        assertThat(match.sample().artifactRefs())
                .contains(
                        installed.clusterPublication().artifactRef(),
                        installed.validation().artifactRef(),
                        installed.clusterPublication().clusterPolicyRef(),
                        installed.publication().artifactRef(),
                        installed.revision().artifactRef());
        assertThat(match.sample().limitations())
                .contains(
                        "CLUSTER_GENERALIZATION_REQUIRES_EXACT_MATCH_POINTERS",
                        "IDENTITY_REQUEST_PROJECTION",
                        "STATE_DEPENDENCE_NOT_MODELED");
        assertThat(match.sample().toString())
                .doesNotContain("C-current")
                .doesNotContain("C-recorded-a");
        assertThat(payloadAuthority.calls).hasValue(5);
    }

    @Test
    void staleClusterAndValidationOutageFailBeforePayloadMaterialization()
            throws Exception {
        ClusterServingFixture installed = installCluster();
        CapabilityCorpusClusterPublishRequest successorRequest =
                new CapabilityCorpusClusterPublishRequest(
                        "",
                        installed.clusterPublication().clusterId(),
                        2,
                        installed.clusterPublication().artifactRef(),
                        installed.revision().capabilityRef(),
                        installed.publication().artifactRef(),
                        installed.clusterPublication().clusterPolicyRef(),
                        installed.validation().artifactRef(),
                        installed.clusterPublication().reviewTicketRef(),
                        installed.clusterPublication().reasonCode());
        CapabilityCorpusClusterPublication successor =
                CapabilityCorpusTestFixtures.clusterPublication(
                        mapper,
                        installed.publication(),
                        installed.revision(),
                        installed.validation(),
                        successorRequest,
                        installed.clusterPublication().artifactRef(),
                        now.minusMillis(100));
        clusters.append(successor);
        payloadAuthority.calls.set(0);

        assertProblem(
                () -> service.resolve(
                        fixture(
                                installed.publication(),
                                installed.clusterPublication()),
                        scope,
                        executionPolicy(),
                        now.plus(Duration.ofMinutes(30)),
                        identity()),
                409,
                "RG.MIRROR.CORPUS_CLUSTER_STALE");
        assertThat(payloadAuthority.calls).hasValue(0);

        clusterPolicies.policy = clusterPolicy(
                installed.revision().capabilityRef(),
                ignored -> {
                    throw new IllegalStateException("policy outage");
                });
        assertProblem(
                () -> service.resolve(
                        fixture(installed.publication(), successor),
                        scope,
                        executionPolicy(),
                        now.plus(Duration.ofMinutes(30)),
                        identity()),
                503,
                "RG.MIRROR.CORPUS_CLUSTER_POLICY_UNAVAILABLE");
        assertThat(payloadAuthority.calls).hasValue(0);

        clusterPolicies.policy = clusterPolicy(
                installed.revision().capabilityRef());
        clusterValidations.available = false;
        assertProblem(
                () -> service.resolve(
                        fixture(installed.publication(), successor),
                        scope,
                        executionPolicy(),
                        now.plus(Duration.ofMinutes(30)),
                        identity()),
                503,
                "RG.MIRROR.CORPUS_CLUSTER_AUTHORITY_UNAVAILABLE");
        assertThat(payloadAuthority.calls).hasValue(0);
    }

    @Test
    void regionClassificationAndPlanHorizonAreEnforcedBeforeMaterialization() {
        MirrorPlan.ExecutionPolicy wrongRegion = new MirrorPlan.ExecutionPolicy(
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                false, false, false, false, true,
                MirrorPlan.UnmatchedResolution.ABSTAINED, 1000,
                Duration.ofMinutes(5),
                CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("eu"), List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
        assertProblem(() -> service.resolve(
                        fixture(publication), scope, wrongRegion,
                        now.plus(Duration.ofHours(1)), identity()),
                403, "RG.MIRROR.CORPUS_REGION_FORBIDDEN");

        assertProblem(() -> service.resolve(
                        fixture(publication), scope, executionPolicy(),
                        source.admission().usableUntil().plusSeconds(1), identity()),
                409, "RG.MIRROR.CORPUS_PUBLICATION_EXPIRES_EARLY");
        assertThat(payloadAuthority.calls).hasValue(0);
    }

    @Test
    void payloadMaterializationUsesTheSharedEvidenceSizeBound() {
        assertThat(materializationRequest(
                MirrorResolutionIntegrity.MAXIMUM_OUTPUT_BYTES).declaredSizeBytes())
                .isEqualTo(MirrorResolutionIntegrity.MAXIMUM_OUTPUT_BYTES);

        assertThatThrownBy(() -> materializationRequest(
                MirrorResolutionIntegrity.MAXIMUM_OUTPUT_BYTES + 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declaredSizeBytes");
    }

    private CapabilityCorpusPayloadAuthority.MaterializationRequest
            materializationRequest(long declaredSizeBytes) {
        CapabilityObservationEnvelope.Material material = source.envelope().material();
        CapabilityObservationEnvelope.PayloadReference response = material.response();
        return new CapabilityCorpusPayloadAuthority.MaterializationRequest(
                scope,
                material.capabilityRef(),
                publication.artifactRef(),
                source.envelope().artifactRef(),
                response.payloadRef(),
                response.sanitizationProofRef(),
                response.schemaRef(),
                response.classification(),
                response.vaultRegion(),
                declaredSizeBytes,
                material.dataUseGrant().grantRef(),
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                now,
                now.plusSeconds(60));
    }

    private ClusterServingFixture installCluster() throws Exception {
        Map<String, Object> requestA = Map.of(
                "channel", "web",
                "operation", "lookup",
                "customerId", "C-recorded-a");
        Map<String, Object> requestB = Map.of(
                "channel", "web",
                "operation", "lookup",
                "customerId", "C-recorded-b");
        Map<String, Object> responseA = Map.of(
                "customer", Map.of("id", "C-recorded-a"),
                "tier", "gold");
        Map<String, Object> responseB = Map.of(
                "customer", Map.of("id", "C-recorded-b"),
                "tier", "gold");
        List<CapabilityObservationEnvelope.AllowedUse> allowedUses =
                List.of(
                        CapabilityObservationEnvelope.AllowedUse
                                .CLUSTER_MODELING,
                        CapabilityObservationEnvelope.AllowedUse
                                .CORPUS_CURATION,
                        CapabilityObservationEnvelope.AllowedUse
                                .EXACT_REPLAY);
        CapabilityObservationRepository.StoredObservation first =
                observation(
                        "observation-cluster-a",
                        requestA,
                        responseA,
                        null,
                        allowedUses);
        CapabilityObservationRepository.StoredObservation second =
                observation(
                        "observation-cluster-b",
                        requestB,
                        responseB,
                        null,
                        allowedUses);
        List<CapabilityObservationRepository.StoredObservation> sources =
                List.of(first, second);
        sources.forEach(observations::append);
        payloadAuthority.payloads.put(
                first.envelope().material().request().payloadRef(),
                mapper.writeValueAsBytes(requestA));
        payloadAuthority.payloads.put(
                first.envelope().material().response().payloadRef(),
                mapper.writeValueAsBytes(responseA));
        payloadAuthority.payloads.put(
                second.envelope().material().request().payloadRef(),
                mapper.writeValueAsBytes(requestB));
        payloadAuthority.payloads.put(
                second.envelope().material().response().payloadRef(),
                mapper.writeValueAsBytes(responseB));

        policies.policy = CapabilityCorpusTestFixtures.policy(
                first, 1, 10_000, 1);
        clusterPolicies.policy = clusterPolicy(
                first.envelope().material().capabilityRef());
        CapabilityCorpusRevision clusterRevision =
                revision("cluster-serving-corpus", sources);
        CapabilityCorpusPublication clusterCorpusPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        clusterRevision,
                        1,
                        null,
                        now.minusSeconds(1));
        corpora.appendRevision(clusterRevision);
        corpora.appendPublication(clusterCorpusPublication);
        CapabilityCorpusClusterValidation validation =
                CapabilityCorpusTestFixtures.clusterValidation(
                        mapper,
                        clusterCorpusPublication,
                        clusterRevision,
                        sources,
                        now.minusSeconds(2));
        clusterValidations.validation = validation;
        CapabilityCorpusClusterPublishRequest request =
                CapabilityCorpusTestFixtures.clusterRequest(
                        clusterCorpusPublication, validation);
        CapabilityCorpusClusterPublication clusterPublication =
                CapabilityCorpusTestFixtures.clusterPublication(
                        mapper,
                        clusterCorpusPublication,
                        clusterRevision,
                        validation,
                        request,
                        null,
                        now.minusMillis(500));
        clusters.append(clusterPublication);
        return new ClusterServingFixture(
                clusterRevision,
                clusterCorpusPublication,
                validation,
                clusterPublication);
    }

    private CapabilityCorpusClusterPolicyProvider.ClusterPolicy
            clusterPolicy(MirrorArtifactRef capabilityRef) {
        return clusterPolicy(capabilityRef, ignored -> true);
    }

    private CapabilityCorpusClusterPolicyProvider.ClusterPolicy
            clusterPolicy(
            MirrorArtifactRef capabilityRef,
            java.util.function.Predicate<CapabilityCorpusClusterValidation>
                    validationPolicy) {
        return new CapabilityCorpusClusterPolicyProvider.ClusterPolicy(
                scope,
                capabilityRef,
                CapabilityObservationTestFixtures.ref(
                        "CORPUS_CLUSTER_POLICY",
                        "support-cluster-policy",
                        3,
                        'b'),
                2,
                2,
                10,
                500,
                0.8,
                Duration.ofHours(2),
                ignored -> true,
                validationPolicy);
    }

    private CapabilityObservationRepository.StoredObservation observation(
            String observationId,
            Object requestValue,
            Object responseValue) throws Exception {
        return observation(
                observationId,
                requestValue,
                responseValue,
                null,
                List.of(
                        CapabilityObservationEnvelope.AllowedUse
                                .CORPUS_CURATION,
                        CapabilityObservationEnvelope.AllowedUse
                                .EXACT_REPLAY));
    }

    private CapabilityObservationRepository.StoredObservation errorObservation(
            String observationId,
            boolean retryable) throws Exception {
        return observation(
                observationId,
                Map.of("customerId", "C-missing"),
                null,
                new CapabilityObservationEnvelope.NormalizedError(
                        "BUSINESS",
                        "CUSTOMER_NOT_FOUND",
                        retryable,
                        "sha256:" + "e".repeat(64)),
                List.of(
                        CapabilityObservationEnvelope.AllowedUse
                                .CORPUS_CURATION,
                        CapabilityObservationEnvelope.AllowedUse
                                .EXACT_REPLAY));
    }

    private CapabilityObservationRepository.StoredObservation observation(
            String observationId,
            Object requestValue,
            Object responseValue,
            CapabilityObservationEnvelope.NormalizedError error,
            List<CapabilityObservationEnvelope.AllowedUse> allowedUses)
            throws Exception {
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        Instant occurredAt = Instant.now().minusSeconds(3);
        byte[] requestJson = mapper.writeValueAsBytes(requestValue);
        CapabilityObservationEnvelope.PayloadReference request = payload(
                "request-" + observationId, requestJson, occurredAt.plus(Duration.ofDays(30)));
        CapabilityObservationEnvelope.PayloadReference response = responseValue == null
                ? null
                : payload(
                        "response-" + observationId,
                        mapper.writeValueAsBytes(responseValue),
                        occurredAt.plus(Duration.ofDays(30)));
        CapabilityObservationEnvelope.DataUseGrant grant =
                new CapabilityObservationEnvelope.DataUseGrant(
                        CapabilityObservationTestFixtures.ref(
                                "DATA_USE_GRANT", "grant-" + observationId, 1, '9'),
                        CapabilityObservationAdmissionService.AUTHORIZED_PURPOSE,
                        allowedUses,
                        occurredAt.minus(Duration.ofDays(1)),
                        occurredAt.plus(Duration.ofDays(20)));
        CapabilityObservationEnvelope.Material material =
                new CapabilityObservationEnvelope.Material(
                        observationId,
                        scope,
                        new MirrorArtifactRef(
                                "CAPABILITY", capability.capabilityId(),
                                capability.revision(), capability.fingerprint()),
                        occurredAt,
                        new CapabilityObservationEnvelope.TraceCoordinates(
                                "trace-" + observationId,
                                "span-" + observationId,
                                1),
                        request,
                        response,
                        error,
                        42,
                        null,
                        null,
                        grant);
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        CapabilityObservationEnvelope envelope = observationIntegrity.seal(
                material, signer, CapabilityObservationTestFixtures.ISSUER);
        CapabilityObservationIntegrity.AuthorityKey authority =
                CapabilityObservationTestFixtures.authorityKey(
                        envelope, signer, CapabilityObservationIntegrity.KeyState.ACTIVE);
        Instant decidedAt = envelope.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission admission = admissionIntegrity.admitted(
                envelope,
                CapabilityObservationTestFixtures.ref(
                        "OBSERVATION_ADMISSION_POLICY",
                        "support-admission-policy", 3, 'f'),
                authority.keyRef(),
                decidedAt,
                decidedAt.plus(Duration.ofDays(10)));
        return new CapabilityObservationRepository.StoredObservation(
                envelope, admission);
    }

    private CapabilityObservationRepository.StoredObservation trajectoryObservation(
            String observationId,
            Object requestValue,
            Object responseValue,
            CapabilityObservationEnvelope.NormalizedError error,
            String traceId,
            String spanId,
            long sequence,
            Instant occurredAt) throws Exception {
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        byte[] requestJson = mapper.writeValueAsBytes(requestValue);
        CapabilityObservationEnvelope.PayloadReference request = payload(
                "request-" + observationId,
                requestJson,
                occurredAt.plus(Duration.ofDays(30)));
        CapabilityObservationEnvelope.PayloadReference response =
                responseValue == null ? null : payload(
                        "response-" + observationId,
                        mapper.writeValueAsBytes(responseValue),
                        occurredAt.plus(Duration.ofDays(30)));
        CapabilityObservationEnvelope.DataUseGrant grant =
                new CapabilityObservationEnvelope.DataUseGrant(
                        CapabilityObservationTestFixtures.ref(
                                "DATA_USE_GRANT",
                                "grant-" + observationId,
                                1,
                                '8'),
                        CapabilityObservationAdmissionService.AUTHORIZED_PURPOSE,
                        List.of(
                                CapabilityObservationEnvelope.AllowedUse
                                        .CORPUS_CURATION,
                                CapabilityObservationEnvelope.AllowedUse
                                        .EXACT_REPLAY,
                                CapabilityObservationEnvelope.AllowedUse
                                        .TRAJECTORY_MODELING),
                        occurredAt.minus(Duration.ofDays(1)),
                        occurredAt.plus(Duration.ofDays(20)));
        CapabilityObservationEnvelope.Material material =
                new CapabilityObservationEnvelope.Material(
                        observationId,
                        scope,
                        new MirrorArtifactRef(
                                "CAPABILITY",
                                capability.capabilityId(),
                                capability.revision(),
                                capability.fingerprint()),
                        occurredAt,
                        new CapabilityObservationEnvelope.TraceCoordinates(
                                traceId, spanId, sequence),
                        request,
                        response,
                        error,
                        42,
                        null,
                        null,
                        grant);
        InMemoryVisualEvidenceSigner signer =
                new InMemoryVisualEvidenceSigner();
        CapabilityObservationEnvelope envelope = observationIntegrity.seal(
                material,
                signer,
                CapabilityObservationTestFixtures.ISSUER);
        CapabilityObservationIntegrity.AuthorityKey authority =
                CapabilityObservationTestFixtures.authorityKey(
                        envelope,
                        signer,
                        CapabilityObservationIntegrity.KeyState.ACTIVE);
        Instant decidedAt = envelope.seal().signedAt().plusSeconds(1);
        CapabilityObservationAdmission admission = admissionIntegrity.admitted(
                envelope,
                CapabilityObservationTestFixtures.ref(
                        "OBSERVATION_ADMISSION_POLICY",
                        "support-admission-policy",
                        3,
                        'f'),
                authority.keyRef(),
                decidedAt,
                decidedAt.plus(Duration.ofDays(10)));
        return new CapabilityObservationRepository.StoredObservation(
                envelope, admission);
    }

    private CapabilityObservationEnvelope.PayloadReference payload(
            String id,
            byte[] json,
            Instant retentionUntil) throws Exception {
        String fingerprint = ProtocolFingerprint.of(
                mapper, mapper.readTree(json));
        return new CapabilityObservationEnvelope.PayloadReference(
                new MirrorArtifactRef("SANITIZED_PAYLOAD", id, 1, fingerprint),
                CapabilityObservationTestFixtures.ref(
                        "PAYLOAD_SANITIZATION_PROOF", id + "-proof", 1, 'a'),
                CapabilityObservationTestFixtures.ref(
                        "JSON_SCHEMA", id + "-schema", 1, 'b'),
                json.length,
                "application/json",
                CapabilityObservationEnvelope.Classification.CONFIDENTIAL,
                "sg",
                retentionUntil);
    }

    private CapabilityCorpusRevision revision(
            String corpusId,
            List<CapabilityObservationRepository.StoredObservation> sources) {
        List<CapabilityObservationRepository.StoredObservation> ordered = sources.stream()
                .sorted((left, right) -> left.envelope().material().observationId()
                        .compareTo(right.envelope().material().observationId()))
                .toList();
        List<CapabilityCorpusRevision.SourceObservation> projections =
                ordered.stream().map(this::projection).toList();
        CapabilityCorpusCandidateRequest request =
                CapabilityCorpusTestFixtures.candidateRequest(
                        corpusId, 1, null, ordered);
        Instant usableUntil = ordered.stream()
                .map(value -> value.admission().usableUntil())
                .min(Instant::compareTo).orElseThrow();
        int uniqueRequests = (int) projections.stream()
                .map(value -> value.requestPayloadRef().fingerprint())
                .distinct().count();
        int duplicateCount = projections.size() - uniqueRequests;
        int maximumMultiplicity = projections.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        value -> value.requestPayloadRef().fingerprint(),
                        java.util.stream.Collectors.counting()))
                .values().stream().mapToInt(Long::intValue).max().orElse(1);
        CapabilityCorpusRevision candidate = new CapabilityCorpusRevision(
                "",
                "sha256:" + "0".repeat(64),
                corpusIntegrity.candidateCommandFingerprint(request),
                scope,
                corpusId,
                1,
                null,
                ordered.getFirst().envelope().material().capabilityRef(),
                policies.policy.governancePolicyRef(),
                projections,
                new CapabilityCorpusRevision.RiskSummary(
                        projections.size(),
                        uniqueRequests,
                        duplicateCount,
                        maximumMultiplicity,
                        1,
                        duplicateCount * 10_000 / projections.size(),
                        CapabilityCorpusRevision.Eligibility.ELIGIBLE,
                        Set.of()),
                "corpus-curator",
                now.minusSeconds(1),
                usableUntil);
        return corpusIntegrity.sealRevision(candidate);
    }

    private CapabilityCorpusRevision.SourceObservation projection(
            CapabilityObservationRepository.StoredObservation stored) {
        CapabilityObservationEnvelope.Material material = stored.envelope().material();
        CapabilityObservationEnvelope.PayloadReference request = material.request();
        CapabilityObservationEnvelope.PayloadReference response = material.response();
        return new CapabilityCorpusRevision.SourceObservation(
                stored.envelope().artifactRef(),
                stored.admission().artifactRef(),
                request.payloadRef(),
                request.sanitizationProofRef(),
                request.schemaRef(),
                response == null ? null : response.payloadRef(),
                response == null ? null : response.sanitizationProofRef(),
                response == null ? null : response.schemaRef(),
                material.error() == null ? "" : material.error().errorCode(),
                corpusIntegrity.traceFingerprint(material.trace()),
                stored.admission().authorityKeyRef(),
                material.occurredAt(),
                stored.admission().usableUntil());
    }

    private FixtureBundle fixture(CapabilityCorpusPublication value) {
        MirrorArtifactRef capabilityRef = source.envelope().material().capabilityRef();
        Map<String, Object> binding = Map.of(
                "capabilityRef", wire(capabilityRef),
                "publicationRef", wire(value.artifactRef()));
        Map<String, Object> mirrorCorpus = Map.of(
                "schemaVersion", FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                "publications", List.of(binding));
        return new FixtureBundle("", "fixture-corpus", 1,
                "sha256:" + "c".repeat(64), "CONFIDENTIAL",
                now, 42L, List.of(), List.of(),
                Map.of(FixtureMirrorCorpusBindings.METADATA_KEY, mirrorCorpus));
    }

    private FixtureBundle fixture(
            CapabilityCorpusPublication value,
            CapabilityCorpusTrajectoryPublication trajectory) {
        MirrorArtifactRef capabilityRef =
                trajectory.capabilityRef();
        Map<String, Object> corpusBinding = Map.of(
                "capabilityRef", wire(capabilityRef),
                "publicationRef", wire(value.artifactRef()));
        Map<String, Object> trajectoryBinding = Map.of(
                "capabilityRef", wire(capabilityRef),
                "corpusPublicationRef", wire(value.artifactRef()),
                "trajectoryPublicationRef", wire(trajectory.artifactRef()));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(
                FixtureMirrorCorpusBindings.METADATA_KEY,
                Map.of(
                        "schemaVersion",
                        FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                        "publications",
                        List.of(corpusBinding)));
        metadata.put(
                FixtureMirrorTrajectoryBindings.METADATA_KEY,
                Map.of(
                        "schemaVersion",
                        FixtureMirrorTrajectoryBindings.SCHEMA_VERSION,
                        "trajectories",
                        List.of(trajectoryBinding)));
        return new FixtureBundle(
                "",
                "fixture-corpus-trajectory",
                1,
                "sha256:" + "d".repeat(64),
                "CONFIDENTIAL",
                now,
                42L,
                List.of(),
                List.of(),
                metadata);
    }

    private FixtureBundle fixture(
            CapabilityCorpusPublication value,
            CapabilityCorpusClusterPublication cluster) {
        MirrorArtifactRef capabilityRef = cluster.capabilityRef();
        Map<String, Object> corpusBinding = Map.of(
                "capabilityRef", wire(capabilityRef),
                "publicationRef", wire(value.artifactRef()));
        Map<String, Object> clusterBinding = Map.of(
                "capabilityRef", wire(capabilityRef),
                "corpusPublicationRef", wire(value.artifactRef()),
                "clusterPublicationRef", wire(cluster.artifactRef()));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(
                FixtureMirrorCorpusBindings.METADATA_KEY,
                Map.of(
                        "schemaVersion",
                        FixtureMirrorCorpusBindings.SCHEMA_VERSION,
                        "publications",
                        List.of(corpusBinding)));
        metadata.put(
                FixtureMirrorClusterBindings.METADATA_KEY,
                Map.of(
                        "schemaVersion",
                        FixtureMirrorClusterBindings.SCHEMA_VERSION,
                        "clusters",
                        List.of(clusterBinding)));
        return new FixtureBundle(
                "",
                "fixture-corpus-cluster",
                1,
                "sha256:" + "e".repeat(64),
                "CONFIDENTIAL",
                now,
                42L,
                List.of(),
                List.of(),
                metadata);
    }

    private static Map<String, Object> wire(MirrorArtifactRef ref) {
        return Map.of(
                "kind", ref.kind(),
                "id", ref.id(),
                "revision", ref.revision(),
                "fingerprint", ref.fingerprint());
    }

    private static MirrorPlan.ExecutionPolicy executionPolicy() {
        return new MirrorPlan.ExecutionPolicy(
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                false, false, false, false, true,
                MirrorPlan.UnmatchedResolution.ABSTAINED, 1000,
                Duration.ofMinutes(5),
                CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg"), List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
    }

    private IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(),
                "SERVICE", "mirror-runner", "",
                MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                "corr-serving", Set.of("mirror-runners"),
                "CONFIDENTIAL", "");
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

    private record ClusterServingFixture(
            CapabilityCorpusRevision revision,
            CapabilityCorpusPublication publication,
            CapabilityCorpusClusterValidation validation,
            CapabilityCorpusClusterPublication clusterPublication
    ) {
    }

    private static final class MutablePolicyProvider
            implements CapabilityCorpusGovernancePolicyProvider {
        private GovernancePolicy policy;
        private boolean available = true;

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
            return available
                    && policy.scope().equals(scope)
                    && policy.capabilityRef().equals(capabilityRef)
                    ? Optional.of(policy) : Optional.empty();
        }
    }

    private CapabilityRetryPolicyProvider.RetryPolicy retryPolicy(
            MirrorArtifactRef capabilityRef,
            long revision) {
        return new CapabilityRetryPolicyProvider.RetryPolicy(
                scope,
                capabilityRef,
                CapabilityObservationTestFixtures.ref(
                        "RETRY_POLICY",
                        "customer-retry-policy",
                        revision,
                        revision == 1 ? '5' : '6'),
                3,
                Set.of("TRANSIENT"),
                Set.of("UPSTREAM_TIMEOUT"));
    }

    private static final class MutableRetryPolicyProvider
            implements CapabilityRetryPolicyProvider {
        private RetryPolicy policy;
        private boolean available = true;

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
            return available
                    && policy.scope().equals(scope)
                    && policy.capabilityRef().equals(capabilityRef)
                    ? Optional.of(policy) : Optional.empty();
        }
    }

    private static final class MutableClusterPolicyProvider
            implements CapabilityCorpusClusterPolicyProvider {
        private ClusterPolicy policy;
        private boolean available = true;

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
            return available
                    && policy.scope().equals(scope)
                    && policy.capabilityRef().equals(capabilityRef)
                    ? Optional.of(policy) : Optional.empty();
        }
    }

    private static final class MutableClusterValidationAuthority
            implements CapabilityCorpusClusterValidationAuthority {
        private CapabilityCorpusClusterValidation validation;
        private boolean available = true;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<CapabilityCorpusClusterValidation> resolve(
                CapabilitySnapshot.Scope scope,
                MirrorArtifactRef validationRef) {
            return available
                    && validation != null
                    && validation.scope().equals(scope)
                    && validation.artifactRef().equals(validationRef)
                    ? Optional.of(validation) : Optional.empty();
        }
    }

    private static final class MutableServingGenerationAuthority
            implements MirrorServingGenerationAuthority {
        private static final String AUTHORITY_ID =
                "test-corpus-serving-authority";

        private final MirrorServingGenerationIntegrity integrity;
        private final InMemoryVisualEvidenceSigner signer;
        private final Instant issuedAt;
        private boolean available = true;
        private boolean reject;
        private MirrorServingGenerationToken current;

        private MutableServingGenerationAuthority(
                MirrorServingGenerationIntegrity integrity,
                InMemoryVisualEvidenceSigner signer,
                Instant issuedAt) {
            this.integrity = integrity;
            this.signer = signer;
            this.issuedAt = issuedAt;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Resolution admit(AdmissionRequest request) {
            if (!available) {
                return Resolution.unavailable("TEST_AUTHORITY_UNAVAILABLE");
            }
            if (reject) {
                return Resolution.rejected("TEST_POLICY_REJECTED");
            }
            current = integrity.seal(
                    new MirrorServingGenerationToken.Material(
                            "support-corpus-serving", 1, "",
                            request.scope(), request.authorizedPurpose(),
                            request.dependencyClosureFingerprint(), 1,
                            issuedAt, request.requiredUntil().plusSeconds(30),
                            Duration.ofSeconds(5)),
                    AUTHORITY_ID, signer);
            return Resolution.current(current);
        }

        @Override
        public Resolution currentFloor(FloorRequest request) {
            if (!available) {
                return Resolution.unavailable("TEST_AUTHORITY_UNAVAILABLE");
            }
            return current == null
                    ? Resolution.rejected("TEST_FLOOR_NOT_FOUND")
                    : Resolution.current(current);
        }
    }

    private static final class MutableSourceVerifier
            implements CapabilityCorpusSourceVerifier {
        private boolean available = true;
        private VerificationResult result;

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
            return result;
        }
    }

    private static final class MutablePayloadAuthority
            implements CapabilityCorpusPayloadAuthority {
        private final Map<MirrorArtifactRef, byte[]> payloads = new HashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        private boolean available = true;
        private Materialization lastMaterialization;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Materialization materialize(MaterializationRequest request) {
            calls.incrementAndGet();
            byte[] value = payloads.get(request.payloadRef());
            lastMaterialization = value == null
                    ? Materialization.rejected("PAYLOAD_NOT_FOUND")
                    : Materialization.materialized(Arrays.copyOf(value, value.length));
            return lastMaterialization;
        }
    }
}
