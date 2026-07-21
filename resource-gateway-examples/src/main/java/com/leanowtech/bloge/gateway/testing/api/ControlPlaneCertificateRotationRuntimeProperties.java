package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict product configuration for durable control-plane certificate rotation.
 *
 * <p>Private keys and passwords are absent. Authority JSON contains public Ed25519 keys only;
 * catalog entries contain public file locations and opaque password references. The initial
 * inventory is an out-of-band baseline; the durable floor proves any later active generation is a
 * contiguous descendant before the runtime restores its material.</p>
 *
 * @param enabled enables signed rotation for registered pinned transports
 * @param required prevents a deployment from disabling the configured rotation policy
 * @param deploymentScopeId exact Resource Gateway deployment scope
 * @param trustDomain independent external certificate-governance trust domain
 * @param acceptedPolicyFingerprints comma-separated exact policy fingerprints
 * @param signatureThreshold required distinct external authority signatures
 * @param authorityKeysJson strict public Ed25519 authority-key array
 * @param minimumOverlapSeconds minimum old/new certificate overlap after activation
 * @param maximumLeadTimeSeconds maximum delay from staging to activation
 * @param initialGenerationsJson strict target-id to baseline generation/material-id object; a
 *                               positive integer remains a shorthand with material id {@code initial}
 * @param materialCatalogJson strict deployment-owned candidate catalog
 */
@ConfigurationProperties(
        prefix = ControlPlaneCertificateRotationRuntimeProperties.PREFIX,
        ignoreUnknownFields = false)
