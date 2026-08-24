package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Binds a packaging plan to ZIP archive entry content, validating nested JAR dependencies.
 *
 * <p>This binder operates in two validation phases:
 * <ol>
 *   <li>
 *     Content-address phase (Phase 1): verify the plan's raw fingerprint matches the expected value.
 *     Failure produces {@code AK-PLAN-MISMATCH}.
 *     <strong>Phase 1 runs FIRST, before any other checks.</strong>
 *   </li>
 *   <li>
 *     Dependency resolution phase (Phase 2): verify the count and exact entry match.
 *     Count mismatch produces {@code AK-NESTED-JAR-COUNT}.
 *     Fingerprint mismatch produces {@code AK-NESTED-JAR-SHA256}.
 *   </li>
 * </ol>
 *
 * <p>Phase ordering is intentional and fixed: plan fingerprint is checked first because
 * it validates the plan's integrity before attempting dependency resolution (PF-01).
 *
 * <p>The binder produces exactly 7 immutable {@link PlanBindingResult.BoundResult} entries
 * sorted by the stable AK-NESTED-JAR-SHA256 key in the format:
 * {@code <entryPath>::<sha256>} where sha256 is the expected fingerprint's 64 hex chars
 * (all lowercase). SHA-256 collision risk is negligible; ties use insertion order.
 *
 * <p>Duplicate JAR entry paths are rejected via {@code AK-NESTED-JAR-COUNT} without
 * leaking path information. Plan duplicate paths/lockIds also produce stable
 * rejections without leaking the duplicate value.
 *
 * <p>This class is immutable and thread-safe.
 */
public final class NestedJarBinder {

    private final PackagingPlanBinding planBinding;

    /**
     * Constructs a binder for the given packaging plan.
     */
    public NestedJarBinder(PackagingPlanBinding planBinding) {
        this.planBinding = Objects.requireNonNull(planBinding, "planBinding must not be null");
    }

    /**
     * Builds the stable AK-NESTED-JAR-SHA256 sort key.
     *
     * @param entryPath           ZIP entry path
     * @param expectedFingerprint expected SHA-256 from the plan (full sha256:... format)
     * @return stable sort key {@code <entryPath>::<sha256 hex without prefix>}
     * @throws IllegalArgumentException if fingerprint format is invalid
     */
    public static String buildSha256Key(String entryPath, String expectedFingerprint) {
        if (!PackagingPlanBinding.isValidSha256Fingerprint(expectedFingerprint)) {
            throw new IllegalArgumentException(
                    "expectedFingerprint must match sha256:<64 lowerhex>");
        }
        String hex = expectedFingerprint.substring(7);
        return entryPath + "::" + hex;
    }

