package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Immutable identity of one durable mirror Session data-plane generation.
 *
 * <p>The generation changes when a new data-plane store is initialized, not when a process
 * restarts or an encryption key rotates. A full database clone or restore deliberately preserves
 * the generation as part of the durable dataset; deployment authority must separately fence
 * active ownership and prevent split brain. Checkpoints pin this value so a valid signature
 * cannot authorize recovery against an independently initialized store that happens to contain a
 * similarly named Session.</p>
 *
 * @param schemaVersion store-generation protocol version
 * @param generationId immutable data-plane identity
 * @param schemaRevision durable Session-store schema revision
 * @param createdAt database-authoritative generation creation time
 * @param fingerprint canonical generation fingerprint
 */
public record MirrorSessionStoreGeneration(
        String schemaVersion,
        String generationId,
        long schemaRevision,
        Instant createdAt,
        String fingerprint
) {
    /** Current store-generation protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionStoreGeneration.v1";
    /** Current durable Session-store schema revision. */
    public static final long CURRENT_SCHEMA_REVISION = 1;
    private static final Pattern GENERATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    /** Validates one payload-free immutable data-plane identity. */
    public MirrorSessionStoreGeneration {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror Session store generation schemaVersion");
        }
        generationId = MirrorStateProtocolSupport.required(
                generationId, "generationId");
        if (!GENERATION_ID.matcher(generationId).matches()) {
            throw new IllegalArgumentException(
                    "generationId contains unsupported characters");
        }
        if (schemaRevision != CURRENT_SCHEMA_REVISION) {
            throw new IllegalArgumentException(
                    "unsupported mirror Session store schema revision");
        }
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        if (Instant.EPOCH.equals(createdAt)) {
            throw new IllegalArgumentException(
                    "store generation createdAt must not be the epoch");
        }
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "store generation fingerprint");
    }

    /**
     * Creates a copy carrying a replacement canonical fingerprint.
     *
     * @return generation copy with the supplied fingerprint
     */
    public MirrorSessionStoreGeneration withFingerprint(String value) {
        return new MirrorSessionStoreGeneration(
                schemaVersion, generationId, schemaRevision, createdAt, value);
    }
}
