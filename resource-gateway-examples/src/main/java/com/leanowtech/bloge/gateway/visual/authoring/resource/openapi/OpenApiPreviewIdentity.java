package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Trusted identity supplied by the authenticated HTTP boundary for remote OpenAPI reads.
 * It is deliberately separate from the client command, so callers cannot self-assert scope or actor.
 *
 * @param scope exact tenant, project, and environment boundary
 * @param actorId authenticated actor identifier
 * @param purpose authenticated authoring purpose
 */
public record OpenApiPreviewIdentity(AuthoringScope scope, String actorId, String purpose) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Validates the non-client-controlled identity before any egress call. */
    public OpenApiPreviewIdentity {
        Objects.requireNonNull(scope, "scope");
        if (actorId == null || !IDENTIFIER.matcher(actorId).matches()) {
            throw new IllegalArgumentException("actorId is invalid");
        }
        if (purpose == null || !IDENTIFIER.matcher(purpose).matches()) {
            throw new IllegalArgumentException("purpose is invalid");
        }
    }
}
