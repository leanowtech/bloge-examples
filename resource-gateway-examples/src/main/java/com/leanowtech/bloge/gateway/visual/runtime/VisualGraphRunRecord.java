package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persisted audit record for one visual graph run.
 *
 * @param schemaVersion run history schema version
 * @param runId run id
 * @param sourceKind TRANSIENT_DRAFT, STORED_DRAFT, or PUBLICATION
 * @param draftId source draft id
 * @param draftRevision source draft revision
 * @param publicationId source publication id
 * @param sourceArtifactKind publication artifact kind, such as EXECUTABLE or DESIGN
 * @param graphName graph name
 * @param tenantId tenant id
 * @param namespace namespace
 * @param environment authoring environment
 * @param outputNode selected output node
 * @param createdAt record creation timestamp
 * @param validated whether visual validation passed
 * @param compiled whether BLOGE compilation passed
 * @param success whether execution succeeded
 * @param elapsedMs execution elapsed milliseconds
 * @param nodeElapsedMs per-node execution elapsed milliseconds
 * @param statusMap node execution statuses
 * @param diagnostics validation, lowering, compiler, or runtime diagnostics
 * @param errors blocking or runtime errors
 * @param contextSummary shape-only summary of submitted context
 * @param outputSummary shape-only summary of selected output
 * @param resultsSummary shape-only summary of node results
 * @param nodeSnapshots shape-only draft node metadata keyed by node id
 * @param generatedDsl generated or frozen DSL used for execution
 * @param draftFingerprint immutable fingerprint of the executed draft material
 * @param operatorDependencyFingerprint fingerprint of the operator snapshot set used by the run
 * @param contextPayload sanitized submitted context retained for governed replay
 * @param outputPayload sanitized selected output retained for governed replay
 * @param resultsPayload sanitized node results retained for governed replay
 * @param redaction sanitizer audit metadata
 * @param edgeSnapshots executed draft edge metadata used to reconstruct edge trace
 */
