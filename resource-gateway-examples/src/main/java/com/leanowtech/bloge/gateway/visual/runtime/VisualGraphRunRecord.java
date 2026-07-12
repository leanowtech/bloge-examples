package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
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
 * @param nodeAttempts sanitized exact operator invocation attempts keyed by node id
 * @param evidenceSeal persisted signature over this immutable run material
 * @param replay immutable recorded-replay lineage, safety policy, and assertion outcomes
 * @param nodeExecutionFacts structured engine-observed and topology-derived node semantics
 * @param runControl graph-level deadline, cancellation, and termination-confirmation fact
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
        List<EdgeSnapshot> edgeSnapshots,
        Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
        VisualRunEvidenceSeal evidenceSeal,
        VisualReplayMetadata replay,
        Map<String, VisualNodeExecutionFact> nodeExecutionFacts,
        VisualRunControlView runControl
) {
    public static final String SCHEMA_VERSION = "bloge.visualGraphRunRecord.v6";
    public static final String SOURCE_TRANSIENT_DRAFT = "TRANSIENT_DRAFT";
    public static final String SOURCE_STORED_DRAFT = "STORED_DRAFT";
    public static final String SOURCE_PUBLICATION = "PUBLICATION";
    public static final String SOURCE_RECORDED_REPLAY = "RECORDED_REPLAY";

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
        nodeAttempts = immutableAttempts(nodeAttempts);
        evidenceSeal = evidenceSeal == null ? VisualRunEvidenceSeal.unsigned() : evidenceSeal;
        replay = replay == null ? VisualReplayMetadata.none() : replay;
        nodeExecutionFacts = nodeExecutionFacts == null ? Map.of() : new LinkedHashMap<>(nodeExecutionFacts);
        runControl = runControl == null ? VisualRunControlView.unmanaged() : runControl;
    }

    /** Backward-compatible constructor for the v5 shape before graph-level run control was persisted. */
    public VisualGraphRunRecord(String schemaVersion, String runId, String sourceKind, String draftId,
                                long draftRevision, String publicationId, String sourceArtifactKind,
                                String graphName, String tenantId, String namespace, String environment,
                                String outputNode, Instant createdAt, boolean validated, boolean compiled,
                                boolean success, long elapsedMs, Map<String, Long> nodeElapsedMs,
                                Map<String, String> statusMap, List<VisualDiagnostic> diagnostics,
                                List<String> errors, Map<String, Object> contextSummary,
                                Map<String, Object> outputSummary, Map<String, Object> resultsSummary,
                                Map<String, NodeSnapshot> nodeSnapshots, String generatedDsl,
                                String draftFingerprint, String operatorDependencyFingerprint,
                                Map<String, Object> contextPayload, Object outputPayload,
                                Map<String, Object> resultsPayload, VisualPayloadRedactionManifest redaction,
                                List<EdgeSnapshot> edgeSnapshots,
                                Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
                                VisualRunEvidenceSeal evidenceSeal, VisualReplayMetadata replay,
                                Map<String, VisualNodeExecutionFact> nodeExecutionFacts) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, sourceArtifactKind,
                graphName, tenantId, namespace, environment, outputNode, createdAt, validated, compiled, success,
                elapsedMs, nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary,
                resultsSummary, nodeSnapshots, generatedDsl, draftFingerprint, operatorDependencyFingerprint,
                contextPayload, outputPayload, resultsPayload, redaction, edgeSnapshots, nodeAttempts, evidenceSeal,
                replay, nodeExecutionFacts, VisualRunControlView.unmanaged());
    }

    /** Backward-compatible constructor for the v4 shape before structured execution facts were persisted. */
    public VisualGraphRunRecord(String schemaVersion, String runId, String sourceKind, String draftId,
                                long draftRevision, String publicationId, String sourceArtifactKind,
                                String graphName, String tenantId, String namespace, String environment,
                                String outputNode, Instant createdAt, boolean validated, boolean compiled,
                                boolean success, long elapsedMs, Map<String, Long> nodeElapsedMs,
                                Map<String, String> statusMap, List<VisualDiagnostic> diagnostics,
                                List<String> errors, Map<String, Object> contextSummary,
                                Map<String, Object> outputSummary, Map<String, Object> resultsSummary,
                                Map<String, NodeSnapshot> nodeSnapshots, String generatedDsl,
                                String draftFingerprint, String operatorDependencyFingerprint,
                                Map<String, Object> contextPayload, Object outputPayload,
                                Map<String, Object> resultsPayload, VisualPayloadRedactionManifest redaction,
                                List<EdgeSnapshot> edgeSnapshots,
                                Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
                                VisualRunEvidenceSeal evidenceSeal, VisualReplayMetadata replay) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, sourceArtifactKind,
                graphName, tenantId, namespace, environment, outputNode, createdAt, validated, compiled, success,
                elapsedMs, nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary,
                resultsSummary, nodeSnapshots, generatedDsl, draftFingerprint, operatorDependencyFingerprint,
                contextPayload, outputPayload, resultsPayload, redaction, edgeSnapshots, nodeAttempts, evidenceSeal,
                replay, Map.of());
    }

    /** Backward-compatible constructor for the v3 shape before replay lineage was first-class. */
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
                                String generatedDsl,
                                String draftFingerprint,
                                String operatorDependencyFingerprint,
                                Map<String, Object> contextPayload,
                                Object outputPayload,
                                Map<String, Object> resultsPayload,
                                VisualPayloadRedactionManifest redaction,
                                List<EdgeSnapshot> edgeSnapshots,
                                Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
                                VisualRunEvidenceSeal evidenceSeal) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, sourceArtifactKind,
                graphName, tenantId, namespace, environment, outputNode, createdAt, validated, compiled, success,
                elapsedMs, nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary,
                resultsSummary, nodeSnapshots, generatedDsl, draftFingerprint, operatorDependencyFingerprint,
                contextPayload, outputPayload, resultsPayload, redaction, edgeSnapshots, nodeAttempts, evidenceSeal,
                VisualReplayMetadata.none());
    }

    /** Backward-compatible constructor for callers using the v2 shape before node capture was added. */
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
                                String generatedDsl,
                                String draftFingerprint,
                                String operatorDependencyFingerprint,
                                Map<String, Object> contextPayload,
                                Object outputPayload,
                                Map<String, Object> resultsPayload,
                                VisualPayloadRedactionManifest redaction,
                                List<EdgeSnapshot> edgeSnapshots) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, sourceArtifactKind,
                graphName, tenantId, namespace, environment, outputNode, createdAt, validated, compiled, success,
                elapsedMs, nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary,
                resultsSummary, nodeSnapshots, generatedDsl, draftFingerprint, operatorDependencyFingerprint,
                contextPayload, outputPayload, resultsPayload, redaction, edgeSnapshots, Map.of(),
                VisualRunEvidenceSeal.unsigned());
    }

    /** Backward-compatible constructor for callers that provide node capture but no persisted seal yet. */
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
                                String generatedDsl,
                                String draftFingerprint,
                                String operatorDependencyFingerprint,
                                Map<String, Object> contextPayload,
                                Object outputPayload,
                                Map<String, Object> resultsPayload,
                                VisualPayloadRedactionManifest redaction,
                                List<EdgeSnapshot> edgeSnapshots,
                                Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts) {
        this(schemaVersion, runId, sourceKind, draftId, draftRevision, publicationId, sourceArtifactKind,
                graphName, tenantId, namespace, environment, outputNode, createdAt, validated, compiled, success,
                elapsedMs, nodeElapsedMs, statusMap, diagnostics, errors, contextSummary, outputSummary,
                resultsSummary, nodeSnapshots, generatedDsl, draftFingerprint, operatorDependencyFingerprint,
                contextPayload, outputPayload, resultsPayload, redaction, edgeSnapshots, nodeAttempts,
                VisualRunEvidenceSeal.unsigned());
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
                VisualPayloadRedactionManifest.empty(), List.of(), Map.of(), VisualRunEvidenceSeal.unsigned());
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
                operatorDependencyFingerprint, contextPayload, outputPayload, resultsPayload, redaction, edgeSnapshots,
                nodeAttempts, VisualRunEvidenceSeal.unsigned(), replay, nodeExecutionFacts, runControl);
    }

    /** Returns a copy sealed after repository identity has been assigned. */
    public VisualGraphRunRecord withEvidenceSeal(VisualRunEvidenceSeal newEvidenceSeal) {
        return new VisualGraphRunRecord(schemaVersion, runId, sourceKind, draftId, draftRevision,
                publicationId, sourceArtifactKind, graphName, tenantId, namespace, environment, outputNode, createdAt,
                validated, compiled, success, elapsedMs, nodeElapsedMs, statusMap, diagnostics, errors, contextSummary,
                outputSummary, resultsSummary, nodeSnapshots, generatedDsl, draftFingerprint,
                operatorDependencyFingerprint, contextPayload, outputPayload, resultsPayload, redaction, edgeSnapshots,
                nodeAttempts, newEvidenceSeal, replay, nodeExecutionFacts, runControl);
    }

    /** Stable fingerprint over every persisted run field except the seal itself. */
    public String evidenceMaterialFingerprint() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("runId", runId);
        material.put("sourceKind", sourceKind);
        material.put("draftId", draftId);
        material.put("draftRevision", draftRevision);
        material.put("publicationId", publicationId);
        material.put("sourceArtifactKind", sourceArtifactKind);
        material.put("graphName", graphName);
        material.put("tenantId", tenantId);
        material.put("namespace", namespace);
        material.put("environment", environment);
        material.put("outputNode", outputNode);
        material.put("createdAt", createdAt);
        material.put("validated", validated);
        material.put("compiled", compiled);
        material.put("success", success);
        material.put("elapsedMs", elapsedMs);
        material.put("nodeElapsedMs", nodeElapsedMs);
        material.put("statusMap", statusMap);
        material.put("diagnostics", diagnostics);
        material.put("errors", errors);
        material.put("contextSummary", contextSummary);
        material.put("outputSummary", outputSummary);
        material.put("resultsSummary", resultsSummary);
        material.put("nodeSnapshots", nodeSnapshots);
        material.put("generatedDsl", generatedDsl);
        material.put("draftFingerprint", draftFingerprint);
        material.put("operatorDependencyFingerprint", operatorDependencyFingerprint);
        material.put("contextPayload", contextPayload);
        material.put("outputPayload", outputPayload == null ? "" : outputPayload);
        material.put("resultsPayload", resultsPayload);
        material.put("redaction", redaction);
        material.put("edgeSnapshots", edgeSnapshots);
        material.put("nodeAttempts", nodeAttempts);
        material.put("replay", replay);
        material.put("nodeExecutionFacts", nodeExecutionFacts);
        material.put("runControl", runControl);
        return VisualBundleFingerprint.fromMaterial(material);
    }

    /** Creates a zero-external-call replay run over this record's already sanitized payload snapshot. */
    public VisualGraphRunRecord recordedReplay(VisualReplayMetadata replayMetadata) {
        VisualReplayMetadata safeReplay = replayMetadata == null ? VisualReplayMetadata.none() : replayMetadata;
        Set<String> nodeIds = new TreeSet<>();
        nodeIds.addAll(nodeSnapshots.keySet());
        nodeIds.addAll(resultsPayload.keySet());
        nodeIds.addAll(nodeAttempts.keySet());
        nodeIds.addAll(nodeExecutionFacts.keySet());
        Map<String, String> replayStatuses = new LinkedHashMap<>();
        Map<String, Long> replayElapsed = new LinkedHashMap<>();
        Map<String, List<VisualNodeExecutionAttempt>> replayAttempts = new LinkedHashMap<>();
        Map<String, VisualNodeExecutionFact> replayFacts = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            List<VisualNodeExecutionAttempt> attempts = nodeAttempts.getOrDefault(nodeId, List.of());
            VisualNodeExecutionAttempt latest = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
            Object input = latest == null ? Map.of() : latest.input();
            Object output = resultsPayload.containsKey(nodeId)
                    ? resultsPayload.get(nodeId)
                    : latest == null ? null : latest.output();
            replayStatuses.put(nodeId, "MOCKED");
            replayElapsed.put(nodeId, 0L);
            replayAttempts.put(nodeId, List.of(new VisualNodeExecutionAttempt(
                    0, input, output, "MOCKED", createdAt, 0, "", "RECORDED_PAYLOAD_REPLAY")));
            replayFacts.put(nodeId, new VisualNodeExecutionFact(
                    "MOCKED", "RECORDED_PAYLOAD_REPLAY", "RECORDED_REPLAY", List.of(),
                    new VisualNodeExecutionFact.Retry(1, 1, false, ""),
                    new VisualNodeExecutionFact.Timeout(false, 0, false),
                    new VisualNodeExecutionFact.Fallback(false, false, "NONE", ""),
                    "NOT_INVOKED", List.of()));
        }
        boolean assertionsPassed = safeReplay.assertionsPassed();
        List<String> replayErrors = safeReplay.assertionResults().stream()
                .filter(result -> !result.passed())
                .map(VisualReplayAssertionResult::message)
                .filter(message -> !message.isBlank())
                .toList();
        return new VisualGraphRunRecord(
                "", "", SOURCE_RECORDED_REPLAY, draftId, draftRevision, publicationId, sourceArtifactKind,
                graphName, tenantId, namespace, environment, outputNode, null, true, false, assertionsPassed, 0,
                replayElapsed, replayStatuses, List.of(), replayErrors, contextSummary, outputSummary, resultsSummary,
                nodeSnapshots, generatedDsl, draftFingerprint, operatorDependencyFingerprint, contextPayload,
                outputPayload, resultsPayload, redaction, edgeSnapshots, replayAttempts,
                VisualRunEvidenceSeal.unsigned(), safeReplay, replayFacts, VisualRunControlView.unmanaged());
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
                context, safeResponse.output(), safeResponse.results(), safeResponse.nodeAttempts());
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
                edgeSnapshots(draft),
                payloadCapture.nodeAttempts(),
                VisualRunEvidenceSeal.unsigned(),
                VisualReplayMetadata.none(),
                safeResponse.nodeExecutionFacts(),
                safeResponse.runControl()
        );
    }

    private static Map<String, List<VisualNodeExecutionAttempt>> immutableAttempts(
            Map<String, List<VisualNodeExecutionAttempt>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<VisualNodeExecutionAttempt>> copy = new LinkedHashMap<>();
        source.forEach((nodeId, attempts) -> copy.put(nodeId, attempts == null ? List.of() : List.copyOf(attempts)));
        return copy;
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
            OperatorDefinition definition = draft.operatorSnapshots().get(node.id());
            snapshots.put(node.id(), new NodeSnapshot(node.id(), i, node.operatorRef(), node.label(),
                    definition == null ? "UNKNOWN" : definition.capabilities().effect(),
                    definition == null ? "UNKNOWN" : definition.capabilities().idempotency()));
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
    public record NodeSnapshot(String nodeId, int nodeIndex, String operatorRef, String label,
                               String effect, String idempotency) {
        /**
         * Creates a node snapshot.
         */
        public NodeSnapshot {
            nodeId = nodeId == null ? "" : nodeId;
            nodeIndex = Math.max(-1, nodeIndex);
            operatorRef = operatorRef == null ? "" : operatorRef;
            label = label == null ? "" : label;
            effect = effect == null || effect.isBlank() ? "UNKNOWN" : effect.toUpperCase(java.util.Locale.ROOT);
            idempotency = idempotency == null || idempotency.isBlank()
                    ? "UNKNOWN" : idempotency.toUpperCase(java.util.Locale.ROOT);
        }

        public NodeSnapshot(String nodeId, int nodeIndex, String operatorRef, String label) {
            this(nodeId, nodeIndex, operatorRef, label, "UNKNOWN", "UNKNOWN");
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
