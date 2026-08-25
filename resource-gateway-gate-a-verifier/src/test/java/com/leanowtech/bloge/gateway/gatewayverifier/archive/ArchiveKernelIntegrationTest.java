package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for A1.3-02 Archive Kernel.
 */
class ArchiveKernelIntegrationTest {

    @TempDir
    Path tempDir;

    private static String sha256hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return hex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256fp(byte[] data) {
        return "sha256:" + sha256hex(data);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // Build a valid JAR/ZIP using standard ZipOutputStream with DEFLATED compression.
    // This is the same approach proven correct by ZipArchiveVerifierTest.
    private byte[] buildJar(List<String> entryNames) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (String name : entryNames) {
                java.util.zip.ZipEntry e = new java.util.zip.ZipEntry(name);
                e.setMethod(java.util.zip.ZipEntry.DEFLATED);
                zos.putNextEntry(e);
                byte[] payload = ("content-" + name).getBytes(StandardCharsets.UTF_8);
                zos.write(payload, 0, payload.length);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    // Build plan JSON with exact field names (maxTotalUncompressedBytes)
    private byte[] buildPlanJson(
            String schemaVersion,
            List<String> archiveEntries,
            List<Map<String, String>> dependencies,
            Map<String, Long> limits
    ) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"").append(schemaVersion).append("\",\n");
        sb.append("  \"exactArchiveEntries\": [\n");
        for (int i = 0; i < archiveEntries.size(); i++) {
            sb.append("    \"").append(archiveEntries.get(i)).append("\"");
            if (i < archiveEntries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"embeddedDependencies\": [\n");
        for (int i = 0; i < dependencies.size(); i++) {
            Map<String, String> dep = dependencies.get(i);
            sb.append("    {\"lockId\": \"").append(dep.get("lockId"))
                    .append("\", \"entryPath\": \"").append(dep.get("entryPath"))
                    .append("\", \"rawFingerprint\": \"").append(dep.get("fingerprint")).append("\"}");
            if (i < dependencies.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"artifactLimits\": {\n");
        sb.append("    \"maxRawBytes\": ").append(limits.get("maxRawBytes")).append(",\n");
        sb.append("    \"maxZipEntries\": ").append(limits.get("maxZipEntries")).append(",\n");
        sb.append("    \"maxSingleEntryBytes\": ").append(limits.get("maxSingleEntryBytes")).append(",\n");
        sb.append("    \"maxTotalUncompressedBytes\": ").append(limits.get("maxTotalUncompressedBytes")).append(",\n");
        sb.append("    \"maxCompressionRatio\": ").append(limits.get("maxCompressionRatio")).append("\n");
        sb.append("  }\n");
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<String> generate28Entries() {
        List<String> entries = new ArrayList<>();
        // 21 regular class entries + 7 nested JAR paths = 28 total
        // The 7 nested JAR paths are part of the 28 (plan exactArchiveEntries = 28)
        for (int i = 0; i < 21; i++) {
            entries.add("lib/module-" + String.format("%02d", i) + ".class");
        }
        for (int i = 0; i < 7; i++) {
            entries.add("META-INF/gate-a/lib" + i + ".jar");
        }
        return entries;
    }

    private List<Map<String, String>> generate7Dependencies(List<String> jarEntries) {
        List<Map<String, String>> deps = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String path = "META-INF/gate-a/lib" + i + ".jar";
            // Fingerprint = SHA256 of the actual entry payload: "content-<path>"
            // buildJar writes content-" + name for each entry
            String content = "content-" + path;
            String fp = sha256fp(content.getBytes(StandardCharsets.UTF_8));
            deps.add(Map.of("lockId", "lock" + i, "entryPath", path, "fingerprint", fp));
        }
        return deps;
    }

    private Map<String, Long> defaultLimits() {
        return Map.of(
                "maxRawBytes", 16 * 1024 * 1024L,
                "maxZipEntries", 512L,
                "maxSingleEntryBytes", 8 * 1024 * 1024L,
                "maxTotalUncompressedBytes", 64 * 1024 * 1024L,
                "maxCompressionRatio", 100L
        );
    }

    // -------------------------------------------------------------------------
    // Parser Tests
    // -------------------------------------------------------------------------

    @Nested
    class PackagingPlanParserTests {

        @Test
        void validPlan_parsesSuccessfully() throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, expectedHash);

            assertTrue(result.isSuccess());
            PackagedPlan plan = result.plan();
            assertEquals("v1", plan.schemaVersion());
            assertEquals(28, plan.exactArchiveEntries().size());
            assertEquals(7, plan.embeddedDependencies().size());
            assertTrue(plan.isHashValid());
            assertEquals(512, plan.artifactLimits().maxZipEntries());
            assertEquals(64 * 1024 * 1024L, plan.artifactLimits().maxTotalUncompressedBytes());
        }

        @Test
        void wrongHash_rejectedWithHashMismatch() throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            String wrongHash = sha256fp("wrong".getBytes(StandardCharsets.UTF_8));

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, wrongHash);

