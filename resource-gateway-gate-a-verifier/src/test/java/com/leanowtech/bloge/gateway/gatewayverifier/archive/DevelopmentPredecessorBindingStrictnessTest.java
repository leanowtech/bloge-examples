package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Negative strictness tests for DevelopmentPredecessorBindingVerifier.
 * Verifies R03-BINDING-INVALID (and related R03-*) rejection for malformed,
 * non-canonical, and tampered binding JSONs.
 *
 * All fixture helpers are self-contained; cannot access PositiveTest helpers.
 * All mutations preserve correct predecessor fingerprints (raw/binding).
 *
 * Run with:
 *   mvn -Pgate-a-verifier -Dgate.a.slice=A1.3 \
 *     -Dtest='DevelopmentPredecessorBinding*Test' \
 *     -f resource-gateway-gate-a-verifier/pom.xml test
 */
class DevelopmentPredecessorBindingStrictnessTest {

    private static final String MSG_VERSION = "1.0.0";
    private static final String SRC_SLICE  = "A1.2";
    private static final String TGT_SLICE  = "A1.3";
    private static final String BINDING_DOMAIN =
            "RG-CS-GATE-A-A1-3-DEVELOPMENT-PREDECESSOR-BINDING-v1";

    // Known-good values for A1.2 TCK_PROVIDER handoff
    private static final String TCK_COORD =
            "com.leanowtech.bloge:resource-gateway-gate-a-tck-provider:1.0.0";
    private static final String TCK_PATH =
            "resource-gateway-gate-a-tck-provider/target/resource-gateway-gate-a-tck-provider-1.0.0.jar";
    private static final String VERIFIER_PROVIDER_ENTRY_PATH =
            "META-INF/gate-a/gate-a-tck-provider-v1.jar";

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Self-contained fixture helpers
    // -------------------------------------------------------------------------

    /** SHA-256 of raw bytes, returned as sha256:<64 hex> (uppercase). */
    private static String sha256Hex(byte[] data) {
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

    /** Canonical JSON: sort keys alphabetically, no extra whitespace. */
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

    /** Compute binding fingerprint from canonical JSON (without bindingFingerprint key). */
    private static String computeBindingFpFromCanonical(byte[] canonicalWithoutBf) {
        byte[] domain = BINDING_DOMAIN.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[domain.length + 1 + canonicalWithoutBf.length];
        System.arraycopy(domain, 0, payload, 0, domain.length);
        payload[domain.length] = 0; // NUL
        System.arraycopy(canonicalWithoutBf, 0, payload, domain.length + 1,
                canonicalWithoutBf.length);
        return sha256Hex(payload);
    }

    /** Build a minimal valid Authority JSON with exactly one A1.2 slice + INDEPENDENT_VERIFIER role. */
    private byte[] buildMinimalAuthority(byte[] tckJarBytes) throws Exception {
        ObjectNode handoffCoord = JsonNodeFactory.instance.objectNode();
        handoffCoord.put("groupId", "com.leanowtech.bloge");
        handoffCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        handoffCoord.put("version", "1.0.0");

        ObjectNode handoff = JsonNodeFactory.instance.objectNode();
        handoff.put("role", "TCK_PROVIDER");
        handoff.set("coordinate", handoffCoord);
        handoff.put("path", TCK_PATH);

        ObjectNode outputCoord = JsonNodeFactory.instance.objectNode();
        outputCoord.put("groupId", "com.leanowtech.bloge");
        outputCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        outputCoord.put("version", "1.0.0");

        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("role", "TCK_PROVIDER");
        output.set("coordinate", outputCoord);
        output.put("path", TCK_PATH);

        ObjectNode slice = JsonNodeFactory.instance.objectNode();
        slice.put("sliceId", "A1.2");
        slice.put("owner", "PROVIDER");
        slice.putArray("modules").add("resource-gateway-gate-a-tck-provider");
        slice.putArray("handoffArtifacts").add(handoff);
        slice.putArray("outputArtifacts").add(output);

        ObjectNode roleContract = JsonNodeFactory.instance.objectNode();
        roleContract.put("role", "INDEPENDENT_VERIFIER");
        roleContract.put("providerEntryPath", VERIFIER_PROVIDER_ENTRY_PATH);

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", "capability-studio.gate-a-protocol-authority.v1");
        root.put("authorityId", "GATE-A-PROTOCOL-AUTHORITY");
        root.put("revision", 1);
        root.putArray("deliverySlices").add(slice);
        root.putArray("roleContracts").add(roleContract);

        ObjectMapper mapper = new ObjectMapper(new JsonFactoryBuilder().build());
        return mapper.writeValueAsBytes(root);
    }

    /**
     * Unified helper: mutate a binding node and write a complete, canonical+LF binding file.
     *
     * Takes the base authority bytes and provider bytes, then applies the given mutation
     * to a freshly-built binding ObjectNode (starting from correct base values).
     * After mutation, recomputes bindingFingerprint from the mutated node's canonical form,
     * writes the provider file, and writes the binding JSON with exactly one trailing LF.
     *
     * @param authBytes   authority bytes (used for authRawFp and to write authority file)
     * @param provBytes   provider artifact bytes
     * @param mutation    lambda: receives the binding ObjectNode, mutates one or more fields.
     *                    Return value is ignored.
     * @return the authorityPath written (for verifier.verify call)
     */
    private Path writeBindingWithMutation(byte[] authBytes, byte[] provBytes,
                                          java.util.function.Consumer<ObjectNode> mutation)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper(new JsonFactoryBuilder().build());

        // Build fresh canonical binding node
        ObjectNode provNode = JsonNodeFactory.instance.objectNode();
        provNode.put("coordinate", TCK_COORD);
        provNode.put("path", TCK_PATH);
        provNode.put("byteLength", provBytes.length);
        provNode.put("rawFingerprint", sha256Hex(provBytes));

        ObjectNode bindingNode = JsonNodeFactory.instance.objectNode();
        bindingNode.put("authorityRawFingerprint", sha256Hex(authBytes));
        bindingNode.put("messageVersion", MSG_VERSION);
        bindingNode.put("sourceSliceId", SRC_SLICE);
        bindingNode.put("targetSliceId", TGT_SLICE);
        bindingNode.set("providerArtifact", provNode);

        // Apply mutation
        mutation.accept(bindingNode);

        // Recompute bindingFingerprint from mutated canonical form
        ObjectNode withoutBf = JsonNodeFactory.instance.objectNode();
        withoutBf.put("authorityRawFingerprint",
                bindingNode.get("authorityRawFingerprint").asText());
        withoutBf.put("messageVersion",
                bindingNode.get("messageVersion").asText());
        withoutBf.put("sourceSliceId",
                bindingNode.get("sourceSliceId").asText());
        withoutBf.put("targetSliceId",
                bindingNode.get("targetSliceId").asText());
        withoutBf.set("providerArtifact", bindingNode.get("providerArtifact"));

        byte[] canonicalWithoutBf = canonicalJson(withoutBf);
        String newBindingFp = computeBindingFpFromCanonical(canonicalWithoutBf);
        bindingNode.put("bindingFingerprint", newBindingFp);

        // Serialize to canonical JSON bytes, then append exactly one LF
        byte[] canonical = canonicalJson(bindingNode);
        byte[] withLf = Arrays.copyOf(canonical, canonical.length + 1);
        withLf[canonical.length] = (byte) '\n';

        // Write provider file at derived path
        Path provPath = deriveProviderPath(tempDir, TCK_PATH);
        Files.createDirectories(provPath.getParent());
        Files.write(provPath, provBytes);

        // Write binding
        Path bindingPath = tempDir.resolve("binding.json");
        Files.write(bindingPath, withLf);

        // Write authority
        Path authorityPath = tempDir.resolve("authority.json");
        Files.write(authorityPath, authBytes);

        return authorityPath;
    }

