package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Authenticated, payload-free command for one durable stability-job cancellation.
 *
 * <p>The command carries the current caller snapshot separately from the job submitter so a
 * delegated operator can be attributed correctly. Its canonical fingerprint is calculated by the
 * application service and deliberately excludes the transient correlation id.</p>
 *
 * @param tenantId verified tenant scope
 * @param environmentId verified non-production environment
 * @param jobId exact durable job identity
 * @param clientRequestId caller-stable cancellation idempotency key
 * @param commandFingerprint canonical actor-bound command fingerprint
 * @param actor credential-free caller snapshot authorized for this cancellation
 */
public record TestSuiteStabilityJobCancellationCommand(
        String tenantId,
        String environmentId,
        String jobId,
        String clientRequestId,
        String commandFingerprint,
        TestSuiteStabilityJobPrincipal actor) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern JOB_ID = Pattern.compile("stability-job-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Requires one exact scope, command identity, fingerprint, and matching current actor. */
    public TestSuiteStabilityJobCancellationCommand {
        tenantId = normalized(tenantId);
        environmentId = normalized(environmentId);
        jobId = normalized(jobId);
        clientRequestId = normalized(clientRequestId);
        commandFingerprint = normalized(commandFingerprint);
        actor = Objects.requireNonNull(actor, "actor");
        if (!IDENTIFIER.matcher(tenantId).matches()
                || !java.util.Set.of("test", "staging").contains(environmentId)
                || !JOB_ID.matcher(jobId).matches()
                || !IDENTIFIER.matcher(clientRequestId).matches()
                || !FINGERPRINT.matcher(commandFingerprint).matches()
                || !tenantId.equals(actor.tenantId())
                || !environmentId.equals(actor.environmentId())) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability job cancellation command");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
