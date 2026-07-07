package com.leanowtech.bloge.gateway.visual.importer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source map from visual ids and JSON pointers back to DSL locations.
 *
 * @param nodes node id to source span
 * @param edges edge id to source span
 * @param bindings binding JSON pointer to source span
 */
public record DslSourceMap(
        Map<String, DslSourceSpan> nodes,
        Map<String, DslSourceSpan> edges,
        Map<String, DslSourceSpan> bindings
) {
    /**
     * Creates a source map.
     */
    public DslSourceMap {
        nodes = nodes == null ? Map.of() : new LinkedHashMap<>(nodes);
        edges = edges == null ? Map.of() : new LinkedHashMap<>(edges);
        bindings = bindings == null ? Map.of() : new LinkedHashMap<>(bindings);
    }

    public static DslSourceMap empty() {
        return new DslSourceMap(Map.of(), Map.of(), Map.of());
    }
}
