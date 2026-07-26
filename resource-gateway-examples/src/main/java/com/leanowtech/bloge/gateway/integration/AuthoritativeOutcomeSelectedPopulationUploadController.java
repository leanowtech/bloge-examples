package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationChunk;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadChunkAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadRequest;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSelectedPopulationUploadStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;

/**
 * Protected auth-before-decode transport for resumable selected-population uploads.
 */
@RestController
@RequestMapping(
        "/api/mirror/outcome-selected-populations/uploads")
@Profile("!production & (test | staging)")
@ConditionalOnBean(
        AuthoritativeOutcomeSelectedPopulationUploadService
                .class)
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class
AuthoritativeOutcomeSelectedPopulationUploadController {
    private final AuthoritativeOutcomeSelectedPopulationUploadService
            service;
    private final IntegrationRequestAuthenticator authenticator;
    private final AuthoritativeOutcomeSelectedPopulationRequestDecoder
            decoder;

    /** Creates the protected resumable-upload transport. */
    public AuthoritativeOutcomeSelectedPopulationUploadController(
            AuthoritativeOutcomeSelectedPopulationUploadService
                    service,
            IntegrationRequestAuthenticator authenticator,
            AuthoritativeOutcomeSelectedPopulationRequestDecoder
                    decoder) {
        this.service = Objects.requireNonNull(
                service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(
                decoder, "decoder");
    }

    /** Creates or exactly replays one immutable upload intent. */
    @PostMapping
    public ResponseEntity<
            IntegrationEnvelope<
                    AuthoritativeOutcomeSelectedPopulationUploadAdmission>>
    begin(
            @RequestBody byte[] body,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                writeIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationUploadRequest
                request = decoder.decodeUpload(
                body, identity);
        AuthoritativeOutcomeSelectedPopulationUploadAdmission
                admission =
                AuthoritativeOutcomeSelectedPopulationUploadAdmission
                        .from(service.begin(
                                request, identity));
        return ResponseEntity.created(
                        URI.create(
                                "/api/mirror/outcome-selected-populations/uploads/"
                                        + admission.status()
                                        .uploadId()))
                .body(IntegrationEnvelope.of(
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_UPLOAD_ADMISSION",
                        admission.schemaVersion(),
                        admission));
    }

    /** Stages or exactly replays one bounded content-addressed chunk. */
    @PutMapping("/{uploadId}/chunks/{chunkIndex}")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationUploadChunkAdmission>
    stageChunk(
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestBody byte[] body,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                writeIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationChunk chunk =
                decoder.decodeUploadChunk(
                        body, identity);
        AuthoritativeOutcomeSelectedPopulationUploadChunkAdmission
                admission =
                AuthoritativeOutcomeSelectedPopulationUploadChunkAdmission
                        .from(service.stageChunk(
                                uploadId,
                                chunkIndex,
                                chunk,
                                body.length,
                                identity));
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_UPLOAD_CHUNK_ADMISSION",
                admission.schemaVersion(),
                admission);
    }

    /** Reads one payload-free resumable upload status. */
    @GetMapping("/{uploadId}")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationUploadStatus>
    find(
            @PathVariable String uploadId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                writeIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationUploadStatus
                status = service.find(
                uploadId, identity).status();
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_UPLOAD_STATUS",
                status.schemaVersion(),
                status);
    }

    /** Finalizes one complete upload through the governed population boundary. */
    @PostMapping("/{uploadId}/finalize")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationAdmission>
    finalizeUpload(
            @PathVariable String uploadId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                writeIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationAdmission
                admission = service.finalizeUpload(
                uploadId, identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ADMISSION",
                admission.schemaVersion(),
                admission);
    }

    /** Aborts an open upload and destroys its staged chunks. */
    @DeleteMapping("/{uploadId}")
    public IntegrationEnvelope<
            AuthoritativeOutcomeSelectedPopulationUploadStatus>
    abort(
            @PathVariable String uploadId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                writeIdentity(headers);
        AuthoritativeOutcomeSelectedPopulationUploadStatus
                status = service.abort(
                uploadId, identity).status();
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_UPLOAD_STATUS",
                status.schemaVersion(),
                status);
    }

    private IntegrationRequestContext writeIdentity(
            HttpHeaders headers) {
        return authenticator.authenticate(
                headers,
                IntegrationOperation
                        .MIRROR_OUTCOME_POPULATION_INGEST);
    }
}
