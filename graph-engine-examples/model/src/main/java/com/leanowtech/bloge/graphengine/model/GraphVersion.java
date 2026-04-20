package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.registry.GraphMigrationPolicy;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable product-layer version snapshot of one graph definition.
 *
 * @param versionId internal version identifier
 * @param definitionId owning definition identifier
 * @param version semantic version string
 * @param contentHash hash of the version's source payload
 * @param dslSource authoritative `.bloge` source
 * @param visualLayout optional visual layout JSON
 * @param metadata derived metadata for the version
 * @param compiledArtifactRef optional compiled artifact reference
 * @param migrationPolicy resume-time migration policy
 * @param status version lifecycle status
 * @param revision optimistic-lock revision
 * @param publishedAt timestamp when the version was published
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record GraphVersion(
        String versionId,
        String definitionId,
        String version,
        String contentHash,
        String dslSource,
        String visualLayout,
        GraphVersionMetadata metadata,
        String compiledArtifactRef,
        GraphMigrationPolicy migrationPolicy,
        GraphVersionStatus status,
        long revision,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public GraphVersion {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId must not be blank");
        }
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
        if (dslSource == null || dslSource.isBlank()) {
            throw new IllegalArgumentException("dslSource must not be blank");
        }
        metadata = Objects.requireNonNullElse(metadata, new GraphVersionMetadata(null, null, null, null, null, null, null));
        migrationPolicy = Objects.requireNonNullElse(migrationPolicy, GraphMigrationPolicy.PIN_VERSION);
        status = Objects.requireNonNullElse(status, GraphVersionStatus.DRAFT);
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        createdAt = createdAt == null ? SystemTimeSource.INSTANCE.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
