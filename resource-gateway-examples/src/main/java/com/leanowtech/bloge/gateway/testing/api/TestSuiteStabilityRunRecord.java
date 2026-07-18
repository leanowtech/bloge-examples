package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;

import java.time.Instant;

/**
 * Immutable tenant-scoped terminal stability analysis.
 *
 * @param stabilityRunId deterministic scope-and-request analysis id
 * @param clientRequestId caller parent idempotency key
 * @param requestFingerprint canonical normalized request fingerprint
 * @param tenantId verified tenant scope
 * @param organizationId verified organization provenance
 * @param projectId verified project provenance
 * @param environmentId verified non-production environment
 * @param actorId verified initiating actor
 * @param classification maximum suite/fixture classification
 * @param evidenceFingerprint canonical stability evidence fingerprint
 * @param evidence payload-free terminal stability evidence
 * @param attestation verified detached terminal signature
 * @param createdAt persistence time
 * @param expiresAt retention deadline bounded by the earliest source run
 */
public record TestSuiteStabilityRunRecord(
        String stabilityRunId,
        String clientRequestId,
        String requestFingerprint,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String actorId,
        String classification,
        String evidenceFingerprint,
        TestSuiteStabilityEvidence evidence,
        TestSuiteStabilityAttestation attestation,
        Instant createdAt,
        Instant expiresAt
) {
}
