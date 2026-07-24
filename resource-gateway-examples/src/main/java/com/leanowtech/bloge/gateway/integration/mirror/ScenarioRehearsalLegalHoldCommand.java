package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.regex.Pattern;

/**
 * Strict idempotent command for placing or releasing one Scenario aggregate legal hold.
 *
 * @param schemaVersion protocol version
 * @param commandId governance command idempotency identity
 * @param holdId independent legal-hold identity
 * @param reasonCode stable governance reason code
 */
public record ScenarioRehearsalLegalHoldCommand(
        String schemaVersion,
        String commandId,
        String holdId,
        String reasonCode
) {
    /** Current protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalLegalHoldCommand.v1";
    private static final Pattern ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
    private static final Pattern REASON =
            Pattern.compile("RG\\.MIRROR\\.[A-Z0-9_.-]{1,224}");

    /** Enforces bounded identities and stable governance reasons. */
    public ScenarioRehearsalLegalHoldCommand {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario legal-hold command schema");
        }
        commandId = identifier(commandId, "commandId");
        holdId = identifier(holdId, "holdId");
        reasonCode = normalized(reasonCode);
        if (!REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "reasonCode must be a stable Mirror code");
        }
    }

    private static String identifier(
            String value, String field) {
        String normalized = normalized(value);
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
