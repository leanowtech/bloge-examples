package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusTrajectoryGovernanceService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusTrajectoryPublication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Protected non-production transport for recorded retry trajectory publication.
 *
 * <p>The route authenticates before strict decoding and exists only in isolated test/staging
 * composition. Its response is an immutable payload-free governance artifact.</p>
 */
@RestController
@RequestMapping("/api/mirror")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class CapabilityCorpusTrajectoryController {
    private final CapabilityCorpusTrajectoryGovernanceService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final CapabilityCorpusTrajectoryDecoder decoder;

    /**
     * Creates the authenticated trajectory-governance transport.
     *
     * @param service protected trajectory publication boundary
     * @param authenticator integration workload authenticator
     * @param decoder strict bounded command decoder
     */
    public CapabilityCorpusTrajectoryController(
            CapabilityCorpusTrajectoryGovernanceService service,
            IntegrationRequestAuthenticator authenticator,
            CapabilityCorpusTrajectoryDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Publishes one exact owner-reviewed recorded retry trajectory.
     *
     * @param request untrusted buffered command JSON
     * @param headers authenticated integration request headers
     * @return immutable trajectory publication
     */
    @PostMapping("/corpus-trajectories")
    public IntegrationEnvelope<CapabilityCorpusTrajectoryPublication> publish(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_CORPUS_TRAJECTORY_PUBLISH);
        CapabilityCorpusTrajectoryPublication publication = service.publish(
                decoder.decode(request, identity), identity);
        return IntegrationEnvelope.of(
                CapabilityCorpusTrajectoryPublication.ARTIFACT_KIND,
                CapabilityCorpusTrajectoryPublication.SCHEMA_VERSION,
                publication);
    }
}
