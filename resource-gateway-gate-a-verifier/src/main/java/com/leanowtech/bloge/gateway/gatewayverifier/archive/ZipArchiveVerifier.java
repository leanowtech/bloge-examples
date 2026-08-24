package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;

/**
 * Standalone JDK-only ZIP/JAR archive kernel for A1.3-02.
 *
 * Implements full ZIP structural parse with RandomAccessFile:
 *   1. EOCD locate (backward scan) + single-disk validation
 *  2. Central-directory read + unsigned offset/count validation
 *   3. Structural rejection (directory/extra/encryption/symlink/special/multi-release)
 *  4. Local-central rebind (name/flags/method/CRC/sizes)
 *  5. Per-entry local-header seek + bounded stream hash (CRC-32 + SHA-256)
 *  6. Data-descriptor policy + limit enforcement
 *
 * Public reason codes follow the stable AK namespace documented in
 * {@link ArchiveKernelException}.
 *
 * No filesystem writes, no network sockets, no business runtime dependencies.
 */
public final class ZipArchiveVerifier {

    // -------------------------------------------------------------------------
    // ZIP constants
    // -------------------------------------------------------------------------

    private static final int LOCSIG           = 0x04034b50;
    private static final int CENSIG          = 0x02014b50;
    private static final int EOCSIG          = 0x06054b50;
    private static final int ZIP64_LOCSIG    = 0x07064b50;
    // EOCD record (22 bytes) + max comment (65535 bytes) = 65557
    private static final int MAX_EOCD_SEARCH = 65_557;
    private static final int CD_HEADER_SIZE   = 46;
    private static final int LOC_HEADER_SIZE  = 30;
    private static final long MAX_UNSIGNED32  = 0xFFFF_FFFFL;

    // General-purpose flag bits
    private static final int GPB_ENCRYPTED = 0x0001;
    private static final int GPB_DATADESC   = 0x0008;

    // External-attribute Unix type constants (high 16 bits)
    private static final int UNIX_TYPE_SYMLINK = 0xA000;
    private static final int UNIX_TYPE_BLOCK   = 0x6000;
    private static final int UNIX_TYPE_CHAR     = 0x2000;
    private static final int UNIX_TYPE_FIFO     = 0x1000;
    private static final int UNIX_TYPE_SOCKET   = 0xC000;

    // Permitted compression methods
    private static final int METHOD_STORED   = 0;
    private static final int METHOD_DEFLATED = 8;

    // Default artifact limits
    public static final long DEFAULT_MAX_RAW_BYTES          = 16 * 1024 * 1024L;
    public static final long DEFAULT_MAX_ZIP_ENTRIES        = 512;
    public static final long DEFAULT_MAX_SINGLE_ENTRY_BYTES  = 8 * 1024 * 1024L;
    public static final long DEFAULT_MAX_TOTAL_UNCOMPRESSED = 64 * 1024 * 1024L;
    public static final long DEFAULT_MAX_COMPRESSION_RATIO  = 100;

    // -------------------------------------------------------------------------
    // Immutable result record
    // -------------------------------------------------------------------------

