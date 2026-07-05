package com.leanowtech.bloge.graphengine.server.rest.dto;

import com.leanowtech.bloge.graphengine.service.RecoveryActionEvidence;

import java.util.Set;

/**
 * Request body for the instance-level retry endpoint.
 *
 * @param nodeIds          optional filter restricting retries to specific node identifiers;
 *                         when {@code null} or empty, all dead-lettered items for the instance
 *                         are retried
 * @param expectedRevision optimistic-lock guard on the instance projection
 * @param reason human-readable recovery reason
 * @param sourceActionCode operations action code that suggested this retry
 * @param sourceIndicatorCode SLO indicator code that triggered this retry
 * @param actor operator or automation identity initiating the retry
 * @param requestId caller-supplied request or ticket identifier used for correlation
 */
public record RetryInstanceRequest(
        Set<String> nodeIds,
        long expectedRevision,
        String reason,
        String sourceActionCode,
        String sourceIndicatorCode,
        String actor,
        String requestId
) {
    /**
     * Converts the HTTP payload to service-layer recovery evidence.
     */
    public RecoveryActionEvidence toEvidence() {
        return new RecoveryActionEvidence(reason, sourceActionCode, sourceIndicatorCode, actor, requestId);
    }
}
