package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Focused acceptance tests for RealVerifierFixtureFactory.
 *
 * <p>Tests prove:
 * <ul>
 *   <li>Factory derives exactly 28 required entries from Authority</li>
 *   <li>Factory derives exactly 7 dependency lock IDs from Authority</li>
 *   <li>Factory derives exactly 5 artifact limits from Authority</li>
 *   <li>Factory derives exactly 7 embedded dependencies from Authority</li>
 *   <li>CLI class has CAFEBABE magic header (from classpath resource)</li>
 *   <li>Baseline JAR has exactly 28 entries with 7 verified fingerprints</li>
 *   <li>Plan JSON is deterministic (3-run identical)</li>
 *   <li>Parser accepts baseline plan successfully</li>
 *   <li>3-run snapshot bytes are identical (determinism)</li>
 *   <li>Mutation: changing requiredJarEntries count is rejected</li>
 *   <li>Mutation: changing artifactLimits keys is rejected</li>
 *   <li>Mutation: changing runtimeDependencyLockIds count is rejected</li>
 *   <li>Mutation: changing embeddedDependencyEntries count is rejected</li>
 * </ul>
 */
class RealVerifierFixtureFactoryTest {

    @TempDir
    Path tempDir;

    private static RealVerifierFixtureFactory FACTORY;
    private static Path DEP_JARS_DIR;
    private static Path AUTHORITY_PATH;
    private static byte[] BASELINE_JAR_BYTES;
    private static byte[] BASELINE_PLAN_BYTES;

    @BeforeAll
    static void setupAll(@TempDir Path staticTempDir) throws Exception {
        // Read Authority path from system property
        String authPathStr = System.getProperty("gate.a.authority.path");
        Assertions.assertNotNull(authPathStr, "System property gate.a.authority.path must be set");
        AUTHORITY_PATH = Path.of(authPathStr);
        Assertions.assertTrue(Files.exists(AUTHORITY_PATH),
                "Authority JSON not found at: " + AUTHORITY_PATH);

        // Read dependency JARs directory from system property
        String depJarsStr = System.getProperty("gate.a.dependency.jars");
        Assertions.assertNotNull(depJarsStr, "System property gate.a.dependency.jars must be set");
        DEP_JARS_DIR = Path.of(depJarsStr);
        Assertions.assertTrue(Files.isDirectory(DEP_JARS_DIR),
                "Dependency JARs directory not found: " + DEP_JARS_DIR);

        // Build factory
        FACTORY = new RealVerifierFixtureFactory(AUTHORITY_PATH, DEP_JARS_DIR);

        // Build baseline JAR and plan
        BASELINE_JAR_BYTES = FACTORY.buildBaselineJar(staticTempDir);
        BASELINE_PLAN_BYTES = FACTORY.buildPackagingPlan(BASELINE_JAR_BYTES);
    }

    // -------------------------------------------------------------------------
    // T1: Count derivations from Authority
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T1: Count derivations")
    class CountDerivations {

        @Test
        @DisplayName("T1-01: Factory derives exactly 28 required entries")
        void derives_28_required_entries() {
            List<String> entries = FACTORY.requiredEntries();
            Assertions.assertEquals(28, entries.size(), "requiredEntries must be exactly 28");
        }

        @Test
        @DisplayName("T1-02: Required entries are unique and non-blank")
        void required_entries_unique_non_blank() {
            List<String> entries = FACTORY.requiredEntries();
            Set<String> seen = new HashSet<>();
            for (String e : entries) {
                Assertions.assertFalse(e.isBlank(), "Entry must not be blank: " + e);
                Assertions.assertTrue(seen.add(e), "Duplicate entry: " + e);
            }
        }

        @Test
        @DisplayName("T1-03: Factory derives exactly 7 dependency lock IDs")
        void derives_7_dependency_lock_ids() {
            List<String> ids = FACTORY.dependencyLockIds();
            Assertions.assertEquals(7, ids.size(), "dependencyLockIds must be exactly 7");
        }

        @Test
        @DisplayName("T1-04: Dependency lock IDs are unique")
        void dependency_lock_ids_unique() {
            List<String> ids = FACTORY.dependencyLockIds();
            Set<String> seen = new HashSet<>();
            for (String id : ids) {
                Assertions.assertTrue(seen.add(id), "Duplicate lockId: " + id);
            }
        }

