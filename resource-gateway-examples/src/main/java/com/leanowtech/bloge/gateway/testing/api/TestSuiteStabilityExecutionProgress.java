package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Durable payload-free progress for one exact suite-stability parent execution.
 *
 * <p>The ordered journal contains only source suite-run identities and their signed aggregate
 * fingerprints. A successor must refetch and verify those source records before scheduling the
 * next attempt or publishing terminal evidence. Fixture values, business context, child payloads,
 * credentials, and outputs never enter this record.</p>
 *
 * @param stabilityRunId deterministic parent identity
 * @param tenantId verified tenant scope
 * @param environmentId verified non-production environment
 * @param clientRequestId caller-stable idempotency identity
 * @param requestFingerprint immutable parent request fingerprint
 * @param suiteRef exact immutable suite revision
 * @param classification frozen suite data classification used by query authorization
 * @param plannedAttempts precommitted bounded horizon
 * @param attempts contiguous completed-attempt journal
 * @param createdAt database-authoritative creation time
 * @param updatedAt database-authoritative latest checkpoint time
 * @param expiresAt exclusive progress-retention deadline
 */
public record TestSuiteStabilityExecutionProgress(
        String stabilityRunId,
        String tenantId,
        String environmentId,
        String clientRequestId,
        String requestFingerprint,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String classification,
        int plannedAttempts,
        List<AttemptReference> attempts,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt
) {
    private static final Pattern STABILITY_RUN_ID = Pattern.compile("stability-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Canonicalizes and verifies the complete durable journal. */
    public TestSuiteStabilityExecutionProgress {
        stabilityRunId = normalized(stabilityRunId);
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId);
        clientRequestId = normalized(clientRequestId);
        requestFingerprint = normalized(requestFingerprint);
        classification = normalized(classification).toUpperCase(java.util.Locale.ROOT);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        if (!STABILITY_RUN_ID.matcher(stabilityRunId).matches()
                || !identifier(tenantId) || !identifier(environmentId)
                || !identifier(clientRequestId)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !validSuiteRef(suiteRef)
                || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(classification)
                || plannedAttempts < TestSuiteStabilityEvidence.MIN_ATTEMPTS
                || plannedAttempts > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                || attempts.size() > plannedAttempts
                || createdAt == null || updatedAt == null || expiresAt == null
                || updatedAt.isBefore(createdAt) || !expiresAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException(
                    "A complete bounded suite-stability execution progress record is required");
        }
        for (int index = 0; index < attempts.size(); index++) {
            AttemptReference attempt = Objects.requireNonNull(
                    attempts.get(index), "attempt reference");
            if (attempt.attempt() != index + 1) {
                throw new IllegalArgumentException(
                        "Suite-stability progress attempts must be contiguous and one-based");
            }
        }
        if (attempts.stream().map(AttemptReference::suiteRunId).distinct().count()
                != attempts.size()) {
            throw new IllegalArgumentException(
                    "Suite-stability progress cannot reuse a source suite run");
        }
    }

    /** @return number of durably checkpointed source attempts */
    public int completedAttempts() {
        return attempts.size();
    }

    /**
     * Appends exactly the next source reference and advances database-authoritative retention.
     *
     * @param attempt next verified source reference
     * @param observedAt database checkpoint time
     * @param nextExpiresAt successor retention deadline
     * @return immutable successor progress
     */
    public TestSuiteStabilityExecutionProgress append(
            AttemptReference attempt,
            Instant observedAt,
            Instant nextExpiresAt) {
        Objects.requireNonNull(attempt, "attempt");
        if (attempt.attempt() != completedAttempts() + 1
                || attempts.stream().anyMatch(value ->
                value.suiteRunId().equals(attempt.suiteRunId()))) {
            throw new IllegalArgumentException(
                    "Only the next unique suite-stability attempt may be checkpointed");
        }
        java.util.ArrayList<AttemptReference> successor = new java.util.ArrayList<>(attempts);
        successor.add(attempt);
        return new TestSuiteStabilityExecutionProgress(
                stabilityRunId, tenantId, environmentId, clientRequestId, requestFingerprint,
                suiteRef, classification, plannedAttempts, successor,
                createdAt, observedAt, nextExpiresAt);
    }

    /** Payload-free pointer to one fully persisted and signed source suite run. */
    public record AttemptReference(
            int attempt,
            String suiteRunId,
            String aggregateEvidenceFingerprint
    ) {
        /** Normalizes and rejects incomplete source coordinates. */
        public AttemptReference {
            suiteRunId = normalized(suiteRunId);
            aggregateEvidenceFingerprint = normalized(aggregateEvidenceFingerprint);
            if (attempt < 1 || attempt > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                    || !identifier(suiteRunId)
                    || !FINGERPRINT.matcher(aggregateEvidenceFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "A complete suite-stability source attempt reference is required");
            }
        }
    }

    private static boolean validSuiteRef(TestSuiteExecutionRequest.SuiteRef value) {
        return value != null && identifier(value.suiteId()) && value.revision() >= 1
                && FINGERPRINT.matcher(normalized(value.fingerprint())).matches();
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
