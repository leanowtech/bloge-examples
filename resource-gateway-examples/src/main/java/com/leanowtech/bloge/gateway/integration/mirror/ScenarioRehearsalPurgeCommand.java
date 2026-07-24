package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.regex.Pattern;

/**
 * Strict idempotent command for governed Scenario aggregate evidence deletion.
 *
 * @param schemaVersion protocol version
 * @param commandId governance command idempotency identity
 * @param reasonCode stable deletion reason code
 */
public record ScenarioRehearsalPurgeCommand(
        String schemaVersion,
        String commandId,
        String reasonCode
) {
    /** Current protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalPurgeCommand.v1";
    private static final Pattern ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
    private static final Pattern REASON =
            Pattern.compile("RG\\.MIRROR\\.[A-Z0-9_.-]{1,224}");

    /** Enforces bounded command identity and stable governance reason. */
    public ScenarioRehearsalPurgeCommand {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario purge command schema");
        }
        commandId = normalized(commandId);
        if (!ID.matcher(commandId).matches()) {
            throw new IllegalArgumentException(
                    "commandId is invalid");
        }
        reasonCode = normalized(reasonCode);
        if (!REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "reasonCode must be a stable Mirror code");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