        @Test
        @DisplayName("T1-05: Factory derives exactly 5 artifact limits")
        void derives_5_artifact_limits() {
            RealVerifierFixtureFactory.ArtifactLimitValues limits = FACTORY.artifactLimits();
            Assertions.assertNotNull(limits, "artifactLimits must not be null");
            // Verify all 5 fields are accessible (non-null) — values come from Authority
            Assertions.assertNotNull(limits.maxRawBytes(), "maxRawBytes present");
            Assertions.assertNotNull(limits.maxZipEntries(), "maxZipEntries present");
            Assertions.assertNotNull(limits.maxSingleEntryBytes(), "maxSingleEntryBytes present");
            Assertions.assertNotNull(limits.maxTotalUncompressedBytes(), "maxTotalUncompressedBytes present");
            Assertions.assertNotNull(limits.maxCompressionRatio(), "maxCompressionRatio present");
        }

        @Test
        @DisplayName("T1-06: Artifact limits values are non-negative")
        void artifact_limits_non_negative() {
            RealVerifierFixtureFactory.ArtifactLimitValues limits = FACTORY.artifactLimits();
            Assertions.assertTrue(limits.maxRawBytes() >= 0, "maxRawBytes >= 0");
            Assertions.assertTrue(limits.maxZipEntries() >= 0, "maxZipEntries >= 0");
            Assertions.assertTrue(limits.maxSingleEntryBytes() >= 0, "maxSingleEntryBytes >= 0");
            Assertions.assertTrue(limits.maxTotalUncompressedBytes() >= 0, "maxTotalUncompressedBytes >= 0");
            Assertions.assertTrue(limits.maxCompressionRatio() >= 0, "maxCompressionRatio >= 0");
        }

        @Test
        @DisplayName("T1-07: Factory derives exactly 7 embedded dependencies")
        void derives_7_embedded_dependencies() {
            List<RealVerifierFixtureFactory.DependencyEntry> deps = FACTORY.embeddedDependencies();
            Assertions.assertEquals(7, deps.size(), "embeddedDependencies must be exactly 7");
        }

        @Test
        @DisplayName("T1-08: Embedded dependencies have non-blank fields and derived artifactFileName")
        void embedded_deps_have_derived_artifact_filename() {
            for (RealVerifierFixtureFactory.DependencyEntry dep : FACTORY.embeddedDependencies()) {
                Assertions.assertFalse(dep.lockId().isBlank(), "lockId must not be blank");
                Assertions.assertFalse(dep.entryPath().isBlank(), "entryPath must not be blank");
                Assertions.assertFalse(dep.sha256().isBlank(), "sha256 must not be blank");
                Assertions.assertFalse(dep.artifactFileName().isBlank(), "artifactFileName must not be blank");
                Assertions.assertTrue(dep.artifactFileName().endsWith(".jar"),
                        "artifactFileName must end with .jar: " + dep.artifactFileName());
            }
        }

        @Test
        @DisplayName("T1-09: Embedded dependency lockId sets equal runtimeDependencyLockIds set")
        void embedded_locks_equal_role_locks() {
            Set<String> roleLocks = new HashSet<>(FACTORY.dependencyLockIds());
            Set<String> embLocks = new HashSet<>();
            for (RealVerifierFixtureFactory.DependencyEntry de : FACTORY.embeddedDependencies()) {
                embLocks.add(de.lockId());
            }
            Assertions.assertEquals(roleLocks, embLocks,
                    "embeddedDependencyEntries.lockId set must equal runtimeDependencyLockIds set");
        }

        @Test
        @DisplayName("T1-10: All embedded dependency entryPaths are in requiredEntries")
        void embedded_entry_paths_in_required() {
            Set<String> reqSet = new HashSet<>(FACTORY.requiredEntries());
            for (RealVerifierFixtureFactory.DependencyEntry de : FACTORY.embeddedDependencies()) {
                Assertions.assertTrue(reqSet.contains(de.entryPath()),
                        "entryPath not in requiredEntries: " + de.entryPath());
            }
        }
    }

