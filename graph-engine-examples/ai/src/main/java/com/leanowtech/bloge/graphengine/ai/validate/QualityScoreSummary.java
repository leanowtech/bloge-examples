package com.leanowtech.bloge.graphengine.ai.validate;

import java.util.Map;

/**
 * Compact projection of the quality benchmark result used by the AI authoring API.
 *
 * @param total total quality score from {@code 0} to {@code 100}
 * @param dimensions per-dimension score breakdown
 * @param parseSuccess whether the source parsed successfully
 * @param productionQuality whether the score crossed the production-quality threshold
 */
public record QualityScoreSummary(
        int total,
        Map<String, Integer> dimensions,
        boolean parseSuccess,
        boolean productionQuality
) {
    public QualityScoreSummary {
        if (total < 0 || total > 100) {
            throw new IllegalArgumentException("total must be between 0 and 100");
        }
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
    }
}
