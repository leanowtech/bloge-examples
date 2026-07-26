package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBinding;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingProtocol;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingRegistrationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowSourceBindingService;
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
 * Protected non-production transport for detached Shadow source-binding registration.
 *
 * <p>Every endpoint authenticates before decoding or lookup, binds the complete enterprise scope
 * to that identity, and requires explicit protocol negotiation. Publication returns only after
 * the candidate evidence was independently resolved and the resulting binding was signed and
 * durably appended.</p>
 */
@RestController
@RequestMapping("/api/mirror/shadow/source-bindings")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ReadOnlyShadowSourceBindingController {
    private final ReadOnlyShadowSourceBindingService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final ReadOnlyShadowSourceBindingDecoder decoder;

    /**
     * Creates the authenticated source-binding transport.
     *
     * @param service detached binding admission and lookup service
     * @param authenticator integration request authenticator
     * @param decoder strict auth-bound registration decoder
     */
    public ReadOnlyShadowSourceBindingController(
            ReadOnlyShadowSourceBindingService service,
            IntegrationRequestAuthenticator authenticator,
            ReadOnlyShadowSourceBindingDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Resolves candidate evidence, signs, and appends one immutable source binding.
     *
     * @param request untrusted bounded registration JSON
     * @param headers authenticated integration and protocol headers
     * @return envelope containing the canonical signed binding
     */
    @PostMapping(
            headers = ReadOnlyShadowSourceBindingProtocol.REQUEST_HEADER
                    + "=" + ReadOnlyShadowSourceBindingProtocol.VERSION,
            produces = ReadOnlyShadowSourceBindingProtocol.MEDIA_TYPE)
    public IntegrationEnvelope<ReadOnlyShadowSourceBinding> publish(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_SHADOW_SOURCE_BINDING_PUBLISH);
        ReadOnlyShadowSourceBindingRegistrationRequest decoded =
                decoder.decode(request, identity);
        try {
            return envelope(
                    service.publish(
                            decoded.toUnsignedBinding()));
        } catch (ReadOnlyShadowSourceBindingService.Failure failure) {
            throw problem(failure, identity);
        }
    }

    /**
     * Reads one exact currently valid source-binding revision.
     *
     * @param bindingId stable binding identity
     * @param revision exact positive revision
     * @param fingerprint exact content fingerprint
     * @param headers authenticated integration and protocol headers
     * @return envelope containing the verified binding
     */
    @GetMapping(
            value = "/{bindingId}/revisions/{revision}",
            headers = ReadOnlyShadowSourceBindingProtocol.REQUEST_HEADER
                    + "=" + ReadOnlyShadowSourceBindingProtocol.VERSION,
            produces = ReadOnlyShadowSourceBindingProtocol.MEDIA_TYPE)
    public IntegrationEnvelope<ReadOnlyShadowSourceBinding> find(
            @PathVariable String bindingId,
            @PathVariable long revision,
            @RequestParam String fingerprint,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_SHADOW_SOURCE_BINDING_READ);
        CapabilitySnapshot.Scope scope =
                ReadOnlyShadowAuthorityKeySetDecoder.scope(identity);
        try {
            return envelope(
                    service.resolve(
                            scope,
                            new MirrorArtifactRef(
                                    ReadOnlyShadowSourceBinding.ARTIFACT_KIND,
                                    bindingId,
                                    revision,
                                    fingerprint)));
        } catch (IllegalArgumentException malformed) {
            throw new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.SHADOW_SOURCE_BINDING_REFERENCE_INVALID",
                            "The source-binding reference is invalid.",
                            identity.correlationId(),
                            Map.of()));
        } catch (ReadOnlyShadowSourceBindingService.Failure failure) {
            throw problem(failure, identity);
        }
    }

    private static IntegrationEnvelope<ReadOnlyShadowSourceBinding>
    envelope(ReadOnlyShadowSourceBinding value) {
        return IntegrationEnvelope.of(
                ReadOnlyShadowSourceBinding.ARTIFACT_KIND,
                ReadOnlyShadowSourceBinding.SCHEMA_VERSION,
                value);
    }

    private static IntegrationProblemException problem(
            ReadOnlyShadowSourceBindingService.Failure failure,
            IntegrationRequestContext identity) {
        IntegrationProblem value = switch (failure.reason()) {
            case AUTHORITY_UNAVAILABLE ->
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.SHADOW_SOURCE_BINDING_AUTHORITY_UNAVAILABLE",
                            "The source-binding authority is unavailable.",
                            identity.correlationId(),
                            Map.of());
            case BINDING_NOT_FOUND, CANDIDATE_NOT_FOUND ->
                    IntegrationProblem.notFound(
                            "RG.MIRROR.SHADOW_SOURCE_BINDING_NOT_FOUND",
                            "The exact source binding or candidate evidence was not found.",
                            identity.correlationId(),
                            Map.of());
            case BINDING_INVALID, CANDIDATE_MISMATCH,
                    REFERENCE_MISMATCH, REVISION_CONFLICT,
                    WINDOW_REJECTED ->
                    IntegrationProblem.conflict(
                            "RG.MIRROR.SHADOW_SOURCE_BINDING_CONFLICT",
                            "The source binding conflicts with trusted source state.",
                            identity.correlationId(),
                            Map.of());
        };
        return new IntegrationProblemException(value);
    }
}
