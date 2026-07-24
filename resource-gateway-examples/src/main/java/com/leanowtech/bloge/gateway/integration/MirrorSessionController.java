package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCommandResult;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionDescriptor;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionRecoveryResult;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateWriteAttempt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Protected transport for encrypted stateful mirror session lifecycle and commands.
 *
 * <p>The controller is physically absent from production and disabled deployments. Authentication
 * and purpose authorization always precede decoding, scope lookup, lease acquisition, or payload
 * decryption.</p>
 */
@RestController
@RequestMapping("/api/mirror/sessions")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = {"enabled", "stateful.enabled"},
        havingValue = "true")
public final class MirrorSessionController {
    private final MirrorSessionIntegrationService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final MirrorSessionRequestDecoder decoder;

    /** Creates the protected stateful Session transport. */
    public MirrorSessionController(
            MirrorSessionIntegrationService service,
            IntegrationRequestAuthenticator authenticator,
            MirrorSessionRequestDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /** Creates or exactly replays one sealed encrypted session aggregate. */
    @PostMapping
    public IntegrationEnvelope<MirrorSessionDescriptor> create(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_CREATE);
        MirrorSessionDescriptor descriptor = service.create(
                decoder.decodeCreate(request, identity), identity);
        return IntegrationEnvelope.of(
                "MIRROR_SESSION_DESCRIPTOR",
                MirrorSessionDescriptor.SCHEMA_VERSION,
                descriptor);
    }

    /** Reads one payload-free current descriptor in the authenticated scope. */
    @GetMapping("/{sessionId}")
    public IntegrationEnvelope<MirrorSessionDescriptor> find(
            @PathVariable String sessionId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_READ);
        MirrorSessionDescriptor descriptor = service.find(
                sessionId, identity);
        return IntegrationEnvelope.of(
                "MIRROR_SESSION_DESCRIPTOR",
                MirrorSessionDescriptor.SCHEMA_VERSION,
                descriptor);
    }

    /** Executes or exactly replays one admitted virtual state transition. */
    @PostMapping("/{sessionId}/commands")
    public IntegrationEnvelope<MirrorSessionCommandResult> command(
            @PathVariable String sessionId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_COMMAND);
        MirrorSessionCommandResult result = service.command(
                sessionId, decoder.decodeCommand(request, identity), identity);
        return IntegrationEnvelope.of(
                "MIRROR_SESSION_COMMAND_RESULT",
                MirrorSessionCommandResult.SCHEMA_VERSION,
                result);
    }

    /** Reads one payload-free durable write-attempt outcome. */
    @GetMapping("/{sessionId}/write-attempts/{attemptId}")
    public IntegrationEnvelope<MirrorStateWriteAttempt> writeAttempt(
            @PathVariable String sessionId,
            @PathVariable String attemptId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation.MIRROR_SESSION_READ);
        MirrorStateWriteAttempt attempt =
                service.writeAttempt(
                        sessionId, attemptId, identity);
        return IntegrationEnvelope.of(
                "MIRROR_STATE_WRITE_ATTEMPT",
                MirrorStateWriteAttempt.SCHEMA_VERSION,
                attempt);
    }

    /** Creates a signed payload-free checkpoint over one exact durable Session state head. */
    @PostMapping("/{sessionId}/checkpoints")
    public IntegrationEnvelope<MirrorSessionCheckpointBundle> checkpoint(
            @PathVariable String sessionId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_CHECKPOINT);
        MirrorSessionCheckpointBundle bundle = service.checkpoint(
                sessionId, identity);
        return IntegrationEnvelope.of(
                "MIRROR_SESSION_CHECKPOINT_BUNDLE",
                MirrorSessionCheckpointBundle.SCHEMA_VERSION,
                bundle);
    }

    /** Admits exact continuation only when the signed checkpoint still matches durable state. */
    @PostMapping("/{sessionId}/recoveries")
    public IntegrationEnvelope<MirrorSessionRecoveryResult> recover(
            @PathVariable String sessionId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_RECOVER);
        MirrorSessionRecoveryResult result = service.recover(
                sessionId, decoder.decodeCheckpoint(request, identity),
                identity);
        return IntegrationEnvelope.of(
                "MIRROR_SESSION_RECOVERY_RESULT",
                MirrorSessionRecoveryResult.SCHEMA_VERSION,
                result);
    }

    /** Irreversibly clears the encrypted payload and returns its terminal descriptor. */
    @DeleteMapping("/{sessionId}")
    public IntegrationEnvelope<MirrorSessionDescriptor> destroy(
            @PathVariable String sessionId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SESSION_DESTROY);
        MirrorSessionDescriptor descriptor = service.destroy(
                sessionId, identity);
        return IntegrationEnvelope.of(
                "MIRROR_SESSION_DESCRIPTOR",
                MirrorSessionDescriptor.SCHEMA_VERSION,
                descriptor);
    }
}
