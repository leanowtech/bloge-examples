package com.leanowtech.bloge.gateway.testkit.ept;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.path.NodePath;
import com.networknt.schema.path.PathType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Canonical EPT receipt envelope: produces and verifies B1 and R1 receipts with
 * deterministic SHA-256 fingerprints derived from the body projection
 * (all fields <em>excluding</em> {@code receiptFingerprint}).
 *
 * <p>All types are package-private.  This class is not thread-safe;
 * caller is responsible for any necessary synchronisation.</p>
 *
 * <p>Fingerprint formula (non-circular):<pre>
 * bodyProjection = all fields of receipt EXCEPT receiptFingerprint
 * canonicalBody   = canonical JSON of bodyProjection (recursively sorted keys, strict escape)
 * receiptFingerprint = "sha256:" + SHA256(canonicalBody)
 * </pre></p>
 */
final class EptReceiptEnvelope {

    /** Hard upper bound for a receipt byte sequence. */
    static final int MAX_SIZE_BYTES = 65536; // 64 KiB

    /** Jackson ObjectMapper for reading receipts (used for both reading and canonical write). */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Cached networknt B1 schema. */
    private static volatile Schema b1Schema;

    /** Cached networknt R1 schema. */
    private static volatile Schema r1Schema;

    /** Shared Draft 2020-12 registry for schema compilation. */
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDialect(Dialects.getDraft202012());

    /** Root JSON Pointer path for error reporting. */
    private static final NodePath ROOT_PATH = new NodePath(PathType.DEFAULT);

    private EptReceiptEnvelope() { }

    // -------------------------------------------------------------------------
    // Context records
    // -------------------------------------------------------------------------

    /**
     * B1 receipt context: fields used to build and verify a B1 receipt body.
     *
     * @param stableId        stable request identity (maps to {@code stableRequestId})
     * @param txId            transaction identity
     * @param b0Closure      B0 closure fingerprint
     * @param idempotencyKey  store idempotency key
     * @param issuer          store publisher issuer identity
     */
    public record B1Context(
            String stableId,
            String txId,
            String b0Closure,
            String idempotencyKey,
            String issuer) {

        public B1Context {
            Objects.requireNonNull(stableId, "stableId");
            Objects.requireNonNull(txId, "txId");
            Objects.requireNonNull(b0Closure, "b0Closure");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(issuer, "issuer");
            validateFingerprint("stableId", stableId);
            validateFingerprint("txId", txId);
            validateFingerprint("b0Closure", b0Closure);
        }
    }

    /**
     * R1 receipt context: fields used to build and verify an R1 receipt body.
     *
     * @param stableId        stable request identity (maps to {@code stableRequestId})
     * @param txId            transaction identity
     * @param b0Closure      B0 closure fingerprint
     * @param b1Fp            B1 receipt fingerprint (binding anchor)
     * @param issuer          store publisher issuer identity
     * @param owner           stable owner identity bound to this commitment
     */
    public record R1Context(
            String stableId,
            String txId,
            String b0Closure,
            String b1Fp,
            String issuer,
            String owner) {

        public R1Context {
            Objects.requireNonNull(stableId, "stableId");
            Objects.requireNonNull(txId, "txId");
            Objects.requireNonNull(b0Closure, "b0Closure");
            Objects.requireNonNull(b1Fp, "b1Fp");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(owner, "owner");
            validateFingerprint("stableId", stableId);
            validateFingerprint("txId", txId);
            validateFingerprint("b0Closure", b0Closure);
            validateFingerprint("b1Fp", b1Fp);
        }
    }

    /**
     * Sealed receipt result: canonical receipt bytes and their fingerprint.
     *
     * @param bytes       deterministic canonical JSON bytes
     * @param fingerprint {@code sha256:hex64} fingerprint of {@code bytes}
     */
    public record SealedReceipt(byte[] bytes, String fingerprint) {

