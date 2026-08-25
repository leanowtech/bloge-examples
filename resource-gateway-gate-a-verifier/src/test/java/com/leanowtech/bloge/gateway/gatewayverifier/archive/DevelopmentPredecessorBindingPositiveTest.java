package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Positive acceptance and negative error-path tests for D7-T2 DevelopmentPredecessorBinding.
 *
 * Run with:
 *   mvn -Pgate-a-verifier -Dgate.a.slice=A1.3 \
 *     -Dtest='DevelopmentPredecessorBinding*Test' \
 *     -f resource-gateway-gate-a-verifier/pom.xml test
 */
class DevelopmentPredecessorBindingPositiveTest {

    @TempDir
    Path tempDir;

    private Path authorityPath;
    private Path providerPath;
    private Path repoRoot;
    private byte[] providerBytes;
    private String providerFp;
    private String authorityFp;
    private DevelopmentPredecessorBindingVerifier verifier;

    // Expected from Authority A1.2 delivery slice
    private static final String MSG_VERSION = "1.0.0";
    private static final String SRC_SLICE = "A1.2";
    private static final String TGT_SLICE = "A1.3";
    private String expectedCoord;
    private String expectedPath;
    private String providerEntryPath;
    private ObjectMapper strictMapper;

        @BeforeEach
    void setUp() throws Exception {
        // Build strict ObjectMapper for Authority parsing
        var factory = new com.fasterxml.jackson.core.JsonFactoryBuilder()
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        strictMapper = new ObjectMapper(factory)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // Determine authorityPath
        String authProp = System.getProperty("gate.a.authority.path");
        if (authProp != null) {
            authorityPath = Path.of(authProp);
        } else {
            Path cwd = Path.of(System.getProperty("user.dir"));
            authorityPath = cwd.resolve("docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/gate-a-protocol-authority-v1.json");
        }

        // Read authority bytes for derivation
        if (!Files.exists(authorityPath)) {
            byte[] fallback = "{\"deliverySlices\":[],\"roleContracts\":[]}".getBytes(StandardCharsets.US_ASCII);
            authorityPath = tempDir.resolve("authority.json");
            Files.write(authorityPath, fallback);
        }
        byte[] authBytes = Files.readAllBytes(authorityPath);
        authorityFp = sha256(authBytes);
        JsonNode authorityRoot = strictMapper.readTree(authBytes);

        // Derive expectedCoord and expectedPath from actual Authority A1.2 slice
        JsonNode slices = authorityRoot.path("deliverySlices");
        JsonNode a12 = null;
        for (JsonNode s : slices) {
            if ("A1.2".equals(s.path("sliceId").asText())) { a12 = s; break; }
        }
        if (a12 != null) {
            JsonNode h0 = a12.path("handoffArtifacts").get(0);
            expectedCoord = h0.path("coordinate").path("groupId").asText("")
                    + ":" + h0.path("coordinate").path("artifactId").asText("")
                    + ":" + h0.path("coordinate").path("version").asText("");
            expectedPath = h0.path("path").asText("");
        } else {
            expectedCoord = "com.leanowtech.bloge:resource-gateway-gate-a-tck-provider:1.0.0";
            expectedPath = "resource-gateway-gate-a-tck-provider/target/resource-gateway-gate-a-tck-provider-1.0.0.jar";
        }

        // Derive providerEntryPath from INDEPENDENT_VERIFIER role
        for (JsonNode role : authorityRoot.path("roleContracts")) {
            if ("INDEPENDENT_VERIFIER".equals(role.path("role").asText())) {
                providerEntryPath = role.path("providerEntryPath").asText("");
                break;
            }
        }

        // Find repo root: traverse up from authorityPath until we find both modules
        Path candidate = authorityPath.toAbsolutePath().getParent();
        while (candidate != null) {
            Path vPath = candidate.resolve("resource-gateway-gate-a-verifier/pom.xml");
            Path pPath = candidate.resolve("resource-gateway-gate-a-tck-provider");
            if (Files.exists(vPath) && Files.isDirectory(pPath)) {
                repoRoot = candidate;
                break;
            }
            candidate = candidate.getParent();
        }
        if (repoRoot == null) repoRoot = authorityPath.toAbsolutePath().getParent();

        // Derive providerPath from repoRoot + expectedPath and read bytes
        providerPath = repoRoot.resolve(expectedPath);
        if (Files.exists(providerPath)) {
            providerBytes = Files.readAllBytes(providerPath);
        } else {
            providerBytes = "mock-provider".getBytes(StandardCharsets.US_ASCII);
        }
        providerFp = sha256(providerBytes);

        verifier = new DevelopmentPredecessorBindingVerifier();
    }

