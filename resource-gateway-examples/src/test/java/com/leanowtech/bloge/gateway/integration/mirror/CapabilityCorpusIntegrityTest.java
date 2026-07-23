package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCorpusIntegrityTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityCorpusIntegrity integrity =
            new CapabilityCorpusIntegrity(mapper);

    @Test
    void sealsAndVerifiesReviewRevisionAndPublication() {
        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        CapabilityObservationRepository.StoredObservation quarantined =
                CapabilityCorpusTestFixtures.quarantined(
                        mapper, scope, "observation-review");
        CapabilityObservationReviewRequest reviewRequest =
                CapabilityCorpusTestFixtures.reviewRequest(quarantined);
        CapabilityObservationReview review = integrity.sealReview(
                new CapabilityObservationReview(
                        "",
                        CapabilityObservationTestFixtures.fingerprint('0'),
                        integrity.reviewCommandFingerprint(reviewRequest),
                        scope,
                        quarantined.envelope().artifactRef(),
                        quarantined.admission().artifactRef(),
                        reviewRequest.disposition(),
                        reviewRequest.reviewTicketRef(),
                        reviewRequest.reasonCode(),
                        "reviewer",
                        Instant.parse("2026-07-23T00:00:00Z")));

        CapabilityObservationRepository.StoredObservation admitted =
                CapabilityCorpusTestFixtures.admitted(
                        mapper, scope, "observation-corpus");
        Instant now = admitted.admission().decidedAt().plusSeconds(1);
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper, admitted, "support-corpus", 1, null, now);
        CapabilityCorpusPublication publication =
                CapabilityCorpusTestFixtures.publication(
                        mapper, revision, 1, null, now.plusSeconds(1));

        assertThat(integrity.reviewVerified(review)).isTrue();
        assertThat(integrity.revisionVerified(revision)).isTrue();
        assertThat(integrity.publicationVerified(publication)).isTrue();
        assertThat(review.artifactRef().fingerprint())
                .isEqualTo(review.reviewFingerprint());
        assertThat(revision.artifactRef().fingerprint())
                .isEqualTo(revision.revisionFingerprint());
        assertThat(publication.artifactRef().fingerprint())
                .isEqualTo(publication.publicationFingerprint());
    }

    @Test
    void rejectsTamperedReviewRevisionAndPublication() {
        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        CapabilityObservationRepository.StoredObservation admitted =
                CapabilityCorpusTestFixtures.admitted(
                        mapper, scope, "observation-tamper");
        Instant now = admitted.admission().decidedAt().plusSeconds(1);
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper, admitted, "support-corpus", 1, null, now);
        CapabilityCorpusRevision tamperedRevision =
                new CapabilityCorpusRevision(
                        revision.schemaVersion(),
                        revision.revisionFingerprint(),
                        revision.sourceCommandFingerprint(),
                        revision.scope(),
                        revision.corpusId(),
                        revision.revision(),
                        revision.predecessorRef(),
                        revision.capabilityRef(),
                        revision.governancePolicyRef(),
                        revision.sources(),
                        revision.riskSummary(),
                        "different-curator",
                        revision.createdAt(),
                        revision.usableUntil());
        CapabilityCorpusPublication publication =
                CapabilityCorpusTestFixtures.publication(
                        mapper, revision, 1, null, now.plusSeconds(1));
        CapabilityCorpusPublication tamperedPublication =
                new CapabilityCorpusPublication(
                        publication.schemaVersion(),
                        publication.publicationFingerprint(),
                        publication.sourceCommandFingerprint(),
                        publication.scope(),
                        publication.corpusId(),
                        publication.revision(),
                        publication.predecessorRef(),
                        publication.corpusRevisionRef(),
                        publication.publicationPolicyRef(),
                        publication.reviewTicketRef(),
                        "DIFFERENT_REASON",
                        publication.reviewedBy(),
                        publication.publishedAt(),
                        publication.usableUntil());

        assertThat(integrity.revisionVerified(tamperedRevision)).isFalse();
        assertThat(integrity.publicationVerified(tamperedPublication)).isFalse();
    }

    @Test
    void sealsAndVerifiesClusterValidationCommandAndPublication() {
        ClusterFixture fixture = clusterFixture();

        assertThat(integrity.clusterValidationVerified(fixture.validation()))
                .isTrue();
        assertThat(integrity.clusterCommandFingerprint(fixture.request()))
                .isEqualTo(fixture.publication().sourceCommandFingerprint());
        assertThat(integrity.clusterVerified(fixture.publication())).isTrue();
        assertThat(fixture.validation().artifactRef())
                .isEqualTo(fixture.request().validationRef())
                .isEqualTo(fixture.publication().validationRef());
    }

    @Test
    void rejectsForgedConfidenceAndOverlappingIdentityProjection() {
        ClusterFixture fixture = clusterFixture();
        CapabilityCorpusClusterValidation validation = fixture.validation();

        assertThatThrownBy(() -> new CapabilityCorpusClusterValidation(
                validation.schemaVersion(),
                validation.validationFingerprint(),
                validation.scope(),
                validation.validationId(),
                validation.revision(),
                validation.capabilityRef(),
                validation.corpusPublicationRef(),
                validation.corpusRevisionRef(),
                validation.representativeSource(),
                validation.members(),
                validation.matchRequestPointers(),
                validation.identityMode(),
                validation.identityProjections(),
                validation.distinctIdentityCount(),
                validation.holdout(),
                new ArtifactProvenance.Confidence(
                        1.0d,
                        1.0d,
                        1.0d,
                        CapabilityCorpusClusterValidation.CONFIDENCE_METHOD),
                validation.identityCoverageComplete(),
                validation.validatedBy(),
                validation.validatedAt(),
                validation.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wilson");

        assertThatThrownBy(() ->
                new CapabilityCorpusClusterValidation.IdentityProjection(
                        "/customer",
                        List.of("/customer", "/customer/id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    private ClusterFixture clusterFixture() {
        CapabilitySnapshot.Scope scope =
                CapabilityObservationTestFixtures.scope("org-a");
        CapabilitySnapshot capability =
                CapabilityObservationTestFixtures.capability(mapper, scope);
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        Instant occurredAt = Instant.now().minusSeconds(5);
        List<CapabilityObservationRepository.StoredObservation> sources =
                List.of(
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "cluster-001", occurredAt, true),
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "cluster-002", occurredAt.plusSeconds(1), true),
                        CapabilityCorpusTestFixtures.clusterObservation(
                                mapper, signer, capability,
                                "cluster-003", occurredAt.plusSeconds(2), true));
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper,
                        sources,
                        "cluster-corpus",
                        occurredAt.plus(Duration.ofHours(1)));
        CapabilityCorpusPublication corpusPublication =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        1,
                        null,
                        revision.createdAt().plusSeconds(1));
        CapabilityCorpusClusterValidation validation =
                CapabilityCorpusTestFixtures.clusterValidation(
                        mapper,
                        corpusPublication,
                        revision,
                        sources,
                        corpusPublication.publishedAt().plusSeconds(1));
        CapabilityCorpusClusterPublishRequest request =
                CapabilityCorpusTestFixtures.clusterRequest(
                        corpusPublication, validation);
        CapabilityCorpusClusterPublication publication =
                CapabilityCorpusTestFixtures.clusterPublication(
                        mapper,
                        corpusPublication,
                        revision,
                        validation,
                        request,
                        null,
                        validation.validatedAt().plusSeconds(1));
        return new ClusterFixture(validation, request, publication);
    }

    private record ClusterFixture(
            CapabilityCorpusClusterValidation validation,
            CapabilityCorpusClusterPublishRequest request,
            CapabilityCorpusClusterPublication publication
    ) {
    }
}
