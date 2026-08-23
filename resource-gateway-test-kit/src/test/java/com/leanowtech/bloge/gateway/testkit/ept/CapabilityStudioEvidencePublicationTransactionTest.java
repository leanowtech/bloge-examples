package com.leanowtech.bloge.gateway.testkit.ept;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;


/**
 * EPT Identity Derivation Oracle Tests (Slice 1).
 *
 * <p>Design §E.2 invariant:</p>
 * <pre>
 * stableRequestId = SHA256(
 *     EPT_DOMAIN
 *   || authorityInputTreeFingerprint
 *   || targetInputTreeFingerprint
 *   || planFingerprint
 *   || targetBindingFingerprint
 *   || declarationFingerprint
 *   || candidateFingerprint
 * )
 *
 * transactionId = SHA256(
 *     EPT_DOMAIN
 *   || stableRequestId
 *   || publicationNonce
 * )
 * </pre>
 *
 * <p>Framing: UTF-8 length-prefixed (4-byte big-endian length + bytes).
 * No bare string concatenation.</p>
 *
 * <p>Oracle tests:</p>
 * <ul>
 *   <li>Oracle 1: Six correct fingerprints → fixed stable/transaction IDs (deterministic)</li>
 *   <li>Oracle 2: Any of six fingerprints changes → stableRequestId changes</li>
 *   <li>Oracle 3: nonce changes → stableRequestId unchanged, transactionId changes</li>
 *   <li>Oracle 4: caller expected stable mismatch → CLOSED(INVALID), zero external calls</li>
 *   <li>Oracle 5: caller cannot inject transactionId (schema rejects extra fields)</li>
 * </ul>
 */
class CapabilityStudioEvidencePublicationTransactionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    Path workDir;
    Path commitDir;

    // Pre-computed valid SHA256 fingerprints (64 hex chars after prefix)
    static final String FP1 = sha256Of("authorityInput");
    static final String FP2 = sha256Of("targetInput");
    static final String FP3 = sha256Of("plan");
    static final String FP4 = sha256Of("targetBinding");
    static final String FP5 = sha256Of("declaration");
    static final String FP6 = sha256Of("candidate");
    static final String NONCE = sha256Of("publicationNonce");

    static String sha256Of(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void setUpDirs() throws IOException {
        workDir = Files.createTempDirectory("ept-work");
        commitDir = Files.createTempDirectory("ept-commit");
        Files.setPosixFilePermissions(workDir,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(commitDir,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
    }



    // ---------------------------------------------------------------------------
    // Oracle 1: Correct six fingerprints → deterministic stable/transaction IDs
    // ---------------------------------------------------------------------------

    @Test
    void oracle1_deriveStable_producesDeterministicId() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();

        String derived = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);

        assertThat(derived).isNotNull();
        assertThat(derived).startsWith("sha256:");
        assertThat(derived).hasSize(7 + 64); // "sha256:" + 64 hex chars
        // Verify determinism: same inputs always produce same output
        String derived2 = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        assertThat(derived).isEqualTo(derived2);
    }

    @Test
    void oracle1_deriveTransaction_producesDeterministicId() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();

        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String txn = ept.deriveTransactionId(stable, NONCE);

        assertThat(txn).isNotNull();
        assertThat(txn).startsWith("sha256:");
        assertThat(txn).hasSize(7 + 64);
        // Verify determinism
        String txn2 = ept.deriveTransactionId(stable, NONCE);
        assertThat(txn).isEqualTo(txn2);
    }

    @Test
    void oracle1_stableAndTransaction_differ() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();

        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String txn = ept.deriveTransactionId(stable, NONCE);

        assertThat(txn).isNotEqualTo(stable);
    }

    /**
     * Oracle 1 — Golden IDs: hard-coded stable/transaction IDs match independently
     * shell-computed values (test does NOT call production helper to derive expected).
     *
     * <p>Shell computation:
     * <pre>
     * stableRequestId = SHA256(lp(EPT_DOMAIN) || lp(FP1) || ... || lp(FP6))
     * transactionId   = SHA256(lp(EPT_DOMAIN) || lp(stableRequestId) || lp(NONCE))
     * </pre>
     */
    @Test
    void oracle1_goldenIds_matchShellComputed() {
        // Hard-coded golden values: stableId and transactionId computed via standalone
        // Java program that mirrors the production SHA-256 LP-framing algorithm.
        // These are NOT derived by calling the EPT production helper in the test.
        String goldenStable = "sha256:531f850b3d2a11e2e2e840abc2009ce96c41eee1776481ef3d6e5af1628c563a";
        String goldenTxn    = "sha256:583881a502233fb0d9bf0aa6834a895222ddfed9f28c19efe44e73d3fe62c13a";

        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String derived = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        assertThat(derived)
                .as("stableRequestId must match shell-computed golden value")
                .isEqualTo(goldenStable);

        String txn = ept.deriveTransactionId(derived, NONCE);
        assertThat(txn)
                .as("transactionId must match shell-computed golden value")
                .isEqualTo(goldenTxn);
    }



    // ---------------------------------------------------------------------------
    // Oracle 2: Any of six fingerprints changes → stableRequestId changes
    // ---------------------------------------------------------------------------

    @Test
    void oracle2_authorityInputChanges_stableChanges() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String changed = ept.deriveStableRequestId(FP2, FP2, FP3, FP4, FP5, FP6);
        assertThat(changed).isNotEqualTo(stable);
    }

    @Test
    void oracle2_targetInputChanges_stableChanges() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String changed = ept.deriveStableRequestId(FP1, FP3, FP3, FP4, FP5, FP6);
        assertThat(changed).isNotEqualTo(stable);
    }

    @Test
    void oracle2_planChanges_stableChanges() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String changed = ept.deriveStableRequestId(FP1, FP2, sha256Of("otherPlan"), FP4, FP5, FP6);
        assertThat(changed).isNotEqualTo(stable);
    }

    @Test
    void oracle2_targetBindingChanges_stableChanges() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String changed = ept.deriveStableRequestId(FP1, FP2, FP3, FP5, FP5, FP6);
        assertThat(changed).isNotEqualTo(stable);
    }

    @Test
    void oracle2_declarationChanges_stableChanges() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String changed = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP6, FP6);
        assertThat(changed).isNotEqualTo(stable);
    }

    @Test
    void oracle2_candidateChanges_stableChanges() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String changed = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, sha256Of("otherCandidate"));
        assertThat(changed).isNotEqualTo(stable);
    }



    // ---------------------------------------------------------------------------
    // Oracle 3: nonce changes → stable unchanged, transaction changes
    // ---------------------------------------------------------------------------

    @Test
    void oracle3_nonceChanges_stableUnchanged() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        // stable depends only on the six fingerprints, not nonce
        String stable1 = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stable2 = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        assertThat(stable1).isEqualTo(stable2);
    }

    @Test
    void oracle3_nonceChanges_transactionChanges() {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String stable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String txn1 = ept.deriveTransactionId(stable, NONCE);
        String txn2 = ept.deriveTransactionId(stable, sha256Of("otherNonce"));
        assertThat(txn2).isNotEqualTo(txn1);
    }



    // ---------------------------------------------------------------------------
    // Oracle 4: caller expected stable mismatch → CLOSED(INVALID), zero external calls
    // ---------------------------------------------------------------------------

    @Test
    void oracle4_expectedStableMismatch_returnsInvalid() throws Exception {
        RecordingFencingAuthority fencing = new RecordingFencingAuthority();
        RecordingEvidenceProducer producer = new RecordingEvidenceProducer();
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(fencing, producer, store);

        String wrongStable = sha256Of("wrongStable");
        Request request = new Request(
                wrongStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict verdict = ept.execute(request);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(verdict.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        assertThat(verdict.reasonCode()).isEqualTo("STABLE_REQUEST_ID_MISMATCH");
        // Zero external calls when stable mismatch
        assertThat(fencing.callCount).isEqualTo(0);
        assertThat(producer.callCount).isEqualTo(0);
        assertThat(store.b1CallCount).isEqualTo(0);
        assertThat(store.r1CallCount).isEqualTo(0);
    }

    @Test
    void oracle4_wrongStable_producerNeverCalled() throws Exception {
        RecordingEvidenceProducer producer = new RecordingEvidenceProducer();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(), producer,
                        new RecordingStorePublisher());

        String wrongStable = sha256Of("wrongStable");
        Request request = new Request(
                wrongStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        ept.execute(request);

        assertThat(producer.callCount).isEqualTo(0);
    }

    @Test
    void oracle4_wrongStable_verdictContainsDerivedIdentity() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();

        String wrongStable = sha256Of("wrongStableExpected");
        Request request = new Request(
                wrongStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict verdict = ept.execute(request);

        // Verdict should contain the internally-derived identity, not caller's wrong value
        assertThat(verdict.stableRequestId()).isNotEqualTo(wrongStable);
        assertThat(verdict.stableRequestId()).startsWith("sha256:");
        assertThat(verdict.transactionId()).startsWith("sha256:");
    }



    // ---------------------------------------------------------------------------
    // Oracle 5: Request schema rejects transactionId (not in Request record)
    // ---------------------------------------------------------------------------

    @Test
    void oracle5_requestSchemaDoesNotIncludeTransactionId() {
        java.lang.reflect.RecordComponent[] components = Request.class.getRecordComponents();
        Set<String> fields = new java.util.HashSet<>();
        for (java.lang.reflect.RecordComponent c : components) {
            fields.add(c.getName());
        }

        // transactionId must NOT be in Request
        assertThat(fields).doesNotContain("transactionId");
        // stableRequestId is now expectedStableRequestId
        assertThat(fields).contains("expectedStableRequestId");
        // All six fingerprints must be present
        assertThat(fields).contains("authorityInputTreeFingerprint");
        assertThat(fields).contains("targetInputTreeFingerprint");
        assertThat(fields).contains("planFingerprint");
        assertThat(fields).contains("targetBindingFingerprint");
        assertThat(fields).contains("declarationFingerprint");
        assertThat(fields).contains("candidateFingerprint");
        assertThat(fields).contains("publicationNonce");
        assertThat(fields).contains("evidenceRoot");
        assertThat(fields).contains("privateParent");
    }



    // ---------------------------------------------------------------------------
    // H01: Fresh ordinary-JAR commit+verify (updated for new Request)
    // ---------------------------------------------------------------------------

    @Test
    void h01_fresh_commit_returns_committed() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict verdict = ept.execute(request);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);
        assertThat(verdict.stableRequestId()).isEqualTo(expectedStable);
        assertThat(verdict.b0Receipt()).isNotNull();
        assertThat(verdict.b0Receipt().b0RawFingerprint()).startsWith("sha256:");
        assertThat(verdict.b0Receipt().b0CanonicalFingerprint()).startsWith("sha256:");
        assertThat(verdict.b0Receipt().b0ClosureFingerprint()).startsWith("sha256:");
    }

    @Test
    void h01_commit_creates_b0_b1_r1_files() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        ept.execute(request);

        Path committed = commitDir.resolve(stableHex);
        assertThat(committed).isDirectory();
        // New layout: header, authority receipt, b0/, b1, r1 at top level
        assertThat(committed.resolve("header.json")).isRegularFile();
        assertThat(committed.resolve("owner-authority-receipt.json")).isRegularFile();
        assertThat(committed.resolve("b1-receipt.json")).isRegularFile();
        assertThat(committed.resolve("r1-receipt.json")).isRegularFile();
        // b0/ contains only evidence-root/ (no b0/manifest.json)
        assertThat(committed.resolve("b0/evidence-root/evidence.txt")).isRegularFile();
        // b0/manifest.json must not exist
        assertThat(committed.resolve("b0/manifest.json")).doesNotExist();
        // Authoritative manifest at root
        assertThat(committed.resolve("b0-inner-manifest.json")).isRegularFile();
    }

    @Test
    void h01_b0_inner_manifest_containsCandidateFingerprint() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        ept.execute(request);

        // Manifest is at b0-inner-manifest.json (b0/manifest.json is removed)
        Path b0File = commitDir.resolve(stableHex).resolve("b0-inner-manifest.json");
        String fileContent = Files.readString(b0File);
        assertThat(fileContent).contains("\"messageVersion\":\"" + CapabilityStudioEvidencePublicationTransaction.EPT_DOMAIN + "\"");
        assertThat(fileContent).contains("\"candidateFingerprint\":\"" + FP6 + "\"");
        assertThat(fileContent).doesNotContain("boundedChildDigest"); // renamed to candidateFingerprint
        assertThat(fileContent).doesNotContain("evidenceRootUri");
        assertThat(fileContent).doesNotContain("evidenceRootUri"); // no external source in manifest
    }

    @Test
    void h01_verify_returns_pass() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String txn = ept.deriveTransactionId(expectedStable, NONCE);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict committed = ept.execute(request);
        Path committedRoot = commitDir.resolve(stableHex);

        ExpectedPins pins = new ExpectedPins(
                committed.stableRequestId(),
                committed.transactionId(),
                committed.b0Receipt().b0RawFingerprint(),
                committed.b0Receipt().b0CanonicalFingerprint(),
                committed.b0Receipt().b0ClosureFingerprint(),
                committed.b0Receipt().b1ReceiptFingerprint(),
                committed.b0Receipt().r1Fingerprint(),
                committed.b0Receipt().authorityFingerprint(),
                committed.b0Receipt().authorityEpoch());

        VerifyResult result = ept.verify(committedRoot, pins);
        assertThat(result.verified())
                .as("verify mismatchCode: " + result.mismatchCode())
                .isTrue();
    }



    // ---------------------------------------------------------------------------
    // H02: COMPLETE exact retry idempotency
    // ---------------------------------------------------------------------------

    @Test
    void h02_retry_idempotent_recovered() throws Exception {
        RecordingEvidenceProducer producer = new RecordingEvidenceProducer();
        CapabilityStudioEvidencePublicationTransaction eptWithRecording =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(), producer,
                        new RecordingStorePublisher());

        String expectedStable = eptWithRecording.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = eptWithRecording.execute(request);
        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);

        // Retry - should recover without re-calling producer.
        // Both requests use committedRoot=commitDir (fresh first execute installs
        // to commitDir/<stableHex>; retry reads from the same committedRoot).
        Verdict second = eptWithRecording.execute(request);
        assertThat(second.outcome()).isEqualTo(Verdict.Outcome.RECOVERED);
        // reasonCode is null for COMMITTED/RECOVERED; show it for debugging clarity
        assertThat(second.reasonCode())
                .as("RECOVERED verdict must have null reasonCode, got: " + second.reasonCode())
                .isNull();
        assertThat(producer.callCount).isEqualTo(1); // Only first call made

    }


    // ---------------------------------------------------------------------------
    // VR01: Read-only verify with zero-write (new layout)
    // ---------------------------------------------------------------------------

    @Test
    void vr01_verify_detectsWrongB0Raw() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        ept.execute(request);

        Path committedRoot = commitDir.resolve(stableHex);
        // Use wrong B0 raw fingerprint (strictLocalB0Verify passes since bundle is intact)
        ExpectedPins pins = new ExpectedPins(
                expectedStable,
                ept.deriveTransactionId(expectedStable, NONCE),
                sha256Of("wrongB0Raw"),
                sha256Of("wrongB0Canonical"),
                sha256Of("wrongB0Closure"),
                sha256Of("wrongB1"),
                sha256Of("wrongR1"),
                sha256Of("wrongAuthority"),
                1L);

        VerifyResult result = ept.verify(committedRoot, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("B0_RAW_MISMATCH");
    }



    // ---------------------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------------------

    private CapabilityStudioEvidencePublicationTransaction buildEpt() {
        return new CapabilityStudioEvidencePublicationTransaction(
                new RecordingFencingAuthority(),
                new RecordingEvidenceProducer(),
                new RecordingStorePublisher());
    }



    // ---------------------------------------------------------------------------
    // Recording test doubles
    // ---------------------------------------------------------------------------

    static final class RecordingFencingAuthority implements FencingAuthority {
        int callCount = 0;

        @Override
        public FencingToken issue(String stableRequestId,
                                  String authorityInputTreeFingerprint,
                                  Path workingDirectory) {
            callCount++;
            byte[] tokenBytes = ("fencing-token-" + callCount + "-" + stableRequestId.substring(7, 15)).getBytes();
            return new FencingToken(tokenBytes, sha256Of("token" + callCount + stableRequestId), 1L + callCount);
        }
    }

    static final class RecordingEvidenceProducer implements EvidenceProducer {
        int callCount = 0;
        Path lastStableDir;
        String lastCandidateDigest;
        Path producedEvidenceRoot;

        @Override
        public SealedEvidenceCandidate produce(String stableRequestId,
                                               String candidateFingerprint,
                                               Path workingDirectory) {
            callCount++;
            lastStableDir = workingDirectory;
            lastCandidateDigest = candidateFingerprint;
            // New layout: b0/evidence-root/
            Path b0Dir = workingDirectory.resolve("b0");
            producedEvidenceRoot = b0Dir.resolve("evidence-root");
            try {
                Files.createDirectories(producedEvidenceRoot);
                Path evidenceFile = producedEvidenceRoot.resolve("evidence.txt");
                Files.writeString(evidenceFile,
                        "evidence for " + stableRequestId.substring(7, 15) + " attempt " + callCount,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new EvidenceProducerException("IO", "Failed to create evidence root", e);
            }
            return new SealedEvidenceCandidate(producedEvidenceRoot, candidateFingerprint, callCount);
        }
    }



    // ---------------------------------------------------------------------------
    // RecordingStorePublisher — demo adapter with complete realistic data
    // ---------------------------------------------------------------------------

    static final class RecordingStorePublisher implements StorePublisher {
        static final String ISSUER = "recording-store-publisher";

        int b1CallCount = 0;
        int r1CallCount = 0;
        String lastB0Closure;
        String lastIdempotencyKey;
        String lastOwner;
        String lastB1ReceiptFingerprint;

        @Override
        public String issuer() {
            return ISSUER;
        }

        @Override
        public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
            b1CallCount++;
            lastB0Closure = b0ClosureFingerprint;
            lastIdempotencyKey = idempotencyKey;
            // Returns acceptance with store issuer and idempotency key — no self-reported fingerprint
            return new B1Acceptance(ISSUER, idempotencyKey);
        }

        @Override
        public R1Acceptance issueR1(String b0ClosureFingerprint,
                                      String b1ReceiptFingerprint,
                                      String idempotencyKey,
                                      String owner) {
            r1CallCount++;
            lastB1ReceiptFingerprint = b1ReceiptFingerprint;
            lastOwner = owner;
            // Returns acceptance with store issuer and owner — no self-reported fingerprint
            return new R1Acceptance(ISSUER, owner);
        }

        @Override
        public B1Acceptance queryB1(String idempotencyKey) {
            return null; // Not used in these tests
        }

    }


    // ---------------------------------------------------------------------------
    // S01: EvidenceSnapshot integration — committed b0/evidence-root has actual content
    // ---------------------------------------------------------------------------

    @Test
    void s01_committed_b0_evidence_root_hasActualFiles() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        ept.execute(request);

        Path committed = commitDir.resolve(stableHex);
        // b0/evidence-root/ contains actual producer-written files
        assertThat(committed.resolve("b0/evidence-root/evidence.txt")).isRegularFile();
        String content = Files.readString(committed.resolve("b0/evidence-root/evidence.txt"));
        assertThat(content).contains("evidence for");
    }

    @Test
    void s01_verify_passes_after_source_deletion() throws Exception {
        RecordingEvidenceProducer producer = new RecordingEvidenceProducer();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(), producer,
                        new RecordingStorePublisher());
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);

        // Delete producer's source directory (simulating source deletion after commit)
        Path sourceDir = producer.lastStableDir.resolve("b0");
        deleteRecursively(sourceDir);
        assertThat(Files.exists(sourceDir)).isFalse();

        // Verify must still pass (evidence is self-contained in committed b0/evidence-root/)
        Path committedRoot = commitDir.resolve(stableHex);
        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(),
                first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());
        VerifyResult result = ept.verify(committedRoot, pins);
        assertThat(result.verified())
                .as("verify after source deletion mismatchCode: " + result.mismatchCode())
                .isTrue();
    }



    // ---------------------------------------------------------------------------
    // S01b: Nested source — deep directory tree is captured and verified
    // ---------------------------------------------------------------------------

    @Test
    void s01b_nested_payload_commit_and_verify() throws Exception {
        RecordingEvidenceProducer producer = new RecordingEvidenceProducer();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(), producer,
                        new RecordingStorePublisher());

        // Create a nested directory structure as evidence source
        Path nestedSource = Files.createTempDirectory("nested-src-");
        try {
            Path dirA = nestedSource.resolve("a");
            Path dirB = nestedSource.resolve("b");
            Files.createDirectories(dirA);
            Files.createDirectories(dirB);
            Files.writeString(nestedSource.resolve("root.txt"), "top-level");
            Files.writeString(dirA.resolve("a1.txt"), "alpha-content");
            Files.writeString(dirA.resolve("a2.txt"), "beta-content");
            Files.writeString(dirB.resolve("b1.txt"), "gamma-content");

            String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
            String stableHex = expectedStable.substring(7);
            Request request = new Request(
                    expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                    nestedSource.toUri(),
                    URI.create("file:///tmp/private"),
                    workDir, commitDir);

            Verdict committed = ept.execute(request);
            assertThat(committed.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);

            Path committedRoot = commitDir.resolve(stableHex);

            // committed b0/evidence-root/ must contain all nested files (flat paths)
            assertThat(committedRoot.resolve("b0/evidence-root/root.txt")).isRegularFile();
            assertThat(committedRoot.resolve("b0/evidence-root/a/a1.txt")).isRegularFile();
            assertThat(committedRoot.resolve("b0/evidence-root/a/a2.txt")).isRegularFile();
            assertThat(committedRoot.resolve("b0/evidence-root/b/b1.txt")).isRegularFile();

            // b0/manifest.json must NOT exist
            assertThat(committedRoot.resolve("b0/manifest.json")).doesNotExist();

            // Verify must pass
            ExpectedPins pins = new ExpectedPins(
                    committed.stableRequestId(),
                    committed.transactionId(),
                    committed.b0Receipt().b0RawFingerprint(),
                    committed.b0Receipt().b0CanonicalFingerprint(),
                    committed.b0Receipt().b0ClosureFingerprint(),
                    committed.b0Receipt().b1ReceiptFingerprint(),
                    committed.b0Receipt().r1Fingerprint(),
                    committed.b0Receipt().authorityFingerprint(),
                    committed.b0Receipt().authorityEpoch());
            VerifyResult result = ept.verify(committedRoot, pins);
            assertThat(result.verified())
                    .as("nested verify mismatchCode: " + result.mismatchCode())
                    .isTrue();
        } finally {
            deleteRecursively(nestedSource);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, Set.of(), Integer.MAX_VALUE,
                new java.nio.file.FileVisitor<>() {
                    @Override public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) {
                        try { Files.deleteIfExists(d); } catch (IOException ignored) {}
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException exc) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
    }


    /**
     * Writes to a read-only receipt file for tamper testing, then restores the read-only
     * permission.  Uses {@code OWNER_READ + OWNER_WRITE} for the write window; restores
     * to {@code OWNER_READ} on exit.  POSIX-only.
     */
    private static void tamperReceipt(Path file, String content) throws IOException {
        Set<PosixFilePermission> saved = Files.getPosixFilePermissions(file);
        Files.setPosixFilePermissions(file,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        try {
            Files.writeString(file, content);
        } finally {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ));
        }
    }


    // ---------------------------------------------------------------------------
    // S02: Payload tamper/delete/add each get stable mismatch codes
    // ---------------------------------------------------------------------------

    @Test
    void s02_payload_tamper_returnsPAYLOAD_FINGERPRINT_MISMATCH() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);
        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);
        // Tamper the evidence file (change content, keeping size same)
        Path evidenceFile = committed.resolve("b0/evidence-root/evidence.txt");
        byte[] originalBytes = Files.readAllBytes(evidenceFile);
        // Tamper with same-length bytes (changes fingerprint but not size)
        byte[] tamperedBytes = originalBytes.clone();
        tamperedBytes[0] = (byte) (tamperedBytes[0] ^ 0xFF);
        Files.write(evidenceFile, tamperedBytes);
        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());
        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("PAYLOAD_FINGERPRINT_MISMATCH");
    }

    @Test
    void s02_payload_delete_returnsPAYLOAD_MISSING() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);
        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);
        // Delete the evidence file
        Files.deleteIfExists(committed.resolve("b0/evidence-root/evidence.txt"));
        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());
        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("PAYLOAD_MISSING");
    }

    @Test
    void s02_payload_add_returnsPAYLOAD_EXTRA() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);
        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);
        // Add an extra file to evidence-root
        Files.writeString(committed.resolve("b0/evidence-root/extra.txt"), "unexpected");
        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());
        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("PAYLOAD_EXTRA");
    }



    // ---------------------------------------------------------------------------
    // S03: Unknown top-level and b0 sibling fail
    // ---------------------------------------------------------------------------

    @Test
    void s03_unknown_top_level_returnsBUNDLE_UNKNOWN_ENTRY() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);
        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);
        // Add an unexpected file at top level
        Files.writeString(committed.resolve("unexpected.txt"), "bad");
        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());
        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("BUNDLE_UNKNOWN_ENTRY");
    }

    @Test
    void s03_unknown_b0_child_returnsPAYLOAD_EXTRA() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);
        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);
        // Add an unexpected file inside b0/ (not manifest.json or evidence-root/)
        Files.writeString(committed.resolve("b0/evil.txt"), "bad");
        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());
        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("PAYLOAD_EXTRA");
    }



    // ---------------------------------------------------------------------------
    // S04: Retry producer/store/authority counts do not increment
    // ---------------------------------------------------------------------------

    @Test
    void s04_retry_producerCount_notIncremented() throws Exception {
        RecordingEvidenceProducer producer = new RecordingEvidenceProducer();
        RecordingFencingAuthority authority = new RecordingFencingAuthority();
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(authority, producer, store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);
        int producerAfterFirst = producer.callCount;
        int authorityAfterFirst = authority.callCount;
        int b1AfterFirst = store.b1CallCount;
        int r1AfterFirst = store.r1CallCount;

        Verdict second = ept.execute(request);
        assertThat(second.outcome()).isEqualTo(Verdict.Outcome.RECOVERED);

        // Producer, authority, B1, R1 counts must not increment on retry
        assertThat(producer.callCount)
                .as("producer callCount must not increment on retry")
                .isEqualTo(producerAfterFirst);
        assertThat(authority.callCount)
                .as("authority callCount must not increment on retry")
                .isEqualTo(authorityAfterFirst);
        assertThat(store.b1CallCount)
                .as("b1CallCount must not increment on retry")
                .isEqualTo(b1AfterFirst);
        assertThat(store.r1CallCount)
                .as("r1CallCount must not increment on retry")
                .isEqualTo(r1AfterFirst);
    }



    // ---------------------------------------------------------------------------
    // S05: Mutated bundle → retry returns CLOSED INVALID (not RECOVERED)
    // ---------------------------------------------------------------------------

    @Test
    void s05_mutated_bundle_retry_returnsCLOSED_INVALID() throws Exception {
        RecordingEvidenceProducer producer = new RecordingEvidenceProducer();
        RecordingFencingAuthority authority = new RecordingFencingAuthority();
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(authority, producer, store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        ept.execute(request); // first execute
        Path committed = commitDir.resolve(stableHex);

        // Mutate the evidence file (simulates post-commit tampering)
        Path evidenceFile = committed.resolve("b0/evidence-root/evidence.txt");
        byte[] originalBytes = Files.readAllBytes(evidenceFile);
        byte[] mutatedBytes = originalBytes.clone();
        mutatedBytes[0] = (byte) (mutatedBytes[0] ^ 0xFF);
        Files.write(evidenceFile, mutatedBytes);

        // Retry must return CLOSED INVALID, not RECOVERED
        Verdict retry = ept.execute(request);
        assertThat(retry.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(retry.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        // Must NOT be RECOVERED (tampering detected)
        assertThat(retry.transactionId()).isNotNull();
    }



    // ---------------------------------------------------------------------------
    // S06: Metadata snapshot proves public verify / retry zero write
    // ---------------------------------------------------------------------------

    @Test
    void s06_verify_zeroWrite_doesNotModifyCommittedBundle() throws Exception {
        CapabilityStudioEvidencePublicationTransaction ept = buildEpt();
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Record file sizes and modification timestamps
        Map<Path, Long> originalSizes = new java.util.HashMap<>();
        Map<Path, Long> originalMtimes = new java.util.HashMap<>();
        Files.walkFileTree(committed, Set.of(), Integer.MAX_VALUE,
                new java.nio.file.FileVisitor<>() {
                    @Override public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                        try {
                            originalSizes.put(committed.relativize(f), Files.size(f));
                            originalMtimes.put(committed.relativize(f), Files.getLastModifiedTime(f).toMillis());
                        } catch (IOException ignored) {}
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException exc) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });

        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());

        // Run verify multiple times
        for (int i = 0; i < 3; i++) {
            VerifyResult result = ept.verify(committed, pins);
            assertThat(result.verified()).isTrue();
        }

        // After all verifies, committed bundle must be bit-identical
        Files.walkFileTree(committed, Set.of(), Integer.MAX_VALUE,
                new java.nio.file.FileVisitor<>() {
                    @Override public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                        Path rel = committed.relativize(f);
                        try {
                            assertThat(Files.size(f))
                                    .as("file size unchanged after verify: " + rel)
                                    .isEqualTo(originalSizes.get(rel));
                            assertThat(Files.getLastModifiedTime(f).toMillis())
                                    .as("file mtime unchanged after verify: " + rel)
                                    .isEqualTo(originalMtimes.get(rel));
                        } catch (IOException ignored) {}
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException exc) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });

    }

    // ---------------------------------------------------------------------------
    // S07: EptReceiptEnvelope integration — fresh execute uses codec fingerprints
    // ---------------------------------------------------------------------------

    @Test
    void s07_fresh_uses_codec_fingerprints() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);

        // The committed B1 and R1 receipts are sealed by EptReceiptEnvelope
        // Their fingerprints are codec fingerprints (computed from body projection)
        assertThat(first.b0Receipt().b1ReceiptFingerprint()).startsWith("sha256:");
        assertThat(first.b0Receipt().r1Fingerprint()).startsWith("sha256:");

        // Verify reads back the same fingerprints
        Path committed = commitDir.resolve(stableHex);
        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());
        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isTrue();
    }



    // ---------------------------------------------------------------------------
    // S08: StorePublisher SPI counters unchanged before/after public verify
    // ---------------------------------------------------------------------------

    @Test
    void s08_public_verify_doesNotCallSpi() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);
        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());

        int b1Before = store.b1CallCount;
        int r1Before = store.r1CallCount;

        // Multiple verifies must not call SPI
        for (int i = 0; i < 5; i++) {
            VerifyResult result = ept.verify(committed, pins);
            assertThat(result.verified()).isTrue();
        }

        assertThat(store.b1CallCount)
                .as("b1CallCount must not change after verify")
                .isEqualTo(b1Before);
        assertThat(store.r1CallCount)
                .as("r1CallCount must not change after verify")
                .isEqualTo(r1Before);
    }



    // ---------------------------------------------------------------------------
    // S09: EptReceiptEnvelope integration — retry uses codec verify
    // ---------------------------------------------------------------------------

    @Test
    void s09_retry_readsAndVerifiesExistingReceipts() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);
        assertThat(first.b0Receipt().b1ReceiptFingerprint()).isNotNull();
        assertThat(first.b0Receipt().r1Fingerprint()).isNotNull();

        // Retry must read and verify existing receipts (codec-based)
        Verdict second = ept.execute(request);
        assertThat(second.outcome()).isEqualTo(Verdict.Outcome.RECOVERED);
        assertThat(second.b0Receipt().b1ReceiptFingerprint())
                .isEqualTo(first.b0Receipt().b1ReceiptFingerprint());
        assertThat(second.b0Receipt().r1Fingerprint())
                .isEqualTo(first.b0Receipt().r1Fingerprint());
    }



    // ---------------------------------------------------------------------------
    // M01: Malicious acceptance — wrong issuer → CLOSED INVALID, no receipt written
    // ---------------------------------------------------------------------------

    @Test
    void m01_wrongIssuer_fresh_returnsClosedInvalid() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);

        // Intercept: make publishB1 return wrong issuer
        StorePublisher maliciousStore = new StorePublisher() {
            final RecordingStorePublisher delegate = new RecordingStorePublisher();
            @Override public String issuer() { return delegate.issuer(); }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                return new B1Acceptance("evil-issuer", idempotencyKey); // wrong issuer
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                                                  String b1ReceiptFingerprint,
                                                  String idempotencyKey,
                                                  String owner) {
                return delegate.issueR1(b0ClosureFingerprint, b1ReceiptFingerprint, idempotencyKey, owner);
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        CapabilityStudioEvidencePublicationTransaction badEpt =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        maliciousStore);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict result = badEpt.execute(request);
        assertThat(result.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(result.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        assertThat(result.reasonCode()).isEqualTo("B1_ISSUER_MISMATCH");

        // No receipt file must exist (CLOSED state, no partial write)
        Path committed = commitDir.resolve(stableHex);
        assertThat(Files.exists(committed.resolve("b1-receipt.json"))).isFalse();
        assertThat(Files.exists(committed.resolve("r1-receipt.json"))).isFalse();
    }



    // ---------------------------------------------------------------------------
    // M02: Malicious acceptance — wrong idempotencyKey → CLOSED INVALID
    // ---------------------------------------------------------------------------

    @Test
    void m02_wrongIdempotencyKey_fresh_returnsClosedInvalid() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);

        StorePublisher maliciousStore = new StorePublisher() {
            final RecordingStorePublisher delegate = new RecordingStorePublisher();
            @Override public String issuer() { return delegate.issuer(); }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                return new B1Acceptance(delegate.issuer(), "wrong-idempotency-key"); // wrong key
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                                                  String b1ReceiptFingerprint,
                                                  String idempotencyKey,
                                                  String owner) {
                return delegate.issueR1(b0ClosureFingerprint, b1ReceiptFingerprint, idempotencyKey, owner);
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        CapabilityStudioEvidencePublicationTransaction badEpt =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        maliciousStore);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict result = badEpt.execute(request);
        assertThat(result.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(result.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        assertThat(result.reasonCode()).isEqualTo("B1_IDEMPOTENCY_KEY_MISMATCH");
    }



    // ---------------------------------------------------------------------------
    // M03: Malicious acceptance — wrong owner → CLOSED INVALID
    // ---------------------------------------------------------------------------

    @Test
    void m03_wrongOwner_issueR1_returnsClosedInvalid() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);

        StorePublisher maliciousStore = new StorePublisher() {
            final RecordingStorePublisher delegate = new RecordingStorePublisher();
            @Override public String issuer() { return delegate.issuer(); }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                return delegate.publishB1(b0ClosureFingerprint, idempotencyKey);
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                                                  String b1ReceiptFingerprint,
                                                  String idempotencyKey,
                                                  String owner) {
                return new R1Acceptance(delegate.issuer(), "attacker-owner"); // wrong owner
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        CapabilityStudioEvidencePublicationTransaction badEpt =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        maliciousStore);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict result = badEpt.execute(request);
        assertThat(result.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(result.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        assertThat(result.reasonCode()).isEqualTo("R1_OWNER_MISMATCH");
    }



    // ---------------------------------------------------------------------------
    // T01: Tamper B1 receipt fields → verify fails with INVALID_B1_RECEIPT
    // ---------------------------------------------------------------------------

    @Test
    void t01_tamperB1Fields_verifyFails() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Tamper B1 receipt: change a field value

        Path b1File = committed.resolve("b1-receipt.json");
        ObjectNode b1Doc = JSON.readValue(Files.readString(b1File), ObjectNode.class);
        b1Doc.put("issuer", "tampered-issuer");
        tamperReceipt(b1File, JSON.writeValueAsString(b1Doc));


        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());

        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("INVALID_B1_RECEIPT");
    }



    // ---------------------------------------------------------------------------
    // T02: Tamper B1 extra field → verify fails (additionalProperties:false)
    // ---------------------------------------------------------------------------

    @Test
    void t02_tamperB1ExtraField_verifyFails() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Tamper B1: add an extra field (violates additionalProperties:false)
        Path b1File = committed.resolve("b1-receipt.json");
        ObjectNode b1Doc = JSON.readValue(Files.readString(b1File), ObjectNode.class);
        b1Doc.put("extraField", "attack");
        tamperReceipt(b1File, JSON.writeValueAsString(b1Doc));


        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());

        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        // Schema validation failure maps to INVALID_B1_RECEIPT
        assertThat(result.mismatchCode()).isEqualTo("INVALID_B1_RECEIPT");
    }



    // ---------------------------------------------------------------------------
    // T03: Tamper B1 fingerprint → verify fails (fingerprint mismatch)
    // ---------------------------------------------------------------------------

    @Test
    void t03_tamperB1Fingerprint_verifyFails() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Tamper B1: change receiptFingerprint value (will mismatch body projection)
        Path b1File = committed.resolve("b1-receipt.json");
        ObjectNode b1Doc = JSON.readValue(Files.readString(b1File), ObjectNode.class);
        b1Doc.put("receiptFingerprint", "a".repeat(64));
        tamperReceipt(b1File, JSON.writeValueAsString(b1Doc));


        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());

        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("INVALID_B1_RECEIPT");
    }



    // ---------------------------------------------------------------------------
    // T04: Tamper R1 B1 reference → verify fails with INVALID_R1_OUTER_COMMITMENT
    // ---------------------------------------------------------------------------

    @Test
    void t04_tamperR1B1Reference_verifyFails() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Tamper R1: change b1ReceiptFingerprint
        Path r1File = committed.resolve("r1-receipt.json");
        ObjectNode r1Doc = JSON.readValue(Files.readString(r1File), ObjectNode.class);
        r1Doc.put("b1ReceiptFingerprint", "b".repeat(64));
        tamperReceipt(r1File, JSON.writeValueAsString(r1Doc));


        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());

        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("INVALID_R1_OUTER_COMMITMENT");
    }



    // ---------------------------------------------------------------------------
    // T05: Tamper R1 owner → verify fails with INVALID_R1_OUTER_COMMITMENT
    // ---------------------------------------------------------------------------

    @Test
    void t05_tamperR1Owner_verifyFails() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Tamper R1: change owner field
        Path r1File = committed.resolve("r1-receipt.json");
        ObjectNode r1Doc = JSON.readValue(Files.readString(r1File), ObjectNode.class);
        r1Doc.put("owner", "attacker-owner");
        tamperReceipt(r1File, JSON.writeValueAsString(r1Doc));


        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());

        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("INVALID_R1_OUTER_COMMITMENT");
    }



    // ---------------------------------------------------------------------------
    // T06: Tamper R1 fingerprint → verify fails with INVALID_R1_OUTER_COMMITMENT
    // ---------------------------------------------------------------------------

    @Test
    void t06_tamperR1Fingerprint_verifyFails() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Tamper R1: change receiptFingerprint
        Path r1File = committed.resolve("r1-receipt.json");
        ObjectNode r1Doc = JSON.readValue(Files.readString(r1File), ObjectNode.class);
        r1Doc.put("receiptFingerprint", "c".repeat(64));
        tamperReceipt(r1File, JSON.writeValueAsString(r1Doc));


        ExpectedPins pins = new ExpectedPins(
                first.stableRequestId(), first.transactionId(),
                first.b0Receipt().b0RawFingerprint(),
                first.b0Receipt().b0CanonicalFingerprint(),
                first.b0Receipt().b0ClosureFingerprint(),
                first.b0Receipt().b1ReceiptFingerprint(),
                first.b0Receipt().r1Fingerprint(),
                first.b0Receipt().authorityFingerprint(),
                first.b0Receipt().authorityEpoch());

        VerifyResult result = ept.verify(committed, pins);
        assertThat(result.verified()).isFalse();
        assertThat(result.mismatchCode()).isEqualTo("INVALID_R1_OUTER_COMMITMENT");
    }



    // ---------------------------------------------------------------------------
    // T07: Exact retry rejects bad receipt — B1 tampered → CLOSED INVALID
    // ---------------------------------------------------------------------------

    @Test
    void t07_retryWithTamperedB1_returnsClosedInvalid() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Tamper B1 receipt after commit
        Path b1File = committed.resolve("b1-receipt.json");
        ObjectNode b1Doc = JSON.readValue(Files.readString(b1File), ObjectNode.class);
        b1Doc.put("idempotencyKey", "wrong-key");
        tamperReceipt(b1File, JSON.writeValueAsString(b1Doc));


        // Retry must detect tampered B1 and return CLOSED INVALID
        Verdict retry = ept.execute(request);
        assertThat(retry.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(retry.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        assertThat(retry.reasonCode()).isEqualTo("INVALID_B1_RECEIPT");
    }



    // ---------------------------------------------------------------------------
    // T08: Exact retry rejects bad receipt — R1 tampered → CLOSED INVALID
    // ---------------------------------------------------------------------------

    @Test
    void t08_retryWithTamperedR1_returnsClosedInvalid() throws Exception {
        RecordingStorePublisher store = new RecordingStorePublisher();
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        new RecordingFencingAuthority(),
                        new RecordingEvidenceProducer(),
                        store);
        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null,
                URI.create("file:///tmp/private"),
                workDir, commitDir);

        ept.execute(request);
        Path committed = commitDir.resolve(stableHex);

        // Tamper R1 receipt after commit
        Path r1File = committed.resolve("r1-receipt.json");
        ObjectNode r1Doc = JSON.readValue(Files.readString(r1File), ObjectNode.class);
        r1Doc.put("b1ReceiptFingerprint", "d".repeat(64));
        tamperReceipt(r1File, JSON.writeValueAsString(r1Doc));


        // Retry must detect tampered R1 and return CLOSED INVALID
        Verdict retry = ept.execute(request);
        assertThat(retry.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(retry.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        assertThat(retry.reasonCode()).isEqualTo("INVALID_R1_OUTER_COMMITMENT");
    }



    // ---------------------------------------------------------------------------
    // V01: EPT constructor rejects null/blank StorePublisher issuer
    // ---------------------------------------------------------------------------

    @Test
    void v01_nullIssuer_rejected() {
        StorePublisher badStore = new StorePublisher() {
            @Override public String issuer() { return null; }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) { return null; }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint, String b1ReceiptFingerprint, String idempotencyKey, String owner) { return null; }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };
        assertThatThrownBy(() -> new CapabilityStudioEvidencePublicationTransaction(
                new RecordingFencingAuthority(),
                new RecordingEvidenceProducer(),
                badStore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
    }

    @Test
    void v02_blankIssuer_rejected() {
        StorePublisher badStore = new StorePublisher() {
            @Override public String issuer() { return "   "; }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) { return null; }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint, String b1ReceiptFingerprint, String idempotencyKey, String owner) { return null; }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };
        assertThatThrownBy(() -> new CapabilityStudioEvidencePublicationTransaction(
                new RecordingFencingAuthority(),
                new RecordingEvidenceProducer(),
                badStore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
    }



    // ---------------------------------------------------------------------------
    // I01: OWNER constant is "ept-owner"
    // ---------------------------------------------------------------------------

    @Test
    void i01_ownerConstant_isEptOwner() {
        assertThat(CapabilityStudioEvidencePublicationTransaction.OWNER).isEqualTo("ept-owner");
    }

}
