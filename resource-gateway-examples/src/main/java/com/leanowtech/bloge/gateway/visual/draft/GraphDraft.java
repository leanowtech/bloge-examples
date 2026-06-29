package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editable visual graph draft submitted by the canvas.
 *
 * @param schemaVersion draft schema version
 * @param draftId draft id
 * @param revision monotonic revision
 * @param graphName BLOGE graph name
 * @param tenantId default tenant id
 * @param namespace default namespace
 * @param environment authoring environment label
 * @param status draft lifecycle status
 * @param inputSchema graph input schema
 * @param nodes visual nodes
 * @param edges visual edges
 * @param visualLayout opaque visual layout model
 * @param output selected graph output
 * @param operatorFingerprints operator fingerprint snapshot keyed by node id
 */
public record GraphDraft(
        String schemaVersion,
        String draftId,
        long revision,
        String graphName,
        String tenantId,
        String namespace,
        String environment,
        String status,
        SchemaEnvelope inputSchema,
        List<DraftNode> nodes,
        List<DraftEdge> edges,
        Map<String, Object> visualLayout,
        OutputSelection output,
        Map<String, String> operatorFingerprints
) {
    /**
     * Creates a graph draft.
     */
    public GraphDraft {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "bloge.visualGraphDraft.v1"
                : schemaVersion;
        draftId = draftId == null ? "" : draftId;
        revision = Math.max(0, revision);
        graphName = graphName == null || graphName.isBlank() ? "visualGraph" : sanitizeIdentifier(graphName);
        tenantId = tenantId == null || tenantId.isBlank() ? "demo-tenant" : tenantId;
        namespace = namespace == null || namespace.isBlank() ? "local" : namespace;
        environment = environment == null || environment.isBlank() ? "local" : environment;
        status = status == null || status.isBlank() ? "DRAFT" : status;
        inputSchema = inputSchema == null ? SchemaEnvelope.opaque() : inputSchema;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        visualLayout = visualLayout == null ? Map.of() : new LinkedHashMap<>(visualLayout);
        output = output == null ? OutputSelection.empty() : output;
        operatorFingerprints = operatorFingerprints == null ? Map.of() : new LinkedHashMap<>(operatorFingerprints);
    }

    /**
     * Backward-compatible constructor for drafts created before operator fingerprint snapshots existed.
     */
    public GraphDraft(String schemaVersion,
                      String draftId,
                      long revision,
                      String graphName,
                      String tenantId,
                      String namespace,
                      String environment,
                      String status,
                      SchemaEnvelope inputSchema,
                      List<DraftNode> nodes,
                      List<DraftEdge> edges,
                      Map<String, Object> visualLayout,
                      OutputSelection output) {
        this(schemaVersion, draftId, revision, graphName, tenantId, namespace, environment, status,
                inputSchema, nodes, edges, visualLayout, output, Map.of());
    }

    /**
     * Returns a copy with repository identity values.
     *
     * @param newDraftId draft id
     * @param newRevision revision
     * @return updated draft
     */
    public GraphDraft withIdentity(String newDraftId, long newRevision) {
        return new GraphDraft(schemaVersion, newDraftId, newRevision, graphName, tenantId, namespace,
                environment, status, inputSchema, nodes, edges, visualLayout, output, operatorFingerprints);
    }

    /**
     * Returns a copy carrying the operator fingerprints observed when the draft was saved/submitted.
     *
     * @param fingerprints operator fingerprints keyed by node id
     * @return updated draft
     */
    public GraphDraft withOperatorFingerprints(Map<String, String> fingerprints) {
        return new GraphDraft(schemaVersion, draftId, revision, graphName, tenantId, namespace,
                environment, status, inputSchema, nodes, edges, visualLayout, output, fingerprints);
    }

    /**
     * A visual graph node.
     *
     * @param id node id
     * @param operatorRef visual operator reference
     * @param label display label
     * @param inputs input bindings keyed by stable binding key
     * @param config operator config
     * @param position canvas position
     */
    public record DraftNode(
            String id,
            String operatorRef,
            String label,
            Map<String, Binding> inputs,
            Map<String, Object> config,
            Position position
    ) {
        /**
         * Creates a draft node.
         */
        public DraftNode {
            id = sanitizeIdentifier(id == null || id.isBlank() ? "node" : id);
            operatorRef = operatorRef == null ? "" : operatorRef;
            label = label == null || label.isBlank() ? id : label;
            inputs = inputs == null ? Map.of() : new LinkedHashMap<>(inputs);
            config = config == null ? Map.of() : new LinkedHashMap<>(config);
            position = position == null ? new Position(0, 0) : position;
        }
    }

    /**
     * Binding value for a node input or transform assignment.
     *
     * @param kind constant, contextPath, nodePath, expression, or objectTemplate
     * @param value literal value for constant bindings
     * @param path context/node path
     * @param nodeId source node id for nodePath bindings
     * @param sourcePort source output port for nodePath bindings
     * @param targetPort target input port for this binding
     * @param targetPath target input path for this binding
     * @param expr raw BLOGE expression
     * @param fields nested fields for objectTemplate bindings
     */
    public record Binding(
            String kind,
            Object value,
            String path,
            String nodeId,
            String sourcePort,
            String targetPort,
            String targetPath,
            String expr,
            Map<String, Binding> fields
    ) {
        /**
         * Creates a binding.
         */
        public Binding {
            kind = kind == null || kind.isBlank() ? "constant" : kind;
            path = path == null ? "" : path;
            nodeId = nodeId == null ? "" : nodeId;
            sourcePort = sourcePort == null ? "" : sourcePort;
            targetPort = targetPort == null ? "" : targetPort;
            targetPath = targetPath == null ? "" : targetPath;
            expr = expr == null ? "" : expr;
            fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
        }

        public static Binding constant(Object value) {
            return new Binding("constant", value, "", "", "", "", "", "", Map.of());
        }

        public static Binding contextPath(String path) {
            return new Binding("contextPath", null, path, "", "", "", "", "", Map.of());
        }

        public static Binding contextPath(String path, String targetPort, String targetPath) {
            return new Binding("contextPath", null, path, "", "", targetPort, targetPath, "", Map.of());
        }

        public static Binding nodePath(String nodeId, String path) {
            return new Binding("nodePath", null, path, nodeId, "", "", "", "", Map.of());
        }

        public static Binding nodePath(String nodeId, String sourcePort, String path) {
            return new Binding("nodePath", null, path, nodeId, sourcePort, "", "", "", Map.of());
        }

        public static Binding nodePath(String nodeId,
                                       String sourcePort,
                                       String path,
                                       String targetPort,
                                       String targetPath) {
            return new Binding("nodePath", null, path, nodeId, sourcePort, targetPort, targetPath, "", Map.of());
        }

        public static Binding expression(String expr) {
            return new Binding("expression", null, "", "", "", "", "", expr, Map.of());
        }
    }

    /**
     * A visual edge.
     *
     * @param id edge id
     * @param kind edge kind
     * @param source source endpoint
     * @param target target endpoint
     */
    public record DraftEdge(
            String id,
            String kind,
            Endpoint source,
            Endpoint target
    ) {
        /**
         * Creates an edge.
         */
        public DraftEdge {
            id = id == null || id.isBlank()
                    ? (source == null ? "" : source.nodeId()) + "->" + (target == null ? "" : target.nodeId())
                    : id;
            kind = kind == null || kind.isBlank() ? "data" : kind;
            source = source == null ? Endpoint.empty() : source;
            target = target == null ? Endpoint.empty() : target;
        }
    }

    /**
     * Edge endpoint.
     *
     * @param nodeId node id
     * @param port port name
     * @param path optional data path
     */
    public record Endpoint(String nodeId, String port, String path) {
        /**
         * Creates an endpoint.
         */
        public Endpoint {
            nodeId = nodeId == null ? "" : nodeId;
            port = port == null ? "" : port;
            path = path == null ? "" : path;
        }

        public static Endpoint empty() {
            return new Endpoint("", "", "");
        }
    }

    /**
     * Canvas position.
     *
     * @param x x coordinate
     * @param y y coordinate
     */
    public record Position(double x, double y) {
    }

    /**
     * Output selector.
     *
     * @param nodeId output node id
     * @param path optional output path
     */
    public record OutputSelection(String nodeId, String path) {
        /**
         * Creates an output selector.
         */
        public OutputSelection {
            nodeId = nodeId == null ? "" : nodeId;
            path = path == null ? "" : path;
        }

        public static OutputSelection empty() {
            return new OutputSelection("", "");
        }
    }

    private static String sanitizeIdentifier(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank()) {
            return "node";
        }
        String sanitized = candidate.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isBlank()) {
            return "node";
        }
        if (Character.isDigit(sanitized.charAt(0))) {
            return "_" + sanitized;
        }
        return sanitized;
    }
}
