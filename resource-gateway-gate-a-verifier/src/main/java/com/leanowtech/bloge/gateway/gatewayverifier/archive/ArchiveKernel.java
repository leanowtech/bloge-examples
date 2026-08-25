package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Archive kernel orchestrator for A1.3-02 Gate A verification.
 *
 * <p>Runs the verification pipeline in frozen priority order:
 * <ol>
 *   <li>Plan hash validation</li>
 *   <li>ZipArchiveVerifier (constructed with plan artifactLimits)</li>
 *   <li>ExactClosureChecker</li>
 *   <li>ArtifactLimitsChecker</li>
 *   <li>NestedJarBinder</li>
 * </ol>
 *
 * <p>Each phase runs only if previous phases passed. Any rejection short-circuits
 * the pipeline and produces a snapshot with the first rejection code.
 *
 * <p>Produces an immutable {@link ArchiveKernelSnapshot} matching the docs fields.
 * Frozen reasonArgs include entryName+actual+limit for single-entry/ratio failures.
 *
 * <p>This class is immutable and thread-safe.
 */
public final class ArchiveKernel {

    public ArchiveKernel() {
    }

    /**
     * Verifies a JAR file against a parsed packaging plan.
     *
     * <p>Pipeline (frozen priority order):
     * <ol>
     *   <li>Plan hash validation</li>
     *   <li>ZipArchiveVerifier (constructed with plan artifactLimits)</li>
     *   <li>ExactClosureChecker</li>
     *   <li>ArtifactLimitsChecker</li>
     *   <li>NestedJarBinder</li>
     * </ol>
     */
    public ArchiveKernelSnapshot verify(Path jarPath, PackagedPlan plan) {
        Objects.requireNonNull(jarPath, "jarPath must not be null");
        Objects.requireNonNull(plan, "plan must not be null");

        // Phase 0: Plan hash validation
        if (!plan.isHashValid()) {
            return buildRejectionSnapshot(plan, "AK-PLAN-HASH-MISMATCH",
                    Map.of("expected", plan.expectedSha256(), "actual", plan.computedSha256()),
                    null, null, null, null);
        }

        // Phase 1: ZipArchiveVerifier — constructed with plan limits
        ArtifactLimits planLimits = plan.toArtifactLimits();
        ZipArchiveVerifier verifier = new ZipArchiveVerifier(
                planLimits.maxRawBytes(),
                planLimits.maxZipEntries(),
                planLimits.maxSingleEntryBytes(),
                planLimits.maxTotalUncompressed(),
                planLimits.maxCompressionRatio()
        );

        ZipArchiveVerifier.Result zipResultRaw = verifier.verify(jarPath);

        ArchiveKernelSnapshot.ZipVerifierResult zipResult;
        if (zipResultRaw.rejected()) {
            zipResult = new ArchiveKernelSnapshot.ZipVerifierResult(
                    false,
                    zipResultRaw.entryCount(),
                    zipResultRaw.entries().stream()
                            .map(ZipArchiveVerifier.Result.EntryResult::name)
                            .sorted().toList(),
                    zipResultRaw.limits().rawBytesHit(),
                    zipResultRaw.limits().zipEntriesHit()
            );
            return buildRejectionSnapshot(plan, zipResultRaw.reasonCode(),
                    zipResultRaw.reasonArgs(), zipResult, null, null, null);
        }
        zipResult = new ArchiveKernelSnapshot.ZipVerifierResult(
                true,
                zipResultRaw.entryCount(),
                zipResultRaw.entries().stream()
                        .map(ZipArchiveVerifier.Result.EntryResult::name)
                        .sorted().toList(),
                false, false
        );

        // Phase 2: ExactClosureChecker
        ExactClosureChecker closureChecker = new ExactClosureChecker(
                (int) plan.artifactLimits().maxZipEntries());

        List<CentralDirectoryEntry> cdEntries = zipResultRaw.entries().stream()
                .map(e -> new CentralDirectoryEntry(
                        e.name().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        e.name(), e.compressionMethod(),
                        e.compressedSize(), e.uncompressedSize(), e.crc32(),
                        0L, 0, 0L,
                        e.name().endsWith("/"),
                        false, false, false, false,
                        e.name().startsWith("META-INF/versions/"),
                        null))
                .toList();

        ExactClosureResult closureResultRaw = closureChecker.check(cdEntries, plan.exactArchiveEntries());

        ArchiveKernelSnapshot.ClosureResult closureResult;
        if (closureResultRaw.rejected()) {
            closureResult = new ArchiveKernelSnapshot.ClosureResult(
                    false, closureResultRaw.reasonCode(), closureResultRaw.reasonArgs());
            return buildRejectionSnapshot(plan, closureResultRaw.reasonCode(),
                    closureResultRaw.reasonArgs(), zipResult, closureResult, null, null);
        }
        closureResult = new ArchiveKernelSnapshot.ClosureResult(true, null, null);

        // Phase 3: ArtifactLimitsChecker
        ArtifactLimitsChecker limitsChecker = new ArtifactLimitsChecker(planLimits);
        List<ZipArchiveVerifier.Result.EntryResult> zipEntries = zipResultRaw.entries().stream().toList();

        long rawBytes;
        try {
            rawBytes = Files.size(jarPath);
        } catch (java.io.IOException e) {
            return buildRejectionSnapshot(plan, "AK-ARCHIVE-IO",
                    Map.of("detail", "file size error"),
                    zipResult, closureResult, null, null);
        }

        ArtifactLimitsResult limitsResultRaw = limitsChecker.check(rawBytes, zipEntries.size(), zipEntries);

        ArchiveKernelSnapshot.LimitsResult limitsResult;
        if (limitsResultRaw.isRejected()) {
            String code = limitsResultRaw.firstRejectedCode();
            Map<String, Object> args = buildLimitsArgs(code, planLimits, rawBytes, zipEntries);
            limitsResult = new ArchiveKernelSnapshot.LimitsResult(false, code, args);
            return buildRejectionSnapshot(plan, code, args,
                    zipResult, closureResult, limitsResult, null);
        }
        limitsResult = new ArchiveKernelSnapshot.LimitsResult(true, null, null);

        // Phase 4: NestedJarBinder
        PackagingPlanBinding binding = plan.toBinding();
        NestedJarBinder binder = new NestedJarBinder(binding);
        PlanBindingResult bindingResultRaw = binder.bind(zipEntries);

        ArchiveKernelSnapshot.BindingResult bindingResult;
        if (bindingResultRaw.rejected()) {
            String code = bindingResultRaw.firstRejectionCode();
            Map<String, Object> args = buildBindingArgs(bindingResultRaw, code);
            bindingResult = new ArchiveKernelSnapshot.BindingResult(
                    false, bindingResultRaw.planMismatch(),
                    bindingResultRaw.countMismatch(), code, args);
            return buildRejectionSnapshot(plan, code, args,
                    zipResult, closureResult, limitsResult, bindingResult);
        }
        bindingResult = new ArchiveKernelSnapshot.BindingResult(true, false, false, null, null);

        // Build success snapshot
        List<ArchiveKernelSnapshot.Entry> entries = zipEntries.stream()
                .map(e -> new ArchiveKernelSnapshot.Entry(
                        e.name(), "sha256:" + e.sha256(), e.uncompressedSize()))
                .sorted(Comparator.comparing(ArchiveKernelSnapshot.Entry::name))
                .toList();

        List<ArchiveKernelSnapshot.Dependency> dependencies = bindingResultRaw.boundResults().stream()
                .map(br -> new ArchiveKernelSnapshot.Dependency(
                        br.lockId(), br.entryPath(),
                        br.expectedFingerprint(), br.actualFingerprint(),
                        br.bound(), br.sha256Key()))
                .sorted(Comparator.comparing(ArchiveKernelSnapshot.Dependency::sha256Key))
                .toList();

        return ArchiveKernelSnapshot.success(
                plan.expectedSha256(), plan.computedSha256(),
                entries.size(), entries,
                dependencies.size(), dependencies,
                zipResult, closureResult, limitsResult, bindingResult);
    }

