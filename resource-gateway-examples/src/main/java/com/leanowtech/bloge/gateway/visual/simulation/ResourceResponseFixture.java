package com.leanowtech.bloge.gateway.visual.simulation;

import java.util.Map;

/** Bounded visual-owned raw response evidence for descriptor-backed resource fixtures. */
public record ResourceResponseFixture(String rawBody, int statusCode, Map<String, String> headers) {
    /** Normalizes the response while preserving only protocol-safe fields. */
    public ResourceResponseFixture {
        rawBody = rawBody == null ? "" : rawBody;
        if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("statusCode is invalid");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
