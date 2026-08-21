package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioDeploymentStateObservation;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioExecutionLeaseEvidencePublication;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.DeploymentUnavailableException;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentStateObservationTest {
    private static final List<String> PACKAGED_CRASH_POINTS = List.of(
            "PRE_OWNER", "OWNER_SOURCE_FORCED", "WRAPPER_DURABLE", "OWNER_DURABLE",
            "BEFORE_SOURCE_FORCED", "BEFORE_DURABLE", "PRE_LEASE",
            "STATE_BEFORE_CHECKPOINT",
            "CHECKPOINT_DURABLE_BEFORE_TRANSITION_EVIDENCE",
            "COMMITTED_SOURCE_FORCED", "COMMITTED_DURABLE",
            "MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", "FINAL_COMMIT_SOURCE_FORCED",
            "FINAL_COMMIT_INSTALLED", "FINAL_COMMIT_DURABLE", "FINAL_INSTALLED",
            "FINAL_BEFORE_STDOUT");

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearProperties() {
        System.clearProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY);
        System.clearProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY);
        System.clearProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY);
    }

    @Test
    void existingOnlyObservationCrossChecksPersistedWitnessAndExactRecovery() throws Exception {
        Context context = context("positive");
        String transaction = fingerprint('8');
        InMemoryJournal journal = new InMemoryJournal();
        Closure closureBefore = closure(context.fixture.stateRoot());
        var committedTransaction = commit(context, transaction, journal);
        var committed = committedTransaction.leaseResult();
        var before = committedTransaction.beforeObservation();
        var after = committedTransaction.afterObservation();
        var recovered = commit(context, transaction, journal).leaseResult();

        assertThat(committed.status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        assertThat(recovered.status()).isEqualTo(ExecutionLeaseCommitStatus.RECOVERED);
        assertThat(recovered.receipt()).isEqualTo(committed.receipt());
        assertThat(recovered.transitionWitness()).isEqualTo(committed.transitionWitness());
        assertThat(before.generation()).isZero();
        assertThat(after.generation()).isEqualTo(1);
        assertThat(after.fencingSequence()).isEqualTo(1);
        assertThat(after.leaseCount()).isEqualTo(1);
        assertThat(committed.transitionWitness().preStateFingerprint())
                .isEqualTo(before.stateFingerprint());
        assertThat(committed.transitionWitness().postStateFingerprint())
                .isEqualTo(after.stateFingerprint());
        assertThat(closureBefore).isNotEqualTo(closure(context.fixture.stateRoot()));
    }

    @Test
    void exactWitnessRecoversAcrossIndependentProviderJvm() throws Exception {
        Context context = context("forked-recovery");
        var transaction = commit(context, fingerprint('8'), new InMemoryJournal());
        var committed = transaction.leaseResult();
        assertThat(runRecoveryWorker(context, "found")).isEqualTo("RECOVERED "
                + committed.receipt().fingerprint() + " "
                + committed.transitionWitness().fingerprint() + " "
                + transaction.afterObservation().observationFingerprint() + "\n");
        assertThat(context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.AFTER, fingerprint('8'))
                .leaseCount()).isEqualTo(1);
    }

    @Test
    void committedWitnessSurvivesRealChildJvmHaltBeforeEvidenceJournalReturns()
            throws Exception {
        Context context = context("forked-commit-halt");

        runCommitCrashWorker(context);

        String recovered = runRecoveryWorker(context, "after-commit-halt");
        assertThat(recovered).startsWith("RECOVERED sha256:");
        assertThat(context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.AFTER, fingerprint('8'))
                .leaseCount()).isEqualTo(1);
    }

    @Test
    void freshJvmRecoveryOnlyLeavesStaleCheckpointAndMissingStateUnchanged()
            throws Exception {
        Context stale = context("forked-stale");
        Path checkpoint = stale.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE);
        byte[] genesisCheckpoint = Files.readAllBytes(checkpoint);
        commit(stale, fingerprint('8'), new InMemoryJournal());
        Files.write(checkpoint, genesisCheckpoint);
        Closure staleClosure = closure(stale.fixture.stateRoot());

        assertThat(runRecoveryWorker(stale, "stale")).isEqualTo("UNAVAILABLE\n");
        assertThat(closure(stale.fixture.stateRoot())).isEqualTo(staleClosure);

        Context missing = context("forked-missing");
        Path state = missing.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        Files.delete(state);
        Closure missingClosure = closure(missing.fixture.stateRoot());
        assertThat(runRecoveryWorker(missing, "missing")).isEqualTo("UNAVAILABLE\n");
        assertThat(closure(missing.fixture.stateRoot())).isEqualTo(missingClosure);
        assertThat(state).doesNotExist();

        Context malformed = context("forked-malformed");
        Path malformedCheckpoint = malformed.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE);
        Files.writeString(malformedCheckpoint, "{\"corrupt\":true}");
        Closure malformedClosure = closure(malformed.fixture.stateRoot());
        assertThat(runRecoveryWorker(malformed, "malformed")).isEqualTo("CONFLICT\n");
        assertThat(closure(malformed.fixture.stateRoot())).isEqualTo(malformedClosure);

        Context unsafe = context("forked-unsafe-root");
        Path unsafeAlias = temporaryDirectory.resolve("forked-unsafe-root-link");
        Files.createSymbolicLink(unsafeAlias, unsafe.fixture.stateRoot());
        Closure unsafeClosure = closure(unsafe.fixture.stateRoot());
        assertThat(runRecoveryWorker(unsafe, "unsafe-root", unsafeAlias))
                .isEqualTo("INVALID\n");
        assertThat(closure(unsafe.fixture.stateRoot())).isEqualTo(unsafeClosure);

        Context vanished = context("forked-root-missing");
        Path originalRoot = vanished.fixture.stateRoot();
        Path movedRoot = originalRoot.resolveSibling("forked-root-preserved");
        Files.move(originalRoot, movedRoot);
        Closure movedClosure = closure(movedRoot);
        assertThat(runRecoveryWorker(vanished, "root-missing")).isEqualTo("UNAVAILABLE\n");
        assertThat(originalRoot).doesNotExist();
        assertThat(closure(movedRoot)).isEqualTo(movedClosure);
    }

    @Test
    void existingOnlyRecoveryReturnsClosedFoundAbsentConflictAndUnavailable() throws Exception {
        Context context = context("recovery-status");
        var committed = commit(context, fingerprint('8'), new InMemoryJournal()).leaseResult();
        Closure committedClosure = closure(context.fixture.stateRoot());
        var recovery = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();

        var found = recover(recovery, context, context.request);
        ExecutionLeaseRequest absentRequest = new ExecutionLeaseRequest(
                context.request.resultId(), context.request.resultRevision(),
                context.request.stageResultRawFingerprint(),
                context.request.evidenceClosureFingerprint(), context.request.contractId(),
                context.request.contractRevision(), "lease:absent",
                context.request.providerOuterFingerprint(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.lifecycleMaterial(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.trustedVerificationTime());
        ExecutionLeaseRequest conflictRequest = new ExecutionLeaseRequest(
                context.request.resultId() + ":conflict", context.request.resultRevision(),
                context.request.stageResultRawFingerprint(),
                context.request.evidenceClosureFingerprint(), context.request.contractId(),
                context.request.contractRevision(), context.request.executionLeaseId(),
                context.request.providerOuterFingerprint(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.lifecycleMaterial(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.trustedVerificationTime());

        assertThat(found.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.FOUND);
        assertThat(found.receipt()).isEqualTo(committed.receipt());
        assertThat(found.transitionWitness()).isEqualTo(committed.transitionWitness());
        var absent = recover(recovery, context, absentRequest);
        assertThat(absent.status())
                .isEqualTo(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExistingEvidenceRecoveryStatus.ABSENT);
        assertThat(absent.failureKind()).isEmpty();
        var conflict = recover(recovery, context, conflictRequest);
        assertThat(conflict.status())
                .isEqualTo(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExistingEvidenceRecoveryStatus.CONFLICT);
        assertThat(conflict.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.INVALID);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(committedClosure);

        Path checkpoint = context.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE);
        byte[] checkpointBytes = Files.readAllBytes(checkpoint);
        Files.writeString(checkpoint, "{\"corrupt\":true}");
        Closure corruptClosure = closure(context.fixture.stateRoot());
        var corrupt = recover(recovery, context, context.request);
        assertThat(corrupt.status())
                .isEqualTo(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExistingEvidenceRecoveryStatus.CONFLICT);
        assertThat(corrupt.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.INVALID);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(corruptClosure);
        Files.write(checkpoint, checkpointBytes);
    }

    @Test
    void malformedJournalIsInvalidWhilePermissionAndMissingIoAreUnavailable()
            throws Exception {
        Context malformed = context("typed-malformed-journal");
        Path transitionPart = malformed.fixture.stateRoot().resolve("."
                + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_PREFIX
                + "00000000000000000001"
                + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_SUFFIX + ".tmp");
        Files.writeString(transitionPart, "{\"unknownCredential\":\"UPPERCASE_PAYLOAD\"}");
        Files.setPosixFilePermissions(transitionPart,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Closure malformedClosure = closure(malformed.fixture.stateRoot());
        var malformedAttempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(malformed.request, fingerprint('8'),
                malformed.request.trustedVerificationTime(), malformed.evidenceParent);
        var malformedResult = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding().interruptedRecovery().recoverInterrupted(
                        malformedAttempt, ignored -> { });
        assertThat(malformedResult.failureKind()).contains(
                com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceFailureKind.INVALID);
        assertThat(malformedResult.toString()).doesNotContain("UPPERCASE_PAYLOAD");
        assertThat(closure(malformed.fixture.stateRoot())).isEqualTo(malformedClosure);

        Context unavailable = context("typed-runtime-unavailable");
        var recovery = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();
        Path state = unavailable.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        byte[] stateBytes = Files.readAllBytes(state);
        Files.setPosixFilePermissions(state,
                java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
        var permission = recover(recovery, unavailable, unavailable.request);
        assertThat(permission.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.UNAVAILABLE);
        assertThat(Files.readAllBytes(state)).isEqualTo(stateBytes);

        Files.setPosixFilePermissions(state,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.delete(state);
        var missing = recover(recovery, unavailable, unavailable.request);
        assertThat(missing.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.UNAVAILABLE);
        assertThat(missing.toString()).doesNotContain(state.toString());
    }

    @Test
    void mountedRejectedTransactionIsClosedWithoutObservationDereference() throws Exception {
        Context context = context("closed-rejected");
        ExecutionLeaseRequest wrong = new ExecutionLeaseRequest(
                context.request.resultId(), context.request.resultRevision(),
                context.request.stageResultRawFingerprint(),
                context.request.evidenceClosureFingerprint(), context.request.contractId(),
                context.request.contractRevision(), "lease:wrong",
                context.request.providerOuterFingerprint(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.lifecycleMaterial(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.trustedVerificationTime());
        var attempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(wrong, fingerprint('8'),
                wrong.trustedVerificationTime(), context.evidenceParent);

        var result = context.evidence.transactionAuthority().commit(
                attempt, new InMemoryJournal());

        assertThat(result.leaseResult().status()).isEqualTo(
                ExecutionLeaseCommitStatus.REJECTED);
        assertThat(result.beforeObservation()).isNull();
        assertThat(result.afterObservation()).isNull();

        var unavailable = context.evidence.transactionAuthority().commit(
                new com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceExecutionLeaseAttempt(context.request, fingerprint('8'),
                        context.request.trustedVerificationTime(), context.evidenceParent),
                new InMemoryJournal() {
                    @Override
                    public CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                            com.leanowtech.bloge.gateway.testkit
                                    .CapabilityStudioStageAcceptanceAuthorityProvider
                                    .EvidenceExecutionLeaseAttempt attempt,
                            CapabilityStudioDeploymentStateObservation.Observation current) {
                        throw new DeploymentUnavailableException();
                    }
                });
        assertThat(unavailable.leaseResult().status()).isEqualTo(
                ExecutionLeaseCommitStatus.UNAVAILABLE);
        assertThat(unavailable.beforeObservation()).isNull();
        assertThat(unavailable.afterObservation()).isNull();
    }

    @Test
    void typedJournalFailureMatrixPreservesStoreAndKeepsClosedCategories() throws Exception {
        Context directInvalid = context("journal-direct-invalid");
        Closure directInvalidBefore = closure(directInvalid.fixture.stateRoot());
        var invalidResult = commit(directInvalid, fingerprint('8'), new InMemoryJournal() {
            @Override
            public com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .EvidenceJournalResult<CapabilityStudioDeploymentStateObservation.Observation>
                    prepareBeforeResult(
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation current) {
                return com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceJournalResult.invalid();
            }
        });
        assertThat(invalidResult.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceFailureKind.INVALID);
        assertThat(invalidResult.leaseResult().status())
                .isEqualTo(ExecutionLeaseCommitStatus.INVALID);
        assertThat(closure(directInvalid.fixture.stateRoot()))
                .isEqualTo(directInvalidBefore);

        Context directRuntime = context("journal-direct-runtime");
        Closure directRuntimeBefore = closure(directRuntime.fixture.stateRoot());
        var runtimeResult = commit(directRuntime, fingerprint('8'), new InMemoryJournal() {
            @Override
            public CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation current) {
                throw new IllegalStateException("UPPERCASE_CREDENTIAL_PAYLOAD");
            }
        });
        assertThat(runtimeResult.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.UNAVAILABLE);
        assertThat(runtimeResult.toString()).doesNotContain(
                "UPPERCASE_CREDENTIAL_PAYLOAD");
        assertThat(closure(directRuntime.fixture.stateRoot()))
                .isEqualTo(directRuntimeBefore);

        Context directIo = context("journal-direct-io");
        Closure directIoBefore = closure(directIo.fixture.stateRoot());
        var ioResult = commit(directIo, fingerprint('8'), new InMemoryJournal() {
            @Override
            public CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation current) {
                throw new java.io.UncheckedIOException(
                        new IOException("PROVIDER_PATH_SECRET"));
            }
        });
        assertThat(ioResult.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.UNAVAILABLE);
        assertThat(ioResult.toString()).doesNotContain("PROVIDER_PATH_SECRET");
        assertThat(closure(directIo.fixture.stateRoot())).isEqualTo(directIoBefore);

        assertRecoveryJournalFailure("journal-recovery-invalid", true, false);
        assertRecoveryJournalFailure("journal-recovery-runtime", false, false);
        assertRecoveryJournalFailure("journal-recovery-io", false, true);
    }

    @Test
    void successorCrashIntermediateIsUnavailableAndNeverRepaired() throws Exception {
        Context context = context("successor");
        Path checkpoint = context.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE);
        byte[] genesisCheckpoint = Files.readAllBytes(checkpoint);
        commit(context, fingerprint('8'), new InMemoryJournal());
        Files.write(checkpoint, genesisCheckpoint);
        Closure before = closure(context.fixture.stateRoot());

        assertThatThrownBy(() -> context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.AFTER, fingerprint('8')))
                .isInstanceOf(DeploymentUnavailableException.class);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(before);
    }

    @Test
    void unknownResidueIsInvalidAndLockContentionIsNonMutatingUnavailable() throws Exception {
        Context residue = context("residue");
        Path unknown = residue.fixture.stateRoot().resolve("unknown.tmp");
        Files.writeString(unknown, "opaque");
        Closure beforeResidue = closure(residue.fixture.stateRoot());
        assertThatThrownBy(() -> residue.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.BEFORE, fingerprint('8')))
                .isInstanceOf(IllegalStateException.class);
        assertThat(closure(residue.fixture.stateRoot())).isEqualTo(beforeResidue);

        Context locked = context("locked");
        Path descriptor = locked.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.LOCK_FILE);
        Closure beforeLock = closure(locked.fixture.stateRoot());
        try (FileChannel channel = FileChannel.open(descriptor, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                    .observeExistingStore(locked.fixture.stateRoot().toRealPath(),
                            locked.storeDescriptorFingerprint,
                            CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                            fingerprint('8'), java.time.Duration.ofMillis(50).toNanos(),
                            () -> 1L))
                    .isInstanceOf(DeploymentUnavailableException.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(1));
        }
        assertThat(closure(locked.fixture.stateRoot())).isEqualTo(beforeLock);
        assertThat(locked.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                fingerprint('8'))).isNotNull();
        assertThat(FilesystemDeploymentAdmissionAuthority.localLockRegistrySizeForTesting())
                .isEqualTo(64);
    }

    @Test
    void observationLeaseBudgetNeverRefillsAcrossRollbackOrLongWrap() {
        java.util.ArrayDeque<Long> rollback = new java.util.ArrayDeque<>(
                java.util.List.of(100L, 150L, 140L));
        var budget = new FilesystemDeploymentAdmissionAuthority.ObservationLeaseBudget(
                100, rollback::removeFirst);
        assertThat(budget.remaining()).isEqualTo(50);
        assertThatThrownBy(budget::remaining)
                .isInstanceOf(IllegalStateException.class);

        java.util.ArrayDeque<Long> wrap = new java.util.ArrayDeque<>(java.util.List.of(
                Long.MAX_VALUE - 5, Long.MIN_VALUE + 5, Long.MIN_VALUE + 4));
        var wrapped = new FilesystemDeploymentAdmissionAuthority.ObservationLeaseBudget(
                100, wrap::removeFirst);
        assertThat(wrapped.remaining()).isEqualTo(89);
        assertThatThrownBy(wrapped::remaining)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void interruptedObserverFailsBoundedAndPreservesInterrupt() throws Exception {
        Context context = context("interrupted");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> FilesystemDeploymentAdmissionAuthority
                    .observeExistingStore(context.fixture.stateRoot(),
                            context.storeDescriptorFingerprint,
                            CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                            fingerprint('8'), java.time.Duration.ofMillis(50).toNanos(),
                            System::nanoTime))
                    .isInstanceOf(DeploymentUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                fingerprint('8'))).isNotNull();
    }

    @Test
    void providerObservationCliWritesFreshCanonicalEvidenceOutsideStore() throws Exception {
        Context context = context("cli");
        Path outputParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.resolve("evidence"));
        Path output = outputParent.resolve("before.json");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = MountedCapabilityStudioDeploymentStateObservationCli.run(new String[]{
                "--phase", "BEFORE",
                "--evidence-transaction-id", fingerprint('8'),
                "--state-root", context.fixture.stateRoot().toString(),
                "--expected-store-descriptor-fingerprint",
                context.storeDescriptorFingerprint,
                "--output", output.toString()
        }, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .startsWith("OBSERVED status=OBSERVED phase=BEFORE ")
                .endsWith("reasonCode="
                        + MountedCapabilityStudioDeploymentStateObservationCli.OBSERVED_REASON
                        + "\n");
        assertThat(CapabilityStudioDeploymentStateObservation.verify(
                Files.readAllBytes(output)).phase())
                .isEqualTo(CapabilityStudioDeploymentStateObservation.Phase.BEFORE);
        assertThat(((Number) Files.getAttribute(output, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777).isEqualTo(0400);
    }

    @Test
    void evidenceParentAtStateRootOrPhysicalAliasFailsBeforeJournalAndLease() throws Exception {
        Context context = context("overlap");
        Closure original = closure(context.fixture.stateRoot());
        java.util.concurrent.atomic.AtomicInteger journalCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        var journal = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceTransactionJournal() {
            @Override
            public CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation current) {
                journalCalls.incrementAndGet();
                return current;
            }

            @Override
            public void persistCommitted(
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation before,
                    CapabilityStudioDeploymentStateObservation.Observation after,
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseCommitResult result) {
                journalCalls.incrementAndGet();
            }
        };
        for (Path parent : java.util.List.of(context.fixture.stateRoot(),
                symbolicAlias(context.fixture.stateRoot()))) {
            var attempt = new com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .EvidenceExecutionLeaseAttempt(context.request, fingerprint('8'),
                    context.request.trustedVerificationTime(), parent);
            var result = context.evidence.transactionAuthority().commit(attempt, journal);
            assertThat(result.leaseResult().status())
                    .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        }
        assertThat(journalCalls).hasValue(0);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(original);
    }

    @Test
    void recomputedWitnessFieldTamperIsRejectedByLayeredStateAndCheckpointChain()
            throws Exception {
        Context context = context("witness-tamper");
        commit(context, fingerprint('8'), new InMemoryJournal());
        Path stateFile = context.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE);
        var state = (com.fasterxml.jackson.databind.node.ObjectNode)
                MountedProviderTestFixtures.JSON.readTree(stateFile.toFile());
        var witness = (com.fasterxml.jackson.databind.node.ObjectNode) state.path("leases")
                .get(0).path("transitionWitness");
        String changedCheckpoint = fingerprint('f');
        witness.put("postCheckpointFingerprint", changedCheckpoint);
        witness.put("fingerprint", com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExecutionLeaseTransitionWitness.witnessFingerprint(
                witness.path("materialFingerprint").textValue(),
                witness.path("postStateFingerprint").textValue(), changedCheckpoint));
        Files.write(stateFile, MountedProviderTestFixtures.JSON.writeValueAsBytes(state));
        Closure tampered = closure(context.fixture.stateRoot());

        assertThatThrownBy(() -> context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.AFTER, fingerprint('8')))
                .isInstanceOf(IllegalStateException.class);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(tampered);
    }

    @Test
    void recomputedHistoricalAfterRawTamperIsRejectedByPersistedStateCommitment()
            throws Exception {
        Context context = context("historical-after-tamper");
        commit(context, fingerprint('8'), new InMemoryJournal());
        Path transitionFile = context.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_PREFIX
                        + "00000000000000000001"
                        + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_SUFFIX);
        var transition = (com.fasterxml.jackson.databind.node.ObjectNode)
                MountedProviderTestFixtures.JSON.readTree(transitionFile.toFile());
        var after = CapabilityStudioDeploymentStateObservation.verify(
                java.util.Base64.getDecoder().decode(
                        transition.path("afterObservation").textValue()));
        var changed = CapabilityStudioDeploymentStateObservation.create(
                after.phase(), after.evidenceTransactionId(),
                after.storeDescriptorFingerprint(), after.storeDescriptorRawFingerprint(),
                after.generation(), after.previousStateFingerprint(),
                after.stateFingerprint(), fingerprint('f'),
                after.checkpointFingerprint(), after.checkpointRawFingerprint(),
                after.revocationHeadSequence(), after.revocationHeadFingerprint(),
                after.revocationHeadRawFingerprint(), after.lifecycleHeadFingerprint(),
                after.fencingSequence(), after.leaseCount(),
                after.leaseInventoryFingerprint());
        transition.put("afterObservation", java.util.Base64.getEncoder()
                .encodeToString(changed.bytes()));
        transition.putNull("transitionEvidenceFingerprint");
        byte[] unhashed = MountedProviderTestFixtures.JSON.writeValueAsBytes(transition);
        transition.put("transitionEvidenceFingerprint", "sha256:"
                + HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(unhashed)));
        Files.write(transitionFile,
                MountedProviderTestFixtures.JSON.writeValueAsBytes(transition));
        Closure tampered = closure(context.fixture.stateRoot());

        var recovery = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();
        var result = recover(recovery, context, context.request);
        assertThat(result.status())
                .isEqualTo(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExistingEvidenceRecoveryStatus.CONFLICT);
        assertThat(result.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.INVALID);
        assertThatThrownBy(() -> context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.AFTER, fingerprint('8')))
                .isInstanceOf(IllegalStateException.class);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(tampered);
    }

    @Test
    void unrelatedCommitCannotEnterBetweenCoordinatorBeforeAndAfter() throws Exception {
        Context context = context("coordinator-lock");
        var enteredJournal = new java.util.concurrent.CountDownLatch(1);
        var releaseJournal = new java.util.concurrent.CountDownLatch(1);
        var journal = new InMemoryJournal() {
            @Override
            public void persistCommitted(
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseAttempt attempt,
                    CapabilityStudioDeploymentStateObservation.Observation before,
                    CapabilityStudioDeploymentStateObservation.Observation after,
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceExecutionLeaseCommitResult result) {
                enteredJournal.countDown();
                try {
                    if (!releaseJournal.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("journal release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                super.persistCommitted(attempt, before, after, result);
            }
        };
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var transaction = executor.submit(
                    () -> commit(context, fingerprint('8'), journal));
            assertThat(enteredJournal.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            ExecutionLeaseRequest fork = new ExecutionLeaseRequest(
                    context.request.resultId() + ":fork", context.request.resultRevision(),
                    context.request.stageResultRawFingerprint(),
                    context.request.evidenceClosureFingerprint(),
                    context.request.contractId(), context.request.contractRevision(),
                    context.request.executionLeaseId(),
                    context.request.providerOuterFingerprint(),
                    context.request.targetRawFingerprint(),
                    context.request.targetCanonicalFingerprint(),
                    context.request.lifecycleMaterial(),
                    context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                    context.request.trustedVerificationTime());
            var unrelated = executor.submit(() -> context.evidence.formalBinding()
                    .targetAdmissionBinding().deploymentAuthorityBinding()
                    .executionLeaseAuthority().commit(fork));
            Thread.sleep(50);
            assertThat(unrelated.isDone()).isFalse();
            releaseJournal.countDown();
            var exact = transaction.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(exact.beforeObservation().generation()).isZero();
            assertThat(exact.afterObservation().generation()).isEqualTo(1);
            assertThat(unrelated.get(2, java.util.concurrent.TimeUnit.SECONDS).status())
                    .isEqualTo(ExecutionLeaseCommitStatus.REJECTED);
        } finally {
            releaseJournal.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2,
                    java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void absentClosureAndSameLeaseInsertionShareOneExclusiveTransaction() throws Exception {
        Context context = context("atomic-absent");
        var recovery = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();
        var callbackEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseCallback = new java.util.concurrent.CountDownLatch(1);
        var attempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(context.request, fingerprint('8'),
                context.request.trustedVerificationTime(), context.evidenceParent);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var absent = executor.submit(() -> recovery.recovery().recoverExisting(
                    attempt, ignored -> {
                        callbackEntered.countDown();
                        try {
                            if (!releaseCallback.await(2,
                                    java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new AssertionError("absent callback timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }));
            assertThat(callbackEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            var commit = executor.submit(
                    () -> commit(context, fingerprint('8'), new InMemoryJournal()));
            Thread.sleep(50);
            assertThat(commit.isDone()).isFalse();
            releaseCallback.countDown();
            assertThat(absent.get(2, java.util.concurrent.TimeUnit.SECONDS).status())
                    .isEqualTo(com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExistingEvidenceRecoveryStatus.ABSENT);
            assertThat(commit.get(2, java.util.concurrent.TimeUnit.SECONDS)
                    .leaseResult().status()).isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2,
                    java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void differentEvidenceParentsShareBoundedStoreLockAndPreserveWaiterInterrupt()
            throws Exception {
        Context context = context("recovery-lock-budget");
        var recovery = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();
        Path secondParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("recovery-lock-budget-second"));
        Path thirdParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("recovery-lock-budget-third"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holderAttempt = attempt(context, context.evidenceParent,
                evidenceBudget(java.time.Duration.ofMillis(250)));
        var timeoutAttempt = attempt(context, secondParent,
                evidenceBudget(java.time.Duration.ofMillis(250)));
        var interruptAttempt = attempt(context, thirdParent,
                evidenceBudget(java.time.Duration.ofSeconds(2)));
        Closure original = closure(context.fixture.stateRoot());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> recovery.recovery().recoverExisting(
                    holderAttempt, ignored -> {
                        entered.countDown();
                        try {
                            release.await(3, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            var timedOut = recovery.recovery().recoverExisting(timeoutAttempt, ignored -> { });
            assertThat(timedOut.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(2));

            var interruptedResult = new java.util.concurrent.atomic.AtomicReference<
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExistingEvidenceRecoveryResult>();
            var interruptPreserved = new java.util.concurrent.atomic.AtomicBoolean();
            Thread waiter = new Thread(() -> {
                interruptedResult.set(recovery.recovery().recoverExisting(
                        interruptAttempt, ignored -> { }));
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            });
            waiter.start();
            Thread.sleep(50);
            waiter.interrupt();
            waiter.join(1_000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(interruptedResult.get().status()).isEqualTo(
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
            assertThat(interruptPreserved).isTrue();

            release.countDown();
            assertThat(holder.get(1, java.util.concurrent.TimeUnit.SECONDS).status())
                    .isEqualTo(com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
            assertThat(closure(context.fixture.stateRoot())).isEqualTo(original);

            var reentrant = recovery.recovery().recoverExisting(
                    attempt(context, secondParent,
                            com.leanowtech.bloge.gateway.testkit
                                    .CapabilityStudioStageAcceptanceAuthorityProvider
                                    .EvidenceLeaseBudget.start()), ignored -> { });
            assertThat(reentrant.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryStatus.ABSENT);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2,
                    java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void recoveryOsLockPollingUsesTheSameBoundedBudget() throws Exception {
        Context context = context("recovery-os-lock-budget");
        var recovery = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();
        Path descriptor = context.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.LOCK_FILE);
        Closure original = closure(context.fixture.stateRoot());
        try (FileChannel channel = FileChannel.open(descriptor, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            long started = System.nanoTime();
            var result = recovery.recovery().recoverExisting(
                    attempt(context, context.evidenceParent,
                            evidenceBudget(java.time.Duration.ofMillis(250))), value -> { });
            assertThat(result.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
            assertThat(result.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .EvidenceFailureKind.UNAVAILABLE);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(2));
        }
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(original);
    }

    @Test
    void recoveryRejectsAliasGrandparentRenameStateRenameAndNlinkDrift()
            throws Exception {
        Context alias = context("recovery-alias");
        var aliasRecovery = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();
        Path link = temporaryDirectory.resolve("recovery-evidence-alias").toAbsolutePath();
        Files.createSymbolicLink(link, alias.evidenceParent);
        Closure aliasState = closure(alias.fixture.stateRoot());
        var aliasResult = aliasRecovery.recovery().recoverExisting(
                attempt(alias, link, evidenceBudget(java.time.Duration.ofSeconds(1))),
                ignored -> { });
        assertThat(aliasResult.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.CONFLICT);
        assertThat(closure(alias.fixture.stateRoot())).isEqualTo(aliasState);

        Context renamed = context("recovery-grandparent-rename");
        Path grandparent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("recovery-grandparent"));
        Path evidenceParent = MountedProviderTestFixtures.privateDirectory(
                grandparent.resolve("evidence"));
        Path movedGrandparent = grandparent.resolveSibling("recovery-grandparent-moved");
        Closure renamedState = closure(renamed.fixture.stateRoot());
        var renamedResult = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding().recovery().recoverExisting(
                        attempt(renamed, evidenceParent,
                                evidenceBudget(java.time.Duration.ofSeconds(1))), ignored -> {
                            try {
                                Files.move(grandparent, movedGrandparent);
                                MountedProviderTestFixtures.privateDirectory(grandparent);
                                MountedProviderTestFixtures.privateDirectory(
                                        grandparent.resolve("evidence"));
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
                        });
        assertThat(renamedResult.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
        assertThat(closure(renamed.fixture.stateRoot())).isEqualTo(renamedState);

        Context nlink = context("recovery-nlink-drift");
        Closure nlinkState = closure(nlink.fixture.stateRoot());
        var nlinkResult = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding().recovery().recoverExisting(
                        attempt(nlink, nlink.evidenceParent,
                                evidenceBudget(java.time.Duration.ofSeconds(1))), ignored -> {
                            try {
                                MountedProviderTestFixtures.privateDirectory(
                                        nlink.evidenceParent.resolve("unexpected-child"));
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
                        });
        assertThat(nlinkResult.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
        assertThat(closure(nlink.fixture.stateRoot())).isEqualTo(nlinkState);

        Context stateRename = context("recovery-state-rename");
        Path originalRoot = stateRename.fixture.stateRoot();
        Path movedRoot = originalRoot.resolveSibling("recovery-state-rename-moved");
        Closure originalState = closure(originalRoot);
        var stateRenameResult = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding().recovery().recoverExisting(
                        attempt(stateRename, stateRename.evidenceParent,
                                evidenceBudget(java.time.Duration.ofSeconds(1))), ignored -> {
                            try {
                                Files.move(originalRoot, movedRoot);
                                MountedProviderTestFixtures.privateDirectory(originalRoot);
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
                        });
        assertThat(stateRenameResult.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
        assertThat(closure(movedRoot)).isEqualTo(originalState);
    }

    @Test
    void exactRecoveryUsesHistoricalClosureAfterUnrelatedLeaseAdvancesStore()
            throws Exception {
        Context context = context("historical-after");
        var target = commit(context, fingerprint('8'), new InMemoryJournal());
        var descriptor = FilesystemDeploymentAdmissionAuthority.openExistingStore(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint);
        var unrelatedAuthority = new FilesystemDeploymentAdmissionAuthority(
                descriptor, context.request.lifecycleMaterial(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.providerOuterFingerprint(), "lease:unrelated",
                java.time.Clock.fixed(context.request.trustedVerificationTime()
                        .plusSeconds(1), java.time.ZoneOffset.UTC));
        ExecutionLeaseRequest unrelatedRequest = new ExecutionLeaseRequest(
                "result:unrelated", 1, fingerprint('a'), fingerprint('b'),
                context.request.contractId(), context.request.contractRevision(),
                "lease:unrelated", context.request.providerOuterFingerprint(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.lifecycleMaterial(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.trustedVerificationTime());

        assertThat(unrelatedAuthority.commitWithWitness(unrelatedRequest).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
        var current = context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.AFTER, fingerprint('8'));
        var recovered = recover(new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding(), context, context.request);

        assertThat(current.generation()).isEqualTo(2);
        assertThat(current.leaseCount()).isEqualTo(2);
        assertThat(recovered.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.FOUND);
        assertThat(recovered.beforeObservation()).isEqualTo(target.beforeObservation());
        assertThat(recovered.afterObservation()).isEqualTo(target.afterObservation());
        assertThat(recovered.afterObservation().generation()).isEqualTo(1);
        assertThat(recovered.afterObservation()).isNotEqualTo(current);
    }

    @Test
    void mixedOrdinaryAndEvidenceTransitionsReplayWithoutSidecarGaps() throws Exception {
        Context context = context("mixed-transition-replay");
        var descriptor = FilesystemDeploymentAdmissionAuthority.openExistingStore(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint);
        var firstAuthority = new FilesystemDeploymentAdmissionAuthority(
                descriptor, context.request.lifecycleMaterial(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.providerOuterFingerprint(), "lease:ordinary:first",
                java.time.Clock.fixed(context.request.trustedVerificationTime(),
                        java.time.ZoneOffset.UTC));
        ExecutionLeaseRequest firstRequest = new ExecutionLeaseRequest(
                "result:ordinary:first", 1, fingerprint('a'), fingerprint('b'),
                context.request.contractId(), context.request.contractRevision(),
                "lease:ordinary:first", context.request.providerOuterFingerprint(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.lifecycleMaterial(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.trustedVerificationTime());
        assertThat(firstAuthority.commitWithWitness(firstRequest).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);

        var target = commit(context, fingerprint('8'), new InMemoryJournal());
        var lastAuthority = new FilesystemDeploymentAdmissionAuthority(
                descriptor, context.request.lifecycleMaterial(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.providerOuterFingerprint(), "lease:ordinary:last",
                java.time.Clock.fixed(context.request.trustedVerificationTime(),
                        java.time.ZoneOffset.UTC));
        ExecutionLeaseRequest lastRequest = new ExecutionLeaseRequest(
                "result:ordinary:last", 1, fingerprint('c'), fingerprint('d'),
                context.request.contractId(), context.request.contractRevision(),
                "lease:ordinary:last", context.request.providerOuterFingerprint(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.lifecycleMaterial(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.trustedVerificationTime());
        assertThat(lastAuthority.commitWithWitness(lastRequest).status())
                .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);

        var recovered = recover(new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding(), context, context.request);

        assertThat(target.afterObservation().generation()).isEqualTo(2);
        assertThat(recovered.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.FOUND);
        assertThat(recovered.beforeObservation()).isEqualTo(target.beforeObservation());
        assertThat(recovered.afterObservation()).isEqualTo(target.afterObservation());
        assertThat(context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.AFTER, fingerprint('8'))
                .generation()).isEqualTo(3);

        Path transitionFile = context.fixture.stateRoot().resolve(
                FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_PREFIX
                        + "00000000000000000002"
                        + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_SUFFIX);
        var transition = (com.fasterxml.jackson.databind.node.ObjectNode)
                MountedProviderTestFixtures.JSON.readTree(transitionFile.toFile());
        var after = CapabilityStudioDeploymentStateObservation.verify(
                java.util.Base64.getDecoder().decode(
                        transition.path("afterObservation").textValue()));
        var changed = CapabilityStudioDeploymentStateObservation.create(
                after.phase(), after.evidenceTransactionId(),
                after.storeDescriptorFingerprint(), after.storeDescriptorRawFingerprint(),
                after.generation(), after.previousStateFingerprint(),
                after.stateFingerprint(), fingerprint('f'),
                after.checkpointFingerprint(), after.checkpointRawFingerprint(),
                after.revocationHeadSequence(), after.revocationHeadFingerprint(),
                after.revocationHeadRawFingerprint(), after.lifecycleHeadFingerprint(),
                after.fencingSequence(), after.leaseCount(),
                after.leaseInventoryFingerprint());
        transition.put("afterObservation", java.util.Base64.getEncoder()
                .encodeToString(changed.bytes()));
        transition.putNull("transitionEvidenceFingerprint");
        transition.put("transitionEvidenceFingerprint", "sha256:"
                + HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(
                MountedProviderTestFixtures.JSON.writeValueAsBytes(transition))));
        Files.write(transitionFile,
                MountedProviderTestFixtures.JSON.writeValueAsBytes(transition));

        var tampered = recover(new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding(), context, context.request);
        assertThat(tampered.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.CONFLICT);
        assertThat(tampered.failureKind()).contains(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.INVALID);
    }

    @Test
    void alternatingOrdinaryAndEvidenceReplayVisitsEachGenerationOnce() throws Exception {
        Context context = context("alternating-transition-replay");
        var descriptor = FilesystemDeploymentAdmissionAuthority.openExistingStore(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint);
        ExecutionLeaseRequest firstEvidenceRequest = null;
        String firstEvidenceTransaction = null;
        int transitions = 20;
        for (int index = 0; index < transitions; index++) {
            String leaseId = "lease:alternating:" + index;
            ExecutionLeaseRequest request = new ExecutionLeaseRequest(
                    "result:alternating:" + index, 1,
                    fingerprint("stage:" + index), fingerprint("evidence:" + index),
                    context.request.contractId(), context.request.contractRevision(), leaseId,
                    context.request.providerOuterFingerprint(),
                    context.request.targetRawFingerprint(),
                    context.request.targetCanonicalFingerprint(),
                    context.request.lifecycleMaterial(),
                    context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                    context.request.trustedVerificationTime());
            var authority = new FilesystemDeploymentAdmissionAuthority(
                    descriptor, request.lifecycleMaterial(), request.targetRawFingerprint(),
                    request.targetCanonicalFingerprint(),
                    request.deploymentAdmissionAuthorityMaterialFingerprint(),
                    request.providerOuterFingerprint(), leaseId,
                    java.time.Clock.fixed(request.trustedVerificationTime(),
                            java.time.ZoneOffset.UTC));
            if ((index & 1) == 0) {
                assertThat(authority.commitWithWitness(request).status())
                        .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
            } else {
                String transactionId = fingerprint("transaction:" + index);
                var attempt = new com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceExecutionLeaseAttempt(request, transactionId,
                        request.trustedVerificationTime(), context.evidenceParent);
                assertThat(authority.commitEvidenceTransaction(
                        attempt, new InMemoryJournal()).leaseResult().status())
                        .isEqualTo(ExecutionLeaseCommitStatus.COMMITTED);
                if (firstEvidenceRequest == null) {
                    firstEvidenceRequest = request;
                    firstEvidenceTransaction = transactionId;
                }
            }
        }

        var recoveryStore = FilesystemDeploymentAdmissionAuthority
                .openExistingEvidenceRecoveryStore(context.fixture.stateRoot());
        var recoveryAttempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(firstEvidenceRequest,
                firstEvidenceTransaction, firstEvidenceRequest.trustedVerificationTime(),
                context.evidenceParent, 1, null,
                evidenceBudget(java.time.Duration.ofSeconds(2)));
        var recovered = FilesystemDeploymentAdmissionAuthority.recoverExistingOnly(
                recoveryStore, recoveryAttempt, ignored -> { });

        assertThat(recovered.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.FOUND);
        assertThat(context.evidence.stateObserver().observe(
                CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                fingerprint('8')).generation()).isEqualTo(transitions);
    }

    @Test
    void fullCapacityFixtureIsAcceptedAndRecoveredByProductionParsers() throws Exception {
        Context context = context("full-capacity-replay");
        long fixtureStarted = System.nanoTime();
        var capacity = EvidenceCapacityStoreFixtureBuilder.build(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint,
                context.request, FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        long fixtureNanos = System.nanoTime() - fixtureStarted;
        Closure immutableStore = closure(context.fixture.stateRoot());
        long validationStarted = System.nanoTime();
        FilesystemDeploymentAdmissionAuthority.openExistingStore(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint);
        var observed = FilesystemDeploymentAdmissionAuthority.observeExistingStore(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint,
                CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                capacity.evidenceTransactionId());
        assertThat(observed.generation())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        assertThat(observed.leaseCount())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        var recoveryStore = FilesystemDeploymentAdmissionAuthority
                .openExistingEvidenceRecoveryStore(context.fixture.stateRoot());
        var attempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(capacity.recoverableRequest(),
                capacity.evidenceTransactionId(),
                capacity.recoverableRequest().trustedVerificationTime(),
                context.evidenceParent);
        var recovered = FilesystemDeploymentAdmissionAuthority.recoverExistingOnly(
                recoveryStore, attempt, ignored -> { });
        assertThat(recovered.status())
                .isEqualTo(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExistingEvidenceRecoveryStatus.FOUND);
        assertThat(recovered.receipt()).isEqualTo(capacity.receipt());
        assertThat(recovered.transitionWitness()).isEqualTo(capacity.witness());
        assertThat(recovered.afterObservation().observationFingerprint())
                .isEqualTo(capacity.historicalAfterObservationFingerprint());
        long validationNanos = System.nanoTime() - validationStarted;
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(immutableStore);

        Path runtimeJar = Path.of(System.getProperty("user.dir"), "target",
                "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                        + "runtime-under-test.jar");
        Path harnessJar = Path.of(System.getProperty("user.dir"), "target",
                "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                        + "child-harness.jar");
        String builderEntry = EvidenceCapacityStoreFixtureBuilder.class.getName()
                .replace('.', '/') + ".class";
        try (var runtime = new java.util.jar.JarFile(runtimeJar.toFile());
             var harness = new java.util.jar.JarFile(harnessJar.toFile())) {
            assertThat(runtime.getJarEntry(builderEntry)).isNull();
            assertThat(harness.getJarEntry(builderEntry)).isNotNull();
        }

        System.out.printf("capacity fixture=%dms validation=%dms%n",
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(fixtureNanos),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(validationNanos));
    }

    @Test
    void packagedChildrenObserveAndRecoverFullCapacityStoreWithin128MiB() throws Exception {
        Context context = context("packaged-full-capacity");
        var capacity = EvidenceCapacityStoreFixtureBuilder.build(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint,
                context.request, FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        Closure immutableStore = closure(context.fixture.stateRoot());
        Path childDirectory = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("packaged-full-capacity-output"));

        Path observationFile = childDirectory.resolve("observation.json");
        Path observationOutput = temporaryDirectory.resolve(
                "packaged-full-capacity-observation.out");
        long observationStarted = System.nanoTime();
        Process observationChild = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-cp", shadedWorkerClasspath(),
                MountedCapabilityStudioDeploymentStateObservationCli.class.getName(),
                "--phase", "AFTER",
                "--evidence-transaction-id", capacity.evidenceTransactionId(),
                "--state-root", context.fixture.stateRoot().toString(),
                "--expected-store-descriptor-fingerprint",
                context.storeDescriptorFingerprint,
                "--output", observationFile.toString())
                .redirectErrorStream(true).redirectOutput(observationOutput.toFile()).start();
        awaitScaleChild(observationChild, observationOutput);
        long observationNanos = System.nanoTime() - observationStarted;
        var observed = CapabilityStudioDeploymentStateObservation.verify(
                Files.readAllBytes(observationFile));
        assertThat(observed.generation())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        assertThat(observed.leaseCount())
                .isEqualTo(FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(immutableStore);

        Path requestFile = temporaryDirectory.resolve("packaged-full-capacity-request.json");
        Files.write(requestFile, requestJson(capacity.recoverableRequest()).toString()
                .getBytes(StandardCharsets.UTF_8));
        Path recoveryOutput = temporaryDirectory.resolve(
                "packaged-full-capacity-recovery.out");
        long recoveryStarted = System.nanoTime();
        Process recoveryChild = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "=" + context.fixture.authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "="
                        + context.fixture.targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "="
                        + context.fixture.stateRoot(),
                "-cp", shadedWorkerClasspath(), MountedEvidenceRecoveryWorker.class.getName(),
                requestFile.toString(), context.evidenceParent.toString(),
                capacity.evidenceTransactionId())
                .redirectErrorStream(true).redirectOutput(recoveryOutput.toFile()).start();
        awaitScaleChild(recoveryChild, recoveryOutput);
        long recoveryNanos = System.nanoTime() - recoveryStarted;
        assertThat(Files.readString(recoveryOutput)).isEqualTo("RECOVERED "
                + capacity.receipt().fingerprint() + " "
                + capacity.witness().fingerprint() + " "
                + capacity.historicalAfterObservationFingerprint() + "\n");
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(immutableStore);

        List<Path> artifacts = workerArtifacts();
        String builderEntry = EvidenceCapacityStoreFixtureBuilder.class.getName()
                .replace('.', '/') + ".class";
        String workerEntry = MountedEvidenceRecoveryWorker.class.getName()
                .replace('.', '/') + ".class";
        for (int index = 0; index < artifacts.size(); index++) {
            try (var jar = new java.util.jar.JarFile(artifacts.get(index).toFile())) {
                if (index == artifacts.size() - 1) {
                    assertThat(jar.getJarEntry(builderEntry)).isNotNull();
                    assertThat(jar.getJarEntry(workerEntry)).isNotNull();
                } else {
                    assertThat(jar.getJarEntry(builderEntry)).isNull();
                    assertThat(jar.getJarEntry(workerEntry)).isNull();
                }
            }
        }
        System.out.printf("capacity child observation=%dms recovery=%dms%n",
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(observationNanos),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(recoveryNanos));
    }

    @Test
    void fullCapacityReplayBudgetAndInterruptReleaseStoreLock() throws Exception {
        Context context = context("budget-full-capacity");
        var capacity = EvidenceCapacityStoreFixtureBuilder.build(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint,
                context.request, FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        Closure immutableStore = closure(context.fixture.stateRoot());
        var store = FilesystemDeploymentAdmissionAuthority
                .openExistingEvidenceRecoveryStore(context.fixture.stateRoot());
        var delayedAttempt = capacityAttempt(capacity, context,
                evidenceBudget(java.time.Duration.ofNanos(1)));
        var exhausted = FilesystemDeploymentAdmissionAuthority.recoverExistingOnly(
                store, delayedAttempt, ignored -> { });
        assertThat(exhausted.status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
        assertThat(FilesystemDeploymentAdmissionAuthority.recoverExistingOnly(
                store, capacityAttempt(capacity, context), ignored -> { }).status())
                .isEqualTo(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExistingEvidenceRecoveryStatus.FOUND);

        var interruptedResult = new java.util.concurrent.atomic.AtomicReference<com.leanowtech
                .bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryResult>();
        var interruptPreserved = new java.util.concurrent.atomic.AtomicBoolean();
        Thread interrupted = Thread.ofPlatform().unstarted(() -> {
            Thread.currentThread().interrupt();
            interruptedResult.set(FilesystemDeploymentAdmissionAuthority.recoverExistingOnly(
                    store, capacityAttempt(capacity, context), ignored -> { }));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });
        interrupted.start();
        interrupted.join(java.time.Duration.ofSeconds(5));
        if (interrupted.isAlive()) {
            interrupted.interrupt();
            interrupted.join(java.time.Duration.ofSeconds(2));
        }
        assertThat(interrupted.isAlive()).isFalse();
        assertThat(interruptedResult.get().status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
        assertThat(interruptPreserved).isTrue();
        assertThat(FilesystemDeploymentAdmissionAuthority.recoverExistingOnly(
                store, capacityAttempt(capacity, context), ignored -> { }).status())
                .isEqualTo(com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .ExistingEvidenceRecoveryStatus.FOUND);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(immutableStore);
    }

    @Test
    void fullCapacityStoreRejectsTheNextCommitWithoutMutation() throws Exception {
        Context context = context("overflow-full-capacity");
        EvidenceCapacityStoreFixtureBuilder.build(context.fixture.stateRoot(),
                context.storeDescriptorFingerprint, context.request,
                FilesystemDeploymentAdmissionAuthority.MAX_LEASES);
        Closure immutableStore = closure(context.fixture.stateRoot());
        var store = FilesystemDeploymentAdmissionAuthority.openExistingStore(
                context.fixture.stateRoot(), context.storeDescriptorFingerprint);
        ExecutionLeaseRequest overflowRequest = new ExecutionLeaseRequest(
                "result:capacity:overflow", 1, fingerprint("capacity-stage:overflow"),
                fingerprint("capacity-evidence:overflow"), context.request.contractId(),
                context.request.contractRevision(), "lease:capacity:overflow",
                context.request.providerOuterFingerprint(),
                context.request.targetRawFingerprint(),
                context.request.targetCanonicalFingerprint(),
                context.request.lifecycleMaterial(),
                context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                context.request.trustedVerificationTime());
        var authority = new FilesystemDeploymentAdmissionAuthority(store,
                overflowRequest.lifecycleMaterial(), overflowRequest.targetRawFingerprint(),
                overflowRequest.targetCanonicalFingerprint(),
                overflowRequest.deploymentAdmissionAuthorityMaterialFingerprint(),
                overflowRequest.providerOuterFingerprint(), overflowRequest.executionLeaseId(),
                java.time.Clock.fixed(overflowRequest.trustedVerificationTime(),
                        java.time.ZoneOffset.UTC));

        var overflow = authority.commitWithWitness(overflowRequest);

        assertThat(overflow.status()).isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
        assertThat(overflow.reasonCode()).isEqualTo(
                FilesystemDeploymentAdmissionAuthority.CAPACITY_UNAVAILABLE);
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(immutableStore);
    }

    @Test
    void transitionEvidenceJournalSurvivesStateAndCheckpointCrashIntermediates()
            throws Exception {
        for (String failedTarget : java.util.List.of(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE,
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE)) {
            Context context = context("transition-crash-" + failedTarget);
            OneReplaceFailure durability = new OneReplaceFailure(failedTarget);
            var store = FilesystemDeploymentAdmissionAuthority
                    .openExistingEvidenceWriterStoreForTesting(
                            context.fixture.stateRoot(),
                            context.storeDescriptorFingerprint, durability);
            var authority = new FilesystemDeploymentAdmissionAuthority(store,
                    context.request.lifecycleMaterial(),
                    context.request.targetRawFingerprint(),
                    context.request.targetCanonicalFingerprint(),
                    context.request.deploymentAdmissionAuthorityMaterialFingerprint(),
                    context.request.providerOuterFingerprint(),
                    context.request.executionLeaseId(),
                    java.time.Clock.fixed(context.request.trustedVerificationTime()
                            .plusSeconds(1), java.time.ZoneOffset.UTC));
            var attempt = new com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .EvidenceExecutionLeaseAttempt(context.request, fingerprint('8'),
                    context.request.trustedVerificationTime(), context.evidenceParent);

            InMemoryJournal journal = new InMemoryJournal();
            var interrupted = authority.commitEvidenceTransaction(attempt, journal);
            assertThat(interrupted.leaseResult().status())
                    .isEqualTo(ExecutionLeaseCommitStatus.UNAVAILABLE);
            Closure intermediate = closure(context.fixture.stateRoot());
            var existingOnly = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                    .formalEvidenceRecoveryBinding();
            assertThat(existingOnly.recovery().recoverExisting(attempt, ignored -> { })
                    .status()).isEqualTo(com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryStatus.UNAVAILABLE);
            assertThat(closure(context.fixture.stateRoot())).isEqualTo(intermediate);

            var recovered = authority.commitEvidenceTransaction(attempt, journal);
            assertThat(recovered.leaseResult().status()).isIn(
                    ExecutionLeaseCommitStatus.COMMITTED,
                    ExecutionLeaseCommitStatus.RECOVERED);
            assertThat(recovered.beforeObservation().generation()).isZero();
            assertThat(recovered.afterObservation().generation()).isEqualTo(1);
            assertThat(context.evidence.stateObserver().observe(
                    CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                    fingerprint('8')).generation()).isEqualTo(1);
        }
    }

    @Test
    void actualShadedEvidenceAndBundleVerifierClisUseOnlyPackagedJars()
            throws Exception {
        var full = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "packaged-evidence");
        var localVerification = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceResultV2Verifier()
                .verify(Files.readAllBytes(full.stageResult()));
        assertThat(localVerification.verified())
                .as(localVerification.errorCode()).isTrue();
        configure(full.fixture());
        var localProvider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        var localAuthority = localProvider.authorityBinding();
        var authorityVerification = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityVerifier().verify(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        Files.readAllBytes(full.stageResult())),
                java.time.Instant.now(), localAuthority.resolver(),
                localAuthority.issuerPolicy(), localAuthority.ownerAuthority());
        assertThat(authorityVerification.accepted())
                .as(authorityVerification.reasonCode()).isTrue();
        String outer = localProvider.formalMaterialDeclaration().formalOuterFingerprint();
        Path evidenceParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("packaged-evidence-output"));
        var publication = provisionPublication(evidenceParent);
        Path transcript = evidenceParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        String classpath = shadedWorkerClasspath();
        assertThat(classpath).doesNotContain("target/classes", "target/test-classes");

        Path acceptanceOutput = temporaryDirectory.resolve("packaged-evidence.out");
        ProcessBuilder acceptance = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "="
                        + full.fixture().stateRoot(),
                "-cp", classpath,
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceCli",
                full.stageResult().toString(), transcript.toString())
                .redirectErrorStream(true).redirectOutput(acceptanceOutput.toFile());
        acceptance.environment().put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", outer);
        acceptance.environment().put(
                CapabilityStudioExecutionLeaseEvidencePublication
                        .EXPECTED_PUBLICATION_FINGERPRINT_ENV,
                publication.publicationFingerprint());
        Process accepted = acceptance.start();
        awaitChild(accepted, acceptanceOutput, 0);
        assertThat(Files.readString(acceptanceOutput))
                .startsWith("ACCEPTED status=ACCEPTED ")
                .contains("evidencePublicationStatus=COMMITTED");
        assertThat(transcript).isRegularFile();

        String stageRaw = "sha256:" + HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(full.stageResult())));
        Path verifyOutput = temporaryDirectory.resolve("packaged-bundle-verify.out");
        Process verified = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath,
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli",
                "--transcript", transcript.toString(),
                "--expected-stage-result-raw-fingerprint", stageRaw,
                "--expected-formal-outer-fingerprint", outer,
                "--expected-publication-fingerprint",
                publication.publicationFingerprint())
                .redirectErrorStream(true).redirectOutput(verifyOutput.toFile()).start();
        awaitChild(verified, verifyOutput, 0);
        assertThat(Files.readString(verifyOutput))
                .startsWith("VERIFIED status=VERIFIED verificationScope=DURABLE_WRAPPER ");
    }

    @Test
    void normalEvidenceCommitProducesStableSortedManifestAndOuterCommitment()
            throws Exception {
        var full = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "manifest-normal");
        configure(full.fixture());
        String outer = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalMaterialDeclaration().formalOuterFingerprint();
        Path parent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("manifest-normal-output"));
        Path transcript = parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);

        Path firstOutput = temporaryDirectory.resolve("manifest-normal-first.out");
        awaitChild(evidenceProcess(full, transcript, outer, null, firstOutput),
                firstOutput, 0);
        Path wrapper = singleEvidenceWrapper(parent);
        Path manifest = wrapper.resolve("commit-manifest-v1.json");
        Path commitment = wrapper.resolve("final-commit-v1.json");
        byte[] manifestBytes = Files.readAllBytes(manifest);
        byte[] commitmentBytes = Files.readAllBytes(commitment);
        var wire = new com.fasterxml.jackson.databind.ObjectMapper().readTree(manifestBytes);
        List<String> paths = new java.util.ArrayList<>();
        wire.path("artifacts").forEach(entry -> paths.add(
                entry.path("relativePath").asText()));
        assertThat(paths).isSorted();
        assertThat(paths).containsExactly(
                "before-v2-g00000000000000000001.json",
                "committed-transcript-v1.json", "owner-v3.json");
        var outerWire = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(commitmentBytes);
        assertThat(outerWire.path("commitManifestRawFingerprint").asText())
                .isEqualTo(rawFingerprint(manifest));
        assertThat(outerWire.path("finalRawFingerprint").asText())
                .isEqualTo(rawFingerprint(transcript));

        Path retryOutput = temporaryDirectory.resolve("manifest-normal-retry.out");
        awaitChild(evidenceProcess(full, transcript, outer, null, retryOutput),
                retryOutput, 0);
        assertThat(Files.readString(retryOutput))
                .contains("evidencePublicationStatus=RECOVERED");
        assertThat(Files.readAllBytes(manifest)).isEqualTo(manifestBytes);
        assertThat(Files.readAllBytes(commitment)).isEqualTo(commitmentBytes);
    }

    @Test
    void packagedManifestDurabilityCheckpointRecoversThroughProductionEntry()
            throws Exception {
        var full = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "manifest-recovery");
        configure(full.fixture());
        String outer = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalMaterialDeclaration().formalOuterFingerprint();
        Path parent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("manifest-recovery-output"));
        Path transcript = parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        var publication = provisionPublication(parent);

        Path crashOutput = temporaryDirectory.resolve("manifest-recovery-crash.out");
        awaitChild(evidenceProcessWithPublication(full, transcript, outer,
                "MANIFEST_DURABLE_BEFORE_FINAL_COMMIT", crashOutput,
                publication.publicationFingerprint()), crashOutput, 86);
        Path wrapper = singleEvidenceWrapper(parent);
        Path manifest = wrapper.resolve("commit-manifest-v1.json");
        Path commitment = wrapper.resolve("final-commit-v1.json");
        Path commitmentPart = wrapper.resolve(".final-commit-v1.json.part");
        Path committed = wrapper.resolve("committed-transcript-v1.json");
        byte[] manifestBytes = Files.readAllBytes(manifest);
        var committedTranscript = com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioExecutionLeaseTranscript.verify(
                Files.readAllBytes(committed));
        assertPrivateRegular(manifest, 1);
        assertThat(wrapper.resolve(".commit-manifest-v1.json.part")).doesNotExist();
        assertThat(commitmentPart).doesNotExist();
        assertThat(commitment).doesNotExist();
        assertThat(transcript).doesNotExist();
        assertEvidencePublicationInventory(parent, wrapper, publication, false);
        assertCommittedStoreInventory(full.fixture().stateRoot());

        Path retryOutput = temporaryDirectory.resolve("manifest-recovery-retry.out");
        awaitChild(productionEvidenceProcess(full, transcript, outer, retryOutput,
                publication.publicationFingerprint()), retryOutput, 0);
        assertThat(Files.readString(retryOutput))
                .contains("evidencePublicationStatus=RECOVERED");
        assertThat(Files.readAllBytes(manifest)).isEqualTo(manifestBytes);
        assertThat(commitment).isRegularFile();
        assertThat(commitmentPart).doesNotExist();
        var recoveredTranscript = com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioExecutionLeaseTranscript.verify(
                Files.readAllBytes(transcript));
        assertThat(recoveredTranscript.executionLeaseReceipt())
                .isEqualTo(committedTranscript.executionLeaseReceipt());
        assertThat(recoveredTranscript.executionLeaseTransitionWitness())
                .isEqualTo(committedTranscript.executionLeaseTransitionWitness());
        assertThat(recoveredTranscript.bytes()).isEqualTo(committedTranscript.bytes());
        assertEvidencePublicationInventory(parent, wrapper, publication, true);
        assertCommittedStoreInventory(full.fixture().stateRoot());

        Path verifyOutput = temporaryDirectory.resolve("manifest-recovery-verify.out");
        awaitChild(bundleVerifierProcess(transcript, rawFingerprint(full.stageResult()),
                outer, verifyOutput), verifyOutput, 0);
        assertThat(Files.readString(verifyOutput)).startsWith(
                "VERIFIED status=VERIFIED verificationScope=DURABLE_WRAPPER ");
        assertEvidencePublicationInventory(parent, wrapper, publication, true);
    }

    @TestFactory
    List<DynamicTest> actualPackagedEvidenceFlowRecoversAllSeventeenContractCrashPoints()
            throws Exception {
        List<String> manifestPoints = crashPointsFromFrozenManifest();
        assertThat(manifestPoints).containsExactlyElementsOf(PACKAGED_CRASH_POINTS);
        assertThat(manifestPoints).hasSize(17);
        return java.util.stream.IntStream.range(0, manifestPoints.size())
                .mapToObj(index -> DynamicTest.dynamicTest(
                        String.format("%02d-%s", index + 1, manifestPoints.get(index)),
                        () -> assertPackagedCrashRecovery(index,
                                manifestPoints.get(index))))
                .toList();
    }

    @RepeatedTest(10)
    void twoPackagedJvmCallersSerializeAndConflictingLeaseRequestIsRejected()
            throws Exception {
        var full = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "packaged-concurrent");
        configure(full.fixture());
        var provider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        String outer = provider.formalMaterialDeclaration().formalOuterFingerprint();
        String descriptor = provider.formalMaterialDeclaration().storeDescriptorFingerprint();
        Path evidenceParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("packaged-concurrent-output"));
        Path transcript = evidenceParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        Path firstOutput = temporaryDirectory.resolve("packaged-concurrent-first.out");
        Path secondOutput = temporaryDirectory.resolve("packaged-concurrent-second.out");
        var publication = provisionPublication(evidenceParent);
        Path barrier = temporaryDirectory.resolve("packaged-concurrent.barrier");
        Path ready = temporaryDirectory.resolve("packaged-concurrent.ready");
        Path lockMiss = temporaryDirectory.resolve("packaged-concurrent-lock-miss.ready");
        Path readyDurabilityBarrier = temporaryDirectory.resolve(
                "packaged-concurrent-ready-durability.barrier");
        Path lockMissDurabilityBarrier = temporaryDirectory.resolve(
                "packaged-concurrent-lock-miss-durability.barrier");
        Files.write(barrier, new byte[0], StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        Files.write(readyDurabilityBarrier, new byte[0], StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        Files.write(lockMissDurabilityBarrier, new byte[0],
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Process first = null;
        Process second = null;
        try {
            try (var barrierChannel = java.nio.channels.FileChannel.open(
                         barrier, StandardOpenOption.WRITE);
                 var barrierLock = barrierChannel.lock();
                 var readyDurabilityChannel = java.nio.channels.FileChannel.open(
                         readyDurabilityBarrier, StandardOpenOption.WRITE);
                 var readyDurabilityLock = readyDurabilityChannel.lock();
                 var lockMissDurabilityChannel = java.nio.channels.FileChannel.open(
                         lockMissDurabilityBarrier, StandardOpenOption.WRITE);
                 var lockMissDurabilityLock = lockMissDurabilityChannel.lock()) {
                first = evidenceBarrierProcess(full, transcript, outer, firstOutput,
                        publication.publicationFingerprint(), ready, barrier,
                        readyDurabilityBarrier);
                awaitDurableMarkerAfterPausedParentForce(
                        ready, "READY\n", first, firstOutput, readyDurabilityLock);

                second = evidenceLockMissProcess(full, transcript, outer,
                        secondOutput, publication.publicationFingerprint(), lockMiss,
                        lockMissDurabilityBarrier);
                awaitDurableMarkerAfterPausedParentForce(
                        lockMiss, "LOCK_MISS\n", second, secondOutput,
                        lockMissDurabilityLock);
                assertThat(first.isAlive())
                        .as("first child output: %s", Files.readString(firstOutput))
                        .isTrue();
                assertThat(second.isAlive())
                        .as("second child output: %s", Files.readString(secondOutput))
                        .isTrue();
                assertThat(Files.readString(firstOutput)).isEmpty();
                assertThat(Files.readString(secondOutput)).isEmpty();
            }
            awaitChild(first, firstOutput, 0);
            awaitChild(second, secondOutput, 0);
        } finally {
            reap(first);
            reap(second);
        }
        String firstLine = Files.readString(firstOutput);
        String secondLine = Files.readString(secondOutput);
        assertThat(firstLine).contains("commitStatus=COMMITTED");
        assertThat(secondLine).contains("commitStatus=RECOVERED");
        Map<String, String> firstFields = acceptedFields(firstLine);
        Map<String, String> secondFields = acceptedFields(secondLine);
        assertThat(secondFields.get("leaseReceiptFingerprint"))
                .isEqualTo(firstFields.get("leaseReceiptFingerprint"));
        assertThat(secondFields.get("transitionWitnessFingerprint"))
                .isEqualTo(firstFields.get("transitionWitnessFingerprint"));
        assertThat(secondFields.get("transcriptFingerprint"))
                .isEqualTo(firstFields.get("transcriptFingerprint"));

        Path productionRecoveryOutput = temporaryDirectory.resolve(
                "packaged-concurrent-production-recovery.out");
        awaitChild(productionEvidenceProcess(full, transcript, outer,
                productionRecoveryOutput, publication.publicationFingerprint()),
                productionRecoveryOutput, 0);
        String productionRecoveryLine = Files.readString(productionRecoveryOutput);
        assertThat(productionRecoveryLine).contains(
                "evidencePublicationStatus=RECOVERED", "commitStatus=RECOVERED");
        Map<String, String> productionRecoveryFields = acceptedFields(productionRecoveryLine);
        assertThat(productionRecoveryFields.get("leaseReceiptFingerprint"))
                .isEqualTo(firstFields.get("leaseReceiptFingerprint"));
        assertThat(productionRecoveryFields.get("transitionWitnessFingerprint"))
                .isEqualTo(firstFields.get("transitionWitnessFingerprint"));
        assertThat(productionRecoveryFields.get("transcriptFingerprint"))
                .isEqualTo(firstFields.get("transcriptFingerprint"));
        var durableTranscript = com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioExecutionLeaseTranscript.verify(Files.readAllBytes(transcript));
        assertThat(durableTranscript.executionLeaseReceipt().fingerprint())
                .isEqualTo(firstFields.get("leaseReceiptFingerprint"));
        assertThat(durableTranscript.executionLeaseTransitionWitness().fingerprint())
                .isEqualTo(firstFields.get("transitionWitnessFingerprint"));
        assertThat(durableTranscript.transcriptFingerprint())
                .isEqualTo(firstFields.get("transcriptFingerprint"));
        Path wrapper = singleEvidenceWrapper(evidenceParent);
        assertThat(Files.readAllBytes(wrapper.resolve("committed-transcript-v1.json")))
                .isEqualTo(Files.readAllBytes(transcript));
        var observed = FilesystemDeploymentAdmissionAuthority.observeExistingStore(
                full.fixture().stateRoot(), descriptor,
                CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                fingerprint('8'));
        assertThat(observed.leaseCount()).isEqualTo(1);
        assertThat(observed.generation()).isEqualTo(1);

        Path driftedStage = temporaryDirectory.resolve("packaged-concurrent-drift.json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Files.writeString(driftedStage, json.writerWithDefaultPrettyPrinter().writeValueAsString(
                json.readTree(Files.readAllBytes(full.stageResult()))));
        var drifted = new MountedProviderTestFixtures.FullEvidenceFixture(
                full.fixture(), driftedStage);
        Path conflictOutput = temporaryDirectory.resolve("packaged-concurrent-conflict.out");
        Closure publicationBeforeConflict = closure(evidenceParent);
        Closure storeBeforeConflict = closure(full.fixture().stateRoot());
        byte[] transcriptBeforeConflict = Files.readAllBytes(transcript);
        Process conflict = productionEvidenceProcess(drifted, transcript, outer,
                conflictOutput, publication.publicationFingerprint());
        awaitChild(conflict, conflictOutput, 2);
        assertThat(Files.readString(conflictOutput))
                .contains("RECOVERY_INVALID")
                .doesNotContain("mounted-provider", "contract:mounted-provider");
        assertThat(Files.readAllBytes(transcript)).isEqualTo(transcriptBeforeConflict);
        assertThat(closure(evidenceParent)).isEqualTo(publicationBeforeConflict);
        assertThat(closure(full.fixture().stateRoot())).isEqualTo(storeBeforeConflict);
        var unchanged = FilesystemDeploymentAdmissionAuthority.observeExistingStore(
                full.fixture().stateRoot(), descriptor,
                CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                fingerprint('8'));
        assertThat(unchanged.leaseCount()).isEqualTo(1);
        assertThat(unchanged.generation()).isEqualTo(1);
    }

    @Test
    void packagedCliClassifiesAndPreservesWrongOwnerPartAndManifest() throws Exception {
        assertPackagedInvalidArtifact("typed-owner", "OWNER_SOURCE_FORCED", null);
        assertPackagedInvalidArtifact("typed-part", "BEFORE_DURABLE",
                ".committed-transcript-v1.json.part");
        assertPackagedInvalidArtifact("typed-manifest", "COMMITTED_DURABLE",
                ".commit-manifest-v1.json.part");
    }

    @Test
    void packagedCliClassifiesPermissionAndStateIoAsUnavailable() throws Exception {
        var permission = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "typed-permission");
        configure(permission.fixture());
        String permissionOuter = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalMaterialDeclaration().formalOuterFingerprint();
        Path permissionParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("typed-permission-output"));
        var publication = provisionPublication(permissionParent);
        Files.setPosixFilePermissions(permissionParent,
                java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        Closure permissionBefore = closure(permissionParent);
        Path permissionOutput = temporaryDirectory.resolve("typed-permission.out");
        Process permissionChild = evidenceProcessWithPublication(permission,
                permissionParent.resolve(
                        CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE),
                permissionOuter, null,
                permissionOutput, publication.publicationFingerprint());
        awaitChild(permissionChild, permissionOutput,
                com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(Files.readString(permissionOutput)).isEqualTo(
                "NOT_ACCEPTED outcome=BLOCKED reasonCode="
                        + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI."
                        + "PUBLICATION_UNAVAILABLE\n");
        assertThat(closure(permissionParent)).isEqualTo(permissionBefore);
        Files.setPosixFilePermissions(permissionParent,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));

        var io = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "typed-io");
        configure(io.fixture());
        String ioOuter = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalMaterialDeclaration().formalOuterFingerprint();
        Path ioParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("typed-io-output"));
        var ioPublication = provisionPublication(ioParent);
        Path movedState = io.fixture().stateRoot().resolveSibling("typed-io-state-moved");
        Files.move(io.fixture().stateRoot(), movedState);
        Closure ioBefore = closure(ioParent);
        Path ioOutput = temporaryDirectory.resolve("typed-io.out");
        Process ioChild = evidenceProcessWithPublication(io,
                ioParent.resolve(
                        CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE),
                ioOuter, null, ioOutput,
                ioPublication.publicationFingerprint());
        awaitChild(ioChild, ioOutput,
                com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
        assertThat(Files.readString(ioOutput))
                .isEqualTo("NOT_ACCEPTED outcome=BLOCKED reasonCode="
                        + "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI."
                        + "FORMAL_TARGET_BINDING_UNAVAILABLE\n")
                .doesNotContain(movedState.toString(), "PROVIDER_PATH_SECRET");
        assertThat(closure(ioParent)).isEqualTo(ioBefore);
    }

    @Test
    void packagedWrapperFileLockTimesOutAndProcessDeathReleasesIt() throws Exception {
        var full = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "packaged-lock-timeout");
        configure(full.fixture());
        String outer = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalMaterialDeclaration().formalOuterFingerprint();
        Path evidenceParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("packaged-lock-output"));
        provisionPublication(evidenceParent);
        Path transcript = evidenceParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        Path lock = evidenceParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE);
        Path holderOutput = temporaryDirectory.resolve("packaged-lock-holder.out");
        Process holder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", shadedWorkerClasspath(),
                MountedEvidencePublicationLockWorker.class.getName(), lock.toString())
                .redirectErrorStream(true).redirectOutput(holderOutput.toFile()).start();
        try {
            long readyUntil = System.nanoTime()
                    + java.time.Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < readyUntil
                    && (!Files.exists(holderOutput)
                    || !Files.readString(holderOutput).contains("READY"))) {
                Thread.sleep(20);
            }
            assertThat(Files.readString(holderOutput)).isEqualTo("READY\n");

            Path blockedOutput = temporaryDirectory.resolve("packaged-lock-blocked.out");
            long started = System.nanoTime();
            Process blocked = evidenceProcess(full, transcript, outer, null, blockedOutput);
            awaitChild(blocked, blockedOutput,
                    com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceCli.EXIT_NOT_ACCEPTED);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofSeconds(9));
            assertThat(Files.readString(blockedOutput))
                    .isEqualTo("NOT_ACCEPTED outcome=BLOCKED reasonCode="
                            + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI."
                            + "PUBLICATION_UNAVAILABLE\n")
                    .doesNotContain(lock.toString());
            assertThat(transcript).doesNotExist();
        } finally {
            holder.destroy();
            if (!holder.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                holder.destroyForcibly();
                assertThat(holder.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }

        Path recoveredOutput = temporaryDirectory.resolve("packaged-lock-recovered.out");
        Process recovered = evidenceProcess(
                full, transcript, outer, null, recoveredOutput);
        awaitChild(recovered, recoveredOutput, 0);
        assertThat(Files.readString(recoveredOutput))
                .contains("commitStatus=COMMITTED");
    }

    private void assertPackagedInvalidArtifact(
            String name, String crashPoint, String artifactName) throws Exception {
        var full = MountedProviderTestFixtures.writeFullEvidence(temporaryDirectory, name);
        configure(full.fixture());
        String outer = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalMaterialDeclaration().formalOuterFingerprint();
        Path parent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve(name + "-output"));
        Path transcript = parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        Path crashOutput = temporaryDirectory.resolve(name + "-crash.out");
        awaitChild(evidenceProcess(full, transcript, outer, crashPoint, crashOutput),
                crashOutput, 86);

        Path observedRoot;
        Path target;
        if (artifactName == null) {
            observedRoot = parent;
            try (var children = Files.list(parent)) {
                target = children.filter(path -> path.getFileName().toString()
                                .endsWith(".owner-claim-v3.json"))
                        .findFirst().orElseThrow();
            }
            Files.setPosixFilePermissions(target,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } else {
            observedRoot = singleEvidenceWrapper(parent);
            target = observedRoot.resolve(artifactName);
        }
        Files.writeString(target, "UPPERCASE_CREDENTIAL_PAYLOAD", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(target,
                java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
        Closure before = closure(observedRoot);

        Path retryOutput = temporaryDirectory.resolve(name + "-retry.out");
        awaitChild(evidenceProcess(full, transcript, outer, null, retryOutput),
                retryOutput, 2);
        assertThat(Files.readString(retryOutput))
                .isEqualTo("INVALID errorCode="
                        + "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_EVIDENCE_CLI."
                        + "RECOVERY_INVALID\n")
                .doesNotContain("UPPERCASE_CREDENTIAL_PAYLOAD", target.toString());
        assertThat(closure(observedRoot)).isEqualTo(before);
    }

    private static Process evidenceProcess(
            MountedProviderTestFixtures.FullEvidenceFixture full,
            Path transcript,
            String outer,
            String crashPoint,
            Path output) throws IOException {
        var publication = provisionPublication(transcript.getParent());
        return evidenceProcessWithPublication(full, transcript, outer, crashPoint, output,
                publication.publicationFingerprint());
    }

    private static Process evidenceProcessWithPublication(
            MountedProviderTestFixtures.FullEvidenceFixture full,
            Path transcript,
            String outer,
            String crashPoint,
            Path output,
            String publicationFingerprint) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "="
                        + full.fixture().stateRoot(),
                "-cp", crashWorkerClasspath(),
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceCrashHarness",
                crashPoint == null ? "NO_CRASH" : crashPoint,
                full.stageResult().toString(), transcript.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", outer);
        builder.environment().put(
                CapabilityStudioExecutionLeaseEvidencePublication
                        .EXPECTED_PUBLICATION_FINGERPRINT_ENV,
                publicationFingerprint);
        return builder.start();
    }

    private static Process productionEvidenceProcess(
            MountedProviderTestFixtures.FullEvidenceFixture full,
            Path transcript,
            String outer,
            Path output,
            String publicationFingerprint) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "="
                        + full.fixture().stateRoot(),
                "-cp", productionWorkerClasspath(),
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceCli",
                full.stageResult().toString(), transcript.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", outer);
        builder.environment().put(
                CapabilityStudioExecutionLeaseEvidencePublication
                        .EXPECTED_PUBLICATION_FINGERPRINT_ENV,
                publicationFingerprint);
        return builder.start();
    }

    private static Process evidenceBarrierProcess(
            MountedProviderTestFixtures.FullEvidenceFixture full,
            Path transcript,
            String outer,
            Path output,
            String publicationFingerprint,
            Path ready,
            Path barrier,
            Path markerDurabilityBarrier) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "="
                        + full.fixture().stateRoot(),
                "-cp", crashWorkerClasspath(),
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceCrashHarness",
                "HOLD_OWNER_DURABLE", full.stageResult().toString(),
                transcript.toString(), ready.toString(), barrier.toString(),
                markerDurabilityBarrier.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", outer);
        builder.environment().put(
                CapabilityStudioExecutionLeaseEvidencePublication
                        .EXPECTED_PUBLICATION_FINGERPRINT_ENV,
                publicationFingerprint);
        return builder.start();
    }

    private static Process evidenceLockMissProcess(
            MountedProviderTestFixtures.FullEvidenceFixture full,
            Path transcript,
            String outer,
            Path output,
            String publicationFingerprint,
            Path lockMiss,
            Path markerDurabilityBarrier) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "="
                        + full.fixture().targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "="
                        + full.fixture().stateRoot(),
                "-cp", crashWorkerClasspath(),
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceCrashHarness",
                "OBSERVE_PUBLICATION_FILE_LOCK_MISS", full.stageResult().toString(),
                transcript.toString(), lockMiss.toString(),
                markerDurabilityBarrier.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("BLOGE_EXPECTED_AUTHORITY_BINDING_FINGERPRINT", outer);
        builder.environment().put(
                CapabilityStudioExecutionLeaseEvidencePublication
                        .EXPECTED_PUBLICATION_FINGERPRINT_ENV,
                publicationFingerprint);
        return builder.start();
    }

    private static void awaitDurableMarkerAfterPausedParentForce(
            Path marker,
            String expected,
            Process child,
            Path output,
            FileLock parentForceBarrier) throws Exception {
        Path ack = marker.resolveSibling(marker.getFileName() + ".ack");
        var waitingForAck = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var visibleWatcher = marker.getFileSystem().newWatchService();
             var ackWatcher = marker.getFileSystem().newWatchService()) {
            marker.getParent().register(visibleWatcher,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
            marker.getParent().register(ackWatcher,
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
            awaitVisibleMarker(visibleWatcher, marker, child, output);
            assertThat(Files.readString(marker)).isEqualTo(expected);
            assertThat(ack).doesNotExist();
            assertThat(child.isAlive()).isTrue();
            assertThat(Files.readString(output)).isEmpty();

            var awaiting = executor.submit(() -> {
                try {
                    awaitHarnessBarrier(ackWatcher, marker, expected, child, output,
                            waitingForAck::countDown);
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            });
            assertThat(waitingForAck.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(awaiting.isDone()).isFalse();
            assertThat(ack).doesNotExist();
            assertThat(child.isAlive()).isTrue();
            assertThat(Files.readString(output)).isEmpty();

            parentForceBarrier.release();
            awaiting.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(Files.readString(ack)).isEqualTo("ACK\n");
            assertThat(Files.readString(marker)).isEqualTo(expected);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(
                    2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitVisibleMarker(
            java.nio.file.WatchService watcher, Path marker, Process child, Path output)
            throws Exception {
        long remaining = java.time.Duration.ofSeconds(10).toNanos();
        long last = System.nanoTime();
        while (remaining > 0 && child.isAlive()) {
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            var key = watcher.poll(Math.min(remaining,
                            java.time.Duration.ofMillis(50).toNanos()),
                    java.util.concurrent.TimeUnit.NANOSECONDS);
            long current = System.nanoTime();
            long elapsed = current - last;
            remaining = elapsed < 0 || elapsed >= remaining ? 0 : remaining - elapsed;
            last = current;
            if (key == null) {
                continue;
            }
            key.pollEvents();
            if (!key.reset()) {
                break;
            }
        }
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        assertThat(child.isAlive())
                .as("child output: %s", Files.exists(output) ? Files.readString(output) : "")
                .isTrue();
        assertThat(marker)
                .as("child output: %s", Files.exists(output) ? Files.readString(output) : "")
                .exists();
    }

    private static void awaitHarnessBarrier(
            java.nio.file.WatchService watcher,
            Path marker,
            String expected,
            Process child,
            Path output,
            Runnable markerVisibleWithoutAck) throws Exception {
        Path ack = marker.resolveSibling(marker.getFileName() + ".ack");
        long remaining = java.time.Duration.ofSeconds(10).toNanos();
        long last = System.nanoTime();
        boolean markerObserved = false;
        while (remaining > 0 && child.isAlive()) {
            if (Files.exists(ack, LinkOption.NOFOLLOW_LINKS)) {
                assertThat(Files.readString(ack)).isEqualTo("ACK\n");
                assertThat(Files.readString(marker)).isEqualTo(expected);
                return;
            }
            if (!markerObserved && Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                markerVisibleWithoutAck.run();
                markerObserved = true;
            }
            var key = watcher.poll(Math.min(remaining,
                            java.time.Duration.ofMillis(50).toNanos()),
                    java.util.concurrent.TimeUnit.NANOSECONDS);
            long current = System.nanoTime();
            long elapsed = current - last;
            remaining = elapsed < 0 || elapsed >= remaining ? 0 : remaining - elapsed;
            last = current;
            if (key == null) {
                continue;
            }
            key.pollEvents();
            if (!key.reset()) {
                break;
            }
        }
        if (Files.exists(ack, LinkOption.NOFOLLOW_LINKS)) {
            assertThat(Files.readString(ack)).isEqualTo("ACK\n");
            assertThat(Files.readString(marker)).isEqualTo(expected);
            return;
        }
        assertThat(child.isAlive())
                .as("child output: %s", Files.exists(output) ? Files.readString(output) : "")
                .isTrue();
        assertThat(ack)
                .as("child output: %s", Files.exists(output) ? Files.readString(output) : "")
                .exists();
    }

    private static Map<String, String> acceptedFields(String line) {
        Map<String, String> fields = new TreeMap<>();
        for (String token : line.strip().split(" ")) {
            int separator = token.indexOf('=');
            if (separator > 0) {
                fields.put(token.substring(0, separator), token.substring(separator + 1));
            }
        }
        return Map.copyOf(fields);
    }

    private static void reap(Process child) throws Exception {
        if (child == null || !child.isAlive()) {
            return;
        }
        child.destroy();
        if (!child.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly();
            assertThat(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static CapabilityStudioExecutionLeaseEvidencePublication.Declaration
            provisionPublication(Path parent) {
        return CapabilityStudioExecutionLeaseEvidencePublication.provision(
                parent, fingerprint('9'));
    }

    private static Path singleEvidenceWrapper(Path parent) throws IOException {
        try (var children = Files.list(parent)) {
            List<Path> wrappers = children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith(".evidence-v3"))
                    .toList();
            assertThat(wrappers).hasSize(1);
            return wrappers.getFirst();
        }
    }

    private static Process bundleVerifierProcess(
            Path transcript,
            String stageRaw,
            String outer,
            Path output) throws IOException {
        String publicationFingerprint = provisionPublication(transcript.getParent())
                .publicationFingerprint();
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", productionWorkerClasspath(),
                "com.leanowtech.bloge.gateway.testkit."
                        + "CapabilityStudioExecutionLeaseEvidenceBundleVerifyCli",
                "--transcript", transcript.toString(),
                "--expected-stage-result-raw-fingerprint", stageRaw,
                "--expected-formal-outer-fingerprint", outer,
                "--expected-publication-fingerprint", publicationFingerprint)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private static void configure(MountedProviderTestFixtures.Fixture fixture) {
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, fixture.authorityRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, fixture.targetRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY, fixture.stateRoot().toString());
    }

    private static void awaitChild(Process child, Path output, int expectedExit)
            throws Exception {
        try {
            assertThat(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as(Files.readString(output)).isEqualTo(expectedExit);
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    assertThat(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }

    private static void awaitScaleChild(Process child, Path output) throws Exception {
        try {
            assertThat(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
                    .as("scale child timed out: %s", Files.readString(output)).isTrue();
            assertThat(child.exitValue()).as(Files.readString(output)).isZero();
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    assertThat(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }

    private static void awaitOwnerClaimOrExit(Path publicationParent, Process child)
            throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (child.isAlive() && System.nanoTime() < deadline) {
            try (var children = Files.list(publicationParent)) {
                if (children.anyMatch(path -> path.getFileName().toString()
                        .endsWith(".owner-claim-v3.json"))) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        assertThat(child.isAlive() || Files.exists(publicationParent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE))).isTrue();
    }

    private Path symbolicAlias(Path target) throws Exception {
        Path alias = temporaryDirectory.resolve("state-alias").toAbsolutePath();
        Files.createSymbolicLink(alias, target);
        return alias;
    }

    private String runRecoveryWorker(Context context, String name) throws Exception {
        return runRecoveryWorker(context, name, context.fixture.stateRoot());
    }

    private String runRecoveryWorker(Context context, String name, Path stateRoot)
            throws Exception {
        Path requestFile = temporaryDirectory.resolve(name + "-request.json");
        Files.write(requestFile, requestJson(context.request).toString()
                .getBytes(StandardCharsets.UTF_8));
        Path childOutput = temporaryDirectory.resolve(name + "-recovery.out");
        String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", "java").toString();
        String classpath = shadedWorkerClasspath();
        Process child = new ProcessBuilder(javaExecutable,
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "=" + context.fixture.authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "=" + context.fixture.targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "=" + stateRoot,
                "-cp", classpath, MountedEvidenceRecoveryWorker.class.getName(),
                requestFile.toString(), context.evidenceParent.toString())
                .redirectErrorStream(true)
                .redirectOutput(childOutput.toFile())
                .start();
        try {
            assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as(Files.readString(childOutput)).isZero();
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    assertThat(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
            }
        }
        return Files.readString(childOutput);
    }

    private void runCommitCrashWorker(Context context) throws Exception {
        Path requestFile = temporaryDirectory.resolve("commit-halt-request.json");
        Files.write(requestFile, requestJson(context.request).toString()
                .getBytes(StandardCharsets.UTF_8));
        Path childOutput = temporaryDirectory.resolve("commit-halt.out");
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .AUTHORITY_BUNDLE_ROOT_PROPERTY + "=" + context.fixture.authorityRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY + "=" + context.fixture.targetRoot(),
                "-D" + MountedCapabilityStudioStageAcceptanceAuthorityProvider
                        .EXECUTION_LEASE_STATE_ROOT_PROPERTY + "=" + context.fixture.stateRoot(),
                "-cp", shadedWorkerClasspath(),
                MountedEvidenceCommitCrashWorker.class.getName(), requestFile.toString(),
                context.evidenceParent.toString())
                .redirectErrorStream(true)
                .redirectOutput(childOutput.toFile())
                .start();
        try {
            assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).as(Files.readString(childOutput)).isEqualTo(77);
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    assertThat(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }

    private static String shadedWorkerClasspath() {
        return workerArtifacts().stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(
                        System.getProperty("path.separator")));
    }

    private static String crashWorkerClasspath() {
        List<Path> artifacts = workerArtifacts();
        return java.util.stream.Stream.of(
                        artifacts.get(2), artifacts.get(0), artifacts.get(1))
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(
                        System.getProperty("path.separator")));
    }

    private static String productionWorkerClasspath() {
        List<Path> artifacts = workerArtifacts();
        return java.util.stream.Stream.of(artifacts.get(0), artifacts.get(1))
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(
                        System.getProperty("path.separator")));
    }

    private static List<Path> workerArtifacts() {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path shaded = project.resolve("target/resolved-test-kit/"
                + "bloge-resource-gateway-test-kit-1.0.0-cli.jar").normalize()
                .toAbsolutePath();
        assertThat(shaded).isRegularFile();
        Path provider = project.resolve("target/"
                + "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                + "runtime-under-test.jar").toAbsolutePath().normalize();
        Path harness = project.resolve("target/"
                + "bloge-capability-studio-mounted-authority-provider-1.0.0-"
                + "child-harness.jar").toAbsolutePath().normalize();
        assertThat(provider).isRegularFile();
        assertThat(harness).isRegularFile();
        return List.of(shaded, provider, harness);
    }

    private static String rawFingerprint(Path path) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static List<String> crashPointsFromFrozenManifest() throws Exception {
        Path harness = workerArtifacts().get(2);
        try (JarFile jar = new JarFile(harness.toFile())) {
            var manifest = StrictCrashInstrumentationManifest.read(jar);
            assertThat(manifest.path("messageVersion").asText())
                    .isEqualTo("bloge.test-only.crash-instrumentation.v3");
            assertThat(manifest.path("pointCount").intValue()).isEqualTo(17);
            List<String> points = new java.util.ArrayList<>();
            manifest.path("points").forEach(point ->
                    points.add(point.path("point").asText()));
            return List.copyOf(points);
        }
    }

    private void assertPackagedCrashRecovery(int index, String point) throws Exception {
        var full = MountedProviderTestFixtures.writeFullEvidence(
                temporaryDirectory, "contract-cp-" + index);
        configure(full.fixture());
        String outer = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalMaterialDeclaration().formalOuterFingerprint();
        Path parent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve("contract-output-" + index));
        Path transcript = parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        var publication = provisionPublication(parent);
        Path wrapper = evidenceWrapper(parent, publication);

        Path crashOutput = temporaryDirectory.resolve("contract-crash-" + index + ".out");
        awaitChild(evidenceProcessWithPublication(full, transcript, outer, point,
                crashOutput, publication.publicationFingerprint()), crashOutput, 86);
        assertThat(Files.readString(crashOutput)).as(point).doesNotContain("ACCEPTED");
        assertCrashStoreInventory(full.fixture().stateRoot(), index);
        assertCrashPublicationInventory(parent, wrapper, publication, index);

        byte[] committedBefore = index >= 9
                ? Files.readAllBytes(wrapper.resolve(index == 9
                ? ".committed-transcript-v1.json.part"
                : "committed-transcript-v1.json")) : null;
        byte[] manifestBefore = index >= 11
                ? Files.readAllBytes(wrapper.resolve("commit-manifest-v1.json")) : null;
        byte[] finalCommitBefore = index >= 12
                ? Files.readAllBytes(wrapper.resolve(index == 12
                ? ".final-commit-v1.json.part" : "final-commit-v1.json")) : null;

        Path retryOutput = temporaryDirectory.resolve("contract-retry-" + index + ".out");
        awaitChild(productionEvidenceProcess(full, transcript, outer, retryOutput,
                publication.publicationFingerprint()), retryOutput, 0);
        String line = Files.readString(retryOutput);
        String expectedStatus = index < 7 ? "COMMITTED" : "RECOVERED";
        assertThat(line).as(point)
                .contains("commitStatus=" + expectedStatus)
                .contains("evidencePublicationStatus=" + expectedStatus)
                .endsWith("EXECUTION_LEASE_EVIDENCE_CLI.ACCEPTED\n");
        assertCommittedStoreInventory(full.fixture().stateRoot());
        assertFinalPublicationInventory(parent, wrapper, publication,
                index >= 4 && index < 7);

        var transcriptValue = com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioExecutionLeaseTranscript.verify(
                Files.readAllBytes(transcript));
        if (committedBefore != null) {
            var committedValue = com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioExecutionLeaseTranscript.verify(committedBefore);
            assertThat(transcriptValue.executionLeaseReceipt()).as(point)
                    .isEqualTo(committedValue.executionLeaseReceipt());
            assertThat(transcriptValue.executionLeaseTransitionWitness()).as(point)
                    .isEqualTo(committedValue.executionLeaseTransitionWitness());
            assertThat(transcriptValue.bytes()).as(point).isEqualTo(committedValue.bytes());
        }
        if (manifestBefore != null) {
            assertThat(Files.readAllBytes(wrapper.resolve("commit-manifest-v1.json")))
                    .as(point).isEqualTo(manifestBefore);
        }
        if (finalCommitBefore != null) {
            assertThat(Files.readAllBytes(wrapper.resolve("final-commit-v1.json")))
                    .as(point).isEqualTo(finalCommitBefore);
        }

        configure(full.fixture());
        var recovery = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();
        var attempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(transcriptValue.executionLeaseRequest(),
                transcriptValue.evidenceTransactionId(),
                transcriptValue.semanticVerificationTime(), parent);
        var exact = recovery.recovery().recoverExisting(attempt, ignored -> { });
        assertThat(exact.status()).as(point).isEqualTo(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.FOUND);
        assertThat(exact.receipt()).as(point)
                .isEqualTo(transcriptValue.executionLeaseReceipt());
        assertThat(exact.transitionWitness()).as(point)
                .isEqualTo(transcriptValue.executionLeaseTransitionWitness());

        Path verifyOutput = temporaryDirectory.resolve("contract-verify-" + index + ".out");
        awaitChild(bundleVerifierProcess(transcript, rawFingerprint(full.stageResult()),
                outer, verifyOutput), verifyOutput, 0);
        assertThat(Files.readString(verifyOutput)).as(point).startsWith(
                "VERIFIED status=VERIFIED verificationScope=DURABLE_WRAPPER ");
        assertFinalPublicationInventory(parent, wrapper, publication,
                index >= 4 && index < 7);
    }

    private static Path evidenceWrapper(
            Path parent,
            CapabilityStudioExecutionLeaseEvidencePublication.Declaration publication) {
        return parent.resolve("." + CapabilityStudioExecutionLeaseEvidencePublication
                .TRANSCRIPT_FILE + "." + publication.evidenceTransactionId()
                .substring("sha256:".length()) + ".evidence-v3");
    }

    private static void assertCrashPublicationInventory(
            Path parent,
            Path wrapper,
            CapabilityStudioExecutionLeaseEvidencePublication.Declaration publication,
            int point) throws Exception {
        String prefix = "." + CapabilityStudioExecutionLeaseEvidencePublication
                .TRANSCRIPT_FILE + "." + publication.evidenceTransactionId()
                .substring("sha256:".length());
        Set<String> parentNames = new java.util.HashSet<>(Set.of(
                CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE,
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE,
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_DECLARATION_FILE));
        if (point >= 1) {
            parentNames.add(prefix + ".owner-claim-v3.json");
        }
        if (point >= 2) {
            parentNames.add(prefix + ".evidence-v3");
        }
        if (point >= 15) {
            parentNames.add(CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        }
        assertThat(closure(parent).entries().keySet()).isEqualTo(parentNames);
        assertFile(parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE),
                0400, 1);
        assertFile(parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE),
                0600, 1);
        assertFile(parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_DECLARATION_FILE),
                0400, 1);
        if (point >= 1) {
            assertFile(parent.resolve(prefix + ".owner-claim-v3.json"), 0400,
                    point >= 3 ? 2 : 1);
        }
        if (point < 2) {
            assertThat(wrapper).doesNotExist();
            return;
        }
        Set<String> wrapperNames = new java.util.HashSet<>();
        if (point >= 3) {
            wrapperNames.add("owner-v3.json");
        }
        if (point == 4) {
            wrapperNames.add(".before-v2-g00000000000000000001.json.part");
        } else if (point >= 5) {
            wrapperNames.add("before-v2-g00000000000000000001.json");
        }
        if (point == 9) {
            wrapperNames.add(".committed-transcript-v1.json.part");
        } else if (point >= 10) {
            wrapperNames.add("committed-transcript-v1.json");
        }
        if (point >= 11) {
            wrapperNames.add("commit-manifest-v1.json");
        }
        if (point == 12 || point == 13) {
            wrapperNames.add(".final-commit-v1.json.part");
        }
        if (point >= 13) {
            wrapperNames.add("final-commit-v1.json");
        }
        assertThat(closure(wrapper).entries().keySet()).isEqualTo(wrapperNames);
        for (String name : wrapperNames) {
            long links = point == 13 && (name.equals(".final-commit-v1.json.part")
                    || name.equals("final-commit-v1.json")) ? 2
                    : point >= 15 && name.equals("committed-transcript-v1.json") ? 2 : 1;
            if (name.equals("owner-v3.json")) {
                links = 2;
            }
            assertFile(wrapper.resolve(name), 0400, links);
        }
        if (point >= 15) {
            assertFile(parent.resolve(
                    CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE),
                    0400, 2);
        }
    }

    private static void assertFinalPublicationInventory(
            Path parent,
            Path wrapper,
            CapabilityStudioExecutionLeaseEvidencePublication.Declaration publication,
            boolean closedAttempt) throws Exception {
        String prefix = "." + CapabilityStudioExecutionLeaseEvidencePublication
                .TRANSCRIPT_FILE + "." + publication.evidenceTransactionId()
                .substring("sha256:".length());
        assertThat(closure(parent).entries().keySet()).isEqualTo(Set.of(
                CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE,
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE,
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_DECLARATION_FILE,
                prefix + ".owner-claim-v3.json", prefix + ".evidence-v3",
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE));
        Set<String> wrapperNames = new java.util.HashSet<>(Set.of(
                "owner-v3.json", "committed-transcript-v1.json",
                "commit-manifest-v1.json", "final-commit-v1.json"));
        if (closedAttempt) {
            wrapperNames.add("before-v2-g00000000000000000001.json");
            wrapperNames.add("attempt-closure-v1-g00000000000000000001.json");
            wrapperNames.add("before-v2-g00000000000000000002.json");
        } else {
            wrapperNames.add("before-v2-g00000000000000000001.json");
        }
        assertThat(closure(wrapper).entries().keySet()).isEqualTo(wrapperNames);
        assertFile(parent.resolve(prefix + ".owner-claim-v3.json"), 0400, 2);
        assertFile(wrapper.resolve("owner-v3.json"), 0400, 2);
        assertFile(wrapper.resolve("committed-transcript-v1.json"), 0400, 2);
        assertFile(parent.resolve(
                CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE),
                0400, 2);
        for (String name : wrapperNames) {
            if (!name.equals("owner-v3.json")
                    && !name.equals("committed-transcript-v1.json")) {
                assertFile(wrapper.resolve(name), 0400, 1);
            }
        }
    }

    private static void assertFile(Path path, int mode, long links) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(attributes.isRegularFile()).as(path.getFileName().toString()).isTrue();
        assertThat(((Number) Files.getAttribute(path, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue()).as(path.getFileName().toString())
                .isEqualTo(links);
        assertThat(((Number) Files.getAttribute(path, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777)
                .as(path.getFileName().toString()).isEqualTo(mode);
    }

    private static void assertEvidencePublicationInventory(
            Path parent,
            Path wrapper,
            CapabilityStudioExecutionLeaseEvidencePublication.Declaration publication,
            boolean committed) throws Exception {
        String prefix = "." + CapabilityStudioExecutionLeaseEvidencePublication
                .TRANSCRIPT_FILE + "." + publication.evidenceTransactionId()
                .substring("sha256:".length());
        Set<String> expectedParent = new java.util.HashSet<>(Set.of(
                CapabilityStudioExecutionLeaseEvidencePublication.OWNER_BOOTSTRAP_FILE,
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_LOCK_FILE,
                CapabilityStudioExecutionLeaseEvidencePublication.PUBLICATION_DECLARATION_FILE,
                prefix + ".owner-claim-v3.json",
                prefix + ".evidence-v3"));
        if (committed) {
            expectedParent.add(CapabilityStudioExecutionLeaseEvidencePublication.TRANSCRIPT_FILE);
        }
        try (var children = Files.list(parent)) {
            assertThat(children.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrderElementsOf(expectedParent);
        }
        Set<String> expectedWrapper = new java.util.HashSet<>(Set.of(
                "owner-v3.json", "before-v2-g00000000000000000001.json",
                "committed-transcript-v1.json", "commit-manifest-v1.json"));
        if (committed) {
            expectedWrapper.add("final-commit-v1.json");
        }
        try (var children = Files.list(wrapper)) {
            assertThat(children.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrderElementsOf(expectedWrapper);
        }
    }

    private static void assertPrivateRegular(Path path, long links) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(attributes.isRegularFile()).isTrue();
        assertThat(((Number) Files.getAttribute(path, "unix:nlink",
                LinkOption.NOFOLLOW_LINKS)).longValue()).isEqualTo(links);
        assertThat(((Number) Files.getAttribute(path, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777).isEqualTo(0400);
    }

    private static void assertCrashStoreInventory(Path root, int point) throws Exception {
        Closure observed = closure(root);
        Set<String> base = Set.of(
                FilesystemDeploymentAdmissionAuthority.LOCK_FILE,
                FilesystemDeploymentAdmissionAuthority.STATE_FILE,
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE,
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE);
        Set<String> expected = new java.util.HashSet<>(base);
        String transition = FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_PREFIX
                + "00000000000000000001"
                + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_SUFFIX;
        if (point == 7 || point == 8) {
            expected.add("." + transition + ".tmp");
        } else if (point > 8) {
            expected.add(transition);
        }
        assertThat(observed.entries().keySet()).isEqualTo(expected);
        assertThat(observed.entries().values()).allSatisfy(entry -> {
            assertThat(entry.regular()).isTrue();
            assertThat(entry.links()).isEqualTo(1);
            assertThat(entry.mode()).isEqualTo(0600);
        });
        var state = MountedProviderTestFixtures.JSON.readTree(
                observed.entries().get(FilesystemDeploymentAdmissionAuthority.STATE_FILE)
                        .bytes());
        var checkpoint = MountedProviderTestFixtures.JSON.readTree(
                observed.entries().get(FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE)
                        .bytes());
        long expectedStateGeneration = point < 7 ? 0 : 1;
        long expectedCheckpointGeneration = point < 8 ? 0 : 1;
        assertThat(state.path("leases").size()).isEqualTo(point < 7 ? 0 : 1);
        assertThat(state.path("generation").longValue()).isEqualTo(expectedStateGeneration);
        assertThat(state.path("fencingSequence").longValue())
                .isEqualTo(expectedStateGeneration);
        assertThat(checkpoint.path("generation").longValue())
                .isEqualTo(expectedCheckpointGeneration);
    }

    private static void assertCommittedStoreInventory(Path root) throws Exception {
        Closure observed = closure(root);
        String transition = FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_PREFIX
                + "00000000000000000001"
                + FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_SUFFIX;
        assertThat(observed.entries().keySet()).isEqualTo(Set.of(
                FilesystemDeploymentAdmissionAuthority.LOCK_FILE,
                FilesystemDeploymentAdmissionAuthority.STATE_FILE,
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE,
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE,
                transition));
        var state = MountedProviderTestFixtures.JSON.readTree(
                observed.entries().get(FilesystemDeploymentAdmissionAuthority.STATE_FILE)
                        .bytes());
        assertThat(state.path("leases")).hasSize(1);
        assertThat(state.path("generation").longValue()).isEqualTo(1);
        assertThat(state.path("fencingSequence").longValue()).isEqualTo(1);
    }

    private Context context(String name) throws Exception {
        var fixture = MountedProviderTestFixtures.write(temporaryDirectory, name);
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .AUTHORITY_BUNDLE_ROOT_PROPERTY, fixture.authorityRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .TARGET_ADMISSION_BUNDLE_ROOT_PROPERTY, fixture.targetRoot().toString());
        System.setProperty(MountedCapabilityStudioStageAcceptanceAuthorityProvider
                .EXECUTION_LEASE_STATE_ROOT_PROPERTY, fixture.stateRoot().toString());
        var provider = new MountedCapabilityStudioStageAcceptanceAuthorityProvider();
        var evidence = provider.formalEvidenceAuthorityBinding();
        assertThat(evidence).isNotNull();
        var formal = evidence.formalBinding();
        var target = formal.targetAdmissionBinding();
        var deployment = target.deploymentAuthorityBinding();
        var trusted = deployment.trustedClock().verificationTime();
        ExecutionLeaseRequest request = new ExecutionLeaseRequest(
                "result:" + name, 1, fingerprint('1'), fingerprint('2'),
                "contract:test", "revision:1",
                target.verificationContext().executionLeaseId(), formal.fingerprint(),
                target.targetRawFingerprint(), target.targetCanonicalFingerprint(),
                target.lifecycleMaterial(), deployment.fingerprint(), trusted);
        String descriptor = provider.formalMaterialDeclaration().storeDescriptorFingerprint();
        Path evidenceParent = MountedProviderTestFixtures.privateDirectory(
                temporaryDirectory.toRealPath().resolve(name + "-transaction"));
        return new Context(fixture, evidence, request, descriptor, evidenceParent);
    }

    private void assertRecoveryJournalFailure(
            String name, boolean invalid, boolean io) throws Exception {
        Context context = context(name);
        Closure before = closure(context.fixture.stateRoot());
        var binding = new MountedCapabilityStudioStageAcceptanceAuthorityProvider()
                .formalEvidenceRecoveryBinding();
        var attempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(context.request, fingerprint('8'),
                context.request.trustedVerificationTime(), context.evidenceParent);
        com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryJournal journal;
        if (invalid) {
            journal = new com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .ExistingEvidenceRecoveryJournal() {
                @Override
                public void closeAbsent(
                        com.leanowtech.bloge.gateway.testkit
                                .CapabilityStudioStageAcceptanceAuthorityProvider
                                .EvidenceExecutionLeaseAttempt ignored) {
                    throw new AssertionError("legacy callback must not run");
                }

                @Override
                public com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceJournalResult<Void> closeAbsentResult(
                        com.leanowtech.bloge.gateway.testkit
                                .CapabilityStudioStageAcceptanceAuthorityProvider
                                .EvidenceExecutionLeaseAttempt ignored) {
                    return com.leanowtech.bloge.gateway.testkit
                            .CapabilityStudioStageAcceptanceAuthorityProvider
                            .EvidenceJournalResult.invalid();
                }
            };
        } else if (io) {
            journal = ignored -> {
                throw new java.io.UncheckedIOException(
                        new IOException("RECOVERY_PROVIDER_PATH_SECRET"));
            };
        } else {
            journal = ignored -> {
                throw new IllegalStateException("RECOVERY_CREDENTIAL_PAYLOAD");
            };
        }
        var result = binding.recovery().recoverExisting(attempt, journal);
        assertThat(result.failureKind()).contains(invalid
                ? com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceFailureKind.INVALID
                : com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceFailureKind.UNAVAILABLE);
        assertThat(result.toString()).doesNotContain(
                "RECOVERY_PROVIDER_PATH_SECRET", "RECOVERY_CREDENTIAL_PAYLOAD");
        assertThat(closure(context.fixture.stateRoot())).isEqualTo(before);
    }

    private static com.leanowtech.bloge.gateway.testkit
            .CapabilityStudioStageAcceptanceAuthorityProvider
            .EvidenceExecutionLeaseTransactionResult commit(
            Context context,
            String transactionId,
            InMemoryJournal journal) {
        var attempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(context.request, transactionId,
                context.request.trustedVerificationTime(), context.evidenceParent);
        return context.evidence.transactionAuthority().commit(attempt, journal);
    }

    private static com.leanowtech.bloge.gateway.testkit
            .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseAttempt
    attempt(
            Context context,
            Path evidenceParent,
            com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceLeaseBudget budget) {
        return new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(context.request, fingerprint('8'),
                context.request.trustedVerificationTime(), evidenceParent, 1, null, budget);
    }

    private static com.leanowtech.bloge.gateway.testkit
            .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseAttempt
    capacityAttempt(
            EvidenceCapacityStoreFixtureBuilder.CapacityFixture capacity,
            Context context) {
        return new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(capacity.recoverableRequest(),
                capacity.evidenceTransactionId(),
                capacity.recoverableRequest().trustedVerificationTime(),
                context.evidenceParent);
    }

    private static com.leanowtech.bloge.gateway.testkit
            .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseAttempt
    capacityAttempt(
            EvidenceCapacityStoreFixtureBuilder.CapacityFixture capacity,
            Context context,
            com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceLeaseBudget budget) {
        return new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(capacity.recoverableRequest(),
                capacity.evidenceTransactionId(),
                capacity.recoverableRequest().trustedVerificationTime(),
                context.evidenceParent, 1, null, budget);
    }

    private static com.leanowtech.bloge.gateway.testkit
            .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceLeaseBudget
    evidenceBudget(java.time.Duration duration) throws Exception {
        var type = com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceLeaseBudget.class;
        var constructor = type.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(System.nanoTime(), duration.toNanos());
    }

    private static com.leanowtech.bloge.gateway.testkit
            .CapabilityStudioStageAcceptanceAuthorityProvider.ExistingEvidenceRecoveryResult
            recover(
            com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .FormalEvidenceRecoveryBinding binding,
            Context context,
            ExecutionLeaseRequest request) {
        var attempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(request, fingerprint('8'),
                request.trustedVerificationTime(), context.evidenceParent);
        return binding.recovery().recoverExisting(attempt, ignored -> { });
    }

    private static Closure closure(Path root) throws Exception {
        Map<String, Entry> entries = new TreeMap<>();
        try (var children = Files.list(root)) {
            for (Path path : children.toList()) {
                BasicFileAttributes attributes = Files.readAttributes(path,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                entries.put(path.getFileName().toString(), new Entry(
                        attributes.fileKey(), attributes.isRegularFile(),
                        ((Number) Files.getAttribute(path, "unix:nlink",
                                LinkOption.NOFOLLOW_LINKS)).longValue(),
                        ((Number) Files.getAttribute(path, "unix:uid",
                                LinkOption.NOFOLLOW_LINKS)).longValue(),
                        ((Number) Files.getAttribute(path, "unix:mode",
                                LinkOption.NOFOLLOW_LINKS)).intValue() & 0777,
                        attributes.size(), attributes.lastModifiedTime(),
                        attributes.isRegularFile() ? Files.readAllBytes(path) : new byte[0]));
            }
        }
        return new Closure(entries);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String fingerprint(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static com.fasterxml.jackson.databind.node.ObjectNode requestJson(
            ExecutionLeaseRequest request) {
        var value = MountedProviderTestFixtures.JSON.createObjectNode();
        value.put("resultId", request.resultId());
        value.put("resultRevision", request.resultRevision());
        value.put("stageResultRawFingerprint", request.stageResultRawFingerprint());
        value.put("evidenceClosureFingerprint", request.evidenceClosureFingerprint());
        value.put("contractId", request.contractId());
        value.put("contractRevision", request.contractRevision());
        value.put("executionLeaseId", request.executionLeaseId());
        value.put("providerOuterFingerprint", request.providerOuterFingerprint());
        value.put("targetRawFingerprint", request.targetRawFingerprint());
        value.put("targetCanonicalFingerprint", request.targetCanonicalFingerprint());
        value.put("deploymentAdmissionAuthorityMaterialFingerprint",
                request.deploymentAdmissionAuthorityMaterialFingerprint());
        value.put("trustedVerificationTime", request.trustedVerificationTime().toString());
        var lifecycle = value.putObject("lifecycleMaterial");
        lifecycle.put("bundleFingerprint", request.lifecycleMaterial().bundleFingerprint());
        lifecycle.put("bundleId", request.lifecycleMaterial().bundleId());
        lifecycle.put("revision", request.lifecycleMaterial().revision());
        lifecycle.put("lifecycleState", request.lifecycleMaterial().lifecycleState());
        if (request.lifecycleMaterial().predecessorBundleFingerprint() == null) {
            lifecycle.putNull("predecessorBundleFingerprint");
        } else {
            lifecycle.put("predecessorBundleFingerprint",
                    request.lifecycleMaterial().predecessorBundleFingerprint());
        }
        var revocation = lifecycle.putObject("revocationAuthority");
        revocation.put("registryRef",
                request.lifecycleMaterial().revocationAuthority().registryRef());
        revocation.put("revision",
                request.lifecycleMaterial().revocationAuthority().revision());
        revocation.put("snapshotFingerprint",
                request.lifecycleMaterial().revocationAuthority().snapshotFingerprint());
        revocation.put("observedAt",
                request.lifecycleMaterial().revocationAuthority().observedAt().toString());
        revocation.put("expiresAt",
                request.lifecycleMaterial().revocationAuthority().expiresAt().toString());
        return value;
    }

    static ExecutionLeaseRequest requestFromJson(byte[] bytes) throws Exception {
        var value = MountedProviderTestFixtures.JSON.readTree(bytes);
        var lifecycle = value.path("lifecycleMaterial");
        var revocation = lifecycle.path("revocationAuthority");
        String predecessor = lifecycle.path("predecessorBundleFingerprint").isNull()
                ? null : lifecycle.path("predecessorBundleFingerprint").textValue();
        var material = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial(
                lifecycle.path("bundleFingerprint").textValue(),
                lifecycle.path("bundleId").textValue(), lifecycle.path("revision").longValue(),
                lifecycle.path("lifecycleState").textValue(), predecessor,
                new com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .RevocationAuthoritySnapshot(
                        revocation.path("registryRef").textValue(),
                        revocation.path("revision").longValue(),
                        revocation.path("snapshotFingerprint").textValue(),
                        java.time.Instant.parse(revocation.path("observedAt").textValue()),
                        java.time.Instant.parse(revocation.path("expiresAt").textValue())));
        return new ExecutionLeaseRequest(value.path("resultId").textValue(),
                value.path("resultRevision").longValue(),
                value.path("stageResultRawFingerprint").textValue(),
                value.path("evidenceClosureFingerprint").textValue(),
                value.path("contractId").textValue(), value.path("contractRevision").textValue(),
                value.path("executionLeaseId").textValue(),
                value.path("providerOuterFingerprint").textValue(),
                value.path("targetRawFingerprint").textValue(),
                value.path("targetCanonicalFingerprint").textValue(), material,
                value.path("deploymentAdmissionAuthorityMaterialFingerprint").textValue(),
                java.time.Instant.parse(value.path("trustedVerificationTime").textValue()));
    }

    private record Context(
            MountedProviderTestFixtures.Fixture fixture,
            com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .FormalEvidenceAuthorityBinding evidence,
            ExecutionLeaseRequest request,
            String storeDescriptorFingerprint,
            Path evidenceParent) {
    }

    private static class InMemoryJournal implements com.leanowtech.bloge.gateway.testkit
            .CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceTransactionJournal {
        private CapabilityStudioDeploymentStateObservation.Observation before;

        @Override
        public CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceExecutionLeaseAttempt attempt,
                CapabilityStudioDeploymentStateObservation.Observation current) {
            if (before == null) {
                before = current;
            }
            return before;
        }

        @Override
        public void persistCommitted(
                com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceExecutionLeaseAttempt attempt,
                CapabilityStudioDeploymentStateObservation.Observation before,
                CapabilityStudioDeploymentStateObservation.Observation after,
                com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceExecutionLeaseCommitResult result) {
            assertThat(this.before).isEqualTo(before);
        }
    }

    private static final class OneReplaceFailure
            implements FilesystemDeploymentAdmissionAuthority.Durability {
        private final String targetName;
        private boolean failed;

        private OneReplaceFailure(String targetName) {
            this.targetName = targetName;
        }

        @Override
        public void atomicReplace(Path source, Path target) throws IOException {
            if (!failed && target.getFileName().toString().equals(targetName)) {
                failed = true;
                throw new IOException("injected replace outage");
            }
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void forceDirectory(Path directory, Path installedEntry) throws IOException {
            try (FileChannel channel = FileChannel.open(directory,
                    StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    private record Closure(Map<String, Entry> entries) {
        private Closure {
            entries = Map.copyOf(entries);
        }
    }

    private record Entry(
            Object fileKey,
            boolean regular,
            long links,
            long uid,
            int mode,
            long size,
            java.nio.file.attribute.FileTime modified,
            byte[] bytes) {
        private Entry {
            bytes = bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry
                    && java.util.Objects.equals(fileKey, entry.fileKey)
                    && regular == entry.regular && links == entry.links && uid == entry.uid
                    && mode == entry.mode && size == entry.size
                    && java.util.Objects.equals(modified, entry.modified)
                    && Arrays.equals(bytes, entry.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(fileKey, regular, links, uid, mode, size, modified,
                    Arrays.hashCode(bytes));
        }
    }
}

final class MountedEvidenceRecoveryWorker {
    private MountedEvidenceRecoveryWorker() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) {
            System.exit(2);
        }
        if (MountedEvidenceRecoveryWorker.class.getResource(
                "/schemas/resource-gateway-capability-studio/"
                        + "capability-studio-execution-lease-transcript-v1.schema.json") == null) {
            System.exit(4);
        }
        var request = requestFromJson(
                Files.readAllBytes(Path.of(args[0])));
        com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryResult result;
        try {
            var providers = java.util.ServiceLoader.load(com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider.class)
                    .stream().limit(2).toList();
            if (providers.size() != 1) {
                System.exit(3);
            }
            var provider = providers.getFirst().get();
            String transactionId = args.length == 3
                    ? args[2] : "sha256:" + "8".repeat(64);
            var attempt = new com.leanowtech.bloge.gateway.testkit
                    .CapabilityStudioStageAcceptanceAuthorityProvider
                    .EvidenceExecutionLeaseAttempt(request,
                    transactionId, request.trustedVerificationTime(),
                    Path.of(args[1]).toAbsolutePath().normalize());
            result = provider.formalEvidenceRecoveryBinding().recovery()
                    .recoverExisting(attempt, ignored -> { });
        } catch (DeploymentUnavailableException unavailable) {
            System.out.println("UNAVAILABLE");
            return;
        } catch (IllegalStateException invalid) {
            System.out.println("INVALID");
            return;
        }
        if (result.status() != com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .ExistingEvidenceRecoveryStatus.FOUND
                || result.receipt() == null || result.transitionWitness() == null) {
            System.out.println(result.status());
            return;
        }
        System.out.println("RECOVERED " + result.receipt().fingerprint() + " "
                + result.transitionWitness().fingerprint() + " "
                + result.afterObservation().observationFingerprint());
    }

    static ExecutionLeaseRequest requestFromJson(byte[] bytes) throws Exception {
        var value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
        var lifecycle = value.path("lifecycleMaterial");
        var revocation = lifecycle.path("revocationAuthority");
        String predecessor = lifecycle.path("predecessorBundleFingerprint").isNull()
                ? null : lifecycle.path("predecessorBundleFingerprint").textValue();
        var material = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial(
                lifecycle.path("bundleFingerprint").textValue(),
                lifecycle.path("bundleId").textValue(), lifecycle.path("revision").longValue(),
                lifecycle.path("lifecycleState").textValue(), predecessor,
                new com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .RevocationAuthoritySnapshot(
                        revocation.path("registryRef").textValue(),
                        revocation.path("revision").longValue(),
                        revocation.path("snapshotFingerprint").textValue(),
                        java.time.Instant.parse(revocation.path("observedAt").textValue()),
                        java.time.Instant.parse(revocation.path("expiresAt").textValue())));
        return new ExecutionLeaseRequest(value.path("resultId").textValue(),
                value.path("resultRevision").longValue(),
                value.path("stageResultRawFingerprint").textValue(),
                value.path("evidenceClosureFingerprint").textValue(),
                value.path("contractId").textValue(), value.path("contractRevision").textValue(),
                value.path("executionLeaseId").textValue(),
                value.path("providerOuterFingerprint").textValue(),
                value.path("targetRawFingerprint").textValue(),
                value.path("targetCanonicalFingerprint").textValue(), material,
                value.path("deploymentAdmissionAuthorityMaterialFingerprint").textValue(),
                java.time.Instant.parse(value.path("trustedVerificationTime").textValue()));
    }
}

final class MountedEvidenceCommitCrashWorker {
    private MountedEvidenceCommitCrashWorker() {
    }

    public static void main(String[] args) throws Exception {
        var request = MountedEvidenceRecoveryWorker.requestFromJson(
                Files.readAllBytes(Path.of(args[0])));
        var providers = java.util.ServiceLoader.load(com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider.class)
                .stream().limit(2).toList();
        if (providers.size() != 1) {
            System.exit(3);
        }
        var binding = providers.getFirst().get().formalEvidenceAuthorityBinding();
        var attempt = new com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioStageAcceptanceAuthorityProvider
                .EvidenceExecutionLeaseAttempt(request,
                "sha256:" + "8".repeat(64), request.trustedVerificationTime(), Path.of(args[1]));
        binding.transactionAuthority().commit(attempt,
                new com.leanowtech.bloge.gateway.testkit
                        .CapabilityStudioStageAcceptanceAuthorityProvider
                        .EvidenceTransactionJournal() {
                    @Override
                    public CapabilityStudioDeploymentStateObservation.Observation prepareBefore(
                            com.leanowtech.bloge.gateway.testkit
                                    .CapabilityStudioStageAcceptanceAuthorityProvider
                                    .EvidenceExecutionLeaseAttempt ignored,
                            CapabilityStudioDeploymentStateObservation.Observation current) {
                        return current;
                    }

                    @Override
                    public void persistCommitted(
                            com.leanowtech.bloge.gateway.testkit
                                    .CapabilityStudioStageAcceptanceAuthorityProvider
                                    .EvidenceExecutionLeaseAttempt ignored,
                            CapabilityStudioDeploymentStateObservation.Observation before,
                            CapabilityStudioDeploymentStateObservation.Observation after,
                            com.leanowtech.bloge.gateway.testkit
                                    .CapabilityStudioStageAcceptanceAuthorityProvider
                                    .EvidenceExecutionLeaseCommitResult result) {
                        Runtime.getRuntime().halt(77);
                    }
                });
        System.exit(3);
    }
}

final class MountedEvidencePublicationLockWorker {
    private MountedEvidencePublicationLockWorker() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.exit(2);
        }
        try (FileChannel channel = FileChannel.open(Path.of(args[0]),
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            System.out.println("READY");
            System.out.flush();
            Thread.sleep(java.time.Duration.ofMinutes(1));
        }
    }
}
