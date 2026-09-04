package com.leanowtech.bloge.gateway.solution;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, uniquely keyed registry of downstream reconciliation authorities. */
@Component
public final class ReconciliationAdapterRegistry {
    private final Map<String, ReconciliationAdapter> adapters;

    /** Freezes all installed adapters and rejects duplicate references at startup. */
    public ReconciliationAdapterRegistry(ObjectProvider<ReconciliationAdapter> adapters) {
        LinkedHashMap<String, ReconciliationAdapter> indexed = new LinkedHashMap<>();
        adapters.orderedStream().forEach(adapter -> {
            String ref = adapter.adapterRef() == null ? "" : adapter.adapterRef().trim();
            if (ref.isBlank() || indexed.put(ref, adapter) != null) {
                throw new IllegalStateException("Reconciliation adapter references must be unique");
            }
        });
        this.adapters = Map.copyOf(indexed);
    }

    /** Resolves one exact adapter without disclosing unavailable references. */
    public ReconciliationAdapter require(String adapterRef, String downstreamSystem) {
        ReconciliationAdapter adapter = adapters.get(adapterRef == null ? "" : adapterRef.trim());
        if (adapter == null || !adapter.downstreamSystem().equals(downstreamSystem)) {
            throw new SolutionContractException(
                    "RECONCILIATION_ADAPTER_UNAVAILABLE", "Reconciliation is unavailable.");
        }
        return adapter;
    }
}
