package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Gate A wire helpers and the closed manifest vocabulary. */
final class CapabilityStudioFormalEvidenceRunManifest {
    /** Fixed contract identifier for Gate A formal evidence run manifests. */
    public static final String CONTRACT_ID = "RG-CS-FELT-v1";
    /** Number of formal checks expected by the full acceptance design. */
    public static final int FORMAL_EXPECTED_COUNT = 27;
    /** Maximum number of evidence files admitted by one manifest. */
    public static final int MAXIMUM_EVIDENCE_COUNT = 256;
    /** Maximum aggregate size, in bytes, of declared evidence files. */
    public static final long MAXIMUM_EVIDENCE_BYTES = 32L * 1024 * 1024;
    /** Maximum size, in bytes, of one declared evidence file. */
    public static final int MAXIMUM_EVIDENCE_FILE_BYTES = 4 * 1024 * 1024;
    /** Maximum encoded manifest size, in bytes. */
    public static final int MAXIMUM_MANIFEST_BYTES = 1024 * 1024;
    /** FELT obligation identifiers in their required wire order. */
    public static final List<String> OBLIGATION_IDS = List.of(
            "FELT-01", "FELT-02", "FELT-03", "FELT-04", "FELT-05", "FELT-06",
            "FELT-07", "FELT-08", "FELT-09", "FELT-10", "FELT-11", "FELT-12",
            "FELT-13", "FELT-14");

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern DATE_TIME = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> ALLOWED_STATUS = Set.of("FAIL", "BLOCKED", "NOT_RUN");
    private static final int MAXIMUM_TYPED_REPLAY_SLOTS = 3;
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private CapabilityStudioFormalEvidenceRunManifest() {
    }

