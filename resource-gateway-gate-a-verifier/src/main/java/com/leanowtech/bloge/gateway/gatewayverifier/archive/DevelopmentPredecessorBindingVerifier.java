package com.leanowtech.bloge.gateway.gatewayverifier.archive;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.Arrays;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Fail-closed consumer for A1.3-R03 DEVELOPMENT predecessor fingerprint binding.
 *
 * <p>Verification steps in first-failure priority order:
 * <ol>
 * <li>R03-BINDING-MISSING
 * <li>R03-BINDING-INVALID (canonical JSON, duplicate key, unknown key, CRLF, nonfinite, non-ASCII, LF-only byte envelope)
 * <li>R03-BINDING-FP-MISMATCH (strict sha256: lowercase 64-hex, LF-only byte envelope)
 * <li>R03-BINDING-STRUCTURE-MISMATCH (messageVersion/sourceSliceId/targetSliceId, strict field types)
 * <li>R03-AUTHORITY-FP-MISMATCH
 * <li>R03-COORDINATE-MISMATCH
 * <li>R03-PATH-MISMATCH
 * <li>R03-PROVIDER-FP-MISMATCH
 * <li>R03-SIZE-MISMATCH
 * <li>R03-READ-UNREADABLE
 * <li>R03-READ-STALE
 * <li>R03-READ-OVERSIZE
 * </ol>
 *
 * <p>File I/O is performed via {@link BindingFileReader} so tests can inject failures.
 */
public final class DevelopmentPredecessorBindingVerifier {

    private static final String BINDING_DOMAIN  = "RG-CS-GATE-A-A1-3-DEVELOPMENT-PREDECESSOR-BINDING-v1";
    private static final String SOURCE_SLICE    = "A1.2";
    private static final String TARGET_SLICE    = "A1.3";
    private static final String MSG_VERSION    = "1.0.0";
    private static final long   MIN_BYTE_LENGTH = 1L;
    private static final long   MAX_BYTE_LENGTH = 16L * 1024 * 1024; // 16 MiB
    private static final long   MAX_BINDING     = 64L * 1024;
    private static final long   MAX_AUTH        = 4L * 1024 * 1024;
    private static final long   MAX_PROVIDER    = MAX_BYTE_LENGTH;

    // sha256: prefix (7) + lowercase hex (64)
    private static final int    SHA256_PREFIXED_HEX_LEN = 7 + 64;

    private static final Set<String> BINDING_KEYS   = Set.of(
            "bindingFingerprint","authorityRawFingerprint","messageVersion",
            "sourceSliceId","targetSliceId","providerArtifact");
    private static final Set<String> PROVIDER_KEYS = Set.of(
            "coordinate","path","byteLength","rawFingerprint");

    /**
     * Functional interface for reading a file into bytes and metadata.
     * Allows test injection of failures (unreadable, stale, oversize).
     * @param path     absolute path to read
     * @param maxBytes maximum allowed size
     * @return immutable result with bytes and pre/post metadata.
     *         The returned bytes MUST NOT be interpreted as text by the caller.
     * @throws IOException on any read failure
     */
    @FunctionalInterface
    public interface BindingFileReader {
        PathBytes read(Path path, long maxBytes) throws IOException;
    }

    /** Immutable result of a successful file read. */
    public record PathBytes(byte[] bytes, long dev, long ino, long size, long mtimeNs) {}

    /**
     * Typed read failure exception. Only three reason codes are permitted:
     * R03-READ-UNREADABLE, R03-READ-STALE, R03-READ-OVERSIZE.
     */
    public static final class FileReadException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String reasonCode;
        private final long size;

        private FileReadException(String reasonCode, String detail, long size) {
            super(detail);
            this.reasonCode = reasonCode;
            this.size = size;
        }

        /** Returns the R03 reason code: UNREADABLE | STALE | OVERSIZE. */
        public String reasonCode() { return reasonCode; }
        /** Actual byte count read before the failure; -1 if unknown. */
        public long size() { return size; }

