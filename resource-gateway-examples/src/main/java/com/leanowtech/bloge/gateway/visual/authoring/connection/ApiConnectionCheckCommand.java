package com.leanowtech.bloge.gateway.visual.authoring.connection;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;

/** Explicit, payload-safe command for checking one committed API Connection. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ApiConnectionCheckCommand.NetworkOnly.class, name = "NETWORK_ONLY"),
        @JsonSubTypes.Type(value = ApiConnectionCheckCommand.SafeRead.class, name = "SAFE_READ")
})
public sealed interface ApiConnectionCheckCommand
        permits ApiConnectionCheckCommand.NetworkOnly, ApiConnectionCheckCommand.SafeRead {
    /** @return stable wire discriminator */
    default String kind() { return this instanceof NetworkOnly ? "NETWORK_ONLY" : "SAFE_READ"; }

    /** Checks only governed DNS, TLS and connection establishment. */
    record NetworkOnly() implements ApiConnectionCheckCommand { }

    /**
     * Future safe-read check against one exact READ_ONLY Resource.
     * Input is defensively copied and never belongs in diagnostics.
     */
    record SafeRead(ApiResourceSpec.ResourceRef resource, JsonNode input, String justification)
            implements ApiConnectionCheckCommand {
        public SafeRead {
            if (resource == null || justification == null || justification.isBlank()
                    || justification.length() > 1_000) {
                throw new IllegalArgumentException("safe-read connection check is invalid");
            }
            input = input == null ? null : input.deepCopy();
        }

        @Override public JsonNode input() { return input == null ? null : input.deepCopy(); }

        @Override public String toString() {
            return "SafeRead[resource=" + resource + ", input=protected, justification=present]";
        }
    }

    /** @return a network-only check command */
    static ApiConnectionCheckCommand networkOnly() { return new NetworkOnly(); }

    /** @return a future exact Resource safe-read check command */
    static ApiConnectionCheckCommand safeRead(ApiResourceSpec.ResourceRef resource, JsonNode input,
                                               String justification) {
        return new SafeRead(resource, input, justification);
    }
}
