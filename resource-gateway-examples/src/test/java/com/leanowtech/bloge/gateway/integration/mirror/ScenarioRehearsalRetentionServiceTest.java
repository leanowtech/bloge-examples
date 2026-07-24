package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalRetentionServiceTest {
    private static final String RUN_ID =
            "scenario-" + "9".repeat(64);

    @Test
    void auditsEachProtectedRetentionOperationWithExactCoordinates() {
        ScenarioRehearsalRetentionRepository repository =
                mock(ScenarioRehearsalRetentionRepository.class);
        ScenarioRehearsalRetentionState state =
                mock(ScenarioRehearsalRetentionState.class);
        RecordingAuditRepository audit =
                new RecordingAuditRepository();
        ScenarioRehearsalRetentionService service =
                service(repository, audit);
        IntegrationRequestContext identity =
                MirrorPersistenceTestFixtures.identity("org-a");
        CapabilitySnapshot.Scope scope =
                MirrorPersistenceTestFixtures.scope("org-a");
        ScenarioRehearsalLegalHoldCommand hold =
                new ScenarioRehearsalLegalHoldCommand(
                        "", "hold-command-1", "legal-a",
                        "RG.MIRROR.REHEARSAL.LITIGATION");
        ScenarioRehearsalPurgeCommand purge =
                new ScenarioRehearsalPurgeCommand(
                        "", "purge-command-1",
                        "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED");
        when(repository.find(scope, RUN_ID))
                .thenReturn(Optional.of(state));
        when(repository.placeHold(
                scope, RUN_ID, hold.commandId(), hold.holdId(),
                identity.actorId(), hold.reasonCode()))
                .thenReturn(state);
        when(repository.releaseHold(
                scope, RUN_ID, hold.commandId(), hold.holdId(),
                identity.actorId(), hold.reasonCode()))
                .thenReturn(state);
        when(repository.purge(
                scope, RUN_ID, purge.commandId(),
                identity.actorId(), purge.reasonCode()))
                .thenReturn(state);

        assertThat(service.find(RUN_ID, identity)).isSameAs(state);
        assertThat(service.placeHold(
                RUN_ID, hold, identity)).isSameAs(state);
        assertThat(service.releaseHold(
                RUN_ID, hold, identity)).isSameAs(state);
        assertThat(service.purge(
                RUN_ID, purge, identity)).isSameAs(state);

        assertThat(audit.events)
                .extracting(
                        MirrorOperationAuditEvent::operation,
                        MirrorOperationAuditEvent::requestId,
                        MirrorOperationAuditEvent::runId,
                        MirrorOperationAuditEvent::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_RETENTION_READ,
                                "", RUN_ID,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_HOLD_PLACE,
                                "hold-command-1", RUN_ID,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_HOLD_RELEASE,
                                "hold-command-1", RUN_ID,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_EVIDENCE_PURGE,
                                "purge-command-1", RUN_ID,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED));
    }

    @Test
    void mapsMissingAndConflictingStateToStablePayloadFreeProblems() {
        ScenarioRehearsalRetentionRepository repository =
                mock(ScenarioRehearsalRetentionRepository.class);
        RecordingAuditRepository audit =
                new RecordingAuditRepository();
        ScenarioRehearsalRetentionService service =
                service(repository, audit);
        IntegrationRequestContext identity =
                MirrorPersistenceTestFixtures.identity("org-a");
        CapabilitySnapshot.Scope scope =
                MirrorPersistenceTestFixtures.scope("org-a");
        ScenarioRehearsalLegalHoldCommand hold =
                new ScenarioRehearsalLegalHoldCommand(
                        "", "release-command-1", "legal-a",
                        "RG.MIRROR.REHEARSAL.LITIGATION_COMPLETE");
        when(repository.find(scope, RUN_ID))
                .thenReturn(Optional.empty());
        when(repository.releaseHold(
                scope, RUN_ID, hold.commandId(), hold.holdId(),
                identity.actorId(), hold.reasonCode()))
                .thenThrow(new IllegalStateException(
                        "customer legal hold is not active"));

        assertThatThrownBy(() -> service.find(RUN_ID, identity))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(404);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.REHEARSAL.RETENTION_NOT_FOUND");
                        });
        assertThatThrownBy(() -> service.releaseHold(
                RUN_ID, hold, identity))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(409);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.REHEARSAL.RETENTION_CONFLICT");
                            assertThat(failure.toString())
                                    .doesNotContain("customer");
                        });
        assertThat(audit.events)
                .extracting(
                        MirrorOperationAuditEvent::outcome,
                        MirrorOperationAuditEvent::reasonCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.REJECTED,
                                "RG.MIRROR.REHEARSAL.RETENTION_NOT_FOUND"),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.REJECTED,
                                "RG.MIRROR.REHEARSAL.RETENTION_CONFLICT"));
    }

    @Test
    void invalidRunIdIsRejectedBeforeRepositoryAccess() {
        ScenarioRehearsalRetentionRepository repository =
                mock(ScenarioRehearsalRetentionRepository.class);
        ScenarioRehearsalRetentionService service =
                service(repository, new RecordingAuditRepository());

        assertThatThrownBy(() -> service.find(
                "run-raw", MirrorPersistenceTestFixtures.identity("org-a")))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.REHEARSAL.RUN_ID_INVALID"));
        verify(repository,
                org.mockito.Mockito.never())
                .find(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static ScenarioRehearsalRetentionService service(
            ScenarioRehearsalRetentionRepository repository,
            MirrorOperationAuditRepository audit) {
        return new ScenarioRehearsalRetentionService(
                repository,
                new MirrorOperationObservability(
                        audit,
                        MirrorOperationTelemetry.noop(),
                        () -> 0));
    }

    private static final class RecordingAuditRepository
            implements MirrorOperationAuditRepository {
        private final List<MirrorOperationAuditEvent> events =
                new ArrayList<>();

        @Override
        public MirrorOperationAuditEvent append(
                MirrorOperationAuditEvent event) {
            MirrorOperationAuditEvent persisted =
                    event.persisted(
                            events.size() + 1L,
                            Instant.parse(
                                    "2026-07-25T08:00:00Z")
                                    .plusSeconds(events.size()));
            events.add(persisted);
            return persisted;
        }

        @Override
        public List<MirrorOperationAuditEvent> recent(
                CapabilitySnapshot.Scope scope, int limit) {
            return List.copyOf(events);
        }
    }
}
