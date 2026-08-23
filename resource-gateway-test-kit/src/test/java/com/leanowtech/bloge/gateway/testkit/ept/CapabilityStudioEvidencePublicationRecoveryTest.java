package com.leanowtech.bloge.gateway.testkit.ept;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * EPT Recovery Scenarios — CapabilityStudioEvidencePublicationRecoveryTest.
 *
 * <p>Tests idempotent recovery behaviour when StorePublisher operations throw
 * recoverable StorePublisherException at various points in the execute lifecycle.</p>
 *
 * <p>All doubles are thread-safe.  Test helpers avoid hand-written JSON strings
 * and do not touch read-only files.</p>
 *
 * <p>Layout invariants tested across R01–R05:</p>
 * <ul>
 *   <li>B0 + evidence payload are committed before any external publication call</li>
 *   <li>Missing B1/R1 receipts after a recoverable throw → CLOSED UNAVAILABLE</li>
 *   <li>Retry must query B1 before re-publishing to avoid duplicate acceptance</li>
 *   <li>R1 recovery must NOT re-query or re-publish B1</li>
 *   <li>Concurrent EPT instances with same committedRoot share state safely</li>
 *   <li>Different stable IDs on same committedRoot do not interfere</li>
 * </ul>
 */
class CapabilityStudioEvidencePublicationRecoveryTest {

    // -------------------------------------------------------------------------
    // Shared test fixtures
    // -------------------------------------------------------------------------

    @TempDir
    Path sharedTemp;

    Path workDir;
    Path commitDir;

    // Pre-computed valid SHA-256 fingerprints (64 hex chars after "sha256:" prefix)
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

    // -------------------------------------------------------------------------
    // R01: Fresh publishB1 throws recoverable → CLOSED UNAVAILABLE; retry recovers
    // -------------------------------------------------------------------------

    /**
     * R01: publishB1 throws recoverable on fresh execute.
     * Verdict = CLOSED UNAVAILABLE.  B0 + evidence payload exist.
     * B1 and R1 receipts are absent.
     * Retry: store recovers, verdict = RECOVERED.
     * fencing and producer each called exactly once total.
     * B0 bytes and mtime unchanged after recovery.
     */
    @Test
    void r01_freshPublishB1_throwsRecoverable_retryRecovers()
            throws Exception {
        // Throw on first publishB1 for this idempotency key, succeed on retry.
        FailableStorePublisher store = new FailableStorePublisher.Builder()
                .failOnFirstPublishB1(true)
                .build();

        AtomicInteger fencingCalls = new AtomicInteger(0);
        AtomicInteger producerCalls = new AtomicInteger(0);

        FencingAuthority fencing = (stableReq, authFp, work) -> {
            fencingCalls.incrementAndGet();
            return new FencingAuthority.FencingToken(
                    ("ftok-" + stableReq.substring(7, 10)).getBytes(),
                    sha256Of("tok" + stableReq), 1L);
        };

        EvidenceProducer producer = (stableReq, candFp, work) -> {
            producerCalls.incrementAndGet();
            Path b0Dir = work.resolve("b0");
            Path evidenceRoot = b0Dir.resolve("evidence-root");
            try {
                Files.createDirectories(evidenceRoot);
                Files.writeString(evidenceRoot.resolve("payload.txt"),
                        "payload-" + stableReq.substring(7, 10));
            } catch (IOException e) {
                throw new EvidenceProducer.EvidenceProducerException("IO", "producer IO failed", e);
            }
            return new EvidenceProducer.SealedEvidenceCandidate(evidenceRoot, candFp, 1);
        };

        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(fencing, producer, store);

        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);

