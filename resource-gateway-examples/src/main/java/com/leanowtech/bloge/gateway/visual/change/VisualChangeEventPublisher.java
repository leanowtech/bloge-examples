package com.leanowtech.bloge.gateway.visual.change;

/** Optional outbound port for protocol adapters that consume visual change facts. */
@FunctionalInterface
public interface VisualChangeEventPublisher {
    void publish(VisualChangeFact fact);

    static VisualChangeEventPublisher unavailable() {
        return ignored -> { };
    }
}
