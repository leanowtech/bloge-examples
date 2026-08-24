package com.leanowtech.bloge.gateway.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Package-private test fixture helper for artifact validator tests.
 */
final class CapabilityStudioGateAArtifactValidatorTestFixtures {

    private CapabilityStudioGateAArtifactValidatorTestFixtures() {}

    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    private static final int CENTRAL_HEADER_SIGNATURE = 0x02014b50;
    private static final int END_HEADER_SIGNATURE = 0x06054b50;

    public static byte[] createMinimalClassBytes(String className) {
        return new byte[] {
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                0x00, 0x00, 0x00, 0x34,
                0x00, 0x0D,
                0x0A, 0x00, 0x03, 0x00, 0x0A,
                0x07, 0x00, 0x0B,
                0x07, 0x00, 0x0C,
                0x01, 0x00, 0x06, '<', 'i', 'n', 'i', 't', '>',
                0x01, 0x00, 0x03, '(', 'V', ')', 'V',
                0x01, 0x00, 0x04, 'C', 'o', 'd', 'e',
                0x01, 0x00, 0x0A, 'S', 'o', 'u', 'r', 'c', 'e', 'F', 'i', 'l', 'e',
                0x01, 0x00, 0x0F, '<', 'u', 'n', 'n', 'a', 'm', 'e', 'd', '>',
                0x0C, 0x00, 0x04, 0x05,
                0x01, 0x00, 0x10, 'j', 'a', 'v', 'a', '/', 'l', 'a', 'n', '/', 'O', 'b', 'j', 'e', 'c', 't',
                0x01, 0x00, 0x10, 'j', 'a', 'v', 'a', '/', 'l', 'a', 'n', '/', 'O', 'b', 'j', 'e', 'c', 't',
                0x00, 0x21, 0x00, 0x02, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x04, 0x00, 0x05, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00, 0x00, 0x11,
                0x00, 0x01, 0x00, 0x00, 0x00, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                (byte) 0xB1,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x07, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x09,
                0x00, 0x01, 0x00, 0x0C, 'D', 'u', 'm', 'm', 'y', '.', 'j', 'a', 'v', 'a'
        };
    }

