package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Exact payload-free invocation context passed to one isolated Shadow connector.
 *
 * @param executionId stable idempotency identity across worker retries
 * @param request immutable durable request
 * @param accessAdmission exact online authority closure
 * @param startedAt trusted paired-execution start
 * @param deadlineAt absolute durable job deadline
 */
public record ReadOnlyShadowConnectorInvocation(
        String executionId,
        ReadOnlyShadowJobRequest request,
        ReadOnlyShadowAccessAuthority.Admission accessAdmission,
        Instant startedAt,
        Instant deadlineAt
) {
    private static final Pattern IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Validates exact scope, grant, idempotency, and execution-window closure. */
    public ReadOnlyShadowConnectorInvocation {
        executionId = executionId == null
                ? "" : executionId.trim();
        request = Objects.requireNonNull(
                request, "request");
        accessAdmission = Objects.requireNonNull(
                accessAdmission, "accessAdmission");
        startedAt = Objects.requireNonNull(
                startedAt, "startedAt");
        deadlineAt = Objects.requireNonNull(
                deadlineAt, "deadlineAt");
        if (!IDENTIFIER.matcher(executionId).matches()
                || !request.scope().equals(
                accessAdmission.scope())
                || !request.accessGrant()
                .zeroWriteProof()
                .equals(accessAdmission.accessProof())
                || !deadlineAt.equals(
                request.deadlineAt())
                || startedAt.isBefore(
                accessAdmission.admittedAt())
                || !deadlineAt.isAfter(startedAt)
                || !accessAdmission.validUntil()
                .isAfter(startedAt)) {
            throw new IllegalArgumentException(
                    "read-only Shadow connector invocation is inconsistent");
        }
    }
}
