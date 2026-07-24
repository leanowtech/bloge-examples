package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunSummary;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateTransitionWorkbookSeed;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateWorkbookSeed;
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

/**
 * Protected transport for durable mirror execution, status, and payload-free evidence.
 *
 * <p>The controller is physically absent from production and from disabled deployments. Every
 * operation authenticates a dedicated MIRROR_REHEARSAL permission before resolving any scoped
 * plan, request, or evidence identity.</p>
 */
@RestController
@RequestMapping("/api/mirror")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorRunIntegrationController {
    private final MirrorRunIntegrationService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final MirrorExecutionRequestDecoder decoder;

    /** Creates the protected execution and evidence transport. */
    public MirrorRunIntegrationController(
            MirrorRunIntegrationService service,
            IntegrationRequestAuthenticator authenticator,
            MirrorExecutionRequestDecoder decoder) {
        this.service = Objects.requireNonNull(service, "service");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /** Executes one sealed generation or returns the result of an exact durable retry. */
    @PostMapping("/executions")
    public IntegrationEnvelope<MirrorRunSummary> execute(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_EXECUTION_CREATE);
        return IntegrationEnvelope.of("MIRROR_RUN_SUMMARY", MirrorRunSummary.SCHEMA_VERSION,
                service.execute(decoder.decode(request, identity), identity));
    }

    /** Reads one payload-free terminal run projection in the authenticated scope. */
    @GetMapping("/runs/{runId}")
    public IntegrationEnvelope<MirrorRunSummary> find(
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_RUN_READ);
        return IntegrationEnvelope.of("MIRROR_RUN_SUMMARY", MirrorRunSummary.SCHEMA_VERSION,
                service.find(runId, identity));
    }

    /** Reads one independently verified HASH_ONLY evidence bundle in the authenticated scope. */
    @GetMapping("/runs/{runId}/evidence")
    public IntegrationEnvelope<MirrorEvidenceBundle> evidence(
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_EVIDENCE_READ);
        MirrorEvidenceBundle bundle =
                service.evidence(runId, identity);
        return IntegrationEnvelope.of("MIRROR_EVIDENCE_BUNDLE",
                bundle.schemaVersion(), bundle);
    }

    /** Reads one deterministic payload-free ANEKE workbook seed for a stateful run. */
    @GetMapping("/runs/{runId}/state-workbook-seed")
    public IntegrationEnvelope<MirrorStateWorkbookSeed>
    stateWorkbookSeed(
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_EVIDENCE_READ);
        return IntegrationEnvelope.of(
                "MIRROR_STATE_WORKBOOK_SEED",
                MirrorStateWorkbookSeed.SCHEMA_VERSION,
                service.stateWorkbookSeed(runId, identity));
    }

    /** Reads a deterministic payload-free transition-workbook seed for a read/write run. */
    @GetMapping("/runs/{runId}/state-transition-workbook-seed")
    public IntegrationEnvelope<MirrorStateTransitionWorkbookSeed>
    stateTransitionWorkbookSeed(
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_EVIDENCE_READ);
        return IntegrationEnvelope.of(
                "MIRROR_STATE_TRANSITION_WORKBOOK_SEED",
                MirrorStateTransitionWorkbookSeed.SCHEMA_VERSION,
                service.stateTransitionWorkbookSeed(
                        runId, identity));
    }
}
