package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchCancellationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationRemediationReceipt;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationRemediationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationHealth;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationStatus;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchItemPage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchJob;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchJobPage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRetentionService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRetentionState;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchWorkbookSeed;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchWorkbookService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalLegalHoldCommand;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalPurgeCommand;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchControllerTest {

    @Test
    void authenticatesSubmitReadPageAndCancelWithDedicatedOperations() {
        ScenarioRehearsalBatchService batches =
                mock(ScenarioRehearsalBatchService.class);
        ScenarioRehearsalBatchWorkbookService workbooks =
                mock(ScenarioRehearsalBatchWorkbookService.class);
        ScenarioRehearsalBatchRetentionService retention =
                mock(ScenarioRehearsalBatchRetentionService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ScenarioArtifactRequestDecoder decoder =
                mock(ScenarioArtifactRequestDecoder.class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        ScenarioRehearsalBatchRequest request =
                mock(ScenarioRehearsalBatchRequest.class);
        ScenarioRehearsalBatchCancellationRequest cancellation =
                new ScenarioRehearsalBatchCancellationRequest(
                        "",
                        "cancel-001",
                        "OWNER_REQUEST");
        ScenarioRehearsalBatchJob job =
                mock(ScenarioRehearsalBatchJob.class);
        ScenarioRehearsalBatchItemPage page =
                mock(ScenarioRehearsalBatchItemPage.class);
        ScenarioRehearsalBatchJobPage jobs =
                mock(ScenarioRehearsalBatchJobPage.class);
        ScenarioRehearsalBatchEvidenceBundle evidence =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchWorkbookSeed workbook =
                mock(ScenarioRehearsalBatchWorkbookSeed.class);
        ScenarioRehearsalBatchFinalizationStatus finalization =
                mock(ScenarioRehearsalBatchFinalizationStatus.class);
        ScenarioRehearsalBatchFinalizationRemediationRequest
                remediation =
                mock(
                        ScenarioRehearsalBatchFinalizationRemediationRequest
                                .class);
        ScenarioRehearsalBatchFinalizationRemediationReceipt
                remediationReceipt =
                mock(
                        ScenarioRehearsalBatchFinalizationRemediationReceipt
                                .class);
        ScenarioRehearsalBatchFinalizationHealth
                finalizationHealth =
                mock(
                        ScenarioRehearsalBatchFinalizationHealth
                                .class);
        ScenarioRehearsalBatchRetentionState retentionState =
                mock(ScenarioRehearsalBatchRetentionState.class);
        ScenarioRehearsalLegalHoldCommand hold =
                new ScenarioRehearsalLegalHoldCommand(
                        "", "hold-command", "legal-a",
                        "RG.MIRROR.REHEARSAL_BATCH.LEGAL_HOLD");
        ScenarioRehearsalPurgeCommand purge =
                new ScenarioRehearsalPurgeCommand(
                        "", "purge-command",
                        "RG.MIRROR.REHEARSAL_BATCH.RETENTION_EXPIRED");
        HttpHeaders headers = new HttpHeaders();
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
        when(job.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchJob.SCHEMA_VERSION);
        when(page.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchItemPage.SCHEMA_VERSION);
        when(jobs.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchJobPage.SCHEMA_VERSION);
        when(evidence.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchEvidenceBundle.SCHEMA_VERSION);
        when(workbook.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchWorkbookSeed.SCHEMA_VERSION);
        when(finalization.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchFinalizationStatus
                        .SCHEMA_VERSION);
        when(remediationReceipt.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchFinalizationRemediationReceipt
                        .SCHEMA_VERSION);
        when(finalizationHealth.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchFinalizationHealth
                        .SCHEMA_VERSION);
        when(retentionState.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchRetentionState.SCHEMA_VERSION);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_SUBMIT))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_CANCEL))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_FINALIZATION_REMEDIATE))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_FINALIZATION_HEALTH_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_EVIDENCE_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_WORKBOOK_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_RETENTION_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_LEGAL_HOLD))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_RETENTION_ADMIN))
                .thenReturn(identity);
        when(decoder.decodeBatchRequest(raw, identity))
                .thenReturn(request);
        when(decoder.decodeBatchCancellationRequest(
                raw, identity))
                .thenReturn(cancellation);
        when(decoder
                .decodeBatchFinalizationRemediationRequest(
                        raw, identity))
                .thenReturn(remediation);
        when(decoder.decodeLegalHoldCommand(raw, identity))
                .thenReturn(hold);
        when(decoder.decodePurgeCommand(raw, identity))
                .thenReturn(purge);
        when(batches.submit(request, identity))
                .thenReturn(
                        new ScenarioRehearsalBatchRepository
                                .SubmissionResult(job, false));
        when(batches.find("job-a", identity))
                .thenReturn(Optional.of(job));
        when(batches.list("", "", 25, identity))
                .thenReturn(jobs);
        when(batches.page(
                "job-a", 10, 25, identity))
                .thenReturn(page);
        when(batches.evidence("job-a", identity))
                .thenReturn(Optional.of(evidence));
        when(workbooks.workbookSeed("job-a", identity))
                .thenReturn(workbook);
        when(batches.finalization("job-a", identity))
                .thenReturn(Optional.of(finalization));
        when(batches.remediateFinalization(
                "job-a", remediation, identity))
                .thenReturn(
                        new ScenarioRehearsalBatchRepository
                                .FinalizationRemediationResult(
                                remediationReceipt, false));
        when(batches.finalizationHealth(identity))
                .thenReturn(finalizationHealth);
        when(batches.cancel(
                "job-a",
                cancellation.commandId(),
                cancellation.reasonCode(),
                identity))
                .thenReturn(
                        new ScenarioRehearsalBatchRepository
                                .SubmissionResult(job, false));
        when(retention.find("job-a", identity))
                .thenReturn(retentionState);
        when(retention.placeHold(
                "job-a", hold, identity))
                .thenReturn(retentionState);
        when(retention.releaseHold(
                "job-a", hold, identity))
                .thenReturn(retentionState);
        when(retention.purge(
                "job-a", purge, identity))
                .thenReturn(retentionState);
        ScenarioRehearsalBatchController controller =
                new ScenarioRehearsalBatchController(
                        batches, workbooks, retention,
                        authenticator, decoder);

        assertThat(controller.submit(raw, headers).payload())
                .isSameAs(job);
        assertThat(controller.find(
                "job-a", headers).payload())
                .isSameAs(job);
        assertThat(controller.list(
                25, "", "", headers))
                .satisfies(envelope -> {
                    assertThat(envelope.payloadKind()).isEqualTo(
                            "SCENARIO_REHEARSAL_BATCH_JOB_PAGE");
                    assertThat(envelope.payloadSchemaVersion())
                            .isEqualTo(
                                    ScenarioRehearsalBatchJobPage
                                            .SCHEMA_VERSION);
                    assertThat(envelope.payload()).isSameAs(jobs);
                });
        assertThat(controller.page(
                "job-a", 10, 25, headers).payload())
                .isSameAs(page);
        assertThat(controller.evidence(
                "job-a", headers).payload())
                .isSameAs(evidence);
        assertThat(controller.workbookSeed(
                "job-a", headers))
                .satisfies(envelope -> {
                    assertThat(envelope.payloadKind()).isEqualTo(
                            "SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED");
                    assertThat(envelope.payloadSchemaVersion())
                            .isEqualTo(
                                    ScenarioRehearsalBatchWorkbookSeed
                                            .SCHEMA_VERSION);
                    assertThat(envelope.payload())
                            .isSameAs(workbook);
                });
        assertThat(controller.finalization(
                "job-a", headers))
                .satisfies(envelope -> {
                    assertThat(envelope.payloadKind()).isEqualTo(
                            "SCENARIO_REHEARSAL_BATCH_FINALIZATION_STATUS");
                    assertThat(envelope.payloadSchemaVersion()).isEqualTo(
                            ScenarioRehearsalBatchFinalizationStatus
                                    .SCHEMA_VERSION);
                    assertThat(envelope.payload())
                            .isSameAs(finalization);
                });
        assertThat(controller.cancel(
                "job-a", raw, headers).payload())
                .isSameAs(job);
        assertThat(controller.remediateFinalization(
                "job-a", raw, headers))
                .satisfies(envelope -> {
                    assertThat(envelope.payloadKind()).isEqualTo(
                            "SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_RECEIPT");
                    assertThat(envelope.payloadSchemaVersion())
                            .isEqualTo(
                                    ScenarioRehearsalBatchFinalizationRemediationReceipt
                                            .SCHEMA_VERSION);
                    assertThat(envelope.payload())
                            .isSameAs(remediationReceipt);
                });
        assertThat(controller.finalizationHealth(headers))
                .satisfies(envelope -> {
                    assertThat(envelope.payloadKind()).isEqualTo(
                            "SCENARIO_REHEARSAL_BATCH_FINALIZATION_HEALTH");
                    assertThat(envelope.payloadSchemaVersion())
                            .isEqualTo(
                                    ScenarioRehearsalBatchFinalizationHealth
                                            .SCHEMA_VERSION);
                    assertThat(envelope.payload())
                            .isSameAs(finalizationHealth);
                });
        assertThat(controller.retention(
                "job-a", headers).payload())
                .isSameAs(retentionState);
        assertThat(controller.placeHold(
                "job-a", raw, headers).payload())
                .isSameAs(retentionState);
        assertThat(controller.releaseHold(
                "job-a", raw, headers).payload())
                .isSameAs(retentionState);
        assertThat(controller.purge(
                "job-a", raw, headers).payload())
                .isSameAs(retentionState);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_SUBMIT);
        verify(authenticator,
                org.mockito.Mockito.times(4))
                .authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_READ);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_CANCEL);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_FINALIZATION_REMEDIATE);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_FINALIZATION_HEALTH_READ);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_EVIDENCE_READ);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_WORKBOOK_READ);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_RETENTION_READ);
        verify(authenticator,
                org.mockito.Mockito.times(2))
                .authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_LEGAL_HOLD);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_RETENTION_ADMIN);
    }
}
