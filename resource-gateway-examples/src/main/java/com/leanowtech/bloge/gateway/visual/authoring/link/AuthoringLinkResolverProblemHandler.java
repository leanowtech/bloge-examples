package com.leanowtech.bloge.gateway.visual.authoring.link;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Stable bad-request projection for malformed or non-allowlisted Link Resolver JSON. */
@RestControllerAdvice(assignableTypes = AuthoringLinkResolverController.class)
public final class AuthoringLinkResolverProblemHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<IntegrationProblem> invalidRequest(Exception failure) {
        IntegrationProblem problem = IntegrationProblem.badRequest(
                "RG.AUTHORING_LINK.REQUEST_INVALID",
                "The Authoring link request is invalid or contains a forbidden coordinate.",
                "", java.util.Map.of());
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(problem);
    }
}