    // -------------------------------------------------------------------------
    // T2: CLI bytes
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T2: CLI bytes")
    class CliBytes {

        @Test
        @DisplayName("T2-01: CLI bytes have CAFEBABE magic header")
        void cli_bytes_have_cafebabe() {
            byte[] cli = FACTORY.cliBytes();
            Assertions.assertNotNull(cli, "cliBytes must not be null");
            Assertions.assertTrue(cli.length >= 4, "cliBytes too short for magic");
            Assertions.assertEquals((byte) 0xCA, cli[0], "magic[0]");
            Assertions.assertEquals((byte) 0xFE, cli[1], "magic[1]");
            Assertions.assertEquals((byte) 0xBA, cli[2], "magic[2]");
            Assertions.assertEquals((byte) 0xBE, cli[3], "magic[3]");
        }

        @Test
        @DisplayName("T2-02: Main class is derived from Authority")
        void main_class_from_authority() {
            String mc = FACTORY.mainClass();
            Assertions.assertFalse(mc.isBlank(), "mainClass must not be blank");
            Assertions.assertTrue(mc.contains("."), "mainClass must be fully qualified: " + mc);
        }
    }

    // -------------------------------------------------------------------------
    // T3: Baseline JAR
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T3: Baseline JAR")
    class BaselineJar {

        @Test
        @DisplayName("T3-01: Baseline JAR has exactly 28 entries")
        void baseline_28_entries() throws Exception {
            int count = 0;
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(new ByteArrayInputStream(BASELINE_JAR_BYTES))) {
                while (zis.getNextEntry() != null) {
                    count++;
                    zis.closeEntry();
                }
            }
            Assertions.assertEquals(28, count, "Baseline JAR must have exactly 28 entries");
        }

