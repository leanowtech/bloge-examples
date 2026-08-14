package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical observable-behavior identity shared by fixture and real implementation runs. */
public final class CapabilityImplementationBehaviorFingerprint {
    public static final String MATERIAL_SCHEMA_VERSION =
            "resourceGateway.capabilityImplementationBehavior.v1";
    private static final int MAXIMUM_VALUE_BYTES = 16 * 1024 * 1024;

    private CapabilityImplementationBehaviorFingerprint() {
    }

    /** Computes the behavior identity of the accepted payload-free Mirror baseline. */
    public static String baseline(ObjectMapper mapper, MirrorRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        List<Map<String, Object>> nodes = evidence.nodeTraces().stream()
                .map(CapabilityImplementationBehaviorFingerprint::baselineNode)
                .sorted(Comparator.comparing(CapabilityImplementationBehaviorFingerprint::key))
                .toList();
        List<Map<String, Object>> edges = evidence.edgeTraces().stream()
                .map(CapabilityImplementationBehaviorFingerprint::baselineEdge)
                .sorted(Comparator.comparing(CapabilityImplementationBehaviorFingerprint::key))
                .toList();
        return fingerprint(mapper, nodes, edges);
    }

    /** Computes the behavior identity of a real implementation conformance run. */
    public static String implementation(ObjectMapper mapper, TestRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        List<Map<String, Object>> nodes = evidence.nodeTrace().stream()
                .map(value -> implementationNode(mapper, value))
                .sorted(Comparator.comparing(CapabilityImplementationBehaviorFingerprint::key))
                .toList();
        List<Map<String, Object>> edges = evidence.edgeTrace().stream()
                .map(value -> implementationEdge(mapper, value))
                .sorted(Comparator.comparing(CapabilityImplementationBehaviorFingerprint::key))
                .toList();
        return fingerprint(mapper, nodes, edges);
    }

    private static String fingerprint(
            ObjectMapper mapper, List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return ProtocolFingerprint.ofBounded(Objects.requireNonNull(mapper, "mapper"), Map.of(
                "schemaVersion", MATERIAL_SCHEMA_VERSION,
                "nodes", nodes,
                "edges", edges), 64 * 1024 * 1024);
    }

    private static Map<String, Object> baselineNode(MirrorRunEvidence.NodeTrace node) {
        List<Map<String, Object>> attempts = node.attempts().stream()
                .map(value -> attempt(value.attempt(), value.status(), value.inputFingerprint(),
                        value.outputFingerprint(), value.errorCode()))
                .toList();
        return node(node.nodeId(), node.operatorRef(), node.status(), node.inputFingerprint(),
                node.outputFingerprint(), node.errorCode(), node.invocationSiteId(),
                node.graphPath(), node.correlationKey(), node.occurrence(), node.graphOccurrence(),
                attempts);
    }

    private static Map<String, Object> implementationNode(
            ObjectMapper mapper, TestRunEvidence.NodeTrace node) {
        List<Map<String, Object>> attempts = node.attempts().stream()
                .map(value -> attempt(value.attempt(), value.status(), value(mapper, value.input()),
                        value(mapper, value.output()), value.errorCode()))
                .toList();
        return node(node.nodeId(), node.operatorRef(), node.status(), value(mapper, node.input()),
                value(mapper, node.output()), node.errorCode(), node.invocationSiteId(),
                node.graphPath(), node.correlationKey(), node.occurrence(), node.graphOccurrence(),
                attempts);
    }

    private static Map<String, Object> node(
            String nodeId,
            String operatorRef,
            String status,
            String inputFingerprint,
            String outputFingerprint,
            String errorCode,
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int graphOccurrence,
            List<Map<String, Object>> attempts) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "NODE");
        value.put("nodeId", nodeId);
        value.put("operatorRef", operatorRef);
        value.put("status", normalizedStatus(status));
        value.put("inputFingerprint", inputFingerprint);
        value.put("outputFingerprint", outputFingerprint);
        value.put("errorCode", errorCode);
        value.put("invocationSiteId", invocationSiteId);
        value.put("graphPath", graphPath);
        value.put("correlationKey", correlationKey);
        value.put("occurrence", occurrence);
        value.put("graphOccurrence", graphOccurrence);
        value.put("attempts", attempts);
        return Map.copyOf(value);
    }

    private static Map<String, Object> attempt(
            int attempt,
            String status,
            String inputFingerprint,
            String outputFingerprint,
            String errorCode) {
        return Map.of(
                "attempt", attempt,
                "status", normalizedStatus(status),
                "inputFingerprint", inputFingerprint,
                "outputFingerprint", outputFingerprint,
                "errorCode", errorCode == null ? "" : errorCode);
    }

    private static Map<String, Object> baselineEdge(MirrorRunEvidence.EdgeTrace edge) {
        return edge(edge.edgeId(), edge.status(), edge.valueFingerprint(), edge.graphPath(),
                edge.correlationKey(), edge.graphOccurrence(), edge.fromInvocationSiteId(),
                edge.toInvocationSiteId());
    }

    private static Map<String, Object> implementationEdge(
            ObjectMapper mapper, TestRunEvidence.EdgeTrace edge) {
        return edge(edge.edgeId(), edge.status(), value(mapper, edge.value()), edge.graphPath(),
                edge.correlationKey(), edge.graphOccurrence(), edge.fromInvocationSiteId(),
                edge.toInvocationSiteId());
    }

    private static Map<String, Object> edge(
            String edgeId,
            String status,
            String valueFingerprint,
            String graphPath,
            String correlationKey,
            int graphOccurrence,
            String fromInvocationSiteId,
            String toInvocationSiteId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "EDGE");
        value.put("edgeId", edgeId);
        value.put("status", normalizedStatus(status));
        value.put("valueFingerprint", valueFingerprint);
        value.put("graphPath", graphPath);
        value.put("correlationKey", correlationKey);
        value.put("graphOccurrence", graphOccurrence);
        value.put("fromInvocationSiteId", fromInvocationSiteId);
        value.put("toInvocationSiteId", toInvocationSiteId);
        return Map.copyOf(value);
    }

    private static String value(ObjectMapper mapper, Object value) {
        return ProtocolFingerprint.ofBounded(mapper, value, MAXIMUM_VALUE_BYTES);
    }

    private static String normalizedStatus(String status) {
        return "MOCKED".equals(status) ? "SUCCESS" : status;
    }

    private static String key(Map<String, Object> value) {
        List<String> coordinates = new ArrayList<>();
        coordinates.add(String.valueOf(value.get("kind")));
        coordinates.add(String.valueOf(value.getOrDefault("graphPath", "")));
        coordinates.add(String.valueOf(value.getOrDefault("graphOccurrence", 0)));
        coordinates.add(String.valueOf(value.getOrDefault("invocationSiteId", "")));
        coordinates.add(String.valueOf(value.getOrDefault("edgeId", "")));
        coordinates.add(String.valueOf(value.getOrDefault("correlationKey", "")));
        coordinates.add(String.valueOf(value.getOrDefault("occurrence", 0)));
        return String.join("\u0000", coordinates);
    }
}
