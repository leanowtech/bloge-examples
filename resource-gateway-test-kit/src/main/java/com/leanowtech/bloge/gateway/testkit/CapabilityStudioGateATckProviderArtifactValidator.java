package com.leanowtech.bloge.gateway.testkit;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * JDK-only, package-private validator for TCK_PROVIDER thin JAR structural integrity
 * and its cross-relationship to the IMPLEMENTATION_CANDIDATE JAR (ABI surface).
 *
 * <p>Scanning is performed via {@link ZipFile} using the provided path arguments,
 * which gives access to the central directory for accurate size/compressedSize/ratio
 * values. The caller-supplied {@code raw} bytes are verified against the path
 * file contents before scanning (stable NOFOLLOW check).
 *
 * <p>All error codes are fixed strings — no runtime values are embedded.</p>
 *
 * <p>Package-private final.</p>
 */
final class CapabilityStudioGateATckProviderArtifactValidator {

    // ── Error codes (all fixed) ───────────────────────────────────────
    // Provider structural
    static final String E_PROVIDER_PATH_STABLE_MISMATCH = "PROVIDER_PATH_STABLE_MISMATCH";
    static final String E_PROVIDER_PATH_NOT_REGULAR     = "PROVIDER_PATH_NOT_REGULAR";
    static final String E_PROVIDER_PATH_SYMLINK         = "PROVIDER_PATH_SYMLINK";
    static final String E_PROVIDER_ZIP_ERROR            = "PROVIDER_ZIP_ERROR";
    static final String E_PROVIDER_ENTRY_PATH_UNNORMALIZED = "PROVIDER_ENTRY_PATH_UNNORMALIZED";
    static final String E_PROVIDER_ENTRY_DUPLICATE     = "PROVIDER_ENTRY_DUPLICATE";
    static final String E_PROVIDER_ENTRY_SIZE_OVERFLOW   = "PROVIDER_ENTRY_SIZE_OVERFLOW";
    static final String E_PROVIDER_ENTRY_RATIO_EXCEEDED = "PROVIDER_ENTRY_RATIO_EXCEEDED";
    // Resource-boundary errors
    static final String E_PROVIDER_RAW_SIZE_EXCEEDED       = "PROVIDER_RAW_SIZE_EXCEEDED";
    static final String E_PROVIDER_ZIP_ENTRY_COUNT_EXCEEDED = "PROVIDER_ZIP_ENTRY_COUNT_EXCEEDED";

    static final String E_PROVIDER_TOTAL_UNCOMPRESSED_EXCEEDED = "PROVIDER_TOTAL_UNCOMPRESSED_EXCEEDED";
    static final String E_PROVIDER_ENTRY_COUNT_MISMATCH = "PROVIDER_ENTRY_COUNT_MISMATCH";
    static final String E_PROVIDER_ENTRY_MISSING       = "PROVIDER_ENTRY_MISSING";
    static final String E_PROVIDER_ENTRY_EXTRA         = "PROVIDER_ENTRY_EXTRA";
    static final String E_PROVIDER_MANIFEST_MISSING    = "PROVIDER_MANIFEST_MISSING";
    static final String E_PROVIDER_MANIFEST_PARSE_ERROR = "PROVIDER_MANIFEST_PARSE_ERROR";
    static final String E_PROVIDER_MANIFEST_MULTI_RELEASE = "PROVIDER_MANIFEST_MULTI_RELEASE";
    static final String E_PROVIDER_CLASS_FORBIDDEN     = "PROVIDER_CLASS_FORBIDDEN";
    static final String E_PROVIDER_SCHEMA_FORBIDDEN   = "PROVIDER_SCHEMA_FORBIDDEN";
    static final String E_PROVIDER_NESTED_JAR_FORBIDDEN = "PROVIDER_NESTED_JAR_FORBIDDEN";
    static final String E_PROVIDER_CANDIDATE_CLASS_FORBIDDEN = "PROVIDER_CANDIDATE_CLASS_FORBIDDEN";
    static final String E_PROVIDER_BUILD_TOOL_FORBIDDEN = "PROVIDER_BUILD_TOOL_FORBIDDEN";
    static final String E_PROVIDER_SPI_DESCRIPTOR_MISSING = "PROVIDER_SPI_DESCRIPTOR_MISSING";
    static final String E_PROVIDER_SPI_DESCRIPTOR_ENCODING = "PROVIDER_SPI_DESCRIPTOR_ENCODING";
    static final String E_PROVIDER_SPI_DESCRIPTOR_BOM    = "PROVIDER_SPI_DESCRIPTOR_BOM";
    static final String E_PROVIDER_SPI_DESCRIPTOR_NO_LF  = "PROVIDER_SPI_DESCRIPTOR_NO_LF";
    static final String E_PROVIDER_SPI_DESCRIPTOR_MULTI  = "PROVIDER_SPI_DESCRIPTOR_MULTI";
    static final String E_PROVIDER_SPI_DESCRIPTOR_CRLF   = "PROVIDER_SPI_DESCRIPTOR_CRLF";
    static final String E_PROVIDER_SPI_DESCRIPTOR_TRAILING_LF = "PROVIDER_SPI_DESCRIPTOR_TRAILING_LF";
    static final String E_PROVIDER_SPI_DESCRIPTOR_EMPTY   = "PROVIDER_SPI_DESCRIPTOR_EMPTY";
    static final String E_PROVIDER_SPI_DESCRIPTOR_WS     = "PROVIDER_SPI_DESCRIPTOR_WS";
    static final String E_PROVIDER_SPI_DESCRIPTOR_COMMENT = "PROVIDER_SPI_DESCRIPTOR_COMMENT";
    static final String E_PROVIDER_SPI_DESCRIPTOR_CLASS  = "PROVIDER_SPI_DESCRIPTOR_CLASS";
    static final String E_CANDIDATE_RAW_SIZE_EXCEEDED       = "CANDIDATE_RAW_SIZE_EXCEEDED";
    static final String E_CANDIDATE_ZIP_ENTRY_COUNT_EXCEEDED = "CANDIDATE_ZIP_ENTRY_COUNT_EXCEEDED";

