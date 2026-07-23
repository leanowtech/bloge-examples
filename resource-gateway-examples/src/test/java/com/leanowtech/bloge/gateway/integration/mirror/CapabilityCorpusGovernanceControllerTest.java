package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusGovernanceController;
import com.leanowtech.bloge.gateway.integration.CapabilityCorpusGovernanceDecoder;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityCorpusGovernanceControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void authenticatesEachCommandBeforeDecodeAndReturnsTypedArtifacts()
            throws Exception {
        CapabilityCorpusGovernanceService service =
                mock(CapabilityCorpusGovernanceService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        CapabilityCorpusGovernanceDecoder decoder =
                mock(CapabilityCorpusGovernanceDecoder.class);
        IntegrationRequestContext identity =
                CapabilityCorpusTestFixtures.identity(
                        "org-a",
                        Set.of("corpus-reviewers", "corpus-publishers"));
        HttpHeaders headers = new HttpHeaders();
        CapabilityObservationRepository.StoredObservation quarantined =
                CapabilityCorpusTestFixtures.quarantined(
                        mapper,
                        CapabilityObservationTestFixtures.scope("org-a"),
                        "observation-controller");
        CapabilityObservationReviewRequest reviewRequest =
                CapabilityCorpusTestFixtures.reviewRequest(quarantined);
        CapabilityCorpusIntegrity integrity = new CapabilityCorpusIntegrity(mapper);
        CapabilityObservationReview review = integrity.sealReview(
                new CapabilityObservationReview(
                        "",
                        CapabilityObservationTestFixtures.fingerprint('0'),
                        integrity.reviewCommandFingerprint(reviewRequest),
                        quarantined.envelope().material().scope(),
                        quarantined.envelope().artifactRef(),
                        quarantined.admission().artifactRef(),
                        reviewRequest.disposition(),
                        reviewRequest.reviewTicketRef(),
                        reviewRequest.reasonCode(),
                        identity.actorId(),
                        Instant.parse("2026-07-23T00:00:00Z")));
        CapabilityObservationRepository.StoredObservation admitted =
                CapabilityCorpusTestFixtures.admitted(
                        mapper,
                        quarantined.envelope().material().scope(),
                        "observation-candidate");
        CapabilityCorpusCandidateRequest candidateRequest =
                CapabilityCorpusTestFixtures.candidateRequest(
                        "support-corpus", 1, null, List.of(admitted));
        CapabilityCorpusRevision revision =
                CapabilityCorpusTestFixtures.revision(
                        mapper,
                        admitted,
                        "support-corpus",
                        1,
                        null,
                        admitted.admission().decidedAt().plusSeconds(1));
        CapabilityCorpusPublishRequest publishRequest =
                new CapabilityCorpusPublishRequest(
                        "",
                        revision.corpusId(),
                        1,
                        null,
                        revision.artifactRef(),
                        CapabilityObservationTestFixtures.ref(
                                "GOVERNANCE_REVIEW_TICKET",
                                "ticket-publish",
                                1,
                                '7'),
                        "OWNER_APPROVED");
        CapabilityCorpusPublication publication =
                CapabilityCorpusTestFixtures.publication(
                        mapper,
                        revision,
                        1,
                        null,
                        revision.createdAt().plusSeconds(1));
        byte[] reviewBody = mapper.writeValueAsBytes(reviewRequest);
        byte[] candidateBody = mapper.writeValueAsBytes(candidateRequest);
        byte[] publishBody = mapper.writeValueAsBytes(publishRequest);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_OBSERVATION_REVIEW))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_CORPUS_CANDIDATE_CREATE))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_CORPUS_PUBLISH))
                .thenReturn(identity);
        when(decoder.decodeReview(reviewBody, identity)).thenReturn(reviewRequest);
        when(decoder.decodeCandidate(candidateBody, identity))
                .thenReturn(candidateRequest);
        when(decoder.decodePublication(publishBody, identity))
                .thenReturn(publishRequest);
        when(service.reviewQuarantine(reviewRequest, identity)).thenReturn(review);
        when(service.createCandidate(candidateRequest, identity))
                .thenReturn(revision);
        when(service.publish(publishRequest, identity)).thenReturn(publication);
        CapabilityCorpusGovernanceController controller =
                new CapabilityCorpusGovernanceController(
                        service, authenticator, decoder);

        var reviewResponse = controller.review(
                reviewRequest.observationRef().id(), reviewBody, headers);
        var candidateResponse =
                controller.createCandidate(candidateBody, headers);
        var publicationResponse = controller.publish(publishBody, headers);

        assertThat(reviewResponse.payload()).isEqualTo(review);
        assertThat(candidateResponse.payload()).isEqualTo(revision);
        assertThat(publicationResponse.payload()).isEqualTo(publication);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_OBSERVATION_REVIEW);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_CORPUS_CANDIDATE_CREATE);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_CORPUS_PUBLISH);
    }

    @Test
    void rejectsReviewPathIdentityMismatchAfterAuthentication() throws Exception {
        CapabilityCorpusGovernanceService service =
                mock(CapabilityCorpusGovernanceService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        CapabilityCorpusGovernanceDecoder decoder =
                mock(CapabilityCorpusGovernanceDecoder.class);
        IntegrationRequestContext identity =
                CapabilityCorpusTestFixtures.identity(
                        "org-a", Set.of("corpus-reviewers"));
        CapabilityObservationRepository.StoredObservation source =
                CapabilityCorpusTestFixtures.quarantined(
                        mapper,
                        CapabilityObservationTestFixtures.scope("org-a"),
                        "observation-controller");
        CapabilityObservationReviewRequest request =
                CapabilityCorpusTestFixtures.reviewRequest(source);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = mapper.writeValueAsBytes(request);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_OBSERVATION_REVIEW))
                .thenReturn(identity);
        when(decoder.decodeReview(body, identity)).thenReturn(request);
        CapabilityCorpusGovernanceController controller =
                new CapabilityCorpusGovernanceController(
                        service, authenticator, decoder);

        assertThatThrownBy(() -> controller.review(
                "different-observation", body, headers))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.OBSERVATION_REVIEW_ID_MISMATCH"));
    }

    @Test
    void operationsAcceptOnlyDedicatedCorpusGovernancePurpose() {
        assertThat(IntegrationOperation.MIRROR_OBSERVATION_REVIEW.acceptedPurposes())
                .containsExactly(
                        CapabilityCorpusGovernanceService.AUTHORIZED_PURPOSE);
        assertThat(IntegrationOperation.MIRROR_CORPUS_CANDIDATE_CREATE
                .acceptedPurposes()).containsExactly(
                        CapabilityCorpusGovernanceService.AUTHORIZED_PURPOSE);
        assertThat(IntegrationOperation.MIRROR_CORPUS_PUBLISH.acceptedPurposes())
                .containsExactly(
                        CapabilityCorpusGovernanceService.AUTHORIZED_PURPOSE);
        assertThat(IntegrationOperation.MIRROR_CORPUS_PUBLISH
                .accepts("MIRROR_REHEARSAL")).isFalse();
    }
}
