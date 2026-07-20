package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;

import java.util.Map;

/**
 * External authority for exact, purpose-bound test-secret resolution.
 *
 * <p>Implementations are security boundaries. They must authorize the complete request context,
 * resolve exact immutable secret versions, return a short-lived closure, and never persist or log
 * plaintext values. Resource Gateway independently verifies every returned binding before use.</p>
 */
@FunctionalInterface
public interface TestSecretAuthority {

    /**
     * Resolves the exact requested closure or fails closed.
     *
     * @param context payload-free scope, target, fixture, purpose, and reference binding
     * @return short-lived exact closure containing runtime-only values
     * @throws ResolutionException when policy denies or the authority cannot make a trusted decision
     */
    ResolvedTestSecrets resolve(TestSecretResolutionContext context);

    /** @return payload-free implementation capability descriptor */
    default Descriptor descriptor() {
        return new Descriptor("bloge.testSecretAuthorityDescriptor.v1", true,
                "IN_PROCESS_EXTERNAL", "custom", Map.of());
    }

    /** @return authority that always fails closed for fixtures requesting test secrets */
    static TestSecretAuthority unavailable() {
        return new TestSecretAuthority() {
            @Override
            public ResolvedTestSecrets resolve(TestSecretResolutionContext context) {
                throw new ResolutionException(Reason.UNAVAILABLE);
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("", false, "UNAVAILABLE", "", Map.of());
            }
        };
    }

    /** Stable authority failure categories safe to map without exposing provider details. */
    enum Reason {
        DENIED,
        UNAVAILABLE,
        INVALID_RESPONSE
    }

    /** Payload-free authority failure. */
    final class ResolutionException extends RuntimeException {
        private final Reason reason;

        /** @param reason stable non-sensitive failure category */
        public ResolutionException(Reason reason) {
            super("Test-secret authority could not authorize the requested dependency closure.");
            this.reason = reason == null ? Reason.UNAVAILABLE : reason;
        }

        /** @return stable non-sensitive failure category */
        public Reason reason() {
            return reason;
        }
    }

    /**
     * Payload-free capability facts. Properties may describe transport and trust policy, but must
     * never contain credentials, opaque references, aliases, or secret values.
     */
    record Descriptor(String schemaVersion, boolean available, String providerType,
                      String authorityId, Map<String, Object> properties) {
        /** Normalizes and freezes descriptor facts. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            if (schemaVersion.isBlank()) {
                schemaVersion = "bloge.testSecretAuthorityDescriptor.v1";
            }
            providerType = normalized(providerType);
            authorityId = normalized(authorityId);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
