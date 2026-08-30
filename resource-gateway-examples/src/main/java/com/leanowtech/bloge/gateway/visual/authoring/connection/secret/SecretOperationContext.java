package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Immutable, non-secret context passed to provider calls outside the database transaction. */
public record SecretOperationContext(AuthoringScope scope, String actorId, String purpose, String connectionId,
                                     long revision, String commandId, int attemptNo, String attemptToken, String slot) {
    public SecretOperationContext {
        if (scope == null) throw new NullPointerException("scope");
        SecretValidation.identifier(actorId, "actorId");
        SecretValidation.text(purpose, "purpose", 128);
        SecretValidation.identifier(connectionId, "connectionId");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        SecretValidation.identifier(commandId, "commandId");
        if (attemptNo < 1) throw new IllegalArgumentException("attemptNo must be positive");
        SecretValidation.text(attemptToken, "attemptToken", 128);
        if (!"token".equals(slot) && !"password".equals(slot) && !"value".equals(slot)) {
            throw new IllegalArgumentException("slot must be token, password or value");
        }
    }

    /** Attempt token is a persistence fence and must not cross a JSON boundary. */
    @JsonIgnore
    @Override public String attemptToken() { return attemptToken; }

    @Override public String toString() {
        return "SecretOperationContext[scope=" + scope + ", connectionId=" + connectionId
                + ", revision=" + revision + ", slot=" + slot + "]";
    }
}