        public static FileReadException unreadable(String detail) {
            return new FileReadException("R03-READ-UNREADABLE", detail, -1L);
        }
        public static FileReadException stale(Path path) {
            return new FileReadException("R03-READ-STALE",
                    "pre/post mismatch on: " + (path != null ? path.toString() : "null"), -1L);
        }
        public static FileReadException oversize(long actualSize) {
            return new FileReadException("R03-READ-OVERSIZE",
                    "file oversize: " + actualSize, actualSize);
        }
    }

    private final BindingFileReader fileReader;
    private final boolean isDefaultReader;

    public DevelopmentPredecessorBindingVerifier() {
        this((BindingFileReader) null);
    }

    public DevelopmentPredecessorBindingVerifier(BindingFileReader fileReader) {
        this.isDefaultReader = fileReader == null;
        this.fileReader = this.isDefaultReader ? new DefaultBindingFileReader() : fileReader;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Verify a binding JSON against its Authority and Provider artifact.
     *
     * @param bindingPath   absolute path to binding JSON
     * @param authorityPath absolute path to protocol authority JSON
     * @param repoRoot      repository root for resolving repo-relative provider paths
     * @return immutable verified binding with defensive-copy providerBytes
     * @throws DevelopmentPredecessorBindingException on any verification failure
     */
    public DevelopmentPredecessorBinding verify(
            Path bindingPath, Path authorityPath, Path repoRoot)
            throws DevelopmentPredecessorBindingException {
        Objects.requireNonNull(bindingPath, "bindingPath");
        Objects.requireNonNull(authorityPath, "authorityPath");
        Objects.requireNonNull(repoRoot, "repoRoot");

        if (!bindingPath.isAbsolute())
            throw new BindingEx("R03-BINDING-MISSING",
                    Map.of("path", bindingPath.toString(), "detail", "bindingPath must be absolute"));
        if (!authorityPath.isAbsolute())
            throw new BindingEx("R03-READ-UNREADABLE",
                    Map.of("path", authorityPath.toString(), "detail", "authorityPath must be absolute"));
        if (!repoRoot.isAbsolute())
            throw new BindingEx("R03-PATH-MISMATCH",
                    Map.of("detail", "repoRoot must be absolute"));

        // Step 1: Read & parse binding JSON -- LF-only byte envelope + strict parse
        byte[] rawBinding = readBindingBytes(bindingPath);
        JsonNode bindingNode = parseBindingJson(rawBinding);
        validateBindingShape(bindingNode);

        // Step 2: Extract raw values
        String storedBindingFp = text(bindingNode, "bindingFingerprint");
        String storedAuthFp   = text(bindingNode, "authorityRawFingerprint");
        String msgVersion     = text(bindingNode, "messageVersion");
        String srcSlice       = text(bindingNode, "sourceSliceId");
        String tgtSlice       = text(bindingNode, "targetSliceId");
        JsonNode provNode     = node(bindingNode, "providerArtifact");
        String storedCoord    = text(provNode, "coordinate");
        String storedPath     = text(provNode, "path");
        long   storedLen     = lnum(provNode, "byteLength");
        String storedProvFp  = text(provNode, "rawFingerprint");

        // Step 3: Binding fingerprint recompute
        recomputeBindingFingerprint(rawBinding, storedBindingFp);

        // Step 4: Fixed structure values
        if (!MSG_VERSION.equals(msgVersion))
            throw new BindingEx("R03-BINDING-STRUCTURE-MISMATCH",
                    Map.of("field","messageVersion","expected",MSG_VERSION,"actual",msgVersion));
        if (!SOURCE_SLICE.equals(srcSlice))
            throw new BindingEx("R03-BINDING-STRUCTURE-MISMATCH",
                    Map.of("field","sourceSliceId","expected",SOURCE_SLICE,"actual",srcSlice));
        if (!TARGET_SLICE.equals(tgtSlice))
            throw new BindingEx("R03-BINDING-STRUCTURE-MISMATCH",
                    Map.of("field","targetSliceId","expected",TARGET_SLICE,"actual",tgtSlice));

        // Step 5: Read & verify Authority raw fingerprint
        PathBytes authBytes;
        try {
            authBytes = fileReader.read(authorityPath, MAX_AUTH);
        } catch (FileReadException fe) {
            Map<String,Object> args = new HashMap<>();
            args.put("path", authorityPath.toString());
            args.put("detail", fe.getMessage());
            if (fe.size() > 0) args.put("size", fe.size());
            throw new BindingEx(fe.reasonCode(), args, fe);
        } catch (IOException e) {
            throw new BindingEx("R03-READ-UNREADABLE",
                    Map.of("path",authorityPath.toString(),"detail",e.getMessage()), e);
        }
        String recomputedAuthFp = sha256Hex(authBytes.bytes());
        if (!storedAuthFp.equals(recomputedAuthFp))
            throw new BindingEx("R03-AUTHORITY-FP-MISMATCH",
                    Map.of("stored",storedAuthFp,"recomputed",recomputedAuthFp));

        // Step 6: Derive expected A1.2 facts from Authority (strict duplicate+single-root parser)
        JsonNode authorityNode = parseAuthorityJson(authBytes.bytes());
        AuthorityA12 a12 = deriveA12Artifact(authorityNode);

        // Step 7: Coordinate & path checks
        if (!a12.coordinate().equals(storedCoord))
            throw new BindingEx("R03-COORDINATE-MISMATCH",
                    Map.of("expected",a12.coordinate(),"actual",storedCoord));
        if (!a12.artifactPath().equals(storedPath))
            throw new BindingEx("R03-PATH-MISMATCH",
                    Map.of("expected",a12.artifactPath(),"actual",storedPath));

        // Step 8: Derive providerEntryPath
        String providerEntryPath = deriveProviderEntryPath(authorityNode);

        // Step 9: Validate & traverse provider repo-relative path (lexical only, no TOCTOU)
        Path providerPath = validateAndTraverse(repoRoot, storedPath);

        // Step 10: Read & verify Provider (root-anchored when using default reader)
        PathBytes provBytes;
        try {
            if (isDefaultReader && fileReader instanceof DefaultBindingFileReader) {
                provBytes = ((DefaultBindingFileReader) fileReader)
                        .readAnchored(repoRoot, storedPath, MAX_PROVIDER);
            } else {
                provBytes = fileReader.read(providerPath, MAX_PROVIDER);
            }
        } catch (FileReadException fe) {
            Map<String,Object> args = new HashMap<>();
            args.put("path", providerPath.toString());
            args.put("detail", fe.getMessage());
            if (fe.size() > 0) args.put("size", fe.size());
            throw new BindingEx(fe.reasonCode(), args, fe);
        } catch (IOException e) {
            throw new BindingEx("R03-READ-UNREADABLE",
                    Map.of("path",providerPath.toString(),"detail",e.getMessage()), e);
        }

        // Step 11: Size check
        if (provBytes.size() != storedLen)
            throw new BindingEx("R03-SIZE-MISMATCH",
                    Map.of("expected",storedLen,"actual",provBytes.size()));

        // Step 12: Provider fingerprint
        String recomputedProvFp = sha256HexProv(provBytes.bytes());
        if (!storedProvFp.equals(recomputedProvFp))
            throw new BindingEx("R03-PROVIDER-FP-MISMATCH",
                    Map.of("stored",storedProvFp,"recomputed",recomputedProvFp));

        // Step 13: Construct verified binding
        var provArtifact = new DevelopmentPredecessorBinding.ProviderArtifact(
                storedCoord, storedPath, storedLen, storedProvFp);
        return new DevelopmentPredecessorBinding(
                storedBindingFp, storedAuthFp, msgVersion, srcSlice, tgtSlice,
                provArtifact, providerEntryPath, provBytes.bytes());
    }

    // -------------------------------------------------------------------------
    // Binding read & parse
    // -------------------------------------------------------------------------

    private byte[] readBindingBytes(Path bindingPath)
            throws DevelopmentPredecessorBindingException {
        if (!Files.exists(bindingPath))
            throw new BindingEx("R03-BINDING-MISSING",
                    Map.of("path", bindingPath.toString()));

        PathBytes pb;
        try {
            pb = fileReader.read(bindingPath, MAX_BINDING);
        } catch (FileReadException fe) {
            Map<String,Object> args = new HashMap<>();
            args.put("path", bindingPath.toString());
            args.put("detail", fe.getMessage());
            if (fe.size() > 0) args.put("size", fe.size());
            throw new BindingEx(fe.reasonCode(), args, fe);
        } catch (IOException e) {
            throw new BindingEx("R03-BINDING-MISSING",
                    Map.of("path",bindingPath.toString(),"detail",e.getMessage()), e);
        }
        return pb.bytes();
    }

    /**
     * Parse binding JSON with strict byte-level and structural requirements:
     * - Exactly one trailing LF (no CRLF, no multi-LF, no embedded LF)
     * - No non-ASCII bytes
     * - Strict duplicate key detection
     * - Single-root enforcement (no trailing tokens)
     */
    private JsonNode parseBindingJson(byte[] raw)
            throws DevelopmentPredecessorBindingException {
        // R03-BINDING-INVALID: byte-level envelope validation
        if (raw == null || raw.length < 2) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "binding too short for LF-terminated JSON"));
        }
        // Check no non-ASCII bytes anywhere
        for (int i = 0; i < raw.length; i++) {
            if ((raw[i] & 0x80) != 0) {
                throw new BindingEx("R03-BINDING-INVALID",
                        Map.of("detail", "non-ASCII byte at offset " + i));
            }
        }
        // Must end with exactly one LF (no CRLF, no multi-LF)
        if (raw[raw.length - 1] != (byte) '\n') {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "binding must end with exactly one LF, no trailing bytes"));
        }
        // Check for CRLF
        if (raw.length >= 2 && raw[raw.length - 2] == (byte) '\r') {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "CRLF not permitted, use LF only"));
        }
        // No embedded LF (except the mandatory trailing one we already checked)
        for (int i = 0; i < raw.length - 1; i++) {
            if (raw[i] == '\n') {
                throw new BindingEx("R03-BINDING-INVALID",
                        Map.of("detail", "embedded LF not permitted, JSON must be single-line"));
            }
        }

        // Strict JSON parser: duplicate detection + single-root enforcement
        JsonFactory factory = JsonFactory.builder()
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        ObjectMapper mapper = new ObjectMapper(factory);
        try {
            JsonParser p = factory.createParser(raw);
            JsonNode node = mapper.readTree(p);
            // Jackson readTree consumes the root; enforce that nothing follows.
            JsonToken next;
            while ((next = p.nextToken()) == JsonToken.NOT_AVAILABLE) {
                // Spin until a definitive token or EOF is available.
            }
            if (next != null) {
                throw new JsonParseException(p,
                        "trailing non-whitespace token after JSON root: " + next);
            }
            // Canonical exact comparison: canonicalJson(node) + LF must equal raw bytes.
            // This rejects leading/trailing whitespace and non-canonical key order.
            byte[] canonicalPlusLf = canonicalJson(node);
            byte[] withTrailingLf = new byte[canonicalPlusLf.length + 1];
            System.arraycopy(canonicalPlusLf, 0, withTrailingLf, 0, canonicalPlusLf.length);
            withTrailingLf[canonicalPlusLf.length] = (byte) '\n';
            if (!Arrays.equals(raw, withTrailingLf)) {
                throw new BindingEx("R03-BINDING-INVALID",
                        Map.of("detail", "binding must be canonical JSON followed by exactly one LF"));
            }
            return node;
        } catch (IOException e) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", e.getMessage()), e);
        }
    }

    /**
     * Strict authority parser: duplicate detection + single-root enforcement.
     * Does NOT require canonical form (unlike binding parser).
     */
    private JsonNode parseAuthorityJson(byte[] raw)
            throws DevelopmentPredecessorBindingException {
        if (raw == null) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "authority bytes are null"));
        }
        JsonFactory factory = JsonFactory.builder()
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        ObjectMapper mapper = new ObjectMapper(factory);
        try {
            JsonParser p = factory.createParser(raw);
            JsonNode node = mapper.readTree(p);
            JsonToken next;
            while ((next = p.nextToken()) == JsonToken.NOT_AVAILABLE) {
                // Spin until a definitive token or EOF is available.
            }
            if (next != null) {
                throw new JsonParseException(p,
                        "trailing non-whitespace token after authority root: " + next);
            }
            return node;
        } catch (IOException e) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "authority parse: " + e.getMessage()), e);
        }
    }

    /**
     * Validate binding JSON structure with strict field-level type checking.
     * - 5 top-level string fields (bindingFingerprint, authorityRawFingerprint, messageVersion, sourceSliceId, targetSliceId)
     * - providerArtifact object with 4 fields (coordinate/path/rawFingerprint string, byteLength integral long)
     * - All strings non-empty
     * - Fingerprints strictly sha256: lowercase 64-hex
     * - byteLength range 1..16MiB
     */
    private void validateBindingShape(JsonNode node)
            throws DevelopmentPredecessorBindingException {
        if (node == null || !node.isObject()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "binding root must be a JSON object"));
        }

        // Top-level: exactly 6 keys, no duplicates, no unknowns
        Iterator<Map.Entry<String,JsonNode>> it = node.fields();
        List<String> seen = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<String,JsonNode> e = it.next();
            String k = e.getKey();
            if (!BINDING_KEYS.contains(k)) {
                throw new BindingEx("R03-BINDING-INVALID",
                        Map.of("field", k, "detail", "unknown top-level key"));
            }
            if (!seen.add(k)) {
                throw new BindingEx("R03-BINDING-INVALID",
                        Map.of("field", k, "detail", "duplicate top-level key"));
            }
            // Strict type: only the 5 string fields must be textual; providerArtifact is checked separately
            if (!k.equals("providerArtifact")) {
                JsonNode val = e.getValue();
                if (!val.isTextual()) {
                    throw new BindingEx("R03-BINDING-INVALID",
                            Map.of("field", k, "detail", "must be a JSON string"));
                }
            }
        }
        if (seen.size() != 6) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "top-level must have exactly 6 keys, got: " + seen.size()));
        }

        // All 5 top-level strings must be non-empty
        for (String field : List.of("bindingFingerprint","authorityRawFingerprint",
                "messageVersion","sourceSliceId","targetSliceId")) {
            String val = text(node, field);
            if (val == null || val.isEmpty()) {
                throw new BindingEx("R03-BINDING-INVALID",
                        Map.of("field", field, "detail", "string must not be empty"));
            }
        }

        // Fingerprint strictness: both must be sha256: lowercase 64 hex chars
        String bf = text(node, "bindingFingerprint");
        String af = text(node, "authorityRawFingerprint");
        validateSha256Hex("bindingFingerprint", bf);
        validateSha256Hex("authorityRawFingerprint", af);

        // providerArtifact: exactly 4 keys, correct types
        JsonNode prov = node.path("providerArtifact");
        if (prov == null || !prov.isObject()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact", "detail", "must be a JSON object"));
        }
        Iterator<Map.Entry<String,JsonNode>> pit = prov.fields();
        List<String> pseen = new ArrayList<>();
        while (pit.hasNext()) {
            Map.Entry<String,JsonNode> e = pit.next();
            String k = e.getKey();
            if (!PROVIDER_KEYS.contains(k)) {
                throw new BindingEx("R03-BINDING-INVALID",
                        Map.of("field", "providerArtifact." + k, "detail", "unknown key"));
            }
            if (!pseen.add(k)) {
                throw new BindingEx("R03-BINDING-INVALID",
                        Map.of("field", "providerArtifact." + k, "detail", "duplicate key"));
            }
        }
        if (pseen.size() != 4) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "providerArtifact must have exactly 4 keys"));
        }

        // providerArtifact field types: coordinate/path/rawFingerprint must be string; byteLength must be integral long
        JsonNode coordVal = prov.path("coordinate");
        JsonNode pathVal  = prov.path("path");
        JsonNode fpVal    = prov.path("rawFingerprint");
        JsonNode lenVal   = prov.path("byteLength");

        if (!coordVal.isTextual()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact.coordinate", "detail", "must be a JSON string"));
        }
        if (!pathVal.isTextual()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact.path", "detail", "must be a JSON string"));
        }
        if (!fpVal.isTextual()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact.rawFingerprint", "detail", "must be a JSON string"));
        }

        // byteLength: must be integral long, no floating-point, no scientific notation
        if (!lenVal.isIntegralNumber()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact.byteLength", "detail", "must be an integral JSON number"));
        }
        long byteLen = lenVal.asLong();
        // byteLength range: 1..16MiB
        if (byteLen < MIN_BYTE_LENGTH || byteLen > MAX_BYTE_LENGTH) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact.byteLength",
                            "detail", "byteLength must be " + MIN_BYTE_LENGTH + ".." + MAX_BYTE_LENGTH + ", got " + byteLen));
        }

        // rawFingerprint strict sha256 hex
        String rawFp = fpVal.asText();
        if (rawFp == null || rawFp.isEmpty()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact.rawFingerprint", "detail", "string must not be empty"));
        }
        validateSha256Hex("providerArtifact.rawFingerprint", rawFp);

        // coordinate and path non-empty strings
        String coordStr = coordVal.asText();
        String pathStr = pathVal.asText();
        if (coordStr == null || coordStr.isEmpty()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact.coordinate", "detail", "string must not be empty"));
        }
        if (pathStr == null || pathStr.isEmpty()) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", "providerArtifact.path", "detail", "string must not be empty"));
        }
    }

    /**
     * Validate that a fingerprint string is strictly sha256: prefix + 64 lowercase hex chars.
     */
    private void validateSha256Hex(String fieldName, String value) throws DevelopmentPredecessorBindingException {
        if (value == null) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", fieldName, "detail", "fingerprint must not be null"));
        }
        if (value.length() != SHA256_PREFIXED_HEX_LEN) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", fieldName, "detail",
                            "fingerprint must be sha256: (7 chars) + 64 lowercase hex chars, got length " + value.length()));
        }
        if (!value.startsWith("sha256:")) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", fieldName, "detail", "fingerprint must start with \"sha256:\""));
        }
        String hex = value.substring(7);
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("field", fieldName, "detail",
                            "fingerprint hex portion must be exactly 64 lowercase hex characters"));
        }
    }

    private void recomputeBindingFingerprint(byte[] raw, String stored)
            throws DevelopmentPredecessorBindingException {
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(raw);
        } catch (IOException e) {
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", e.getMessage()), e);
        }
        ObjectNode on = new ObjectMapper().valueToTree(node);
        on.remove("bindingFingerprint");
        byte[] canonical = canonicalJson(on);
        byte[] domain = BINDING_DOMAIN.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[domain.length + 1 + canonical.length];
        System.arraycopy(domain, 0, payload, 0, domain.length);
        payload[domain.length] = 0; // NUL
        System.arraycopy(canonical, 0, payload, domain.length + 1, canonical.length);
        String computed = sha256Hex(payload);
        if (!stored.equals(computed)) {
            throw new BindingEx("R03-BINDING-FP-MISMATCH",
                    Map.of("stored", stored, "recomputed", computed));
        }
    }

    // -------------------------------------------------------------------------
    // Authority derivation
    // -------------------------------------------------------------------------

    /**
     * Derive A1.2 artifact facts from Authority deliverySlices.
     * Requirements:
     * - deliverySlices must contain exactly one A1.2 entry
     * - handoffArtifacts must contain exactly one TCK_PROVIDER role entry (other roles allowed)
     * - outputArtifacts must contain exactly one TCK_PROVIDER role entry (other roles allowed)
     * - Both TCK_PROVIDER entries must have identical coordinate objects and identical paths
     * - Both TCK_PROVIDER coordinate classifiers must be absent/null/empty
     */
    private AuthorityA12 deriveA12Artifact(JsonNode authorityNode)
            throws DevelopmentPredecessorBindingException {
        JsonNode slices = authorityNode.path("deliverySlices");
        if (slices.isMissingNode() || !slices.isArray())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "deliverySlices missing or not array"));

        // deliverySlices must contain exactly one A1.2 entry
        List<JsonNode> a12Candidates = new ArrayList<>();
        for (JsonNode s : slices) {
            if (s.isObject() && SOURCE_SLICE.equals(s.path("sliceId").asText())) {
                a12Candidates.add(s);
            }
        }
        if (a12Candidates.isEmpty())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "A1.2 slice not found"));
        if (a12Candidates.size() > 1)
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "multiple A1.2 slices found"));

        JsonNode a12 = a12Candidates.get(0);

        JsonNode ha = a12.path("handoffArtifacts");
        JsonNode oa = a12.path("outputArtifacts");
        if (!ha.isArray())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "handoffArtifacts must be an array"));
        if (!oa.isArray())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "outputArtifacts must be an array"));

        // Exactly one TCK_PROVIDER in handoffArtifacts (array may contain other roles)
        JsonNode hTck = null;
        int hTckCount = 0;
        for (JsonNode h : ha) {
            if (h.isObject() && "TCK_PROVIDER".equals(h.path("role").asText(null))) {
                hTck = h;
                hTckCount++;
            }
        }
        if (hTckCount != 1)
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "handoffArtifacts must contain exactly one TCK_PROVIDER entry (found " + hTckCount + ")"));

        // Exactly one TCK_PROVIDER in outputArtifacts (array may contain other roles)
        JsonNode oTck = null;
        int oTckCount = 0;
        for (JsonNode o : oa) {
            if (o.isObject() && "TCK_PROVIDER".equals(o.path("role").asText(null))) {
                oTck = o;
                oTckCount++;
            }
        }
        if (oTckCount != 1)
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "outputArtifacts must contain exactly one TCK_PROVIDER entry (found " + oTckCount + ")"));

        // coordinate object must be identical between handoff and output TCK_PROVIDER entries
        String hCoord = coordStr(hTck.path("coordinate"));
        String oCoord = coordStr(oTck.path("coordinate"));
        if (!hCoord.equals(oCoord))
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "handoff/output TCK_PROVIDER coordinate mismatch"));

        // path must be identical between handoff and output TCK_PROVIDER entries
        String hPath = hTck.path("path").asText("");
        String oPath = oTck.path("path").asText("");
        if (!hPath.equals(oPath))
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "handoff/output TCK_PROVIDER path mismatch"));
        if (hPath.isBlank())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "handoff TCK_PROVIDER path must not be blank"));

        // Coordinate fields: non-blank groupId/artifactId/version, classifier absent/null/""
        JsonNode hCoordNode = hTck.path("coordinate");
        String groupId    = hCoordNode.path("groupId").asText("");
        String artifactId = hCoordNode.path("artifactId").asText("");
        String version    = hCoordNode.path("version").asText("");
        String hClassifier = hCoordNode.path("classifier").asText(null);
        String oClassifier = oTck.path("coordinate").path("classifier").asText(null);

        if (groupId.isBlank() || artifactId.isBlank() || version.isBlank())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "coordinate has blank field"));
        // classifier must be null or empty string (not set to a non-empty value)
        if (hClassifier != null && !hClassifier.isEmpty())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "handoff coordinate classifier must be null or empty"));
        if (oClassifier != null && !oClassifier.isEmpty())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "output coordinate classifier must be null or empty"));

        String coordinate   = groupId + ":" + artifactId + ":" + version;
        String artifactPath = hPath;
        if (artifactPath.isBlank())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "artifactPath is blank"));

        return new AuthorityA12(coordinate, artifactPath);
    }

    /**
     * Derive providerEntryPath from roleContracts.
     * Requirements:
     * - roleContracts must be an array
     * - Exactly one INDEPENDENT_VERIFIER entry required
     * - providerEntryPath must be non-blank
     */
    private String deriveProviderEntryPath(JsonNode authorityNode)
            throws DevelopmentPredecessorBindingException {
        JsonNode rc = authorityNode.path("roleContracts");
        if (!rc.isArray())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "roleContracts must be array"));

        // Exactly one INDEPENDENT_VERIFIER entry required
        int indepCount = 0;
        String providerEntryPath = null;
        for (JsonNode role : rc) {
            if (role.isObject() && "INDEPENDENT_VERIFIER".equals(role.path("role").asText(null))) {
                indepCount++;
                providerEntryPath = role.path("providerEntryPath").asText("");
            }
        }
        if (indepCount == 0)
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "INDEPENDENT_VERIFIER role not found"));
        if (indepCount > 1)
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "multiple INDEPENDENT_VERIFIER roles found, exactly one required"));
        if (providerEntryPath == null || providerEntryPath.isBlank())
            throw new BindingEx("R03-BINDING-INVALID",
                    Map.of("detail", "providerEntryPath missing in INDEPENDENT_VERIFIER"));
        return providerEntryPath;
    }

    // -------------------------------------------------------------------------
    // Provider path traversal (fail-closed)
    // -------------------------------------------------------------------------

    /** Validate repo-relative path lexically and normalize. No TOCTOU. Fails closed. */
    private Path validateAndTraverse(Path repoRoot, String repoRelative)
            throws DevelopmentPredecessorBindingException {
        if (repoRoot == null)
            throw new BindingEx("R03-PATH-MISMATCH",
                    Map.of("detail", "repoRoot is null"));
        if (repoRelative == null || repoRelative.isBlank())
            throw new BindingEx("R03-PATH-MISMATCH",
                    Map.of("detail", "repo-relative path is blank"));
        if (repoRelative.startsWith("/"))
            throw new BindingEx("R03-PATH-MISMATCH",
                    Map.of("detail", "absolute path rejected"));
        if (repoRelative.contains("\0"))
            throw new BindingEx("R03-PATH-MISMATCH",
                    Map.of("detail", "NUL character rejected"));
        if (repoRelative.contains("\\"))
            throw new BindingEx("R03-PATH-MISMATCH",
                    Map.of("detail", "backslash rejected"));

        // Lexical validation: no empty, ".", or ".." components
        String[] parts = repoRelative.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part))
                throw new BindingEx("R03-PATH-MISMATCH",
                        Map.of("detail", "invalid path component: " + part));
        }

        // Normalize: normalize root first, then resolve and verify anchor
        Path root;
        try {
            root = repoRoot.toAbsolutePath().normalize();
        } catch (SecurityException e) {
            throw new BindingEx("R03-READ-UNREADABLE",
                    Map.of("detail", "repoRoot normalize: " + e.getMessage()), e);
        }

        Path normalized;
        try {
            normalized = root.resolve(repoRelative).normalize();
        } catch (SecurityException e) {
            throw new BindingEx("R03-READ-UNREADABLE",
                    Map.of("detail", "resolve: " + e.getMessage()), e);
        }

        // Must start with root (after normalize to resolve any ".." in path)
        if (!normalized.startsWith(root)) {
            throw new BindingEx("R03-PATH-MISMATCH",
                    Map.of("detail", "path escapes root"));
        }

        return normalized;
    }

    // -------------------------------------------------------------------------
    // Utilities

    // ---------------------------------------------------------------------
    // Default file reader (NOFOLLOW + bounded + pre/post check)
    // ---------------------------------------------------------------------

    private static final class DefaultBindingFileReader implements BindingFileReader {

        private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
        private static final Set<java.nio.file.OpenOption> NOFOLLOW_READ =
                Set.of(java.nio.file.StandardOpenOption.READ, NOFOLLOW);

        @Override
        public PathBytes read(Path path, long maxBytes) throws IOException {
            BasicFileAttributes pre;
            try {
                pre = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
            } catch (NoSuchFileException e) {
                throw FileReadException.unreadable("not found: " + path);
            } catch (SecurityException e) {
                throw FileReadException.unreadable("security: " + e.getMessage());
            } catch (IOException e) {
                throw FileReadException.unreadable("attrs: " + e.getMessage());
            }

            if (!pre.isRegularFile()) {
                throw FileReadException.unreadable("not a regular file");
            }

            long size = pre.size();
            if (size < 0) {
                throw FileReadException.unreadable("negative size");
            }
            if (size > maxBytes) {
                throw FileReadException.oversize(size);
            }

            Object preKey = pre.fileKey();
            long preMtime = pre.lastModifiedTime().toMillis();
            long preDev = extractDev(pre);
            long preIno = extractIno(pre);

            byte[] buf;
            IOException closeEx = null;
            java.nio.channels.SeekableByteChannel ch = null;
            try {
                ch = Files.newByteChannel(path, NOFOLLOW_READ);
                if (ch.size() != size) {
                    throw FileReadException.stale(path);
                }
                buf = new byte[(int) size];
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
                long total = 0;
                while (total < size) {
                    int n = ch.read(bb);
                    if (n < 0) break;
                    total += n;
                    if (total > maxBytes) {
                        throw FileReadException.oversize(total);
                    }
                }
                if (total != size) {
                    throw FileReadException.stale(path);
                }
            } catch (FileReadException e) {
                try { if (ch != null) ch.close(); } catch (IOException ce) { e.addSuppressed(ce); }
                throw e;
            } catch (SecurityException e) {
                IOException ex = FileReadException.unreadable("read: " + e.getMessage());
                try { if (ch != null) ch.close(); } catch (IOException ce) { ex.addSuppressed(ce); }
                throw ex;
            } catch (IOException e) {
                IOException ex = FileReadException.unreadable("read: " + e.getMessage());
                try { if (ch != null) ch.close(); } catch (IOException ce) { ex.addSuppressed(ce); }
                throw ex;
            }

            try {
                ch.close();
            } catch (IOException e) {
                closeEx = e;
            }

            BasicFileAttributes post;
            try {
                post = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
            } catch (IOException e) {
                IOException ex = FileReadException.stale(path);
                if (closeEx != null) ex.addSuppressed(closeEx);
                throw ex;
            }

            Object postKey = post.fileKey();
            if (preKey != null && postKey != null && !preKey.equals(postKey)) {
                IOException ex = FileReadException.stale(path);
                if (closeEx != null) ex.addSuppressed(closeEx);
                throw ex;
            }
            if (post.size() != size) {
                IOException ex = FileReadException.stale(path);
                if (closeEx != null) ex.addSuppressed(closeEx);
                throw ex;
            }
            long postMtime = post.lastModifiedTime().toMillis();
            if (postMtime != preMtime) {
                IOException ex = FileReadException.stale(path);
                if (closeEx != null) ex.addSuppressed(closeEx);
                throw ex;
            }

            if (closeEx != null) {
                throw FileReadException.unreadable("close failed: " + closeEx.getMessage());
            }

            return new PathBytes(buf, preDev, preIno, size, preMtime * 1_000_000L);
        }

        public PathBytes readAnchored(Path repoRoot, String repoRelative, long maxBytes)
                throws IOException {
            if (repoRoot == null) {
                throw FileReadException.unreadable("repoRoot is null");
            }
            if (repoRelative == null || repoRelative.isEmpty()) {
                throw FileReadException.unreadable("repoRelative is null or empty");
            }

            // Normalize root first to handle paths like "module/.."
            Path root;
            try {
                root = repoRoot.toAbsolutePath().normalize();
            } catch (SecurityException e) {
                throw FileReadException.unreadable("repoRoot normalize: " + e.getMessage());
            }

            BasicFileAttributes rootAttrs;
            try {
                rootAttrs = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW);
            } catch (NoSuchFileException e) {
                throw FileReadException.unreadable("repoRoot not found");
            } catch (IOException e) {
                throw FileReadException.unreadable("repoRoot attrs: " + e.getMessage());
            }
            if (!rootAttrs.isDirectory()) {
                throw FileReadException.unreadable("repoRoot is not a directory");
            }

            java.nio.file.DirectoryStream<Path> rootDs;
            try {
                rootDs = Files.newDirectoryStream(root);
            } catch (SecurityException e) {
                throw FileReadException.unreadable("root dir stream: " + e.getMessage());
            } catch (IOException e) {
                throw FileReadException.unreadable("root dir stream: " + e.getMessage());
            }
            if (!(rootDs instanceof java.nio.file.SecureDirectoryStream)) {
                IOException mainEx = FileReadException.unreadable("root not a SecureDirectoryStream");
                try { rootDs.close(); } catch (IOException e) { mainEx.addSuppressed(e); }
                throw mainEx;
            }
            @SuppressWarnings("unchecked")
            java.nio.file.SecureDirectoryStream<Path> rootSds =
                    (java.nio.file.SecureDirectoryStream<Path>) rootDs;

            // All opened SDS handles, closed in reverse order in the finally block.
            java.util.Deque<java.nio.file.SecureDirectoryStream<Path>> opened =
                    new java.util.ArrayDeque<>();
            opened.add(rootSds);

            // currentSds starts at root and advances level-by-level through the path.
            java.nio.file.SecureDirectoryStream<Path> currentSds = rootSds;

            String[] segments = repoRelative.split("/", -1);

            Throwable primary = null;
            try {
                for (int i = 0; i < segments.length; i++) {
                    String seg = segments[i];
                    if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                        throw FileReadException.unreadable("invalid segment: " + seg);
                    }

                    boolean isLeaf = (i == segments.length - 1);
                    Path segPath = root.getFileSystem().getPath(seg);

                    if (isLeaf) {
                        // Pre attrs via current SDS getFileAttributeView with NOFOLLOW
                        java.nio.file.attribute.BasicFileAttributeView fav;
                        fav = currentSds.getFileAttributeView(segPath,
                                java.nio.file.attribute.BasicFileAttributeView.class, NOFOLLOW);
                        if (fav == null) {
                            throw FileReadException.unreadable("leaf not found: " + seg);
                        }

                        BasicFileAttributes pre;
                        try {
                            pre = fav.readAttributes();
                        } catch (NoSuchFileException e) {
                            throw FileReadException.unreadable("leaf not found: " + seg);
                        } catch (IOException e) {
                            throw FileReadException.unreadable("pre attrs: " + e.getMessage());
                        }

                        if (!pre.isRegularFile()) {
                            throw FileReadException.unreadable("leaf not regular: " + seg);
                        }

                        long size = pre.size();
                        if (size < 0) {
                            throw FileReadException.unreadable("negative size");
                        }
                        if (size > maxBytes) {
                            throw FileReadException.oversize(size);
                        }

                        Object preKey = pre.fileKey();
                        long preMtime = pre.lastModifiedTime().toMillis();
                        long preDev = extractDev(pre);
                        long preIno = extractIno(pre);

                        java.nio.ByteBuffer buf;
                        java.nio.channels.SeekableByteChannel ch = null;
                        try (java.nio.channels.SeekableByteChannel chan =
                                currentSds.newByteChannel(segPath, NOFOLLOW_READ)) {
                            ch = chan;
                            if (chan.size() != size) {
                                throw FileReadException.stale(root.resolve(repoRelative));
                            }
                            buf = java.nio.ByteBuffer.allocate((int) size);
                            long total = 0;
                            while (total < size) {
                                int n = chan.read(buf);
                                if (n < 0) break;
                                total += n;
                                if (total > maxBytes) {
                                    throw FileReadException.oversize(total);
                                }
                            }
                            if (total != size) {
                                throw FileReadException.stale(root.resolve(repoRelative));
                            }
                        } catch (FileReadException e) {
                            throw e;
                        } catch (IOException e) {
                            IOException ex = FileReadException.unreadable("read: " + e.getMessage());
                            try { if (ch != null) ch.close(); } catch (IOException ce) { ex.addSuppressed(ce); }
                            throw ex;
                        }

                        // Post attrs via current SDS with NOFOLLOW
                        java.nio.file.attribute.BasicFileAttributeView postFav;
                        postFav = currentSds.getFileAttributeView(segPath,
                                java.nio.file.attribute.BasicFileAttributeView.class, NOFOLLOW);
                        if (postFav == null) {
                            throw FileReadException.stale(root.resolve(repoRelative));
                        }

                        BasicFileAttributes post;
                        try {
                            post = postFav.readAttributes();
                        } catch (IOException e) {
                            throw FileReadException.stale(root.resolve(repoRelative));
                        }

                        Object postKey = post.fileKey();
                        if (preKey != null && postKey != null && !preKey.equals(postKey)) {
                            throw FileReadException.stale(root.resolve(repoRelative));
                        }
                        if (post.size() != size) {
                            throw FileReadException.stale(root.resolve(repoRelative));
                        }
                        long postMtime = post.lastModifiedTime().toMillis();
                        if (postMtime != preMtime) {
                            throw FileReadException.stale(root.resolve(repoRelative));
                        }

                        return new PathBytes(buf.array(), preDev, preIno, size, preMtime * 1_000_000L);

                    } else {
                        // Intermediate segment: check and open next level via current SDS
                        java.nio.file.attribute.BasicFileAttributeView segFav;
                        segFav = currentSds.getFileAttributeView(segPath,
                                java.nio.file.attribute.BasicFileAttributeView.class, NOFOLLOW);
                        if (segFav == null) {
                            throw FileReadException.unreadable("segment not found: " + seg);
                        }

                        BasicFileAttributes segAttrs;
                        try {
                            segAttrs = segFav.readAttributes();
                        } catch (NoSuchFileException e) {
                            throw FileReadException.unreadable("segment not found: " + seg);
                        } catch (IOException e) {
                            throw FileReadException.unreadable("segment attrs: " + e.getMessage());
                        }
                        if (!segAttrs.isDirectory()) {
                            throw FileReadException.unreadable("segment not a directory: " + seg);
                        }

                        java.nio.file.DirectoryStream<Path> nextDs;
                        try {
                            nextDs = currentSds.newDirectoryStream(segPath, NOFOLLOW);
                        } catch (NoSuchFileException e) {
                            throw FileReadException.unreadable("segment not found: " + seg);
                        } catch (IOException e) {
                            throw FileReadException.unreadable("segment open: " + e.getMessage());
                        }

                        if (!(nextDs instanceof java.nio.file.SecureDirectoryStream)) {
                            IOException mainEx = FileReadException.unreadable("segment not a SecureDirectoryStream");
                            try { nextDs.close(); } catch (IOException e) { mainEx.addSuppressed(e); }
                            throw mainEx;
                        }

                        @SuppressWarnings("unchecked")
                        java.nio.file.SecureDirectoryStream<Path> nextSds =
                                (java.nio.file.SecureDirectoryStream<Path>) nextDs;
                        opened.add(nextSds);
                        currentSds = nextSds;
                    }
                }

                throw FileReadException.unreadable("traversal incomplete: no leaf");

            } catch (Throwable t) {
                primary = t;
                throw t;
            } finally {
                IOException firstCloseFailure = null;
                // Close in reverse order: leafmost (last added) first.
                while (!opened.isEmpty()) {
                    java.nio.file.SecureDirectoryStream<Path> sds = opened.removeLast();
                    try {
                        sds.close();
                    } catch (IOException e) {
                        if (firstCloseFailure == null) firstCloseFailure = e;
                        else primary.addSuppressed(e);
                    }
                }
                if (firstCloseFailure != null && primary == null) {
                    throw FileReadException.unreadable(
                            "directory close failed: " + firstCloseFailure.getMessage());
                }
            }
        }

        private static long extractIno(BasicFileAttributes attrs) {
            Object key = attrs.fileKey();
            if (key == null) return -1;
            return (long) key.hashCode();
        }

        private static long extractDev(BasicFileAttributes attrs) {
            Object key = attrs.fileKey();
            if (key == null) return -1;
            return ((long) key.hashCode()) >>> 32;
        }
    }
    // -------------------------------------------------------------------------

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(7 + hash.length * 2);
            sb.append("sha256:");
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Provider artifact fingerprint: sha256: prefix + lowercase hex. */
    private static String sha256HexProv(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(7 + hash.length * 2);
            sb.append("sha256:");
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Canonical JSON: sort keys at every level, no extra whitespace. */
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
                try { out.write(keys.get(i).getBytes(StandardCharsets.UTF_8)); }
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
            try { out.write(node.asText().getBytes(StandardCharsets.UTF_8)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        } else if (node.isBoolean()) {
            try { out.write(Boolean.toString(node.asBoolean())
                    .getBytes(StandardCharsets.UTF_8)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        } else {
            try { out.write("null".getBytes(StandardCharsets.UTF_8)); }
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
                        try {
                            out.write(String.format("\\u%04x", (int)c)
                                    .getBytes(StandardCharsets.UTF_8));
                        } catch (java.io.IOException e) { throw new UncheckedIOException(e); }
                    } else {
                        out.write(c);
                    }
                }
            }
        }
    }

    /**
     * Strict text extraction: node must be textual.
     * No asText() coercion from number/boolean/null.
     */
    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode()) throw new IllegalArgumentException("missing field: " + field);
        if (!v.isTextual()) throw new IllegalArgumentException("field '" + field + "' must be textual, got " + v.getNodeType());
        return v.asText();
    }

    private static JsonNode node(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode()) throw new IllegalArgumentException("missing field: " + field);
        return v;
    }

    /**
     * Strict integral long extraction: node must be integral number.
     * No asLong() coercion from text/boolean/null.
     */
    private static long lnum(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode()) throw new IllegalArgumentException("missing field: " + field);
        if (!v.isIntegralNumber()) throw new IllegalArgumentException("field '" + field + "' must be integral number, got " + v.getNodeType());
        return v.asLong();
    }

    private static String coordStr(JsonNode coordNode) {
        String g = coordNode.path("groupId").asText("");
        String a = coordNode.path("artifactId").asText("");
        String v = coordNode.path("version").asText("");
        return g + ":" + a + ":" + v;
    }

    /** Private unchecked exception that chains to the public checked exception. */
    private static final class BindingEx extends DevelopmentPredecessorBindingException {
        BindingEx(String code, Map<String,Object> args) {
            super(code, args);
        }
        BindingEx(String code, Map<String,Object> args, Throwable cause) {
            super(code, args, cause);
        }
    }

    /** Intermediate type carrying Authority-derived A1.2 facts. */
    private record AuthorityA12(String coordinate, String artifactPath) {}
}
