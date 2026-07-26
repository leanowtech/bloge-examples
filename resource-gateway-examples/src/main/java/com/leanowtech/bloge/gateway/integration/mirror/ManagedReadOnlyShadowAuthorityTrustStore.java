package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-current, root-verified trust store for read-only Shadow authority publications.
 *
 * <p>Every resolution fetches the current key-set head, current root policy, and durable floor,
 * then re-verifies binding, threshold signatures, freshness, and content addressing. No positive
 * key cache exists, so a committed RETIRED or REVOKED successor takes effect on the next authority
 * observation across every replica sharing the database.</p>
 */
public final class ManagedReadOnlyShadowAuthorityTrustStore
        implements ReadOnlyShadowAuthorityTrustStore {
    private final ReadOnlyShadowAuthorityKeySetRepository publications;
    private final ReadOnlyShadowAuthorityKeySetTrustPolicyProvider trustPolicies;
    private final ReadOnlyShadowAuthorityKeySetIntegrity integrity;
    private final Clock clock;

    /**
     * Creates a managed current-head trust store.
     *
     * @param publications durable key-set log and floor
     * @param trustPolicies independently governed bootstrap trust
     * @param integrity key-set threshold-signature verifier
     * @param clock trusted freshness clock
     */
    public ManagedReadOnlyShadowAuthorityTrustStore(
            ReadOnlyShadowAuthorityKeySetRepository publications,
            ReadOnlyShadowAuthorityKeySetTrustPolicyProvider trustPolicies,
            ReadOnlyShadowAuthorityKeySetIntegrity integrity,
            Clock clock) {
        this.publications = Objects.requireNonNull(publications, "publications");
        this.trustPolicies = Objects.requireNonNull(trustPolicies, "trustPolicies");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<ReadOnlyShadowAuthorityIntegrity.AuthorityKey> resolve(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
            String issuer,
            String keyId) {
        CapabilitySnapshot.Scope exactScope =
                ReadOnlyShadowAuthoritySeal.scope(scope, "scope");
        ReadOnlyShadowAuthorityIntegrity.PublicationKind exactKind =
                Objects.requireNonNull(publicationKind, "publicationKind");
        String exactIssuer = ReadOnlyShadowAuthoritySeal.identifier(issuer, "issuer");
        String exactKeyId = ReadOnlyShadowAuthoritySeal.identifier(keyId, "keyId");
        try {
            if (!trustPolicies.available() || !publications.available()) {
                return Optional.empty();
            }
            ReadOnlyShadowAuthorityKeySetTrustPolicyProvider.TrustPolicy policy =
                    trustPolicies.resolve(exactScope, exactKind, exactIssuer).orElse(null);
            if (policy == null
                    || !policy.binding().scope().equals(exactScope)
                    || policy.binding().publicationKind() != exactKind
                    || !policy.binding().issuer().equals(exactIssuer)) {
                return Optional.empty();
            }
            var stream = new ReadOnlyShadowAuthorityKeySetRepository.StreamIdentity(
                    exactScope, exactKind, exactIssuer, policy.binding().keySetId());
            ReadOnlyShadowAuthorityKeySetPublication publication =
                    publications.latest(stream).orElse(null);
            ReadOnlyShadowAuthorityKeySetIntegrity.TrustedFloor floor =
                    publications.floor(stream).orElse(null);
            if (publication == null || floor == null
                    || !integrity.verify(publication, policy.binding(), policy.roots(),
                    floor, clock.instant()).verified()) {
                return Optional.empty();
            }
            return publication.material().keys().stream()
                    .filter(key -> key.keyId().equals(exactKeyId))
                    .findFirst()
                    .map(key -> key.runtimeKey(publication.material()));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public boolean available() {
        try {
            return trustPolicies.available() && publications.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
