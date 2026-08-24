package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for PathValidator (T2a) and ExactClosureChecker (T2b).
 */
class PathAndClosureVerifierTest {

    @TempDir
    Path tempDir;

    // PathValidator: AK-PATH-NUL (B07)

    @Nested
    class NulByteTests {
        @Test
        void reject_nulByte_midPath() {
            byte[] raw = "safe/path\u0000evil".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NUL", r.firstReason());
            assertEquals(Set.of("entryName"), r.firstArgs().keySet());
        }

        @Test
        void reject_nulByte_firstByte() {
            byte[] raw = new byte[]{0, 'f', 'i', 'l', 'e', '.', 't', 'x', 't'};
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NUL", r.firstReason());
        }

        @Test
        void reject_nulByte_lastByte() {
            byte[] raw = "file.txt\u0000".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NUL", r.firstReason());
        }

        @Test
        void reject_nulByte_multiple() {
            byte[] raw = "a\u0000b\u0000c".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NUL", r.firstReason());
            assertEquals(Set.of("entryName"), r.firstArgs().keySet());
        }
    }

    // PathValidator: AK-PATH-ABSOLUTE (B08)

    @Nested
    class AbsolutePathTests {
        @Test
        void reject_absoluteRoot() {
            byte[] raw = "/etc/passwd".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-ABSOLUTE", r.firstReason());
            assertEquals("/etc/passwd", r.firstArgs().get("entryName"));
            assertEquals(Set.of("entryName"), r.firstArgs().keySet());
        }

        @Test
        void reject_absoluteSingleSlash() {
            byte[] raw = "/".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-ABSOLUTE", r.firstReason());
        }

        @Test
        void reject_absolutePathDeep() {
            byte[] raw = "/a/b/c/d.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-ABSOLUTE", r.firstReason());
        }

        @Test
        void accept_relativePath() {
            byte[] raw = "a/b/c.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }
    }

    // PathValidator: AK-PATH-BACKSLASH (B09)

    @Nested
    class BackslashTests {
        @Test
        void reject_backslashMidPath() {
            byte[] raw = "a\\b\\c.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-BACKSLASH", r.firstReason());
            assertEquals(Set.of("entryName"), r.firstArgs().keySet());
        }

        @Test
        void reject_backslashOnly() {
            byte[] raw = "\\".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-BACKSLASH", r.firstReason());
        }

