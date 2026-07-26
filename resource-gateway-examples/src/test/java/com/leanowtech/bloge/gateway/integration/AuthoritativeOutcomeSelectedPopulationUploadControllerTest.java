package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationChunk;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadRepository;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeSelectedPopulationUploadControllerTest {

    @Test
    void authenticatesBeforeBoundedDecodeAndExposesResumableLifecycle() {
        AuthoritativeOutcomeSelectedPopulationUploadService service =
                mock(
                        AuthoritativeOutcomeSelectedPopulationUploadService
                                .class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        AuthoritativeOutcomeSelectedPopulationRequestDecoder decoder =
                mock(
                        AuthoritativeOutcomeSelectedPopulationRequestDecoder
                                .class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        AuthoritativeOutcomeSelectedPopulationUploadRequest request =
                mock(
                        AuthoritativeOutcomeSelectedPopulationUploadRequest
                                .class);
        AuthoritativeOutcomeSelectedPopulationChunk chunk =
                mock(
                        AuthoritativeOutcomeSelectedPopulationChunk
                                .class);
        AuthoritativeOutcomeSelectedPopulationAdmission population =
                mock(
                        AuthoritativeOutcomeSelectedPopulationAdmission
                                .class);
        AuthoritativeOutcomeSelectedPopulationUploadStatus open =
                status(
                        AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.OPEN,
                        0,
                        0,
                        0,
                        "");
        AuthoritativeOutcomeSelectedPopulationUploadStatus complete =
                status(
                        AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.OPEN,
                        2,
                        3_072,
                        -1,
                        "");
        AuthoritativeOutcomeSelectedPopulationUploadStatus aborted =
                status(
                        AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.ABORTED,
                        0,
                        0,
                        0,
                        "");
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_INGEST))
                .thenReturn(identity);
        when(decoder.decodeUpload(body, identity))
                .thenReturn(request);
        when(decoder.decodeUploadChunk(body, identity))
                .thenReturn(chunk);
        when(service.begin(request, identity))
                .thenReturn(
                        new AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Admission(open, false));
        when(service.stageChunk(
                "upload-1",
                1,
                chunk,
                body.length,
                identity))
                .thenReturn(
                        new AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .ChunkAdmission(
                                complete,
                                1,
                                "sha256:"
                                        + "b".repeat(64),
                                false));
        when(service.find(
                "upload-1", identity))
                .thenReturn(
                        new AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Upload(
                                request,
                                complete,
                                Optional.empty()));
        when(service.finalizeUpload(
                "upload-1", identity))
                .thenReturn(population);
        when(population.schemaVersion())
                .thenReturn(
                        AuthoritativeOutcomeSelectedPopulationAdmission
                                .SCHEMA_VERSION);
        when(service.abort(
                "upload-1", identity))
                .thenReturn(
                        new AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Upload(
                                request,
                                aborted,
                                Optional.empty()));

        AuthoritativeOutcomeSelectedPopulationUploadController controller =
                new
                        AuthoritativeOutcomeSelectedPopulationUploadController(
                        service,
                        authenticator,
                        decoder);

        var begin = controller.begin(body, headers);
        assertThat(begin.getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(begin.getHeaders().getLocation())
                .hasToString(
                        "/api/mirror/outcome-selected-populations/uploads/upload-1");
        assertThat(controller.stageChunk(
                "upload-1",
                1,
                body,
                headers).payload().status())
                .isEqualTo(complete);
        assertThat(controller.find(
                "upload-1", headers).payload())
                .isEqualTo(complete);
        assertThat(controller.finalizeUpload(
                "upload-1", headers).payload())
                .isSameAs(population);
        assertThat(controller.abort(
                "upload-1", headers).payload())
                .isEqualTo(aborted);

        InOrder order = inOrder(
                authenticator, decoder, service);
        order.verify(authenticator)
                .authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_POPULATION_INGEST);
        order.verify(decoder)
                .decodeUpload(body, identity);
        order.verify(service)
                .begin(request, identity);
        order.verify(authenticator)
                .authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_POPULATION_INGEST);
        order.verify(decoder)
                .decodeUploadChunk(body, identity);
        order.verify(service)
                .stageChunk(
                        "upload-1",
                        1,
                        chunk,
                        body.length,
                        identity);
    }

    @Test
    void authenticationFailureNeverDecodesChunkBody() {
        AuthoritativeOutcomeSelectedPopulationUploadService service =
                mock(
                        AuthoritativeOutcomeSelectedPopulationUploadService
                                .class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        AuthoritativeOutcomeSelectedPopulationRequestDecoder decoder =
                mock(
                        AuthoritativeOutcomeSelectedPopulationRequestDecoder
                                .class);
        HttpHeaders headers = new HttpHeaders();
        RuntimeException rejected =
                new IllegalStateException("unauthorized");
        when(authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_INGEST))
                .thenThrow(rejected);
        AuthoritativeOutcomeSelectedPopulationUploadController controller =
                new
                        AuthoritativeOutcomeSelectedPopulationUploadController(
                        service,
                        authenticator,
                        decoder);

        assertThatThrownBy(() ->
                controller.stageChunk(
                        "upload-1",
                        0,
                        "{}".getBytes(
                                StandardCharsets.UTF_8),
                        headers))
                .isSameAs(rejected);
        verifyNoInteractions(decoder, service);
    }

    private static AuthoritativeOutcomeSelectedPopulationUploadStatus
    status(
            AuthoritativeOutcomeSelectedPopulationUploadStatus.State
                    state,
            int received,
            long bytes,
            int nextMissing,
            String fingerprint) {
        Instant now =
                Instant.parse("2026-07-27T06:30:00Z");
        return new AuthoritativeOutcomeSelectedPopulationUploadStatus(
                "",
                "upload-1",
                "sha256:" + "a".repeat(64),
                "refund-population",
                1,
                state,
                2,
                received,
                bytes,
                nextMissing,
                0,
                now,
                now,
                now.plusSeconds(3_600),
                Instant.EPOCH,
                fingerprint);
    }
}
