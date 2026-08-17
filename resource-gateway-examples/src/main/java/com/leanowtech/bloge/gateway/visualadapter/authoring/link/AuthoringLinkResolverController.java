package com.leanowtech.bloge.gateway.visualadapter.authoring.link;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.authoring.link.AuthoringLinkDescriptor;
import com.leanowtech.bloge.gateway.visual.authoring.link.AuthoringLinkResolution;
import com.leanowtech.bloge.gateway.visual.authoring.link.AuthoringLinkResolveRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Authenticated exact Author route resolver for cross-workspace navigation. */
@RestController
@RequestMapping("/api/visual")
public final class AuthoringLinkResolverController {
    private final AuthoringLinkResolverService service;
    private final IntegrationRequestAuthenticator authenticator;

    public AuthoringLinkResolverController(
            AuthoringLinkResolverService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping("/authoring-links:resolve")
    public ResponseEntity<AuthoringLinkDescriptor> resolve(
            @RequestBody AuthoringLinkResolveRequest request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticate(headers);
        AuthoringLinkResolution result = service.resolve(request, identity);
        if (result.status() != AuthoringLinkResolution.Status.RESOLVED) {
            throw problem(result, identity.correlationId());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(result.descriptor());
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers) {
        if (authenticator == null) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.AUTHENTICATOR_UNAVAILABLE",
                    "Authoring link authentication is unavailable.", "", Map.of()));
        }
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.AUTHORING_LINK_RESOLVE);
        identity.requireComplete();
        if (identity.projectId().isBlank() || identity.region().isBlank()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.CONTEXT_REQUIRED",
                    "Project and region are required for Authoring links.",
                    identity.correlationId(), Map.of("projectId", "required", "region", "required")));
        }
        return identity;
    }

    private static IntegrationProblemException problem(
            AuthoringLinkResolution result, String correlationId) {
        return switch (result.status()) {
            case INVALID_REQUEST -> new IntegrationProblemException(IntegrationProblem.badRequest(
                    result.errorCode(), "The Authoring link request is invalid.",
                    correlationId, Map.of()));
            case DRIFTED -> new IntegrationProblemException(IntegrationProblem.conflict(
                    result.errorCode(), "The requested Author source coordinate drifted.",
                    correlationId, Map.of()));
            case FORBIDDEN -> new IntegrationProblemException(IntegrationProblem.forbidden(
                    result.errorCode(), "The requested Author source is not accessible.",
                    correlationId, Map.of()));
            case NOT_FOUND -> new IntegrationProblemException(IntegrationProblem.notFound(
                    result.errorCode(), "The requested Author source was not found.",
                    correlationId, Map.of()));
            case RESOLVED -> throw new IllegalStateException("resolved result has no problem");
        };
    }
}
