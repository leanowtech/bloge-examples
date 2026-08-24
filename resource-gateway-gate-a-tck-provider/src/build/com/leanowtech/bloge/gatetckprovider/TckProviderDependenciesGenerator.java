package com.leanowtech.bloge.gatetckprovider;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/**
 * JDK-only generator for META-INF/gate-a/manifests/dependencies.json.
 *
 * <p>Build-time only. Reads the Candidate (gate-a-candidate) JAR from the local Maven
 * repository and emits one scope=provided entry binding the Candidate SPI class
 * raw fingerprint. No runtime dependencies.
 *
 * <p>Manifest format: capability-studio-gate-a-dependency-lock-manifest-v1.schema.json
 * <p>Fingerprint domain: RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1
 *
 * <p>Authority constants from gate-a-protocol-authority-v1.json TCK_PROVIDER role:
 * <ul>
 *   <li>coordinate: com.leanowtech.bloge:bloge-resource-gateway-test-kit:1.0.0:gate-a-candidate</li>
 *   <li>entryPath: com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class</li>
 *   <li>scope: provided</li>
 * </ul>
 */
public final class TckProviderDependenciesGenerator {

    private static final String SCHEMA_VERSION =
            "capability-studio.gate-a-dependency-lock-manifest.v1";
    private static final String DOMAIN =
            "RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1";

    // Authority-defined Candidate coordinate
    private static final String CANDIDATE_GROUP    = "com.leanowtech.bloge";
    private static final String CANDIDATE_ARTIFACT = "bloge-resource-gateway-test-kit";
    private static final String CANDIDATE_VERSION  = "1.0.0";
    private static final String CANDIDATE_CLASSIFIER = "gate-a-candidate";
    private static final String CANDIDATE_COORDINATE =
            CANDIDATE_GROUP + ":" + CANDIDATE_ARTIFACT + ":" + CANDIDATE_VERSION + ":" + CANDIDATE_CLASSIFIER;

    // SPI class path from authority candidateClassEntryPath
    private static final String SPI_CLASS_ENTRY_PATH =
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class";

