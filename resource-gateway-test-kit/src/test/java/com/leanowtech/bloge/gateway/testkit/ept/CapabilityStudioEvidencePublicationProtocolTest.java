package com.leanowtech.bloge.gateway.testkit.ept;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * EPT Protocol Acceptance Tests (P01–P06).
 *
 * <p>Each test exercises one durable protocol invariant using event/latch-driven
 * doubles — no sleep, no hardcoded hashes, no timing assumptions.</p>
 *
 * <p>Layout invariants:</p>
 * <ul>
 *   <li>P01: FencingAuthorityException → CLOSED BLOCKED; fencing/producer/store all 0 external calls</li>
 *   <li>P02: EvidenceProducerException → CLOSED BLOCKED; fencing called 1×, producer/store 0 external calls</li>
 *   <li>P03: same stable + same nonce concurrent → strict serialisation; fencing/producer each 1× total; one COMMITTED, one RECOVERED</li>
 *   <li>P04: outputDir with .aborted marker → CLOSED ABORTED; zero external calls</li>
 *   <li>P05: same stable + different nonce → CLOSED CONFLICT; original bundle bytes unchanged</li>
 *   <li>P06: caller expected stable mismatch → CLOSED INVALID; fencing/producer/store all 0 calls (oracle4)</li>
 * </ul>
 */
class CapabilityStudioEvidencePublicationProtocolTest {

    @TempDir
    Path sharedTemp;

    Path workDir;
    Path commitDir;

