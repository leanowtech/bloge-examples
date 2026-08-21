package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioDeploymentStateObservation.Observation;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioDeploymentStateObservation.Phase;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseTranscript.EvidencePublicationStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseTranscript.Transcript;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentAdmissionAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentUnavailableException;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseAttempt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseTransactionResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceFailureKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceJournalResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceJournalStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceLeaseBudget;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceTransactionJournal;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryJournal;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalEvidenceAuthorityBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalEvidenceRecoveryBinding;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.FormalTargetBoundAuthorityBinding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Full-evidence formal-v2 CLI using the exact Stage Acceptance validation path.
 *
 * <p>The command accepts a Stage Result path and a fresh transcript output path. It requires the
 * additive v4 evidence Provider capability, stages a strict BEFORE observation before lease
 * commit, persists or recovers the authority-issued transition witness, captures AFTER, and only
 * then publishes a canonical transcript. The inner commit manifest inventories immutable wrapper
 * artifacts but not itself; a separate final commitment binds the owner, final transcript, and
 * exact manifest raw/canonical fingerprints. This two-layer closure is non-circular. It never
 * falls back to an ordinary v2 lease authority.</p>
 */
public final class CapabilityStudioExecutionLeaseEvidenceCli {
    /** Evidence transaction identity domain. */
    public static final String EVIDENCE_TRANSACTION_MESSAGE_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-transaction.v1";
    private static final String OWNER_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-owner.v3";
    private static final String BEFORE_JOURNAL_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-before-journal.v2";
    private static final String ATTEMPT_CLOSURE_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-attempt-closure.v1";
    private static final String COMMIT_MANIFEST_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-commit-manifest.v1";
    private static final String OWNER_FILE = "owner-v3.json";
    private static final String COMMITTED_FILE = "committed-transcript-v1.json";
    private static final String COMMIT_MANIFEST_FILE = "commit-manifest-v1.json";
    private static final String FINAL_COMMIT_FILE = "final-commit-v1.json";
    private static final int MAXIMUM_COMMIT_MANIFEST_BYTES = 1024 * 1024;
    private static final String BUNDLE_COMMITMENT_VERSION =
            "resource-gateway.capability-studio.execution-lease-evidence-bundle-commitment.v1";
    static final Duration DEFAULT_PUBLICATION_LEASE_TIMEOUT = Duration.ofSeconds(5);
    private static final long PUBLICATION_LOCK_RETRY_NANOS =
            Duration.ofMillis(20).toNanos();
    private static final int PUBLICATION_LOCK_MISS_LIMIT = 1024;
    private static final int PUBLICATION_LOCK_STRIPES = 64;
    private static final int MAXIMUM_TRANSACTION_CHILDREN = 2 * 1_024 + 7;
    private static final int STICKY_BIT = 01000;
    private static final ReentrantLock[] JVM_PUBLICATION_LOCKS = publicationLocks();
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private CapabilityStudioExecutionLeaseEvidenceCli() {
    }

