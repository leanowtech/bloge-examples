package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

class ScenarioRehearsalBatchWorkbookServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-25T08:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a",
                    "org-a",
                    "support",
                    "test",
                    "sg");
    private static final String JOB_ID =
            "scenario-batch-" + "a".repeat(64);
    private static final String RUN_ID =
            "scenario-" + "b".repeat(64);
    private static final String WORKBOOK_FINGERPRINT =
            "sha256:" + "c".repeat(64);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void verifiesEverySourceBeforeProjectingAndAuditsTheRead() {
        ScenarioRehearsalBatchEvidenceRepository evidence =
                mock(
                        ScenarioRehearsalBatchEvidenceRepository
                                .class);
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .class);
        ScenarioRehearsalBatchRetentionRepository retention =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .class);
        ScenarioRehearsalRuntimeService rehearsals =
                mock(ScenarioRehearsalRuntimeService.class);
        ScenarioRehearsalBatchWorkbookService.Projector projector =
                mock(
                        ScenarioRehearsalBatchWorkbookService
                                .Projector.class);
        ScenarioRehearsalBatchEvidenceBundle stored =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchEvidenceBundle verifiedBundle =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchEvidenceIntegrityService
                .VerifiedBundle verification =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .VerifiedBundle.class);
        ScenarioRehearsalBatchEvidenceIndex index =
                mock(ScenarioRehearsalBatchEvidenceIndex.class);
        ScenarioRehearsalBatchItemPage.Item item =
                mock(
                        ScenarioRehearsalBatchItemPage.Item
                                .class);
        ScenarioRehearsalWorkbookSeed child =
                mock(ScenarioRehearsalWorkbookSeed.class);
        ScenarioRehearsalBatchRetentionState state =
                mock(ScenarioRehearsalBatchRetentionState.class);
        ScenarioRehearsalBatchRetentionEvent event =
                mock(ScenarioRehearsalBatchRetentionEvent.class);
        ScenarioRehearsalBatchWorkbookSeed projected =
                mock(ScenarioRehearsalBatchWorkbookSeed.class);
        ScenarioRehearsalBatchWorkbookSeed sealed =
                mock(ScenarioRehearsalBatchWorkbookSeed.class);
        VisualEvidenceSigner signer =
                mock(VisualEvidenceSigner.class);
        VisualRunEvidenceSeal seal =
                mock(VisualRunEvidenceSeal.class);
        String attestation =
                "sha256:" + "d".repeat(64);
        when(evidence.find(SCOPE, JOB_ID))
                .thenReturn(Optional.of(stored));
        when(integrity.requireVerified(stored))
                .thenReturn(verification);
        when(verification.bundle())
                .thenReturn(verifiedBundle);
        when(verifiedBundle.index()).thenReturn(index);
        when(index.items()).thenReturn(List.of(item));
        when(item.runId()).thenReturn(RUN_ID);
        when(item.workbookSeedFingerprint())
                .thenReturn(WORKBOOK_FINGERPRINT);
        when(rehearsals.workbookSeed(
                RUN_ID, identity()))
                .thenReturn(child);
        when(child.runId()).thenReturn(RUN_ID);
        when(child.seedFingerprint())
                .thenReturn(WORKBOOK_FINGERPRINT);
        when(retention.find(SCOPE, JOB_ID))
                .thenReturn(Optional.of(state));
        when(retention.events(SCOPE, JOB_ID))
                .thenReturn(List.of(event));
        when(projector.project(
                mapper,
                verifiedBundle,
                state,
                List.of(event),
                Map.of(RUN_ID, child)))
                .thenReturn(projected);
        when(projected.attestationMaterialFingerprint(
                mapper)).thenReturn(attestation);
        when(projected.seedFingerprint())
                .thenReturn(WORKBOOK_FINGERPRINT);
        when(signer.seal(
                attestation,
                "scenario-batch-workbook:"
                        + JOB_ID + ":"
                        + WORKBOOK_FINGERPRINT))
                .thenReturn(seal);
        when(signer.verify(seal, attestation))
                .thenReturn(
                        new VisualEvidenceSigner.Verification(
                                true, "VERIFIED", ""));
        when(projected.withWorkbookSeal(seal))
                .thenReturn(sealed);
        List<MirrorOperationAuditEvent> audit =
                new ArrayList<>();
        ScenarioRehearsalBatchWorkbookService service =
                new ScenarioRehearsalBatchWorkbookService(
                        evidence,
                        integrity,
                        retention,
                        rehearsals,
                        mapper,
                        observations(audit),
                        signer,
                        projector);

        assertThat(service.workbookSeed(
                JOB_ID, identity()))
                .isSameAs(sealed);

        ArgumentCaptor<Map<String, ScenarioRehearsalWorkbookSeed>>
                children = ArgumentCaptor.forClass(Map.class);
        verify(projector).project(
                any(),
                any(),
                any(),
                any(),
                children.capture());
        assertThat(children.getValue())
                .containsExactlyEntriesOf(
                        Map.of(RUN_ID, child));
        verify(projected).verify(mapper);
        verify(sealed).verify(mapper);
        assertThat(audit).singleElement()
                .satisfies(value -> {
                    assertThat(value.operation()).isEqualTo(
                            MirrorOperationAuditEvent.Operation
                                    .SCENARIO_REHEARSAL_BATCH_WORKBOOK_READ);
                    assertThat(value.outcome()).isEqualTo(
                            MirrorOperationAuditEvent.Outcome
                                    .SUCCEEDED);
                });
    }

    @Test
    void rejectsUnauthorizedPurposeBeforeReadingAnySource() {
        ScenarioRehearsalBatchEvidenceRepository evidence =
                mock(
                        ScenarioRehearsalBatchEvidenceRepository
                                .class);
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .class);
        ScenarioRehearsalBatchRetentionRepository retention =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .class);
        ScenarioRehearsalRuntimeService rehearsals =
                mock(ScenarioRehearsalRuntimeService.class);
        VisualEvidenceSigner signer =
                mock(VisualEvidenceSigner.class);
        ScenarioRehearsalBatchWorkbookService service =
                new ScenarioRehearsalBatchWorkbookService(
                        evidence,
                        integrity,
                        retention,
                        rehearsals,
                        mapper,
                        MirrorOperationObservability.noop(),
                        signer);

        assertThatThrownBy(() ->
                service.workbookSeed(
                        JOB_ID,
                        identity("CHANGE_SYNC")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException)
                                failure).problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.READ_PURPOSE_REQUIRED"));
        verifyNoInteractions(
                evidence,
                integrity,
                retention,
                rehearsals,
                signer);
    }

    @Test
    void mapsInvalidBatchSignatureToAStableClosureConflict() {
        ScenarioRehearsalBatchEvidenceRepository evidence =
                mock(
                        ScenarioRehearsalBatchEvidenceRepository
                                .class);
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .class);
        ScenarioRehearsalBatchEvidenceBundle stored =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        when(evidence.find(SCOPE, JOB_ID))
                .thenReturn(Optional.of(stored));
        when(integrity.requireVerified(stored))
                .thenThrow(new IllegalArgumentException(
                        "signature invalid"));
        ScenarioRehearsalRuntimeService rehearsals =
                mock(ScenarioRehearsalRuntimeService.class);
        ScenarioRehearsalBatchWorkbookService service =
                new ScenarioRehearsalBatchWorkbookService(
                        evidence,
                        integrity,
                        mock(
                                ScenarioRehearsalBatchRetentionRepository
                                        .class),
                        rehearsals,
                        mapper,
                        MirrorOperationObservability.noop(),
                        mock(VisualEvidenceSigner.class));

        assertThatThrownBy(() ->
                service.workbookSeed(
                        JOB_ID, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException)
                                failure).problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.REHEARSAL_BATCH.WORKBOOK_CLOSURE_INVALID"));
        verify(rehearsals, never())
                .workbookSeed(any(), any());
    }

    @Test
    void reportsMissingEvidenceWithoutConsultingChildRuntime() {
        ScenarioRehearsalBatchEvidenceRepository evidence =
                mock(
                        ScenarioRehearsalBatchEvidenceRepository
                                .class);
        ScenarioRehearsalRuntimeService rehearsals =
                mock(ScenarioRehearsalRuntimeService.class);
        when(evidence.find(SCOPE, JOB_ID))
                .thenReturn(Optional.empty());
        ScenarioRehearsalBatchWorkbookService service =
                new ScenarioRehearsalBatchWorkbookService(
                        evidence,
                        mock(
                                ScenarioRehearsalBatchEvidenceIntegrityService
                                        .class),
                        mock(
                                ScenarioRehearsalBatchRetentionRepository
                                        .class),
                        rehearsals,
                        mapper,
                        MirrorOperationObservability.noop(),
                        mock(VisualEvidenceSigner.class));

        assertThatThrownBy(() ->
                service.workbookSeed(
                        JOB_ID, identity()))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException)
                                failure).problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.REHEARSAL_BATCH.WORKBOOK_NOT_FOUND"));
        verifyNoInteractions(rehearsals);
    }

    @Test
    void failsClosedWhenWorkbookSigningAuthorityIsUnavailable() {
        ScenarioRehearsalBatchEvidenceRepository evidence =
                mock(
                        ScenarioRehearsalBatchEvidenceRepository
                                .class);
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .class);
        ScenarioRehearsalBatchRetentionRepository retention =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .class);
        ScenarioRehearsalBatchEvidenceBundle stored =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchEvidenceBundle verified =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchEvidenceIntegrityService
                .VerifiedBundle verification =
                mock(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .VerifiedBundle.class);
        ScenarioRehearsalBatchEvidenceIndex index =
                mock(ScenarioRehearsalBatchEvidenceIndex.class);
        ScenarioRehearsalBatchRetentionState state =
                mock(ScenarioRehearsalBatchRetentionState.class);
        ScenarioRehearsalBatchRetentionEvent event =
                mock(ScenarioRehearsalBatchRetentionEvent.class);
        ScenarioRehearsalBatchWorkbookSeed material =
                mock(ScenarioRehearsalBatchWorkbookSeed.class);
        ScenarioRehearsalBatchWorkbookService.Projector projector =
                mock(
                        ScenarioRehearsalBatchWorkbookService
                                .Projector.class);
        VisualEvidenceSigner signer =
                mock(VisualEvidenceSigner.class);
        when(evidence.find(SCOPE, JOB_ID))
                .thenReturn(Optional.of(stored));
        when(integrity.requireVerified(stored))
                .thenReturn(verification);
        when(verification.bundle()).thenReturn(verified);
        when(verified.index()).thenReturn(index);
        when(index.items()).thenReturn(List.of());
        when(retention.find(SCOPE, JOB_ID))
                .thenReturn(Optional.of(state));
        when(retention.events(SCOPE, JOB_ID))
                .thenReturn(List.of(event));
        when(projector.project(
                mapper, verified, state,
                List.of(event), Map.of()))
                .thenReturn(material);
        when(material.attestationMaterialFingerprint(
                mapper)).thenReturn(fingerprint('e'));
        when(material.seedFingerprint())
                .thenReturn(WORKBOOK_FINGERPRINT);
        when(signer.seal(any(), any()))
                .thenThrow(new IllegalStateException(
                        "kms unavailable"));
        ScenarioRehearsalBatchWorkbookService service =
                new ScenarioRehearsalBatchWorkbookService(
                        evidence,
                        integrity,
                        retention,
                        mock(ScenarioRehearsalRuntimeService.class),
                        mapper,
                        MirrorOperationObservability.noop(),
                        signer,
                        projector);

        assertThatThrownBy(() ->
                service.workbookSeed(
                        JOB_ID, identity()))
                .isInstanceOf(
                        IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(
                                ((IntegrationProblemException)
                                        failure).problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.REHEARSAL_BATCH.WORKBOOK_VERIFICATION_UNAVAILABLE"));
    }

    private IntegrationRequestContext identity() {
        return identity(
                "GOVERNANCE_EVIDENCE_INGESTION");
    }

    private IntegrationRequestContext identity(
            String purpose) {
        return new IntegrationRequestContext(
                SCOPE.tenantId(),
                SCOPE.organizationId(),
                SCOPE.projectId(),
                SCOPE.environmentId(),
                SCOPE.region(),
                "SERVICE",
                "aneke-tool-studio",
                "",
                purpose,
                "corr-batch-workbook",
                Set.of("governance-reader"),
                "RESTRICTED",
                "");
    }

    private static MirrorOperationObservability observations(
            List<MirrorOperationAuditEvent> events) {
        MirrorOperationAuditRepository audit =
                new MirrorOperationAuditRepository() {
                    @Override
                    public MirrorOperationAuditEvent append(
                            MirrorOperationAuditEvent event) {
                        MirrorOperationAuditEvent persisted =
                                event.persisted(
                                        events.size() + 1L,
                                        NOW);
                        events.add(persisted);
                        return persisted;
                    }

                    @Override
                    public List<MirrorOperationAuditEvent> recent(
                            CapabilitySnapshot.Scope scope,
                            int limit) {
                        return List.copyOf(events);
                    }
                };
        return new MirrorOperationObservability(
                audit,
                MirrorOperationTelemetry.noop(),
                () -> 0L);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