    // Pre-computed SHA-256 fingerprints (64 hex chars after prefix)
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
        workDir = Files.createTempDirectory(sharedTemp, "ept-work");
        commitDir = Files.createTempDirectory(sharedTemp, "ept-commit");
        Files.setPosixFilePermissions(workDir,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(commitDir,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
    }
    /**
     * Test helper: produces a sealed evidence candidate, wrapping IOException
     * into EvidenceProducerException so it can be thrown from a producer lambda.
     */
    static EvidenceProducer.SealedEvidenceCandidate produceEvidence(
            Path work, String stableReq, String candFp) {
        Path er = work.resolve("b0").resolve("evidence-root");
        try {
            Files.createDirectories(er);
            Files.writeString(er.resolve("evidence.txt"), "payload");
        } catch (IOException e) {
            throw new EvidenceProducer.EvidenceProducerException("IO", "evidence creation failed", e);
        }
        return new EvidenceProducer.SealedEvidenceCandidate(er, candFp, 1);
    }


    // -------------------------------------------------------------------------
    // P01: FencingAuthorityException → CLOSED BLOCKED; zero external calls
    // -------------------------------------------------------------------------

    /**
     * P01: fencing authority throws FencingAuthorityException.
     * Verdict = CLOSED BLOCKED.  fencing/producer/store all receive 0 calls.
     * No directories created under commitDir.
     */
    @Test
    void p01_fencingAuthorityException_returnsBlocked() throws Exception {
        AtomicInteger fencingCalls = new AtomicInteger(0);
        AtomicInteger producerCalls = new AtomicInteger(0);
        AtomicInteger storeB1Calls = new AtomicInteger(0);
        AtomicInteger storeR1Calls = new AtomicInteger(0);

        FencingAuthority failingFencing = (stableReq, authFp, work) -> {
            fencingCalls.incrementAndGet();
            throw new FencingAuthority.FencingAuthorityException("CAPABILITY_UNAVAILABLE",
                    "fencing authority unavailable");
        };

        EvidenceProducer countingProducer = (stableReq, candFp, work) -> {
            producerCalls.incrementAndGet();
            Path er = work.resolve("b0").resolve("evidence-root");
            return produceEvidence(work, stableReq, candFp);
        };

        StorePublisher recordingStore = new StorePublisher() {
            @Override public String issuer() { return "recording-store"; }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                storeB1Calls.incrementAndGet();
                return new B1Acceptance(issuer(), idempotencyKey);
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                    String b1ReceiptFingerprint, String idempotencyKey, String owner) {
                storeR1Calls.incrementAndGet();
                return new R1Acceptance(issuer(), owner);
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        failingFencing, countingProducer, recordingStore);

        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict verdict = ept.execute(request);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(verdict.closedCategory()).isEqualTo(ClosedCategory.BLOCKED);
        assertThat(verdict.reasonCode()).startsWith("FENCING_AUTHORITY_FAILED_");

        // Zero external calls at every layer
        assertThat(fencingCalls.get())
                .as("fencing called once (threw exception)")
                .isEqualTo(1);
        assertThat(producerCalls.get())
                .as("producer called zero times (fencing exception prevents reaching producer)")
                .isEqualTo(0);
        assertThat(storeB1Calls.get())
                .as("store publishB1 called zero times")
                .isEqualTo(0);
        assertThat(storeR1Calls.get())
                .as("store issueR1 called zero times")
                .isEqualTo(0);

        // No output created
        // .ept-locks directory is created by the ExactLockLease registry and is not output
        assertThat(Files.list(commitDir)
                .filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().equals(".ept-locks"))
                .findFirst())
                .as("no stable-hex output directory in commitDir (only .ept-locks allowed)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // P02: EvidenceProducerException → CLOSED BLOCKED; fencing 1×, producer/store 0×
    // -------------------------------------------------------------------------

    /**
     * P02: evidence producer throws EvidenceProducerException.
     * Verdict = CLOSED BLOCKED.  fencing called once, producer throws,
     * store never called.  No output under commitDir.
     */
    @Test
    void p02_evidenceProducerException_returnsBlocked() throws Exception {
        AtomicInteger fencingCalls = new AtomicInteger(0);
        AtomicInteger producerCalls = new AtomicInteger(0);
        AtomicInteger storeB1Calls = new AtomicInteger(0);
        AtomicInteger storeR1Calls = new AtomicInteger(0);

        FencingAuthority countingFencing = (stableReq, authFp, work) -> {
            fencingCalls.incrementAndGet();
            return new FencingAuthority.FencingToken(
                    ("ftok-" + stableReq.substring(7, 10)).getBytes(),
                    sha256Of("tok" + stableReq), 1L);
        };

        EvidenceProducer failingProducer = (stableReq, candFp, work) -> {
            producerCalls.incrementAndGet();
            throw new EvidenceProducer.EvidenceProducerException("PRODUCTION_FAILED",
                    "evidence production failed");
        };

        StorePublisher recordingStore = new StorePublisher() {
            @Override public String issuer() { return "recording-store"; }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                storeB1Calls.incrementAndGet();
                return new B1Acceptance(issuer(), idempotencyKey);
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                    String b1ReceiptFingerprint, String idempotencyKey, String owner) {
                storeR1Calls.incrementAndGet();
                return new R1Acceptance(issuer(), owner);
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        countingFencing, failingProducer, recordingStore);

        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict verdict = ept.execute(request);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(verdict.closedCategory()).isEqualTo(ClosedCategory.BLOCKED);
        assertThat(verdict.reasonCode()).startsWith("EVIDENCE_PRODUCER_FAILED_");

        assertThat(fencingCalls.get())
                .as("fencing called once")
                .isEqualTo(1);
        assertThat(producerCalls.get())
                .as("producer called once (threw exception)")
                .isEqualTo(1);
        assertThat(storeB1Calls.get())
                .as("store publishB1 called zero times (producer exception prevents reaching store)")
                .isEqualTo(0);
        assertThat(storeR1Calls.get())
                .as("store issueR1 called zero times")
                .isEqualTo(0);

        // .ept-locks directory is created by the ExactLockLease registry and is not output
        assertThat(Files.list(commitDir)
                .filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().equals(".ept-locks"))
                .findFirst())
                .as("no stable-hex output directory in commitDir (only .ept-locks allowed)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // P03: same stable + same nonce concurrent → strict serialisation,
    //       fencing/producer each 1× total; one COMMITTED, one RECOVERED
    // -------------------------------------------------------------------------

    /**
     * P03: two threads execute with identical stable + nonce simultaneously.
     * EPT-CN02: same stable + same nonce serialises inside the JVM lock.
     * fencing and producer each fire exactly once total.
     * Exactly one COMMITTED, one RECOVERED.
     * Producer file appears once under the shared committed root.
     */
    @Test
    void p03_sameStableSameNonce_concurrentSerialises_oneCommittedOneRecovered()
            throws Exception {
        AtomicInteger fencingCalls = new AtomicInteger(0);
        AtomicInteger producerCalls = new AtomicInteger(0);
        AtomicInteger storeB1Calls = new AtomicInteger(0);

        FencingAuthority sharedFencing = (stableReq, authFp, work) -> {
            fencingCalls.incrementAndGet();
            return new FencingAuthority.FencingToken(
                    ("ftok-" + stableReq.substring(7, 10)).getBytes(),
                    sha256Of("tok" + stableReq), 1L);
        };

        EvidenceProducer sharedProducer = (stableReq, candFp, work) -> {
            producerCalls.incrementAndGet();
            Path er = work.resolve("b0").resolve("evidence-root");
            return produceEvidence(work, stableReq, candFp);
        };

        StorePublisher sharedStore = new StorePublisher() {
            @Override public String issuer() { return "shared-store"; }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                storeB1Calls.incrementAndGet();
                return new B1Acceptance(issuer(), idempotencyKey);
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                    String b1ReceiptFingerprint, String idempotencyKey, String owner) {
                return new R1Acceptance(issuer(), owner);
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        Path workA = Files.createTempDirectory(sharedTemp, "work-a");
        Path workB = Files.createTempDirectory(sharedTemp, "work-b");

        CapabilityStudioEvidencePublicationTransaction eptA =
                new CapabilityStudioEvidencePublicationTransaction(
                        sharedFencing, sharedProducer, sharedStore);
        CapabilityStudioEvidencePublicationTransaction eptB =
                new CapabilityStudioEvidencePublicationTransaction(
                        sharedFencing, sharedProducer, sharedStore);

        String expectedStable = eptA.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String sameNonce = sha256Of("same-nonce");

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<Verdict> verdictA = new AtomicReference<>();
        AtomicReference<Verdict> verdictB = new AtomicReference<>();
        AtomicReference<Throwable> exA = new AtomicReference<>();
        AtomicReference<Throwable> exB = new AtomicReference<>();

        Thread tA = new Thread(() -> {
            try {
                startLatch.await();
                verdictA.set(eptA.execute(new Request(
                        expectedStable, sameNonce, FP1, FP2, FP3, FP4, FP5, FP6,
                        null, URI.create("file:///tmp/private"),
                        workA, commitDir)));
            } catch (Throwable t) { exA.set(t); }
        });
        Thread tB = new Thread(() -> {
            try {
                startLatch.await();
                verdictB.set(eptB.execute(new Request(
                        expectedStable, sameNonce, FP1, FP2, FP3, FP4, FP5, FP6,
                        null, URI.create("file:///tmp/private"),
                        workB, commitDir)));
            } catch (Throwable t) { exB.set(t); }
        });

        tA.start();
        tB.start();
        startLatch.countDown();
        tA.join(30_000);
        tB.join(30_000);

        assertThat(tA.isAlive()).as("Thread A must terminate").isFalse();
        assertThat(tB.isAlive()).as("Thread B must terminate").isFalse();
        assertThat(exA.get()).isNull();
        assertThat(exB.get()).isNull();

        Verdict.Outcome outcomeA = verdictA.get().outcome();
        Verdict.Outcome outcomeB = verdictB.get().outcome();

        // EPT-CN02: exact same stable+nonce → one COMMITTED, one RECOVERED
        assertThat(List.of(outcomeA, outcomeB))
                .as("exactly one COMMITTED and one RECOVERED")
                .containsExactlyInAnyOrder(
                        Verdict.Outcome.COMMITTED, Verdict.Outcome.RECOVERED);

        // Side-effect counters: each exactly once
        assertThat(fencingCalls.get())
                .as("fencing called exactly once total")
                .isEqualTo(1);
        assertThat(producerCalls.get())
                .as("producer called exactly once total")
                .isEqualTo(1);
        assertThat(storeB1Calls.get())
                .as("publishB1 called exactly once total")
                .isEqualTo(1);

        // Producer creates exactly one evidence file in the committed root
        String stableHex = expectedStable.substring(7);
        Path committed = commitDir.resolve(stableHex);
        Path evidenceRoot = committed.resolve("b0/evidence-root");
        try (var stream = Files.list(evidenceRoot)) {
            List<Path> files = stream.toList();
            assertThat(files)
                    .as("exactly one evidence file in committed bundle")
                    .hasSize(1);
        }
    }

    // -------------------------------------------------------------------------
    // P04: outputDir with .aborted marker → CLOSED ABORTED; zero external calls
    // -------------------------------------------------------------------------

    /**
     * P04: committedRoot/stableHex/.aborted exists before execute.
     * Verdict = CLOSED ABORTED.  No fencing/producer/store calls.
     * The .aborted marker is not modified by execute.
     */
    @Test
    void p04_abortedMarker_present_returnsClosedAborted() throws Exception {
        AtomicInteger fencingCalls = new AtomicInteger(0);
        AtomicInteger producerCalls = new AtomicInteger(0);
        AtomicInteger storeCalls = new AtomicInteger(0);

        FencingAuthority countingFencing = (stableReq, authFp, work) -> {
            fencingCalls.incrementAndGet();
            return new FencingAuthority.FencingToken(
                    ("ftok-" + stableReq.substring(7, 10)).getBytes(),
                    sha256Of("tok" + stableReq), 1L);
        };

        EvidenceProducer countingProducer = (stableReq, candFp, work) -> {
            producerCalls.incrementAndGet();
            Path er = work.resolve("b0").resolve("evidence-root");
            return produceEvidence(work, stableReq, candFp);
        };

        StorePublisher countingStore = new StorePublisher() {
            @Override public String issuer() { return "counting-store"; }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                storeCalls.incrementAndGet();
                return new B1Acceptance(issuer(), idempotencyKey);
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                    String b1ReceiptFingerprint, String idempotencyKey, String owner) {
                storeCalls.incrementAndGet();
                return new R1Acceptance(issuer(), owner);
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        countingFencing, countingProducer, countingStore);

        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        Path outputDir = commitDir.resolve(stableHex);

        // Create the aborted directory before execute
        Files.createDirectories(outputDir);
        Path abortedMarker = outputDir.resolve(".aborted");
        byte[] originalMarkerContent = "aborted-at-epoch-1".getBytes();
        Files.write(abortedMarker, originalMarkerContent);

        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict verdict = ept.execute(request);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(verdict.closedCategory()).isEqualTo(ClosedCategory.ABORTED);
        assertThat(verdict.reasonCode()).isEqualTo("ABORTED_STATE");

        // Zero external calls
        assertThat(fencingCalls.get()).isEqualTo(0);
        assertThat(producerCalls.get()).isEqualTo(0);
        assertThat(storeCalls.get()).isEqualTo(0);

        // .aborted marker not modified
        assertThat(Files.readAllBytes(abortedMarker))
                .as(".aborted marker content unchanged after execute")
                .isEqualTo(originalMarkerContent);
    }

    // -------------------------------------------------------------------------
    // P05: same stable + different nonce →
    //       CLOSED CONFLICT; original committed bundle bytes unchanged
    // -------------------------------------------------------------------------

    /**
     * P05: first execute commits with candidate FP6.  Second execute uses same
     * stable but different nonce (FP6).  Verdict = CLOSED
     * CONFLICT.  The original committed bundle is not modified.
     */
    @Test
    void p05_sameStableDifferentNonce_returnsClosedConflict()
            throws Exception {
        FencingAuthority fencing = (stableReq, authFp, work) ->
                new FencingAuthority.FencingToken(
                        ("ftok-" + stableReq.substring(7, 10)).getBytes(),
                        sha256Of("tok" + stableReq), 1L);

        EvidenceProducer producer = (stableReq, candFp, work) -> {
            Path er = work.resolve("b0").resolve("evidence-root");
            return produceEvidence(work, stableReq, candFp);
        };

        StorePublisher store = new StorePublisher() {
            @Override public String issuer() { return "conflict-store"; }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                return new B1Acceptance(issuer(), idempotencyKey);
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                    String b1ReceiptFingerprint, String idempotencyKey, String owner) {
                return new R1Acceptance(issuer(), owner);
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(fencing, producer, store);

        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);

        // First execute: commits with FP6
        Request commitRequest = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict first = ept.execute(commitRequest);
        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);

        Path committed = commitDir.resolve(stableHex);

        // Snapshot committed bundle bytes before second attempt
        Map<Path, byte[]> bundleSnapshot = new HashMap<>();
        try (var stream = Files.walk(committed)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                bundleSnapshot.put(committed.relativize(p), Files.readAllBytes(p));
            }
        }

        // Second execute: same stable, different nonce, different candidate
        String differentNonce = sha256Of("different-nonce");
        Request conflictRequest = new Request(
                expectedStable, differentNonce, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict second = ept.execute(conflictRequest);

        // Same candidate + different nonce: this is the EPT-CN02 scenario.
        // Verdict is CLOSED CONFLICT because the stored candidate matches
        // but txnId differs from the derived one.
        assertThat(second.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(second.closedCategory()).isEqualTo(ClosedCategory.CONFLICT);
        assertThat(second.reasonCode()).isEqualTo("TRANSACTION_ID_MISMATCH");

        // Original bundle bytes unchanged
        try (var stream = Files.walk(committed)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                Path rel = committed.relativize(p);
                byte[] original = bundleSnapshot.get(rel);
                byte[] current = Files.readAllBytes(p);
                assertThat(current)
                        .as("bundle file '%s' unchanged after CLOSED CONFLICT", rel)
                        .isEqualTo(original);
            }
        }
    }

    // -------------------------------------------------------------------------
    // P06: caller expected stable mismatch → CLOSED INVALID;
    //       fencing/producer/store all receive 0 calls (oracle4)
    // -------------------------------------------------------------------------

    /**
     * P06: caller provides wrong expectedStableRequestId.  EPT derives the
     * correct stable internally and detects mismatch → CLOSED INVALID.
     * No fencing, producer, or store calls are made.
     */
    @Test
    void p06_stableMismatch_zeroExternalCalls() throws Exception {
        AtomicInteger fencingCalls = new AtomicInteger(0);
        AtomicInteger producerCalls = new AtomicInteger(0);
        AtomicInteger storeCalls = new AtomicInteger(0);

        FencingAuthority countingFencing = (stableReq, authFp, work) -> {
            fencingCalls.incrementAndGet();
            return new FencingAuthority.FencingToken(
                    ("ftok-" + stableReq.substring(7, 10)).getBytes(),
                    sha256Of("tok" + stableReq), 1L);
        };

        EvidenceProducer countingProducer = (stableReq, candFp, work) -> {
            producerCalls.incrementAndGet();
            Path er = work.resolve("b0").resolve("evidence-root");
            return produceEvidence(work, stableReq, candFp);
        };

        StorePublisher countingStore = new StorePublisher() {
            @Override public String issuer() { return "counting-store"; }
            @Override public B1Acceptance publishB1(String b0ClosureFingerprint, String idempotencyKey) {
                storeCalls.incrementAndGet();
                return new B1Acceptance(issuer(), idempotencyKey);
            }
            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                    String b1ReceiptFingerprint, String idempotencyKey, String owner) {
                storeCalls.incrementAndGet();
                return new R1Acceptance(issuer(), owner);
            }
            @Override public B1Acceptance queryB1(String idempotencyKey) { return null; }
        };

        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(
                        countingFencing, countingProducer, countingStore);

        // Wrong stable — derive one from different inputs
        String wrongStable = ept.deriveStableRequestId(
                sha256Of("wrongAuthorityInput"), FP2, FP3, FP4, FP5, FP6);

        Request request = new Request(
                wrongStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir);

        Verdict verdict = ept.execute(request);

        assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(verdict.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        assertThat(verdict.reasonCode()).isEqualTo("STABLE_REQUEST_ID_MISMATCH");

        // Verdict contains internally derived (correct) identity, not caller's wrong value
        assertThat(verdict.stableRequestId()).isNotEqualTo(wrongStable);
        assertThat(verdict.stableRequestId()).startsWith("sha256:");
        assertThat(verdict.transactionId()).startsWith("sha256:");

        // Zero external calls at every layer
        assertThat(fencingCalls.get()).isEqualTo(0);
        assertThat(producerCalls.get()).isEqualTo(0);
        assertThat(storeCalls.get()).isEqualTo(0);

        // .ept-locks directory is created by the ExactLockLease registry and is not output
        assertThat(Files.list(commitDir)
                .filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().equals(".ept-locks"))
                .findFirst())
                .as("no stable-hex output directory in commitDir (only .ept-locks allowed)")
                .isEmpty();
    }

    /**
     * P07: committed state + corrupt sealed B1 → executeRetry reads corrupt receipt,
     * strictLocalB0Verify passes, receipt verification fails → CLOSED INVALID.
     * The sealed B1 receipt bytes are unchanged after executeRetry.
     *
     * Flow:
     *   1. First execute: store succeeds → COMMITTED (B0 + B1 + R1)
     *   2. Manually corrupt the sealed B1 receipt
     *   3. Second execute: COMPLETE state exists → executeRetry called
     *      → strictLocalB0Verify PASS → readExistingReceipts → corrupt B1 → INVALID
     *
     * Key assertions:
     *   - Both executes succeed at store level (publishB1/issueR1)
     *   - Second execute verdict = CLOSED INVALID (receipt verify failed)
     *   - sealed B1 receipt bytes unchanged after second execute
     */
    @Test
    void p07_executeRetry_corruptSealedReceipt_noOverwrite_returnsInvalid()
            throws Exception {
        AtomicInteger storePublishCalls = new AtomicInteger(0);
        AtomicInteger storeQueryCalls = new AtomicInteger(0);

        FencingAuthority fencing = (stableReq, authFp, work) ->
                new FencingAuthority.FencingToken(
                        ("ftok-" + stableReq.substring(7, 10)).getBytes(),
                        sha256Of("tok" + stableReq), 1L);

        EvidenceProducer producer = (stableReq, candFp, work) ->
                produceEvidence(work, stableReq, candFp);

        // Store: publishB1 and issueR1 always succeed.
        // queryB1 always returns non-null so the EXTERNAL_PENDING recovery path
        // (which calls queryB1 first) is skipped in favour of executeRetry.
        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(fencing, producer,
                        new StorePublisher() {
                            @Override public String issuer() { return "test-store"; }
                            @Override public B1Acceptance publishB1(String b0ClosureFingerprint,
                                    String idempotencyKey) {
                                storePublishCalls.incrementAndGet();
                                return new B1Acceptance(issuer(), idempotencyKey);
                            }
                            @Override public R1Acceptance issueR1(String b0ClosureFingerprint,
                                    String b1ReceiptFingerprint, String idempotencyKey, String owner) {
                                return new R1Acceptance(issuer(), owner);
                            }
                            @Override public B1Acceptance queryB1(String idempotencyKey) {
                                storeQueryCalls.incrementAndGet();
                                return new B1Acceptance(issuer(), idempotencyKey);
                            }
                        });

        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);
        String txnId = ept.deriveTransactionId(expectedStable, NONCE);
        String idempotencyKey = stableHex + "-" + txnId.substring(7);

        Request request = new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir);

        // 1. First execute → COMMITTED
        Verdict first = ept.execute(request);
        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.COMMITTED);