    /**
     * Binds the packaging plan to the ZIP archive entries.
     *
     * <p>Phase 1: Content-address check (plan fingerprint) — runs FIRST.
     * Phase 2: Dependency count and exact entry match — runs only if Phase 1 passes.
     *
     * @param zipEntries the ZIP entry results from {@link ZipArchiveVerifier.Result#entries()}
     * @return immutable binding result
     */
    public PlanBindingResult bind(List<ZipArchiveVerifier.Result.EntryResult> zipEntries) {
        Objects.requireNonNull(zipEntries, "zipEntries must not be null");

        // Check for null elements in zipEntries — fail closed
        for (ZipArchiveVerifier.Result.EntryResult entry : zipEntries) {
            if (entry == null) {
                throw new IllegalArgumentException("zipEntries must not contain null elements");
            }
        }

        // PHASE 1: Content-address check (plan fingerprint) — MUST run before any other check (PF-01)
        if (!planBinding.validatePlanFingerprint()) {
            return PlanBindingResult.planMismatch(
                    planBinding.expectedPlanFingerprint(),
                    planBinding.computePlanFingerprint()
            );
        }

        // PHASE 2: Dependency resolution — runs only if Phase 1 passed

        // Check for duplicate ZIP entry names — stable reject via AK-NESTED-JAR-COUNT.
        // Uses effective unique count rather than leaking the duplicate path.
        Set<String> seenEntryPaths = new HashSet<>();
        int effectiveUniqueJarPaths = 0;
        for (ZipArchiveVerifier.Result.EntryResult entry : zipEntries) {
            if (seenEntryPaths.add(entry.name())) {
                effectiveUniqueJarPaths++;
            }
        }
        if (effectiveUniqueJarPaths != zipEntries.size()) {
            // Duplicate JAR entry path detected; reject without leaking which path.
            return PlanBindingResult.countMismatch(7, effectiveUniqueJarPaths);
        }

        List<PackagingPlanBinding.Dependency> deps = planBinding.dependencies();

        // Count check: must have exactly 7 declared dependencies
        if (deps.size() != 7) {
            return PlanBindingResult.countMismatch(7, deps.size());
        }

        // Build lookup map by entry path
        Map<String, ZipArchiveVerifier.Result.EntryResult> entryByPath = zipEntries.stream()
                .collect(Collectors.toMap(
                        ZipArchiveVerifier.Result.EntryResult::name,
                        e -> e,
                        (a, b) -> a // Should not occur due to duplicate check above
                ));

        // TM-22: Count how many of the 7 declared dependency entryPaths are actually present.
        // If actual bound candidate count != 7, return AK-NESTED-JAR-COUNT {actual}.
        // Do NOT fabricate BoundResult hashes; count result may have empty boundResults.
        int actualBoundCount = 0;
        for (PackagingPlanBinding.Dependency dep : deps) {
            if (entryByPath.containsKey(dep.entryPath())) {
                actualBoundCount++;
            }
        }
        if (actualBoundCount != 7) {
            // Missing nested JAR(s) — return AK-NESTED-JAR-COUNT with actual count and empty boundResults.
            return PlanBindingResult.countMismatch(7, actualBoundCount);
        }

        // Check for duplicate dependency entry paths in plan.
        // Stable reject via AK-NESTED-JAR-COUNT without leaking the duplicate path.
        Set<String> seenDepPaths = new HashSet<>();
        int effectiveUniquePlanPaths = 0;
        for (PackagingPlanBinding.Dependency dep : deps) {
            if (seenDepPaths.add(dep.entryPath())) {
                effectiveUniquePlanPaths++;
            }
        }
        if (effectiveUniquePlanPaths != deps.size()) {
            return PlanBindingResult.countMismatch(7, effectiveUniquePlanPaths);
        }

        // Check for duplicate lockIds in plan.
        // Stable reject via AK-NESTED-JAR-COUNT without leaking the duplicate lockId.
        Set<String> seenLockIds = new HashSet<>();
        for (PackagingPlanBinding.Dependency dep : deps) {
            if (!seenLockIds.add(dep.lockId())) {
                // Duplicate lockId in plan; reject without leaking which lockId.
                return PlanBindingResult.countMismatch(7, deps.size());
            }
        }

        // Check for invalid fingerprint formats in dependencies — fail closed.
        // Returns AK-NESTED-JAR-COUNT for consistency; the plan is malformed.
        for (PackagingPlanBinding.Dependency dep : deps) {
            if (!dep.hasValidFingerprintFormat()) {
                return PlanBindingResult.countMismatch(7, deps.size());
            }
        }

        // Build bound results with exact entry SHA-256 matching.
        // Actual fingerprint is always "sha256:<64 lowerhex>" or empty string if missing.
        List<PlanBindingResult.BoundResult> boundResults = new ArrayList<>(7);

        for (PackagingPlanBinding.Dependency dep : deps) {
            ZipArchiveVerifier.Result.EntryResult entry = entryByPath.get(dep.entryPath());

            String actualFingerprint;
            boolean bound;

            if (entry == null) {
                // Entry not found in JAR — fail closed; no path leakage.
                actualFingerprint = "";
                bound = false;
            } else {
                // Entry found: compare expected sha256 against entry's uncompressed bytes SHA-256.
                actualFingerprint = "sha256:" + entry.sha256();
                bound = constantTimeEquals(dep.rawFingerprint(), actualFingerprint);
            }

            String sha256Key = buildSha256Key(dep.entryPath(), dep.rawFingerprint());

            boundResults.add(new PlanBindingResult.BoundResult(
                    dep.lockId(),
                    dep.entryPath(),
                    dep.rawFingerprint(),
                    actualFingerprint,
                    bound,
                    sha256Key
            ));
        }

        // Sort by stable AK-NESTED-JAR-SHA256 key.
        // SHA-256 collision risk is negligible; ties maintain insertion order.
        boundResults.sort(Comparator.comparing(PlanBindingResult.BoundResult::sha256Key));

        return PlanBindingResult.success(boundResults);
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
