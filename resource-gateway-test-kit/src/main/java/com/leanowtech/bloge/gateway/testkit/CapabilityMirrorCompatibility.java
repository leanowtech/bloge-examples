package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forward-compatible negotiation of a Resource Gateway capability probe against the packaged
 * capability-mirror Stage 0 baseline.
 *
 * <p>Required protocol objects and feature facts fail closed. Deferred features are reported but
 * deliberately do not block Stage 0 compatibility, so enabling later mirror stages remains an
 * additive server change.</p>
 */
public final class CapabilityMirrorCompatibility {

    private CapabilityMirrorCompatibility() {
    }

    /**
     * Assesses one decoded {@code /api/integration/capabilities} payload.
     *
     * <p>The method consumes the capability payload itself, not the surrounding integration
     * envelope. Unknown fields and additional object versions are ignored for forward
     * compatibility. Every reason code is drawn from the packaged baseline and never includes a
     * server payload value.</p>
     *
     * @param capabilityPayload decoded capability probe payload
     * @return immutable compatibility decision and negotiated versions
     */
    public static Assessment assess(JsonNode capabilityPayload) {
        JsonNode baseline = CapabilityMirrorProtocol.compatibilityBaseline();
        List<String> reasons = new ArrayList<>();
        Map<String, String> negotiatedObjects = new LinkedHashMap<>();
        Map<String, Boolean> deferred = new LinkedHashMap<>();

        if (capabilityPayload == null || !capabilityPayload.isObject()) {
            reasons.add("RG.MIRROR.CLIENT.CAPABILITY_PROBE_INVALID");
            return assessment(baseline, "", reasons, negotiatedObjects, deferred);
        }
        requireExactText(capabilityPayload, "schemaVersion",
                "toolStudio.resourceGateway.capabilities.v1",
                "RG.MIRROR.CLIENT.CAPABILITY_SCHEMA_UNSUPPORTED", reasons);
        requireExactText(capabilityPayload, "protocol", baseline.path("protocol").asText(),
                "RG.MIRROR.CLIENT.PROTOCOL_UNSUPPORTED", reasons);

        String negotiatedProtocol = negotiateText(capabilityPayload.path("protocolVersion"),
                baseline.path("protocolVersions"));
        if (negotiatedProtocol.isEmpty()) {
            reasons.add("RG.MIRROR.CLIENT.PROTOCOL_VERSION_UNSUPPORTED");
        }

        JsonNode supportedObjects = capabilityPayload.path("supportedObjects");
        baseline.path("requiredObjects").fields().forEachRemaining(required -> {
            String selected = negotiateTextArray(supportedObjects.path(required.getKey()),
                    required.getValue());
            if (selected.isEmpty()) {
                reasons.add("RG.MIRROR.CLIENT.OBJECT_VERSION_UNAVAILABLE." + required.getKey());
            } else {
                negotiatedObjects.put(required.getKey(), selected);
            }
        });

        JsonNode features = capabilityPayload.path("features");
        baseline.path("requiredFeatures").forEach(required -> {
            String name = required.asText();
            if (!features.path(name).isBoolean() || !features.path(name).booleanValue()) {
                reasons.add("RG.MIRROR.CLIENT.FEATURE_UNAVAILABLE." + name);
            }
        });
        baseline.path("deferredFeatures").forEach(item -> {
            String name = item.asText();
            deferred.put(name, features.path(name).isBoolean() && features.path(name).booleanValue());
        });
        return assessment(baseline, negotiatedProtocol, reasons, negotiatedObjects, deferred);
    }

    private static Assessment assessment(JsonNode baseline,
                                         String negotiatedProtocol,
                                         List<String> reasons,
                                         Map<String, String> negotiatedObjects,
                                         Map<String, Boolean> deferred) {
        return new Assessment(baseline.path("schemaVersion").asText(), reasons.isEmpty(),
                negotiatedProtocol, reasons, negotiatedObjects, deferred);
    }

    private static void requireExactText(JsonNode payload,
                                         String field,
                                         String expected,
                                         String reason,
                                         List<String> reasons) {
        JsonNode actual = payload.path(field);
        if (!actual.isTextual() || !expected.equals(actual.textValue())) {
            reasons.add(reason);
        }
    }

    private static String negotiateText(JsonNode actual, JsonNode supported) {
        if (!actual.isTextual() || !supported.isArray()) {
            return "";
        }
        String value = actual.textValue();
        for (JsonNode candidate : supported) {
            if (candidate.isTextual() && value.equals(candidate.textValue())) {
                return value;
            }
        }
        return "";
    }

    private static String negotiateTextArray(JsonNode actual, JsonNode required) {
        if (!actual.isArray() || !required.isArray()) {
            return "";
        }
        for (JsonNode candidate : required) {
            if (!candidate.isTextual()) {
                continue;
            }
            for (JsonNode available : actual) {
                if (available.isTextual() && candidate.textValue().equals(available.textValue())) {
                    return candidate.textValue();
                }
            }
        }
        return "";
    }

    /**
     * Immutable compatibility result safe to log because it contains only protocol metadata.
     *
     * @param baselineVersion exact compatibility fixture version used for the decision
     * @param compatible whether every required Stage 0 object and feature was negotiated
     * @param negotiatedProtocolVersion selected Tool Studio integration protocol version
     * @param reasonCodes stable payload-free incompatibility reasons
     * @param negotiatedObjectVersions selected version for every required mirror object
     * @param deferredFeatures current server facts for later mirror stages
     */
    public record Assessment(
            String baselineVersion,
            boolean compatible,
            String negotiatedProtocolVersion,
            List<String> reasonCodes,
            Map<String, String> negotiatedObjectVersions,
            Map<String, Boolean> deferredFeatures
    ) {
        /** Copies all collections so the result remains stable after negotiation. */
        public Assessment {
            baselineVersion = baselineVersion == null ? "" : baselineVersion;
            negotiatedProtocolVersion = negotiatedProtocolVersion == null
                    ? "" : negotiatedProtocolVersion;
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
            negotiatedObjectVersions = immutableMap(negotiatedObjectVersions);
            deferredFeatures = immutableMap(deferredFeatures);
        }

        /**
         * Fails closed when the server cannot satisfy the packaged baseline.
         *
         * @throws IllegalStateException containing only stable incompatibility reason codes
         */
        public void requireCompatible() {
            if (!compatible) {
                throw new IllegalStateException(String.join(",", reasonCodes));
            }
        }

        private static <T> Map<String, T> immutableMap(Map<String, T> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