    /** Derive provider path from repoRoot + repo-relative path. */
    private Path deriveProviderPath(Path repoRoot, String repoRelative) {
        Path p = repoRoot;
        for (String part : repoRelative.split("/")) {
            p = p.resolve(part);
        }
        return p;
    }

    /** Minimal ZIP stub bytes for provider JAR. */
    private static byte[] zipStub() {
        return new byte[]{'P','K',3,4};
    }

    // -------------------------------------------------------------------------
    // Exception catcher
    // -------------------------------------------------------------------------

    private static DevelopmentPredecessorBindingException catchEx(ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (DevelopmentPredecessorBindingException e) {
            return e;
        } catch (Throwable t) {
            fail("Expected DevelopmentPredecessorBindingException but got: " +
                    t.getClass().getName() + ": " + t.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    // -------------------------------------------------------------------------
    // Test cases
    // -------------------------------------------------------------------------

    // 1. Missing trailing LF
    @Test
    void rejectsBindingWithoutTrailingLf() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        String authFp = sha256Hex(authBytes);
        String provFp = sha256Hex(tckJar);

        // Build canonical binding, serialize WITHOUT trailing LF
        ObjectNode provNode = JsonNodeFactory.instance.objectNode();
        provNode.put("coordinate", TCK_COORD);
        provNode.put("path", TCK_PATH);
        provNode.put("byteLength", tckJar.length);
        provNode.put("rawFingerprint", provFp);

        ObjectNode bindingNode = JsonNodeFactory.instance.objectNode();
        bindingNode.put("bindingFingerprint", "sha256:" + "00".repeat(64));
        bindingNode.put("authorityRawFingerprint", authFp);
        bindingNode.put("messageVersion", MSG_VERSION);
        bindingNode.put("sourceSliceId", SRC_SLICE);
        bindingNode.put("targetSliceId", TGT_SLICE);
        bindingNode.set("providerArtifact", provNode);

        ObjectNode withoutBf = JsonNodeFactory.instance.objectNode();
        withoutBf.put("authorityRawFingerprint", authFp);
        withoutBf.put("messageVersion", MSG_VERSION);
        withoutBf.put("sourceSliceId", SRC_SLICE);
        withoutBf.put("targetSliceId", TGT_SLICE);
        withoutBf.set("providerArtifact", provNode);
        String bindingFp = computeBindingFpFromCanonical(canonicalJson(withoutBf));
        bindingNode.put("bindingFingerprint", bindingFp);

        // NO trailing LF
        byte[] canonical = canonicalJson(bindingNode);
        Path bindingPath = tempDir.resolve("binding.json");
        Files.write(bindingPath, canonical); // no LF appended

        Path authorityPath = writeAuthority(authBytes);

        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex, "Should reject binding without trailing LF");
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 2. Multiple trailing LFs
    @Test
    void rejectsBindingWithMultipleTrailingLfs() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        String authFp = sha256Hex(authBytes);
        String provFp = sha256Hex(tckJar);

        Path authorityPath = writeAuthority(authBytes);
        Path bindingPath = tempDir.resolve("binding.json");
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        // Build valid binding, then corrupt: append 3 extra LFs
        byte[] validWithLf = buildCanonicalBindingBytes(authFp, provFp, tckJar.length);
        byte[] bad = Arrays.copyOf(validWithLf, validWithLf.length + 3);
        bad[validWithLf.length] = (byte) '\n';
        bad[validWithLf.length + 1] = (byte) '\n';
        bad[validWithLf.length + 2] = (byte) '\n';
        Files.write(bindingPath, bad);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 3. CRLF line endings
    @Test
    void rejectsBindingWithCrlf() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        String authFp = sha256Hex(authBytes);
        String provFp = sha256Hex(tckJar);

        Path authorityPath = writeAuthority(authBytes);
        Path bindingPath = tempDir.resolve("binding.json");
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        byte[] valid = buildCanonicalBindingBytes(authFp, provFp, tckJar.length);
        // Replace LF with CRLF throughout
        String s = new String(valid, StandardCharsets.US_ASCII).replace("\n", "\r\n");
        Files.write(bindingPath, s.getBytes(StandardCharsets.US_ASCII));

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 4. Leading whitespace before JSON
    @Test
    void rejectsBindingWithLeadingWhitespace() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        String authFp = sha256Hex(authBytes);
        String provFp = sha256Hex(tckJar);

        Path authorityPath = writeAuthority(authBytes);
        Path bindingPath = tempDir.resolve("binding.json");
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        byte[] valid = buildCanonicalBindingBytes(authFp, provFp, tckJar.length);
        byte[] bad = new byte[3 + valid.length];
        bad[0] = (byte) ' '; bad[1] = (byte) ' '; bad[2] = (byte) ' ';
        System.arraycopy(valid, 0, bad, 3, valid.length);
        // Ensure trailing LF
        bad[bad.length - 1] = (byte) '\n';
        Files.write(bindingPath, bad);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 5. Trailing whitespace after JSON (before LF)
    @Test
    void rejectsBindingWithTrailingWhitespace() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        String authFp = sha256Hex(authBytes);
        String provFp = sha256Hex(tckJar);

        Path authorityPath = writeAuthority(authBytes);
        Path bindingPath = tempDir.resolve("binding.json");
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        byte[] valid = buildCanonicalBindingBytes(authFp, provFp, tckJar.length);
        byte[] bad = Arrays.copyOf(valid, valid.length + 4);
        bad[valid.length] = (byte) ' '; bad[valid.length+1] = (byte) ' ';
        bad[valid.length+2] = (byte) ' '; bad[valid.length+3] = (byte) '\n';
        Files.write(bindingPath, bad);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 6. Non-canonical key order -> R03-BINDING-INVALID
    @Test
    void rejectsNonCanonicalKeyOrder() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        String authFp = sha256Hex(authBytes);
        String provFp = sha256Hex(tckJar);

        Path authorityPath = writeAuthority(authBytes);
        Path bindingPath = tempDir.resolve("binding.json");
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        // Build providerArtifact node
        ObjectNode provNode = JsonNodeFactory.instance.objectNode();
        provNode.put("coordinate", TCK_COORD);
        provNode.put("path", TCK_PATH);
        provNode.put("byteLength", (long) tckJar.length);
        provNode.put("rawFingerprint", provFp);

        // Compute correct bindingFingerprint from canonical (non-mutated) form
        ObjectNode withoutBf = JsonNodeFactory.instance.objectNode();
        withoutBf.put("authorityRawFingerprint", authFp);
        withoutBf.put("messageVersion", MSG_VERSION);
        withoutBf.put("sourceSliceId", SRC_SLICE);
        withoutBf.put("targetSliceId", TGT_SLICE);
        withoutBf.set("providerArtifact", provNode);
        String bindingFp = computeBindingFpFromCanonical(canonicalJson(withoutBf));

        // Build binding with keys in deliberately non-canonical insertion order.
        // Jackson ObjectNode preserves field insertion order.
        // Canonical order: authorityRawFingerprint, bindingFingerprint, messageVersion,
        //                  providerArtifact, sourceSliceId, targetSliceId
        // Non-canonical: messageVersion, targetSliceId, sourceSliceId,
        //                providerArtifact, authorityRawFingerprint, bindingFingerprint
        ObjectNode bindingNode = JsonNodeFactory.instance.objectNode();
        bindingNode.put("bindingFingerprint", bindingFp);   // correct, pre-computed
        bindingNode.put("messageVersion", MSG_VERSION);
        bindingNode.put("targetSliceId", TGT_SLICE);
        bindingNode.put("sourceSliceId", SRC_SLICE);
        bindingNode.set("providerArtifact", provNode);
        bindingNode.put("authorityRawFingerprint", authFp); // last instead of first

        // Serialize compact + trailing LF
        byte[] canonical = new ObjectMapper().writeValueAsBytes(bindingNode);
        byte[] withLf = Arrays.copyOf(canonical, canonical.length + 1);
        withLf[canonical.length] = (byte) '\n';
        Files.write(bindingPath, withLf);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex, "Non-canonical key order should be rejected");
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 7. Unknown field in binding
    @Test
    void rejectsBindingWithUnknownField() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {
            node.put("unknownField", "forbidden");
        });
        Path bindingPath = tempDir.resolve("binding.json");

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 8. Missing required field: sourceSliceId
    @Test
    void rejectsBindingMissingSourceSliceId() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        String authFp = sha256Hex(authBytes);
        String provFp = sha256Hex(tckJar);

