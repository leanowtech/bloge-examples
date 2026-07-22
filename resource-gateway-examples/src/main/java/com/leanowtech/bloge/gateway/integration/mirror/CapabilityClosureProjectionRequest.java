package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.time.Instant;
import java.util.Objects;

/**
 * Versioned request for projecting a portable visual graph into a draft capability closure.
 *
 * <p>Enterprise scope, purpose, ownership, region policy, and lifecycle are intentionally absent:
 * the authenticated integration identity supplies them. The caller controls only the immutable
 * capability revision, deterministic creation time, and classification that its clearance permits.</p>
 *
 * @param schemaVersion projection request protocol version
 * @param draft portable or persisted visual graph draft
 * @param revision positive capability snapshot revision assigned by the caller's registry workflow
 * @param createdAt deterministic artifact creation time
 * @param classification maximum data classification carried by the projected contract
 */
public record CapabilityClosureProjectionRequest(
        String schemaVersion,
        GraphDraft draft,
        long revision,
        Instant createdAt,
        CapabilityContract.DataClassification classification
) {
    /** Current request protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityClosureProjectionRequest.v1";

    /** Validates the immutable projection coordinates. */
    public CapabilityClosureProjectionRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported capability closure projection request schemaVersion");
        }
        draft = Objects.requireNonNull(draft, "draft");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        classification = Objects.requireNonNull(classification, "classification");
    }
}
