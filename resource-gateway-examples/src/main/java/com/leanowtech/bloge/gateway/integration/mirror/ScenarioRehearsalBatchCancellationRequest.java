package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strict idempotent cancellation intent for one durable Scenario rehearsal batch.
 *
 * @param schemaVersion exact command protocol version
 * @param commandId caller-stable cancellation idempotency identity
 * @param reasonCode bounded machine-readable owner reason
 */
public record ScenarioRehearsalBatchCancellationRequest(
        String schemaVersion,
        String commandId,
        String reasonCode
) {
    /** Current cancellation command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchCancellationRequest.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates exact command identity and bounded structural reason. */
    public ScenarioRehearsalBatchCancellationRequest {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch cancellation schemaVersion");
        }
        commandId = commandId == null
                ? "" : commandId.trim();
        reasonCode = reasonCode == null
                ? "" : reasonCode.trim().toUpperCase(
                Locale.ROOT);
        if (!IDENTIFIER.matcher(commandId).matches()
                || !CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "Scenario batch cancellation identity is invalid");
        }
    }
}
