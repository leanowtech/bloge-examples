package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;

import java.util.List;
import java.util.Optional;

/**
 * Store contract for immutable graph version snapshots.
 */
public interface GraphVersionStore {

    /**
     * Creates a new version snapshot.
     *
     * @param version version to persist
     */
    void create(GraphVersion version);

    /**
     * Loads one version by identifier.
     *
     * @param versionId internal version identifier
     * @return matching version when present
     */
    Optional<GraphVersion> get(String versionId);

    /**
     * Loads one version by semantic version string within a definition.
     *
     * @param definitionId owning definition identifier
     * @param version semantic version string
     * @return matching version when present
     */
    Optional<GraphVersion> getByDefinitionAndVersion(String definitionId, String version);

    /**
     * Queries versions for one definition.
     *
     * @param query query filter; must not be {@code null}
     * @return immutable page of versions
     */
    List<GraphVersion> query(GraphVersionQuery query);

    /**
     * Replaces one version snapshot.
     *
     * @param version updated snapshot
     * @param expectedRevision optimistic-lock revision guard
     * @return updated version snapshot
     */
    GraphVersion update(GraphVersion version, long expectedRevision);

    /**
     * Changes a version's lifecycle status.
     *
     * @param versionId version to mutate
     * @param status new status
     * @param expectedRevision optimistic-lock revision guard
     * @return updated version snapshot
     */
    GraphVersion updateStatus(String versionId, GraphVersionStatus status, long expectedRevision);

    /**
     * Returns the most recently published version for the given definition, if any.
     *
     * @param definitionId owning definition identifier
     * @return the latest published version, or empty when none exists
     */
    Optional<GraphVersion> findLatestPublished(String definitionId);
}