        Path authorityPath = tempDir.resolve("authority.json");
        Files.write(authorityPath, authBytes);
        Path provPath = deriveProviderPath(tempDir, TCK_PATH);
        Files.createDirectories(provPath.getParent());
        Files.write(provPath, tckJar);

        ObjectNode provNode = JsonNodeFactory.instance.objectNode();
        provNode.put("coordinate", TCK_COORD);
        provNode.put("path", TCK_PATH);
        provNode.put("byteLength", tckJar.length);
        provNode.put("rawFingerprint", provFp);

        // Build full node with all correct fields including sourceSliceId
        ObjectNode full = JsonNodeFactory.instance.objectNode();
        full.put("authorityRawFingerprint", authFp);
        full.put("messageVersion", MSG_VERSION);
        full.put("sourceSliceId", SRC_SLICE);
        full.put("targetSliceId", TGT_SLICE);
        full.set("providerArtifact", provNode);
        String correctFp = computeBindingFpFromCanonical(canonicalJson(full));

        // Now remove bindingFingerprint (never put it back - sourceSliceId is missing in final write)
        full.put("bindingFingerprint", correctFp);
        // Remove sourceSliceId to corrupt
        full.remove("sourceSliceId");
        // Re-add bindingFingerprint (but sourceSliceId is gone)
        full.put("bindingFingerprint", correctFp);

