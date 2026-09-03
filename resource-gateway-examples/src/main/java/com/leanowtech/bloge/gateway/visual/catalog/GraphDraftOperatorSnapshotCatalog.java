package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable operator catalog reconstructed from one graph draft's reviewed snapshots.
 *
 * <p>This catalog is an operation-scoped consistency boundary. Validation, simulation, DSL
 * generation, and dependency reporting can all consume the same instance without consulting a
 * mutable registry after the executable draft has been materialized. A conflicting pair of
 * snapshots for the same operator reference is rejected instead of choosing one silently.</p>
 */
public final class GraphDraftOperatorSnapshotCatalog implements VisualOperatorCatalog {
    private final Map<String, OperatorDefinition> definitions;

    private GraphDraftOperatorSnapshotCatalog(Map<String, OperatorDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    /**
     * Captures every node-owned operator snapshot from an already materialized graph draft.
     *
     * @param draft immutable operation draft whose snapshots are authoritative
     * @return an immutable catalog view
     * @throws IllegalArgumentException when a node lacks its matching snapshot or one operator
     *                                  reference has conflicting snapshots
     */
    public static GraphDraftOperatorSnapshotCatalog from(GraphDraft draft) {
        Objects.requireNonNull(draft, "draft");
        Map<String, OperatorDefinition> definitions = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            OperatorDefinition snapshot = draft.operatorSnapshots().get(node.id());
            if (snapshot == null || !node.operatorRef().equals(snapshot.operatorRef())) {
                throw new IllegalArgumentException(
                        "Every executable graph node must have a matching operator snapshot.");
            }
            OperatorDefinition previous = definitions.putIfAbsent(snapshot.operatorRef(), snapshot);
            if (previous != null && !previous.fingerprint().equals(snapshot.fingerprint())) {
                throw new IllegalArgumentException(
                        "One executable operation cannot contain conflicting operator snapshots.");
            }
        }
        return new GraphDraftOperatorSnapshotCatalog(definitions);
    }

    /**
     * Lists the frozen definitions visible in the requested graph scope.
     *
     * <p>Discovery facets are intentionally not re-evaluated: this class represents a closed graph
     * dependency set, not a replacement for the live browsing catalog. Scope policy is still
     * enforced because dependency reporting uses this method to distinguish an unavailable target.</p>
     */
    @Override
    public List<OperatorDefinition> list(OperatorCatalogQuery query) {
        OperatorCatalogQuery effective = query == null ? OperatorCatalogQuery.all() : query;
        return definitions.values().stream()
                .filter(operator -> scopeAllowed(operator, effective))
                .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                .toList();
    }

    /** Returns the exact frozen definition without consulting external state. */
    @Override
    public Optional<OperatorDefinition> find(String operatorRef) {
        return Optional.ofNullable(definitions.get(operatorRef));
    }

    /** Resolves all requested references from the same immutable map. */
    @Override
    public Map<String, OperatorDefinition> findAll(Iterable<String> operatorRefs) {
        if (operatorRefs == null) return Map.of();
        Map<String, OperatorDefinition> resolved = new LinkedHashMap<>();
        for (String operatorRef : operatorRefs) {
            OperatorDefinition definition = definitions.get(operatorRef);
            if (definition != null) resolved.putIfAbsent(operatorRef, definition);
        }
        return Map.copyOf(resolved);
    }

    private static boolean scopeAllowed(OperatorDefinition operator, OperatorCatalogQuery query) {
        if (query.tenantId().isBlank() && query.namespace().isBlank() && query.environment().isBlank()) {
            return true;
        }
        return operator.policy().violations(
                query.tenantId(), query.namespace(), query.environment()).isEmpty();
    }
}
