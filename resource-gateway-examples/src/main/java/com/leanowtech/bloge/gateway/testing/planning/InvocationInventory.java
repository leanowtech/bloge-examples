package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable preflight inventory of every operator binding reachable from a graph artifact.
 *
 * <p>The inventory deliberately keeps two identities. {@link Entry#engineStructuralId()} follows
 * BLOGE's runtime resolver protocol, where resource operators are primary invocations. The public
 * {@link Entry#site()} retains Resource Gateway's governance kind such as {@code RESOURCE}. This
 * explicit mapping prevents protocol vocabulary differences from becoming fixture misses.</p>
 */
public record InvocationInventory(
        List<Entry> entries,
        Map<String, Entry> byEngineStructuralId,
        Map<String, Entry> byInvocationSiteId
) {
    /** Creates immutable, declaration-ordered inventory projections. */
    public InvocationInventory {
        entries = entries == null ? List.of() : List.copyOf(entries);
        byEngineStructuralId = immutableMap(byEngineStructuralId);
        byInvocationSiteId = immutableMap(byInvocationSiteId);
    }

    /**
     * One frozen structural call site and the exact operator implementation selected at preflight.
     *
     * @param graph graph containing the node
     * @param node effective node contract used for fixture schema validation
     * @param site governance-facing invocation identity
     * @param engineStructuralId BLOGE resolver identity
     * @param frozenOperator exact implementation pinned for this run
     */
    public record Entry(
            Graph graph,
            NodeSpec node,
            InvocationSite site,
            String engineStructuralId,
            Object frozenOperator
    ) {
        /** Rejects incomplete entries before execution-control compilation starts. */
        public Entry {
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(site, "site");
            if (engineStructuralId == null || engineStructuralId.isBlank()) {
                throw new IllegalArgumentException("engineStructuralId must not be blank");
            }
            engineStructuralId = engineStructuralId.trim();
            Objects.requireNonNull(frozenOperator, "frozenOperator");
        }
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
