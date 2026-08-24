package com.leanowtech.bloge.gatetckprovider;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JDK-only build-time TCK Provider JAR structure verifier.
 *
 * <p>Strict exact allowlist – every entry is checked:
 * <ol>
 *   <li>Required exact paths (5): MANIFEST.MF, service descriptor, GateATckProvider.class,
 *       pom.properties, dependencies.json</li>
 *   <li>Forbidden: any schema/ entry, any extra META-INF/services/, any nested JAR,
 *       any class other than GateATckProvider.class</li>
 * </ol>
 *
 * <p>Limits enforced: maxRawBytes=16MiB, maxZipEntries=512, maxSingleEntryBytes=8MiB,
 * maxTotalUncompressedBytes=64MiB, maxCompressionRatio=100.
 *
 * <p>dependencies.json validated against capability-studio-gate-a-dependency-lock-manifest-v1.schema.json:
 * schemaVersion, manifestFingerprint (AGGREGATE_COMMITMENT domain RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1),
 * exactly 1 entry with scope=provided, SPI class entryPath, valid RAW_BYTES fingerprint.
 *
 * <p>No runtime deps. Stable error codes only – no error leaks path/value/count/ratio.
 */
public final class TckProviderArchiveVerifier {

    // ── Limits from TCK_PROVIDER role contract ────────────────────────
    private static final long MAX_RAW_BYTES          = 16 * 1024 * 1024L;
    private static final int  MAX_ZIP_ENTRIES        = 512;
    private static final long MAX_SINGLE_ENTRY        =  8 * 1024 * 1024L;
    private static final long MAX_TOTAL_UNCOMPRESSED = 64 * 1024 * 1024L;
    private static final double MAX_COMPRESSION_RATIO = 100.0;

    // ── Required entries (exact 5) ───────────────────────────────────
    private static final String MANIFEST_PATH             = "META-INF/MANIFEST.MF";
    private static final String SERVICE_DESCRIPTOR_PATH    =
            "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider";
    private static final String GATE_TCK_CLASS_PATH        =
            "com/leanowtech/bloge/gatetckprovider/GateATckProvider.class";
    private static final String POM_PROPERTIES_PATH        =
            "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties";
    private static final String DEPENDENCIES_JSON_PATH    =
            "META-INF/gate-a/manifests/dependencies.json";

    private static final Set<String> REQUIRED_ENTRY_PATHS = Set.of(
            MANIFEST_PATH, SERVICE_DESCRIPTOR_PATH, GATE_TCK_CLASS_PATH,
            POM_PROPERTIES_PATH, DEPENDENCIES_JSON_PATH
    );

    // ── Schema constants ───────────────────────────────────────────────
    private static final String SCHEMA_VERSION_DEP   = "capability-studio.gate-a-dependency-lock-manifest.v1";
    private static final String DOMAIN_DEP_MANIFEST   = "RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1";