    public static byte[] createMinimalDependencyJar(String lockId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                jos.putNextEntry(new JarEntry("META-INF/"));
                jos.closeEntry();
                jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jos.write(manifestToBytes(manifest));
                jos.closeEntry();
                jos.putNextEntry(new JarEntry("dummy.txt"));
                jos.write(("Dependency: " + lockId).getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] createJar(String mainClass, java.util.Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            if (mainClass != null) {
                manifest.getMainAttributes().putValue("Main-Class", mainClass);
            }
            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    jos.putNextEntry(new JarEntry(entry.getKey()));
                    jos.write(entry.getValue());
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] createRawZip(java.util.Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] createJarWithCompressibleContent(String mainClass, String content, int repetitions) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class", mainClass);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < repetitions; i++) {
                sb.append(content);
            }
            byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                jos.putNextEntry(new JarEntry("payload.bin"));
                jos.write(payload);
                jos.closeEntry();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] createJarWithManyEntries(String mainClass, int entryCount) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class", mainClass);
            try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
                for (int i = 0; i < entryCount; i++) {
                    jos.putNextEntry(new JarEntry("entry" + i + ".txt"));
                    jos.write(("Entry " + i).getBytes(StandardCharsets.UTF_8));
                    jos.closeEntry();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a JAR with a duplicate ZIP entry by directly writing raw ZIP bytes.
     */
    public static byte[] createJarWithDuplicateEntry() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");
            ByteArrayOutputStream manifestBaos = new ByteArrayOutputStream();
            manifest.write(manifestBaos);
            byte[] manifestBytes = manifestBaos.toByteArray();
            java.util.List<byte[]> centralDirEntries = new java.util.ArrayList<>();
            ByteArrayOutputStream localData = new ByteArrayOutputStream();

            // MANIFEST.MF local header
            centralDirEntries.add(writeLocalEntry("META-INF/MANIFEST.MF", manifestBytes, localData));

            // First Duplicate.class
            byte[] dupContent = new byte[]{1, 2, 3, 4};
            centralDirEntries.add(writeLocalEntry("com/dup/Duplicate.class", dupContent, localData));

            // Second Duplicate.class (duplicate!)
            centralDirEntries.add(writeLocalEntry("com/dup/Duplicate.class", dupContent, localData));

            baos.write(localData.toByteArray());
            int centralDirStart = baos.size();
            for (byte[] cent : centralDirEntries) {
                baos.write(cent);
            }
            int centralDirEnd = baos.size();
            writeEndOfCentralDir(baos, centralDirEntries.size(), centralDirStart, centralDirEnd);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a JAR with an entry that has usize=0 in central directory but actual content.
     * The validator checks usize (from central directory) vs actual read bytes.
     */
    public static byte[] createJarWithZeroSizeEntry() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Main-Class",
                    "com.leanowtech.bloge.gateway.testkit.CapabilityStudioGateAChallengeCli");
            ByteArrayOutputStream manifestBaos = new ByteArrayOutputStream();
            manifest.write(manifestBaos);
            byte[] manifestBytes = manifestBaos.toByteArray();
            java.util.List<byte[]> centralDirEntries = new java.util.ArrayList<>();
            ByteArrayOutputStream localData = new ByteArrayOutputStream();

            // MANIFEST.MF entry - normal
            centralDirEntries.add(writeLocalEntry("META-INF/MANIFEST.MF", manifestBytes, localData));

            // Zero-size entry: write actual content in local header, but central says usize=0
            byte[] actualContent = "has content".getBytes(StandardCharsets.UTF_8);
            centralDirEntries.add(writeLocalEntryWithCentralZeroSize("META-INF/some-file.txt", actualContent, localData));

            baos.write(localData.toByteArray());
            int centralDirStart = baos.size();
            for (byte[] cent : centralDirEntries) {
                baos.write(cent);
            }
            int centralDirEnd = baos.size();
            writeEndOfCentralDir(baos, centralDirEntries.size(), centralDirStart, centralDirEnd);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] writeLocalEntry(String name, byte[] data, ByteArrayOutputStream out) throws IOException {
        return writeLocalEntryInternal(name, data, data.length, out, data.length);
    }

    /**
     * Writes local entry where central directory says usize=0 but actual content exists.
     */
    private static byte[] writeLocalEntryWithCentralZeroSize(String name, byte[] data, ByteArrayOutputStream out) throws IOException {
        // Local header: write actual sizes (so actual data is readable)
        // Central directory: set usize=0 (triggers the validation)
        return writeLocalEntryInternal(name, data, data.length, out, 0);
    }

    private static byte[] writeLocalEntryInternal(String name, byte[] data, int localDeclaredSize,
            ByteArrayOutputStream out, int centralUncompressedSize) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        int crcVal = (int) crc.getValue();
        int localOffset = out.size();
        int headerSize = 30 + nameBytes.length;

        // Local file header
        ByteBuffer hdr = ByteBuffer.allocate(headerSize);
        hdr.order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(LOCAL_HEADER_SIGNATURE);
        hdr.putShort((short) 20);               // version needed
        hdr.putShort((short) 0);                // general purpose bit flag
        hdr.putShort((short) 0);                // compression method (STORED)
        hdr.putShort((short) 0);               // last mod time
        hdr.putShort((short) 0);               // last mod date
        hdr.putInt(crcVal);
        hdr.putInt(localDeclaredSize);          // compressed size in local header
        hdr.putInt(data.length);                // uncompressed size in local header
        hdr.putShort((short) nameBytes.length);
        hdr.putShort((short) 0);
        hdr.put(nameBytes);
        out.write(hdr.array());
        out.write(data);

        // Central directory entry
        return buildCentralDirEntry(nameBytes, centralUncompressedSize, localDeclaredSize, crcVal, localOffset);
    }

    private static byte[] buildCentralDirEntry(byte[] nameBytes, int uncompSize, int compSize, int crc, int localOffset) {
        int totalSize = 46 + nameBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(CENTRAL_HEADER_SIGNATURE);
        buf.putShort((short) 20);               // version made by
        buf.putShort((short) 20);               // version needed
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putInt(crc);
        buf.putInt(compSize);                   // compressed size
        buf.putInt(uncompSize);                 // uncompressed size (0 for our test case)
        buf.putShort((short) nameBytes.length);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.putInt(localOffset);
        buf.put(nameBytes);
        return buf.array();
    }

    private static void writeEndOfCentralDir(ByteArrayOutputStream baos, int entryCount, int centralStart, int centralEnd) {
        ByteBuffer buf = ByteBuffer.allocate(22);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(END_HEADER_SIGNATURE);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) entryCount);
        buf.putShort((short) entryCount);
        buf.putInt(centralEnd - centralStart);
        buf.putInt(centralStart);
        buf.putShort((short) 0);
        try {
            baos.write(buf.array());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return "sha256:" + sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] manifestToBytes(Manifest manifest) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            manifest.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
