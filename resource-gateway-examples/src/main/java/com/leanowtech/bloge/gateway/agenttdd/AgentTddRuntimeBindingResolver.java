package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Materializes server-approved library bindings into a compiler-only graph projection. */
final class AgentTddRuntimeBindingResolver {

    private AgentTddRuntimeBindingResolver() {
    }

    /**
     * Replaces each bound contract node with its compatible runtime operator for validation and
     * lowering. The persisted draft remains contract-addressed, so changing this projection cannot
     * silently mutate the reviewed Tool contract or its evidence fingerprint.
     *
     * @param draft authoritative contract-addressed draft
     * @param resolver server catalog lookup function
     * @return transient graph whose bound nodes address executable catalog operators
     */
    static GraphDraft materialize(GraphDraft draft,
                                  Function<String, Optional<OperatorDefinition>> resolver) {
        List<GraphDraft.DraftNode> nodes = new ArrayList<>();
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>(draft.operatorSnapshots());
        Map<String, String> fingerprints = new LinkedHashMap<>(draft.operatorFingerprints());
        boolean changed = false;
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition contract = draft.operatorSnapshots().get(node.id());
            String bindingRef = bindingRef(contract);
            if (bindingRef.isBlank()) {
                nodes.add(node);
                continue;
            }
            OperatorDefinition target = resolver.apply(bindingRef)
                    .or(() -> resolver.apply("resource:" + bindingRef))
                    .orElseThrow(() -> new AgentTddToolException(
                            "LIBRARY_NOT_FOUND", "runtime.bindingRef no longer resolves in the server catalog."));
            AgentTddMutationService.requireCompatibleBinding(contract, target);
            changed = true;
            nodes.add(new GraphDraft.DraftNode(node.id(), target.operatorRef(), node.label(),
                    node.inputs(), node.config(), node.position()));
            snapshots.put(node.id(), target);
            fingerprints.put(node.id(), target.fingerprint());
        }
        if (!changed) return draft;
        return new GraphDraft(draft.schemaVersion(), draft.draftId(), draft.revision(), draft.graphName(),
                draft.tenantId(), draft.namespace(), draft.environment(), draft.status(),
                draft.inputSchema(), draft.outputSchema(), List.copyOf(nodes), draft.edges(),
                draft.visualLayout(), draft.nodeFixtures(), draft.output(), Map.copyOf(fingerprints),
                Map.copyOf(snapshots), draft.revisionMetadata());
    }

    private static String bindingRef(OperatorDefinition operator) {
        if (operator == null) return "";
        Object value = operator.lowering().parameters().get("bindingRef");
        return value instanceof String binding && !binding.isBlank() ? binding : "";
    }
}