    /**
     * Immutable output produced by the verifier.
     *
     * @param entryCount            total entries in central directory
     * @param entries               ordered list of parsed + hashed entries (defensive copy)
     * @param limits                five-limit check results
     * @param embeddedDependencies  placeholder for T3 nested-JAR binding (defensive copy)
     * @param rejected              true if any rejection occurred
     * @param reasonCode            first rejection AK code; null if accepted
     * @param reasonArgs            structured args for reasonCode; null if accepted
     */
    public record Result(
            int entryCount,
            List<EntryResult> entries,
            LimitResults limits,
            List<EmbeddedDependency> embeddedDependencies,
            boolean rejected,
            String reasonCode,
            Map<String, Object> reasonArgs
    ) {

        /** Per-entry content-verification result. */
        public record EntryResult(
                String name,
                String sha256,
                long crc32,
                long uncompressedSize,
                long compressedSize,
                int compressionMethod
        ) {}

        /** Placeholder for T3 nested-JAR binding. */
        public record EmbeddedDependency(
                String entryPath,
                String rawFingerprint,
                String lockId,
                boolean bound
        ) {}

        /** Constructor that makes defensive copies of all mutable collections. */
        public Result {
            entries              = entries              != null ? List.copyOf(entries)              : List.of();
            embeddedDependencies = embeddedDependencies != null ? List.copyOf(embeddedDependencies) : List.of();
            reasonArgs           = reasonArgs          != null ? Map.copyOf(reasonArgs)            : null;
        }

    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final long maxRawBytes;
    private final long maxZipEntries;
    private final long maxSingleEntryBytes;
    private final long maxTotalUncompressed;
    private final long maxCompressionRatio;

    // -------------------------------------------------------------------------
    // Public constructor
    // -------------------------------------------------------------------------

    public ZipArchiveVerifier() {
        this(DEFAULT_MAX_RAW_BYTES,
                DEFAULT_MAX_ZIP_ENTRIES,
                DEFAULT_MAX_SINGLE_ENTRY_BYTES,
                DEFAULT_MAX_TOTAL_UNCOMPRESSED,
                DEFAULT_MAX_COMPRESSION_RATIO);
    }

    public ZipArchiveVerifier(long maxRawBytes,
                              long maxZipEntries,
                              long maxSingleEntryBytes,
                              long maxTotalUncompressed,
                              long maxCompressionRatio) {
        this.maxRawBytes          = maxRawBytes;
        this.maxZipEntries        = maxZipEntries;
        this.maxSingleEntryBytes  = maxSingleEntryBytes;
        this.maxTotalUncompressed = maxTotalUncompressed;
        this.maxCompressionRatio  = maxCompressionRatio;
    }

    // -------------------------------------------------------------------------
    // LimitResults
    // -------------------------------------------------------------------------

    public record LimitResults(
            boolean rawBytesHit,
            boolean zipEntriesHit,
            boolean singleEntryHit,
            boolean totalUncompressedHit,
            boolean compressionRatioHit
    ) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Verify a ZIP/JAR archive at the given path.
     *
     * @param path  path to a ZIP or JAR file
     * @return      immutable Result with entry list, limit results, and rejection info
     */
    public Result verify(Path path) {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            return verify(raf);
        } catch (NoSuchFileException | AccessDeniedException e) {
            return new Result(0, List.of(),
                    new LimitResults(false, false, false, false, false),
                    List.of(), true, "AK-ARCHIVE-IO", null);
        } catch (ArchiveKernelException e) {
            // reasonArgs is already a stable Map or null — never e.getMessage.
            return new Result(0, List.of(),
                    new LimitResults(false, false, false, false, false),
                    List.of(), true, e.reasonCode(),
                    e.reasonArgs());
        } catch (IOException e) {
            // RAF constructor or close can throw IOException (e.g. FileNotFoundException
            // for a directory path).  Convert to AK-ARCHIVE-IO with null args —
            // no path or system text ever reaches the caller.
            return new Result(0, List.of(),
                    new LimitResults(false, false, false, false, false),
                    List.of(), true, "AK-ARCHIVE-IO", null);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1: EOCD locate + parse + single-disk validation
    // -------------------------------------------------------------------------

    private record Eocd(
            long eocdOffset,
            long cdOffset,
            long cdSize,
            int cdEntriesTotal,
            int cdEntriesOnThisDisk
    ) {}

    /**
     * Locate the End-of-Central-Directory record by scanning backward from file end,
     * validate its fields, and reject archives that could hide structures inside
     * comments or that contain ZIP64 sentinels.
     *
     * EOCD comment handling:
     *   - commentLen is parsed as unsigned 16-bit (0..65535).
     *   - A candidate EOCD at offset X is accepted only when X + 22 + commentLen
     *     exactly equals the file size. This prevents fake EOCD signatures buried
     *     inside an actual comment from being accepted.
     *   - Undeclared bytes, truncated comments, or extra data after the EOCD record
     *     are always rejected.
     *
     * ZIP64 rejection:
     *   - 0xFFFF in any EOCD count field (entries-on-disk, total entries)
     *   - 0xFFFFFFFF in cdSize or cdOffset fields
     *   - ZIP64 locator (0x07064b50) appearing immediately before the EOCD record
     *
     * @throws ArchiveKernelException on any structural failure (no path/IOException leakage)
     * @throws IOException           only on unanticipated file read errors
     */
    private Eocd findAndParseEocd(RandomAccessFile raf, long fileSize)
            throws ArchiveKernelException, IOException {

        // Fail-closed: file must hold minimum EOCD
        if (fileSize < 22) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        int scanLimit = (int) Math.min(MAX_EOCD_SEARCH, fileSize);
        byte[] trailer = new byte[scanLimit];
        raf.seek(fileSize - scanLimit);
        // T1-4: Use readFully so we get all bytes or an IOException.
        // The bounds check above guarantees scanLimit <= fileSize, so
        // fileSize - scanLimit >= 0 and scanning within file bounds.
        raf.readFully(trailer);

        // Scan backward for EOCD signature.  A valid EOCD is accepted only when
        // eocdOffset + 22 + commentLen == fileSize, which prevents a genuine
        // 0x06054b50 that happens to sit inside a comment from being mistaken
        // for the real EOCD record.
        int sigPos = -1;
        short candidateCommentLen = -1;

        for (int i = scanLimit - 22; i >= 0; i--) {
            if (trailer[i]     == 0x50
                    && trailer[i + 1] == 0x4b
                    && trailer[i + 2] == 0x05
                    && trailer[i + 3] == 0x06) {

                // Parse commentLen at this candidate (unsigned 16-bit)
                int commentLenRaw = ((trailer[i + 20] & 0xFF)
                        | ((trailer[i + 21] & 0xFF) << 8));
                long candidateEnd = (fileSize - scanLimit + i) + 22L + commentLenRaw;

                // Accept only if the candidate fills to exactly file size
                if (candidateEnd == fileSize) {
                    sigPos = i;
                    candidateCommentLen = (short) commentLenRaw;
                    break;
                }

            }

        }


        if (sigPos < 0) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }


        long eocdOffset = fileSize - scanLimit + sigPos;
        ByteBuffer bb = ByteBuffer.wrap(trailer, sigPos, 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.getInt();
        short diskNum         = bb.getShort();
        short cdStartDisk     = bb.getShort();
        short entriesThisDisk = bb.getShort();
        short entriesTotal    = bb.getShort();
        int cdSize           = bb.getInt();
        int cdOffset         = bb.getInt();
        short commentLen     = bb.getShort(); // already validated == candidateCommentLen

        // --- ZIP64 sentinel rejection (T1-2) ---
        // 0xFFFF in count fields signals ZIP64 extended information is required
        if (entriesThisDisk == (short) 0xFFFF) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        if (entriesTotal == (short) 0xFFFF) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        // 0xFFFFFFFF in cdSize or cdOffset signals ZIP64
        if ((cdSize   & 0xFFFFFFFFL) == 0xFFFFFFFFL) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        if ((cdOffset & 0xFFFFFFFFL) == 0xFFFFFFFFL) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }


        // --- Single-disk enforcement ---
        if (diskNum != 0) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        if (cdStartDisk != 0) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        if (entriesThisDisk != entriesTotal) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }


        // commentLen == candidateCommentLen already confirmed by exact-end check above
        // No additional comment content validation needed — any bytes are allowed

        // --- Undeclared / truncated / trailing bytes ---
        // Already enforced by the candidate acceptance test above:
        // eocdOffset + 22 + commentLen == fileSize
        // Extra bytes after the declared comment are structurally invalid

        // Unsigned 32-bit rebind
        long cdOffsetU = Integer.toUnsignedLong(cdOffset);
        long cdSizeU   = Integer.toUnsignedLong(cdSize);

        // cdOffset must be within file
        if (cdOffsetU > fileSize) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }


