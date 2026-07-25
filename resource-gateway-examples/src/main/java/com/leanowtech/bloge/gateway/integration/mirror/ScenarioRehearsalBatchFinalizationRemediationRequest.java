package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict compare-and-set command for re-queuing one quarantined batch finalization.
 *
 * <p>The caller supplies only an idempotency identity, the exact public state fence it reviewed,
 * and a bounded reason. Signing time, signing key, retry policy, terminal projection, and
 * retention extension remain server-owned so this recovery API cannot rewrite evidence.</p>
 *
 * @param schemaVersion exact command protocol version
 * @param commandId caller-stable remediation idempotency identity
 * @param expectedAttemptCount exact quarantined attempt count reviewed by the caller
 * @param expectedUpdatedAt exact quarantined control timestamp reviewed by the caller
 * @param reasonCode bounded machine-readable owner reason
 */
public record ScenarioRehearsalBatchFinalizationRemediationRequest(
        String schemaVersion,
        String commandId,
        int expectedAttemptCount,
        Instant expectedUpdatedAt,
        String reasonCode
) {
    /** Current finalization-remediation command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchFinalizationRemediationRequest.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates the immutable command identity and exact public quarantine fence. */
    public ScenarioRehearsalBatchFinalizationRemediationRequest {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        commandId = normalized(commandId);
        expectedUpdatedAt = Objects.requireNonNull(
                expectedUpdatedAt, "expectedUpdatedAt");
        reasonCode = normalized(reasonCode).toUpperCase(Locale.ROOT);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !IDENTIFIER.matcher(commandId).matches()
                || expectedAttemptCount < 1
                || expectedUpdatedAt.equals(Instant.EPOCH)
                || expectedUpdatedAt.isBefore(Instant.EPOCH)
                || !CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization remediation command is invalid");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
