package com.leanowtech.bloge.graphengine.server.rest.dto;

/**
 * HTTP payload that signals one running or suspended instance.
 *
 * @param nodeId optional graph node identifier for graph-mode signals
 * @param eventName optional logical event name for state-machine signals
 * @param payload signal payload
 * @param callerId optional caller identity
 */
public record SignalInstanceRequest(
        String nodeId,
        String eventName,
        Object payload,
        String callerId
) {
}