        // T1-3: Require cdOffset + cdSize == eocdOffset exactly.
        // The parsed pointer must land at the start of the EOCD record.
        long cdEnd = addExact(cdOffsetU, cdSizeU);
        if (cdEnd != eocdOffset) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }


        // Unsigned 16-bit entry count
        int entriesU = Short.toUnsignedInt(entriesTotal);

        // --- ZIP64 locator rejection: must NOT appear immediately before EOCD (T1-2) ---
        // Valid ZIP64 archives store the locator at eocdOffset - 20.
        // Any locator that would overlap the EOCD is invalid.
        if (eocdOffset >= 20) {
            byte[] beforeEocd = new byte[20];
            raf.seek(eocdOffset - 20);
            // T1-4: Use readFully; bounds check (eocdOffset >= 20) guarantees the seek
            // position is valid and we can read 20 bytes.
            raf.readFully(beforeEocd);
            ByteBuffer z64 = ByteBuffer.wrap(beforeEocd).order(ByteOrder.LITTLE_ENDIAN);
            if (z64.getInt() == ZIP64_LOCSIG) {
                throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
            }
        }


        return new Eocd(eocdOffset, cdOffsetU, cdSizeU, entriesU, entriesU);
    }

    // -------------------------------------------------------------------------
    // Phase 2: central-directory read + unsigned bounds
    // -------------------------------------------------------------------------

    /**
     * Read the central directory from the RAF and validate its extent.
     * The cdEnd computed from eocd.cdOffset + eocd.cdSize must equal the file
     * position after reading all entries (parsed pointer lands exactly at cdEnd).
     *
     * @throws ArchiveKernelException on structural failures (no path/IOException leakage)
     * @throws IOException           only on unanticipated file read errors
     */
    private List<CentralDirectoryEntry> parseCentralDirectory(
            RandomAccessFile raf, Eocd eocd)
            throws ArchiveKernelException, IOException {

        // T1-3: eocd.cdOffset() + eocd.cdSize() == eocd.eocdOffset() already
        // enforced in findAndParseEocd.  Compute cdEnd for boundary checks.
        long cdEnd = addExact(eocd.cdOffset(), eocd.cdSize());

        if (eocd.cdOffset() > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        raf.seek(eocd.cdOffset());

        List<CentralDirectoryEntry> entries = new ArrayList<>(eocd.cdEntriesTotal());
        int parsed = 0;

        while (parsed < eocd.cdEntriesTotal()) {
            entries.add(readCdEntry(raf));
            parsed++;
        }

        if (raf.getFilePointer() != cdEnd) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        return entries;
    }

    // -------------------------------------------------------------------------
    // Phase 2 (helper): read one CD entry
    // -------------------------------------------------------------------------

    /**
     * Read one central-directory entry from the current RAF position.
     *
     * @throws ArchiveKernelException on structural failures (no path/IOException leakage)
     * @throws IOException           only on unanticipated file read errors
     */
    private CentralDirectoryEntry readCdEntry(RandomAccessFile raf)
            throws ArchiveKernelException, IOException {

        long fp = raf.getFilePointer();

        if (fp + CD_HEADER_SIZE > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

        byte[] buf = new byte[CD_HEADER_SIZE];
        raf.readFully(buf);
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);

        int cenSig = bb.getInt();
        if (cenSig != CENSIG) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }
        bb.getShort(); bb.getShort(); // versionMade, versionNeeded
        int gpb           = Short.toUnsignedInt(bb.getShort());
        int method        = Short.toUnsignedInt(bb.getShort());
        bb.getShort(); bb.getShort(); // modTime, modDate
        int crcRaw         = bb.getInt();
        long compSize      = Integer.toUnsignedLong(bb.getInt());
        long uncompSize    = Integer.toUnsignedLong(bb.getInt());
        int nameLen        = Short.toUnsignedInt(bb.getShort());
        int extraLen       = Short.toUnsignedInt(bb.getShort());
        int commentLen     = Short.toUnsignedInt(bb.getShort());
        bb.getShort(); bb.getShort(); // diskStart, internalAttr
        int externalAttr   = bb.getInt();
        long localOffset   = Integer.toUnsignedLong(bb.getInt());

        // T1-3: Validate local header offset bounds.
        // Use checked-add so overflow throws.
        long entryDataStart = localOffset;
        long locHeaderEnd   = addExact(entryDataStart, LOC_HEADER_SIZE);
        long nameEnd        = addExact(locHeaderEnd, nameLen);
        long extraEnd       = addExact(nameEnd, extraLen);
        if (extraEnd > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }


        // Bounds-check name+extra+comment before reading them
        long afterHeader = raf.getFilePointer();
        long need = (long) nameLen + extraLen + commentLen;
        if (afterHeader + need > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }


        byte[] nameBytes = new byte[nameLen];
        raf.readFully(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);

        if (extraLen    > 0) raf.skipBytes(extraLen);
        if (commentLen  > 0) raf.skipBytes(commentLen);

        int unixType = externalAttr >>> 16;
        boolean isSpecial = unixType == UNIX_TYPE_BLOCK
                || unixType == UNIX_TYPE_CHAR
                || unixType == UNIX_TYPE_FIFO
                || unixType == UNIX_TYPE_SOCKET;

        return new CentralDirectoryEntry(
                nameBytes, name, method, compSize, uncompSize,
                (crcRaw & 0xFFFFFFFFL), localOffset, gpb, externalAttr,
                name.endsWith("/"),
                extraLen > 0,
                (gpb & GPB_ENCRYPTED) != 0,
                unixType == UNIX_TYPE_SYMLINK,
                isSpecial,
                name.startsWith("META-INF/versions/"),
                null);
    }

    // -------------------------------------------------------------------------
    // Phase 3: structural rejection
    // -------------------------------------------------------------------------

    private Result checkStructuralRejection(List<CentralDirectoryEntry> entries) {
        for (CentralDirectoryEntry entry : entries) {
            ArchiveKernelException rejection = structuralRejection(entry);
            if (rejection != null) {
                return new Result(entries.size(), List.of(),
                        new LimitResults(false, false, false, false, false),
                        List.of(), true, rejection.reasonCode(),
                        rejection.reasonArgs());
            }

        }

        return null;
    }

    /**
     * Returns null if the entry is structurally acceptable, otherwise the
     * appropriate AK rejection.
     *
     * Priority (checked in order): directory → extra-field → encrypted →
     * symlink → special-file → multi-release.
     */
    private ArchiveKernelException structuralRejection(CentralDirectoryEntry entry) {
        if (entry.isDirectory()) {
            return new ArchiveKernelException("AK-ENTRY-DIRECTORY",
                    Map.of("entry", entry.nameUtf8()));
        }

        if (entry.hasExtra()) {
            return new ArchiveKernelException("AK-EXTRA-FIELD",
                    Map.of("entry", entry.nameUtf8()));
        }

        if (entry.isEncrypted()) {
            return new ArchiveKernelException("AK-ENCRYPTED",
                    Map.of("entry", entry.nameUtf8()));
        }

        if (entry.isSymlink()) {
            return new ArchiveKernelException("AK-EXTERNAL-SYMLINK",
                    Map.of("entry", entry.nameUtf8()));
        }

        if (entry.isSpecialFile()) {
            return new ArchiveKernelException("AK-EXTERNAL-SPECIAL",
                    Map.of("entry", entry.nameUtf8()));
        }

        if (entry.hasMultiReleasePath()) {
            return new ArchiveKernelException("AK-MULTI-RELEASE",
                    Map.of("entry", entry.nameUtf8()));
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Phase 4: per-entry local-header rebind + hash
    // -------------------------------------------------------------------------

    private List<CentralDirectoryEntry> verifyAndHashEntries(RandomAccessFile raf,
                                       List<CentralDirectoryEntry> entries,
                                       long cdOffset)
            throws ArchiveKernelException, IOException {

        List<CentralDirectoryEntry> updated = new ArrayList<>(entries.size());
        for (CentralDirectoryEntry entry : entries) {
            StreamHasher.Result hash = hashEntryData(raf, entry, cdOffset);
            if (hash == null) {
                // DD entry without size — unverifiable; keep without hash
                updated.add(entry);
                continue;
            }

            if (hash.crc32() != entry.crc32()) {
                throw new ArchiveKernelException("AK-CRC-MISMATCH",
                        Map.of(
                                "entry",       entry.nameUtf8(),
                                "expectedCrc", entry.crc32(),
                                "actualCrc",   hash.crc32()));
            }

            updated.add(entry.withHash(hash));
        }

        return updated;
    }

    /**
     * Seeks to the local header, rebinds name/flags/method/CRC/sizes against CD,
     * and returns the stream hash.  Returns null when the entry is a DD entry
     * with zero cdSize and zero crc (unverifiable).
     *
     * @param cdOffset  central directory offset — used to enforce that entry data
     *                  stays within the local-file area (before CD)
     * @throws ArchiveKernelException on structural failures (no path/IOException leakage)
     * @throws IOException           only on unanticipated file read errors
     */
    private StreamHasher.Result hashEntryData(RandomAccessFile raf,
                                                CentralDirectoryEntry entry,
                                                long cdOffset)
            throws ArchiveKernelException, IOException {

        // T1-3: Validate local header offset bounds with checked arithmetic.
        long localOffset = entry.localHeaderOffset();
        if (localOffset > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }

        // T1-3: Validate local header is readable (30 bytes at offset).
        // Use addExact to detect overflow.
        long afterHeader = addExact(localOffset, LOC_HEADER_SIZE);
        if (afterHeader > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }

        raf.seek(localOffset);

        byte[] header = new byte[LOC_HEADER_SIZE];
        raf.readFully(header);
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        int sig = bb.getInt();          // offset 0-3: LOC signature
        if (sig != LOCSIG) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }


        bb.getShort();                  // offset 4-5:  ver needed
        short gpbLoc     = bb.getShort();   // offset 6-7:  GPB
        short methodLoc   = bb.getShort();   // offset 8-9:  compression method
        bb.getShort();                  // offset 10-11: mod time
        bb.getShort();                  // offset 12-13: mod date
        int crcLoc        = bb.getInt();     // offset 14-17: CRC-32
        long cSizeLoc     = Integer.toUnsignedLong(bb.getInt()); // offset 18-21: compressed size
        long uSizeLoc     = Integer.toUnsignedLong(bb.getInt()); // offset 22-25: uncompressed size
        int nameLenLoc    = Short.toUnsignedInt(bb.getShort());  // offset 26-27: name length
        int extraLenLoc   = Short.toUnsignedInt(bb.getShort());   // offset 28-29: extra field length

        long crcLocU = Integer.toUnsignedLong(crcLoc);

        // LC/CD rebind checks

        if (nameLenLoc != entry.nameRaw().length) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }


        // T1-3: Validate name is fully readable within file bounds before reading.
        long rafAfterHeader = addExact(localOffset, LOC_HEADER_SIZE);
        long rafAfterName   = addExact(rafAfterHeader, nameLenLoc);
        if (rafAfterName > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }

        byte[] locName = new byte[nameLenLoc];
        raf.readFully(locName);
        if (!Arrays.equals(locName, entry.nameRaw())) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }


        boolean hasDD = (gpbLoc & GPB_DATADESC) != 0;

        if (gpbLoc != entry.generalPurposeFlags()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }


        if (methodLoc != entry.compressionMethod()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }


        // CRC/size rebind: only when DD flag is NOT set.
        if (!hasDD) {
            if (crcLocU != entry.crc32()) {
                throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                        Map.of("entry", entry.nameUtf8()));
            }


            if (cSizeLoc != entry.compressedSize()) {
                throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                        Map.of("entry", entry.nameUtf8()));
            }


            if (uSizeLoc != entry.uncompressedSize()) {
                throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                        Map.of("entry", entry.nameUtf8()));
            }

        }


        // T1-3: Bounds-check extra field before reading or rejecting.
        // Bounds are verified before the extra field is examined, so malformed
        // archives with extra fields cannot read past the file boundary even
        // though extra fields are structurally rejected.
        long rafAfterExtra = addExact(rafAfterName, extraLenLoc);
        if (rafAfterExtra > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }

        // Any extra field in local header is rejected
        if (extraLenLoc > 0) {
            throw new ArchiveKernelException("AK-EXTRA-FIELD",
                    Map.of("entry", entry.nameUtf8()));
        }


        // DD policy: unverifiable when DD flag set with zero cdSize AND zero crc
        if ((entry.generalPurposeFlags() & GPB_DATADESC) != 0
                && entry.compressedSize() == 0
                && entry.crc32() == 0) {
            throw new ArchiveKernelException("AK-DD-UNVERIFIABLE",
                    Map.of("entry", entry.nameUtf8()));
        }


        // T1-3: Validate that the bound does not exceed remaining file bytes,
        // and that entry data does not cross into the central directory.
        // For non-DD entries the bound comes from the local header (already rebound to CD).
        // For DD entries the local header CRC/size are 0; use the CD value.
        long bound = hasDD ? entry.compressedSize() : cSizeLoc;
        if (bound > MAX_UNSIGNED32) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }


        // T1-3: Compute the end of the entry data region and verify it stays
        // within the local-file area (before the central directory).
        // entryDataEnd = RAF pointer after reading name + bound
        long entryDataEnd = addExact(rafAfterName, bound);

        if (entryDataEnd > raf.length()) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }

        // T1-3: Entry data must not cross into the central directory.
        if (entryDataEnd > cdOffset) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE",
                    Map.of("entry", entry.nameUtf8()));
        }


        BoundedFileInputStream bounded = new BoundedFileInputStream(raf, bound);
        return StreamHasher.hash(bounded, entry.uncompressedSize(), methodLoc);
    }

    // -------------------------------------------------------------------------
    // Phase 5: limit enforcement
    // -------------------------------------------------------------------------

    private Result evaluateLimits(List<CentralDirectoryEntry> entries, long fileSize)
            throws ArchiveKernelException {
        long totalUncomp   = 0;
        long largestEntry  = 0;
        boolean ratioHit   = false;

        for (CentralDirectoryEntry e : entries) {
            totalUncomp += e.uncompressedSize();
            if (e.uncompressedSize() > largestEntry) {
                largestEntry = e.uncompressedSize();
            }


            // T1-6: Exact comparison — reject any ratio > maxCompressionRatio,
            // including fractional values like 100.01.
            // Use quotient + remainder to avoid multiplication overflow.
            // For compressedSize=0 use max(1, cSize) per spec (DEFLATED entry
            // with cSize=0 would be a malformed or DD entry, but we guard
            // the check against division by zero with cSizeD = Math.max(1, cSize)).
            if (e.compressionMethod() == METHOD_DEFLATED && e.compressedSize() > 0) {
                long uSize = e.uncompressedSize();
                long cSize = e.compressedSize();
                long quotient  = uSize / cSize;
                long remainder = uSize % cSize;
                if (quotient > maxCompressionRatio
                        || (quotient == maxCompressionRatio && remainder > 0)) {
                    ratioHit = true;
                }

            }

        }


        if (entries.size() > maxZipEntries) {
            List<Result.EntryResult> entryResults = buildEntryResults(entries);
            return new Result(entries.size(),
                    entryResults,
                    new LimitResults(false, true, false, false, false),
                    List.of(), true, "AK-LIMIT-ZIP-ENTRIES",
                    Map.of("limit", maxZipEntries, "actual", entries.size()));
        }

        if (largestEntry > maxSingleEntryBytes) {
            List<Result.EntryResult> entryResults = buildEntryResults(entries);
            return new Result(entries.size(),
                    entryResults,
                    new LimitResults(false, false, true, false, false),
                    List.of(), true, "AK-LIMIT-SINGLE-ENTRY",
                    Map.of("limit", maxSingleEntryBytes, "actual", largestEntry));
        }

        if (totalUncomp > maxTotalUncompressed) {
            List<Result.EntryResult> entryResults = buildEntryResults(entries);
            return new Result(entries.size(),
                    entryResults,
                    new LimitResults(false, false, false, true, false),
                    List.of(), true, "AK-LIMIT-TOTAL-UNCOMPRESSED",
                    Map.of("limit", maxTotalUncompressed, "actual", totalUncomp));
        }

        if (ratioHit) {
            List<Result.EntryResult> entryResults = buildEntryResults(entries);
            return new Result(entries.size(),
                    entryResults,
                    new LimitResults(false, false, false, false, true),
                    List.of(), true, "AK-LIMIT-COMPRESSION-RATIO",
                    Map.of("maxRatio", maxCompressionRatio));
        }


        List<Result.EntryResult> entryResults = buildEntryResults(entries);
        return new Result(entries.size(), entryResults,
                new LimitResults(false, false, false, false, false),
                List.of(), false, null, null);
    }

    private List<Result.EntryResult> buildEntryResults(List<CentralDirectoryEntry> entries) {
        return entries.stream()
                .filter(e -> e.streamHash() != null)
                .map(e -> new Result.EntryResult(
                        e.nameUtf8(),
                        e.streamHash().sha256(),
                        e.streamHash().crc32(),
                        e.streamHash().uncompressedSize(),
                        e.compressedSize(),
                        e.compressionMethod()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Arithmetic helpers
    // -------------------------------------------------------------------------

    /**
     * Throw ArithmeticException if a + b overflows long.
     * Used for structural boundary validation where overflow would indicate
     * a malformed archive regardless of the actual byte values.
     */
    private static long addExact(long a, long b) throws ArchiveKernelException {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new ArchiveKernelException("AK-ZIP-STRUCTURE", null);
        }

    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static int readInt(RandomAccessFile raf) throws IOException {
        int b0 = raf.read(); int b1 = raf.read();
        int b2 = raf.read(); int b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new IOException("Unexpected EOF");
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }

    // -------------------------------------------------------------------------
    // Core verify
    // -------------------------------------------------------------------------

    /**
     * Core verification path.
     *
     * @throws ArchiveKernelException on any structural failure
     * @throws IOException           only on unanticipated file read errors
     */
    private Result verify(RandomAccessFile raf)
            throws ArchiveKernelException, IOException {

        long fileSize = raf.length();

        // T1-Fix: Check raw-bytes limit before any ZIP parsing (structural phase).
        // fileSize is a trusted value from RandomAccessFile.length().
        if (fileSize > maxRawBytes) {
            return new Result(0, List.of(),
                    new LimitResults(true, false, false, false, false),
                    List.of(), true, "AK-LIMIT-RAW-BYTES",
                    Map.of("limit", maxRawBytes, "actual", fileSize));
        }

        // --- Phase 1: EOCD ---
        Eocd eocd = findAndParseEocd(raf, fileSize);

        // --- Phase 2: Central directory ---
        List<CentralDirectoryEntry> entries = parseCentralDirectory(raf, eocd);

        // --- Phase 3: Structural rejection ---
        Result structural = checkStructuralRejection(entries);
        if (structural != null) return structural;

        // --- Phase 4: Per-entry rebind + hash ---
        List<CentralDirectoryEntry> hashed = verifyAndHashEntries(raf, entries, eocd.cdOffset());

        // --- Phase 5: Limit checks ---
        return evaluateLimits(hashed, fileSize);
    }

    // -------------------------------------------------------------------------
    // Bounded RAF wrapper
    // -------------------------------------------------------------------------

    private static final class BoundedFileInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long remaining;

        BoundedFileInputStream(RandomAccessFile raf, long bound) {
            this.raf = raf;
            this.remaining = bound;
        }


        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = raf.read();
            if (b >= 0) remaining--;
            return b;
        }


        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = raf.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }


        @Override public long skip(long n) throws IOException {
            long s = Math.min(n, remaining);
            raf.seek(raf.getFilePointer() + s);
            remaining -= s;
            return s;
        }


        @Override public int available() {
            return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
        }

    }
}
