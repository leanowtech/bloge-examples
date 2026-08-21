package com.leanowtech.bloge.gateway.testkit.mounted;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.jar.JarFile;

/** Strict, bounded test-only parser for the packaged crash instrumentation manifest. */
final class StrictCrashInstrumentationManifest {
    static final int MAXIMUM_BYTES = 1024 * 1024;
    private static final String ENTRY = "META-INF/bloge/crash-instrumentation-v1.json";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final List<String> TOP_FIELDS = List.of(
            "messageVersion", "pointCount", "semanticWindowCount", "semanticWindows",
            "sources", "observationHooks", "points", "classes");
    private static final List<String> SOURCE_FIELDS = List.of(
            "sourcePath", "sourceSha256", "instrumentedSha256", "points");
    private static final List<String> HOOK_FIELDS = List.of(
            "hook", "className", "anchorSummary");
    private static final List<String> POINT_FIELDS = List.of(
            "point", "semanticWindowId", "className", "anchorSummary");
    private static final List<String> CLASS_FIELDS = List.of(
            "className", "classSha256");

    private StrictCrashInstrumentationManifest() {
    }

    static ObjectNode read(JarFile jar) throws IOException {
        return parse(readRaw(jar));
    }

    static byte[] readRaw(JarFile jar) throws IOException {
        var entry = jar.getJarEntry(ENTRY);
        if (entry == null || entry.getSize() < 1 || entry.getSize() > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("invalid crash manifest");
        }
        byte[] bytes;
        try (var input = jar.getInputStream(entry)) {
            bytes = input.readNBytes(MAXIMUM_BYTES + 1);
        }
        if (bytes.length < 1 || bytes.length > MAXIMUM_BYTES
                || bytes.length != entry.getSize()) {
            throw new IllegalArgumentException("invalid crash manifest");
        }
        return bytes;
    }

    static ObjectNode parse(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("invalid crash manifest");
        }
        try {
            String document = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            ObjectNode parsed;
            try (JsonParser parser = JSON.createParser(document)) {
                JsonNode value = JSON.readTree(parser);
                if (!(value instanceof ObjectNode object) || parser.nextToken() != null) {
                    throw new IllegalArgumentException("invalid crash manifest");
                }
                parsed = object;
            }
            if (!Arrays.equals(bytes, canonical(parsed))) {
                throw new IllegalArgumentException("invalid crash manifest");
            }
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("invalid crash manifest", invalid);
        }
    }

    private static byte[] canonical(ObjectNode parsed) throws IOException {
        requireFields(parsed, TOP_FIELDS);
        ObjectNode canonical = JSON.createObjectNode();
        canonical.set("messageVersion", parsed.get("messageVersion"));
        canonical.set("pointCount", parsed.get("pointCount"));
        canonical.set("semanticWindowCount", parsed.get("semanticWindowCount"));
        canonical.set("semanticWindows", requireArray(parsed, "semanticWindows"));
        canonical.set("sources", canonicalArray(parsed, "sources", SOURCE_FIELDS));
        canonical.set("observationHooks",
                canonicalArray(parsed, "observationHooks", HOOK_FIELDS));
        canonical.set("points", canonicalArray(parsed, "points", POINT_FIELDS));
        canonical.set("classes", canonicalArray(parsed, "classes", CLASS_FIELDS));
        byte[] json = JSON.writeValueAsBytes(canonical);
        byte[] withLf = Arrays.copyOf(json, json.length + 1);
        withLf[json.length] = '\n';
        return withLf;
    }

    private static ArrayNode canonicalArray(
            ObjectNode parent, String name, List<String> fields) {
        ArrayNode values = requireArray(parent, name);
        ArrayNode canonical = JSON.createArrayNode();
        for (JsonNode value : values) {
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalArgumentException("invalid crash manifest");
            }
            requireFields(object, fields);
            ObjectNode item = JSON.createObjectNode();
            fields.forEach(field -> item.set(field, object.get(field)));
            canonical.add(item);
        }
        return canonical;
    }

    private static ArrayNode requireArray(ObjectNode parent, String name) {
        JsonNode value = parent.get(name);
        if (!(value instanceof ArrayNode array)) {
            throw new IllegalArgumentException("invalid crash manifest");
        }
        return array;
    }

    private static void requireFields(ObjectNode object, List<String> expected) {
        Iterator<String> names = object.fieldNames();
        java.util.LinkedHashSet<String> actual = new java.util.LinkedHashSet<>();
        names.forEachRemaining(actual::add);
        if (!actual.equals(new java.util.LinkedHashSet<>(expected))) {
            throw new IllegalArgumentException("invalid crash manifest");
        }
    }
}
