package com.leanowtech.bloge.graphengine.model;

import java.util.Objects;

/**
 * Diagram payload for one immutable version.
 *
 * @param versionId internal version identifier
 * @param version semantic version string
 * @param visualLayout stored visual layout payload as-is
 */
public record GraphVersionDiagram(
        String versionId,
        String version,
        String visualLayout
) {
    public GraphVersionDiagram {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(version, "version");
    }
}
