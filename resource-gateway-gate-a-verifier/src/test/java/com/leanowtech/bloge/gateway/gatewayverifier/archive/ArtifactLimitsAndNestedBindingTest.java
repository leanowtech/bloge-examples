package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for T3 artifact binding:
 * ArtifactLimitsChecker, PackagingPlanBinding, NestedJarBinder, PlanBindingResult.
 *
 * No dependency on bloge-resource-gateway-test-kit.
 */
class ArtifactLimitsAndNestedBindingTest {

    // -------------------------------------------------------------------------
    // Helpers (static, accessible from all nested classes)
    // -------------------------------------------------------------------------

    private static String sha256hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String fp(String hex64) { return "sha256:" + hex64; }

    private static String fpBytes(byte[] data) { return "sha256:" + sha256hex(data); }

    private static ZipArchiveVerifier.Result.EntryResult entry(
            String name, String sha256hex, long uSize, long cSize, int method) {
        return new ZipArchiveVerifier.Result.EntryResult(name, sha256hex, 0L, uSize, cSize, method);
    }

    private static ZipArchiveVerifier.Result.EntryResult entry(
            String name, String sha256hex, long uSize, long cSize) {
        return entry(name, sha256hex, uSize, cSize, 8);
    }

    private static List<PackagingPlanBinding.Dependency> sevenDeps() {
        List<PackagingPlanBinding.Dependency> deps = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String path = "META-INF/gate-a/lib" + i + ".jar";
            deps.add(new PackagingPlanBinding.Dependency(
                    "lock" + i, path, fpBytes(path.getBytes(StandardCharsets.UTF_8))));
        }
        return deps;
    }

    private static PackagingPlanBinding validPlan(byte[] planBytes,
            List<PackagingPlanBinding.Dependency> deps) {
        return new PackagingPlanBinding(planBytes, fpBytes(planBytes), deps);
    }

    private static List<ZipArchiveVerifier.Result.EntryResult> jarEntries7() {
        List<ZipArchiveVerifier.Result.EntryResult> entries = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String path = "META-INF/gate-a/lib" + i + ".jar";
            entries.add(entry(path, sha256hex(path.getBytes(StandardCharsets.UTF_8)), 100, 100));
        }
        return entries;
    }

    // -------------------------------------------------------------------------
    // ArtifactLimits construction
    // -------------------------------------------------------------------------

    @Nested
    class ArtifactLimitsConstruction {
        @Test
        void defaults_factory_all_five_defaults() {
            ArtifactLimits limits = ArtifactLimits.defaults();
            assertEquals(16 * 1024 * 1024L, limits.maxRawBytes());
            assertEquals(512, limits.maxZipEntries());
            assertEquals(8 * 1024 * 1024L, limits.maxSingleEntryBytes());
            assertEquals(64 * 1024 * 1024L, limits.maxTotalUncompressed());
            assertEquals(100, limits.maxCompressionRatio());
        }

        @Test
        void negative_limit_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactLimits(-1, 512, 8 << 20, 64 << 20, 100));
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactLimits(16 << 20, -1, 8 << 20, 64 << 20, 100));
        }

        @Test
        void zero_limits_valid() {
            assertDoesNotThrow(() -> new ArtifactLimits(0, 0, 0, 0, 0));
        }

        @Test
        void equals_and_hashCode() {
            ArtifactLimits a = ArtifactLimits.defaults();
            ArtifactLimits b = ArtifactLimits.defaults();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, new ArtifactLimits(1, 512, 8 << 20, 64 << 20, 100));
        }
    }

    // -------------------------------------------------------------------------
    // ArtifactLimitsChecker: five-tier stable priority (B20-B23)
    // -------------------------------------------------------------------------

    @Nested
    class FiveTierStablePriority {

        private List<ZipArchiveVerifier.Result.EntryResult> validEntries() {
            String h = sha256hex("content".getBytes(StandardCharsets.UTF_8));
            return List.of(
                    entry("a.txt", h, 100, 50),
                    entry("b.txt", h, 100, 50),
                    entry("c.txt", h, 100, 50),
                    entry("d.txt", h, 100, 50),
                    entry("e.txt", h, 100, 50)
            );
        }

        @Test
        void all_pass_notRejected() {
            ArtifactLimitsResult r = new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(1_000_000L, 5, validEntries());
            assertFalse(r.isRejected());
            assertNull(r.firstRejectedCode());
            assertTrue(r.allRejectedCodes().isEmpty());
        }

        @Test
        void rawBytesExceeded_is_priority_1() {
            ArtifactLimits limits = ArtifactLimits.defaults();
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(limits.maxRawBytes() + 1, 5, validEntries());
            assertTrue(r.rawBytes());
            assertEquals("AK-LIMIT-RAW-BYTES", r.firstRejectedCode());
            assertEquals(1, r.allRejectedCodes().size());
        }

        @Test
        void zipEntriesExceeded_is_priority_2() {
            ArtifactLimits limits = ArtifactLimits.defaults();
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(1_000_000L, (int) limits.maxZipEntries() + 1, validEntries());
            assertTrue(r.zipEntries());
            assertEquals("AK-LIMIT-ZIP-ENTRIES", r.firstRejectedCode());
        }

        @Test
        void singleEntryExceeded_is_priority_3() {
            ArtifactLimits limits = ArtifactLimits.defaults();
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("huge.bin", h, limits.maxSingleEntryBytes() + 1, limits.maxSingleEntryBytes() + 1)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(1_000_000L, 1, entries);
            assertTrue(r.singleEntry());
            assertEquals("AK-LIMIT-SINGLE-ENTRY", r.firstRejectedCode());
        }

        @Test
        void totalUncompressedExceeded_is_priority_4() {
            // Two entries totalling 200 > limit=150; each < maxSingleEntryBytes
            ArtifactLimits limits = new ArtifactLimits(16 << 20, 512, 8 << 20, 150, 100);
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("a.bin", h, 100L, 50),
                    entry("b.bin", h, 100L, 50)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(1_000_000L, 2, entries);
            assertTrue(r.totalUncompressed());
            assertEquals("AK-LIMIT-TOTAL-UNCOMPRESSED", r.firstRejectedCode());
        }

        @Test
        void compressionRatioExceeded_is_priority_5() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            // u=101, c=1 -> 101 > 100 * 1 = 100 -> reject
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("ratio.bin", h, 101L, 1)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(200L, 1, entries);
            assertTrue(r.compressionRatio());
            assertEquals("AK-LIMIT-COMPRESSION-RATIO", r.firstRejectedCode());
        }

        @Test
        void rawBytes_and_compressionRatio_returns_rawBytes_priority() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            ArtifactLimits limits = ArtifactLimits.defaults();
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("x.bin", h, 101L, 1)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(limits.maxRawBytes() + 1, 1, entries);
            assertTrue(r.rawBytes());
            assertTrue(r.compressionRatio());
            assertEquals("AK-LIMIT-RAW-BYTES", r.firstRejectedCode());
            assertEquals(2, r.allRejectedCodes().size());
        }

        @Test
        void all_five_simultaneous_returns_RAW_BYTES_priority() {
            // rawBytes > limit, zipEntries > limit, ratio > limit
            // totalUncompressed=false (200 << 64MiB), singleEntry=false (200 << 8MiB)
            ArtifactLimits limits = ArtifactLimits.defaults();
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            // u=200, c=1 -> ratio=200 > 100 -> reject; u=200 << 8MiB; total=200 << 64MiB
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("a.bin", h, 200L, 1)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(limits.maxRawBytes() + 1,
                            (int) limits.maxZipEntries() + 1,
                            entries);
            assertTrue(r.rawBytes());
            assertTrue(r.zipEntries());
            assertFalse(r.singleEntry());
            assertFalse(r.totalUncompressed());
            assertTrue(r.compressionRatio());
            assertEquals("AK-LIMIT-RAW-BYTES", r.firstRejectedCode());
            assertEquals(3, r.allRejectedCodes().size());
        }
    }

    // -------------------------------------------------------------------------
    // ArtifactLimitsChecker: dimensionless u/max(1,c) without overflow false positives
    // -------------------------------------------------------------------------

    @Nested
    class DimensionlessRatioNoOverflow {

        private ArtifactLimitsResult ratioResult(long u, long c) {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("x.bin", h, u, c)
            );
            return new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(200L, 1, entries);
        }

        @Test
        void ratio_at_limit_passes() {
            assertFalse(ratioResult(100L, 1).compressionRatio());
        }

        @Test
        void ratio_above_limit_rejects() {
            assertTrue(ratioResult(101L, 1).compressionRatio());
        }

        @Test
        void c_zero_uses_denominator_1() {
            assertFalse(ratioResult(50L, 0).compressionRatio());
        }

        @Test
        void c_zero_exceeding_limit_rejects() {
            assertTrue(ratioResult(200L, 0).compressionRatio());
        }

        @Test
        void stored_entry_ratio_within_limit_passes() {
            // STORED entries (method=0) are now checked for ratio.
            // u=50, c=50 -> ratio=1, max=100 -> passes
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("s.bin", h, 0L, 50, 50, 0)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(200L, 1, entries);
            assertFalse(r.compressionRatio());
        }

        @Test
        void stored_entry_ratio_exceeds_limit_rejects() {
            // STORED: u=200, c=1 -> ratio=200 > max=100 -> rejects
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("s.bin", h, 0L, 200, 1, 0)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(201L, 1, entries);
            assertTrue(r.compressionRatio());
        }

        @Test
        void multiplyExact_overflow_passes() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("big.bin", h, 0L, 1, Long.MAX_VALUE, 8)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(Long.MAX_VALUE, 1, entries);
            assertFalse(r.compressionRatio());
        }

        @Test
        void maxRatio_zero_large_c_zero_u_passes() {
            // maxRatio=0, u=0, c=Long.MAX_VALUE -> quotient=0 == 0, remainder=0 -> passes
            ArtifactLimits limits = new ArtifactLimits(16 << 20, 512, 8 << 20, 64 << 20, 0);
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("x.bin", h, 0L, 0, Long.MAX_VALUE, 8)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(Long.MAX_VALUE, 1, entries);
            assertFalse(r.compressionRatio());
        }

        @Test
        void maxRatio_zero_any_positive_ratio_rejects() {
            // maxRatio=0, u=1, c=1 -> quotient=1 > 0 -> reject
            ArtifactLimits limits = new ArtifactLimits(16 << 20, 512, 8 << 20, 64 << 20, 0);
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("x.bin", h, 0L, 1, 1, 8)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(1L, 1, entries);
            assertTrue(r.compressionRatio());
        }

        @Test
        void maxRatio_zero_u_zero_c_positive_passes() {
            // maxRatio=0, u=0, c=1 -> quotient=0 == 0, remainder=0 -> passes
            ArtifactLimits limits = new ArtifactLimits(16 << 20, 512, 8 << 20, 64 << 20, 0);
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("x.bin", h, 0L, 0, 1, 8)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(1L, 1, entries);
            assertFalse(r.compressionRatio());
        }

        @Test
        void u_zero_passes() {
            assertFalse(ratioResult(0L, 1).compressionRatio());
        }

        @Test
        void u_zero_large_compressed_passes() {
            assertFalse(ratioResult(0L, 1_000_000).compressionRatio());
        }
    }

    // -------------------------------------------------------------------------
    // ArtifactLimitsChecker: total recomputed; fail closed
    // -------------------------------------------------------------------------

    @Nested
    class TotalRecomputedFailClosed {

        @Test
        void total_from_entries_not_from_input() {
            ArtifactLimits limits = new ArtifactLimits(16 << 20, 512, 8 << 20, 300, 100);
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("a.bin", h, 100L, 50),
                    entry("b.bin", h, 100L, 50)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(1_000_000L, 2, entries);
            assertFalse(r.totalUncompressed());
        }

        @Test
        void total_exceeds_limit_rejects() {
            ArtifactLimits limits = new ArtifactLimits(16 << 20, 512, 8 << 20, 150, 100);
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("a.bin", h, 100L, 50),
                    entry("b.bin", h, 100L, 50)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(1_000_000L, 2, entries);
            assertTrue(r.totalUncompressed());
            assertEquals("AK-LIMIT-TOTAL-UNCOMPRESSED", r.firstRejectedCode());
        }

        @Test
        void null_entry_throws() {
            ArtifactLimits limits = ArtifactLimits.defaults();
            List<ZipArchiveVerifier.Result.EntryResult> entries = new ArrayList<>();
            entries.add(null);
            assertThrows(NullPointerException.class,
                    () -> new ArtifactLimitsChecker(limits).check(1_000_000L, 1, entries));
        }

        @Test
        void negative_rawBytes_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactLimitsChecker(ArtifactLimits.defaults()).check(-1L, 0, List.of()));
        }

        @Test
        void negative_zipEntries_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactLimitsChecker(ArtifactLimits.defaults()).check(1_000_000L, -1, List.of()));
        }

        @Test
        void negative_compressedSize_throws() {
            // Fail-closed: negative compressedSize must not silently pass.
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("neg.bin", h, 0L, 100L, -1L, 8)
            );
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactLimitsChecker(ArtifactLimits.defaults()).check(1_000_000L, 1, entries));
        }

        @Test
        void negative_uncompressedSize_throws_before_recompute() {
            // validateEntries throws before recomputeTotalUncompressed,
            // so negatives cannot contaminate or cancel in the sum.
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("neg.bin", h, 0L, -1L, 100L, 8)
            );
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactLimitsChecker(ArtifactLimits.defaults()).check(1_000_000L, 1, entries));
        }

        @Test
        void negative_u_cancelling_entries_throws_before_sum() {
            // Entries [-1, +1] sum to 0 — without validateEntries pre-check,
            // Math.addExact would return 0 without throwing.
            // validateEntries throws IllegalArgumentException before sum is reached.
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("neg.bin", h, 0L, -1L, 1L, 8),
                    new ZipArchiveVerifier.Result.EntryResult("pos.bin", h, 0L, 1L, 1L, 8)
            );
            assertThrows(IllegalArgumentException.class,
                    () -> new ArtifactLimitsChecker(ArtifactLimits.defaults()).check(2L, 2, entries));
        }

        @Test
        void overflow_in_total_throws() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    entry("a.bin", h, Long.MAX_VALUE, Long.MAX_VALUE),
                    entry("b.bin", h, Long.MAX_VALUE, Long.MAX_VALUE)
            );
            assertThrows(ArithmeticException.class,
                    () -> new ArtifactLimitsChecker(ArtifactLimits.defaults()).check(1_000_000L, 2, entries));
        }

        @Test
        void null_entries_throws() {
            assertThrows(NullPointerException.class,
                    () -> new ArtifactLimitsChecker(ArtifactLimits.defaults()).check(1_000_000L, 0, null));
        }
    }

    // -------------------------------------------------------------------------
    // ArtifactLimitsResult: allRejectedCodes only actual true; immutable
    // -------------------------------------------------------------------------

    @Nested
    class AllRejectedCodesOnlyActualTrue {

        @Test
        void empty_when_not_rejected() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            ArtifactLimitsResult r = new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(1_000_000L, 1, List.of(entry("x.bin", h, 100, 100)));
            assertFalse(r.isRejected());
            assertTrue(r.allRejectedCodes().isEmpty());
        }

        @Test
        void only_actual_true_flags_included() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            ArtifactLimits limits = ArtifactLimits.defaults();
            List<ZipArchiveVerifier.Result.EntryResult> entries = List.of(
                    new ZipArchiveVerifier.Result.EntryResult("x.bin", h, 0L, 101, 1, 8)
            );
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(limits.maxRawBytes() + 1, 1, entries);
            assertTrue(r.rawBytes());
            assertTrue(r.compressionRatio());
            assertFalse(r.zipEntries());
            assertFalse(r.singleEntry());
            assertFalse(r.totalUncompressed());
            List<String> codes = r.allRejectedCodes();
            assertEquals(2, codes.size());
            assertTrue(codes.contains("AK-LIMIT-RAW-BYTES"));
            assertTrue(codes.contains("AK-LIMIT-COMPRESSION-RATIO"));
            assertFalse(codes.contains("AK-LIMIT-ZIP-ENTRIES"));
        }

        @Test
        void stable_priority_order() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            ArtifactLimits limits = ArtifactLimits.defaults();
            ArtifactLimitsResult r = new ArtifactLimitsChecker(limits)
                    .check(limits.maxRawBytes() + 1, 1,
                            List.of(new ZipArchiveVerifier.Result.EntryResult("x.bin", h, 0L, 101, 1, 8)));
            List<String> codes = r.allRejectedCodes();
            assertEquals(0, codes.indexOf("AK-LIMIT-RAW-BYTES"));
            assertEquals(1, codes.indexOf("AK-LIMIT-COMPRESSION-RATIO"));
        }

        @Test
        void immutable() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            ArtifactLimitsResult r = new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(ArtifactLimits.defaults().maxRawBytes() + 1, 1,
                            List.of(entry("x.bin", h, 100, 100)));
            assertThrows(UnsupportedOperationException.class, () -> r.allRejectedCodes().add("FOO"));
        }
    }

    // -------------------------------------------------------------------------
    // PackagingPlanBinding: strict sha256:<64 lowerhex> fingerprint
    // -------------------------------------------------------------------------

    @Nested
    class Sha256FingerprintFormat {

        @Test
        void valid_sha256_lowerhex_accepted() {
            assertTrue(PackagingPlanBinding.isValidSha256Fingerprint(
                    fp(sha256hex("test".getBytes(StandardCharsets.UTF_8)))));
        }

        @Test
        void wrong_prefix_rejected() {
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint("md5:" + "a".repeat(64)));
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint("sha256" + "a".repeat(64)));
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint("sha256:"));
        }

        @Test
        void wrong_length_rejected() {
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint(fp("a".repeat(63))));
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint(fp("a".repeat(65))));
        }

        @Test
        void uppercase_hex_rejected() {
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint(fp("A".repeat(64))));
        }

        @Test
        void non_hex_rejected() {
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint(fp("g" + "0".repeat(63))));
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint(fp("0123456789abcdef".repeat(4) + "!")));
        }

        @Test
        void null_rejected() {
            assertFalse(PackagingPlanBinding.isValidSha256Fingerprint(null));
        }

        @Test
        void computePlanFingerprint_correct_format() {
            byte[] planBytes = "plan content".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, List.of());
            String computed = binding.computePlanFingerprint();
            assertTrue(computed.startsWith("sha256:"));
            assertEquals(71, computed.length());
            assertEquals(sha256hex(planBytes), computed.substring(7));
        }

        @Test
        void constructor_invalid_fingerprint_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PackagingPlanBinding("test".getBytes(), "invalid-fp", List.of()));
        }

        @Test
        void rawPlanBytes_defensive_clone() {
            byte[] planBytes = "original".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, List.of());
            byte[] before = binding.rawPlanBytes();
            before[0] = 'X';
            assertEquals('o', planBytes[0]);
        }

        @Test
        void null_dependency_in_list_throws() {
            // Objects.requireNonNull(dep) is called before accessing dep fields,
            // so a null element in the dependencies list throws NPE.
            List<PackagingPlanBinding.Dependency> deps = new ArrayList<>();
            deps.add(null);
            assertThrows(NullPointerException.class,
                    () -> new PackagingPlanBinding(
                            "plan".getBytes(StandardCharsets.UTF_8),
                            fp(sha256hex("plan".getBytes(StandardCharsets.UTF_8))),
                            deps));
        }

        @Test
        void dependencies_immutable() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<PackagingPlanBinding.Dependency> deps = new ArrayList<>();
            deps.add(new PackagingPlanBinding.Dependency("l1", "p1", fp(h)));
            PackagingPlanBinding binding = validPlan("plan".getBytes(), deps);
            deps.clear();
            assertEquals(1, binding.dependencies().size());
        }

        @Test
        void dependency_valid_fingerprint() {
            String h = sha256hex("dep.jar".getBytes(StandardCharsets.UTF_8));
            PackagingPlanBinding.Dependency dep = new PackagingPlanBinding.Dependency(
                    "l1", "META-INF/gate-a/lib1.jar", fp(h));
            assertTrue(dep.hasValidFingerprintFormat());
        }

        @Test
        void dependency_invalid_fingerprint_rejected() {
            PackagingPlanBinding.Dependency dep = new PackagingPlanBinding.Dependency(
                    "l1", "META-INF/gate-a/lib1.jar", "invalid");
            assertFalse(dep.hasValidFingerprintFormat());
        }

        @Test
        void validatePlanFingerprint_match_true() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, List.of());
            assertTrue(binding.validatePlanFingerprint());
        }

        @Test
        void validatePlanFingerprint_mismatch_false() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = new PackagingPlanBinding(
                    planBytes, fpBytes("wrong".getBytes(StandardCharsets.UTF_8)), List.of());
            assertFalse(binding.validatePlanFingerprint());
        }
    }

    // -------------------------------------------------------------------------
    // NestedJarBinder: plan content-address first (PF-01)
    // -------------------------------------------------------------------------

    @Nested
    class PlanContentAddressFirst {

        @Test
        void plan_mismatch_rejected_before_count_check() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = new PackagingPlanBinding(
                    planBytes, fpBytes("wrong".getBytes(StandardCharsets.UTF_8)), sevenDeps());
            NestedJarBinder binder = new NestedJarBinder(binding);
            String h = sha256hex("content".getBytes(StandardCharsets.UTF_8));
            PlanBindingResult r = binder.bind(List.of(
                    entry("META-INF/gate-a/lib1.jar", h, 100, 100)));
            assertTrue(r.rejected());
            assertEquals("AK-PLAN-MISMATCH", r.firstRejectionCode());
        }

        @Test
        void count_not_checked_when_plan_mismatch() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = new PackagingPlanBinding(
                    planBytes, fpBytes("wrong".getBytes(StandardCharsets.UTF_8)),
                    List.of(
                            new PackagingPlanBinding.Dependency("l1",
                                    "META-INF/gate-a/lib1.jar", fpBytes("x".getBytes())),
                            new PackagingPlanBinding.Dependency("l2",
                                    "META-INF/gate-a/lib2.jar", fpBytes("y".getBytes())),
                            new PackagingPlanBinding.Dependency("l3",
                                    "META-INF/gate-a/lib3.jar", fpBytes("z".getBytes()))));
            NestedJarBinder binder = new NestedJarBinder(binding);
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            PlanBindingResult r = binder.bind(List.of(
                    entry("META-INF/gate-a/lib1.jar", h, 10, 10)));
            assertEquals("AK-PLAN-MISMATCH", r.firstRejectionCode());
            assertFalse(r.countMismatch());
        }
    }

    // -------------------------------------------------------------------------
    // NestedJarBinder: dependency count 6/8 -> AK-NESTED-JAR-COUNT (TM-22)
    // -------------------------------------------------------------------------

    @Nested
    class DependencyCountBoundary {

        private List<PackagingPlanBinding.Dependency> depsWithCount(int count) {
            List<PackagingPlanBinding.Dependency> deps = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String path = "META-INF/gate-a/lib" + i + ".jar";
                deps.add(new PackagingPlanBinding.Dependency(
                        "lock" + i, path, fpBytes(path.getBytes(StandardCharsets.UTF_8))));
            }
            return deps;
        }

        private List<ZipArchiveVerifier.Result.EntryResult> jarEntries(String... paths) {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = new ArrayList<>();
            for (String p : paths) { entries.add(entry(p, h, 100, 100)); }
            return entries;
        }

        private PlanBindingResult bindWithCount(int depCount, List<ZipArchiveVerifier.Result.EntryResult> entries) {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = new PackagingPlanBinding(
                    planBytes, fpBytes(planBytes), depsWithCount(depCount));
            return new NestedJarBinder(binding).bind(entries);
        }

        @Test
        void count_6_returns_AK_NESTED_JAR_COUNT() {
            PlanBindingResult r = bindWithCount(6, jarEntries(
                    "META-INF/gate-a/lib0.jar", "META-INF/gate-a/lib1.jar",
                    "META-INF/gate-a/lib2.jar", "META-INF/gate-a/lib3.jar",
                    "META-INF/gate-a/lib4.jar", "META-INF/gate-a/lib5.jar"));
            assertTrue(r.rejected());
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
            assertTrue(r.countMismatch());
        }

        @Test
        void count_8_returns_AK_NESTED_JAR_COUNT() {
            PlanBindingResult r = bindWithCount(8, jarEntries(
                    "META-INF/gate-a/lib0.jar", "META-INF/gate-a/lib1.jar",
                    "META-INF/gate-a/lib2.jar", "META-INF/gate-a/lib3.jar",
                    "META-INF/gate-a/lib4.jar", "META-INF/gate-a/lib5.jar",
                    "META-INF/gate-a/lib6.jar", "META-INF/gate-a/lib7.jar"));
            assertTrue(r.rejected());
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
        }

        @Test
        void count_5_returns_AK_NESTED_JAR_COUNT() {
            PlanBindingResult r = bindWithCount(5, jarEntries(
                    "META-INF/gate-a/lib0.jar", "META-INF/gate-a/lib1.jar",
                    "META-INF/gate-a/lib2.jar", "META-INF/gate-a/lib3.jar",
                    "META-INF/gate-a/lib4.jar"));
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
        }

        @Test
        void count_0_returns_AK_NESTED_JAR_COUNT() {
            PlanBindingResult r = bindWithCount(0, jarEntries());
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
        }
    }

    // -------------------------------------------------------------------------
    // NestedJarBinder: duplicate paths -- stable reject without path leakage
    // -------------------------------------------------------------------------

    @Nested
    class DuplicatePathsStableReject {

        private List<ZipArchiveVerifier.Result.EntryResult> jarEntries(String... paths) {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = new ArrayList<>();
            for (String p : paths) { entries.add(entry(p, h, 100, 100)); }
            return entries;
        }

        @Test
        void actual_duplicate_jar_paths_returns_AK_NESTED_JAR_COUNT_no_path_leak() {
            // 8 entries where lib1.jar appears twice -> effective unique=7 != 8 -> reject
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, sevenDeps());
            NestedJarBinder binder = new NestedJarBinder(binding);
            PlanBindingResult r = binder.bind(jarEntries(
                    "META-INF/gate-a/lib0.jar",
                    "META-INF/gate-a/lib1.jar",
                    "META-INF/gate-a/lib1.jar",
                    "META-INF/gate-a/lib2.jar",
                    "META-INF/gate-a/lib3.jar",
                    "META-INF/gate-a/lib4.jar",
                    "META-INF/gate-a/lib5.jar",
                    "META-INF/gate-a/lib6.jar"
            ));
            assertTrue(r.rejected());
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
            assertFalse(r.toString().contains("lib1"));
        }

        @Test
        void plan_duplicate_entry_paths_returns_AK_NESTED_JAR_COUNT_no_path_leak() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            String h = sha256hex("c".getBytes(StandardCharsets.UTF_8));
            List<PackagingPlanBinding.Dependency> deps = List.of(
                    new PackagingPlanBinding.Dependency("l1", "META-INF/gate-a/lib1.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l2", "META-INF/gate-a/lib1.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l3", "META-INF/gate-a/lib3.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l4", "META-INF/gate-a/lib4.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l5", "META-INF/gate-a/lib5.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l6", "META-INF/gate-a/lib6.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l7", "META-INF/gate-a/lib7.jar", fp(h))
            );
            PackagingPlanBinding binding = new PackagingPlanBinding(
                    planBytes, fpBytes(planBytes), deps);
            NestedJarBinder binder = new NestedJarBinder(binding);
            PlanBindingResult r = binder.bind(jarEntries(
                    "META-INF/gate-a/lib1.jar",
                    "META-INF/gate-a/lib3.jar",
                    "META-INF/gate-a/lib4.jar",
                    "META-INF/gate-a/lib5.jar",
                    "META-INF/gate-a/lib6.jar",
                    "META-INF/gate-a/lib7.jar",
                    "META-INF/gate-a/lib8.jar"
            ));
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
            assertFalse(r.toString().contains("lib1"));
        }

        @Test
        void plan_duplicate_lockIds_returns_AK_NESTED_JAR_COUNT_no_lockId_leak() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            String h = sha256hex("c".getBytes(StandardCharsets.UTF_8));
            List<PackagingPlanBinding.Dependency> deps = List.of(
                    new PackagingPlanBinding.Dependency("same-lock", "META-INF/gate-a/lib1.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("same-lock", "META-INF/gate-a/lib2.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l3", "META-INF/gate-a/lib3.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l4", "META-INF/gate-a/lib4.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l5", "META-INF/gate-a/lib5.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l6", "META-INF/gate-a/lib6.jar", fp(h)),
                    new PackagingPlanBinding.Dependency("l7", "META-INF/gate-a/lib7.jar", fp(h))
            );
            PackagingPlanBinding binding = new PackagingPlanBinding(
                    planBytes, fpBytes(planBytes), deps);
            NestedJarBinder binder = new NestedJarBinder(binding);
            PlanBindingResult r = binder.bind(jarEntries(
                    "META-INF/gate-a/lib1.jar", "META-INF/gate-a/lib2.jar",
                    "META-INF/gate-a/lib3.jar", "META-INF/gate-a/lib4.jar",
                    "META-INF/gate-a/lib5.jar", "META-INF/gate-a/lib6.jar",
                    "META-INF/gate-a/lib7.jar"
            ));
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
            assertFalse(r.toString().contains("same-lock"));
        }

        @Test
        void invalid_fp_in_dependency_throws_at_construction() {
            // PackagingPlanBinding validates fingerprints at construction — malformed plans
            // throw IllegalArgumentException rather than being misreported as AK-NESTED-JAR-COUNT.
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            String h = sha256hex("c".getBytes(StandardCharsets.UTF_8));
            List<PackagingPlanBinding.Dependency> deps = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                String path = "META-INF/gate-a/lib" + i + ".jar";
                String fp2 = (i == 2) ? "invalid" : fp(h);
                deps.add(new PackagingPlanBinding.Dependency("lock" + i, path, fp2));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new PackagingPlanBinding(planBytes, fpBytes(planBytes), deps));
        }
    }

    // -------------------------------------------------------------------------
    // NestedJarBinder: missing/mismatch -> unified AK-NESTED-JAR-SHA256
    // -------------------------------------------------------------------------

    @Nested
    class MissingMismatchUnifiedSha256 {

        private List<ZipArchiveVerifier.Result.EntryResult> jarEntries(String... paths) {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = new ArrayList<>();
            for (String p : paths) { entries.add(entry(p, h, 100, 100)); }
            return entries;
        }

        @Test
        void sha256_mismatch_returns_AK_NESTED_JAR_SHA256() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, sevenDeps());
            NestedJarBinder binder = new NestedJarBinder(binding);
            String wrongHash = sha256hex("wrong".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                String path = "META-INF/gate-a/lib" + i + ".jar";
                entries.add(entry(path, wrongHash, 100, 100));
            }
            PlanBindingResult r = binder.bind(entries);
            assertTrue(r.rejected());
            assertEquals("AK-NESTED-JAR-SHA256", r.firstRejectionCode());
            assertFalse(r.countMismatch());
        }

        @Test
        void both_missing_and_mismatch_returns_AK_NESTED_JAR_SHA256() {
            // All 7 declared deps are present in the JAR (count satisfied).
            // 4 entries have correct hash, 3 have wrong hash.
            // First failure is AK-NESTED-JAR-SHA256 (count check passes, SHA256 check fails).
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, sevenDeps());
            NestedJarBinder binder = new NestedJarBinder(binding);
            String wrongHash = sha256hex("wrong".getBytes(StandardCharsets.UTF_8));
            List<ZipArchiveVerifier.Result.EntryResult> entries = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                String path = "META-INF/gate-a/lib" + i + ".jar";
                // lib0-lib3: correct hash; lib4-lib6: wrong hash
                String hash = (i < 4)
                        ? sha256hex(path.getBytes(StandardCharsets.UTF_8))
                        : wrongHash;
                entries.add(entry(path, hash, 100, 100));
            }
            PlanBindingResult r = binder.bind(entries);
            assertEquals("AK-NESTED-JAR-SHA256", r.firstRejectionCode());
        }

        @Test
        void no_zero_hash_fallback_missing_entry_empty_fp() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, sevenDeps());
            NestedJarBinder binder = new NestedJarBinder(binding);
            // JAR has 3 unique entries (no duplicates)
            // TM-22: 7 declared deps but only 3 actual entries -> AK-NESTED-JAR-COUNT {actual=3}
            // with empty boundResults (count mismatch does not fabricate hashes)
            PlanBindingResult r = binder.bind(jarEntries(
                    "META-INF/gate-a/lib0.jar",
                    "META-INF/gate-a/lib1.jar",
                    "META-INF/gate-a/lib2.jar"
            ));
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
            assertTrue(r.countMismatch());
            assertTrue(r.boundResults().isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // NestedJarBinder: success -- exactly 7 sorted BoundResults
    // -------------------------------------------------------------------------

    @Nested
    class SuccessExactlySevenSorted {

        @Test
        void success_returns_exactly_7_boundResults() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, sevenDeps());
            NestedJarBinder binder = new NestedJarBinder(binding);
            PlanBindingResult r = binder.bind(jarEntries7());
            assertFalse(r.rejected());
            assertEquals(7, r.boundResults().size());
            for (PlanBindingResult.BoundResult br : r.boundResults()) {
                assertTrue(br.bound());
                assertNull(br.reasonCode());
            }
        }

        @Test
        void boundResults_sorted_by_sha256Key() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, sevenDeps());
            NestedJarBinder binder = new NestedJarBinder(binding);
            PlanBindingResult r = binder.bind(jarEntries7());
            List<String> keys = r.boundResults().stream()
                    .map(PlanBindingResult.BoundResult::sha256Key).toList();
            List<String> sorted = new ArrayList<>(keys);
            sorted.sort(Comparator.naturalOrder());
            assertEquals(sorted, keys);
        }

        @Test
        void sha256Key_format_path_doublecolon_hex() {
            byte[] planBytes = "plan".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, sevenDeps());
            NestedJarBinder binder = new NestedJarBinder(binding);
            PlanBindingResult r = binder.bind(jarEntries7());
            for (PlanBindingResult.BoundResult br : r.boundResults()) {
                String[] parts = br.sha256Key().split("::");
                assertEquals(2, parts.length);
                assertEquals(64, parts[1].length());
                assertTrue(parts[1].matches("[0-9a-f]{64}"));
            }
        }

        @Test
        void buildSha256Key_validates_fingerprint() {
            assertThrows(IllegalArgumentException.class,
                    () -> NestedJarBinder.buildSha256Key("path", "invalid"));
        }

        @Test
        void buildSha256Key_correct_format() {
            String h = sha256hex("test".getBytes(StandardCharsets.UTF_8));
            String key = NestedJarBinder.buildSha256Key("META-INF/gate-a/lib.jar", fp(h));
            assertEquals("META-INF/gate-a/lib.jar::" + h, key);
        }
    }

    // -------------------------------------------------------------------------
    // PlanBindingResult structure and immutability
    // -------------------------------------------------------------------------

    @Nested
    class PlanBindingResultStructure {

        private String hx() { return sha256hex("x".getBytes(StandardCharsets.UTF_8)); }

        @Test
        void planMismatch_rejected_correct_code() {
            PlanBindingResult r = PlanBindingResult.planMismatch(fp("a".repeat(64)), fp("b".repeat(64)));
            assertTrue(r.planMismatch());
            assertFalse(r.countMismatch());
            assertTrue(r.rejected());
            assertEquals("AK-PLAN-MISMATCH", r.firstRejectionCode());
        }

        @Test
        void countMismatch_rejected_correct_code() {
            PlanBindingResult r = PlanBindingResult.countMismatch(7, 6);
            assertTrue(r.countMismatch());
            assertTrue(r.rejected());
            assertEquals("AK-NESTED-JAR-COUNT", r.firstRejectionCode());
        }

        @Test
        void countMismatch_args() {
            PlanBindingResult r = PlanBindingResult.countMismatch(8, 6);
            assertEquals(6, r.countArgs().get("actual"));
        }

        @Test
        void success_not_rejected() {
            String hx2 = hx();
            List<PlanBindingResult.BoundResult> brs = List.of(
                    new PlanBindingResult.BoundResult("l1", "p1", fp(hx2), fp(hx2), true, "p1::" + hx2),
                    new PlanBindingResult.BoundResult("l2", "p2", fp(hx2), fp(hx2), true, "p2::" + hx2)
            );
            PlanBindingResult r = PlanBindingResult.success(brs);
            assertFalse(r.rejected());
            assertNull(r.firstRejectionCode());
        }

        @Test
        void boundResults_immutable() {
            String hx2 = hx();
            PlanBindingResult r = PlanBindingResult.success(List.of(
                    new PlanBindingResult.BoundResult("l1", "p1", fp(hx2), fp(hx2), true, "p1::" + hx2)));
            assertThrows(UnsupportedOperationException.class,
                    () -> r.boundResults().add(
                            new PlanBindingResult.BoundResult("l2", "p2", fp(hx2), fp(hx2), true, "p2::" + hx2)));
        }

        @Test
        void boundResult_reasonCode_null_when_bound() {
            PlanBindingResult.BoundResult br = new PlanBindingResult.BoundResult(
                    "l1", "p1", fp(hx()), fp(hx()), true, "p1::" + hx());
            assertNull(br.reasonCode());
        }

        @Test
        void boundResult_reasonCode_AK_NESTED_JAR_SHA256_when_not_bound() {
            PlanBindingResult.BoundResult br = new PlanBindingResult.BoundResult(
                    "l1", "p1", fp(hx()), "", false, "p1::" + hx());
            assertEquals("AK-NESTED-JAR-SHA256", br.reasonCode());
        }

        @Test
        void planArgs_immutable() {
            PlanBindingResult r = PlanBindingResult.planMismatch(fp("a".repeat(64)), fp("b".repeat(64)));
            Map<String, Object> pa = r.planArgs();
            assertThrows(UnsupportedOperationException.class, pa::clear);
        }

        @Test
        void planMismatch_args_exact_keys() {
            PlanBindingResult r = PlanBindingResult.planMismatch(fp("a".repeat(64)), fp("b".repeat(64)));
            Map<String, Object> pa = r.planArgs();
            assertEquals(java.util.Set.of("expectedPlanHash", "actual"), pa.keySet());
            assertEquals(fp("a".repeat(64)), pa.get("expectedPlanHash"));
            assertEquals(fp("b".repeat(64)), pa.get("actual"));
        }

        @Test
        void countMismatch_args_exact_keys() {
            PlanBindingResult r = PlanBindingResult.countMismatch(7, 6);
            Map<String, Object> ca = r.countArgs();
            assertEquals(java.util.Set.of("actual"), ca.keySet());
            assertEquals(6, ca.get("actual"));
        }

        @Test
        void countArgs_immutable() {
            PlanBindingResult r = PlanBindingResult.countMismatch(7, 6);
            Map<String, Object> ca = r.countArgs();
            assertThrows(UnsupportedOperationException.class, ca::clear);
        }

        @Test
        void equals_and_hashCode() {
            String hx2 = hx();
            PlanBindingResult r1 = PlanBindingResult.success(List.of(
                    new PlanBindingResult.BoundResult("l1", "p1", fp(hx2), fp(hx2), true, "p1::" + hx2)));
            PlanBindingResult r2 = PlanBindingResult.success(List.of(
                    new PlanBindingResult.BoundResult("l1", "p1", fp(hx2), fp(hx2), true, "p1::" + hx2)));
            assertEquals(r1, r2);
            assertEquals(r1.hashCode(), r2.hashCode());
        }
    }

    // -------------------------------------------------------------------------
    // Defensive copy verification
    // -------------------------------------------------------------------------

    @Nested
    class DefensiveCopyVerification {

        @Test
        void allRejectedCodes_immutable() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            ArtifactLimitsResult r = new ArtifactLimitsChecker(ArtifactLimits.defaults())
                    .check(ArtifactLimits.defaults().maxRawBytes() + 1, 1,
                            List.of(entry("x.bin", h, 100, 100)));
            assertThrows(UnsupportedOperationException.class, () -> r.allRejectedCodes().add("FOO"));
        }

        @Test
        void rawPlanBytes_defensive_clone() {
            byte[] planBytes = "original".getBytes(StandardCharsets.UTF_8);
            PackagingPlanBinding binding = validPlan(planBytes, List.of());
            byte[] retrieved = binding.rawPlanBytes();
            retrieved[0] = 'X';
            assertEquals('o', planBytes[0]);
        }

        @Test
        void dependencies_immutable() {
            String h = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            List<PackagingPlanBinding.Dependency> deps = new ArrayList<>();
            deps.add(new PackagingPlanBinding.Dependency("l1", "p1", fp(h)));
            PackagingPlanBinding binding = validPlan("plan".getBytes(), deps);
            deps.clear();
            assertEquals(1, binding.dependencies().size());
        }

        @Test
        void boundResults_immutable() {
            String hx = sha256hex("x".getBytes(StandardCharsets.UTF_8));
            PlanBindingResult r = PlanBindingResult.success(List.of(
                    new PlanBindingResult.BoundResult("l1", "p1", fp(hx), fp(hx), true, "p1::" + hx)));
            assertThrows(UnsupportedOperationException.class,
                    () -> r.boundResults().add(
                            new PlanBindingResult.BoundResult("l2", "p2", fp(hx), fp(hx), true, "p2::" + hx)));
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    class EdgeCases {

        @Test
        void null_zipEntries_throws() {
            PackagingPlanBinding binding = validPlan("plan".getBytes(), sevenDeps());
            assertThrows(NullPointerException.class,
                    () -> new NestedJarBinder(binding).bind(null));
        }

        @Test
        void null_entry_in_list_throws() {
            PackagingPlanBinding binding = validPlan("plan".getBytes(), sevenDeps());
            List<ZipArchiveVerifier.Result.EntryResult> entries = new ArrayList<>();
            entries.add(null);
            assertThrows(IllegalArgumentException.class,
                    () -> new NestedJarBinder(binding).bind(entries));
        }

        @Test
        void null_artifactLimits_throws() {
            assertThrows(NullPointerException.class,
                    () -> new ArtifactLimitsChecker(null));
        }
    }
}
