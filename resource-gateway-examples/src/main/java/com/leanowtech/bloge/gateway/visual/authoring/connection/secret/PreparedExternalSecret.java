package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import java.time.Instant;

/** Provider preparation result. Opaque fields must never be rendered into logs or responses. */
public record PreparedExternalSecret(String providerId, String leaseId, String opaqueLocator, Instant leaseUntil) {
    public PreparedExternalSecret {
        SecretValidation.identifier(providerId, "providerId");
        SecretValidation.text(leaseId, "leaseId", 512);
        SecretValidation.text(opaqueLocator, "opaqueLocator", 2048);
        SecretValidation.expiry(leaseUntil);
    }
    @Override public String toString() { return "PreparedExternalSecret[providerId=" + providerId + ", redacted=true]"; }
}
