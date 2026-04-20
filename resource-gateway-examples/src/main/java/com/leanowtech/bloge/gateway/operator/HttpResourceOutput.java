package com.leanowtech.bloge.gateway.operator;

import java.time.Duration;

/**
 * Output record from the {@link HttpResourceOperator}.
 *
 * <p>Contains both the extracted payload and the raw response for diagnostic purposes.
 *
 * @param resourceId the logical resource identifier that was called
 * @param statusCode the HTTP status code from the response
 * @param payload    the extracted payload (after {@code payloadPath} extraction), or the
 *                   full parsed JSON if no payload path was specified
 * @param rawBody    the raw response body string
 * @param duration   how long the HTTP call took
 * @param success    whether the response was deemed successful by the response protocol
 */
public record HttpResourceOutput(
    String resourceId,
    int statusCode,
    Object payload,
    String rawBody,
    Duration duration,
    boolean success
) {}
