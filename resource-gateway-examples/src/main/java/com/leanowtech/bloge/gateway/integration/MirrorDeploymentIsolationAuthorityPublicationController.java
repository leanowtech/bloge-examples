package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityKeySetPublication;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationTrustDistributionProtocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Protected non-production trusted-distribution transport for isolation-authority key sets.
 *
 * <p>Every route authenticates a dedicated operation before parsing or looking up a publication.
 * The transport is physically absent from production and disabled compositions. Exact reads are
 * content addressed but are served only while that address remains the durable current floor.</p>
 */
@RestController
@RequestMapping("/api/mirror/trust/deployment-isolation/authority-key-sets")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorDeploymentIsolationAuthorityPublicationController {
    private final MirrorDeploymentIsolationAuthorityPublicationService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final MirrorDeploymentIsolationAuthorityPublicationDecoder decoder;

    /**
     * Creates the authenticated trusted-distribution transport.
     *
     * @param service trusted-distribution application boundary
     * @param authenticator integration workload authenticator
     * @param decoder strict bounded publication decoder
     */
    public MirrorDeploymentIsolationAuthorityPublicationController(
            MirrorDeploymentIsolationAuthorityPublicationService service,
            IntegrationRequestAuthenticator authenticator,
            MirrorDeploymentIsolationAuthorityPublicationDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Verifies and atomically appends one current authority key-set generation.
     *
     * @param request untrusted buffered publication JSON
     * @param headers authenticated integration request headers
     * @return versioned envelope containing the committed publication
     */
    @PostMapping
    public IntegrationEnvelope<MirrorDeploymentIsolationAuthorityKeySetPublication> publish(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_PUBLISH);
        return envelope(service.publish(decoder.decode(request, identity), identity));
    }

    /**
     * Reads and re-verifies the latest durable floor in one exact governed stream.
     *
     * @param keySetId exact governed key-set stream
     * @param deploymentScopeId exact governed deployment scope
     * @param headers authenticated integration request headers
     * @return versioned envelope containing the current verified publication
     */
    @GetMapping(value = "/{keySetId}/latest",
            headers = MirrorDeploymentIsolationTrustDistributionProtocol.REQUEST_HEADER + "="
                    + MirrorDeploymentIsolationTrustDistributionProtocol.VERSION,
            produces = MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE)
    public IntegrationEnvelope<MirrorDeploymentIsolationAuthorityKeySetPublication> latest(
            @PathVariable String keySetId,
            @RequestParam String deploymentScopeId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ);
        return envelope(service.latest(deploymentScopeId, keySetId, identity));
    }

    /**
     * Reads an exact content address only when it still equals the durable floor.
     *
     * @param keySetId exact governed key-set stream
     * @param generation exact expected current generation
     * @param deploymentScopeId exact governed deployment scope
     * @param publicationFingerprint exact expected current content fingerprint
     * @param headers authenticated integration request headers
     * @return versioned envelope containing the current verified publication
     */
    @GetMapping(value = "/{keySetId}/generations/{generation}",
            headers = MirrorDeploymentIsolationTrustDistributionProtocol.REQUEST_HEADER + "="
                    + MirrorDeploymentIsolationTrustDistributionProtocol.VERSION,
            produces = MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE)
    public IntegrationEnvelope<MirrorDeploymentIsolationAuthorityKeySetPublication> current(
            @PathVariable String keySetId,
            @PathVariable long generation,
            @RequestParam String deploymentScopeId,
            @RequestParam String publicationFingerprint,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ);
        return envelope(service.current(deploymentScopeId, keySetId, generation,
                publicationFingerprint, identity));
    }

    private static IntegrationEnvelope<MirrorDeploymentIsolationAuthorityKeySetPublication>
    envelope(MirrorDeploymentIsolationAuthorityKeySetPublication publication) {
        return IntegrationEnvelope.of(
                MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                MirrorDeploymentIsolationAuthorityKeySetPublication.SCHEMA_VERSION,
                publication);
    }
}
