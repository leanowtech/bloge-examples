package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

import java.time.Instant;

/**
 * Durable, sanitized test-run aggregate stored outside the production run tables.
 *
 * <p>The record deliberately duplicates scope and lookup columns from evidence so repository
 * authorization never needs to deserialize attacker-controlled JSON before applying tenant and
 * environment predicates.</p>
 *
 * @param runId unique test-run id
 * @param tenantId verified tenant scope
 * @param organizationId verified organization provenance
 * @param projectId verified project provenance
 * @param environmentId verified non-production environment
 * @param actorId verified initiating actor
 * @param target frozen target identity
 * @param fixtureBundleRef exact fixture provenance
 * @param requestedVerbosity original response projection
 * @param plan immutable effective execution plan
 * @param evidence complete sanitized terminal evidence
 * @param integrity detached signature manifest over the complete evidence
 * @param createdAt authoritative record creation time
 * @param expiresAt retention deadline
 */
public record TestRunRecord(
        String runId,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String actorId,
        TestExecutionApiRequest.Target target,
        TestExecutionApiResponse.ResolvedFixtureBundleRef fixtureBundleRef,
        TestExecutionApiRequest.Verbosity requestedVerbosity,
        EffectiveExecutionPlan plan,
        TestRunEvidence evidence,
        TestEvidenceIntegrity integrity,
        Instant createdAt,
        Instant expiresAt
) {
    /** Applies a legacy unsigned marker when reading records written before evidence signing. */
    public TestRunRecord {
        integrity = integrity == null ? TestEvidenceIntegrity.unsigned() : integrity;
    }
}
