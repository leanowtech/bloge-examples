package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

/** Durable binding used to resolve an activated secret. */
public record ActiveSecretBinding(String providerId, String leaseId, String activeLocator) {
    public ActiveSecretBinding {
        SecretValidation.identifier(providerId, "providerId");
        SecretValidation.text(leaseId, "leaseId", 512);
        SecretValidation.text(activeLocator, "activeLocator", 2048);
    }
    @Override public String toString() { return "ActiveSecretBinding[providerId=" + providerId + ", redacted=true]"; }
}
