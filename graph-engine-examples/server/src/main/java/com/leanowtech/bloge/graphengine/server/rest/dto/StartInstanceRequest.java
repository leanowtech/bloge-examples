package com.leanowtech.bloge.graphengine.server.rest.dto;

import java.util.Map;

/**
 * HTTP payload that starts a new graph instance.
 *
 * @param version optional exact version pin
 * @param environment optional deployment environment label
 * @param businessKey optional business idempotency key
 * @param initiator optional caller identity
 * @param variables start variables
 */
public record StartInstanceRequest(
        String version,
        String environment,
        String businessKey,
        String initiator,
        Map<String, Object> variables
) {
}
