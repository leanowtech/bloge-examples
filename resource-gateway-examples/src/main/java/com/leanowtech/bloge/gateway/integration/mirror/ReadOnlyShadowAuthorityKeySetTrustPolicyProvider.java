package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Operator-owned bootstrap trust for managed read-only Shadow authority key sets.
 *
 * <p>Expected stream identity, accepted policy generations, threshold, and root keys must come
 * from a separately governed source. They must never be inferred from the untrusted publication
 * currently being admitted.</p>
 */
public interface ReadOnlyShadowAuthorityKeySetTrustPolicyProvider {
    /** @return whether an authoritative local policy snapshot can currently be resolved */
    boolean available();

    /**
     * Resolves exact bootstrap policy for one authority stream.
     *
     * @param scope complete enterprise scope
     * @param publicationKind exact Shadow authority protocol
     * @param issuer exact delegated authority
     * @return detached local trust policy, or empty when the stream is not governed here
     */
    Optional<TrustPolicy> resolve(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
            String issuer);

    /** Creates a fail-closed provider used until governed bootstrap trust is installed. */
    static ReadOnlyShadowAuthorityKeySetTrustPolicyProvider unavailable() {
        return new ReadOnlyShadowAuthorityKeySetTrustPolicyProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<TrustPolicy> resolve(
                    CapabilitySnapshot.Scope scope,
                    ReadOnlyShadowAuthorityIntegrity.PublicationKind publicationKind,
                    String issuer) {
                return Optional.empty();
            }
        };
    }

    /**
     * One detached bootstrap trust snapshot.
     *
     * @param binding exact locally governed stream identity
     * @param roots independently pinned bootstrap-root public keys
     */
    record TrustPolicy(
            ReadOnlyShadowAuthorityKeySetIntegrity.ExpectedBinding binding,
            List<ReadOnlyShadowAuthorityKeySetIntegrity.RootVerificationKey> roots
    ) {
        /** Defensively detaches policy material. */
        public TrustPolicy {
            binding = Objects.requireNonNull(binding, "binding");
            roots = roots == null ? List.of() : List.copyOf(roots);
            roots.forEach(root -> Objects.requireNonNull(root, "root"));
            if (roots.isEmpty()) {
                throw new IllegalArgumentException("bootstrap roots are required");
            }
        }
    }
}
