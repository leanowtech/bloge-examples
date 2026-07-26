package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Protected append response carrying the exact signed revision and current durable head.
 *
 * @param schemaVersion exact response version
 * @param observation admitted signed immutable revision
 * @param entry current durable lineage head after admission
 * @param idempotentReplay whether no new revision was committed
 */
public record AuthoritativeOutcomeInboxAdmission(
        String schemaVersion,
        AuthoritativeOutcomeObservation observation,
        AuthoritativeOutcomeInboxEntry entry,
        boolean idempotentReplay
) {
    /** Exact protected admission response version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeInboxAdmission.v1";

    /** Enforces admitted observation and head lineage correspondence. */
    public AuthoritativeOutcomeInboxAdmission {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported outcome inbox admission schemaVersion");
        }
        observation = Objects.requireNonNull(
                observation, "observation");
        entry = Objects.requireNonNull(entry, "entry");
        if (!observation.scope().equals(entry.scope())
                || !observation.observationId().equals(
                entry.observationId())
                || observation.revision()
                > entry.currentRevision()) {
            throw new IllegalArgumentException(
                    "outcome inbox admission lineage is inconsistent");
        }
    }
}
