package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.Optional;

/**
 * Operator-owned bootstrap policy for one deployment-isolation attestation stream.
 *
 * <p>Attestation v1 carries a revision but no signed predecessor fingerprint. Consequently an
 * empty database cannot distinguish the real external head from an older, still-valid proof.
 * This provider pins the exact first revision the local control plane may accept; every later
 * revision is then enforced by the durable repository floor. Values must come from local governed
 * configuration or a bounded local cache, never from the ingest request.</p>
 */
public interface MirrorDeploymentIsolationAttestationAdmissionPolicyProvider {
    /**
     * Reports whether a bounded local policy snapshot is ready.
     *
     * <p>This method is used by capability probes and therefore must not perform remote I/O or
     * unbounded blocking.</p>
     *
     * @return whether policy resolution can currently serve requests
     */
    boolean available();

    /**
     * Resolves the exact operator-owned bootstrap policy.
     *
     * @param scope complete enterprise scope
     * @param deploymentScopeId exact immutable deployment stream coordinate
     * @param keySetId exact authority publication stream
     * @param attestationId exact attestation stream
     * @return local policy, or empty when the stream is not governed here
     */
    Optional<AdmissionPolicy> resolve(
            CapabilitySnapshot.Scope scope,
            String deploymentScopeId,
            String keySetId,
            String attestationId);

    /**
     * Returns a provider that is always unavailable and resolves no policy.
     *
     * @return fail-closed provider
     */
    static MirrorDeploymentIsolationAttestationAdmissionPolicyProvider unavailable() {
        return new MirrorDeploymentIsolationAttestationAdmissionPolicyProvider() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<AdmissionPolicy> resolve(
                    CapabilitySnapshot.Scope scope,
                    String deploymentScopeId,
                    String keySetId,
                    String attestationId) {
                return Optional.empty();
            }
        };
    }

    /**
     * Exact local bootstrap floor and immutable stream binding.
     *
     * @param scope complete enterprise scope
     * @param deployment exact immutable deployment identity
     * @param keySetId exact authority publication stream
     * @param attestationId exact attestation stream
     * @param bootstrapRevision exact first revision accepted into an empty local stream
     */
    record AdmissionPolicy(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            String keySetId,
            String attestationId,
            long bootstrapRevision
    ) {
        /** Validates complete operator-owned bootstrap coordinates. */
        public AdmissionPolicy {
            scope = Objects.requireNonNull(scope, "scope");
            deployment = Objects.requireNonNull(deployment, "deployment");
            keySetId = required(keySetId, "keySetId");
            attestationId = required(attestationId, "attestationId");
            if (bootstrapRevision < 1) {
                throw new IllegalArgumentException("bootstrapRevision must be positive");
            }
        }

        private static String required(String value, String field) {
            String exact = value == null ? "" : value.trim();
            if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            return exact;
        }
    }
}