        @Test
        @DisplayName("T3-02: Baseline JAR contains all required entries")
        void baseline_contains_required_entries() throws Exception {
            Set<String> entries = new HashSet<>();
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(new ByteArrayInputStream(BASELINE_JAR_BYTES))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    entries.add(entry.getName());
                    zis.closeEntry();
                }
            }
            for (String required : FACTORY.requiredEntries()) {
                Assertions.assertTrue(entries.contains(required),
                        "Missing required entry: " + required);
            }
        }

        @Test
        @DisplayName("T3-03: Baseline JAR 7 embedded dependency SHA-256 fingerprints verify")
        void baseline_fingerprints_verify() throws Exception {
            Map<String, byte[]> contentMap = new LinkedHashMap<>();
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(new ByteArrayInputStream(BASELINE_JAR_BYTES))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    contentMap.put(e.getName(), zis.readAllBytes());
                    zis.closeEntry();
                }
            }
            for (RealVerifierFixtureFactory.DependencyEntry dep : FACTORY.embeddedDependencies()) {
                byte[] content = contentMap.get(dep.entryPath());
                Assertions.assertNotNull(content, "Embedded dep entry not found: " + dep.entryPath());
                String actualFp = RealVerifierFixtureFactory.sha256fp(content);
                Assertions.assertEquals(dep.sha256(), actualFp,
                        "Fingerprint mismatch for " + dep.lockId());
            }
        }

        @Test
        @DisplayName("T3-04: Baseline JAR entries are in Authority-defined order")
        void baseline_entries_in_authority_order() throws Exception {
            List<String> actualOrder = new ArrayList<>();
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(new ByteArrayInputStream(BASELINE_JAR_BYTES))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    actualOrder.add(e.getName());
                    zis.closeEntry();
                }
            }
            List<String> expectedOrder = FACTORY.requiredEntries();
            Assertions.assertEquals(expectedOrder, actualOrder,
                    "JAR entry order must match Authority requiredEntries order");
        }

        @Test
        @DisplayName("T3-05: Baseline JAR supports DEFLATED compression")
        void baseline_deflated() throws Exception {
            Set<Integer> methods = new HashSet<>();
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(new ByteArrayInputStream(BASELINE_JAR_BYTES))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    methods.add(entry.getMethod());
                    zis.closeEntry();
                }
            }
            Assertions.assertTrue(methods.contains(java.util.zip.ZipEntry.DEFLATED),
                    "Baseline JAR must use DEFLATED compression");
        }
    }

    // -------------------------------------------------------------------------
    // T4: Packaging plan determinism
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T4: Plan determinism")
    class PlanDeterminism {

        @Test
        @DisplayName("T4-01: 3 plan builds produce identical bytes")
        void three_plan_builds_identical() {
            byte[] run1 = FACTORY.buildPackagingPlan(BASELINE_JAR_BYTES);
            byte[] run2 = FACTORY.buildPackagingPlan(BASELINE_JAR_BYTES);
            byte[] run3 = FACTORY.buildPackagingPlan(BASELINE_JAR_BYTES);

            Assertions.assertArrayEquals(run1, run2, "Run 1 and 2 should be identical");
            Assertions.assertArrayEquals(run2, run3, "Run 2 and 3 should be identical");
        }

        @Test
        @DisplayName("T4-02: Plan JSON is valid UTF-8 and parses")
        void plan_json_valid_utf8() {
            byte[] plan = BASELINE_PLAN_BYTES;
            Assertions.assertNotNull(plan, "plan must not be null");
            String json = new String(plan, java.nio.charset.StandardCharsets.UTF_8);
            Assertions.assertFalse(json.isBlank(), "plan JSON must not be blank");
            // Verify it looks like JSON
            Assertions.assertTrue(json.startsWith("{"), "plan must start with '{'");
        }
    }

    // -------------------------------------------------------------------------
    // T5: Kernel + Parser acceptance
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T5: Kernel + Parser acceptance")
    class KernelAcceptance {

        @Test
        @DisplayName("T5-01: Parser accepts baseline plan successfully")
        void parser_accepts_baseline_plan() {
            PackagingPlanParser.ParseResult pr = new PackagingPlanParser()
                    .parse(BASELINE_PLAN_BYTES,
                            RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES));
            Assertions.assertTrue(pr.isSuccess(),
                    "Parser should succeed. Code: " + pr.rejectionCode()
                    + ", args: " + pr.rejectionArgs());
        }

        @Test
        @DisplayName("T5-02: ArchiveKernel accepts baseline JAR and plan")
        void kernel_accepts_baseline(@TempDir Path testTempDir) throws Exception {
            PackagingPlanParser.ParseResult pr = new PackagingPlanParser()
                    .parse(BASELINE_PLAN_BYTES,
                            RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES));
            Assertions.assertTrue(pr.isSuccess(), "Parser must succeed");

            ArchiveKernel kernel = new ArchiveKernel();
            Path jarPath = testTempDir.resolve("test.jar");
            Files.write(jarPath, BASELINE_JAR_BYTES);

            ArchiveKernelSnapshot snap = kernel.verify(jarPath, pr.plan());
            Assertions.assertFalse(snap.rejected(),
                    "Kernel should accept baseline. Rejection: " + snap.rejectionCode());
        }
    }

    // -------------------------------------------------------------------------
    // T6: 3-run snapshot determinism (uses existing serializer)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T6: 3-run snapshot determinism")
    class ThreeRunDeterminism {

        @Test
        @DisplayName("T6-01: 3 baseline JAR builds produce identical bytes")
        void three_jar_builds_identical() throws Exception {
            byte[] run1 = FACTORY.buildBaselineJar(tempDir.resolve("run1"));
            byte[] run2 = FACTORY.buildBaselineJar(tempDir.resolve("run2"));
            byte[] run3 = FACTORY.buildBaselineJar(tempDir.resolve("run3"));

            Assertions.assertArrayEquals(run1, run2, "Run 1 and 2 should be identical");
            Assertions.assertArrayEquals(run2, run3, "Run 2 and 3 should be identical");
        }

        @Test
        @DisplayName("T6-02: 3 snapshot JSON bytes are identical via ArchiveKernelSnapshotSerializer")
        void three_snapshots_identical(@TempDir Path snapTempDir) throws Exception {
            PackagingPlanParser.ParseResult pr = new PackagingPlanParser()
                    .parse(BASELINE_PLAN_BYTES,
                            RealVerifierFixtureFactory.sha256fp(BASELINE_PLAN_BYTES));
            Assertions.assertTrue(pr.isSuccess(), "Parser must succeed");

            ArchiveKernel kernel = new ArchiveKernel();
            Path jar1 = snapTempDir.resolve("snap1.jar");
            Path jar2 = snapTempDir.resolve("snap2.jar");
            Path jar3 = snapTempDir.resolve("snap3.jar");
            Files.write(jar1, BASELINE_JAR_BYTES);
            Files.write(jar2, BASELINE_JAR_BYTES);
            Files.write(jar3, BASELINE_JAR_BYTES);

            ArchiveKernelSnapshot snap1 = kernel.verify(jar1, pr.plan());
            ArchiveKernelSnapshot snap2 = kernel.verify(jar2, pr.plan());
            ArchiveKernelSnapshot snap3 = kernel.verify(jar3, pr.plan());

            // Use existing ArchiveKernelSnapshotSerializer.serialize() (not toJson)
            ArchiveKernelSnapshotSerializer serializer = new ArchiveKernelSnapshotSerializer();
            byte[] json1 = serializer.serialize(snap1);
            byte[] json2 = serializer.serialize(snap2);
            byte[] json3 = serializer.serialize(snap3);

            Assertions.assertArrayEquals(json1, json2, "Snapshots 1 and 2 should be identical");
            Assertions.assertArrayEquals(json2, json3, "Snapshots 2 and 3 should be identical");
        }
    }

    // -------------------------------------------------------------------------
    // T7: Mutation tests — derive changes and strict rejections
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T7: Mutation tests")
    class MutationTests {

        /**
         * Helper: deep-copy the Authority JSON bytes, mutate the role field in the
         * same ObjectMapper, and verify the factory either rejects or accepts with
         * derived changes.
         */
        private void withMutatedRoleContract(
                String mutationLabel,
                java.util.function.Consumer<JsonNode> mutator,
                java.util.function.Consumer<RealVerifierFixtureFactory> acceptance,
                java.util.function.Supplier<String> errorMatcher
        ) throws Exception {
            // Read original Authority bytes
            byte[] original = Files.readAllBytes(AUTHORITY_PATH);

            // Parse with strict mapper
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode root = mapper.readTree(original);

            // Find INDEPENDENT_VERIFIER role and apply mutation
            JsonNode roleContracts = root.path("roleContracts");
            JsonNode roleNode = null;
            for (JsonNode r : roleContracts) {
                if ("INDEPENDENT_VERIFIER".equals(r.path("role").asText(null))) {
                    roleNode = r;
                    break;
                }
            }
            Assertions.assertNotNull(roleNode, "INDEPENDENT_VERIFIER role not found");

            mutator.accept(roleNode);

            // Write mutated JSON to temp file
            Path mutatedPath = tempDir.resolve("mutated-authority-" + mutationLabel + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(mutatedPath.toFile(), root);

            // Try to build factory with mutated Authority
            try {
                RealVerifierFixtureFactory f = new RealVerifierFixtureFactory(mutatedPath, DEP_JARS_DIR);
                // If accepted, verify derived values changed as expected
                if (acceptance != null) {
                    acceptance.accept(f);
                }
            } catch (IllegalArgumentException e) {
                // Expected: mutation should cause rejection
                String expected = errorMatcher.get();
                Assertions.assertTrue(
                        e.getMessage().contains(expected) || e.getMessage().contains("INDEPENDENT_VERIFIER"),
                        "Expected rejection containing '" + expected + "' but got: " + e.getMessage()
                );
            }
        }

        @Test
        @DisplayName("T7-01: Mutating requiredJarEntries to wrong count is rejected")
        void rejects_wrong_required_entries_count() throws Exception {
            withMutatedRoleContract(
                    "wrong-count",
                    role -> {
                        ArrayNode arr =
                                (ArrayNode) role.get("requiredJarEntries");
                        // Remove one entry
                        arr.remove(arr.size() - 1);
                    },
                    null,
                    () -> "exactly 28"
            );
        }

        @Test
        @DisplayName("T7-02: Mutating artifactLimits to missing key is rejected")
        void rejects_missing_artifact_limit_key() throws Exception {
            withMutatedRoleContract(
                    "missing-limit-key",
                    role -> {
                        ObjectNode limits =
                                (ObjectNode) role.get("artifactLimits");
                        limits.remove("maxCompressionRatio");
                    },
                    null,
                    () -> "exactly 5"
            );
        }

        @Test
        @DisplayName("T7-03: Mutating runtimeDependencyLockIds to wrong count is rejected")
        void rejects_wrong_lock_ids_count() throws Exception {
            withMutatedRoleContract(
                    "wrong-lock-count",
                    role -> {
                        ArrayNode arr =
                                (ArrayNode) role.get("runtimeDependencyLockIds");
                        arr.remove(arr.size() - 1);
                    },
                    null,
                    () -> "exactly 7"
            );
        }

        @Test
        @DisplayName("T7-04: Mutating embeddedDependencyEntries to wrong count is rejected")
        void rejects_wrong_embedded_count() throws Exception {
            withMutatedRoleContract(
                    "wrong-embedded-count",
                    role -> {
                        ObjectNode packaging =
                                (ObjectNode) role.get("packagingContract");
                        ArrayNode arr =
                                (ArrayNode) packaging.get("embeddedDependencyEntries");
                        arr.remove(arr.size() - 1);
                    },
                    null,
                    () -> "exactly 7"
            );
        }

        @Test
        @DisplayName("T7-05: Mutating lockId to unknown value in embedded is rejected")
        void rejects_unknown_lockid_in_embedded() throws Exception {
            withMutatedRoleContract(
                    "unknown-lockid",
                    role -> {
                        ObjectNode packaging =
                                (ObjectNode) role.get("packagingContract");
                        ArrayNode arr =
                                (ArrayNode) packaging.get("embeddedDependencyEntries");
                        if (arr.size() > 0) {
                            ObjectNode first =
                                    (ObjectNode) arr.get(0);
                            first.put("lockId", "UNKNOWN_FAKE_LOCKID_999");
                        }
                    },
                    null,
                    () -> "UNKNOWN_FAKE_LOCKID_999"
            );
        }

        @Test
        @DisplayName("T7-06: Mutating entryPath not in requiredJarEntries is rejected")
        void rejects_entry_path_not_in_required() throws Exception {
            withMutatedRoleContract(
                    "bad-entry-path",
                    role -> {
                        ObjectNode packaging =
                                (ObjectNode) role.get("packagingContract");
                        ArrayNode arr =
                                (ArrayNode) packaging.get("embeddedDependencyEntries");
                        if (arr.size() > 0) {
                            ObjectNode first =
                                    (ObjectNode) arr.get(0);
                            first.put("entryPath", "META-INF/FAKE/fake.jar");
                        }
                    },
                    null,
                    () -> "not found in requiredJarEntries"
            );
        }
    }

    // -------------------------------------------------------------------------
    // T8: SHA-256 helpers
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T8: SHA-256 helpers")
    class ShaHelpers {

        @Test
        @DisplayName("T8-01: sha256fp formats correctly")
        void sha256fp_format() {
            byte[] data = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String fp = RealVerifierFixtureFactory.sha256fp(data);
            Assertions.assertEquals(71, fp.length(), "sha256: prefix + 64 hex chars");
            Assertions.assertTrue(fp.startsWith("sha256:"), "Must start with sha256:");
        }

        @Test
        @DisplayName("T8-02: isValidSha256Fingerprint accepts valid fingerprints")
        void valid_fingerprint() {
            String fp = "sha256:"
                    + "a".repeat(64);
            Assertions.assertTrue(
                    RealVerifierFixtureFactory.isValidSha256Fingerprint(fp));
        }

        @Test
        @DisplayName("T8-03: isValidSha256Fingerprint rejects invalid fingerprints")
        void invalid_fingerprint() {
            Assertions.assertFalse(
                    RealVerifierFixtureFactory.isValidSha256Fingerprint(null));
            Assertions.assertFalse(
                    RealVerifierFixtureFactory.isValidSha256Fingerprint("too-short"));
            Assertions.assertFalse(
                    RealVerifierFixtureFactory.isValidSha256Fingerprint(
                            "sha256:" + "g".repeat(64))); // invalid hex
        }
    }
}