    static final String E_CANDIDATE_TOTAL_UNCOMPRESSED_EXCEEDED = "CANDIDATE_TOTAL_UNCOMPRESSED_EXCEEDED";
    
    static final String E_PROVIDER_DEP_MANIFEST_MISSING = "PROVIDER_DEP_MANIFEST_MISSING";
    static final String E_PROVIDER_DEP_JSON_PARSE_ERROR  = "PROVIDER_DEP_JSON_PARSE_ERROR";
    static final String E_PROVIDER_DEP_TOP_KEYS         = "PROVIDER_DEP_TOP_KEYS";
    static final String E_PROVIDER_DEP_SCHEMA_VERSION    = "PROVIDER_DEP_SCHEMA_VERSION";
    static final String E_PROVIDER_DEP_ENTRIES_TYPE     = "PROVIDER_DEP_ENTRIES_TYPE";
    static final String E_PROVIDER_DEP_ENTRIES_SIZE      = "PROVIDER_DEP_ENTRIES_SIZE";
    static final String E_PROVIDER_DEP_MISSING_FIELD    = "PROVIDER_DEP_MISSING_FIELD";
    static final String E_PROVIDER_DEP_ENTRY_SCOPE       = "PROVIDER_DEP_ENTRY_SCOPE";
    static final String E_PROVIDER_DEP_ENTRY_PATH       = "PROVIDER_DEP_ENTRY_PATH";
    static final String E_PROVIDER_DEP_COORDINATE       = "PROVIDER_DEP_COORDINATE";
    static final String E_PROVIDER_DEP_RAW_FP_TYPE      = "PROVIDER_DEP_RAW_FP_TYPE";
    static final String E_PROVIDER_DEP_RAW_FP_VALUE_FMT = "PROVIDER_DEP_RAW_FP_VALUE_FMT";
    static final String E_PROVIDER_DEP_FP_TYPE          = "PROVIDER_DEP_FP_TYPE";
    static final String E_PROVIDER_DEP_FP_MISMATCH      = "PROVIDER_DEP_FP_MISMATCH";
    static final String E_PROVIDER_DEP_SPI_FP_MISMATCH  = "PROVIDER_DEP_SPI_FP_MISMATCH";

    // Candidate structural
    static final String E_CANDIDATE_PATH_STABLE_MISMATCH = "CANDIDATE_PATH_STABLE_MISMATCH";
    static final String E_CANDIDATE_PATH_NOT_REGULAR     = "CANDIDATE_PATH_NOT_REGULAR";
    static final String E_CANDIDATE_PATH_SYMLINK         = "CANDIDATE_PATH_SYMLINK";
    static final String E_CANDIDATE_ZIP_ERROR            = "CANDIDATE_ZIP_ERROR";
    static final String E_CANDIDATE_ENTRY_PATH_UNNORMALIZED = "CANDIDATE_ENTRY_PATH_UNNORMALIZED";
    static final String E_CANDIDATE_ENTRY_DUPLICATE     = "CANDIDATE_ENTRY_DUPLICATE";
    static final String E_CANDIDATE_ENTRY_SIZE_OVERFLOW   = "CANDIDATE_ENTRY_SIZE_OVERFLOW";
    static final String E_CANDIDATE_ENTRY_RATIO_EXCEEDED = "CANDIDATE_ENTRY_RATIO_EXCEEDED";
    static final String E_CANDIDATE_MISSING_CLI          = "CANDIDATE_MISSING_CLI";
    static final String E_CANDIDATE_MISSING_SPI          = "CANDIDATE_MISSING_SPI";
    static final String E_CANDIDATE_MISSING_PROJECTION   = "CANDIDATE_MISSING_PROJECTION";
    static final String E_CANDIDATE_SCHEMA_MISSING       = "CANDIDATE_SCHEMA_MISSING";
    static final String E_CANDIDATE_SCHEMA_EXTRA         = "CANDIDATE_SCHEMA_EXTRA";
    static final String E_CANDIDATE_NESTED_JAR           = "CANDIDATE_NESTED_JAR";

    // Input
    static final String E_PROVIDER_RAW_NULL   = "PROVIDER_RAW_NULL";
    static final String E_CANDIDATE_RAW_NULL = "CANDIDATE_RAW_NULL";
    static final String E_PROVIDER_PATH_NULL  = "PROVIDER_PATH_NULL";
    static final String E_CANDIDATE_PATH_NULL = "CANDIDATE_PATH_NULL";
    static final String E_CONTRACT_NULL       = "CONTRACT_NULL";

    // ── Constants ─────────────────────────────────────────────────────
    private static final String SPI_DESCRIPTOR =
            "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider";
    private static final String PROVIDER_SPI_CLASS =
            "com.leanowtech.bloge.gatetckprovider.GateATckProvider";
    private static final String DEP_MANIFEST_DOMAIN =
            "RG-CS-GATE-A-DEPENDENCY-LOCK-MANIFEST-v1";
    // Exact dependency values from authority IMPLEMENTATION_CANDIDATE roleContract
    private static final String DEP_COORDINATE_EXACT =
            "com.leanowtech.bloge:bloge-resource-gateway-test-kit:1.0.0:gate-a-candidate";
    private static final String DEP_ENTRY_PATH_EXACT =
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class";