    /**
     * Parses exactly one JSON value with duplicate-field detection enabled.
     *
     * @param bytes encoded manifest bytes
     * @return parsed JSON value
     * @throws IOException if bytes are absent, malformed, duplicated, or followed by trailing JSON
     */
    public static JsonNode parseStrict(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("manifest bytes are absent");
        }
        try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
            JsonNode value = JSON.readTree(parser);
            if (value == null || parser.nextToken() != null) {
                throw new IOException("manifest has trailing JSON");
            }
            return value;
        }
    }

    /**
     * Encodes a JSON value with object keys recursively sorted.
     *
     * @param value JSON value to encode
     * @return canonical JSON bytes
     * @throws IOException if the value is absent or cannot be encoded
     */
    public static byte[] canonicalBytes(JsonNode value) throws IOException {
        if (value == null) {
            throw new IOException("canonical value is absent");
        }
        return JSON.writeValueAsBytes(canonicalNode(value));
    }

    /**
     * Computes the SHA-256 fingerprint of a canonical JSON value.
     *
     * @param value JSON value to fingerprint
     * @return lowercase {@code sha256:} fingerprint
     * @throws IOException if the value cannot be canonically encoded
     */
    public static String canonicalFingerprint(JsonNode value) throws IOException {
        return sha256(canonicalBytes(value));
    }

    /**
     * Computes the SHA-256 fingerprint of exact bytes.
     *
     * @param bytes bytes to fingerprint
     * @return lowercase {@code sha256:} fingerprint
     */
    public static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Compiles and validates one exact, canonical manifest byte sequence. */
    static Compiled compile(byte[] exactBytes) {
        if (exactBytes == null || exactBytes.length > MAXIMUM_MANIFEST_BYTES) {
            throw invalid();
        }
        final JsonNode manifest;
        try {
            manifest = parseStrict(exactBytes);
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
        try {
            if (!Arrays.equals(canonicalBytes(manifest), exactBytes)) {
                throw invalid();
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof CompileException compileFailure) {
                throw compileFailure;
            }
            throw invalid();
        }
        verifySchema(manifest);
        verifyHeader(manifest);
        Obligations obligations = verifyObligations(manifest);
        Map<String, EvidenceEntry> inventory =
                verifyInventory(manifest.path("evidenceInventory"));
        long evidenceByteSize = verifyInventoryCounts(manifest, inventory);
        verifyObligationEvidencePaths(manifest, inventory);
        List<CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest> replayPlan =
                verifyTypedReplayRefs(manifest.path("typedEvidenceReplays"), inventory);
        verifyManifestFingerprint(manifest);
        return new Compiled(
                inventory, replayPlan, obligations.values(), obligations.counts(),
                evidenceByteSize, text(manifest, "verificationLevel"),
                text(manifest, "manifestFingerprint"), sha256(exactBytes), exactBytes);
    }

    static ObjectNode manifestWithoutFingerprint(JsonNode manifest) {
        ObjectNode copy = (ObjectNode) manifest.deepCopy();
        copy.putNull("manifestFingerprint");
        return copy;
    }

    private static void verifySchema(JsonNode manifest) {
        try {
            if (!CapabilityStudioSchemaSupport.validate(
                    manifest, CapabilityStudioSchemaSupport.FORMAL_EVIDENCE_RUN_MANIFEST_RESOURCE)
                    .isEmpty()) {
                throw invalid();
            }
        } catch (CompileException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void verifyHeader(JsonNode manifest) {
        String runId = text(manifest, "runId");
        if (!CONTRACT_ID.equals(text(manifest, "contractId"))
                || !FINGERPRINT.matcher(runId).matches()
                || manifest.path("passed").intValue() != 0
                || manifest.path("formalPassCount").intValue() != 0
                || manifest.path("formalExpectedCount").intValue() != FORMAL_EXPECTED_COUNT) {
            throw invalid();
        }
        Instant started = verifyInstant(manifest.path("executionWindow").path("startedAt"));
        Instant ended = verifyInstant(manifest.path("executionWindow").path("endedAt"));
        Instant reviewed = verifyInstant(manifest.path("independentReview").path("reviewedAt"));
        if (ended.isBefore(started) || reviewed.isBefore(ended)) {
            throw invalid();
        }
    }

    private static Obligations verifyObligations(JsonNode manifest) {
        JsonNode values = manifest.path("obligations");
        if (!values.isArray() || values.size() != OBLIGATION_IDS.size()) {
            throw invalid();
        }
        int failed = 0;
        int blocked = 0;
        int notRun = 0;
        List<Obligation> compiled = new ArrayList<>(OBLIGATION_IDS.size());
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            String id = text(value, "id");
            String status = text(value, "status");
            if (!OBLIGATION_IDS.get(index).equals(id) || !ALLOWED_STATUS.contains(status)) {
                throw invalid();
            }
            if (("BLOCKED".equals(status) || "NOT_RUN".equals(status))
                    && value.path("evidencePaths").size() != 0) {
                throw invalid();
            }
            String previous = null;
            List<String> evidencePaths = new ArrayList<>();
            for (JsonNode path : value.path("evidencePaths")) {
                String current = path.textValue();
                if (!safePath(current) || previous != null && previous.compareTo(current) >= 0) {
                    throw invalid();
                }
                previous = current;
                evidencePaths.add(current);
            }
            compiled.add(new Obligation(id, status, evidencePaths));
            switch (status) {
                case "FAIL" -> failed++;
                case "BLOCKED" -> blocked++;
                case "NOT_RUN" -> notRun++;
                default -> throw invalid();
            }
        }
        if (failed != manifest.path("failed").intValue()
                || blocked != manifest.path("blocked").intValue()
                || notRun != manifest.path("notRun").intValue()
                || failed + blocked + notRun != OBLIGATION_IDS.size()) {
            throw invalid();
        }
        return new Obligations(compiled, new ObligationCounts(failed, blocked, notRun));
    }

    private static Map<String, EvidenceEntry> verifyInventory(JsonNode value) {
        if (!value.isArray() || value.size() > MAXIMUM_EVIDENCE_COUNT) {
            throw invalid();
        }
        Map<String, EvidenceEntry> result = new LinkedHashMap<>();
        String previous = null;
        for (JsonNode entry : value) {
            String path = text(entry, "relativePath");
            if (!safePath(path) || previous != null && previous.compareTo(path) >= 0) {
                throw invalid();
            }
            previous = path;
            EvidenceEntry compiled = new EvidenceEntry(
                    path, entry.path("byteSize").longValue(),
                    text(entry, "rawFingerprint"));
            if (result.put(path, compiled) != null) {
                throw invalid();
            }
        }
        return result;
    }

    private static long verifyInventoryCounts(
            JsonNode manifest, Map<String, EvidenceEntry> inventory) {
        long total = 0;
        for (EvidenceEntry entry : inventory.values()) {
            long size = entry.byteSize();
            if (size < 0 || size > MAXIMUM_EVIDENCE_FILE_BYTES
                    || total > MAXIMUM_EVIDENCE_BYTES - size) {
                throw invalid();
            }
            total += size;
        }
        try {
            if (manifest.path("evidenceCount").intValue() != inventory.size()
                    || manifest.path("evidenceByteSize").longValue() != total
                    || !canonicalFingerprint(manifest.path("evidenceInventory"))
                    .equals(text(manifest, "inventoryClosureFingerprint"))) {
                throw invalid();
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof CompileException compileFailure) {
                throw compileFailure;
            }
            throw invalid();
        }
        return total;
    }

    private static void verifyObligationEvidencePaths(
            JsonNode manifest, Map<String, EvidenceEntry> inventory) {
        for (JsonNode obligation : manifest.path("obligations")) {
            for (JsonNode path : obligation.path("evidencePaths")) {
                if (!inventory.containsKey(path.textValue())) {
                    throw invalid();
                }
            }
        }
    }

    private static List<CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest>
            verifyTypedReplayRefs(JsonNode replays, Map<String, EvidenceEntry> inventory) {
        if (!replays.isArray() || replays.size() > MAXIMUM_TYPED_REPLAY_SLOTS) {
            throw invalid();
        }
        String previousId = null;
        Set<String> ids = new java.util.HashSet<>();
        Set<CapabilityStudioTypedEvidenceReplayRegistry.Slot> adapterSlots =
                EnumSet.noneOf(CapabilityStudioTypedEvidenceReplayRegistry.Slot.class);
        List<CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest> result =
                new ArrayList<>();
        for (JsonNode replay : replays) {
            CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest request;
            try {
                request = CapabilityStudioTypedEvidenceReplayRegistry.compile(replay);
            } catch (CapabilityStudioTypedEvidenceReplayRegistry.ReplayException failure) {
                throw invalid();
            }
            String id = request.id();
            if ((previousId != null && previousId.compareTo(id) >= 0)
                    || !ids.add(id) || !adapterSlots.add(request.slot())) {
                throw invalid();
            }
            previousId = id;
            String subject = request.subjectPath();
            if (!safePath(subject)) {
                throw invalid();
            }
            if (request.slot().fileSubject()) {
                if (!inventory.containsKey(subject)) {
                    throw invalid();
                }
            } else if (inventory.keySet().stream()
                    .noneMatch(path -> path.startsWith(subject + "/"))) {
                throw invalid();
            }
            result.add(request);
        }
        return List.copyOf(result);
    }

    private static void verifyManifestFingerprint(JsonNode manifest) {
        try {
            if (!canonicalFingerprint(manifestWithoutFingerprint(manifest))
                    .equals(text(manifest, "manifestFingerprint"))) {
                throw invalid();
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof CompileException compileFailure) {
                throw compileFailure;
            }
            throw invalid();
        }
    }

    private static Instant verifyInstant(JsonNode value) {
        String valueText = value.textValue();
        if (valueText == null || !DATE_TIME.matcher(valueText).matches()) {
            throw invalid();
        }
        try {
            return Instant.parse(valueText);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).textValue();
        if (value == null || value.isEmpty()) {
            throw invalid();
        }
        return value;
    }

    private static boolean safePath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.endsWith("/")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)
                    || !SAFE_SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    private static CompileException invalid() {
        return new CompileException(FailureKind.INVALID);
    }

    private static CompileException unavailable() {
        return new CompileException(FailureKind.UNAVAILABLE);
    }

    enum FailureKind {
        INVALID,
        UNAVAILABLE
    }

    static final class CompileException extends RuntimeException {
        private final FailureKind failureKind;

        CompileException(FailureKind failureKind) {
            super("formal evidence run manifest compilation failed");
            this.failureKind = failureKind;
        }

        FailureKind failureKind() {
            return failureKind;
        }

        FailureKind kind() {
            return failureKind;
        }
    }

    record ObligationCounts(int failed, int blocked, int notRun) {
    }

    record Obligation(String id, String status, List<String> evidencePaths) {
        Obligation {
            if (!OBLIGATION_IDS.contains(id) || !ALLOWED_STATUS.contains(status)
                    || evidencePaths == null) {
                throw invalid();
            }
            evidencePaths = List.copyOf(evidencePaths);
        }
    }

    private record Obligations(List<Obligation> values, ObligationCounts counts) {
        private Obligations {
            values = List.copyOf(values);
        }
    }

    record EvidenceEntry(String relativePath, long byteSize, String rawFingerprint) {
        EvidenceEntry {
            if (!safePath(relativePath) || byteSize < 0
                    || byteSize > MAXIMUM_EVIDENCE_FILE_BYTES
                    || !FINGERPRINT.matcher(rawFingerprint).matches()) {
                throw invalid();
            }
        }
    }

    static final class Compiled {
        private final Map<String, EvidenceEntry> inventory;
        private final List<CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest> replayPlan;
        private final List<Obligation> obligations;
        private final ObligationCounts obligationCounts;
        private final long evidenceByteSize;
        private final String verificationLevel;
        private final String manifestFingerprint;
        private final String rawManifestFingerprint;
        private final byte[] exactBytes;

        private Compiled(
                Map<String, EvidenceEntry> inventory,
                List<CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest> replayPlan,
                List<Obligation> obligations, ObligationCounts obligationCounts,
                long evidenceByteSize, String verificationLevel,
                String manifestFingerprint, String rawManifestFingerprint, byte[] exactBytes) {
            this.inventory = Map.copyOf(new LinkedHashMap<>(inventory));
            this.replayPlan = List.copyOf(replayPlan);
            this.obligations = List.copyOf(obligations);
            this.obligationCounts = obligationCounts;
            this.evidenceByteSize = evidenceByteSize;
            this.verificationLevel = verificationLevel;
            this.manifestFingerprint = manifestFingerprint;
            this.rawManifestFingerprint = rawManifestFingerprint;
            this.exactBytes = exactBytes.clone();
        }

        Map<String, EvidenceEntry> inventory() {
            return inventory;
        }

        List<CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest> replayPlan() {
            return replayPlan;
        }

        List<Obligation> obligations() {
            return obligations;
        }

        ObligationCounts obligationCounts() {
            return obligationCounts;
        }

        String manifestFingerprint() {
            return manifestFingerprint;
        }

        String rawManifestFingerprint() {
            return rawManifestFingerprint;
        }

        String verificationLevel() {
            return verificationLevel;
        }

        long evidenceByteSize() {
            return evidenceByteSize;
        }

        byte[] exactBytes() {
            return exactBytes.clone();
        }
    }

    private static JsonNode canonicalNode(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Map<String, JsonNode> sorted = new TreeMap<>();
            value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, child) -> result.set(key, canonicalNode(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(child -> result.add(canonicalNode(child)));
            return result;
        }
        return value.deepCopy();
    }
}
