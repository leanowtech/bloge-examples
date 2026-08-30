package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * V003 durable binding used to resolve an activated secret.
 * @param providerId provider implementation identity
 * @param activeLocator provider-owned locator used for resolution
 * @param commandId exact authoring command that committed this binding
 */
public record ActiveSecretBinding(String providerId, String activeLocator, String commandId) {
    public ActiveSecretBinding {
        SecretValidation.identifier(providerId, "providerId");
        SecretValidation.text(activeLocator, "activeLocator", 2048);
        SecretValidation.identifier(commandId, "commandId");
    }
    @JsonIgnore @Override public String activeLocator() { return activeLocator; }
    @Override public String toString() { return "ActiveSecretBinding[providerId=" + providerId + ", redacted=true]"; }
}
