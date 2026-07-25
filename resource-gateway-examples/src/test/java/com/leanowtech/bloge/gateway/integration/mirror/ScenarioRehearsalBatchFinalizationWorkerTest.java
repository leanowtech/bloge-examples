package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchFinalizationWorkerTest {
    private static final String JOB_ID =
            "scenario-batch-" + "a".repeat(64);
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a",
                    "org-a",
                    "support",
                    "test",
                    "sg");
    private final ScenarioRehearsalBatchFinalizationPolicy policy =
            ScenarioRehearsalBatchFinalizationPolicy.defaults();
    private ScenarioRehearsalBatchRepository repository;
    private ScenarioRehearsalBatchEvidencePublisher publisher;
    private ScenarioRehearsalBatchFinalizationWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(
                ScenarioRehearsalBatchRepository.class);
        publisher = mock(
                ScenarioRehearsalBatchEvidencePublisher.class);
        worker = new ScenarioRehearsalBatchFinalizationWorker(
                repository, publisher, policy);
    }

    @Test
    void returnsNoWorkWithoutCallingRemotePreparation() {
        when(repository.claimFinalization(
                "sg", "test", "finalizer-a", policy))
                .thenReturn(
                        new ScenarioRehearsalBatchRepository
                                .FinalizationAcquisition(
                                ScenarioRehearsalBatchRepository
                                        .FinalizationClaimOutcome
                                        .NO_WORK,
                                Instant.parse(
                                        "2026-07-25T00:00:00Z"),
                                null,
                                null));

        ScenarioRehearsalBatchFinalizationWorker.Turn turn =
                worker.runOnce(
                        "sg", "test", "finalizer-a");

        assertThat(turn.disposition()).isEqualTo(
                ScenarioRehearsalBatchFinalizationWorker
                        .Disposition.NO_WORK);
        assertThat(turn.jobId()).isBlank();
    }

    @Test
    void preparesOutsideTheRepositoryAndCommitsOneTerminalJob() {
        Fixture fixture = fixture();
        ScenarioRehearsalBatchEvidencePublisher.PreparedFinalization
                prepared = mock(
                ScenarioRehearsalBatchEvidencePublisher
                        .PreparedFinalization.class);
        ScenarioRehearsalBatchJob terminal =
                mock(ScenarioRehearsalBatchJob.class);
        when(terminal.jobId()).thenReturn(JOB_ID);
        when(terminal.status()).thenReturn(
                ScenarioRehearsalBatchJob.Status.SUCCEEDED);
        when(publisher.prepare(
                any(), any(), any(), any(), any(),
                any(), anyString()))
                .thenReturn(prepared);
        when(repository.completeFinalization(
                fixture.claim(), prepared))
                .thenReturn(terminal);

        ScenarioRehearsalBatchFinalizationWorker.Turn turn =
                worker.runOnce(
                        "sg", "test", "finalizer-a");

        assertThat(turn.disposition()).isEqualTo(
                ScenarioRehearsalBatchFinalizationWorker
                        .Disposition.FINALIZED);
        assertThat(turn.terminalJob()).isSameAs(terminal);
        verify(repository).completeFinalization(
                fixture.claim(), prepared);
    }

    @Test
    void retriesOnlyAClassifiedTransientPreparationFailure() {
        Fixture fixture = fixture();
        when(publisher.prepare(
                any(), any(), any(), any(), any(),
                any(), anyString()))
                .thenThrow(
                        new ScenarioRehearsalBatchFinalizationException(
                                ScenarioRehearsalBatchFinalizationException
                                        .Reason.SIGNER_UNAVAILABLE));
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                retry = snapshot(
                ScenarioRehearsalBatchRepository
                        .FinalizationState.RETRY_WAIT,
                fixture.claim().attemptCount());
        when(repository.releaseFinalization(
                fixture.claim(),
                ScenarioRehearsalBatchFinalizationException
                        .Reason.SIGNER_UNAVAILABLE,
                policy))
                .thenReturn(retry);

        ScenarioRehearsalBatchFinalizationWorker.Turn turn =
                worker.runOnce(
                        "sg", "test", "finalizer-a");

        assertThat(turn.disposition()).isEqualTo(
                ScenarioRehearsalBatchFinalizationWorker
                        .Disposition.RETRY_SCHEDULED);
        assertThat(turn.failureCode()).isEqualTo(
                ScenarioRehearsalBatchFinalizationException
                        .Reason.SIGNER_UNAVAILABLE
                        .failureCode());
    }

    @Test
    void quarantinesInvalidPreparedMaterialWithoutExceptionGuessing() {
        Fixture fixture = fixture();
        when(publisher.prepare(
                any(), any(), any(), any(), any(),
                any(), anyString()))
                .thenThrow(
                        new ScenarioRehearsalBatchFinalizationException(
                                ScenarioRehearsalBatchFinalizationException
                                        .Reason.SIGNATURE_INVALID));
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                quarantined = snapshot(
                ScenarioRehearsalBatchRepository
                        .FinalizationState.QUARANTINED,
                fixture.claim().attemptCount());
        when(repository.releaseFinalization(
                fixture.claim(),
                ScenarioRehearsalBatchFinalizationException
                        .Reason.SIGNATURE_INVALID,
                policy))
                .thenReturn(quarantined);

        ScenarioRehearsalBatchFinalizationWorker.Turn turn =
                worker.runOnce(
                        "sg", "test", "finalizer-a");

        assertThat(turn.disposition()).isEqualTo(
                ScenarioRehearsalBatchFinalizationWorker
                        .Disposition.QUARANTINED);
        assertThat(turn.failureCode()).isEqualTo(
                ScenarioRehearsalBatchFinalizationException
                        .Reason.SIGNATURE_INVALID
                        .failureCode());
    }

    @Test
    void reportsLeaseLossWhenAnotherReplicaAdvancesTheFence() {
        Fixture fixture = fixture();
        when(publisher.prepare(
                any(), any(), any(), any(), any(),
                any(), anyString()))
                .thenThrow(new IllegalStateException(
                        "control unavailable"));
        when(repository.releaseFinalization(
                fixture.claim(),
                ScenarioRehearsalBatchFinalizationException
                        .Reason.CONTROL_UNAVAILABLE,
                policy))
                .thenThrow(new IllegalStateException(
                        "stale"));
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                takeover = snapshot(
                ScenarioRehearsalBatchRepository
                        .FinalizationState.SIGNING,
                fixture.claim().attemptCount() + 1);
        long takeoverEpoch =
                fixture.claim().leaseEpoch() + 1;
        when(takeover.leaseEpoch()).thenReturn(
                takeoverEpoch);
        when(takeover.leaseOwner()).thenReturn(
                "finalizer-b");
        when(repository.findFinalization(SCOPE, JOB_ID))
                .thenReturn(Optional.of(takeover));

        ScenarioRehearsalBatchFinalizationWorker.Turn turn =
                worker.runOnce(
                        "sg", "test", "finalizer-a");

        assertThat(turn.disposition()).isEqualTo(
                ScenarioRehearsalBatchFinalizationWorker
                        .Disposition.LEASE_LOST);
        assertThat(turn.snapshot()).isSameAs(takeover);
    }

    private Fixture fixture() {
        ScenarioRehearsalBatchRepository.FinalizationIntent intent =
                mock(
                        ScenarioRehearsalBatchRepository
                                .FinalizationIntent.class);
        ScenarioRehearsalBatchJob terminal =
                mock(ScenarioRehearsalBatchJob.class);
        when(terminal.jobId()).thenReturn(JOB_ID);
        when(terminal.scope()).thenReturn(SCOPE);
        when(intent.terminalJob()).thenReturn(terminal);
        when(intent.request()).thenReturn(
                mock(ScenarioRehearsalBatchRequest.class));
        when(intent.manifest()).thenReturn(
                mock(ScenarioRehearsalBatchManifest.class));
        when(intent.items()).thenReturn(
                List.of(
                        mock(
                                ScenarioRehearsalBatchItemPage
                                        .Item.class)));
        when(intent.signingRequestId())
                .thenReturn("scenario-batch-finalization:"
                        + "b".repeat(64));
        when(intent.retainUntil()).thenReturn(
                Instant.parse("2026-08-25T00:00:00Z"));
        ScenarioRehearsalBatchRepository.FinalizationClaim claim =
                mock(
                        ScenarioRehearsalBatchRepository
                                .FinalizationClaim.class);
        when(claim.intent()).thenReturn(intent);
        when(claim.ownerId()).thenReturn("finalizer-a");
        when(claim.leaseEpoch()).thenReturn(1L);
        when(claim.attemptCount()).thenReturn(1);
        when(claim.signingStartedAt()).thenReturn(
                Instant.parse("2026-07-25T00:00:00Z"));
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                snapshot = snapshot(
                ScenarioRehearsalBatchRepository
                        .FinalizationState.SIGNING,
                1);
        ScenarioRehearsalBatchRepository.FinalizationAcquisition
                acquisition =
                new ScenarioRehearsalBatchRepository
                        .FinalizationAcquisition(
                        ScenarioRehearsalBatchRepository
                                .FinalizationClaimOutcome.ACQUIRED,
                        Instant.parse("2026-07-25T00:00:00Z"),
                        snapshot,
                        claim);
        when(repository.claimFinalization(
                "sg", "test", "finalizer-a", policy))
                .thenReturn(acquisition);
        return new Fixture(claim);
    }

    private static ScenarioRehearsalBatchRepository
    .FinalizationSnapshot snapshot(
            ScenarioRehearsalBatchRepository.FinalizationState state,
            int attemptCount) {
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                snapshot = mock(
                ScenarioRehearsalBatchRepository
                        .FinalizationSnapshot.class);
        when(snapshot.state()).thenReturn(state);
        when(snapshot.jobId()).thenReturn(JOB_ID);
        when(snapshot.attemptCount()).thenReturn(attemptCount);
        when(snapshot.leaseOwner()).thenReturn("finalizer-a");
        when(snapshot.leaseEpoch()).thenReturn(1L);
        return snapshot;
    }

    private record Fixture(
            ScenarioRehearsalBatchRepository.FinalizationClaim
                    claim) {
    }
}
