package com.leanowtech.bloge.gateway.example;

import java.util.List;
import java.util.Map;

/**
 * Presentation-only visual layout for resource-gateway example scenarios.
 *
 * <p>This record mirrors the graph-engine {@code bloge.visualLayout.v1} JSON
 * contract without adding a dependency from the standalone resource-gateway
 * example to the graph-engine project. The gateway DSL files remain the
 * authoritative source for graph behavior; this layout only gives the browser
 * enough stable geometry and annotations to explain the examples visually.</p>
 *
 * @param schemaVersion versioned layout contract identifier
 * @param rootId gateway graph name
 * @param executionMode execution-mode family, currently {@code GRAPH}
 * @param nodes visual nodes for resource calls, branches, streams, and transforms
 * @param edges visual edges between nodes
 * @param groups optional visual groups such as foreach or parallel fan-out
 * @param viewport initial canvas viewport
 */
public record ExampleVisualLayout(
        String schemaVersion,
        String rootId,
        String executionMode,
        List<Node> nodes,
        List<Edge> edges,
        List<Group> groups,
        Viewport viewport
) {
    /**
     * Current shared layout schema identifier.
     */
    public static final String SCHEMA_VERSION = "bloge.visualLayout.v1";

    /**
     * Creates a layout payload.
     */
    public ExampleVisualLayout {
        schemaVersion = (schemaVersion == null || schemaVersion.isBlank()) ? SCHEMA_VERSION : schemaVersion;
        if (rootId == null || rootId.isBlank()) {
            throw new IllegalArgumentException("rootId must not be blank");
        }
        executionMode = (executionMode == null || executionMode.isBlank()) ? "GRAPH" : executionMode;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        groups = groups == null ? List.of() : List.copyOf(groups);
        viewport = viewport == null ? new Viewport(0, 0, 1) : viewport;
    }

    /**
     * Visual node in a gateway scenario diagram.
     *
     * @param id node identifier from the {@code .bloge} graph
     * @param kind node kind shown by the UI
     * @param operatorRef operator reference when applicable
     * @param label human-readable label
     * @param position canvas position
     * @param size stable node size
     * @param group optional visual group identifier
     * @param annotations small explanatory annotations
     */
    public record Node(
            String id,
            String kind,
            String operatorRef,
            String label,
            Position position,
            Size size,
            String group,
            Map<String, Object> annotations
    ) {
        /**
         * Creates a visual node.
         */
        public Node {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            kind = (kind == null || kind.isBlank()) ? "node" : kind;
            label = (label == null || label.isBlank()) ? id : label;
            position = position == null ? new Position(0, 0) : position;
            size = size == null ? new Size(180, 72) : size;
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }
    }

    /**
     * Visual edge in a gateway scenario diagram.
     *
     * @param id stable edge identifier
     * @param source source node identifier
     * @param target target node identifier
     * @param label optional edge label
     */
    public record Edge(String id, String source, String target, String label) {
        /**
         * Creates a visual edge.
         */
        public Edge {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("source must not be blank");
            }
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("target must not be blank");
            }
            id = (id == null || id.isBlank()) ? source + "->" + target : id;
        }
    }

    /**
     * Optional grouping hint used by the browser diagram.
     *
     * @param id group identifier
     * @param label group label
     * @param kind group kind
     */
    public record Group(String id, String label, String kind) {
        /**
         * Creates a group hint.
         */
        public Group {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            label = (label == null || label.isBlank()) ? id : label;
            kind = (kind == null || kind.isBlank()) ? "group" : kind;
        }
    }

    /**
     * Canvas position.
     *
     * @param x horizontal coordinate
     * @param y vertical coordinate
     */
    public record Position(double x, double y) {
    }

    /**
     * Node size.
     *
     * @param width width in canvas units
     * @param height height in canvas units
     */
    public record Size(double width, double height) {
        /**
         * Creates a node size.
         */
        public Size {
            if (width <= 0) {
                width = 180;
            }
            if (height <= 0) {
                height = 72;
            }
        }
    }

    /**
     * Initial viewport.
     *
     * @param x viewport x offset
     * @param y viewport y offset
     * @param zoom zoom factor
     */
    public record Viewport(double x, double y, double zoom) {
        /**
         * Creates a viewport.
         */
        public Viewport {
            if (zoom <= 0) {
                zoom = 1;
            }
        }
    }
}
