package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchRetentionServiceTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            MirrorPersistenceTestFixtures.scope("org-a");
    private static final String JOB_ID =
            ScenarioRehearsalBatchIdentity.derive(
                    new ObjectMapper().findAndRegisterModules(),
                    SCOPE,
                    "batch-retention-service");

    @Test
    void enforcesPurposeAndAuditsEachProtectedRetentionOperation() {
        ScenarioRehearsalBatchRetentionRepository repository =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .class);
        ScenarioRehearsalBatchRetentionState state =
                mock(ScenarioRehearsalBatchRetentionState.class);
        RecordingAuditRepository audit =
                new RecordingAuditRepository();
        ScenarioRehearsalBatchRetentionService service =
                service(repository, audit);
        IntegrationRequestContext read =
                identity("GOVERNANCE_EVIDENCE_INGESTION");
        IntegrationRequestContext legal =
                identity("LEGAL_HOLD");
        IntegrationRequestContext admin =
                identity("PAYLOAD_RETENTION_ADMIN");
        ScenarioRehearsalLegalHoldCommand hold =
                new ScenarioRehearsalLegalHoldCommand(
                        "", "hold-command-1", "legal-a",
                        "RG.MIRROR.REHEARSAL_BATCH.LITIGATION");
        ScenarioRehearsalPurgeCommand purge =
                new ScenarioRehearsalPurgeCommand(
                        "", "purge-command-1",
                        "RG.MIRROR.REHEARSAL_BATCH.RETENTION_EXPIRED");
        when(repository.find(SCOPE, JOB_ID))
                .thenReturn(Optional.of(state));
        when(repository.placeHold(
                SCOPE, JOB_ID, hold.commandId(), hold.holdId(),
                legal.actorId(), hold.reasonCode()))
                .thenReturn(state);
        when(repository.releaseHold(
                SCOPE, JOB_ID, hold.commandId(), hold.holdId(),
                legal.actorId(), hold.reasonCode()))
                .thenReturn(state);
        when(repository.purge(
                SCOPE, JOB_ID, purge.commandId(),
                admin.actorId(), purge.reasonCode()))
                .thenReturn(state);

        assertThat(service.find(
                JOB_ID, read)).isSameAs(state);
        assertThat(service.placeHold(
                JOB_ID, hold, legal)).isSameAs(state);
        assertThat(service.releaseHold(
                JOB_ID, hold, legal)).isSameAs(state);
        assertThat(service.purge(
                JOB_ID, purge, admin)).isSameAs(state);

        assertThat(audit.events)
                .extracting(
                        MirrorOperationAuditEvent::operation,
                        MirrorOperationAuditEvent::requestId,
                        MirrorOperationAuditEvent::runId,
                        MirrorOperationAuditEvent::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_BATCH_RETENTION_READ,
                                "", JOB_ID,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_BATCH_HOLD_PLACE,
                                hold.commandId(), JOB_ID,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_BATCH_HOLD_RELEASE,
                                hold.commandId(), JOB_ID,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_BATCH_EVIDENCE_PURGE,
                                purge.commandId(), JOB_ID,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED));
    }

    @Test
    void mapsMissingConflictAndWrongPurposeToStableProblems() {
        ScenarioRehearsalBatchRetentionRepository repository =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .class);
        RecordingAuditRepository audit =
                new RecordingAuditRepository();
        ScenarioRehearsalBatchRetentionService service =
                service(repository, audit);
        IntegrationRequestContext read =
                identity("GOVERNANCE_EVIDENCE_INGESTION");
        IntegrationRequestContext legal =
                identity("LEGAL_HOLD");
        ScenarioRehearsalLegalHoldCommand hold =
                new ScenarioRehearsalLegalHoldCommand(
                        "", "release-command-1", "legal-a",
                        "RG.MIRROR.REHEARSAL_BATCH.LITIGATION_COMPLETE");
        when(repository.find(SCOPE, JOB_ID))
                .thenReturn(Optional.empty());
        when(repository.releaseHold(
                SCOPE, JOB_ID, hold.commandId(), hold.holdId(),
                legal.actorId(), hold.reasonCode()))
                .thenThrow(new IllegalStateException(
                        "customer legal hold is not active"));

        assertThatThrownBy(() -> service.find(JOB_ID, read))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(404);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.REHEARSAL_BATCH.RETENTION_NOT_FOUND");
                        });
        assertThatThrownBy(() -> service.releaseHold(
                JOB_ID, hold, legal))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(409);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.REHEARSAL_BATCH.RETENTION_CONFLICT");
                            assertThat(failure.toString())
                                    .doesNotContain("customer");
                        });
        assertThatThrownBy(() -> service.placeHold(
                JOB_ID,
                hold,
                identity("MIRROR_REHEARSAL")))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(403);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.LEGAL_HOLD_PURPOSE_REQUIRED");
                        });
        assertThat(audit.events)
                .extracting(
                        MirrorOperationAuditEvent::outcome,
                        MirrorOperationAuditEvent::reasonCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.REJECTED,
                                "RG.MIRROR.REHEARSAL_BATCH.RETENTION_NOT_FOUND"),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.REJECTED,
                                "RG.MIRROR.REHEARSAL_BATCH.RETENTION_CONFLICT"),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Outcome.REJECTED,
                                "RG.MIRROR.LEGAL_HOLD_PURPOSE_REQUIRED"));
    }

    @Test
    void invalidJobIdIsRejectedBeforeRepositoryAccess() {
        ScenarioRehearsalBatchRetentionRepository repository =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .class);
        ScenarioRehearsalBatchRetentionService service =
                service(repository, new RecordingAuditRepository());

        assertThatThrownBy(() -> service.find(
                "job-raw",
                identity("GOVERNANCE_EVIDENCE_INGESTION")))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.REHEARSAL_BATCH.JOB_ID_INVALID"));
        verify(repository,
                org.mockito.Mockito.never())
                .find(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static ScenarioRehearsalBatchRetentionService service(
            ScenarioRehearsalBatchRetentionRepository repository,
            MirrorOperationAuditRepository audit) {
        return new ScenarioRehearsalBatchRetentionService(
                repository,
                new MirrorOperationObservability(
                        audit,
                        MirrorOperationTelemetry.noop(),
                        () -> 0));
    }

    private static IntegrationRequestContext identity(
            String purpose) {
        return new IntegrationRequestContext(
                SCOPE.tenantId(),
                SCOPE.organizationId(),
                SCOPE.projectId(),
                SCOPE.environmentId(),
                SCOPE.region(),
                "SERVICE",
                "governance-owner",
                "",
                purpose,
                "corr-batch-retention",
                Set.of("quality"),
                "RESTRICTED",
                "");
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