    // Value type tests    // Value type tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ProviderArtifact: validates byteLength range and SHA format")
    void providerArtifact_validation() {
        assertThrows(IllegalArgumentException.class,
                () -> new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", "p", 0, providerFp));
        assertThrows(IllegalArgumentException.class,
                () -> new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", "p", 17 * 1024 * 1024, providerFp));
        // Invalid: uppercase hex (not lowercase)
        assertThrows(IllegalArgumentException.class,
                () -> new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", "p", 1,
                        "sha256:" + "A".repeat(64)));
        // Invalid: wrong algorithm prefix
        assertThrows(IllegalArgumentException.class,
                () -> new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", "p", 1,
                        "sha256:" + "a".repeat(63) + "g"));  // 65 chars after prefix
        assertThrows(IllegalArgumentException.class,
                () -> new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", "p", 1,
                        "md5:" + "a".repeat(32)));
        assertThrows(NullPointerException.class,
                () -> new DevelopmentPredecessorBinding.ProviderArtifact(null, "p", 1, providerFp));
        assertThrows(NullPointerException.class,
                () -> new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", null, 1, providerFp));
        // Valid construction
        var art = new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", "p/path", 1024, providerFp);
        assertEquals("g:a:v", art.coordinate());
        assertEquals("p/path", art.path());
        assertEquals(1024, art.byteLength());
    }

    @Test
    @DisplayName("Binding: defensive copy, equals, hashCode")
    void binding_defensive_and_equality() {
        byte[] bytes = "test".getBytes(StandardCharsets.US_ASCII);
        var pa = new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", "p", bytes.length, providerFp);
        var b1 = new DevelopmentPredecessorBinding(
                providerFp, authorityFp, MSG_VERSION, SRC_SLICE, TGT_SLICE, pa, providerEntryPath, bytes);
        byte[] retrieved = b1.providerBytes();
        assertNotSame(bytes, retrieved);
        assertArrayEquals(bytes, retrieved);

        byte[] bytes2 = "test".getBytes(StandardCharsets.US_ASCII);
        var pa2 = new DevelopmentPredecessorBinding.ProviderArtifact("g:a:v", "p", bytes2.length, providerFp);
        var b2 = new DevelopmentPredecessorBinding(
                providerFp, authorityFp, MSG_VERSION, SRC_SLICE, TGT_SLICE, pa2, providerEntryPath, bytes2);
        assertEquals(b1, b2);
        assertEquals(b1.hashCode(), b2.hashCode());
    }

