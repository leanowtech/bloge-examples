package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Loads and validates the versioned, server-owned DSL syntax and example bundle. */
final class DslReferenceBundleLoader {
    static final String RESOURCE = "agent-tdd/dsl-reference/v1/bundle.json";
    private static final int MAX_STATIC_BUNDLE_BYTES = 256 * 1024;

    private final Bundle bundle;

    /** Fails closed when the packaged reference cannot be read or violates its structural contract. */
    DslReferenceBundleLoader(ObjectMapper mapper) {
        try (InputStream input = DslReferenceBundleLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Packaged DSL reference is missing");
            }
            byte[] bytes = input.readNBytes(MAX_STATIC_BUNDLE_BYTES + 1);
            if (bytes.length > MAX_STATIC_BUNDLE_BYTES) {
                throw new IllegalStateException("Packaged DSL reference exceeds its startup size limit");
            }
            JsonNode root = mapper.readTree(bytes);
            this.bundle = decode(mapper, root);
        } catch (IOException failure) {
            throw new IllegalStateException("Packaged DSL reference cannot be loaded", failure);
        }
    }

    /** @return immutable, startup-validated reference bundle */
    Bundle bundle() {
        return bundle;
    }

    private static Bundle decode(ObjectMapper mapper, JsonNode root) {
        if (!root.isObject() || !"rg.dslReferenceBundle.v1".equals(root.path("schemaVersion").asText())
                || root.path("languageVersion").asText().isBlank()
                || root.path("compilerProfile").asText().isBlank()) {
            throw new IllegalStateException("Packaged DSL reference has an unsupported schema");
        }
        List<String> roots = strings(root.path("supportedRootKinds"));
        if (!roots.equals(List.of("graph"))) {
            throw new IllegalStateException("Resource Gateway DSL reference must be graph-only");
        }
        List<DslReferenceSnapshot.Topic> topics = java.util.stream.StreamSupport
                .stream(root.path("topics").spliterator(), false)
                .map(DslReferenceBundleLoader::topic)
                .toList();
        ensureUnique(topics.stream().map(DslReferenceSnapshot.Topic::topicId).toList(), "topic");
        List<String> defaults = strings(root.path("defaultTopics"));
        if (!topics.stream().map(DslReferenceSnapshot.Topic::topicId).collect(java.util.stream.Collectors.toSet())
                .containsAll(defaults)) {
            throw new IllegalStateException("Packaged DSL reference contains an unknown default topic");
        }
        List<BundleExample> examples = java.util.stream.StreamSupport
                .stream(root.path("examples").spliterator(), false)
                .map(DslReferenceBundleLoader::example)
                .toList();
        ensureUnique(examples.stream().map(BundleExample::exampleId).toList(), "example");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, root, MAX_STATIC_BUNDLE_BYTES);
        return new Bundle(root.path("languageVersion").asText(), root.path("compilerProfile").asText(),
                roots, defaults, topics, examples, fingerprint);
    }

    private static DslReferenceSnapshot.Topic topic(JsonNode node) {
        String topicId = requiredSafeText(node, "topicId");
        String title = requiredSafeText(node, "title");
        List<DslReferenceSnapshot.Rule> rules = java.util.stream.StreamSupport
                .stream(node.path("rules").spliterator(), false)
                .map(rule -> new DslReferenceSnapshot.Rule(
                        requiredSafeText(rule, "ruleId"), requiredSafeText(rule, "summary")))
                .toList();
        if (rules.isEmpty()) {
            throw new IllegalStateException("Packaged DSL reference topic has no rules");
        }
        return new DslReferenceSnapshot.Topic(topicId, title, rules, strings(node.path("exampleRefs")));
    }

    private static BundleExample example(JsonNode node) {
        String source = requiredSafeText(node, "source");
        if (source.length() > 32 * 1024) {
            throw new IllegalStateException("Packaged DSL reference example is too large");
        }
        return new BundleExample(requiredSafeText(node, "exampleId"), requiredSafeText(node, "intent"),
                source, strings(node.path("assertions")), strings(node.path("requiredOperatorRefs")));
    }

    private static String requiredSafeText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank() || value.chars().anyMatch(character -> character < 0x20
                && character != '\n' && character != '\r' && character != '\t')) {
            throw new IllegalStateException("Packaged DSL reference contains invalid text");
        }
        return value;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalStateException("Packaged DSL reference contains a non-array selector");
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(value -> value.asText("").trim())
                .peek(value -> {
                    if (value.isBlank()) throw new IllegalStateException("Packaged DSL reference contains blank text");
                })
                .toList();
    }

    private static void ensureUnique(List<String> values, String kind) {
        Set<String> unique = new LinkedHashSet<>(values);
        if (unique.size() != values.size()) {
            throw new IllegalStateException("Packaged DSL reference contains a duplicate " + kind);
        }
    }

    /** Immutable static reference facts independent of tenant catalog state. */
    record Bundle(
            String languageVersion,
            String compilerProfile,
            List<String> supportedRootKinds,
            List<String> defaultTopics,
            List<DslReferenceSnapshot.Topic> topics,
            List<BundleExample> examples,
            String fingerprint
    ) {
        Bundle {
            supportedRootKinds = List.copyOf(supportedRootKinds);
            defaultTopics = List.copyOf(defaultTopics);
            topics = List.copyOf(topics);
            examples = List.copyOf(examples);
        }
    }

    /** Static example plus the operator contracts required to expose it safely. */
    record BundleExample(
            String exampleId,
            String intent,
            String source,
            List<String> assertions,
            List<String> requiredOperatorRefs
    ) {
        BundleExample {
            assertions = List.copyOf(assertions);
            requiredOperatorRefs = List.copyOf(requiredOperatorRefs);
        }
    }
}
