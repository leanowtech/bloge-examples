package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusGovernanceService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusPublication;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusRevision;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationReview;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationReviewRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Protected non-production transport for quarantine review and corpus publication.
 *
 * <p>Every route authenticates before strict JSON decoding. The controller, decoder, service, and
 * repositories are physically absent from production composition. Responses contain only
 * immutable payload-free governance artifacts after their mandatory success audit commits.</p>
 */
@RestController
@RequestMapping("/api/mirror")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class CapabilityCorpusGovernanceController {
    private final CapabilityCorpusGovernanceService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final CapabilityCorpusGovernanceDecoder decoder;

    /**
     * Creates the authenticated corpus-governance transport.
     *
     * @param service protected governance application boundary
     * @param authenticator integration workload authenticator
     * @param decoder strict bounded command decoder
     */
    public CapabilityCorpusGovernanceController(
            CapabilityCorpusGovernanceService service,
            IntegrationRequestAuthenticator authenticator,
            CapabilityCorpusGovernanceDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Records the terminal owner review of one quarantined observation.
     *
     * @param observationId path-level observation identity
     * @param request untrusted buffered command JSON
     * @param headers authenticated integration request headers
     * @return immutable terminal review
     */
    @PostMapping("/observations/{observationId}/reviews")
    public IntegrationEnvelope<CapabilityObservationReview> review(
            @PathVariable String observationId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_OBSERVATION_REVIEW);
        CapabilityObservationReviewRequest decoded =
                decoder.decodeReview(request, identity);
        if (!decoded.observationRef().id().equals(observationId)) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.MIRROR.OBSERVATION_REVIEW_ID_MISMATCH",
                    "The path and review observation identities must match.",
                    identity.correlationId(),
                    Map.of()));
        }
        CapabilityObservationReview review =
                service.reviewQuarantine(decoded, identity);
        return IntegrationEnvelope.of(
                CapabilityObservationReview.ARTIFACT_KIND,
                CapabilityObservationReview.SCHEMA_VERSION,
                review);
    }

    /**
     * Freezes admitted observations into one immutable corpus revision candidate.
     *
     * @param request untrusted buffered command JSON
     * @param headers authenticated integration request headers
     * @return immutable non-serving corpus revision
     */
    @PostMapping("/corpus-candidates")
    public IntegrationEnvelope<CapabilityCorpusRevision> createCandidate(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_CORPUS_CANDIDATE_CREATE);
        CapabilityCorpusRevision revision = service.createCandidate(
                decoder.decodeCandidate(request, identity), identity);
        return IntegrationEnvelope.of(
                CapabilityCorpusRevision.ARTIFACT_KIND,
                CapabilityCorpusRevision.SCHEMA_VERSION,
                revision);
    }

    /**
     * Publishes one exact eligible candidate as the serving corpus head.
     *
     * @param request untrusted buffered command JSON
     * @param headers authenticated integration request headers
     * @return immutable serving publication
     */
    @PostMapping("/corpus-publications")
    public IntegrationEnvelope<CapabilityCorpusPublication> publish(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_CORPUS_PUBLISH);
        CapabilityCorpusPublication publication = service.publish(
                decoder.decodePublication(request, identity), identity);
        return IntegrationEnvelope.of(
                CapabilityCorpusPublication.ARTIFACT_KIND,
                CapabilityCorpusPublication.SCHEMA_VERSION,
                publication);
    }
}
