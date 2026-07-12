package com.leanowtech.bloge.gateway.integration;

/** Idempotent governance command for legal hold, release, or explicit payload purge. */
public record PayloadLifecycleCommand(
        String schemaVersion,
        String requestId,
        String holdId,
        String reason
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.payloadLifecycleCommand.v1";

    public PayloadLifecycleCommand {
        schemaVersion = normalize(schemaVersion, SCHEMA_VERSION);
        requestId = normalize(requestId, "");
        holdId = normalize(holdId, "");
        reason = normalize(reason, "");
        if (requestId.length() > 128 || holdId.length() > 128 || reason.length() > 1024) {
            throw new IllegalArgumentException("Payload lifecycle command fields exceed their bounded size");
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
