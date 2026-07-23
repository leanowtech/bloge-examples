package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterGovernanceService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterPublication;
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
 * Protected non-production transport for recorded-cluster publication.
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
public final class CapabilityCorpusClusterController {
    private final CapabilityCorpusClusterGovernanceService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final CapabilityCorpusClusterDecoder decoder;

    /**
     * Creates the authenticated cluster-governance transport.
     *
     * @param service protected cluster publication boundary
     * @param authenticator integration workload authenticator
     * @param decoder strict bounded command decoder
     */
    public CapabilityCorpusClusterController(
            CapabilityCorpusClusterGovernanceService service,
            IntegrationRequestAuthenticator authenticator,
            CapabilityCorpusClusterDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Publishes one exact owner-reviewed recorded cluster.
     *
     * @param request untrusted buffered command JSON
     * @param headers authenticated integration request headers
     * @return immutable cluster publication
     */
    @PostMapping("/corpus-clusters")
    public IntegrationEnvelope<CapabilityCorpusClusterPublication> publish(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers,
                IntegrationOperation.MIRROR_CORPUS_CLUSTER_PUBLISH);
        CapabilityCorpusClusterPublication publication = service.publish(
                decoder.decode(request, identity), identity);
        return IntegrationEnvelope.of(
                CapabilityCorpusClusterPublication.ARTIFACT_KIND,
                CapabilityCorpusClusterPublication.SCHEMA_VERSION,
                publication);
    }
}
