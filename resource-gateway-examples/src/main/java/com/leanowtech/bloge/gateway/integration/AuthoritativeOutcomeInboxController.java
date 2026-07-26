package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeInboxAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeInboxEntry;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeInboxLifecyclePage;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeInboxService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeObservation;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeObservationAdmissionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;

/**
 * Protected strict transport for authoritative outcome admission, reads, and lifecycle audit.
 *
 * <p>The route is physically absent from production. Every operation authenticates before body
 * decoding or exact-scope lookup. No route accepts business payload values, credentials, mutable
 * pass/fail claims, or an imperative worker bypass.</p>
 */
@RestController
@RequestMapping("/api/mirror/outcome-observations")
@Profile("!production & (test | staging)")
@ConditionalOnBean(AuthoritativeOutcomeInboxService.class)
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class AuthoritativeOutcomeInboxController {
    private final AuthoritativeOutcomeInboxService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final AuthoritativeOutcomeObservationRequestDecoder decoder;

    /**
     * Creates the protected outcome inbox transport.
     *
     * @param service governed application boundary
     * @param authenticator trusted workload identity boundary
     * @param decoder strict post-authentication admission decoder
     */
    public AuthoritativeOutcomeInboxController(
            AuthoritativeOutcomeInboxService service,
            IntegrationRequestAuthenticator authenticator,
            AuthoritativeOutcomeObservationRequestDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /** Verifies, signs, and appends one exact immutable observation revision. */
    @PostMapping
    public ResponseEntity<IntegrationEnvelope<AuthoritativeOutcomeInboxAdmission>>
    ingest(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_OBSERVATION_INGEST);
        AuthoritativeOutcomeObservationAdmissionRequest command =
                decoder.decode(request, identity);
        AuthoritativeOutcomeInboxAdmission admission =
                service.ingest(command, identity);
        return ResponseEntity.created(
                        URI.create(
                                "/api/mirror/outcome-observations/"
                                        + admission.observation()
                                        .observationId()
                                        + "/revisions/"
                                        + admission.observation()
                                        .revision()))
                .body(IntegrationEnvelope.of(
                        "AUTHORITATIVE_OUTCOME_INBOX_ADMISSION",
                        admission.schemaVersion(),
                        admission));
    }

    /** Reads one exact immutable signed revision in authenticated enterprise scope. */
    @GetMapping("/{observationId}/revisions/{revision}")
    public IntegrationEnvelope<AuthoritativeOutcomeObservation>
    findObservation(
            @PathVariable String observationId,
            @PathVariable long revision,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_OBSERVATION_READ);
        AuthoritativeOutcomeObservation observation =
                service.findObservation(
                        observationId, revision, identity);
        return IntegrationEnvelope.of(
                AuthoritativeOutcomeObservation.ARTIFACT_KIND,
                observation.schemaVersion(),
                observation);
    }

    /** Reads the current immutable signed revision in authenticated enterprise scope. */
    @GetMapping("/{observationId}/latest")
    public IntegrationEnvelope<AuthoritativeOutcomeObservation>
    findLatestObservation(
            @PathVariable String observationId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_OBSERVATION_READ);
        AuthoritativeOutcomeObservation observation =
                service.findLatestObservation(
                        observationId, identity);
        return IntegrationEnvelope.of(
                AuthoritativeOutcomeObservation.ARTIFACT_KIND,
                observation.schemaVersion(),
                observation);
    }

    /** Reads the mutable durable reconciliation head after current-revision reverification. */
    @GetMapping("/{observationId}/head")
    public IntegrationEnvelope<AuthoritativeOutcomeInboxEntry>
    findEntry(
            @PathVariable String observationId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_OBSERVATION_READ);
        AuthoritativeOutcomeInboxEntry entry =
                service.findEntry(observationId, identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_INBOX_ENTRY",
                entry.schemaVersion(),
                entry);
    }

    /** Reads one bounded append-ordered lifecycle suffix. */
    @GetMapping("/{observationId}/lifecycle")
    public IntegrationEnvelope<AuthoritativeOutcomeInboxLifecyclePage>
    lifecycle(
            @PathVariable String observationId,
            @RequestParam(defaultValue = "0")
            long afterOrdinal,
            @RequestParam(defaultValue = "100")
            int limit,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_OUTCOME_LIFECYCLE_READ);
        AuthoritativeOutcomeInboxLifecyclePage page =
                service.lifecycle(
                        observationId,
                        afterOrdinal,
                        limit,
                        identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_INBOX_LIFECYCLE_PAGE",
                page.schemaVersion(),
                page);
    }
}
