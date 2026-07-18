package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact authenticated intent admitted to the durable suite-stability queue.
 *
 * @param jobId server-derived job identity
 * @param request existing exact stability execution intent
 * @param requestFingerprint canonical execution-request fingerprint
 * @param classification frozen suite classification
 * @param principal credential-free authenticated principal snapshot
 * @param priority bounded caller priority
 * @param deadlineAt absolute execution deadline
 */
public record TestSuiteStabilityJobSubmission(
        String jobId,
        TestSuiteStabilityExecutionRequest request,
        String requestFingerprint,
        String classification,
        TestSuiteStabilityJobPrincipal principal,
        Priority priority,
        Instant deadlineAt) {

    private static final Pattern JOB_ID =
            Pattern.compile("stability-job-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Queue priority; aging may raise effective priority but never changes this audit value. */
    public enum Priority {
        LOW,
        NORMAL,
        HIGH
    }

    /** Validates a complete persistence-safe queue intent. */
    public TestSuiteStabilityJobSubmission {
        jobId = normalized(jobId);
        requestFingerprint = normalized(requestFingerprint);
        classification = normalized(classification).toUpperCase(Locale.ROOT);
        request = java.util.Objects.requireNonNull(request, "request");
        principal = java.util.Objects.requireNonNull(principal, "principal");
        priority = priority == null ? Priority.NORMAL : priority;
        deadlineAt = java.util.Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (!JOB_ID.matcher(jobId).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(classification)
                || request.clientRequestId().isBlank()
                || request.suiteRef() == null) {
            throw new IllegalArgumentException("Invalid suite-stability job submission");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