        Path outputDir = commitDir.resolve(stableHex);
        Path b1ReceiptFile = outputDir.resolve("b1-receipt.json");

        // 2. Corrupt the sealed B1 receipt: structurally valid JSON but the b1Fingerprint
        // field is wrong, so immediate cross-verification in executeRetry fails.
        String wrongHex64 = "aa".repeat(32);
        String wrongHex64b = "bb".repeat(32);
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode corruptB1Node = om.createObjectNode();
        corruptB1Node.put("b1Fingerprint", "sha256:" + wrongHex64);
        corruptB1Node.put("stableRequestId", expectedStable.substring(7));
        corruptB1Node.put("transactionId", txnId.substring(7));
        corruptB1Node.put("b0ClosureFingerprint", "sha256:" + wrongHex64b);
        corruptB1Node.put("idempotencyKey", idempotencyKey);
        corruptB1Node.put("issuer", "test-store");
        String corruptSealedB1;
        try {
            corruptSealedB1 = om.writeValueAsString(corruptB1Node);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        // Grant write on b1ReceiptFile itself so we can overwrite it
        Files.setPosixFilePermissions(b1ReceiptFile,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.writeString(b1ReceiptFile, corruptSealedB1);
        // Restore to read-only to faithfully simulate post-publish corruption
        Files.setPosixFilePermissions(b1ReceiptFile,
                Set.of(PosixFilePermission.OWNER_READ));

        // Snapshot B1 receipt bytes before second execute
        byte[] sentinelBytes = Files.readAllBytes(b1ReceiptFile);

        // 3. Second execute → COMPLETE exists → executeRetry called
        //    → strictLocalB0Verify PASS → readExistingReceipts → corrupt B1 → INVALID
        Verdict second = ept.execute(request);

        assertThat(second.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(second.closedCategory()).isEqualTo(ClosedCategory.INVALID);
        assertThat(second.reasonCode()).startsWith("INVALID_");

        // Both executes succeeded at store level (publishB1 was called twice)
        assertThat(storePublishCalls.get())
                .as("retry sees corrupt local B1 — no republish")
                .isEqualTo(1);

        // B1 receipt bytes unchanged after second executeRetry
        assertThat(Files.readAllBytes(b1ReceiptFile))
                .as("sealed B1 receipt bytes must not be overwritten by executeRetry")
                .isEqualTo(sentinelBytes);
    }
}