public record ControlPlaneCertificateRotationRuntimeProperties(
        Boolean enabled,
        Boolean required,
        String deploymentScopeId,
        String trustDomain,
        String acceptedPolicyFingerprints,
        Integer signatureThreshold,
        String authorityKeysJson,
        Long minimumOverlapSeconds,
        Long maximumLeadTimeSeconds,
        String initialGenerationsJson,
        String materialCatalogJson) {

    /** Configuration prefix shared by profile YAML, environment, and capability documentation. */
    public static final String PREFIX =
            "gateway.testing.control-plane-certificate-rotation";
    private static final long MAXIMUM_SECONDS = Duration.ofDays(30).toSeconds();
    private static final Pattern MATERIAL_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Set<String> INITIAL_TARGET_FIELDS = Set.of("generation", "materialId");

    /**
     * Out-of-band target baseline used to verify or establish a durable floor.
     *
     * @param generation positive baseline generation
     * @param materialId opaque baseline material identity
     */
    public record InitialTargetSpec(long generation, String materialId) {
        /** Rejects incomplete or path-like material identities. */
        public InitialTargetSpec {
            materialId = normalized(materialId);
            if (generation < 1 || !MATERIAL_ID.matcher(materialId).matches()) {
                throw invalid();
            }
        }
    }

    /** Applies finite defaults and rejects partial, residual, or downgrade-prone policies. */
    public ControlPlaneCertificateRotationRuntimeProperties {
        enabled = Boolean.TRUE.equals(enabled);
        required = Boolean.TRUE.equals(required);
        deploymentScopeId = normalized(deploymentScopeId);
        trustDomain = normalized(trustDomain);
        acceptedPolicyFingerprints = normalized(acceptedPolicyFingerprints);
        signatureThreshold = signatureThreshold == null ? 0 : signatureThreshold;
        authorityKeysJson = normalized(authorityKeysJson);
        minimumOverlapSeconds = minimumOverlapSeconds == null
                ? 300L : minimumOverlapSeconds;
        maximumLeadTimeSeconds = maximumLeadTimeSeconds == null
                ? 86_400L : maximumLeadTimeSeconds;
        initialGenerationsJson = normalized(initialGenerationsJson);
        materialCatalogJson = normalized(materialCatalogJson);
        boolean residual = !deploymentScopeId.isBlank() || !trustDomain.isBlank()
                || !acceptedPolicyFingerprints.isBlank() || signatureThreshold != 0
                || !emptyArray(authorityKeysJson) || minimumOverlapSeconds != 300L
                || maximumLeadTimeSeconds != 86_400L
                || !emptyObject(initialGenerationsJson) || !emptyArray(materialCatalogJson);
        if (required && !enabled || !enabled && residual
                || enabled && (deploymentScopeId.isBlank() || trustDomain.isBlank()
                || acceptedPolicyFingerprints.isBlank() || signatureThreshold < 1
                || signatureThreshold > 32 || emptyArray(authorityKeysJson)
                || minimumOverlapSeconds < 0 || minimumOverlapSeconds > MAXIMUM_SECONDS
                || maximumLeadTimeSeconds < 1
                || maximumLeadTimeSeconds > MAXIMUM_SECONDS
                || emptyObject(initialGenerationsJson) || emptyArray(materialCatalogJson))) {
            throw invalid();
        }
    }

    /** @return immutable minimum certificate overlap */
    public Duration minimumOverlap() {
        return Duration.ofSeconds(minimumOverlapSeconds);
    }

    /** @return immutable maximum staging lead time */
    public Duration maximumLeadTime() {
        return Duration.ofSeconds(maximumLeadTimeSeconds);
    }

    /**
     * Parses exact active generations and rejects unknown/duplicate product target identities.
     *
     * @param objectMapper deployment JSON decoder
     * @return immutable target generation inventory
     */
    public Map<String, InitialTargetSpec> initialTargets(ObjectMapper objectMapper) {
        if (!enabled) {
            return Map.of();
        }
        try {
            JsonNode root = Objects.requireNonNull(objectMapper, "objectMapper").reader()
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(initialGenerationsJson);
            if (root == null || !root.isObject() || root.isEmpty()
                    || root.size() > ControlPlaneCertificateRotationTargets.values().size()) {
                throw invalid();
            }
            LinkedHashMap<String, InitialTargetSpec> targets = new LinkedHashMap<>();
            root.properties().forEach(field -> {
                JsonNode value = field.getValue();
                InitialTargetSpec target = initialTarget(value);
                if (!ControlPlaneCertificateRotationTargets.contains(field.getKey())
                        || targets.putIfAbsent(field.getKey(), target) != null) {
                    throw invalid();
                }
            });
            return Map.copyOf(targets);
        } catch (RuntimeException | java.io.IOException failure) {
            throw new IllegalArgumentException(
                    "Control-plane certificate rotation generation inventory is invalid",
                    failure);
        }
    }

    /**
     * Returns only baseline generations for compatibility with inventory diagnostics.
     *
     * @param objectMapper strict deployment JSON decoder
     * @return immutable target generation inventory
     */
    public Map<String, Long> initialGenerations(ObjectMapper objectMapper) {
        LinkedHashMap<String, Long> generations = new LinkedHashMap<>();
        initialTargets(objectMapper).forEach((target, initial) ->
                generations.put(target, initial.generation()));
        return Map.copyOf(generations);
    }

    /** Returns the canonical disabled configuration. */
    public static ControlPlaneCertificateRotationRuntimeProperties disabled() {
        return new ControlPlaneCertificateRotationRuntimeProperties(false, false,
                "", "", "", 0, "[]", 300L, 86_400L, "{}", "[]");
    }

    private static boolean emptyArray(String value) {
        return value.isBlank() || "[]".equals(value);
    }

    private static boolean emptyObject(String value) {
        return value.isBlank() || "{}".equals(value);
    }

    private static InitialTargetSpec initialTarget(JsonNode value) {
        if (value != null && value.isIntegralNumber() && value.canConvertToLong()) {
            return new InitialTargetSpec(value.longValue(), "initial");
        }
        if (value == null || !value.isObject() || value.size() != INITIAL_TARGET_FIELDS.size()
                || !INITIAL_TARGET_FIELDS.stream().allMatch(value::has)) {
            throw invalid();
        }
        JsonNode generation = value.get("generation");
        JsonNode materialId = value.get("materialId");
        if (!generation.isIntegralNumber() || !generation.canConvertToLong()
                || !materialId.isTextual()) {
            throw invalid();
        }
        return new InitialTargetSpec(generation.longValue(), materialId.textValue());
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation runtime configuration is invalid");
    }
}
