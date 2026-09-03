package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.ArrayList;
import java.util.Comparator;
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
            OperatorDefinition target = resolve(bindingRef, resolver);
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

    /**
     * Captures the current executable identity of every reviewed contract binding.
     *
     * <p>The catalog is mutable while an evidence/signoff pair is not. Including this sorted
     * identity in the evidence subject makes target replacement invalidate the pair before
     * publication can freeze a different implementation.</p>
     *
     * @param draft reviewed contract-addressed draft
     * @param resolver current server catalog lookup
     * @return canonical, node-sorted binding identities
     */
    static List<Map<String, String>> bindingIdentity(
            GraphDraft draft,
            Function<String, Optional<OperatorDefinition>> resolver) {
        return bindingIdentity(draft, materialize(draft, resolver));
    }

    /**
     * Captures binding identities from an already frozen executable projection.
     *
     * <p>This overload performs no catalog reads, so callers can fingerprint and execute or
     * publish the exact same immutable projection without a target-replacement race.</p>
     */
    static List<Map<String, String>> bindingIdentity(GraphDraft draft, GraphDraft executable) {
        List<Map<String, String>> identities = new ArrayList<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition contract = draft.operatorSnapshots().get(node.id());
            String bindingRef = bindingRef(contract);
            if (bindingRef.isBlank()) continue;
            OperatorDefinition target = executable.operatorSnapshots().get(node.id());
            if (target == null) {
                throw new AgentTddToolException(
                        "LIBRARY_NOT_FOUND", "Frozen executable projection has no bound operator snapshot.");
            }
            AgentTddMutationService.requireCompatibleBinding(contract, target);
            identities.add(Map.of(
                    "nodeId", node.id(),
                    "bindingRef", bindingRef,
                    "targetOperatorRef", target.operatorRef(),
                    "targetFingerprint", target.fingerprint()));
        }
        identities.sort(Comparator.comparing(identity -> identity.get("nodeId")));
        return List.copyOf(identities);
    }

    private static OperatorDefinition resolve(
            String bindingRef,
            Function<String, Optional<OperatorDefinition>> resolver) {
        return resolver.apply(bindingRef)
                .or(() -> resolver.apply("resource:" + bindingRef))
                .orElseThrow(() -> new AgentTddToolException(
                        "LIBRARY_NOT_FOUND", "runtime.bindingRef no longer resolves in the server catalog."));
    }

    private static String bindingRef(OperatorDefinition operator) {
        if (operator == null) return "";
        Object value = operator.lowering().parameters().get("bindingRef");
        return value instanceof String binding && !binding.isBlank() ? binding : "";
    }
}
