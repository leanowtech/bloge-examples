package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic trust source for signed read-only Shadow authority publications.
 *
 * <p>Implementations are expected to observe key rotation and revocation on every lookup. A
 * runtime must not retain a positive key result across authority observations unless the backing
 * trust protocol itself supplies a shorter safe freshness bound.</p>
 */
public interface ReadOnlyShadowAuthorityTrustStore {
    /**
     * Resolves one exact issuer-owned verification key.
     *
     * @param scope exact enterprise namespace being authorized
     * @param publicationKind exact authority protocol being verified
     * @param issuer exact authority identity from signed material
     * @param keyId exact detached-signature key id
     * @return current key policy, or empty when the key is unknown
     */
    Optional<ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
    resolve(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind
                    publicationKind,
            String issuer,
            String keyId);

    /** @return whether fresh key and revocation state can currently be resolved */
    boolean available();

    /** Creates a fail-closed trust source. */
    static ReadOnlyShadowAuthorityTrustStore unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton. */
    final class Unavailable
            implements ReadOnlyShadowAuthorityTrustStore {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public Optional<
                ReadOnlyShadowAuthorityIntegrity.AuthorityKey>
        resolve(
                CapabilitySnapshot.Scope scope,
                ReadOnlyShadowAuthorityIntegrity.PublicationKind
                        publicationKind,
                String issuer,
                String keyId) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(
                    publicationKind, "publicationKind");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(keyId, "keyId");
            return Optional.empty();
        }

        @Override
        public boolean available() {
            return false;
        }
    }
}
