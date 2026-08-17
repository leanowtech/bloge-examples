package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Strict business-shaped request for the tutorial dependency behavior editor. */
@JsonIgnoreProperties(ignoreUnknown = false)
record CapabilityStudioTutorialBranchBehaviorUpdateRequest(
        String condition,
        String behavior,
        Long durationMs,
        Long expectedRevision) {

    @JsonCreator
    CapabilityStudioTutorialBranchBehaviorUpdateRequest(
            @JsonProperty("condition") String condition,
            @JsonProperty("behavior") String behavior,
            @JsonProperty("durationMs") Long durationMs,
            @JsonProperty("expectedRevision") Long expectedRevision) {
        this.condition = condition;
        this.behavior = behavior;
        this.durationMs = durationMs;
        this.expectedRevision = expectedRevision;
    }

    /** Keeps unknown raw payload/mock fields from being silently accepted by a relaxed mapper. */
    @JsonAnySetter
    void rejectUnknownField(String field, JsonNode ignored) {
        throw new IllegalArgumentException("Unknown field: " + field);
    }
}
