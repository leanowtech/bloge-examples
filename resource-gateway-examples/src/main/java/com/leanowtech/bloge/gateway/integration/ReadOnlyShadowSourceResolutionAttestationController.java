package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestation;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestationProtocol;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceResolutionAttestationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Protected non-production exact-read transport for source-resolution attestations.
 */
@RestController
@RequestMapping("/api/mirror/shadow/source-resolutions")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ReadOnlyShadowSourceResolutionAttestationController {
    private final ReadOnlyShadowSourceResolutionAttestationService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the authenticated exact source-resolution transport.
     *
     * @param service exact attestation read boundary
     * @param authenticator integration request authenticator
     */
    public ReadOnlyShadowSourceResolutionAttestationController(
            ReadOnlyShadowSourceResolutionAttestationService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
    }

    /**
     * Reads one exact independently verified source-resolution revision.
     *
     * @param attestationId deterministic attestation identity
     * @param revision exact positive revision
     * @param fingerprint exact content fingerprint
     * @param headers authenticated integration and protocol headers
     * @return envelope containing the verified signed attestation
     */
    @GetMapping(
            value = "/{attestationId}/revisions/{revision}",
            headers =
                    ReadOnlyShadowSourceResolutionAttestationProtocol
                            .REQUEST_HEADER
                            + "="
                            + ReadOnlyShadowSourceResolutionAttestationProtocol
                            .VERSION,
            produces =
                    ReadOnlyShadowSourceResolutionAttestationProtocol
                            .MEDIA_TYPE)
    public IntegrationEnvelope<ReadOnlyShadowSourceResolutionAttestation>
    find(
            @PathVariable String attestationId,
            @PathVariable long revision,
            @RequestParam String fingerprint,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_SHADOW_SOURCE_RESOLUTION_READ);
        CapabilitySnapshot.Scope scope =
                ReadOnlyShadowAuthorityKeySetDecoder
                        .scope(identity);
        try {
            ReadOnlyShadowSourceResolutionAttestation value =
                    service.resolve(
                            scope,
                            new MirrorArtifactRef(
                                    ReadOnlyShadowSourceResolutionAttestation
                                            .ARTIFACT_KIND,
                                    attestationId,
                                    revision,
                                    fingerprint));
            return IntegrationEnvelope.of(
                    ReadOnlyShadowSourceResolutionAttestation
                            .ARTIFACT_KIND,
                    ReadOnlyShadowSourceResolutionAttestation
                            .SCHEMA_VERSION,
                    value);
        } catch (IllegalArgumentException malformed) {
            throw new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.SHADOW_SOURCE_RESOLUTION_REFERENCE_INVALID",
                            "The source-resolution attestation reference is invalid.",
                            identity.correlationId(),
                            Map.of()));
        } catch (ReadOnlyShadowSourceResolutionAttestationService
                         .Failure failure) {
            IntegrationProblem problem =
                    failure.reason()
                    == ReadOnlyShadowSourceResolutionAttestationService
                    .Reason.NOT_FOUND
                            ? IntegrationProblem.notFound(
                            "RG.MIRROR.SHADOW_SOURCE_RESOLUTION_NOT_FOUND",
                            "The exact source-resolution attestation was not found.",
                            identity.correlationId(),
                            Map.of())
                            : IntegrationProblem.conflict(
                            "RG.MIRROR.SHADOW_SOURCE_RESOLUTION_CONFLICT",
                            "The attestation reference conflicts with trusted state.",
                            identity.correlationId(),
                            Map.of());
            throw new IntegrationProblemException(problem);
        }
    }
}
