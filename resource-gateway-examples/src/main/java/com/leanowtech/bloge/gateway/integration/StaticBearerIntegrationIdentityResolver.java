package com.leanowtech.bloge.gateway.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Optional;

/** Constant-time bearer credential resolver backed by a server-side identity record. */
public final class StaticBearerIntegrationIdentityResolver implements IntegrationIdentityResolver {
    private final byte[] credentialDigest;
    private final IntegrationWorkloadIdentity identity;
    private final Clock clock;
    private final boolean demoMode;

    public StaticBearerIntegrationIdentityResolver(String credential,
                                                   IntegrationWorkloadIdentity identity,
                                                   boolean demoMode) {
        this(credential, identity, demoMode, Clock.systemUTC());
    }

    StaticBearerIntegrationIdentityResolver(String credential,
                                            IntegrationWorkloadIdentity identity,
                                            boolean demoMode,
                                            Clock clock) {
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("A non-empty integration bearer credential is required");
        }
        if (identity == null || identity.identityId().isBlank()) {
            throw new IllegalArgumentException("A server-owned integration identity is required");
        }
        this.credentialDigest = digest(credential.trim());
        this.identity = identity;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.demoMode = demoMode;
    }

    @Override
    public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
        if (credential == null || credential.isBlank()
                || !MessageDigest.isEqual(credentialDigest, digest(credential.trim()))
                || !identity.activeAt(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(identity);
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("STATIC_BEARER_REGISTRY", "SERVER_REGISTRY", true, demoMode,
                !identity.delegatedBy().isBlank());
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
