package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exact-closure checker for ZIP archives.
 *
 * <p>Validates that an archive's entry list satisfies an exact-closure constraint:
 * <ul>
 *   <li>Every entry passes {@link PathValidator} path checks.</li>
 *   <li>The required entry list supplied by the caller is non-null, unique, and element-non-null.</li>
 *   <li>No duplicate entry names exist (after path validation).</li>
 *   <li>All required entries are present (no missing).</li>
 *   <li>Entry count matches required count (pure cardinality mismatch).</li>
 *   <li>No extra entries exist that are not in the required list.</li>
 *   <li>Total entry count does not exceed {@code maxZipEntries}.</li>
 * </ul>
 *
 * <p><strong>Priority ordering for first-offending entry (deterministic):</strong>
 * UTF-8 name sort ascending among the candidate offending entries for the selected
 * reason code.
 *
 * <p><strong>Reason code priority:</strong>
 * path, duplicate, missing, count-mismatch, extra.
 *
 * <p><strong>Stable reason-code arg fields (frozen protocol):</strong>
 * <ul>
 *   <li>MISSING: {@code entryName}</li>
 *   <li>EXTRA: {@code entryName}</li>
 *   <li>DUPLICATE: {@code entryName}, {@code count}</li>
 *   <li>COUNT_MISMATCH: {@code actualCount}</li>
 *   <li>AK-LIMIT-ZIP-ENTRIES: {@code actualCount}, {@code limit}</li>
 * </ul>
 *
 * <p>All violation types are computed in one pass; every result carries complete
 * diagnostic lists (sorted) regardless of which reason was selected.
 *
 * <p>Programming errors (null required list, null element, duplicate in required)
 * throw unchecked exceptions with no entry values in messages.
 *
 * @see PathValidator
 * @see ExactClosureResult
 */
public final class ExactClosureChecker {

    private final int maxZipEntries;

    public ExactClosureChecker(int maxZipEntries) {
        if (maxZipEntries <= 0) {
            throw new IllegalArgumentException(
                    "maxZipEntries must be positive: " + maxZipEntries);
        }
        this.maxZipEntries = maxZipEntries;
    }

    public ExactClosureChecker() {
        this(512);
    }

    public ExactClosureResult check(List<CentralDirectoryEntry> entries,
                                  List<String> required) {
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(required, "required must not be null");
        checkRequiredList(required);

        // Phase 1: path validation
        List<PathCheckResult> pathResults = entries.stream()
                .map(e -> PathValidator.validate(e.nameRaw()))
                .toList();

        List<Violation> pathViolations = new ArrayList<>();
        for (int i = 0; i < pathResults.size(); i++) {
            if (!pathResults.get(i).passed()) {
                PathCheckResult pr = pathResults.get(i);
                pathViolations.add(new Violation(
                        pr.firstReason(),
                        pr.nameUtf8(),
                        pr.firstArgs()));
            }
        }

        if (!pathViolations.isEmpty()) {
            pathViolations.sort(Comparator.comparing(v -> v.name));
            Violation first = pathViolations.get(0);
            // validEntries = count of path-passed entries
            int validEntries = (int) pathResults.stream().filter(PathCheckResult::passed).count();
            return new ExactClosureResult(
                    true, first.reason, first.args,
                    entries.size(), validEntries,
                    List.of(), List.of(), List.of());
        }

        int totalEntries = entries.size();
        List<String> allPaths = entries.stream()
                .map(CentralDirectoryEntry::nameUtf8)
                .toList();
        Set<String> validSet = new LinkedHashSet<>(allPaths);

        // Phase 2: duplicates
        Map<String, Long> counts = allPaths.stream()
                .collect(Collectors.groupingBy(p -> p, Collectors.counting()));
        List<String> duplicates = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        List<Violation> duplicateViolations = new ArrayList<>();
        for (String dup : duplicates) {
            duplicateViolations.add(new Violation(
                    "AK-ENTRY-DUPLICATE",
                    dup,
                    Map.of("entryName", dup, "count", counts.get(dup))));
        }

        if (!duplicateViolations.isEmpty()) {
            Violation first = duplicateViolations.get(0);
            return new ExactClosureResult(
                    true, first.reason, first.args,
                    totalEntries, totalEntries,
                    duplicates, List.of(), List.of());
        }

        // Phase 3-5: compute all violation sets once, sorted
        Set<String> requiredSet = new LinkedHashSet<>(required);
        List<String> sortedMissing = required.stream()
                .filter(r -> !validSet.contains(r))
                .sorted()
                .toList();
        List<String> sortedExtra = validSet.stream()
                .filter(v -> !requiredSet.contains(v))
                .sorted()
                .toList();
        boolean countsDiffer = validSet.size() != required.size();

        // Phase 6: entry count limit
        if (totalEntries > maxZipEntries) {
            return new ExactClosureResult(
                    true, "AK-LIMIT-ZIP-ENTRIES",
                    Map.of("actual", totalEntries, "limit", maxZipEntries),
                    totalEntries, totalEntries,
                    List.of(), sortedMissing, sortedExtra);
        }

        // Priority 3: missing
        if (!sortedMissing.isEmpty()) {
            String firstMissing = sortedMissing.get(0);
            return new ExactClosureResult(
                    true, "AK-ENTRY-MISSING",
                    Map.of("entryName", firstMissing),
                    totalEntries, totalEntries,
                    List.of(), sortedMissing, sortedExtra);
        }

        // Priority 4: pure count mismatch (all required present, counts differ)
        if (countsDiffer) {
            // reasonArgs: actualCount only (per frozen protocol)
            return new ExactClosureResult(
                    true, "AK-ENTRY-COUNT-MISMATCH",
                    Map.of("actualCount", validSet.size()),
                    totalEntries, totalEntries,
                    List.of(), List.of(), sortedExtra);
        }

        // Priority 5: extra
        if (!sortedExtra.isEmpty()) {
            String firstExtra = sortedExtra.get(0);
            return new ExactClosureResult(
                    true, "AK-ENTRY-EXTRA",
                    Map.of("entryName", firstExtra),
                    totalEntries, totalEntries,
                    List.of(), List.of(), sortedExtra);
        }

        return ExactClosureResult.closed(totalEntries, totalEntries);
    }

    private void checkRequiredList(List<String> required) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < required.size(); i++) {
            String r = required.get(i);
            if (r == null) {
                throw new NullPointerException(
                        "required element at index " + i + " is null");
            }
            if (!seen.add(r)) {
                throw new IllegalArgumentException(
                        "required list contains duplicate at index " + i);
            }
        }
    }

    private record Violation(
            String reason,
            String name,
            Map<String, Object> args) {}
}
