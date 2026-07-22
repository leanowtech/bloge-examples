package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.List;
import java.util.Locale;

/** Shared node-aware identity rules for capability projection and closure assembly. */
final class CapabilityBoundaryResolver {
    private static final List<String> GRAPH_SOURCE_KINDS = List.of(
            "GRAPH", "COMPOSED_GRAPH", "NESTED_GRAPH");
    private static final List<String> EXTERNAL_SOURCE_KINDS = List.of(
            "REMOTE_WORKER", "AI_TOOL", "EVENT_SOURCE", "MESSAGE_HANDLER", "WEBHOOK");

    private CapabilityBoundaryResolver() {
    }

    static boolean isBoundary(OperatorDefinition operator) {
        String effect = normalize(operator.capabilities().effect());
        String sourceKind = normalize(operator.source().kind());
        return !"PURE".equals(effect)
                || !operator.source().resourceId().isBlank()
                || GRAPH_SOURCE_KINDS.contains(sourceKind)
                || EXTERNAL_SOURCE_KINDS.contains(sourceKind);
    }

    static Target resolve(GraphDraft.DraftNode node, OperatorDefinition operator) {
        String sourceKind = normalize(operator.source().kind());
        if (GRAPH_SOURCE_KINDS.contains(sourceKind)) {
            String sourceRef = operator.source().resourceId().isBlank()
                    ? operator.operatorRef() : operator.source().resourceId();
            return new Target(CapabilitySnapshot.SourceKind.GRAPH, sourceRef);
        }
        if (!operator.source().resourceId().isBlank()) {
            return new Target(CapabilitySnapshot.SourceKind.RESOURCE, operator.source().resourceId());
        }
        String resourceId = staticResourceId(node, operator);
        return resourceId == null
                ? new Target(CapabilitySnapshot.SourceKind.OPERATOR, operator.operatorRef())
                : new Target(CapabilitySnapshot.SourceKind.RESOURCE, resourceId);
    }

    static String staticResourceId(GraphDraft.DraftNode node, OperatorDefinition operator) {
        if (!"httpResource".equals(operator.operatorRef())) {
            return null;
        }
        GraphDraft.Binding binding = node.inputs().get("resourceId");
        if (binding == null || !"constant".equals(binding.kind()) || !(binding.value() instanceof String value)) {
            return null;
        }
        String resourceId = value.trim();
        return resourceId.isEmpty() ? null : resourceId;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    record Target(CapabilitySnapshot.SourceKind sourceKind, String sourceRef) {
    }
}
