package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;

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
 * @param attestation signed checkpoint or terminal aggregate closure
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
        TestSuiteRunEvidenceProtocol evidence,
        TestSuiteRunAttestation attestation,
        Instant createdAt,
        Instant expiresAt
) {
    /** Applies a migration-safe unsigned marker when reading historical records. */
    public TestSuiteRunRecord {
        attestation = attestation == null ? TestSuiteRunAttestation.unsigned() : attestation;
    }

    /**
     * Retains source compatibility for historical checkpoint construction.
     *
     * <p>Records created through this constructor are deliberately unsigned and cannot be trusted
     * by the current execution service until migrated or re-executed.</p>
     */
    public TestSuiteRunRecord(
            String suiteRunId, String clientRequestId, String requestFingerprint,
            String tenantId, String organizationId, String projectId, String environmentId,
            String actorId, String classification, String evidenceFingerprint,
            TestSuiteRunEvidenceProtocol evidence, Instant createdAt, Instant expiresAt) {
        this(suiteRunId, clientRequestId, requestFingerprint, tenantId, organizationId, projectId,
                environmentId, actorId, classification, evidenceFingerprint, evidence,
                TestSuiteRunAttestation.unsigned(), createdAt, expiresAt);
    }
}