    private static final String DEP_MANIFEST_SCHEMA_VERSION =
            "capability-studio.gate-a-dependency-lock-manifest.v1";

    private static final int   MAX_ENTRY_NAME   = 512;

    // Required Candidate outer-class paths (exact entry names)
    private static final Set<String> REQUIRED_CANDIDATE_OUTER = Set.of(
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRoleSelfTest.class",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator.class",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer.class",
            "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException.class",
            "com/leanowtech/bloge/gateway/testkit/StrictJsonParser.class"
    );

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Validates provider and candidate JAR files and their cross-relationship.
     *
     * @param providerRaw  raw bytes supplied by caller
     * @param providerPath path to provider JAR file (required)
     * @param candidateRaw raw bytes supplied by caller
     * @param candidatePath path to candidate JAR file (required)
     * @param contract    projected TCK_PROVIDER role contract
     * @param enforceProviderCodeSource not implemented; retained for signature stability
     * @return immutable snapshot; {@code errors} contains only fixed codes
     */
    public static ValidationSnapshot validate(
            byte[] providerRaw,
            Path providerPath,
            byte[] candidateRaw,
            Path candidatePath,
            CapabilityStudioGateATckProviderRoleSelfTest.TckRoleContract contract,
            boolean enforceProviderCodeSource) {

        List<String> errors = new ArrayList<>();

        // Input guards
        if (providerRaw == null)   { errors.add(E_PROVIDER_RAW_NULL); }
        if (candidateRaw == null)  { errors.add(E_CANDIDATE_RAW_NULL); }
        if (providerPath == null)   { errors.add(E_PROVIDER_PATH_NULL); }
        if (candidatePath == null)  { errors.add(E_CANDIDATE_PATH_NULL); }
        if (contract == null)      { errors.add(E_CONTRACT_NULL); }
        if (!errors.isEmpty()) {
            return new ValidationSnapshot(null, Collections.emptyMap(), null, null,
                    Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
        }

        // ── Stable byte check ─────────────────────────────────────
        PathWork pWork = openPath(providerPath, providerRaw, contract.maxRawBytes(), errors,
                E_PROVIDER_PATH_STABLE_MISMATCH,
                E_PROVIDER_PATH_NOT_REGULAR,
                E_PROVIDER_PATH_SYMLINK,
                E_PROVIDER_RAW_SIZE_EXCEEDED);
        PathWork cWork = openPath(candidatePath, candidateRaw, contract.maxRawBytes(), errors,
                E_CANDIDATE_PATH_STABLE_MISMATCH,
                E_CANDIDATE_PATH_NOT_REGULAR,
                E_CANDIDATE_PATH_SYMLINK,
                E_CANDIDATE_RAW_SIZE_EXCEEDED);

        if (!errors.isEmpty()) {
            return new ValidationSnapshot(
                    fingerprint(providerRaw), Collections.emptyMap(),
                    fingerprint(candidateRaw), null,
                    Collections.emptyMap(), Collections.emptyMap(),
                    new ArrayList<>(errors));
        }

        // ── Phase 1: provider ─────────────────────────────────────
        JarScan pScan = scanViaZipFile(pWork.path, "PROVIDER", ArchiveLimits.from(contract), errors);
        validateProvider(pScan, pWork.raw, errors);

        // ── Phase 2: candidate ─────────────────────────────────────
        JarScan cScan = scanViaZipFile(cWork.path, "CANDIDATE", ArchiveLimits.from(contract), errors);
        validateCandidate(cScan, cWork.raw, contract, errors);

        // SPI cross-check
        if (pScan.providedSpiFingerprint != null && cScan.spiBytes != null) {
            String actual = fingerprint(cScan.spiBytes);
            if (!pScan.providedSpiFingerprint.equals(actual)) {
                errors.add(E_PROVIDER_DEP_SPI_FP_MISMATCH);
            }
        }

        // Build entry fingerprints
        Map<String, String> pEntries = new LinkedHashMap<>();
        for (EntryInfo e : pScan.entries) {
            if (!e.isDir && e.data != null) pEntries.put(e.name, fingerprint(e.data));
        }
        Map<String, String> schemas = new LinkedHashMap<>();
        Map<String, Long> schemaLengths = new LinkedHashMap<>();
        String spiFp = null;
        if (cScan.spiBytes != null) {
            spiFp = fingerprint(cScan.spiBytes);
        }
        for (String sid : cScan.schemaIds) {
            byte[] sb = cScan.schemaData.get(sid);
            if (sb != null) {
                schemas.put(sid, fingerprint(sb));
                schemaLengths.put(sid, (long) sb.length);
            }
        }

        return new ValidationSnapshot(
                fingerprint(providerRaw),
                new LinkedHashMap<>(pEntries),
                fingerprint(candidateRaw),
                spiFp,
                new LinkedHashMap<>(schemas),
                new LinkedHashMap<>(schemaLengths),
                new ArrayList<>(errors));
    }

    // ── Path work ─────────────────────────────────────────────────────

    private static PathWork openPath(Path path, byte[] expectedRaw, long maxRawBytes,
            List<String> errors, String mismatchCode, String nonRegCode, String symlinkCode,
            String sizeExceedCode) {
        // Pre-read attrs (NOFOLLOW)
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            errors.add(mismatchCode);
            return new PathWork(path, null);
        }
        // Symlink check first — must be reachable independently of regular-file check
        if (Files.isSymbolicLink(path)) {
            errors.add(symlinkCode);
            return new PathWork(path, null);
        }
        if (!attrs.isRegularFile()) {
            errors.add(nonRegCode);
            return new PathWork(path, null);
        }

        // Size pre-check against contract limit (both byte[] length and attrs size)
        if (expectedRaw.length > maxRawBytes || attrs.size() > maxRawBytes) {
            errors.add(sizeExceedCode);
            return new PathWork(path, null);
        }

        // Bounded read via channel (NOFOLLOW)
        byte[] actual;
        try {
            actual = readAllBytesBounded(path, maxRawBytes + 1);
        } catch (IOException e) {
            errors.add(mismatchCode);
            return new PathWork(path, null);
        }

        // Constant-time compare
        if (!MessageDigest.isEqual(expectedRaw, actual)) {
            errors.add(mismatchCode);
        }

        // Post-read attrs (NOFOLLOW); verify identity still holds
        BasicFileAttributes postAttrs;
        try {
            postAttrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            errors.add(mismatchCode);
            return new PathWork(path, null);
        }
        if (Files.isSymbolicLink(path)) {
            errors.add(mismatchCode);
            return new PathWork(path, null);
        }
        if (!postAttrs.isRegularFile()) {
            errors.add(mismatchCode);
            return new PathWork(path, null);
        }
        // fileKey: nullness must match; if both nonnull, must be equal
        Object fk1 = attrs.fileKey();
        Object fk2 = postAttrs.fileKey();
        boolean fkNullMatch = (fk1 == null) == (fk2 == null);
        if (!fkNullMatch || (fk1 != null && !fk1.equals(fk2))) {
            errors.add(mismatchCode);
            return new PathWork(path, null);
        }
        // Size (primitive long) and lastModifiedTime (FileTime) must match
        if (attrs.size() != postAttrs.size()
                || !attrs.lastModifiedTime().equals(postAttrs.lastModifiedTime())) {
            errors.add(mismatchCode);
        }

        return new PathWork(path, actual);
    }

