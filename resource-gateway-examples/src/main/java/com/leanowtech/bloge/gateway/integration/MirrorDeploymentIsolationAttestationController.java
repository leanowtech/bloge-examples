package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationService;
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

import java.util.Map;
import java.util.Objects;

/**
 * Protected non-production transport for attestation ingest, current distribution, and revocation.
 *
 * <p>Authentication always completes before JSON decoding. Every read returns one atomic bundle,
 * exact reads expose only the durable current floor, and revocation is a separately fenced
 * denial-only command. The controller is physically absent from production composition.</p>
 */
@RestController
@RequestMapping("/api/mirror/trust/deployment-isolation/attestations")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorDeploymentIsolationAttestationController {
    private final MirrorDeploymentIsolationAttestationService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final MirrorDeploymentIsolationAttestationDecoder decoder;

    /**
     * Creates the authenticated attestation trust-control transport.
     *
     * @param service attestation trust-control application boundary
     * @param authenticator integration workload authenticator
     * @param decoder strict bounded trust-input decoder
     */
    public MirrorDeploymentIsolationAttestationController(
            MirrorDeploymentIsolationAttestationService service,
            IntegrationRequestAuthenticator authenticator,
            MirrorDeploymentIsolationAttestationDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Verifies and atomically ingests one externally signed attestation revision.
     *
     * @param request untrusted buffered attestation JSON
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact current authority publication stream
     * @param headers authenticated integration request headers
     * @return versioned envelope containing the atomic current bundle
     */
    @PostMapping
    public IntegrationEnvelope<MirrorDeploymentIsolationAttestationBundle> ingest(
            @RequestBody byte[] request,
            @RequestParam String deploymentScopeId,
            @RequestParam String keySetId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_ADMIN);
        return envelope(service.ingest(deploymentScopeId, keySetId,
                decoder.decodeAttestation(request, identity), identity));
    }

    /**
     * Reads the current active or revoked bundle in one exact governed stream.
     *
     * @param attestationId exact external attestation stream
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact authority publication stream
     * @param headers authenticated integration request headers
     * @return versioned envelope containing the atomic current bundle
     */
    @GetMapping(value = "/{attestationId}/latest",
            headers = MirrorDeploymentIsolationTrustDistributionProtocol.REQUEST_HEADER + "="
                    + MirrorDeploymentIsolationTrustDistributionProtocol.VERSION,
            produces = MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE)
    public IntegrationEnvelope<MirrorDeploymentIsolationAttestationBundle> current(
            @PathVariable String attestationId,
            @RequestParam String deploymentScopeId,
            @RequestParam String keySetId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_READ);
        return envelope(service.current(
                deploymentScopeId, keySetId, attestationId, identity));
    }

    /**
     * Reads exact attestation and status coordinates only while they remain current.
     *
     * @param attestationId exact external attestation stream
     * @param revision expected current external revision
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact authority publication stream
     * @param attestationFingerprint expected external content address
     * @param statusRevision expected current local status revision
     * @param statusFingerprint expected current local status content address
     * @param headers authenticated integration request headers
     * @return versioned envelope containing the atomic current bundle
     */
    @GetMapping(value = "/{attestationId}/revisions/{revision}",
            headers = MirrorDeploymentIsolationTrustDistributionProtocol.REQUEST_HEADER + "="
                    + MirrorDeploymentIsolationTrustDistributionProtocol.VERSION,
            produces = MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE)
    public IntegrationEnvelope<MirrorDeploymentIsolationAttestationBundle> currentExact(
            @PathVariable String attestationId,
            @PathVariable long revision,
            @RequestParam String deploymentScopeId,
            @RequestParam String keySetId,
            @RequestParam String attestationFingerprint,
            @RequestParam long statusRevision,
            @RequestParam String statusFingerprint,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_READ);
        return envelope(service.current(deploymentScopeId, keySetId, attestationId,
                expectation(revision, attestationFingerprint, statusRevision,
                        statusFingerprint, identity), identity));
    }

    /**
     * Irreversibly revokes the exact current attestation and active status.
     *
     * @param attestationId exact external attestation stream
     * @param request untrusted buffered revocation-command JSON
     * @param deploymentScopeId exact governed deployment scope
     * @param keySetId exact authority publication stream
     * @param headers authenticated integration request headers
     * @return versioned envelope containing the current revoked bundle
     */
    @PostMapping("/{attestationId}/revocations")
    public IntegrationEnvelope<MirrorDeploymentIsolationAttestationBundle> revoke(
            @PathVariable String attestationId,
            @RequestBody byte[] request,
            @RequestParam String deploymentScopeId,
            @RequestParam String keySetId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_ADMIN);
        return envelope(service.revoke(deploymentScopeId, keySetId, attestationId,
                decoder.decodeRevocation(request, identity), identity));
    }

    private static MirrorDeploymentIsolationAttestationRepository.CurrentExpectation expectation(
            long revision,
            String attestationFingerprint,
            long statusRevision,
            String statusFingerprint,
            IntegrationRequestContext identity) {
        try {
            return new MirrorDeploymentIsolationAttestationRepository.CurrentExpectation(
                    revision, attestationFingerprint, statusRevision, statusFingerprint);
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.MIRROR.ISOLATION_ATTESTATION_REF_INVALID",
                    "A canonical current attestation and status reference is required.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static IntegrationEnvelope<MirrorDeploymentIsolationAttestationBundle> envelope(
            MirrorDeploymentIsolationAttestationBundle bundle) {
        return IntegrationEnvelope.of(
                MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                MirrorDeploymentIsolationAttestationBundle.SCHEMA_VERSION, bundle);
    }
}
