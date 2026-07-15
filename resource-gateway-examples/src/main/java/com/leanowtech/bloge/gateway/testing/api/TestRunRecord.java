package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

import java.time.Instant;

/**
 * Durable, sanitized test-run aggregate stored outside the production run tables.
 *
 * <p>The record deliberately duplicates scope and lookup columns from evidence so repository
 * authorization never needs to deserialize attacker-controlled JSON before applying tenant and
 * environment predicates.</p>
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
        Instant createdAt,
        Instant expiresAt
) {
}