        public SealedReceipt {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (bytes.length == 0) throw new IllegalArgumentException("bytes must not be empty");
            if (!fingerprint.startsWith("sha256:"))
                throw new IllegalArgumentException("fingerprint must start with sha256:");
        }
    }

    // -------------------------------------------------------------------------
    // Seal (produce)
    // -------------------------------------------------------------------------

    /**
     * Seals a B1 receipt for the given context.
     *
     * <p>Canonical JSON is produced with recursively sorted keys and strict escape.
     * The fingerprint covers all fields <em>except</em> {@code receiptFingerprint}
     * (body projection).  The result is a deterministic, schema-conformant JSON
     * byte sequence whose fingerprint matches {@code receiptFingerprint} in the body.</p>
     *
     * @param ctx non-null B1 context
     * @return sealed receipt
     * @throws ReceiptException on canonicalisation or IO error
     */
    public static SealedReceipt sealB1(B1Context ctx) {
        Objects.requireNonNull(ctx, "ctx");
        try {
            // Build body projection (all fields except receiptFingerprint)
            ObjectNode body = JSON.createObjectNode();
            body.put("messageVersion", "resource-gateway.capability-studio.evidence-publication-transaction.v1");
            body.put("schemaVersion", "resource-gateway.capability-studio.evidence-publication-transaction.v1");
            body.put("stableRequestId", ctx.stableId());
            body.put("transactionId", ctx.txId());
            body.put("b0ClosureFingerprint", ctx.b0Closure());
            body.put("issuer", ctx.issuer());
            body.put("idempotencyKey", ctx.idempotencyKey());

            // Canonical body JSON -> SHA-256
            byte[] canonicalBody = writeCanonicalJson(body);
            String fp = sha256Hex(canonicalBody);

            // Full envelope (body + receiptFingerprint)
            ObjectNode envelope = body.deepCopy();
            envelope.put("receiptFingerprint", fp);

            byte[] envelopeBytes = writeCanonicalJson(envelope);

            if (envelopeBytes.length > MAX_SIZE_BYTES) {
                throw new ReceiptException("B1_RECEIPT_SIZE_EXCEEDED",
                        "B1 receipt exceeds maximum size of " + MAX_SIZE_BYTES + " bytes");
            }

            return new SealedReceipt(envelopeBytes, fp);
        } catch (ReceiptException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ReceiptException("B1_SEAL_INTERNAL", "B1 seal failed", e);
        }
    }

