package com.leanowtech.bloge.graphengine.service;

import java.util.List;
import java.util.Objects;

/**
 * Product-layer result of comparing two versions of the same graph definition.
 *
 * <p>Contains identifying summaries for both sides, a source-equality flag,
 * an optional unified-diff view of the DSL source, and a structural metadata
 * comparison derived from the compiled version metadata.</p>
 *
 * @param left          identifying summary of the left (older) version
 * @param right         identifying summary of the right (newer) version
 * @param sourceEqual   {@code true} when both versions share the same content hash
 * @param unifiedDiff   line-oriented unified diff of the DSL source; empty when sources are equal
 * @param metadataDiff  structural comparison of compiled metadata
 */
public record GraphVersionDiff(
        VersionSummary left,
        VersionSummary right,
        boolean sourceEqual,
        List<String> unifiedDiff,
        MetadataDiff metadataDiff
) {
    public GraphVersionDiff {
        left = Objects.requireNonNull(left, "left");
        right = Objects.requireNonNull(right, "right");
        unifiedDiff = unifiedDiff == null ? List.of() : List.copyOf(unifiedDiff);
        metadataDiff = Objects.requireNonNull(metadataDiff, "metadataDiff");
    }
}
