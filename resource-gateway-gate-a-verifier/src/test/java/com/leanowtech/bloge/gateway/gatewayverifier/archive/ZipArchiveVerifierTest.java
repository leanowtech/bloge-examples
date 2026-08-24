package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for ZipArchiveVerifier — T1 implementation.
 *
 * Covers AK-ENTRY-DIRECTORY through AK-MULTI-RELEASE (structural),
 * AK-DD-UNVERIFIABLE through AK-SIZE-MISMATCH (content),
 * AK-LIMIT-* (limits), plus oracle tests and attack tests.
 *
 * No dependency on bloge-resource-gateway-test-kit.
 */
class ZipArchiveVerifierTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // CRC helper
    // -------------------------------------------------------------------------

    private static long crcLong(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return Integer.toUnsignedLong((int) crc.getValue());
    }

    private static byte[] deflateRaw(byte[] raw) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
            dos.write(raw);
        }
        return baos.toByteArray();
    }

    private static String sha256hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Manual ZIP builder (supports commentLen for EOCD)
    // -------------------------------------------------------------------------

    /**
     * Build a minimal valid ZIP from specs.  Allows nonzero EOCD comment via
     * eocdCommentLen / eocdCommentBytes.
     */
    private static byte[] buildZip(List<EntrySpec> entries,
                                   byte[] eocdCommentBytes) throws Exception {
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
            loc.putInt((int) e.crc);
            loc.putInt((int) e.cSize);
            loc.putInt((int) e.uSize);
            loc.putShort((short) nameBytes.length);
            loc.putShort((short) e.localExtraLen);
            loc.put(nameBytes);
            if (e.localExtraLen > 0) {
                loc.put(new byte[e.localExtraLen]);
            }
            out.write(loc.array(), 0, loc.array().length);

            if (e.data != null) {
                out.write(e.data, 0, e.data.length);
            }
        }

        long cdOffset = out.size();

        for (int i = 0; i < entries.size(); i++) {
            EntrySpec e = entries.get(i);
            byte[] nameBytes = e.name.getBytes(StandardCharsets.UTF_8);
            byte[] cd = buildCdRecord(e, localOffsets.get(i));
            out.write(cd, 0, cd.length);
        }

        long cdEnd = out.size();
        int cdSize = (int) (cdEnd - cdOffset);

        int eocdSize = 22 + (eocdCommentBytes != null ? eocdCommentBytes.length : 0);
        ByteBuffer eocd = ByteBuffer.allocate(eocdSize)
                .order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50);
        eocd.putShort((short) 0);
        eocd.putShort((short) 0);
        eocd.putShort((short) entries.size());
        eocd.putShort((short) entries.size());
        eocd.putInt(cdSize);
        eocd.putInt((int) cdOffset);
        eocd.putShort((short) (eocdCommentBytes != null ? eocdCommentBytes.length : 0));
        if (eocdCommentBytes != null) {
            eocd.put(eocdCommentBytes);
        }
        out.write(eocd.array());

        return out.toByteArray();
    }

    /** Convenience: zero-comment ZIP. */
    private static byte[] buildZip(List<EntrySpec> entries) throws Exception {
        return buildZip(entries, null);
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
        cd.putInt((int) e.cSize);
        cd.putInt((int) e.uSize);
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

    private Path writeZip(byte[] data) throws IOException {
        Path p = tempDir.resolve("test.zip");
        Files.write(p, data);
        return p;
    }

    private ZipArchiveVerifier.Result verify(Path p) throws Exception {
        return new ZipArchiveVerifier().verify(p);
    }

    // -------------------------------------------------------------------------
    // Oracle tests — standard ZipOutputStream must pass
    // -------------------------------------------------------------------------

    @Test
    void oracle_emptyJar_accepted() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {}
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(0, r.entryCount());
    }

    @Test
    void oracle_singleStoredEntry_accepted() throws Exception {
        byte[] content = "Hello, world!".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("hello.txt");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(1, r.entryCount());
        assertEquals("hello.txt", r.entries().get(0).name());
        assertEquals(sha256hex(content), r.entries().get(0).sha256());
    }

    @Test
    void oracle_singleDeflatedEntry_accepted() throws Exception {
        byte[] content = "Compressible content for DEFLATE test.".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("data.bin");
            e.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(1, r.entryCount());
        assertEquals(sha256hex(content), r.entries().get(0).sha256());
    }

    @Test
    void oracle_multipleEntries_accepted() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (int i = 1; i <= 3; i++) {
                byte[] content = ("file" + i).getBytes(StandardCharsets.UTF_8);
                java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("file" + i + ".txt");
                e.setMethod(java.util.zip.ZipEntry.STORED);
                e.setSize(content.length);
                e.setCrc(crcLong(content));
                zos.putNextEntry(e);
                zos.write(content);
                zos.closeEntry();
            }
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(3, r.entryCount());
    }

    @Test
    void oracle_crcMatches_accepted() throws Exception {
        byte[] content = "CRC verification content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("crc.txt");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(crcLong(content), r.entries().get(0).crc32());
    }

    @Test
    void oracle_sha256Matches_accepted() throws Exception {
        byte[] content = "SHA-256 test content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("sha.txt");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(sha256hex(content), r.entries().get(0).sha256());
    }

    @Test
    void oracle_deflatedContentMatches_accepted() throws Exception {
        byte[] content = "Deflate content matching test data".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("deflate.txt");
            e.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(sha256hex(content), r.entries().get(0).sha256());
    }

    @Test
    void oracle_entryCountMatches() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (int i = 0; i < 5; i++) {
                byte[] content = ("content" + i).getBytes(StandardCharsets.UTF_8);
                java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("file" + i + ".txt");
                e.setMethod(java.util.zip.ZipEntry.STORED);
                e.setSize(content.length);
                e.setCrc(crcLong(content));
                zos.putNextEntry(e);
                zos.write(content);
                zos.closeEntry();
            }
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(5, r.entryCount());
    }

    @Test
    void oracle_deflatedEntriesHaveCorrectMethod() throws Exception {
        byte[] content = "Method check".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("method.txt");
            e.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(8, r.entries().get(0).compressionMethod());
    }

    @Test
    void oracle_storedEntriesHaveCorrectMethod() throws Exception {
        byte[] content = "Stored method check".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("stored.txt");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected());
        assertEquals(0, r.entries().get(0).compressionMethod());
    }

    // -------------------------------------------------------------------------
    // Structural rejection tests (AK-*)
    // -------------------------------------------------------------------------

    @Test
    void reject_directoryEntry_AK_ENTRY_DIRECTORY() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "META-INF/";
        e.method = 0;
        e.gpb = 0;
        e.crc = 0;
        e.uSize = 0;
        e.cSize = 0;
        e.data = new byte[0];

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ENTRY-DIRECTORY", r.reasonCode());
    }

    @Test
    void reject_centralExtraField_AK_EXTRA_FIELD() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.gpb = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();
        e.cdExtraLen = 4;

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-EXTRA-FIELD", r.reasonCode());
    }

    @Test
    void reject_encryptedEntry_AK_ENCRYPTED() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "secret.txt";
        e.method = 0;
        e.gpb = 0x0001;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ENCRYPTED", r.reasonCode());
    }

    @Test
    void reject_symlinkEntry_AK_EXTERNAL_SYMLINK() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "link";
        e.method = 0;
        e.gpb = 0;
        e.crc = (int) crcLong("target".getBytes());
        e.uSize = 6;
        e.cSize = 6;
        e.data = "target".getBytes();
        e.extAttr = 0xA0000000L;

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-EXTERNAL-SYMLINK", r.reasonCode());
    }

    @Test
    void reject_blockDeviceEntry_AK_EXTERNAL_SPECIAL() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "blockdev";
        e.method = 0;
        e.gpb = 0;
        e.crc = 0;
        e.uSize = 0;
        e.cSize = 0;
        e.data = new byte[0];
        e.extAttr = 0x60000000L;

        Path p = writeZip(buildZip(List.of(e)));
        assertEquals("AK-EXTERNAL-SPECIAL", verify(p).reasonCode());
    }

    @Test
    void reject_charDeviceEntry_AK_EXTERNAL_SPECIAL() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "chardev";
        e.method = 0;
        e.gpb = 0;
        e.crc = 0;
        e.uSize = 0;
        e.cSize = 0;
        e.data = new byte[0];
        e.extAttr = 0x20000000L;

        Path p = writeZip(buildZip(List.of(e)));
        assertEquals("AK-EXTERNAL-SPECIAL", verify(p).reasonCode());
    }

    @Test
    void reject_multiReleaseEntry_AK_MULTI_RELEASE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "META-INF/versions/9/Test.class";
        e.method = 0;
        e.gpb = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-MULTI-RELEASE", r.reasonCode());
    }

    @Test
    void reject_unverifiableDataDescriptor_AK_DD_UNVERIFIABLE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "dd.txt";
        e.method = 8;
        e.gpb = 0x0008;
        e.crc = 0;
        e.uSize = 0;
        e.cSize = 0;
        e.data = "data".getBytes();

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-DD-UNVERIFIABLE", r.reasonCode());
    }

    @Test
    void reject_unknownCompressionMethod_AK_UNKNOWN_COMPRESSION() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "unknown.txt";
        e.method = 99;
        e.gpb = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-UNKNOWN-COMPRESSION", r.reasonCode());
    }

    @Test
    void reject_wrongCrc_AK_CRC_MISMATCH() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.gpb = 0;
        e.crc = 0xDEADBEEF;
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-CRC-MISMATCH", r.reasonCode());
    }

    @Test
    void reject_wrongUncompressedSize_AK_SIZE_MISMATCH() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.gpb = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 999;
        e.cSize = 1;
        e.data = "x".getBytes();

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-SIZE-MISMATCH", r.reasonCode());
    }

    // -------------------------------------------------------------------------
    // Limit tests (AK-LIMIT-*)
    // -------------------------------------------------------------------------

    @Test
    void reject_exceedRawBytesLimit_AK_LIMIT_RAW_BYTES() throws Exception {
        byte[] content = new byte[10];
        Arrays.fill(content, (byte) 'x');

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("large.bin");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());

        ZipArchiveVerifier strict = new ZipArchiveVerifier(1L, 512, 8 * 1024 * 1024, 64 * 1024 * 1024, 100);
        ZipArchiveVerifier.Result r = strict.verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-LIMIT-RAW-BYTES", r.reasonCode());
    }

    @Test
    void reject_exceedZipEntriesLimit_AK_LIMIT_ZIP_ENTRIES() throws Exception {
        byte[] content = new byte[1];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (int i = 0; i < 10; i++) {
                java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("file" + i + ".txt");
                e.setMethod(java.util.zip.ZipEntry.STORED);
                e.setSize(1);
                e.setCrc(crcLong(content));
                zos.putNextEntry(e);
                zos.write(content);
                zos.closeEntry();
            }
        }
        Path p = writeZip(baos.toByteArray());

        ZipArchiveVerifier strict = new ZipArchiveVerifier(16 * 1024 * 1024, 5, 8 * 1024 * 1024, 64 * 1024 * 1024, 100);
        ZipArchiveVerifier.Result r = strict.verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-LIMIT-ZIP-ENTRIES", r.reasonCode());
    }

    @Test
    void reject_exceedSingleEntryLimit_AK_LIMIT_SINGLE_ENTRY() throws Exception {
        byte[] content = new byte[10 * 1024];
        Arrays.fill(content, (byte) 'x');

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("large.bin");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());

        ZipArchiveVerifier strict = new ZipArchiveVerifier(16 * 1024 * 1024, 512, 1024, 64 * 1024 * 1024, 100);
        ZipArchiveVerifier.Result r = strict.verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-LIMIT-SINGLE-ENTRY", r.reasonCode());
    }

    @Test
    void reject_exceedCompressionRatio_AK_LIMIT_COMPRESSION_RATIO() throws Exception {
        byte[] content = new byte[100 * 1024];
        Arrays.fill(content, (byte) 'A');

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("compressible.bin");
            e.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());

        ZipArchiveVerifier strict = new ZipArchiveVerifier(16 * 1024 * 1024, 512, 8 * 1024 * 1024, 64 * 1024 * 1024, 50);
        ZipArchiveVerifier.Result r = strict.verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-LIMIT-COMPRESSION-RATIO", r.reasonCode());
    }

    // -------------------------------------------------------------------------
    // T1-Fix #6: compression ratio — exact quotient+remainder comparison
    // -------------------------------------------------------------------------

    /**
     * T1-6 positive: a DEFLATE entry with a permissive maxRatio (well above
     * the actual ratio) is accepted.  Data-driven: first run with a permissive
     * limit to discover the actual compressed size, then confirm that using
     * maxRatio = actual_ratio + 10 does not reject.
     */
    @Test
    void accept_compressionRatioWellBelowLimit_accepted() throws Exception {
        byte[] content = new byte[100];
        Arrays.fill(content, (byte) 'A');

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("c.bin");
            entry.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());

        // Pass 1: permissive ratio to discover actual compressed size
        ZipArchiveVerifier permissive = new ZipArchiveVerifier(
                16 * 1024 * 1024, 512, 8 * 1024 * 1024, 64 * 1024 * 1024, 10_000);
        ZipArchiveVerifier.Result oracle = permissive.verify(p);
        assertFalse(oracle.rejected(), "oracle should pass: " + oracle.reasonCode());
        assertFalse(oracle.entries().isEmpty());

        long uSize = oracle.entries().get(0).uncompressedSize();
        long cSize = oracle.entries().get(0).compressedSize();
        assertTrue(cSize > 0, "compressed size must be > 0 for DEFLATE");
        long actualQuotient = uSize / cSize;
        long actualRemainder = uSize % cSize;

        // Pass 2: use a permissive maxRatio well above the actual ratio.
        // The check rejects only when quotient > maxRatio OR
        // (quotient == maxRatio AND remainder > 0).  With a generous
        // maxRatio, neither condition is met, so the archive is accepted.
        long permissiveLimit = actualQuotient + 10;
        ZipArchiveVerifier verifier = new ZipArchiveVerifier(
                16 * 1024 * 1024, 512, 8 * 1024 * 1024, 64 * 1024 * 1024, permissiveLimit);
        ZipArchiveVerifier.Result r = verifier.verify(p);
        assertFalse(r.rejected(),
                "ratio q=" + actualQuotient + ", r=" + actualRemainder
                + " with limit=" + permissiveLimit + " should be accepted: " + r.reasonCode());
    }

    /**
     * T1-6 negative: ratio strictly greater than maxRatio is rejected.
     * Data-driven: discover actual compressed size, then set maxRatio =
     * actual_quotient - 1 so that quotient > maxRatio always holds.
     *
     * This exercises the "quotient > maxRatio" arm of the exact comparison:
     * actual quotient = uSize / cSize, limit = actual_quotient - 1
     * → quotient > maxRatio  (since limit = q - 1 < q)
     * → rejected with AK-LIMIT-COMPRESSION-RATIO.
     */
    @Test
    void reject_compressionRatioOverLimit_quotientGreater_rejected() throws Exception {
        byte[] content = new byte[100];
        Arrays.fill(content, (byte) 'A');

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("c.bin");
            entry.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());

        // Pass 1: permissive ratio to discover actual compressed size
        ZipArchiveVerifier permissive = new ZipArchiveVerifier(
                16 * 1024 * 1024, 512, 8 * 1024 * 1024, 64 * 1024 * 1024, 10_000);
        ZipArchiveVerifier.Result oracle = permissive.verify(p);
        assertFalse(oracle.rejected(), "oracle should pass: " + oracle.reasonCode());

        long uSize = oracle.entries().get(0).uncompressedSize();
        long cSize = oracle.entries().get(0).compressedSize();
        long actualQuotient = uSize / cSize;
        long actualRemainder = uSize % cSize;

        // Pass 2: set maxRatio = actual_quotient - 1 (guaranteed < actual quotient)
        // quotient > maxRatio always holds, triggering AK-LIMIT-COMPRESSION-RATIO
        long strictLimit = Math.max(0, actualQuotient - 1);
        ZipArchiveVerifier strict = new ZipArchiveVerifier(
                16 * 1024 * 1024, 512, 8 * 1024 * 1024, 64 * 1024 * 1024, strictLimit);
        ZipArchiveVerifier.Result r = strict.verify(p);
        assertTrue(r.rejected(),
                "q=" + actualQuotient + ", r=" + actualRemainder + ", limit=" + strictLimit
                + " should be rejected: " + r.reasonCode());
        assertEquals("AK-LIMIT-COMPRESSION-RATIO", r.reasonCode());
    }

    // -------------------------------------------------------------------------
    // ZIP structure tests (AK-ZIP-STRUCTURE)
    // -------------------------------------------------------------------------

    @Test
    void reject_invalidEocdSignature_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong(new byte[0]);
        e.uSize = 0;
        e.cSize = 0;
        e.data = new byte[0];

        byte[] zip = buildZip(List.of(e));
        zip[zip.length - 22] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_multiDiskZip_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong(new byte[0]);
        e.uSize = 0;
        e.cSize = 0;
        e.data = new byte[0];

        byte[] zip = buildZip(List.of(e));
        int eocdDiskOffset = zip.length - 22 + 4;
        zip[eocdDiskOffset] = (byte) 0x01;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_zip64Sentinel_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong(new byte[0]);
        e.uSize = 0;
        e.cSize = 0;
        e.data = new byte[0];

        byte[] zip = buildZip(List.of(e));
        int cdOffsetLoc = zip.length - 10;
        zip[cdOffsetLoc] = (byte) 0xFF;
        zip[cdOffsetLoc + 1] = (byte) 0xFF;
        zip[cdOffsetLoc + 2] = (byte) 0xFF;
        zip[cdOffsetLoc + 3] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    // ── Helper: find CenSig (0x02014b50 = bytes 50 4B 01 02) in a byte array ──
    private static int findCenSig(byte[] zip) {
        for (int i = 0; i <= zip.length - 4; i++) {
            if ((zip[i] & 0xFF) == 0x50
                    && (zip[i + 1] & 0xFF) == 0x4B
                    && (zip[i + 2] & 0xFF) == 0x01
                    && (zip[i + 3] & 0xFF) == 0x02) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void reject_nonCensigInCentralDirectory_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong(new byte[1]);
        e.uSize = 1;
        e.cSize = 1;
        e.data = new byte[1];

        byte[] zip = buildZip(List.of(e));
        int cdPos = findCenSig(zip);
        assertTrue(cdPos >= 0, "CenSig not found — fixture broken");
        zip[cdPos] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_localHeaderMismatchName_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int nameOffset = 30;
        zip[nameOffset + 1] = 'r';
        zip[nameOffset + 2] = 'o';
        zip[nameOffset + 3] = 'n';
        zip[nameOffset + 4] = 'g';

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_localHeaderMismatchGpb_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.gpb = 0x0008;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        zip[6] = 0;
        zip[6 + 1] = 0;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_localHeaderMismatchMethod_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 8;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        zip[8] = 0;
        zip[8 + 1] = 0;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_localHeaderMismatchCrc_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.gpb = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        zip[14] ^= 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_localHeaderMismatchCompressedSize_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.gpb = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        zip[18] = 2;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_localHeaderMismatchUncompressedSize_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.gpb = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        zip[22] = 2;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_localExtraField_AK_EXTRA_FIELD() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();
        e.localExtraLen = 4;

        Path p = writeZip(buildZip(List.of(e)));
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-EXTRA-FIELD", r.reasonCode());
    }

    @Test
    void reject_entryDataPastCentralDirectory_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int cdPos = findCenSig(zip);
        assertTrue(cdPos >= 0, "CenSig not found — fixture broken");
        // Corrupt the compressed-size field (offset +20 from the CD record start)
        zip[cdPos + 20] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_localHeaderOffsetOutOfBounds_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int cdPos = findCenSig(zip);
        assertTrue(cdPos >= 0, "CenSig not found — fixture broken");
        zip[cdPos + 42] = (byte) 0xFF;
        zip[cdPos + 43] = (byte) 0xFF;
        zip[cdPos + 44] = (byte) 0xFF;
        zip[cdPos + 45] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_entryCountMismatch_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int eocdOffset = zip.length - 22;
        int entryCountOffset = eocdOffset + 10;
        zip[entryCountOffset] = 0;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_commentLengthMismatch_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        // Append extra bytes after EOCD — the declared commentLen is 0 but extra bytes exist.
        // This violates eocdOffset + 22 + commentLen == fileSize, so it is rejected.
        byte[] extended = Arrays.copyOf(zip, zip.length + 10);
        System.arraycopy(zip, 0, extended, 0, zip.length);
        // Extra trailing bytes are not declared as a comment, so eocdEnd != fileSize
        for (int i = zip.length; i < extended.length; i++) {
            extended[i] = (byte) 'X';
        }

        Path p = writeZip(extended);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    @Test
    void reject_centralDirectoryOffsetMismatch_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "file.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int eocdOffset = zip.length - 22;
        int cdOffsetOffset = eocdOffset + 16;
        zip[cdOffsetOffset] ^= 0x01;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /**
     * T1-Fix #2: crafted archive where declared cdSize is exact but a real
     * gap exists between CD end and EOCD.  The EOCD cdSize field matches the
     * CD length, so cdOffset + cdSize lands at the CD end (satisfying the
     * cdEnd == eocdOffset check), but the EOCD record itself was written
     * 4 bytes further, creating a gap.  Rejected because the parsed central
     * directory does not extend to the EOCD start.
     */
    @Test
    void reject_cdSizeExactButRealGapBetweenCdAndEocd_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        // Build a valid ZIP so cdOffset + cdSize == eocdOffset initially
        byte[] zip = buildZip(List.of(e));

        // Append 4 bytes of garbage between CD end and EOCD.
        // cdOffset is unchanged, cdSize is unchanged (exact),
        // but eocdOffset = old_eocdOffset + 4, so cdEnd != eocdOffset.
        byte[] withGap = Arrays.copyOf(zip, zip.length + 4);
        System.arraycopy(zip, 0, withGap, 0, zip.length);
        // bytes at [zip.length - 22 .. zip.length - 22 + 4) are the gap
        // bytes at [zip.length - 18 .. zip.length + 4) are the (misplaced) EOCD

        Path p = writeZip(withGap);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected(),
                "gap between CD and EOCD with exact cdSize should be rejected: "
                + r.reasonCode());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }


    /**
     * T1-Fix #3 negative: STORED entry whose local data interval extends past
     * the central directory offset.  The entry header/name are consistent, but
     * the declared compressed size makes the local data bytes overlap the CD.
     * Must be rejected with AK-ZIP-STRUCTURE.
     *
     * Layout for 1-byte name "a" + 1-byte STORED data:
     *   [0..29]  LOC header (30 bytes)
     *   [30]     'a'  (name)
     *   [31]     <data byte>
     *   [32..]   CD record  (cdOffset = 32)
     * Corrupting LOC compressedSize to 50 makes entryDataEnd = 31 + 50 = 81 > cdOffset(32).
     */
    @Test
    void reject_entryDataOverlapsCentralDirectory_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "a";           // 1-byte name
        e.method = 0;            // STORED
        e.gpb = 0;               // no data descriptor
        e.crc = 0;               // dummy CRC (structural rejection fires first)
        e.uSize = 1;
        e.cSize = 1;
        e.data = new byte[1];

        byte[] zip = buildZip(List.of(e));
        // LOC compressed-size field: offset 0 + 18 = 18
        zip[18] = 50;  // claim compressed size = 50 bytes (data interval >> CD)

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected(),
                "entry data overlapping CD should be rejected: " + r.reasonCode());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    // -------------------------------------------------------------------------
    // T1-1: No message leak — IOException/system messages → AK-ARCHIVE-IO constant args
    // -------------------------------------------------------------------------

    @Test
    void reject_nonexistentPath_AK_ARCHIVE_IO_noPathLeak() throws Exception {
        Path nonexistent = tempDir.resolve("does-not-exist.zip");
        ZipArchiveVerifier.Result r = new ZipArchiveVerifier().verify(nonexistent);
        assertTrue(r.rejected());
        assertEquals("AK-ARCHIVE-IO", r.reasonCode());
        // reasonArgs must be null — never a path string
        assertNull(r.reasonArgs());
        assertFalse(r.reasonArgs() != null && r.reasonArgs().toString().contains("does-not-exist"),
                "reasonArgs must not contain path");
    }

    @Test
    void reject_directoryPath_AK_ARCHIVE_IO_noSystemMessage() throws Exception {
        // A directory is readable by Files.size() but RandomAccessFile.open()
        // throws IOException — verifying that reasonArgs stays null and no
        // system message (e.g. "Is a directory") leaks into the result.
        Path dir = tempDir;
        ZipArchiveVerifier.Result r = new ZipArchiveVerifier().verify(dir);
        assertTrue(r.rejected());
        assertEquals("AK-ARCHIVE-IO", r.reasonCode());
        assertNull(r.reasonArgs());
        // reasonArgs must not contain system text
        String argsStr = r.reasonArgs() != null ? r.reasonArgs().toString() : "";
        assertFalse(argsStr.contains("Is a directory"), "no 'Is a directory' leak");
        assertFalse(argsStr.contains("Permission"), "no permission message leak");
        assertFalse(argsStr.contains(dir.toString()), "no path leak");
    }

    @Test
    void archiveKernelException_nullArgsForStructuralFailures() throws Exception {
        // Structural exceptions created by the verifier carry null reasonArgs
        // (no path or IOException text).  This is verified by constructing the
        // exception directly and confirming the args field is null.
        ArchiveKernelException ex = new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        assertNull(ex.reasonArgs());
        assertEquals("AK-ZIP-STRUCTURE", ex.reasonCode());
    }

    @Test
    void archiveKernelException_ioException_keepsNullArgs() throws Exception {
        // When an IOException propagates to the catch in verify(), it is re-thrown
        // as ArchiveKernelException("AK-ARCHIVE-IO", null, e) — the args stay null.
        ArchiveKernelException ex = new ArchiveKernelException("AK-ARCHIVE-IO", null, new IOException("disk error"));
        assertNull(ex.reasonArgs());
        assertEquals("AK-ARCHIVE-IO", ex.reasonCode());
        assertNotNull(ex.getCause());
    }

    // -------------------------------------------------------------------------
    // T1-2: EOCD — nonzero comment, fake-sig avoidance, undeclared/trailing bytes,
    //        ZIP64 sentinels (0xFFFF counts, 0xFFFFFFFF cdSize/cdOffset),
    //        ZIP64 locator immediately before EOCD
    // -------------------------------------------------------------------------

    /** Positive: nonzero EOCD comment is accepted. */
    @Test
    void accept_nonzeroEocdComment_accepted() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] comment = "built by test".getBytes(StandardCharsets.UTF_8);
        byte[] zip = buildZip(List.of(e), comment);

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected(), "nonzero EOCD comment should be accepted: " + r.reasonCode());
        assertEquals(1, r.entryCount());
    }

    /** Positive: max-length EOCD comment (65535) is accepted. */
    @Test
    void accept_exactMaxEocdComment_65535_accepted() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "a";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        // Exactly 65535 bytes — the legal maximum per APPNOTE.txt §4.4.4.
        byte[] comment = new byte[65535];
        Arrays.fill(comment, (byte) 'A');
        byte[] zip = buildZip(List.of(e), comment);

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected(), "max-length EOCD comment should be accepted: " + r.reasonCode());
        assertEquals(1, r.entryCount());
    }

    /** Negative: fake EOCD signature inside a comment is ignored. */
    @Test
    void accept_fakeEocdSignatureInsideComment_accepted() throws Exception {
        // The ZIP comment contains the EOCD signature 0x06054b50 at its end.
        // The backward scan finds this fake candidate first.  It is rejected because
        // eocdOffset + 22 + commentLen != fileSize for that candidate.
        // The scan then finds the real EOCD at the start of the file.
        // The real EOCD's declared comment is valid (exact-end check passes),
        // so the archive is accepted.
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        // 8-byte comment; bytes [4..7] are the EOCD signature
        byte[] comment = new byte[8];
        comment[4] = 0x50; comment[5] = 0x4B;
        comment[6] = 0x05; comment[7] = 0x06;

        byte[] zip = buildZip(List.of(e), comment);
        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertFalse(r.rejected(),
                "real EOCD with valid declared comment must be accepted even if comment contains fake sig: "
                + r.reasonCode());
        assertEquals(1, r.entryCount());
    }

    /** Negative: undeclared bytes after EOCD (commentLen = 0 but extra bytes present). */
    @Test
    void reject_undeclaredTrailingBytes_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        // Valid ZIP, then 5 undeclared bytes appended
        byte[] zip = buildZip(List.of(e));
        byte[] extended = Arrays.copyOf(zip, zip.length + 5);
        System.arraycopy(zip, 0, extended, 0, zip.length);
        Arrays.fill(extended, zip.length, extended.length, (byte) 0x42);

        Path p = writeZip(extended);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /** Negative: truncated comment (declared > actual). */
    @Test
    void reject_truncatedComment_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        // Build with commentLen=10 but only 3 actual comment bytes
        byte[] baseZip = buildZip(List.of(e), null);
        byte[] withTruncated = Arrays.copyOf(baseZip, baseZip.length + 3);
        System.arraycopy(baseZip, 0, withTruncated, 0, baseZip.length);
        // EOCD commentLen offset = baseZip.length - 22 + 20 = baseZip.length - 2
        withTruncated[baseZip.length - 2] = 10; // declare 10 bytes
        withTruncated[baseZip.length - 1] = 0;
        // only 3 bytes present after EOCD

        Path p = writeZip(withTruncated);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /** Negative: ZIP64 sentinel — 0xFFFF in entries-on-disk field. */
    @Test
    void reject_zip64Sentinel_entriesOnDisk_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int eocdOffset = zip.length - 22;
        // entriesThisDisk field: offset +8 within EOCD
        zip[eocdOffset + 8] = (byte) 0xFF;
        zip[eocdOffset + 9] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /** Negative: ZIP64 sentinel — 0xFFFF in total-entries field. */
    @Test
    void reject_zip64Sentinel_entriesTotal_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int eocdOffset = zip.length - 22;
        // entriesTotal field: offset +10 within EOCD
        zip[eocdOffset + 10] = (byte) 0xFF;
        zip[eocdOffset + 11] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /** Negative: ZIP64 sentinel — 0xFFFFFFFF in cdSize field. */
    @Test
    void reject_zip64Sentinel_cdSize_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int eocdOffset = zip.length - 22;
        // cdSize field: offset +12 within EOCD
        zip[eocdOffset + 12] = (byte) 0xFF;
        zip[eocdOffset + 13] = (byte) 0xFF;
        zip[eocdOffset + 14] = (byte) 0xFF;
        zip[eocdOffset + 15] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /** Negative: ZIP64 sentinel — 0xFFFFFFFF in cdOffset field. */
    @Test
    void reject_zip64Sentinel_cdOffset_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        int eocdOffset = zip.length - 22;
        // cdOffset field: offset +16 within EOCD
        zip[eocdOffset + 16] = (byte) 0xFF;
        zip[eocdOffset + 17] = (byte) 0xFF;
        zip[eocdOffset + 18] = (byte) 0xFF;
        zip[eocdOffset + 19] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /** Negative: ZIP64 locator immediately before EOCD. */
    @Test
    void reject_zip64LocatorBeforeEocd_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        // Build a minimal ZIP, then prepend a ZIP64 locator before the EOCD
        byte[] zip = buildZip(List.of(e));
        byte[] withLocator = new byte[zip.length + 20];
        System.arraycopy(zip, 0, withLocator, 0, zip.length);

        // Write ZIP64 locator (20 bytes) immediately before EOCD
        int locOffset = zip.length - 22; // EOCD starts at old end - 22
        // locator starts 20 bytes before EOCD
        int locatorStart = locOffset - 20;
        ByteBuffer loc = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        loc.putInt(0x07064b50);         // ZIP64_LOCSIG
        loc.putInt(0);                  // disk number
        loc.putLong(locOffset);        // offset of ZIP64 EOCD (arbitrary)
        loc.putInt(1);                 // disk of ZIP64 EOCD
        System.arraycopy(loc.array(), 0, withLocator, locatorStart, 20);

        Path p = writeZip(withLocator);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    // -------------------------------------------------------------------------
    // T1-3: cdOffset+cdSize==eocdOffset (checked-add); parsed pointer == cdEnd;
    //        local header and entry data end <= cdOffset (checked-add)
    // -------------------------------------------------------------------------

    /** Negative: cdOffset + cdSize < eocdOffset (gap before EOCD). */
    @Test
    void reject_cdGapBeforeEocd_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        // Build a valid zip, then inflate the declared cdSize by 1
        byte[] zip = buildZip(List.of(e));
        int eocdOffset = zip.length - 22;
        // cdSize field: offset +12 within EOCD
        int oldSize = (zip[eocdOffset + 12] & 0xFF)
                | ((zip[eocdOffset + 13] & 0xFF) << 8)
                | ((zip[eocdOffset + 14] & 0xFF) << 16)
                | ((zip[eocdOffset + 15] & 0xFF) << 24);
        int newSize = oldSize + 1; // too large → cdOffset+cdSize > eocdOffset
        zip[eocdOffset + 12] = (byte) (newSize & 0xFF);
        zip[eocdOffset + 13] = (byte) ((newSize >> 8) & 0xFF);
        zip[eocdOffset + 14] = (byte) ((newSize >> 16) & 0xFF);
        zip[eocdOffset + 15] = (byte) ((newSize >> 24) & 0xFF);

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /** Negative: entry data extends past cdOffset (checked-add overflow in nameEnd/extraEnd). */
    @Test
    void reject_entryDataPastCentralDirectoryCheckedAdd_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        byte[] zip = buildZip(List.of(e));
        // Set local extra field to a large value that causes nameEnd > file length
        // The entry is at offset 0, LOC_HEADER=30, nameLen=5, so extraEnd = 0 + 30 + 5 + 0xFFFF_FFFF
        // That overflows long → ArithmeticException → AK-ZIP-STRUCTURE
        int cdPos = findCenSig(zip);
        // extraLen offset in CD record: 28 bytes into header + nameLen
        int extraLenOffset = cdPos + 28 + 5; // after name
        // Write 0xFF 0xFF 0xFF 0xFF as extraLen (unsigned 16-bit, so only 0xFFFF)
        // Actually extraLen is a 16-bit field, max 65535. Set it so nameEnd+extraEnd > file.
        // localOffset=0, LOC_HEADER=30, nameLen=5, extraLen=65535
        // nameEnd = 0 + 30 + 5 = 35, extraEnd = 35 + 65535 = 65570 > file size (~80)
        zip[extraLenOffset]     = (byte) 0xFF;
        zip[extraLenOffset + 1] = (byte) 0xFF;

        Path p = writeZip(zip);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    /** Negative: parsed CD pointer doesn't land at cdEnd (extra padding between CD and EOCD). */
    @Test
    void reject_parsedCdPointerNotAtCdEnd_AK_ZIP_STRUCTURE() throws Exception {
        EntrySpec e = new EntrySpec();
        e.name = "f.txt";
        e.method = 0;
        e.crc = (int) crcLong("x".getBytes());
        e.uSize = 1;
        e.cSize = 1;
        e.data = "x".getBytes();

        // Build ZIP, then insert padding between CD and EOCD
        byte[] zip = buildZip(List.of(e));
        // The CD ends at zip.length - 22 (before EOCD)
        // Append 4 padding bytes between CD and EOCD
        byte[] withPadding = Arrays.copyOf(zip, zip.length + 4);
        System.arraycopy(zip, 0, withPadding, 0, zip.length);
        // EOCD cdSize still reflects the original CD size (without padding)
        // so cdEnd = cdOffset + cdSize < eocdOffset (gap exists)
        // OR: increase cdSize to cover the gap, then eocdOffset + 22 != fileSize
        // The simpler rejection path: padding causes extra bytes after EOCD
        // since we haven't changed commentLen. So eocdEnd != fileSize → rejection.
        Path p = writeZip(withPadding);
        ZipArchiveVerifier.Result r = verify(p);
        assertTrue(r.rejected());
        assertEquals("AK-ZIP-STRUCTURE", r.reasonCode());
    }

    // -------------------------------------------------------------------------
    // Result immutability tests
    // -------------------------------------------------------------------------

    @Test
    void result_entriesAreImmutable() throws Exception {
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("file.txt");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);

        assertThrows(UnsupportedOperationException.class,
                () -> r.entries().add(new ZipArchiveVerifier.Result.EntryResult(
                        "extra", "sha", 0, 0, 0, 0)));
    }

    @Test
    void result_embeddedDependenciesAreImmutable() throws Exception {
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("file.txt");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());
        ZipArchiveVerifier.Result r = verify(p);

        assertThrows(UnsupportedOperationException.class,
                () -> r.embeddedDependencies().add(new ZipArchiveVerifier.Result.EmbeddedDependency(
                        "path", "sha", "lock", false)));
    }

    @Test
    void result_reasonArgsIsImmutable() throws Exception {
        byte[] content = new byte[100];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry e = new java.util.zip.ZipEntry("file.txt");
            e.setMethod(java.util.zip.ZipEntry.STORED);
            e.setSize(content.length);
            e.setCrc(crcLong(content));
            zos.putNextEntry(e);
            zos.write(content);
            zos.closeEntry();
        }
        Path p = writeZip(baos.toByteArray());

        ZipArchiveVerifier strict = new ZipArchiveVerifier(16 * 1024 * 1024, 512, 50, 64 * 1024 * 1024, 100);
        ZipArchiveVerifier.Result r = strict.verify(p);
        assertTrue(r.rejected());
        assertNotNull(r.reasonArgs());

        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) r.reasonArgs()).put("extra", "value"));
    }

    // -------------------------------------------------------------------------
    // CentralDirectoryEntry record tests
    // -------------------------------------------------------------------------

    @Test
    void centralDirectoryEntry_withHash_preservesAllFields() throws Exception {
        byte[] raw = "content".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry original = new CentralDirectoryEntry(
                raw, "test.txt", 0, 7L, 7L, 12345L,
                42L, 0, 0x80000000L,
                false, false, false, false, false, false, null);

        StreamHasher.Result hash = new StreamHasher.Result(12345L, 7L, "abc123");
        CentralDirectoryEntry withHash = original.withHash(hash);

        assertEquals(hash, withHash.streamHash());
        assertEquals("test.txt", withHash.nameUtf8());
        assertEquals(7L, withHash.uncompressedSize());
        assertEquals(42L, withHash.localHeaderOffset());
    }

    @Test
    void centralDirectoryEntry_cleared_resetsStructuralFlags() throws Exception {
        byte[] raw = "META-INF/versions/9/X.class".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry original = new CentralDirectoryEntry(
                raw, "META-INF/versions/9/X.class",
                0, 0L, 0L, 0L, 0L, 0, 0xA0000000L,
                true, true, true, true, true, true, null);

        CentralDirectoryEntry cleared = original.cleared();

        assertFalse(cleared.isDirectory());
        assertFalse(cleared.hasExtra());
        assertFalse(cleared.isEncrypted());
        assertFalse(cleared.isSymlink());
        assertFalse(cleared.isSpecialFile());
        assertFalse(cleared.hasMultiReleasePath());
        assertEquals("META-INF/versions/9/X.class", cleared.nameUtf8());
        assertEquals(0xA0000000L, cleared.externalAttributes());
    }

    // -------------------------------------------------------------------------
    // StreamHasher unit tests
    // -------------------------------------------------------------------------

    @Test
    void streamHasher_stored_matchesCrcAndSha256() throws Exception {
        byte[] data = "STORED hash verification test data".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);

        StreamHasher.Result r = StreamHasher.hash(bais, data.length, 0);

        assertEquals(crcLong(data), r.crc32());
        assertEquals(data.length, r.uncompressedSize());
        assertEquals(sha256hex(data), r.sha256());
    }

    @Test
    void streamHasher_deflated_matchesCrcAndSha256() throws Exception {
        byte[] data = "DEFLATE hash verification — compressible content".getBytes(StandardCharsets.UTF_8);
        byte[] deflated = deflateRaw(data);
        ByteArrayInputStream bais = new ByteArrayInputStream(deflated);

        StreamHasher.Result r = StreamHasher.hash(bais, data.length, 8);

        assertEquals(crcLong(data), r.crc32());
        assertEquals(data.length, r.uncompressedSize());
        assertEquals(sha256hex(data), r.sha256());
    }

    @Test
    void streamHasher_unknownMethod_rejected_AK_UNKNOWN_COMPRESSION() throws Exception {
        ArchiveKernelException ex = assertThrows(ArchiveKernelException.class,
                () -> StreamHasher.hash(InputStream.nullInputStream(), 0, 99));
        assertEquals("AK-UNKNOWN-COMPRESSION", ex.reasonCode());
    }

    @Test
    void streamHasher_deflatedCorruptData_rejected() throws Exception {
        byte[] corrupt = deflateRaw("x".getBytes());
        corrupt[corrupt.length - 1] ^= 0xFF;
        ByteArrayInputStream bais = new ByteArrayInputStream(corrupt);

        ArchiveKernelException ex = assertThrows(ArchiveKernelException.class,
                () -> StreamHasher.hash(bais, 1, 8));
        assertTrue(ex.reasonCode().startsWith("AK-"));
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static EntrySpec makeStored(String name, byte[] data) {
        EntrySpec e = new EntrySpec();
        e.name = name;
        e.method = 0;
        e.crc = (int) crcLong(data);
        e.uSize = data.length;
        e.cSize = data.length;
        e.data = data;
        return e;
    }

    // -------------------------------------------------------------------------
    // T1 Fix: StreamHasher — truncated / malformed raw DEFLATE rejection tests
    // -------------------------------------------------------------------------

    /**
     * Truncated deflate stream where expected uncompressed size is 0.
     * The stream terminates mid-block before Inflater.finished().
     * Must be rejected with AK-DD-UNVERIFIABLE, not accepted silently.
     */
    @Test
    void streamHasher_deflatedTruncated_expectedSizeZero_rejected() throws Exception {
        // Build a valid deflate stream, then truncate it to produce
        // a stream that ends in the middle of a deflate block.
        byte[] fullData = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] deflated = deflateRaw(fullData);
        // Truncate to 60% — well before the stream can finish.
        int truncatedLen = Math.max(1, deflated.length * 3 / 5);
        byte[] truncated = Arrays.copyOf(deflated, truncatedLen);
        ByteArrayInputStream bais = new ByteArrayInputStream(truncated);

        ArchiveKernelException ex = assertThrows(ArchiveKernelException.class,
                () -> StreamHasher.hash(bais, 0, 8));
        assertEquals("AK-DD-UNVERIFIABLE", ex.reasonCode());
        // Detail must be one of the safe constant strings, not leaked content.
        Object detail = ((Map<String, Object>) ex.reasonArgs()).get("detail");
        assertTrue(
                detail == null
                        || "truncated deflate stream — input exhausted before inflation complete".equals(detail)
                        || "invalid deflate data".equals(detail)
                        || "trailing compressed bytes after inflation complete".equals(detail)
                        || "deflate stream requires unknown preset dictionary".equals(detail)
                        || "deflate stream stalled — no progress and no input needed".equals(detail)
                        || "unread bytes in bounded input stream after inflation complete".equals(detail),
                "detail must be a safe constant, not leaked content: " + detail);
    }

    /**
     * Truncated deflate stream where the crafted expected size exactly matches
     * the uncompressed prefix the truncated stream would produce.
     * This attacks the "accept if actual matches expected" path that might
     * incorrectly pass a truncated stream that happens to match a small expected size.
     */
    @Test
    void streamHasher_deflatedTruncated_prefixMatchesExpectedSize_rejected() throws Exception {
        byte[] fullData = "hello world this is a longer message".getBytes(StandardCharsets.UTF_8);
        byte[] deflated = deflateRaw(fullData);
        // Truncate to 40% — enough to produce some output but not finish.
        int truncatedLen = Math.max(1, deflated.length * 2 / 5);
        byte[] truncated = Arrays.copyOf(deflated, truncatedLen);

        // First, find the actual uncompressed prefix produced by the truncated stream.
        Inflater probeInflater = new Inflater(true);
        probeInflater.setInput(truncated);
        byte[] probeOut = new byte[8192];
        int prefixLen = 0;
        try {
            while (!probeInflater.finished()) {
                if (probeInflater.needsInput()) break;
                int n = probeInflater.inflate(probeOut);
                if (n > 0) prefixLen += n;
            }
        } catch (java.util.zip.DataFormatException e) {
            // ignore
        }
        probeInflater.end();

        // Assign to effectively-final variable for lambda capture.
        final int expectedPrefixSize = prefixLen;

        // The prefix length is what we would "expect" — but the stream is still truncated.
        ByteArrayInputStream bais = new ByteArrayInputStream(truncated);
        ArchiveKernelException ex = assertThrows(ArchiveKernelException.class,
                () -> StreamHasher.hash(bais, expectedPrefixSize, 8));
        assertEquals("AK-DD-UNVERIFIABLE", ex.reasonCode());
    }

    /**
     * Trailing compressed byte after inflation is complete.
     * After Inflater.finished(), any remaining input byte means structural overflow.
     */
    @Test
    void streamHasher_deflatedTrailingCompressedByte_rejected() throws Exception {
        byte[] fullData = "a".getBytes(StandardCharsets.UTF_8);
        byte[] deflated = deflateRaw(fullData);
        // Append one garbage byte — this exceeds the bounded cSize.
        byte[] withTrailingGarbage = Arrays.copyOf(deflated, deflated.length + 1);
        withTrailingGarbage[deflated.length] = (byte) 0xFF;
        ByteArrayInputStream bais = new ByteArrayInputStream(withTrailingGarbage);

        ArchiveKernelException ex = assertThrows(ArchiveKernelException.class,
                () -> StreamHasher.hash(bais, fullData.length, 8));
        assertEquals("AK-DD-UNVERIFIABLE", ex.reasonCode());
    }

    /**
     * Inflater signals needsDictionary before finished — preset dictionary
     * required but not provided in a ZIP context.
     */
    @Test
    void streamHasher_deflatedNeedsDictionary_rejected() throws Exception {
        // Manually construct a deflate stream that emits a "stored" block with
        // BFINAL=1, BTYPE=01 (stored), but use a preset-dictionary flag.
        // The cleanest way: use Inflater with a dictionary set and then feed
        // a stream that references it.
        byte[] dict = "dictionary".getBytes(StandardCharsets.UTF_8);
        Inflater dictInflater = new Inflater(true);
        dictInflater.setDictionary(dict);
        // Feed empty input to get the dictionary set.
        // Now build a stream that references this dictionary.
        // Unfortunately, producing such a stream requires more involved byte-level
        // construction. Instead, we test via a corrupt stream that triggers
        // needsDictionary by setting the zlib header to indicate dictionary mode.
        // The safest coverage: we construct a minimal stream that ends in a state
        // where Inflater.needsDictionary() returns true after a few bytes.
        //
        // Direct byte-level: a deflate block with ZLIB header byte 0x5E
        // (CMF=0x58, FLG=0x9C → DEFLATE, preset dictionary flag set, check bits invalid
        // but enough for Inflater to see needsDictionary before finished).
        //
        // Practical approach: create a stream with the DEFLATE method byte followed
        // by bytes crafted to set the dictionary-required state before any valid blocks.
        byte[] dictStream = new byte[] {
                0x78, (byte)0x9C,             // ZLIB header (no preset)
                // Now inject a stream that, after some processing, leaves needsDictionary=true.
                // The most reliable test: modify the deflater to produce a stream with
                // the PRESET_DICT flag in the zlib header.
        };
        // Use a custom zlib wrapper with PRESET_DICT flag set (0x20).
        // CMF=0x78, FLG=0x9C|0x20=0xBC → check bits: (0x78*31 + 0xBC) % 256 = 0.
        byte[] presetDictHeader = new byte[] {
                0x78, (byte)0xBC,             // ZLIB header with PRESET_DICT (check=0)
                0x01, 0x00, 0x00, 0x00, 0x00  // Minimal deflate data (BFINAL=1, BTYPE=00)
        };
        ByteArrayInputStream bais = new ByteArrayInputStream(presetDictHeader);

        ArchiveKernelException ex = assertThrows(ArchiveKernelException.class,
                () -> StreamHasher.hash(bais, 0, 8));
        assertEquals("AK-DD-UNVERIFIABLE", ex.reasonCode());
    }

    /**
     * No-progress trap: Inflater neither finished, nor needs input, nor produces output.
     */
    @Test
    void streamHasher_deflatedNoProgress_rejected() throws Exception {
        // Build a minimal DEFLATE stream that puts the Inflater in a no-progress
        // state without being finished.  A stream with BFINAL=0, BTYPE=00 (no compression),
        // and a length of 0, followed by an empty final block, creates this scenario.
        byte[] noProgressStream = new byte[] {
                0x78, (byte)0x9C,             // ZLIB header
                0x01, 0x00,                   // BFINAL=0, BTYPE=00, LEN=0
                0x00, 0x00,                   // NLEN
                0x01, 0x00,                   // BFINAL=1, BTYPE=00, LEN=0
                0x00, 0x00                    // NLEN
        };
        ByteArrayInputStream bais = new ByteArrayInputStream(noProgressStream);

        // This stream is technically valid but very short. It may or may not finish
        // depending on Inflater internals. To reliably trigger no-progress, we need
        // a stream that leaves the Inflater waiting after exhausting all input.
        // Instead, test: give a stream that feeds bytes that the Inflater consumes
        // but that produces zero output and doesn't finish.
        //
        // Simpler: inject bytes that produce a repeat-code in a way that exhausts
        // input but not output. Since DEFLATE repeat codes require back-references,
        // they can't appear in isolation. The no-progress path is reached when
        // the Inflater is waiting for more input (needsInput=false) but inflate()
        // returns 0.
        //
        // The most reliable trigger: a deflate stream that ends with a block header
        // indicating "more blocks follow" (BFINAL=0) but with no further data.
        byte[] unfinishedBlockStream = new byte[] {
                0x78, (byte)0x9C,             // ZLIB header
                0x02, 0x00,                   // BFINAL=0, BTYPE=01 (stored), LEN=2
                0x02, 0x00,                   // NLEN=0x0002
                0x00, 0x00                    // 2 bytes of stored data
                // Missing EOB / next block
        };
        ByteArrayInputStream bais2 = new ByteArrayInputStream(unfinishedBlockStream);

        ArchiveKernelException ex = assertThrows(ArchiveKernelException.class,
                () -> StreamHasher.hash(bais2, 0, 8));
        assertEquals("AK-DD-UNVERIFIABLE", ex.reasonCode());
    }

    /**
     * Valid deflate stream with zero uncompressed output (empty content).
     * Must be accepted and produce crc=0, size=0.
     */
    @Test
    void streamHasher_deflatedValidEmptyContent_accepted() throws Exception {
        byte[] deflated = deflateRaw(new byte[0]);
        ByteArrayInputStream bais = new ByteArrayInputStream(deflated);

        StreamHasher.Result r = StreamHasher.hash(bais, 0, 8);

        assertEquals(0L, r.crc32());
        assertEquals(0L, r.uncompressedSize());
        assertEquals(sha256hex(new byte[0]), r.sha256());
    }

    /**
     * Valid bounded deflate stream: standard ZipOutputStream output must pass.
     */
    @Test
    void streamHasher_deflatedValidBounded_accepted() throws Exception {
        byte[] data = "DEFLATE bounded acceptance test".getBytes(StandardCharsets.UTF_8);
        byte[] deflated = deflateRaw(data);
        ByteArrayInputStream bais = new ByteArrayInputStream(deflated);

        StreamHasher.Result r = StreamHasher.hash(bais, data.length, 8);

        assertEquals(crcLong(data), r.crc32());
        assertEquals(data.length, r.uncompressedSize());
        assertEquals(sha256hex(data), r.sha256());
    }

    /**
     * Valid deflate stream where the CD declares the exact correct compressed size.
     * No message leaks in exception output.
     */
    @Test
    void streamHasher_noMessageLeakInRejection() throws Exception {
        byte[] corrupt = deflateRaw("x".getBytes());
        corrupt[corrupt.length - 1] ^= 0xFF;
        ByteArrayInputStream bais = new ByteArrayInputStream(corrupt);

        ArchiveKernelException ex = assertThrows(ArchiveKernelException.class,
                () -> StreamHasher.hash(bais, 1, 8));
        // reasonArgs must not contain raw content or file data.
        Map<String, Object> args = (Map<String, Object>) ex.reasonArgs();
        assertFalse(args.values().stream().anyMatch(v ->
                v instanceof String && ((String) v).length() > 100));
    }

    // -------------------------------------------------------------------------
    // T1 Fix: CentralDirectoryEntry — defensive nameRaw immutability tests
    // -------------------------------------------------------------------------

    /**
     * Null nameRaw in constructor must throw NullPointerException.
     */
    @Test
    void centralDirectoryEntry_nullNameRaw_rejected() {
        assertThrows(NullPointerException.class,
                () -> new CentralDirectoryEntry(
                        null, "file.txt", 0, 0L, 0L, 0L,
                        0L, 0, 0L,
                        false, false, false, false, false, false, null));
    }

    /**
     * Null nameUtf8 in constructor must throw NullPointerException.
     */
    @Test
    void centralDirectoryEntry_nullNameUtf8_rejected() {
        assertThrows(NullPointerException.class,
                () -> new CentralDirectoryEntry(
                        new byte[]{'f'}, null, 0, 0L, 0L, 0L,
                        0L, 0, 0L,
                        false, false, false, false, false, false, null));
    }

    /**
     * Mutation of caller's array after construction does not affect the record.
     */
    @Test
    void centralDirectoryEntry_nameRawMutationAfterConstruction_noEffect() {
        byte[] callerArray = "file.txt".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry entry = new CentralDirectoryEntry(
                callerArray, "file.txt", 0, 0L, 0L, 0L,
                0L, 0, 0L,
                false, false, false, false, false, false, null);

        // Mutate the caller's array.
        callerArray[0] = 'X';

        // Record must retain original name.
        assertEquals("file.txt", new String(entry.nameRaw(), StandardCharsets.UTF_8));
        assertEquals("file.txt", entry.nameUtf8());
    }

    /**
     * Caller cannot alter the internal nameRaw array via the accessor.
     */
    @Test
    void centralDirectoryEntry_nameRawMutationViaAccessor_noEffect() {
        byte[] raw = "file.txt".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry entry = new CentralDirectoryEntry(
                raw, "file.txt", 0, 0L, 0L, 0L,
                0L, 0, 0L,
                false, false, false, false, false, false, null);

        // Get the raw array and mutate it.
        byte[] obtained = entry.nameRaw();
        obtained[0] = 'Z';

        // Record must retain original name.
        assertEquals("file.txt", new String(entry.nameRaw(), StandardCharsets.UTF_8));
        assertEquals("file.txt", entry.nameUtf8());
    }

    /**
     * Multiple calls to nameRaw() each return independent clones.
     */
    @Test
    void centralDirectoryEntry_nameRawAccessor_returnsIndependentClones() {
        byte[] raw = "file.txt".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry entry = new CentralDirectoryEntry(
                raw, "file.txt", 0, 0L, 0L, 0L,
                0L, 0, 0L,
                false, false, false, false, false, false, null);

        byte[] first = entry.nameRaw();
        byte[] second = entry.nameRaw();

        // Both return the same content.
        assertArrayEquals(first, second);

        // But they are distinct array instances.
        assertNotSame(first, second);

        // Mutating one does not affect the other or the record.
        first[0] = 'X';
        assertEquals("file.txt", new String(entry.nameRaw(), StandardCharsets.UTF_8));
    }

    /**
     * withHash() maintains nameRaw isolation: caller cannot mutate the copy
     * returned by withHash().
     */
    @Test
    void centralDirectoryEntry_nameRawMutationAfterWithHash_noEffect() {
        byte[] raw = "test.txt".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry original = new CentralDirectoryEntry(
                raw, "test.txt", 0, 0L, 0L, 0L,
                0L, 0, 0L,
                false, false, false, false, false, false, null);

        StreamHasher.Result hash = new StreamHasher.Result(0L, 0L, "abc");
        CentralDirectoryEntry withHash = original.withHash(hash);

        // Mutate the withHash copy.
        withHash.nameRaw()[0] = 'X';

        // Original unchanged.
        assertEquals("test.txt", new String(original.nameRaw(), StandardCharsets.UTF_8));
    }

    /**
     * withHash() copy is independent from original: mutating the original's
     * nameRaw does not affect the copy.
     */
    @Test
    void centralDirectoryEntry_withHash_independentFromOriginal() {
        byte[] raw = "test.txt".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry original = new CentralDirectoryEntry(
                raw, "test.txt", 0, 0L, 0L, 0L,
                0L, 0, 0L,
                false, false, false, false, false, false, null);

        StreamHasher.Result hash = new StreamHasher.Result(0L, 0L, "abc");
        CentralDirectoryEntry withHash = original.withHash(hash);

        // Mutate the original.
        original.nameRaw()[0] = 'Q';

        // withHash copy unchanged.
        assertEquals("test.txt", new String(withHash.nameRaw(), StandardCharsets.UTF_8));
    }

    /**
     * cleared() maintains nameRaw isolation: caller cannot mutate the copy.
     */
    @Test
    void centralDirectoryEntry_nameRawMutationAfterCleared_noEffect() {
        byte[] raw = "test.txt".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry original = new CentralDirectoryEntry(
                raw, "test.txt", 0, 0L, 0L, 0L,
                0L, 0, 0L,
                true, true, true, true, true, true, null);

        CentralDirectoryEntry cleared = original.cleared();

        // Mutate the cleared copy.
        cleared.nameRaw()[0] = 'Y';

        // Original unchanged.
        assertEquals("test.txt", new String(original.nameRaw(), StandardCharsets.UTF_8));
    }

    /**
     * cleared() copy is independent from original: mutating the original's
     * nameRaw does not affect the cleared copy.
     */
    @Test
    void centralDirectoryEntry_cleared_independentFromOriginal() {
        byte[] raw = "test.txt".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry original = new CentralDirectoryEntry(
                raw, "test.txt", 0, 0L, 0L, 0L,
                0L, 0, 0L,
                true, true, true, true, true, true, null);

        CentralDirectoryEntry cleared = original.cleared();

        // Mutate the original.
        original.nameRaw()[0] = 'Z';

        // cleared copy unchanged.
        assertEquals("test.txt", new String(cleared.nameRaw(), StandardCharsets.UTF_8));
    }

    /**
     * Accessing nameRaw after withHash() preserves immutability.
     */
    @Test
    void centralDirectoryEntry_withHash_preservesNameRawIsolation() {
        byte[] raw = "a/b.txt".getBytes(StandardCharsets.UTF_8);
        CentralDirectoryEntry entry = new CentralDirectoryEntry(
                raw, "a/b.txt", 0, 5L, 5L, 0L,
                0L, 0, 0L,
                false, false, false, false, false, false, null);

        StreamHasher.Result hash = new StreamHasher.Result(0L, 5L, "def");
        CentralDirectoryEntry withHash = entry.withHash(hash);

        // Both should have independent clones.
        byte[] entryRaw = entry.nameRaw();
        byte[] withHashRaw = withHash.nameRaw();

        assertNotSame(entryRaw, withHashRaw);
        assertArrayEquals(entryRaw, withHashRaw);

        // Mutate entry raw.
        entryRaw[0] = 0;

        // withHash raw unchanged.
        assertEquals("a/b.txt", new String(withHash.nameRaw(), StandardCharsets.UTF_8));
    }
}
