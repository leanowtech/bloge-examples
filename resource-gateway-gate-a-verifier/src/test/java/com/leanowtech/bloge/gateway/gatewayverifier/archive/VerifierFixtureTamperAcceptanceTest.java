package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * A1.3-02 TM-01..TM-12 tamper acceptance tests.
 */
class VerifierFixtureTamperAcceptanceTest {

    @TempDir
    Path tempDir;

    private static RealVerifierFixtureFactory FACTORY;
    private static Path AUTHORITY_PATH;
    private static Path DEP_JARS_DIR;
    private static byte[] BASELINE_JAR_BYTES;
    private static byte[] BASELINE_PLAN_BYTES;
    private static List<String> ALL_ENTRY_NAMES;

    @BeforeAll
    static void setupAll(@TempDir Path staticTempDir) throws Exception {
        String authPathStr = System.getProperty("gate.a.authority.path");
        Assertions.assertNotNull(authPathStr,
                "System property gate.a.authority.path must be set");
        AUTHORITY_PATH = Path.of(authPathStr);
        Assertions.assertTrue(Files.exists(AUTHORITY_PATH),
                "Authority JSON not found: " + AUTHORITY_PATH);

        String depJarsStr = System.getProperty("gate.a.dependency.jars");
        Assertions.assertNotNull(depJarsStr,
                "System property gate.a.dependency.jars must be set");
        DEP_JARS_DIR = Path.of(depJarsStr);
        Assertions.assertTrue(Files.isDirectory(DEP_JARS_DIR),
                "Dependency JARs dir not found: " + DEP_JARS_DIR);

        FACTORY = new RealVerifierFixtureFactory(AUTHORITY_PATH, DEP_JARS_DIR);
        BASELINE_JAR_BYTES = FACTORY.buildBaselineJar(staticTempDir);
        BASELINE_PLAN_BYTES = FACTORY.buildPackagingPlan(BASELINE_JAR_BYTES);

        ALL_ENTRY_NAMES = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(BASELINE_JAR_BYTES))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                ALL_ENTRY_NAMES.add(e.getName());
                zis.closeEntry();
            }
        }
    }

    // -------------------------------------------------------------------------
    // TM-01: omit one required nondependency -> AK-ENTRY-MISSING
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM01: omit one required nondependency")
    void tm01_omit_one_required_nondependency() throws Exception {
        String omittedEntry = FACTORY.requiredEntries().get(0);
        byte[] tamperedJar = tamperOmitEntry(BASELINE_JAR_BYTES, omittedEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm01.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM01: must be rejected");
        Assertions.assertEquals("AK-ENTRY-MISSING", snapshot.rejectionCode(),
                "TM01: must have AK-ENTRY-MISSING code");
    }

    // -------------------------------------------------------------------------
    // TM-02: duplicate entry -> AK-ENTRY-DUPLICATE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM02: duplicate entry")
    void tm02_duplicate_entry() throws Exception {
        // Find two required entries with the same UTF-8 byte length.
        // Rename srcEntry's name bytes to dstEntry's name in-place in both local and CD.
        // All offsets/sizes/counts stay the same; duplicate is detected by ExactClosureChecker.
        String[] pair = findEqualLenPair();
        String dstEntry = pair[0]; // name will become this (the one already in JAR)
        String srcEntry = pair[1]; // name bytes will be overwritten with dstEntry's bytes
        byte[] tamperedJar = tamperDuplicateBySwap(BASELINE_JAR_BYTES, srcEntry, dstEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm02.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM02: must be rejected");
        Assertions.assertEquals("AK-ENTRY-DUPLICATE", snapshot.rejectionCode(),
                "TM02: must have AK-ENTRY-DUPLICATE code");
    }

    // -------------------------------------------------------------------------
    // TM-03: NUL in path -> AK-PATH-NUL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM03: NUL in path")
    void tm03_nul_in_path() throws Exception {
        String targetEntry = findSimpleEntry();
        // Overwrite byte at name length / 2 with 0x00 in-place in both local and CD.
        byte[] tamperedJar = tamperNulInName(BASELINE_JAR_BYTES, targetEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm03.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM03: must be rejected");
        Assertions.assertEquals("AK-PATH-NUL", snapshot.rejectionCode(),
                "TM03: must have AK-PATH-NUL code");
    }

    // -------------------------------------------------------------------------
    // TM-04: absolute path -> AK-PATH-ABSOLUTE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM04: absolute path")
    void tm04_absolute_path() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperAbsolutePath(BASELINE_JAR_BYTES, baseEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm04.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM04: must be rejected");
        Assertions.assertEquals("AK-PATH-ABSOLUTE", snapshot.rejectionCode(),
                "TM04: must have AK-PATH-ABSOLUTE code");
    }

    // -------------------------------------------------------------------------
    // TM-05: backslash in path -> AK-PATH-BACKSLASH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM05: backslash in path")
    void tm05_backslash_in_path() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperBackslashPath(BASELINE_JAR_BYTES, baseEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm05.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM05: must be rejected");
        Assertions.assertEquals("AK-PATH-BACKSLASH", snapshot.rejectionCode(),
                "TM05: must have AK-PATH-BACKSLASH code");
    }

    // -------------------------------------------------------------------------
    // TM-06: '..' path segment -> AK-PATH-DOT-SEGMENT
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM06: '..' path segment")
    void tm06_dotdot_path_segment() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperDotSegmentPath(BASELINE_JAR_BYTES, baseEntry, "..");

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm06.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM06: must be rejected");
        Assertions.assertEquals("AK-PATH-DOT-SEGMENT", snapshot.rejectionCode(),
                "TM06: must have AK-PATH-DOT-SEGMENT code");
    }

    // -------------------------------------------------------------------------
    // TM-07: '.' path segment -> AK-PATH-DOT-SEGMENT
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM07: '.' path segment")
    void tm07_dot_path_segment() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperDotSegmentPath(BASELINE_JAR_BYTES, baseEntry, ".");

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm07.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM07: must be rejected");
        Assertions.assertEquals("AK-PATH-DOT-SEGMENT", snapshot.rejectionCode(),
                "TM07: must have AK-PATH-DOT-SEGMENT code");
    }

    // -------------------------------------------------------------------------
    // TM-08: nonempty ZIP extra field -> AK-EXTRA-FIELD
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM08: nonempty ZIP extra field")
    void tm08_nonempty_extra_field() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperNonemptyExtraField(BASELINE_JAR_BYTES, baseEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm08.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM08: must be rejected");
        Assertions.assertEquals("AK-EXTRA-FIELD", snapshot.rejectionCode(),
                "TM08: must have AK-EXTRA-FIELD code");
    }


    // -------------------------------------------------------------------------
    // TM-09: entry name ends in "/" (directory) -> AK-ENTRY-DIRECTORY
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM09: entry name ends in '/'")
    void tm09_entry_name_ends_in_slash() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperDirectoryEntry(BASELINE_JAR_BYTES, baseEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm09.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM09: must be rejected");
        Assertions.assertEquals("AK-ENTRY-DIRECTORY", snapshot.rejectionCode(),
                "TM09: must have AK-ENTRY-DIRECTORY code");
    }

    // -------------------------------------------------------------------------
    // TM-10: GPB encrypted flag 0x0001 in local+6 and central+8 -> AK-ENCRYPTED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM10: GPB encrypted flag")
    void tm10_encrypted_flag() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperEncryptedFlag(BASELINE_JAR_BYTES, baseEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm10.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM10: must be rejected");
        Assertions.assertEquals("AK-ENCRYPTED", snapshot.rejectionCode(),
                "TM10: must have AK-ENCRYPTED code");
    }

    // -------------------------------------------------------------------------
    // TM-11: GPB DD flag 0x0008 + zero central CRC -> AK-DD-UNVERIFIABLE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM11: data descriptor with zero central CRC")
    void tm11_data_descriptor_zero_crc() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperDDEntry(BASELINE_JAR_BYTES, baseEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm11.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM11: must be rejected");
        Assertions.assertEquals("AK-DD-UNVERIFIABLE", snapshot.rejectionCode(),
                "TM11: must have AK-DD-UNVERIFIABLE code");
    }

    // -------------------------------------------------------------------------
    // TM-12: unknown compression method 99 in local+8 and central+10 -> AK-UNKNOWN-COMPRESSION
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM12: unknown compression method 99")
    void tm12_unknown_compression_method() throws Exception {
        String baseEntry = findSimpleEntry();
        byte[] tamperedJar = tamperUnknownCompression(BASELINE_JAR_BYTES, baseEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess());

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm12.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM12: must be rejected");
        Assertions.assertEquals("AK-UNKNOWN-COMPRESSION", snapshot.rejectionCode(),
                "TM12: must have AK-UNKNOWN-COMPRESSION code");
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    // -------------------------------------------------------------------------
    // TM09 helper: rename entry to same-byte-length name ending in "/"
    // -------------------------------------------------------------------------

    private byte[] tamperDirectoryEntry(byte[] jarBytes, String baseEntry) throws IOException {
        byte[] result = jarBytes.clone();

        int[] info = findCentralEntry(result, baseEntry);
        int cdPos = info[0];
        int localOffset = info[1];
        int nameLen = info[2];
        int cdExtraLen = info[3];
        int cdCommentLen = info[4];

        // Build same-byte-length name that ends with "/".
        // Replace the final byte of the UTF-8 name with 0x2F ('/'),
        // keeping byte length identical. Works for both slash and non-slash names.
        byte[] newNameBytes = baseEntry.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        newNameBytes[newNameBytes.length - 1] = '/';

        // Overwrite name in local header (offset localOffset + 30)
        System.arraycopy(newNameBytes, 0, result, localOffset + 30, nameLen);
        // Overwrite name in central directory (offset cdPos + 46)
        System.arraycopy(newNameBytes, 0, result, cdPos + 46, nameLen);
        return result;
    }

    // -------------------------------------------------------------------------
    // TM10 helper: OR GPB encrypted flag 0x0001 in local+6 and central+8
    // -------------------------------------------------------------------------

    private byte[] tamperEncryptedFlag(byte[] jarBytes, String baseEntry) throws IOException {
        byte[] result = jarBytes.clone();

        int[] info = findCentralEntry(result, baseEntry);
        int cdPos = info[0];
        int localOffset = info[1];

        // OR 0x0001 at local GPB offset (localOffset + 6)
        result[localOffset + 6] = (byte) (result[localOffset + 6] | 0x01);
        result[localOffset + 7] = (byte) (result[localOffset + 7] | 0x00);
        // OR 0x0001 at central GPB offset (cdPos + 8)
        result[cdPos + 8] = (byte) (result[cdPos + 8] | 0x01);
        result[cdPos + 9] = (byte) (result[cdPos + 9] | 0x00);
        return result;
    }

    // -------------------------------------------------------------------------
    // TM11 helper: OR GPB DD flag 0x0008 + zero central CRC/compressed/uncompressed
    // -------------------------------------------------------------------------

    private byte[] tamperDDEntry(byte[] jarBytes, String baseEntry) throws IOException {
        byte[] result = jarBytes.clone();

        int[] info = findCentralEntry(result, baseEntry);
        int cdPos = info[0];
        int localOffset = info[1];

        // OR 0x0008 at local GPB offset (localOffset + 6)
        result[localOffset + 6] = (byte) (result[localOffset + 6] | 0x08);
        // OR 0x0008 at central GPB offset (cdPos + 8)
        result[cdPos + 8] = (byte) (result[cdPos + 8] | 0x08);
        // Zero central CRC (cdPos + 16..19)
        result[cdPos + 16] = 0;
        result[cdPos + 17] = 0;
        result[cdPos + 18] = 0;
        result[cdPos + 19] = 0;
        // Zero central compressed size (cdPos + 20..23)
        result[cdPos + 20] = 0;
        result[cdPos + 21] = 0;
        result[cdPos + 22] = 0;
        result[cdPos + 23] = 0;
        // Zero central uncompressed size (cdPos + 24..27)
        result[cdPos + 24] = 0;
        result[cdPos + 25] = 0;
        result[cdPos + 26] = 0;
        result[cdPos + 27] = 0;
        return result;
    }

    // -------------------------------------------------------------------------
    // TM12 helper: set compression method 99 in local+8 and central+10
    // -------------------------------------------------------------------------

    private byte[] tamperUnknownCompression(byte[] jarBytes, String baseEntry) throws IOException {
        byte[] result = jarBytes.clone();

        int[] info = findCentralEntry(result, baseEntry);
        int cdPos = info[0];
        int localOffset = info[1];

        // Set method 99 (little-endian: 99, 0) at local offset 8
        result[localOffset + 8] = 99;
        result[localOffset + 9] = 0;
        // Set method 99 (little-endian: 99, 0) at central offset 10
        result[cdPos + 10] = 99;
        result[cdPos + 11] = 0;
        return result;
    }

    // -------------------------------------------------------------------------
    // Shared byte-level locator used by TM09-TM12 helpers
    // Returns: [cdPos, localOffset, nameLen, cdExtraLen, cdCommentLen]
    // -------------------------------------------------------------------------

    private int[] findCentralEntry(byte[] data, String targetEntry) {
        long eocdOffset = findEocdOffset(data);
        assert eocdOffset >= 0 : "EOCD not found";
        assert data[(int)eocdOffset + 0] == 0x50 && data[(int)eocdOffset + 1] == 0x4b
                && data[(int)eocdOffset + 2] == 0x05 && data[(int)eocdOffset + 3] == 0x06
                : "EOCD sig not found";

        int cdOffset = readIntLE(data, (int) eocdOffset + 16);
        int cdEntries = readShortLE(data, (int) eocdOffset + 10);

        int pos = cdOffset;
        for (int i = 0; i < cdEntries; i++) {
            assert readIntLE(data, pos) == 0x02014b50 : "Not a CD signature";
            int nameLen = readShortLE(data, pos + 28);
            int cdExtraLen = readShortLE(data, pos + 30);
            int cdCommentLen = readShortLE(data, pos + 32);
            int localOffset = readIntLE(data, pos + 42);

            byte[] nameBytes = java.util.Arrays.copyOfRange(data, pos + 46, pos + 46 + nameLen);
            String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (name.equals(targetEntry)) {
                return new int[] { pos, localOffset, nameLen, cdExtraLen, cdCommentLen };
            }
            pos += 46 + nameLen + cdExtraLen + cdCommentLen;
        }
        assert false : "Entry not found: " + targetEntry;
        return null;
    }


    private String findSimpleEntry() {
        for (String name : ALL_ENTRY_NAMES) {
            if (!name.equals("META-INF/MANIFEST.MF")) return name;
        }
        return ALL_ENTRY_NAMES.isEmpty() ? null : ALL_ENTRY_NAMES.get(0);
    }

    /**
     * Find two required entries with the same UTF-8 byte length for TM02.
     * Returns [dstEntry, srcEntry] where srcEntry name bytes will be overwritten
     * with dstEntry name bytes (same length, so no offsets change).
     */
    private String[] findEqualLenPair() {
        List<String> required = FACTORY.requiredEntries();
        for (int i = 0; i < required.size(); i++) {
            for (int j = i + 1; j < required.size(); j++) {
                if (required.get(i).getBytes(StandardCharsets.UTF_8).length
                        == required.get(j).getBytes(StandardCharsets.UTF_8).length) {
                    return new String[] { required.get(i), required.get(j) };
                }
            }
        }
        Assertions.fail("No two required entries have equal UTF-8 byte length");
        return null;
    }

    private static String sha256fp(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // =========================================================================
    // ZIP Tampering Operations
    // =========================================================================

    private record RawEntry(String name, int method, int crc, int uSize, int cSize, byte[] data) {}

    private List<RawEntry> parseZipEntries(byte[] jarBytes) throws IOException {
        List<RawEntry> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] data = zis.readAllBytes();
                CRC32 c = new CRC32();
                c.update(data);
                entries.add(new RawEntry(entry.getName(), entry.getMethod(),
                        (int) c.getValue(), data.length, data.length, data));
                zis.closeEntry();
            }
        }
        return entries;
    }

    // -------------------------------------------------------------------------
    // TM01: omit entry — valid ZIP rebuild
    // -------------------------------------------------------------------------

    private byte[] tamperOmitEntry(byte[] jarBytes, String entryName) throws IOException {
        List<RawEntry> entries = parseZipEntries(jarBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (RawEntry e : entries) {
                if (e.name().equals(entryName)) continue;
                ZipEntry ze = new ZipEntry(e.name());
                ze.setMethod(e.method());
                if (e.method() == ZipEntry.STORED) {
                    ze.setCrc(e.crc());
                    ze.setSize(e.uSize());
                    ze.setCompressedSize(e.cSize());
                }
                zos.putNextEntry(ze);
                zos.write(e.data());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // TM02: duplicate — swap name bytes in-place (same length, no offsets change)
    //
    // Strategy: locate srcEntry's CD record, read its localHeaderOffset,
    // verify srcNameLen == dstNameLen, overwrite name bytes at:
    //   - local:  localOffset + 30
    //   - central: cdPos + 46
    // No other bytes change. All offsets/sizes/counts stay identical.
    // -------------------------------------------------------------------------

    private byte[] tamperDuplicateBySwap(byte[] jarBytes, String srcEntry, String dstEntry)
            throws IOException {
        byte[] result = jarBytes.clone();

        long eocdOffset = findEocdOffset(result);
        Assertions.assertTrue(eocdOffset >= 0, "EOCD not found");

        int cdOffset = readIntLE(result, (int) eocdOffset + 16);
        int cdEntries = readShortLE(result, (int) eocdOffset + 10);

        byte[] dstNameBytes = dstEntry.getBytes(StandardCharsets.UTF_8);
        int nameLen = dstNameBytes.length;

        // Walk central directory to find srcEntry
        int pos = cdOffset;
        for (int i = 0; i < cdEntries; i++) {
            if (readIntLE(result, pos) != 0x02014b50) break;

            int cdNameLen = readShortLE(result, pos + 28);
            int cdExtraLen = readShortLE(result, pos + 30);
            int cdCommentLen = readShortLE(result, pos + 32);
            int localOffset = readIntLE(result, pos + 42);
            int cdEntryLen = 46 + cdNameLen + cdExtraLen + cdCommentLen;

            if (cdNameLen == nameLen) {
                byte[] cdNameBytes = Arrays.copyOfRange(result, pos + 46, pos + 46 + cdNameLen);
                String cdName = new String(cdNameBytes, StandardCharsets.UTF_8);

                if (cdName.equals(srcEntry)) {
                    // Verify local header name matches
                    int locNameLen = readShortLE(result, localOffset + 26);
                    Assertions.assertEquals(nameLen, locNameLen,
                            "Local name length must match CD name length");

                    // Overwrite name bytes in local header
                    System.arraycopy(dstNameBytes, 0, result, localOffset + 30, nameLen);
                    // Overwrite name bytes in central directory
                    System.arraycopy(dstNameBytes, 0, result, pos + 46, nameLen);
                    return result;
                }
            }

            pos += cdEntryLen;
        }

        Assertions.fail("srcEntry not found: " + srcEntry);
        return result;
    }

    // -------------------------------------------------------------------------
    // TM03: NUL in name — mutate one byte in-place in both local and CD
    //
    // Strategy: locate the entry's CD record, read localHeaderOffset,
    // change byte at nameLen/2 to 0x00 in both local+30 and central+46.
    // No size/offset/metadata changes.
    // -------------------------------------------------------------------------

    private byte[] tamperNulInName(byte[] jarBytes, String targetEntry) throws IOException {
        byte[] result = jarBytes.clone();

        long eocdOffset = findEocdOffset(result);
        Assertions.assertTrue(eocdOffset >= 0, "EOCD not found");

        int cdOffset = readIntLE(result, (int) eocdOffset + 16);
        int cdEntries = readShortLE(result, (int) eocdOffset + 10);

        int pos = cdOffset;
        for (int i = 0; i < cdEntries; i++) {
            if (readIntLE(result, pos) != 0x02014b50) break;

            int nameLen = readShortLE(result, pos + 28);
            int extraLen = readShortLE(result, pos + 30);
            int commentLen = readShortLE(result, pos + 32);
            int localOffset = readIntLE(result, pos + 42);
            int cdEntryLen = 46 + nameLen + extraLen + commentLen;

            byte[] cdNameBytes = Arrays.copyOfRange(result, pos + 46, pos + 46 + nameLen);
            String cdName = new String(cdNameBytes, StandardCharsets.UTF_8);

            if (cdName.equals(targetEntry)) {
                // Verify local header name matches
                int locNameLen = readShortLE(result, localOffset + 26);
                Assertions.assertEquals(nameLen, locNameLen,
                        "Local name length must match CD name length");

                // Mutate byte at position nameLen/2 to NUL in local header
                result[localOffset + 30 + nameLen / 2] = 0x00;
                // Mutate same byte position in central directory
                result[pos + 46 + nameLen / 2] = 0x00;
                return result;
            }

            pos += cdEntryLen;
        }

        Assertions.fail("targetEntry not found: " + targetEntry);
        return result;
    }

    // -------------------------------------------------------------------------
    // TM04/TM05/TM06/TM07: name changes via valid ZIP rebuild
    // -------------------------------------------------------------------------

    private byte[] tamperAbsolutePath(byte[] jarBytes, String baseEntry) throws IOException {
        return replaceEntryName(jarBytes, baseEntry, "/" + baseEntry);
    }

    private byte[] tamperBackslashPath(byte[] jarBytes, String baseEntry) throws IOException {
        return replaceEntryName(jarBytes, baseEntry, baseEntry.replace("/", "\\"));
    }

    private byte[] tamperDotSegmentPath(byte[] jarBytes, String baseEntry, String segment)
            throws IOException {
        int slashPos = baseEntry.lastIndexOf('/');
        String tamperedName;
        if (slashPos >= 0) {
            tamperedName = baseEntry.substring(0, slashPos + 1) + segment
                    + baseEntry.substring(slashPos);
        } else {
            tamperedName = segment + "/" + baseEntry;
        }
        return replaceEntryName(jarBytes, baseEntry, tamperedName);
    }

    private byte[] replaceEntryName(byte[] jarBytes, String oldName, String newName)
            throws IOException {
        List<RawEntry> entries = parseZipEntries(jarBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (RawEntry e : entries) {
                String targetName = e.name().equals(oldName) ? newName : e.name();
                ZipEntry ze = new ZipEntry(targetName);
                ze.setMethod(e.method());
                if (e.method() == ZipEntry.STORED) {
                    ze.setCrc(e.crc());
                    ze.setSize(e.uSize());
                    ze.setCompressedSize(e.cSize());
                }
                zos.putNextEntry(ze);
                zos.write(e.data());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // TM08: nonzero extra field — raw ZIP with cdExtraLen=4 on target entry
    //
    // Uses the EntrySpec + buildZip pattern from ZipArchiveVerifierTest.
    // -------------------------------------------------------------------------

    private static class EntrySpec {
        String name = "file.txt";
        int method = 0;
        int gpb = 0;
        int crc = 0;
        int uSize = 0;
        int cSize = 0;
        byte[] data = new byte[0];
        int localExtraLen = 0;
        int cdExtraLen = 0;
        int cdCommentLen = 0;
        long extAttr = 0;
    }

    private static byte[] buildCdRecord(EntrySpec e, long localOffset) {
        byte[] nameBytes = e.name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer cd = ByteBuffer.allocate(46 + nameBytes.length + e.cdExtraLen + e.cdCommentLen)
                .order(ByteOrder.LITTLE_ENDIAN);
        cd.putInt(0x02014b50);
        cd.putShort((short) 20);
        cd.putShort((short) 20);
        cd.putShort((short) e.gpb);
        cd.putShort((short) e.method);
        cd.putShort((short) 0);
        cd.putShort((short) 0);
        cd.putInt(e.crc);
        cd.putInt(e.cSize);
        cd.putInt(e.uSize);
        cd.putShort((short) nameBytes.length);
        cd.putShort((short) e.cdExtraLen);
        cd.putShort((short) e.cdCommentLen);
        cd.putShort((short) 0);
        cd.putShort((short) 0);
        cd.putInt((int) e.extAttr);
        cd.putInt((int) localOffset);
        cd.put(nameBytes);
        if (e.cdExtraLen > 0) cd.put(new byte[e.cdExtraLen]);
        if (e.cdCommentLen > 0) cd.put(new byte[e.cdCommentLen]);
        return cd.array();
    }

    private static byte[] buildZip(List<EntrySpec> entries, byte[] eocdCommentBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        List<Long> localOffsets = new ArrayList<>();

        for (EntrySpec e : entries) {
            long localOffset = out.size();
            localOffsets.add(localOffset);
            byte[] nameBytes = e.name.getBytes(StandardCharsets.UTF_8);

            ByteBuffer loc = ByteBuffer.allocate(30 + nameBytes.length + e.localExtraLen)
                    .order(ByteOrder.LITTLE_ENDIAN);
            loc.putInt(0x04034b50);
            loc.putShort((short) 20);
            loc.putShort((short) e.gpb);
            loc.putShort((short) e.method);
            loc.putShort((short) 0);
            loc.putShort((short) 0);
            loc.putInt(e.crc);
            loc.putInt(e.cSize);
            loc.putInt(e.uSize);
            loc.putShort((short) nameBytes.length);
            loc.putShort((short) e.localExtraLen);
            loc.put(nameBytes);
            if (e.localExtraLen > 0) loc.put(new byte[e.localExtraLen]);
            out.write(loc.array(), 0, loc.array().length);
            if (e.data != null) out.write(e.data, 0, e.data.length);
        }

        long cdOffset = out.size();
        for (int i = 0; i < entries.size(); i++) {
            byte[] cd = buildCdRecord(entries.get(i), localOffsets.get(i));
            out.write(cd, 0, cd.length);
        }
        long cdEnd = out.size();
        int cdSize = (int) (cdEnd - cdOffset);

        int eocdSize = 22 + (eocdCommentBytes != null ? eocdCommentBytes.length : 0);
        ByteBuffer eocd = ByteBuffer.allocate(eocdSize).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50);
        eocd.putShort((short) 0);
        eocd.putShort((short) 0);
        eocd.putShort((short) entries.size());
        eocd.putShort((short) entries.size());
        eocd.putInt(cdSize);
        eocd.putInt((int) cdOffset);
        eocd.putShort((short) (eocdCommentBytes != null ? eocdCommentBytes.length : 0));
        if (eocdCommentBytes != null) eocd.put(eocdCommentBytes);
        out.write(eocd.array());

        return out.toByteArray();
    }

    private static byte[] buildZip(List<EntrySpec> entries) throws IOException {
        return buildZip(entries, null);
    }

    private byte[] tamperNonemptyExtraField(byte[] jarBytes, String targetName) throws IOException {
        List<RawEntry> entries = parseZipEntries(jarBytes);
        List<EntrySpec> specs = new ArrayList<>();
        List<Long> localOffsets = new ArrayList<>();
        ByteArrayOutputStream localOut = new ByteArrayOutputStream();

        for (RawEntry e : entries) {
            long localOffset = localOut.size();
            localOffsets.add(localOffset);
            byte[] nameBytes = e.name().getBytes(StandardCharsets.UTF_8);

            ByteBuffer loc = ByteBuffer.allocate(30 + nameBytes.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            loc.putInt(0x04034b50);
            loc.putShort((short) 20);
            loc.putShort((short) 0);
            loc.putShort((short) e.method());
            loc.putShort((short) 0);
            loc.putShort((short) 0);
            loc.putInt(e.crc());
            loc.putInt(e.cSize());
            loc.putInt(e.uSize());
            loc.putShort((short) nameBytes.length);
            loc.putShort((short) 0);  // localExtraLen = 0
            loc.put(nameBytes);
            localOut.write(loc.array(), 0, loc.array().length);
            localOut.write(e.data(), 0, e.data().length);

            EntrySpec spec = new EntrySpec();
            spec.name = e.name();
            spec.method = e.method();
            spec.crc = e.crc();
            spec.uSize = e.uSize();
            spec.cSize = e.cSize();
            spec.data = e.data();
            spec.extAttr = 0;
            if (e.name().equals(targetName)) {
                spec.cdExtraLen = 4;  // nonzero -> triggers AK-EXTRA-FIELD
            }
            specs.add(spec);
        }

        // Central directory
        ByteArrayOutputStream cdOut = new ByteArrayOutputStream();
        for (int i = 0; i < specs.size(); i++) {
            byte[] cd = buildCdRecord(specs.get(i), localOffsets.get(i));
            cdOut.write(cd, 0, cd.length);
        }

        long cdOffset = localOut.size();
        int cdSize = cdOut.size();
        int totalEntries = specs.size();

        ByteArrayOutputStream eocdOut = new ByteArrayOutputStream();
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50);
        eocd.putShort((short) 0);
        eocd.putShort((short) 0);
        eocd.putShort((short) totalEntries);
        eocd.putShort((short) totalEntries);
        eocd.putInt(cdSize);
        eocd.putInt((int) cdOffset);
        eocd.putShort((short) 0);
        eocdOut.write(eocd.array());

        byte[] result = new byte[(int) cdOffset + cdSize + 22];
        System.arraycopy(localOut.toByteArray(), 0, result, 0, (int) cdOffset);
        System.arraycopy(cdOut.toByteArray(), 0, result, (int) cdOffset, cdSize);
        System.arraycopy(eocdOut.toByteArray(), 0, result, (int) cdOffset + cdSize, 22);
        return result;
    }

    // =========================================================================
    // ZIP Byte-Level Utilities
    // =========================================================================

    private static long findEocdOffset(byte[] data) {
        int maxSearch = Math.min(data.length, 65557);
        for (int i = data.length - 22; i >= data.length - maxSearch; i--) {
            if (i + 4 > data.length) continue;
            if ((data[i] & 0xFF) == 0x50 && (data[i+1] & 0xFF) == 0x4b
                    && (data[i+2] & 0xFF) == 0x05 && (data[i+3] & 0xFF) == 0x06) {
                return i;
            }
        }
        return -1;
    }

    private static int readIntLE(byte[] data, int pos) {
        return ((data[pos] & 0xFF))
                | ((data[pos+1] & 0xFF) << 8)
                | ((data[pos+2] & 0xFF) << 16)
                | ((data[pos+3] & 0xFF) << 24);
    }

    private static int readShortLE(byte[] data, int pos) {
        return ((data[pos] & 0xFF)) | ((data[pos+1] & 0xFF) << 8);
    }
}
