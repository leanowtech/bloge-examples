package com.leanowtech.bloge.gateway.integration;

import java.util.Optional;
import java.util.Map;
import java.util.Locale;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/** Verifies a workload credential and returns server-owned identity claims. */
public interface IntegrationIdentityResolver {
    Optional<IntegrationWorkloadIdentity> resolve(String credential);

    default Optional<Resolution> resolveVerified(String credential) {
        return resolve(credential).map(identity -> new Resolution(identity, "", ""));
    }

    default ResolutionAttempt resolveAttempt(String credential) {
        return resolveVerified(credential)
                .map(ResolutionAttempt::verified)
                .orElseGet(ResolutionAttempt::invalid);
    }

    Descriptor descriptor();

    static IntegrationIdentityResolver unavailable() {
        return new IntegrationIdentityResolver() {
            @Override
            public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
                return Optional.empty();
            }

            @Override
            public ResolutionAttempt resolveAttempt(String credential) {
                return ResolutionAttempt.providerUnavailable();
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("UNAVAILABLE", "NONE", false, false, false);
            }
        };
    }

    record Descriptor(String providerType,
                      String claimsSource,
                      boolean available,
                      boolean demoMode,
                      boolean delegatedIdentitySupported,
                      Map<String, Object> properties) {
        public Descriptor(String providerType,
                          String claimsSource,
                          boolean available,
                          boolean demoMode,
                          boolean delegatedIdentitySupported) {
            this(providerType, claimsSource, available, demoMode, delegatedIdentitySupported, Map.of());
        }

        public Descriptor {
            providerType = normalize(providerType).toUpperCase(Locale.ROOT);
            claimsSource = normalize(claimsSource).toUpperCase(Locale.ROOT);
            Map<String, Object> orderedProperties = new TreeMap<>();
            if (properties != null) {
                orderedProperties.putAll(properties);
            }
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(orderedProperties));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** Verified identity plus non-secret credential identifiers used for audit correlation. */
    record Resolution(IntegrationWorkloadIdentity identity, String credentialId, String tokenId) {
        public Resolution {
            if (identity == null) {
                throw new IllegalArgumentException("A verified integration identity is required");
            }
            credentialId = normalize(credentialId);
            tokenId = normalize(tokenId);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    enum ResolutionOutcome {
        VERIFIED,
        INVALID,
        PROVIDER_UNAVAILABLE
    }

    /** Distinguishes an invalid credential from a temporarily unavailable identity authority. */
    record ResolutionAttempt(ResolutionOutcome outcome, Resolution resolution) {
        public ResolutionAttempt {
            outcome = outcome == null ? ResolutionOutcome.INVALID : outcome;
            if (outcome == ResolutionOutcome.VERIFIED && resolution == null) {
                throw new IllegalArgumentException("A verified integration identity resolution is required");
            }
            if (outcome != ResolutionOutcome.VERIFIED) {
                resolution = null;
            }
        }

        public static ResolutionAttempt verified(Resolution resolution) {
            return new ResolutionAttempt(ResolutionOutcome.VERIFIED, resolution);
        }

        public static ResolutionAttempt invalid() {
            return new ResolutionAttempt(ResolutionOutcome.INVALID, null);
        }

        public static ResolutionAttempt providerUnavailable() {
            return new ResolutionAttempt(ResolutionOutcome.PROVIDER_UNAVAILABLE, null);
        }
    }
}