    /**
     * Runs one full-evidence formal acceptance attempt.
     *
     * @param args exact Stage Result and transcript output paths
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one full-evidence attempt without changing the ordinary CLI contract.
     *
     * @param args exact Stage Result and fresh transcript output paths
     * @param out payload-free one-line result output
     * @param err reserved payload-free error output
     * @return ordinary Stage Acceptance exit status
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return runWithProviderDiscovery(args, out, err);
    }

    private static int runWithProviderDiscovery(
            String[] args, PrintStream out, PrintStream err) {
        PrintStream safeOut = out == null ? System.out : out;
        if (args == null || args.length != 2) {
            return line(safeOut, "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.USAGE") ? 2 : 2;
        }
        Path transcriptOutput;
        try {
            transcriptOutput = absolute(args[1]);
        } catch (RuntimeException invalid) {
            line(safeOut, "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.OUTPUT_INVALID");
            return 2;
        }
        Instant semanticTime = Instant.now();
        String expectedPublication = System.getenv(
                CapabilityStudioExecutionLeaseEvidencePublication
                        .EXPECTED_PUBLICATION_FINGERPRINT_ENV);
        EvidenceFlow flow = new EvidenceFlow(transcriptOutput, semanticTime,
                expectedPublication);
        String expectedOuter = System.getenv(
                CapabilityStudioStageAcceptanceCli.EXPECTED_AUTHORITY_BINDING_ENV);
        return execute(args[0], safeOut, err, semanticTime, flow,
                CapabilityStudioStageAcceptanceCli::providers, expectedOuter,
                (stage, internal) -> CapabilityStudioStageAcceptanceCli
                        .runWithAcceptanceFlow(new String[]{stage}, internal, err,
                                semanticTime, flow));
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            Instant semanticTime,
            CapabilityStudioStageAcceptanceCli.ProviderSource providerSource,
            String expectedOuterFingerprint) {
        return run(args, out, err, semanticTime, providerSource, expectedOuterFingerprint,
                System.getProperty(CapabilityStudioExecutionLeaseEvidencePublication
                        .EXPECTED_PUBLICATION_FINGERPRINT_ENV));
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            Instant semanticTime,
            CapabilityStudioStageAcceptanceCli.ProviderSource providerSource,
            String expectedOuterFingerprint,
            String expectedPublicationFingerprint) {
        PrintStream safeOut = out == null ? System.out : out;
        if (args == null || args.length != 2) {
            line(safeOut, "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.USAGE");
            return 2;
        }
        try {
            EvidenceFlow flow = new EvidenceFlow(absolute(args[1]), semanticTime,
                    expectedPublicationFingerprint);
            return execute(args[0], safeOut, err, semanticTime, flow,
                    providerSource, expectedOuterFingerprint,
                    (stage, internal) -> CapabilityStudioStageAcceptanceCli.run(
                            new String[]{stage}, internal, err, semanticTime, providerSource,
                            expectedOuterFingerprint, flow));
        } catch (RuntimeException invalid) {
            line(safeOut, "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.OUTPUT_INVALID");
            return 2;
        }
    }

    private static ReentrantLock[] publicationLocks() {
        ReentrantLock[] locks = new ReentrantLock[PUBLICATION_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static FileLock acquirePublicationFileLock(
            FileChannel channel, EvidenceLeaseBudget budget)
            throws IOException, InterruptedException {
        int misses = (int) Math.min(PUBLICATION_LOCK_MISS_LIMIT,
                1L + (EvidenceLeaseBudget.MAXIMUM_NANOS - 1L)
                        / PUBLICATION_LOCK_RETRY_NANOS);
        while (misses-- > 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("evidence publication lock interrupted");
            }
            long remaining = budget.remainingNanos();
            if (remaining <= 0) {
                throw new IOException("evidence publication lock timeout");
            }
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    if (budget.remainingNanos() <= 0) {
                        lock.release();
                        throw new IOException("evidence publication lock timeout");
                    }
                    return lock;
                }
            } catch (OverlappingFileLockException unavailable) {
                // A non-cooperating channel in this JVM owns the same OS lock.
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining,
                    PUBLICATION_LOCK_RETRY_NANOS));
        }
        throw new IOException("evidence publication lock timeout");
    }

    private static CapabilityStudioExecutionLeaseEvidencePublication.Declaration
    requirePublicationPreflight(Path parent, String expectedFingerprint)
            throws EvidenceInvalidException, EvidenceUnavailableException {
        try {
            return CapabilityStudioExecutionLeaseEvidencePublication.verifyExisting(
                    parent, expectedFingerprint);
        } catch (CapabilityStudioExecutionLeaseEvidencePublication
                 .PublicationException failure) {
            if (failure.failureKind() == EvidenceFailureKind.UNAVAILABLE) {
                throw new EvidenceUnavailableException(
                        "evidence publication preflight unavailable");
            }
            throw new EvidenceInvalidException("evidence publication preflight invalid");
        }
    }

    private static AncestorChain capturePublicationChain(Path parent)
            throws EvidenceInvalidException, EvidenceUnavailableException {
        if (parent == null || !parent.isAbsolute() || !parent.equals(parent.normalize())) {
            throw new EvidenceInvalidException("evidence publication parent invalid");
        }
        List<AncestorIdentity> identities = new ArrayList<>();
        Path current = parent.getRoot();
        identities.add(ancestorIdentity(current));
        for (Path component : parent) {
            current = current.resolve(component);
            identities.add(ancestorIdentity(current));
        }
        AncestorIdentity publicationParent = identities.getLast();
        if (publicationParent.mode != 0700) {
            throw new EvidenceUnavailableException("evidence publication parent unavailable");
        }
        Set<Long> allowedOwners = Set.of(0L, publicationParent.ownerUid);
        for (int index = 0; index < identities.size(); index++) {
            AncestorIdentity identity = identities.get(index);
            if (!allowedOwners.contains(identity.ownerUid)) {
                throw new EvidenceUnavailableException("evidence ancestor owner unavailable");
            }
            if ((identity.mode & 0022) != 0
                    && ((identity.mode & STICKY_BIT) == 0
                    || index + 1 >= identities.size()
                    || !allowedOwners.contains(identities.get(index + 1).ownerUid))) {
                throw new EvidenceUnavailableException(
                        "evidence ancestor permissions unavailable");
            }
        }
        return new AncestorChain(List.copyOf(identities), publicationParent.ownerUid);
    }

    private static AncestorChain captureVerificationChain(Path parent)
            throws EvidenceInvalidException, EvidenceUnavailableException {
        if (parent == null || !parent.isAbsolute() || !parent.equals(parent.normalize())) {
            throw new EvidenceInvalidException("evidence verification parent invalid");
        }
        List<AncestorIdentity> identities = new ArrayList<>();
        Path current = parent.getRoot();
        identities.add(ancestorIdentity(current));
        for (Path component : parent) {
            current = current.resolve(component);
            identities.add(ancestorIdentity(current));
        }
        AncestorIdentity verificationParent = identities.getLast();
        if ((verificationParent.mode & 0022) != 0
                || (verificationParent.mode & 0500) != 0500) {
            throw new EvidenceInvalidException("evidence verification parent invalid");
        }
        Set<Long> allowedOwners = Set.of(0L, verificationParent.ownerUid);
        for (int index = 0; index < identities.size(); index++) {
            AncestorIdentity identity = identities.get(index);
            if (!allowedOwners.contains(identity.ownerUid)) {
                throw new EvidenceUnavailableException("evidence ancestor owner unavailable");
            }
            if ((identity.mode & 0022) != 0
                    && ((identity.mode & STICKY_BIT) == 0
                    || index + 1 >= identities.size()
                    || !allowedOwners.contains(identities.get(index + 1).ownerUid))) {
                throw new EvidenceUnavailableException(
                        "evidence ancestor permissions unavailable");
            }
        }
        return new AncestorChain(List.copyOf(identities), verificationParent.ownerUid);
    }

    private static AncestorIdentity ancestorIdentity(Path path)
            throws EvidenceInvalidException, EvidenceUnavailableException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new EvidenceInvalidException("evidence ancestor invalid");
            }
            if (attributes.fileKey() == null) {
                throw new EvidenceUnavailableException("evidence ancestor unavailable");
            }
            long uid = ((Number) Files.getAttribute(path, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            int mode = ((Number) Files.getAttribute(path, "unix:mode",
                    LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
            return new AncestorIdentity(path, attributes.fileKey(), uid, mode);
        } catch (EvidenceInvalidException | EvidenceUnavailableException failure) {
            throw failure;
        } catch (IOException | RuntimeException unavailable) {
            throw new EvidenceUnavailableException("evidence ancestor unavailable");
        }
    }

    private static void recheckAncestorChain(AncestorChain chain)
            throws EvidenceInvalidException, EvidenceUnavailableException {
        for (AncestorIdentity expected : chain.identities) {
            if (!expected.equals(ancestorIdentity(expected.path))) {
                throw new EvidenceUnavailableException("evidence ancestor changed");
            }
        }
    }

    private static int execute(
            String stage,
            PrintStream safeOut,
            PrintStream err,
            Instant semanticTime,
            EvidenceFlow flow,
            CapabilityStudioStageAcceptanceCli.ProviderSource providerSource,
            String expectedOuterFingerprint,
            StageInvoker invoker) {
        try {
            return flow.publication.withPublicationLease(() -> executeLocked(stage,
                    safeOut, err, semanticTime, flow, providerSource,
                    expectedOuterFingerprint, invoker));
        } catch (EvidenceInvalidException invalid) {
            return line(safeOut, "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.PUBLICATION_INVALID")
                    ? 2 : 2;
        } catch (EvidenceUnavailableException unavailable) {
            return line(safeOut, "NOT_ACCEPTED outcome=BLOCKED reasonCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.PUBLICATION_UNAVAILABLE")
                    ? CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED : 2;
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return line(safeOut, "NOT_ACCEPTED outcome=BLOCKED reasonCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.PUBLICATION_UNAVAILABLE")
                    ? CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED : 2;
        } catch (RuntimeException invalid) {
            return line(safeOut, "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.PUBLICATION_INVALID")
                    ? 2 : 2;
        }
    }

    private static int executeLocked(
            String stage,
            PrintStream safeOut,
            PrintStream err,
            Instant semanticTime,
            EvidenceFlow flow,
            CapabilityStudioStageAcceptanceCli.ProviderSource providerSource,
            String expectedOuterFingerprint,
            StageInvoker invoker) {
        Recovery recovery = flow.recoverBeforeValidation(stage,
                expectedOuterFingerprint, providerSource);
        if (recovery == Recovery.RECOVERED) {
            return emitSuccess(safeOut, flow);
        }
        if (recovery == Recovery.BLOCKED) {
            return line(safeOut, "NOT_ACCEPTED outcome=BLOCKED reasonCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.RECOVERY_UNAVAILABLE")
                    ? CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED : 2;
        }
        if (recovery == Recovery.INVALID) {
            return line(safeOut, "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.RECOVERY_INVALID")
                    ? 2 : 2;
        }
        ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        int exit;
        try (PrintStream internal = new PrintStream(buffered, true, StandardCharsets.UTF_8)) {
            exit = invoker.run(stage, internal);
        }
        if (exit != CapabilityStudioStageAcceptanceCli.EXIT_ACCEPTED) {
            return line(safeOut, exactSingleLine(buffered.toByteArray())) ? exit : 2;
        }
        Transcript transcript = flow.publishedTranscript;
        if (transcript == null || flow.publicationStatus == null) {
            return line(safeOut, "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.PUBLICATION") ? 2 : 2;
        }
        return emitSuccess(safeOut, flow);
    }

    private static int emitSuccess(PrintStream safeOut, EvidenceFlow flow) {
        String success = "ACCEPTED status=ACCEPTED evidencePublicationStatus="
                + flow.publicationStatus
                + " commitStatus=" + flow.evidenceResult.status()
                + " leaseReceiptFingerprint="
                + flow.evidenceResult.receipt().fingerprint()
                + " transitionWitnessFingerprint="
                + flow.evidenceResult.transitionWitness().fingerprint()
                + " transcriptFingerprint=" + flow.publishedTranscript.transcriptFingerprint()
                + " reasonCode="
                + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.ACCEPTED";
        return line(safeOut, success) ? 0 : 2;
    }

    private enum Recovery {
        NONE,
        RECOVERED,
        BLOCKED,
        INVALID
    }

    static class EvidenceInvalidException extends IOException {
        private EvidenceInvalidException(String message) {
            super(message);
        }

        private EvidenceInvalidException(String message, Throwable cause) {
            super(message, cause);
        }

        EvidenceFailureKind failureKind() {
            return EvidenceFailureKind.INVALID;
        }
    }

    static final class EvidenceUnavailableException extends IOException {
        private EvidenceUnavailableException(String message) {
            super(message);
        }

        EvidenceFailureKind failureKind() {
            return EvidenceFailureKind.UNAVAILABLE;
        }
    }

    private static final class EvidenceInvalidRuntimeException extends RuntimeException {
        private EvidenceInvalidRuntimeException(Throwable cause) {
            super(cause);
        }
    }

    @FunctionalInterface
    private interface StageInvoker {
        int run(String stage, PrintStream output);
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T run() throws EvidenceInvalidException, EvidenceUnavailableException;
    }

    private static final class EvidenceFlow
            implements CapabilityStudioStageAcceptanceCli.AcceptanceFlow {
        private final TranscriptPublication publication;
        private final Instant semanticTime;
        private FormalEvidenceAuthorityBinding binding;
        private EvidenceExecutionLeaseCommitResult evidenceResult;
        private Observation before;
        private Observation after;
        private String transactionId;
        private long attemptGeneration = 1;
        private String previousAttemptClosureFingerprint;
        private EvidencePublicationStatus publicationStatus;
        private Transcript publishedTranscript;

        private EvidenceFlow(
                Path output, Instant semanticTime, String expectedPublicationFingerprint) {
            this.publication = new TranscriptPublication(
                    output, expectedPublicationFingerprint);
            this.semanticTime = semanticTime;
        }

        private Recovery recoverBeforeValidation(
                String stage,
                String expectedOuterFingerprint,
                CapabilityStudioStageAcceptanceCli.ProviderSource providerSource) {
            try {
                if (expectedOuterFingerprint == null
                        || !expectedOuterFingerprint.matches("sha256:[0-9a-f]{64}")) {
                    return Recovery.INVALID;
                }
                byte[] stageBytes = CapabilityStudioBoundedFileReader.read(Path.of(stage),
                        CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES);
                String stageRaw = sha256(stageBytes);
                transactionId = publication.evidenceTransactionId();
                Transcript recovered = publication.recoverCommitted(
                        transactionId, stageRaw, expectedOuterFingerprint);
                if (recovered != null) {
                    acceptRecovered(recovered);
                    return Recovery.RECOVERED;
                }
                TranscriptPublication.BeforeJournal pending =
                        publication.readPending(transactionId, stageRaw,
                                expectedOuterFingerprint);
                if (pending == null) {
                    TranscriptPublication.AttemptCoordinates next =
                            publication.nextAttempt(transactionId,
                                    stageRaw, expectedOuterFingerprint);
                    attemptGeneration = next.generation();
                    previousAttemptClosureFingerprint =
                            next.previousAttemptClosureFingerprint();
                    return Recovery.NONE;
                }
                CapabilityStudioStageAcceptanceAuthorityProvider provider =
                        CapabilityStudioProviderOutputIsolation.call(
                                () -> CapabilityStudioStageAcceptanceCli
                                        .loadProvider(providerSource));
                if (provider == null) {
                    return Recovery.INVALID;
                }
                FormalEvidenceRecoveryBinding recoveryBinding =
                        CapabilityStudioProviderOutputIsolation.call(
                                provider::formalEvidenceRecoveryBinding);
                if (recoveryBinding == null
                        || !recoveryBinding.storeDescriptorFingerprint().equals(
                        pending.before.storeDescriptorFingerprint())) {
                    return Recovery.BLOCKED;
                }
                EvidenceExecutionLeaseAttempt attempt = new EvidenceExecutionLeaseAttempt(
                        pending.request, transactionId, pending.semanticVerificationTime,
                        publication.output.getParent(), pending.attemptGeneration,
                        pending.previousAttemptClosureFingerprint,
                        publication.leaseBudget());
                ExistingEvidenceRecoveryResult lookup =
                        CapabilityStudioProviderOutputIsolation.call(
                                () -> recoveryBinding.recovery().recoverExisting(
                                        attempt, publication.recoveryJournal(pending)));
                if (lookup == null) {
                    return Recovery.INVALID;
                }
                if (lookup.failureKind().orElse(null) == EvidenceFailureKind.UNAVAILABLE) {
                    lookup = CapabilityStudioProviderOutputIsolation.call(
                            () -> recoveryBinding.interruptedRecovery()
                                    .recoverInterrupted(attempt,
                                            publication.recoveryJournal(pending)));
                    if (lookup == null) {
                        return Recovery.INVALID;
                    }
                }
                if (lookup.status() == ExistingEvidenceRecoveryStatus.ABSENT) {
                    TranscriptPublication.AttemptCoordinates next =
                            publication.nextAttempt(transactionId,
                                    stageRaw, expectedOuterFingerprint);
                    attemptGeneration = next.generation();
                    previousAttemptClosureFingerprint =
                            next.previousAttemptClosureFingerprint();
                    return Recovery.NONE;
                }
                if (lookup.failureKind().orElse(null) == EvidenceFailureKind.INVALID) {
                    return Recovery.INVALID;
                }
                if (lookup.failureKind().orElse(null) == EvidenceFailureKind.UNAVAILABLE) {
                    return Recovery.BLOCKED;
                }
                evidenceResult = new EvidenceExecutionLeaseCommitResult(
                        ExecutionLeaseCommitStatus.RECOVERED, lookup.receipt(),
                        lookup.transitionWitness(), "LEASE_RECOVERED");
                before = lookup.beforeObservation();
                after = lookup.afterObservation();
                new EvidenceExecutionLeaseTransactionResult(before, after, evidenceResult);
                publication.persistCommitted(attempt, before, after, evidenceResult);
                Transcript completed = publication.recoverCommitted(
                        transactionId, stageRaw, expectedOuterFingerprint);
                if (completed == null) {
                    return Recovery.BLOCKED;
                }
                acceptRecovered(completed);
                return Recovery.RECOVERED;
            } catch (DeploymentUnavailableException unavailable) {
                return Recovery.BLOCKED;
            } catch (EvidenceInvalidException invalid) {
                return Recovery.INVALID;
            } catch (EvidenceUnavailableException unavailable) {
                return Recovery.BLOCKED;
            } catch (IOException unavailable) {
                return Recovery.BLOCKED;
            } catch (RuntimeException invalid) {
                return Recovery.INVALID;
            }
        }

        private void acceptRecovered(Transcript transcript) {
            publishedTranscript = transcript;
            publicationStatus = EvidencePublicationStatus.RECOVERED;
            evidenceResult = new EvidenceExecutionLeaseCommitResult(
                    ExecutionLeaseCommitStatus.RECOVERED,
                    transcript.executionLeaseReceipt(),
                    transcript.executionLeaseTransitionWitness(), "LEASE_RECOVERED");
        }

        @Override
        public FormalTargetBoundAuthorityBinding formalBinding(
                CapabilityStudioStageAcceptanceAuthorityProvider provider) {
            binding = provider.formalEvidenceAuthorityBinding();
            if (binding == null) {
                throw new DeploymentUnavailableException();
            }
            return binding.formalBinding();
        }

        @Override
        public ExecutionLeaseCommitResult commit(
                DeploymentAdmissionAuthorityBinding ignored,
                ExecutionLeaseRequest request) {
            transactionId = publication.evidenceTransactionId();
            try {
                EvidenceExecutionLeaseAttempt attempt = new EvidenceExecutionLeaseAttempt(
                        request, transactionId, semanticTime,
                        publication.output.getParent(), attemptGeneration,
                        previousAttemptClosureFingerprint, publication.leaseBudget());
                EvidenceExecutionLeaseTransactionResult transaction =
                        binding.transactionAuthority().commit(attempt, publication);
                if (transaction == null) {
                    throw new IllegalArgumentException("evidence transaction is invalid");
                }
                evidenceResult = transaction.leaseResult();
                before = transaction.beforeObservation();
                after = transaction.afterObservation();
            } catch (EvidenceInvalidRuntimeException invalid) {
                throw invalid;
            } catch (IllegalArgumentException invalid) {
                throw new EvidenceInvalidRuntimeException(invalid);
            } catch (DeploymentUnavailableException unavailable) {
                throw unavailable;
            } catch (RuntimeException invalid) {
                throw new EvidenceInvalidRuntimeException(invalid);
            }
            if (evidenceResult == null) {
                throw new IllegalArgumentException("evidence lease result is invalid");
            }
            return new ExecutionLeaseCommitResult(evidenceResult.status(),
                    evidenceResult.receipt(), evidenceResult.reasonCode());
        }

        @Override
        public boolean afterValidatedCommit(
                ExecutionLeaseRequest request,
                ExecutionLeaseCommitResult ignored) {
            try {
                Transcript candidate = CapabilityStudioExecutionLeaseTranscript.create(
                        transactionId, EvidencePublicationStatus.COMMITTED, before, after,
                        request, evidenceResult, semanticTime);
                TranscriptPublication.Result result = publication.publish(candidate, request,
                        evidenceResult);
                publicationStatus = result.status;
                publishedTranscript = result.transcript;
                return true;
            } catch (EvidenceInvalidException invalid) {
                throw new EvidenceInvalidRuntimeException(invalid);
            } catch (EvidenceUnavailableException unavailable) {
                throw new DeploymentUnavailableException();
            } catch (IOException unavailable) {
                throw new DeploymentUnavailableException();
            } catch (RuntimeException invalid) {
                throw new EvidenceInvalidRuntimeException(invalid);
            }
        }
    }

    private static final class TranscriptPublication implements EvidenceTransactionJournal {
        private static final long MAXIMUM_ATTEMPTS = 1_024;
        private final Path output;
        private final String expectedPublicationFingerprint;
        private boolean publicationLeaseHeld;
        private FileLock publicationFileLock;
        private StrictIdentity publicationLockIdentity;
        private AncestorChain publicationChain;
        private EvidenceLeaseBudget activeBudget;
        private CapabilityStudioExecutionLeaseEvidencePublication.Declaration
                publicationDeclaration;

        private TranscriptPublication(
                Path output, String expectedPublicationFingerprint) {
            this.output = output;
            this.expectedPublicationFingerprint = expectedPublicationFingerprint;
        }

        private <T> T withPublicationLease(LockedAction<T> action)
                throws EvidenceInvalidException, EvidenceUnavailableException {
            EvidenceLeaseBudget budget = EvidenceLeaseBudget.start();
            Path parent;
            try {
                parent = requirePrivateParent(output);
            } catch (EvidenceInvalidException invalid) {
                throw invalid;
            } catch (IOException unavailable) {
                throw new EvidenceUnavailableException(
                        "evidence publication parent unavailable");
            }
            AncestorChain chain = capturePublicationChain(parent);
            CapabilityStudioExecutionLeaseEvidencePublication.Declaration declaration =
                    requirePublicationPreflight(parent, expectedPublicationFingerprint);
            requireDeclaredOutput(parent, declaration);
            Path lockPath = parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE);
            ReentrantLock monitor = JVM_PUBLICATION_LOCKS[Math.floorMod(
                    parent.hashCode(), JVM_PUBLICATION_LOCKS.length)];
            boolean acquired = false;
            try {
                long remaining = budget.remainingNanos();
                if (remaining <= 0 || !monitor.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                    throw new EvidenceUnavailableException("evidence publication lock timeout");
                }
                acquired = true;
                recheckAncestorChain(chain);
                StrictIdentity identity;
                try {
                    identity = strictIdentity(lockPath, Set.of(0600), Set.of(1L));
                } catch (EvidenceInvalidException invalid) {
                    throw invalid;
                } catch (IOException unavailable) {
                    throw new EvidenceUnavailableException(
                            "evidence publication lock unavailable");
                }
                try (FileChannel channel = FileChannel.open(lockPath,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock fileLock = acquirePublicationFileLock(channel, budget)) {
                    if (!identity.equals(strictIdentity(lockPath,
                            Set.of(0600), Set.of(1L)))) {
                        throw new EvidenceUnavailableException(
                                "evidence publication lock changed");
                    }
                    recheckAncestorChain(chain);
                    publicationLeaseHeld = true;
                    publicationFileLock = fileLock;
                    publicationLockIdentity = identity;
                    publicationChain = chain;
                    activeBudget = budget;
                    publicationDeclaration = declaration;
                    T result = action.run();
                    assertPublicationLease();
                    return result;
                } catch (EvidenceInvalidException | EvidenceUnavailableException failure) {
                    throw failure;
                } catch (IOException failure) {
                    throw new EvidenceUnavailableException(
                            "evidence publication lock unavailable");
                } finally {
                    publicationLeaseHeld = false;
                    publicationFileLock = null;
                    publicationLockIdentity = null;
                    publicationChain = null;
                    activeBudget = null;
                    publicationDeclaration = null;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new EvidenceUnavailableException("evidence publication lock interrupted");
            } finally {
                if (acquired) {
                    monitor.unlock();
                }
            }
        }

        private void assertPublicationLease() throws IOException {
            if (!publicationLeaseHeld || publicationFileLock == null
                    || !publicationFileLock.isValid() || publicationLockIdentity == null
                    || publicationChain == null || activeBudget == null
                    || publicationDeclaration == null
                    || activeBudget.remainingNanos() <= 0) {
                throw new EvidenceUnavailableException("evidence publication lease unavailable");
            }
            recheckAncestorChain(publicationChain);
            Path parent = output.getParent();
            Path lockPath = parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE);
            if (!publicationLockIdentity.equals(strictIdentity(
                    lockPath, Set.of(0600), Set.of(1L)))) {
                throw new EvidenceUnavailableException(
                        "evidence publication lock changed");
            }
            CapabilityStudioExecutionLeaseEvidencePublication.Declaration current;
            try {
                current = CapabilityStudioExecutionLeaseEvidencePublication
                        .verifyExistingWhileLocked(
                                parent, expectedPublicationFingerprint);
            } catch (CapabilityStudioExecutionLeaseEvidencePublication
                     .PublicationException failure) {
                if (failure.failureKind() == EvidenceFailureKind.UNAVAILABLE) {
                    throw new EvidenceUnavailableException(
                            "evidence publication preflight unavailable");
                }
                throw new EvidenceInvalidException(
                        "evidence publication preflight invalid");
            }
            if (!publicationDeclaration.equals(current)) {
                throw new EvidenceUnavailableException(
                        "evidence publication preflight changed");
            }
            requireDeclaredOutput(parent, publicationDeclaration);
        }

        private String evidenceTransactionId() {
            if (!publicationLeaseHeld || publicationDeclaration == null) {
                throw new DeploymentUnavailableException();
            }
            return publicationDeclaration.evidenceTransactionId();
        }

        private void requireDeclaredOutput(
                Path parent,
                CapabilityStudioExecutionLeaseEvidencePublication.Declaration declaration)
                throws EvidenceInvalidException {
            Path declared = parent.resolve(declaration.transcriptRelativePath()).normalize();
            if (!parent.equals(declared.getParent()) || !output.equals(declared)) {
                throw new EvidenceInvalidException("evidence output is not provisioned");
            }
        }

        private EvidenceLeaseBudget leaseBudget() {
            if (!publicationLeaseHeld || activeBudget == null
                    || activeBudget.remainingNanos() <= 0) {
                throw new DeploymentUnavailableException();
            }
            return activeBudget;
        }

        private Transcript recoverCommitted(
                String transactionId,
                String stageRawFingerprint,
                String expectedOuterFingerprint) throws IOException {
            assertPublicationLease();
            requirePrivateParent(output);
            Path transaction = transactionDirectory(transactionId);
            if (!Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw new EvidenceInvalidException("unowned evidence output");
                }
                Path claim = ownerClaim(transactionId);
                if (Files.exists(claim, LinkOption.NOFOLLOW_LINKS)) {
                    parseOwner(readStrict(claim, 16 * 1024, Set.of(1L)),
                            transactionId, stageRawFingerprint, expectedOuterFingerprint);
                }
                return null;
            }
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(transaction.resolve(OWNER_FILE),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new EvidenceInvalidException("unowned evidence transaction");
            }
            Owner owner = recoverPreparedOwner(transaction, transactionId,
                    stageRawFingerprint, expectedOuterFingerprint);
            AttemptChain chain = readAttemptChain(transaction, owner);
            Path committed = transaction.resolve(COMMITTED_FILE);
            Path committedPart = part(committed);
            if (Files.exists(committedPart, LinkOption.NOFOLLOW_LINKS)) {
                Transcript candidate = requireExactTranscript(readTranscript(committedPart),
                        transactionId, stageRawFingerprint, expectedOuterFingerprint);
                installOwnedFile(committedPart, committed, candidate.bytes());
            }
            if (!Files.exists(committed, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(transaction.resolve(COMMIT_MANIFEST_FILE),
                        LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(transaction.resolve(FINAL_COMMIT_FILE),
                        LinkOption.NOFOLLOW_LINKS)) {
                    throw new EvidenceInvalidException("evidence committed source missing");
                }
                return null;
            }
            Transcript transcript = requireExactTranscript(readTranscript(committed),
                    transactionId, stageRawFingerprint, expectedOuterFingerprint);
            CommitManifest manifest = recoverCommitManifest(transaction, owner, chain);
            if (manifest == null) {
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    throw new EvidenceInvalidException("uncommitted evidence output");
                }
                return null;
            }
            requireManifest(manifest, owner, chain, transcript);
            byte[] manifestBytes = readStrict(transaction.resolve(COMMIT_MANIFEST_FILE),
                    MAXIMUM_COMMIT_MANIFEST_BYTES);
            FinalCommit commitment = recoverFinalCommit(transaction, owner, transcript,
                    manifestBytes, manifest);
            requireFinalCommit(commitment, owner, transcript, manifestBytes, manifest);
            assertPublicationLease();
            publishRetained(committed, output, transcript.bytes());
            assertPublicationLease();
            Transcript recovered = requireExactTranscript(readTranscript(output),
                    transactionId, stageRawFingerprint, expectedOuterFingerprint);
            if (!Arrays.equals(transcript.bytes(), recovered.bytes())) {
                throw new EvidenceInvalidException("evidence output mismatch");
            }
            requireManifest(manifest, owner, chain, recovered);
            requireFinalCommit(commitment, owner, recovered, manifestBytes, manifest);
            return recovered;
        }

        private Transcript verifyCommittedReadOnly(
                String stageRawFingerprint,
                String expectedOuterFingerprint) throws IOException {
            Path parent = output.getParent();
            AncestorChain chain = captureVerificationChain(parent);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.exists(parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE),
                    LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE),
                    LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication
                            .PUBLICATION_DECLARATION_FILE), LinkOption.NOFOLLOW_LINKS))) {
                throw new EvidenceInvalidException(
                        "evidence committed publication preflight is incomplete");
            }
            try {
                publicationDeclaration = CapabilityStudioExecutionLeaseEvidencePublication
                        .verifyExisting(parent, expectedPublicationFingerprint);
            } catch (CapabilityStudioExecutionLeaseEvidencePublication
                     .PublicationException failure) {
                if (failure.failureKind() == EvidenceFailureKind.UNAVAILABLE) {
                    throw new EvidenceUnavailableException(
                            "evidence publication preflight unavailable");
                }
                throw new EvidenceInvalidException(
                        "evidence publication preflight invalid");
            }
            requireDeclaredOutput(parent, publicationDeclaration);
            String transactionId = publicationDeclaration.evidenceTransactionId();
            Path transaction = transactionDirectory(transactionId);
            BundleInventory inventoryBefore = captureBundleInventory(parent, transaction);
            requirePublicationClosure(inventoryBefore, parent, transaction, transactionId);
            StrictIdentity transactionBefore = directoryIdentity(transaction, 0700);
            Owner owner = requireOwner(transaction, transactionId,
                    stageRawFingerprint, expectedOuterFingerprint);
            Path committed = transaction.resolve(COMMITTED_FILE);
            Path manifestPath = transaction.resolve(COMMIT_MANIFEST_FILE);
            Path commitmentPath = transaction.resolve(FINAL_COMMIT_FILE);
            Set<String> wrapperNames = inventoryBefore.wrapperNames();
            if (!wrapperNames.containsAll(Set.of(OWNER_FILE, COMMITTED_FILE,
                    COMMIT_MANIFEST_FILE, FINAL_COMMIT_FILE))) {
                throw new EvidenceInvalidException("evidence committed closure invalid");
            }
            byte[] manifestBytes = readStrict(
                    manifestPath, MAXIMUM_COMMIT_MANIFEST_BYTES);
            CommitManifest manifest = parseCommitManifest(manifestBytes);
            Set<String> declaredWrapper = new HashSet<>();
            for (ArtifactEntry artifact : manifest.artifacts) {
                declaredWrapper.add(artifact.relativePath);
            }
            declaredWrapper.add(COMMIT_MANIFEST_FILE);
            declaredWrapper.add(FINAL_COMMIT_FILE);
            if (!declaredWrapper.equals(wrapperNames)) {
                throw new EvidenceInvalidException("evidence wrapper closure invalid");
            }
            requireWrapperMetadata(inventoryBefore, transaction, owner, manifest);
            AttemptChain attempts = readAttemptChainReadOnly(
                    transaction, owner, wrapperNames);
            if (attempts.pending == null) {
                throw new EvidenceInvalidException("evidence committed attempt missing");
            }
            Transcript transcript = requireExactTranscript(readTranscript(committed),
                    transactionId, stageRawFingerprint, expectedOuterFingerprint);
            requireManifest(manifest, owner, attempts, transcript);
            requireFinalCommit(parseFinalCommit(readStrict(commitmentPath, 64 * 1024)),
                    owner, transcript, manifestBytes, manifest);
            StrictIdentity sourceBefore = strictIdentity(
                    committed, Set.of(0400), Set.of(2L));
            StrictIdentity outputBefore = strictIdentity(output, Set.of(0400), Set.of(2L));
            if (!java.util.Objects.equals(sourceBefore.fileKey, outputBefore.fileKey)
                    || !Arrays.equals(transcript.bytes(), readStrict(output,
                    CapabilityStudioExecutionLeaseTranscript.MAXIMUM_BYTES,
                    Set.of(2L)))) {
                throw new EvidenceInvalidException("evidence retained output invalid");
            }
            requireTransactionClosureReadOnly(transaction);
            try {
                CapabilityStudioExecutionLeaseEvidencePublication.verifyExisting(
                        parent, expectedPublicationFingerprint);
            } catch (CapabilityStudioExecutionLeaseEvidencePublication
                     .PublicationException failure) {
                throw new EvidenceUnavailableException(
                        "evidence publication preflight changed");
            }
            BundleInventory inventoryAfter = captureBundleInventory(parent, transaction);
            recheckAncestorChain(chain);
            if (!inventoryBefore.equals(inventoryAfter)
                    || !transactionBefore.equals(directoryIdentity(transaction, 0700))
                    || !sourceBefore.equals(strictIdentity(
                    committed, Set.of(0400), Set.of(2L)))
                    || !outputBefore.equals(strictIdentity(output, Set.of(0400), Set.of(2L)))) {
                throw new EvidenceUnavailableException("evidence bundle changed");
            }
            return transcript;
        }

        private AttemptChain readAttemptChainReadOnly(Path transaction, Owner owner)
                throws IOException {
            return readAttemptChainReadOnly(transaction, owner, childNames(transaction));
        }

        private AttemptChain readAttemptChainReadOnly(
                Path transaction, Owner owner, Set<String> names) throws IOException {
            String previous = null;
            long generation = 1;
            while (generation <= MAXIMUM_ATTEMPTS) {
                Path before = beforePath(transaction, generation);
                Path closure = closurePath(transaction, generation);
                boolean hasBefore = names.contains(before.getFileName().toString());
                boolean hasClosure = names.contains(closure.getFileName().toString());
                if (!hasBefore) {
                    if (hasClosure || hasLaterAttempt(names, generation)) {
                        throw new EvidenceInvalidException("evidence attempt chain gap");
                    }
                    return new AttemptChain(null, generation, previous);
                }
                BeforeJournal journal = parseBefore(readStrict(before,
                        CapabilityStudioExecutionLeaseTranscript.MAXIMUM_BYTES));
                requirePendingCoordinates(journal, owner, generation, previous);
                if (!hasClosure) {
                    if (hasLaterAttempt(names, generation)) {
                        throw new EvidenceInvalidException("evidence attempt chain fork");
                    }
                    return new AttemptChain(journal, generation, previous);
                }
                AttemptClosure closed = parseAttemptClosure(readStrict(closure, 32 * 1024));
                if (closed.attemptGeneration != generation
                        || !closed.beforeJournalFingerprint.equals(journal.fingerprint)
                        || !java.util.Objects.equals(
                        closed.previousAttemptClosureFingerprint, previous)) {
                    throw new EvidenceInvalidException("evidence attempt closure invalid");
                }
                previous = closed.fingerprint;
                generation++;
            }
            throw new EvidenceUnavailableException("evidence attempt capacity unavailable");
        }

        private BeforeJournal readPending(
                String transactionId,
                String stageRawFingerprint,
                String expectedOuterFingerprint) throws IOException {
            assertPublicationLease();
            Path transaction = transactionDirectory(transactionId);
            if (!Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Owner owner = requireOwner(transaction, transactionId, stageRawFingerprint,
                    expectedOuterFingerprint);
            return readAttemptChain(transaction, owner).pending;
        }

        private AttemptCoordinates nextAttempt(
                String transactionId,
                String stageRawFingerprint,
                String expectedOuterFingerprint) throws IOException {
            assertPublicationLease();
            Path transaction = transactionDirectory(transactionId);
            if (!Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
                return new AttemptCoordinates(1, null);
            }
            Owner owner = requireOwner(transaction, transactionId, stageRawFingerprint,
                    expectedOuterFingerprint);
            AttemptChain chain = readAttemptChain(transaction, owner);
            if (chain.pending != null) {
                return new AttemptCoordinates(chain.pending.attemptGeneration,
                        chain.pending.previousAttemptClosureFingerprint);
            }
            return new AttemptCoordinates(chain.nextGeneration,
                    chain.previousClosureFingerprint);
        }

        private void closeAbsent(BeforeJournal pending) throws IOException {
            assertPublicationLease();
            Path transaction = transactionDirectory(pending.transactionId);
            Owner owner = requireOwner(transaction, pending.transactionId,
                    pending.request.stageResultRawFingerprint(),
                    pending.request.providerOuterFingerprint());
            AttemptChain chain = readAttemptChain(transaction, owner);
            if (chain.pending == null || !chain.pending.equals(pending)) {
                throw new EvidenceInvalidException("evidence attempt conflict");
            }
            AttemptClosure closure = AttemptClosure.create(pending);
            publishOwned(closurePath(transaction, pending.attemptGeneration),
                    closureBytes(closure, closure.fingerprint));
            AttemptChain closed = readAttemptChain(transaction, owner);
            if (closed.pending != null
                    || !closure.fingerprint.equals(closed.previousClosureFingerprint)) {
                throw new EvidenceUnavailableException("evidence attempt closure unavailable");
            }
        }

        private void closeAbsentForRecovery(BeforeJournal pending) {
            try {
                closeAbsent(pending);
            } catch (EvidenceInvalidException invalid) {
                throw new EvidenceInvalidRuntimeException(invalid);
            } catch (IOException unavailable) {
                throw new DeploymentUnavailableException();
            }
        }

        private ExistingEvidenceRecoveryJournal recoveryJournal(BeforeJournal pending) {
            return new ExistingEvidenceRecoveryJournal() {
                @Override
                public void closeAbsent(EvidenceExecutionLeaseAttempt ignored) {
                    closeAbsentForRecovery(pending);
                }

                @Override
                public EvidenceJournalResult<Void> closeAbsentResult(
                        EvidenceExecutionLeaseAttempt ignored) {
                    try {
                        TranscriptPublication.this.closeAbsent(pending);
                        return EvidenceJournalResult.completed();
                    } catch (EvidenceInvalidException invalid) {
                        return EvidenceJournalResult.invalid();
                    } catch (IOException | RuntimeException unavailable) {
                        return EvidenceJournalResult.unavailable();
                    }
                }
            };
        }

        @Override
        public EvidenceJournalResult<Observation> prepareBeforeResult(
                EvidenceExecutionLeaseAttempt attempt,
                Observation current) {
            try {
                return EvidenceJournalResult.completed(prepareBefore(attempt, current));
            } catch (EvidenceInvalidRuntimeException invalid) {
                return EvidenceJournalResult.invalid();
            } catch (RuntimeException unavailable) {
                return EvidenceJournalResult.unavailable();
            }
        }

        @Override
        public Observation prepareBefore(
                EvidenceExecutionLeaseAttempt attempt,
                Observation current) {
            String transactionId = attempt.evidenceTransactionId();
            ExecutionLeaseRequest request = attempt.request();
            try {
                assertPublicationLease();
                Owner owner = ensureOwner(attempt);
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    Transcript recovered = recoverCommitted(transactionId,
                            request.stageResultRawFingerprint(),
                            request.providerOuterFingerprint());
                    if (recovered == null) {
                        throw new EvidenceInvalidException("evidence output is unowned");
                    }
                    return recovered.beforeStateObservation();
                }
                Path transaction = transactionDirectory(transactionId);
                AttemptChain chain = readAttemptChain(transaction, owner);
                if (chain.pending != null) {
                    if (!chain.pending.request.equals(request)
                            || chain.pending.attemptGeneration != attempt.attemptGeneration()
                            || !java.util.Objects.equals(
                            chain.pending.previousAttemptClosureFingerprint,
                            attempt.previousAttemptClosureFingerprint())) {
                        throw new EvidenceInvalidException("evidence request conflict");
                    }
                    return chain.pending.before;
                }
                if (chain.nextGeneration != attempt.attemptGeneration()
                        || !java.util.Objects.equals(chain.previousClosureFingerprint,
                        attempt.previousAttemptClosureFingerprint())
                        || current == null || current.phase() != Phase.BEFORE
                        || !transactionId.equals(current.evidenceTransactionId())) {
                    throw new EvidenceInvalidException("evidence attempt coordinates invalid");
                }
                BeforeJournal journal = BeforeJournal.create(transactionId,
                        request, current, attempt.semanticVerificationTime(), owner.fingerprint,
                        attempt.attemptGeneration(),
                        attempt.previousAttemptClosureFingerprint());
                Path before = beforePath(transaction, attempt.attemptGeneration());
                byte[] bytes = beforeBytes(journal, journal.fingerprint);
                prepareOwnedSource(part(before), bytes);
                installOwnedFile(part(before), before, bytes);
                return current;
            } catch (EvidenceInvalidException invalid) {
                throw new EvidenceInvalidRuntimeException(invalid);
            } catch (EvidenceUnavailableException unavailable) {
                throw new DeploymentUnavailableException();
            } catch (IOException unavailable) {
                throw new DeploymentUnavailableException();
            } catch (RuntimeException unavailable) {
                throw new DeploymentUnavailableException();
            }
        }

        @Override
        public EvidenceJournalResult<Void> persistCommittedResult(
                EvidenceExecutionLeaseAttempt attempt,
                Observation before,
                Observation after,
                EvidenceExecutionLeaseCommitResult result) {
            try {
                persistCommitted(attempt, before, after, result);
                return EvidenceJournalResult.completed();
            } catch (EvidenceInvalidRuntimeException invalid) {
                return EvidenceJournalResult.invalid();
            } catch (RuntimeException unavailable) {
                return EvidenceJournalResult.unavailable();
            }
        }

        @Override
        public void persistCommitted(
                EvidenceExecutionLeaseAttempt attempt,
                Observation before,
                Observation after,
                EvidenceExecutionLeaseCommitResult result) {
            try {
                assertPublicationLease();
                Owner owner = ensureOwner(attempt);
                Path transaction = transactionDirectory(attempt.evidenceTransactionId());
                AttemptChain chain = readAttemptChain(transaction, owner);
                if (chain.pending == null
                        || chain.pending.attemptGeneration != attempt.attemptGeneration()
                        || !chain.pending.before.equals(before)
                        || !chain.pending.request.equals(attempt.request())) {
                    throw new EvidenceInvalidException("evidence commit attempt invalid");
                }
                Path committed = transaction.resolve(COMMITTED_FILE);
                Transcript transcript;
                if (Files.exists(committed, LinkOption.NOFOLLOW_LINKS)) {
                    transcript = requireExactTranscript(readTranscript(committed),
                            attempt.evidenceTransactionId(),
                            attempt.request().stageResultRawFingerprint(),
                            attempt.request().providerOuterFingerprint());
                    if (!transcript.beforeStateObservation().equals(before)
                            || !transcript.afterStateObservation().equals(after)
                            || !transcript.executionLeaseRequest().equals(attempt.request())
                            || !transcript.executionLeaseReceipt().equals(result.receipt())
                            || !transcript.executionLeaseTransitionWitness().equals(
                            result.transitionWitness())) {
                        throw new EvidenceInvalidException(
                                "evidence committed transcript conflict");
                    }
                } else {
                    transcript = CapabilityStudioExecutionLeaseTranscript.create(
                            attempt.evidenceTransactionId(),
                            EvidencePublicationStatus.COMMITTED,
                            before, after, attempt.request(), result,
                            attempt.semanticVerificationTime());
                    Path committedPart = part(committed);
                    prepareOwnedSource(committedPart, transcript.bytes());
                    installOwnedFile(committedPart, committed, transcript.bytes());
                }
                Transcript exactCommitted = requireExactTranscript(readTranscript(committed),
                        attempt.evidenceTransactionId(),
                        attempt.request().stageResultRawFingerprint(),
                        attempt.request().providerOuterFingerprint());
                if (!Arrays.equals(exactCommitted.bytes(), transcript.bytes())) {
                    throw new EvidenceInvalidException(
                            "evidence committed transcript conflict");
                }
                CommitManifest manifest = CommitManifest.create(owner, chain.pending,
                        transcript, result,
                        artifactInventory(transaction, owner, chain, transcript));
                Path manifestPath = transaction.resolve(COMMIT_MANIFEST_FILE);
                if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                    CommitManifest existing = parseCommitManifest(readStrict(
                            manifestPath, 64 * 1024));
                    if (!existing.equals(manifest)) {
                        throw new EvidenceInvalidException("evidence commit manifest conflict");
                    }
                } else {
                    byte[] bytes = commitManifestBytes(manifest, manifest.fingerprint);
                    prepareOwnedSource(part(manifestPath), bytes);
                    installOwnedFile(part(manifestPath), manifestPath, bytes);
                }
                byte[] manifestBytes = readStrict(
                        manifestPath, MAXIMUM_COMMIT_MANIFEST_BYTES);
                FinalCommit commitment = FinalCommit.create(owner,
                        output.getFileName().toString(), transcript, manifestBytes,
                        manifest.fingerprint);
                Path commitmentPath = transaction.resolve(FINAL_COMMIT_FILE);
                if (Files.exists(commitmentPath, LinkOption.NOFOLLOW_LINKS)) {
                    if (!parseFinalCommit(readStrict(commitmentPath, 64 * 1024))
                            .equals(commitment)) {
                        throw new EvidenceInvalidException(
                                "evidence final commitment conflict");
                    }
                } else {
                    byte[] bytes = finalCommitBytes(commitment, commitment.fingerprint);
                    prepareOwnedSource(part(commitmentPath), bytes);
                    installOwnedFile(part(commitmentPath), commitmentPath, bytes);
                }
                requireManifest(parseCommitManifest(readStrict(
                        manifestPath, MAXIMUM_COMMIT_MANIFEST_BYTES)), owner, chain,
                        transcript);
                requireFinalCommit(parseFinalCommit(readStrict(commitmentPath, 64 * 1024)),
                        owner, transcript, manifestBytes, manifest);
            } catch (EvidenceInvalidException invalid) {
                throw new EvidenceInvalidRuntimeException(invalid);
            } catch (EvidenceUnavailableException unavailable) {
                throw new DeploymentUnavailableException();
            } catch (IOException unavailable) {
                throw new DeploymentUnavailableException();
            } catch (RuntimeException unavailable) {
                throw new DeploymentUnavailableException();
            }
        }

        private Result publish(
                Transcript candidate,
                ExecutionLeaseRequest request,
                EvidenceExecutionLeaseCommitResult leaseResult) throws IOException {
            assertPublicationLease();
            boolean existed = Files.exists(output, LinkOption.NOFOLLOW_LINKS);
            Transcript persisted = recoverCommitted(candidate.evidenceTransactionId(),
                    request.stageResultRawFingerprint(), request.providerOuterFingerprint());
            if (persisted == null
                    || !leaseResult.receipt().fingerprint().equals(
                    persisted.executionLeaseReceipt().fingerprint())
                    || !leaseResult.transitionWitness().fingerprint().equals(
                    persisted.executionLeaseTransitionWitness().fingerprint())) {
                throw new EvidenceInvalidException("evidence durable publication invalid");
            }
            return new Result(existed ? EvidencePublicationStatus.RECOVERED
                    : EvidencePublicationStatus.COMMITTED, persisted);
        }

        private CommitManifest recoverCommitManifest(
                Path transaction, Owner owner, AttemptChain chain) throws IOException {
            requireTransactionClosure(transaction);
            Path manifest = transaction.resolve(COMMIT_MANIFEST_FILE);
            Path manifestPart = part(manifest);
            if (Files.exists(manifestPart, LinkOption.NOFOLLOW_LINKS)) {
                CommitManifest candidate = parseCommitManifest(readStrict(
                        manifestPart, MAXIMUM_COMMIT_MANIFEST_BYTES));
                installOwnedFile(manifestPart, manifest,
                        commitManifestBytes(candidate, candidate.fingerprint));
            }
            if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            return parseCommitManifest(readStrict(
                    manifest, MAXIMUM_COMMIT_MANIFEST_BYTES));
        }

        private FinalCommit recoverFinalCommit(
                Path transaction,
                Owner owner,
                Transcript transcript,
                byte[] manifestBytes,
                CommitManifest manifest) throws IOException {
            Path target = transaction.resolve(FINAL_COMMIT_FILE);
            Path source = part(target);
            FinalCommit expected = FinalCommit.create(owner,
                    output.getFileName().toString(), transcript, manifestBytes,
                    manifest.fingerprint);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                FinalCommit candidate = parseFinalCommit(readStrict(
                        source, 64 * 1024, Set.of(1L, 2L)));
                if (!candidate.equals(expected)) {
                    throw new EvidenceInvalidException(
                            "evidence final commitment conflict");
                }
                installOwnedFile(source, target,
                        finalCommitBytes(candidate, candidate.fingerprint));
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = finalCommitBytes(expected, expected.fingerprint);
                prepareOwnedSource(source, bytes);
                installOwnedFile(source, target, bytes);
            }
            FinalCommit actual = parseFinalCommit(readStrict(target, 64 * 1024));
            if (!actual.equals(expected)) {
                throw new EvidenceInvalidException("evidence final commitment conflict");
            }
            return actual;
        }

        private void requireManifest(
                CommitManifest manifest,
                Owner owner,
                AttemptChain chain,
                Transcript transcript) throws IOException {
            BeforeJournal pending = chain.pending;
            if (pending == null
                    || !manifest.ownerFingerprint.equals(owner.fingerprint)
                    || manifest.attemptGeneration != pending.attemptGeneration
                    || !java.util.Objects.equals(manifest.previousAttemptClosureFingerprint,
                    pending.previousAttemptClosureFingerprint)
                    || !manifest.requestCommitIdentityFingerprint.equals(
                    pending.request.commitIdentityFingerprint())
                    || !manifest.beforeRawFingerprint.equals(sha256(pending.before.bytes()))
                    || !manifest.beforeJournalFingerprint.equals(pending.fingerprint)
                    || !manifest.transcriptRawFingerprint.equals(sha256(transcript.bytes()))
                    || !manifest.transcriptFingerprint.equals(
                    transcript.transcriptFingerprint())
                    || !manifest.receiptFingerprint.equals(
                    transcript.executionLeaseReceipt().fingerprint())
                    || !manifest.witnessFingerprint.equals(
                    transcript.executionLeaseTransitionWitness().fingerprint())
                    || !manifest.artifacts.equals(
                    artifactInventory(transactionDirectory(owner.transactionId), owner,
                            chain, transcript))) {
                throw new EvidenceInvalidException("evidence commit manifest invalid");
            }
        }

        private void requireFinalCommit(
                FinalCommit commitment,
                Owner owner,
                Transcript transcript,
                byte[] manifestBytes,
                CommitManifest manifest) throws IOException {
            FinalCommit expected = FinalCommit.create(owner,
                    output.getFileName().toString(), transcript, manifestBytes,
                    manifest.fingerprint);
            if (!expected.equals(commitment)) {
                throw new EvidenceInvalidException("evidence final commitment invalid");
            }
        }

        private List<ArtifactEntry> artifactInventory(
                Path transaction,
                Owner owner,
                AttemptChain chain,
                Transcript transcript) throws IOException {
            BeforeJournal pending = chain.pending;
            if (pending == null) {
                throw new EvidenceInvalidException("evidence committed attempt missing");
            }
            List<ArtifactEntry> artifacts = new ArrayList<>();
            artifacts.add(artifact(transaction, transaction.resolve(OWNER_FILE), "OWNER",
                    owner.fingerprint, 16 * 1024, Set.of(2L)));
            for (long generation = 1; generation <= pending.attemptGeneration; generation++) {
                Path before = beforePath(transaction, generation);
                byte[] beforeBytes = readStrict(before,
                        CapabilityStudioExecutionLeaseTranscript.MAXIMUM_BYTES);
                BeforeJournal journal = parseBefore(beforeBytes);
                artifacts.add(artifact(transaction, before,
                        "BEFORE_JOURNAL_G" + String.format("%020d", generation),
                        journal.fingerprint, beforeBytes));
                if (generation < pending.attemptGeneration) {
                    Path closure = closurePath(transaction, generation);
                    byte[] closureBytes = readStrict(closure, 32 * 1024);
                    AttemptClosure closureValue = parseAttemptClosure(closureBytes);
                    artifacts.add(artifact(transaction, closure,
                            "ATTEMPT_CLOSURE_G" + String.format("%020d", generation),
                            closureValue.fingerprint, closureBytes));
                }
            }
            Path committed = transaction.resolve(COMMITTED_FILE);
            byte[] committedBytes = readStrict(committed,
                    CapabilityStudioExecutionLeaseTranscript.MAXIMUM_BYTES,
                    Set.of(1L, 2L));
            Transcript committedTranscript = CapabilityStudioExecutionLeaseTranscript.verify(
                    committedBytes);
            if (!Arrays.equals(committedBytes, transcript.bytes())) {
                throw new EvidenceInvalidException("evidence retained transcript invalid");
            }
            artifacts.add(artifact(transaction, committed, "RETAINED_TRANSCRIPT",
                    committedTranscript.transcriptFingerprint(), committedBytes));
            artifacts.sort(Comparator.comparing(ArtifactEntry::relativePath));
            Set<String> paths = new HashSet<>();
            Set<String> roles = new HashSet<>();
            for (ArtifactEntry artifact : artifacts) {
                if (!paths.add(artifact.relativePath) || !roles.add(artifact.role)) {
                    throw new EvidenceInvalidException("evidence artifact inventory invalid");
                }
            }
            return List.copyOf(artifacts);
        }

        private ArtifactEntry artifact(
                Path transaction,
                Path path,
                String role,
                String canonicalFingerprint,
                int maximum,
                Set<Long> acceptedLinks) throws IOException {
            return artifact(transaction, path, role, canonicalFingerprint,
                    readStrict(path, maximum, acceptedLinks));
        }

        private ArtifactEntry artifact(
                Path transaction,
                Path path,
                String role,
                String canonicalFingerprint,
                byte[] bytes) throws IOException {
            Path relative = transaction.relativize(path.normalize());
            if (relative.getNameCount() != 1
                    || !path.normalize().getParent().equals(transaction)
                    || !relative.toString().matches("[A-Za-z0-9._-]+")) {
                throw new EvidenceInvalidException("evidence artifact path invalid");
            }
            return new ArtifactEntry(relative.toString(), role, bytes.length,
                    sha256(bytes), false, canonicalFingerprint);
        }

        private BundleInventory captureBundleInventory(Path parent, Path transaction)
                throws IOException {
            List<InventoryEntry> entries = new ArrayList<>();
            entries.add(inventoryEntry(".", parent));
            int parentCount = 0;
            try (var children = Files.newDirectoryStream(parent)) {
                for (Path child : children) {
                    if (++parentCount > 16) {
                        throw new EvidenceInvalidException(
                                "evidence publication closure invalid");
                    }
                    String name = child.getFileName().toString();
                    InventoryEntry entry = inventoryEntry("P:" + name, child);
                    entries.add(entry);
                    if (child.equals(transaction) && "DIRECTORY".equals(entry.type)) {
                        int wrapperCount = 0;
                        try (var wrapper = Files.newDirectoryStream(child)) {
                            for (Path artifact : wrapper) {
                                if (++wrapperCount > MAXIMUM_TRANSACTION_CHILDREN) {
                                    throw new EvidenceInvalidException(
                                            "evidence wrapper capacity invalid");
                                }
                                entries.add(inventoryEntry("W:"
                                        + artifact.getFileName(), artifact));
                            }
                        }
                    }
                }
            }
            entries.sort(Comparator.comparing(InventoryEntry::relativePath));
            return new BundleInventory(List.copyOf(entries));
        }

        private InventoryEntry inventoryEntry(String relativePath, Path path)
                throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String type = attributes.isSymbolicLink() ? "SYMLINK"
                    : attributes.isDirectory() ? "DIRECTORY"
                    : attributes.isRegularFile() ? "REGULAR" : "OTHER";
            if (attributes.isSymbolicLink() || (!attributes.isDirectory()
                    && !attributes.isRegularFile())) {
                return new InventoryEntry(relativePath, type, attributes.fileKey(), -1, -1,
                        -1, attributes.size(), attributes.lastModifiedTime(), null);
            }
            InventoryEntry before = inventoryMetadata(relativePath, type, path, attributes);
            String raw = null;
            if (attributes.isRegularFile()
                    && attributes.size() <= MAXIMUM_COMMIT_MANIFEST_BYTES) {
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length != attributes.size()) {
                    throw new EvidenceUnavailableException(
                            "evidence inventory changed");
                }
                raw = sha256(bytes);
            }
            BasicFileAttributes afterAttributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            InventoryEntry after = inventoryMetadata(relativePath, type, path,
                    afterAttributes);
            if (!before.withoutRaw().equals(after.withoutRaw())) {
                throw new EvidenceUnavailableException("evidence inventory changed");
            }
            return new InventoryEntry(relativePath, type, before.fileKey, before.links,
                    before.uid, before.mode, before.size, before.modifiedTime, raw);
        }

        private InventoryEntry inventoryMetadata(
                String relativePath,
                String expectedType,
                Path path,
                BasicFileAttributes attributes) throws IOException {
            String type = attributes.isSymbolicLink() ? "SYMLINK"
                    : attributes.isDirectory() ? "DIRECTORY"
                    : attributes.isRegularFile() ? "REGULAR" : "OTHER";
            if (!expectedType.equals(type)) {
                throw new EvidenceUnavailableException("evidence inventory changed");
            }
            if (attributes.fileKey() == null) {
                throw new EvidenceUnavailableException(
                        "evidence inventory metadata unavailable");
            }
            long links = ((Number) Files.getAttribute(path, "unix:nlink",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            long uid = ((Number) Files.getAttribute(path, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS)).longValue();
            int mode = ((Number) Files.getAttribute(path, "unix:mode",
                    LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
            return new InventoryEntry(relativePath, type, attributes.fileKey(), links, uid,
                    mode, attributes.size(), attributes.lastModifiedTime(), null);
        }

        private void requirePublicationClosure(
                BundleInventory inventory,
                Path parent,
                Path transaction,
                String transactionId) throws IOException {
            Set<String> expected = Set.of(
                    CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE,
                    CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE,
                    CapabilityStudioExecutionLeaseEvidencePublication
                            .PUBLICATION_DECLARATION_FILE,
                    ownerClaim(transactionId).getFileName().toString(),
                    transaction.getFileName().toString(), output.getFileName().toString());
            if (!inventory.parentNames().equals(expected)) {
                throw new EvidenceInvalidException("evidence publication closure invalid");
            }
            InventoryEntry root = inventory.require(".");
            if (!"DIRECTORY".equals(root.type) || root.mode != 0700) {
                throw new EvidenceInvalidException("evidence publication parent invalid");
            }
            requireInventoryFile(inventory, CapabilityStudioExecutionLeaseEvidencePublication
                    .OWNER_BOOTSTRAP_FILE, 0400, 1, root.uid);
            requireInventoryFile(inventory, CapabilityStudioExecutionLeaseEvidencePublication
                    .PUBLICATION_LOCK_FILE, 0600, 1, root.uid);
            requireInventoryFile(inventory, CapabilityStudioExecutionLeaseEvidencePublication
                    .PUBLICATION_DECLARATION_FILE, 0400, 1, root.uid);
            InventoryEntry wrapper = inventory.require("P:"
                    + transaction.getFileName());
            if (!"DIRECTORY".equals(wrapper.type) || wrapper.mode != 0700
                    || wrapper.uid != root.uid || wrapper.links < 1) {
                throw new EvidenceInvalidException("evidence wrapper metadata invalid");
            }
            requireInventoryFile(inventory, ownerClaim(transactionId).getFileName().toString(),
                    0400, 2, root.uid);
            requireInventoryFile(inventory, output.getFileName().toString(),
                    0400, 2, root.uid);
        }

        private void requireWrapperMetadata(
                BundleInventory inventory,
                Path transaction,
                Owner owner,
                CommitManifest manifest) throws IOException {
            long uid = inventory.require("P:" + transaction.getFileName()).uid;
            for (ArtifactEntry artifact : manifest.artifacts) {
                long links = switch (artifact.role) {
                    case "OWNER", "RETAINED_TRANSCRIPT" -> 2;
                    default -> 1;
                };
                InventoryEntry actual = requireInventoryFile(inventory,
                        "W:" + artifact.relativePath, 0400, links, uid);
                if (actual.size != artifact.byteSize
                        || !artifact.rawFingerprint.equals(actual.rawFingerprint)) {
                    throw new EvidenceInvalidException(
                            "evidence artifact material invalid");
                }
            }
            requireInventoryFile(inventory, "W:" + COMMIT_MANIFEST_FILE,
                    0400, 1, uid);
            requireInventoryFile(inventory, "W:" + FINAL_COMMIT_FILE,
                    0400, 1, uid);
            InventoryEntry ownerClaim = inventory.require("P:"
                    + ownerClaim(owner.transactionId).getFileName());
            InventoryEntry ownerEntry = inventory.require("W:" + OWNER_FILE);
            if (!java.util.Objects.equals(ownerClaim.fileKey, ownerEntry.fileKey)) {
                throw new EvidenceInvalidException("evidence owner identity invalid");
            }
        }

        private InventoryEntry requireInventoryFile(
                BundleInventory inventory,
                String name,
                int mode,
                long links,
                long uid) throws IOException {
            String key = name.startsWith("P:") || name.startsWith("W:")
                    ? name : "P:" + name;
            InventoryEntry entry = inventory.require(key);
            requireVerifierFileMetadata(entry.type, entry.fileKey != null, true,
                    entry.mode, entry.links, entry.uid, mode, links, uid);
            if (entry.rawFingerprint == null) {
                throw new EvidenceUnavailableException(
                        "evidence raw metadata unavailable");
            }
            return entry;
        }

        private AttemptChain readAttemptChain(Path transaction, Owner owner)
                throws IOException {
            requireTransactionClosure(transaction);
            String previous = null;
            BeforeJournal pending = null;
            long generation = 1;
            while (generation <= MAXIMUM_ATTEMPTS) {
                Path before = beforePath(transaction, generation);
                Path closure = closurePath(transaction, generation);
                recoverBeforePart(before, owner, generation, previous);
                recoverClosurePart(closure, generation, previous);
                boolean hasBefore = Files.exists(before, LinkOption.NOFOLLOW_LINKS);
                boolean hasClosure = Files.exists(closure, LinkOption.NOFOLLOW_LINKS);
                if (!hasBefore) {
                    if (hasClosure || hasLaterAttempt(transaction, generation)) {
                        throw new EvidenceInvalidException("evidence attempt chain gap");
                    }
                    return new AttemptChain(null, generation, previous);
                }
                BeforeJournal journal = parseBefore(readStrict(before,
                        CapabilityStudioExecutionLeaseTranscript.MAXIMUM_BYTES));
                requirePendingCoordinates(journal, owner, generation, previous);
                if (!hasClosure) {
                    if (hasLaterAttempt(transaction, generation)) {
                        throw new EvidenceInvalidException("evidence attempt chain fork");
                    }
                    pending = journal;
                    return new AttemptChain(pending, generation, previous);
                }
                AttemptClosure closed = parseAttemptClosure(readStrict(closure, 32 * 1024));
                if (closed.attemptGeneration != generation
                        || !closed.beforeJournalFingerprint.equals(journal.fingerprint)
                        || !java.util.Objects.equals(
                        closed.previousAttemptClosureFingerprint, previous)) {
                    throw new EvidenceInvalidException("evidence attempt closure invalid");
                }
                previous = closed.fingerprint;
                generation++;
            }
            throw new EvidenceUnavailableException("evidence attempt capacity unavailable");
        }

        private void recoverBeforePart(
                Path before, Owner owner, long generation, String previous) throws IOException {
            Path source = part(before);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                BeforeJournal candidate = parseBefore(readStrict(source,
                        CapabilityStudioExecutionLeaseTranscript.MAXIMUM_BYTES));
                requirePendingCoordinates(candidate, owner, generation, previous);
                installOwnedFile(source, before,
                        beforeBytes(candidate, candidate.fingerprint));
            }
        }

        private void recoverClosurePart(Path closure, long generation, String previous)
                throws IOException {
            Path source = part(closure);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                AttemptClosure candidate = parseAttemptClosure(readStrict(source, 32 * 1024));
                if (candidate.attemptGeneration != generation
                        || !java.util.Objects.equals(
                        candidate.previousAttemptClosureFingerprint, previous)) {
                    throw new EvidenceInvalidException("evidence attempt part conflict");
                }
                installOwnedFile(source, closure,
                        closureBytes(candidate, candidate.fingerprint));
            }
        }

        private boolean hasLaterAttempt(Path transaction, long generation) throws IOException {
            return hasLaterAttempt(childNames(transaction), generation);
        }

        private boolean hasLaterAttempt(Set<String> names, long generation) {
            for (String name : names) {
                long observed = attemptGeneration(name);
                if (observed > generation) {
                    return true;
                }
            }
            return false;
        }

        private Owner ensureOwner(EvidenceExecutionLeaseAttempt attempt) throws IOException {
            ExecutionLeaseRequest request = attempt.request();
            String transactionId = attempt.evidenceTransactionId();
            Path parent = requirePrivateParent(output);
            Path transaction = transactionDirectory(transactionId);
            Path claim = ownerClaim(transactionId);
            boolean transactionExists = Files.exists(transaction, LinkOption.NOFOLLOW_LINKS);
            boolean claimExists = Files.exists(claim, LinkOption.NOFOLLOW_LINKS);
            if (transactionExists && !claimExists) {
                throw new EvidenceInvalidException("unowned evidence transaction");
            }
            Owner owner;
            if (claimExists) {
                owner = parseOwner(readStrict(claim, 16 * 1024, Set.of(1L, 2L)),
                        transactionId, request.stageResultRawFingerprint(),
                        request.providerOuterFingerprint());
            } else {
                owner = Owner.create(transactionId, request.stageResultRawFingerprint(),
                        request.providerOuterFingerprint(), publicationDeclaration);
                publishOwned(claim, ownerBytes(owner, owner.fingerprint));
            }
            if (!transactionExists) {
                try {
                    Files.createDirectory(transaction,
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
                    forceDirectory(parent);
                } catch (FileAlreadyExistsException raced) {
                    throw new EvidenceInvalidException("evidence transaction conflict", raced);
                }
            }
            requirePrivateTransactionDirectory(transaction, parent);
            Path target = transaction.resolve(OWNER_FILE);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (transactionExists) {
                    throw new EvidenceInvalidException("unowned evidence transaction");
                }
                if (!childNames(transaction).isEmpty()) {
                    throw new EvidenceInvalidException("evidence transaction is not empty");
                }
                try {
                    Files.createLink(target, claim);
                } catch (FileAlreadyExistsException raced) {
                    throw new EvidenceInvalidException("evidence owner conflict", raced);
                }
                forceFile(target);
                forceDirectory(transaction);
                forceDirectory(parent);
            }
            Owner exact = requireOwner(transaction, transactionId,
                    request.stageResultRawFingerprint(), request.providerOuterFingerprint());
            requireTransactionClosure(transaction);
            return exact;
        }

        private Owner requireOwner(
                Path transaction,
                String transactionId,
                String stageRawFingerprint,
                String expectedOuterFingerprint) throws IOException {
            requirePrivateTransactionDirectory(transaction, output.getParent());
            Path claim = ownerClaim(transactionId);
            Path ownerFile = transaction.resolve(OWNER_FILE);
            if (!Files.exists(claim, LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(ownerFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new EvidenceInvalidException("evidence owner is missing");
            }
            StrictIdentity claimIdentity = strictIdentity(claim, Set.of(0400), Set.of(2L));
            StrictIdentity ownerIdentity = strictIdentity(ownerFile, Set.of(0400), Set.of(2L));
            if (!java.util.Objects.equals(claimIdentity.fileKey, ownerIdentity.fileKey)) {
                throw new EvidenceInvalidException("evidence owner identity conflict");
            }
            byte[] claimBytes = readStrict(claim, 16 * 1024, Set.of(2L));
            byte[] ownerBytes = readStrict(ownerFile, 16 * 1024, Set.of(2L));
            if (!Arrays.equals(claimBytes, ownerBytes)) {
                throw new EvidenceInvalidException("evidence owner bytes conflict");
            }
            return parseOwner(ownerBytes, transactionId, stageRawFingerprint,
                    expectedOuterFingerprint);
        }

        private Owner recoverPreparedOwner(
                Path transaction,
                String transactionId,
                String stageRawFingerprint,
                String expectedOuterFingerprint) throws IOException {
            requirePrivateTransactionDirectory(transaction, output.getParent());
            Path ownerFile = transaction.resolve(OWNER_FILE);
            if (Files.exists(ownerFile, LinkOption.NOFOLLOW_LINKS)) {
                return requireOwner(transaction, transactionId, stageRawFingerprint,
                        expectedOuterFingerprint);
            }
            Path claim = ownerClaim(transactionId);
            if (!Files.exists(claim, LinkOption.NOFOLLOW_LINKS)
                    || !childNames(transaction).isEmpty()) {
                throw new EvidenceInvalidException("unowned evidence transaction");
            }
            parseOwner(readStrict(claim, 16 * 1024, Set.of(1L)), transactionId,
                    stageRawFingerprint, expectedOuterFingerprint);
            try {
                Files.createLink(ownerFile, claim);
            } catch (FileAlreadyExistsException raced) {
                throw new EvidenceInvalidException("evidence owner conflict", raced);
            }
            forceFile(ownerFile);
            forceDirectory(transaction);
            forceDirectory(output.getParent());
            return requireOwner(transaction, transactionId, stageRawFingerprint,
                    expectedOuterFingerprint);
        }

        private Owner parseOwner(
                byte[] bytes,
                String transactionId,
                String stageRawFingerprint,
                String expectedOuterFingerprint) throws IOException {
            try {
                JsonNode parsed = JSON.readTree(bytes);
                if (!(parsed instanceof ObjectNode node)
                        || !fieldNames(node).equals(Set.of("messageVersion",
                        "evidenceTransactionId", "stageResultRawFingerprint",
                        "providerOuterFingerprint", "publicationFingerprint",
                        "transactionNonce",
                        "ownerFingerprint"))) {
                    throw new EvidenceInvalidException("evidence owner fields invalid");
                }
                Owner owner = new Owner(text(node, "evidenceTransactionId"),
                        text(node, "stageResultRawFingerprint"),
                        text(node, "providerOuterFingerprint"),
                        text(node, "publicationFingerprint"),
                        text(node, "transactionNonce"), text(node, "ownerFingerprint"));
                if (!OWNER_VERSION.equals(text(node, "messageVersion"))
                        || !owner.transactionId.equals(transactionId)
                        || !owner.stageRawFingerprint.equals(stageRawFingerprint)
                        || !owner.providerOuterFingerprint.equals(expectedOuterFingerprint)
                        || publicationDeclaration == null
                        || !owner.publicationFingerprint.equals(
                        publicationDeclaration.publicationFingerprint())
                        || !owner.transactionNonce.equals(
                        CapabilityStudioExecutionLeaseEvidencePublication.transactionNonce(
                                publicationDeclaration, transactionId))
                        || !owner.transactionNonce.matches("sha256:[0-9a-f]{64}")
                        || !owner.fingerprint.equals(ownerFingerprint(owner))
                        || !Arrays.equals(bytes, ownerBytes(owner, owner.fingerprint))) {
                    throw new EvidenceInvalidException("evidence owner invalid");
                }
                return owner;
            } catch (EvidenceInvalidException invalid) {
                throw invalid;
            } catch (IOException | RuntimeException invalid) {
                throw new EvidenceInvalidException("evidence owner invalid", invalid);
            }
        }

        private void requirePendingCoordinates(
                BeforeJournal journal,
                Owner owner,
                long generation,
                String previous) throws IOException {
            if (!journal.transactionId.equals(owner.transactionId)
                    || !journal.request.stageResultRawFingerprint().equals(
                    owner.stageRawFingerprint)
                    || !journal.request.providerOuterFingerprint().equals(
                    owner.providerOuterFingerprint)
                    || !journal.ownerFingerprint.equals(owner.fingerprint)
                    || journal.attemptGeneration != generation
                    || !java.util.Objects.equals(
                    journal.previousAttemptClosureFingerprint, previous)) {
                throw new EvidenceInvalidException("evidence journal coordinates invalid");
            }
        }

        private Transcript requireExactTranscript(
                Transcript transcript,
                String transactionId,
                String stageRawFingerprint,
                String expectedOuterFingerprint) throws IOException {
            if (!transactionId.equals(transcript.evidenceTransactionId())
                    || !stageRawFingerprint.equals(transcript.executionLeaseRequest()
                    .stageResultRawFingerprint())
                    || !expectedOuterFingerprint.equals(transcript.executionLeaseRequest()
                    .providerOuterFingerprint())) {
                throw new EvidenceInvalidException("evidence transcript coordinates invalid");
            }
            return transcript;
        }

        private void publishOwned(Path target, byte[] bytes) throws IOException {
            assertPublicationLease();
            Path source = part(target);
            prepareOwnedSource(source, bytes);
            assertPublicationLease();
            installOwnedFile(source, target, bytes);
            assertPublicationLease();
        }

        private void publishOrRequireExactTranscript(Path target, Transcript transcript)
                throws IOException {
            assertPublicationLease();
            Path source = part(target);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                Transcript candidate = readTranscript(source);
                if (!Arrays.equals(candidate.bytes(), transcript.bytes())) {
                    throw new EvidenceInvalidException("evidence transcript part conflict");
                }
                installOwnedFile(source, target, transcript.bytes());
                assertPublicationLease();
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Transcript existing = readTranscript(target);
                if (!Arrays.equals(existing.bytes(), transcript.bytes())) {
                    throw new EvidenceInvalidException("evidence committed transcript conflict");
                }
                return;
            }
            publishOwned(target, transcript.bytes());
        }

        private Transcript readTranscript(Path path) throws IOException {
            try {
                return CapabilityStudioExecutionLeaseTranscript.verify(
                        readStrict(path,
                                CapabilityStudioExecutionLeaseTranscript.MAXIMUM_BYTES,
                                Set.of(1L, 2L)));
            } catch (IllegalArgumentException invalid) {
                throw new EvidenceInvalidException("evidence transcript invalid", invalid);
            }
        }

        private Path transactionDirectory(String transactionId) {
            return output.getParent().resolve("." + output.getFileName() + "."
                    + transactionId.substring("sha256:".length()) + ".evidence-v3");
        }

        private Path ownerClaim(String transactionId) {
            return output.getParent().resolve("." + output.getFileName() + "."
                    + transactionId.substring("sha256:".length()) + ".owner-claim-v3.json");
        }

        private static Path beforePath(Path transaction, long generation) {
            return transaction.resolve("before-v2-g" + String.format("%020d", generation)
                    + ".json");
        }

        private static Path closurePath(Path transaction, long generation) {
            return transaction.resolve("attempt-closure-v1-g"
                    + String.format("%020d", generation) + ".json");
        }

        private record BundleInventory(List<InventoryEntry> entries) {
            private BundleInventory {
                entries = List.copyOf(entries);
            }

            private InventoryEntry require(String relativePath) throws IOException {
                return entries.stream()
                        .filter(entry -> entry.relativePath.equals(relativePath))
                        .findFirst()
                        .orElseThrow(() -> new EvidenceInvalidException(
                                "evidence inventory entry missing"));
            }

            private Set<String> parentNames() {
                Set<String> names = new HashSet<>();
                for (InventoryEntry entry : entries) {
                    if (entry.relativePath.startsWith("P:")) {
                        names.add(entry.relativePath.substring(2));
                    }
                }
                return Set.copyOf(names);
            }

            private Set<String> wrapperNames() {
                Set<String> names = new HashSet<>();
                for (InventoryEntry entry : entries) {
                    if (entry.relativePath.startsWith("W:")) {
                        names.add(entry.relativePath.substring(2));
                    }
                }
                return Set.copyOf(names);
            }
        }

        private record InventoryEntry(
                String relativePath,
                String type,
                Object fileKey,
                long links,
                long uid,
                int mode,
                long size,
                java.nio.file.attribute.FileTime modifiedTime,
                String rawFingerprint) {
            private InventoryEntry withoutRaw() {
                return new InventoryEntry(relativePath, type, fileKey, links, uid, mode,
                        size, modifiedTime, null);
            }
        }

        private record Result(EvidencePublicationStatus status, Transcript transcript) {
        }

        private record AttemptCoordinates(
                long generation, String previousAttemptClosureFingerprint) {
        }

        private record AttemptChain(
                BeforeJournal pending,
                long nextGeneration,
                String previousClosureFingerprint) {
        }

        private record Owner(
                String transactionId,
                String stageRawFingerprint,
                String providerOuterFingerprint,
                String publicationFingerprint,
                String transactionNonce,
                String fingerprint) {
            private static Owner create(
                    String transactionId,
                    String stageRawFingerprint,
                    String providerOuterFingerprint,
                    CapabilityStudioExecutionLeaseEvidencePublication.Declaration declaration) {
                Owner unhashed = new Owner(transactionId, stageRawFingerprint,
                        providerOuterFingerprint, declaration.publicationFingerprint(),
                        CapabilityStudioExecutionLeaseEvidencePublication.transactionNonce(
                                declaration, transactionId), null);
                return new Owner(transactionId, stageRawFingerprint,
                        providerOuterFingerprint, declaration.publicationFingerprint(),
                        unhashed.transactionNonce,
                        ownerFingerprint(unhashed));
            }
        }

        private record BeforeJournal(
                String transactionId,
                ExecutionLeaseRequest request,
                Observation before,
                Instant semanticVerificationTime,
                String ownerFingerprint,
                long attemptGeneration,
                String previousAttemptClosureFingerprint,
                String fingerprint) {
            private static BeforeJournal create(
                    String transactionId,
                    ExecutionLeaseRequest request,
                    Observation before,
                    Instant semanticVerificationTime,
                    String ownerFingerprint,
                    long attemptGeneration,
                    String previousAttemptClosureFingerprint) {
                BeforeJournal unhashed = new BeforeJournal(transactionId, request, before,
                        semanticVerificationTime, ownerFingerprint, attemptGeneration,
                        previousAttemptClosureFingerprint, null);
                return new BeforeJournal(transactionId, request, before,
                        semanticVerificationTime, ownerFingerprint, attemptGeneration,
                        previousAttemptClosureFingerprint,
                        sha256(beforeBytes(unhashed, null)));
            }
        }

        private record AttemptClosure(
                long attemptGeneration,
                String beforeJournalFingerprint,
                String previousAttemptClosureFingerprint,
                String fingerprint) {
            private static AttemptClosure create(BeforeJournal before) {
                AttemptClosure unhashed = new AttemptClosure(before.attemptGeneration,
                        before.fingerprint, before.previousAttemptClosureFingerprint, null);
                return new AttemptClosure(before.attemptGeneration, before.fingerprint,
                        before.previousAttemptClosureFingerprint,
                        sha256(closureBytes(unhashed, null)));
            }
        }

        private record CommitManifest(
                String ownerFingerprint,
                long attemptGeneration,
                String previousAttemptClosureFingerprint,
                String requestCommitIdentityFingerprint,
                String beforeRawFingerprint,
                String beforeJournalFingerprint,
                String transcriptRawFingerprint,
                String transcriptFingerprint,
                String receiptFingerprint,
                String witnessFingerprint,
                List<ArtifactEntry> artifacts,
                String fingerprint) {
            private CommitManifest {
                artifacts = List.copyOf(artifacts);
            }

            private static CommitManifest create(
                    Owner owner,
                    BeforeJournal before,
                    Transcript transcript,
                    EvidenceExecutionLeaseCommitResult result,
                    List<ArtifactEntry> artifacts) {
                CommitManifest unhashed = new CommitManifest(owner.fingerprint,
                        before.attemptGeneration, before.previousAttemptClosureFingerprint,
                        before.request.commitIdentityFingerprint(),
                        sha256(before.before.bytes()), before.fingerprint,
                        sha256(transcript.bytes()), transcript.transcriptFingerprint(),
                        result.receipt().fingerprint(),
                        result.transitionWitness().fingerprint(), artifacts, null);
                return new CommitManifest(unhashed.ownerFingerprint,
                        unhashed.attemptGeneration,
                        unhashed.previousAttemptClosureFingerprint,
                        unhashed.requestCommitIdentityFingerprint,
                        unhashed.beforeRawFingerprint,
                        unhashed.beforeJournalFingerprint,
                        unhashed.transcriptRawFingerprint,
                        unhashed.transcriptFingerprint, unhashed.receiptFingerprint,
                        unhashed.witnessFingerprint,
                        unhashed.artifacts,
                        sha256(commitManifestBytes(unhashed, null)));
            }
        }

        private record ArtifactEntry(
                String relativePath,
                String role,
                long byteSize,
                String rawFingerprint,
                boolean canonicalAbsent,
                String canonicalFingerprint) {
            private ArtifactEntry {
                if (relativePath == null || role == null || byteSize < 0
                        || rawFingerprint == null
                        || canonicalAbsent != (canonicalFingerprint == null)) {
                    throw new IllegalArgumentException("evidence artifact is invalid");
                }
            }
        }

        private record FinalCommit(
                String ownerFingerprint,
                String finalRelativePath,
                String finalRawFingerprint,
                String finalCanonicalFingerprint,
                String commitManifestRawFingerprint,
                String commitManifestFingerprint,
                String fingerprint) {
            private static FinalCommit create(
                    Owner owner,
                    String finalRelativePath,
                    Transcript transcript,
                    byte[] manifestBytes,
                    String manifestFingerprint) {
                FinalCommit unhashed = new FinalCommit(owner.fingerprint,
                        finalRelativePath, sha256(transcript.bytes()),
                        transcript.transcriptFingerprint(), sha256(manifestBytes),
                        manifestFingerprint, null);
                return new FinalCommit(unhashed.ownerFingerprint,
                        unhashed.finalRelativePath, unhashed.finalRawFingerprint,
                        unhashed.finalCanonicalFingerprint,
                        unhashed.commitManifestRawFingerprint,
                        unhashed.commitManifestFingerprint,
                        sha256(finalCommitBytes(unhashed, null)));
            }
        }
    }

    static void prepareOwnedSource(Path source, byte[] bytes) throws IOException {
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            StrictIdentity identity = strictIdentity(source, Set.of(0400), Set.of(1L));
            byte[] existing = Files.readAllBytes(source);
            if (Arrays.equals(existing, bytes)) {
                return;
            }
            throw new EvidenceInvalidException("evidence source conflict");
        }
        try (FileChannel channel = FileChannel.open(source,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            Files.setPosixFilePermissions(source,
                    PosixFilePermissions.fromString("r--------"));
            channel.force(true);
        }
        if (!Arrays.equals(bytes, readStrict(source, bytes.length))) {
            throw new IOException("evidence source unavailable");
        }
        forceDirectory(source.getParent());
    }

    static void installOwnedFile(Path source, Path target, byte[] bytes)
            throws IOException {
        StrictIdentity sourceIdentity = identityIfPresent(source,
                Set.of(0400), Set.of(1L, 2L));
        StrictIdentity targetIdentity = identityIfPresent(target,
                Set.of(0400), Set.of(1L, 2L));
        if (sourceIdentity == null && targetIdentity == null) {
            prepareOwnedSource(source, bytes);
            installOwnedFile(source, target, bytes);
            return;
        }
        if (sourceIdentity == null) {
            requireExact(target, bytes, 1L);
            forceFile(target);
            forceDirectory(target.getParent());
            return;
        }
        if (targetIdentity != null) {
            if (!java.util.Objects.equals(sourceIdentity.fileKey, targetIdentity.fileKey)) {
                throw new EvidenceInvalidException("evidence publication conflict");
            }
            requireExact(source, bytes, 2L);
            requireExact(target, bytes, 2L);
            forceFile(target);
            forceDirectory(target.getParent());
            Files.delete(source);
            forceDirectory(source.getParent());
            requireExact(target, bytes, 1L);
            return;
        }
        requireExact(source, bytes, 1L);
        try {
            Files.createLink(target, source);
        } catch (FileAlreadyExistsException raced) {
            installOwnedFile(source, target, bytes);
            return;
        }
        requireExact(source, bytes, 2L);
        requireExact(target, bytes, 2L);
        forceFile(target);
        forceDirectory(target.getParent());
        Files.delete(source);
        forceDirectory(source.getParent());
        requireExact(target, bytes, 1L);
    }

    private static void requireExact(Path path, byte[] bytes, long links) throws IOException {
        StrictIdentity before = strictIdentity(path, Set.of(0400), Set.of(links));
        byte[] actual = Files.readAllBytes(path);
        StrictIdentity after = strictIdentity(path, Set.of(0400), Set.of(links));
        if (!before.equals(after) || !Arrays.equals(bytes, actual)
                || actual.length != before.size) {
            throw new IOException("evidence file changed");
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    static Path part(Path target) {
        return target.resolveSibling("." + target.getFileName() + ".part");
    }

    private static byte[] ownerBytes(
            TranscriptPublication.Owner owner, String fingerprint) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", OWNER_VERSION);
        node.put("evidenceTransactionId", owner.transactionId());
        node.put("stageResultRawFingerprint", owner.stageRawFingerprint());
        node.put("providerOuterFingerprint", owner.providerOuterFingerprint());
        node.put("publicationFingerprint", owner.publicationFingerprint());
        node.put("transactionNonce", owner.transactionNonce());
        if (fingerprint == null) {
            node.putNull("ownerFingerprint");
        } else {
            node.put("ownerFingerprint", fingerprint);
        }
        return writeJson(node);
    }

    private static String ownerFingerprint(TranscriptPublication.Owner owner) {
        return sha256(ownerBytes(new TranscriptPublication.Owner(owner.transactionId(),
                owner.stageRawFingerprint(), owner.providerOuterFingerprint(),
                owner.publicationFingerprint(), owner.transactionNonce(), null), null));
    }

    private static byte[] beforeBytes(
            TranscriptPublication.BeforeJournal journal, String fingerprint) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", BEFORE_JOURNAL_VERSION);
        node.put("evidenceTransactionId", journal.transactionId());
        node.put("executionLeaseRequest", Base64.getEncoder().encodeToString(
                CapabilityStudioExecutionLeaseTranscript.requestBytes(journal.request())));
        node.put("beforeStateObservation", Base64.getEncoder().encodeToString(
                journal.before().bytes()));
        node.put("semanticVerificationTime",
                journal.semanticVerificationTime().toString());
        node.put("ownerFingerprint", journal.ownerFingerprint());
        node.put("attemptGeneration", journal.attemptGeneration());
        if (journal.previousAttemptClosureFingerprint() == null) {
            node.putNull("previousAttemptClosureFingerprint");
        } else {
            node.put("previousAttemptClosureFingerprint",
                    journal.previousAttemptClosureFingerprint());
        }
        if (fingerprint == null) {
            node.putNull("journalFingerprint");
        } else {
            node.put("journalFingerprint", fingerprint);
        }
        return writeJson(node);
    }

    private static TranscriptPublication.BeforeJournal parseBefore(byte[] bytes)
            throws IOException {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (!(parsed instanceof ObjectNode node)
                    || !fieldNames(node).equals(Set.of("messageVersion",
                    "evidenceTransactionId", "executionLeaseRequest",
                    "beforeStateObservation", "semanticVerificationTime",
                    "ownerFingerprint", "attemptGeneration",
                    "previousAttemptClosureFingerprint", "journalFingerprint"))
                    || !BEFORE_JOURNAL_VERSION.equals(text(node, "messageVersion"))) {
                throw new IOException("evidence journal unavailable");
            }
            ExecutionLeaseRequest request = CapabilityStudioExecutionLeaseTranscript
                    .verifyRequestBytes(Base64.getDecoder().decode(
                            text(node, "executionLeaseRequest")));
            Observation before = CapabilityStudioDeploymentStateObservation.verify(
                    Base64.getDecoder().decode(text(node, "beforeStateObservation")));
            TranscriptPublication.BeforeJournal journal =
                    new TranscriptPublication.BeforeJournal(
                            text(node, "evidenceTransactionId"), request, before,
                            Instant.parse(text(node, "semanticVerificationTime")),
                            text(node, "ownerFingerprint"),
                            positiveLong(node, "attemptGeneration"),
                            nullableFingerprint(node, "previousAttemptClosureFingerprint"),
                            text(node, "journalFingerprint"));
            if (!journal.fingerprint().equals(sha256(beforeBytes(journal, null)))
                    || !Arrays.equals(bytes,
                    beforeBytes(journal, journal.fingerprint()))) {
                throw new EvidenceInvalidException("evidence journal invalid");
            }
            return journal;
        } catch (EvidenceInvalidException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw new EvidenceInvalidException("evidence journal invalid", invalid);
        }
    }

    private static byte[] closureBytes(
            TranscriptPublication.AttemptClosure closure, String fingerprint) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", ATTEMPT_CLOSURE_VERSION);
        node.put("attemptGeneration", closure.attemptGeneration());
        node.put("beforeJournalFingerprint", closure.beforeJournalFingerprint());
        if (closure.previousAttemptClosureFingerprint() == null) {
            node.putNull("previousAttemptClosureFingerprint");
        } else {
            node.put("previousAttemptClosureFingerprint",
                    closure.previousAttemptClosureFingerprint());
        }
        node.put("closureStatus", "ABSENT");
        if (fingerprint == null) {
            node.putNull("attemptClosureFingerprint");
        } else {
            node.put("attemptClosureFingerprint", fingerprint);
        }
        return writeJson(node);
    }

    private static TranscriptPublication.AttemptClosure parseAttemptClosure(byte[] bytes)
            throws IOException {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (!(parsed instanceof ObjectNode node)
                    || !fieldNames(node).equals(Set.of("messageVersion",
                    "attemptGeneration", "beforeJournalFingerprint",
                    "previousAttemptClosureFingerprint", "closureStatus",
                    "attemptClosureFingerprint"))
                    || !ATTEMPT_CLOSURE_VERSION.equals(text(node, "messageVersion"))
                    || !"ABSENT".equals(text(node, "closureStatus"))) {
                throw new EvidenceInvalidException("evidence attempt closure invalid");
            }
            var closure = new TranscriptPublication.AttemptClosure(
                    positiveLong(node, "attemptGeneration"),
                    text(node, "beforeJournalFingerprint"),
                    nullableFingerprint(node, "previousAttemptClosureFingerprint"),
                    text(node, "attemptClosureFingerprint"));
            if (!closure.fingerprint().equals(sha256(closureBytes(closure, null)))
                    || !Arrays.equals(bytes, closureBytes(closure, closure.fingerprint()))) {
                throw new EvidenceInvalidException("evidence attempt closure invalid");
            }
            return closure;
        } catch (EvidenceInvalidException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw new EvidenceInvalidException("evidence attempt closure invalid", invalid);
        }
    }

    private static byte[] commitManifestBytes(
            TranscriptPublication.CommitManifest manifest, String fingerprint) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", COMMIT_MANIFEST_VERSION);
        node.put("ownerFingerprint", manifest.ownerFingerprint());
        node.put("attemptGeneration", manifest.attemptGeneration());
        if (manifest.previousAttemptClosureFingerprint() == null) {
            node.putNull("previousAttemptClosureFingerprint");
        } else {
            node.put("previousAttemptClosureFingerprint",
                    manifest.previousAttemptClosureFingerprint());
        }
        node.put("requestCommitIdentityFingerprint",
                manifest.requestCommitIdentityFingerprint());
        node.put("beforeRawFingerprint", manifest.beforeRawFingerprint());
        node.put("beforeJournalFingerprint", manifest.beforeJournalFingerprint());
        node.put("transcriptRawFingerprint", manifest.transcriptRawFingerprint());
        node.put("transcriptFingerprint", manifest.transcriptFingerprint());
        node.put("receiptFingerprint", manifest.receiptFingerprint());
        node.put("witnessFingerprint", manifest.witnessFingerprint());
        var artifacts = node.putArray("artifacts");
        for (TranscriptPublication.ArtifactEntry artifact : manifest.artifacts()) {
            ObjectNode entry = artifacts.addObject();
            entry.put("relativePath", artifact.relativePath());
            entry.put("role", artifact.role());
            entry.put("byteSize", artifact.byteSize());
            entry.put("rawFingerprint", artifact.rawFingerprint());
            entry.put("canonicalAbsent", artifact.canonicalAbsent());
            if (artifact.canonicalAbsent()) {
                entry.putNull("canonicalFingerprint");
            } else {
                entry.put("canonicalFingerprint", artifact.canonicalFingerprint());
            }
        }
        if (fingerprint == null) {
            node.putNull("commitManifestFingerprint");
        } else {
            node.put("commitManifestFingerprint", fingerprint);
        }
        return writeJson(node);
    }

    private static TranscriptPublication.CommitManifest parseCommitManifest(byte[] bytes)
            throws IOException {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (!(parsed instanceof ObjectNode node)
                    || !CapabilityStudioSchemaSupport.validate(parsed,
                    CapabilityStudioSchemaSupport
                            .EXECUTION_LEASE_EVIDENCE_COMMIT_MANIFEST_V1_RESOURCE)
                    .isEmpty()
                    || !fieldNames(node).equals(Set.of("messageVersion", "ownerFingerprint",
                    "attemptGeneration", "previousAttemptClosureFingerprint",
                    "requestCommitIdentityFingerprint", "beforeRawFingerprint",
                    "beforeJournalFingerprint", "transcriptRawFingerprint",
                    "transcriptFingerprint", "receiptFingerprint", "witnessFingerprint",
                    "artifacts", "commitManifestFingerprint"))
                    || !COMMIT_MANIFEST_VERSION.equals(text(node, "messageVersion"))) {
                throw new EvidenceInvalidException("evidence commit manifest invalid");
            }
            List<TranscriptPublication.ArtifactEntry> artifacts = new ArrayList<>();
            Set<String> paths = new HashSet<>();
            Set<String> roles = new HashSet<>();
            for (JsonNode value : node.withArray("artifacts")) {
                if (!(value instanceof ObjectNode entry)
                        || !fieldNames(entry).equals(Set.of("relativePath", "role",
                        "byteSize", "rawFingerprint", "canonicalAbsent",
                        "canonicalFingerprint"))) {
                    throw new EvidenceInvalidException("evidence artifact manifest invalid");
                }
                String path = text(entry, "relativePath");
                String role = text(entry, "role");
                JsonNode sizeNode = entry.get("byteSize");
                JsonNode absentNode = entry.get("canonicalAbsent");
                if (sizeNode == null || !sizeNode.canConvertToLong()
                        || sizeNode.longValue() < 0 || absentNode == null
                        || !absentNode.isBoolean() || !paths.add(path) || !roles.add(role)) {
                    throw new EvidenceInvalidException("evidence artifact manifest invalid");
                }
                boolean absent = absentNode.booleanValue();
                String canonical = nullableFingerprint(entry, "canonicalFingerprint");
                artifacts.add(new TranscriptPublication.ArtifactEntry(path, role,
                        sizeNode.longValue(), text(entry, "rawFingerprint"), absent,
                        canonical));
            }
            List<TranscriptPublication.ArtifactEntry> sorted = new ArrayList<>(artifacts);
            sorted.sort(Comparator.comparing(
                    TranscriptPublication.ArtifactEntry::relativePath));
            if (!sorted.equals(artifacts)) {
                throw new EvidenceInvalidException("evidence artifact order invalid");
            }
            var manifest = new TranscriptPublication.CommitManifest(
                    text(node, "ownerFingerprint"), positiveLong(node, "attemptGeneration"),
                    nullableFingerprint(node, "previousAttemptClosureFingerprint"),
                    text(node, "requestCommitIdentityFingerprint"),
                    text(node, "beforeRawFingerprint"),
                    text(node, "beforeJournalFingerprint"),
                    text(node, "transcriptRawFingerprint"),
                    text(node, "transcriptFingerprint"), text(node, "receiptFingerprint"),
                    text(node, "witnessFingerprint"),
                    artifacts,
                    text(node, "commitManifestFingerprint"));
            if (!manifest.fingerprint().equals(
                    sha256(commitManifestBytes(manifest, null)))
                    || !Arrays.equals(bytes,
                    commitManifestBytes(manifest, manifest.fingerprint()))) {
                throw new EvidenceInvalidException("evidence commit manifest invalid");
            }
            return manifest;
        } catch (EvidenceInvalidException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw new EvidenceInvalidException("evidence commit manifest invalid", invalid);
        }
    }

    private static byte[] finalCommitBytes(
            TranscriptPublication.FinalCommit commitment, String fingerprint) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", BUNDLE_COMMITMENT_VERSION);
        node.put("ownerFingerprint", commitment.ownerFingerprint());
        node.put("finalRelativePath", commitment.finalRelativePath());
        node.put("finalRawFingerprint", commitment.finalRawFingerprint());
        node.put("finalCanonicalFingerprint", commitment.finalCanonicalFingerprint());
        node.put("commitManifestRawFingerprint",
                commitment.commitManifestRawFingerprint());
        node.put("commitManifestFingerprint", commitment.commitManifestFingerprint());
        if (fingerprint == null) {
            node.putNull("bundleCommitmentFingerprint");
        } else {
            node.put("bundleCommitmentFingerprint", fingerprint);
        }
        return writeJson(node);
    }

    private static TranscriptPublication.FinalCommit parseFinalCommit(byte[] bytes)
            throws IOException {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (!(parsed instanceof ObjectNode node)
                    || !fieldNames(node).equals(Set.of("messageVersion", "ownerFingerprint",
                    "finalRelativePath", "finalRawFingerprint",
                    "finalCanonicalFingerprint", "commitManifestRawFingerprint",
                    "commitManifestFingerprint", "bundleCommitmentFingerprint"))
                    || !BUNDLE_COMMITMENT_VERSION.equals(text(node, "messageVersion"))) {
                throw new EvidenceInvalidException("evidence final commitment invalid");
            }
            var commitment = new TranscriptPublication.FinalCommit(
                    text(node, "ownerFingerprint"), text(node, "finalRelativePath"),
                    text(node, "finalRawFingerprint"),
                    text(node, "finalCanonicalFingerprint"),
                    text(node, "commitManifestRawFingerprint"),
                    text(node, "commitManifestFingerprint"),
                    text(node, "bundleCommitmentFingerprint"));
            if (!commitment.finalRelativePath().matches("[A-Za-z0-9._-]+")
                    || !commitment.fingerprint().equals(
                    sha256(finalCommitBytes(commitment, null)))
                    || !Arrays.equals(bytes,
                    finalCommitBytes(commitment, commitment.fingerprint()))) {
                throw new EvidenceInvalidException("evidence final commitment invalid");
            }
            return commitment;
        } catch (EvidenceInvalidException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw new EvidenceInvalidException("evidence final commitment invalid", invalid);
        }
    }

    private static long positiveLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1) {
            throw new IllegalArgumentException("evidence numeric field invalid");
        }
        return value.longValue();
    }

    private static String nullableFingerprint(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || !value.textValue().matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evidence fingerprint field invalid");
        }
        return value.textValue();
    }

    private static byte[] writeJson(ObjectNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException impossible) {
            throw new IllegalStateException("JSON unavailable");
        }
    }

    private static Set<String> fieldNames(ObjectNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String text(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("evidence field invalid");
        }
        return value.textValue();
    }

    private static void requirePrivateTransactionDirectory(Path directory, Path parent)
            throws IOException {
        StrictIdentity parentIdentity = directoryIdentity(parent, 0700);
        StrictIdentity directoryIdentity = directoryIdentity(directory, 0700);
        requireOwnedUidForTesting(directoryIdentity.uid, parentIdentity.uid);
    }

    private static StrictIdentity directoryIdentity(Path path, int expectedMode)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long uid = ((Number) Files.getAttribute(path, "unix:uid",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        int mode = ((Number) Files.getAttribute(path, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new EvidenceInvalidException("evidence directory type is invalid");
        }
        if (attributes.fileKey() == null) {
            throw new EvidenceUnavailableException("evidence directory unavailable");
        }
        if (mode != expectedMode) {
            throw new EvidenceInvalidException("evidence directory mode is invalid");
        }
        return new StrictIdentity(attributes.fileKey(), 0, uid, mode,
                attributes.size(), attributes.lastModifiedTime());
    }

    private static void requireTransactionClosure(Path transaction) throws IOException {
        Set<String> fixed = Set.of(OWNER_FILE, COMMITTED_FILE, COMMIT_MANIFEST_FILE,
                FINAL_COMMIT_FILE,
                part(transaction.resolve(COMMITTED_FILE)).getFileName().toString(),
                part(transaction.resolve(COMMIT_MANIFEST_FILE)).getFileName().toString(),
                part(transaction.resolve(FINAL_COMMIT_FILE)).getFileName().toString());
        for (String name : childNames(transaction)) {
            if (!fixed.contains(name) && attemptGeneration(name) < 1) {
                throw new EvidenceInvalidException("evidence transaction closure invalid");
            }
        }
    }

    private static void requireTransactionClosureReadOnly(Path transaction)
            throws IOException {
        Set<String> fixed = Set.of(OWNER_FILE, COMMITTED_FILE, COMMIT_MANIFEST_FILE,
                FINAL_COMMIT_FILE);
        for (String name : childNames(transaction)) {
            if (name.startsWith(".") && name.endsWith(".part")) {
                throw new EvidenceInvalidException("evidence transaction part remains");
            }
            if (!fixed.contains(name) && attemptGeneration(name) < 1) {
                throw new EvidenceInvalidException("evidence transaction closure invalid");
            }
        }
    }

    private static long attemptGeneration(String name) {
        String normalized = name;
        if (normalized.startsWith(".") && normalized.endsWith(".part")) {
            normalized = normalized.substring(1, normalized.length() - ".part".length());
        }
        String beforePrefix = "before-v2-g";
        String closurePrefix = "attempt-closure-v1-g";
        String prefix = normalized.startsWith(beforePrefix) ? beforePrefix
                : normalized.startsWith(closurePrefix) ? closurePrefix : null;
        if (prefix == null || !normalized.endsWith(".json")) {
            return -1;
        }
        String digits = normalized.substring(prefix.length(),
                normalized.length() - ".json".length());
        if (!digits.matches("[0-9]{20}")) {
            return -1;
        }
        try {
            long value = Long.parseLong(digits);
            return value > 0 ? value : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static Set<String> childNames(Path directory) throws IOException {
        Set<String> names = new HashSet<>();
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (names.size() >= MAXIMUM_TRANSACTION_CHILDREN) {
                    throw new EvidenceUnavailableException(
                            "evidence transaction capacity unavailable");
                }
                if (!names.add(child.getFileName().toString())) {
                    throw new IOException("evidence transaction closure unavailable");
                }
                BasicFileAttributes attributes = Files.readAttributes(child,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw new EvidenceInvalidException(
                            "evidence transaction closure invalid");
                }
            }
        }
        return names;
    }

    static void requireBoundedTransactionInventoryForTesting(Path directory)
            throws IOException {
        childNames(directory);
    }

    private static Path requirePrivateParent(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent == null || !output.equals(parent.resolve(output.getFileName()).normalize())) {
            throw new IOException("evidence output unavailable");
        }
        BasicFileAttributes attributes = Files.readAttributes(parent,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        int mode = ((Number) Files.getAttribute(parent, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new EvidenceInvalidException("evidence output type is invalid");
        }
        if (attributes.fileKey() == null || mode != 0700) {
            throw new EvidenceUnavailableException("evidence output unavailable");
        }
        return parent;
    }

    private static ParentIdentity parentIdentity(Path parent) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(parent,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long uid = ((Number) Files.getAttribute(parent, "unix:uid",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        int mode = ((Number) Files.getAttribute(parent, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new EvidenceInvalidException("evidence parent type is invalid");
        }
        if (attributes.fileKey() == null || mode != 0700) {
            throw new EvidenceUnavailableException("evidence parent unavailable");
        }
        return new ParentIdentity(attributes.fileKey(), uid, mode);
    }

    private static byte[] readStrict(Path path, int maximum) throws IOException {
        return readStrict(path, maximum, Set.of(1L));
    }

    private static byte[] readStrict(
            Path path, int maximum, Set<Long> acceptedLinks) throws IOException {
        StrictIdentity before = strictIdentity(path, Set.of(0400), acceptedLinks);
        if (before.size < 1 || before.size > maximum) {
            throw new EvidenceInvalidException("evidence file size is invalid");
        }
        byte[] bytes = Files.readAllBytes(path);
        StrictIdentity after = strictIdentity(path, Set.of(0400), acceptedLinks);
        if (!before.equals(after) || bytes.length != before.size) {
            throw new IOException("evidence file changed");
        }
        return bytes;
    }

    private static void publishRetained(Path source, Path target, byte[] bytes)
            throws IOException {
        StrictIdentity sourceIdentity = strictIdentity(
                source, Set.of(0400), Set.of(1L, 2L));
        StrictIdentity targetIdentity = identityIfPresent(
                target, Set.of(0400), Set.of(1L, 2L));
        if (targetIdentity == null) {
            if (sourceIdentity.links != 1) {
                throw new EvidenceInvalidException("evidence retained source conflict");
            }
            try {
                Files.createLink(target, source);
            } catch (FileAlreadyExistsException raced) {
                publishRetained(source, target, bytes);
                return;
            }
        }
        if (targetIdentity != null && targetIdentity.links != 2) {
            throw new EvidenceInvalidException("evidence output is not transaction-owned");
        }
        StrictIdentity linkedSource = strictIdentity(source, Set.of(0400), Set.of(2L));
        StrictIdentity linkedTarget = strictIdentity(target, Set.of(0400), Set.of(2L));
        if (!java.util.Objects.equals(linkedSource.fileKey, linkedTarget.fileKey)) {
            throw new EvidenceInvalidException("evidence retained output conflict");
        }
        requireExact(source, bytes, 2L);
        requireExact(target, bytes, 2L);
        forceFile(target);
        forceDirectory(target.getParent());
        forceDirectory(source.getParent());
    }

    private static StrictIdentity identityIfPresent(
            Path path, Set<Integer> modes, Set<Long> links) throws IOException {
        try {
            return strictIdentity(path, modes, links);
        } catch (java.nio.file.NoSuchFileException missing) {
            return null;
        }
    }

    private static StrictIdentity strictIdentity(
            Path path, Set<Integer> modes, Set<Long> acceptedLinks) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long links = ((Number) Files.getAttribute(path, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        int mode = ((Number) Files.getAttribute(path, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777;
        long uid = ((Number) Files.getAttribute(path, "unix:uid",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        long parentUid = ((Number) Files.getAttribute(path.getParent(), "unix:uid",
                LinkOption.NOFOLLOW_LINKS)).longValue();
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new EvidenceInvalidException("evidence file type is invalid");
        }
        if (attributes.fileKey() == null) {
            throw new EvidenceUnavailableException("evidence file unavailable");
        }
        if (!modes.contains(mode)) {
            throw new EvidenceInvalidException("evidence file mode is invalid");
        }
        if (!acceptedLinks.contains(links)) {
            throw new EvidenceInvalidException("evidence file identity is invalid");
        }
        requireOwnedUidForTesting(uid, parentUid);
        return new StrictIdentity(attributes.fileKey(), links, uid, mode,
                attributes.size(), attributes.lastModifiedTime());
    }

    static void requireOwnedUidForTesting(long observedUid, long parentUid)
            throws EvidenceInvalidException {
        if (observedUid != parentUid) {
            throw new EvidenceInvalidException("evidence file identity is invalid");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("evidence durability unavailable", unsupported);
        }
    }

    static Transcript verifyPublishedEvidence(
            Path output,
            String stageResultRawFingerprint,
            String providerOuterFingerprint) {
        return verifyPublishedEvidence(output, stageResultRawFingerprint,
                providerOuterFingerprint, null);
    }

    static Transcript verifyPublishedEvidence(
            Path output,
            String stageResultRawFingerprint,
            String providerOuterFingerprint,
            String expectedPublicationFingerprint) {
        try {
            if (output == null || !output.isAbsolute() || !output.equals(output.normalize())
                    || stageResultRawFingerprint == null
                    || !stageResultRawFingerprint.matches("sha256:[0-9a-f]{64}")
                    || providerOuterFingerprint == null
                    || !providerOuterFingerprint.matches("sha256:[0-9a-f]{64}")
                    || expectedPublicationFingerprint == null
                    || !expectedPublicationFingerprint.matches("sha256:[0-9a-f]{64}")) {
                throw new EvidenceInvalidException("evidence verification input invalid");
            }
            return new TranscriptPublication(output,
                    expectedPublicationFingerprint).verifyCommittedReadOnly(
                    stageResultRawFingerprint, providerOuterFingerprint);
        } catch (EvidenceInvalidException invalid) {
            throw new CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                    .VerificationException(EvidenceFailureKind.INVALID);
        } catch (EvidenceUnavailableException | java.nio.file.NoSuchFileException unavailable) {
            throw new CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                    .VerificationException(EvidenceFailureKind.UNAVAILABLE);
        } catch (IOException unavailable) {
            throw new CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                    .VerificationException(EvidenceFailureKind.UNAVAILABLE);
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            throw new CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                    .VerificationException(EvidenceFailureKind.UNAVAILABLE);
        } catch (RuntimeException invalid) {
            if (invalid instanceof CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                    .VerificationException verification) {
                throw verification;
            }
            throw new CapabilityStudioExecutionLeaseEvidenceBundleVerifier
                    .VerificationException(EvidenceFailureKind.INVALID);
        }
    }

    static String sha256ForEvidence(byte[] bytes) {
        return sha256(bytes);
    }

    static void verifyCommitManifestWireForTesting(byte[] bytes) throws IOException {
        parseCommitManifest(bytes);
    }

    static void requireVerifierMetadataForTesting(
            String type,
            boolean fileKeyAvailable,
            boolean metadataAvailable,
            int mode,
            long links,
            long uid,
            int expectedMode,
            long expectedLinks,
            long expectedUid) throws IOException {
        requireVerifierFileMetadata(type, fileKeyAvailable, metadataAvailable, mode, links,
                uid, expectedMode, expectedLinks, expectedUid);
    }

    private static void requireVerifierFileMetadata(
            String type,
            boolean fileKeyAvailable,
            boolean metadataAvailable,
            int mode,
            long links,
            long uid,
            int expectedMode,
            long expectedLinks,
            long expectedUid) throws IOException {
        if (!"REGULAR".equals(type)) {
            throw new EvidenceInvalidException("evidence file type is invalid");
        }
        if (!fileKeyAvailable || !metadataAvailable) {
            throw new EvidenceUnavailableException("evidence metadata unavailable");
        }
        if (mode != expectedMode || links != expectedLinks || uid != expectedUid) {
            throw new EvidenceInvalidException("evidence file metadata invalid");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static Path absolute(String value) {
        Path path = Path.of(value);
        Path normalized = path.toAbsolutePath().normalize();
        if (!path.isAbsolute() || !path.equals(normalized)) {
            throw new IllegalArgumentException("path is invalid");
        }
        return path;
    }

    private static String exactSingleLine(byte[] bytes) {
        String line = new String(bytes, StandardCharsets.UTF_8);
        if (!line.endsWith("\n") || line.indexOf('\n') != line.length() - 1
                || line.indexOf('\0') >= 0) {
            return "INVALID errorCode="
                    + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI.INTERNAL";
        }
        return line.substring(0, line.length() - 1);
    }

    private static boolean line(PrintStream output, String value) {
        try {
            output.print(value);
            output.print('\n');
            output.flush();
            return !output.checkError();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private record ParentIdentity(Object fileKey, long uid, int mode) {
    }

    private record AncestorIdentity(Path path, Object fileKey, long ownerUid, int mode) {
    }

    private record AncestorChain(List<AncestorIdentity> identities, long ownerUid) {
    }

    private record StrictIdentity(
            Object fileKey,
            long links,
            long uid,
            int mode,
            long size,
            java.nio.file.attribute.FileTime modifiedTime) {
    }

}
