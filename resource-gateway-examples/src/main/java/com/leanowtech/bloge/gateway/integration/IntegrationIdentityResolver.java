package com.leanowtech.bloge.gateway.integration;

import java.util.Optional;

/** Verifies a workload credential and returns server-owned identity claims. */
public interface IntegrationIdentityResolver {
    Optional<IntegrationWorkloadIdentity> resolve(String credential);

    Descriptor descriptor();

    static IntegrationIdentityResolver unavailable() {
        return new IntegrationIdentityResolver() {
            @Override
            public Optional<IntegrationWorkloadIdentity> resolve(String credential) {
                return Optional.empty();
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
                      boolean delegatedIdentitySupported) {
        public Descriptor {
            providerType = normalize(providerType).toUpperCase();
            claimsSource = normalize(claimsSource).toUpperCase();
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
