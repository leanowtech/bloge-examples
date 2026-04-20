/**
 * Micrometer-backed implementations of the graph-engine control-plane metrics SPI.
 * <p>
 * Co-locating the default Micrometer observer with
 * {@link com.leanowtech.bloge.graphengine.service.GraphEngineMetricsObserver}
 * keeps the product-layer {@code ge.*} counters available without introducing a
 * reverse dependency from {@code bloge-metrics-otel} back into the graph-engine
 * modules.
 */
package com.leanowtech.bloge.graphengine.service.metrics;
