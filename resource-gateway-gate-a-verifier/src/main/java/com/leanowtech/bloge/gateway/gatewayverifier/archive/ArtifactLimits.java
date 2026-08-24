package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.Objects;

/**
 * Immutable container for five archive-size limits used by T3 artifact binding.
 *
 * <p>All limit fields are validated to be non-negative at construction.
 * Zero is a valid limit (useful for disabling a particular check).
 *
 * <p>This record is the input to {@link ArtifactLimitsChecker} and
 * represents the policy contract between the gate and the T3 plan binder.
 *
 * @param maxRawBytes           maximum total raw archive byte count
 * @param maxZipEntries        maximum number of ZIP entries
 * @param maxSingleEntryBytes maximum bytes for any single entry
 * @param maxTotalUncompressed maximum total uncompressed bytes across all entries
 * @param maxCompressionRatio  maximum compression ratio (uncompressed / compressed),
 *                              expressed as a decimal (e.g., 1 = 1:1 ratio, 100 = 100:1 ratio)
 */
public final class ArtifactLimits {

    public static final long MAX_RAW_BYTES_DEFAULT           = 16 * 1024 * 1024L;
    public static final long MAX_ZIP_ENTRIES_DEFAULT        = 512;
    public static final long MAX_SINGLE_ENTRY_BYTES_DEFAULT  = 8 * 1024 * 1024L;
    public static final long MAX_TOTAL_UNCOMPRESSED_DEFAULT = 64 * 1024 * 1024L;
    // Default 100 means 100:1 ratio (u/c = 100); 1 means 1:1 ratio
    public static final long MAX_COMPRESSION_RATIO_DEFAULT  = 100;

    private final long maxRawBytes;
    private final long maxZipEntries;
    private final long maxSingleEntryBytes;
    private final long maxTotalUncompressed;
    private final long maxCompressionRatio;

    /**
     * Canonical constructor with full validation.
     *
     * @throws IllegalArgumentException if any limit is negative
     */
    public ArtifactLimits(
            long maxRawBytes,
            long maxZipEntries,
            long maxSingleEntryBytes,
            long maxTotalUncompressed,
            long maxCompressionRatio
    ) {
        this.maxRawBytes          = validateNonNegative("maxRawBytes", maxRawBytes);
        this.maxZipEntries        = validateNonNegative("maxZipEntries", maxZipEntries);
        this.maxSingleEntryBytes  = validateNonNegative("maxSingleEntryBytes", maxSingleEntryBytes);
        this.maxTotalUncompressed = validateNonNegative("maxTotalUncompressed", maxTotalUncompressed);
        this.maxCompressionRatio   = validateNonNegative("maxCompressionRatio", maxCompressionRatio);
    }

    /**
     * Convenience factory using default values for unspecified limits.
     * All five limits are set to their documented defaults.
     */
    public static ArtifactLimits defaults() {
        return new ArtifactLimits(
                MAX_RAW_BYTES_DEFAULT,
                MAX_ZIP_ENTRIES_DEFAULT,
                MAX_SINGLE_ENTRY_BYTES_DEFAULT,
                MAX_TOTAL_UNCOMPRESSED_DEFAULT,
                MAX_COMPRESSION_RATIO_DEFAULT
        );
    }

    private static long validateNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative, got: " + value);
        }
        return value;
    }

    public long maxRawBytes()           { return maxRawBytes; }
    public long maxZipEntries()         { return maxZipEntries; }
    public long maxSingleEntryBytes()  { return maxSingleEntryBytes; }
    public long maxTotalUncompressed()  { return maxTotalUncompressed; }
    public long maxCompressionRatio()   { return maxCompressionRatio; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArtifactLimits that = (ArtifactLimits) o;
        return maxRawBytes == that.maxRawBytes
                && maxZipEntries == that.maxZipEntries
                && maxSingleEntryBytes == that.maxSingleEntryBytes
                && maxTotalUncompressed == that.maxTotalUncompressed
                && maxCompressionRatio == that.maxCompressionRatio;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                maxRawBytes, maxZipEntries, maxSingleEntryBytes,
                maxTotalUncompressed, maxCompressionRatio);
    }

    @Override
    public String toString() {
        return "ArtifactLimits{" +
                "maxRawBytes=" + maxRawBytes +
                ", maxZipEntries=" + maxZipEntries +
                ", maxSingleEntryBytes=" + maxSingleEntryBytes +
                ", maxTotalUncompressed=" + maxTotalUncompressed +
                ", maxCompressionRatio=" + maxCompressionRatio +
                '}';
    }
}
