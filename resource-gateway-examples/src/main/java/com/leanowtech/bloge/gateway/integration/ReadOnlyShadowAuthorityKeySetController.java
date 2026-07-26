package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetPage;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetPublication;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityKeySetService;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowAuthorityTrustDistributionProtocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Protected non-production transport for read-only Shadow authority trust distribution.
 *
 * <p>Dedicated operations are authenticated before body decoding or database lookup. Publication
 * writes are scope-bound and root-verified before append. Reads return bounded contiguous cursor
 * pages under explicit protocol negotiation, allowing consumers to recover missed rotations
 * without accepting a generation gap.</p>
 */
@RestController
@RequestMapping("/api/mirror/trust/read-only-shadow/authority-key-sets")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class ReadOnlyShadowAuthorityKeySetController {
    private final ReadOnlyShadowAuthorityKeySetService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final ReadOnlyShadowAuthorityKeySetDecoder decoder;

    /**
     * Creates the authenticated authority trust-distribution transport.
     *
     * @param service verified key-set publication and paging service
     * @param authenticator integration request authenticator
     * @param decoder strict auth-bound publication decoder
     */
    public ReadOnlyShadowAuthorityKeySetController(
            ReadOnlyShadowAuthorityKeySetService service,
            IntegrationRequestAuthenticator authenticator,
            ReadOnlyShadowAuthorityKeySetDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Verifies and atomically appends one authority key-set successor.
     *
     * @param request untrusted bounded publication JSON
     * @param headers authenticated integration headers
     * @return envelope containing the accepted canonical publication
     */
    @PostMapping
    public IntegrationEnvelope<ReadOnlyShadowAuthorityKeySetPublication> publish(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SHADOW_AUTHORITY_KEY_SET_PUBLISH);
        try {
            return IntegrationEnvelope.of(
                    ReadOnlyShadowAuthorityKeySetPublication.ARTIFACT_KIND,
                    ReadOnlyShadowAuthorityKeySetPublication.SCHEMA_VERSION,
                    service.publish(decoder.decode(request, identity)));
        } catch (ReadOnlyShadowAuthorityKeySetService.AdmissionRejected rejected) {
            throw admissionProblem(rejected, identity);
        } catch (ReadOnlyShadowAuthorityKeySetRepository.Violation rejected) {
            throw repositoryProblem(rejected, identity);
        }
    }

    /**
     * Reads one atomic contiguous cursor page from the authenticated scope.
     *
     * @param publicationKind exact authority publication kind
     * @param issuer exact authority issuer
     * @param afterGeneration last durably trusted generation, or zero for bootstrap
     * @param afterPublicationFingerprint fingerprint of the last durably trusted generation
     * @param limit maximum successors to return
     * @param headers authenticated integration and protocol-negotiation headers
     * @return envelope containing a frozen-head contiguous publication page
     */
    @GetMapping(value = "/pages",
            headers = ReadOnlyShadowAuthorityTrustDistributionProtocol.REQUEST_HEADER + "="
                    + ReadOnlyShadowAuthorityTrustDistributionProtocol.VERSION,
            produces = ReadOnlyShadowAuthorityTrustDistributionProtocol.MEDIA_TYPE)
    public IntegrationEnvelope<ReadOnlyShadowAuthorityKeySetPage> page(
            @RequestParam ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
            @RequestParam String issuer,
            @RequestParam(defaultValue = "0") long afterGeneration,
            @RequestParam(defaultValue = "") String afterPublicationFingerprint,
            @RequestParam(defaultValue = "64") int limit,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SHADOW_AUTHORITY_KEY_SET_READ);
        try {
            ReadOnlyShadowAuthorityKeySetPage page = service.page(
                    ReadOnlyShadowAuthorityKeySetDecoder.scope(identity),
                    publicationKind, issuer, afterGeneration,
                    afterPublicationFingerprint, limit);
            return IntegrationEnvelope.of(
                    ReadOnlyShadowAuthorityKeySetPage.ARTIFACT_KIND,
                    ReadOnlyShadowAuthorityKeySetPage.SCHEMA_VERSION,
                    page);
        } catch (ReadOnlyShadowAuthorityKeySetService.AdmissionRejected rejected) {
            throw admissionProblem(rejected, identity);
        } catch (ReadOnlyShadowAuthorityKeySetRepository.Violation rejected) {
            throw repositoryProblem(rejected, identity);
        }
    }

    private static IntegrationProblemException admissionProblem(
            ReadOnlyShadowAuthorityKeySetService.AdmissionRejected rejected,
            IntegrationRequestContext identity) {
        return switch (rejected.reason()) {
            case TRUST_POLICY_UNAVAILABLE -> problem(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.SHADOW_AUTHORITY_TRUST_UNAVAILABLE",
                            "The managed Shadow authority trust policy is unavailable.",
                            identity.correlationId(), Map.of()));
            case BINDING_MISMATCH, ROOT_POLICY_REJECTED -> problem(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_POLICY_REJECTED",
                            "The authority key set was rejected by local trust policy.",
                            identity.correlationId(), Map.of()));
            case SIGNATURE_INVALID -> problem(IntegrationProblem.badRequest(
                    "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_INVALID",
                    "The authority key set failed canonical signature validation.",
                    identity.correlationId(), Map.of()));
            case WINDOW_REJECTED, CHAIN_REJECTED -> problem(IntegrationProblem.conflict(
                    "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_CONFLICT",
                    "The authority key set conflicts with current trust state.",
                    identity.correlationId(), Map.of()));
        };
    }

    private static IntegrationProblemException repositoryProblem(
            ReadOnlyShadowAuthorityKeySetRepository.Violation rejected,
            IntegrationRequestContext identity) {
        return switch (rejected.reason()) {
            case CANONICAL_INVALID -> problem(IntegrationProblem.badRequest(
                    "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_INVALID",
                    "The authority key set is not canonically content addressed.",
                    identity.correlationId(), Map.of()));
            case STORED_STATE_CORRUPT -> problem(IntegrationProblem.serviceUnavailable(
                    "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_STORE_UNAVAILABLE",
                    "The authority key-set store failed integrity validation.",
                    identity.correlationId(), Map.of()));
            case IDENTITY_MISMATCH, BOOTSTRAP_GENERATION_INVALID, GENERATION_ROLLBACK,
                    GENERATION_FORK, GENERATION_GAP, PREDECESSOR_MISMATCH,
                    KEY_LIFECYCLE_INVALID, CONTENT_ADDRESS_CONFLICT,
                    CHECKPOINT_INVALID, CONCURRENT_INITIALIZATION ->
                    problem(IntegrationProblem.conflict(
                            "RG.MIRROR.SHADOW_AUTHORITY_KEY_SET_CONFLICT",
                            "The authority key set conflicts with current trust state.",
                            identity.correlationId(), Map.of()));
        };
    }

    private static IntegrationProblemException problem(IntegrationProblem value) {
        return new IntegrationProblemException(value);
    }
}
