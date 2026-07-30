package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ephemeral, bounded sample batch used to infer observed authoring facts.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SampleInferenceRequest(
        String schemaVersion,
        Target target,
        List<JsonNode> samples,
        Options options,
        String idempotencyKey
) {
    public static final String SCHEMA_VERSION = "bloge.visualSampleInferenceRequest.v1";

    public SampleInferenceRequest {
        schemaVersion = normalized(schemaVersion, "");
        samples = samples == null || samples.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(samples));
        idempotencyKey = normalized(idempotencyKey, "");
    }

    /**
     * Exact authoring coordinate that will receive an accepted candidate.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Target(
            String assetKind,
            String assetRef,
            String portDirection,
            String portName
    ) {
        public Target {
            assetKind = normalized(assetKind, "").toUpperCase(Locale.ROOT);
            assetRef = normalized(assetRef, "");
            portDirection = normalized(portDirection, "").toUpperCase(Locale.ROOT);
            portName = normalized(portName, "");
        }

        public String authoringPath() {
            String direction = "OUTPUT".equals(portDirection) ? "output" : "input";
            return "/operators/%s/%s/%s".formatted(
                    pointer(assetRef),
                    direction,
                    pointer(portName)
            );
        }
    }

    /**
     * Conservative inference controls. Payload persistence is intentionally unsupported in Stage 2.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Options(
            Boolean suggestEnums,
            Boolean suggestFormats,
            Boolean persistPayload
    ) {
        public static Options defaults() {
            return new Options(true, true, false);
        }

        public boolean enumsEnabled() {
            return Boolean.TRUE.equals(suggestEnums);
        }

        public boolean formatsEnabled() {
            return Boolean.TRUE.equals(suggestFormats);
        }

        public boolean payloadPersistenceRequested() {
            return Boolean.TRUE.equals(persistPayload);
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String pointer(String value) {
        return normalized(value, "").replace("~", "~0").replace("/", "~1");
    }
}
