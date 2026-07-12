package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Governance evidence projection for one persisted run.
 */
public record RunEvidenceBundle(
        String schemaVersion,
        String evidenceId,
        String runId,
        Source source,
        Fingerprints fingerprints,
        Execution execution,
        PayloadSummary context,
        PayloadSummary output,
        List<NodeEvidence> nodes,
        List<EdgeEvidence> edges,
        Assertions assertions,
        List<VisualDiagnostic> diagnostics,
        List<String> errors,
        Retention retention,
        EvidenceManifest manifest
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.runEvidenceBundle.v1";

    public RunEvidenceBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        evidenceId = evidenceId == null ? "" : evidenceId;
        runId = runId == null ? "" : runId;
        source = source == null ? Source.empty() : source;
        fingerprints = fingerprints == null ? Fingerprints.empty() : fingerprints;
        execution = execution == null ? Execution.empty() : execution;
        context = context == null ? PayloadSummary.empty() : context;
        output = output == null ? PayloadSummary.empty() : output;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        assertions = assertions == null ? Assertions.notRun() : assertions;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        errors = errors == null ? List.of() : List.copyOf(errors);
        retention = retention == null ? Retention.summaryOnly() : retention;
        manifest = manifest == null ? EvidenceManifest.empty() : manifest;
    }

    public static RunEvidenceBundle from(VisualGraphRunRecord record) {
        return from(record, VisualEvidenceSigner.unavailable());
    }

    public static RunEvidenceBundle from(VisualGraphRunRecord record, VisualEvidenceSigner evidenceSigner) {
        Source source = new Source(record.sourceKind(), record.draftId(), record.draftRevision(),
                record.publicationId(), record.sourceArtifactKind(), record.graphName(), record.tenantId(),
                record.namespace(), record.environment());
        Fingerprints fingerprints = new Fingerprints(record.draftFingerprint(), dslFingerprint(record),
                record.operatorDependencyFingerprint());
        List<NodeEvidence> nodes = nodeEvidence(record);
        List<EdgeEvidence> edges = edgeEvidence(record);
        Execution execution = new Execution(graphStatus(record, nodes), record.createdAt(), record.elapsedMs(),
                record.outputNode(), nodes.stream().anyMatch(NodeEvidence::mocked));
        Retention retention = new Retention("SANITIZED", record.redaction().profile(),
                record.createdAt().plus(30, ChronoUnit.DAYS));
        EvidenceManifest manifest = manifest(record, nodes, edges,
                evidenceSigner == null ? VisualEvidenceSigner.unavailable() : evidenceSigner);
        return new RunEvidenceBundle("", "evidence:" + record.runId(), record.runId(), source, fingerprints,
                execution,
                new PayloadSummary(record.contextSummary(), "payload:" + record.runId() + ":context"),
                new PayloadSummary(record.outputSummary(), "payload:" + record.runId() + ":output"),
                nodes, edges, Assertions.notRun(), record.diagnostics(), record.errors(), retention, manifest);
    }

    private static String dslFingerprint(VisualGraphRunRecord record) {
        return VisualBundleFingerprint.fromMaterial(Map.of("generatedDsl", record.generatedDsl()));
    }

    private static List<NodeEvidence> nodeEvidence(VisualGraphRunRecord record) {
        Set<String> nodeIds = new LinkedHashSet<>(record.nodeSnapshots().keySet());
        nodeIds.addAll(record.statusMap().keySet());
        nodeIds.addAll(record.resultsPayload().keySet());
        nodeIds.addAll(record.nodeAttempts().keySet());
        List<NodeEvidence> nodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            VisualGraphRunRecord.NodeSnapshot snapshot = record.nodeSnapshots().get(nodeId);
            List<VisualNodeExecutionAttempt> attempts = record.nodeAttempts().getOrDefault(nodeId, List.of());
            VisualNodeExecutionAttempt latestAttempt = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
            VisualRunStatus status = nodeStatus(record.statusMap().get(nodeId), latestAttempt);
            boolean inputAvailable = latestAttempt != null;
            boolean outputAvailable = record.resultsPayload().containsKey(nodeId)
                    || latestAttempt != null && latestAttempt.output() != null;
            nodes.add(new NodeEvidence(
                    nodeId,
                    snapshot == null ? "" : snapshot.operatorRef(),
                    status.name(),
                    reason(status, latestAttempt),
                    record.nodeElapsedMs().getOrDefault(nodeId, 0L),
                    status == VisualRunStatus.MOCKED,
                    new Retry(attempts.size(), attempts.size(), latestAttempt == null ? "" : latestAttempt.errorType()),
                    new Fallback(status == VisualRunStatus.FALLBACK, "", ""),
                    "payload:" + record.runId() + ":node." + nodeId + ".input",
                    outputAvailable ? "payload:" + record.runId() + ":node." + nodeId + ".output" : "",
                    inputAvailable,
                    outputAvailable
            ));
        }
        return nodes;
    }

    private static String reason(VisualRunStatus status, VisualNodeExecutionAttempt latestAttempt) {
        if (latestAttempt != null && !latestAttempt.errorMessage().isBlank()) {
            return latestAttempt.errorMessage();
        }
        return status == VisualRunStatus.UNKNOWN ? "STATUS_NOT_CAPTURED" : "";
    }

    private static VisualRunStatus nodeStatus(String runtimeStatus, VisualNodeExecutionAttempt latestAttempt) {
        VisualRunStatus status = VisualRunStatus.fromRuntime(runtimeStatus);
        if (status == VisualRunStatus.FAILED && latestAttempt != null
                && latestAttempt.errorType().toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
            return VisualRunStatus.TIMEOUT;
        }
        return status;
    }

    private static List<EdgeEvidence> edgeEvidence(VisualGraphRunRecord record) {
        Map<String, VisualRunStatus> statuses = new LinkedHashMap<>();
        record.statusMap().forEach((nodeId, status) -> {
            List<VisualNodeExecutionAttempt> attempts = record.nodeAttempts().getOrDefault(nodeId, List.of());
            VisualNodeExecutionAttempt latest = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
            statuses.put(nodeId, nodeStatus(status, latest));
        });
        return record.edgeSnapshots().stream()
                .map(edge -> new EdgeEvidence(
                        edge.edgeId(), edge.sourceNodeId(), edge.targetNodeId(),
                        edgeStatus(statuses.get(edge.sourceNodeId()), statuses.get(edge.targetNodeId())).name(),
                        "payload:" + record.runId() + ":edge." + edge.edgeId()
                ))
                .toList();
    }

    private static VisualRunStatus edgeStatus(VisualRunStatus source, VisualRunStatus target) {
        if (source == VisualRunStatus.SUCCESS && target == VisualRunStatus.SUCCESS) {
            return VisualRunStatus.SUCCESS;
        }
        if (target == VisualRunStatus.SKIPPED) {
            return VisualRunStatus.SKIPPED;
        }
        if (source == VisualRunStatus.TIMEOUT || target == VisualRunStatus.TIMEOUT) {
            return VisualRunStatus.TIMEOUT;
        }
        if (source == VisualRunStatus.FAILED || target == VisualRunStatus.FAILED) {
            return VisualRunStatus.FAILED;
        }
        if (source == VisualRunStatus.UNKNOWN || target == VisualRunStatus.UNKNOWN
                || source == null || target == null) {
            return VisualRunStatus.UNKNOWN;
        }
        return VisualRunStatus.PARTIAL;
    }

    private static String graphStatus(VisualGraphRunRecord record, List<NodeEvidence> nodes) {
        if (record.success()) {
            return VisualRunStatus.SUCCESS.name();
        }
        boolean succeeded = nodes.stream().anyMatch(node -> VisualRunStatus.SUCCESS.name().equals(node.status()));
        boolean unsuccessful = nodes.stream().anyMatch(node -> List.of(
                VisualRunStatus.FAILED.name(), VisualRunStatus.TIMEOUT.name(), VisualRunStatus.CANCELLED.name(),
                VisualRunStatus.PARTIAL.name()).contains(node.status()));
        if (succeeded && unsuccessful) {
            return VisualRunStatus.PARTIAL.name();
        }
        for (VisualRunStatus candidate : List.of(VisualRunStatus.TIMEOUT, VisualRunStatus.CANCELLED,
                VisualRunStatus.PARTIAL, VisualRunStatus.FAILED)) {
            if (nodes.stream().anyMatch(node -> candidate.name().equals(node.status()))) {
                return candidate.name();
            }
        }
        return VisualRunStatus.FAILED.name();
    }

    private static EvidenceManifest manifest(VisualGraphRunRecord record,
                                             List<NodeEvidence> nodes,
                                             List<EdgeEvidence> edges,
                                             VisualEvidenceSigner evidenceSigner) {
        int expectedNodes = record.nodeSnapshots().size();
        int capturedNodes = (int) nodes.stream().filter(node -> !VisualRunStatus.UNKNOWN.name().equals(node.status()))
                .count();
        int expectedEdges = record.edgeSnapshots().size();
        int capturedEdges = (int) edges.stream().filter(edge -> !VisualRunStatus.UNKNOWN.name().equals(edge.status()))
                .count();
        int expectedNodeInputs = (int) nodes.stream().filter(RunEvidenceBundle::requiresCapturedInput).count();
        int capturedNodeInputs = (int) nodes.stream()
                .filter(RunEvidenceBundle::requiresCapturedInput)
                .filter(NodeEvidence::inputAvailable)
                .count();
        int expectedNodeOutputs = (int) nodes.stream().filter(RunEvidenceBundle::requiresCapturedOutput).count();
        int capturedNodeOutputs = (int) nodes.stream()
                .filter(RunEvidenceBundle::requiresCapturedOutput)
                .filter(NodeEvidence::outputAvailable)
                .count();
        boolean complete = expectedNodes == capturedNodes
                && expectedEdges == capturedEdges
                && expectedNodeInputs == capturedNodeInputs
                && expectedNodeOutputs == capturedNodeOutputs;
        String hash = record.evidenceMaterialFingerprint();
        VisualRunEvidenceSeal seal = record.evidenceSeal();
        VisualEvidenceSigner.Verification verification = evidenceSigner.verify(seal, hash);
        List<String> gaps = new ArrayList<>();
        if (expectedNodes != capturedNodes || expectedEdges != capturedEdges) {
            gaps.add("Expected node or edge status was not captured.");
        }
        if (expectedNodeInputs != capturedNodeInputs) {
            gaps.add("Exact input was not captured for every invoked node.");
        }
        if (expectedNodeOutputs != capturedNodeOutputs) {
            gaps.add("Exact output was not captured for every successful node.");
        }
        if (!verification.valid()) {
            gaps.add("Evidence integrity is not verified: " + verification.reason());
        }
        String evidenceStatus = complete && verification.valid() ? "READY" : "QUARANTINED";
        return new EvidenceManifest(expectedNodes, capturedNodes, expectedEdges, capturedEdges,
                expectedNodeInputs, capturedNodeInputs, expectedNodeOutputs, capturedNodeOutputs,
                evidenceStatus, complete, gaps, hash,
                verification.status(), seal.keyId(), seal.algorithm(), seal.signedAt(), seal.signature());
    }

    private static boolean requiresCapturedInput(NodeEvidence node) {
        return !VisualRunStatus.SKIPPED.name().equals(node.status())
                && !VisualRunStatus.UNKNOWN.name().equals(node.status());
    }

    private static boolean requiresCapturedOutput(NodeEvidence node) {
        return List.of(VisualRunStatus.SUCCESS.name(), VisualRunStatus.MOCKED.name(),
                VisualRunStatus.FALLBACK.name()).contains(node.status());
    }

    public record Source(String sourceKind, String draftId, long draftRevision, String publicationId,
                         String sourceArtifactKind, String graphName, String tenantId, String namespace,
                         String environment) {
        static Source empty() { return new Source("", "", 0, "", "", "", "", "", ""); }
    }

    public record Fingerprints(String draftFingerprint, String generatedDslFingerprint,
                               String operatorDependencyFingerprint) {
        static Fingerprints empty() { return new Fingerprints("", "", ""); }
    }

    public record Execution(String status, Instant startedAt, long elapsedMs, String outputNode, boolean mockUsed) {
        static Execution empty() { return new Execution("UNKNOWN", Instant.EPOCH, 0, "", false); }
    }

    public record PayloadSummary(Map<String, Object> summary, String payloadRef) {
        public PayloadSummary {
            summary = summary == null ? Map.of() : new LinkedHashMap<>(summary);
            payloadRef = payloadRef == null ? "" : payloadRef;
        }
        static PayloadSummary empty() { return new PayloadSummary(Map.of(), ""); }
    }

    public record NodeEvidence(String nodeId, String operatorRef, String status, String reason, long elapsedMs,
                               boolean mocked, Retry retry, Fallback fallback, String inputPayloadRef,
                               String outputPayloadRef, boolean inputAvailable, boolean outputAvailable) {
    }

    public record EdgeEvidence(String edgeId, String sourceNodeId, String targetNodeId, String status,
                               String payloadRef) {
    }

    public record Retry(int attempts, int maxAttempts, String lastErrorCode) {
    }

    public record Fallback(boolean used, String strategy, String sourceNodeId) {
    }

    public record Assertions(String status, List<String> suiteRefs, List<Object> results) {
        static Assertions notRun() { return new Assertions("NOT_RUN", List.of(), List.of()); }
    }

    public record Retention(String payloadPolicy, String redactionProfile, Instant expiresAt) {
        static Retention summaryOnly() { return new Retention("SUMMARY_ONLY", "", Instant.EPOCH); }
    }

    public record EvidenceManifest(int expectedNodeCount, int capturedNodeCount, int expectedEdgeCount,
                                   int capturedEdgeCount, int expectedNodeInputCount,
                                   int capturedNodeInputCount, int expectedNodeOutputCount,
                                   int capturedNodeOutputCount, String evidenceStatus, boolean complete,
                                   List<String> gaps, String manifestHash, String signatureStatus, String keyId,
                                   String signatureAlgorithm, Instant signedAt, String signature) {
        public EvidenceManifest {
            evidenceStatus = evidenceStatus == null || evidenceStatus.isBlank() ? "QUARANTINED" : evidenceStatus;
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
            signatureAlgorithm = signatureAlgorithm == null ? "" : signatureAlgorithm;
            signedAt = signedAt == null ? Instant.EPOCH : signedAt;
            signature = signature == null ? "" : signature;
        }
        static EvidenceManifest empty() {
            return new EvidenceManifest(0, 0, 0, 0, 0, 0, 0, 0, "QUARANTINED", false,
                    List.of(), "", "UNSIGNED", "", "", Instant.EPOCH, "");
        }
    }
}
