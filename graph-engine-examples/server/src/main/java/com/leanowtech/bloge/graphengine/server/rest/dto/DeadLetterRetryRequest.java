package com.leanowtech.bloge.graphengine.server.rest.dto;

import com.leanowtech.bloge.graphengine.service.RecoveryActionEvidence;

/**
 * Optional request body for replaying one dead-lettered work item.
 *
 * @param reason human-readable recovery reason
 * @param sourceActionCode operations action code that suggested this retry
 * @param sourceIndicatorCode SLO indicator code that triggered this retry
 * @param actor operator or automation identity initiating the retry
 * @param requestId caller-supplied request or ticket identifier used for correlation
 */
public record DeadLetterRetryRequest(
        String reason,
        String sourceActionCode,
        String sourceIndicatorCode,
        String actor,
        String requestId
) {
    /**
     * Converts an optional HTTP payload to service-layer recovery evidence.
     */
    public static RecoveryActionEvidence toEvidence(DeadLetterRetryRequest request) {
        return request == null
                ? RecoveryActionEvidence.empty()
                : new RecoveryActionEvidence(
                        request.reason(),
                        request.sourceActionCode(),
                        request.sourceIndicatorCode(),
                        request.actor(),
                        request.requestId()
                );
    }
}
