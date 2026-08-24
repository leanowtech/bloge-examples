package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.List;
import java.util.Objects;

/**
 * Checks archive metrics against a set of {@link ArtifactLimits}.
 *
 * <p>This class evaluates all five limits and produces a single
 * {@link ArtifactLimitsResult} containing the status of each check.
 *
 * <p>All checks are performed atomically with checked arithmetic to prevent overflow.
 * The result's {@link ArtifactLimitsResult#firstRejectedCode()} returns the
 * first violated limit in stable priority order.
 *
 * <p>Compression ratio is dimensionless: {@code u / max(1, c)} where u is uncompressed
 * size and c is compressed size. No multiplication by 100.
 *
 * <p>This class is stateless and thread-safe.
 */
public final class ArtifactLimitsChecker {

    private final ArtifactLimits limits;

    public ArtifactLimitsChecker(ArtifactLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    /**
     * Checks all five limits against the provided metrics and recomputes
     * totalUncompressed from entries with checked arithmetic.
     *
     * @param rawBytes    total raw archive byte count (must be non-negative)
     * @param zipEntries number of ZIP entries (must be non-negative)
     * @param entries   per-entry results from the ZIP verifier (must not be null,
     *                   must not contain null elements)
     * @return immutable result with per-limit booleans
     * @throws IllegalArgumentException if rawBytes or zipEntries are negative
     * @throws NullPointerException   if entries or any element is null
     */
    public ArtifactLimitsResult check(
            long rawBytes,
            int zipEntries,
            List<ZipArchiveVerifier.Result.EntryResult> entries
    ) {
        // Validate inputs - fail closed
        if (rawBytes < 0) {
            throw new IllegalArgumentException("rawBytes must be non-negative: " + rawBytes);
        }
        if (zipEntries < 0) {
            throw new IllegalArgumentException("zipEntries must be non-negative: " + zipEntries);
        }
        Objects.requireNonNull(entries, "entries must not be null");

        // Validate all entries for null/u/c BEFORE recompute so negatives cannot
        // contaminate or cancel in the total sum.
        validateEntries(entries);

        // Recompute totalUncompressed from entries with checked arithmetic.
        // Overflow triggers Math.addExact → ArithmeticException.
        long totalUncompressed = recomputeTotalUncompressed(entries);

        // 1. Check raw bytes (priority 1)
        boolean rawBytesExceeded = rawBytes > limits.maxRawBytes();

        // 2. Check zip entries (priority 2)
        boolean zipEntriesExceeded = (long) zipEntries > limits.maxZipEntries();

        // 3. Check single entry — any entry exceeds limit (priority 3)
        boolean singleEntryExceeded = checkSingleEntry(entries);

        // 4. Check total uncompressed (priority 4)
        boolean totalUncompressedExceeded = totalUncompressed > limits.maxTotalUncompressed();

        // 5. Check compression ratio (priority 5)
        boolean compressionRatioExceeded = checkCompressionRatio(entries);

        return new ArtifactLimitsResult(
                rawBytesExceeded,
                zipEntriesExceeded,
                singleEntryExceeded,
                totalUncompressedExceeded,
                compressionRatioExceeded
        );
    }

    /**
     * Validates every entry: null element, negative uncompressedSize, negative compressedSize.
     * Called before recompute so negatives cannot contaminate or cancel in the total sum.
     *
     * @param entries must not be null; no null elements allowed
     * @throws NullPointerException     if entries is null or contains a null element
     * @throws IllegalArgumentException if any entry has a negative size
     */
    private void validateEntries(List<ZipArchiveVerifier.Result.EntryResult> entries) {
        for (ZipArchiveVerifier.Result.EntryResult entry : entries) {
            Objects.requireNonNull(entry, "entries must not contain null elements");
            long u = entry.uncompressedSize();
            long c = entry.compressedSize();
            if (u < 0) {
                throw new IllegalArgumentException(
                        "entry uncompressedSize must be non-negative: " + u);
            }
            if (c < 0) {
                throw new IllegalArgumentException(
                        "entry compressedSize must be non-negative: " + c);
            }
        }
    }

    /**
     * Recomputes total uncompressed bytes from entries using checked arithmetic.
     * Overflow causes Math.addExact to throw ArithmeticException.
     * Caller must have already validated entries via {@link #validateEntries(List)}.
     */
    private long recomputeTotalUncompressed(List<ZipArchiveVerifier.Result.EntryResult> entries) {
        long total = 0;
        for (ZipArchiveVerifier.Result.EntryResult entry : entries) {
            // validateEntries guarantees no null elements.
            total = Math.addExact(total, entry.uncompressedSize());
        }
        return total;
    }

    /**
     * Checks if any single entry exceeds the per-entry limit.
     * Negative u/c are already validated by {@link #validateEntries(List)}.
     */
    private boolean checkSingleEntry(List<ZipArchiveVerifier.Result.EntryResult> entries) {
        long maxSingle = limits.maxSingleEntryBytes();
        for (ZipArchiveVerifier.Result.EntryResult entry : entries) {
            if (entry.uncompressedSize() > maxSingle) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if any entry exceeds the compression ratio limit.
     *
     * <p>Dimensionless ratio: {@code u / max(1, c)}.
     * Reject when {@code u / max(1, c) > maxRatio},
     * or when quotient equals maxRatio with a positive remainder.
     * Negative u/c are already validated by {@link #validateEntries(List)}.
     *
     * @param entries all ZIP entries; must not contain null elements
     * @return true if any entry exceeds the compression ratio limit
     */
    private boolean checkCompressionRatio(List<ZipArchiveVerifier.Result.EntryResult> entries) {
        long maxRatio = limits.maxCompressionRatio();
        for (ZipArchiveVerifier.Result.EntryResult entry : entries) {
            long c = entry.compressedSize();
            long u = entry.uncompressedSize();
            // Denominator: max(1, c) so c=0 yields den=1, avoiding div-by-zero.
            long den = Math.max(1, c);
            // Exact quotient/remainder: reject iff u/den > maxRatio
            // OR (quotient == maxRatio AND remainder > 0).
            // This naturally handles maxRatio == 0.
            long quotient = u / den;
            if (quotient > maxRatio) {
                return true;
            }
            if (quotient == maxRatio) {
                long remainder = u % den;
                if (remainder > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
