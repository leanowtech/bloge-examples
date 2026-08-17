package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.capabilitystudio.CapabilityStudioDataLensProjection.PermissionMode;

/**
 * Projects existing {@link TestRunEvidence} into a bounded Data Lens read model.
 *
 * <p>It intentionally has no runtime dependency other than the evidence value. In particular, it
 * does not execute operators, resolve fixtures, or recompute edge transfers.</p>
 */
public final class CapabilityStudioDataLensProjector {
    public static final int MAX_NODES = 256;
    public static final int MAX_EDGES = 512;
    public static final int MAX_ATTEMPTS_PER_NODE = 16;
    public static final int MAX_STRING_LENGTH = 256;
    private static final int MAX_FINGERPRINT_MATERIAL_BYTES = 16 * 1_048_576;

    private final ObjectMapper objectMapper;

    public CapabilityStudioDataLensProjector(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Projects the trace with the requested payload permission. */
    public CapabilityStudioDataLensProjection project(TestRunEvidence evidence,
                                                       PermissionMode permissionMode) {
        if (evidence == null) {
            throw new IllegalArgumentException("evidence must not be null");
        }
        if (permissionMode == null) {
            throw new IllegalArgumentException("permissionMode must not be null");
        }

        List<TestRunEvidence.NodeTrace> sourceNodes = sortedNodes(evidence.nodeTrace());
        List<TestRunEvidence.EdgeTrace> sourceEdges = sortedEdges(evidence.edgeTrace());
        int nodeLimit = Math.min(MAX_NODES, sourceNodes.size());
        int edgeLimit = Math.min(MAX_EDGES, sourceEdges.size());
        List<CapabilityStudioDataLensProjection.Node> nodes = new ArrayList<>(nodeLimit);
        int omittedAttempts = sourceNodes.subList(0, nodeLimit).stream()
                .mapToInt(source -> Math.max(0, source.attempts().size() - MAX_ATTEMPTS_PER_NODE))
                .sum();
        for (int index = 0; index < nodeLimit; index++) {
            TestRunEvidence.NodeTrace source = sourceNodes.get(index);
            int attemptLimit = Math.min(MAX_ATTEMPTS_PER_NODE, source.attempts().size());
            nodes.add(projectNode(source, permissionMode, attemptLimit));
        }

        List<CapabilityStudioDataLensProjection.Edge> edges = new ArrayList<>(edgeLimit);
        for (int index = 0; index < edgeLimit; index++) {
            edges.add(projectEdge(sourceEdges.get(index), permissionMode));
        }

        CapabilityStudioDataLensProjection.Truncation truncation =
                new CapabilityStudioDataLensProjection.Truncation(
                        sourceNodes.size() > nodeLimit, sourceNodes.size() - nodeLimit,
                        sourceEdges.size() > edgeLimit, sourceEdges.size() - edgeLimit,
                        omittedAttempts > 0, omittedAttempts);
        CapabilityStudioDataLensProjection.FirstDifference firstDifference =
                firstDifference(evidence.assertionResults(), permissionMode);
        String runId = bounded(evidence.runId());
        String fingerprint = ProtocolFingerprint.ofBounded(objectMapper, new FingerprintMaterial(
                runId, evidence.status().name(), permissionMode,
                nodes, edges, firstDifference, truncation), MAX_FINGERPRINT_MATERIAL_BYTES);
        return new CapabilityStudioDataLensProjection(
                CapabilityStudioDataLensProjection.SCHEMA_VERSION,
                runId, evidence.status().name(), permissionMode,
                nodes, edges, firstDifference, truncation, fingerprint);
    }

    private CapabilityStudioDataLensProjection.Node projectNode(TestRunEvidence.NodeTrace source,
                                                                PermissionMode mode,
                                                                int attemptLimit) {
        List<CapabilityStudioDataLensProjection.Attempt> attempts = new ArrayList<>(attemptLimit);
        for (int index = 0; index < attemptLimit; index++) {
            attempts.add(projectAttempt(source.attempts().get(index), mode));
        }
        return new CapabilityStudioDataLensProjection.Node(
                bounded(source.nodeId()), bounded(source.operatorRef()), bounded(source.status()),
                bounded(source.fidelity()), bounded(source.graphPath()), bounded(source.invocationSiteId()),
                bounded(source.correlationKey()), source.occurrence(), source.graphOccurrence(),
                visible(source.input(), mode), fingerprint(source.input()),
                visible(source.output(), mode), fingerprint(source.output()),
                safeErrorCode(source.errorCode()), source.durationMs(), attempts,
                Math.max(0, source.attempts().size() - 1), exactFallback(source));
    }

    private CapabilityStudioDataLensProjection.Attempt projectAttempt(
            TestRunEvidence.AttemptTrace source, PermissionMode mode) {
        return new CapabilityStudioDataLensProjection.Attempt(
                source.attempt(), bounded(source.status()), bounded(source.fidelity()),
                visible(source.input(), mode), fingerprint(source.input()),
                visible(source.output(), mode), fingerprint(source.output()),
                safeErrorCode(source.errorCode()), source.durationMs());
    }

    private CapabilityStudioDataLensProjection.Edge projectEdge(TestRunEvidence.EdgeTrace source,
                                                                PermissionMode mode) {
        return new CapabilityStudioDataLensProjection.Edge(
                bounded(source.edgeId()), bounded(source.status()), bounded(source.graphPath()),
                bounded(source.correlationKey()), source.graphOccurrence(),
                bounded(source.fromInvocationSiteId()), bounded(source.toInvocationSiteId()),
                visible(source.value(), mode), fingerprint(source.value()));
    }

    private CapabilityStudioDataLensProjection.FirstDifference firstDifference(
            List<TestRunEvidence.AssertionResult> assertions, PermissionMode mode) {
        if (assertions == null) {
            return null;
        }
        for (int index = 0; index < assertions.size(); index++) {
            TestRunEvidence.AssertionResult assertion = assertions.get(index);
            if (assertion != null && !assertion.passed()) {
                return difference(index, assertion, mode);
            }
        }
        return null;
    }

    private CapabilityStudioDataLensProjection.FirstDifference difference(
            int index, TestRunEvidence.AssertionResult source, PermissionMode mode) {
        return new CapabilityStudioDataLensProjection.FirstDifference(
                "ASSERTION", "/assertions/" + index, bounded(source.scope()),
                bounded(source.path()), visible(source.expected(), mode), fingerprint(source.expected()),
                visible(source.actual(), mode), fingerprint(source.actual()));
    }

    private static List<TestRunEvidence.NodeTrace> sortedNodes(List<TestRunEvidence.NodeTrace> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing((TestRunEvidence.NodeTrace value) -> bounded(value.graphPath()))
                        .thenComparing(value -> bounded(value.invocationSiteId()))
                        .thenComparing(value -> bounded(value.correlationKey()))
                        .thenComparingInt(TestRunEvidence.NodeTrace::graphOccurrence)
                        .thenComparingInt(TestRunEvidence.NodeTrace::occurrence)
                        .thenComparing(value -> bounded(value.nodeId())))
                .toList();
    }

