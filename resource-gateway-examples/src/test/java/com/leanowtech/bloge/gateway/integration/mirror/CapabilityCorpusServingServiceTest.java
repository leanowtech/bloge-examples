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
    private MutablePolicyProvider policies;
    private MutableSourceVerifier sourceVerifier;
    private MutablePayloadAuthority payloadAuthority;
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
        scope = CapabilityObservationTestFixtures.scope("org-a");
        source = observation(
                "observation-exact", Map.of("customerId", "C-1"),
                Map.of("customerId", "C-recorded"));
        observations.append(source);
        now = source.admission().decidedAt().plusSeconds(2);
        policies = new MutablePolicyProvider(
                CapabilityCorpusTestFixtures.policy(source, 1, 10_000, 1));
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
        service = new CapabilityCorpusServingService(
                corpora, observations, policies, sourceVerifier, payloadAuthority,
                corpusIntegrity, mapper, Clock.fixed(now, ZoneOffset.UTC));
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
        assertThat(service.ready()).isTrue();
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

    private CapabilityObservationRepository.StoredObservation observation(
            String observationId,
            Object requestValue,
            Object responseValue) throws Exception {
        return observation(
                observationId, requestValue, responseValue, null);
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
                        "sha256:" + "e".repeat(64)));
    }

    private CapabilityObservationRepository.StoredObservation observation(
            String observationId,
            Object requestValue,
            Object responseValue,
            CapabilityObservationEnvelope.NormalizedError error) throws Exception {
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
                        List.of(
                                CapabilityObservationEnvelope.AllowedUse.CORPUS_CURATION,
                                CapabilityObservationEnvelope.AllowedUse.EXACT_REPLAY),
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

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Materialization materialize(MaterializationRequest request) {
            calls.incrementAndGet();
            byte[] value = payloads.get(request.payloadRef());
            return value == null
                    ? Materialization.rejected("PAYLOAD_NOT_FOUND")
                    : Materialization.materialized(Arrays.copyOf(value, value.length));
        }
    }
}
