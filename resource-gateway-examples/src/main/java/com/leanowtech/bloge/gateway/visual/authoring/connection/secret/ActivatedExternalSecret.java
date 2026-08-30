package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Provider activation result; lease and active locator remain provider-opaque. */
public record ActivatedExternalSecret(String providerId, String leaseId, String activeLocator) {
    public ActivatedExternalSecret {
        SecretValidation.identifier(providerId, "providerId");
        SecretValidation.text(leaseId, "leaseId", 512);
        SecretValidation.text(activeLocator, "activeLocator", 2048);
    }
    @JsonIgnore @Override public String leaseId() { return leaseId; }
    @JsonIgnore @Override public String activeLocator() { return activeLocator; }
    @Override public String toString() { return "ActivatedExternalSecret[providerId=" + providerId + ", redacted=true]"; }
}
