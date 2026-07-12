package com.leanowtech.bloge.gateway.integration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable registry of provider-owned reconciliation adapters. */
public final class SideEffectReconcilerRegistry {
    private final Map<String, SideEffectReconciler> reconcilers;

    public SideEffectReconcilerRegistry(Collection<SideEffectReconciler> values) {
        Map<String, SideEffectReconciler> registered = new LinkedHashMap<>();
        if (values != null) {
            for (SideEffectReconciler reconciler : values) {
                if (reconciler == null || reconciler.reconcilerRef() == null
                        || reconciler.reconcilerRef().isBlank()) {
                    throw new IllegalArgumentException("A reconcilerRef is required");
                }
                SideEffectReconciler previous = registered.putIfAbsent(
                        reconciler.reconcilerRef().trim(), reconciler);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Duplicate side-effect reconciler: " + reconciler.reconcilerRef());
                }
            }
        }
        this.reconcilers = Map.copyOf(registered);
    }

    public Optional<SideEffectReconciler> find(String reconcilerRef) {
        return Optional.ofNullable(reconcilers.get(reconcilerRef == null ? "" : reconcilerRef.trim()));
    }

    public boolean available() {
        return !reconcilers.isEmpty();
    }

    public int size() {
        return reconcilers.size();
    }
}
