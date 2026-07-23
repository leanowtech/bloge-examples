package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Operator-owned source of local trust for isolation-authority publication admission.
 *
 * <p>The HTTP request can supply only a signed publication. Expected deployment binding,
 * accepted policy generations, threshold, and bootstrap-root public keys come through this SPI
 * from a separately governed security control plane. Implementations should return detached
 * immutable snapshots and must never resolve trust from fields that were first learned from the
 * same untrusted request.</p>
 */
public interface MirrorDeploymentIsolationAuthorityTrustPolicyProvider {
    /**
     * Reports whether the provider can currently resolve authoritative policy snapshots.
     *
     * <p>Capability probes call this method synchronously. Implementations must therefore read a
     * bounded, non-blocking local readiness snapshot instead of performing remote control-plane
     * I/O on the request thread.</p>
     *
     * @return true only when the external or local trust source is ready
     */
    boolean available();

    /**
     * Resolves an exact policy without broadening or falling back across scopes.
     *
     * @param scope authenticated complete enterprise scope
     * @param deploymentScopeId operator-owned deployment scope
     * @param keySetId stable authority key-set stream
     * @return exact local policy, or empty when that identity is not governed here
     */
    Optional<TrustPolicy> resolve(
            CapabilitySnapshot.Scope scope, String deploymentScopeId, String keySetId);

    /**
     * Returns a fail-closed provider used until an operator wires governed trust.
     *
     * @return provider that is never ready and never resolves policy
     */
    static MirrorDeploymentIsolationAuthorityTrustPolicyProvider unavailable() {
        return new MirrorDeploymentIsolationAuthorityTrustPolicyProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<TrustPolicy> resolve(
                    CapabilitySnapshot.Scope scope, String deploymentScopeId, String keySetId) {
                return Optional.empty();
            }
        };
    }

    /**
     * One detached local verification snapshot.
     *
     * @param binding exact scope, deployment, stream, threshold, and policy allowlist
     * @param roots independently pinned bootstrap-root public keys
     */
    record TrustPolicy(
            MirrorDeploymentIsolationAuthorityKeySetIntegrity.ExpectedBinding binding,
            List<MirrorDeploymentIsolationAuthorityKeySetIntegrity.RootVerificationKey> roots
    ) {
        /** Defensively detaches policy material before it reaches request handling. */
        public TrustPolicy {
            binding = Objects.requireNonNull(binding, "binding");
            roots = roots == null ? List.of() : List.copyOf(roots);
            roots.forEach(root -> Objects.requireNonNull(root, "root"));
        }
    }
}
