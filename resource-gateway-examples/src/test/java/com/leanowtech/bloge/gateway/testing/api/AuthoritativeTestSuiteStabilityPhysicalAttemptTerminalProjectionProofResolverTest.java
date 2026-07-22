package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthoritativeTestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-07-22T01:00:00Z");
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);
    private static final String ATTEMPT_ID = "stability-attempt-" + "a".repeat(64);
    private static final String JOB_ID = "stability-job-" + "b".repeat(64);

    private final TestSuiteStabilityAttemptCancellationJournal cancellations = mock(
            TestSuiteStabilityAttemptCancellationJournal.class);
    private final TestSuiteStabilityJobRepository jobs = mock(
            TestSuiteStabilityJobRepository.class);
    private final TestSuiteStabilityRunRepository parentRuns = mock(
            TestSuiteStabilityRunRepository.class);
    private final TestSuiteStabilityJobParentAuthority parentAuthority = mock(
            TestSuiteStabilityJobParentAuthority.class);
    private final AuthoritativeTestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
            resolver = new AuthoritativeTestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver(
            MAPPER, cancellations, jobs, parentRuns, parentAuthority);

    @Test
    void rejectsDispositionThatNeedsNoAdditionalProof() {
        assertThatThrownBy(() -> resolver.resolve(identity(),
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition.FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Terminal projection proof is not required for this disposition");
        verifyNoInteractions(cancellations, jobs, parentRuns, parentAuthority);
    }

    @Test
    void treatsAbsentCancellationAsPending() {
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity();
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenReturn(TestSuiteStabilityAttemptCancellationJournal.AttemptLookup.absent());

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity,
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .CANCELLED);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ResolutionStatus
                        .PENDING);
        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .CANCELLATION_NOT_CONFIRMED);
    }

    @Test
    void preservesAmbiguousCancellationAsPermanentConflict() {
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenReturn(TestSuiteStabilityAttemptCancellationJournal.AttemptLookup.conflict(
                        TestSuiteStabilityAttemptCancellationJournal.AttemptLookupReason
                                .AMBIGUOUS));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .CANCELLED);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ResolutionStatus
                        .CONFLICT);
        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .AMBIGUOUS_PROOF);
    }

    @Test
    void preservesCancellationIntegrityFailureAsPermanentConflict() {
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenReturn(TestSuiteStabilityAttemptCancellationJournal.AttemptLookup.conflict(
                        TestSuiteStabilityAttemptCancellationJournal.AttemptLookupReason
                                .INTEGRITY_CONFLICT));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .CANCELLED);

        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
    }

    @Test
    void keepsExactPreparedCancellationPending() {
        TestSuiteStabilityAttemptCancellationJournal.Entry entry = cancellationEntry(
                TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED, "worker-a");
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenReturn(TestSuiteStabilityAttemptCancellationJournal.AttemptLookup.found(
                        entry));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .CANCELLED);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ResolutionStatus
                        .PENDING);
    }

    @Test
    void rejectsTerminalNonConfirmingCancellation() {
        TestSuiteStabilityAttemptCancellationJournal.Entry entry = cancellationEntry(
                TestSuiteStabilityAttemptCancellationJournal.Status.UNCONFIRMED, "worker-a");
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenReturn(TestSuiteStabilityAttemptCancellationJournal.AttemptLookup.found(
                        entry));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .CANCELLED);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ResolutionStatus
                        .CONFLICT);
        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
    }

    @Test
    void returnsOnlyExactProviderConfirmedCancellation() {
        TestSuiteStabilityAttemptCancellationJournal.Entry entry = cancellationEntry(
                TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED, "worker-a");
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenReturn(TestSuiteStabilityAttemptCancellationJournal.AttemptLookup.found(
                        entry));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .CANCELLED);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ResolutionStatus
                        .READY);
        assertThat(result.proof().orElseThrow().kind()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ProofKind
                        .CANCELLATION);
        assertThat(result.proof().orElseThrow().cancellation()).contains(entry);
    }

    @Test
    void rejectsCancellationBoundToAnotherLeaseOwner() {
        TestSuiteStabilityAttemptCancellationJournal.Entry entry = cancellationEntry(
                TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED, "worker-b");
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenReturn(TestSuiteStabilityAttemptCancellationJournal.AttemptLookup.found(
                        entry));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .CANCELLED);

        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
    }

    @Test
    void rejectsCancellationAttestationFromAnotherDescriptorKey() {
        TestSuiteStabilityAttemptCancellationJournal.Entry exact = cancellationEntry(
                TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED, "worker-a");
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor wrongKey =
                new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                        TestSuiteStabilityAttemptCancellationAuthority.Descriptor.SCHEMA_VERSION,
                        "provider-a", "deployment-a", "key-b", true,
                        Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode
                                .CONTAINER), Duration.ofSeconds(1));
        TestSuiteStabilityAttemptCancellationJournal.Entry entry =
                new TestSuiteStabilityAttemptCancellationJournal.Entry(
                        TestSuiteStabilityAttemptCancellationJournal.Entry.SCHEMA_VERSION,
                        exact.command(), wrongKey, exact.status(), exact.attestation(), NOW, NOW,
                        SHA_D);
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenReturn(TestSuiteStabilityAttemptCancellationJournal.AttemptLookup.found(
                        entry));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .CANCELLED);

        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
    }

    @Test
    void propagatesCancellationStorageOutage() {
        IllegalStateException outage = new IllegalStateException("database unavailable");
        when(cancellations.findByAttempt("tenant-a", "test", ATTEMPT_ID, 7))
                .thenThrow(outage);

        assertThatThrownBy(() -> resolver.resolve(identity(),
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                        .CANCELLED))
                .isSameAs(outage);
    }

    @Test
    void rejectsSuccessWhenExactQueueJobIsGone() {
        when(jobs.find("tenant-a", "test", JOB_ID)).thenReturn(Optional.empty());

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .SUCCEEDED);

        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
        verifyNoInteractions(parentRuns, parentAuthority);
    }

    @Test
    void keepsUnfinishedParentSuccessPending() {
        TestSuiteStabilityJobRecord job = job(TestSuiteStabilityJobRecord.Status.RUNNING);
        String parentId = parentId(job);
        when(jobs.find("tenant-a", "test", JOB_ID)).thenReturn(Optional.of(job));
        when(parentRuns.find("tenant-a", "test", parentId)).thenReturn(Optional.empty());
        when(parentRuns.findStop("tenant-a", "test", parentId)).thenReturn(Optional.empty());

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .SUCCEEDED);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ResolutionStatus
                        .PENDING);
        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PARENT_NOT_CONFIRMED);
        verifyNoInteractions(parentAuthority);
    }

    @Test
    void rejectsSuccessAfterParentStopWon() {
        TestSuiteStabilityJobRecord job = job(TestSuiteStabilityJobRecord.Status.RUNNING);
        String parentId = parentId(job);
        when(jobs.find("tenant-a", "test", JOB_ID)).thenReturn(Optional.of(job));
        when(parentRuns.find("tenant-a", "test", parentId)).thenReturn(Optional.empty());
        when(parentRuns.findStop("tenant-a", "test", parentId)).thenReturn(
                Optional.of(mock(TestSuiteStabilityExecutionStop.class)));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .SUCCEEDED);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ResolutionStatus
                        .CONFLICT);
        verifyNoInteractions(parentAuthority);
    }

    @Test
    void rejectsParentRunAfterNonSuccessQueueTerminal() {
        TestSuiteStabilityJobRecord job = job(TestSuiteStabilityJobRecord.Status.FAILED);
        TestSuiteStabilityRunRecord parent = parent(job);
        when(jobs.find("tenant-a", "test", JOB_ID)).thenReturn(Optional.of(job));
        when(parentRuns.find("tenant-a", "test", parentId(job)))
                .thenReturn(Optional.of(parent));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .SUCCEEDED);

        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
        verifyNoInteractions(parentAuthority);
    }

    @Test
    void returnsOnlyIndependentlyVerifiedExactParentSuccess() {
        TestSuiteStabilityJobRecord job = job(TestSuiteStabilityJobRecord.Status.RUNNING);
        TestSuiteStabilityRunRecord parent = parent(job);
        when(jobs.find("tenant-a", "test", JOB_ID)).thenReturn(Optional.of(job));
        when(parentRuns.find("tenant-a", "test", parentId(job)))
                .thenReturn(Optional.of(parent));
        when(parentAuthority.requireCompleted(job, parentId(job), SHA_D)).thenReturn(
                TestSuiteStabilityJobParentAuthority.Resolution.completed(
                        parentId(job), SHA_D));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .SUCCEEDED);

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ResolutionStatus
                        .READY);
        assertThat(result.proof().orElseThrow().parentStabilityRunId())
                .isEqualTo(parentId(job));
        assertThat(result.proof().orElseThrow().parentEvidenceFingerprint()).isEqualTo(SHA_D);
        verify(parentAuthority).requireCompleted(job, parentId(job), SHA_D);
    }

    @Test
    void turnsCryptographicParentConflictIntoPermanentProofConflict() {
        TestSuiteStabilityJobRecord job = job(TestSuiteStabilityJobRecord.Status.RUNNING);
        TestSuiteStabilityRunRecord parent = parent(job);
        when(jobs.find("tenant-a", "test", JOB_ID)).thenReturn(Optional.of(job));
        when(parentRuns.find("tenant-a", "test", parentId(job)))
                .thenReturn(Optional.of(parent));
        when(parentAuthority.requireCompleted(job, parentId(job), SHA_D)).thenThrow(
                new TestSuiteStabilityRunConflictException(
                        TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT,
                        "signature conflict"));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .SUCCEEDED);

        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
    }

    @Test
    void rejectsWrongShapedParentAuthorityResult() {
        TestSuiteStabilityJobRecord job = job(TestSuiteStabilityJobRecord.Status.RUNNING);
        TestSuiteStabilityRunRecord parent = parent(job);
        when(jobs.find("tenant-a", "test", JOB_ID)).thenReturn(Optional.of(job));
        when(parentRuns.find("tenant-a", "test", parentId(job)))
                .thenReturn(Optional.of(parent));
        when(parentAuthority.requireCompleted(job, parentId(job), SHA_D)).thenReturn(
                TestSuiteStabilityJobParentAuthority.Resolution.completed(
                        "stability-" + "e".repeat(64), SHA_D));

        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution result =
                resolver.resolve(identity(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .SUCCEEDED);

        assertThat(result.reason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
    }

    @Test
    void propagatesParentRepositoryOutage() {
        TestSuiteStabilityJobRecord job = job(TestSuiteStabilityJobRecord.Status.RUNNING);
        IllegalStateException outage = new IllegalStateException("parent repository unavailable");
        when(jobs.find("tenant-a", "test", JOB_ID)).thenReturn(Optional.of(job));
        when(parentRuns.find("tenant-a", "test", parentId(job))).thenThrow(outage);

        assertThatThrownBy(() -> resolver.resolve(identity(),
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                        .SUCCEEDED))
                .isSameAs(outage);
    }

    private static TestSuiteStabilityPhysicalAttemptIdentity identity() {
        return new TestSuiteStabilityPhysicalAttemptIdentity(
                TestSuiteStabilityPhysicalAttemptIdentity.SCHEMA_VERSION,
                ATTEMPT_ID, SHA_A, "tenant-a", "test", JOB_ID, SHA_C,
                "worker-a", 7, SHA_B, "provider-a", "deployment-a",
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.CONTAINER);
    }

    private static TestSuiteStabilityAttemptCancellationJournal.Entry cancellationEntry(
            TestSuiteStabilityAttemptCancellationJournal.Status status,
            String ownerId) {
        TestSuiteStabilityAttemptCancellationCommand command =
                TestSuiteStabilityAttemptCancellationCommand.create(
                        MAPPER, "tenant-a", "test", JOB_ID, ATTEMPT_ID, ownerId, 7,
                        SHA_B, TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED,
                        NOW, NOW.plusSeconds(5), Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(new byte[32]));
        TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor =
                new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                        TestSuiteStabilityAttemptCancellationAuthority.Descriptor.SCHEMA_VERSION,
                        "provider-a", "deployment-a", "key-a", true,
                        Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode
                                .CONTAINER), Duration.ofSeconds(1));
        Optional<TestSuiteStabilityAttemptCancellationReceipt.Attestation> attestation =
                status == TestSuiteStabilityAttemptCancellationJournal.Status.PREPARED
                        ? Optional.empty() : Optional.of(attestation(command, status));
        return new TestSuiteStabilityAttemptCancellationJournal.Entry(
                TestSuiteStabilityAttemptCancellationJournal.Entry.SCHEMA_VERSION,
                command, descriptor, status, attestation, NOW, NOW, SHA_D);
    }

    private static TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationJournal.Status status) {
        boolean confirmed = status == TestSuiteStabilityAttemptCancellationJournal.Status
                .CONFIRMED;
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                new TestSuiteStabilityAttemptCancellationReceipt(
                        TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), "provider-a",
                        "deployment-a", ATTEMPT_ID, 7, 11,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.CONTAINER,
                        confirmed
                                ? TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED
                                : TestSuiteStabilityAttemptCancellationReceipt.Outcome.NOT_FOUND,
                        confirmed
                                ? TestSuiteStabilityAttemptCancellationReceipt.TerminationMode
                                .CONTAINER_TERMINATION
                                : TestSuiteStabilityAttemptCancellationReceipt.TerminationMode
                                .NONE,
                        SHA_A, SHA_B, NOW);
        return new TestSuiteStabilityAttemptCancellationReceipt.Attestation(
                TestSuiteStabilityAttemptCancellationReceipt.Attestation.SCHEMA_VERSION,
                receipt, "key-a", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[64]));
    }

    private static TestSuiteStabilityJobRecord job(TestSuiteStabilityJobRecord.Status status) {
        TestSuiteStabilityExecutionRequest request = new TestSuiteStabilityExecutionRequest(
                "", new TestSuiteExecutionRequest.SuiteRef("suite-a", 1, SHA_A),
                "client-a", 3, Map.of("pipeline", "release"));
        TestSuiteStabilityJobPrincipal principal = new TestSuiteStabilityJobPrincipal(
                "tenant-a", "org-a", "project-a", "test", "sg", "SERVICE",
                "actor-a", "", "TEST_EXECUTION", "correlation-a", Set.of("release"),
                "CONFIDENTIAL", "");
        return new TestSuiteStabilityJobRecord(
                JOB_ID, request, SHA_C, "CONFIDENTIAL", principal,
                TestSuiteStabilityJobSubmission.Priority.NORMAL, status, 0, NOW,
                NOW.plusSeconds(300), NOW.minusSeconds(10), NOW,
                NOW.plusSeconds(3600), "", "", status == TestSuiteStabilityJobRecord.Status.FAILED
                ? "RG.TEST.FAILURE" : "", "", "", SHA_B);
    }

    private static String parentId(TestSuiteStabilityJobRecord job) {
        return TestSuiteStabilityExecutionIdentity.descriptor(MAPPER, job).stabilityRunId();
    }

    private static TestSuiteStabilityRunRecord parent(TestSuiteStabilityJobRecord job) {
        TestSuiteStabilityRunRecord parent = mock(TestSuiteStabilityRunRecord.class);
        when(parent.stabilityRunId()).thenReturn(parentId(job));
        when(parent.tenantId()).thenReturn("tenant-a");
        when(parent.environmentId()).thenReturn("test");
        when(parent.clientRequestId()).thenReturn("client-a");
        when(parent.requestFingerprint()).thenReturn(SHA_C);
        when(parent.evidenceFingerprint()).thenReturn(SHA_D);
        return parent;
    }
}