public record VisualGraphRunRecord(
        String schemaVersion,
        String runId,
        String sourceKind,
        String draftId,
        long draftRevision,
        String publicationId,
        String sourceArtifactKind,
        String graphName,
        String tenantId,
        String namespace,
        String environment,
        String outputNode,
        Instant createdAt,
        boolean validated,
        boolean compiled,
        boolean success,
        long elapsedMs,
        Map<String, Long> nodeElapsedMs,
        Map<String, String> statusMap,
        List<VisualDiagnostic> diagnostics,
        List<String> errors,
        Map<String, Object> contextSummary,
        Map<String, Object> outputSummary,
        Map<String, Object> resultsSummary,
        Map<String, NodeSnapshot> nodeSnapshots,
        String generatedDsl,
        String draftFingerprint,
        String operatorDependencyFingerprint,
        Map<String, Object> contextPayload,
        Object outputPayload,
        Map<String, Object> resultsPayload,
        VisualPayloadRedactionManifest redaction,
        List<EdgeSnapshot> edgeSnapshots
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphRunRecord.v2";
    public static final String SOURCE_TRANSIENT_DRAFT = "TRANSIENT_DRAFT";
    public static final String SOURCE_STORED_DRAFT = "STORED_DRAFT";
    public static final String SOURCE_PUBLICATION = "PUBLICATION";

    private static final int MAX_KEYS = 25;
    private static final int MAX_CHILDREN = 10;
    private static final int MAX_DEPTH = 2;

    /**
     * Creates a run record.
     */
    public VisualGraphRunRecord {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        runId = runId == null ? "" : runId;
        sourceKind = sourceKind == null || sourceKind.isBlank() ? SOURCE_TRANSIENT_DRAFT : sourceKind;
        draftId = draftId == null ? "" : draftId;
        draftRevision = Math.max(0, draftRevision);
        publicationId = publicationId == null ? "" : publicationId;
        sourceArtifactKind = sourceArtifactKind == null ? "" : sourceArtifactKind.trim().toUpperCase();
        graphName = graphName == null ? "" : graphName;
        tenantId = tenantId == null ? "" : tenantId;
        namespace = namespace == null ? "" : namespace;
        environment = environment == null ? "" : environment;
        outputNode = outputNode == null ? "" : outputNode;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        elapsedMs = Math.max(0, elapsedMs);
        nodeElapsedMs = nodeElapsedMs == null ? Map.of() : new LinkedHashMap<>(nodeElapsedMs);
        statusMap = statusMap == null ? Map.of() : new LinkedHashMap<>(statusMap);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
        contextSummary = contextSummary == null ? Map.of() : new LinkedHashMap<>(contextSummary);
        outputSummary = outputSummary == null ? Map.of() : new LinkedHashMap<>(outputSummary);
        resultsSummary = resultsSummary == null ? Map.of() : new LinkedHashMap<>(resultsSummary);
        nodeSnapshots = nodeSnapshots == null ? Map.of() : new LinkedHashMap<>(nodeSnapshots);
        generatedDsl = generatedDsl == null ? "" : generatedDsl;
        draftFingerprint = draftFingerprint == null ? "" : draftFingerprint;
        operatorDependencyFingerprint = operatorDependencyFingerprint == null ? "" : operatorDependencyFingerprint;
        contextPayload = contextPayload == null ? Map.of() : new LinkedHashMap<>(contextPayload);
        resultsPayload = resultsPayload == null ? Map.of() : new LinkedHashMap<>(resultsPayload);
        redaction = redaction == null ? VisualPayloadRedactionManifest.empty() : redaction;
        edgeSnapshots = edgeSnapshots == null ? List.of() : List.copyOf(edgeSnapshots);
    }

    /**
     * Backward-compatible constructor for callers using the v1 run-record shape.
     */
    public VisualGraphRunRecord(String schemaVersion,
                                String runId,
                                String sourceKind,
                                String draftId,
                                long draftRevision,
                                String publicationId,
                                String sourceArtifactKind,
                                String graphName,
                                String tenantId,
                                String namespace,
                                String environment,
                                String outputNode,
                                Instant createdAt,
                                boolean validated,
                                boolean compiled,
                                boolean success,
                                long elapsedMs,
                                Map<String, Long> nodeElapsedMs,
                                Map<String, String> statusMap,
                                List<VisualDiagnostic> diagnostics,
                                List<String> errors,
                                Map<String, Object> contextSummary,
                                Map<String, Object> outputSummary,
                                Map<String, Object> resultsSummary,
                                Map<String, NodeSnapshot> nodeSnapshots,
                                String generatedDsl) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, sourceArtifactKind, graphName,
                tenantId, namespace, environment, outputNode, createdAt, validated, compiled, success, elapsedMs,
                nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary, resultsSummary,
                nodeSnapshots, generatedDsl, "", "", Map.of(), null, Map.of(),
                VisualPayloadRedactionManifest.empty(), List.of());
    }

    /**
     * Backward-compatible constructor for run records created before publication artifact kind, node timings,
     * and snapshots existed.
     */
    public VisualGraphRunRecord(String schemaVersion,
                                String runId,
                                String sourceKind,
                                String draftId,
                                long draftRevision,
                                String publicationId,
                                String graphName,
                                String tenantId,
                                String namespace,
                                String environment,
                                String outputNode,
                                Instant createdAt,
                                boolean validated,
                                boolean compiled,
                                boolean success,
                                long elapsedMs,
                                Map<String, String> statusMap,
                                List<VisualDiagnostic> diagnostics,
                                List<String> errors,
                                Map<String, Object> contextSummary,
                                Map<String, Object> outputSummary,
                                Map<String, Object> resultsSummary,
                                String generatedDsl) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, graphName, tenantId,
                namespace, environment, outputNode, createdAt, validated, compiled, success, elapsedMs, Map.of(),
                statusMap, diagnostics, errors, contextSummary, outputSummary, resultsSummary, Map.of(),
                generatedDsl);
    }

    /**
     * Convenience constructor for records that include publication artifact kind but omit node timings and snapshots.
     */
    public VisualGraphRunRecord(String schemaVersion,
                                String runId,
                                String sourceKind,
                                String draftId,
                                long draftRevision,
                                String publicationId,
                                String sourceArtifactKind,
                                String graphName,
                                String tenantId,
                                String namespace,
                                String environment,
                                String outputNode,
                                Instant createdAt,
                                boolean validated,
                                boolean compiled,
                                boolean success,
                                long elapsedMs,
                                Map<String, String> statusMap,
                                List<VisualDiagnostic> diagnostics,
                                List<String> errors,
                                Map<String, Object> contextSummary,
                                Map<String, Object> outputSummary,
                                Map<String, Object> resultsSummary,
                                String generatedDsl) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, sourceArtifactKind, graphName,
                tenantId, namespace, environment, outputNode, createdAt, validated, compiled, success, elapsedMs,
                Map.of(), statusMap, diagnostics, errors, contextSummary, outputSummary, resultsSummary, Map.of(),
                generatedDsl);
    }

    /**
     * Backward-compatible constructor for run records created before publication artifact kind was tracked.
     */
    public VisualGraphRunRecord(String schemaVersion,
                                String runId,
                                String sourceKind,
                                String draftId,
                                long draftRevision,
                                String publicationId,
                                String graphName,
                                String tenantId,
                                String namespace,
                                String environment,
                                String outputNode,
                                Instant createdAt,
                                boolean validated,
                                boolean compiled,
                                boolean success,
                                long elapsedMs,
                                Map<String, Long> nodeElapsedMs,
                                Map<String, String> statusMap,
                                List<VisualDiagnostic> diagnostics,
                                List<String> errors,
                                Map<String, Object> contextSummary,
                                Map<String, Object> outputSummary,
                                Map<String, Object> resultsSummary,
                                Map<String, NodeSnapshot> nodeSnapshots,
                                String generatedDsl) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, "", graphName, tenantId,
                namespace, environment, outputNode, createdAt, validated, compiled, success, elapsedMs,
                nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary, resultsSummary,
                nodeSnapshots, generatedDsl);
    }

    /**
     * Convenience constructor for records that include publication artifact kind and node timings but omit node snapshots.
     */
    public VisualGraphRunRecord(String schemaVersion,
                                String runId,
                                String sourceKind,
                                String draftId,
                                long draftRevision,
                                String publicationId,
                                String sourceArtifactKind,
                                String graphName,
                                String tenantId,
                                String namespace,
                                String environment,
                                String outputNode,
                                Instant createdAt,
                                boolean validated,
                                boolean compiled,
                                boolean success,
                                long elapsedMs,
                                Map<String, Long> nodeElapsedMs,
                                Map<String, String> statusMap,
                                List<VisualDiagnostic> diagnostics,
                                List<String> errors,
                                Map<String, Object> contextSummary,
                                Map<String, Object> outputSummary,
                                Map<String, Object> resultsSummary,
                                String generatedDsl) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, sourceArtifactKind, graphName,
                tenantId, namespace, environment, outputNode, createdAt, validated, compiled, success, elapsedMs,
                nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary, resultsSummary,
                Map.of(), generatedDsl);
    }

    /**
     * Backward-compatible constructor for records created before publication artifact kind and node snapshots existed.
     */
    public VisualGraphRunRecord(String schemaVersion,
                                String runId,
                                String sourceKind,
                                String draftId,
                                long draftRevision,
                                String publicationId,
                                String graphName,
                                String tenantId,
                                String namespace,
                                String environment,
                                String outputNode,
                                Instant createdAt,
                                boolean validated,
                                boolean compiled,
                                boolean success,
                                long elapsedMs,
                                Map<String, Long> nodeElapsedMs,
                                Map<String, String> statusMap,
                                List<VisualDiagnostic> diagnostics,
                                List<String> errors,
                                Map<String, Object> contextSummary,
                                Map<String, Object> outputSummary,
                                Map<String, Object> resultsSummary,
                                String generatedDsl) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, graphName, tenantId,
                namespace, environment, outputNode, createdAt, validated, compiled, success, elapsedMs,
                nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary, resultsSummary,
                Map.of(), generatedDsl);
    }

    /**
     * Creates a transient draft run history record.
     */
    public static VisualGraphRunRecord transientDraft(GraphDraft draft,
                                                      Map<String, Object> context,
                                                      VisualGraphRunResponse response) {
        return fromDraft(SOURCE_TRANSIENT_DRAFT, draft, "", context, response);
    }

    /**
     * Creates a stored draft run history record.
     */
    public static VisualGraphRunRecord storedDraft(GraphDraft draft,
                                                   Map<String, Object> context,
                                                   VisualGraphRunResponse response) {
        return fromDraft(SOURCE_STORED_DRAFT, draft, "", context, response);
    }

    /**
     * Creates a published artifact run history record.
     */
    public static VisualGraphRunRecord publication(VisualGraphPublication publication,
                                                   Map<String, Object> context,
                                                   VisualGraphRunResponse response) {
        GraphDraft draft = publication == null ? null : publication.draft();
        return fromDraft(SOURCE_PUBLICATION, draft, publication == null ? "" : publication.publicationId(),
                publication == null ? "" : publication.artifactKind(), context, response);
    }

    /**
     * Returns a copy with repository identity values.
     */
    public VisualGraphRunRecord withIdentity(String newRunId, Instant newCreatedAt) {
        return new VisualGraphRunRecord(schemaVersion, newRunId, sourceKind, draftId, draftRevision,
                publicationId, sourceArtifactKind, graphName, tenantId, namespace, environment, outputNode, newCreatedAt,
                validated, compiled, success, elapsedMs, nodeElapsedMs, statusMap, diagnostics, errors, contextSummary,
                outputSummary, resultsSummary, nodeSnapshots, generatedDsl, draftFingerprint,
                operatorDependencyFingerprint, contextPayload, outputPayload, resultsPayload, redaction, edgeSnapshots);
    }

    private static VisualGraphRunRecord fromDraft(String sourceKind,
                                                  GraphDraft draft,
                                                  String publicationId,
                                                  Map<String, Object> context,
                                                  VisualGraphRunResponse response) {
        return fromDraft(sourceKind, draft, publicationId, "", context, response);
    }

    private static VisualGraphRunRecord fromDraft(String sourceKind,
                                                  GraphDraft draft,
                                                  String publicationId,
                                                  String sourceArtifactKind,
                                                  Map<String, Object> context,
                                                  VisualGraphRunResponse response) {
        VisualGraphRunResponse safeResponse = response == null
                ? new VisualGraphRunResponse(false, false, false, "", "", null, Map.of(), Map.of(), 0,
                        List.of(), List.of("Visual graph run response is missing."), null, null, "")
                : response;
        VisualPayloadSanitizer.Capture payloadCapture = VisualPayloadSanitizer.capture(
                context, safeResponse.output(), safeResponse.results());
        return new VisualGraphRunRecord(
                "",
                "",
                sourceKind,
                draft == null ? "" : draft.draftId(),
                draft == null ? 0 : draft.revision(),
                publicationId,
                sourceArtifactKind,
                safeResponse.graphName().isBlank() && draft != null ? draft.graphName() : safeResponse.graphName(),
                draft == null ? "" : draft.tenantId(),
                draft == null ? "" : draft.namespace(),
                draft == null ? "" : draft.environment(),
                safeResponse.outputNode(),
                null,
                safeResponse.validated(),
                safeResponse.compiled(),
                safeResponse.success(),
                safeResponse.elapsedMs(),
                safeResponse.nodeElapsedMs(),
                safeResponse.statusMap(),
                safeResponse.diagnostics(),
                safeResponse.errors(),
                summarizeMap(context),
                summarizeRoot(safeResponse.output()),
                summarizeMap(safeResponse.results()),
                nodeSnapshots(draft),
                safeResponse.generatedDsl(),
                draftFingerprint(draft),
                operatorDependencyFingerprint(draft),
                payloadCapture.context(),
                payloadCapture.output(),
                payloadCapture.results(),
                payloadCapture.redaction(),
                edgeSnapshots(draft)
        );
    }

    private static String draftFingerprint(GraphDraft draft) {
        return draft == null ? "" : VisualBundleFingerprint.fromMaterial(Map.of(
                "draft", draft.withNodeFixtures(Map.of())
        ));
    }

    private static String operatorDependencyFingerprint(GraphDraft draft) {
        return draft == null ? "" : VisualBundleFingerprint.fromMaterial(Map.of(
                "operatorFingerprints", draft.operatorFingerprints(),
                "operatorSnapshots", draft.operatorSnapshots()
        ));
    }

    private static Map<String, NodeSnapshot> nodeSnapshots(GraphDraft draft) {
        if (draft == null || draft.nodes().isEmpty()) {
            return Map.of();
        }
        Map<String, NodeSnapshot> snapshots = new LinkedHashMap<>();
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            snapshots.put(node.id(), new NodeSnapshot(node.id(), i, node.operatorRef(), node.label()));
        }
        return snapshots;
    }

    private static List<EdgeSnapshot> edgeSnapshots(GraphDraft draft) {
        if (draft == null || draft.edges().isEmpty()) {
            return List.of();
        }
        return draft.edges().stream()
                .map(edge -> new EdgeSnapshot(edge.id(), edge.kind(), edge.source().nodeId(), edge.target().nodeId(),
                        edge.source().port(), edge.target().port(), edge.condition()))
                .toList();
    }

    private static Map<String, Object> summarizeMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        values.keySet().stream()
                .map(String::valueOf)
                .sorted()
                .limit(MAX_KEYS)
                .forEach(key -> summary.put(key, summarizeValue(values.get(key), 0)));
        if (values.size() > MAX_KEYS) {
            summary.put("_truncated", Map.of("type", "boolean", "present", true));
        }
        return summary;
    }

    private static Map<String, Object> summarizeRoot(Object value) {
        Object summary = summarizeValue(value, 0);
        if (summary instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        return Map.of("type", "unknown");
    }

    private static Map<String, Object> summarizeValue(Object value, int depth) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (value == null) {
            summary.put("type", "null");
            return summary;
        }
        if (value instanceof Map<?, ?> map) {
            summary.put("type", "object");
            summary.put("size", map.size());
            List<String> keys = map.keySet().stream()
                    .map(String::valueOf)
                    .sorted()
                    .limit(MAX_KEYS)
                    .toList();
            summary.put("keys", keys);
            if (depth < MAX_DEPTH) {
                Map<String, Object> fields = new LinkedHashMap<>();
                keys.stream().limit(MAX_CHILDREN)
                        .forEach(key -> fields.put(key, summarizeValue(map.get(key), depth + 1)));
                summary.put("fields", fields);
            }
            if (map.size() > MAX_KEYS) {
                summary.put("truncated", true);
            }
            return summary;
        }
        if (value instanceof Collection<?> collection) {
            summary.put("type", "array");
            summary.put("size", collection.size());
            summary.put("itemTypes", itemTypes(collection));
            return summary;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                items.add(Array.get(value, i));
            }
            summary.put("type", "array");
            summary.put("size", length);
            summary.put("itemTypes", itemTypes(items));
            return summary;
        }
        if (value instanceof CharSequence text) {
            summary.put("type", "string");
            summary.put("length", text.length());
            summary.put("blank", text.toString().isBlank());
            return summary;
        }
        if (value instanceof Number number) {
            summary.put("type", "number");
            summary.put("numberType", number.getClass().getSimpleName());
            return summary;
        }
        if (value instanceof Boolean) {
            summary.put("type", "boolean");
            return summary;
        }
        summary.put("type", value.getClass().getSimpleName());
        return summary;
    }

    private static List<String> itemTypes(Collection<?> collection) {
        Set<String> types = new TreeSet<>();
        for (Object item : collection) {
            if (item == null) {
                types.add("null");
            } else if (item instanceof Map<?, ?>) {
                types.add("object");
            } else if (item instanceof Collection<?> || item.getClass().isArray()) {
                types.add("array");
            } else if (item instanceof CharSequence) {
                types.add("string");
            } else if (item instanceof Number) {
                types.add("number");
            } else if (item instanceof Boolean) {
                types.add("boolean");
            } else {
                types.add(item.getClass().getSimpleName());
            }
        }
        return types.stream().toList();
    }

    /**
     * Shape-only visual node metadata captured when a run record is created.
     *
     * @param nodeId node id
     * @param nodeIndex zero-based draft node index
     * @param operatorRef operator reference used by the node
     * @param label display label
     */
    public record NodeSnapshot(String nodeId, int nodeIndex, String operatorRef, String label) {
        /**
         * Creates a node snapshot.
         */
        public NodeSnapshot {
            nodeId = nodeId == null ? "" : nodeId;
            nodeIndex = Math.max(-1, nodeIndex);
            operatorRef = operatorRef == null ? "" : operatorRef;
            label = label == null ? "" : label;
        }
    }

    /**
     * Immutable draft edge metadata captured with the run.
     */
    public record EdgeSnapshot(
            String edgeId,
            String kind,
            String sourceNodeId,
            String targetNodeId,
            String sourcePort,
            String targetPort,
            String condition
    ) {
        public EdgeSnapshot {
            edgeId = edgeId == null ? "" : edgeId;
            kind = kind == null ? "" : kind;
            sourceNodeId = sourceNodeId == null ? "" : sourceNodeId;
            targetNodeId = targetNodeId == null ? "" : targetNodeId;
            sourcePort = sourcePort == null ? "" : sourcePort;
            targetPort = targetPort == null ? "" : targetPort;
            condition = condition == null ? "" : condition;
        }
    }
}
