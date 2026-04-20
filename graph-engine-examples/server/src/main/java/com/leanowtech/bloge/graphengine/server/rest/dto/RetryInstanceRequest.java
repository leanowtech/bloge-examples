package com.leanowtech.bloge.graphengine.server.rest.dto;

import java.util.Set;

/**
 * Request body for the instance-level retry endpoint.
 *
 * @param nodeIds          optional filter restricting retries to specific node identifiers;
 *                         when {@code null} or empty, all dead-lettered items for the instance
 *                         are retried
 * @param expectedRevision optimistic-lock guard on the instance projection
 */
public record RetryInstanceRequest(
        Set<String> nodeIds,
        long expectedRevision
) {
}
