package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict deployment catalog for certificate generations referenced by signed opaque material ids.
 *
 * <p>The catalog contains no password values. Opaque secret references are passed to the existing
 * resolver only when an authorized event requests the exact target/material pair. Every candidate
 * is structurally validated and fingerprinted at startup, so a malformed catalog cannot partially
 * mutate a live transport.</p>
 */
public final class ConfiguredControlPlaneCertificateRotationMaterialSource
        implements ControlPlaneCertificateRotationMaterialSource {

    private static final int MAXIMUM_MATERIALS = 128;
    private static final Set<String> FIELDS = Set.of(
            "targetId", "materialId", "trustStorePath", "trustStorePasswordRef",
            "clientKeyStorePath", "clientKeyStorePasswordRef", "serverSpkiPins",
            "expectedClientSubjectDn", "expectedClientUriSan", "clientIssuerSpkiPins",
            "expectedServerUriSan", "serverIssuerSpkiPins");

    private final Map<String, ResolvedMaterial> materials;

    private ConfiguredControlPlaneCertificateRotationMaterialSource(
            Map<String, ResolvedMaterial> materials) {
        this.materials = Map.copyOf(materials);
    }

    /**
     * Parses and fingerprints a strict public configuration catalog.
     *
     * @param objectMapper deployment JSON decoder
     * @param fingerprinter path- and credential-free settings fingerprinter
     * @param catalogJson strict candidate array
     * @return immutable exact lookup source
     */
    public static ConfiguredControlPlaneCertificateRotationMaterialSource fromJson(
            ObjectMapper objectMapper,
            ControlPlaneCertificateSettingsFingerprint fingerprinter,
            String catalogJson) {
        try {
            ObjectMapper strict = Objects.requireNonNull(objectMapper, "objectMapper").copy();
            strict.getFactory().enable(
                    StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
            JsonNode root = strict.readTree(Objects.requireNonNullElse(catalogJson, "").trim());
            if (root == null || !root.isArray() || root.isEmpty()
                    || root.size() > MAXIMUM_MATERIALS) {
                throw invalid();
            }
            LinkedHashMap<String, ResolvedMaterial> indexed = new LinkedHashMap<>();
            for (JsonNode entry : root) {
                if (!entry.isObject() || entry.size() != FIELDS.size()) {
                    throw invalid();
                }
                Set<String> names = new HashSet<>();
                entry.fieldNames().forEachRemaining(names::add);
                if (!names.equals(FIELDS)) {
                    throw invalid();
                }
                String targetId = text(entry, "targetId");
                String materialId = text(entry, "materialId");
                if (!ControlPlaneCertificateRotationTargets.contains(targetId)
                        || materialId.contains(":") || materialId.contains("/")
                        || materialId.contains("#")) {
                    throw invalid();
                }
                var transport = new RecoveryFleetPublicationTransportProperties(
                        true, true, text(entry, "trustStorePath"),
                        text(entry, "trustStorePasswordRef"),
                        text(entry, "clientKeyStorePath"),
                        text(entry, "clientKeyStorePasswordRef"),
                        text(entry, "serverSpkiPins"), true,
                        text(entry, "expectedClientSubjectDn"),
                        text(entry, "expectedClientUriSan"),
                        text(entry, "clientIssuerSpkiPins"),
                        text(entry, "expectedServerUriSan"),
                        text(entry, "serverIssuerSpkiPins"));
                var settings = transport.pinnedSettings();
                var resolved = new ResolvedMaterial(
                        Objects.requireNonNull(fingerprinter, "fingerprinter")
                                .fingerprint(settings), settings);
                if (indexed.putIfAbsent(key(targetId, materialId), resolved) != null) {
                    throw invalid();
                }
            }
            return new ConfiguredControlPlaneCertificateRotationMaterialSource(indexed);
        } catch (RuntimeException | java.io.IOException failure) {
            throw new IllegalArgumentException(
                    "Control-plane certificate rotation material catalog is invalid", failure);
        }
    }

    /** {@inheritDoc} */
    @Override
    public ResolvedMaterial resolve(String targetId, long generation, String materialId) {
        if (generation < 2) {
            throw unavailable();
        }
        ResolvedMaterial resolved = materials.get(key(targetId, materialId));
        if (resolved == null) {
            throw unavailable();
        }
        return resolved;
    }

    /** @return bounded configured candidate count */
    public int materialCount() {
        return materials.size();
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > 16_384) {
            throw invalid();
        }
        return value.textValue().trim();
    }

    private static String key(String targetId, String materialId) {
        return Objects.requireNonNullElse(targetId, "").trim() + '\u0000'
                + Objects.requireNonNullElse(materialId, "").trim();
    }

    private static IllegalArgumentException unavailable() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation material is unavailable");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation material catalog is invalid");
    }
}
