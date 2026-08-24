package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of an exact-closure check performed by {@link ExactClosureChecker}.
 *
 * <p>Closure semantics:
 * <ul>
 *   <li>An archive <em>passes</em> only when all required entries are present,
 *       no duplicates exist, and no extra entries are present.</li>
 *   <li>An entry is <em>reachable</em> if it is not rejected by path validation.</li>
 *   <li>Rejections are reported in deterministic priority order:
 *       path, duplicate, missing, count-mismatch, extra.</li>
 * </ul>
 *
 * <p>Priority stable-ordering rule for first-offending entry:
 * UTF-8 name sort ascending (lexicographic on the validated path string).
 *
 * <p>Reason code argument naming (frozen protocol):
 * <ul>
 *   <li>AK-ENTRY-MISSING, AK-ENTRY-EXTRA: {@code entryName} (String)</li>
 *   <li>AK-ENTRY-DUPLICATE: {@code entryName} (String) and {@code count} (long)</li>
 *   <li>AK-ENTRY-COUNT-MISMATCH: {@code actualCount} (int)</li>
 *   <li>AK-LIMIT-ZIP-ENTRIES: {@code actual} (int) and {@code limit} (int)</li>
 * </ul>
 *
 * @param rejected     true if any closure constraint was violated
 * @param reasonCode   first rejection code; null if closed
 * @param reasonArgs   structured args for reasonCode; null if closed
 * @param totalEntries total number of entries in the archive
 * @param validEntries number of entries that passed path validation
 * @param duplicates   list of duplicate entry names (after path validation)
 * @param missing      list of required entry names not found
 * @param extra        list of extra entry names not in required list
 */
public record ExactClosureResult(
        boolean rejected,
        String reasonCode,
        Map<String, Object> reasonArgs,
        int totalEntries,
        int validEntries,
        List<String> duplicates,
        List<String> missing,
        List<String> extra
) {

    /** Result for a closed (valid) archive with no violations. */
    public static ExactClosureResult closed(int totalEntries, int validEntries) {
        return new ExactClosureResult(
                false, null, null,
                totalEntries, validEntries,
                List.of(), List.of(), List.of());
    }

    /**
     * Canonical constructor with defensive copies.
     */
    public ExactClosureResult {
        Objects.requireNonNull(duplicates, "duplicates must not be null");
        Objects.requireNonNull(missing, "missing must not be null");
        Objects.requireNonNull(extra, "extra must not be null");
        duplicates = List.copyOf(duplicates);
        missing = List.copyOf(missing);
        extra = List.copyOf(extra);
        reasonArgs = reasonArgs != null ? Map.copyOf(reasonArgs) : null;
    }

    /** Convenience: returns the first duplicate, or null if none. */
    public String firstDuplicate() {
        return duplicates.isEmpty() ? null : duplicates.getFirst();
    }

    /** Convenience: returns the first missing, or null if none. */
    public String firstMissing() {
        return missing.isEmpty() ? null : missing.getFirst();
    }

    /** Convenience: returns the first extra, or null if none. */
    public String firstExtra() {
        return extra.isEmpty() ? null : extra.getFirst();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExactClosureResult that = (ExactClosureResult) o;
        return rejected == that.rejected
                && totalEntries == that.totalEntries
                && validEntries == that.validEntries
                && Objects.equals(reasonCode, that.reasonCode)
                && Objects.equals(reasonArgs, that.reasonArgs)
                && duplicates.equals(that.duplicates)
                && missing.equals(that.missing)
                && extra.equals(that.extra);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rejected, reasonCode, reasonArgs, totalEntries,
                validEntries, duplicates, missing, extra);
    }
}
