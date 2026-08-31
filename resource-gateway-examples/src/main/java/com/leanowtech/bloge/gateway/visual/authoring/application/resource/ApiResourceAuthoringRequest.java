package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Trusted, transport-neutral input to the Resource authoring facade. */
public record ApiResourceAuthoringRequest(AuthoringScope scope, String actorId, String resourceId,
                                          String idempotencyKey,
                                          ApiResourceAuthoringPrecondition precondition,
                                          ApiResourceSaveCommand command) {
    /** Avoids expanding examples or future nested credentials in diagnostics. */
    @Override public String toString() {
        return "ApiResourceAuthoringRequest[scope=" + scope + ", actorId=" + actorId
                + ", resourceId=" + resourceId + ", idempotencyKey=" + idempotencyKey
                + ", precondition=" + precondition + ", command="
                + (command == null ? "null" : ApiResourceSaveCommand.class.getSimpleName()) + "]";
    }
}
