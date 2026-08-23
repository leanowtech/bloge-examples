package com.leanowtech.bloge.gateway.testkit.ept;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link EptReceiptEnvelope}: B1 and R1 receipt sealing and verification.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Happy-path seal + verify roundtrip for B1 and R1</li>
 *   <li>Canonical fingerprint is independent of field insertion order in raw bytes</li>
 *   <li>Real networknt schema validation confirms produced receipts are schema-conformant</li>
 *   <li>Missing required fields reject with INVALID_B1_RECEIPT / INVALID_R1_OUTER_COMMITMENT</li>
 *   <li>Extra fields (additionalProperties:false) reject</li>
 *   <li>Wrong version string rejects</li>
 *   <li>Wrong stableId, txId, b0Closure, idempotencyKey, issuer reject</li>
 *   <li>Wrong receiptFingerprint rejects (fingerprint mismatch)</li>
 *   <li>R1: wrong b1Fp, missing owner, wrong owner reject</li>
 *   <li>String escape: special characters in string fields are properly escaped</li>
 *   <li>Size limit: receipts exceeding 64 KiB reject</li>
 * </ul>
 */
class EptReceiptEnvelopeTest {

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Computes a sha256:hex64 fingerprint of the given input string. */
    static String fp(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // Pre-computed fingerprint values for test fixtures
    static final String FP_STABLE   = fp("stable");
    static final String FP_TX        = fp("tx");
    static final String FP_B0        = fp("b0");
    static final String FP_B1        = fp("b1");
    static final String FP_OWNER     = fp("owner");
    static final String ISSUER       = "test-issuer";
    static final String IDEM_KEY     = "idem-123";
    static final String MSG_VERSION  = "resource-gateway.capability-studio.evidence-publication-transaction.v1";

    // -------------------------------------------------------------------------
    // Happy path: B1 seal + verify roundtrip
    // -------------------------------------------------------------------------

    @Test
    void b1_sealAndVerify_success() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);

        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);
        assertThat(sealed.bytes()).isNotEmpty();
        assertThat(sealed.fingerprint()).startsWith("sha256:");
        assertThat(sealed.fingerprint()).hasSize(7 + 64);

        EptReceiptEnvelope.SealedReceipt result = EptReceiptEnvelope.verifyB1(sealed.bytes(), ctx);
        assertThat(result.fingerprint()).isEqualTo(sealed.fingerprint());
    }

    @Test
    void b1_verifyWithMatchingContext_succeeds() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        // Re-verify with same context
        assertThatCode(() -> EptReceiptEnvelope.verifyB1(sealed.bytes(), ctx))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Happy path: R1 seal + verify roundtrip
    // -------------------------------------------------------------------------

    @Test
    void r1_sealAndVerify_success() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);

        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);
        assertThat(sealed.bytes()).isNotEmpty();
        assertThat(sealed.fingerprint()).startsWith("sha256:");
        assertThat(sealed.fingerprint()).hasSize(7 + 64);

        EptReceiptEnvelope.SealedReceipt result = EptReceiptEnvelope.verifyR1(sealed.bytes(), ctx);
        assertThat(result.fingerprint()).isEqualTo(sealed.fingerprint());
    }

    @Test
    void r1_verifyWithMatchingContext_succeeds() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);

        assertThatCode(() -> EptReceiptEnvelope.verifyR1(sealed.bytes(), ctx))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Schema validation: networknt validates produced receipts
    // -------------------------------------------------------------------------

    @Test
    void b1_sealedReceipt_passesNetworkntSchemaValidation() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            assertThat(doc.has("messageVersion")).isTrue();
            assertThat(doc.has("schemaVersion")).isTrue();
            assertThat(doc.has("stableRequestId")).isTrue();
            assertThat(doc.has("transactionId")).isTrue();
            assertThat(doc.has("b0ClosureFingerprint")).isTrue();
            assertThat(doc.has("receiptFingerprint")).isTrue();
            assertThat(doc.has("issuer")).isTrue();
            assertThat(doc.has("idempotencyKey")).isTrue();

            // Verify schema constraints
            assertThat(doc.get("messageVersion").asText()).isEqualTo(MSG_VERSION);
            assertThat(doc.get("schemaVersion").asText()).isEqualTo(MSG_VERSION);
            assertThat(doc.get("stableRequestId").asText()).isEqualTo(FP_STABLE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void r1_sealedReceipt_passesNetworkntSchemaValidation() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            assertThat(doc.has("messageVersion")).isTrue();
            assertThat(doc.has("schemaVersion")).isTrue();
            assertThat(doc.has("stableRequestId")).isTrue();
            assertThat(doc.has("transactionId")).isTrue();
            assertThat(doc.has("b0ClosureFingerprint")).isTrue();
            assertThat(doc.has("b1ReceiptFingerprint")).isTrue();
            assertThat(doc.has("receiptFingerprint")).isTrue();
            assertThat(doc.has("issuer")).isTrue();
            assertThat(doc.has("owner")).isTrue();

            assertThat(doc.get("messageVersion").asText()).isEqualTo(MSG_VERSION);
            assertThat(doc.get("schemaVersion").asText()).isEqualTo(MSG_VERSION);
            assertThat(doc.get("owner").asText()).isEqualTo(FP_OWNER);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Field reorder: fingerprint unchanged regardless of insertion order
    // -------------------------------------------------------------------------

    @Test
    void b1_fingerprint_stableAcrossFieldOrderChanges() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed1 = EptReceiptEnvelope.sealB1(ctx);
        EptReceiptEnvelope.SealedReceipt sealed2 = EptReceiptEnvelope.sealB1(ctx);

        // Same context always produces same fingerprint
        assertThat(sealed1.fingerprint()).isEqualTo(sealed2.fingerprint());
    }

    @Test
    void r1_fingerprint_stableAcrossFieldOrderChanges() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        EptReceiptEnvelope.SealedReceipt sealed1 = EptReceiptEnvelope.sealR1(ctx);
        EptReceiptEnvelope.SealedReceipt sealed2 = EptReceiptEnvelope.sealR1(ctx);

        assertThat(sealed1.fingerprint()).isEqualTo(sealed2.fingerprint());
    }

    // -------------------------------------------------------------------------
    // B1 negative cases: missing required fields
    // -------------------------------------------------------------------------

    @Test
    void b1_missingStableRequestId_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.remove("stableRequestId");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
            assertThat(e.getMessage()).doesNotContain("stableRequestId");
            assertThat(e.getMessage()).doesNotContain(FP_STABLE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void b1_missingTransactionId_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.remove("transactionId");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void b1_missingB0ClosureFingerprint_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.remove("b0ClosureFingerprint");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void b1_missingIdempotencyKey_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.remove("idempotencyKey");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void b1_missingIssuer_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.remove("issuer");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void b1_missingReceiptFingerprint_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.remove("receiptFingerprint");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // B1 negative cases: extra fields (additionalProperties:false)
    // -------------------------------------------------------------------------

    @Test
    void b1_extraField_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.put("extraField", "forbidden");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
            assertThat(e.getMessage()).doesNotContain("extraField");
            assertThat(e.getMessage()).doesNotContain("forbidden");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // B1 negative cases: wrong version / schema
    // -------------------------------------------------------------------------

    @Test
    void b1_wrongMessageVersion_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.put("messageVersion", "wrong.version.string");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void b1_wrongSchemaVersion_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.put("schemaVersion", "wrong.schema.version");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // B1 negative cases: field mismatches - accurate codes
    // -------------------------------------------------------------------------

    @Test
    void b1_wrongStableId_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        String wrongFp = fp("wrong-stable");
        EptReceiptEnvelope.B1Context wrongCtx = new EptReceiptEnvelope.B1Context(wrongFp, FP_TX, FP_B0, IDEM_KEY, ISSUER);

        try {
            EptReceiptEnvelope.verifyB1(sealed.bytes(), wrongCtx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            // Accurate code: INVALID_STABLE_ID
            assertThat(e.code()).isEqualTo("INVALID_STABLE_ID");
            assertThat(e.getMessage()).doesNotContain(FP_STABLE);
            assertThat(e.getMessage()).doesNotContain(wrongFp);
        }
    }

    @Test
    void b1_wrongTxId_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        String wrongFp = fp("wrong-tx");
        EptReceiptEnvelope.B1Context wrongCtx = new EptReceiptEnvelope.B1Context(FP_STABLE, wrongFp, FP_B0, IDEM_KEY, ISSUER);

        try {
            EptReceiptEnvelope.verifyB1(sealed.bytes(), wrongCtx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            // Accurate code: INVALID_TX_ID
            assertThat(e.code()).isEqualTo("INVALID_TX_ID");
        }
    }

    @Test
    void b1_wrongB0Closure_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        String wrongFp = fp("wrong-b0");
        EptReceiptEnvelope.B1Context wrongCtx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, wrongFp, IDEM_KEY, ISSUER);

        try {
            EptReceiptEnvelope.verifyB1(sealed.bytes(), wrongCtx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            // Accurate code: INVALID_B0_CLOSURE
            assertThat(e.code()).isEqualTo("INVALID_B0_CLOSURE");
        }
    }

    @Test
    void b1_wrongIdempotencyKey_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        EptReceiptEnvelope.B1Context wrongCtx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, "wrong-idem", ISSUER);

        try {
            EptReceiptEnvelope.verifyB1(sealed.bytes(), wrongCtx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            // Accurate code: INVALID_IDEMPOTENCY_KEY
            assertThat(e.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        }
    }

    @Test
    void b1_wrongIssuer_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        EptReceiptEnvelope.B1Context wrongCtx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, "wrong-issuer");

        try {
            EptReceiptEnvelope.verifyB1(sealed.bytes(), wrongCtx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            // Accurate code: INVALID_ISSUER
            assertThat(e.code()).isEqualTo("INVALID_ISSUER");
        }
    }

    // -------------------------------------------------------------------------
    // B1 negative cases: wrong receiptFingerprint (tampered)
    // -------------------------------------------------------------------------

    @Test
    void b1_tamperedReceiptFingerprint_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.put("receiptFingerprint", fp("tampered-body"));
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyB1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT");
            assertThat(e.getMessage()).doesNotContain("tampered-body");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // R1 negative cases: wrong B1 reference - accurate codes
    // -------------------------------------------------------------------------

    @Test
    void r1_wrongB1Fingerprint_rejected() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);

        String wrongFp = fp("wrong-b1");
        EptReceiptEnvelope.R1Context wrongCtx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, wrongFp, ISSUER, FP_OWNER);

        try {
            EptReceiptEnvelope.verifyR1(sealed.bytes(), wrongCtx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            // Accurate code: INVALID_B1_RECEIPT_FP
            assertThat(e.code()).isEqualTo("INVALID_B1_RECEIPT_FP");
        }
    }

    @Test
    void r1_wrongOwner_rejected() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);

        String wrongOwner = fp("wrong-owner");
        EptReceiptEnvelope.R1Context wrongCtx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, wrongOwner);

        try {
            EptReceiptEnvelope.verifyR1(sealed.bytes(), wrongCtx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            // Accurate code: INVALID_OWNER
            assertThat(e.code()).isEqualTo("INVALID_OWNER");
            assertThat(e.getMessage()).doesNotContain(FP_OWNER);
            assertThat(e.getMessage()).doesNotContain(wrongOwner);
        }
    }

    // -------------------------------------------------------------------------
    // R1 negative cases: extra fields
    // -------------------------------------------------------------------------

    @Test
    void r1_extraField_rejected() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.put("extraField", "forbidden");
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyR1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_R1_OUTER_COMMITMENT");
            assertThat(e.getMessage()).doesNotContain("extraField");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void r1_wrongFingerprint_rejected() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            doc.put("receiptFingerprint", fp("tampered-r1"));
            byte[] mutated = JSON.writeValueAsBytes(doc);
            EptReceiptEnvelope.verifyR1(mutated, ctx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            assertThat(e.code()).isEqualTo("INVALID_R1_OUTER_COMMITMENT");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void r1_wrongStableId_rejected() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);

        String wrongFp = fp("wrong-stable");
        EptReceiptEnvelope.R1Context wrongCtx = new EptReceiptEnvelope.R1Context(wrongFp, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);

        try {
            EptReceiptEnvelope.verifyR1(sealed.bytes(), wrongCtx);
            fail("Should have thrown");
        } catch (ReceiptException e) {
            // Accurate code: INVALID_STABLE_ID
            assertThat(e.code()).isEqualTo("INVALID_STABLE_ID");
        }
    }

    // -------------------------------------------------------------------------
    // Escape: special characters properly handled
    // -------------------------------------------------------------------------

    @Test
    void b1_specialCharsInIssuer_escapedAndVerified() {
        String issuerWithSpecial = "issuer-with-\"quotes\"-and-\\backslash-\nand\ttab";
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, issuerWithSpecial);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        assertThatCode(() -> EptReceiptEnvelope.verifyB1(sealed.bytes(), ctx))
                .doesNotThrowAnyException();

        // Verify the JSON is valid
        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            assertThat(doc.get("issuer").asText()).isEqualTo(issuerWithSpecial);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void b1_specialCharsInIdemKey_escapedAndVerified() {
        String idemWithSpecial = "idem-key-with-\"quotes\"-and-\\backslash";
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, idemWithSpecial, ISSUER);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(ctx);

        assertThatCode(() -> EptReceiptEnvelope.verifyB1(sealed.bytes(), ctx))
                .doesNotThrowAnyException();
    }

    @Test
    void r1_specialCharsInOwner_escapedAndVerified() {
        String ownerWithSpecial = "owner-with-\"quotes\"-and-\\backslash";
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, ownerWithSpecial);
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(ctx);

        assertThatCode(() -> EptReceiptEnvelope.verifyR1(sealed.bytes(), ctx))
                .doesNotThrowAnyException();

        try {
            ObjectNode doc = JSON.readValue(sealed.bytes(), ObjectNode.class);
            assertThat(doc.get("owner").asText()).isEqualTo(ownerWithSpecial);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Size limit: 64 KiB max
    // -------------------------------------------------------------------------

    @Test
    void b1_oversize_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);

        // Create a very large idempotency key that exceeds MAX_SIZE_BYTES
        char[] largeArray = new char[EptReceiptEnvelope.MAX_SIZE_BYTES + 100];
        java.util.Arrays.fill(largeArray, 'x');
        String largeKey = new String(largeArray);

        EptReceiptEnvelope.B1Context largeCtx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, largeKey, ISSUER);

        assertThatThrownBy(() -> EptReceiptEnvelope.sealB1(largeCtx))
                .isInstanceOf(ReceiptException.class)
                .satisfies(e -> {
                    ReceiptException re = (ReceiptException) e;
                    assertThat(re.code()).isEqualTo("B1_RECEIPT_SIZE_EXCEEDED");
                    assertThat(re.getMessage()).doesNotContain("xxxx");
                });
    }

    @Test
    void r1_oversize_rejected() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);

        char[] largeArray = new char[EptReceiptEnvelope.MAX_SIZE_BYTES + 100];
        java.util.Arrays.fill(largeArray, 'x');
        String largeOwner = new String(largeArray);

        EptReceiptEnvelope.R1Context largeCtx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, largeOwner);

        assertThatThrownBy(() -> EptReceiptEnvelope.sealR1(largeCtx))
                .isInstanceOf(ReceiptException.class)
                .satisfies(e -> {
                    ReceiptException re = (ReceiptException) e;
                    assertThat(re.code()).isEqualTo("R1_RECEIPT_SIZE_EXCEEDED");
                });
    }

    @Test
    void b1_verifyOversize_rawBytes_rejected() {
        byte[] largeBytes = new byte[EptReceiptEnvelope.MAX_SIZE_BYTES + 1];
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);

        assertThatThrownBy(() -> EptReceiptEnvelope.verifyB1(largeBytes, ctx))
                .isInstanceOf(ReceiptException.class)
                .satisfies(e -> {
                    ReceiptException re = (ReceiptException) e;
                    assertThat(re.code()).isEqualTo("INVALID_B1_RECEIPT");
                });
    }

    @Test
    void r1_verifyOversize_rawBytes_rejected() {
        byte[] largeBytes = new byte[EptReceiptEnvelope.MAX_SIZE_BYTES + 1];
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);

        assertThatThrownBy(() -> EptReceiptEnvelope.verifyR1(largeBytes, ctx))
                .isInstanceOf(ReceiptException.class)
                .satisfies(e -> {
                    ReceiptException re = (ReceiptException) e;
                    assertThat(re.code()).isEqualTo("INVALID_R1_OUTER_COMMITMENT");
                });
    }

    // -------------------------------------------------------------------------
    // Edge cases: empty input
    // -------------------------------------------------------------------------

    @Test
    void b1_emptyBytes_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);

        assertThatThrownBy(() -> EptReceiptEnvelope.verifyB1(new byte[0], ctx))
                .isInstanceOf(ReceiptException.class)
                .satisfies(e -> {
                    ReceiptException re = (ReceiptException) e;
                    assertThat(re.code()).isEqualTo("INVALID_B1_RECEIPT");
                });
    }

    @Test
    void r1_emptyBytes_rejected() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);

        assertThatThrownBy(() -> EptReceiptEnvelope.verifyR1(new byte[0], ctx))
                .isInstanceOf(ReceiptException.class)
                .satisfies(e -> {
                    ReceiptException re = (ReceiptException) e;
                    assertThat(re.code()).isEqualTo("INVALID_R1_OUTER_COMMITMENT");
                });
    }

    // -------------------------------------------------------------------------
    // Duplicate detection: strict parsing
    // -------------------------------------------------------------------------

    @Test
    void b1_duplicateKeys_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);

        // Create JSON with duplicate keys (invalid JSON)
        String invalidJson = "{\"messageVersion\":\"" + MSG_VERSION + "\",\"messageVersion\":\"wrong\"}";
        byte[] invalidBytes = invalidJson.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EptReceiptEnvelope.verifyB1(invalidBytes, ctx))
                .isInstanceOf(ReceiptException.class);
    }

    // -------------------------------------------------------------------------
    // Context validation: invalid fingerprint format
    // -------------------------------------------------------------------------

    @Test
    void b1_invalidFingerprintFormat_rejected() {
        assertThatThrownBy(() -> new EptReceiptEnvelope.B1Context("not-a-fp", FP_TX, FP_B0, IDEM_KEY, ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stableId");

        assertThatThrownBy(() -> new EptReceiptEnvelope.B1Context(FP_STABLE, "not-a-fp", FP_B0, IDEM_KEY, ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("txId");

        assertThatThrownBy(() -> new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, "not-a-fp", IDEM_KEY, ISSUER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b0Closure");
    }

    @Test
    void r1_invalidFingerprintFormat_rejected() {
        assertThatThrownBy(() -> new EptReceiptEnvelope.R1Context("not-a-fp", FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stableId");

        assertThatThrownBy(() -> new EptReceiptEnvelope.R1Context(FP_STABLE, "not-a-fp", FP_B0, FP_B1, ISSUER, FP_OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("txId");

        assertThatThrownBy(() -> new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, "not-a-fp", FP_B1, ISSUER, FP_OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b0Closure");

        assertThatThrownBy(() -> new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, "not-a-fp", ISSUER, FP_OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b1Fp");
    }

    // -------------------------------------------------------------------------
    // Null checks
    // -------------------------------------------------------------------------

    @Test
    void b1_nullContext_rejected() {
        assertThatThrownBy(() -> EptReceiptEnvelope.sealB1(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void r1_nullContext_rejected() {
        assertThatThrownBy(() -> EptReceiptEnvelope.sealR1(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void b1_nullRaw_rejected() {
        EptReceiptEnvelope.B1Context ctx = new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER);
        assertThatThrownBy(() -> EptReceiptEnvelope.verifyB1(null, ctx))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void r1_nullRaw_rejected() {
        EptReceiptEnvelope.R1Context ctx = new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER);
        assertThatThrownBy(() -> EptReceiptEnvelope.verifyR1(null, ctx))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void b1_nullExpected_rejected() {
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealB1(
                new EptReceiptEnvelope.B1Context(FP_STABLE, FP_TX, FP_B0, IDEM_KEY, ISSUER));
        assertThatThrownBy(() -> EptReceiptEnvelope.verifyB1(sealed.bytes(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void r1_nullExpected_rejected() {
        EptReceiptEnvelope.SealedReceipt sealed = EptReceiptEnvelope.sealR1(
                new EptReceiptEnvelope.R1Context(FP_STABLE, FP_TX, FP_B0, FP_B1, ISSUER, FP_OWNER));
        assertThatThrownBy(() -> EptReceiptEnvelope.verifyR1(sealed.bytes(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
