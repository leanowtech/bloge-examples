package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider preparation result. This is an internal recovery record, not a wire DTO;
 * its lease may already be expired when a recovery or abort operation hydrates it.
 * @param providerId provider implementation identity
 * @param leaseId provider lease identity, never serialized
 * @param opaqueLocator provider preparation locator, never serialized
 * @param leaseUntil provider-reported expiry used by recovery policy
 */
public record PreparedExternalSecret(String providerId, String leaseId, String opaqueLocator, Instant leaseUntil) {
    public PreparedExternalSecret {
        SecretValidation.identifier(providerId, "providerId");
        SecretValidation.text(leaseId, "leaseId", 512);
        SecretValidation.text(opaqueLocator, "opaqueLocator", 2048);
        Objects.requireNonNull(leaseUntil, "leaseUntil");
    }

    @JsonIgnore @Override public String leaseId() { return leaseId; }
    @JsonIgnore @Override public String opaqueLocator() { return opaqueLocator; }
    @Override public String toString() { return "PreparedExternalSecret[providerId=" + providerId + ", redacted=true]"; }
}