    /**
     * Builds frozen reasonArgs for limits failures.
     * Frozen protocol: entryName+actual+limit for single-entry;
     * entryName+actual+maxRatio+limit for ratio.
     */
    private Map<String, Object> buildLimitsArgs(
            String code,
            ArtifactLimits limits,
            long rawBytes,
            List<ZipArchiveVerifier.Result.EntryResult> entries) {
        switch (code) {
            case "AK-LIMIT-RAW-BYTES":
                return Map.of("limit", limits.maxRawBytes(), "actual", rawBytes);
            case "AK-LIMIT-ZIP-ENTRIES":
                return Map.of("limit", limits.maxZipEntries(), "actual", (long) entries.size());
            case "AK-LIMIT-SINGLE-ENTRY": {
                String offendingEntry = null;
                long actualSize = 0;
                for (ZipArchiveVerifier.Result.EntryResult e : entries) {
                    if (e.uncompressedSize() > limits.maxSingleEntryBytes()) {
                        offendingEntry = e.name();
                        actualSize = e.uncompressedSize();
                        break;
                    }
                }
                return Map.of(
                        "entryName", offendingEntry != null ? offendingEntry : "",
                        "actual", actualSize,
                        "limit", limits.maxSingleEntryBytes());
            }
            case "AK-LIMIT-TOTAL-UNCOMPRESSED": {
                long totalUnc = entries.stream()
                        .mapToLong(ZipArchiveVerifier.Result.EntryResult::uncompressedSize).sum();
                return Map.of("limit", limits.maxTotalUncompressed(), "actual", totalUnc);
            }
            case "AK-LIMIT-COMPRESSION-RATIO": {
                String offendingEntry = null;
                long offendingRatio = 0;
                for (ZipArchiveVerifier.Result.EntryResult e : entries) {
                    long c = e.compressedSize();
                    long u = e.uncompressedSize();
                    if (c <= 0) continue;
                    long den = Math.max(1, c);
                    long quotient = u / den;
                    long remainder = u % den;
                    boolean exceeded = quotient > limits.maxCompressionRatio()
                            || (quotient == limits.maxCompressionRatio() && remainder > 0);
                    if (exceeded) {
                        offendingEntry = e.name();
                        offendingRatio = u;
                        break;
                    }
                }
                return Map.of(
                        "entryName", offendingEntry != null ? offendingEntry : "",
                        "actual", offendingRatio,
                        "maxRatio", limits.maxCompressionRatio(),
                        "limit", limits.maxCompressionRatio());
            }
            default:
                return Map.of();
        }
    }

