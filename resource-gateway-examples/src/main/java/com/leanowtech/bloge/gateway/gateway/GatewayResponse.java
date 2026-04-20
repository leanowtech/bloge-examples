package com.leanowtech.bloge.gateway.gateway;

/**
 * Generic response wrapper for gateway graph execution results.
 *
 * <p>Provides a consistent envelope for all non-streaming gateway endpoints,
 * including success/failure status, the graph output payload, an optional error
 * message, and execution timing.
 *
 * @param success  whether the graph execution completed without errors
 * @param data     the output payload from the terminal graph node, or {@code null} on failure
 * @param error    a human-readable error summary, or {@code null} on success
 * @param elapsedMs wall-clock execution time in milliseconds
 */
public record GatewayResponse(
    boolean success,
    Object data,
    String error,
    long elapsedMs
) {}
