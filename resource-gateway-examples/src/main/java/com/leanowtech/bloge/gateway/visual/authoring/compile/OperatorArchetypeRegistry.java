package com.leanowtech.bloge.gateway.visual.authoring.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned, explainable defaults used by the Quick operator builder and compiler.
 */
public final class OperatorArchetypeRegistry {

    public static final String VERSION = "bloge.operatorArchetypes.v1";

    private final Map<String, Archetype> archetypes;

    public OperatorArchetypeRegistry() {
        Map<String, Archetype> values = new LinkedHashMap<>();
        add(values, new Archetype("pure", "PURE", "DETERMINISTIC", "design", false,
                List.of("input", "output")));
        add(values, new Archetype("decision", "PURE", "DETERMINISTIC", "design", false,
                List.of("rules", "input", "output")));
        add(values, new Archetype("resource-read", "READ_EXTERNAL", "IDEMPOTENT", "design", null,
                List.of("runtime", "requiresSecrets")));
        add(values, new Archetype("external-write", "WRITE_EXTERNAL", "UNKNOWN", "design", null,
                List.of("idempotency", "sideEffectProtocol", "requiresSecrets", "runtime")));
        add(values, new Archetype("remote-worker", "EXTERNAL", "UNKNOWN", "remote-worker", null,
                List.of("effect", "runtime", "durable", "timeout")));
        add(values, new Archetype("ai-tool", "EXTERNAL", "UNKNOWN", "ai-tool", null,
                List.of("runtime", "dataPolicy", "timeout")));
        add(values, new Archetype("event-source", "EXTERNAL", "UNKNOWN", "event-source", null,
                List.of("runtime", "delivery")));
        add(values, new Archetype("message-handler", "EXTERNAL", "UNKNOWN", "message-handler", null,
                List.of("effect", "runtime", "retry", "deadLetter")));
        add(values, new Archetype("webhook", "EXTERNAL", "UNKNOWN", "webhook", null,
                List.of("runtime", "auth", "requestVerification")));
        archetypes = Map.copyOf(values);
    }

    public Optional<Archetype> find(String name) {
        return Optional.ofNullable(archetypes.get(name == null ? "" : name.trim().toLowerCase()));
    }

    public Collection<Archetype> all() {
        return archetypes.values().stream()
                .sorted(java.util.Comparator.comparing(Archetype::name))
                .toList();
    }

    public String fingerprint(ObjectMapper mapper) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "version", VERSION,
                "archetypes", all()
        ), 256 * 1024);
    }

    private static void add(Map<String, Archetype> values, Archetype archetype) {
        values.put(archetype.name(), archetype);
    }

    public record Archetype(
            String name,
            String effect,
            String idempotency,
            String loweringMode,
            Boolean requiresSecretsDefault,
            List<String> requiredFacts
    ) {
        public Archetype {
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
        }
    }
}
