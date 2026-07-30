package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Exact inference replay and explicit decisions used for an atomic draft update.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SampleInferenceApplyRequest(
        String schemaVersion,
        SampleInferenceRequest inference,
        String evidenceFingerprint,
        List<Decision> decisions,
        String actor
) {
    public static final String SCHEMA_VERSION = "bloge.visualSampleInferenceApplyRequest.v1";

    public SampleInferenceApplyRequest {
        schemaVersion = normalized(schemaVersion);
        evidenceFingerprint = normalized(evidenceFingerprint);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        actor = normalized(actor);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Decision(
            String confirmationId,
            String value
    ) {
        public Decision {
            confirmationId = normalized(confirmationId);
            value = normalized(value);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
