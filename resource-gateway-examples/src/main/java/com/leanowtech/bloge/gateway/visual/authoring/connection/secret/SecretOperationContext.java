package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

/** Immutable, non-secret context passed to provider calls outside the database transaction. */
public record SecretOperationContext(String scope, String actorId, String purpose, String connectionId,
                                     long revision, String commandId, int attemptNo, String attemptToken, String slot) {
    public SecretOperationContext {
        SecretValidation.scope(scope);
        SecretValidation.text(actorId, "actorId", 256);
        SecretValidation.text(purpose, "purpose", 128);
        SecretValidation.identifier(connectionId, "connectionId");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        SecretValidation.identifier(commandId, "commandId");
        if (attemptNo < 1) throw new IllegalArgumentException("attemptNo must be positive");
        SecretValidation.text(attemptToken, "attemptToken", 512);
        SecretValidation.identifier(slot, "slot");
    }
    @Override public String toString() { return "SecretOperationContext[scope=" + scope + ", connectionId=" + connectionId + ", revision=" + revision + ", slot=" + slot + "]"; }
}
