package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;

import java.time.Instant;

/**
 * Tenant-scoped durable suite-run checkpoint stored outside production run tables.
 *
 * @param suiteRunId server-minted aggregate run id
 * @param clientRequestId caller idempotency key
 * @param requestFingerprint canonical normalized request fingerprint
 * @param tenantId verified tenant scope
 * @param organizationId verified organization provenance
 * @param projectId verified project provenance
 * @param environmentId verified non-production environment
 * @param actorId verified initiating actor
 * @param classification maximum suite and fixture data classification
 * @param evidenceFingerprint terminal aggregate fingerprint; blank while running
 * @param evidence latest durable aggregate checkpoint
 * @param createdAt authoritative record creation time
 * @param expiresAt retention deadline
 */
public record TestSuiteRunRecord(
        String suiteRunId,
        String clientRequestId,
        String requestFingerprint,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String actorId,
        String classification,
        String evidenceFingerprint,
        TestSuiteRunEvidence evidence,
        Instant createdAt,
        Instant expiresAt
) {
}
