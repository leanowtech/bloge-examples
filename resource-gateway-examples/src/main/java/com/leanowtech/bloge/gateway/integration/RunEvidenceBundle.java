package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlView;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunRecoveryMetadata;

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
        Replay replay,
        Recovery recovery,
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
    public static final String SCHEMA_VERSION_V1 = "toolStudio.resourceGateway.runEvidenceBundle.v1";
    public static final String SCHEMA_VERSION_V2 = "toolStudio.resourceGateway.runEvidenceBundle.v2";
    public static final String SCHEMA_VERSION_V3 = "toolStudio.resourceGateway.runEvidenceBundle.v3";
    public static final String SCHEMA_VERSION_V4 = "toolStudio.resourceGateway.runEvidenceBundle.v4";
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.runEvidenceBundle.v5";

    public RunEvidenceBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        evidenceId = evidenceId == null ? "" : evidenceId;
        runId = runId == null ? "" : runId;
        source = source == null ? Source.empty() : source;
        replay = replay == null ? Replay.none() : replay;
        recovery = recovery == null ? Recovery.none() : recovery;
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
        Execution execution = graphExecution(record, nodes);
        Retention retention = new Retention("SANITIZED", record.redaction().profile(),
                record.createdAt().plus(30, ChronoUnit.DAYS));
        EvidenceManifest manifest = manifest(record, nodes, edges,
                evidenceSigner == null ? VisualEvidenceSigner.unavailable() : evidenceSigner);
        Replay replay = new Replay(record.replay().parentRunId(), record.replay().requestId(),
                record.replay().mode(), record.replay().caseType(), record.replay().sideEffectPolicy(),
                record.replay().externalInvocationCount());
        Assertions assertions = record.replay().replay()
                ? new Assertions(record.replay().assertionsPassed() ? "PASSED" : "FAILED",
                        List.of("replay-request:" + record.replay().requestId()),
                        record.replay().assertionResults().stream().map(result -> (Object) result).toList())
                : Assertions.notRun();
        Recovery recovery = Recovery.from(record.recovery());
        return new RunEvidenceBundle("", "evidence:" + record.runId(), record.runId(), source, replay, recovery,
                fingerprints,
                execution,
                new PayloadSummary(record.contextSummary(), "payload:" + record.runId() + ":context"),
                new PayloadSummary(record.outputSummary(), "payload:" + record.runId() + ":output"),
                nodes, edges, assertions, record.diagnostics(), record.errors(), retention, manifest);
    }

    private static String dslFingerprint(VisualGraphRunRecord record) {
        return VisualBundleFingerprint.fromMaterial(Map.of("generatedDsl", record.generatedDsl()));
    }

    private static List<NodeEvidence> nodeEvidence(VisualGraphRunRecord record) {
        Set<String> nodeIds = new LinkedHashSet<>(record.nodeSnapshots().keySet());
        nodeIds.addAll(record.statusMap().keySet());
        nodeIds.addAll(record.resultsPayload().keySet());
        nodeIds.addAll(record.nodeAttempts().keySet());
        nodeIds.addAll(record.nodeExecutionFacts().keySet());
        List<NodeEvidence> nodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            VisualGraphRunRecord.NodeSnapshot snapshot = record.nodeSnapshots().get(nodeId);
            VisualNodeExecutionFact fact = record.nodeExecutionFacts().get(nodeId);
            List<VisualNodeExecutionAttempt> attempts = record.nodeAttempts().getOrDefault(nodeId, List.of());
            VisualNodeExecutionAttempt latestAttempt = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
            VisualRunStatus status = nodeStatus(record.statusMap().get(nodeId), fact);
            boolean inputAvailable = latestAttempt != null;
            boolean outputAvailable = record.resultsPayload().containsKey(nodeId)
                    || latestAttempt != null && latestAttempt.output() != null;
            nodes.add(new NodeEvidence(
                    nodeId,
                    snapshot == null ? "" : snapshot.operatorRef(),
                    status.name(),
                    fact == null ? status == VisualRunStatus.UNKNOWN ? "STATUS_NOT_CAPTURED" : "LEGACY_STATUS_ONLY"
                            : fact.reasonCode(),
                    fact == null ? "LEGACY_DERIVATION" : fact.observationSource(),
                    fact == null ? List.of() : fact.causedByNodeIds(),
                    record.nodeElapsedMs().getOrDefault(nodeId, 0L),
                    status == VisualRunStatus.MOCKED,
                    retry(fact, attempts, latestAttempt),
                    fallback(fact),
                    sideEffectOutcome(snapshot, fact, status),
                    "payload:" + record.runId() + ":node." + nodeId + ".input",
                    outputAvailable ? "payload:" + record.runId() + ":node." + nodeId + ".output" : "",
                    inputAvailable,
                    outputAvailable
            ));
        }
        return nodes;
    }

    private static Retry retry(VisualNodeExecutionFact fact, List<VisualNodeExecutionAttempt> attempts,
                               VisualNodeExecutionAttempt latestAttempt) {
        if (fact == null) {
            return new Retry(attempts.isEmpty() ? 0 : 1, 0, false,
                    latestAttempt == null ? "" : latestAttempt.errorType());
        }
        return new Retry(fact.retry().observedAttempts(), fact.retry().configuredMaxAttempts(),
                fact.retry().exhausted(), fact.retry().lastErrorType());
    }

    private static Fallback fallback(VisualNodeExecutionFact fact) {
        if (fact == null) {
            return new Fallback(false, false, "NOT_CAPTURED", "");
        }
        return new Fallback(fact.fallback().configured(), fact.fallback().used(), fact.fallback().strategy(),
                fact.fallback().originalErrorType());
    }

    private static String sideEffectOutcome(VisualGraphRunRecord.NodeSnapshot snapshot,
                                            VisualNodeExecutionFact fact,
                                            VisualRunStatus status) {
        if (status == VisualRunStatus.MOCKED) {
            return "NOT_INVOKED";
        }
        if (fact != null && !"NOT_CAPTURED".equals(fact.sideEffectOutcome())) {
            return fact.sideEffectOutcome();
        }
        String effect = snapshot == null ? "UNKNOWN" : snapshot.effect();
        if (Set.of("PURE", "READ").contains(effect)) {
            return "NOT_APPLICABLE";
        }
        return "UNKNOWN_COMMIT";
    }

    private static VisualRunStatus nodeStatus(String runtimeStatus, VisualNodeExecutionFact fact) {
        return fact == null ? VisualRunStatus.fromRuntime(runtimeStatus) : VisualRunStatus.fromRuntime(fact.status());
    }

    private static List<EdgeEvidence> edgeEvidence(VisualGraphRunRecord record) {
        Map<String, VisualRunStatus> statuses = new LinkedHashMap<>();
        record.statusMap().forEach((nodeId, status) -> {
            statuses.put(nodeId, nodeStatus(status, record.nodeExecutionFacts().get(nodeId)));
        });
        return record.edgeSnapshots().stream()
                .map(edge -> {
                    VisualRunStatus source = statuses.get(edge.sourceNodeId());
                    VisualRunStatus target = statuses.get(edge.targetNodeId());
                    boolean propagated = edgePropagated(source, target);
                    return new EdgeEvidence(
                            edge.edgeId(), edge.sourceNodeId(), edge.targetNodeId(),
                            edgeStatus(source, target).name(), edgeReason(source, target),
                            "TOPOLOGY_DERIVATION", propagated,
                            propagated ? "payload:" + record.runId() + ":edge." + edge.edgeId() : "");
                })
                .toList();
    }

    private static VisualRunStatus edgeStatus(VisualRunStatus source, VisualRunStatus target) {
        if (source == VisualRunStatus.MOCKED && target == VisualRunStatus.MOCKED) {
            return VisualRunStatus.MOCKED;
        }
        if (target == VisualRunStatus.SKIPPED) {
            return VisualRunStatus.SKIPPED;
        }
        if (source != null
                && Set.of(VisualRunStatus.FAILED, VisualRunStatus.TIMEOUT, VisualRunStatus.CANCELLED).contains(source)) {
            return VisualRunStatus.CANCELLED;
        }
        if (Set.of(VisualRunStatus.SUCCESS, VisualRunStatus.FALLBACK, VisualRunStatus.MOCKED).contains(source)
                && target != null && target != VisualRunStatus.UNKNOWN && target != VisualRunStatus.CANCELLED) {
            return VisualRunStatus.SUCCESS;
        }
        if (source == VisualRunStatus.UNKNOWN || target == VisualRunStatus.UNKNOWN
                || source == null || target == null) {
            return VisualRunStatus.UNKNOWN;
        }
        return VisualRunStatus.PARTIAL;
    }

    private static String edgeReason(VisualRunStatus source, VisualRunStatus target) {
        if (target == VisualRunStatus.SKIPPED) {
            return "BRANCH_NOT_TAKEN";
        }
        if (source != null
                && Set.of(VisualRunStatus.FAILED, VisualRunStatus.TIMEOUT, VisualRunStatus.CANCELLED).contains(source)) {
            return "UPSTREAM_FAILURE_PROPAGATED";
        }
        if (edgePropagated(source, target)) {
            return source == VisualRunStatus.MOCKED ? "RECORDED_PAYLOAD_REPLAY" : "VALUE_PROPAGATED";
        }
        return "PROPAGATION_NOT_CAPTURED";
    }

    private static boolean edgePropagated(VisualRunStatus source, VisualRunStatus target) {
        return source != null
                && Set.of(VisualRunStatus.SUCCESS, VisualRunStatus.FALLBACK, VisualRunStatus.MOCKED).contains(source)
                && target != null && !Set.of(VisualRunStatus.SKIPPED, VisualRunStatus.CANCELLED,
                VisualRunStatus.UNKNOWN).contains(target);
    }

    private static Execution graphExecution(VisualGraphRunRecord record, List<NodeEvidence> nodes) {
        NodeEvidence output = nodes.stream().filter(node -> node.nodeId().equals(record.outputNode())).findFirst()
                .orElse(null);
        boolean outputReached = output != null && output.outputAvailable()
                && Set.of("SUCCESS", "FALLBACK", "MOCKED").contains(output.status());
        boolean degraded = nodes.stream().anyMatch(node -> Set.of("FAILED", "TIMEOUT", "CANCELLED", "PARTIAL")
                .contains(node.status()));
        String status = graphStatus(record, nodes, outputReached, degraded);
        RunControl control = RunControl.from(record.runControl());
        String reason = controlledReason(control, record.success() ? "NONE"
                : outputReached && degraded ? "CRITICAL_OUTPUT_REACHED_WITH_DEGRADATION"
                : output == null ? "OUTPUT_NODE_NOT_CAPTURED"
                : "CRITICAL_OUTPUT_NOT_REACHED");
        return new Execution(status, reason, record.createdAt(), record.elapsedMs(), record.outputNode(),
                nodes.stream().anyMatch(NodeEvidence::mocked), outputReached, degraded, control);
    }

    private static String graphStatus(VisualGraphRunRecord record, List<NodeEvidence> nodes,
                                      boolean outputReached, boolean degraded) {
        String controlStatus = record.runControl().status();
        if ("TIMED_OUT".equals(controlStatus) || "TIMING_OUT".equals(controlStatus)) {
            return VisualRunStatus.TIMEOUT.name();
        }
        if ("CANCELLED".equals(controlStatus) || "CANCEL_REQUESTED".equals(controlStatus)) {
            return VisualRunStatus.CANCELLED.name();
        }
        if ("TERMINATION_UNCONFIRMED".equals(controlStatus)) {
            return VisualRunStatus.PARTIAL.name();
        }
        if (record.success()) {
            return VisualRunStatus.SUCCESS.name();
        }
        if (outputReached && degraded) {
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

    private static String controlledReason(RunControl control, String fallback) {
        if (control == null || !control.managed() || "SUCCEEDED".equals(control.status())) {
            return fallback;
        }
        return control.reasonCode();
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
        int expectedNodeFacts = expectedNodes;
        int capturedNodeFacts = (int) record.nodeExecutionFacts().values().stream()
                .filter(fact -> !Set.of("NOT_CAPTURED", "ENGINE_STATUS_WITH_EVENT_GAP")
                        .contains(fact.observationSource()))
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
        boolean controlComplete = record.runControl().requestId().isBlank()
                || record.runControl().terminationConfirmed() && !record.runControl().sideEffectsMayBeInFlight();
        boolean recoveryComplete = !record.recovery().recovered();
        boolean complete = expectedNodes == capturedNodes
                && expectedEdges == capturedEdges
                && expectedNodeInputs == capturedNodeInputs
                && expectedNodeOutputs == capturedNodeOutputs
                && expectedNodeFacts == capturedNodeFacts
                && controlComplete
                && recoveryComplete;
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
        if (expectedNodeFacts != capturedNodeFacts) {
            gaps.add("Structured execution semantics were not captured for every node.");
        }
        if (!controlComplete) {
            gaps.add("Controlled run termination is not confirmed; external side effects may still be in flight.");
        }
        if (!recoveryComplete) {
            gaps.add("Run evidence was synthesized from durable recovery facts after the normal evidence transaction did not complete.");
        }
        if (!verification.valid()) {
            gaps.add("Evidence integrity is not verified: " + verification.reason());
        }
        String evidenceStatus = complete && verification.valid() ? "READY" : "QUARANTINED";
        return new EvidenceManifest(expectedNodes, capturedNodes, expectedEdges, capturedEdges,
                expectedNodeInputs, capturedNodeInputs, expectedNodeOutputs, capturedNodeOutputs,
                evidenceStatus, complete, gaps, hash,
                verification.status(), seal.keyId(), seal.algorithm(), seal.signedAt(), seal.signature(),
                expectedNodeFacts, capturedNodeFacts);
    }

    private static boolean requiresCapturedInput(NodeEvidence node) {
        return !VisualRunStatus.SKIPPED.name().equals(node.status())
                && !VisualRunStatus.CANCELLED.name().equals(node.status())
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

    public record Replay(String parentRunId, String requestId, String mode, String caseType,
                         String sideEffectPolicy, int externalInvocationCount) {
        public Replay {
            parentRunId = parentRunId == null ? "" : parentRunId;
            requestId = requestId == null ? "" : requestId;
            mode = mode == null || mode.isBlank() ? "NONE" : mode;
            caseType = caseType == null ? "" : caseType;
            sideEffectPolicy = sideEffectPolicy == null || sideEffectPolicy.isBlank() ? "DENY" : sideEffectPolicy;
            externalInvocationCount = Math.max(0, externalInvocationCount);
        }
        static Replay none() { return new Replay("", "", "NONE", "", "DENY", 0); }
    }

    public record Recovery(String mode, String reservationId, String reservationFingerprint,
                           Instant recoveredAt, long controlRevision, int attempt, String triggerReason) {
        public Recovery {
            mode = mode == null || mode.isBlank() ? VisualRunRecoveryMetadata.MODE_NONE : mode;
            reservationId = reservationId == null ? "" : reservationId;
            reservationFingerprint = reservationFingerprint == null ? "" : reservationFingerprint;
            recoveredAt = recoveredAt == null ? Instant.EPOCH : recoveredAt;
            controlRevision = Math.max(0, controlRevision);
            attempt = Math.max(0, attempt);
            triggerReason = triggerReason == null || triggerReason.isBlank() ? "NONE" : triggerReason;
        }

        static Recovery from(VisualRunRecoveryMetadata source) {
            if (source == null || !source.recovered()) {
                return none();
            }
            return new Recovery(source.mode(), source.reservationId(), source.reservationFingerprint(),
                    source.recoveredAt(), source.controlRevision(), source.attempt(), source.triggerReason());
        }

        static Recovery none() {
            return new Recovery(VisualRunRecoveryMetadata.MODE_NONE, "", "", Instant.EPOCH, 0, 0, "NONE");
        }
    }

    public record Fingerprints(String draftFingerprint, String generatedDslFingerprint,
                               String operatorDependencyFingerprint) {
        static Fingerprints empty() { return new Fingerprints("", "", ""); }
    }

    public record Execution(String status, String reasonCode, Instant startedAt, long elapsedMs, String outputNode,
                            boolean mockUsed, boolean criticalOutputReached, boolean degraded,
                            RunControl runControl) {
        public Execution {
            runControl = runControl == null ? RunControl.unmanaged() : runControl;
        }
        static Execution empty() {
            return new Execution("UNKNOWN", "STATUS_NOT_CAPTURED", Instant.EPOCH, 0, "", false, false, false,
                    RunControl.unmanaged());
        }
    }

    public record RunControl(String requestId, String engineExecutionId, String status, String reasonCode,
                             long revision, Instant deadlineAt, Instant cancelRequestedAt, Instant terminalAt,
                             boolean terminationConfirmed, boolean sideEffectsMayBeInFlight) {
        public RunControl {
            requestId = requestId == null ? "" : requestId;
            engineExecutionId = engineExecutionId == null ? "" : engineExecutionId;
            status = status == null || status.isBlank() ? "UNMANAGED" : status;
            reasonCode = reasonCode == null || reasonCode.isBlank() ? "NONE" : reasonCode;
            revision = Math.max(0, revision);
        }

        static RunControl from(VisualRunControlView source) {
            if (source == null || source.requestId().isBlank()) {
                return unmanaged();
            }
            return new RunControl(source.requestId(), source.engineExecutionId(), source.status(),
                    source.reasonCode(), source.revision(), source.deadlineAt(), source.cancelRequestedAt(),
                    source.terminalAt(), source.terminationConfirmed(), source.sideEffectsMayBeInFlight());
        }

        static RunControl unmanaged() {
            return new RunControl("", "", "UNMANAGED", "NONE", 0, null, null, null, true, false);
        }

        boolean managed() {
            return !requestId.isBlank();
        }
    }

    public record PayloadSummary(Map<String, Object> summary, String payloadRef) {
        public PayloadSummary {
            summary = summary == null ? Map.of() : new LinkedHashMap<>(summary);
            payloadRef = payloadRef == null ? "" : payloadRef;
        }
        static PayloadSummary empty() { return new PayloadSummary(Map.of(), ""); }
    }

    public record NodeEvidence(String nodeId, String operatorRef, String status, String reasonCode,
                               String observationSource, List<String> causedByNodeIds, long elapsedMs,
                               boolean mocked, Retry retry, Fallback fallback, String sideEffectOutcome,
                               String inputPayloadRef,
                               String outputPayloadRef, boolean inputAvailable, boolean outputAvailable) {
    }

    public record EdgeEvidence(String edgeId, String sourceNodeId, String targetNodeId, String status,
                               String reasonCode, String observationSource, boolean propagated, String payloadRef) {
    }

    public record Retry(int attempts, int maxAttempts, boolean exhausted, String lastErrorCode) {
    }

    public record Fallback(boolean configured, boolean used, String strategy, String originalErrorType) {
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
                                   String signatureAlgorithm, Instant signedAt, String signature,
                                   int expectedNodeFactCount, int capturedNodeFactCount) {
        public EvidenceManifest {
            evidenceStatus = evidenceStatus == null || evidenceStatus.isBlank() ? "QUARANTINED" : evidenceStatus;
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
            signatureAlgorithm = signatureAlgorithm == null ? "" : signatureAlgorithm;
            signedAt = signedAt == null ? Instant.EPOCH : signedAt;
            signature = signature == null ? "" : signature;
            expectedNodeFactCount = Math.max(0, expectedNodeFactCount);
            capturedNodeFactCount = Math.max(0, capturedNodeFactCount);
        }
        static EvidenceManifest empty() {
            return new EvidenceManifest(0, 0, 0, 0, 0, 0, 0, 0, "QUARANTINED", false,
                    List.of(), "", "UNSIGNED", "", "", Instant.EPOCH, "", 0, 0);
        }
    }
}
