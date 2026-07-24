package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchCancellationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchItemPage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchJob;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchService;
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
        HttpHeaders headers = new HttpHeaders();
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
        when(job.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchJob.SCHEMA_VERSION);
        when(page.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchItemPage.SCHEMA_VERSION);
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
        when(decoder.decodeBatchRequest(raw, identity))
                .thenReturn(request);
        when(decoder.decodeBatchCancellationRequest(
                raw, identity))
                .thenReturn(cancellation);
        when(batches.submit(request, identity))
                .thenReturn(
                        new ScenarioRehearsalBatchRepository
                                .SubmissionResult(job, false));
        when(batches.find("job-a", identity))
                .thenReturn(Optional.of(job));
        when(batches.page(
                "job-a", 10, 25, identity))
                .thenReturn(page);
        when(batches.cancel(
                "job-a",
                cancellation.commandId(),
                cancellation.reasonCode(),
                identity))
                .thenReturn(
                        new ScenarioRehearsalBatchRepository
                                .SubmissionResult(job, false));
        ScenarioRehearsalBatchController controller =
                new ScenarioRehearsalBatchController(
                        batches, authenticator, decoder);

        assertThat(controller.submit(raw, headers).payload())
                .isSameAs(job);
        assertThat(controller.find(
                "job-a", headers).payload())
                .isSameAs(job);
        assertThat(controller.page(
                "job-a", 10, 25, headers).payload())
                .isSameAs(page);
        assertThat(controller.cancel(
                "job-a", raw, headers).payload())
                .isSameAs(job);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_SUBMIT);
        verify(authenticator,
                org.mockito.Mockito.times(2))
                .authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_READ);
        verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_REHEARSAL_BATCH_CANCEL);
    }
}
