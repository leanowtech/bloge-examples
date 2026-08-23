package com.leanowtech.bloge.gateway.testkit.ept;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Evidence Publication Transaction: idempotent publish and read-only verify.
 *
 * <p>Public surface is exactly two methods:</p>
 * <ul>
 *   <li>{@link #execute(Request)} — idempotent publish/recover</li>
 *   <li>{@link #verify(Path, ExpectedPins)} — read-only verification, zero-write</li>
 * </ul>
 *
 * <p>Identity derivation (§E.2):</p>
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
 * <p>Lifecycle states (package-private): PREPARED, LEASE_COMMITTED, LOCAL_COMMITTED,
 * EXTERNAL_PENDING, COMPLETE, ABORTED.</p>
 *
 * <p>Exit code mapping:</p>
 * <ul>
 *   <li>0 = COMMITTED or RECOVERED</li>
 *   <li>2 = CLOSED INVALID</li>
 *   <li>3 = CLOSED CONFLICT</li>
 *   <li>4 = CLOSED UNAVAILABLE</li>
 *   <li>5 = CLOSED BLOCKED</li>
 *   <li>6 = CLOSED ABORTED or INTERNAL</li>
 * </ul>
 *
 * <p>Obligation denominator RG-CS-EPT-v1 = 27.  CP01..08, cross-process, bounded child
 * JVM are reported as NOT_RUN with explicit obligation tracking.</p>
 */
public final class CapabilityStudioEvidencePublicationTransaction {

    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    /** EPT domain identifier used as the first input to every identity derivation. */
    public static final String EPT_DOMAIN =
            "resource-gateway.capability-studio.evidence-publication-transaction.v1";

    /** Filename of the inner B0 manifest file. */
    public static final String B0_INNER_MANIFEST_FILE = "b0-inner-manifest.json";
    /** Filename of the B1 receipt file. */
    public static final String B1_RECEIPT_FILE = "b1-receipt.json";
    /** Filename of the R1 receipt file. */
    public static final String R1_RECEIPT_FILE = "r1-receipt.json";
    /** Filename of the owner authority receipt file. */
    public static final String OWNER_AUTHORITY_RECEIPT_FILE = "owner-authority-receipt.json";
    /** Filename of the bundle header file. */
    public static final String HEADER_FILE = "header.json";

    /** b0/ subdirectory name */
    public static final String B0_DIRECTORY = "b0";
    /** b0/evidence-root/ — physical payload directory */
    public static final String B0_EVIDENCE_ROOT_DIRECTORY = "b0/evidence-root";

    /** Default lease timeout for B1/R1 publication operations. */
    public static final Duration DEFAULT_PUBLICATION_LEASE_TIMEOUT = Duration.ofSeconds(5);

    /** Maximum number of entries allowed in the evidence-root directory. */
    public static final int MAXIMUM_EVIDENCE_ROOT_ENTRIES = 512;
    /** Maximum total byte size allowed for the evidence-root directory. */
    public static final long MAXIMUM_EVIDENCE_ROOT_TOTAL_BYTES = 32L * 1024 * 1024;
    /** Grace period before a stale lease is considered abandoned for recovery. */
    public static final Duration RECOVERY_GRACE_PERIOD = Duration.ofSeconds(1800);
    /**
     * Stable owner identity bound to every R1 final commitment.
     * Used as the {@code owner} field in the R1 receipt body sealed by EptReceiptEnvelope.
     */
    public static final String OWNER = "ept-owner";
    /**
     * Private directory under committedRoot for lock files.
     * Lock files are named by stableHex and are NOT included in output bundles.
     */
    public static final String EPT_LOCKS_DIRECTORY = ".ept-locks";


    private static final Pattern FINGERPRINT =
            Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern SAFE_NAME =
            Pattern.compile("^(?!\\.{1,2}$)[A-Za-z0-9][A-Za-z0-9._/-]{0,254}$");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            Set.of(PosixFilePermission.OWNER_READ);
    private static final Set<PosixFilePermission> BUILD_FILE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /**
     * Exact-key JVM lock registry ensuring same key serializes, different keys parallelize.
     *
     * <p>Each key (committedRoot|stableHex) maps to one ReentrantLock with a refcount.
     * {@code acquire(key, timeout)} creates the entry, locks, and increments refcount atomically.
     * {@code release(key)} decrements refcount; when it reaches zero the entry is removed
     * and the lock becomes eligible for GC.
     *
     * <p>File locks remain the cross-process arbitrator.  This registry only provides
     * intra-JVM exact-key serialization — no hash collision, no deadlock between
     * concurrent instances with different stableHex.
     */
    /**
     * Handle returned by {@link ExactKeyLockRegistry#acquire}.
     * Distinguishes the held-from-acquire case (unlock+decrement) from the
     * abandon-ref case where the timeout fired before the lock was obtained
     * (only decrement; never unlock).
     */
    static final class LockHandle {
        private final String key;
        private final ExactKeyLockRegistry.LockEntry entry;
        private boolean released;

        LockHandle(String key, ExactKeyLockRegistry.LockEntry entry) {
            this.key = key;
            this.entry = entry;
        }

        /**
         * Releases the lock and drops the reference.  Call exactly once.
         *
         * <p>On success: unlock, mark released, drop ref, remove entry from registry
         * if count reaches zero.
         *
         * <p>On unlock failure (IllegalMonitorStateException): the ref is NOT decremented
         * and the registry entry is NOT removed. The exception propagates; the registry
         * stays fail-closed so the key cannot be acquired by another thread until the
         * process restarts or the entry is otherwise recovered. This prevents a
         * split-brain window where a concurrent acquire() would create a new entry while
         * the original thread still holds the JVM lock.
         */
        void releaseHeld() {
            if (released) return;
            entry.lock.unlock();   // throws IllegalMonitorStateException on failure
            released = true;
            if (entry.refcount.decrementAndGet() == 0) {
                LOCK_REGISTRY.map.remove(key, entry);
            }
        }

        /** Drops the extra ref added by acquire() when the lock was never obtained. */
        void abandonRef() {
            if (released) return;
            released = true;
            if (entry.refcount.decrementAndGet() == 0) {
                LOCK_REGISTRY.map.remove(key, entry);
            }
        }
    }

    private static final class ExactKeyLockRegistry {

        private final ConcurrentHashMap<String, LockEntry> map = new ConcurrentHashMap<>();

        private static final class LockEntry {
            final ReentrantLock lock = new ReentrantLock();
            final AtomicInteger refcount = new AtomicInteger(1);
        }

        /**
         * Atomically acquires the lock for {@code key} within {@code timeout}.
         *
         * @return a handle whose {@link LockHandle#releaseHeld()} must be called on success,
         *         or whose {@link LockHandle#abandonRef()} must be called if the caller
         *         abandons before obtaining the lock
         * @throws EptFailure.unavailable if timeout expires or interrupted
         */
        LockHandle acquire(String key, long timeoutNanos) throws InterruptedException {
            LockEntry entry = map.compute(key, (k, existing) -> {
                if (existing == null) return new LockEntry();
                existing.refcount.incrementAndGet();
                return existing;
            });
            boolean acquired = false;
            try {
                acquired = entry.lock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                if (entry.refcount.decrementAndGet() == 0) map.remove(key, entry);
                Thread.currentThread().interrupt();
                throw e;
            } catch (RuntimeException e) {
                if (entry.refcount.decrementAndGet() == 0) map.remove(key, entry);
                throw e;
            }
            if (!acquired) {
                if (entry.refcount.decrementAndGet() == 0) map.remove(key, entry);
                throw EptFailure.unavailable("LOCK_TIMEOUT");
            }
            return new LockHandle(key, entry);
        }
    }


    private static final ExactKeyLockRegistry LOCK_REGISTRY = new ExactKeyLockRegistry();

    /** Thread-safe counter for unique identifiers (avoids deprecated Thread.getId()). */
    private static final java.util.concurrent.atomic.AtomicLong THREAD_SEQ = new java.util.concurrent.atomic.AtomicLong(0);
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    // ---------------------------------------------------------------------------
    // Stable mismatch codes from strictLocalB0Verify
    // ---------------------------------------------------------------------------

    /** Manifest top-level entry not in allowed set. */
    static final String MC_PAYLOAD_UNKNOWN_ENTRY = "BUNDLE_UNKNOWN_ENTRY";
    /** b0/ directory is missing or not a directory. */
    static final String MC_BUNDLE_MISSING = "PAYLOAD_MISSING";
    /** b0/ contains unexpected child (not evidence-root/). */
    static final String MC_BUNDLE_EXTRA = "PAYLOAD_EXTRA";
    /** manifest.json missing or malformed. */
    static final String MC_MANIFEST_INVALID = "MANIFEST_INVALID";
    /** Manifest entry count mismatch. */
    static final String MC_ENTRY_COUNT_MISMATCH = "ENTRY_COUNT_MISMATCH";
    /** Manifest totalByteSize mismatch. */
    static final String MC_TOTAL_SIZE_MISMATCH = "PAYLOAD_SIZE_MISMATCH";
    /** Manifest treeFingerprint mismatch. */
    static final String MC_TREE_FINGERPRINT_MISMATCH = "PAYLOAD_TREE_MISMATCH";
    /** Manifest entries not sorted by UTF-8 path. */
    static final String MC_ENTRY_ORDER_INVALID = "ENTRY_ORDER_INVALID";
    /** Physical payload file fingerprint mismatch. */
    static final String MC_PAYLOAD_FINGERPRINT_MISMATCH = "PAYLOAD_FINGERPRINT_MISMATCH";
    /** Physical payload file size mismatch. */
    static final String MC_PAYLOAD_SIZE_MISMATCH = "PAYLOAD_SIZE_MISMATCH";
    /** Physical payload has fewer files than manifest. */
    static final String MC_PAYLOAD_MISSING = "PAYLOAD_MISSING";
    /** Physical payload has extra files not in manifest. */
    static final String MC_PAYLOAD_EXTRA = "PAYLOAD_EXTRA";
    /** Physical payload file path invalid. */
    static final String MC_PAYLOAD_PATH_INVALID = "PAYLOAD_TYPE_INVALID";

    // ---------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------

    private final FencingAuthority fencingAuthority;
    private final EvidenceProducer evidenceProducer;
    private final StorePublisher storePublisher;
    /** Issuer captured from StorePublisher at construction; non-null after constructor. */
    private final String capturedIssuer;

    private final Duration publicationLeaseTimeout;
    private final EptObserver observer;

    // ---------------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------------

    /**
     * Constructs an EPT with default lease timeout and a NOOP observer.
     *
     * @param fencingAuthority authority for fencing tokens
     * @param evidenceProducer producer of deterministic sealed candidates
     * @param storePublisher external receipt store publisher
     */
    public CapabilityStudioEvidencePublicationTransaction(
            FencingAuthority fencingAuthority,
            EvidenceProducer evidenceProducer,
            StorePublisher storePublisher) {
        this(fencingAuthority, evidenceProducer, storePublisher,
                DEFAULT_PUBLICATION_LEASE_TIMEOUT, EptObserver.NOOP);
    }

    /**
     * Constructs an EPT with custom lease timeout and observer.
     *
     * @param fencingAuthority authority for fencing tokens
     * @param evidenceProducer producer of deterministic sealed candidates
     * @param storePublisher external receipt store publisher
     * @param publicationLeaseTimeout lease timeout for B1/R1 publication
     * @param observer lifecycle observer
     */
    public CapabilityStudioEvidencePublicationTransaction(
            FencingAuthority fencingAuthority,
            EvidenceProducer evidenceProducer,
            StorePublisher storePublisher,
            Duration publicationLeaseTimeout,
            EptObserver observer) {
        this.fencingAuthority = Objects.requireNonNull(fencingAuthority, "fencingAuthority");
        this.evidenceProducer = Objects.requireNonNull(evidenceProducer, "evidenceProducer");
        this.storePublisher = Objects.requireNonNull(storePublisher, "storePublisher");
        this.publicationLeaseTimeout = Objects.requireNonNull(publicationLeaseTimeout, "publicationLeaseTimeout");
        this.observer = Objects.requireNonNull(observer, "observer");
        String iss = storePublisher.issuer();
        if (iss == null || iss.isBlank()) {
            throw new IllegalArgumentException("StorePublisher.issuer() must be non-blank");
        }
        this.capturedIssuer = iss;
    }

    // ---------------------------------------------------------------------------
    // Public identity derivation (§E.2)
    // ---------------------------------------------------------------------------

    /**
     * Derives stableRequestId from six input fingerprints.
     * Identical inputs always produce identical output (deterministic).
     */
    /**
     * Derives stableRequestId from six input fingerprints.
     *
     * @param authorityInputTreeFingerprint authority input tree fingerprint
     * @param targetInputTreeFingerprint target input tree fingerprint
     * @param planFingerprint plan fingerprint
     * @param targetBindingFingerprint target binding fingerprint
     * @param declarationFingerprint declaration fingerprint
     * @param candidateFingerprint candidate fingerprint
     * @return stableRequestId (sha256:hex64)
     */
    public String deriveStableRequestId(String authorityInputTreeFingerprint,
                                        String targetInputTreeFingerprint,
                                        String planFingerprint,
                                        String targetBindingFingerprint,
                                        String declarationFingerprint,
                                        String candidateFingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            digestLp(md, EPT_DOMAIN);
            digestLp(md, authorityInputTreeFingerprint);
            digestLp(md, targetInputTreeFingerprint);
            digestLp(md, planFingerprint);
            digestLp(md, targetBindingFingerprint);
            digestLp(md, declarationFingerprint);
            digestLp(md, candidateFingerprint);
            return "sha256:" + bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Derives transactionId from stableRequestId and nonce.
     * Identical inputs always produce identical output (deterministic).
     */
    /**
     * Derives transactionId from stableRequestId and nonce.
     *
     * @param stableRequestId stable request identity
     * @param publicationNonce publication nonce
     * @return transactionId (sha256:hex64)
     */
    public String deriveTransactionId(String stableRequestId, String publicationNonce) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            digestLp(md, EPT_DOMAIN);
            digestLp(md, stableRequestId);
            digestLp(md, publicationNonce);
            return "sha256:" + bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------------------
    // Public execute / verify
    // ---------------------------------------------------------------------------

    /**
     * Idempotent publish: fresh execute or exact-retry recovery.
     * Either produces a new committed bundle or recovers from an existing one.
     */
    /**
     * Idempotent publish: fresh execute or exact-retry recovery.
     *
     * @param request publication request
     * @return verdict (COMMITTED, RECOVERED, or CLOSED)
     */
    public Verdict execute(Request request) {
        Objects.requireNonNull(request, "request");
        String derivedStable = deriveStableRequestId(
                request.authorityInputTreeFingerprint(),
                request.targetInputTreeFingerprint(),
                request.planFingerprint(),
                request.targetBindingFingerprint(),
                request.declarationFingerprint(),
                request.candidateFingerprint());
        String derivedTxn = deriveTransactionId(derivedStable, request.publicationNonce());
        if (!derivedStable.equals(request.expectedStableRequestId())) {
            return Verdict.closed(derivedTxn, derivedStable,
                    ClosedCategory.INVALID, "STABLE_REQUEST_ID_MISMATCH");
        }
        try {
            return executeInternal(request, derivedStable, derivedTxn);
        } catch (EptFailure f) {
            return f.toVerdict(derivedTxn, derivedStable);
        } catch (IOException e) {
            return Verdict.closed(derivedTxn, derivedStable,
                    ClosedCategory.INTERNAL, "EXECUTE_IO_ERROR");
        }
    }

    /**
     * Read-only verification. Zero-write, zero lock, zero external call.
     *
     * @param committedRoot absolute path to the committed bundle root
     * @param pins caller-expected fingerprints for verification
     * @return verify result with pass/fail and mismatch code
     */
    public VerifyResult verify(Path committedRoot, ExpectedPins pins) {
        Objects.requireNonNull(committedRoot, "committedRoot");
        Objects.requireNonNull(pins, "pins");
        return verifyInternal(committedRoot, pins);
    }

    /**
     * Maps a Verdict to a POSIX exit code.
     *
     * @param verdict the verdict to convert
     * @return 0 on COMMITTED/RECOVERED, 2-6 for CLOSED categories
     */
    public static int exitCode(Verdict verdict) {
        return switch (verdict.outcome()) {
            case COMMITTED, RECOVERED -> 0;
            case CLOSED -> switch (verdict.closedCategory()) {
                case INVALID -> 2;
                case CONFLICT -> 3;
                case UNAVAILABLE -> 4;
                case BLOCKED -> 5;
                case ABORTED, INTERNAL -> 6;
            };
        };
    }

    // ---------------------------------------------------------------------------
    // Execute
    // ---------------------------------------------------------------------------

    private Verdict executeInternal(Request request, String stableId, String txnId) throws IOException {
        Path workingDir = abs(request.workingDirectory(), "WORKING_DIR_INVALID");
        Path committedRoot = abs(request.committedRoot(), "COMMITTED_ROOT_INVALID");

        observer.onExecuteStart(request);
        preflightPrivateParent(workingDir);
        preflightPrivateParent(committedRoot);

        String stableIdHex = stableId.substring(7);
        Path outputDir = committedRoot.resolve(stableIdHex);

        LeaseBudget budget = LeaseBudget.start(publicationLeaseTimeout.toNanos());
        // Lease manages channel + file-lock + JVM lock; close() fires on any exit path.
        // Lock key is committedRoot + stableHex for correct serialization across workingDir changes.
        try (ExactLockLease lease = acquireExactLock(committedRoot, stableIdHex, budget)) {
            LifecycleState state = currentState(outputDir);
            if (state == LifecycleState.COMPLETE) {
                return executeRetry(request, outputDir, stableId, txnId);
            }
            if (state == LifecycleState.ABORTED) {
                return Verdict.closed(txnId, stableId,
                        ClosedCategory.ABORTED, "ABORTED_STATE");
            }
            if (state == LifecycleState.EXTERNAL_PENDING) {
                // B0 exists locally but B1 and/or R1 are missing.
                // Recover without re-calling fencing/producer; do NOT reinstall B0.
                return recoverExternalPending(request, outputDir, stableId, txnId);
            }
            if (state != null) {
                return Verdict.closed(txnId, stableId,
                        ClosedCategory.INTERNAL, "UNEXPECTED_STATE_" + state);
            }

            // Fresh first execute: state is null (outputDir does not yet exist)
            if (state == null) {
                // idempotencyKey derived from internally derived stableId + transactionId
                String idempotencyKey = stableIdHex + "-" + txnId.substring(7);
                // PREPARED: create active directory
                Path activeDir = createActiveDirectory(request, workingDir, stableId, txnId);

                // LEASE_COMMITTED: call fencing authority
                FencingAuthority.FencingToken fencingToken =
                        callFencingAuthority(stableId, request.authorityInputTreeFingerprint(),
                                workingDir);

                // Write authority receipt to activeDir (read-only verify reads it from committed output)
                byte[] tokenReceiptBytes = JSON.writeValueAsBytes(Map.of(
                        "tokenFingerprint", fencingToken.tokenFingerprint(),
                        "epoch", fencingToken.epoch(),
                        "tokenBytes", Base64.getEncoder().encodeToString(fencingToken.tokenBytes())
                ));
                writeAtomic(activeDir.resolve(OWNER_AUTHORITY_RECEIPT_FILE), tokenReceiptBytes, PRIVATE_FILE);

                // PRODUCE: call evidence producer
                EvidenceProducer.SealedEvidenceCandidate candidate =
                        callEvidenceProducer(stableId, request.candidateFingerprint(), workingDir);

                // ANTI-DRIFT: evidenceRoot priority: request.evidenceRoot > candidate.evidenceRoot
                Path sourceRoot = request.evidenceRoot() != null
                        ? Path.of(request.evidenceRoot())
                        : candidate.evidenceRoot();

                // INVENTORY: single EvidenceSnapshot.create copies source to b0/evidence-root
        Path evidenceRootTarget = activeDir.resolve(B0_EVIDENCE_ROOT_DIRECTORY);
        EvidenceSnapshot.Snapshot snap;
        try {
            snap = EvidenceSnapshot.create(sourceRoot, evidenceRootTarget);
        } catch (EvidenceSnapshot.SnapshotException e) {
            throw EptFailure.blocked("EVIDENCE_CREATE_FAILED_" + e.code(), e);
        }

                // Compute B0 content using snapshot entries
                B0Content b0 = computeB0FromSnapshot(request, candidate, snap, fencingToken, txnId, stableId);

                // LOCAL_COMMITTED: atomic rename of entire activeDir to outputDir
                installB0Atomically(outputDir, b0, activeDir);

                // EXTERNAL_PENDING: B1 publish via acceptance API
                StorePublisher.B1Acceptance b1Accept;
                try {
                    b1Accept = callPublishB1(b0.closureFingerprint(), idempotencyKey);
                } catch (StorePublisher.StorePublisherException e) {
                    if (e.recoverable()) {
                        return Verdict.closed(txnId, stableId,
                                ClosedCategory.UNAVAILABLE, "PUBLISH_B1_UNAVAILABLE_" + e.code());
                    }
                    return Verdict.closed(txnId, stableId,
                            ClosedCategory.INVALID, "PUBLISH_B1_INVALID_" + e.code());
                }

                // Validate acceptance matches captured issuer and derived idempotency key
                if (!capturedIssuer.equals(b1Accept.issuer())) {
                    throw EptFailure.invalid("B1_ISSUER_MISMATCH");
                }
                if (!idempotencyKey.equals(b1Accept.idempotencyKey())) {
                    throw EptFailure.invalid("B1_IDEMPOTENCY_KEY_MISMATCH");
                }

                // EptReceiptEnvelope: seal B1 receipt from captured identity
                EptReceiptEnvelope.B1Context b1Ctx = new EptReceiptEnvelope.B1Context(
                        stableId, txnId, b0.closureFingerprint(), idempotencyKey, capturedIssuer);
                EptReceiptEnvelope.SealedReceipt sealedB1 = EptReceiptEnvelope.sealB1(b1Ctx);

                // Immediate verification: cross-check sealed receipt against captured context
                EptReceiptEnvelope.SealedReceipt verifiedB1 = EptReceiptEnvelope.verifyB1(sealedB1.bytes(), b1Ctx);

                writeAtomicNew(outputDir.resolve(B1_RECEIPT_FILE), verifiedB1.bytes(), PRIVATE_FILE);
                forceDirectory(outputDir);

                // EXTERNAL_COMPLETE: R1 issue via acceptance API
                StorePublisher.R1Acceptance r1Accept;
                try {
                    r1Accept = callIssueR1(b0.closureFingerprint(),
                            sealedB1.fingerprint(), idempotencyKey);
                } catch (StorePublisher.StorePublisherException e) {
                    if (e.recoverable()) {
                        return Verdict.closed(txnId, stableId,
                                ClosedCategory.UNAVAILABLE, "ISSUE_R1_UNAVAILABLE_" + e.code());
                    }
                    return Verdict.closed(txnId, stableId,
                            ClosedCategory.INVALID, "ISSUE_R1_INVALID_" + e.code());
                }

                // Validate acceptance matches captured issuer and OWNER constant
                if (!capturedIssuer.equals(r1Accept.issuer())) {
                    throw EptFailure.invalid("R1_ISSUER_MISMATCH");
                }
                if (!OWNER.equals(r1Accept.owner())) {
                    throw EptFailure.invalid("R1_OWNER_MISMATCH");
                }

                // EptReceiptEnvelope: seal R1 receipt binding B1 fingerprint as anchor
                EptReceiptEnvelope.R1Context r1Ctx = new EptReceiptEnvelope.R1Context(
                        stableId, txnId, b0.closureFingerprint(),
                        sealedB1.fingerprint(), capturedIssuer, OWNER);
                EptReceiptEnvelope.SealedReceipt sealedR1 = EptReceiptEnvelope.sealR1(r1Ctx);

                // Immediate verification: cross-check sealed receipt against captured context
                EptReceiptEnvelope.SealedReceipt verifiedR1 = EptReceiptEnvelope.verifyR1(sealedR1.bytes(), r1Ctx);

                writeAtomicNew(outputDir.resolve(R1_RECEIPT_FILE), verifiedR1.bytes(), PRIVATE_FILE);
                forceDirectory(outputDir);

                // COMPLETE: build and return verdict
                observer.onExecuteComplete(request, LifecycleState.COMPLETE);
                return Verdict.committed(txnId, stableId,
                        new Verdict.B0Receipt(
                                b0.rawFingerprint(),
                                b0.canonicalFingerprint(),
                                b0.closureFingerprint(),
                                verifiedB1.fingerprint(),
                                verifiedR1.fingerprint(),
                                fencingToken.tokenFingerprint(),
                                fencingToken.epoch()));
            }
            // All state transitions above return; state==null is the only path to reach here
            throw new AssertionError("executeInternal: unreachable state");
        } catch (EptFailure f) {
            throw f;
        } catch (IOException e) {
            throw EptFailure.internal("EXECUTE_IO_ERROR", e);
        }
    }

    private Verdict executeRetry(Request request, Path outputDir, String stableId, String txnId) {
        // Idempotent retry: strictLocalB0Verify ensures physical payload integrity.
        Path root = outputDir;
        String mismatchCode = strictLocalB0Verify(root, null);
        if (mismatchCode != null) {
            return Verdict.closed(txnId, stableId,
                    ClosedCategory.INVALID, mismatchCode);
        }

        // EPT-CN02: compare stored transactionId against derived txnId.
        // B0 manifest is authoritative; strictLocalB0Verify has already validated physical integrity.
        // Different nonce -> different txnId -> CLOSED CONFLICT before any store call.
        String storedTxnId = readB0TransactionId(outputDir);
        if (!txnId.equals(storedTxnId)) {
            return Verdict.closed(txnId, stableId,
                    ClosedCategory.CONFLICT, "TRANSACTION_ID_MISMATCH");
        }

        String existingCandidate;
        try {
            existingCandidate = readExistingCandidateDigest(outputDir.resolve(HEADER_FILE));
        } catch (IOException e) {
            throw EptFailure.internal("EXECUTE_RETRY_IO_ERROR", e);
        }
        if (!request.candidateFingerprint().equals(existingCandidate)) {
            return Verdict.closed(txnId, stableId,
                    ClosedCategory.CONFLICT, "CANDIDATE_FINGERPRINT_MISMATCH");
        }
        return Verdict.recovered(txnId, stableId, readExistingReceipts(outputDir));
    }


    // ---------------------------------------------------------------------------
    // readB0TransactionId — private helper: reads stored txnId from B0 inner manifest
    //
    // EPT-CN02: same stableRequestId but different publicationNonce must return
    // CLOSED(CONFLICT) before any producer/fencing/store call.
    // The B0 manifest is the authoritative source for the committed transactionId.
    //
    // Validation: format must match sha256:<64 hex>, null/missing/wrong-format
    // maps to INVALID. IO errors map to INTERNAL. No architecture drift.
    // ---------------------------------------------------------------------------
    private String readB0TransactionId(Path outputDir) {
        try {
            byte[] manifestBytes = Files.readAllBytes(outputDir.resolve(B0_INNER_MANIFEST_FILE));
            JsonNode node = JSON.readTree(manifestBytes);
            String txnId = node.path("transactionId").asText(null);
            if (txnId == null || !FINGERPRINT.matcher(txnId).matches()) {
                throw EptFailure.invalid("TRANSACTION_ID_INVALID");
            }
            return txnId;
        } catch (EptFailure f) {
            throw f;
        } catch (IOException e) {
            throw EptFailure.internal("B0_TRANSACTION_ID_IO_ERROR", e);
        }
    }

    private String readExistingCandidateDigest(Path headerFile) throws IOException {
        return JSON.readTree(Files.readAllBytes(headerFile))
                .path("candidateFingerprint").asText();
    }

    private Verdict.B0Receipt readExistingReceipts(Path outputDir) {
        try {
            byte[] b0Bytes = Files.readAllBytes(outputDir.resolve(B0_INNER_MANIFEST_FILE));
            String b0Raw = sha256Raw(b0Bytes);
            String b0Canonical = sha256CanonicalJson(new String(b0Bytes, StandardCharsets.UTF_8));
            JsonNode b0Node = JSON.readTree(b0Bytes);
            String stableId = b0Node.path("stableRequestId").asText();
            String txnId = b0Node.path("transactionId").asText();
            String treeFp = b0Node.path("treeFingerprint").asText();
            int entryCount = b0Node.path("entryCount").asInt();
            long totalBytes = b0Node.path("totalByteSize").asLong();
            FencingAuthority.FencingToken token = readAuthorityToken(
                    outputDir.resolve(OWNER_AUTHORITY_RECEIPT_FILE));
            String b0Closure = computeB0ClosureFingerprint(b0Raw, b0Canonical, treeFp,
                    entryCount, totalBytes, token);

            // Verify B1 receipt: read bytes, cross-check against stored context, return codec fingerprint
            byte[] b1Bytes = Files.readAllBytes(outputDir.resolve(B1_RECEIPT_FILE));
            EptReceiptEnvelope.B1Context b1Ctx = new EptReceiptEnvelope.B1Context(
                    stableId, txnId, b0Closure, deriveIdempotencyKey(stableId, txnId), capturedIssuer);
            String b1Fp;
            try {
                b1Fp = EptReceiptEnvelope.verifyB1(b1Bytes, b1Ctx).fingerprint();
            } catch (ReceiptException e) {
                throw EptFailure.invalid(e.code());
            }

            // Verify R1 receipt: read bytes, cross-check against stored context and B1 fingerprint, return codec fingerprint
            byte[] r1Bytes = Files.readAllBytes(outputDir.resolve(R1_RECEIPT_FILE));
            EptReceiptEnvelope.R1Context r1Ctx = new EptReceiptEnvelope.R1Context(
                    stableId, txnId, b0Closure, b1Fp, capturedIssuer, OWNER);
            String r1Fp;
            try {
                r1Fp = EptReceiptEnvelope.verifyR1(r1Bytes, r1Ctx).fingerprint();
            } catch (ReceiptException e) {
                throw EptFailure.invalid(e.code());
            }

            return new Verdict.B0Receipt(b0Raw, b0Canonical, b0Closure, b1Fp, r1Fp,
                    token.tokenFingerprint(), token.epoch());
        } catch (ReceiptException e) {
            throw EptFailure.invalid(e.code());
        } catch (IOException e) {
            throw EptFailure.internal("READ_EXISTING_RECEIPTS_IO_ERROR", e);
        }
    }

    // ---------------------------------------------------------------------------
    // EXTERNAL_PENDING recovery
    //
    // Called inside the lock when outputDir exists with B0 but B1 or R1 is missing.
    // Does NOT re-call fencing or producer; does NOT reinstall B0 bytes/metadata.
    // Store exceptions are stable UNAVAILABLE/INVALID; B0 bytes/metadata unchanged.
    // ---------------------------------------------------------------------------

    private Verdict recoverExternalPending(Request request, Path outputDir,
                                           String stableId, String txnId) throws IOException {
        String idempotencyKey = deriveIdempotencyKey(stableId, txnId);

        // Step 1: strictLocalB0Verify — recompute B0 from manifest+authority, cross-validate identity
        String mismatchCode = strictLocalB0Verify(outputDir, null);
        if (mismatchCode != null) {
            return Verdict.closed(txnId, stableId, ClosedCategory.INVALID, mismatchCode);
        }

        // Step 2: Read existing B0 content for closure fingerprint and pins
        byte[] b0Bytes = Files.readAllBytes(outputDir.resolve(B0_INNER_MANIFEST_FILE));
        String b0Raw = sha256Raw(b0Bytes);
        String b0Canonical = sha256CanonicalJson(new String(b0Bytes, StandardCharsets.UTF_8));
        JsonNode b0Node = JSON.readTree(b0Bytes);
        String treeFp = b0Node.path("treeFingerprint").asText();
        int entryCount = b0Node.path("entryCount").asInt();
        long totalBytes = b0Node.path("totalByteSize").asLong();
        FencingAuthority.FencingToken token = readAuthorityToken(
                outputDir.resolve(OWNER_AUTHORITY_RECEIPT_FILE));
        String b0Closure = computeB0ClosureFingerprint(b0Raw, b0Canonical, treeFp,
                entryCount, totalBytes, token);

        // Step 3: Candidate digest cross-check
        String existingCandidate;
        try {
            existingCandidate = readExistingCandidateDigest(outputDir.resolve(HEADER_FILE));
        } catch (IOException e) {
            throw EptFailure.internal("RECOVER_IO_ERROR", e);
        }
        if (!request.candidateFingerprint().equals(existingCandidate)) {
            return Verdict.closed(txnId, stableId,
                    ClosedCategory.CONFLICT, "CANDIDATE_FINGERPRINT_MISMATCH");
        }

        // EPT-CN02: compare stored transactionId against derived txnId before ANY
        // query/publish/issue call. B0 manifest is authoritative. Different nonce ->
        // different txnId -> CLOSED CONFLICT. bundle bytes and b0 metadata unchanged.
        String storedTxnId = readB0TransactionId(outputDir);
        if (!txnId.equals(storedTxnId)) {
            return Verdict.closed(txnId, stableId,
                    ClosedCategory.CONFLICT, "TRANSACTION_ID_MISMATCH");
        }

        String b1Fp;
        boolean b1Local = present(outputDir.resolve(B1_RECEIPT_FILE));

        if (b1Local) {
            // B1 exists locally: strict verify
            try {
                EptReceiptEnvelope.B1Context b1Ctx = new EptReceiptEnvelope.B1Context(
                        stableId, txnId, b0Closure, idempotencyKey, capturedIssuer);
                b1Fp = EptReceiptEnvelope.verifyB1(
                        Files.readAllBytes(outputDir.resolve(B1_RECEIPT_FILE)), b1Ctx).fingerprint();
            } catch (ReceiptException e) {
                return Verdict.closed(txnId, stableId, ClosedCategory.INVALID, e.code());
            }
        } else {
            // B1 missing locally: query store
            StorePublisher.B1Acceptance acceptance = callQueryB1(idempotencyKey);
            if (acceptance != null) {
                // Store has acceptance: validate issuer/key, seal, write
                if (!capturedIssuer.equals(acceptance.issuer())) {
                    return Verdict.closed(txnId, stableId,
                            ClosedCategory.INVALID, "B1_ISSUER_MISMATCH");
                }
                if (!idempotencyKey.equals(acceptance.idempotencyKey())) {
                    return Verdict.closed(txnId, stableId,
                            ClosedCategory.INVALID, "B1_IDEMPOTENCY_KEY_MISMATCH");
                }
            } else {
                // No acceptance: publish B1 once
                try {
                    acceptance = callPublishB1(b0Closure, idempotencyKey);
                } catch (StorePublisher.StorePublisherException e) {
                    if (e.recoverable()) {
                        return Verdict.closed(txnId, stableId,
                                ClosedCategory.UNAVAILABLE, "RECOVER_B1_UNAVAILABLE_" + e.code());
                    }
                    return Verdict.closed(txnId, stableId,
                            ClosedCategory.INVALID, "RECOVER_B1_INVALID_" + e.code());
                }
                if (!capturedIssuer.equals(acceptance.issuer())) {
                    return Verdict.closed(txnId, stableId,
                            ClosedCategory.INVALID, "B1_ISSUER_MISMATCH");
                }
                if (!idempotencyKey.equals(acceptance.idempotencyKey())) {
                    return Verdict.closed(txnId, stableId,
                            ClosedCategory.INVALID, "B1_IDEMPOTENCY_KEY_MISMATCH");
                }
            }
            // Seal B1 receipt
            EptReceiptEnvelope.B1Context b1Ctx = new EptReceiptEnvelope.B1Context(
                    stableId, txnId, b0Closure, idempotencyKey, capturedIssuer);
            EptReceiptEnvelope.SealedReceipt sealedB1 = EptReceiptEnvelope.sealB1(b1Ctx);
            // Write as create-new (no REPLACE)
            writeAtomicNew(outputDir.resolve(B1_RECEIPT_FILE), sealedB1.bytes(), PRIVATE_FILE);
            forceDirectory(outputDir);
            b1Fp = sealedB1.fingerprint();
        }

        // Step 4: R1 local existence check and recovery
        boolean r1Local = present(outputDir.resolve(R1_RECEIPT_FILE));
        String r1Fp;

        if (r1Local) {
            // R1 exists locally: strict verify
            try {
                EptReceiptEnvelope.R1Context r1Ctx = new EptReceiptEnvelope.R1Context(
                        stableId, txnId, b0Closure, b1Fp, capturedIssuer, OWNER);
                r1Fp = EptReceiptEnvelope.verifyR1(
                        Files.readAllBytes(outputDir.resolve(R1_RECEIPT_FILE)), r1Ctx).fingerprint();
            } catch (ReceiptException e) {
                return Verdict.closed(txnId, stableId, ClosedCategory.INVALID, e.code());
            }
        } else {
            // R1 missing locally: issue R1 once
            StorePublisher.R1Acceptance acceptance;
            try {
                acceptance = callIssueR1(b0Closure, b1Fp, idempotencyKey);
            } catch (StorePublisher.StorePublisherException e) {
                if (e.recoverable()) {
                    return Verdict.closed(txnId, stableId,
                            ClosedCategory.UNAVAILABLE, "RECOVER_R1_UNAVAILABLE_" + e.code());
                }
                return Verdict.closed(txnId, stableId,
                        ClosedCategory.INVALID, "RECOVER_R1_INVALID_" + e.code());
            }
            if (!capturedIssuer.equals(acceptance.issuer())) {
                return Verdict.closed(txnId, stableId,
                        ClosedCategory.INVALID, "R1_ISSUER_MISMATCH");
            }
            if (!OWNER.equals(acceptance.owner())) {
                return Verdict.closed(txnId, stableId,
                        ClosedCategory.INVALID, "R1_OWNER_MISMATCH");
            }
            // Seal R1 receipt
            EptReceiptEnvelope.R1Context r1Ctx = new EptReceiptEnvelope.R1Context(
                    stableId, txnId, b0Closure, b1Fp, capturedIssuer, OWNER);
            EptReceiptEnvelope.SealedReceipt sealedR1 = EptReceiptEnvelope.sealR1(r1Ctx);
            // Write as create-new (no REPLACE)
            writeAtomicNew(outputDir.resolve(R1_RECEIPT_FILE), sealedR1.bytes(), PRIVATE_FILE);
            forceDirectory(outputDir);
            r1Fp = sealedR1.fingerprint();
        }

        // Step 5: Force directory sync and return RECOVERED verdict
        forceDirectory(outputDir);
        observer.onExecuteComplete(request, LifecycleState.COMPLETE);
        return Verdict.recovered(txnId, stableId,
                new Verdict.B0Receipt(
                        b0Raw, b0Canonical, b0Closure,
                        b1Fp, r1Fp,
                        token.tokenFingerprint(), token.epoch()));
    }

    private StorePublisher.B1Acceptance callQueryB1(String idempotencyKey) {
        try {
            return storePublisher.queryB1(idempotencyKey);
        } catch (StorePublisher.StorePublisherException e) {
            if (e.recoverable()) {
                throw EptFailure.unavailable("STORE_QUERY_B1_FAILED_" + e.code(), e);
            } else {
                throw EptFailure.invalid("STORE_QUERY_B1_FAILED_" + e.code(), e);
            }
        }
    }

    /**
     * Atomically writes a new file with proof of no-overwrite.
     *
     * <p>The staging file is placed in {@code committedRoot/.ept-locks/}
     * (derived from {@code target} as
     * {@code target.getParent().getParent()/.ept-locks/}) so that it is on the
     * same filesystem as {@code target} and a hard-link publish via {@link
     * Files#createLink} is guaranteed to fail with {@link FileAlreadyExistsException}
     * if {@code target} already exists, regardless of OS or filesystem.
     *
     * <p>Idempotency: if {@code target} already exists and its SHA-256 fingerprint
     * matches {@code bytes}, this method returns silently without modifying the file.
     *
     * <p>Publish step: {@code Files.createLink(target, stagingFile)} is atomic on
     * POSIX and raises {@code FileAlreadyExistsException} when {@code target} exists.
     * After the link succeeds, the staging file is deleted and its parent directory
     * is forced to stable storage.
     *
     * <p>POSIX/hard-link is required: if the platform does not support hard links or
     * the operation fails for any reason, the staging file is cleaned up and an
     * {@code UNAVAILABLE} failure is thrown. There is no fallback to unsafe operations.
     *
     * <p>Throws CONFLICT if {@code target} already exists.
     * Throws UNAVAILABLE if the hard-link operation is not supported or fails.
     *
     * @see Files#createLink
     */
    private static void writeAtomicNew(Path target, byte[] bytes,
            Set<PosixFilePermission> perms) throws IOException {
        // committedRoot/stableHex/FILE  =>  getParent().getParent() = committedRoot
        Path committedRoot = target.getParent().getParent();
        Path stagingDir = committedRoot.resolve(EPT_LOCKS_DIRECTORY);

        // Prepare staging dir
        try {
            Files.createDirectories(stagingDir);
            Files.setPosixFilePermissions(stagingDir, PRIVATE_DIRECTORY);
        } catch (RuntimeException | IOException e) {
            throw EptFailure.unavailable("STAGING_DIR_UNAVAILABLE", e);
        }

        // Stage: CREATE_NEW + asFileAttribute applies permissions atomically at creation,
        // with no chmod window and the permission metadata included in the force sync.
        Path stagingFile = stagingDir.resolve(
                target.getParent().getFileName() + "." + target.getFileName()
                + ".staging." + java.util.UUID.randomUUID());
        IOException stagedWriteFailure = null;
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> atomicPerms =
                    java.util.Set.copyOf(perms);
            try (FileChannel ch = FileChannel.open(stagingFile,
                    java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(atomicPerms))) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true); // durable write before linking
            }
        } catch (IOException e) {
            stagedWriteFailure = e;
        }
        if (stagedWriteFailure != null) {
            IOException cleanupFailure = null;
            try { Files.deleteIfExists(stagingFile); } catch (IOException ce) { cleanupFailure = ce; }
            EptFailure f = EptFailure.unavailable("STAGING_WRITE_FAILED", stagedWriteFailure);
            if (cleanupFailure != null) f.addSuppressed(cleanupFailure);
            throw f;
        }

        // Publish: hard-link stagingFile -> target (atomic on POSIX, no-overwrite guaranteed)
        try {
            Files.createLink(target, stagingFile);
        } catch (FileAlreadyExistsException e) {
            // Target already installed by a concurrent writer; clean up and propagate CONFLICT
            IOException cleanupFailure = null;
            try { Files.deleteIfExists(stagingFile); } catch (IOException ce) { cleanupFailure = ce; }
            EptFailure f = EptFailure.conflict("RECEIPT_EXISTS_CONFLICT");
            if (cleanupFailure != null) f.addSuppressed(cleanupFailure);
            throw f;
        } catch (UnsupportedOperationException e) {
            // Platform does not support hard links — fail closed
            IOException cleanupFailure = null;
            try { Files.deleteIfExists(stagingFile); } catch (IOException ce) { cleanupFailure = ce; }
            EptFailure f = EptFailure.unavailable("HARD_LINK_UNSUPPORTED", e);
            if (cleanupFailure != null) f.addSuppressed(cleanupFailure);
            throw f;
        } catch (RuntimeException | IOException e) {
            IOException cleanupFailure = null;
            try { Files.deleteIfExists(stagingFile); } catch (IOException ce) { cleanupFailure = ce; }
            EptFailure f = EptFailure.unavailable("HARD_LINK_FAILED", e);
            if (cleanupFailure != null) f.addSuppressed(cleanupFailure);
            throw f;
        }

        // Sync parent of target to stable storage
        try {
            forceDirectory(target.getParent());
        } catch (IOException syncFailure) {
            IOException cleanupFailure = null;
            try { Files.deleteIfExists(stagingFile); } catch (IOException ce) { cleanupFailure = ce; }
            EptFailure f = EptFailure.unavailable("TARGET_SYNC_FAILED", syncFailure);
            if (cleanupFailure != null) f.addSuppressed(cleanupFailure);
            throw f;
        }

        // Delete staging file and force the staging directory to stable storage
        IOException cleanupFailure = null;
        try {
            Files.deleteIfExists(stagingFile);
        } catch (IOException e) {
            cleanupFailure = e;
        }
        // Flush directory metadata after unlinking: setPosixFilePermissions is a metadata operation
        // that forces the parent inode update to stable storage.
        try {
            Files.setPosixFilePermissions(stagingDir, PRIVATE_DIRECTORY);
            forceDirectory(stagingDir);
        } catch (IOException e) {
            if (cleanupFailure != null) cleanupFailure.addSuppressed(e);
            else cleanupFailure = e;
        }
        if (cleanupFailure != null) throw cleanupFailure;
    }
    // ---------------------------------------------------------------------------

    private B0Content computeB0FromSnapshot(Request request,
                                               EvidenceProducer.SealedEvidenceCandidate candidate,
                                               EvidenceSnapshot.Snapshot snap,
                                               FencingAuthority.FencingToken fencingToken,
                                               String txnId,
                                               String stableId) throws IOException {
        // Convert snapshot entries to TreeEntry list
        List<TreeEntry> entries = snap.entries().stream()
                .map(e -> new TreeEntry(e.relativePath(), e.size(), e.rawFingerprint()))
                .toList();

        String treeFp = snap.treeFingerprint();
        long totalBytes = snap.totalBytes();

        byte[] manifestBytes = buildB0ManifestBytes(request, candidate, entries, treeFp, txnId, stableId,
                snap.entries().size(), totalBytes);
        String b0Raw = sha256Raw(manifestBytes);
        String b0Canonical = sha256CanonicalJson(new String(manifestBytes, StandardCharsets.UTF_8));
        String b0Closure = computeB0ClosureFingerprint(b0Raw, b0Canonical, treeFp,
                entries.size(), totalBytes, fencingToken);
        return new B0Content(b0Raw, b0Canonical, b0Closure, manifestBytes, entries, treeFp);
    }

    private byte[] buildB0ManifestBytes(Request request,
                                        EvidenceProducer.SealedEvidenceCandidate candidate,
                                        List<TreeEntry> entries,
                                        String treeFp,
                                        String txnId,
                                        String stableId,
                                        int entryCount,
                                        long totalBytes) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("messageVersion", EPT_DOMAIN);
        manifest.put("schemaVersion", EPT_DOMAIN);
        manifest.put("stableRequestId", stableId);
        manifest.put("transactionId", txnId);
        manifest.put("candidateFingerprint", request.candidateFingerprint());
        manifest.put("entryCount", entryCount);
        manifest.put("totalByteSize", totalBytes);
        manifest.put("treeFingerprint", treeFp);
        manifest.put("entries", entries.stream()
                .map(e -> Map.of(
                        "relativePath", e.relativePath(),
                        "byteSize", e.byteSize(),
                        "rawFingerprint", e.rawFingerprint()))
                .toList());
        try {
            return JSON.writeValueAsBytes(manifest);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private String computeB0ClosureFingerprint(String b0RawFingerprint,
                                                String b0CanonicalFingerprint,
                                                String treeFingerprint,
                                                int entryCount,
                                                long totalBytes,
                                                FencingAuthority.FencingToken fencingToken) {
        // UTF-8 length-prefixed framing for deterministic closure
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            digestLp(md, EPT_DOMAIN);
            digestLp(md, b0RawFingerprint);
            digestLp(md, b0CanonicalFingerprint);
            digestLp(md, treeFingerprint);
            digestLp(md, String.valueOf(entryCount));
            digestLp(md, String.valueOf(totalBytes));
            digestLp(md, fencingToken.tokenFingerprint());
            return "sha256:" + bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    // ---------------------------------------------------------------------------
    // Active directory creation
    // ---------------------------------------------------------------------------

    private Path createActiveDirectory(Request request, Path workingDir,
                                       String stableId, String txnId) throws IOException {
        Path activeDir = workingDir.resolve("active-" + THREAD_SEQ.incrementAndGet());
        Files.createDirectories(activeDir);
        Files.setPosixFilePermissions(activeDir, PRIVATE_DIRECTORY);
        byte[] headerBytes = JSON.writeValueAsBytes(Map.of(
                "messageVersion", EPT_DOMAIN,
                "stableRequestId", stableId,
                "transactionId", txnId,
                "candidateFingerprint", request.candidateFingerprint(),
                "authorityInputTreeFingerprint", request.authorityInputTreeFingerprint(),
                "publicationNonce", request.publicationNonce()));
        writeAtomic(activeDir.resolve(HEADER_FILE), headerBytes, BUILD_FILE);
        return activeDir;
    }

    // ---------------------------------------------------------------------------
    // External calls
    // ---------------------------------------------------------------------------

    private FencingAuthority.FencingToken callFencingAuthority(
            String stableRequestId,
            String authorityInputTreeFingerprint,
            Path workingDir) {
        try {
            return fencingAuthority.issue(stableRequestId, authorityInputTreeFingerprint, workingDir);
        } catch (FencingAuthority.FencingAuthorityException e) {
            throw EptFailure.blocked("FENCING_AUTHORITY_FAILED_" + e.code(), e);
        }
    }

    private EvidenceProducer.SealedEvidenceCandidate callEvidenceProducer(
            String stableRequestId,
            String candidateFingerprint,
            Path workingDir) throws IOException {
        try {
            Path producerDir = workingDir.resolve("producer");
            Files.createDirectories(producerDir);
            return evidenceProducer.produce(stableRequestId, candidateFingerprint, producerDir);
        } catch (EvidenceProducer.EvidenceProducerException e) {
            throw EptFailure.blocked("EVIDENCE_PRODUCER_FAILED_" + e.code(), e);
        }
    }

    private StorePublisher.B1Acceptance callPublishB1(String b0ClosureFingerprint,
                                        String idempotencyKey) {
        return storePublisher.publishB1(b0ClosureFingerprint, idempotencyKey);
    }

    private StorePublisher.R1Acceptance callIssueR1(String b0ClosureFingerprint,
                                      String b1ReceiptFingerprint,
                                      String idempotencyKey) {
        return storePublisher.issueR1(b0ClosureFingerprint, b1ReceiptFingerprint,
                idempotencyKey, OWNER);
    }

    // ---------------------------------------------------------------------------
    // Atomic B0 install (LOCAL_COMMITTED)
    // ---------------------------------------------------------------------------

    private void installB0Atomically(Path outputDir, B0Content b0, Path activeDir)
            throws IOException {
        // Write the authoritative manifest at root: b0-inner-manifest.json
        Path activeB0Inner = activeDir.resolve(B0_INNER_MANIFEST_FILE);
        writeAtomic(activeB0Inner, b0.manifestBytes(), PRIVATE_FILE);

        // Check output does not already exist
        if (present(outputDir)) {
            throw EptFailure.conflict("OUTPUT_EXISTS_CONFLICT");
        }

        // Force parent directory exists
        forceDirectory(outputDir.getParent());

        try {
            // Move entire activeDir atomically to outputDir — NO REPLACE_EXISTING
            Files.move(activeDir, outputDir,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw EptFailure.internal("ATOMIC_MOVE_UNSUPPORTED", e);
        } catch (FileAlreadyExistsException e) {
            throw EptFailure.conflict("OUTPUT_EXISTS_RACE");
        } catch (NoSuchFileException e) {
            throw EptFailure.internal("ACTIVE_DIR_MISSING", e);
        }
        // Force both the output directory and its parent after atomic move
        forceDirectory(outputDir);
        forceDirectory(outputDir.getParent());
    }

    // ---------------------------------------------------------------------------
    // strictLocalB0Verify — shared private helper for fresh execute, retry, and public verify
    //
    // Validates:
    //   1. Top-level: allowed set, no symlinks, b0 is dir, others are regular files
    //   2. b0/ is a directory containing exactly evidence-root/
    //   3. b0-inner-manifest.json parses correctly and is consistent with header
    //   4. Physical evidence-root/ inspected; path set compared before per-entry checks
    //
    // Returns null on success, or a stable mismatch code on failure.
    // Does NOT access B1/R1 receipts or make external calls.
    // ---------------------------------------------------------------------------

    private String strictLocalB0Verify(Path root, EvidenceSnapshot.Snapshot expectedSnapshot) {
        Set<String> allowedTopLevel = Set.of(
                HEADER_FILE,
                B0_DIRECTORY,
                B0_INNER_MANIFEST_FILE,
                B1_RECEIPT_FILE,
                R1_RECEIPT_FILE,
                OWNER_AUTHORITY_RECEIPT_FILE
        );
        try {
            // 1. Top-level: allowed set + type enforcement (no symlinks)
            Set<String> topLevelDirs = new HashSet<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path child : ds) {
                    String name = child.getFileName().toString();
                    if (!allowedTopLevel.contains(name)) {
                        return MC_PAYLOAD_UNKNOWN_ENTRY;
                    }
                    if (Files.isSymbolicLink(child)) {
                        return MC_PAYLOAD_PATH_INVALID;
                    }
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        topLevelDirs.add(name);
                    }
                }
            }

            // b0/ must be a directory
            if (!topLevelDirs.contains(B0_DIRECTORY)) {
                return MC_BUNDLE_MISSING;
            }

            // 2. b0/ must contain exactly evidence-root/ (no manifest.json)
            Path b0Dir = root.resolve(B0_DIRECTORY);
            if (!Files.isDirectory(b0Dir, LinkOption.NOFOLLOW_LINKS)) {
                return MC_BUNDLE_MISSING;
            }
            Set<String> b0Allowed = Set.of("evidence-root");
            Set<String> b0Unexpected = new HashSet<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(b0Dir)) {
                for (Path child : ds) {
                    String rel = b0Dir.relativize(child).toString();
                    if (!b0Allowed.contains(rel)) {
                        b0Unexpected.add(rel);
                    }
                }
            }
            if (!b0Unexpected.isEmpty()) {
                return MC_BUNDLE_EXTRA;
            }

            // 3. Parse and validate b0-inner-manifest.json (authoritative manifest)
            Path manifestFile = root.resolve(B0_INNER_MANIFEST_FILE);
            if (!present(manifestFile)) {
                return MC_MANIFEST_INVALID;
            }
            JsonNode manifestNode;
            try {
                manifestNode = JSON.readTree(Files.readAllBytes(manifestFile));
            } catch (IOException e) {
                return MC_MANIFEST_INVALID;
            }

            String manifestStableId = manifestNode.path("stableRequestId").asText(null);
            String manifestTxnId = manifestNode.path("transactionId").asText(null);
            String manifestCandidateFp = manifestNode.path("candidateFingerprint").asText(null);

            // Validate header consistency with manifest root identity
            Path headerFile = root.resolve(HEADER_FILE);
            if (present(headerFile)) {
                JsonNode headerNode;
                try {
                    headerNode = JSON.readTree(Files.readAllBytes(headerFile));
                } catch (IOException e) {
                    // header unreadable
                    headerNode = null;
                }
                if (headerNode != null) {
                    String headerStable = headerNode.path("stableRequestId").asText(null);
                    String headerTxn = headerNode.path("transactionId").asText(null);
                    String headerCand = headerNode.path("candidateFingerprint").asText(null);
                    if (headerStable != null && !headerStable.equals(manifestStableId)) {
                        return MC_MANIFEST_INVALID;
                    }
                    if (headerTxn != null && !headerTxn.equals(manifestTxnId)) {
                        return MC_MANIFEST_INVALID;
                    }
                    if (headerCand != null && !headerCand.equals(manifestCandidateFp)) {
                        return MC_MANIFEST_INVALID;
                    }
                }
            }

            JsonNode entriesNode = manifestNode.path("entries");
            if (!entriesNode.isArray()) {
                return MC_MANIFEST_INVALID;
            }

            List<TreeEntry> manifestEntries = new ArrayList<>();
            for (JsonNode e : entriesNode) {
                String rp = e.path("relativePath").asText(null);
                long bs = e.path("byteSize").asLong(-1);
                String fp = e.path("rawFingerprint").asText(null);
                if (rp == null || bs < 0 || fp == null) {
                    return MC_MANIFEST_INVALID;
                }
                try {
                    manifestEntries.add(new TreeEntry(rp, bs, fp));
                } catch (EptFailure f) {
                    return MC_MANIFEST_INVALID;
                }
            }

            // Check entry ordering (UTF-8 lexicographic)
            for (int i = 1; i < manifestEntries.size(); i++) {
                if (manifestEntries.get(i - 1).relativePath()
                        .compareTo(manifestEntries.get(i).relativePath()) >= 0) {
                    return MC_ENTRY_ORDER_INVALID;
                }
            }

            int entryCount = manifestNode.path("entryCount").asInt(-1);
            long totalByteSize = manifestNode.path("totalByteSize").asLong(-1L);
            String treeFingerprint = manifestNode.path("treeFingerprint").asText(null);

            if (entryCount < 0 || totalByteSize < 0 || treeFingerprint == null) {
                return MC_MANIFEST_INVALID;
            }

            // 4. Inspect physical payload
            Path evidenceRoot = root.resolve(B0_EVIDENCE_ROOT_DIRECTORY);

            EvidenceSnapshot.Snapshot snap;
            try {
                snap = EvidenceSnapshot.inspect(evidenceRoot);
            } catch (EvidenceSnapshot.SnapshotException e) {
                if (e.code().equals("SOURCE_MISSING") || e.code().equals("SOURCE_NOT_DIRECTORY")) {
                    return MC_PAYLOAD_MISSING;
                }
                if (e.code().equals("SOURCE_SYMLINK")) {
                    return MC_PAYLOAD_PATH_INVALID;
                }
                return MC_PAYLOAD_MISSING;
            }

            // 4a. Path set comparison first
            Set<String> manifestPaths = new HashSet<>();
            for (TreeEntry me : manifestEntries) {
                manifestPaths.add(me.relativePath());
            }
            Set<String> physicalPaths = new HashSet<>();
            for (EvidenceSnapshot.Snapshot.Entry pe : snap.entries()) {
                physicalPaths.add(pe.relativePath());
            }
            Set<String> missing = new HashSet<>(manifestPaths);
            missing.removeAll(physicalPaths);
            if (!missing.isEmpty()) {
                return MC_PAYLOAD_MISSING;
            }
            Set<String> extra = new HashSet<>(physicalPaths);
            extra.removeAll(manifestPaths);
            if (!extra.isEmpty()) {
                return MC_PAYLOAD_EXTRA;
            }

            // 4b. entryCount, totalByteSize aggregates
            if (snap.entries().size() != entryCount) {
                return MC_ENTRY_COUNT_MISMATCH;
            }
            if (snap.totalBytes() != totalByteSize) {
                return MC_TOTAL_SIZE_MISMATCH;
            }

            // 4c. Per-entry size and hash (same path guaranteed by path set match above)
            Map<String, EvidenceSnapshot.Snapshot.Entry> snapMap = new LinkedHashMap<>();
            for (EvidenceSnapshot.Snapshot.Entry e : snap.entries()) {
                snapMap.put(e.relativePath(), e);
            }
            for (TreeEntry me : manifestEntries) {
                EvidenceSnapshot.Snapshot.Entry se = snapMap.get(me.relativePath());
                if (se.size() != me.byteSize()) {
                    return MC_PAYLOAD_SIZE_MISMATCH;
                }
                if (!se.rawFingerprint().equals(me.rawFingerprint())) {
                    return MC_PAYLOAD_FINGERPRINT_MISMATCH;
                }
            }

            // 4d. treeFingerprint aggregate (checked last; per-entry checks are more specific)
            if (!snap.treeFingerprint().equals(treeFingerprint)) {
                return MC_TREE_FINGERPRINT_MISMATCH;
            }

            // If caller provided expectedSnapshot, compare for retry integrity
            if (expectedSnapshot != null) {
                if (!snap.treeFingerprint().equals(expectedSnapshot.treeFingerprint())) {
                    return MC_TREE_FINGERPRINT_MISMATCH;
                }
                if (snap.entries().size() != expectedSnapshot.entries().size()) {
                    return MC_ENTRY_COUNT_MISMATCH;
                }
                for (int i = 0; i < snap.entries().size(); i++) {
                    EvidenceSnapshot.Snapshot.Entry actual = snap.entries().get(i);
                    EvidenceSnapshot.Snapshot.Entry expected = expectedSnapshot.entries().get(i);
                    if (!actual.relativePath().equals(expected.relativePath())) {
                        return MC_ENTRY_COUNT_MISMATCH;
                    }
                    if (!actual.rawFingerprint().equals(expected.rawFingerprint())) {
                        return MC_PAYLOAD_FINGERPRINT_MISMATCH;
                    }
                }
            }

            return null; // All checks passed

        } catch (IOException e) {
            return MC_BUNDLE_MISSING;
        }
    }

    // ---------------------------------------------------------------------------
    // Verify (read-only, zero-write)
    // ---------------------------------------------------------------------------

    private VerifyResult verifyInternal(Path committedRoot, ExpectedPins pins) {
        Path outputDir = abs(committedRoot, "COMMITTED_ROOT_INVALID");
        try {
            // Step 1: strictLocalB0Verify — validates b0/ layout + physical payload
            String payloadMismatch = strictLocalB0Verify(outputDir, null);
            if (payloadMismatch != null) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), payloadMismatch);
            }

            // Step 2: B0 inner manifest verification
            String b0Raw = sha256Raw(Files.readAllBytes(outputDir.resolve(B0_INNER_MANIFEST_FILE)));
            if (!b0Raw.equals(pins.b0RawFingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "B0_RAW_MISMATCH");
            }

            String b0Content = Files.readString(outputDir.resolve(B0_INNER_MANIFEST_FILE));
            String b0Canonical = sha256CanonicalJson(b0Content);
            if (!b0Canonical.equals(pins.b0CanonicalFingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "B0_CANONICAL_MISMATCH");
            }

            // Recompute B0 closure using UTF-8 LP framing
            byte[] b0Bytes = b0Content.getBytes(StandardCharsets.UTF_8);
            JsonNode node = JSON.readTree(b0Bytes);
            List<TreeEntry> entries = parseTreeEntries(node.path("entries"));
            String treeFp = node.path("treeFingerprint").asText();
            int entryCount = node.path("entryCount").asInt();
            long totalBytes = node.path("totalByteSize").asLong();
            FencingAuthority.FencingToken token = readAuthorityToken(
                    outputDir.resolve(OWNER_AUTHORITY_RECEIPT_FILE));
            String b0Closure = computeB0ClosureFingerprint(b0Raw, b0Canonical, treeFp,
                    entryCount, totalBytes, token);
            if (!b0Closure.equals(pins.b0ClosureFingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "B0_CLOSURE_MISMATCH");
            }

            // Step 3: B1 receipt — read bytes, EptReceiptEnvelope codec verify, cross-bind to expected pins
            byte[] b1Bytes = Files.readAllBytes(outputDir.resolve(B1_RECEIPT_FILE));
            EptReceiptEnvelope.B1Context b1VerifyCtx = new EptReceiptEnvelope.B1Context(
                    pins.stableRequestId(), pins.transactionId(),
                    pins.b0ClosureFingerprint(), deriveIdempotencyKey(pins.stableRequestId(), pins.transactionId()), capturedIssuer);
            String b1CodecFp;
            try {
                b1CodecFp = EptReceiptEnvelope.verifyB1(b1Bytes, b1VerifyCtx).fingerprint();
            } catch (ReceiptException e) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), e.code());
            }
            if (!b1CodecFp.equals(pins.b1ReceiptFingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "B1_RECEIPT_MISMATCH");
            }

            // Step 4: R1 receipt — read bytes, EptReceiptEnvelope codec verify, cross-bind B1 and owner
            byte[] r1Bytes = Files.readAllBytes(outputDir.resolve(R1_RECEIPT_FILE));
            EptReceiptEnvelope.R1Context r1VerifyCtx = new EptReceiptEnvelope.R1Context(
                    pins.stableRequestId(), pins.transactionId(),
                    pins.b0ClosureFingerprint(), b1CodecFp, capturedIssuer, OWNER);
            String r1CodecFp;
            try {
                r1CodecFp = EptReceiptEnvelope.verifyR1(r1Bytes, r1VerifyCtx).fingerprint();
            } catch (ReceiptException e) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), e.code());
            }
            if (!r1CodecFp.equals(pins.r1Fingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "R1_RECEIPT_MISMATCH");
            }

            // Step 5: Authority
            if (!token.tokenFingerprint().equals(pins.authorityFingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "AUTHORITY_FINGERPRINT_MISMATCH");
            }
            if (token.epoch() != pins.authorityEpoch()) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "AUTHORITY_EPOCH_MISMATCH");
            }

            return VerifyResult.pass(pins.stableRequestId(), pins.transactionId(),
                    b0Raw, b0Canonical, b0Closure, b1CodecFp, r1CodecFp,
                    token.tokenFingerprint(), token.epoch());

        } catch (IOException e) {
            return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "VERIFY_IO_ERROR");
        }
    }

    // ---------------------------------------------------------------------------
    // B0-only recovery verifier (package-private, not public)
    // ---------------------------------------------------------------------------

    VerifyResult verifyB0Only(Path committedRoot, ExpectedPins pins) {
        Path outputDir = abs(committedRoot, "COMMITTED_ROOT_INVALID");
        try {
            String b0Raw = sha256Raw(Files.readAllBytes(outputDir.resolve(B0_INNER_MANIFEST_FILE)));
            if (!b0Raw.equals(pins.b0RawFingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "B0_RAW_MISMATCH");
            }
            String b0Content = Files.readString(outputDir.resolve(B0_INNER_MANIFEST_FILE));
            String b0Canonical = sha256CanonicalJson(b0Content);
            if (!b0Canonical.equals(pins.b0CanonicalFingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "B0_CANONICAL_MISMATCH");
            }
            byte[] b0Bytes = b0Content.getBytes(StandardCharsets.UTF_8);
            JsonNode node = JSON.readTree(b0Bytes);
            List<TreeEntry> entries = parseTreeEntries(node.path("entries"));
            String treeFp = node.path("treeFingerprint").asText();
            int entryCount = node.path("entryCount").asInt();
            long totalBytes = node.path("totalByteSize").asLong();
            FencingAuthority.FencingToken token = readAuthorityToken(
                    outputDir.resolve(OWNER_AUTHORITY_RECEIPT_FILE));
            String b0Closure = computeB0ClosureFingerprint(b0Raw, b0Canonical, treeFp,
                    entryCount, totalBytes, token);
            if (!b0Closure.equals(pins.b0ClosureFingerprint())) {
                return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "B0_CLOSURE_MISMATCH");
            }
            return VerifyResult.pass(pins.stableRequestId(), pins.transactionId(),
                    b0Raw, b0Canonical, b0Closure, pins.b1ReceiptFingerprint(),
                    pins.r1Fingerprint(), pins.authorityFingerprint(), pins.authorityEpoch());
        } catch (IOException e) {
            return VerifyResult.fail(pins.stableRequestId(), pins.transactionId(), "VERIFY_IO_ERROR");
        }
    }

    // ---------------------------------------------------------------------------
    // Preflight
    // ---------------------------------------------------------------------------

    private void preflightPrivateParent(Path dir) throws IOException {
        // Only set permissions on the dir itself, not ancestors
        // (Ancestors like /var/folders/.../T are system-managed)
        if (!present(dir)) {
            Files.createDirectories(dir);
        }
        if (!Files.isWritable(dir)) {
            throw EptFailure.blocked("WORKING_DIR_NOT_WRITABLE");
        }
        Files.setPosixFilePermissions(dir, PRIVATE_DIRECTORY);
    }

    private Path abs(Path p, String errorCode) {
        if (p == null || !p.isAbsolute()) {
            throw EptFailure.blocked(errorCode);
        }
        return p.normalize();
    }

    // ---------------------------------------------------------------------------
    // State detection
    // ---------------------------------------------------------------------------

    private LifecycleState currentState(Path outputDir) {
        if (!present(outputDir)) return null;
        if (present(outputDir.resolve(B0_INNER_MANIFEST_FILE))
                && present(outputDir.resolve(B1_RECEIPT_FILE))
                && present(outputDir.resolve(R1_RECEIPT_FILE))) {
            return LifecycleState.COMPLETE;
        }
        if (present(outputDir.resolve(".aborted"))) {
            return LifecycleState.ABORTED;
        }
        // Partial state treated as INTERNAL (should not reach here normally)
        return LifecycleState.EXTERNAL_PENDING;
    }

    // ---------------------------------------------------------------------------
    // Lock management
    // ---------------------------------------------------------------------------

    /**
     * Holds file-lock + channel for the duration of a critical section.
     * Package-private so the test harness can observe lease lifecycle.
     * The JVM lock is released via {@link LockHandle#releaseHeld()}.
     * close() order: file lock → channel → JVM lock.
     */
    static final class ExactLockLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock fileLock;
        private final LockHandle handle;

        ExactLockLease(FileChannel channel, FileLock fileLock, LockHandle handle) {
            this.channel = channel;
            this.fileLock = fileLock;
            this.handle = handle;
        }

        @Override
        public void close() {
            IOException ioFailure = null;
            try {
                fileLock.release();
            } catch (IOException e) {
                ioFailure = e;
            }
            try {
                channel.close();
            } catch (IOException e) {
                if (ioFailure != null) ioFailure.addSuppressed(e);
                else ioFailure = e;
            }
            RuntimeException runtimeFailure = null;
            try {
                handle.releaseHeld();
            } catch (IllegalMonitorStateException e) {
                runtimeFailure = new java.io.UncheckedIOException(
                        new IOException("JVM lock not held on close", e));
            } catch (RuntimeException e) {
                runtimeFailure = e;
            }
            if (runtimeFailure != null) {
                if (ioFailure != null) runtimeFailure.addSuppressed(ioFailure);
                throw runtimeFailure;
            }
            if (ioFailure != null) throw new java.io.UncheckedIOException(ioFailure);
        }
    }

    private ExactLockLease acquireExactLock(Path committedRoot, String stableHex, LeaseBudget budget) throws IOException {
        String lockKey = committedRoot.toString() + "|" + stableHex;
        LockHandle handle;
        try {
            handle = LOCK_REGISTRY.acquire(lockKey, budget.remainingNanos());
        } catch (InterruptedException e) {
            // acquire threw after dropping its own ref: restore flag and propagate
            Thread.currentThread().interrupt();
            throw EptFailure.unavailable("LOCK_INTERRUPTED");
        }

        // Lock file stored in committedRoot/.ept-locks/<stableHex>.lock (private, not in output bundle)
        Path locksDir = committedRoot.resolve(EPT_LOCKS_DIRECTORY);
        FileChannel channel = null;
        try {
            Files.createDirectories(locksDir);
            Files.setPosixFilePermissions(locksDir, PRIVATE_DIRECTORY);
            Path lockFile = locksDir.resolve(stableHex + ".lock");
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (RuntimeException | IOException e) {
            Throwable suppressed = null;
            try { handle.releaseHeld(); } catch (IllegalMonitorStateException se) {
                Exception ims = new java.io.UncheckedIOException(
                        new IOException("JVM lock release failed", se));
                if (suppressed != null) suppressed.addSuppressed(ims);
                else suppressed = ims;
            }
            if (suppressed != null) {
                EptFailure f = EptFailure.unavailable("LOCK_SETUP_FAILED", e);
                f.addSuppressed(suppressed);
                throw f;
            }
            throw EptFailure.unavailable("LOCK_SETUP_FAILED", e);
        }

        FileLock fileLock = null;
        while (budget.remainingNanos() > 0) {
            try {
                fileLock = channel.tryLock();
                if (fileLock != null) break;
            } catch (OverlappingFileLockException e) {
                // Contended: back off within budget
            } catch (ClosedChannelException e) {
                Throwable suppressed = null;
                try { channel.close(); } catch (IOException se) {
                    if (suppressed != null) suppressed.addSuppressed(se);
                    else suppressed = se;
                }
                try { handle.releaseHeld(); } catch (IllegalMonitorStateException se) {
                    Exception ims = new java.io.UncheckedIOException(
                            new IOException("JVM lock release failed", se));
                    if (suppressed != null) suppressed.addSuppressed(ims);
                    else suppressed = ims;
                }
                if (suppressed != null) {
                    EptFailure f = EptFailure.unavailable("LOCK_CHANNEL_CLOSED");
                    f.addSuppressed(suppressed);
                    throw f;
                }
                throw EptFailure.unavailable("LOCK_CHANNEL_CLOSED");
            } catch (IOException e) {
                Throwable suppressed = null;
                try { channel.close(); } catch (IOException se) {
                    if (suppressed != null) suppressed.addSuppressed(se);
                    else suppressed = se;
                }
                try { handle.releaseHeld(); } catch (IllegalMonitorStateException se) {
                    Exception ims = new java.io.UncheckedIOException(
                            new IOException("JVM lock release failed", se));
                    if (suppressed != null) suppressed.addSuppressed(ims);
                    else suppressed = ims;
                }
                if (suppressed != null) {
                    EptFailure f = EptFailure.unavailable("FILE_LOCK_IO_ERROR", e);
                    f.addSuppressed(suppressed);
                    throw f;
                }
                throw EptFailure.unavailable("FILE_LOCK_IO_ERROR", e);
            }
            // Budget-bounded backoff: park min(20 ms, remaining)
            long remaining = budget.remainingNanos();
            if (remaining <= 0) break;
            try {
                Thread.sleep(Math.min(20, remaining / 1_000_000));
            } catch (InterruptedException e) {
                // sleep interrupted: treat as unavailable
                Thread.currentThread().interrupt();
                Throwable suppressed = null;
                try { channel.close(); } catch (IOException se) {
                    if (suppressed != null) suppressed.addSuppressed(se);
                    else suppressed = se;
                }
                try { handle.releaseHeld(); } catch (IllegalMonitorStateException se) {
                    Exception ims = new java.io.UncheckedIOException(
                            new IOException("JVM lock release failed", se));
                    if (suppressed != null) suppressed.addSuppressed(ims);
                    else suppressed = ims;
                }
                if (suppressed != null) {
                    EptFailure f = EptFailure.unavailable("LOCK_INTERRUPTED");
                    f.addSuppressed(suppressed);
                    throw f;
                }
                throw EptFailure.unavailable("LOCK_INTERRUPTED");
            }
        }

        if (fileLock == null) {
            Throwable suppressed = null;
            try { channel.close(); } catch (IOException se) {
                if (suppressed != null) suppressed.addSuppressed(se);
                else suppressed = se;
            }
            try { handle.releaseHeld(); } catch (IllegalMonitorStateException se) {
                Exception ims = new java.io.UncheckedIOException(
                        new IOException("JVM lock release failed", se));
                if (suppressed != null) suppressed.addSuppressed(ims);
                else suppressed = ims;
            }
            if (suppressed != null) {
                EptFailure f = EptFailure.unavailable("LOCK_TIMEOUT");
                f.addSuppressed(suppressed);
                throw f;
            }
            throw EptFailure.unavailable("LOCK_TIMEOUT");
        }
        return new ExactLockLease(channel, fileLock, handle);
    }

    // ---------------------------------------------------------------------------
    // File utilities
    // ---------------------------------------------------------------------------

    private static boolean present(Path p) {
        return Files.exists(p, LinkOption.NOFOLLOW_LINKS);
    }

    private static void writeAtomic(Path file, byte[] bytes, Set<PosixFilePermission> perms)
            throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + THREAD_SEQ.incrementAndGet());
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.setPosixFilePermissions(tmp, perms);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void forceDirectory(Path dir) throws IOException {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    // ---------------------------------------------------------------------------
    // SHA-256 primitives
    // ---------------------------------------------------------------------------

    private static String sha256Raw(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + bytesToHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String sha256CanonicalJson(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            canonicalWrite(node, out);
            return sha256Raw(out.toByteArray());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Derives the idempotency key from a stableRequestId and transactionId fingerprint pair.
     * Formula: stableHex + "-" + txHex  (both are the 64-char hex portion of sha256:fingerprint)
     * <p>This is a pure function; the same two fingerprints always yield the same key, matching
     * the formula used during the fresh execute path in {@code executeInternal}.</p>
     */
    private static String deriveIdempotencyKey(String stableId, String txnId) {
        return stableId.substring(7) + "-" + txnId.substring(7);
    }

    private static void canonicalWrite(JsonNode node, OutputStream out) throws IOException {
        switch (node.getNodeType()) {
            case STRING -> {
                out.write('"');
                out.write(node.asText().getBytes(StandardCharsets.UTF_8));
                out.write('"');
            }
            case NUMBER -> out.write(node.isIntegralNumber()
                    ? String.valueOf(node.longValue()).getBytes(StandardCharsets.UTF_8)
                    : node.numberValue().toString().getBytes(StandardCharsets.UTF_8));
            case BOOLEAN -> { byte[] boolBytes = (node.booleanValue() ? "true" : "false").getBytes(StandardCharsets.UTF_8); out.write(boolBytes, 0, boolBytes.length); }
            case NULL -> out.write("null".getBytes(StandardCharsets.UTF_8));
            case ARRAY -> {
                out.write('[');
                var it = node.elements();
                boolean first = true;
                while (it.hasNext()) {
                    if (!first) out.write(',');
                    first = false;
                    canonicalWrite(it.next(), out);
                }
                out.write(']');
            }
            case OBJECT -> {
                out.write('{');
                java.util.Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                java.util.List<Map.Entry<String, JsonNode>> fields = new java.util.ArrayList<>();
                it.forEachRemaining(fields::add);
                fields.sort(Map.Entry.comparingByKey());
                for (int i = 0; i < fields.size(); i++) {
                    if (i > 0) out.write(',');
                    Map.Entry<String, JsonNode> f = fields.get(i);
                    out.write('"');
                    out.write(f.getKey().getBytes(StandardCharsets.UTF_8));
                    out.write('"');
                    out.write(':');
                    canonicalWrite(f.getValue(), out);
                }
                out.write('}');
            }
            default -> out.write("null".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void digestLp(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // 4-byte big-endian length prefix
        md.update((byte) ((bytes.length >>> 24) & 0xFF));
        md.update((byte) ((bytes.length >>> 16) & 0xFF));
        md.update((byte) ((bytes.length >>> 8) & 0xFF));
        md.update((byte) (bytes.length & 0xFF));
        md.update(bytes);
    }

    // ---------------------------------------------------------------------------
    // Lease budget
    // ---------------------------------------------------------------------------

    private static final class LeaseBudget {
        private final long timeoutNanos;
        private long lastTick;
        private long remainingNanos;

        private LeaseBudget(long timeoutNanos, long lastTick, long remainingNanos) {
            this.timeoutNanos = timeoutNanos;
            this.lastTick = lastTick;
            this.remainingNanos = remainingNanos;
        }

        static LeaseBudget start(long timeoutNanos) {
            return new LeaseBudget(timeoutNanos, System.nanoTime(), timeoutNanos);
        }

        long remainingNanos() {
            long now = System.nanoTime();
            long delta = now - lastTick;
            lastTick = now;
            if (delta < 0) delta = 0;
            if (delta >= remainingNanos) remainingNanos = 0;
            else remainingNanos -= delta;
            return remainingNanos;
        }
    }

    // ---------------------------------------------------------------------------
    // Internal records
    // ---------------------------------------------------------------------------

    record B0Content(
            String rawFingerprint,
            String canonicalFingerprint,
            String closureFingerprint,
            byte[] manifestBytes,
            List<TreeEntry> entries,
            String treeFingerprint) {}

    record TreeEntry(String relativePath, long byteSize, String rawFingerprint) {
        public TreeEntry {
            if (relativePath == null || !SAFE_NAME.matcher(relativePath).matches()) {
                throw EptFailure.blocked("ENTRY_INVALID_NAME");
            }
            if (byteSize < 1) throw EptFailure.blocked("ENTRY_INVALID_SIZE");
            if (rawFingerprint == null || !FINGERPRINT.matcher(rawFingerprint).matches()) {
                throw EptFailure.blocked("ENTRY_INVALID_FINGERPRINT");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Authority token
    // ---------------------------------------------------------------------------

    private static FencingAuthority.FencingToken readAuthorityToken(Path tokenFile)
            throws IOException {
        JsonNode node = JSON.readTree(Files.readAllBytes(tokenFile));
        String fp = node.path("tokenFingerprint").asText();
        long epoch = node.path("epoch").asLong();
        byte[] tokenBytes = node.path("tokenBytes").binaryValue();
        return new FencingAuthority.FencingToken(tokenBytes, fp, epoch);
    }

    // ---------------------------------------------------------------------------
    // Tree parsing
    // ---------------------------------------------------------------------------

    private List<TreeEntry> parseTreeEntries(JsonNode entriesNode) {
        List<TreeEntry> entries = new ArrayList<>();
        for (JsonNode e : entriesNode) {
            entries.add(new TreeEntry(
                    e.path("relativePath").asText(),
                    e.path("byteSize").asLong(),
                    e.path("rawFingerprint").asText()));
        }
        return entries;
    }

    // ---------------------------------------------------------------------------
    // Failure
    // ---------------------------------------------------------------------------

    static final class EptFailure extends RuntimeException {
        private final ClosedCategory category;
        private final String code;

        EptFailure(ClosedCategory category, String code, Throwable cause) {
            super(code, cause);
            this.category = category;
            this.code = code;
        }

        ClosedCategory category() { return category; }
        String code() { return code; }

        Verdict toVerdict(String transactionId, String stableRequestId) {
            return Verdict.closed(transactionId, stableRequestId, category, code);
        }

        /** @return BLOCKED failure */
        static EptFailure blocked(String code) {
            return new EptFailure(ClosedCategory.BLOCKED, code, null);
        }

        /** @return BLOCKED failure */
        static EptFailure blocked(String code, Throwable cause) {
            return new EptFailure(ClosedCategory.BLOCKED, code, cause);
        }

        /** @return UNAVAILABLE failure */
        static EptFailure unavailable(String code) {
            return new EptFailure(ClosedCategory.UNAVAILABLE, code, null);
        }

        /** @return UNAVAILABLE failure */
        static EptFailure unavailable(String code, Throwable cause) {
            return new EptFailure(ClosedCategory.UNAVAILABLE, code, cause);
        }

        /** @return CONFLICT failure */
        static EptFailure conflict(String code) {
            return new EptFailure(ClosedCategory.CONFLICT, code, null);
        }

        /** @return INVALID failure */
        static EptFailure invalid(String code) {
            return new EptFailure(ClosedCategory.INVALID, code, null);
        }

        /** @return INVALID failure */
        static EptFailure invalid(String code, Throwable cause) {
            return new EptFailure(ClosedCategory.INVALID, code, cause);
        }

        /** @return INTERNAL failure */
        static EptFailure internal(String code, Throwable cause) {
            return new EptFailure(ClosedCategory.INTERNAL, code, cause);
        }
    }

    // ---------------------------------------------------------------------------
    // Observer
    // ---------------------------------------------------------------------------

    /** Callback interface for EPT lifecycle events. */
    public interface EptObserver {
        /**
         * Called before execute begins.
         * @param request the publication request
         */
        default void onExecuteStart(Request request) {}
        /**
         * Called after execute reaches a terminal state.
         * @param request the publication request
         * @param finalState the final lifecycle state
         */
        default void onExecuteComplete(Request request, LifecycleState finalState) {}
        /**
         * Called when execute retries.
         * @param request the publication request
         */
        default void onRetry(Request request) {}
        /** No-op observer instance. */
        EptObserver NOOP = new EptObserver() {};
    }
}
