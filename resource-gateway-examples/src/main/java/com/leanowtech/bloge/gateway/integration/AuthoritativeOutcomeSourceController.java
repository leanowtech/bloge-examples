package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeConnectorControlCommand;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSourceCheckpointRepository;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSourceControlService;
import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSourcePage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Protected backfill, generation-revocation, and payload-free checkpoint transport. */
@RestController
@RequestMapping("/api/mirror/outcome-sources")
@Profile("!production & (test | staging)")
@ConditionalOnBean(AuthoritativeOutcomeSourceControlService.class)
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public final class AuthoritativeOutcomeSourceController {
    private final AuthoritativeOutcomeSourceControlService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final AuthoritativeOutcomeSourceCommandDecoder decoder;

    /** Creates the authenticate-before-decode source control transport. */
    public AuthoritativeOutcomeSourceController(
            AuthoritativeOutcomeSourceControlService service,
            IntegrationRequestAuthenticator authenticator,
            AuthoritativeOutcomeSourceCommandDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /** Registers or exactly replays one externally authorized historical stream. */
    @PostMapping("/backfills")
    public IntegrationEnvelope<AuthoritativeOutcomeSourceCheckpointRepository.Admission>
    registerBackfill(@RequestBody byte[] request, @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_OUTCOME_SOURCE_CONTROL);
        AuthoritativeOutcomeConnectorControlCommand command = decoder.decode(request, identity);
        var result = service.registerBackfill(command, identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SOURCE_CHECKPOINT_ADMISSION",
                AuthoritativeOutcomeSourceCheckpointRepository.SNAPSHOT_SCHEMA_VERSION,
                result);
    }

    /** Irreversibly fences one exact source connector generation. */
    @PostMapping("/revocations")
    public IntegrationEnvelope<AuthoritativeOutcomeSourceCheckpointRepository.Revocation>
    revokeGeneration(@RequestBody byte[] request, @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_OUTCOME_SOURCE_CONTROL);
        AuthoritativeOutcomeConnectorControlCommand command = decoder.decode(request, identity);
        var result = service.revokeGeneration(command, identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SOURCE_GENERATION_REVOCATION",
                AuthoritativeOutcomeConnectorControlCommand.SCHEMA_VERSION,
                result);
    }

    /** Reads one exact payload-free source progress checkpoint. */
    @GetMapping("/{connectorId}/generations/{generation}/streams/{streamKind}/{streamId}")
    public IntegrationEnvelope<AuthoritativeOutcomeSourceCheckpointRepository.Snapshot>
    find(
            @PathVariable String connectorId,
            @PathVariable long generation,
            @PathVariable AuthoritativeOutcomeSourcePage.StreamKind streamKind,
            @PathVariable String streamId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_OUTCOME_SOURCE_CHECKPOINT_READ);
        var value = service.find(
                connectorId, generation, streamKind, streamId, identity);
        return IntegrationEnvelope.of(
                "AUTHORITATIVE_OUTCOME_SOURCE_CHECKPOINT",
                AuthoritativeOutcomeSourceCheckpointRepository.SNAPSHOT_SCHEMA_VERSION,
                value);
    }
}
