package com.leanowtech.bloge.gateway.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/** Constant-time bearer credential resolver backed by a server-side identity record. */
public final class StaticBearerIntegrationIdentityResolver implements IntegrationIdentityResolver {
    private final List<CredentialIdentity> identities;
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
        this(Map.of(credential, identity), demoMode, clock);
    }

    /**
     * Creates a small server-owned credential registry, used by the local demo to keep the Agent
     * workload credential separate from the human reviewer credential.
     */
    public StaticBearerIntegrationIdentityResolver(Map<String, IntegrationWorkloadIdentity> credentials,
                                                    boolean demoMode) {
        this(credentials, demoMode, Clock.systemUTC());
    }

    StaticBearerIntegrationIdentityResolver(Map<String, IntegrationWorkloadIdentity> credentials,
                                             boolean demoMode,
                                             Clock clock) {
        if (credentials == null || credentials.isEmpty()) {
            throw new IllegalArgumentException("At least one integration bearer credential is required");
        }
        List<CredentialIdentity> configured = new ArrayList<>();
        credentials.forEach((credential, identity) -> {
            if (credential == null || credential.isBlank()) {
                throw new IllegalArgumentException("A non-empty integration bearer credential is required");
            }
            if (identity == null || identity.identityId().isBlank()) {
                throw new IllegalArgumentException("A server-owned integration identity is required");
            }
            configured.add(new CredentialIdentity(digest(credential.trim()), identity));
        });
        this.identities = List.copyOf(configured);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.demoMode = demoMode;
    }

    @Override
    public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
        if (credential == null || credential.isBlank()) {
            return Optional.empty();
        }
        byte[] supplied = digest(credential.trim());
        IntegrationWorkloadIdentity matched = null;
        for (CredentialIdentity candidate : identities) {
            if (MessageDigest.isEqual(candidate.digest(), supplied)) {
                matched = candidate.identity();
            }
        }
        return matched == null || !matched.activeAt(clock.instant()) ? Optional.empty() : Optional.of(matched);
    }

    @Override
    public Optional<Resolution> resolveVerified(String credential) {
        return resolve(credential).map(value -> new Resolution(value, "static-bearer", ""));
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor("STATIC_BEARER_REGISTRY", "SERVER_REGISTRY", true, demoMode,
                identities.stream().anyMatch(value -> !value.identity().delegatedBy().isBlank()), Map.of(
                "keyRotationSupported", false,
                "keyRevocationSupported", false,
                "tokenRevocationSupported", false));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record CredentialIdentity(byte[] digest, IntegrationWorkloadIdentity identity) { }
}
