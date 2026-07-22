package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-22T01:00:00Z");
    private static final TestSuiteStabilityQueuePolicy POLICY =
            new TestSuiteStabilityQueuePolicy(
                    1, 100, 20, 10, 5, Duration.ofSeconds(30), Duration.ofSeconds(10),
                    Duration.ofSeconds(1), Duration.ofSeconds(30), 2,
                    Duration.ofHours(1), Duration.ofDays(30));

    private ObjectMapper objectMapper;
    private TestSuiteStabilityPhysicalAttemptRegistry attempts;
    private TestSuiteStabilityPhysicalAttemptStartJournal starts;
    private TestSuiteStabilityPhysicalAttemptObservationJournal observations;
    private TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver proofs;
    private TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal projections;
    private TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        attempts = mock(TestSuiteStabilityPhysicalAttemptRegistry.class);
        starts = mock(TestSuiteStabilityPhysicalAttemptStartJournal.class);
        observations = mock(TestSuiteStabilityPhysicalAttemptObservationJournal.class);
        proofs = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.class);
        projections = mock(TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.class);
        coordinator = new TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator(
                objectMapper, attempts, starts, observations, proofs, projections);
    }

    @Test
    void failedTerminalProjectsExactSourceChainWithoutResolvingAdditionalProof() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.FAILED);
        when(projections.project(any(), any())).thenAnswer(invocation -> projection(
                invocation.getArgument(0),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.PROJECTED));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.PROJECTED);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason.NONE);
        assertThat(result.projection()).isPresent();
        ArgumentCaptor<TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand> command =
                ArgumentCaptor.forClass(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand.class);
        verify(projections).project(command.capture(), org.mockito.ArgumentMatchers.eq(POLICY));
        assertThat(command.getValue().attemptId()).isEqualTo(fixture.identity().attemptId());
        assertThat(command.getValue().reservationRecordFingerprint())
                .isEqualTo(fixture.reservation().recordFingerprint());
        assertThat(command.getValue().observationCommandId())
                .isEqualTo(fixture.observation().command().commandId());
        verifyNoInteractions(proofs);
    }

    @Test
    void exactProjectionReplayRemainsDistinctFromNewCommit() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.TIMED_OUT);
        when(projections.project(any(), any())).thenAnswer(invocation -> projection(
                invocation.getArgument(0),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.REPLAYED));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.REPLAYED);
        assertThat(result.projection()).get().extracting(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Projection::status)
                .isEqualTo(TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.REPLAYED);
        verifyNoInteractions(proofs);
    }

    @Test
    void cancelledTerminalWaitsForConfirmedCancellationProofWithoutWritingProjection() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED);
        when(proofs.resolve(fixture.identity(), fixture.disposition())).thenReturn(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution
                        .pending(TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                                .Reason.CANCELLATION_NOT_CONFIRMED));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.PROOF_PENDING);
        assertThat(result.proofReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .CANCELLATION_NOT_CONFIRMED);
        verifyNoInteractions(projections);
    }

    @Test
    void succeededTerminalWaitsForSignedParentWithoutWritingProjection() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.SUCCEEDED);
        when(proofs.resolve(fixture.identity(), fixture.disposition())).thenReturn(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution
                        .pending(TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                                .Reason.PARENT_NOT_CONFIRMED));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.PROOF_PENDING);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROOF_NOT_READY);
        verifyNoInteractions(projections);
    }

    @Test
    void confirmedCancellationProofIsBoundIntoProjectionCommand() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED);
        TestSuiteStabilityAttemptCancellationJournal.Entry cancellation =
                cancellation(fixture);
        when(proofs.resolve(fixture.identity(), fixture.disposition())).thenReturn(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution.ready(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Proof
                                .cancellation(cancellation)));
        when(projections.project(any(), any())).thenAnswer(invocation -> projection(
                invocation.getArgument(0),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.PROJECTED));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.PROJECTED);
        ArgumentCaptor<TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand> command =
                ArgumentCaptor.forClass(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand.class);
        verify(projections).project(command.capture(), any());
        assertThat(command.getValue().cancellationCommandId())
                .isEqualTo(cancellation.command().commandId());
        assertThat(command.getValue().cancellationEntryFingerprint())
                .isEqualTo(cancellation.recordFingerprint());
        assertThat(command.getValue().parentStabilityRunId()).isEmpty();
    }

    @Test
    void signedParentCandidateIsBoundIntoSuccessProjectionCommand() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.SUCCEEDED);
        String parentRun = "stability-run-a";
        String parentEvidence = fingerprint('d');
        when(proofs.resolve(fixture.identity(), fixture.disposition())).thenReturn(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution.ready(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Proof
                                .parentSuccess(parentRun, parentEvidence)));
        when(projections.project(any(), any())).thenAnswer(invocation -> projection(
                invocation.getArgument(0),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.PROJECTED));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.PROJECTED);
        ArgumentCaptor<TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand> command =
                ArgumentCaptor.forClass(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand.class);
        verify(projections).project(command.capture(), any());
        assertThat(command.getValue().parentStabilityRunId()).isEqualTo(parentRun);
        assertThat(command.getValue().parentEvidenceFingerprint()).isEqualTo(parentEvidence);
        assertThat(command.getValue().cancellationCommandId()).isEmpty();
    }

    @Test
    void wrongReadyProofKindFailsClosedBeforeProjectionTransaction() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.SUCCEEDED);
        when(proofs.resolve(fixture.identity(), fixture.disposition())).thenReturn(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution.ready(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Proof
                                .cancellation(cancellation(fixture))));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PERMANENT_CONFLICT);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROOF_CONFLICT);
        assertThat(result.proofReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PROOF_CONFLICT);
        verifyNoInteractions(projections);
    }

    @Test
    void ambiguousProofConflictIsPreservedWithoutProjectionTransaction() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED);
        when(proofs.resolve(fixture.identity(), fixture.disposition())).thenReturn(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution
                        .conflict(TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                                .Reason.AMBIGUOUS_PROOF));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PERMANENT_CONFLICT);
        assertThat(result.proofReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .AMBIGUOUS_PROOF);
        verifyNoInteractions(projections);
    }

    @Test
    void missingReservationIsPermanentSourceConflict() {
        when(attempts.find("tenant-a", "test", attemptId())).thenReturn(Optional.empty());

        var result = coordinator.project("tenant-a", "test", attemptId(), POLICY);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PERMANENT_CONFLICT);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .SOURCE_NOT_RETAINED);
        verifyNoInteractions(starts, observations, proofs, projections);
    }

    @Test
    void nonTerminalPositiveFloorCannotBePromotedToQueueClosure() {
        Fixture fixture = wireRunning();

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PERMANENT_CONFLICT);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .TERMINAL_NOT_CONFIRMED);
        verify(starts, never()).find(any(), any(), any());
        verifyNoInteractions(proofs, projections);
    }

    @Test
    void transactionalProjectionConflictKeepsExactJournalReason() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.FAILED);
        when(projections.project(any(), any())).thenThrow(
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictException(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason
                                .JOB_FENCE_CHANGED));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PERMANENT_CONFLICT);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROJECTION_CONFLICT);
        assertThat(result.projectionConflictReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason
                        .JOB_FENCE_CHANGED);
    }

    @Test
    void sourceStoreOutageRemainsUnavailableAndDoesNotTouchProjection() {
        when(attempts.find("tenant-a", "test", attemptId()))
                .thenThrow(new IllegalStateException("database unavailable"));

        var result = coordinator.project("tenant-a", "test", attemptId(), POLICY);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.UNAVAILABLE);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .SOURCE_UNAVAILABLE);
        verifyNoInteractions(proofs, projections);
    }

    @Test
    void proofAuthorityOutageRemainsUnavailableAndDoesNotTouchProjection() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.SUCCEEDED);
        when(proofs.resolve(fixture.identity(), fixture.disposition()))
                .thenThrow(new IllegalStateException("parent authority unavailable"));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.UNAVAILABLE);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROOF_RESOLUTION_UNAVAILABLE);
        verifyNoInteractions(projections);
    }

    @Test
    void projectionStoreOutageRemainsUnavailable() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.PROVIDER_ABORTED);
        when(projections.project(any(), any()))
                .thenThrow(new IllegalStateException("projection store unavailable"));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.UNAVAILABLE);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROJECTION_UNAVAILABLE);
    }

    @Test
    void projectionAdapterCannotReturnAnotherCommandAsSuccess() {
        Fixture fixture = wire(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.FAILED);
        Fixture other = fixture(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.TIMED_OUT, true, 'f');
        TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand otherCommand =
                command(other, Optional.empty(), "", "");
        when(projections.project(any(), any())).thenReturn(projection(
                otherCommand,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.PROJECTED));

        var result = project(fixture);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.UNAVAILABLE);
        assertThat(result.failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROJECTION_CONTRACT_VIOLATION);
    }

    @Test
    void proofValueRejectsMixedCancellationAndParentShape() {
        Fixture fixture = fixture(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.CANCELLED, true, 'a');

        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Proof(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.ProofKind
                        .CANCELLATION,
                Optional.of(cancellation(fixture)), "parent-a", fingerprint('d')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal-projection proof");
    }

    @Test
    void proofResolutionRejectsPendingWithPermanentConflictReason() {
        assertThatThrownBy(() ->
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Resolution
                        .pending(TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
                                .Reason.PROOF_CONFLICT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proof resolution");
    }

    @Test
    void attemptValueRejectsProjectedStageWithReplayProjection() {
        Fixture fixture = fixture(TestSuiteStabilityPhysicalAttemptObservationReceipt
                .TerminalDisposition.FAILED, true, 'a');
        var replayed = projection(command(fixture, Optional.empty(), "", ""),
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                        .ProjectionStatus.REPLAYED);

        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage.PROJECTED,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason.NONE,
                Optional.empty(), Optional.empty(), Optional.of(replayed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal-projection attempt");
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt project(
            Fixture fixture) {
        return coordinator.project(fixture.identity().tenantId(),
                fixture.identity().environmentId(), fixture.identity().attemptId(), POLICY);
    }

    private Fixture wire(
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                    disposition) {
        Fixture fixture = fixture(disposition, true, 'a');
        wire(fixture);
        return fixture;
    }

    private Fixture wireRunning() {
        Fixture fixture = fixture(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition.NONE,
                false, 'a');
        wire(fixture);
        return fixture;
    }

    private void wire(Fixture fixture) {
        when(attempts.find(fixture.identity().tenantId(),
                fixture.identity().environmentId(), fixture.identity().attemptId()))
                .thenReturn(Optional.of(fixture.reservation()));
        when(observations.latestPositive(fixture.identity().tenantId(),
                fixture.identity().environmentId(), fixture.identity().attemptId()))
                .thenReturn(Optional.of(fixture.state()));
        when(observations.find(fixture.identity().tenantId(),
                fixture.identity().environmentId(),
                fixture.observation().command().commandId()))
                .thenReturn(Optional.of(fixture.observation()));
        when(starts.find(fixture.identity().tenantId(),
                fixture.identity().environmentId(), fixture.start().command().commandId()))
                .thenReturn(Optional.of(fixture.start()));
    }

    private Fixture fixture(
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition,
            boolean terminal,
            char seed) {
        TestSuiteStabilityPhysicalAttemptIdentity identity =
                new TestSuiteStabilityPhysicalAttemptIdentity(
                        TestSuiteStabilityPhysicalAttemptIdentity.SCHEMA_VERSION,
                        "stability-attempt-" + String.valueOf(seed).repeat(64),
                        fingerprint(seed), "tenant-a", "test",
                        "stability-job-" + String.valueOf(seed).repeat(64),
                        fingerprint('c'), "worker-a", 1, fingerprint('b'),
                        "provider-a", "deployment-a",
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
        TestSuiteStabilityPhysicalAttemptRegistry.Entry reservation =
                new TestSuiteStabilityPhysicalAttemptRegistry.Entry(
                        TestSuiteStabilityPhysicalAttemptRegistry.Entry.SCHEMA_VERSION,
                        identity, NOW, fingerprint('1'));
        TestSuiteStabilityPhysicalAttemptStartCommand startCommand =
                new TestSuiteStabilityPhysicalAttemptStartCommand(
                        TestSuiteStabilityPhysicalAttemptStartCommand.SCHEMA_VERSION,
                        "stability-attempt-start-" + String.valueOf(seed).repeat(64),
                        fingerprint(seed), identity,
                        "stability-envelope-" + String.valueOf(seed).repeat(64),
                        fingerprint('e'), NOW, NOW.plusSeconds(30), challenge(seed));
        TestSuiteStabilityPhysicalAttemptStartJournal.Entry start =
                new TestSuiteStabilityPhysicalAttemptStartJournal.Entry(
                        TestSuiteStabilityPhysicalAttemptStartJournal.Entry.SCHEMA_VERSION,
                        startCommand, startDescriptor(),
                        TestSuiteStabilityPhysicalAttemptStartJournal.Status.PREPARED,
                        Optional.empty(), NOW, NOW, fingerprint('2'));
        char observationSeed = seed == 'f' ? 'e' : 'd';
        TestSuiteStabilityPhysicalAttemptObservationCommand observationCommand =
                new TestSuiteStabilityPhysicalAttemptObservationCommand(
                        TestSuiteStabilityPhysicalAttemptObservationCommand.SCHEMA_VERSION,
                        "stability-attempt-observe-"
                                + String.valueOf(observationSeed).repeat(64),
                        fingerprint(observationSeed), startCommand, "", 0,
                        NOW.plusSeconds(1), NOW.plusSeconds(31), challenge(observationSeed));
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt = terminal
                ? terminalReceipt(identity, startCommand, observationCommand, disposition)
                : runningReceipt(identity, startCommand, observationCommand);
        var attestation = new TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation.SCHEMA_VERSION,
                receipt, "key-a", signature('s'));
        TestSuiteStabilityPhysicalAttemptObservationJournal.Entry observation =
                new TestSuiteStabilityPhysicalAttemptObservationJournal.Entry(
                        TestSuiteStabilityPhysicalAttemptObservationJournal.Entry.SCHEMA_VERSION,
                        observationCommand, observationDescriptor(),
                        TestSuiteStabilityPhysicalAttemptObservationJournal.Status.POSITIVE,
                        Optional.of(attestation), NOW.plusSeconds(1), NOW.plusSeconds(4),
                        fingerprint('3'));
        TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState state =
                new TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState(
                        TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState
                                .SCHEMA_VERSION,
                        identity.tenantId(), identity.environmentId(), identity.attemptId(),
                        identity.identityFingerprint(), startCommand.commandId(),
                        startCommand.commandFingerprint(), observationCommand.commandId(),
                        fingerprint('4'), receipt, NOW.plusSeconds(4), fingerprint('5'));
        return new Fixture(identity, reservation, start, observation, state, disposition);
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt terminalReceipt(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityPhysicalAttemptStartCommand start,
            TestSuiteStabilityPhysicalAttemptObservationCommand observation,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition) {
        return new TestSuiteStabilityPhysicalAttemptObservationReceipt(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.SCHEMA_VERSION,
                observation.commandId(), observation.commandFingerprint(),
                identity.providerId(), identity.deploymentId(), identity.attemptId(),
                identity.identityFingerprint(), start.commandId(), start.commandFingerprint(),
                identity.leaseEpoch(), 10, 2, identity.isolationMode(),
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.TERMINAL,
                fingerprint('6'), fingerprint('7'), disposition, fingerprint('8'),
                NOW.plusSeconds(3), NOW.plusSeconds(4));
    }

    private TestSuiteStabilityPhysicalAttemptObservationReceipt runningReceipt(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityPhysicalAttemptStartCommand start,
            TestSuiteStabilityPhysicalAttemptObservationCommand observation) {
        return new TestSuiteStabilityPhysicalAttemptObservationReceipt(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.SCHEMA_VERSION,
                observation.commandId(), observation.commandFingerprint(),
                identity.providerId(), identity.deploymentId(), identity.attemptId(),
                identity.identityFingerprint(), start.commandId(), start.commandFingerprint(),
                identity.leaseEpoch(), 10, 1, identity.isolationMode(),
                TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                fingerprint('6'), fingerprint('7'),
                TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition.NONE, "",
                NOW.plusSeconds(2), NOW.plusSeconds(3));
    }

    private TestSuiteStabilityAttemptCancellationJournal.Entry cancellation(Fixture fixture) {
        TestSuiteStabilityPhysicalAttemptIdentity identity = fixture.identity();
        TestSuiteStabilityAttemptCancellationCommand command =
                TestSuiteStabilityAttemptCancellationCommand.create(
                        objectMapper, identity.tenantId(), identity.environmentId(),
                        identity.jobId(), identity.attemptId(), identity.ownerId(),
                        identity.leaseEpoch(), identity.runtimeBindingFingerprint(),
                        TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED,
                        NOW, NOW.plusSeconds(30), challenge('c'));
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                new TestSuiteStabilityAttemptCancellationReceipt(
                        TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), identity.providerId(),
                        identity.deploymentId(), identity.attemptId(), identity.leaseEpoch(), 9,
                        identity.isolationMode(),
                        TestSuiteStabilityAttemptCancellationReceipt.Outcome.TERMINATED,
                        TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.PROCESS_KILL,
                        fixture.state().receipt().processIdentityFingerprint(),
                        fixture.state().receipt().runtimeStateFingerprint(), NOW.plusSeconds(2));
        var attestation = new TestSuiteStabilityAttemptCancellationReceipt.Attestation(
                TestSuiteStabilityAttemptCancellationReceipt.Attestation.SCHEMA_VERSION,
                receipt, "key-a", signature('c'));
        return new TestSuiteStabilityAttemptCancellationJournal.Entry(
                TestSuiteStabilityAttemptCancellationJournal.Entry.SCHEMA_VERSION,
                command, cancellationDescriptor(),
                TestSuiteStabilityAttemptCancellationJournal.Status.CONFIRMED,
                Optional.of(attestation), NOW, NOW.plusSeconds(2), fingerprint('9'));
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand command(
            Fixture fixture,
            Optional<TestSuiteStabilityAttemptCancellationJournal.Entry> cancellation,
            String parentRun,
            String parentEvidence) {
        return TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand.create(
                objectMapper, fixture.reservation(), fixture.start(), fixture.observation(),
                fixture.state(), cancellation, parentRun, parentEvidence);
    }

    private TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Projection projection(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand command,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ProjectionStatus status) {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.QueueDecision decision =
                switch (command.terminalDisposition()) {
                    case SUCCEEDED -> TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                            .QueueDecision.SUCCEEDED;
                    case CANCELLED -> TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                            .QueueDecision.CANCELLED;
                    case TIMED_OUT -> TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                            .QueueDecision.EXPIRED;
                    case FAILED, PROVIDER_ABORTED ->
                            TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
                                    .QueueDecision.FAILED;
                    case NONE -> throw new IllegalArgumentException("terminal command required");
                };
        TestSuiteStabilityJobRecord.Status jobStatus = switch (decision) {
            case REQUEUED -> TestSuiteStabilityJobRecord.Status.QUEUED;
            case SUCCEEDED -> TestSuiteStabilityJobRecord.Status.SUCCEEDED;
            case FAILED -> TestSuiteStabilityJobRecord.Status.FAILED;
            case CANCELLED -> TestSuiteStabilityJobRecord.Status.CANCELLED;
            case EXPIRED -> TestSuiteStabilityJobRecord.Status.EXPIRED;
        };
        var queue = new TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.QueueResult(
                command.jobId(), jobStatus, 1, NOW.plusSeconds(5), command.leaseEpoch(),
                command.parentStabilityRunId(), command.parentEvidenceFingerprint(),
                jobStatus == TestSuiteStabilityJobRecord.Status.SUCCEEDED
                        ? "" : "RG.TEST.STABILITY_ATTEMPT_TERMINAL",
                fingerprint('c'));
        var entry = new TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Entry(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Entry.SCHEMA_VERSION,
                command, decision, queue, NOW.plusSeconds(5), fingerprint('d'));
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.Projection(
                status, entry);
    }

    private static TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor startDescriptor() {
        return new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor.SCHEMA_VERSION,
                "provider-a", "deployment-a", "key-a", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofMillis(100));
    }

    private static TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor
            observationDescriptor() {
        return new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor.SCHEMA_VERSION,
                "provider-a", "deployment-a", "key-a", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofMillis(100), Duration.ofMinutes(5));
    }

    private static TestSuiteStabilityAttemptCancellationAuthority.Descriptor
            cancellationDescriptor() {
        return new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                TestSuiteStabilityAttemptCancellationAuthority.Descriptor.SCHEMA_VERSION,
                "provider-a", "deployment-a", "key-a", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofMillis(100));
    }

    private static String attemptId() {
        return "stability-attempt-" + "a".repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String challenge(char value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String signature(char value) {
        byte[] bytes = new byte[64];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Fixture(
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            TestSuiteStabilityPhysicalAttemptRegistry.Entry reservation,
            TestSuiteStabilityPhysicalAttemptStartJournal.Entry start,
            TestSuiteStabilityPhysicalAttemptObservationJournal.Entry observation,
            TestSuiteStabilityPhysicalAttemptObservationJournal.PositiveState state,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition disposition) {
    }
}
