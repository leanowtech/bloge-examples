package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable result of a single-entry path validation performed by {@link PathValidator}.
 *
 * <p>Each record carries:
 * <ul>
 *   <li>the raw bytes and UTF-8 decode of the entry name,</li>
 *   <li>a non-null, non-empty list of zero or more rejection reason codes,</li>
 *   <li>structured args for each rejection, keyed by reason code.</li>
 * </ul>
 *
 * <p>Reason code argument naming (frozen protocol):
 * <ul>
 *   <li>AK-PATH-NUL, AK-PATH-ABSOLUTE, AK-PATH-BACKSLASH, AK-PATH-DOT-SEGMENT:
 *       {@code entryName} (String)</li>
 *   <li>AK-PATH-NFC-MISMATCH: {@code entryName} (String) and {@code decodedForm} (String)</li>
 * </ul>
 *
 * @param nameRaw   original byte[] from the central directory (defensive copy on construction)
 * @param nameUtf8  entry name decoded as UTF-8
 * @param reasons   ordered list of all rejection codes; empty = pass
 * @param reasonArgs per-reason structured args map; may be null when reasons is empty.
 *                   Both the outer map and every inner map are deep-copied on construction,
 *                   so the caller's maps and any inner maps obtained from accessors are
 *                   immutable.
 */
public record PathCheckResult(
        byte[] nameRaw,
        String nameUtf8,
        List<String> reasons,
        Map<String, Map<String, Object>> reasonArgs
) {

    /**
     * Convenience constructor for a passing entry (no rejections).
     *
     * @param nameRaw  original byte[] (cloned internally)
     * @param nameUtf8 UTF-8 decoded name
     */
    public PathCheckResult(byte[] nameRaw, String nameUtf8) {
        this(Objects.requireNonNull(nameRaw, "nameRaw must not be null").clone(),
                Objects.requireNonNull(nameUtf8, "nameUtf8 must not be null"),
                List.of(),
                null);
    }

    /**
     * Full constructor for a failing entry.
     *
     * @param nameRaw    original byte[] (cloned internally)
     * @param nameUtf8   UTF-8 decoded name
     * @param reasons    ordered list of reason codes (cloned internally)
     * @param reasonArgs per-reason args (deep-copied: both outer map and all inner maps)
     * @throws NullPointerException if nameRaw or nameUtf8 is null
     * @throws NullPointerException if reasons is null
     */
    public PathCheckResult {
        Objects.requireNonNull(nameRaw, "nameRaw must not be null");
        nameRaw = nameRaw.clone();
        Objects.requireNonNull(nameUtf8, "nameUtf8 must not be null");
        Objects.requireNonNull(reasons, "reasons must not be null");
        reasons = List.copyOf(reasons);
        reasonArgs = reasonArgs != null ? deepCopyReasonArgs(reasonArgs) : null;
    }

    /**
     * Returns a deep-copied, fully-immutable view of the nested reasonArgs map.
     * Both the outer map and every inner map are wrapped via {@link Map#copyOf},
     * preventing any caller mutation of maps supplied at construction time
     * and preventing mutation of inner maps returned by accessors.
     */
    private static Map<String, Map<String, Object>> deepCopyReasonArgs(
            Map<String, Map<String, Object>> original) {
        return original.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> Map.copyOf(e.getValue())));
    }

    /** Returns true when this entry passed all path checks. */
    public boolean passed() {
        return reasons.isEmpty();
    }

    /** Returns the first reason code, or null if passed. */
    public String firstReason() {
        return reasons.isEmpty() ? null : reasons.getFirst();
    }

    /** Returns args for the first reason, or null if passed. */
    public Map<String, Object> firstArgs() {
        if (reasons.isEmpty() || reasonArgs == null) return null;
        return reasonArgs.get(reasons.getFirst());
    }

    @Override
    public byte[] nameRaw() {
        return nameRaw.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathCheckResult that = (PathCheckResult) o;
        return nameUtf8.equals(that.nameUtf8)
                && reasons.equals(that.reasons)
                && Objects.equals(reasonArgs, that.reasonArgs)
                && java.util.Arrays.equals(nameRaw, that.nameRaw);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(nameUtf8, reasons, reasonArgs);
        result = 31 * result + java.util.Arrays.hashCode(nameRaw);
        return result;
    }
}
