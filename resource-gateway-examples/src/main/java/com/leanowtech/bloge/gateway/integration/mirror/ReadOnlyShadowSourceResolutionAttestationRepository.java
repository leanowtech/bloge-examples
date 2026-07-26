package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/** Append-only persistence boundary for signed source-resolution attestations. */
public interface ReadOnlyShadowSourceResolutionAttestationRepository {
    /**
     * Appends one exact signed attestation revision.
     *
     * @param attestation independently verified attestation
     * @return identical persisted attestation
     */
    ReadOnlyShadowSourceResolutionAttestation create(
            ReadOnlyShadowSourceResolutionAttestation attestation);

    /**
     * Finds one exact signed attestation revision.
     *
     * @param scope complete enterprise namespace
     * @param attestationId exact attestation identity
     * @param revision exact positive revision
     * @return verified attestation when present
     */
    Optional<ReadOnlyShadowSourceResolutionAttestation> find(
            CapabilitySnapshot.Scope scope,
            String attestationId,
            long revision);
}
