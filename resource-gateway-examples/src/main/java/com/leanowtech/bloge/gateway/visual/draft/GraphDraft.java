package com.leanowtech.bloge.gateway.visual.draft;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
 * @param outputSchema graph output schema exposed to external integrators
 * @param nodes visual nodes
 * @param edges visual edges
 * @param visualLayout opaque visual layout model
 * @param nodeFixtures non-semantic per-node simulation fixtures used only by the mock-run path
 * @param output selected graph output
 * @param operatorFingerprints operator fingerprint snapshot keyed by node id
 * @param operatorSnapshots operator definition snapshot keyed by node id
 * @param revisionMetadata audit metadata captured for this revision snapshot
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
        SchemaEnvelope outputSchema,
        List<DraftNode> nodes,
        List<DraftEdge> edges,
        Map<String, Object> visualLayout,
        Map<String, NodeFixture> nodeFixtures,
        OutputSelection output,
        Map<String, String> operatorFingerprints,
        Map<String, OperatorDefinition> operatorSnapshots,
        RevisionMetadata revisionMetadata
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphDraft.v1";
    public static final String STATUS_DRAFT = "DRAFT";

    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            STATUS_DRAFT
    );

    /**
     * Creates a graph draft.
     */
    public GraphDraft {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion;
        draftId = draftId == null ? "" : draftId;
        revision = Math.max(0, revision);
        graphName = graphName == null || graphName.isBlank() ? "visualGraph" : sanitizeIdentifier(graphName);
        tenantId = tenantId == null || tenantId.isBlank() ? "demo-tenant" : tenantId;
        namespace = namespace == null || namespace.isBlank() ? "local" : namespace;
        environment = environment == null || environment.isBlank() ? "local" : environment;
        status = normalizeStatus(status);
        inputSchema = inputSchema == null ? SchemaEnvelope.opaque() : inputSchema;
        outputSchema = outputSchema == null ? outputSchemaFromVisualLayout(visualLayout) : outputSchema;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        visualLayout = visualLayout == null ? Map.of() : new LinkedHashMap<>(visualLayout);
        nodeFixtures = normalizeNodeFixtures(nodeFixtures);
        output = output == null ? OutputSelection.empty() : output;
        operatorFingerprints = operatorFingerprints == null ? Map.of() : new LinkedHashMap<>(operatorFingerprints);
        operatorSnapshots = operatorSnapshots == null ? Map.of() : new LinkedHashMap<>(operatorSnapshots);
        revisionMetadata = revisionMetadata == null ? RevisionMetadata.empty() : revisionMetadata;
    }

    /**
     * @param status raw lifecycle status
     * @return true when the status is part of the supported draft lifecycle contract
     */
    public static boolean isSupportedStatus(String status) {
        return SUPPORTED_STATUSES.contains(normalizeStatus(status));
    }

    /**
     * Backward-compatible constructor for drafts created before graph output schema became a first-class field.
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
                      Map<String, NodeFixture> nodeFixtures,
                      OutputSelection output,
                      Map<String, String> operatorFingerprints,
                      Map<String, OperatorDefinition> operatorSnapshots,
                      RevisionMetadata revisionMetadata) {
        this(schemaVersion, draftId, revision, graphName, tenantId, namespace, environment, status,
                inputSchema, outputSchemaFromVisualLayout(visualLayout), nodes, edges, visualLayout, nodeFixtures,
                output, operatorFingerprints, operatorSnapshots, revisionMetadata);
    }

    /**
     * Backward-compatible constructor for drafts created before simulation fixtures were persisted.
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
                      OutputSelection output,
                      Map<String, String> operatorFingerprints,
                      Map<String, OperatorDefinition> operatorSnapshots,
                      RevisionMetadata revisionMetadata) {
        this(schemaVersion, draftId, revision, graphName, tenantId, namespace, environment, status,
                inputSchema, nodes, edges, visualLayout, Map.of(), output, operatorFingerprints,
                operatorSnapshots, revisionMetadata);
    }

    /**
     * Backward-compatible constructor for drafts created before operator definition snapshots existed.
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
                      OutputSelection output,
                      Map<String, String> operatorFingerprints,
                      RevisionMetadata revisionMetadata) {
        this(schemaVersion, draftId, revision, graphName, tenantId, namespace, environment, status,
                inputSchema, nodes, edges, visualLayout, Map.of(), output, operatorFingerprints, Map.of(),
                revisionMetadata);
    }

    /**
     * Backward-compatible constructor for drafts created before revision audit metadata existed.
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
                      OutputSelection output,
                      Map<String, String> operatorFingerprints) {
        this(schemaVersion, draftId, revision, graphName, tenantId, namespace, environment, status,
                inputSchema, nodes, edges, visualLayout, Map.of(), output, operatorFingerprints, Map.of(),
                RevisionMetadata.empty());
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
                inputSchema, nodes, edges, visualLayout, Map.of(), output, Map.of(), Map.of(),
                RevisionMetadata.empty());
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
                environment, status, inputSchema, outputSchema, nodes, edges, visualLayout, nodeFixtures, output,
                operatorFingerprints,
                operatorSnapshots, revisionMetadata);
    }

    /**
     * Returns a copy carrying the operator fingerprints observed when the draft was saved/submitted.
     *
     * @param fingerprints operator fingerprints keyed by node id
     * @return updated draft
     */
    public GraphDraft withOperatorFingerprints(Map<String, String> fingerprints) {
        return new GraphDraft(schemaVersion, draftId, revision, graphName, tenantId, namespace,
                environment, status, inputSchema, outputSchema, nodes, edges, visualLayout, nodeFixtures, output,
                fingerprints, operatorSnapshots, revisionMetadata);
    }

    /**
     * Returns a copy carrying the operator definition snapshots observed when the draft was saved/submitted.
     *
     * @param snapshots operator definitions keyed by node id
     * @return updated draft
     */
    public GraphDraft withOperatorSnapshots(Map<String, OperatorDefinition> snapshots) {
        return new GraphDraft(schemaVersion, draftId, revision, graphName, tenantId, namespace,
                environment, status, inputSchema, outputSchema, nodes, edges, visualLayout, nodeFixtures, output,
                operatorFingerprints,
                snapshots, revisionMetadata);
    }

    /**
     * Returns a copy carrying both operator fingerprint and definition snapshots.
     *
     * @param fingerprints operator fingerprints keyed by node id
     * @param snapshots operator definitions keyed by node id
     * @return updated draft
     */
    public GraphDraft withOperatorSnapshotState(Map<String, String> fingerprints,
                                                Map<String, OperatorDefinition> snapshots) {
        return new GraphDraft(schemaVersion, draftId, revision, graphName, tenantId, namespace,
                environment, status, inputSchema, outputSchema, nodes, edges, visualLayout, nodeFixtures, output,
                fingerprints, snapshots, revisionMetadata);
    }

    /**
     * Returns a copy with author-pinned simulation fixtures.
     *
     * <p>Fixtures are intentionally non-semantic: they are ignored by DSL generation, graph diffing,
     * and publish-time executable behavior. They travel with a draft so authors can repeat the same
     * mock-run without re-entering sample outputs.</p>
     *
     * @param fixtures per-node simulation fixtures keyed by node id
     * @return updated draft
     */
    public GraphDraft withNodeFixtures(Map<String, NodeFixture> fixtures) {
        return new GraphDraft(schemaVersion, draftId, revision, graphName, tenantId, namespace,
                environment, status, inputSchema, outputSchema, nodes, edges, visualLayout, fixtures, output,
                operatorFingerprints, operatorSnapshots, revisionMetadata);
    }

    /**
     * Returns a copy with updated visual-only layout metadata.
     *
     * @param nextVisualLayout visual layout metadata
     * @return updated draft
     */
    public GraphDraft withVisualLayout(Map<String, Object> nextVisualLayout) {
        return new GraphDraft(schemaVersion, draftId, revision, graphName, tenantId, namespace,
                environment, status, inputSchema, outputSchema, nodes, edges, nextVisualLayout, nodeFixtures, output,
                operatorFingerprints, operatorSnapshots, revisionMetadata);
    }

    /**
     * Returns a copy with updated graph output schema.
     *
     * @param nextOutputSchema graph output schema
     * @return updated draft
     */
    public GraphDraft withOutputSchema(SchemaEnvelope nextOutputSchema) {
        return new GraphDraft(schemaVersion, draftId, revision, graphName, tenantId, namespace,
                environment, status, inputSchema, nextOutputSchema, nodes, edges, visualLayout, nodeFixtures, output,
                operatorFingerprints, operatorSnapshots, revisionMetadata);
    }

    /**
     * Returns a copy with revision audit metadata.
     *
     * @param metadata revision metadata
     * @return updated draft
     */
    public GraphDraft withRevisionMetadata(RevisionMetadata metadata) {
        return new GraphDraft(schemaVersion, draftId, revision, graphName, tenantId, namespace,
                environment, status, inputSchema, outputSchema, nodes, edges, visualLayout, nodeFixtures, output,
                operatorFingerprints,
                operatorSnapshots, metadata);
    }

    private static SchemaEnvelope outputSchemaFromVisualLayout(Map<String, Object> visualLayout) {
        if (visualLayout == null || visualLayout.isEmpty()) {
            return SchemaEnvelope.opaque();
        }
        Object rawContract = visualLayout.get("graphContract");
        if (!(rawContract instanceof Map<?, ?> contract)) {
            return SchemaEnvelope.opaque();
        }
        return schemaEnvelopeFromObject(contract.get("outputSchema"));
    }

    private static SchemaEnvelope schemaEnvelopeFromObject(Object value) {
        if (value instanceof SchemaEnvelope envelope) {
            return envelope;
        }
        Map<String, Object> map = objectMap(value);
        if (map.isEmpty() || !map.containsKey("schema")) {
            return SchemaEnvelope.opaque();
        }
        return new SchemaEnvelope(stringValue(map.get("format")), stringValue(map.get("version")),
                objectMap(map.get("schema")));
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        rawMap.forEach((key, item) -> map.put(String.valueOf(key), item));
        return map;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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
     * @param targetUnionBranch explicit target oneOf/anyOf branch selected by the author
     * @param targetUnionBranches explicit nested target oneOf/anyOf branches keyed by target path
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
            Map<String, Binding> fields,
            UnionBranchSelection targetUnionBranch,
            Map<String, UnionBranchSelection> targetUnionBranches
    ) {
        /**
         * Creates a binding.
         */
        public Binding {
            kind = canonicalBindingKind(kind);
            path = path == null ? "" : path;
            nodeId = nodeId == null ? "" : nodeId;
            sourcePort = sourcePort == null ? "" : sourcePort;
            targetPort = targetPort == null ? "" : targetPort;
            targetPath = targetPath == null ? "" : targetPath;
            expr = expr == null ? "" : expr;
            fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
            targetUnionBranch = targetUnionBranch == null ? UnionBranchSelection.empty() : targetUnionBranch;
            targetUnionBranches = normalizeUnionBranchSelections(targetUnionBranches);
        }

        /**
         * Backward-compatible constructor for bindings created before nested union branch selection existed.
         */
        public Binding(String kind,
                       Object value,
                       String path,
                       String nodeId,
                       String sourcePort,
                       String targetPort,
                       String targetPath,
                       String expr,
                       Map<String, Binding> fields,
                       UnionBranchSelection targetUnionBranch) {
            this(kind, value, path, nodeId, sourcePort, targetPort, targetPath, expr, fields,
                    targetUnionBranch, Map.of());
        }

        /**
         * Backward-compatible constructor for bindings created before explicit union branch selection existed.
         */
        public Binding(String kind,
                       Object value,
                       String path,
                       String nodeId,
                       String sourcePort,
                       String targetPort,
                       String targetPath,
                       String expr,
                       Map<String, Binding> fields) {
            this(kind, value, path, nodeId, sourcePort, targetPort, targetPath, expr, fields,
                    UnionBranchSelection.empty(), Map.of());
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

        private static String canonicalBindingKind(String value) {
            if (value == null || value.isBlank()) {
                return "constant";
            }
            String trimmed = value.trim();
            return switch (trimmed.toLowerCase(Locale.ROOT)) {
                case "constant" -> "constant";
                case "contextpath" -> "contextPath";
                case "nodepath" -> "nodePath";
                case "expression" -> "expression";
                case "objecttemplate" -> "objectTemplate";
                default -> trimmed;
            };
        }
    }

    private static Map<String, UnionBranchSelection> normalizeUnionBranchSelections(
            Map<String, UnionBranchSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            return Map.of();
        }
        Map<String, UnionBranchSelection> normalized = new LinkedHashMap<>();
        selections.forEach((path, selection) -> {
            UnionBranchSelection value = selection == null ? UnionBranchSelection.empty() : selection;
            if (value.selected()) {
                normalized.put(path == null ? "" : path.trim(), value);
            }
        });
        return normalized;
    }

    private static Map<String, NodeFixture> normalizeNodeFixtures(Map<String, NodeFixture> fixtures) {
        if (fixtures == null || fixtures.isEmpty()) {
            return Map.of();
        }
        Map<String, NodeFixture> normalized = new LinkedHashMap<>();
        fixtures.forEach((nodeId, fixture) -> {
            if (nodeId != null && !nodeId.isBlank() && fixture != null) {
                normalized.put(sanitizeIdentifier(nodeId), fixture);
            }
        });
        return normalized;
    }

    /**
     * Explicit oneOf/anyOf branch selected for a binding target.
     *
     * @param keyword JSON Schema union keyword, oneOf or anyOf
     * @param index zero-based union branch index
     */
    public record UnionBranchSelection(String keyword, int index) {
        /**
         * Creates a union branch selection.
         */
        public UnionBranchSelection {
            keyword = keyword == null ? "" : keyword.trim();
            index = Math.max(-1, index);
        }

        public static UnionBranchSelection empty() {
            return new UnionBranchSelection("", -1);
        }

        public boolean selected() {
            return !keyword.isBlank() || index >= 0;
        }
    }

    /**
     * A visual edge.
     *
     * @param id edge id
     * @param kind edge kind
     * @param source source endpoint
     * @param target target endpoint
     * @param condition route condition for control-flow edges
     */
    public record DraftEdge(
            String id,
            String kind,
            Endpoint source,
            Endpoint target,
            String condition
    ) {
        /**
         * Creates an edge.
         */
        public DraftEdge {
            id = id == null || id.isBlank()
                    ? (source == null ? "" : source.nodeId()) + "->" + (target == null ? "" : target.nodeId())
                    : id;
            kind = canonicalEdgeKind(kind);
            source = source == null ? Endpoint.empty() : source;
            target = target == null ? Endpoint.empty() : target;
            condition = condition == null ? "" : condition.trim();
            if ("dependency".equals(kind) || "route".equals(kind)) {
                source = new Endpoint(source.nodeId(), "", "");
                target = new Endpoint(target.nodeId(), "", "");
            }
        }

        /**
         * Backward-compatible constructor for drafts created before route edge conditions existed.
         */
        public DraftEdge(String id, String kind, Endpoint source, Endpoint target) {
            this(id, kind, source, target, "");
        }

        private static String canonicalEdgeKind(String value) {
            if (value == null || value.isBlank()) {
                return "data";
            }
            String trimmed = value.trim();
            return switch (trimmed.toLowerCase(Locale.ROOT)) {
                case "data" -> "data";
                case "dependency", "dependson", "depends_on" -> "dependency";
                case "route", "branch" -> "route";
                default -> trimmed;
            };
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
     * Author-pinned simulation fixture for one node during visual graph simulation.
     *
     * <p>The fixture belongs to the authoring draft, not to the executable graph contract. Simulation
     * injects {@link #output()} when the node is mocked and can assert {@link #expectedInput()} after
     * the stand-in observes its runtime input. Normal run, compile, publication DSL, fingerprinting,
     * and action-readiness checks ignore both values, while raw-secret hygiene still scans them before
     * persistence or validation succeeds. A governed reference carries only the exact server-owned
     * fixture coordinate so a saved draft can restore reuse state without copying protected material.</p>
     *
     * @param output value injected as the node's simulated output; may be {@code null}
     * @param expectedInput optional input payload expected by the node during simulation; {@code null}
     *                      means no input assertion is evaluated
     * @param governedRef optional payload-free coordinate of an ACTIVE governed fixture
     * @param resourceFidelity requested resource evidence boundary; omitted values retain the
     *                         historical output-level behavior
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeFixture(
            Object output,
            Object expectedInput,
            GovernedFixtureRef governedRef,
            ResourceFidelity resourceFidelity
    ) {
        /** Resource evidence boundaries transported with a persisted fixture. */
        public enum ResourceFidelity { OUTPUT_LEVEL, PROTOCOL_DERIVED, TRANSPORT_LEVEL }

        /**
         * Backward-compatible constructor for output-only pins.
         *
         * @param output value injected as the node's simulated output; may be {@code null}
         */
        public NodeFixture(Object output) {
            this(output, null, null, null);
        }

        /**
         * Backward-compatible constructor for output and input assertion fixtures.
         *
         * @param output value injected as the node's simulated output; may be {@code null}
         * @param expectedInput optional input assertion; may be {@code null}
         */
        public NodeFixture(Object output, Object expectedInput) {
            this(output, expectedInput, null, null);
        }

        /** Normalizes omitted evidence metadata while preserving legacy fixture constructors. */
        public NodeFixture {
            resourceFidelity = resourceFidelity == null ? ResourceFidelity.OUTPUT_LEVEL : resourceFidelity;
        }
    }

    /**
     * Payload-free persisted identity of the governed fixture bound to a node.
     *
     * @param fixtureAssetId exact governance asset id
     * @param revision exact ACTIVE revision used by simulation
     * @param schemaFingerprint exact output-schema fingerprint at bind time
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GovernedFixtureRef(String fixtureAssetId, long revision, String schemaFingerprint) {
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

        /**
         * Creates an exact reference and rejects a coordinate that could silently bind different material.
         */
        public GovernedFixtureRef {
            fixtureAssetId = required(fixtureAssetId, "fixtureAssetId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            schemaFingerprint = required(schemaFingerprint, "schemaFingerprint");
            if (!FINGERPRINT.matcher(schemaFingerprint).matches()) {
                throw new IllegalArgumentException("schemaFingerprint must be an exact sha256 fingerprint");
            }
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
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

    /**
     * Audit metadata captured with each stored draft revision.
     *
     * @param createdAt first stored revision timestamp
     * @param createdBy first authoring actor
     * @param updatedAt current revision timestamp
     * @param updatedBy current revision actor
     * @param changeSource source system or UI surface that produced this revision
     * @param changeSummary human-readable change summary
     * @param changedPaths JSON pointer paths touched by this revision when known
     * @param reason operator-facing reason for audit and migration review
     */
    public record RevisionMetadata(
            String createdAt,
            String createdBy,
            String updatedAt,
            String updatedBy,
            String changeSource,
            String changeSummary,
            List<String> changedPaths,
            String reason
    ) {
        /**
         * Creates revision metadata.
         */
        public RevisionMetadata {
            createdAt = createdAt == null ? "" : createdAt;
            createdBy = createdBy == null ? "" : createdBy;
            updatedAt = updatedAt == null ? "" : updatedAt;
            updatedBy = updatedBy == null ? "" : updatedBy;
            changeSource = changeSource == null ? "" : changeSource;
            changeSummary = changeSummary == null ? "" : changeSummary;
            changedPaths = changedPaths == null ? List.of() : changedPaths.stream()
                    .filter(path -> path != null && !path.isBlank())
                    .distinct()
                    .toList();
            reason = reason == null ? "" : reason.trim();
        }

        /**
         * Backward-compatible constructor for revision metadata created before reason was first-class.
         */
        public RevisionMetadata(String createdAt,
                                String createdBy,
                                String updatedAt,
                                String updatedBy,
                                String changeSource,
                                String changeSummary,
                                List<String> changedPaths) {
            this(createdAt, createdBy, updatedAt, updatedBy, changeSource, changeSummary, changedPaths, "");
        }

        public static RevisionMetadata empty() {
            return new RevisionMetadata("", "", "", "", "", "", List.of(), "");
        }

        public static RevisionMetadata patch(String actor,
                                             String source,
                                             String summary,
                                             List<String> changedPaths) {
            return patch(actor, source, summary, changedPaths, "");
        }

        public static RevisionMetadata patch(String actor,
                                             String source,
                                             String summary,
                                             List<String> changedPaths,
                                             String reason) {
            return new RevisionMetadata("", "", "", normalize(actor, "visual-canvas"),
                    normalize(source, "patch"), normalize(summary, "Patched draft."), changedPaths,
                    normalize(reason, ""));
        }

        public RevisionMetadata storedFrom(RevisionMetadata previous, String defaultSummary) {
            String now = Instant.now().toString();
            RevisionMetadata base = previous == null ? empty() : previous;
            String actor = normalize(updatedBy, normalize(createdBy, "visual-canvas"));
            String source = normalize(changeSource, "api");
            String summary = normalize(changeSummary, defaultSummary);
            String firstAt = normalize(base.createdAt, normalize(createdAt, now));
            String firstBy = normalize(base.createdBy, normalize(createdBy, actor));
            return new RevisionMetadata(firstAt, firstBy, now, actor, source, summary, changedPaths,
                    normalize(reason, ""));
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
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

    private static String normalizeStatus(String value) {
        return value == null || value.isBlank()
                ? STATUS_DRAFT
                : value.trim().toUpperCase(Locale.ROOT);
    }
}
