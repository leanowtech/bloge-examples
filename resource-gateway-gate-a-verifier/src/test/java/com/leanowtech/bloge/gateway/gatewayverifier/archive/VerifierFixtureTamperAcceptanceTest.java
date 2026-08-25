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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    // -------------------------------------------------------------------------
    // TM-13: rename entry to same-byte-length name starting with META-INF/versions/
    //         -> AK-MULTI-RELEASE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM13: entry name starts with META-INF/versions/")
    void tm13_multi_release_path() throws Exception {
        String baseEntry = findEntryOfMinByteLength(25);
        Assertions.assertNotNull(baseEntry,
                "Need an entry with at least 25-byte UTF-8 name for META-INF/versions/");
        byte[] tamperedJar = tamperMultiReleasePath(BASELINE_JAR_BYTES, baseEntry);

        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm13.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM13: must be rejected");
        Assertions.assertEquals("AK-MULTI-RELEASE", snapshot.rejectionCode(),
                "TM13: must have AK-MULTI-RELEASE code");
    }

    // -------------------------------------------------------------------------
    // TM-14: Unix symlink external attribute on a required entry -> AK-EXTERNAL-SYMLINK
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM14: Unix symlink external attribute")
    void tm14_external_symlink() throws Exception {
        byte[] tamperedJar = buildFullJarWithSymlinkAttr();

        byte[] planBytes = FACTORY.buildPackagingPlan(tamperedJar);

        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult = parser.parse(planBytes, sha256fp(planBytes));
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm14.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM14: must be rejected");
        Assertions.assertEquals("AK-EXTERNAL-SYMLINK", snapshot.rejectionCode(),
                "TM14: must have AK-EXTERNAL-SYMLINK code");
    }

    // -------------------------------------------------------------------------
    // TM-15: Unix block-device external attribute -> AK-EXTERNAL-SPECIAL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM15: Unix block-device external attribute")
    void tm15_external_block_device() throws Exception {
        byte[] tamperedJar = buildFullJarWithBlockAttr();

        byte[] planBytes = FACTORY.buildPackagingPlan(tamperedJar);

        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult = parser.parse(planBytes, sha256fp(planBytes));
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm15.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM15: must be rejected");
        Assertions.assertEquals("AK-EXTERNAL-SPECIAL", snapshot.rejectionCode(),
                "TM15: must have AK-EXTERNAL-SPECIAL code");
    }

    // -------------------------------------------------------------------------
    // TM-16: STORED nondep entry, one payload byte mutated -> AK-CRC-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM16: STORED entry with mutated payload")
    void tm16_stored_payload_mutate() throws Exception {
        byte[] tamperedJar = buildFullStoredJarWithPayloadMutation();

        byte[] planBytes = FACTORY.buildPackagingPlan(tamperedJar);

        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult = parser.parse(planBytes, sha256fp(planBytes));
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm16.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM16: must be rejected");
        Assertions.assertEquals("AK-CRC-MISMATCH", snapshot.rejectionCode(),
                "TM16: must have AK-CRC-MISMATCH code");
    }

    // -------------------------------------------------------------------------
    // TM-17: STORED entry, declared uncompressed size = actual+1 -> AK-SIZE-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM17: STORED entry with inflated size")
    void tm17_stored_size_inflated() throws Exception {
        byte[] tamperedJar = buildFullStoredJarWithSizePlusOne();

        byte[] planBytes = FACTORY.buildPackagingPlan(tamperedJar);

        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult = parser.parse(planBytes, sha256fp(planBytes));
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm17.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM17: must be rejected");
        Assertions.assertEquals("AK-SIZE-MISMATCH", snapshot.rejectionCode(),
                "TM17: must have AK-SIZE-MISMATCH code");
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

    // -------------------------------------------------------------------------
    // TM13 helper: find entry with name >= minByteLength
    // -------------------------------------------------------------------------

    private String findEntryOfMinByteLength(int minByteLength) {
        for (String name : ALL_ENTRY_NAMES) {
            byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (nameBytes.length >= minByteLength) {
                return name;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // TM13 helper: rename entry to META-INF/versions/xx/... prefix (same byte length)
    // -------------------------------------------------------------------------

    private byte[] tamperMultiReleasePath(byte[] jarBytes, String baseEntry) throws IOException {
        byte[] result = jarBytes.clone();
        byte[] baseNameBytes = baseEntry.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int baseLen = baseNameBytes.length;

        // "META-INF/versions/" = 18 bytes; pad with '.' to fill same byte length
        String prefix = "META-INF/versions/";
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int padLen = baseLen - prefixBytes.length;

        byte[] newNameBytes = new byte[baseLen];
        System.arraycopy(prefixBytes, 0, newNameBytes, 0, prefixBytes.length);
        for (int i = 0; i < padLen; i++) {
            newNameBytes[prefixBytes.length + i] = '.';
        }

        int[] info = findCentralEntry(result, baseEntry);
        int cdPos = info[0];
        int localOffset = info[1];

        System.arraycopy(newNameBytes, 0, result, localOffset + 30, baseLen);
        System.arraycopy(newNameBytes, 0, result, cdPos + 46, baseLen);
        return result;
    }

    // -------------------------------------------------------------------------
    // TM14 helper: build full JAR with Unix symlink attribute (0120000)
    // -------------------------------------------------------------------------

    private byte[] buildFullJarWithSymlinkAttr() throws Exception {
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                builder.addDependency(req);
            } else {
                builder.addNonDependency(req, new byte[16]);
            }
        }
        return mutateEntryExtAttr(builder.build(), FACTORY.requiredEntries().get(0), 0120000);
    }

    // -------------------------------------------------------------------------
    // TM15 helper: build full JAR with Unix block-device attribute (060000)
    // -------------------------------------------------------------------------

    private byte[] buildFullJarWithBlockAttr() throws Exception {
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                builder.addDependency(req);
            } else {
                builder.addNonDependency(req, new byte[16]);
            }
        }
        return mutateEntryExtAttr(builder.build(), FACTORY.requiredEntries().get(0), 060000);
    }

    // -------------------------------------------------------------------------
    // TM16 helper: build all-STORED JAR, mutate payload byte (preserves CRC in plan)
    // -------------------------------------------------------------------------

    private byte[] buildFullStoredJarWithPayloadMutation() throws Exception {
        List<RawEntry> baselineEntries = parseZipEntries(BASELINE_JAR_BYTES);
        java.util.Map<String, byte[]> nondepContent = new java.util.LinkedHashMap<>();
        for (RawEntry e : baselineEntries) {
            if (!isDependencyEntry(e.name())) {
                nondepContent.put(e.name(), e.data());
            }
        }

        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                builder.addStoredDependency(req);
            } else {
                byte[] content = nondepContent.getOrDefault(req, new byte[1]);
                builder.addStoredEntry(req, content);
            }
        }
        byte[] storedJar = builder.build();
        Assertions.assertTrue(isAllStored(storedJar), "JAR must be all-STORED for TM16");

        String targetEntry = nondepContent.keySet().iterator().next();
        return mutateStoredEntryPayload(storedJar, targetEntry, 0, (byte) '!');
    }

    // -------------------------------------------------------------------------
    // TM17 helper: build all-STORED JAR, set uncompressed size = actual+1
    // -------------------------------------------------------------------------

    private byte[] buildFullStoredJarWithSizePlusOne() throws Exception {
        List<RawEntry> baselineEntries = parseZipEntries(BASELINE_JAR_BYTES);
        java.util.Map<String, byte[]> nondepContent = new java.util.LinkedHashMap<>();
        for (RawEntry e : baselineEntries) {
            if (!isDependencyEntry(e.name())) {
                nondepContent.put(e.name(), e.data());
            }
        }

        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                builder.addStoredDependency(req);
            } else {
                byte[] content = nondepContent.getOrDefault(req, new byte[1]);
                builder.addStoredEntry(req, content);
            }
        }
        byte[] storedJar = builder.build();

        String targetEntry = nondepContent.keySet().iterator().next();
        return mutateStoredEntrySize(storedJar, targetEntry, +1);
    }

    // -------------------------------------------------------------------------
    // Shared helper: check if all entries use STORED compression
    // -------------------------------------------------------------------------

    private boolean isAllStored(byte[] jarBytes) throws IOException {
        List<RawEntry> entries = parseZipEntries(jarBytes);
        for (RawEntry e : entries) {
            if (e.method() != java.util.zip.ZipEntry.STORED) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Shared helper: check if entry is a dependency
    // -------------------------------------------------------------------------

    private boolean isDependencyEntry(String entryPath) {
        for (RealVerifierFixtureFactory.DependencyEntry dep : FACTORY.embeddedDependencies()) {
            if (dep.entryPath().equals(entryPath)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Shared mutation helpers
    // -------------------------------------------------------------------------

    private byte[] mutateEntryExtAttr(byte[] jarBytes, String targetEntry, int unixType) {
        byte[] result = jarBytes.clone();
        int[] info = findCentralEntry(result, targetEntry);
        int cdPos = info[0];
        int currentExtAttr = readIntLE(result, cdPos + 38);
        int newExtAttr = (unixType << 16) | (currentExtAttr & 0xFFFF);
        writeIntLE(result, cdPos + 38, newExtAttr);
        return result;
    }

    private byte[] mutateStoredEntryPayload(byte[] jarBytes, String targetEntry,
                                            int byteIndex, byte newValue) {
        byte[] result = jarBytes.clone();
        int[] info = findCentralEntry(result, targetEntry);
        int localOffset = info[1];
        int nameLen = info[2];
        int uSize = readIntLE(result, localOffset + 18);

        long payloadStart = localOffset + 30 + nameLen;
        if (byteIndex >= 0 && byteIndex < uSize) {
            result[(int) payloadStart + byteIndex] = newValue;
        }
        return result;
    }

    // NOTE: for TM17 size mismatch, only uncompressed size fields are modified.
    // local+18 (compressed size) and cd+20 (compressed size) are left untouched.
    private byte[] mutateStoredEntrySize(byte[] jarBytes, String targetEntry, int sizeDelta) {
        byte[] result = jarBytes.clone();
        int[] info = findCentralEntry(result, targetEntry);
        int cdPos = info[0];
        int localOffset = info[1];

        // Read uncompressed sizes from correct offsets:
        // local+22 = uncompressed size (local header)
        // cd+24    = uncompressed size (central directory)
        int uSizeLocal = readIntLE(result, localOffset + 22);
        int uSizeCd = readIntLE(result, cdPos + 24);

        writeIntLE(result, localOffset + 22, uSizeLocal + sizeDelta);
        writeIntLE(result, cdPos + 24, uSizeCd + sizeDelta);

        return result;
    }

    private static void writeIntLE(byte[] data, int offset, int value) {
        data[offset    ] = (byte) (value        & 0xFF);
        data[offset + 1] = (byte) ((value >> 8)  & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    // -------------------------------------------------------------------------
    // TM-19: 29-entry archive (28 required + 1 extra small entry)
    //         raw/entry limits below caps except entry count -> AK-ENTRY-COUNT-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM19: one extra entry beyond required 28")
    void tm19_one_extra_entry() throws Exception {
        // Build all-STORED 28-entry JAR, then add one extra STORED entry via builder.
        // Entry count = 29, so plan's expected count (28) mismatches.
        // All other limits are well below their caps.

        List<RawEntry> baselineEntries = parseZipEntries(BASELINE_JAR_BYTES);
        java.util.Map<String, byte[]> nondepContent = new java.util.LinkedHashMap<>();
        for (RawEntry e : baselineEntries) {
            if (!isDependencyEntry(e.name())) {
                nondepContent.put(e.name(), e.data());
            }
        }

        // Build 28-entry all-STORED JAR, then add one extra STORED entry.
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                builder.addStoredDependency(req);
            } else {
                byte[] content = nondepContent.getOrDefault(req, new byte[1]);
                builder.addStoredEntry(req, content);
            }
        }
        // Append the 29th entry (not in plan's exactArchiveEntries)
        builder.addStoredEntry("extra/entry.txt", new byte[8]);

        byte[] tamperedJar = builder.build();

        int entryCount = countZipEntries(tamperedJar);
        Assertions.assertEquals(29, entryCount,
                "Tampered JAR must have exactly 29 entries");
        Assertions.assertTrue(isAllStored(tamperedJar), "JAR must be all-STORED for TM19");

        // Verify entry count is within maxZipEntries (the plan mismatch is the trigger)
        long maxZip = FACTORY.artifactLimits().maxZipEntries();
        Assertions.assertTrue(29 <= maxZip,
                "TM19 entry count must be within maxZipEntries so plan mismatch is the trigger");

        // Plan from baseline expects exactly 28 entries
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");
        PackagedPlan plan = parseResult.plan();

        Path jarPath = tempDir.resolve("tm19.jar");
        Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM19: must be rejected");
        Assertions.assertEquals("AK-ENTRY-COUNT-MISMATCH", snapshot.rejectionCode(),
                "TM19: must have AK-ENTRY-COUNT-MISMATCH code");
    }

    // -------------------------------------------------------------------------
    // TM-25: 28 required entries, one nondep STORED exactly maxSingleEntryBytes+1,
    //         total raw bytes below maxRawBytes -> AK-LIMIT-SINGLE-ENTRY
    //         Build all STORED so DD/content validation passes.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM25: single entry uncompressed exceeds maxSingleEntryBytes")
    void tm25_single_entry_exceeds_max_single_entry_bytes() throws Exception {
        long maxSingleEntry = FACTORY.artifactLimits().maxSingleEntryBytes();
        long maxRaw = FACTORY.artifactLimits().maxRawBytes();

        // Uncompressed target for the one oversized entry
        long oversizedUncomp = maxSingleEntry + 1L; // exactly maxSingleEntryBytes + 1

        // Determine which nondep entry will be oversized (deterministic: first nondep)
        String oversizedEntry = null;
        for (String req : FACTORY.requiredEntries()) {
            if (!isDependencyEntry(req)) {
                oversizedEntry = req;
                break;
            }
        }
        Assertions.assertNotNull(oversizedEntry, "Must have at least one nondep entry for TM25");

        // Build all-STORED JAR: real deps, one oversized STORED nondep, rest tiny STORED
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                builder.addStoredDependency(req);
            } else {
                if (req.equals(oversizedEntry)) {
                    // Oversized entry: STORED with maxSingleEntry+1 bytes
                    builder.addStoredEntry(req, new byte[(int) oversizedUncomp]);
                } else {
                    // Tiny nondep content
                    builder.addStoredEntry(req, new byte[1]);
                }
            }
        }
        byte[] jarBytes = builder.build();

        // Verify preconditions
        int entryCount = countZipEntries(jarBytes);
        Assertions.assertEquals(28, entryCount, "JAR must have exactly 28 entries for TM25");
        Assertions.assertTrue(isAllStored(jarBytes), "JAR must be all-STORED for TM25");

        // Verify total raw bytes < maxRawBytes
        long totalRaw = jarBytes.length;
        Assertions.assertTrue(totalRaw < maxRaw,
                "TM25: total raw (" + totalRaw + ") must be < maxRawBytes (" + maxRaw + ")");

        // Verify one STORED entry has size exactly maxSingleEntry + 1
        boolean[] foundOversized = { false };
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getMethod() == ZipEntry.STORED) {
                    byte[] data = zis.readAllBytes();
                    if (data.length == oversizedUncomp) {
                        foundOversized[0] = true;
                    }
                }
                zis.closeEntry();
            }
        }
        Assertions.assertTrue(foundOversized[0],
                "Must have one STORED entry with size exactly " + oversizedUncomp);

        // Build plan from baseline JAR
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");
        PackagedPlan plan = parseResult.plan();

        Path jarPath = tempDir.resolve("tm25.jar");
        Files.write(jarPath, jarBytes);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM25: must be rejected");
        Assertions.assertEquals("AK-LIMIT-SINGLE-ENTRY", snapshot.rejectionCode(),
                "TM25: must have AK-LIMIT-SINGLE-ENTRY code");
    }

    // -------------------------------------------------------------------------
    // TM-18: exactly 28 entries, all STORED, total raw bytes = maxRawBytes + 1,
    //         distribute deterministic padding across nondep entries so every
    //         uncompressed entry <= maxSingleEntryBytes -> AK-LIMIT-RAW-BYTES
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM18: total raw bytes exceeds maxRawBytes by exactly 1")
    void tm18_raw_bytes_exceeds_max_raw_bytes() throws Exception {
        long maxRaw = FACTORY.artifactLimits().maxRawBytes();
        long maxSingleEntry = FACTORY.artifactLimits().maxSingleEntryBytes();

        // Collect nondep entry content from baseline
        List<RawEntry> baselineEntries = parseZipEntries(BASELINE_JAR_BYTES);
        java.util.Map<String, byte[]> nondepContent = new java.util.LinkedHashMap<>();
        for (RawEntry e : baselineEntries) {
            if (!isDependencyEntry(e.name())) {
                nondepContent.put(e.name(), e.data());
            }
        }

        // Build baseline all-STORED JAR (28 entries)
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                builder.addStoredDependency(req);
            } else {
                byte[] content = nondepContent.getOrDefault(req, new byte[1]);
                builder.addStoredEntry(req, content);
            }
        }
        byte[] baselineStoredJar = builder.build();
        Assertions.assertTrue(isAllStored(baselineStoredJar),
                "Baseline for TM18 must be all-STORED");
        Assertions.assertEquals(28, countZipEntries(baselineStoredJar),
                "Baseline JAR must have exactly 28 entries");

        // Measure baseline raw size
        long baselineRaw = baselineStoredJar.length;
        Assertions.assertTrue(baselineRaw < maxRaw + 1,
                "Baseline STORED JAR raw size (" + baselineRaw
                        + ") must be < maxRawBytes+1 (" + (maxRaw + 1) + ")");

        // Padding needed to reach maxRaw + 1
        int totalPaddingNeeded = (int) ((maxRaw + 1L) - baselineRaw);

        // Build overrides: distribute padding across nondep entries deterministically.
        // For each nondep in required-order: cap = maxSingleEntry - original.length,
        // pad = min(remaining, max(0, cap)), override with original + pad bytes.
        java.util.Map<String, byte[]> overrides = new java.util.LinkedHashMap<>();
        int remaining = totalPaddingNeeded;
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) continue;
            byte[] original = nondepContent.getOrDefault(req, new byte[1]);
            long capacity = maxSingleEntry - original.length;
            Assertions.assertTrue(capacity >= 0,
                    "Entry '" + req + "' original length (" + original.length
                            + ") must be <= maxSingleEntryBytes (" + maxSingleEntry + ")");
            int padThis = (int) Math.min(remaining, Math.max(0, capacity));
            byte[] padded = new byte[original.length + padThis];
            System.arraycopy(original, 0, padded, 0, original.length);
            overrides.put(req, padded);
            remaining -= padThis;
        }
        Assertions.assertEquals(0, remaining,
                "All padding (" + totalPaddingNeeded + " bytes) must be distributed; "
                        + remaining + " bytes remain");

        // Rebuild with padding overrides
        byte[] paddedJar = builder.buildWithOverrides(overrides);

        // Verify preconditions
        Assertions.assertTrue(isAllStored(paddedJar), "TM18 JAR must be all-STORED");
        Assertions.assertEquals(28, countZipEntries(paddedJar),
                "TM18 JAR must have exactly 28 entries");

        long actualRaw = paddedJar.length;
        Assertions.assertEquals(maxRaw + 1L, actualRaw,
                "TM18: total raw bytes must be exactly maxRawBytes + 1 = " + (maxRaw + 1)
                        + " (got " + actualRaw + ")");

        // Verify every uncompressed entry <= maxSingleEntryBytes
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(paddedJar))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] data = zis.readAllBytes();
                Assertions.assertTrue(data.length <= maxSingleEntry,
                        "TM18: entry '" + entry.getName() + "' data length " + data.length
                                + " must be <= maxSingleEntryBytes (" + maxSingleEntry + ")");
                zis.closeEntry();
            }
        }

        // Build plan and verify rejection
        PackagingPlanParser parser = new PackagingPlanParser();
        String planFp = sha256fp(BASELINE_PLAN_BYTES);
        PackagingPlanParser.ParseResult parseResult = parser.parse(BASELINE_PLAN_BYTES, planFp);
        Assertions.assertTrue(parseResult.isSuccess(), "Plan must parse");
        PackagedPlan plan = parseResult.plan();

        Path jarPath = tempDir.resolve("tm18.jar");
        Files.write(jarPath, paddedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM18: must be rejected");
        Assertions.assertEquals("AK-LIMIT-RAW-BYTES", snapshot.rejectionCode(),
                "TM18: must have AK-LIMIT-RAW-BYTES code");
    }

    // -------------------------------------------------------------------------
    // Shared helper: count ZIP entries in a JAR byte array
    // -------------------------------------------------------------------------

    private int countZipEntries(byte[] jarBytes) throws IOException {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            while (zis.getNextEntry() != null) {
                count++;
                zis.closeEntry();
            }
        }
        return count;
    }
    // -------------------------------------------------------------------------
    // TM-20: dependency entry stored with another dep JAR's content (nested JAR
    // content substituted via addStoredEntry) — all 28 entries STORED, baseline
    // plan derived from tampered JAR — expect AK-NESTED-JAR-SHA256
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM20: dependency entry stored with another dep JAR content")
    void tm20_nested_jar_content() throws Exception {
        // Collect all dependency JAR bytes for potential replacement content
        List<RealVerifierFixtureFactory.DependencyEntry> deps = FACTORY.embeddedDependencies();
        Assertions.assertTrue(deps.size() >= 2, "Need at least 2 dependencies for TM20");

        // Source content: read the LAST dependency JAR as replacement content
        RealVerifierFixtureFactory.DependencyEntry srcDep = deps.get(deps.size() - 1);
        Path srcJarPath = DEP_JARS_DIR.resolve(srcDep.artifactFileName());
        byte[] replacementContent = java.nio.file.Files.readAllBytes(srcJarPath);

        // Target: pick the FIRST dependency entry path to substitute
        RealVerifierFixtureFactory.DependencyEntry targetDep = deps.get(0);
        String targetEntryPath = targetDep.entryPath();

        // Build exact 28 STORED JAR:
        // - dependencies: use addStoredDependency (correct fingerprint in plan)
        // - EXCEPT targetEntryPath: use addStoredEntry with OTHER dep JAR content
        // - non-dependencies: use addStoredEntry with tiny 1-byte content
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                if (req.equals(targetEntryPath)) {
                    // Substitute: add as non-dep entry with another dep JAR's content
                    // (isDependency=false, method=STORED, custom content)
                    builder.addStoredEntry(req, replacementContent);
                } else {
                    builder.addStoredDependency(req);
                }
            } else {
                builder.addStoredEntry(req, new byte[1]);
            }
        }
        byte[] tamperedJar = builder.build();
        Assertions.assertEquals(28, countZipEntries(tamperedJar),
                "TM20 JAR must have exactly 28 entries");
        Assertions.assertTrue(isAllStored(tamperedJar),
                "TM20 JAR must be all-STORED");

        // Authority baseline plan: all 7 dep SHA-256 fingerprints are from
        // embeddedDependencyAuthority; the plan declares the correct fingerprint for
        // targetEntryPath, but the JAR contains another dep's JAR bytes there.
        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult =
                parser.parse(BASELINE_PLAN_BYTES, sha256fp(BASELINE_PLAN_BYTES));
        Assertions.assertTrue(parseResult.isSuccess(), "TM20 plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm20.jar");
        java.nio.file.Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM20: must be rejected");
        Assertions.assertEquals("AK-NESTED-JAR-SHA256", snapshot.rejectionCode(),
                "TM20: must have AK-NESTED-JAR-SHA256 code");
    }

    // -------------------------------------------------------------------------
    // TM-21: mutate first plan embeddedDependencies rawFingerprint last hex bit
    // via Jackson, serialize, parse using sha256fp(mutated) — expect
    // AK-NESTED-JAR-SHA256 when verified against baseline jar
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM21: plan fingerprint mutated (first dep, last hex bit)")
    void tm21_plan_fingerprint_mutated() throws Exception {
        // Parse baseline plan as generic JsonNode to mutate without full binding
        com.fasterxml.jackson.databind.ObjectMapper mutationMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode planNode =
                mutationMapper.readTree(BASELINE_PLAN_BYTES);

        // Navigate to embeddedDependencies[0].rawFingerprint
        com.fasterxml.jackson.databind.JsonNode embDeps =
                planNode.get("embeddedDependencies");
        Assertions.assertFalse(embDeps == null || embDeps.isMissingNode(),
                "embeddedDependencies must be present in plan");
        Assertions.assertTrue(embDeps.isArray() && embDeps.size() > 0,
                "embeddedDependencies must be a non-empty array");

        com.fasterxml.jackson.databind.JsonNode firstDep = embDeps.get(0);
        String originalFp = firstDep.get("rawFingerprint").asText();
        Assertions.assertNotNull(originalFp, "rawFingerprint must not be null");

        // Mutate: flip exactly one bit in the last hex character
        char lastChar = originalFp.charAt(originalFp.length() - 1);
        int nibble = Character.digit(lastChar, 16);
        char mutatedChar = Character.forDigit(nibble ^ 0x1, 16); // flip exactly 1 bit
        String mutatedFp = originalFp.substring(0, originalFp.length() - 1) + mutatedChar;
        Assertions.assertNotEquals(originalFp, mutatedFp,
                "Mutation must change the fingerprint");

        // Apply mutation to JSON tree
        ((ObjectNode) firstDep).put(
                "rawFingerprint", mutatedFp);

        // Reserialize with deterministic factory (same factory as factory uses)
        com.fasterxml.jackson.databind.ObjectMapper planOutMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        byte[] mutatedPlanBytes = planOutMapper.writeValueAsBytes(planNode);

        // Parse with sha256fp of mutated bytes
        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult =
                parser.parse(mutatedPlanBytes, sha256fp(mutatedPlanBytes));
        Assertions.assertTrue(parseResult.isSuccess(),
                "TM21: mutated plan must parse with new fingerprint");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm21.jar");
        java.nio.file.Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM21: must be rejected");
        Assertions.assertEquals("AK-NESTED-JAR-SHA256", snapshot.rejectionCode(),
                "TM21: must have AK-NESTED-JAR-SHA256 code");
    }

    // -------------------------------------------------------------------------
    // TM-22: tamperOmitEntry on first dependency entry, baseline plan —
    // expect AK-ENTRY-MISSING
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM22: dependency entry omitted from JAR")
    void tm22_dependency_entry_omitted() throws Exception {
        // Pick the first dependency entry
        String firstDepPath = FACTORY.embeddedDependencies().get(0).entryPath();

        // Omit it from the baseline JAR
        byte[] tamperedJar = tamperOmitEntry(BASELINE_JAR_BYTES, firstDepPath);

        // Verify it was actually removed
        int entryCount = countZipEntries(tamperedJar);
        Assertions.assertEquals(27, entryCount,
                "TM22: JAR must have exactly 27 entries after omission");

        // Use baseline plan (with all 28 entries including omitted one)
        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult =
                parser.parse(BASELINE_PLAN_BYTES, sha256fp(BASELINE_PLAN_BYTES));
        Assertions.assertTrue(parseResult.isSuccess(), "TM22: baseline plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm22.jar");
        java.nio.file.Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM22: must be rejected");
        Assertions.assertEquals("AK-ENTRY-MISSING", snapshot.rejectionCode(),
                "TM22: must have AK-ENTRY-MISSING code");
    }

    // -------------------------------------------------------------------------
    // TM-23: build all 28 STORED, target nondep content {1,2,3,4}, zero
    // local+central cSize+20 fields only — expect AK-SIZE-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM23: STORED entry with zeroed compressed-size fields")
    void tm23_zeroed_compressed_size() throws Exception {
        byte[] content = new byte[]{1, 2, 3, 4};

        // Find a non-dependency entry
        String nonDepEntry = null;
        for (String req : FACTORY.requiredEntries()) {
            if (!isDependencyEntry(req)) {
                nonDepEntry = req;
                break;
            }
        }
        Assertions.assertNotNull(nonDepEntry, "Need at least one non-dependency entry for TM23");

        // Build exact 28 JAR, all STORED
        RealVerifierFixtureFactory.DeterministicJarBuilder builder = FACTORY.jarBuilder();
        for (String req : FACTORY.requiredEntries()) {
            if (isDependencyEntry(req)) {
                builder.addStoredDependency(req);
            } else {
                builder.addStoredEntry(req, req.equals(nonDepEntry) ? content : new byte[1]);
            }
        }
        byte[] baseJar = builder.build();
        Assertions.assertEquals(28, countZipEntries(baseJar), "TM23: must have 28 entries");
        Assertions.assertTrue(isAllStored(baseJar), "TM23: must be all-STORED");

        // Find and modify: zero cSize at local+20 and central+20 (both little-endian 4-byte)
        byte[] tamperedJar = baseJar.clone();
        int[] entryInfo = findCentralEntry(tamperedJar, nonDepEntry);
        int localPos = entryInfo[1];
        int centralPos = entryInfo[0];

        // Zero out cSize at local header offset + 18 (4 bytes) — actually local+18 to local+21
        // and central header offset + 20 (4 bytes) — central+20 to central+23
        // "cSize+18" and "cSize+20" per instructions: byte offsets from record start
        Assertions.assertTrue(localPos >= 0, "Local header not found for " + nonDepEntry);
        Assertions.assertTrue(centralPos > 0, "Central entry not found for " + nonDepEntry);

        // Zero 4 bytes at local + 18 (cSize field in local header)
        for (int i = 0; i < 4; i++) tamperedJar[localPos + 18 + i] = 0;
        // Zero 4 bytes at central + 20 (cSize field in central directory)
        for (int i = 0; i < 4; i++) tamperedJar[centralPos + 20 + i] = 0;

        // Plan from baseline JAR
        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult =
                parser.parse(BASELINE_PLAN_BYTES, sha256fp(BASELINE_PLAN_BYTES));
        Assertions.assertTrue(parseResult.isSuccess(), "TM23: plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm23.jar");
        java.nio.file.Files.write(jarPath, tamperedJar);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM23: must be rejected");
        Assertions.assertEquals("AK-SIZE-MISMATCH", snapshot.rejectionCode(),
                "TM23: must have AK-SIZE-MISMATCH code");
    }

    // -------------------------------------------------------------------------
    // TM-24: mutate last dep rawFingerprint 1 bit, reserialize/rehash,
    // baseline jar — expect AK-NESTED-JAR-SHA256
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TM24: last dep plan fingerprint mutated 1 bit")
    void tm24_plan_entry_path_mutated() throws Exception {
        // Parse plan JSON as mutable tree
        com.fasterxml.jackson.databind.ObjectMapper mutationMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode planNode =
                mutationMapper.readTree(BASELINE_PLAN_BYTES);

        com.fasterxml.jackson.databind.JsonNode embDeps = planNode.get("embeddedDependencies");
        Assertions.assertFalse(embDeps == null || embDeps.isMissingNode(),
                "embeddedDependencies must be present");
        int lastIdx = embDeps.size() - 1;
        com.fasterxml.jackson.databind.JsonNode lastDep = embDeps.get(lastIdx);

        String originalFp = lastDep.get("rawFingerprint").asText();
        Assertions.assertNotNull(originalFp, "rawFingerprint must not be null");

        // Mutate exactly one bit: flip LSB of last hex character
        char lastChar = originalFp.charAt(originalFp.length() - 1);
        int nibble = Character.digit(lastChar, 16);
        char mutatedChar = Character.forDigit(nibble ^ 0x1, 16);
        String mutatedFp = originalFp.substring(0, originalFp.length() - 1) + mutatedChar;
        Assertions.assertNotEquals(originalFp, mutatedFp,
                "Mutation must change the fingerprint");

        // Apply mutation
        ((ObjectNode) lastDep).put("rawFingerprint", mutatedFp);

        // Reserialize
        com.fasterxml.jackson.databind.ObjectMapper planOutMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        byte[] mutatedPlanBytes = planOutMapper.writeValueAsBytes(planNode);

        // Parse with sha256fp of mutated bytes
        PackagingPlanParser parser = new PackagingPlanParser();
        PackagingPlanParser.ParseResult parseResult =
                parser.parse(mutatedPlanBytes, sha256fp(mutatedPlanBytes));
        Assertions.assertTrue(parseResult.isSuccess(),
                "TM24: mutated plan must parse");

        PackagedPlan plan = parseResult.plan();
        Path jarPath = tempDir.resolve("tm24.jar");
        java.nio.file.Files.write(jarPath, BASELINE_JAR_BYTES);

        ArchiveKernel kernel = new ArchiveKernel();
        ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

        Assertions.assertTrue(snapshot.rejected(), "TM24: must be rejected");
        Assertions.assertEquals("AK-NESTED-JAR-SHA256", snapshot.rejectionCode(),
                "TM24: must have AK-NESTED-JAR-SHA256 code");
    }

    // -------------------------------------------------------------------------
    // @AfterAll: reflection-based validation of test inventory
    // -------------------------------------------------------------------------

    @AfterAll
    static void validateTestInventory() {
        java.lang.reflect.Method[] methods =
                VerifierFixtureTamperAcceptanceTest.class.getDeclaredMethods();
        java.util.List<java.lang.reflect.Method> testMethods = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : methods) {
            if (m.isAnnotationPresent(org.junit.jupiter.api.Test.class)) {
                testMethods.add(m);
            }
        }
        Assertions.assertEquals(25, testMethods.size(),
                "Must have exactly 25 @Test methods (TM01..TM25)");

        java.util.Set<String> expected = new java.util.LinkedHashSet<>();
        for (int i = 1; i <= 25; i++) {
            expected.add("TM" + String.format("%02d", i) + ":");
        }
        java.util.Set<String> actual = new java.util.LinkedHashSet<>();
        for (java.lang.reflect.Method m : testMethods) {
            DisplayName dm = m.getAnnotation(DisplayName.class);
            if (dm != null) {
                actual.add(dm.value().substring(0, 5)); // "TM01:"
            }
        }
        Assertions.assertEquals(expected, actual,
                "DisplayNames must cover TM01..TM25 exactly once each");
    }

}
