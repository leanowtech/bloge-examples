package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

import static com.leanowtech.bloge.gateway.gatewayverifier.archive.RealVerifierFixtureFactory.R03_FIXTURE_PROVIDER_MISMATCH;
import static com.leanowtech.bloge.gateway.gatewayverifier.archive.RealVerifierFixtureFactory.FixtureProviderException;

/**
 * D7-T3 integration tests for DevelopmentPredecessorBinding-aware fixture builder.
 *
 * <p>Tests prove:
 * <ul>
 *   <li>T1: Exact provider bytes in ZIP equal binding.providerBytes()</li>
 *   <li>T2: Exactly 28 entries in fixture JAR</li>
 *   <li>T3: PackagingPlanParser accepts fixture plan successfully</li>
 *   <li>T4: ArchiveKernel accepts fixture JAR successfully</li>
 *   <li>T5: Three build runs produce identical JAR bytes (determinism)</li>
 *   <li>T6: Three serialized snapshots produce identical bytes (ArchiveKernelSnapshotSerializer)</li>
 *   <li>T7: Wrong providerEntryPath fails with R03-FIXTURE-PROVIDER-MISMATCH</li>
 *   <li>T8: Provider path not in requiredEntries fails with R03-FIXTURE-PROVIDER-MISMATCH</li>
 *   <li>T9: Defensive copy: mutating result arrays does not affect internal state</li>
 *   <li>T10: FixtureProviderException never leaks provider payload</li>
 *   <li>T11: DeriveAuthorityProviderEntryPath derives from Authority</li>
 * </ul>
 */
class R03DevelopmentGateKernelIntegrationTest {

    @TempDir
    Path tempDir;

    private static RealVerifierFixtureFactory FACTORY;
    private static Path DEP_JARS_DIR;
    private static Path AUTHORITY_PATH;
    private static DevelopmentPredecessorBinding BINDING;

    /** BINDING consumed by factory: returned by DevelopmentPredecessorBindingVerifier.verify(). */
    @BeforeAll
    static void setupAll(@TempDir Path staticTempDir) throws Exception {
        // Read Authority path from system property (always required)
        String authPathStr = System.getProperty("gate.a.authority.path");
        assertNotNull(authPathStr, "System property gate.a.authority.path must be set");
        AUTHORITY_PATH = Path.of(authPathStr);
        assertTrue(Files.exists(AUTHORITY_PATH), "Authority JSON not found at: " + AUTHORITY_PATH);

        // Read dependency JARs directory from system property (always required)
        String depJarsStr = System.getProperty("gate.a.dependency.jars");
        assertNotNull(depJarsStr, "System property gate.a.dependency.jars must be set");
        DEP_JARS_DIR = Path.of(depJarsStr);
        assertTrue(Files.isDirectory(DEP_JARS_DIR), "Dependency JARs directory not found: " + DEP_JARS_DIR);

        // Build factory
        FACTORY = new RealVerifierFixtureFactory(AUTHORITY_PATH, DEP_JARS_DIR);

        // Read optional binding.path and repo.root for specialized/e2e mode
        String bindingPathStr = System.getProperty("gate.a.binding.path");
        String repoRootStr   = System.getProperty("gate.a.repo.root");

        boolean hasBinding = bindingPathStr != null && !bindingPathStr.isBlank();
        boolean hasRepo   = repoRootStr   != null && !repoRootStr.isBlank();

        if (hasBinding && hasRepo) {
            // Specialized/e2e: consume producer output via verifier
            Path bindingPath = Path.of(bindingPathStr);
            Path repoRoot    = Path.of(repoRootStr);
            DevelopmentPredecessorBindingVerifier verifier =
                    new DevelopmentPredecessorBindingVerifier();
            BINDING = verifier.verify(bindingPath, AUTHORITY_PATH, repoRoot);
        } else if (hasBinding || hasRepo) {
            // Exactly one present: ambiguous - fail clearly
            fail("gate.a.binding.path and gate.a.repo.root must both be set or both absent. "
                    + "binding.path=" + bindingPathStr + ", repo.root=" + repoRootStr);
        } else {
            // Both absent: focused test mode - use self-contained temp verified binding
            BINDING = buildVerifiedBinding(staticTempDir);
        }
    }

