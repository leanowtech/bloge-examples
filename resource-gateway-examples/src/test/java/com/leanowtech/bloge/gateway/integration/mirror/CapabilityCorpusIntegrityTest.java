package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
}
