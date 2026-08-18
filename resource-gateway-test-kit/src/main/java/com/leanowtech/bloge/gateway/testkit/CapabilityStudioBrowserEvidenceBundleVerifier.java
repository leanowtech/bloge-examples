package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Offline verifier and deterministic manifest producer for the Capability Studio browser
 * evidence bundle v1.
 *
 * <p>The verifier first delegates result semantics and the anomaly-to-normal exact binding to the
 * existing browser result verifiers. It then treats evidence references as untrusted coordinates,
 * resolves them below the supplied artifact root without following links, streams every evidence
 * file through SHA-256, and compares the complete on-disk file set with the declared closure. No
 * evidence payload is parsed or included in the returned manifest.</p>
 */
public final class CapabilityStudioBrowserEvidenceBundleVerifier {
    /** Manifest schema version emitted by this verifier. */
    public static final String SCHEMA_VERSION =
            "bloge.capabilityStudioBrowserEvidenceBundleManifest.v1";
    /** Required number of normal browser matrix cells. */
    public static final int EXPECTED_NORMAL_CELL_COUNT = 60;
    /** Required number of evidence references for each normal browser matrix cell. */
    public static final int EXPECTED_NORMAL_EVIDENCE_PER_CELL = 1;
    /** Required total number of normal browser matrix evidence references. */
    public static final int EXPECTED_NORMAL_EVIDENCE_COUNT =
            EXPECTED_NORMAL_CELL_COUNT * EXPECTED_NORMAL_EVIDENCE_PER_CELL;
    /** Required number of anomaly browser matrix obligations. */
    public static final int EXPECTED_ANOMALY_OBLIGATION_COUNT = 126;
    /** Required number of evidence references for each anomaly browser matrix obligation. */
    public static final int EXPECTED_ANOMALY_EVIDENCE_PER_OBLIGATION = 3;
    /** Required total number of anomaly browser matrix evidence references. */
    public static final int EXPECTED_ANOMALY_EVIDENCE_COUNT =
            EXPECTED_ANOMALY_OBLIGATION_COUNT * EXPECTED_ANOMALY_EVIDENCE_PER_OBLIGATION;
    /** Required total number of evidence references and persisted files. */
    public static final int EXPECTED_TOTAL_EVIDENCE_COUNT =
            EXPECTED_NORMAL_EVIDENCE_COUNT + EXPECTED_ANOMALY_EVIDENCE_COUNT;
    /** Maximum number of filesystem entries visited while inventorying evidence directories. */
    public static final int MAXIMUM_INVENTORY_ENTRIES = 4096;

    private static final String CODE_PREFIX =
            "RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_";
    private static final String NORMAL_PREFIX = "artifact:browser-matrix-evidence/";
    private static final String ANOMALY_PREFIX = "artifact:browser-anomaly-evidence/";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    /** Stable classification of a bundle verification result. */
    public enum FailureKind {
        /** The evidence bundle and both source results are valid. */
        NONE,
        /** The source results, artifact tree, or manifest contract is invalid. */
        INVALID
    }

    /**
     * Payload-free result returned by bundle verification.
     *
     * @param failureKind stable outcome classification
     * @param errorCode stable error code, or {@code null} for a complete bundle
     * @param expectedEntryCount number of evidence references declared by both results
     * @param persistedEntryCount number of regular evidence files found under both evidence dirs
     * @param manifestFingerprint manifest fingerprint, or {@code null} when no manifest exists
     * @param manifest deterministic manifest JSON, or {@code null} when verification failed
     */
    public record VerificationResult(
            FailureKind failureKind,
            String errorCode,
            int expectedEntryCount,
            int persistedEntryCount,
            String manifestFingerprint,
            JsonNode manifest) {
        /** Validates and defensively copies the payload-free result. */
        public VerificationResult {
            failureKind = Objects.requireNonNull(failureKind, "failureKind");
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("errorCode is not a protocol code");
            }
            if (expectedEntryCount < 0 || persistedEntryCount < 0) {
                throw new IllegalArgumentException("entry counts must not be negative");
            }
            manifest = manifest == null ? null : manifest.deepCopy();
        }