    /**
     * Bounded read: reads at most (limit) bytes; returns partial result if truncated.
     * Uses FileChannel with NOFOLLOW to prevent symlink attacks.
     */
    private static byte[] readAllBytesBounded(Path path, long limit) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long fileSize = ch.size();
            long toRead = Math.min(fileSize, limit);
            ByteBuffer buf = ByteBuffer.allocate((int) toRead);
            long total = 0;
            while (total < toRead) {
                int r = ch.read(buf);
                if (r < 0) break;
                total += r;
            }
            return Arrays.copyOf(buf.array(), (int) total);
        }
    }

// ── ZipFile scanning ───────────────────────────────────────────────

    /**
     * Scans via ZipFile (central directory gives accurate compressedSize/size).
     * Contract-enforced: maxZipEntries, maxEntryNameBytes=512, maxSingleEntryBytes,
     * maxTotalUncompressedBytes, maxCompressionRatio (as double).
     * Entry data read only when central metadata passes all limits; uses bounded stream.
     */
    static JarScan scanViaZipFile(Path path, String label, ArchiveLimits limits, List<String> errors) {
        JarScan scan = new JarScan();
        long maxZipEntries = limits.maxZipEntries;
        long maxSingle = limits.maxSingleEntryBytes;
        long maxTotal = limits.maxTotalUncompressedBytes;
        double maxRatio = limits.maxCompressionRatio;

        int entryCount = 0;
        long totalUncompressed = 0L;
        try (ZipFile zf = new ZipFile(path.toFile())) {
            Set<String> seen = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                String name = ze.getName();
                boolean isDir = ze.isDirectory();

                // Increment entry count immediately for every central entry
                entryCount++;

                // Name byte-length check
                if (name.getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_NAME) {
                    add(errors, label, E_PROVIDER_ENTRY_PATH_UNNORMALIZED,
                            E_CANDIDATE_ENTRY_PATH_UNNORMALIZED);
                    continue;
                }
                if (!isNormalized(name, isDir)) {
                    add(errors, label, E_PROVIDER_ENTRY_PATH_UNNORMALIZED,
                            E_CANDIDATE_ENTRY_PATH_UNNORMALIZED);
                    continue;
                }

                if (!seen.add(name)) {
                    add(errors, label, E_PROVIDER_ENTRY_DUPLICATE,
                            E_CANDIDATE_ENTRY_DUPLICATE);
                    continue;
                }

                // Check total entry count at most once
                if (entryCount > maxZipEntries) {
                    addOnce(errors, label.equals("PROVIDER")
                            ? E_PROVIDER_ZIP_ENTRY_COUNT_EXCEEDED
                            : E_CANDIDATE_ZIP_ENTRY_COUNT_EXCEEDED);
                }

                long compressedSize = ze.getCompressedSize();
                long uncompressedSize = ze.getSize();

                // invalidMetadata: negative non-dir sizes OR size > maxSingle OR ratio breach
                boolean invalidMetadata = false;
                if (!isDir && (compressedSize < 0 || uncompressedSize < 0)) {
                    addOnce(errors, label.equals("PROVIDER")
                                ? E_PROVIDER_ENTRY_SIZE_OVERFLOW : E_CANDIDATE_ENTRY_SIZE_OVERFLOW);
                    invalidMetadata = true;
                }
                if (compressedSize > maxSingle || uncompressedSize > maxSingle) {
                    addOnce(errors, label.equals("PROVIDER")
                                ? E_PROVIDER_ENTRY_SIZE_OVERFLOW : E_CANDIDATE_ENTRY_SIZE_OVERFLOW);
                    invalidMetadata = true;
                }
                // Ratio breach: compressed=0 with nonzero uncompressed, or double ratio exceeded
                boolean ratioBreach = false;
                if (compressedSize == 0 && uncompressedSize > 0) {
                    ratioBreach = true;
                } else if (compressedSize > 0 && uncompressedSize > 0) {
                    if (((double) uncompressedSize) / compressedSize > maxRatio) {
                        ratioBreach = true;
                    }
                }
                if (ratioBreach) {
                    addOnce(errors, label.equals("PROVIDER")
                                ? E_PROVIDER_ENTRY_RATIO_EXCEEDED : E_CANDIDATE_ENTRY_RATIO_EXCEEDED);
                    invalidMetadata = true;
                }

                // Accumulate total uncompressed (overflow-safe, addOnce guard)
                if (!isDir && uncompressedSize > 0 && !invalidMetadata) {
                    if (uncompressedSize > maxTotal - totalUncompressed) {
                        addOnce(errors, label.equals("PROVIDER")
                                ? E_PROVIDER_TOTAL_UNCOMPRESSED_EXCEEDED
                                : E_CANDIDATE_TOTAL_UNCOMPRESSED_EXCEEDED);
                        totalUncompressed = Long.MAX_VALUE;
                    } else {
                        totalUncompressed += uncompressedSize;
                    }
                }

                // Skip entry stream open if metadata invalid or total already exceeded
                byte[] data = null;
                if (!isDir && !invalidMetadata && entryCount <= maxZipEntries && totalUncompressed <= maxTotal) {
                    ReadResult rr;
                    try (InputStream in = zf.getInputStream(ze)) {
                        rr = readEntryBounded(in, maxSingle);
                    }
                    // readAll == false means exceeded limit or EOF not reached at boundary
                    if (!rr.readAll || rr.data.length > maxSingle) {
                        addOnce(errors, label.equals("PROVIDER")
                                    ? E_PROVIDER_ENTRY_SIZE_OVERFLOW : E_CANDIDATE_ENTRY_SIZE_OVERFLOW);
                    } else if (uncompressedSize >= 0 && rr.data.length != uncompressedSize) {
                        // actual bytes read must equal declared size when known
                        addOnce(errors, label.equals("PROVIDER")
                                    ? E_PROVIDER_ENTRY_SIZE_OVERFLOW : E_CANDIDATE_ENTRY_SIZE_OVERFLOW);
                    } else {
                        data = rr.data;
                    }
                }

                if (data != null) {
                    scan.dataByName.put(name, data);

                    // Class detection by exact path — record in foundRequired set
                    switch (name) {
                        case "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class":
                            scan.foundRequired.add(name);
                            break;
                        case "com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class":
                            scan.foundRequired.add(name);
                            scan.spiBytes = data;
                            break;
                        case "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderRoleSelfTest.class":
                        case "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateATckProviderArtifactValidator.class":
                        case "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAReceiptCanonicalizer.class":
                        case "com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAException.class":
                        case "com/leanowtech/bloge/gateway/testkit/StrictJsonParser.class":
                            scan.foundRequired.add(name);
                            break;
                    }

                    // Schema detection
                    if (name.startsWith("schemas/") && name.endsWith(".schema.json")) {
                        String sid = name.substring("schemas/".length());
                        scan.schemaIds.add(sid);
                        scan.schemaData.put(sid, data);
                    }
                }

                scan.entries.add(new EntryInfo(name, isDir, data, uncompressedSize));
            }
        } catch (IOException e) {
            add(errors, label, E_PROVIDER_ZIP_ERROR, E_CANDIDATE_ZIP_ERROR);
        }
        return scan;
    }
    // ── Provider validation ───────────────────────────────────────────


    private static void add(List<String> errors, String label,
            String provErr, String candErr) {
        errors.add(label.equals("PROVIDER") ? provErr : candErr);
    }

    /**
     * Adds error code only if not already present in the list (idempotent guard).
     */
    private static void addOnce(List<String> errors, String code) {
        if (!errors.contains(code)) errors.add(code);
    }

    /**
     * Result of bounded entry read: bytes read and whether EOF was reached.
     * readAll == false means entry exceeded the limit.
     */
    private static final class ReadResult {
        final byte[] data;
        final boolean readAll; // true = EOF reached within limit; false = exceeded or truncated
        ReadResult(byte[] data, boolean readAll) { this.data = data; this.readAll = readAll; }
    }

    /**
     * Reads from InputStream up to (limit+1) bytes.
     * Returns ReadResult with readAll=false if limit exceeded or EOF not reached at limit.
     * Returns ReadResult with readAll=true if EOF reached within limit.
     */
    private static ReadResult readEntryBounded(InputStream in, long limit) throws IOException {
        byte[] buf = new byte[(int) Math.min(limit + 1, Integer.MAX_VALUE)];
        int pos = 0;
        int n;
        while (pos < buf.length && (n = in.read(buf, pos, buf.length - pos)) >= 0) {
            pos += n;
        }
        boolean readAll;
        if (pos < buf.length) {
            // EOF reached before filling buffer
            readAll = true;
        } else {
            // Buffer filled — check if there is more data
            int peek = in.read();
            if (peek < 0) {
                readAll = true; // exactly at limit and EOF
            } else {
                readAll = false; // more data beyond limit
            }
        }
        return new ReadResult(Arrays.copyOf(buf, pos), readAll);
    }

    private static void validateProvider(JarScan scan, byte[] providerRaw, List<String> errors) {
        // Entry count
        int nonDir = 0;
        Set<String> nonDirPaths = new HashSet<>();
        for (EntryInfo e : scan.entries) {
            if (!e.isDir) { nonDir++; nonDirPaths.add(e.name); }
        }
        if (nonDir != 5) errors.add(E_PROVIDER_ENTRY_COUNT_MISMATCH);

        // Exact entry set
        Set<String> required = Set.of(
                "META-INF/MANIFEST.MF", SPI_DESCRIPTOR,
                "com/leanowtech/bloge/gatetckprovider/GateATckProvider.class",
                "META-INF/maven/com.leanowtech.bloge/resource-gateway-gate-a-tck-provider/pom.properties",
                "META-INF/gate-a/manifests/dependencies.json"
        );
        for (String r : required) {
            if (!nonDirPaths.contains(r)) { errors.add(E_PROVIDER_ENTRY_MISSING); break; }
        }
        for (String a : nonDirPaths) {
            if (!required.contains(a)) { errors.add(E_PROVIDER_ENTRY_EXTRA); break; }
        }

        // Manifest
        byte[] mfRaw = scan.dataOf("META-INF/MANIFEST.MF");
        if (mfRaw == null) {
            errors.add(E_PROVIDER_MANIFEST_MISSING);
        } else {
            try {
                Manifest mf = new Manifest(new ByteArrayInputStream(mfRaw));
                String mr = mf.getMainAttributes().getValue("Multi-Release");
                if ("true".equalsIgnoreCase(mr)) errors.add(E_PROVIDER_MANIFEST_MULTI_RELEASE);
            } catch (Exception e) {
                errors.add(E_PROVIDER_MANIFEST_PARSE_ERROR);
            }
        }

        // Class: exactly GateATckProvider.class allowed (path check, no byte-reading)
        Set<String> classEntries = new TreeSet<>();
        for (EntryInfo e : scan.entries) {
            if (!e.isDir && e.name.endsWith(".class")) classEntries.add(e.name);
        }
        if (!classEntries.equals(Set.of("com/leanowtech/bloge/gatetckprovider/GateATckProvider.class"))) {
            errors.add(E_PROVIDER_CLASS_FORBIDDEN);
        }

        // Forbidden content
        for (EntryInfo e : scan.entries) {
            if (e.isDir) continue;
            String n = e.name;
            if (n.startsWith("schemas/"))    { errors.add(E_PROVIDER_SCHEMA_FORBIDDEN); break; }
            if (n.endsWith(".jar"))          { errors.add(E_PROVIDER_NESTED_JAR_FORBIDDEN); break; }
            if (n.contains("/Candidate") || n.contains("CapabilityStudioGateACandidate")
                    || n.contains("CapabilityStudioGateAChallengeCli")) {
                errors.add(E_PROVIDER_CANDIDATE_CLASS_FORBIDDEN); break;
            }
            if (n.equals("build.gradle") || n.equals("build.gradle.kts") || n.equals("pom.xml")) {
                errors.add(E_PROVIDER_BUILD_TOOL_FORBIDDEN); break;
            }
        }

        // Service descriptor
        byte[] descRaw = scan.dataOf(SPI_DESCRIPTOR);
        validateDescriptor(descRaw, errors);

        // Dependencies manifest
        byte[] depRaw = scan.dataOf("META-INF/gate-a/manifests/dependencies.json");
        if (depRaw == null) {
            errors.add(E_PROVIDER_DEP_MANIFEST_MISSING);
        } else {
            validateDepManifest(depRaw, scan, errors);
        }
    }

    private static void validateDescriptor(byte[] data, List<String> errors) {
        if (data == null) { errors.add(E_PROVIDER_SPI_DESCRIPTOR_MISSING); return; }

        // UTF-8 via CharsetDecoder with REPORT — malformed/unmappable → ENCODING before other checks
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(data));
        } catch (CharacterCodingException e) {
            errors.add(E_PROVIDER_SPI_DESCRIPTOR_ENCODING);
            return;
        }

        // BOM check (add error but do not return — continue to other checks)
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            errors.add(E_PROVIDER_SPI_DESCRIPTOR_BOM);
        }

        int lf = 0, cr = 0;
        for (byte b : data) {
            if (b == 0x0A) lf++;
            if (b == 0x0D) cr++;
        }
        if (lf == 0)   { errors.add(E_PROVIDER_SPI_DESCRIPTOR_NO_LF); return; }
        if (lf > 1)     { errors.add(E_PROVIDER_SPI_DESCRIPTOR_MULTI); return; }
        if (cr > 0)    { errors.add(E_PROVIDER_SPI_DESCRIPTOR_CRLF); return; }
        if (data[data.length - 1] != 0x0A) { errors.add(E_PROVIDER_SPI_DESCRIPTOR_TRAILING_LF); return; }

        String line = new String(data, StandardCharsets.UTF_8);
        if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
        if (line.isEmpty())    { errors.add(E_PROVIDER_SPI_DESCRIPTOR_EMPTY); return; }
        if (!line.equals(line.trim())) { errors.add(E_PROVIDER_SPI_DESCRIPTOR_WS); return; }
        if (line.contains("#") || line.contains("//")) { errors.add(E_PROVIDER_SPI_DESCRIPTOR_COMMENT); return; }
        if (!line.equals(PROVIDER_SPI_CLASS)) { errors.add(E_PROVIDER_SPI_DESCRIPTOR_CLASS); }
    }

    private static void validateDepManifest(byte[] depRaw, JarScan scan, List<String> errors) {
        Map<String, Object> man;
        try {
            man = StrictJsonParser.parse(depRaw);
        } catch (CapabilityStudioGateAException e) {
            errors.add(E_PROVIDER_DEP_JSON_PARSE_ERROR); return;
        }

        // Exact top-level key set
        if (!man.keySet().equals(Set.of("schemaVersion", "entries", "manifestFingerprint")))
            errors.add(E_PROVIDER_DEP_TOP_KEYS);
        if (!DEP_MANIFEST_SCHEMA_VERSION.equals(man.get("schemaVersion")))
            errors.add(E_PROVIDER_DEP_SCHEMA_VERSION);

        Object eo = man.get("entries");
        if (!(eo instanceof List)) { errors.add(E_PROVIDER_DEP_ENTRIES_TYPE); return; }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) eo;
        if (entries.size() != 1) errors.add(E_PROVIDER_DEP_ENTRIES_SIZE);

        if (!entries.isEmpty()) {
            Map<String, Object> entry = entries.get(0);
            // Exact field set for entry
            if (!entry.keySet().equals(Set.of("coordinate", "scope", "entryPath", "rawFingerprint")))
                errors.add(E_PROVIDER_DEP_MISSING_FIELD);
            if (!"provided".equals(entry.get("scope")))
                errors.add(E_PROVIDER_DEP_ENTRY_SCOPE);
            // Exact entryPath (class file path, not SPI service descriptor)
            if (!DEP_ENTRY_PATH_EXACT.equals(entry.get("entryPath")))
                errors.add(E_PROVIDER_DEP_ENTRY_PATH);
            // Exact coordinate
            if (!DEP_COORDINATE_EXACT.equals(entry.get("coordinate")))
                errors.add(E_PROVIDER_DEP_COORDINATE);

            // rawFingerprint: exact key set + strict value format "sha256:<64-hex>"
            Object rawFpObj = entry.get("rawFingerprint");
            if (!(rawFpObj instanceof Map)) {
                errors.add(E_PROVIDER_DEP_RAW_FP_TYPE);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> rawFpMap = (Map<String, Object>) rawFpObj;
                if (!rawFpMap.keySet().equals(Set.of("kind", "algorithm", "value"))) {
                    errors.add(E_PROVIDER_DEP_RAW_FP_TYPE);
                } else {
                    if (!"RAW_BYTES".equals(rawFpMap.get("kind"))
                            || !"SHA-256".equals(rawFpMap.get("algorithm"))) {
                        errors.add(E_PROVIDER_DEP_RAW_FP_TYPE);
                    } else {
                        Object val = rawFpMap.get("value");
                        String rawFp = (val instanceof String) ? (String) val : null;
                        if (!isValidSpiFpValue(rawFp)) {
                            errors.add(E_PROVIDER_DEP_RAW_FP_VALUE_FMT);
                        } else {
                            scan.providedSpiFingerprint = rawFp;
                        }
                    }
                }
            }
        }

        // manifestFingerprint typed AGGREGATE_COMMITMENT over full material
        String fpValue = extractAggFp(man.get("manifestFingerprint"));
        if (fpValue == null) {
            errors.add(E_PROVIDER_DEP_FP_TYPE);
        } else {
            Map<String, Object> commitBase = new LinkedHashMap<>();
            commitBase.put("schemaVersion", man.get("schemaVersion"));
            commitBase.put("entries", man.get("entries"));
            String expected = "sha256:" + CapabilityStudioGateAReceiptCanonicalizer.committed(DEP_MANIFEST_DOMAIN, commitBase);
            if (!fpValue.equals(expected)) errors.add(E_PROVIDER_DEP_FP_MISMATCH);
        }
    }

    // ── Candidate validation ──────────────────────────────────────────

    private static void validateCandidate(JarScan scan, byte[] raw,
            CapabilityStudioGateATckProviderRoleSelfTest.TckRoleContract contract,
            List<String> errors) {
        if (!scan.foundRequired.contains("com/leanowtech/bloge/gateway/testkit/CapabilityStudioGateAChallengeCli.class")) {
            errors.add(E_CANDIDATE_MISSING_CLI);
        }
        if (!scan.foundRequired.contains("com/leanowtech/bloge/gateway/testkit/CapabilityStudioStageAcceptanceAuthorityProvider.class")) {
            errors.add(E_CANDIDATE_MISSING_SPI);
        }
        // Exact set: all 7 required outer-class paths must be present
        for (String required : REQUIRED_CANDIDATE_OUTER) {
            if (!scan.foundRequired.contains(required)) {
                errors.add(E_CANDIDATE_MISSING_PROJECTION);
                break;
            }
        }

        Set<String> visible = contract.visibleSchemaIds();
        Set<String> found   = scan.schemaIds;

        Set<String> miss = new TreeSet<>(visible);
        miss.removeAll(found);
        if (!miss.isEmpty()) errors.add(E_CANDIDATE_SCHEMA_MISSING);

        Set<String> extra = new TreeSet<>(found);
        extra.removeAll(visible);
        if (!extra.isEmpty()) errors.add(E_CANDIDATE_SCHEMA_EXTRA);

        for (EntryInfo e : scan.entries) {
            if (!e.isDir && e.name.endsWith(".jar")) {
                errors.add(E_CANDIDATE_NESTED_JAR); break;
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static boolean isNormalized(String name, boolean isDir) {
        if (name == null || name.isEmpty()) return false;
        if (name.length() > MAX_ENTRY_NAME) return false;
        if (name.startsWith("/")) return false;
        if (name.contains("\\")) return false;
        if (name.indexOf('\0') >= 0) return false;
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            if (b == 0) return false;
            if (b < 0x20 && b != 0x09) return false;
        }
        if (!isDir && name.endsWith("/")) return false;
        for (String comp : name.split("/")) {
            if (comp.equals("..") || comp.equals(".")) return false;
        }
        return true;
    }

    /**
     * Validates rawFingerprint.value format: exactly "sha256:" + 64 lowercase hex chars.
     */
    private static boolean isValidSpiFpValue(String value) {
        if (value == null) return false;
        if (!value.startsWith("sha256:")) return false;
        String hex = value.substring("sha256:".length());
        if (hex.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static String extractAggFp(Object fp) {
        if (!(fp instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) fp;
        // Exact key set
        if (!m.keySet().equals(Set.of("kind", "algorithm", "value"))) return null;
        if (!"AGGREGATE_COMMITMENT".equals(m.get("kind"))) return null;
        if (!"SHA-256".equals(m.get("algorithm"))) return null;
        Object v = m.get("value");
        if (!(v instanceof String)) return null;
        // Value must pass same strict sha256:<64-hex-lowercase> check
        if (!isValidSpiFpValue((String) v)) return null;
        return (String) v;
    }

    private static String fingerprint(byte[] data) {
        return CapabilityStudioGateAReceiptCanonicalizer.rawFingerprint(data);
    }

    // ── Inner types ───────────────────────────────────────────────────

    private static final class PathWork {
        final Path path;
        final byte[] raw;
        PathWork(Path path, byte[] raw) { this.path = path; this.raw = raw; }
    }

    static final class JarScan {
        final List<EntryInfo> entries = new ArrayList<>();
        final Map<String, byte[]> dataByName = new LinkedHashMap<>();
        final Set<String> schemaIds = new TreeSet<>();
        final Map<String, byte[]> schemaData = new LinkedHashMap<>();
        final Set<String> foundRequired = new HashSet<>();
        byte[] spiBytes;
        String providedSpiFingerprint;
        byte[] dataOf(String name) { return dataByName.get(name); }
    }

    static final class EntryInfo {
        final String name;
        final boolean isDir;
        final byte[] data;
        final long uncompressedSize;
        EntryInfo(String name, boolean isDir, byte[] data, long uncompressedSize) {
            this.name = name; this.isDir = isDir;
            this.data = data; this.uncompressedSize = uncompressedSize;
        }
    }

    /**
     * Immutable validation result. All error codes are fixed strings.
     * Constructor defensively copies all mutable inputs into new collections
     * and wraps them as unmodifiable before storing.
     */
    public static final class ValidationSnapshot {
        public final String providerRawFingerprint;
        public final Map<String, String> providerEntryFingerprints;
        public final String candidateRawFingerprint;
        public final String candidateSpiFingerprint;
        public final Map<String, String> candidateSchemaFingerprints;
        public final Map<String, Long> candidateSchemaByteLengths;
        public final List<String> errors;

        ValidationSnapshot(String provFp, Map<String, String> provEntries,
                          String candFp, String spiFp,
                          Map<String, String> schemas,
                          Map<String, Long> schemaLengths,
                          List<String> errors) {
            this.providerRawFingerprint = provFp;
            this.providerEntryFingerprints = Collections.unmodifiableMap(new LinkedHashMap<>(provEntries));
            this.candidateRawFingerprint = candFp;
            this.candidateSpiFingerprint = spiFp;
            this.candidateSchemaFingerprints = Collections.unmodifiableMap(new LinkedHashMap<>(schemas));
            this.candidateSchemaByteLengths = Collections.unmodifiableMap(new LinkedHashMap<>(schemaLengths));
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }

        public boolean isPassed() { return errors.isEmpty(); }
    }

    /**
     * Immutable resource limits for JAR scanning validation.
     * Package-private; constructed via {@link #from(TckRoleContract)}.
     */
    static final class ArchiveLimits {
        final long maxZipEntries;
        final long maxSingleEntryBytes;
        final long maxTotalUncompressedBytes;
        final double maxCompressionRatio;
        final int maxEntryNameBytes;

        ArchiveLimits(long maxZipEntries, long maxSingleEntryBytes,
                      long maxTotalUncompressedBytes, double maxCompressionRatio,
                      int maxEntryNameBytes) {
            if (maxZipEntries <= 0) throw new IllegalArgumentException("maxZipEntries must be positive");
            if (maxSingleEntryBytes <= 0) throw new IllegalArgumentException("maxSingleEntryBytes must be positive");
            if (maxTotalUncompressedBytes <= 0) throw new IllegalArgumentException("maxTotalUncompressedBytes must be positive");
            if (Double.isNaN(maxCompressionRatio) || Double.isInfinite(maxCompressionRatio) || maxCompressionRatio <= 0)
                throw new IllegalArgumentException("maxCompressionRatio must be finite positive");
            if (maxEntryNameBytes <= 0) throw new IllegalArgumentException("maxEntryNameBytes must be positive");
            this.maxZipEntries = maxZipEntries;
            this.maxSingleEntryBytes = maxSingleEntryBytes;
            this.maxTotalUncompressedBytes = maxTotalUncompressedBytes;
            this.maxCompressionRatio = maxCompressionRatio;
            this.maxEntryNameBytes = maxEntryNameBytes;
        }

        static ArchiveLimits from(CapabilityStudioGateATckProviderRoleSelfTest.TckRoleContract contract) {
            return new ArchiveLimits(
                contract.maxZipEntries(),
                contract.maxSingleEntryBytes(),
                contract.maxTotalUncompressedBytes(),
                contract.maxCompressionRatio(),
                512 // fixed per contract
            );
        }
    }
}
