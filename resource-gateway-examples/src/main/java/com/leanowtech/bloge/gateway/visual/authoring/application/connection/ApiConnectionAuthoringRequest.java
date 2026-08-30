package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Trusted, transport-neutral input to the Connection authoring facade. */
public record ApiConnectionAuthoringRequest(
        AuthoringScope scope,
        String actorId,
        String connectionId,
        String idempotencyKey,
        ApiConnectionAuthoringPrecondition precondition,
        ApiConnectionCommand command) {

    /** Keeps the write-only command out of diagnostics while retaining normal record access. */
    @Override public String toString() {
        return "ApiConnectionAuthoringRequest[scope=" + scope + ", actorId=" + actorId
                + ", connectionId=" + connectionId + ", idempotencyKey=" + idempotencyKey
                + ", precondition=" + precondition + ", command="
                + (command == null ? "null" : command.getClass().getSimpleName()) + "]";
    }
}
