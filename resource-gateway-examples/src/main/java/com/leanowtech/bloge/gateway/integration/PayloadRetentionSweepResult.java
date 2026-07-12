package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;

/** Result of one bounded policy-driven expired-payload sweep. */
public record PayloadRetentionSweepResult(String schemaVersion, Instant observedAt, int purgedCount) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.payloadRetentionSweepResult.v1";

    public PayloadRetentionSweepResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        observedAt = observedAt == null ? Instant.now() : observedAt;
        purgedCount = Math.max(0, purgedCount);
    }
}