        /**
         * Returns whether all result, path, file-set, and fingerprint checks passed.
         *
         * @return true only for a complete bundle
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null && manifest != null;
        }

        /**
         * Returns a defensive manifest copy for callers that need to serialize it.
         *
         * @return manifest copy, or {@code null} after a failed verification
         */
        @Override
        public JsonNode manifest() {
            return manifest == null ? null : manifest.deepCopy();
        }
    }

    private record ExpectedEntry(String exactRef, String fingerprint) {
    }

    private record FileInventory(Map<String, Path> files, int regularFileCount) {
    }

    private record EvidenceDenominator(int itemCount, int evidenceCount, boolean exactPerItem) {
    }

    private record Digest(String fingerprint, long byteSize) {
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioBrowserEvidenceBundleVerifier() {
    }

    /**
     * Verifies the normal result, anomaly result, exact base binding, and artifact-root closure.
     *
     * @param normalResultBytes UTF-8 normal browser matrix result
     * @param anomalyResultBytes UTF-8 browser anomaly matrix result
     * @param artifactRoot root containing the two browser evidence directories
     * @return payload-free verification result and deterministic manifest on success
     */
    public VerificationResult verify(
            byte[] normalResultBytes,
            byte[] anomalyResultBytes,
            Path artifactRoot) {
        try {
            return verifyInternal(normalResultBytes, anomalyResultBytes, artifactRoot);
        } catch (RuntimeException failure) {
            return invalid("VERIFIER_FAILURE", 0, 0);
        }
    }

    private VerificationResult verifyInternal(
            byte[] normalResultBytes,
            byte[] anomalyResultBytes,
            Path artifactRoot) {
        if (normalResultBytes == null) {
            return invalid("NORMAL_RESULT_INVALID", 0, 0);
        }
        if (anomalyResultBytes == null) {
            return invalid("ANOMALY_RESULT_INVALID", 0, 0);
        }
        CapabilityStudioBrowserMatrixResultVerifier.VerificationResult normal =
                new CapabilityStudioBrowserMatrixResultVerifier().verify(normalResultBytes);
        CapabilityStudioBrowserAnomalyMatrixResultVerifier.VerificationResult anomaly =
                new CapabilityStudioBrowserAnomalyMatrixResultVerifier().verify(anomalyResultBytes);

        if (!normal.verified()) {
            return invalid("NORMAL_RESULT_INVALID", 0, 0);
        }
        if (!anomaly.verified()) {
            return invalid("ANOMALY_RESULT_INVALID", 0, 0);
        }
        if (normal.artifactStatus()
                != CapabilityStudioBrowserMatrixResultVerifier.ArtifactStatus.COMPLETE) {
            return invalid("NORMAL_RESULT_NOT_COMPLETE", 0, 0);
        }
        if (anomaly.artifactStatus()
                != CapabilityStudioBrowserAnomalyMatrixResultVerifier.ArtifactStatus.COMPLETE) {
            return invalid("ANOMALY_RESULT_NOT_COMPLETE", 0, 0);
        }

        CapabilityStudioBrowserAnomalyMatrixResultVerifier.VerificationResult binding =
                new CapabilityStudioBrowserAnomalyMatrixResultVerifier().verify(
                        anomalyResultBytes, normalResultBytes);
        if (!binding.verified()) {
            return invalid("BASE_BINDING_INVALID", 0, 0);
        }
        if (artifactRoot == null) {
            return invalid("ARTIFACT_ROOT_INVALID", 0, 0);
        }

        final JsonNode normalDocument;
        final JsonNode anomalyDocument;
        try {
            normalDocument = JSON.readTree(normalResultBytes);
            anomalyDocument = JSON.readTree(anomalyResultBytes);
        } catch (IOException | RuntimeException invalidJson) {
            return invalid("RESULT_PARSE_INVALID", 0, 0);
        }

        EvidenceDenominator normalDenominator = evidenceDenominator(
                normalDocument, "cells", EXPECTED_NORMAL_EVIDENCE_PER_CELL);
        EvidenceDenominator anomalyDenominator = evidenceDenominator(
                anomalyDocument, "obligations", EXPECTED_ANOMALY_EVIDENCE_PER_OBLIGATION);
        int declaredTotal = normalDenominator.evidenceCount()
                + anomalyDenominator.evidenceCount();
        if (normalDenominator.itemCount() != EXPECTED_NORMAL_CELL_COUNT
                || !normalDenominator.exactPerItem()
                || normalDenominator.evidenceCount() != EXPECTED_NORMAL_EVIDENCE_COUNT
                || anomalyDenominator.itemCount() != EXPECTED_ANOMALY_OBLIGATION_COUNT
                || !anomalyDenominator.exactPerItem()
                || anomalyDenominator.evidenceCount() != EXPECTED_ANOMALY_EVIDENCE_COUNT
                || declaredTotal != EXPECTED_TOTAL_EVIDENCE_COUNT) {
            return invalid("EVIDENCE_DENOMINATOR_MISMATCH", declaredTotal, 0);
        }

        Map<String, ExpectedEntry> expected = new TreeMap<>();
        String duplicateCode = extractExpected(normalDocument, "normal", NORMAL_PREFIX, expected);
        if (duplicateCode != null) {
            return invalid(duplicateCode, expected.size(), 0);
        }
        duplicateCode = extractExpected(anomalyDocument, "anomaly", ANOMALY_PREFIX, expected);
        if (duplicateCode != null) {
            return invalid(duplicateCode, expected.size(), 0);
        }
        if (!normalEvidenceRolesMatch(normalDocument)
                || !anomalyEvidenceRolesMatch(anomalyDocument)) {
            return invalid("EVIDENCE_ROLE_MISMATCH", expected.size(), 0);
        }

        final Path root;
        try {
            root = canonicalArtifactRoot(artifactRoot);
        } catch (BundleFailure failure) {
            return invalid(failure.code, expected.size(), failure.persistedCount);
        }

        FileInventory inventory;
        try {
            inventory = inventory(root);
        } catch (BundleFailure failure) {
            return invalid(failure.code, expected.size(), failure.persistedCount);
        }
        for (ExpectedEntry entry : expected.values()) {
            Path target = root.resolve(entry.exactRef().substring("artifact:".length())).normalize();
            if (!target.startsWith(root)) {
                return invalid("ROOT_ESCAPE", expected.size(), inventory.regularFileCount());
            }
            if (Files.isSymbolicLink(target)) {
                return invalid("EVIDENCE_SYMLINK", expected.size(), inventory.regularFileCount());
            }
            if (Files.exists(target, NOFOLLOW) && !Files.isRegularFile(target, NOFOLLOW)) {
                return invalid("EVIDENCE_NON_REGULAR", expected.size(), inventory.regularFileCount());
            }
        }
        if (!inventory.files().keySet().containsAll(expected.keySet())) {
            return invalid("EVIDENCE_MISSING", expected.size(), inventory.regularFileCount());
        }
        if (!expected.keySet().containsAll(inventory.files().keySet())) {
            return invalid("EVIDENCE_EXTRA", expected.size(), inventory.regularFileCount());
        }

        Map<String, Digest> persisted = new TreeMap<>();
        for (ExpectedEntry entry : expected.values()) {
            Path evidence = inventory.files().get(entry.exactRef());
            try {
                Digest digest = digest(evidence);
                if (digest.byteSize() == 0) {
                    return invalid("EVIDENCE_EMPTY", expected.size(), inventory.regularFileCount());
                }
                if (!entry.fingerprint().equals(digest.fingerprint())) {
                    return invalid("EVIDENCE_FINGERPRINT_MISMATCH",
                            expected.size(), inventory.regularFileCount());
                }
                persisted.put(entry.exactRef(), digest);
            } catch (IOException | RuntimeException failure) {
                return invalid("EVIDENCE_READ_INVALID", expected.size(), inventory.regularFileCount());
            }
        }

        ObjectNode manifest = manifest(normalDocument, anomalyDocument, persisted);
        try {
            var errors = CapabilityStudioSchemaSupport.validate(
                    manifest, CapabilityStudioSchemaSupport.BROWSER_EVIDENCE_BUNDLE_MANIFEST_RESOURCE);
            if (!errors.isEmpty()) {
                return invalid("MANIFEST_SCHEMA_INVALID", expected.size(), inventory.regularFileCount());
            }
        } catch (RuntimeException unavailable) {
            return invalid("MANIFEST_SCHEMA_UNAVAILABLE", expected.size(), inventory.regularFileCount());
        }
        String fingerprint = manifest.path("manifestFingerprint").asText();
        return new VerificationResult(
                FailureKind.NONE, null, expected.size(), inventory.regularFileCount(), fingerprint, manifest);
    }

    private static EvidenceDenominator evidenceDenominator(
            JsonNode result,
            String itemField,
            int expectedPerItem) {
        int itemCount = 0;
        int evidenceCount = 0;
        boolean exactPerItem = true;
        for (JsonNode item : result.path(itemField)) {
            itemCount++;
            int itemEvidenceCount = item.path("evidenceRefs").size();
            evidenceCount += itemEvidenceCount;
            if (itemEvidenceCount != expectedPerItem) {
                exactPerItem = false;
            }
        }
        return new EvidenceDenominator(itemCount, evidenceCount, exactPerItem);
    }

    private static String extractExpected(
            JsonNode result,
            String kind,
            String prefix,
            Map<String, ExpectedEntry> expected) {
        String resultField = "normal".equals(kind) ? "cells" : "obligations";
        String refField = "normal".equals(kind) ? "evidenceId" : "exactRef";
        for (JsonNode item : result.path(resultField)) {
            for (JsonNode ref : item.path("evidenceRefs")) {
                String exactRef = ref.path(refField).textValue();
                String fingerprint = ref.path("fingerprint").textValue();
                String pathCode = validateRef(exactRef, prefix);
                if (pathCode != null) {
                    return "EVIDENCE_" + pathCode;
                }
                ExpectedEntry previous = expected.putIfAbsent(
                        exactRef, new ExpectedEntry(exactRef, fingerprint));
                if (previous != null) {
                    return "EVIDENCE_DUPLICATE";
                }
            }
        }
        return null;
    }

    private static String validateRef(String exactRef, String prefix) {
        if (exactRef == null || !exactRef.startsWith(prefix)) {
            return "PREFIX_INVALID";
        }
        String relative = exactRef.substring("artifact:".length());
        if (relative.isEmpty() || relative.indexOf('\\') >= 0 || relative.indexOf('%') >= 0) {
            return "PATH_INVALID";
        }
        String[] segments = relative.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return "PATH_INVALID";
            }
        }
        try {
            Path path = Path.of(relative);
            if (path.isAbsolute() || !path.normalize().equals(path)) {
                return "PATH_INVALID";
            }
        } catch (RuntimeException invalidPath) {
            return "PATH_INVALID";
        }
        return null;
    }

    private static boolean normalEvidenceRolesMatch(JsonNode result) {
        for (JsonNode cell : result.path("cells")) {
            String fileName = cell.path("goldenPathId").asText().toLowerCase(Locale.ROOT)
                    + "-" + cell.path("locale").asText()
                    + "-" + cell.path("viewport").path("width").asInt()
                    + "x" + cell.path("viewport").path("height").asInt()
                    + ".png";
            if (!exactRefsMatch(cell.path("evidenceRefs"), "evidenceId",
                    List.of(NORMAL_PREFIX + fileName))) {
                return false;
            }
        }
        return true;
    }

    private static boolean anomalyEvidenceRolesMatch(JsonNode result) {
        for (JsonNode obligation : result.path("obligations")) {
            String obligationId = obligation.path("obligationId").asText();
            if (!obligationId.startsWith("BAM-")) {
                return false;
            }
            String prefix = ANOMALY_PREFIX
                    + obligationId.substring("BAM-".length()).toLowerCase(Locale.ROOT);
            if (!exactRefsMatch(obligation.path("evidenceRefs"), "exactRef", List.of(
                    prefix + "-error.png",
                    prefix + "-recovered.png",
                    prefix + "-trigger.json"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactRefsMatch(
            JsonNode refs,
            String field,
            List<String> expected) {
        if (refs.size() != expected.size()) {
            return false;
        }
        java.util.Set<String> actual = new java.util.HashSet<>();
        for (JsonNode ref : refs) {
            if (!actual.add(ref.path(field).asText())) {
                return false;
            }
        }
        return actual.equals(java.util.Set.copyOf(expected));
    }

    private static Path canonicalArtifactRoot(Path artifactRoot) throws BundleFailure {
        if (artifactRoot == null) {
            throw new BundleFailure("ARTIFACT_ROOT_INVALID", 0);
        }
        try {
            Path absolute = artifactRoot.toAbsolutePath().normalize();
            if (hasCallerControlledSymbolicLinkComponent(absolute)) {
                throw new BundleFailure("ARTIFACT_ROOT_SYMLINK", 0);
            }
            if (!Files.isDirectory(absolute, NOFOLLOW)) {
                throw new BundleFailure("ARTIFACT_ROOT_INVALID", 0);
            }
            Path canonical = absolute.toRealPath();
            if (hasCallerControlledSymbolicLinkComponent(absolute)) {
                throw new BundleFailure("ARTIFACT_ROOT_SYMLINK", 0);
            }
            if (!Files.isDirectory(canonical, NOFOLLOW)) {
                throw new BundleFailure("ARTIFACT_ROOT_INVALID", 0);
            }
            return canonical;
        } catch (BundleFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new BundleFailure("ARTIFACT_ROOT_INVALID", 0);
        }
    }

    private static boolean hasCallerControlledSymbolicLinkComponent(Path absolute) {
        Path current = absolute.getRoot();
        int index = 0;
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            // A privileged top-level alias such as macOS /var -> /private/var is canonicalized.
            if (Files.isSymbolicLink(current)
                    && (index > 0 || absolute.getNameCount() == 1)) {
                return true;
            }
            index++;
        }
        return false;
    }

    private static FileInventory inventory(Path root) throws BundleFailure {
        Map<String, Path> files = new TreeMap<>();
        int regular = 0;
        int visited = 0;
        for (String directory : List.of("browser-matrix-evidence", "browser-anomaly-evidence")) {
            Path evidenceDirectory = root.resolve(directory).normalize();
            if (!evidenceDirectory.startsWith(root)
                    || Files.isSymbolicLink(evidenceDirectory)) {
                throw new BundleFailure("EVIDENCE_SYMLINK", regular);
            }
            if (!Files.isDirectory(evidenceDirectory, NOFOLLOW)) {
                throw new BundleFailure("EVIDENCE_DIRECTORY_INVALID", regular);
            }
            try (Stream<Path> paths = Files.walk(evidenceDirectory, Integer.MAX_VALUE)) {
                Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (path.equals(evidenceDirectory)) {
                        continue;
                    }
                    visited++;
                    if (visited > MAXIMUM_INVENTORY_ENTRIES) {
                        throw new BundleFailure("EVIDENCE_INVENTORY_LIMIT_EXCEEDED", regular);
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw new BundleFailure("EVIDENCE_SYMLINK", regular);
                    }
                    if (Files.isDirectory(path, NOFOLLOW)) {
                        continue;
                    }
                    if (!Files.isRegularFile(path, NOFOLLOW)) {
                        throw new BundleFailure("EVIDENCE_NON_REGULAR", regular);
                    }
                    String relative = root.relativize(path).toString()
                            .replace(path.getFileSystem().getSeparator(), "/");
                    String exactRef = "artifact:" + relative;
                    String prefix = directory.equals("browser-matrix-evidence")
                            ? NORMAL_PREFIX : ANOMALY_PREFIX;
                    String pathCode = validateRef(exactRef, prefix);
                    if (pathCode != null) {
                        throw new BundleFailure("EVIDENCE_" + pathCode, regular);
                    }
                    if (files.putIfAbsent(exactRef, path) != null) {
                        throw new BundleFailure("EVIDENCE_DUPLICATE", regular);
                    }
                    regular++;
                }
            } catch (IOException | UncheckedIOException failure) {
                throw new BundleFailure("EVIDENCE_DIRECTORY_READ_INVALID", regular);
            }
        }
        return new FileInventory(Map.copyOf(files), regular);
    }

    private static Digest digest(Path evidence) throws IOException {
        if (Files.isSymbolicLink(evidence) || !Files.isRegularFile(evidence, NOFOLLOW)) {
            throw new IOException("evidence is not a regular file");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = Files.newInputStream(evidence, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
                    DigestInputStream stream = new DigestInputStream(input, digest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read > 0) {
                        size += read;
                    }
                }
            }
            return new Digest("sha256:" + HexFormat.of().formatHex(digest.digest()), size);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static ObjectNode manifest(
            JsonNode normal,
            JsonNode anomaly,
            Map<String, Digest> persisted) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.set("normal", resultSummary(normal));
        material.set("anomaly", resultSummary(anomaly));
        material.put("expectedEntryCount", EXPECTED_TOTAL_EVIDENCE_COUNT);
        material.put("persistedEntryCount", EXPECTED_TOTAL_EVIDENCE_COUNT);
        ArrayNode entries = material.putArray("entries");
        persisted.forEach((exactRef, digest) -> entries.addObject()
                .put("exactRef", exactRef)
                .put("fingerprint", digest.fingerprint())
                .put("byteSize", digest.byteSize()));
        material.remove("manifestFingerprint");
        material.put("manifestFingerprint", EvidenceVerificationSupport.sha256Bounded(material,
                4 * 1024 * 1024));
        return material;
    }

    private static ObjectNode resultSummary(JsonNode result) {
        return JSON.createObjectNode()
                .put("resultId", result.path("resultId").asText())
                .put("evidenceClosureFingerprint",
                        result.path("evidenceClosureFingerprint").asText());
    }

    private static VerificationResult invalid(String suffix, int expected, int persisted) {
        return new VerificationResult(
                FailureKind.INVALID, CODE_PREFIX + suffix, expected, persisted, null, null);
    }

    private static final class BundleFailure extends Exception {
        private final String code;
        private final int persistedCount;

        private BundleFailure(String code, int persistedCount) {
            this.code = code;
            this.persistedCount = persistedCount;
        }
    }
}