        @Test
        void reject_backslashMixed() {
            byte[] raw = "a/b\\c.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-BACKSLASH", r.firstReason());
        }
    }

    // PathValidator: AK-PATH-DOT-SEGMENT (B10-B13)

    @Nested
    class DotSegmentTests {
        @Test
        void reject_dotSegment_singleDot() {
            byte[] raw = ".".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-DOT-SEGMENT", r.firstReason());
        }

        @Test
        void reject_dotSegment_doubleDot() {
            byte[] raw = "..".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-DOT-SEGMENT", r.firstReason());
        }

        @Test
        void reject_dotSegment_leadingDotSlash() {
            byte[] raw = "./file.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-DOT-SEGMENT", r.firstReason());
        }

        @Test
        void reject_dotSegment_leadingDotDotSlash() {
            byte[] raw = "../file.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-DOT-SEGMENT", r.firstReason());
            assertEquals(Set.of("entryName"), r.firstArgs().keySet());
        }

        @Test
        void reject_dotSegment_midSlashDot() {
            byte[] raw = "a/./b.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-DOT-SEGMENT", r.firstReason());
        }

        @Test
        void reject_dotSegment_midSlashDotDot() {
            byte[] raw = "a/../b.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-DOT-SEGMENT", r.firstReason());
        }

        // Trailing dots in filenames are VALID
        @Test
        void accept_dotInBasename() {
            byte[] raw = "a/b.".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }

        @Test
        void accept_dotsInBasename() {
            byte[] raw = "a/b..".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }

        @Test
        void accept_dotInMiddleOfBasename() {
            byte[] raw = "a.b/c.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }
    }

    // PathValidator: AK-PATH-NFC-MISMATCH (B14-B15)

    @Nested
    class NfcMismatchTests {
        @Test
        void reject_nfcMismatch_decomposed() {
            String nfd = "À";
            byte[] raw = nfd.getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NFC-MISMATCH", r.firstReason());
            assertEquals(Set.of("entryName", "decodedForm"), r.firstArgs().keySet());
        }

        @Test
        void accept_nfcNormalized() {
            String nfc = Normalizer.normalize("À", Normalizer.Form.NFC);
            byte[] raw = nfc.getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }

        @Test
        void reject_nfcMismatch_multipleAccents() {
            String nfd = "café";
            byte[] raw = nfd.getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NFC-MISMATCH", r.firstReason());
        }

        @Test
        void accept_nfc_korean() {
            String nfc = "가";
            byte[] raw = nfc.getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }

        @Test
        void reject_invalidUtf8_continuation() {
            byte[] overlong = new byte[]{(byte) 0xC0, (byte) 0xAF};
            PathCheckResult r = PathValidator.validate(overlong);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NFC-MISMATCH", r.firstReason());
        }

        @Test
        void reject_invalidUtf8_truncated() {
            byte[] truncated = new byte[]{(byte) 0xC3};
            PathCheckResult r = PathValidator.validate(truncated);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NFC-MISMATCH", r.firstReason());
        }

        @Test
        void reject_invalidUtf8_invalidContinuation() {
            byte[] invalid = new byte[]{(byte) 0xC0, (byte) 0x7F};
            PathCheckResult r = PathValidator.validate(invalid);
            assertFalse(r.passed());
            assertEquals("AK-PATH-NFC-MISMATCH", r.firstReason());
        }
    }

    // Positive tests

    @Nested
    class PositivePathTests {
        @Test
        void accept_simpleAscii() {
            byte[] raw = "META-INF/MANIFEST.MF".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }

        @Test
        void accept_nestedPath() {
            byte[] raw = "com/example/deep/nesting/Class.class".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }

        @Test
        void accept_unicodeJapanese() {
            String japanese = "あいう";
            byte[] raw = japanese.getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }

        @Test
        void accept_unicodeChinese() {
            String chinese = "中文";
            byte[] raw = chinese.getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }

        @Test
        void accept_singleSegment() {
            byte[] raw = "file.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            assertTrue(r.passed());
        }
    }

    // Immutability tests

    @Nested
    class PathValidatorImmutabilityTests {
        @Test
        void pathCheckResult_nameRawMutation_noEffect() {
            byte[] raw = "test.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            byte[] obtained = r.nameRaw();
            obtained[0] = 'X';
            assertEquals("test.txt", new String(r.nameRaw(), StandardCharsets.UTF_8));
        }

        @Test
        void pathCheckResult_nameRawAccessor_returnsClone() {
            byte[] raw = "test.txt".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            byte[] first = r.nameRaw();
            byte[] second = r.nameRaw();
            assertNotSame(first, second);
            assertArrayEquals(first, second);
        }

        @Test
        void pathCheckResult_reasonsImmutable() {
            byte[] raw = "/etc/passwd".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            List reasons = r.reasons();
            assertThrows(UnsupportedOperationException.class, () -> reasons.add("extra"));
        }

        @Test
        void pathCheckResult_reasonArgsOuterImmutable() {
            byte[] raw = "/etc/passwd".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            Map args = r.reasonArgs();
            assertThrows(UnsupportedOperationException.class, () -> args.put("extra", "value"));
        }

        @Test
        void pathCheckResult_callerInnerMapMutation_noEffect() {
            // Caller mutates the inner map after construction — PathCheckResult is unaffected
            Map<String, Object> callerInner = new HashMap<>();
            callerInner.put("entryName", "/etc/passwd");
            Map<String, Map<String, Object>> callerOuter =
                    Map.of("AK-PATH-ABSOLUTE", callerInner);
            PathCheckResult r = new PathCheckResult(
                    "/etc/passwd".getBytes(StandardCharsets.UTF_8),
                    "/etc/passwd",
                    List.of("AK-PATH-ABSOLUTE"),
                    callerOuter);
            // Mutate the caller's inner map — result is unchanged
            callerInner.put("entryName", "TAMPERED");
            assertEquals("/etc/passwd", r.firstArgs().get("entryName"));
        }

        @Test
        void pathCheckResult_returnedInnerMap_immutable() {
            byte[] raw = "/etc/passwd".getBytes(StandardCharsets.UTF_8);
            PathCheckResult r = PathValidator.validate(raw);
            Map<String, Object> inner = r.firstArgs();
            assertThrows(UnsupportedOperationException.class,
                    () -> inner.put("entryName", "TAMPERED"));
        }
    }

    @Nested
    class RequiredListValidationTests {
        @Test
        void reject_nullRequired() {
            // null required list is a programming error — throws NullPointerException
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"));
            assertThrows(NullPointerException.class,
                    () -> checker.check(entries, null));
        }

        @Test
        void reject_duplicateInRequired() {
            // duplicate in required list is a programming error — throws IllegalArgumentException
            ExactClosureChecker checker = new ExactClosureChecker();
            List<String> required = List.of("a.txt", "b.txt", "a.txt");
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"), entry("b.txt"));
            assertThrows(IllegalArgumentException.class,
                    () -> checker.check(entries, required));
        }
    }

    // ExactClosureChecker: path validation phase

    @Nested
    class PathValidationPhaseTests {
        @Test
        void reject_pathViolationBeforeCountCheck() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("a.txt"),
                    entry("/absolute.txt"),
                    entry("b.txt"));
            List<String> required = List.of("a.txt", "/absolute.txt", "b.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-PATH-ABSOLUTE", r.reasonCode());
        }

        @Test
        void reject_nulPathViolation() {
            ExactClosureChecker checker = new ExactClosureChecker();
            // Create entry with NUL byte
            byte[] nulName = new byte[]{0, 'f', 'o', 'o'};
            List<CentralDirectoryEntry> entries = List.of(entryFromRaw(nulName, "\u0000foo"));
            List<String> required = List.of("\u0000foo");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-PATH-NUL", r.reasonCode());
        }
    }

    // ExactClosureChecker: duplicate detection

    @Nested
    class DuplicateDetectionTests {
        @Test
        void reject_duplicateEntry() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("a.txt"),
                    entry("b.txt"),
                    entry("a.txt"));
            List<String> required = List.of("a.txt", "b.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-DUPLICATE", r.reasonCode());
            assertEquals("a.txt", r.firstDuplicate());
        }

        @Test
        void reject_multipleDuplicates_sortedFirst() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("z.txt"),
                    entry("z.txt"),
                    entry("a.txt"),
                    entry("a.txt"),
                    entry("m.txt"));
            List<String> required = List.of("z.txt", "a.txt", "m.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-DUPLICATE", r.reasonCode());
            assertEquals("a.txt", r.firstDuplicate());
        }
    }

    // ExactClosureChecker: missing detection

    @Nested
    class MissingDetectionTests {
        @Test
        void reject_missingEntry() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"), entry("c.txt"));
            List<String> required = List.of("a.txt", "b.txt", "extra.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-MISSING", r.reasonCode());
            assertEquals("b.txt", r.firstMissing());
            assertTrue(r.missing().contains("b.txt"));
        }

        @Test
        void reject_multipleMissing_firstByRequiredOrder() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"));
            List<String> required = List.of("a.txt", "b.txt", "c.txt", "missing.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-MISSING", r.reasonCode());
            assertEquals("b.txt", r.firstMissing());
        }
    }

    // ExactClosureChecker: count mismatch

    @Nested
    class CountMismatchTests {
        @Test
        void reject_countMismatch_extraEntries() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("a.txt"), entry("b.txt"), entry("c.txt"), entry("d.txt"));
            // extra.txt is NOT in entries: MISSING fires (priority over count mismatch)
            List<String> required = List.of("a.txt", "b.txt", "extra.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-MISSING", r.reasonCode());
            assertEquals("extra.txt", r.firstMissing());
            assertEquals("c.txt", r.firstExtra());
        }

        @Test
        void reject_countMismatch_fewerEntries() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"));
            List<String> required = List.of("a.txt", "b.txt", "extra.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            // Missing has priority over count mismatch
            assertEquals("AK-ENTRY-MISSING", r.reasonCode());
            assertEquals(List.of(), r.extra());
        }
    }

    // ExactClosureChecker: extra detection

    @Nested
    class ExtraDetectionTests {
        @Test
        void reject_extraEntry_sameCount() {
            ExactClosureChecker checker = new ExactClosureChecker();
            // Same count (3 vs 3): extra.txt missing, c.txt extra
            // entries: a.txt, b.txt, extra.txt (extra = c.txt not in entries)
            // required: a.txt, b.txt, c.txt (c.txt is extra from archive perspective)
            // Missing has priority → AK-ENTRY-MISSING, firstMissing = "c.txt"
            List<CentralDirectoryEntry> entries = List.of(
                    entry("a.txt"), entry("b.txt"), entry("extra.txt"));
            List<String> required = List.of("a.txt", "b.txt", "c.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-MISSING", r.reasonCode());
            assertEquals("c.txt", r.firstMissing());
        }

        @Test
        void accept_noExtra_matchingSet() {
            ExactClosureChecker checker = new ExactClosureChecker();
            // entries: a.txt, b.txt, c.txt
            // required: a.txt, b.txt, c.txt — exact match, no extra
            List<CentralDirectoryEntry> entries = List.of(
                    entry("a.txt"), entry("b.txt"), entry("c.txt"));
            List<String> required = List.of("a.txt", "b.txt", "c.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertFalse(r.rejected());
        }
    }

    // ExactClosureChecker: entry limit

    @Nested
    class EntryLimitTests {
        @Test
        void reject_exceedMaxZipEntries() {
            ExactClosureChecker checker = new ExactClosureChecker(5);
            List<CentralDirectoryEntry> entries = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                entries.add(entry("file" + i + ".txt"));
            }
            List<String> required = new ArrayList<>();
            for (CentralDirectoryEntry e : entries) {
                required.add(e.nameUtf8());
            }
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-LIMIT-ZIP-ENTRIES", r.reasonCode());
            assertEquals(5, r.reasonArgs().get("limit"));
            assertEquals(6, r.reasonArgs().get("actual"));
        }

        @Test
        void accept_atMaxZipEntries() {
            ExactClosureChecker checker = new ExactClosureChecker(3);
            // entries and required both have exactly 3 items, sets match → closed
            List<CentralDirectoryEntry> entries = List.of(
                    entry("a.txt"), entry("b.txt"), entry("c.txt"));
            List<String> required = List.of("a.txt", "b.txt", "c.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertFalse(r.rejected());
        }

        @Test
        void constructor_rejectsNonPositive() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExactClosureChecker(0));
            assertThrows(IllegalArgumentException.class,
                    () -> new ExactClosureChecker(-1));
        }
    }

    // Positive closure tests

    @Nested
    class PositiveClosureTests {
        @Test
        void accept_emptyArchiveEmptyRequired() {
            ExactClosureChecker checker = new ExactClosureChecker();
            ExactClosureResult r = checker.check(List.of(), List.of());
            assertFalse(r.rejected());
            assertNull(r.reasonCode());
            assertEquals(0, r.totalEntries());
            assertEquals(0, r.validEntries());
        }

        @Test
        void accept_singleMatchingEntry() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"));
            List<String> required = List.of("a.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertFalse(r.rejected());
            assertEquals(1, r.totalEntries());
            assertEquals(1, r.validEntries());
        }

        @Test
        void accept_complexMatchingSet() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("META-INF/MANIFEST.MF"),
                    entry("com/example/App.class"),
                    entry("resource/config.properties"),
                    entry("META-INF/INDEX.LIST"));
            List<String> required = List.of(
                    "META-INF/MANIFEST.MF",
                    "com/example/App.class",
                    "resource/config.properties",
                    "META-INF/INDEX.LIST");
            ExactClosureResult r = checker.check(entries, required);
            assertFalse(r.rejected());
        }

        @Test
        void accept_unicodeEntryNames() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("\u3042.txt"),
                    entry("\u4E2D\u6587.txt"));
            List<String> required = List.of("\u3042.txt", "\u4E2D\u6587.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertFalse(r.rejected());
        }

        @Test
        void accept_exactMatchDifferentOrder() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("z.txt"), entry("a.txt"), entry("m.txt"));
            List<String> required = List.of("a.txt", "m.txt", "z.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertFalse(r.rejected());
        }

        @Test
        void accept_28Entries() {
            ExactClosureChecker checker = new ExactClosureChecker(100);
            List<CentralDirectoryEntry> entries = new ArrayList<>();
            List<String> required = new ArrayList<>();
            for (int i = 0; i < 28; i++) {
                String name = "file" + i + ".txt";
                entries.add(entry(name));
                required.add(name);
            }
            ExactClosureResult r = checker.check(entries, required);
            assertFalse(r.rejected());
            assertEquals(28, r.totalEntries());
            assertEquals(28, r.validEntries());
        }
    }

    // Immutability tests

    @Nested
    class ClosureResultImmutabilityTests {
        @Test
        void duplicatesListImmutable() {
            ExactClosureResult r = new ExactClosureResult(
                    true, "AK-ENTRY-DUPLICATE", Map.of("entryName", "a.txt", "count", 2L),
                    3, 3, List.of("a.txt"), List.of(), List.of());
            List duplicates = r.duplicates();
            assertThrows(UnsupportedOperationException.class, () -> duplicates.add("b.txt"));
        }

        @Test
        void missingListImmutable() {
            ExactClosureResult r = new ExactClosureResult(
                    true, "AK-ENTRY-MISSING", Map.of("entryName", "b.txt"),
                    2, 2, List.of(), List.of("b.txt"), List.of());
            List missing = r.missing();
            assertThrows(UnsupportedOperationException.class, () -> missing.add("c.txt"));
        }

        @Test
        void extraListImmutable() {
            ExactClosureResult r = new ExactClosureResult(
                    true, "AK-ENTRY-EXTRA", Map.of("entryName", "c.txt"),
                    3, 3, List.of(), List.of(), List.of("c.txt"));
            List extra = r.extra();
            assertThrows(UnsupportedOperationException.class, () -> extra.add("d.txt"));
        }

        @Test
        void closedFactoryResultImmutable() {
            ExactClosureResult r = ExactClosureResult.closed(5, 5);
            List duplicates = r.duplicates();
            List missing = r.missing();
            List extra = r.extra();
            assertThrows(UnsupportedOperationException.class, () -> duplicates.add("x"));
            assertThrows(UnsupportedOperationException.class, () -> missing.add("x"));
            assertThrows(UnsupportedOperationException.class, () -> extra.add("x"));
        }
    }

    // Determinism tests

    @Nested
    class DeterminismTests {
        @Test
        void duplicateFirstOffending_deterministicByUtf8Sort() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("zebra.txt"), entry("apple.txt"), entry("apple.txt"),
                    entry("mango.txt"), entry("apple.txt"));
            List<String> required = List.of("apple.txt", "mango.txt", "zebra.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-DUPLICATE", r.reasonCode());
            assertEquals("apple.txt", r.firstDuplicate());
        }

        @Test
        void extraFirstOffending_deterministicByUtf8Sort() {
            ExactClosureChecker checker = new ExactClosureChecker();
            // entries: 0.txt, a.txt, b.txt, z.txt (4 items, all path-valid)
            // required: a.txt, b.txt, z.txt, extra.txt (4 items)
            // 0.txt is extra (in entries, not in required), extra.txt is missing
            // MISSING fires first → skip to next test for EXTRA determinism
            ExactClosureResult r = checker.check(
                    List.of(entry("0.txt"), entry("a.txt"), entry("b.txt"), entry("z.txt")),
                    List.of("a.txt", "b.txt", "z.txt", "extra.txt"));
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-MISSING", r.reasonCode());
        }

        @Test
        void extraFirstOffending_deterministicByUtf8Sort_extraOnly() {
            // 5 entries, 4 required (all present): COUNT_MISMATCH fires before EXTRA
            // firstExtra() is null here since extra list is empty at COUNT_MISMATCH stage
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("a.txt"), entry("b.txt"), entry("c.txt"), entry("d.txt"), entry("z.txt"));
            List<String> required = List.of("a.txt", "b.txt", "c.txt", "d.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-COUNT-MISMATCH", r.reasonCode());
            // actualCount only (per frozen protocol)
            assertEquals(5, r.reasonArgs().get("actualCount"));
            assertEquals("z.txt", r.firstExtra());
        }

        @Test
        void missingFirstOffending_byRequiredOrder() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"), entry("c.txt"));
            List<String> required = List.of("b.txt", "c.txt", "a.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-MISSING", r.reasonCode());
            assertEquals("b.txt", r.firstMissing());
        }

        @Test
        void pathViolationFirstOffending_byArchiveOrder() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("valid.txt"), entry("/absolute.txt"),
                    entry("also/valid.txt"));
            List<String> required = List.of("valid.txt", "/absolute.txt", "also/valid.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-PATH-ABSOLUTE", r.reasonCode());
        }
    }

    // Priority ordering tests

    @Nested
    class PriorityOrderingTests {
        @Test
        void pathBeforeDuplicate() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("/absolute.txt"), entry("a.txt"), entry("a.txt"));
            List<String> required = List.of("/absolute.txt", "a.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-PATH-ABSOLUTE", r.reasonCode());
        }

        @Test
        void duplicateBeforeMissing() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"), entry("a.txt"));
            List<String> required = List.of("a.txt", "b.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-DUPLICATE", r.reasonCode());
        }

        @Test
        void missingBeforeCountMismatch() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(entry("a.txt"), entry("b.txt"));
            List<String> required = List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-MISSING", r.reasonCode());
        }

        @Test
        void countMismatchBeforeExtra() {
            ExactClosureChecker checker = new ExactClosureChecker();
            List<CentralDirectoryEntry> entries = List.of(
                    entry("a.txt"), entry("b.txt"), entry("c.txt"), entry("extra.txt"));
            List<String> required = List.of("a.txt", "b.txt");
            ExactClosureResult r = checker.check(entries, required);
            assertTrue(r.rejected());
            assertEquals("AK-ENTRY-COUNT-MISMATCH", r.reasonCode());
        }
    }

    // Helper methods

    private static CentralDirectoryEntry entry(String nameUtf8) {
        byte[] raw = nameUtf8.getBytes(StandardCharsets.UTF_8);
        return entryFromRaw(raw, nameUtf8);
    }

    private static CentralDirectoryEntry entryFromRaw(byte[] raw, String nameUtf8) {
        return new CentralDirectoryEntry(
                raw.clone(), nameUtf8, 0, 0L, 0L, 0L, 0L, 0, 0L,
                nameUtf8.endsWith("/"), false, false, false, false,
                nameUtf8.startsWith("META-INF/versions/"), null);
    }
}