        // Fresh execute — B1 fails → CLOSED UNAVAILABLE
        Verdict first = ept.execute(new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir));

        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(first.closedCategory()).isEqualTo(ClosedCategory.UNAVAILABLE);
        assertThat(first.b0Receipt()).isNull();

        Path committed = commitDir.resolve(stableHex);

        // B0 and payload ARE committed (durable before external call)
        assertThat(committed.resolve("b0/evidence-root/payload.txt")).isRegularFile();
        byte[] b0Bytes = Files.readAllBytes(
                committed.resolve(CapabilityStudioEvidencePublicationTransaction.B0_INNER_MANIFEST_FILE));
        long b0Mtime = Files.getLastModifiedTime(
                committed.resolve(CapabilityStudioEvidencePublicationTransaction.B0_INNER_MANIFEST_FILE))
                .toMillis();

        // B1 and R1 receipts are absent
        assertThat(committed.resolve("b1-receipt.json")).doesNotExist();
        assertThat(committed.resolve("r1-receipt.json")).doesNotExist();

        // Retry — store recovers (second publishB1 succeeds for same idempotency key)
        Verdict retry = ept.execute(new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir));

        assertThat(retry.outcome()).isEqualTo(Verdict.Outcome.RECOVERED);
        assertThat(retry.b0Receipt()).isNotNull();

        // fencing and producer each called exactly once total
        assertThat(fencingCalls.get()).isEqualTo(1);
        assertThat(producerCalls.get()).isEqualTo(1);

        // B0 bytes and mtime unchanged after recovery
        byte[] b0BytesAfter = Files.readAllBytes(
                committed.resolve(CapabilityStudioEvidencePublicationTransaction.B0_INNER_MANIFEST_FILE));
        long b0MtimeAfter = Files.getLastModifiedTime(
                committed.resolve(CapabilityStudioEvidencePublicationTransaction.B0_INNER_MANIFEST_FILE))
                .toMillis();
        assertThat(b0BytesAfter).isEqualTo(b0Bytes);
        assertThat(b0MtimeAfter).isEqualTo(b0Mtime);

        // B1 and R1 now present
        assertThat(committed.resolve("b1-receipt.json")).isRegularFile();
        assertThat(committed.resolve("r1-receipt.json")).isRegularFile();
    }

    // -------------------------------------------------------------------------
    // R02: publishB1 saves acceptance first, then throws recoverable (response lost)
    // -------------------------------------------------------------------------

    /**
     * R02: publishB1 succeeds internally but the response is lost (throw recoverable).
     * The acceptance IS saved to the store.  Retry queryB1 hits the existing acceptance.
     * publishB1 called exactly once; queryB1 called at least once.
     * After recovery: B1 and R1 present; verdict = RECOVERED.
     */
    @Test
    void r02_publishB1SavesThenThrowsRecoverable_queryB1FindsIt()
            throws Exception {
        AtomicInteger publishCount = new AtomicInteger(0);
        AtomicInteger queryCount = new AtomicInteger(0);
        AtomicReference<StorePublisher.B1Acceptance> savedAcceptance = new AtomicReference<>();

        StorePublisher store = new StorePublisher() {
            @Override public String issuer() { return "recoverable-store"; }

            @Override
            public StorePublisher.B1Acceptance publishB1(String b0ClosureFingerprint,
                                                         String idempotencyKey) {
                publishCount.incrementAndGet();
                StorePublisher.B1Acceptance acceptance =
                        new StorePublisher.B1Acceptance(issuer(), idempotencyKey);
                savedAcceptance.set(acceptance);
                // Throw recoverable — caller thinks the call failed
                throw new StorePublisherException("RESPONSE_LOST", "response lost", true);
            }

            @Override
            public StorePublisher.R1Acceptance issueR1(String b0ClosureFingerprint,
                                                       String b1ReceiptFingerprint,
                                                       String idempotencyKey,
                                                       String owner) {
                return new StorePublisher.R1Acceptance(issuer(), owner);
            }

            @Override
            public StorePublisher.B1Acceptance queryB1(String idempotencyKey) {
                queryCount.incrementAndGet();
                return savedAcceptance.get();
            }
        };

        FencingAuthority fencing = (s, a, w) -> new FencingAuthority.FencingToken(
                ("ftok-" + s.substring(7, 10)).getBytes(),
                sha256Of("tok" + s), 1L);

        EvidenceProducer producer = (s, c, w) -> {
            Path b0Dir = w.resolve("b0");
            Path er = b0Dir.resolve("evidence-root");
            try {
                Files.createDirectories(er);
                Files.writeString(er.resolve("evidence.txt"), "evidence");
            } catch (IOException e) {
                throw new EvidenceProducer.EvidenceProducerException("IO", "producer IO failed", e);
            }
            return new EvidenceProducer.SealedEvidenceCandidate(er, c, 1);
        };

        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(fencing, producer, store);

        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);

        // Fresh: publishB1 throws recoverable after saving acceptance
        Verdict first = ept.execute(new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir));

        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(first.closedCategory()).isEqualTo(ClosedCategory.UNAVAILABLE);

        Path committed = commitDir.resolve(stableHex);
        assertThat(committed.resolve("b1-receipt.json")).doesNotExist();

        // Retry: queryB1 finds the saved acceptance; no re-publish
        Verdict retry = ept.execute(new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir));

        assertThat(retry.outcome()).isEqualTo(Verdict.Outcome.RECOVERED);
        assertThat(retry.b0Receipt()).isNotNull();

        // publishB1 called exactly once; queryB1 called at least once
        assertThat(publishCount.get()).isEqualTo(1);
        assertThat(queryCount.get()).isGreaterThanOrEqualTo(1);

        // B1 and R1 now present
        assertThat(committed.resolve("b1-receipt.json")).isRegularFile();
        assertThat(committed.resolve("r1-receipt.json")).isRegularFile();
    }

    // -------------------------------------------------------------------------
    // R03: issueR1 throws recoverable first; B1 exists locally; retry re-issues R1 only
    // -------------------------------------------------------------------------

    /**
     * R03: B1 is committed.  issueR1 throws recoverable.
     * On retry: queryB1 and publishB1 must NOT be called.
     * Only issueR1 is called again.
     * After recovery: verdict = RECOVERED; B1 bytes unchanged.
     */
    @Test
    void r03_issueR1ThrowsRecoverable_retryOnlyReissuesR1()
            throws Exception {
        AtomicInteger publishB1Count = new AtomicInteger(0);
        AtomicInteger queryB1Count = new AtomicInteger(0);
        AtomicInteger issueR1Count = new AtomicInteger(0);

        StorePublisher store = new StorePublisher() {
            @Override public String issuer() { return "r1-fail-store"; }

            @Override
            public StorePublisher.B1Acceptance publishB1(String b0ClosureFingerprint,
                                                         String idempotencyKey) {
                publishB1Count.incrementAndGet();
                return new StorePublisher.B1Acceptance(issuer(), idempotencyKey);
            }

            @Override
            public StorePublisher.R1Acceptance issueR1(String b0ClosureFingerprint,
                                                        String b1ReceiptFingerprint,
                                                        String idempotencyKey,
                                                        String owner) {
                issueR1Count.incrementAndGet();
                if (issueR1Count.get() == 1) {
                    // First call throws recoverable
                    throw new StorePublisherException("R1_UNAVAILABLE", "r1 unavailable", true);
                }
                return new StorePublisher.R1Acceptance(issuer(), owner);
            }

            @Override
            public StorePublisher.B1Acceptance queryB1(String idempotencyKey) {
                queryB1Count.incrementAndGet();
                return null;
            }
        };

        FencingAuthority fencing = (s, a, w) -> new FencingAuthority.FencingToken(
                ("ftok-" + s.substring(7, 10)).getBytes(),
                sha256Of("tok" + s), 1L);

        EvidenceProducer producer = (s, c, w) -> {
            Path b0Dir = w.resolve("b0");
            Path er = b0Dir.resolve("evidence-root");
            try {
                Files.createDirectories(er);
                Files.writeString(er.resolve("b1-verify.txt"), "b1-presence-check");
            } catch (IOException e) {
                throw new EvidenceProducer.EvidenceProducerException("IO", "producer IO failed", e);
            }
            return new EvidenceProducer.SealedEvidenceCandidate(er, c, 1);
        };

        CapabilityStudioEvidencePublicationTransaction ept =
                new CapabilityStudioEvidencePublicationTransaction(fencing, producer, store);

        String expectedStable = ept.deriveStableRequestId(FP1, FP2, FP3, FP4, FP5, FP6);
        String stableHex = expectedStable.substring(7);

        // Fresh: B1 committed, R1 fails
        Verdict first = ept.execute(new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir));

        assertThat(first.outcome()).isEqualTo(Verdict.Outcome.CLOSED);
        assertThat(first.closedCategory()).isEqualTo(ClosedCategory.UNAVAILABLE);

        Path committed = commitDir.resolve(stableHex);

        // B1 present, R1 absent
        assertThat(committed.resolve("b1-receipt.json")).isRegularFile();
        assertThat(committed.resolve("r1-receipt.json")).doesNotExist();

        // Capture B1 bytes and evidence bytes for unchanged verification
        byte[] b1BytesBefore = Files.readAllBytes(committed.resolve("b1-receipt.json"));
        byte[] evidenceBytesBefore =
                Files.readAllBytes(committed.resolve("b0/evidence-root/b1-verify.txt"));

        // publishB1 called once (first execute), issueR1 called once (failed)
        assertThat(publishB1Count.get()).isEqualTo(1);
        assertThat(issueR1Count.get()).isEqualTo(1);

        // Retry: only issueR1 called again; queryB1 and publishB1 NOT called
        Verdict retry = ept.execute(new Request(
                expectedStable, NONCE, FP1, FP2, FP3, FP4, FP5, FP6,
                null, URI.create("file:///tmp/private"),
                workDir, commitDir));

        assertThat(retry.outcome()).isEqualTo(Verdict.Outcome.RECOVERED);
        assertThat(retry.b0Receipt()).isNotNull();

        // publishB1 still called exactly once; issueR1 now called twice
        assertThat(publishB1Count.get()).isEqualTo(1);
        assertThat(issueR1Count.get()).isEqualTo(2);
        assertThat(queryB1Count.get()).isEqualTo(0);

        // B1 bytes unchanged
        byte[] b1BytesAfter = Files.readAllBytes(committed.resolve("b1-receipt.json"));
        byte[] evidenceBytesAfter =
                Files.readAllBytes(committed.resolve("b0/evidence-root/b1-verify.txt"));
        assertThat(b1BytesAfter).isEqualTo(b1BytesBefore);
        assertThat(evidenceBytesAfter).isEqualTo(evidenceBytesBefore);
    }

    // -------------------------------------------------------------------------
    // R04: Two EPT instances, same committedRoot, different workingDir, concurrent
    // -------------------------------------------------------------------------

    /**
     * R04: Two EPT instances share committedRoot but have different workingDir.
     * They are started concurrently via CountDownLatch.
     * One completes COMMITTED, the other RECOVERED.
     * Shared thread-safe counters confirm each fresh side effect fires exactly once.
     * Files are strict-verified; no overwrites or lost data.
     */
    @Test
    void r04_twoEptInstances_concurrentSameCommittedRoot_oneCommittedOneRecovered()
            throws Exception {
        Path workA = Files.createTempDirectory(sharedTemp, "work-a");
        Path workB = Files.createTempDirectory(sharedTemp, "work-b");
        Files.setPosixFilePermissions(workA,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(workB,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));

        AtomicInteger fencingCallsShared = new AtomicInteger(0);
        AtomicInteger producerCallsShared = new AtomicInteger(0);
        AtomicInteger publishB1CallsShared = new AtomicInteger(0);
        AtomicInteger issueR1CallsShared = new AtomicInteger(0);

        StorePublisher sharedStore = new StorePublisher() {
            @Override public String issuer() { return "shared-store"; }

            @Override
            public StorePublisher.B1Acceptance publishB1(String b0ClosureFingerprint,
                                                         String idempotencyKey) {
                publishB1CallsShared.incrementAndGet();
                return new StorePublisher.B1Acceptance(issuer(), idempotencyKey);
            }

            @Override
            public StorePublisher.R1Acceptance issueR1(String b0ClosureFingerprint,
                                                        String b1ReceiptFingerprint,
                                                        String idempotencyKey,
                                                        String owner) {
                issueR1CallsShared.incrementAndGet();
                return new StorePublisher.R1Acceptance(issuer(), owner);
            }

            @Override
            public StorePublisher.B1Acceptance queryB1(String idempotencyKey) {
                return null;
            }
        };

        FencingAuthority sharedFencing = (stableReq, authFp, work) -> {
            fencingCallsShared.incrementAndGet();
            return new FencingAuthority.FencingToken(
                    ("ftok-" + stableReq.substring(7, 10)).getBytes(),
                    sha256Of("tok" + stableReq), 1L);
        };

        EvidenceProducer sharedProducer = (stableReq, candFp, work) -> {
            producerCallsShared.incrementAndGet();
            // EPT passes its own active-* attempt dir as work — do NOT name output after it.
            // Write one fixed file so concurrent winner/follower assertions are stable.
            Path b0Dir = work.resolve("b0");
            Path er = b0Dir.resolve("evidence-root");
            try {
                Files.createDirectories(er);
                Files.writeString(er.resolve("winner.txt"), "produced-by-shared");
            } catch (IOException e) {
                throw new EvidenceProducer.EvidenceProducerException("IO", "producer IO failed", e);
            }
            return new EvidenceProducer.SealedEvidenceCandidate(er, candFp, 1);
        };

        // Build EPT instances first so we can derive the stable ID via the real method.
        CapabilityStudioEvidencePublicationTransaction eptA =
                new CapabilityStudioEvidencePublicationTransaction(
                        sharedFencing, sharedProducer, sharedStore);
        CapabilityStudioEvidencePublicationTransaction eptB =
                new CapabilityStudioEvidencePublicationTransaction(
                        sharedFencing, sharedProducer, sharedStore);

        // Same stable ID (derived from six semantic fingerprints), same nonce → exact retry path.
        // EPT-CN02: same stable + same nonce on retry → CLOSED(CONFLICT), not RECOVERED.
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
        startLatch.countDown(); // both start simultaneously
        tA.join(30_000);
        tB.join(30_000);

        assertThat(exA.get()).isNull();
        assertThat(exB.get()).isNull();

        Verdict.Outcome outcomeA = verdictA.get().outcome();
        Verdict.Outcome outcomeB = verdictB.get().outcome();

        // Exactly one COMMITTED, one RECOVERED (use list for multi-element check)
        assertThat(List.of(outcomeA, outcomeB))
                .as("exactly one COMMITTED and one RECOVERED")
                .containsExactlyInAnyOrder(
                        Verdict.Outcome.COMMITTED, Verdict.Outcome.RECOVERED);

        // Each fresh side effect fires exactly once total
        assertThat(fencingCallsShared.get())
                .as("fencing called once total")
                .isEqualTo(1);
        assertThat(producerCallsShared.get())
                .as("producer called once total")
                .isEqualTo(1);
        assertThat(publishB1CallsShared.get())
                .as("publishB1 called once total")
                .isEqualTo(1);
        assertThat(issueR1CallsShared.get())
                .as("issueR1 called once total")
                .isEqualTo(1);

        // Producer called once → winner produced exactly ONE evidence file.
        String stableHex = expectedStable.substring(7);
        Path committed = commitDir.resolve(stableHex);
        Path evidenceRoot = committed.resolve("b0/evidence-root");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:winner.txt");
        List<Path> winnerFiles;
        try (var stream = Files.list(evidenceRoot)) {
            winnerFiles = stream.filter(p -> matcher.matches(p.getFileName())).toList();
        }
        assertThat(winnerFiles)
                .as("exactly one winner.txt exists under evidence-root (single producer call)")
                .hasSize(1);

        assertThat(committed.resolve("b1-receipt.json")).isRegularFile();
        assertThat(committed.resolve("r1-receipt.json")).isRegularFile();
        assertThat(committed.resolve("b0-inner-manifest.json")).isRegularFile();

        // Both verdicts reference the same B0 closure and external fingerprints
        Verdict winner = outcomeA == Verdict.Outcome.COMMITTED
                ? verdictA.get() : verdictB.get();
        Verdict follower = outcomeA == Verdict.Outcome.RECOVERED
                ? verdictA.get() : verdictB.get();

        assertThat(winner.b0Receipt().b0ClosureFingerprint())
                .isEqualTo(follower.b0Receipt().b0ClosureFingerprint());
        assertThat(winner.b0Receipt().b1ReceiptFingerprint())
                .isEqualTo(follower.b0Receipt().b1ReceiptFingerprint());
        assertThat(winner.b0Receipt().r1Fingerprint())
                .isEqualTo(follower.b0Receipt().r1Fingerprint());
    }

    // -------------------------------------------------------------------------
    // R05: Different stable IDs on same committedRoot can complete concurrently
    // -------------------------------------------------------------------------

    /**
     * R05: Two different stable IDs are executed concurrently on the same committedRoot.
     * Uses a concurrent producer that tracks maximum simultaneous active attempts (>= 2).
     * CountDownLatches ensure both start simultaneously rather than relying on timing.
     * Both complete successfully; no overwrites; evidence files from both survive.
     */
    @Test
    void r05_differentStableIds_concurrentSameCommittedRoot_bothComplete()
            throws Exception {
        Path workC = Files.createTempDirectory(sharedTemp, "work-c");
        Path workD = Files.createTempDirectory(sharedTemp, "work-d");
        Files.setPosixFilePermissions(workC,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(workD,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));

        AtomicInteger activeProducers = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        // CountDownLatches ensure both producers enter the critical section simultaneously
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        EvidenceProducer concurrentProducer = (stableReq, candFp, work) -> {
            int before = activeProducers.incrementAndGet();
            maxConcurrent.updateAndGet(cur -> Math.max(cur, activeProducers.get()));
            try {
                if (before == 1) {
                    firstEntered.countDown();
                } else {
                    secondEntered.countDown();
                }
                // Wait for the other producer to also enter before proceeding (10 s timeout → clear failure)
                try {
                    firstEntered.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new EvidenceProducer.EvidenceProducerException(
                            "BARRIER_TIMEOUT", "firstEntered latch timed out after 10 s", e);
                }
                try {
                    secondEntered.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new EvidenceProducer.EvidenceProducerException(
                            "BARRIER_TIMEOUT", "secondEntered latch timed out after 10 s", e);
                }
                Files.createDirectories(work.resolve("b0").resolve("evidence-root"));
                // Filename derives from stableReq (the key EPT-CN02 cares about), so concurrent writers
                // with different stableIds create different files — no overwriting risk.
                String fileName = "stable-" + stableReq.substring(7, 16) + ".txt";
                Files.writeString(work.resolve("b0").resolve("evidence-root").resolve(fileName), "produced-" + fileName);
                return new EvidenceProducer.SealedEvidenceCandidate(
                        work.resolve("b0").resolve("evidence-root"), candFp, 1);
            } catch (IOException e) {
                throw new EvidenceProducer.EvidenceProducerException("IO", "producer IO failed", e);
            } finally {
                activeProducers.decrementAndGet();
            }
        };

        FencingAuthority countingFencing = (s, a, w) ->
                new FencingAuthority.FencingToken(
                        ("ftok-" + s.substring(7, 10)).getBytes(),
                        sha256Of("tok" + s), 1L);

        StorePublisher simpleStore = new StorePublisher() {
            @Override public String issuer() { return "concurrent-store"; }
            @Override
            public StorePublisher.B1Acceptance publishB1(String b0ClosureFingerprint,
                                                         String idempotencyKey) {
                return new StorePublisher.B1Acceptance(issuer(), idempotencyKey);
            }
            @Override
            public StorePublisher.R1Acceptance issueR1(String b0ClosureFingerprint,
                                                        String b1ReceiptFingerprint,
                                                        String idempotencyKey,
                                                        String owner) {
                return new StorePublisher.R1Acceptance(issuer(), owner);
            }
            @Override
            public StorePublisher.B1Acceptance queryB1(String idempotencyKey) {
                return null;
            }
        };

        // Authority fingerprints for deriving two genuinely different stable IDs.
        String authC = sha256Of("authC");
        String authD = sha256Of("authD");

        CapabilityStudioEvidencePublicationTransaction eptC =
                new CapabilityStudioEvidencePublicationTransaction(
                        countingFencing, concurrentProducer, simpleStore);
        CapabilityStudioEvidencePublicationTransaction eptD =
                new CapabilityStudioEvidencePublicationTransaction(
                        countingFencing, concurrentProducer, simpleStore);

        // Derive stable IDs after EPT instances exist (each calls its own deriveStableRequestId).
        // Derive stable IDs: authC/authD = authorityInput; FP2 = targetInput; FP3–FP6 = plan…candidate
        String stableC = eptC.deriveStableRequestId(authC, FP2, FP3, FP4, FP5, FP6);
        String stableD = eptD.deriveStableRequestId(authD, FP2, FP3, FP4, FP5, FP6);

        CyclicBarrier startBarrier = new CyclicBarrier(2);
        AtomicReference<Verdict> vC = new AtomicReference<>();
        AtomicReference<Verdict> vD = new AtomicReference<>();
        AtomicReference<Throwable> exC = new AtomicReference<>();
        AtomicReference<Throwable> exD = new AtomicReference<>();

        Thread tC = new Thread(() -> {
            try {
                startBarrier.await();
                vC.set(eptC.execute(new Request(
                        stableC, sha256Of("nonce-c"), authC, FP2, FP3, FP4, FP5, FP6,
                        null, URI.create("file:///tmp/private"),
                        workC, commitDir)));
            } catch (Throwable t) { exC.set(t); }
        });
        Thread tD = new Thread(() -> {
            try {
                startBarrier.await();
                vD.set(eptD.execute(new Request(
                        stableD, sha256Of("nonce-d"), authD, FP2, FP3, FP4, FP5, FP6,
                        null, URI.create("file:///tmp/private"),
                        workD, commitDir)));
            } catch (Throwable t) { exD.set(t); }
        });

        tC.start();
        tD.start();
        tC.join(30_000);
        tD.join(30_000);

        // Fail fast if threads are still alive (barrier/await deadlock) to avoid NPE on null verdictRef.
        assertThat(tC.isAlive())
                .as("Thread C must have terminated within 30 s (check for deadlock or slow recovery)")
                .isFalse();
        assertThat(tD.isAlive())
                .as("Thread D must have terminated within 30 s (check for deadlock or slow recovery)")
                .isFalse();

        // Surface any uncaught exception before reading verdictRef
        assertThat(exC.get())
                .as("Thread C must not throw")
                .isNull();
        assertThat(exD.get())
                .as("Thread D must not throw")
                .isNull();

        // verdictRef is null if thread was interrupted or didn't reach execute() — fail clearly.
        assertThat(vC.get())
                .as("vC verdictRef must not be null (thread must have completed execute)")
                .isNotNull();
        assertThat(vD.get())
                .as("vD verdictRef must not be null (thread must have completed execute)")
                .isNotNull();

        // Both complete successfully
        assertThat(vC.get().outcome()).isEqualTo(Verdict.Outcome.COMMITTED);
        assertThat(vD.get().outcome()).isEqualTo(Verdict.Outcome.COMMITTED);

        // Max concurrent active producers >= 2 (both ran simultaneously)
        assertThat(maxConcurrent.get())
                .as("max concurrent producers >= 2 (both ran simultaneously)")
                .isGreaterThanOrEqualTo(2);

        // Both committed dirs created under same parent committedRoot (but different stable-hex subdirectories).
        // Derive paths dynamically from stableC/stableD (same logic as producer uses for filename).
        String hexC = stableC.substring(7);
        String hexD = stableD.substring(7);
        Path commitC = commitDir.resolve(hexC);
        Path commitD = commitDir.resolve(hexD);
        String fileNameC = "stable-" + hexC.substring(0, 9) + ".txt";
        String fileNameD = "stable-" + hexD.substring(0, 9) + ".txt";
        assertThat(commitC.resolve("b0/evidence-root").resolve(fileNameC)).isRegularFile();
        assertThat(commitD.resolve("b0/evidence-root").resolve(fileNameD)).isRegularFile();

        // Neither overwrote the other's evidence
        assertThat(Files.exists(commitD.resolve("b0/evidence-root").resolve(fileNameC))).isFalse();
        assertThat(Files.exists(commitC.resolve("b0/evidence-root").resolve(fileNameD))).isFalse();
    }

    // -------------------------------------------------------------------------
    // Thread-safe test doubles
    // -------------------------------------------------------------------------

    /**
     * Thread-safe FailableStorePublisher.
     *
     * <p>Key behaviour for idempotency-key-scoped failure:
     * publishB1 throws on the <b>first</b> call per idempotency key, succeeds on retry
     * (same idempotency key → same idempotency key → idempotent).  This matches the
     * EPT's internal retry semantics where the same idempotency key is used on both
     * the failing attempt and the recovery attempt.</p>
     *
     * <p>All mutable state is accessed via atomic counters.</p>
     */
    static final class FailableStorePublisher implements StorePublisher {

        private final String issuerValue;
        private final boolean failOnFirstPublishB1;
        private final AtomicInteger publishB1CallCount = new AtomicInteger(0);
        private final AtomicInteger queryB1CallCount = new AtomicInteger(0);
        private final AtomicInteger issueR1CallCount = new AtomicInteger(0);
        private final AtomicReference<StorePublisher.B1Acceptance> lastAcceptance =
                new AtomicReference<>();

        FailableStorePublisher(String issuer, boolean failOnFirstPublishB1) {
            this.issuerValue = issuer;
            this.failOnFirstPublishB1 = failOnFirstPublishB1;
        }

        static final class Builder {
            private String issuer = "failable-store";
            private boolean failOnFirstPublishB1 = false;
            Builder issuer(String v) { this.issuer = v; return this; }
            Builder failOnFirstPublishB1(boolean v) { this.failOnFirstPublishB1 = v; return this; }
            FailableStorePublisher build() {
                return new FailableStorePublisher(issuer, failOnFirstPublishB1);
            }
        }

        @Override public String issuer() { return issuerValue; }

        @Override
        public StorePublisher.B1Acceptance publishB1(String b0ClosureFingerprint,
                                                     String idempotencyKey) {
            int count = publishB1CallCount.incrementAndGet();
            StorePublisher.B1Acceptance acc =
                    new StorePublisher.B1Acceptance(issuerValue, idempotencyKey);
            lastAcceptance.set(acc);
            if (failOnFirstPublishB1 && count == 1) {
                // Throw recoverable: idempotent store will accept retry for same idempotency key
                throw new StorePublisherException("B1_FAIL", "b1 unavailable", true);
            }
            return acc;
        }

        @Override
        public StorePublisher.R1Acceptance issueR1(String b0ClosureFingerprint,
                                                    String b1ReceiptFingerprint,
                                                    String idempotencyKey,
                                                    String owner) {
            issueR1CallCount.incrementAndGet();
            return new StorePublisher.R1Acceptance(issuerValue, owner);
        }

        @Override
        public StorePublisher.B1Acceptance queryB1(String idempotencyKey) {
            queryB1CallCount.incrementAndGet();
            return lastAcceptance.get();
        }

        int publishB1Calls() { return publishB1CallCount.get(); }
        int queryB1Calls() { return queryB1CallCount.get(); }
        int issueR1Calls() { return issueR1CallCount.get(); }
    }
}
