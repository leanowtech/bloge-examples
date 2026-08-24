package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable snapshot of one ZIP central-directory entry, plus optional
 * per-entry content-verification results populated by
 * {@link ZipArchiveVerifier#verifyAndHashEntries}.
 *
 * <p>All mutability vectors for the raw name byte-array are sealed:
 * <ul>
 *   <li>The canonical constructor defensively clones the supplied {@code nameRaw}.</li>
 *   <li>The {@link #nameRaw()} accessor returns a fresh clone.</li>
 *   <li>The {@link #withHash} and {@link #cleared} copy factories clone {@code nameRaw}.</li>
 * </ul>
 *
 * @param nameRaw               original byte[] from the central-directory record
 * @param nameUtf8              entry name decoded as UTF-8
 * @param compressionMethod     0 (STORED) or 8 (DEFLATED); negative = unset
 * @param compressedSize        value from central directory
 * @param uncompressedSize      value from central directory
 * @param crc32                 value from central directory
 * @param localHeaderOffset     offset to the matching local file header
 * @param generalPurposeFlags   raw GPB flags word
 * @param externalAttributes    raw external-attributes word
 * @param isDirectory           true when the UTF-8 name ends with '/'
 * @param hasExtra              true when local header or CD contains an extra field
 * @param isEncrypted           true when encryption bit (GPB bit 0) is set
 * @param isSymlink             true when external attributes declare a Unix symlink
 * @param isSpecialFile         true when external attributes declare a non-regular-file special file
 * @param hasMultiReleasePath   true when name starts with "META-INF/versions/"
 * @param streamHash            null until populated by {@link ZipArchiveVerifier#verifyAndHashEntries}
 */
public record CentralDirectoryEntry(
        byte[] nameRaw,
        String nameUtf8,
        int compressionMethod,
        long compressedSize,
        long uncompressedSize,
        long crc32,
        long localHeaderOffset,
        int generalPurposeFlags,
        long externalAttributes,
        boolean isDirectory,
        boolean hasExtra,
        boolean isEncrypted,
        boolean isSymlink,
        boolean isSpecialFile,
        boolean hasMultiReleasePath,
        StreamHasher.Result streamHash
) {

    /**
     * Canonical constructor: defensively clones {@code nameRaw} to prevent
     * the caller's array from aliasing the internal state.
     *
     * @throws NullPointerException if {@code nameRaw} is {@code null}
     */
    public CentralDirectoryEntry {
        Objects.requireNonNull(nameRaw, "nameRaw must not be null");
        nameRaw = nameRaw.clone();
        nameUtf8 = Objects.requireNonNull(nameUtf8, "nameUtf8 must not be null");
    }

    /**
     * Returns a <em>clone</em> of the raw name bytes.
     * Callers may mutate the returned array without affecting the record's
     * internal state.
     */
    @Override
    public byte[] nameRaw() {
        // Defensive clone: prevents caller from aliasing the internal array.
        return this.nameRaw.clone();
    }

    /**
     * Returns a copy of this entry with {@code streamHash} populated.
     * Used by the verifier after content hashing completes.
     * The raw name array is cloned to maintain immutability.
     */
    public CentralDirectoryEntry withHash(StreamHasher.Result hash) {
        return new CentralDirectoryEntry(
                this.nameRaw.clone(), nameUtf8, compressionMethod,
                compressedSize, uncompressedSize, crc32,
                localHeaderOffset, generalPurposeFlags, externalAttributes,
                isDirectory, hasExtra, isEncrypted, isSymlink, isSpecialFile,
                hasMultiReleasePath, hash);
    }

    /**
     * Returns a copy of this entry with all structural flags cleared
     * (directory/extra/encrypted/symlink/special/multi-release).
     * Used when T2/T3 need a clean view after structural rejection.
     * The raw name array is cloned to maintain immutability.
     */
    public CentralDirectoryEntry cleared() {
        return new CentralDirectoryEntry(
                this.nameRaw.clone(), nameUtf8, compressionMethod,
                compressedSize, uncompressedSize, crc32,
                localHeaderOffset, generalPurposeFlags, externalAttributes,
                false, false, false, false, false, false, streamHash);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CentralDirectoryEntry that = (CentralDirectoryEntry) o;
        return compressionMethod == that.compressionMethod
                && compressedSize == that.compressedSize
                && uncompressedSize == that.uncompressedSize
                && crc32 == that.crc32
                && localHeaderOffset == that.localHeaderOffset
                && generalPurposeFlags == that.generalPurposeFlags
                && externalAttributes == that.externalAttributes
                && isDirectory == that.isDirectory
                && hasExtra == that.hasExtra
                && isEncrypted == that.isEncrypted
                && isSymlink == that.isSymlink
                && isSpecialFile == that.isSpecialFile
                && hasMultiReleasePath == that.hasMultiReleasePath
                && Arrays.equals(nameRaw, that.nameRaw)
                && Objects.equals(nameUtf8, that.nameUtf8)
                && Objects.equals(streamHash, that.streamHash);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(nameUtf8, compressionMethod, compressedSize,
                uncompressedSize, crc32, localHeaderOffset, generalPurposeFlags,
                externalAttributes, isDirectory, hasExtra, isEncrypted, isSymlink,
                isSpecialFile, hasMultiReleasePath, streamHash);
        result = 31 * result + Arrays.hashCode(nameRaw);
        return result;
    }
}