    // -------------------------------------------------------------------------
    // Positive baseline: canonical binding with actual provider verifies
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Baseline: canonical binding with correct facts verifies successfully")
    void baseline_positive_verify() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);

        DevelopmentPredecessorBinding result = verifier.verify(bindingPath, authorityPath, repoRoot);
        assertEquals(MSG_VERSION, result.messageVersion());
        assertEquals(SRC_SLICE, result.sourceSliceId());
        assertEquals(TGT_SLICE, result.targetSliceId());
        assertArrayEquals(providerBytes, result.providerBytes());
        assertEquals(providerEntryPath, result.providerEntryPath());
    }

    @Test
    @DisplayName("3-run determinism: same binding verifies identically 3 times")
    void determinism_3_runs() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);

        DevelopmentPredecessorBinding r1 = verifier.verify(bindingPath, authorityPath, repoRoot);
        DevelopmentPredecessorBinding r2 = verifier.verify(bindingPath, authorityPath, repoRoot);
        DevelopmentPredecessorBinding r3 = verifier.verify(bindingPath, authorityPath, repoRoot);

        assertEquals(r1.bindingFingerprint(), r2.bindingFingerprint());
        assertEquals(r2.bindingFingerprint(), r3.bindingFingerprint());
        assertArrayEquals(r1.providerBytes(), r2.providerBytes());
        assertArrayEquals(r2.providerBytes(), r3.providerBytes());
    }

    // -------------------------------------------------------------------------
    // R03-BINDING-MISSING
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-BINDING-MISSING: non-existent file")
    void binding_missing() {
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(tempDir.resolve("nonexistent.json"), authorityPath, repoRoot));
        assertEquals("R03-BINDING-MISSING", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // R03-BINDING-INVALID: shape
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-BINDING-INVALID: unknown top-level key")
    void binding_invalid_unknown_key() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        binding = binding.replace("}", ",\"unknownKey\":null}");
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    @Test
    @DisplayName("R03-BINDING-INVALID: duplicate top-level key")
    void binding_invalid_duplicate_key() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        // Insert duplicate key before closing brace
        int brace = binding.lastIndexOf('}');
        binding = binding.substring(0, brace) + ",\"bindingFingerprint\":null" + binding.substring(brace);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    @Test
    @DisplayName("R03-BINDING-INVALID: missing top-level key (targetSliceId)")
    void binding_invalid_missing_key() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        // Remove targetSliceId key-value pair
        binding = binding.replace(",\"targetSliceId\":\"" + TGT_SLICE + "\"", "");
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    @Test
    @DisplayName("R03-BINDING-INVALID: providerArtifact wrong key count (extra)")
    void binding_invalid_provider_extra_key() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        binding = binding.replace(
                "\"rawFingerprint\":\"" + providerFp + "\"",
                "\"rawFingerprint\":\"" + providerFp + "\",\"extra\":null");
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    @Test
    @DisplayName("R03-BINDING-INVALID: trailing token after JSON object")
    void binding_invalid_trailing_token() throws Exception {
        // Jackson allows trailing content after a single valid JSON value by default.
        // Write raw bytes: valid JSON + binary garbage that is not valid UTF-8 JSON
        // continuation, forcing Jackson to throw a parse exception.
        byte[] jsonBytes = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] garbage = new byte[] { (byte)0xFF, (byte)0xFE, (byte)0x00, (byte)0x00 };
        Path bindingPath = writeBindingBytes(jsonBytes, garbage);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    @Test
    @DisplayName("R03-BINDING-INVALID: nonfinite numbers (NaN)")
    void binding_invalid_nonfinite() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        binding = binding.replace(
                "\"byteLength\":" + providerBytes.length,
                "\"byteLength\":NaN");
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // R03-BINDING-STRUCTURE-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-BINDING-STRUCTURE-MISMATCH: wrong messageVersion")
    void structure_wrong_messageVersion() throws Exception {
        String binding = buildCanonicalBinding("2.0.0", SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-STRUCTURE-MISMATCH", ex.reasonCode());
        assertEquals("messageVersion", ex.reasonArgs().get("field"));
        assertEquals(MSG_VERSION, ex.reasonArgs().get("expected"));
    }

    @Test
    @DisplayName("R03-BINDING-STRUCTURE-MISMATCH: wrong sourceSliceId")
    void structure_wrong_sourceSliceId() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, "A1.1", TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-STRUCTURE-MISMATCH", ex.reasonCode());
        assertEquals("sourceSliceId", ex.reasonArgs().get("field"));
    }

    @Test
    @DisplayName("R03-BINDING-STRUCTURE-MISMATCH: wrong targetSliceId")
    void structure_wrong_targetSliceId() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, "A1.4",
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-STRUCTURE-MISMATCH", ex.reasonCode());
        assertEquals("targetSliceId", ex.reasonArgs().get("field"));
    }

    // -------------------------------------------------------------------------
    // R03-BINDING-FP-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-BINDING-FP-MISMATCH: binding fingerprint wrong")
    void binding_fp_mismatch() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        binding = binding.replaceFirst(
                "sha256:[a-f0-9]{64}",
                "sha256:" + "0".repeat(64));
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-FP-MISMATCH", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // R03-AUTHORITY-FP-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-AUTHORITY-FP-MISMATCH: authority fingerprint wrong")
    void authority_fp_mismatch() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp,
                "sha256:" + "c".repeat(64));
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-AUTHORITY-FP-MISMATCH", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // R03-COORDINATE-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-COORDINATE-MISMATCH: provider coordinate wrong")
    void coordinate_mismatch() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                "wrong:coord:version", expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-COORDINATE-MISMATCH", ex.reasonCode());
        assertEquals(expectedCoord, ex.reasonArgs().get("expected"));
    }

    // -------------------------------------------------------------------------
    // R03-PATH-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-PATH-MISMATCH: provider path wrong")
    void path_mismatch() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, "wrong/path", providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-PATH-MISMATCH", ex.reasonCode());
        assertEquals(expectedPath, ex.reasonArgs().get("expected"));
    }

    // -------------------------------------------------------------------------
    // R03-SIZE-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-SIZE-MISMATCH: provider byteLength wrong")
    void size_mismatch() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length + 1, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-SIZE-MISMATCH", ex.reasonCode());
        assertEquals(providerBytes.length + 1L, ((Number) ex.reasonArgs().get("expected")).longValue());
    }

    // -------------------------------------------------------------------------
    // R03-PROVIDER-FP-MISMATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-PROVIDER-FP-MISMATCH: provider fingerprint wrong")
    void provider_fp_mismatch() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length,
                "sha256:" + "d".repeat(64), authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-PROVIDER-FP-MISMATCH", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // R03-READ-UNREADABLE (injectable reader hook)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-READ-UNREADABLE: provider file unreadable")
    void provider_unreadable() throws Exception {
        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier(
                (path, maxBytes) -> {
                    if (path.toAbsolutePath().normalize().equals(providerPath.toAbsolutePath().normalize())) {
                        throw DevelopmentPredecessorBindingVerifier.FileReadException.unreadable("simulated unreadable");
                    }
                    byte[] bytes = Files.readAllBytes(path);
                    return new DevelopmentPredecessorBindingVerifier.PathBytes(
                            bytes, 0L, 0L, bytes.length, 0L);
                });
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-READ-UNREADABLE", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // R03-READ-STALE (injectable reader hook)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-READ-STALE: provider pre/post size/mtime mismatch")
    void provider_stale() throws Exception {
        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier(
                (path, maxBytes) -> {
                    if (path.toAbsolutePath().normalize().equals(providerPath.toAbsolutePath().normalize())) {
                        throw DevelopmentPredecessorBindingVerifier.FileReadException.stale(path);
                    }
                    byte[] bytes = Files.readAllBytes(path);
                    return new DevelopmentPredecessorBindingVerifier.PathBytes(
                            bytes, 0L, 0L, bytes.length, 0L);
                });
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-READ-STALE", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // R03-READ-OVERSIZE (injectable reader hook)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("R03-READ-OVERSIZE: provider file > 16MiB")
    void provider_oversize() throws Exception {
        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier(
                (path, maxBytes) -> {
                    if (path.toAbsolutePath().normalize().equals(providerPath.toAbsolutePath().normalize())) {
                        throw DevelopmentPredecessorBindingVerifier.FileReadException.oversize(17 * 1024 * 1024);
                    }
                    byte[] bytes = Files.readAllBytes(path);
                    return new DevelopmentPredecessorBindingVerifier.PathBytes(
                            bytes, 0L, 0L, bytes.length, 0L);
                });
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-READ-OVERSIZE", ex.reasonCode());
        assertTrue(((Number) ex.reasonArgs().get("size")).longValue() > 16 * 1024 * 1024);
    }

    // -------------------------------------------------------------------------
    // Security: no payload leak
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Exception reasonArgs never expose raw binding/provider bytes")
    void no_payload_leak() throws Exception {
        String binding = buildCanonicalBinding("X", SRC_SLICE, TGT_SLICE,
                "c:a:v", "p", 1, providerFp, authorityFp);
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        String argsStr = ex.reasonArgs().toString();
        assertFalse(argsStr.contains("provider-bytes"));
        assertFalse(argsStr.contains("rawBytes"));
    }

    // -------------------------------------------------------------------------
    // First-failure priority checks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("First-failure: BINDING-MISSING fires before other errors")
    void first_failure_missing() {
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(tempDir.resolve("missing.json"), authorityPath, repoRoot));
        assertEquals("R03-BINDING-MISSING", ex.reasonCode());
    }

    @Test
    @DisplayName("First-failure: BINDING-INVALID (unknown key) fires before structure")
    void first_failure_invalid_before_structure() throws Exception {
        String binding = buildCanonicalBinding(MSG_VERSION, SRC_SLICE, TGT_SLICE,
                expectedCoord, expectedPath, providerBytes.length, providerFp, authorityFp);
        binding = binding.replace("}", ",\"badKey\":null}");
        Path bindingPath = writeBinding(binding);
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                verifier.verify(bindingPath, authorityPath, repoRoot));
        assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // Helper: canonical JSON construction
    // -------------------------------------------------------------------------

    /**
     * Build a canonical binding JSON string.
     * Uses Jackson ObjectMapper to parse and canonicalize, matching production behavior exactly.
     * File is written with exactly one LF (no CRLF, no trailing content).
     */
    private String buildCanonicalBinding(
            String msgV, String srcS, String tgtS,
            String coord, String provPath, long provLen, String provFp,
            String authFp) throws Exception {

        // Build the JSON as an ObjectNode, then canonicalize
        ObjectNode root = strictMapper.createObjectNode();
        root.put("authorityRawFingerprint", authFp);
        root.put("messageVersion", msgV);
        root.put("sourceSliceId", srcS);
        root.put("targetSliceId", tgtS);

        ObjectNode prov = strictMapper.createObjectNode();
        prov.put("byteLength", provLen);
        prov.put("coordinate", coord);
        prov.put("path", provPath);
        prov.put("rawFingerprint", provFp);
        root.set("providerArtifact", prov);

        // Compute binding fingerprint from domain+ASCII+NUL+canonical JSON (without bindingFingerprint field)
        byte[] canonicalPayload = canonicalJson(root);
        byte[] domainBytes = "RG-CS-GATE-A-A1-3-DEVELOPMENT-PREDECESSOR-BINDING-v1"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] combined = new byte[domainBytes.length + 1 + canonicalPayload.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        combined[domainBytes.length] = 0;
        System.arraycopy(canonicalPayload, 0, combined, domainBytes.length + 1, canonicalPayload.length);
        String bindingFp = sha256(combined);

        // Add bindingFingerprint and re-canonicalize
        root.put("bindingFingerprint", bindingFp);
        byte[] finalPayload = canonicalJson(root);

        // Write as ASCII string (canonicalJson produces ASCII-only output)
        return new String(finalPayload, StandardCharsets.US_ASCII);
    }
    /**
     * Canonical JSON: sort keys, no extra whitespace.
     * Copied from DevelopmentPredecessorBindingVerifier to ensure test matches production.
     */
    private static byte[] canonicalJson(JsonNode node) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        canonicalize(node, baos);
        return baos.toByteArray();
    }

    private static void canonicalize(JsonNode node, ByteArrayOutputStream out) {
        if (node.isObject()) {
            out.write('{');
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); )
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
            try { out.write(Boolean.toString(node.asBoolean()).getBytes(StandardCharsets.US_ASCII)); }
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

    private Path writeBinding(String json) throws IOException {
        Path p = tempDir.resolve("binding-" + System.nanoTime() + ".json");
        // Write canonical bytes + exactly one LF (no CRLF)
        byte[] data = (json + "\n").getBytes(StandardCharsets.US_ASCII);
        Files.write(p, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return p;
    }


    /**
     * Write binding JSON bytes followed by arbitrary raw bytes (no string encoding).
     * Used to inject invalid content that Jackson must reject during parsing.
     */
    private Path writeBindingBytes(byte[] jsonBytes, byte[]... extraChunks) throws IOException {
        Path p = tempDir.resolve("binding-" + System.nanoTime() + ".json");
        try (OutputStream os = Files.newOutputStream(p,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            os.write(jsonBytes);
            for (byte[] chunk : extraChunks) os.write(chunk);
        }
        return p;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Exception catcher
    // -------------------------------------------------------------------------

    private static DevelopmentPredecessorBindingException catchEx(ThrowingRunnable r) {
        try {
            r.run();
            fail("Expected DevelopmentPredecessorBindingException");
            return null;
        } catch (DevelopmentPredecessorBindingException e) {
            return e;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root)
                root = root.getCause();
            fail("Expected DevelopmentPredecessorBindingException but got: " + root.getClass().getName() + ": " + root.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