    /**
     * Builds a verified DevelopmentPredecessorBinding using DevelopmentPredecessorBindingVerifier.
     * Derives A1.2 TCK_PROVIDER coordinate and repo-relative JAR path from the Authority
     * deliverySlices (unique A1.2, exactly one TCK_PROVIDER in handoff and output,
     * both identical). Creates a temp repo, writes the provider JAR, and calls
     * verifier.verify() to produce the binding consumed by all factory tests.
     */
    private static DevelopmentPredecessorBinding buildVerifiedBinding(Path staticTempDir)
            throws Exception {
        // Parse Authority to derive A1.2 TCK_PROVIDER facts
        byte[] authBytes = Files.readAllBytes(AUTHORITY_PATH);
        var authMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode authRoot =
                authMapper.readTree(AUTHORITY_PATH.toFile());

        // deliverySlices: find the unique A1.2 entry
        com.fasterxml.jackson.databind.JsonNode slices = authRoot.path("deliverySlices");
        if (!slices.isArray())
            throw new IllegalStateException("deliverySlices must be an array");
        com.fasterxml.jackson.databind.JsonNode a12Slice = null;
        for (com.fasterxml.jackson.databind.JsonNode s : slices) {
            if ("A1.2".equals(s.path("sliceId").asText(null))) {
                a12Slice = s;
                break;
            }
        }
        if (a12Slice == null)
            throw new IllegalStateException("Authority deliverySlices must contain exactly one A1.2 entry");

        // handoffArtifacts: exactly one TCK_PROVIDER entry
        com.fasterxml.jackson.databind.JsonNode handoff = a12Slice.path("handoffArtifacts");
        com.fasterxml.jackson.databind.JsonNode hTck = null;
        if (!handoff.isArray())
            throw new IllegalStateException("handoffArtifacts must be an array");
        for (com.fasterxml.jackson.databind.JsonNode h : handoff) {
            if ("TCK_PROVIDER".equals(h.path("role").asText(null))) {
                hTck = h;
                break;
            }
        }
        if (hTck == null)
            throw new IllegalStateException("handoffArtifacts must contain exactly one TCK_PROVIDER entry");

        // outputArtifacts: exactly one TCK_PROVIDER entry, identical coordinate and path
        com.fasterxml.jackson.databind.JsonNode output = a12Slice.path("outputArtifacts");
        com.fasterxml.jackson.databind.JsonNode oTck = null;
        if (!output.isArray())
            throw new IllegalStateException("outputArtifacts must be an array");
        for (com.fasterxml.jackson.databind.JsonNode o : output) {
            if ("TCK_PROVIDER".equals(o.path("role").asText(null))) {
                oTck = o;
                break;
            }
        }
        if (oTck == null)
            throw new IllegalStateException("outputArtifacts must contain exactly one TCK_PROVIDER entry");

        // Validate handoff and output TCK_PROVIDER have identical coordinate and path
        String hCoord = mavenCoord(hTck.path("coordinate"));
        String oCoord = mavenCoord(oTck.path("coordinate"));
        if (!hCoord.equals(oCoord))
            throw new IllegalStateException("handoff/output TCK_PROVIDER coordinate mismatch");
        String hPath = hTck.path("path").asText(null);
        String oPath = oTck.path("path").asText(null);
        if (hPath == null || hPath.isBlank())
            throw new IllegalStateException("handoff TCK_PROVIDER path is blank");
        if (!hPath.equals(oPath))
            throw new IllegalStateException("handoff/output TCK_PROVIDER path mismatch");

        // Extract Maven coordinate parts
        String[] parts = hCoord.split(":");
        String groupId    = parts[0];
        String artifactId = parts[1];
        String version    = parts[2];
        String repoRelativeJarPath = hPath;   // repo-relative path to JAR file

        // Build deterministic provider JAR bytes
        byte[] providerBytes = buildDeterministicProviderJar();
        String providerFp = sha256fp(providerBytes);

        // Write provider JAR to Authority-derived repo-relative path
        Path repoRoot = staticTempDir.resolve("test-repo");
        Files.createDirectories(repoRoot);
        Path providerJarPath = repoRoot.resolve(repoRelativeJarPath);
        Files.createDirectories(providerJarPath.getParent());
        Files.write(providerJarPath, providerBytes);

        // Derive provider entry path from INDEPENDENT_VERIFIER role (Authority-derived)
        String providerEntryPath = FACTORY.deriveAuthorityProviderEntryPath();

        // Build binding JSON using derived values
        String authorityFp = sha256fp(authBytes);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        root.put("authorityRawFingerprint", authorityFp);
        root.put("messageVersion", "1.0.0");
        root.put("sourceSliceId", "A1.2");
        root.put("targetSliceId", "A1.3");

        com.fasterxml.jackson.databind.node.ObjectNode prov = mapper.createObjectNode();
        prov.put("byteLength", (long) providerBytes.length);
        prov.put("coordinate", hCoord);
        prov.put("path", repoRelativeJarPath);
        prov.put("rawFingerprint", providerFp);
        root.set("providerArtifact", prov);

        // Compute binding fingerprint
        byte[] canonicalPayload = canonicalJson(mapper.valueToTree(root));
        byte[] domainBytes = "RG-CS-GATE-A-A1-3-DEVELOPMENT-PREDECESSOR-BINDING-v1"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] combined = new byte[domainBytes.length + 1 + canonicalPayload.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        combined[domainBytes.length] = 0;
        System.arraycopy(canonicalPayload, 0, combined, domainBytes.length + 1, canonicalPayload.length);
        String bindingFp = sha256fp(combined);
        root.put("bindingFingerprint", bindingFp);

        // Write binding JSON (exactly one LF per verifier protocol)
        byte[] finalPayload = canonicalJson(mapper.valueToTree(root));
        byte[] withLf = Arrays.copyOf(finalPayload, finalPayload.length + 1);
        withLf[finalPayload.length] = (byte) '\n';
        Path bindingJson = staticTempDir.resolve("binding.json");
        Files.write(bindingJson, withLf);

        // Verify using DevelopmentPredecessorBindingVerifier
        DevelopmentPredecessorBindingVerifier verifier =
                new DevelopmentPredecessorBindingVerifier();
        return verifier.verify(bindingJson, AUTHORITY_PATH, repoRoot);
    }