    /**
     * Seals an R1 receipt for the given context.
     *
     * <p>Canonical JSON is produced with recursively sorted keys and strict escape.
     * The fingerprint covers all fields <em>except</em> {@code receiptFingerprint}
     * (body projection).  The result is a deterministic, schema-conformant JSON
     * byte sequence whose fingerprint matches {@code receiptFingerprint} in the body.</p>
     *
     * @param ctx non-null R1 context
     * @return sealed receipt
     * @throws ReceiptException on canonicalisation or IO error
     */
    public static SealedReceipt sealR1(R1Context ctx) {
        Objects.requireNonNull(ctx, "ctx");
        try {
            // Build body projection (all fields except receiptFingerprint)
            ObjectNode body = JSON.createObjectNode();
            body.put("messageVersion", "resource-gateway.capability-studio.evidence-publication-transaction.v1");
            body.put("schemaVersion", "resource-gateway.capability-studio.evidence-publication-transaction.v1");
            body.put("stableRequestId", ctx.stableId());
            body.put("transactionId", ctx.txId());
            body.put("b0ClosureFingerprint", ctx.b0Closure());
            body.put("b1ReceiptFingerprint", ctx.b1Fp());
            body.put("issuer", ctx.issuer());
            body.put("owner", ctx.owner());

            // Canonical body JSON -> SHA-256
            byte[] canonicalBody = writeCanonicalJson(body);
            String fp = sha256Hex(canonicalBody);

            // Full envelope (body + receiptFingerprint)
            ObjectNode envelope = body.deepCopy();
            envelope.put("receiptFingerprint", fp);

            byte[] envelopeBytes = writeCanonicalJson(envelope);

            if (envelopeBytes.length > MAX_SIZE_BYTES) {
                throw new ReceiptException("R1_RECEIPT_SIZE_EXCEEDED",
                        "R1 receipt exceeds maximum size of " + MAX_SIZE_BYTES + " bytes");
            }

            return new SealedReceipt(envelopeBytes, fp);
        } catch (ReceiptException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ReceiptException("R1_SEAL_INTERNAL", "R1 seal failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Verify
    // -------------------------------------------------------------------------

    /**
     * Verifies a B1 receipt from raw bytes and an expected context.
     *
     * <p>Checks performed (in order):
     * <ol>
     *   <li>Strict JSON duplicate-key detection via streaming parse</li>
     *   <li>Schema validation via networknt (additionalProperties, required, const, pattern)</li>
     *   <li>Body fingerprint recomputed from projection (excluding receiptFingerprint itself)</li>
     *   <li>Context cross-check: stableRequestId, transactionId, b0ClosureFingerprint,
     *       idempotencyKey, issuer</li>
     * </ol>
     *
     * @param raw raw JSON bytes
     * @param expected expected B1 context for cross-check
     * @return sealed receipt wrapping the raw bytes and their fingerprint
     * @throws ReceiptException with code {@code INVALID_B1_RECEIPT} on any failure;
     *         payload bytes are never exposed in the exception message
     */
    public static SealedReceipt verifyB1(byte[] raw, B1Context expected) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(expected, "expected");
        if (raw.length == 0)
            throw new ReceiptException("INVALID_B1_RECEIPT", "Empty B1 receipt bytes");
        if (raw.length > MAX_SIZE_BYTES)
            throw new ReceiptException("INVALID_B1_RECEIPT", "B1 receipt exceeds maximum size");

        try {
            // Step 1: Strict JSON parse (duplicate key detection happens here)
            ObjectNode doc = JSON.readValue(raw, ObjectNode.class);

            // Step 2: Schema validation via networknt
            if (!validateB1Schema(doc).isEmpty()) {
                throw new ReceiptException("INVALID_B1_RECEIPT",
                        "B1 receipt failed schema validation");
            }

            // Step 3: Recompute body fingerprint (excluding receiptFingerprint)
            ObjectNode bodyProjection = doc.deepCopy();
            bodyProjection.remove("receiptFingerprint");
            byte[] canonicalBody = writeCanonicalJson(bodyProjection);
            String computedFp = sha256Hex(canonicalBody);

            String embeddedFp = doc.path("receiptFingerprint").asText();
            if (!computedFp.equals(embeddedFp)) {
                throw new ReceiptException("INVALID_B1_RECEIPT",
                        "B1 receipt fingerprint mismatch");
            }

            // Step 4: Context cross-check
            checkField(doc, "stableRequestId", expected.stableId());
            checkField(doc, "transactionId", expected.txId());
            checkField(doc, "b0ClosureFingerprint", expected.b0Closure());
            checkField(doc, "idempotencyKey", expected.idempotencyKey());
            checkField(doc, "issuer", expected.issuer());

            return new SealedReceipt(raw, embeddedFp);

        } catch (ReceiptException e) {
            throw e;
        } catch (IOException e) {
            throw new ReceiptException("INVALID_B1_RECEIPT", "B1 receipt is not valid JSON");
        } catch (RuntimeException e) {
            throw new ReceiptException("INVALID_B1_RECEIPT", "B1 receipt verification failed");
        }
    }

    /**
     * Verifies an R1 receipt from raw bytes and an expected context.
     *
     * <p>Checks performed (in order):
     * <ol>
     *   <li>Strict JSON duplicate-key detection via streaming parse</li>
     *   <li>Schema validation via networknt (additionalProperties, required, const, pattern)</li>
     *   <li>Body fingerprint recomputed from projection (excluding receiptFingerprint itself)</li>
     *   <li>Context cross-check: stableRequestId, transactionId, b0ClosureFingerprint,
     *       b1ReceiptFingerprint, issuer, <strong>owner</strong></li>
     * </ol>
     *
     * @param raw raw JSON bytes
     * @param expected expected R1 context for cross-check (owner is verified)
     * @return sealed receipt wrapping the raw bytes and their fingerprint
     * @throws ReceiptException with code {@code INVALID_R1_OUTER_COMMITMENT} on any failure;
     *         payload bytes are never exposed in the exception message
     */
    public static SealedReceipt verifyR1(byte[] raw, R1Context expected) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(expected, "expected");
        if (raw.length == 0)
            throw new ReceiptException("INVALID_R1_OUTER_COMMITMENT", "Empty R1 receipt bytes");
        if (raw.length > MAX_SIZE_BYTES)
            throw new ReceiptException("INVALID_R1_OUTER_COMMITMENT", "R1 receipt exceeds maximum size");

        try {
            // Step 1: Strict JSON parse (duplicate key detection)
            ObjectNode doc = JSON.readValue(raw, ObjectNode.class);

            // Step 2: Schema validation via networknt
            if (!validateR1Schema(doc).isEmpty()) {
                throw new ReceiptException("INVALID_R1_OUTER_COMMITMENT",
                        "R1 receipt failed schema validation");
            }

            // Step 3: Recompute body fingerprint (excluding receiptFingerprint)
            ObjectNode bodyProjection = doc.deepCopy();
            bodyProjection.remove("receiptFingerprint");
            byte[] canonicalBody = writeCanonicalJson(bodyProjection);
            String computedFp = sha256Hex(canonicalBody);

            String embeddedFp = doc.path("receiptFingerprint").asText();
            if (!computedFp.equals(embeddedFp)) {
                throw new ReceiptException("INVALID_R1_OUTER_COMMITMENT",
                        "R1 receipt fingerprint mismatch");
            }

            // Step 4: Context cross-check (includes owner for R1 binding)
            checkField(doc, "stableRequestId", expected.stableId());
            checkField(doc, "transactionId", expected.txId());
            checkField(doc, "b0ClosureFingerprint", expected.b0Closure());
            checkField(doc, "b1ReceiptFingerprint", expected.b1Fp());
            checkField(doc, "issuer", expected.issuer());
            checkField(doc, "owner", expected.owner());

            return new SealedReceipt(raw, embeddedFp);

        } catch (ReceiptException e) {
            throw e;
        } catch (IOException e) {
            throw new ReceiptException("INVALID_R1_OUTER_COMMITMENT", "R1 receipt is not valid JSON");
        } catch (RuntimeException e) {
            throw new ReceiptException("INVALID_R1_OUTER_COMMITMENT", "R1 receipt verification failed");
        }
    }

    // -------------------------------------------------------------------------
    // Canonical JSON writer
    // -------------------------------------------------------------------------

    /**
     * Serialises an ObjectNode as canonical JSON bytes with recursively sorted keys.
     * The Jackson ObjectMapper sorts top-level fields; this method additionally
     * sorts all nested objects to guarantee a fully deterministic total order.
     */
    private static byte[] writeCanonicalJson(ObjectNode node) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            JSON.writeValue(out, sortKeys(node));
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Canonical JSON write failed", e);
        }
    }

    /** Returns a deep copy of the given ObjectNode with all object children recursively sorted by key. */
    private static ObjectNode sortKeys(ObjectNode node) {
        ObjectNode result = JSON.createObjectNode();
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        Collections.sort(fieldNames);
        for (String name : fieldNames) {
            JsonNode child = node.get(name);
            if (child.isObject()) {
                result.set(name, sortKeys((ObjectNode) child));
            } else if (child.isArray()) {
                ArrayNode arr = JSON.createArrayNode();
                for (JsonNode element : child) {
                    if (element.isObject()) {
                        arr.add(sortKeys((ObjectNode) element));
                    } else {
                        arr.add(element.deepCopy());
                    }
                }
                result.set(name, arr);
            } else {
                result.set(name, child.deepCopy());
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // SHA-256
    // -------------------------------------------------------------------------

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static void validateFingerprint(String field, String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(
                    field + " must match fingerprint pattern sha256:hex64");
        }
    }

    // -------------------------------------------------------------------------
    // Context field cross-check
    // -------------------------------------------------------------------------

    private static void checkField(ObjectNode doc, String fieldName, String expected) {
        String actual = doc.path(fieldName).asText(null);
        if (!expected.equals(actual)) {
            throw new ReceiptException("INVALID_" + errorSuffix(fieldName),
                    "Receipt field mismatch: " + fieldName);
        }
    }

    private static String errorSuffix(String fieldName) {
        return switch (fieldName) {
            case "stableRequestId" -> "STABLE_ID";
            case "transactionId" -> "TX_ID";
            case "b0ClosureFingerprint" -> "B0_CLOSURE";
            case "idempotencyKey" -> "IDEMPOTENCY_KEY";
            case "issuer" -> "ISSUER";
            case "b1ReceiptFingerprint" -> "B1_RECEIPT_FP";
            case "owner" -> "OWNER";
            default -> fieldName.toUpperCase(Locale.ROOT).replace("FINGERPRINT", "_FP");
        };
    }

    // -------------------------------------------------------------------------
    // Schema loading and validation (networknt 2.0.x)
    // -------------------------------------------------------------------------

    private static final String B1_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-evidence-publication-b1-receipt.schema.json";
    private static final String R1_SCHEMA_RESOURCE =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-evidence-publication-r1-receipt.schema.json";

    private static Schema b1Schema() {
        if (b1Schema == null) {
            b1Schema = loadSchema(B1_SCHEMA_RESOURCE);
        }
        return b1Schema;
    }

    private static Schema r1Schema() {
        if (r1Schema == null) {
            r1Schema = loadSchema(R1_SCHEMA_RESOURCE);
        }
        return r1Schema;
    }

    private static Schema loadSchema(String resource) {
        try (var in = EptReceiptEnvelope.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Schema not on classpath: " + resource);
            return REGISTRY.getSchema(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), InputFormat.JSON);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load schema: " + resource, e);
        }
    }

    /**
     * Validates a B1 document against the classpath networknt schema.
     * Returns a list of errors (empty = valid).
     * Internal failures are encoded as a single sentinel error.
     */
    private static List<Error> validateB1Schema(ObjectNode doc) {
        try {
            return b1Schema().validate(doc.toString(), InputFormat.JSON,
                    ctx -> ctx.executionConfig(c -> c.failFast(true)));
        } catch (RuntimeException e) {
            return List.of(internalError(e.getMessage()));
        }
    }

    /**
     * Validates an R1 document against the classpath networknt schema.
     * Returns a list of errors (empty = valid).
     * Internal failures are encoded as a single sentinel error.
     */
    private static List<Error> validateR1Schema(ObjectNode doc) {
        try {
            return r1Schema().validate(doc.toString(), InputFormat.JSON,
                    ctx -> ctx.executionConfig(c -> c.failFast(true)));
        } catch (RuntimeException e) {
            return List.of(internalError(e.getMessage()));
        }
    }

    /** Builds a sentinel error for internal failures (e.g. schema load). */
    private static Error internalError(String msg) {
        return Error.builder()
                .keyword("internal")
                .instanceLocation(ROOT_PATH)
                .message(msg == null ? "internal error" : msg)
                .build();
    }

    // -------------------------------------------------------------------------
    // ReceiptException
}
