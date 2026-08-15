package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;

/** Integrity-addressed ANEKE feedback snapshot. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredCorrectnessGovernanceFeedback(
        String schemaVersion,
        String feedbackFingerprint,
        CorrectnessGovernanceFeedback feedback
) {
    public static final String SCHEMA_VERSION = "bloge.storedCorrectnessGovernanceFeedback.v1";

    public StoredCorrectnessGovernanceFeedback {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || feedbackFingerprint == null
                || !feedbackFingerprint.matches("sha256:[0-9a-f]{64}")
                || feedback == null) {
            throw new IllegalArgumentException("Exact stored governance feedback is required");
        }
    }

    public static StoredCorrectnessGovernanceFeedback verified(
            ObjectMapper mapper, CorrectnessGovernanceFeedback feedback) {
        return new StoredCorrectnessGovernanceFeedback(
                "", CorrectnessProtocolFingerprint.derivedFingerprint(mapper, feedback), feedback);
    }
}