    // Bounded read limit (8MiB for single class file)
    private static final int MAX_SPI_CLASS_BYTES = 8 * 1024 * 1024;

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("USAGE");
            System.exit(1);
        }

        Path mavenRepo;
        Path outputDir;
        try {
            mavenRepo = Path.of(args[0]);
            outputDir = Path.of(args[1]);
        } catch (InvalidPathException | NullPointerException e) {
            System.err.println("INVALID_INPUT");
            System.exit(1);
            return;
        }

        // Validate mavenRepo is a directory with NOFOLLOW
        if (!isRegularDirectory(mavenRepo)) {
            System.err.println("INVALID_REPO_DIR");
            System.exit(1);
        }

        // Validate/create outputDir with symlink rejection
        if (!ensureRegularDirectory(outputDir)) {
            System.err.println("INVALID_OUTPUT_DIR");
            System.exit(1);
        }

        // Resolve Candidate JAR path
        String mavenRepoPath;
        try {
            mavenRepoPath = CANDIDATE_GROUP.replace('.', '/');
        } catch (Exception e) {
            System.err.println("INVALID_GROUP");
            System.exit(1);
            return;
        }

        Path candidateJar;
        try {
            candidateJar = mavenRepo.resolve(mavenRepoPath)
                    .resolve(CANDIDATE_ARTIFACT)
                    .resolve(CANDIDATE_VERSION)
                    .resolve(CANDIDATE_ARTIFACT + "-" + CANDIDATE_VERSION + "-" + CANDIDATE_CLASSIFIER + ".jar");
        } catch (InvalidPathException | NullPointerException e) {
            System.err.println("INVALID_CANDIDATE_PATH");
            System.exit(1);
            return;
        }

        // Check candidate JAR exists as regular file with NOFOLLOW
        if (!isRegularFile(candidateJar)) {
            System.err.println("CANDIDATE_JAR_NOT_FOUND");
            System.exit(1);
        }

        // Extract SPI class bytes from Candidate JAR with bounded read
        byte[] spiClassBytes;
        try {
            spiClassBytes = extractFromJarBounded(candidateJar, SPI_CLASS_ENTRY_PATH, MAX_SPI_CLASS_BYTES);
        } catch (Exception e) {
            System.err.println("JAR_EXTRACT_ERROR");
            System.exit(1);
            return;
        }

        if (spiClassBytes == null) {
            System.err.println("SPI_CLASS_NOT_IN_CANDIDATE_JAR");
            System.exit(1);
        }

        // Compute rawFingerprint
        String rawFingerprint = sha256Fingerprint(spiClassBytes);

        // Build canonical entries list (sorted by coordinate)
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("coordinate", CANDIDATE_COORDINATE);
        entry.put("scope", "provided");
        entry.put("entryPath", SPI_CLASS_ENTRY_PATH);

        Map<String, Object> rawFp = new LinkedHashMap<>();
        rawFp.put("kind", "RAW_BYTES");
        rawFp.put("algorithm", "SHA-256");
        rawFp.put("value", "sha256:" + rawFingerprint);
        entry.put("rawFingerprint", rawFp);

        entries.add(entry);

        // Build material for aggregate fingerprint: {schemaVersion, entries} WITHOUT manifestFingerprint
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("entries", entries);

        // Compute aggregate fingerprint: sha256(domain UTF8 + NUL byte + canonical(material) UTF8)
        String canonicalMaterial = canonicalJson(material);
        byte[] domainBytes;
        byte[] materialBytes;
        byte[] aggregateInput;
        try {
            domainBytes = DOMAIN.getBytes(StandardCharsets.UTF_8);
            materialBytes = canonicalMaterial.getBytes(StandardCharsets.UTF_8);
            aggregateInput = new byte[domainBytes.length + 1 + materialBytes.length];
            System.arraycopy(domainBytes, 0, aggregateInput, 0, domainBytes.length);
            aggregateInput[domainBytes.length] = 0; // NUL byte
            System.arraycopy(materialBytes, 0, aggregateInput, domainBytes.length + 1, materialBytes.length);
        } catch (Exception e) {
            System.err.println("FINGERPRINT_ERROR");
            System.exit(1);
            return;
        }
        String manifestFingerprint = sha256Hex(aggregateInput);

        // Build manifestFingerprint wrapper
        Map<String, Object> manifestFp = new LinkedHashMap<>();
        manifestFp.put("kind", "AGGREGATE_COMMITMENT");
        manifestFp.put("algorithm", "SHA-256");
        manifestFp.put("value", "sha256:" + manifestFingerprint);

        // Build root manifest with manifestFingerprint for JSON output
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("manifestFingerprint", manifestFp);
        manifest.put("entries", entries);

        String json = canonicalJson(manifest);

        // Write output using NOFOLLOW channel, then verify stable attributes
        Path out = outputDir.resolve("dependencies.json");
        boolean written = writeAndVerify(out, json);
        if (!written) {
            System.err.println("WRITE_ERROR");
            System.exit(1);
            return;
        }

        System.out.println("PASS");
    }

    /**
     * Ensures directory exists and is a regular directory (not symlink).
     * Rejects existing symlinks. Creates directories if needed (with NOFOLLOW safety).
     */
    private static boolean ensureRegularDirectory(Path dir) {
        try {
            // Check if exists with NOFOLLOW
            BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isDirectory() && !attrs.isSymbolicLink()) {
                return true; // exists and is regular directory
            }
            return false; // exists but is not a regular directory
        } catch (NoSuchFileException | FileSystemNotFoundException e) {
            // Does not exist - create with NOFOLLOW safety
            try {
                Files.createDirectories(dir);
                // Immediately verify it's a regular directory
                return isRegularDirectory(dir);
            } catch (IOException | SecurityException | UnsupportedOperationException ex) {
                return false;
            }
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * Write using NOFOLLOW channel, then verify stable attributes to avoid TOCTOU.
     */
    private static boolean writeAndVerify(Path path, String content) {
        FileChannel ch = null;
        try {
            ch = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            ByteBuffer bb = StandardCharsets.UTF_8.encode(content);
            while (bb.hasRemaining()) {
                long written = ch.write(bb);
                if (written < 0) return false;
            }
            ch.force(true);
            ch.close();
            ch = null;

            // Post-write verify: must still be regular file with NOFOLLOW
            if (!isRegularFile(path)) {
                return false;
            }
            // Verify size matches expectation
            long size = Files.size(path);
            if (size != content.getBytes(StandardCharsets.UTF_8).length) {
                return false;
            }
            return true;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return false;
        } finally {
            if (ch != null) {
                try { ch.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Checks if path is a regular directory (not symlink) with NOFOLLOW.
     */
    private static boolean isRegularDirectory(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attrs.isDirectory() && !attrs.isSymbolicLink();
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * Checks if path is a regular file (not symlink) with NOFOLLOW.
     */
    private static boolean isRegularFile(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attrs.isRegularFile() && !attrs.isSymbolicLink();
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * Extracts one entry from a JAR by name, returning null on any failure.
     * Uses bounded read with NOFOLLOW links.
     */
    private static byte[] extractFromJarBounded(Path jarPath, String entryName, int maxBytes) {
        try ( var fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path entry = fs.getPath("/" + entryName);
            // Check exists with NOFOLLOW
            if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            // Bounded streaming read
            try (InputStream in = Files.newInputStream(entry)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
                byte[] buf = new byte[4096];
                int read;
                int total = 0;
                while ((read = in.read(buf)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        return null; // exceeds limit
                    }
                    baos.write(buf, 0, read);
                }
                return baos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── Canonical JSON ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String canonicalJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        if (v instanceof String) return canonicalString((String) v);
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) v;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(canonicalJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (v instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) v;
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) keys.add(String.valueOf(k));
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(",");
                String key = keys.get(i);
                sb.append(canonicalString(key)).append(":");
                sb.append(canonicalJson(map.get(key)));
            }
            sb.append("}");
            return sb.toString();
        }
        return canonicalString(String.valueOf(v));
    }

    private static String canonicalString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    // ── Fingerprint helpers ─────────────────────────────────────────────

    private static String sha256Fingerprint(byte[] data) {
        return sha256Hex(data);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError("SHA-256 not available");
        }
    }
}
