package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/** Resolved material. Closing this object erases the returned secret. */
public final class ResolvedExternalSecret implements AutoCloseable {
    private final String providerId;
    private final DestroyableSecret material;
    public ResolvedExternalSecret(String providerId, DestroyableSecret material) {
        this.providerId = SecretValidation.identifier(providerId, "providerId");
        this.material = Objects.requireNonNull(material, "material");
    }
    public String providerId() { return providerId; }
    /** Caller-owned resolved material; close this result after the provider call. */
    @JsonIgnore public DestroyableSecret material() { return material; }
    @Override public void close() { material.close(); }
    @Override public String toString() { return "ResolvedExternalSecret[providerId=" + providerId + ", redacted=true]"; }
}