    private static List<TestRunEvidence.EdgeTrace> sortedEdges(List<TestRunEvidence.EdgeTrace> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing((TestRunEvidence.EdgeTrace value) -> bounded(value.graphPath()))
                        .thenComparing(value -> bounded(value.correlationKey()))
                        .thenComparingInt(TestRunEvidence.EdgeTrace::graphOccurrence)
                        .thenComparing(value -> bounded(value.edgeId())))
                .toList();
    }

    private Object visible(Object value, PermissionMode mode) {
        return mode == PermissionMode.PAYLOAD_VISIBLE ? value : null;
    }

    private String fingerprint(Object value) {
        return value == null ? "" : ProtocolFingerprint.ofBounded(
                objectMapper, value, MAX_FINGERPRINT_MATERIAL_BYTES);
    }

    private static String exactFallback(TestRunEvidence.NodeTrace source) {
        if ("FALLBACK".equalsIgnoreCase(source.status())) {
            return "FALLBACK";
        }
        String explicit = source.attempts().stream()
                .filter(java.util.Objects::nonNull)
                .map(TestRunEvidence.AttemptTrace::status)
                .filter(value -> "FALLBACK".equalsIgnoreCase(value))
                .findFirst().map(value -> "FALLBACK").orElse("");
        if (!explicit.isBlank()) {
            return explicit;
        }
        if (!("SUCCESS".equalsIgnoreCase(source.status())
                || "MOCKED".equalsIgnoreCase(source.status()))) {
            return "";
        }
        TestRunEvidence.AttemptTrace last = source.attempts().stream()
                .filter(java.util.Objects::nonNull)
                .max(Comparator.comparingInt(TestRunEvidence.AttemptTrace::attempt))
                .orElse(null);
        return last != null && ("FAILED".equalsIgnoreCase(last.status())
                || "TIMEOUT".equalsIgnoreCase(last.status())) ? "FALLBACK" : "";
    }

    private static String safeErrorCode(String value) {
        String safe = bounded(value);
        return safe.matches("[A-Za-z0-9_.:-]{0,128}") ? safe : "UNSAFE_ERROR_CODE";
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_STRING_LENGTH
                ? trimmed : trimmed.substring(0, MAX_STRING_LENGTH);
    }

    private record FingerprintMaterial(
            String runId,
            String runStatus,
            PermissionMode permissionMode,
            List<CapabilityStudioDataLensProjection.Node> nodes,
            List<CapabilityStudioDataLensProjection.Edge> edges,
            CapabilityStudioDataLensProjection.FirstDifference firstDifference,
            CapabilityStudioDataLensProjection.Truncation truncation
    ) {
    }
}
