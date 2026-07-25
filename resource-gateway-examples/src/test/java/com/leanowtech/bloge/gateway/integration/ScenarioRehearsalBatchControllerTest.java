package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchCancellationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationStatus;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchItemPage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchJob;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRetentionService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRetentionState;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchService;
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
        ScenarioRehearsalBatchEvidenceBundle evidence =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchFinalizationStatus finalization =
                mock(ScenarioRehearsalBatchFinalizationStatus.class);
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
        when(evidence.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchEvidenceBundle.SCHEMA_VERSION);
        when(finalization.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchFinalizationStatus
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
                        .MIRROR_REHEARSAL_BATCH_EVIDENCE_READ))
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
        when(batches.page(
                "job-a", 10, 25, identity))
                .thenReturn(page);
        when(batches.evidence("job-a", identity))
                .thenReturn(Optional.of(evidence));
        when(batches.finalization("job-a", identity))
                .thenReturn(Optional.of(finalization));
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
                        batches, retention,
                        authenticator, decoder);

        assertThat(controller.submit(raw, headers).payload())
                .isSameAs(job);
        assertThat(controller.find(
                "job-a", headers).payload())
                .isSameAs(job);
        assertThat(controller.page(
                "job-a", 10, 25, headers).payload())
                .isSameAs(page);
        assertThat(controller.evidence(
                "job-a", headers).payload())
                .isSameAs(evidence);
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
                org.mockito.Mockito.times(3))
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
                        .MIRROR_REHEARSAL_BATCH_EVIDENCE_READ);
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