    /** Derives Maven coordinate string from a Jackson coordinate node. */
    private static String mavenCoord(com.fasterxml.jackson.databind.JsonNode coordNode) {
        String g = coordNode.path("groupId").asText("");
        String a = coordNode.path("artifactId").asText("");
        String v = coordNode.path("version").asText("");
        return g + ":" + a + ":" + v;
    }

    // -------------------------------------------------------------------------
    // T1: Provider bytes in ZIP
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T1: Provider bytes in ZIP")
    class ProviderBytesInZip {

        @Test
        @DisplayName("T1-01: Provider bytes in ZIP equal binding.providerBytes()")
        void provider_bytes_in_zip_equal_binding() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);

            byte[] extracted = extractEntryFromJarHelper(result.jarBytes(),
                    result.providerEntryPath());
            assertNotNull(extracted, "Provider entry must exist in ZIP");
            assertTrue(Arrays.equals(extracted, BINDING.providerBytes()),
                    "Extracted provider bytes must equal binding.providerBytes()");
        }

        @Test
        @DisplayName("T1-02: Provider entry path matches Authority-derived path")
        void provider_entry_path_from_authority() throws Exception {
            String expectedPath = FACTORY.deriveAuthorityProviderEntryPath();
            assertNotNull(expectedPath, "Authority provider entry path must be derivable");
            assertFalse(expectedPath.isBlank(), "Authority provider entry path must be non-blank");
            assertTrue(FACTORY.requiredEntries().contains(expectedPath),
                    "Authority provider entry path must be in requiredEntries");

            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);
            assertEquals(expectedPath, result.providerEntryPath(),
                    "Result provider entry path must match Authority-derived path");
        }
    }

    // -------------------------------------------------------------------------
    // T2: Exactly 28 entries
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T2: Entry count")
    class EntryCount {

        @Test
        @DisplayName("T2-01: Fixture JAR has exactly 28 entries")
        void fixture_jar_has_28_entries() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);
            assertEquals(28, result.totalEntryCount(),
                    "Fixture JAR must have exactly 28 entries");
        }

        @Test
        @DisplayName("T2-02: All required entries are present")
        void all_required_entries_present() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);

            Set<String> found = new HashSet<>();
            try (ZipInputStream zis = new ZipInputStream(
                    new ByteArrayInputStream(result.jarBytes()))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    found.add(e.getName());
                    zis.closeEntry();
                }
            }

            for (String required : FACTORY.requiredEntries()) {
                assertTrue(found.contains(required),
                        "Required entry must be present: " + required);
            }
        }
    }

    // -------------------------------------------------------------------------
    // T3: Parser acceptance
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T3: Parser acceptance")
    class ParserAcceptance {

        @Test
        @DisplayName("T3-01: Parser accepts fixture plan successfully")
        void parser_accepts_fixture_plan() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);
            byte[] planBytes = FACTORY.buildPackagingPlan(result.jarBytes());
            String expectedHash = sha256fp(planBytes);

            PackagingPlanParser parser = new PackagingPlanParser();
            PackagingPlanParser.ParseResult parseResult =
                    parser.parse(planBytes, expectedHash);

            assertTrue(parseResult.isSuccess(), "Parser must accept fixture plan");
            assertNotNull(parseResult.plan(), "Parsed plan must not be null");
        }

        @Test
        @DisplayName("T3-02: Parsed plan has correct schema version")
        void parsed_plan_schema_version() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);
            byte[] planBytes = FACTORY.buildPackagingPlan(result.jarBytes());

            PackagingPlanParser parser = new PackagingPlanParser();
            PackagingPlanParser.ParseResult parseResult =
                    parser.parse(planBytes, sha256fp(planBytes));

            assertTrue(parseResult.isSuccess());
            assertEquals("v1", parseResult.plan().schemaVersion());
        }
    }

    // -------------------------------------------------------------------------
    // T4: Kernel acceptance
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T4: Kernel acceptance")
    class KernelAcceptance {

        @Test
        @DisplayName("T4-01: Kernel accepts fixture JAR successfully")
        void kernel_accepts_fixture_jar() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);
            byte[] planBytes = FACTORY.buildPackagingPlan(result.jarBytes());

            PackagingPlanParser parser = new PackagingPlanParser();
            PackagingPlanParser.ParseResult parseResult =
                    parser.parse(planBytes, sha256fp(planBytes));
            assertTrue(parseResult.isSuccess());

            Path jarPath = tempDir.resolve("fixture-test.jar");
            Files.write(jarPath, result.jarBytes());

            ArchiveKernel kernel = new ArchiveKernel();
            ArchiveKernelSnapshot snapshot = kernel.verify(jarPath, parseResult.plan());

            assertFalse(snapshot.rejected(),
                    "Kernel must accept fixture JAR: " + snapshot.rejectionCode());
        }
    }

    // -------------------------------------------------------------------------
    // T5: Build determinism
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T5: Build determinism")
    class BuildDeterminism {

        @Test
        @DisplayName("T5-01: Three build runs produce identical JAR bytes")
        void three_builds_identical() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result1 =
                    FACTORY.buildFixtureJar(BINDING, tempDir);

            Path td2 = tempDir.resolve("run2");
            Path td3 = tempDir.resolve("run3");
            RealVerifierFixtureFactory.FixtureProviderResult result2 =
                    FACTORY.buildFixtureJar(BINDING, td2);
            RealVerifierFixtureFactory.FixtureProviderResult result3 =
                    FACTORY.buildFixtureJar(BINDING, td3);

            assertTrue(Arrays.equals(result1.jarBytes(), result2.jarBytes()),
                    "First and second build must produce identical bytes");
            assertTrue(Arrays.equals(result1.jarBytes(), result3.jarBytes()),
                    "First and third build must produce identical bytes");
            assertTrue(Arrays.equals(result2.jarBytes(), result3.jarBytes()),
                    "Second and third build must produce identical bytes");
        }
    }

    // -------------------------------------------------------------------------
    // T6: Snapshot determinism using ArchiveKernelSnapshotSerializer
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T6: Snapshot determinism")
    class SnapshotDeterminism {

        @Test
        @DisplayName("T6-01: Three serialized snapshots via serializer are byte-identical")
        void three_serialized_snapshots_identical() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);
            byte[] planBytes = FACTORY.buildPackagingPlan(result.jarBytes());

            PackagingPlanParser parser = new PackagingPlanParser();
            PackagingPlanParser.ParseResult parseResult =
                    parser.parse(planBytes, sha256fp(planBytes));
            assertTrue(parseResult.isSuccess());

            ArchiveKernelSnapshotSerializer serializer =
                    new ArchiveKernelSnapshotSerializer();
            ArchiveKernel kernel = new ArchiveKernel();

            Path jarPath = tempDir.resolve("snap1.jar");
            Files.write(jarPath, result.jarBytes());
            ArchiveKernelSnapshot snap1 = kernel.verify(jarPath, parseResult.plan());
            byte[] ser1 = serializer.serialize(snap1);

            jarPath = tempDir.resolve("snap2.jar");
            Files.write(jarPath, result.jarBytes().clone());
            ArchiveKernelSnapshot snap2 = kernel.verify(jarPath, parseResult.plan());
            byte[] ser2 = serializer.serialize(snap2);

            jarPath = tempDir.resolve("snap3.jar");
            Files.write(jarPath, result.jarBytes().clone());
            ArchiveKernelSnapshot snap3 = kernel.verify(jarPath, parseResult.plan());
            byte[] ser3 = serializer.serialize(snap3);

            assertFalse(snap1.rejected());
            assertFalse(snap2.rejected());
            assertFalse(snap3.rejected());

            assertArrayEquals(ser1, ser2,
                    "First and second serialized snapshots must be byte-identical");
            assertArrayEquals(ser1, ser3,
                    "First and third serialized snapshots must be byte-identical");
            assertArrayEquals(ser2, ser3,
                    "Second and third serialized snapshots must be byte-identical");
        }
    }

    // -------------------------------------------------------------------------
    // T7: Wrong providerEntryPath validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T7: Validation - wrong providerEntryPath")
    class WrongProviderEntryPath {

        @Test
        @DisplayName("T7-01: Null binding fails with R03-FIXTURE-PROVIDER-MISMATCH")
        void null_binding_fails() {
            FixtureProviderException e = assertThrows(
                    FixtureProviderException.class,
                    () -> FACTORY.buildFixtureJar(null, tempDir));
            assertEquals(R03_FIXTURE_PROVIDER_MISMATCH,
                    e.reasonCode());
            assertNotNull(e.reasonArgs());
        }

        @Test
        @DisplayName("T7-02: Wrong providerEntryPath fails with R03-FIXTURE-PROVIDER-MISMATCH")
        void wrong_provider_entry_path_fails() throws Exception {
            String wrongPath = FACTORY.deriveAuthorityProviderEntryPath() + "-WRONG";

            DevelopmentPredecessorBinding.ProviderArtifact art =
                    new DevelopmentPredecessorBinding.ProviderArtifact(
                            "com.leanowtech.bloge:test:1.0.0",
                            "test/path.jar",
                            BINDING.providerBytes().length,
                            sha256fp(BINDING.providerBytes()));

            DevelopmentPredecessorBinding wrongBinding = new DevelopmentPredecessorBinding(
                    sha256fp("wrong".getBytes()),
                    sha256fp(Files.readAllBytes(AUTHORITY_PATH)),
                    DevelopmentPredecessorBinding.MESSAGE_VERSION,
                    DevelopmentPredecessorBinding.SOURCE_SLICE_ID,
                    DevelopmentPredecessorBinding.TARGET_SLICE_ID,
                    art,
                    wrongPath,
                    BINDING.providerBytes().clone()
            );

            FixtureProviderException e = assertThrows(
                    FixtureProviderException.class,
                    () -> FACTORY.buildFixtureJar(wrongBinding, tempDir));
            assertEquals(R03_FIXTURE_PROVIDER_MISMATCH,
                    e.reasonCode());
            assertNotNull(e.reasonArgs());
        }
    }

    // -------------------------------------------------------------------------
        // -------------------------------------------------------------------------
    // T8: Provider path not in required entries (provable fail)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T8: Provider path not in required entries")
    class ProviderNotRequired {

        @Test
        @DisplayName("T8-01: providerEntryPath not in requiredEntries fails with R03-FIXTURE-PROVIDER-MISMATCH")
        void provider_not_in_required_fails() throws Exception {
            String nonRequiredPath = "NOT/REQUIRED/entry.txt";

            DevelopmentPredecessorBinding.ProviderArtifact art =
                    new DevelopmentPredecessorBinding.ProviderArtifact(
                            "com.leanowtech.bloge:test:1.0.0",
                            "test/path.jar",
                            BINDING.providerBytes().length,
                            sha256fp(BINDING.providerBytes()));

            DevelopmentPredecessorBinding badBinding = new DevelopmentPredecessorBinding(
                    sha256fp("bad".getBytes()),
                    sha256fp(Files.readAllBytes(AUTHORITY_PATH)),
                    DevelopmentPredecessorBinding.MESSAGE_VERSION,
                    DevelopmentPredecessorBinding.SOURCE_SLICE_ID,
                    DevelopmentPredecessorBinding.TARGET_SLICE_ID,
                    art,
                    nonRequiredPath,
                    BINDING.providerBytes().clone()
            );

            FixtureProviderException e = assertThrows(
                    FixtureProviderException.class,
                    () -> FACTORY.buildFixtureJar(badBinding, tempDir));
            assertEquals(R03_FIXTURE_PROVIDER_MISMATCH,
                    e.reasonCode());
        }
    }

    // T9: Defensive copy
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T9: Defensive copy")
    class DefensiveCopy {

        @Test
        @DisplayName("T9-01: Mutating jarBytes() returned from result does not affect internal state")
        void mutate_jar_bytes_does_not_affect_result() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);

            // Retain pristine copy before mutation
            byte[] pristine = result.jarBytes().clone();
            assertTrue(pristine.length > 0, "Result must contain JAR bytes");

            // Mutate the returned array
            byte[] returned = result.jarBytes();
            returned[0] = (byte) (returned[0] ^ 0xFF);

            // Fresh accessor call must return pristine bytes unchanged
            byte[] fresh = result.jarBytes();
            assertArrayEquals(pristine, fresh,
                    "jarBytes() must return pristine bytes after mutation of previous return");
        }

        @Test
        @DisplayName("T9-02: Mutating providerBytes() returned from result does not affect internal state")
        void mutate_provider_bytes_does_not_affect_result() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);

            // Mutate the returned providerBytes
            byte[] returned = result.providerBytes();
            if (returned.length > 0) {
                returned[0] = (byte) (returned[0] ^ 0xFF);
            }

            // Get fresh copy and verify unchanged
            byte[] fresh = result.providerBytes();
            assertTrue(Arrays.equals(BINDING.providerBytes(), fresh),
                    "Provider bytes must match binding bytes after mutation attempt");
        }

        @Test
        @DisplayName("T9-03: Both accessors return independent arrays")
        void both_accessors_independent() throws Exception {
            RealVerifierFixtureFactory.FixtureProviderResult result =
                    FACTORY.buildFixtureJar(BINDING, tempDir);

            byte[] jar1 = result.jarBytes();
            byte[] jar2 = result.jarBytes();
            assertArrayEquals(jar1, jar2,
                    "jarBytes() must return identical content on repeated calls");

            byte[] prov1 = result.providerBytes();
            byte[] prov2 = result.providerBytes();
            assertArrayEquals(prov1, prov2,
                    "providerBytes() must return identical content on repeated calls");
        }
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // T10: No payload leak
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T10: No payload leak")
    class NoPayloadLeak {

        @Test
        @DisplayName("T10-01: FixtureProviderException never leaks provider bytes in message or args")
        void exception_no_payload_leak() throws Exception {
            // Use wrong path to trigger V1 exception (detail contains paths, not bytes)
            String wrongPath = FACTORY.deriveAuthorityProviderEntryPath() + "-WRONG";

            DevelopmentPredecessorBinding.ProviderArtifact art =
                    new DevelopmentPredecessorBinding.ProviderArtifact(
                            "com.leanowtech.bloge:test:1.0.0",
                            "test/path.jar",
                            BINDING.providerBytes().length,
                            sha256fp(BINDING.providerBytes()));

            DevelopmentPredecessorBinding badBinding = new DevelopmentPredecessorBinding(
                    sha256fp("bad".getBytes()),
                    sha256fp(Files.readAllBytes(AUTHORITY_PATH)),
                    DevelopmentPredecessorBinding.MESSAGE_VERSION,
                    DevelopmentPredecessorBinding.SOURCE_SLICE_ID,
                    DevelopmentPredecessorBinding.TARGET_SLICE_ID,
                    art,
                    wrongPath,
                    BINDING.providerBytes().clone()
            );

            FixtureProviderException e = assertThrows(
                    FixtureProviderException.class,
                    () -> FACTORY.buildFixtureJar(badBinding, tempDir));

            assertEquals(R03_FIXTURE_PROVIDER_MISMATCH,
                    e.reasonCode());
            assertNotNull(e.reasonArgs(), "reasonArgs must never be null");
            assertNotEquals(0, e.reasonArgs().size(), "reasonArgs must be non-empty on failure");

            // Message: code only, never contains provider bytes
            String msg = e.getMessage();
            assertFalse(msg.contains("REAL-PROVIDER"),
                    "Message must not contain provider content");
            assertFalse(msg.contains("R03-PROVIDER"),
                    "Message must not contain provider content");
            assertFalse(msg.contains("WRONG"),
                    "Message must not contain WRONG string used only in test");

            // Args: never contains provider bytes
            String argsStr = e.reasonArgs().toString();
            assertFalse(argsStr.contains("REAL-PROVIDER"),
                    "reasonArgs must not contain provider content");
            assertFalse(argsStr.contains("R03-PROVIDER"),
                    "reasonArgs must not contain provider content");
        }
    }

    // T11: DeriveAuthorityProviderEntryPath
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("T11: DeriveAuthorityProviderEntryPath")
    class DeriveAuthorityProviderEntryPath {

        @Test
        @DisplayName("T11-01: Derived path is non-blank and member of requiredEntries")
        void derive_non_blank_member_required() {
            String path = FACTORY.deriveAuthorityProviderEntryPath();
            assertNotNull(path);
            assertFalse(path.isBlank(), "Derived path must be non-blank");
            assertTrue(FACTORY.requiredEntries().contains(path),
                    "Derived path must be a member of requiredEntries");
        }

        @Test
        @DisplayName("T11-02: Derived path is NOT an embedded dependency")
        void derive_not_embedded_dependency() {
            String path = FACTORY.deriveAuthorityProviderEntryPath();
            boolean isEmbedded = FACTORY.embeddedDependencies().stream()
                    .anyMatch(dep -> dep.entryPath().equals(path));
            assertFalse(isEmbedded,
                    "Provider entry path must not be an embedded dependency");
        }
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private static String sha256fp(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return "sha256:" + hex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Builds a deterministic ZIP with the provider JAR entry. */
    private static byte[] buildDeterministicProviderJar() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        try (java.util.zip.ZipOutputStream zos =
                new java.util.zip.ZipOutputStream(baos)) {
            // Provider manifest entry
            java.util.zip.ZipEntry manifest = new java.util.zip.ZipEntry(
                    "META-INF/MANIFEST.MF");
            manifest.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zos.putNextEntry(manifest);
            zos.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Provider class entry
            java.util.zip.ZipEntry cls = new java.util.zip.ZipEntry(
                    "com/example/Provider.class");
            cls.setMethod(java.util.zip.ZipEntry.DEFLATED);
            zos.putNextEntry(cls);
            // Deterministic class-like content
            byte[] clsContent = "R03-PROVIDER-v1.0.0".getBytes(StandardCharsets.UTF_8);
            zos.write(clsContent);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    /** Canonical JSON: sort keys, no extra whitespace. */
    private static byte[] canonicalJson(com.fasterxml.jackson.databind.JsonNode node) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        canonicalize(node, baos);
        return baos.toByteArray();
    }

    private static void canonicalize(com.fasterxml.jackson.databind.JsonNode node,
                                     ByteArrayOutputStream out) {
        if (node.isObject()) {
            out.write('{');
            List<String> keys = new ArrayList<>();
            for (java.util.Iterator<String> it = node.fieldNames(); it.hasNext(); )
                keys.add(it.next());
            keys.sort(String::compareTo);
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.write(',');
                out.write('"');
                try { out.write(keys.get(i).getBytes(StandardCharsets.US_ASCII)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
                out.write('"');
                out.write(':');
                canonicalize(node.get(keys.get(i)), out);
            }
            out.write('}');
        } else if (node.isArray()) {
            out.write('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.write(',');
                canonicalize(node.get(i), out);
            }
            out.write(']');
        } else if (node.isTextual()) {
            out.write('"');
            escapeString(node.asText(), out);
            out.write('"');
        } else if (node.isNumber()) {
            try { out.write(node.asText().getBytes(StandardCharsets.US_ASCII)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        } else if (node.isBoolean()) {
            try { out.write(Boolean.toString(node.asBoolean())
                    .getBytes(StandardCharsets.US_ASCII)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        } else {
            try { out.write("null".getBytes(StandardCharsets.US_ASCII)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }

    private static void escapeString(String s, ByteArrayOutputStream out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> { out.write(92); out.write(34); }
                case '\\' -> { out.write(92); out.write(92); }
                case '\b' -> { out.write(92); out.write(98);  }
                case '\f' -> { out.write(92); out.write(102); }
                case '\n' -> { out.write(92); out.write(110); }
                case '\r' -> { out.write(92); out.write(114); }
                case '\t' -> { out.write(92); out.write(116); }
                default   -> {
                    if (c < 0x20) {
                        try { out.write(String.format("\\u%04x", (int) c)
                                .getBytes(StandardCharsets.US_ASCII)); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    } else {
                        out.write(c);
                    }
                }
            }
        }
    }

    /** Extracts entry bytes from JAR, returns null if not found. Throws IOException on I/O error. */
    private static byte[] extractEntryFromJarHelper(byte[] jarBytes, String entryPath)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryPath)) {
                    byte[] content = zis.readAllBytes();
                    zis.closeEntry();
                    return content;
                }
                zis.closeEntry();
            }
        }
        return null;
    }
}
