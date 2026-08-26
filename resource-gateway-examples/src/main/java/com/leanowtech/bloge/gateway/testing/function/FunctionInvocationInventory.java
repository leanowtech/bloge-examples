package com.leanowtech.bloge.gateway.testing.function;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, collision-checked inventory of statically compiled function call sites. */
public final class FunctionInvocationInventory {

    public static final int MAX_SITES = 10_000;

    private final List<FunctionInvocationSite> sites;
    private final Map<String, FunctionInvocationSite> byStructuralKey;
    private final String inventoryFingerprint;

    public FunctionInvocationInventory(Collection<FunctionInvocationSite> sites) {
        if (sites == null) {
            throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
        }
        if (sites.size() > MAX_SITES) {
            throw new FunctionControlException(FunctionControlException.Code.INVENTORY_LIMIT);
        }
        List<FunctionInvocationSite> sorted = new ArrayList<>();
        Map<String, FunctionInvocationSite> byKey = new LinkedHashMap<>();
        for (FunctionInvocationSite site : sites) {
            if (site == null) {
                throw new FunctionControlException(FunctionControlException.Code.INVENTORY_INVALID);
            }
            if (byKey.putIfAbsent(site.structuralKey(), site) != null) {
                throw new FunctionControlException(FunctionControlException.Code.SITE_COLLISION);
            }
            sorted.add(site);
        }
        sorted.sort(Comparator.naturalOrder());
        this.sites = List.copyOf(sorted);
        this.byStructuralKey = Map.copyOf(byKey);
        this.inventoryFingerprint = FunctionValueSupport.fingerprint(
                this.sites.stream().map(FunctionInvocationSite::structuralKey).toList());
    }

    public List<FunctionInvocationSite> sites() {
        return sites;
    }

    public FunctionInvocationSite find(String structuralKey) {
        return byStructuralKey.get(structuralKey);
    }

    public String inventoryFingerprint() {
        return inventoryFingerprint;
    }

    @Override
    public String toString() {
        return "FunctionInvocationInventory[size=" + sites.size()
                + ", fingerprint=" + inventoryFingerprint + "]";
    }
}
