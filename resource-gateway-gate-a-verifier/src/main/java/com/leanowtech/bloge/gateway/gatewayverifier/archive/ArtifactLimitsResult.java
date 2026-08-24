package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of an artifact-limits check performed by {@link ArtifactLimitsChecker}.
 *
 * <p>Results are checked in stable AK-LIMIT-* priority order:
 * <ol>
 *   <li>{@code rawBytes} — AK-LIMIT-RAW-BYTES</li>
 *   <li>{@code zipEntries} — AK-LIMIT-ZIP-ENTRIES</li>
 *   <li>{@code singleEntry} — AK-LIMIT-SINGLE-ENTRY</li>
 *   <li>{@code totalUncompressed} — AK-LIMIT-TOTAL-UNCOMPRESSED</li>
 *   <li>{@code compressionRatio} — AK-LIMIT-COMPRESSION-RATIO</li>
 * </ol>
 *
 * <p>The first non-rejected check in priority order is the returned reason.
 * If all checks pass, the result is not rejected.
 *
 * <p>All fields are immutable and all collections are defensively copied.
 */
public final class ArtifactLimitsResult {

    // Stable priority order: index 0 = highest priority
    private static final String[] PRIORITY_CODES = {
            "AK-LIMIT-RAW-BYTES",
            "AK-LIMIT-ZIP-ENTRIES",
            "AK-LIMIT-SINGLE-ENTRY",
            "AK-LIMIT-TOTAL-UNCOMPRESSED",
            "AK-LIMIT-COMPRESSION-RATIO"
    };

    private final boolean rawBytes;
    private final boolean zipEntries;
    private final boolean singleEntry;
    private final boolean totalUncompressed;
    private final boolean compressionRatio;

    private final transient String cachedFirstRejectionCode;

    public ArtifactLimitsResult(
            boolean rawBytes,
            boolean zipEntries,
            boolean singleEntry,
            boolean totalUncompressed,
            boolean compressionRatio
    ) {
        this.rawBytes = rawBytes;
        this.zipEntries = zipEntries;
        this.singleEntry = singleEntry;
        this.totalUncompressed = totalUncompressed;
        this.compressionRatio = compressionRatio;

        this.cachedFirstRejectionCode = computeFirstRejectionCode();
    }

    private String computeFirstRejectionCode() {
        if (rawBytes) return "AK-LIMIT-RAW-BYTES";
        if (zipEntries) return "AK-LIMIT-ZIP-ENTRIES";
        if (singleEntry) return "AK-LIMIT-SINGLE-ENTRY";
        if (totalUncompressed) return "AK-LIMIT-TOTAL-UNCOMPRESSED";
        if (compressionRatio) return "AK-LIMIT-COMPRESSION-RATIO";
        return null;
    }

    public boolean rawBytes()           { return rawBytes; }
    public boolean zipEntries()         { return zipEntries; }
    public boolean singleEntry()        { return singleEntry; }
    public boolean totalUncompressed()  { return totalUncompressed; }
    public boolean compressionRatio()   { return compressionRatio; }

    /** Returns true if any limit was exceeded. */
    public boolean isRejected() {
        return rawBytes || zipEntries || singleEntry
                || totalUncompressed || compressionRatio;
    }

    /**
     * Returns the first rejection code in stable priority order, or null if all passed.
     */
    public String firstRejectedCode() {
        return cachedFirstRejectionCode;
    }

    /**
     * Returns a list of rejection codes for all limits that are actually true,
     * in stable priority order.
     */
    public List<String> allRejectedCodes() {
        List<String> codes = new ArrayList<>(5);
        if (rawBytes)           codes.add("AK-LIMIT-RAW-BYTES");
        if (zipEntries)         codes.add("AK-LIMIT-ZIP-ENTRIES");
        if (singleEntry)        codes.add("AK-LIMIT-SINGLE-ENTRY");
        if (totalUncompressed)   codes.add("AK-LIMIT-TOTAL-UNCOMPRESSED");
        if (compressionRatio)    codes.add("AK-LIMIT-COMPRESSION-RATIO");
        return List.copyOf(codes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArtifactLimitsResult that = (ArtifactLimitsResult) o;
        return rawBytes == that.rawBytes
                && zipEntries == that.zipEntries
                && singleEntry == that.singleEntry
                && totalUncompressed == that.totalUncompressed
                && compressionRatio == that.compressionRatio;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawBytes, zipEntries, singleEntry, totalUncompressed, compressionRatio);
    }

    @Override
    public String toString() {
        return "ArtifactLimitsResult{" +
                "rawBytes=" + rawBytes +
                ", zipEntries=" + zipEntries +
                ", singleEntry=" + singleEntry +
                ", totalUncompressed=" + totalUncompressed +
                ", compressionRatio=" + compressionRatio +
                '}';
    }
}
