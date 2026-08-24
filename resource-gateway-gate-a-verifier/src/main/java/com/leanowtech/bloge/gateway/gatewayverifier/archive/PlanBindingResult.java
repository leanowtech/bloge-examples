package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of binding a packaging plan to archive entry content.
 *
 * <p>Contains exactly 7 bound results (one per dependency declared in
 * {@link PackagingPlanBinding}), sorted in stable deterministic order
 * by the stable argument key AK-NESTED-JAR-SHA256.
 *
 * <p>The result distinguishes three classes of outcomes:
 * <ul>
 *   <li>{@code planMismatch}: the plan fingerprint does not match</li>
 *   <li>{@code countMismatch}: the number of resolved dependencies differs from expected</li>
 *   <li>{@code boundResults}: exactly 7 immutable dependency bindings</li>
 * </ul>
 *
 * <p>Frozen wire-contract args:
 * <ul>
 *   <li>AK-PLAN-MISMATCH: {@code {expectedPlanHash, actual}}</li>
 *   <li>AK-NESTED-JAR-COUNT: {@code {actual}} (do not leak expected=7)</li>
 *   <li>AK-NESTED-JAR-SHA256: {@code {entryPath, expected, actual}} (from first failed BoundResult)</li>
 * </ul>
 *
 * <p>Use {@link #rejected()} to check if any rejection occurred, and
 * {@link #firstRejectionCode()} to get the first rejection code.
 * For binding failures (where plan and count passed but individual bindings failed),
 * the rejection code is {@code AK-NESTED-JAR-SHA256}.
 *
 * <p>This record is immutable and thread-safe.
 */
public final class PlanBindingResult {

    /** Frozen key set for plan mismatch args. */
    private static final java.util.Set<String> PLAN_ARG_KEYS =
            java.util.Set.of("expectedPlanHash", "actual");

    /**
     * Immutable binding for a single nested JAR dependency.
     */
    public record BoundResult(
            String lockId,
            String entryPath,
            String expectedFingerprint,
            String actualFingerprint,
            boolean bound,
            String sha256Key
    ) {
        public BoundResult {
            Objects.requireNonNull(lockId, "lockId must not be null");
            Objects.requireNonNull(entryPath, "entryPath must not be null");
            Objects.requireNonNull(expectedFingerprint, "expectedFingerprint must not be null");
            Objects.requireNonNull(actualFingerprint, "actualFingerprint must not be null");
            Objects.requireNonNull(sha256Key, "sha256Key must not be null");
        }

        /**
         * Returns the reason code for this binding.
         * Returns null if bound is true.
         */
        public String reasonCode() {
            return bound ? null : "AK-NESTED-JAR-SHA256";
        }
    }

    private final boolean planMismatch;
    private final Map<String, Object> planArgs;
    private final boolean countMismatch;
    private final Map<String, Object> countArgs;
    private final List<BoundResult> boundResults;

    /**
     * Canonical constructor.
     */
    public PlanBindingResult(
            boolean planMismatch,
            Map<String, Object> planArgs,
            boolean countMismatch,
            Map<String, Object> countArgs,
            List<BoundResult> boundResults
    ) {
        this.planMismatch = planMismatch;
        // Frozen args: expectedPlanHash, actual
        if (planArgs != null && planMismatch) {
            java.util.Set<String> keys = planArgs.keySet();
            if (!keys.equals(PLAN_ARG_KEYS)) {
                throw new IllegalArgumentException(
                        "planArgs keys must be exactly " + PLAN_ARG_KEYS + " but got " + keys);
            }
        }
        this.planArgs = planArgs != null ? Map.copyOf(planArgs) : Map.of();
        this.countMismatch = countMismatch;
        // Frozen args: actual (do not include expected=7 to avoid path leakage)
        this.countArgs = countArgs != null ? Map.copyOf(countArgs) : Map.of();
        this.boundResults = boundResults != null ? List.copyOf(boundResults) : List.of();
    }

    /**
     * Creates a plan mismatch result.
     * Frozen args: {@code {expectedPlanHash, actual}}.
     */
    public static PlanBindingResult planMismatch(String expectedPlanFingerprint, String actualPlanFingerprint) {
        return new PlanBindingResult(
                true,
                Map.of("expectedPlanHash", expectedPlanFingerprint, "actual", actualPlanFingerprint),
                false,
                Map.of(),
                List.of()
        );
    }

    /**
     * Creates a count mismatch result.
     * Frozen args: {@code {actual}} — do not leak expected=7.
     */
    public static PlanBindingResult countMismatch(int expectedCount, int actualCount) {
        return new PlanBindingResult(
                false,
                Map.of(),
                true,
                // Frozen: only 'actual' — expected is not included to avoid path leakage
                Map.of("actual", actualCount),
                List.of()
        );
    }

    /**
     * Returns a fully-bound success result with exactly 7 bound results.
     */
    public static PlanBindingResult success(List<BoundResult> boundResults) {
        return new PlanBindingResult(false, Map.of(), false, Map.of(), boundResults);
    }

    public boolean planMismatch()   { return planMismatch; }
    public boolean countMismatch()  { return countMismatch; }
    public List<BoundResult> boundResults() { return boundResults; }

    /**
     * Returns the plan mismatch args, for test and inspection purposes.
     * Returns an immutable view with frozen keys: expectedPlanHash, actual.
     */
    public Map<String, Object> planArgs() { return planArgs; }

    /**
     * Returns the count mismatch args, for test and inspection purposes.
     * Returns an immutable view with frozen keys: actual.
     */
    public Map<String, Object> countArgs() { return countArgs; }

    /**
     * Returns true if any rejection occurred (plan mismatch, count mismatch, or binding failure).
     */
    public boolean rejected() {
        if (planMismatch) return true;
        if (countMismatch) return true;
        // Check for binding failures
        return boundResults.stream().anyMatch(r -> !r.bound());
    }

    /**
     * Returns the first rejection code in priority order:
     * 1. AK-PLAN-MISMATCH
     * 2. AK-NESTED-JAR-COUNT
     * 3. AK-NESTED-JAR-SHA256 (first binding failure, with args {entryPath, expected, actual})
     *
     * Returns null if no rejection occurred.
     */
    public String firstRejectionCode() {
        if (planMismatch) return "AK-PLAN-MISMATCH";
        if (countMismatch) return "AK-NESTED-JAR-COUNT";
        // Check for binding failures
        return boundResults.stream()
                .filter(r -> !r.bound())
                .map(r -> "AK-NESTED-JAR-SHA256")
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the args for AK-NESTED-JAR-SHA256 from the first failed bound result,
     * or an empty map if no binding failure exists.
     * Frozen args: {@code {entryPath, expected, actual}}.
     */
    public Map<String, Object> sha256MismatchArgs() {
        return boundResults.stream()
                .filter(r -> !r.bound())
                .findFirst()
                .map(br -> Map.<String, Object>of(
                        "entryPath", br.entryPath(),
                        "expected", br.expectedFingerprint(),
                        "actual", br.actualFingerprint()))
                .orElse(Map.of());
    }

    /**
     * Returns true if any binding failed (but plan and count passed).
     */
    public boolean hasBindingFailures() {
        if (planMismatch || countMismatch) return false;
        return boundResults.stream().anyMatch(r -> !r.bound());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlanBindingResult that = (PlanBindingResult) o;
        return planMismatch == that.planMismatch
                && countMismatch == that.countMismatch
                && Objects.equals(planArgs, that.planArgs)
                && Objects.equals(countArgs, that.countArgs)
                && Objects.equals(boundResults, that.boundResults);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planMismatch, planArgs, countMismatch, countArgs, boundResults);
    }

    @Override
    public String toString() {
        return "PlanBindingResult{" +
                "planMismatch=" + planMismatch +
                ", countMismatch=" + countMismatch +
                ", rejected=" + rejected() +
                ", boundResults=" + boundResults +
                '}';
    }
}
