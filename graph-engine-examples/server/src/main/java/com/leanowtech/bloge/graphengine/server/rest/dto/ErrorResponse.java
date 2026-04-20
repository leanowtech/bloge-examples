package com.leanowtech.bloge.graphengine.server.rest.dto;

import java.time.Instant;
import java.util.List;

/**
 * Structured JSON error response returned by the graph-engine HTTP API.
 *
 * @param errorCode stable error code
 * @param message human-readable error message
 * @param status HTTP status code
 * @param timestamp response timestamp
 * @param path request path that failed
 * @param details optional validation or diagnostic details
 */
public record ErrorResponse(
        String errorCode,
        String message,
        int status,
        Instant timestamp,
        String path,
        List<String> details
) {
    public ErrorResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
