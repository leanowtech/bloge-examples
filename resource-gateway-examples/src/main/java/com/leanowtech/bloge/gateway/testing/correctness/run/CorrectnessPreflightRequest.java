package com.leanowtech.bloge.gateway.testing.correctness.run;

/** Payload-free request to resolve the execution plan visible to a correctness author. */
public record CorrectnessPreflightRequest(
        String schemaVersion,
        CorrectnessRunRequest.PublicationRef publicationRef,
        CorrectnessRunRequest.Selection selection
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessPreflightRequest.v1";

    public CorrectnessPreflightRequest {
        String normalized = schemaVersion == null ? "" : schemaVersion.trim();
        schemaVersion = normalized.isEmpty() ? SCHEMA_VERSION : normalized;
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported correctness preflight schemaVersion");
        }
        if (publicationRef == null || selection == null) {
            throw new IllegalArgumentException("Publication ref and selection are required");
        }
    }
}
