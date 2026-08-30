package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;

/** Payload-free durable response for a committed command. */
public record CommandReceipt(String schemaVersion, JsonNode body, String bodyFingerprint, String strongEtag) {
    /** Receipt schema version used by the reference implementation. */
    public static final String SCHEMA_VERSION = "bloge.authoring.commandReceipt.v1";

    /** Copies mutable JSON and validates the opaque validator fields. */
    public CommandReceipt {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        if (schemaVersion.isBlank() || body == null || bodyFingerprint == null || strongEtag == null || strongEtag.isBlank()
                || !bodyFingerprint.matches("sha256:[0-9a-f]{64}") || !AuthoringFingerprints.of(body).equals(bodyFingerprint)
                || !strongEtag.startsWith("\"") || !strongEtag.endsWith("\"") || strongEtag.length() < 3
                || strongEtag.startsWith("\"W/")) {
            throw new IllegalArgumentException("receipt fields are required");
        }
        body = body.deepCopy();
    }

    @Override public JsonNode body() { return body.deepCopy(); }
}
