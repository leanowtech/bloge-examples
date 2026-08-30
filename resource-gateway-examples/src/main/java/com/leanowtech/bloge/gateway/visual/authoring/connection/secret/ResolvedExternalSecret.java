package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Resolved material. Closing this object erases the returned secret.
 * @see DestroyableSecret
 */
public final class ResolvedExternalSecret implements AutoCloseable {
    private final String providerId;
    private final DestroyableSecret material;
    /**
     * Creates a caller-closeable resolved result.
     * @param providerId provider implementation identity
     * @param material caller-owned material holder
     */
    public ResolvedExternalSecret(String providerId, DestroyableSecret material) {
        this.providerId = SecretValidation.identifier(providerId, "providerId");
        this.material = Objects.requireNonNull(material, "material");
    }
    /** @return provider implementation identity */
    @JsonProperty("providerId")
    public String providerId() { return providerId; }
    /** Caller-owned resolved material; close this result after the provider call. */
    @JsonIgnore public DestroyableSecret material() { return material; }
    /** Erases resolved material; repeated calls are safe. */
    @Override public void close() { material.close(); }
    @Override public String toString() { return "ResolvedExternalSecret[providerId=" + providerId + ", redacted=true]"; }
}
