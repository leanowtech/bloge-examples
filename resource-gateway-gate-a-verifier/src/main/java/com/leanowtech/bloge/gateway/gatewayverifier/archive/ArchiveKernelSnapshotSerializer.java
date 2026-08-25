package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Deterministic JSON serializer for {@link ArchiveKernelSnapshot}.
 *
 * <p>Produces byte-identical output across multiple serialization runs:
 * <ul>
 *   <li>Fixed field order in all objects</li>
 *   <li>Entries sorted by name (UTF-8 lexicographic)</li>
 *   <li>Dependencies sorted by sha256Key (stable)</li>
 *   <li>No whitespace variations</li>
 * </ul>
 *
 * <p>This class is immutable and thread-safe.
 */
public final class ArchiveKernelSnapshotSerializer {

    private final ObjectMapper mapper;

    public ArchiveKernelSnapshotSerializer() {
        this.mapper = new ObjectMapper();
        this.mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.mapper.configure(JsonGenerator.Feature.STRICT_DUPLICATE_DETECTION, false);
        this.mapper.configure(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM, true);

        SimpleModule module = new SimpleModule("ArchiveKernelSnapshotModule");
        module.addSerializer(ArchiveKernelSnapshot.class, new SnapshotSerializer());
        module.addSerializer(ArchiveKernelSnapshot.ZipVerifierResult.class, new ZipVerifierResultSerializer());
        module.addSerializer(ArchiveKernelSnapshot.ClosureResult.class, new ClosureResultSerializer());
        module.addSerializer(ArchiveKernelSnapshot.LimitsResult.class, new LimitsResultSerializer());
        module.addSerializer(ArchiveKernelSnapshot.BindingResult.class, new BindingResultSerializer());
        module.addSerializer(ArchiveKernelSnapshot.Entry.class, new EntrySerializer());
        module.addSerializer(ArchiveKernelSnapshot.Dependency.class, new DependencySerializer());
        mapper.registerModule(module);
    }

