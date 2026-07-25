package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowComparison;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJob;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobLifecyclePage;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadOnlyShadowJobControllerTest {

    @Test
    void authenticatesBeforeDecodeAndUsesDedicatedReadOperations() {
        ReadOnlyShadowJobService service =
                mock(ReadOnlyShadowJobService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ReadOnlyShadowJobRequestDecoder decoder =
                mock(
                        ReadOnlyShadowJobRequestDecoder
                                .class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        ReadOnlyShadowJobRequest command =
                mock(ReadOnlyShadowJobRequest.class);
        ReadOnlyShadowJob job =
                mock(ReadOnlyShadowJob.class);
        ReadOnlyShadowComparison comparison =
                mock(ReadOnlyShadowComparison.class);
        ReadOnlyShadowJobLifecyclePage page =
                mock(
                        ReadOnlyShadowJobLifecyclePage
                                .class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(
                StandardCharsets.UTF_8);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_JOB_SUBMIT))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_JOB_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_COMPARISON_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_LIFECYCLE_READ))
                .thenReturn(identity);
        when(decoder.decode(
                body, identity)).thenReturn(command);
        when(service.submit(command, identity))
                .thenReturn(
                        new ReadOnlyShadowJobRepository
                                .Submission(
                                job, false));
        when(service.find(
                "shadow-job", identity))
                .thenReturn(job);
        when(service.findRequest(
                "shadow-job", identity))
                .thenReturn(command);
        when(service.findComparison(
                "shadow-job", identity))
                .thenReturn(comparison);
        when(service.lifecycle(
                "shadow-job",
                7,
                25,
                identity)).thenReturn(page);
        when(job.schemaVersion()).thenReturn(
                ReadOnlyShadowJob.SCHEMA_VERSION);
        when(job.jobId()).thenReturn(
                "shadow-job");
        when(command.schemaVersion()).thenReturn(
                ReadOnlyShadowJobRequest
                        .SCHEMA_VERSION);
        when(comparison.schemaVersion()).thenReturn(
                ReadOnlyShadowComparison
                        .SCHEMA_VERSION);
        when(page.schemaVersion()).thenReturn(
                ReadOnlyShadowJobLifecyclePage
                        .SCHEMA_VERSION);
        ReadOnlyShadowJobController controller =
                new ReadOnlyShadowJobController(
                        service,
                        authenticator,
                        decoder);

        var submitted = controller.submit(
                body, headers);
        assertThat(submitted.getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(submitted.getHeaders()
                .getLocation())
                .hasToString(
                        "/api/mirror/shadow-jobs/shadow-job");
        assertThat(submitted.getBody())
                .isNotNull();
        assertThat(submitted.getBody().payload())
                .isSameAs(job);
        assertThat(controller.find(
                "shadow-job", headers).payload())
                .isSameAs(job);
        assertThat(controller.findRequest(
                "shadow-job", headers).payload())
                .isSameAs(command);
        assertThat(controller.findComparison(
                "shadow-job", headers).payload())
                .isSameAs(comparison);
        assertThat(controller.lifecycle(
                "shadow-job",
                7,
                25,
                headers).payload())
                .isSameAs(page);

        InOrder order = inOrder(
                authenticator, decoder, service);
        order.verify(authenticator).authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_JOB_SUBMIT);
        order.verify(decoder).decode(
                body, identity);
        order.verify(service).submit(
                command, identity);
    }

    @Test
    void authenticationFailureNeverParsesTheBody() {
        ReadOnlyShadowJobService service =
                mock(ReadOnlyShadowJobService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ReadOnlyShadowJobRequestDecoder decoder =
                mock(
                        ReadOnlyShadowJobRequestDecoder
                                .class);
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "not-json".getBytes(
                StandardCharsets.UTF_8);
        RuntimeException rejected =
                new IllegalStateException(
                        "unauthorized");
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_SHADOW_JOB_SUBMIT))
                .thenThrow(rejected);
        ReadOnlyShadowJobController controller =
                new ReadOnlyShadowJobController(
                        service,
                        authenticator,
                        decoder);

        assertThatThrownBy(() ->
                controller.submit(body, headers))
                .isSameAs(rejected);
        verify(decoder, never())
                .decode(body, null);
        verify(service, never())
                .submit(null, null);
    }

    @Test
    void operationsSeparateExecutionFromEvidencePurposes() {
        assertThat(
                IntegrationOperation
                        .MIRROR_SHADOW_JOB_SUBMIT
                        .acceptedPurposes())
                .containsExactly("MIRROR_SHADOW");
        assertThat(
                IntegrationOperation
                        .MIRROR_SHADOW_COMPARISON_READ
                        .acceptedPurposes())
                .containsExactlyInAnyOrder(
                        "MIRROR_SHADOW",
                        "GOVERNANCE_EVIDENCE_INGESTION");
        assertThat(
                IntegrationOperation
                        .MIRROR_SHADOW_JOB_SUBMIT
                        .accepts(
                                "GOVERNANCE_EVIDENCE_INGESTION"))
                .isFalse();
    }
}
