package com.leanowtech.bloge.gateway.example;

import java.util.Map;

/**
 * Browser-selectable sample input for one showcase scenario.
 *
 * @param label user-facing preset label
 * @param values input values applied to the scenario form
 * @param expected optional expected outcome metadata, such as a rule id
 */
public record GatewayExamplePreset(
        String label,
        Map<String, Object> values,
        Map<String, Object> expected
) {
    /**
     * Creates a sample preset.
     */
    public GatewayExamplePreset {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        values = values == null ? Map.of() : Map.copyOf(values);
        expected = expected == null ? Map.of() : Map.copyOf(expected);
    }
}
