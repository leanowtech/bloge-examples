package com.leanowtech.bloge.graphengine.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned browser-facing graph layout payload used by example UX screens.
 *
 * <p>The layout is intentionally presentation-only. BLOGE DSL and compiled
 * metadata remain the source of truth for nodes, edges, execution semantics,
 * schemas, retries, waits, and branching behavior. This record stores stable
 * positions, labels, grouping hints, and lightweight annotations so a UI can
 * render a graph without inventing a second definition model.</p>
 *
 * @param schemaVersion layout schema identifier, currently {@value #SCHEMA_VERSION}
 * @param rootId graph/session/state-machine root identifier
 * @param executionMode execution-mode family represented by this layout
 * @param nodes visual nodes addressed by DSL node, phase, or state identifiers
 * @param edges visual edges derived from compiled graph or lifecycle transitions
 * @param groups optional visual grouping hints
 * @param viewport initial canvas viewport
 */
public record VisualLayout(
        String schemaVersion,
        String rootId,
        GraphExecutionMode executionMode,
        List<Node> nodes,
        List<Edge> edges,
        List<Group> groups,
        Viewport viewport
) {
    /**
     * Current layout schema identifier.
     */
    public static final String SCHEMA_VERSION = "bloge.visualLayout.v1";

    /**
     * Creates a layout payload.
     */
    public VisualLayout {
        schemaVersion = (schemaVersion == null || schemaVersion.isBlank()) ? SCHEMA_VERSION : schemaVersion;
        if (rootId == null || rootId.isBlank()) {
            throw new IllegalArgumentException("rootId must not be blank");
        }
        executionMode = Objects.requireNonNullElse(executionMode, GraphExecutionMode.GRAPH);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        groups = groups == null ? List.of() : List.copyOf(groups);
        viewport = viewport == null ? new Viewport(0, 0, 1) : viewport;
    }

    /**
     * Visual node for one executable unit.
     *
     * @param id stable DSL node, session phase, or state identifier
     * @param kind presentation kind such as {@code extension}, {@code transform},
     *             {@code stream}, {@code phase}, or {@code state}
     * @param operatorRef operator reference when the node maps to a BLOGE operator
     * @param label human-readable label for the node
     * @param position canvas position
     * @param size stable node size
     * @param group optional visual group identifier
     * @param annotations small presentation annotations such as timeout or retry
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
     * Visual edge between two executable units.
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
     * Visual grouping hint for related nodes.
     *
     * @param id stable group identifier
     * @param label human-readable group label
     * @param kind group kind such as {@code parallel}, {@code branch}, or {@code foreach}
     */
    public record Group(String id, String label, String kind) {
        /**
         * Creates a visual group.
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
     * @param x horizontal canvas coordinate
     * @param y vertical canvas coordinate
     */
    public record Position(double x, double y) {
    }

    /**
     * Stable node size used by the browser renderer.
     *
     * @param width node width in canvas units
     * @param height node height in canvas units
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
     * Initial viewport for a graph canvas.
     *
     * @param x viewport x offset
     * @param y viewport y offset
     * @param zoom viewport zoom factor
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