    public byte[] serialize(ArchiveKernelSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            mapper.writeValue(baos, snapshot);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize snapshot", e);
        }
    }

    public String serializeToString(ArchiveKernelSnapshot snapshot) {
        return new String(serialize(snapshot), StandardCharsets.UTF_8);
    }

    public boolean isDeterministic(ArchiveKernelSnapshot snapshot) {
        byte[] first = serialize(snapshot);
        byte[] second = serialize(snapshot);
        byte[] third = serialize(snapshot);
        return Arrays.equals(first, second) && Arrays.equals(second, third);
    }

    // Fixed field order serialization

    private static class SnapshotSerializer extends JsonSerializer<ArchiveKernelSnapshot> {
        @Override
        public void serialize(ArchiveKernelSnapshot v, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeStartObject();
            gen.writeBooleanField("planHashValid", v.planHashValid());
            gen.writeStringField("planExpectedHash", v.planExpectedHash());
            gen.writeStringField("planActualHash", v.planActualHash());
            gen.writeBooleanField("rejected", v.rejected());
            writeField(gen, "rejectionCode", v.rejectionCode());
            writeField(gen, "rejectionArgs", v.rejectionArgs());
            gen.writeNumberField("entryCount", v.entryCount());
            gen.writeFieldName("entries");
            gen.writeStartArray();
            List<ArchiveKernelSnapshot.Entry> sortedEntries = new ArrayList<>(v.entries());
            sortedEntries.sort(Comparator.comparing(ArchiveKernelSnapshot.Entry::name));
            for (ArchiveKernelSnapshot.Entry e : sortedEntries) provider.defaultSerializeValue(e, gen);
            gen.writeEndArray();
            gen.writeNumberField("dependencyCount", v.dependencyCount());
            gen.writeFieldName("dependencies");
            gen.writeStartArray();
            List<ArchiveKernelSnapshot.Dependency> sortedDeps = new ArrayList<>(v.dependencies());
            sortedDeps.sort(Comparator.comparing(ArchiveKernelSnapshot.Dependency::sha256Key));
            for (ArchiveKernelSnapshot.Dependency d : sortedDeps) provider.defaultSerializeValue(d, gen);
            gen.writeEndArray();
            gen.writeFieldName("zipVerifierResult");
            provider.defaultSerializeValue(v.zipVerifierResult(), gen);
            gen.writeFieldName("closureResult");
            provider.defaultSerializeValue(v.closureResult(), gen);
            gen.writeFieldName("limitsResult");
            provider.defaultSerializeValue(v.limitsResult(), gen);
            gen.writeFieldName("bindingResult");
            provider.defaultSerializeValue(v.bindingResult(), gen);
            gen.writeEndObject();
        }
    }

    private static class ZipVerifierResultSerializer extends JsonSerializer<ArchiveKernelSnapshot.ZipVerifierResult> {
        @Override
        public void serialize(ArchiveKernelSnapshot.ZipVerifierResult v, JsonGenerator gen,
                              SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeBooleanField("passed", v.passed());
            gen.writeNumberField("entryCount", v.entryCount());
            gen.writeFieldName("entryNames");
            gen.writeStartArray();
            List<String> sortedNames = new ArrayList<>(v.entryNames());
            sortedNames.sort(String::compareTo);
            for (String n : sortedNames) gen.writeString(n);
            gen.writeEndArray();
            gen.writeBooleanField("rawBytesLimitExceeded", v.rawBytesLimitExceeded());
            gen.writeBooleanField("zipEntriesLimitExceeded", v.zipEntriesLimitExceeded());
            gen.writeEndObject();
        }
    }

    private static class ClosureResultSerializer extends JsonSerializer<ArchiveKernelSnapshot.ClosureResult> {
        @Override
        public void serialize(ArchiveKernelSnapshot.ClosureResult v, JsonGenerator gen,
                              SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeBooleanField("passed", v.passed());
            writeField(gen, "reasonCode", v.reasonCode());
            writeField(gen, "reasonArgs", v.reasonArgs());
            gen.writeEndObject();
        }
    }

    private static class LimitsResultSerializer extends JsonSerializer<ArchiveKernelSnapshot.LimitsResult> {
        @Override
        public void serialize(ArchiveKernelSnapshot.LimitsResult v, JsonGenerator gen,
                              SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeBooleanField("passed", v.passed());
            writeField(gen, "reasonCode", v.reasonCode());
            writeField(gen, "reasonArgs", v.reasonArgs());
            gen.writeEndObject();
        }
    }

    private static class BindingResultSerializer extends JsonSerializer<ArchiveKernelSnapshot.BindingResult> {
        @Override
        public void serialize(ArchiveKernelSnapshot.BindingResult v, JsonGenerator gen,
                              SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeBooleanField("passed", v.passed());
            gen.writeBooleanField("planMismatch", v.planMismatch());
            gen.writeBooleanField("countMismatch", v.countMismatch());
            writeField(gen, "reasonCode", v.reasonCode());
            writeField(gen, "reasonArgs", v.reasonArgs());
            gen.writeEndObject();
        }
    }

    private static class EntrySerializer extends JsonSerializer<ArchiveKernelSnapshot.Entry> {
        @Override
        public void serialize(ArchiveKernelSnapshot.Entry v, JsonGenerator gen,
                              SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("name", v.name());
            gen.writeStringField("sha256", v.sha256());
            gen.writeNumberField("uncompressedSize", v.uncompressedSize());
            gen.writeEndObject();
        }
    }

    private static class DependencySerializer extends JsonSerializer<ArchiveKernelSnapshot.Dependency> {
        @Override
        public void serialize(ArchiveKernelSnapshot.Dependency v, JsonGenerator gen,
                              SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("lockId", v.lockId());
            gen.writeStringField("entryPath", v.entryPath());
            gen.writeStringField("expectedFingerprint", v.expectedFingerprint());
            gen.writeStringField("actualFingerprint", v.actualFingerprint());
            gen.writeBooleanField("bound", v.bound());
            gen.writeStringField("sha256Key", v.sha256Key());
            gen.writeEndObject();
        }
    }

    // Null-safe field write with sorted object for maps
    private static void writeField(JsonGenerator gen, String name, Object value) throws IOException {
        gen.writeFieldName(name);
        if (value == null) {
            gen.writeNull();
        } else if (value instanceof String s) {
            gen.writeString(s);
        } else if (value instanceof Boolean b) {
            gen.writeBoolean(b);
        } else if (value instanceof Long l) {
            gen.writeNumber(l);
        } else if (value instanceof Integer i) {
            gen.writeNumber(i);
        } else if (value instanceof Double d) {
            gen.writeNumber(d);
        } else if (value instanceof Map<?, ?> m) {
            gen.writeStartObject();
            List<String> keys = new ArrayList<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                Object k = entry.getKey();
                if (k instanceof String s) {
                    keys.add(s);
                }
            }
            keys.sort(String::compareTo);
            for (String k : keys) {
                gen.writeFieldName(k);
                writeKnownValue(gen, m.get(k));
            }
            gen.writeEndObject();
        } else {
            gen.writeString(value.toString());
        }
    }

    private static void writeValue(JsonGenerator gen, Object val) throws IOException {
        if (val == null) {
            gen.writeNull();
        } else if (val instanceof String s) {
            gen.writeString(s);
        } else if (val instanceof Boolean b) {
            gen.writeBoolean(b);
        } else if (val instanceof Long l) {
            gen.writeNumber(l);
        } else if (val instanceof Integer i) {
            gen.writeNumber(i);
        } else if (val instanceof Double d) {
            gen.writeNumber(d);
        } else {
            gen.writeString(val.toString());
        }
    }


    // Writes a value known to be one of the frozen reasonArgs types (String, Boolean, Long, Integer).
    // No unchecked casts are needed because we match each type explicitly.
    private static void writeKnownValue(JsonGenerator gen, Object val) throws IOException {
        if (val == null) {
            gen.writeNull();
        } else if (val instanceof String s) {
            gen.writeString(s);
        } else if (val instanceof Boolean b) {
            gen.writeBoolean(b);
        } else if (val instanceof Long l) {
            gen.writeNumber(l);
        } else if (val instanceof Integer i) {
            gen.writeNumber(i);
        } else {
            // Fallback: should not occur for frozen protocol args
            gen.writeString(val.toString());
        }
    }

}