        byte[] canonical = canonicalJson(full);
        byte[] withLf = Arrays.copyOf(canonical, canonical.length + 1);
        withLf[canonical.length] = (byte) 10;
        Files.write(tempDir.resolve("binding.json"), withLf);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 9. Wrong type: byteLength as string
    @Test
    void rejectsProviderByteLengthAsString() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {
            ((ObjectNode) node.get("providerArtifact"))
                    .put("byteLength", "123"); // intentionally wrong type
        });
        Path bindingPath = tempDir.resolve("binding.json");

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 10. Short fingerprint (< 64 hex chars) -> R03-BINDING-INVALID
    @Test
    void rejectsShortFingerprint() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        String authFp = sha256Hex(authBytes);
        String provFp = sha256Hex(tckJar);

        // Write authority file first (needed by verifier)
        Path authorityPath = writeAuthority(authBytes);
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        // Build providerArtifact node
        ObjectNode provNode = JsonNodeFactory.instance.objectNode();
        provNode.put("coordinate", TCK_COORD);
        provNode.put("path", TCK_PATH);
        provNode.put("byteLength", (long) tckJar.length);
        provNode.put("rawFingerprint", provFp);

        // Compute correct bindingFingerprint from canonical (non-short) binding
        ObjectNode withoutBf = JsonNodeFactory.instance.objectNode();
        withoutBf.put("authorityRawFingerprint", authFp);
        withoutBf.put("messageVersion", MSG_VERSION);
        withoutBf.put("sourceSliceId", SRC_SLICE);
        withoutBf.put("targetSliceId", TGT_SLICE);
        withoutBf.set("providerArtifact", provNode);
        // (bindingFingerprint is intentionally NOT put here for fp computation)

        // Build binding with a SHORT (invalid) bindingFingerprint value.
        // Do NOT use writeBindingWithMutation - it would recompute and overwrite
        // the short fp with a correct one, defeating the test.
        ObjectNode bindingNode = JsonNodeFactory.instance.objectNode();
        bindingNode.put("bindingFingerprint", "sha256:deadbeef"); // intentionally short
        bindingNode.put("authorityRawFingerprint", authFp);
        bindingNode.put("messageVersion", MSG_VERSION);
        bindingNode.put("sourceSliceId", SRC_SLICE);
        bindingNode.put("targetSliceId", TGT_SLICE);
        bindingNode.set("providerArtifact", provNode);

        // Serialize canonical + trailing LF (valid JSON syntax)
        byte[] canonical = canonicalJson(bindingNode);
        byte[] withLf = Arrays.copyOf(canonical, canonical.length + 1);
        withLf[canonical.length] = (byte) '\n';
        Files.write(tempDir.resolve("binding.json"), withLf);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex, "Short bindingFingerprint should be rejected");
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 11. Duplicate top-level key
    @Test
    void rejectsDuplicateBindingKey() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeAuthority(authBytes);
        Path bindingPath = tempDir.resolve("binding.json");
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        byte[] valid = buildCanonicalBindingBytes(sha256Hex(authBytes), sha256Hex(tckJar),
                tckJar.length);
        String validJson = new String(valid, StandardCharsets.US_ASCII);
        // Inject duplicate authorityRawFingerprint key
        String dup = validJson.replaceFirst(
                "\"authorityRawFingerprint\":",
                "\"authorityRawFingerprint\":,\"authorityRawFingerprint\":");
        Files.write(bindingPath, (dup + "\n").getBytes(StandardCharsets.US_ASCII));

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 12. Trailing token after JSON root
    @Test
    void rejectsTrailingTokenAfterJson() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeAuthority(authBytes);
        Path bindingPath = tempDir.resolve("binding.json");
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        byte[] valid = buildCanonicalBindingBytes(sha256Hex(authBytes), sha256Hex(tckJar),
                tckJar.length);
        byte[] bad = Arrays.copyOf(valid, valid.length + 6);
        bad[valid.length]     = (byte) '\n';
        bad[valid.length + 1] = (byte) 'n';
        bad[valid.length + 2] = (byte) 'u';
        bad[valid.length + 3] = (byte) 'l';
        bad[valid.length + 4] = (byte) 'l';
        bad[valid.length + 5] = (byte) '\n';
        Files.write(bindingPath, bad);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(bindingPath, authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 13. Duplicate A1.2 delivery slice in Authority
    @Test
    void rejectsAuthorityWithDuplicateA12Slice() throws Exception {
        byte[] tckJar = zipStub();
        byte[] baseAuth = buildMinimalAuthority(tckJar);
        String authJson = new String(baseAuth, StandardCharsets.US_ASCII);

        // Extract and duplicate the first slice (A1.2)
        int slicesIdx = authJson.indexOf("\"deliverySlices\"");
        int firstSliceStart = authJson.indexOf("{", slicesIdx);
        int slicesEnd = authJson.lastIndexOf("]");
        String oneSlice = authJson.substring(firstSliceStart, slicesEnd);
        String dupAuth = authJson.substring(0, slicesEnd)
                + "," + oneSlice
                + authJson.substring(slicesEnd);
        byte[] badAuth = dupAuth.getBytes(StandardCharsets.US_ASCII);

        Path authorityPath = tempDir.resolve("authority.json");
        Files.write(authorityPath, badAuth);
        Path provPath = deriveProviderPath(tempDir, TCK_PATH);
        Files.createDirectories(provPath.getParent());
        Files.write(provPath, tckJar);

        writeBindingWithMutation(badAuth, tckJar, node -> {});

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 15. Provider file missing -> READ-UNREADABLE (provider artifact unrelated to binding validity)
    @Test
    void rejectsMissingProviderFile() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {});
        // Intentionally do NOT write the provider file
        // (deriveProviderPath is called inside writeBindingWithMutation but we
        // need to verify: since writeBindingWithMutation wrote it, we delete it)
        Files.deleteIfExists(deriveProviderPath(tempDir, TCK_PATH));

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-READ-UNREADABLE", ex.reasonCode());
    }

    // 16. Extra content in provider JAR (valid binding, unrelated extra bytes)
    @Test
    void acceptsExtraBytesInProviderJar() throws Exception {
        byte[] tckJar = "PKextra content".getBytes(StandardCharsets.US_ASCII);
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {});

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNull(ex, "Extra bytes in provider JAR should not affect valid binding");
    }

    // 17. Handoff path != output path in Authority
    @Test
    void rejectsHandoffOutputPathMismatch() throws Exception {
        byte[] tckJar = zipStub();

        // Build authority with mismatched handoff/output paths
        ObjectNode handoffCoord = JsonNodeFactory.instance.objectNode();
        handoffCoord.put("groupId", "com.leanowtech.bloge");
        handoffCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        handoffCoord.put("version", "1.0.0");

        ObjectNode handoff = JsonNodeFactory.instance.objectNode();
        handoff.put("role", "TCK_PROVIDER");
        handoff.set("coordinate", handoffCoord);
        handoff.put("path", TCK_PATH); // handoff says correct path

        ObjectNode outputCoord = JsonNodeFactory.instance.objectNode();
        outputCoord.put("groupId", "com.leanowtech.bloge");
        outputCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        outputCoord.put("version", "1.0.0");

        ObjectNode wrongOutput = JsonNodeFactory.instance.objectNode();
        wrongOutput.put("role", "TCK_PROVIDER");
        wrongOutput.set("coordinate", outputCoord);
        wrongOutput.put("path", "WRONG/PATH/provider.jar"); // output says different path!

        ObjectNode slice = JsonNodeFactory.instance.objectNode();
        slice.put("sliceId", "A1.2");
        slice.put("owner", "PROVIDER");
        slice.putArray("modules").add("resource-gateway-gate-a-tck-provider");
        slice.putArray("handoffArtifacts").add(handoff);
        slice.putArray("outputArtifacts").add(wrongOutput);

        ObjectNode roleContract = JsonNodeFactory.instance.objectNode();
        roleContract.put("role", "INDEPENDENT_VERIFIER");
        roleContract.put("providerEntryPath", VERIFIER_PROVIDER_ENTRY_PATH);

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", "capability-studio.gate-a-protocol-authority.v1");
        root.put("authorityId", "GATE-A-PROTOCOL-AUTHORITY");
        root.put("revision", 1);
        root.putArray("deliverySlices").add(slice);
        root.putArray("roleContracts").add(roleContract);

        ObjectMapper mapper = new ObjectMapper(new JsonFactoryBuilder().build());
        byte[] badAuth = mapper.writeValueAsBytes(root);

        Path authorityPath = writeAuthority(badAuth);
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);
        // Write binding with the authority-derived (correct) path but authority has mismatch
        writeBindingWithMutation(badAuth, tckJar, node -> {});

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 18. Coordinate mismatch between binding and authority derivation
    @Test
    void rejectsCoordinateMismatch() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        // Authority says 1.0.0; binding says 2.0.0
        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {
            ((ObjectNode) node.get("providerArtifact"))
                    .put("coordinate", "com.leanowtech.bloge:resource-gateway-gate-a-tck-provider:2.0.0");
        });

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-COORDINATE-MISMATCH", ex.reasonCode());
    }

    // 19. Duplicate INDEPENDENT_VERIFIER role contract
    @Test
    void rejectsDuplicateIndependentVerifierRole() throws Exception {
        byte[] tckJar = zipStub();
        byte[] baseAuth = buildMinimalAuthority(tckJar);
        String authJson = new String(baseAuth, StandardCharsets.US_ASCII);

        ObjectNode dupRole = JsonNodeFactory.instance.objectNode();
        dupRole.put("role", "INDEPENDENT_VERIFIER");
        dupRole.put("providerEntryPath", "META-INF/dup.jar");

        ObjectMapper mapper = new ObjectMapper(new JsonFactoryBuilder().build());
        String dupRoleJson = "," + mapper.writeValueAsString(dupRole);

        int roleEnd = authJson.lastIndexOf("]");
        String dupAuth = authJson.substring(0, roleEnd) + dupRoleJson + authJson.substring(roleEnd);
        byte[] badAuth = dupAuth.getBytes(StandardCharsets.US_ASCII);

        Path authorityPath = writeAuthority(badAuth);
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        writeBindingWithMutation(badAuth, tckJar, node -> {});

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // 20. Provider fingerprint mismatch (tampered file vs stored FP)
    @Test
    void rejectsProviderFingerprintMismatch() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);
        byte[] tamperedJar = new byte[]{'Q','L',5,6}; // same 4-byte length as tckJar

        // Write binding with original (correct) provider fingerprint
        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {});
        // Overwrite provider with tampered bytes (different fingerprint)
        Files.write(deriveProviderPath(tempDir, TCK_PATH), tamperedJar);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-PROVIDER-FP-MISMATCH", ex.reasonCode());
    }

    // 21. Wrong messageVersion
    @Test
    void rejectsWrongMessageVersion() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {
            node.put("messageVersion", "99.0.0");
        });

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-STRUCTURE-MISMATCH", ex.reasonCode());
    }

    // 22. Wrong sourceSliceId
    @Test
    void rejectsWrongSourceSliceId() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {
            node.put("sourceSliceId", "A1.99");
        });

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-STRUCTURE-MISMATCH", ex.reasonCode());
    }

    // 23. Missing INDEPENDENT_VERIFIER role contract
    @Test
    void rejectsMissingIndependentVerifierRole() throws Exception {
        byte[] tckJar = zipStub();

        ObjectNode handoffCoord = JsonNodeFactory.instance.objectNode();
        handoffCoord.put("groupId", "com.leanowtech.bloge");
        handoffCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        handoffCoord.put("version", "1.0.0");

        ObjectNode handoff = JsonNodeFactory.instance.objectNode();
        handoff.put("role", "TCK_PROVIDER");
        handoff.set("coordinate", handoffCoord);
        handoff.put("path", TCK_PATH);

        ObjectNode outputCoord = JsonNodeFactory.instance.objectNode();
        outputCoord.put("groupId", "com.leanowtech.bloge");
        outputCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        outputCoord.put("version", "1.0.0");

        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("role", "TCK_PROVIDER");
        output.set("coordinate", outputCoord);
        output.put("path", TCK_PATH);

        ObjectNode slice = JsonNodeFactory.instance.objectNode();
        slice.put("sliceId", "A1.2");
        slice.put("owner", "PROVIDER");
        slice.putArray("modules").add("resource-gateway-gate-a-tck-provider");
        slice.putArray("handoffArtifacts").add(handoff);
        slice.putArray("outputArtifacts").add(output);

        ObjectNode wrongRole = JsonNodeFactory.instance.objectNode();
        wrongRole.put("role", "SOME_OTHER_ROLE");
        wrongRole.put("providerEntryPath", "META-INF/other.jar");

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", "capability-studio.gate-a-protocol-authority.v1");
        root.put("authorityId", "GATE-A-PROTOCOL-AUTHORITY");
        root.put("revision", 1);
        root.putArray("deliverySlices").add(slice);
        root.putArray("roleContracts").add(wrongRole);

        ObjectMapper mapper = new ObjectMapper(new JsonFactoryBuilder().build());
        byte[] badAuth = mapper.writeValueAsBytes(root);

        Path authorityPath = writeAuthority(badAuth);
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        writeBindingWithMutation(badAuth, tckJar, node -> {});

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex);
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // Helper: build canonical binding bytes with correct FP for base values
    // -------------------------------------------------------------------------

    private byte[] buildCanonicalBindingBytes(String authRawFp, String provRawFp,
                                              long provSize) throws Exception {
        ObjectNode provNode = JsonNodeFactory.instance.objectNode();
        provNode.put("coordinate", TCK_COORD);
        provNode.put("path", TCK_PATH);
        provNode.put("byteLength", provSize);
        provNode.put("rawFingerprint", provRawFp);

        ObjectNode withoutBf = JsonNodeFactory.instance.objectNode();
        withoutBf.put("authorityRawFingerprint", authRawFp);
        withoutBf.put("messageVersion", MSG_VERSION);
        withoutBf.put("sourceSliceId", SRC_SLICE);
        withoutBf.put("targetSliceId", TGT_SLICE);
        withoutBf.set("providerArtifact", provNode);

        String bindingFp = computeBindingFpFromCanonical(canonicalJson(withoutBf));

        ObjectNode full = JsonNodeFactory.instance.objectNode();
        full.put("bindingFingerprint", bindingFp);
        full.put("authorityRawFingerprint", authRawFp);
        full.put("messageVersion", MSG_VERSION);
        full.put("sourceSliceId", SRC_SLICE);
        full.put("targetSliceId", TGT_SLICE);
        full.set("providerArtifact", provNode);

        byte[] canonical = canonicalJson(full);
        byte[] withLf = Arrays.copyOf(canonical, canonical.length + 1);
        withLf[canonical.length] = (byte) '\n';
        return withLf;
    }

    // -------------------------------------------------------------------------
    // Writer helpers
    // -------------------------------------------------------------------------

    private Path writeAuthority(byte[] content) throws IOException {
        Path p = tempDir.resolve("authority.json");
        Files.write(p, content,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return p;
    }

    // 24. Unrelated OTHER-role artifact in both handoff and output arrays passes
    @Test
    void acceptsUnrelatedArtifactInHandoffAndOutput() throws Exception {
        byte[] tckJar = zipStub();

        ObjectNode handoffCoord = JsonNodeFactory.instance.objectNode();
        handoffCoord.put("groupId", "com.leanowtech.bloge");
        handoffCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        handoffCoord.put("version", "1.0.0");

        ObjectNode tckHandoff = JsonNodeFactory.instance.objectNode();
        tckHandoff.put("role", "TCK_PROVIDER");
        tckHandoff.set("coordinate", handoffCoord);
        tckHandoff.put("path", TCK_PATH);

        ObjectNode otherHandoff = JsonNodeFactory.instance.objectNode();
        otherHandoff.put("role", "OTHER");
        otherHandoff.put("coordinate", "com.example:unrelated:1.0.0");
        otherHandoff.put("path", "other/artifact.jar");

        ObjectNode outputCoord = JsonNodeFactory.instance.objectNode();
        outputCoord.put("groupId", "com.leanowtech.bloge");
        outputCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        outputCoord.put("version", "1.0.0");

        ObjectNode tckOutput = JsonNodeFactory.instance.objectNode();
        tckOutput.put("role", "TCK_PROVIDER");
        tckOutput.set("coordinate", outputCoord);
        tckOutput.put("path", TCK_PATH);

        ObjectNode otherOutput = JsonNodeFactory.instance.objectNode();
        otherOutput.put("role", "OTHER");
        otherOutput.put("coordinate", "com.example:unrelated:1.0.0");
        otherOutput.put("path", "other/artifact.jar");

        ObjectNode slice = JsonNodeFactory.instance.objectNode();
        slice.put("sliceId", "A1.2");
        slice.put("owner", "PROVIDER");
        slice.putArray("modules").add("resource-gateway-gate-a-tck-provider");
        slice.putArray("handoffArtifacts").add(tckHandoff).add(otherHandoff);
        slice.putArray("outputArtifacts").add(tckOutput).add(otherOutput);

        ObjectNode roleContract = JsonNodeFactory.instance.objectNode();
        roleContract.put("role", "INDEPENDENT_VERIFIER");
        roleContract.put("providerEntryPath", VERIFIER_PROVIDER_ENTRY_PATH);

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", "capability-studio.gate-a-protocol-authority.v1");
        root.put("authorityId", "GATE-A-PROTOCOL-AUTHORITY");
        root.put("revision", 1);
        root.putArray("deliverySlices").add(slice);
        root.putArray("roleContracts").add(roleContract);

        ObjectMapper authMapper = new ObjectMapper(new JsonFactoryBuilder().build());
        byte[] authBytes = authMapper.writeValueAsBytes(root);

        Path authorityPath = writeAuthority(authBytes);
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);
        Files.createDirectories(tempDir.resolve("other"));
        Files.write(tempDir.resolve("other/artifact.jar"), new byte[4]);

        writeBindingWithMutation(authBytes, tckJar, node -> {});

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNull(ex,
                "OTHER-role artifacts in handoff/output should not affect verification: "
                + (ex != null ? ex.reasonCode() : ""));
    }

    // 25. Duplicate TCK_PROVIDER role in outputArtifacts array -> R03-BINDING-INVALID
    @Test
    void rejectsDuplicateOutputArtifactRole() throws Exception {
        byte[] tckJar = zipStub();

        ObjectNode handoffCoord = JsonNodeFactory.instance.objectNode();
        handoffCoord.put("groupId", "com.leanowtech.bloge");
        handoffCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        handoffCoord.put("version", "1.0.0");

        ObjectNode tckHandoff = JsonNodeFactory.instance.objectNode();
        tckHandoff.put("role", "TCK_PROVIDER");
        tckHandoff.set("coordinate", handoffCoord);
        tckHandoff.put("path", TCK_PATH);

        ObjectNode outputCoord = JsonNodeFactory.instance.objectNode();
        outputCoord.put("groupId", "com.leanowtech.bloge");
        outputCoord.put("artifactId", "resource-gateway-gate-a-tck-provider");
        outputCoord.put("version", "1.0.0");

        ObjectNode tckOutput = JsonNodeFactory.instance.objectNode();
        tckOutput.put("role", "TCK_PROVIDER");
        tckOutput.set("coordinate", outputCoord);
        tckOutput.put("path", TCK_PATH);

        // Second TCK_PROVIDER entry (duplicate) in outputArtifacts
        ObjectNode tckOutputDup = tckOutput.deepCopy();

        ObjectNode slice = JsonNodeFactory.instance.objectNode();
        slice.put("sliceId", "A1.2");
        slice.put("owner", "PROVIDER");
        slice.putArray("modules").add("resource-gateway-gate-a-tck-provider");
        slice.putArray("handoffArtifacts").add(tckHandoff);
        slice.putArray("outputArtifacts").add(tckOutput).add(tckOutputDup);

        ObjectNode roleContract = JsonNodeFactory.instance.objectNode();
        roleContract.put("role", "INDEPENDENT_VERIFIER");
        roleContract.put("providerEntryPath", VERIFIER_PROVIDER_ENTRY_PATH);

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", "capability-studio.gate-a-protocol-authority.v1");
        root.put("authorityId", "GATE-A-PROTOCOL-AUTHORITY");
        root.put("revision", 1);
        root.putArray("deliverySlices").add(slice);
        root.putArray("roleContracts").add(roleContract);

        ObjectMapper mapper = new ObjectMapper(new JsonFactoryBuilder().build());
        byte[] badAuth = mapper.writeValueAsBytes(root);

        Path authorityPath = writeAuthority(badAuth);
        Files.createDirectories(deriveProviderPath(tempDir, TCK_PATH).getParent());
                Files.write(deriveProviderPath(tempDir, TCK_PATH), tckJar);

        writeBindingWithMutation(badAuth, tckJar, node -> {});

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));
        Assertions.assertNotNull(ex,
                "Duplicate TCK_PROVIDER in outputArtifacts should be rejected");
        Assertions.assertEquals("R03-BINDING-INVALID", ex.reasonCode());
    }

    // -------------------------------------------------------------------------
    // Filesystem-mutation tests (26-30)
    // Each uses writeBindingWithMutation to build a valid binding first,
    // then mutates the filesystem and verifies the verifier rejects it.
    // -------------------------------------------------------------------------

    // 26. Provider leaf symlinked to external file of same byte length -> R03-READ-UNREADABLE
    //     NOFOLLOW reads symlink attrs; isRegularFile() returns false for a symlink.
    @Test
    void rejectsProviderLeafSymlinkedToExternalFile() throws Exception {
        byte[] tckJar = zipStub();                         // 4 bytes; byteLength=4 in binding
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {});

        // Build external file: same byte length as zipStub
        Path externalDir = tempDir.resolve("external_link_target");
        Files.createDirectories(externalDir);
        Path externalFile = externalDir.resolve("external_provider.jar");
        Files.write(externalFile, tckJar);                  // same 4 bytes

        // Replace provider leaf with symlink to external file
        Path provPath = deriveProviderPath(tempDir, TCK_PATH);
        Files.deleteIfExists(provPath);
        Files.createSymbolicLink(provPath, externalFile);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));

        Assertions.assertNotNull(ex, "Symlinked provider leaf should be rejected");
        Assertions.assertEquals("R03-READ-UNREADABLE", ex.reasonCode(),
                "NOFOLLOW: symlink is not a regular file -> UNREADABLE");
    }

    // 27. First parent dir of TCK_PATH replaced with symlink to external directory
    //     containing the remaining path + same-byte-length provider -> UNREADABLE
    //     SDS traversal sees symlink-to-dir at segment level; NOFOLLOW means
    //     isDirectory() returns false for the symlink itself -> UNREADABLE.
    @Test
    void rejectsParentDirSymlinkedToExternalDirectory() throws Exception {
        byte[] tckJar = zipStub();                         // 4 bytes
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {});

        // Build external directory mirroring the remaining TCK_PATH structure
        // TCK_PATH = "resource-gateway-gate-a-tck-provider/target/<leaf>"
        Path externalRoot = tempDir.resolve("external_parent_link");
        Files.createDirectories(externalRoot);
        Path externalProvParent = externalRoot.resolve(
                "resource-gateway-gate-a-tck-provider").resolve("target");
        Files.createDirectories(externalProvParent);
        Path externalProv = externalProvParent.resolve(
                "resource-gateway-gate-a-tck-provider-1.0.0.jar");
        Files.write(externalProv, tckJar);                 // same 4 bytes, same name

        // Replace the first segment of TCK_PATH with a symlink to externalRoot
        String firstSegment = "resource-gateway-gate-a-tck-provider";
        Path firstSegmentPath = tempDir.resolve(firstSegment);
        // Wipe the real dir tree that writeBindingWithMutation created
        Files.walk(firstSegmentPath).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } });
        Files.createSymbolicLink(firstSegmentPath, externalRoot);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));

        Assertions.assertNotNull(ex,
                "Parent dir symlinked to external dir should be rejected");
        Assertions.assertEquals("R03-READ-UNREADABLE", ex.reasonCode(),
                "NOFOLLOW on segment: symlink is not a directory -> UNREADABLE");
    }

    // 28. Provider leaf replaced with a directory -> UNREADABLE
    //     isRegularFile() returns false for a directory.
    @Test
    void rejectsProviderLeafAsDirectory() throws Exception {
        byte[] tckJar = zipStub();                         // 4 bytes
        byte[] authBytes = buildMinimalAuthority(tckJar);

        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {});

        Path provPath = deriveProviderPath(tempDir, TCK_PATH);
        Files.deleteIfExists(provPath);
        Files.createDirectories(provPath);                  // leaf is now a directory

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));

        Assertions.assertNotNull(ex, "Provider leaf as directory should be rejected");
        Assertions.assertEquals("R03-READ-UNREADABLE", ex.reasonCode(),
                "isRegularFile() false for directory -> UNREADABLE");
    }

    // 29. Provider file 17 MiB (byteLength = 17 MiB in binding, passes structural
    //     validation: 17 MiB <= MAX_BYTE_LENGTH 16 MiB? NO -> check:
    //     Actually byteLength=17M > MAX_BYTE_LENGTH=16M, so byteLength validation
    //     FAILS first with R03-BINDING-STRUCTURE-MISMATCH.
    //     Approach: set byteLength=16M in binding, write 16M file, then OVERWRITE
    //     with 17M content. During verify readAnchored reads 17M -> oversize
    //     BEFORE byteLength check fires. But byteLength=16M stored vs 17M actual
    //     also mismatches -> SIZE-MISMATCH. To guarantee R03-READ-OVERSIZE as
    //     primary rejection, set byteLength=17M in binding (structural validation
    //     should allow 17M <= 16M which fails). Alternative: let byteLength
    //     validation PASS with 16M then trigger oversize on read. Correct path:
    //     byteLength=16M, file=17M -> byteLength OK, read oversize -> R03-READ-OVERSIZE.
    @Test
    void rejectsOversizeProviderFileWithSizeReasonArg() throws Exception {
        int sixteenMB = 16 * 1024 * 1024;
        int seventeenMB = 17 * 1024 * 1024;

        // Build 16 MiB stub for binding computation
        byte[] sixteenMbStub = new byte[sixteenMB];
        byte[] authBytes = buildMinimalAuthority(sixteenMbStub);

        Path authorityPath = writeBindingWithMutation(authBytes, sixteenMbStub, node -> {});

        // Overwrite provider with 17 MiB file
        Path provPath = deriveProviderPath(tempDir, TCK_PATH);
        byte[] seventeenMbContent = new byte[seventeenMB];
        // Fill with non-zero bytes so file is not trivially compressible
        Arrays.fill(seventeenMbContent, (byte) 0xAB);
        Files.write(provPath, seventeenMbContent);

        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, tempDir));

        Assertions.assertNotNull(ex, "Oversize provider file should be rejected");
        // Primary rejection: readAnchored hits MAX_PROVIDER ceiling
        Assertions.assertEquals("R03-READ-OVERSIZE", ex.reasonCode(),
                "Provider > 16 MiB MAX_PROVIDER -> R03-READ-OVERSIZE");
        // Secondary: reasonArgs should contain size field
        Map<?,?> args = ex.reasonArgs();
        Assertions.assertNotNull(args, "reasonArgs should be present");
        Assertions.assertTrue(args.containsKey("size"),
                "reasonArgs should contain 'size' for oversize error");
        Object sizeVal = args.get("size");
        Assertions.assertTrue(sizeVal instanceof Long,
                "size reasonArg should be a Long");
        Assertions.assertEquals((long) seventeenMB, ((Number) sizeVal).longValue(),
                "size reasonArg should report actual file size (17 MiB)");
    }

    // 30. repoRoot itself is a symlink to the real repoRoot directory -> UNREADABLE
    //     readAnchored normalizes repoRoot, opens SDS on it (which is a symlink).
    //     SDS open on a symlink succeeds (the link itself exists), but when SDS
    //     tries to read segment attributes with NOFOLLOW, the symlink's attrs
    //     don't match what SDS expects from a real directory, causing UNREADABLE.
    @Test
    void rejectsRepoRootSymlinkedToRealDirectory() throws Exception {
        byte[] tckJar = zipStub();
        byte[] authBytes = buildMinimalAuthority(tckJar);

        // writeBindingWithMutation builds valid binding + provider inside tempDir
        Path authorityPath = writeBindingWithMutation(authBytes, tckJar, node -> {});

        // Make an alias symlink: linkBack -> tempDir (self-referential link)
        Path linkBack = tempDir.resolve("link_to_real");
        Files.createSymbolicLink(linkBack, tempDir);

        // Verify using linkBack as repoRoot: SDS open on symlink -> UNREADABLE
        DevelopmentPredecessorBindingVerifier v = new DevelopmentPredecessorBindingVerifier();
        DevelopmentPredecessorBindingException ex = catchEx(() ->
                v.verify(tempDir.resolve("binding.json"), authorityPath, linkBack));

        Assertions.assertNotNull(ex,
                "repoRoot as symlink should be rejected with UNREADABLE");
        Assertions.assertEquals("R03-READ-UNREADABLE", ex.reasonCode(),
                "repoRoot symlink not followed -> UNREADABLE");
    }
}