    private Map<String, Object> buildBindingArgs(PlanBindingResult br, String code) {
        switch (code) {
            case "AK-PLAN-MISMATCH":
                return br.planArgs();
            case "AK-NESTED-JAR-COUNT":
                return br.countArgs();
            case "AK-NESTED-JAR-SHA256":
                return br.sha256MismatchArgs();
            default:
                return Map.of();
        }
    }

    private ArchiveKernelSnapshot buildRejectionSnapshot(
            PackagedPlan plan,
            String rejectionCode,
            Map<String, Object> rejectionArgs,
            ArchiveKernelSnapshot.ZipVerifierResult zipResult,
            ArchiveKernelSnapshot.ClosureResult closureResult,
            ArchiveKernelSnapshot.LimitsResult limitsResult,
            ArchiveKernelSnapshot.BindingResult bindingResult) {
        ArchiveKernelSnapshot.ZipVerifierResult zr = zipResult != null ? zipResult :
                new ArchiveKernelSnapshot.ZipVerifierResult(false, 0, List.of(), false, false);
        ArchiveKernelSnapshot.ClosureResult cr = closureResult != null ? closureResult :
                new ArchiveKernelSnapshot.ClosureResult(false, rejectionCode, rejectionArgs);
        ArchiveKernelSnapshot.LimitsResult lr = limitsResult != null ? limitsResult :
                new ArchiveKernelSnapshot.LimitsResult(false, rejectionCode, rejectionArgs);
        ArchiveKernelSnapshot.BindingResult br = bindingResult != null ? bindingResult :
                new ArchiveKernelSnapshot.BindingResult(false, false, false, rejectionCode, rejectionArgs);
        return ArchiveKernelSnapshot.rejected(
                plan.expectedSha256(), plan.computedSha256(),
                rejectionCode, rejectionArgs, zr, cr, lr, br);
    }
}
