package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.ConditionalEdge;
import com.leanowtech.bloge.core.model.DirectEdge;
import com.leanowtech.bloge.core.model.Edge;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.StreamEdge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable target fingerprint projection for graph-contract test targets.
 *
 * <p>Executable Java predicates cannot be serialized safely. Published graphs therefore include
 * their recoverable definition source, while inline graphs still freeze every inspectable topology
 * and schema fact. A caller must not treat an inline conditional graph without a definition source
 * as publication-grade evidence.</p>
 */
public final class GraphArtifactFingerprint {

    private GraphArtifactFingerprint() {
    }

    /**
     * Fingerprints graph identity, node bindings/schemas, serializable topology facts, and the
     * exact recoverable source payload when present.
     *
     * @param mapper canonical protocol mapper
     * @param graph frozen graph
     * @return prefixed SHA-256 artifact fingerprint
     */
    public static String of(ObjectMapper mapper, Graph graph) {
        List<Map<String, Object>> nodes = graph.nodes().values().stream()
                .sorted(java.util.Comparator.comparing(node -> node.id()))
                .map(node -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", node.id());
                    value.put("operatorRef", node.operatorRef());
                    value.put("operatorFingerprint", node.operatorFingerprint() == null
                            ? "" : node.operatorFingerprint());
                    value.put("inputSchema", node.inputSchema().toMap());
                    value.put("outputSchema", node.outputSchema().toMap());
                    value.put("kind", node.metadata().kind() == null ? "" : node.metadata().kind().wireValue());
                    return Map.copyOf(value);
                }).toList();
        List<Map<String, Object>> edges = graph.edges().stream().flatMap(edge -> edgeFacts(edge).stream())
                .sorted(java.util.Comparator.comparing(value -> String.valueOf(value.get("id"))))
                .toList();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("name", graph.name());
        material.put("nodes", nodes);
        material.put("edges", edges);
        material.put("inputSchema", graph.declaredInputSchema() == null
                ? Map.of() : graph.declaredInputSchema().toMap());
        material.put("outputSchema", graph.declaredOutputSchema() == null
                ? Map.of() : graph.declaredOutputSchema().toMap());
        material.put("definitionSource", sourceFacts(graph));
        return ProtocolFingerprint.of(mapper, material);
    }

    private static List<Map<String, Object>> edgeFacts(Edge edge) {
        return switch (edge) {
            case DirectEdge direct -> List.of(Map.of("id", direct.from() + "->" + direct.to(),
                    "kind", "DIRECT", "from", direct.from(), "to", direct.to(),
                    "requiresCompletion", direct.requiresCompletion()));
            case StreamEdge stream -> List.of(Map.of("id", stream.from() + "~>" + stream.to(),
                    "kind", "STREAM", "from", stream.from(), "to", stream.to()));
            case ConditionalEdge conditional -> {
                java.util.ArrayList<Map<String, Object>> facts = new java.util.ArrayList<>();
                for (int index = 0; index < conditional.branches().size(); index++) {
                    Edge.Branch branch = conditional.branches().get(index);
                    Map<String, Object> fact = new LinkedHashMap<>();
                    fact.put("id", conditional.from() + "?" + index + "->" + branch.target());
                    fact.put("kind", "CONDITIONAL");
                    fact.put("from", conditional.from());
                    fact.put("to", branch.target());
                    fact.put("branchIndex", index);
                    fact.put("conditionField", conditional.conditionField());
                    fact.put("inclusive", conditional.inclusive());
                    fact.put("narrowedSchema", branch.narrowedSchema() == null
                            ? Map.of() : branch.narrowedSchema().toMap());
                    facts.add(Map.copyOf(fact));
                }
                if (conditional.otherwise() != null) {
                    facts.add(Map.of("id", conditional.from() + "?:->" + conditional.otherwise(),
                            "kind", "OTHERWISE", "from", conditional.from(),
                            "to", conditional.otherwise(), "conditionField", conditional.conditionField(),
                            "inclusive", conditional.inclusive()));
                }
                yield List.copyOf(facts);
            }
        };
    }

    private static Map<String, Object> sourceFacts(Graph graph) {
        if (graph.definitionSource() == null) {
            return Map.of();
        }
        return Map.of(
                "graphVersion", graph.definitionSource().graphVersion(),
                "format", graph.definitionSource().format(),
                "payloadFingerprint", ProtocolFingerprint.ofText(graph.definitionSource().payloadJson()));
    }
}