            assertTrue(result.isRejected());
            assertEquals("AK-PLAN-HASH-MISMATCH", result.rejectionCode());
            assertNotNull(result.rejectionArgs().get("expected"));
            assertNotNull(result.rejectionArgs().get("actual"));
        }

        @Test
        void wrongSchemaVersion_rejected() throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            byte[] planBytes = buildPlanJson("v2", entries, deps, defaultLimits());
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, expectedHash);

            assertTrue(result.isRejected());
            assertEquals("AK-PLAN-SCHEMA-MISMATCH", result.rejectionCode());
        }

        @Test
        void wrongEntryCount_rejected() throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            List<String> entries = List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt");
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, expectedHash);

            assertTrue(result.isRejected());
            assertEquals("AK-PLAN-INVALID-ENTRIES", result.rejectionCode());
        }

        @Test
        void duplicateEntries_rejected() throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < 27; i++) {
                entries.add("lib/module-" + String.format("%02d", i) + ".class");
            }
            entries.add("lib/module-00.class"); // Duplicate
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, expectedHash);

            assertTrue(result.isRejected());
            assertEquals("AK-PLAN-INVALID-ENTRIES", result.rejectionCode());
        }

        @Test
        void wrongDependencyCount_rejected() throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String path = "META-INF/gate-a/lib" + i + ".jar";
                deps.add(Map.of("lockId", "lock" + i, "entryPath", path,
                        "fingerprint", sha256fp(path.getBytes(StandardCharsets.UTF_8))));
            }
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, expectedHash);

            assertTrue(result.isRejected());
            assertEquals("AK-PLAN-INVALID-DEPS", result.rejectionCode());
        }

        @Test
        void invalidFingerprintFormat_rejected() throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                String path = "META-INF/gate-a/lib" + i + ".jar";
                deps.add(Map.of("lockId", "lock" + i, "entryPath", path,
                        "fingerprint", "invalid-fp"));
            }
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, expectedHash);

            assertTrue(result.isRejected());
            assertEquals("AK-PLAN-INVALID-FINGERPRINT", result.rejectionCode());
        }

        @Test
        void wrongLimitFieldName_rejected() throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            // Use wrong field name: maxTotalUncompressed (should be maxTotalUncompressedBytes)
            Map<String, Long> badLimits = Map.of(
                    "maxRawBytes", 16 * 1024 * 1024L,
                    "maxZipEntries", 512L,
                    "maxSingleEntryBytes", 8 * 1024 * 1024L,
                    "maxTotalUncompressed", 64 * 1024 * 1024L, // WRONG name
                    "maxCompressionRatio", 100L
            );
            byte[] planBytes = buildPlanJson("v1", entries, deps, badLimits);
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, expectedHash);

            assertTrue(result.isRejected());
            assertEquals("AK-PLAN-INVALID-LIMITS", result.rejectionCode());
        }

        @Test
        void invalidJson_rejected() {
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = "not valid json {{{".getBytes(StandardCharsets.UTF_8);
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser.ParseResult result = parser.parse(planBytes, expectedHash);

            assertTrue(result.isRejected());
            assertEquals("AK-PLAN-INVALID-JSON", result.rejectionCode());
        }
    }

    // -------------------------------------------------------------------------
    // Phase Priority Tests
    // -------------------------------------------------------------------------

    @Nested
    class PhasePriorityTests {

        private PackagedPlan parsePlan(List<String> entries, List<String> jarEntries,
                                      List<Map<String, String>> deps, Map<String, Long> limits) throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = buildPlanJson("v1", entries, deps, limits);
            String expectedHash = sha256fp(planBytes);
            PackagingPlanParser.ParseResult r = parser.parse(planBytes, expectedHash);
            assertTrue(r.isSuccess(), "Plan parse failed: " + r.rejectionCode());
            return r.plan();
        }

        @Test
        void zipVerifierRejection_shortCircuitsPipeline() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagedPlan plan = parsePlan(entries, jarEntries, deps, defaultLimits());

            Path jarPath = tempDir.resolve("corrupted.jar");
            byte[] corruptedJar = buildJar(jarEntries);
            Files.write(jarPath, Arrays.copyOf(corruptedJar, corruptedJar.length / 2));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertTrue(snapshot.rejected());
            assertEquals("AK-ZIP-STRUCTURE", snapshot.rejectionCode());
        }

        @Test
        void closureRejection_shortCircuitsPipeline() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagedPlan plan = parsePlan(entries, jarEntries, deps, defaultLimits());

            // Only 10 entries instead of 28
            Path jarPath = tempDir.resolve("partial.jar");
            Files.write(jarPath, buildJar(jarEntries.subList(0, 10)));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertTrue(snapshot.rejected());
            assertEquals("AK-ENTRY-MISSING", snapshot.rejectionCode());
            assertFalse(snapshot.closureResult().passed());
        }

        @Test
        void limitsRejection_shortCircuitsPipeline() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            Map<String, Long> tightLimits = Map.of(
                    "maxRawBytes", 100L,
                    "maxZipEntries", 10L,
                    "maxSingleEntryBytes", 1000L,
                    "maxTotalUncompressedBytes", 1000L,
                    "maxCompressionRatio", 2L
            );
            PackagedPlan plan = parsePlan(entries, jarEntries, deps, tightLimits);

            Path jarPath = tempDir.resolve("large.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertTrue(snapshot.rejected());
            assertTrue(snapshot.rejectionCode().startsWith("AK-LIMIT-"));
            assertFalse(snapshot.limitsResult().passed());
        }

        @Test
        void bindingRejection_shortCircuitsPipeline() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                String path = "META-INF/gate-a/lib" + i + ".jar";
                // Fingerprint uses "wrong-content-" to trigger AK-NESTED-JAR-SHA256
                deps.add(Map.of("lockId", "lock" + i, "entryPath", path,
                        "fingerprint", sha256fp(("wrong-content-" + i).getBytes(StandardCharsets.UTF_8))));
                // path already in jarEntries via generate28Entries() — do NOT add
            }
            PackagedPlan plan = parsePlan(entries, jarEntries, deps, defaultLimits());

            Path jarPath = tempDir.resolve("mismatch.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertTrue(snapshot.rejected());
            assertEquals("AK-NESTED-JAR-SHA256", snapshot.rejectionCode());
            assertFalse(snapshot.bindingResult().passed());
        }
    }

    // -------------------------------------------------------------------------
    // Successful Integration Tests
    // -------------------------------------------------------------------------

    @Nested
    class SuccessfulIntegrationTests {

        private PackagedPlan parsePlan(List<String> entries, List<String> jarEntries,
                                      List<Map<String, String>> deps) throws Exception {
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            String expectedHash = sha256fp(planBytes);
            PackagingPlanParser.ParseResult r = parser.parse(planBytes, expectedHash);
            assertTrue(r.isSuccess());
            return r.plan();
        }

        @Test
        void validPlanAndJar_producesSuccessSnapshot() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagedPlan plan = parsePlan(entries, jarEntries, deps);

            Path jarPath = tempDir.resolve("valid.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertFalse(snapshot.rejected());
            assertTrue(snapshot.planHashValid());
            assertEquals(plan.expectedSha256(), snapshot.planExpectedHash());
            assertEquals(plan.computedSha256(), snapshot.planActualHash());
            assertTrue(snapshot.zipVerifierResult().passed());
            assertTrue(snapshot.closureResult().passed());
            assertTrue(snapshot.limitsResult().passed());
            assertTrue(snapshot.bindingResult().passed());
            assertEquals(28, snapshot.entryCount());
            assertEquals(7, snapshot.dependencyCount());
        }

        @Test
        void snapshotContainsAllPhaseResults() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagedPlan plan = parsePlan(entries, jarEntries, deps);

            Path jarPath = tempDir.resolve("valid.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertNotNull(snapshot.zipVerifierResult());
            assertNotNull(snapshot.closureResult());
            assertNotNull(snapshot.limitsResult());
            assertNotNull(snapshot.bindingResult());
            assertNotNull(snapshot.entries());
            assertEquals(28, snapshot.entries().size());
            assertNotNull(snapshot.dependencies());
            assertEquals(7, snapshot.dependencies().size());

            // Verify dependencies sorted by sha256Key
            List<String> keys = snapshot.dependencies().stream()
                    .map(ArchiveKernelSnapshot.Dependency::sha256Key).toList();
            List<String> sortedKeys = new ArrayList<>(keys);
            Collections.sort(sortedKeys);
            assertEquals(sortedKeys, keys);
        }

        @Test
        void limitsArgsIncludeEntryNameAndActual_forSingleEntryViolation() throws Exception {
            // Plan: 28 entries (21 regular + 7 nested JARs), maxSingleEntryBytes=50
            List<String> planEntries = new ArrayList<>();
            for (int i = 0; i < 21; i++) planEntries.add("lib/module-" + String.format("%02d", i) + ".class");
            for (int i = 0; i < 7; i++) planEntries.add("META-INF/gate-a/lib" + i + ".jar");

            // JAR: same 28 + one oversized entry (exceeds maxSingleEntryBytes=50)
            List<String> jarEntries = new ArrayList<>(planEntries);
            jarEntries.add("lib/oversized-" + "x".repeat(200) + ".class");

            // Deps: fingerprints hash "content-<path>" (buildJar payload)
            List<Map<String, String>> deps = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                String path = "META-INF/gate-a/lib" + i + ".jar";
                String content = "content-" + path;
                String fp = sha256fp(content.getBytes(StandardCharsets.UTF_8));
                deps.add(Map.of("lockId", "lock" + i, "entryPath", path, "fingerprint", fp));
            }

            Map<String, Long> tightLimits = Map.of(
                    "maxRawBytes", 16 * 1024 * 1024L,
                    "maxZipEntries", 512L,
                    "maxSingleEntryBytes", 50L,
                    "maxTotalUncompressedBytes", 64 * 1024 * 1024L,
                    "maxCompressionRatio", 100L
            );
            PackagedPlan plan;
            {
                PackagingPlanParser parser = new PackagingPlanParser();
                byte[] planBytes = buildPlanJson("v1", planEntries, deps, tightLimits);
                String expectedHash = sha256fp(planBytes);
                PackagingPlanParser.ParseResult r = parser.parse(planBytes, expectedHash);
                assertTrue(r.isSuccess());
                plan = r.plan();
            }

            Path jarPath = tempDir.resolve("large-entry.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertTrue(snapshot.rejected());
            assertEquals("AK-LIMIT-SINGLE-ENTRY", snapshot.rejectionCode());
            assertNotNull(snapshot.rejectionArgs().get("entryName"));
            assertNotNull(snapshot.rejectionArgs().get("actual"));
            assertNotNull(snapshot.rejectionArgs().get("limit"));
        }
    }


    // -------------------------------------------------------------------------
    // Determinism Tests
    // -------------------------------------------------------------------------

    @Nested
    class DeterminismTests {

        @Test
        void serializer3RunDeterministic_success() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            String expectedHash = sha256fp(planBytes);
            PackagingPlanParser.ParseResult r = parser.parse(planBytes, expectedHash);
            assertTrue(r.isSuccess());
            PackagedPlan plan = r.plan();

            Path jarPath = tempDir.resolve("valid.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            ArchiveKernelSnapshotSerializer serializer = new ArchiveKernelSnapshotSerializer();
            byte[] first = serializer.serialize(snapshot);
            byte[] second = serializer.serialize(snapshot);
            byte[] third = serializer.serialize(snapshot);

            assertArrayEquals(first, second);
            assertArrayEquals(second, third);
        }

        @Test
        void isDeterministic_returnsTrue() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            PackagingPlanParser.ParseResult r = parser.parse(planBytes, sha256fp(planBytes));
            assertTrue(r.isSuccess());
            PackagedPlan plan = r.plan();

            Path jarPath = tempDir.resolve("valid.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            ArchiveKernelSnapshotSerializer serializer = new ArchiveKernelSnapshotSerializer();
            assertTrue(serializer.isDeterministic(snapshot));
        }

        @Test
        void entriesSortedByName() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            PackagingPlanParser.ParseResult r = parser.parse(planBytes, sha256fp(planBytes));
            assertTrue(r.isSuccess());

            Path jarPath = tempDir.resolve("valid.jar");
            Files.write(jarPath, buildJar(new ArrayList<>(entries)));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, r.plan());

            ArchiveKernelSnapshotSerializer serializer = new ArchiveKernelSnapshotSerializer();
            String json = serializer.serializeToString(snapshot);

            List<String> sortedNames = snapshot.entries().stream()
                    .map(ArchiveKernelSnapshot.Entry::name).sorted().toList();
            int lastIndex = -1;
            for (String name : sortedNames) {
                int idx = json.indexOf("\"" + name + "\"", lastIndex + 1);
                assertTrue(idx > lastIndex, "Entries must appear in sorted order: " + name);
                lastIndex = idx;
            }
        }

        @Test
        void dependenciesSortedBySha256Key() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            PackagingPlanParser.ParseResult pr = parser.parse(planBytes, sha256fp(planBytes));
            assertTrue(pr.isSuccess());

            Path jarPath = tempDir.resolve("valid.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, pr.plan());

            ArchiveKernelSnapshotSerializer serializer = new ArchiveKernelSnapshotSerializer();
            String json = serializer.serializeToString(snapshot);

            List<String> sortedKeys = snapshot.dependencies().stream()
                    .map(ArchiveKernelSnapshot.Dependency::sha256Key).sorted().toList();
            int lastIndex = -1;
            for (String key : sortedKeys) {
                int idx = json.indexOf("\"" + key + "\"", lastIndex + 1);
                assertTrue(idx > lastIndex, "Dependencies must appear in sorted order: " + key);
                lastIndex = idx;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot Immutability Tests
    // -------------------------------------------------------------------------

    @Nested
    class SnapshotImmutabilityTests {

        @Test
        void snapshotEntriesAreImmutable() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            PackagingPlanParser.ParseResult r = parser.parse(planBytes, sha256fp(planBytes));
            assertTrue(r.isSuccess());
            PackagedPlan plan = r.plan();

            Path jarPath = tempDir.resolve("valid.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.entries().add(
                            new ArchiveKernelSnapshot.Entry("x", "sha256:00", 0)));
        }

        @Test
        void snapshotDependenciesAreImmutable() throws Exception {
            List<String> entries = generate28Entries();
            List<String> jarEntries = new ArrayList<>(entries);
            List<Map<String, String>> deps = generate7Dependencies(jarEntries);
            PackagingPlanParser parser = new PackagingPlanParser();
            byte[] planBytes = buildPlanJson("v1", entries, deps, defaultLimits());
            PackagingPlanParser.ParseResult r = parser.parse(planBytes, sha256fp(planBytes));
            assertTrue(r.isSuccess());
            PackagedPlan plan = r.plan();

            Path jarPath = tempDir.resolve("valid.jar");
            Files.write(jarPath, buildJar(jarEntries));

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, plan);

            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.dependencies().add(
                            new ArchiveKernelSnapshot.Dependency("x", "y", "z", "w", true, "k")));
        }
    }
}
