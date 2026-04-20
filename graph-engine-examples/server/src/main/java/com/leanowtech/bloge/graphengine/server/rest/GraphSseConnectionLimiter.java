package com.leanowtech.bloge.graphengine.server.rest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks active graph-engine SSE subscriptions per tenant and enforces an upper bound.
 */
public final class GraphSseConnectionLimiter {
    private final int maxConnectionsPerTenant;
    private final ConcurrentHashMap<String, AtomicInteger> connections = new ConcurrentHashMap<>();

    public GraphSseConnectionLimiter(int maxConnectionsPerTenant) {
        if (maxConnectionsPerTenant < 1) {
            throw new IllegalArgumentException("maxConnectionsPerTenant must be >= 1");
        }
        this.maxConnectionsPerTenant = maxConnectionsPerTenant;
    }

    public boolean acquire(String tenantKey) {
        AtomicInteger counter = connections.computeIfAbsent(tenantKey, ignored -> new AtomicInteger(0));
        while (true) {
            int current = counter.get();
            if (current >= maxConnectionsPerTenant) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release(String tenantKey) {
        AtomicInteger counter = connections.get(tenantKey);
        if (counter != null) {
            counter.updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    public int activeConnections(String tenantKey) {
        AtomicInteger counter = connections.get(tenantKey);
        return counter == null ? 0 : counter.get();
    }

    public int maxConnectionsPerTenant() {
        return maxConnectionsPerTenant;
    }
}
