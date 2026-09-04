package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Loads and validates the versioned, server-owned DSL syntax and example bundle. */
final class DslReferenceBundleLoader {
    static final String RESOURCE = "agent-tdd/dsl-reference/v1/bundle.json";
    private static final String DSL_RUNTIME_METADATA =
            "META-INF/maven/com.leanowtech.bloge/bloge-dsl/pom.properties";
    private static final int MAX_STATIC_BUNDLE_BYTES = 256 * 1024;

    private final Bundle bundle;

    /** Fails closed when the packaged reference cannot be read or violates its structural contract. */
    DslReferenceBundleLoader(ObjectMapper mapper) {
        this(mapper, runtimeLanguageVersion());
    }

    /**
     * Reads the Maven identity packaged inside the actual DSL runtime selected by the class loader.
     *
     * <p>This is intentionally not a source constant: a command-line dependency override must
     * change the observed version and make a stale reference bundle fail during startup.</p>
     *
     * @return language identity bound to the linked {@link DslCompiler} artifact
     */
    static String runtimeLanguageVersion() {
        ClassLoader runtimeLoader = DslCompiler.class.getClassLoader();
        try (InputStream input = runtimeLoader.getResourceAsStream(DSL_RUNTIME_METADATA)) {
            if (input == null) {
                throw new IllegalStateException("BLOGE DSL runtime metadata is missing");
            }
            Properties metadata = new Properties();
            metadata.load(input);
            String artifact = metadata.getProperty("artifactId", "").trim();
            String group = metadata.getProperty("groupId", "").trim();
            String version = metadata.getProperty("version", "").trim();
            if (!"com.leanowtech.bloge".equals(group) || !"bloge-dsl".equals(artifact)
                    || !version.matches("[0-9A-Za-z][0-9A-Za-z._-]*")) {
                throw new IllegalStateException("BLOGE DSL runtime metadata is invalid");
            }
            return "bloge-dsl-" + version;
        } catch (IOException failure) {
            throw new IllegalStateException("BLOGE DSL runtime metadata cannot be loaded", failure);
        }
    }

    /**
     * Fails closed when the packaged reference does not describe the linked BLOGE DSL version.
     *
     * @param mapper canonical JSON mapper
     * @param expectedLanguageVersion build-certified BLOGE DSL language version
     */
    DslReferenceBundleLoader(ObjectMapper mapper, String expectedLanguageVersion) {
        try (InputStream input = DslReferenceBundleLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Packaged DSL reference is missing");
            }
            byte[] bytes = input.readNBytes(MAX_STATIC_BUNDLE_BYTES + 1);
            if (bytes.length > MAX_STATIC_BUNDLE_BYTES) {
                throw new IllegalStateException("Packaged DSL reference exceeds its startup size limit");
            }
            JsonNode root = mapper.readTree(bytes);
            this.bundle = decode(mapper, root, expectedLanguageVersion);
        } catch (IOException failure) {
            throw new IllegalStateException("Packaged DSL reference cannot be loaded", failure);
        }
    }

    /** @return immutable, startup-validated reference bundle */
    Bundle bundle() {
        return bundle;
    }

    private static Bundle decode(ObjectMapper mapper, JsonNode root, String expectedLanguageVersion) {
        if (!root.isObject() || !"rg.dslReferenceBundle.v1".equals(root.path("schemaVersion").asText())
                || root.path("languageVersion").asText().isBlank()
                || root.path("compilerProfile").asText().isBlank()) {
            throw new IllegalStateException("Packaged DSL reference has an unsupported schema");
        }
        if (expectedLanguageVersion == null || expectedLanguageVersion.isBlank()
                || !expectedLanguageVersion.equals(root.path("languageVersion").asText())) {
            throw new IllegalStateException("Packaged DSL reference does not match the BLOGE DSL runtime");
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
                .map(node -> example(mapper, node))
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

    private static BundleExample example(ObjectMapper mapper, JsonNode node) {
        String source = requiredSafeText(node, "source");
        if (source.length() > 32 * 1024) {
            throw new IllegalStateException("Packaged DSL reference example is too large");
        }
        String exampleId = requiredSafeText(node, "exampleId");
        String intent = requiredSafeText(node, "intent");
        List<String> assertions = strings(node.path("assertions"));
        List<String> requiredOperatorRefs = strings(node.path("requiredOperatorRefs"));
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, java.util.Map.of(
                "exampleId", exampleId, "intent", intent, "source", source,
                "assertions", assertions, "requiredOperatorRefs", requiredOperatorRefs),
                MAX_STATIC_BUNDLE_BYTES);
        return new BundleExample(exampleId, intent, source, assertions, requiredOperatorRefs, fingerprint);
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
            List<String> requiredOperatorRefs,
            String fingerprint
    ) {
        BundleExample {
            assertions = List.copyOf(assertions);
            requiredOperatorRefs = List.copyOf(requiredOperatorRefs);
        }
    }
}
