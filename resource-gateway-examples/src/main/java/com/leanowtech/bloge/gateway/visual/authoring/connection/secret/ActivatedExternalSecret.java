package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

/** Provider activation result; locator is opaque to the gateway. */
public record ActivatedExternalSecret(String providerId, String leaseId, String activeLocator) {
    public ActivatedExternalSecret {
        SecretValidation.identifier(providerId, "providerId");
        SecretValidation.text(leaseId, "leaseId", 512);
        SecretValidation.text(activeLocator, "activeLocator", 2048);
    }
    @Override public String toString() { return "ActivatedExternalSecret[providerId=" + providerId + ", redacted=true]"; }
}