    public static void main(String[] args) {
        if (args.length != 2) {
            fail("USAGE");
        }

        Path jarPath;
        Path workingDir;
        try {
            jarPath = Path.of(args[0]);
            workingDir = Path.of(args[1]);
        } catch (InvalidPathException | NullPointerException e) {
            fail("INVALID_INPUT");
            return;
        }

        // Validate JAR is a regular file with NOFOLLOW
        if (!isRegularFile(jarPath)) {
            fail("INVALID_JAR");
            return;
        }

        // Validate workingDir is a regular directory with NOFOLLOW
        if (!isRegularDirectory(workingDir)) {
            fail("INVALID_WORKING_DIR");
            return;
        }

        List<String> errors = verify(jarPath, workingDir);
        if (!errors.isEmpty()) {
            for (String e : errors) System.err.println(e);
            System.exit(1);
        }
        System.out.println("PASS");
        System.exit(0);
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

    static List<String> verify(Path jarPath, Path workingDir) {
        List<String> errors = new ArrayList<>();

        errors.addAll(phase1Structure(jarPath));

        Map<String, byte[]> entries;
        try {
            entries = readAllEntriesBounded(jarPath, MAX_TOTAL_UNCOMPRESSED);
        } catch (IOException e) {
            errors.add("ENTRY_READ_ERROR");
            return errors;
        }

        errors.addAll(phase2ExactFileAllowlist(entries));
        errors.addAll(phase2ClassAllowlist(entries));
        errors.addAll(phase3ServiceDescriptor(entries));
        errors.addAll(phase4Manifest(entries));
        errors.addAll(phase5DependenciesJson(entries));

        return errors;
    }

    // ── Phase 1: JAR structure ─────────────────────────────────────

    private static List<String> phase1Structure(Path jarPath) {
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Check raw JAR size using Files.size (actual bytes on disk)
        try {
            long rawSize = Files.size(jarPath);
            if (rawSize > MAX_RAW_BYTES) {
                errors.add("JAR_TOO_LARGE");
            }
            // Negative size means filesystem error
            if (rawSize < 0) {
                errors.add("ZIP_READ_ERROR");
                return errors;
            }
        } catch (IOException | SecurityException e) {
            errors.add("ZIP_READ_ERROR");
            return errors;
        }

        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            long totalUncompressed = 0;
            int count = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;

                count++;
                if (count > MAX_ZIP_ENTRIES) {
                    errors.add("TOO_MANY_ENTRIES");
                    break;
                }

                String name = e.getName();
                if (!seen.add(name)) {
                    errors.add("DUPLICATE_ENTRY");
                }

                long csize = e.getCompressedSize();
                long usize = e.getSize();

                // Fail closed on invalid metadata: size=-1 or compressedSize=-1
                if (usize < 0 || csize < 0) {
                    errors.add("ZIP_METADATA_INVALID");
                    continue;
                }

                if (usize > MAX_SINGLE_ENTRY) {
                    errors.add("ENTRY_TOO_LARGE");
                }
                totalUncompressed += usize;
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED) {
                    errors.add("TOTAL_UNC_EXCEEDED");
                }

                if (csize > 0 && usize > 0) {
                    double ratio = (double) usize / csize;
                    if (ratio > MAX_COMPRESSION_RATIO) {
                        errors.add("COMPRESSION_RATIO_EXCEEDED");
                    }
                }

                // Nested JAR detection
                if (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")) {
                    errors.add("FORBIDDEN_NESTED_JAR");
                }
                // Forbidden prefixes
                if (name.startsWith("schemas/") || name.startsWith("META-INF/gate-a/schemas/")) {
                    errors.add("FORBIDDEN_SCHEMAS");
                }
                // Extra services
                if (name.startsWith("META-INF/services/") && !name.equals(SERVICE_DESCRIPTOR_PATH)) {
                    errors.add("FORBIDDEN_EXTRA_SERVICE");
                }
            }

        } catch (IOException e) {
            errors.add("ZIP_READ_ERROR");
        }
        return errors;
    }

    // ── Phase 2: Exact allowlist ─────────────────────────────────

    private static List<String> phase2ExactFileAllowlist(Map<String, byte[]> entries) {
        List<String> errors = new ArrayList<>();

        for (String required : REQUIRED_ENTRY_PATHS) {
            if (!entries.containsKey(required)) {
                errors.add("MISSING_REQUIRED");
            }
        }

        for (String name : entries.keySet()) {
            if (!REQUIRED_ENTRY_PATHS.contains(name)) {
                errors.add("FORBIDDEN_EXTRA_FILE");
                break;
            }
        }

        return errors;
    }

    private static List<String> phase2ClassAllowlist(Map<String, byte[]> entries) {
        List<String> errors = new ArrayList<>();
        boolean foundClass = false;

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String path = e.getKey();
            if (!path.endsWith(".class")) continue;

            if (!path.equals(GATE_TCK_CLASS_PATH)) {
                errors.add("FORBIDDEN_CLASS");
                continue;
            }

            foundClass = true;
            byte[] classBytes = e.getValue();

            // 4 KiB sanity check
            if (classBytes.length < 4) {
                errors.add("CLASS_TRUNCATED");
                continue;
            }

            // Magic: 0xCAFEBABE (big-endian u4)
            int magic =
                    ((classBytes[0] & 0xff) << 24) |
                    ((classBytes[1] & 0xff) << 16) |
                    ((classBytes[2] & 0xff) <<  8) |
                    ((classBytes[3] & 0xff)      );
            if (magic != 0xCAFEBABE) {
                errors.add("INVALID_CLASS_MAGIC");
            }
        }

        if (!foundClass) {
            errors.add("MISSING_REQUIRED_CLASS");
        }

        return errors;
    }

    // ── Phase 3: Service descriptor ────────────────────────────────

    private static List<String> phase3ServiceDescriptor(Map<String, byte[]> entries) {
        List<String> errors = new ArrayList<>();

        byte[] svcBytes = entries.get(SERVICE_DESCRIPTOR_PATH);
        if (svcBytes == null) {
            errors.add("MISSING_SERVICE_DESCRIPTOR");
            return errors;
        }

        String content = new String(svcBytes, StandardCharsets.UTF_8).trim();
        String expected = "com.leanowtech.bloge.gatetckprovider.GateATckProvider";
        if (!expected.equals(content)) {
            errors.add("SERVICE_DESCRIPTOR_MISMATCH");
        }

        return errors;
    }

    // ── Phase 4: MANIFEST.MF ──────────────────────────────────────

    private static List<String> phase4Manifest(Map<String, byte[]> entries) {
        List<String> errors = new ArrayList<>();

        byte[] mfBytes = entries.get(MANIFEST_PATH);
        if (mfBytes == null) {
            errors.add("MISSING_MANIFEST");
            return errors;
        }

        try {
            Manifest mf = new Manifest(new ByteArrayInputStream(mfBytes));
            if (mf.getMainAttributes().isEmpty()) {
                errors.add("MANIFEST_EMPTY");
            }
        } catch (Exception e) {
            errors.add("MANIFEST_PARSE_ERROR");
        }

        return errors;
    }

    // ── Phase 5: dependencies.json ─────────────────────────────────

    private static List<String> phase5DependenciesJson(Map<String, byte[]> entries) {
        List<String> errors = new ArrayList<>();

        byte[] jsonBytes = entries.get(DEPENDENCIES_JSON_PATH);
        if (jsonBytes == null) {
            errors.add("MISSING_DEPENDENCIES_JSON");
            return errors;
        }

        Object root;
        try {
            root = parseJson(new String(jsonBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            errors.add("DEPENDENCIES_JSON_PARSE_ERROR");
            return errors;
        }

        if (!(root instanceof Map)) {
            errors.add("DEPENDENCIES_JSON_NOT_OBJECT");
            return errors;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rootMap = (Map<String, Object>) root;

        // schemaVersion check
        Object schemaVersion = rootMap.get("schemaVersion");
        if (!SCHEMA_VERSION_DEP.equals(schemaVersion)) {
            errors.add("SCHEMA_VERSION_INVALID");
        }

        // manifestFingerprint validation
        Object fpObj = rootMap.get("manifestFingerprint");
        if (!matchesAggregateFp(fpObj, rootMap)) {
            errors.add("AGGREGATE_FP_INVALID");
        }

        // entries array
        Object entriesObj = rootMap.get("entries");
        if (!(entriesObj instanceof List)) {
            errors.add("ENTRIES_NOT_ARRAY");
            return errors;
        }

        @SuppressWarnings("unchecked")
        List<?> entryList = (List<?>) entriesObj;

        if (entryList.size() != 1) {
            errors.add("ENTRY_COUNT_INVALID");
            return errors;
        }

        Object entryObj = entryList.get(0);
        if (!(entryObj instanceof Map)) {
            errors.add("ENTRY_NOT_OBJECT");
            return errors;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) entryObj;

        String expectedCoord = "com.leanowtech.bloge:bloge-resource-gateway-test-kit:1.0.0:gate-a-candidate";
        if (!expectedCoord.equals(entry.get("coordinate"))) {
            errors.add("COORDINATE_INVALID");
        }

        if (!"provided".equals(entry.get("scope"))) {
            errors.add("SCOPE_INVALID");
        }

        String expectedPath = "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class";
        if (!expectedPath.equals(entry.get("entryPath"))) {
            errors.add("ENTRY_PATH_INVALID");
        }

        if (!validateRawFp(entry.get("rawFingerprint"))) {
            errors.add("RAW_FP_INVALID");
        }

        return errors;
    }

    // ── Aggregate fingerprint: sha256(domain + NUL + canonical({schemaVersion, entries})) ──

    private static boolean matchesAggregateFp(Object fpObj, Map<String, Object> rootMap) {
        if (!(fpObj instanceof Map)) return false;

        @SuppressWarnings("unchecked")
        Map<String, Object> fpMap = (Map<String, Object>) fpObj;

        if (!"AGGREGATE_COMMITMENT".equals(fpMap.get("kind"))) return false;
        if (!"SHA-256".equals(fpMap.get("algorithm"))) return false;

        // Build material: {schemaVersion, entries} WITHOUT manifestFingerprint
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", rootMap.get("schemaVersion"));
        material.put("entries", rootMap.get("entries"));

        String canonicalMaterial = canonicalValue(material);
        byte[] domainBytes;
        byte[] materialBytes;
        byte[] aggregateInput;
        try {
            domainBytes = DOMAIN_DEP_MANIFEST.getBytes(StandardCharsets.UTF_8);
            materialBytes = canonicalMaterial.getBytes(StandardCharsets.UTF_8);
            aggregateInput = new byte[domainBytes.length + 1 + materialBytes.length];
            System.arraycopy(domainBytes, 0, aggregateInput, 0, domainBytes.length);
            aggregateInput[domainBytes.length] = 0; // NUL byte
            System.arraycopy(materialBytes, 0, aggregateInput, domainBytes.length + 1, materialBytes.length);
        } catch (Exception e) {
            return false;
        }
        String computed = sha256Hex(aggregateInput);

        return ("sha256:" + computed).equals(fpMap.get("value"));
    }

    private static boolean validateRawFp(Object fpObj) {
        if (!(fpObj instanceof Map)) return false;
        @SuppressWarnings("unchecked")
        Map<String, Object> fpMap = (Map<String, Object>) fpObj;
        if (!"RAW_BYTES".equals(fpMap.get("kind"))) return false;
        if (!"SHA-256".equals(fpMap.get("algorithm"))) return false;
        Object val = fpMap.get("value");
        if (!(val instanceof String)) return false;
        return ((String) val).matches("^sha256:[0-9a-f]{64}$");
    }

    // ── JSON parser (subset) ──────────────────────────────────────

    private static Object parseJson(String s) {
        int[] pos = {0};
        skipWhitespace(s, pos);
        Object v = parseValue(s, pos);
        skipWhitespace(s, pos);
        if (pos[0] != s.length()) throw new IllegalArgumentException("trailing");
        return v;
    }

    private static void skipWhitespace(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) pos[0]++;
    }

    private static Object parseValue(String s, int[] pos) {
        skipWhitespace(s, pos);
        if (pos[0] >= s.length()) throw new IllegalArgumentException("unexpected EOF");
        char c = s.charAt(pos[0]);
        if (c == '{') return parseObject(s, pos);
        if (c == '[') return parseArray(s, pos);
        if (c == '"') return parseString(s, pos);
        if (c == 't' || c == 'f') return parseBoolean(s, pos);
        if (c == 'n') return parseNull(s, pos);
        if (c == '-' || (c >= '0' && c <= '9')) return parseNumber(s, pos);
        throw new IllegalArgumentException("invalid char");
    }

    private static Map<String, Object> parseObject(String s, int[] pos) {
        Map<String, Object> m = new LinkedHashMap<>();
        pos[0]++; // skip '{'
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return m;
        }
        for (;;) {
            skipWhitespace(s, pos);
            String key = parseString(s, pos);
            skipWhitespace(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ':') throw new IllegalArgumentException("expected colon");
            pos[0]++;
            Object val = parseValue(s, pos);
            m.put(key, val);
            skipWhitespace(s, pos);
            if (s.charAt(pos[0]) == '}') { pos[0]++; break; }
            if (s.charAt(pos[0]) != ',') throw new IllegalArgumentException("expected comma");
            pos[0]++;
        }
        return m;
    }

    private static List<Object> parseArray(String s, int[] pos) {
        List<Object> l = new ArrayList<>();
        pos[0]++; // skip '['
        skipWhitespace(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == ']') {
            pos[0]++;
            return l;
        }
        for (;;) {
            l.add(parseValue(s, pos));
            skipWhitespace(s, pos);
            if (s.charAt(pos[0]) == ']') { pos[0]++; break; }
            if (s.charAt(pos[0]) != ',') throw new IllegalArgumentException("expected comma");
            pos[0]++;
        }
        return l;
    }

    private static String parseString(String s, int[] pos) {
        if (pos[0] >= s.length() || s.charAt(pos[0]) != '"') throw new IllegalArgumentException("expected quote");
        pos[0]++;
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]++);
            if (c == '"') break;
            if (c == '\\') {
                if (pos[0] >= s.length()) throw new IllegalArgumentException("escape at EOF");
                c = s.charAt(pos[0]++);
                switch (c) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (pos[0] + 4 > s.length()) throw new IllegalArgumentException("truncated unicode");
                        int code = Integer.parseInt(s.substring(pos[0], pos[0] + 4), 16);
                        sb.append((char) code);
                        pos[0] += 4;
                        break;
                    default: throw new IllegalArgumentException("unknown escape");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Object parseNumber(String s, int[] pos) {
        int start = pos[0];
        if (s.charAt(pos[0]) == '-') pos[0]++;
        while (pos[0] < s.length() && s.charAt(pos[0]) >= '0' && s.charAt(pos[0]) <= '9') pos[0]++;
        if (pos[0] < s.length() && s.charAt(pos[0]) == '.') {
            pos[0]++;
            while (pos[0] < s.length() && s.charAt(pos[0]) >= '0' && s.charAt(pos[0]) <= '9') pos[0]++;
        }
        if (pos[0] < s.length() && (s.charAt(pos[0]) == 'e' || s.charAt(pos[0]) == 'E')) {
            pos[0]++;
            if (pos[0] < s.length() && (s.charAt(pos[0]) == '+' || s.charAt(pos[0]) == '-')) pos[0]++;
            while (pos[0] < s.length() && s.charAt(pos[0]) >= '0' && s.charAt(pos[0]) <= '9') pos[0]++;
        }
        String num = s.substring(start, pos[0]);
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        return Long.parseLong(num);
    }

    private static Boolean parseBoolean(String s, int[] pos) {
        if (s.startsWith("true", pos[0])) { pos[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", pos[0])) { pos[0] += 5; return Boolean.FALSE; }
        throw new IllegalArgumentException("invalid boolean");
    }

    private static Object parseNull(String s, int[] pos) {
        if (s.startsWith("null", pos[0])) { pos[0] += 4; return null; }
        throw new IllegalArgumentException("invalid null");
    }

    // ── Canonical JSON for aggregate fingerprint material ───────────

    @SuppressWarnings("unchecked")
    private static String canonicalValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        if (v instanceof String) return canonicalString((String) v);
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(canonicalValue(l.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            List<String> ks = new ArrayList<>();
            for (Object k : m.keySet()) ks.add(String.valueOf(k));
            Collections.sort(ks);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < ks.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(canonicalString(ks.get(i))).append(":");
                sb.append(canonicalValue(m.get(ks.get(i))));
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

    // ── Utility ───────────────────────────────────────────────────

    /**
     * Reads all entries with per-entry and total bounded streaming reads.
     * Fails closed on any exceeded limit during read, never post-hoc.
     */
    private static Map<String, byte[]> readAllEntriesBounded(Path jarPath, long maxTotal) throws IOException {
        Map<String, byte[]> r = new LinkedHashMap<>();
        long totalRead = 0;
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;

                long entrySize = e.getSize();
                // Fail closed: unknown entry size treated as max allowed
                if (entrySize < 0) entrySize = MAX_SINGLE_ENTRY;
                if (entrySize > MAX_SINGLE_ENTRY) {
                    throw new IOException("entry exceeds single limit");
                }

                // Remaining budget
                long remaining = maxTotal - totalRead;
                if (remaining <= 0) {
                    throw new IOException("total exceeds limit");
                }
                // Cap per-read to remaining budget
                long cap = Math.min(entrySize, remaining);

                byte[] bytes = readEntryBounded(zf, e, cap);
                totalRead += bytes.length;
                r.put(e.getName(), bytes);
            }
        }
        return r;
    }

    /**
     * Streaming read of single entry with hard cap.
     */
    private static byte[] readEntryBounded(ZipFile zf, ZipEntry entry, long maxBytes) throws IOException {
        try (InputStream in = zf.getInputStream(entry)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(maxBytes, 8192));
            byte[] buf = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("entry exceeds limit during read");
                }
                baos.write(buf, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError();
        }
    }

    private static void fail(String msg) {
        System.err.println(msg);
        System.exit(1);
    }
}
