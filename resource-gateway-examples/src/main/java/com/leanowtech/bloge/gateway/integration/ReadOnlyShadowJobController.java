package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowComparison;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJob;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobLifecyclePage;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ReadOnlyShadowJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Protected strict transport for durable read-only Shadow admission, evidence, and lifecycle.
 *
 * <p>The route is physically absent from production. Every operation authenticates before body
 * decoding or exact-scope lookup; no endpoint accepts runtime proof, business payload, credential,
 * worker failure text, or an imperative “run now” bypass around the durable queue.</p>
 */
@RestController
@RequestMapping("/api/mirror/shadow-jobs")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ReadOnlyShadowJobController {
    private final ReadOnlyShadowJobService service;
    private final IntegrationRequestAuthenticator authenticator;
    private final ReadOnlyShadowJobRequestDecoder decoder;

    /**
     * Creates the protected Shadow transport.
     *
     * @param service governed application boundary
     * @param authenticator trusted workload identity boundary
     * @param decoder strict post-authentication command decoder
     */
    public ReadOnlyShadowJobController(
            ReadOnlyShadowJobService service,
            IntegrationRequestAuthenticator authenticator,
            ReadOnlyShadowJobRequestDecoder decoder) {
        this.service = Objects.requireNonNull(
                service, "service");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(
                decoder, "decoder");
    }

    /** Reserves one exact sampling ordinal and returns its durable job projection. */
    @PostMapping
    public ResponseEntity<IntegrationEnvelope<ReadOnlyShadowJob>>
    submit(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_SHADOW_JOB_SUBMIT);
        ReadOnlyShadowJobRequest command =
                decoder.decode(request, identity);
        ReadOnlyShadowJob job =
                service.submit(
                        command, identity).job();
        return ResponseEntity.accepted()
                .location(URI.create(
                        "/api/mirror/shadow-jobs/"
                                + job.jobId()))
                .body(IntegrationEnvelope.of(
                        "READ_ONLY_SHADOW_JOB",
                        job.schemaVersion(),
                        job));
    }

    /** Reads one integrity-verified durable job in exact authenticated scope. */
    @GetMapping("/{jobId}")
    public IntegrationEnvelope<ReadOnlyShadowJob> find(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_SHADOW_JOB_READ);
        ReadOnlyShadowJob job =
                service.find(jobId, identity);
        return IntegrationEnvelope.of(
                "READ_ONLY_SHADOW_JOB",
                job.schemaVersion(),
                job);
    }

    /** Reads the immutable request required for independent job closure verification. */
    @GetMapping("/{jobId}/request")
    public IntegrationEnvelope<ReadOnlyShadowJobRequest>
    findRequest(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_SHADOW_JOB_READ);
        ReadOnlyShadowJobRequest request =
                service.findRequest(
                        jobId, identity);
        return IntegrationEnvelope.of(
                "READ_ONLY_SHADOW_JOB_REQUEST",
                request.schemaVersion(),
                request);
    }

    /** Reads one independently reverified terminal signed comparison. */
    @GetMapping("/{jobId}/comparison")
    public IntegrationEnvelope<ReadOnlyShadowComparison>
    findComparison(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_SHADOW_COMPARISON_READ);
        ReadOnlyShadowComparison comparison =
                service.findComparison(
                        jobId, identity);
        return IntegrationEnvelope.of(
                "READ_ONLY_SHADOW_COMPARISON",
                comparison.schemaVersion(),
                comparison);
    }

    /** Reads one bounded append-ordered lifecycle suffix. */
    @GetMapping("/{jobId}/lifecycle")
    public IntegrationEnvelope<ReadOnlyShadowJobLifecyclePage>
    lifecycle(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0")
            long afterSequence,
            @RequestParam(defaultValue = "100")
            int limit,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_SHADOW_LIFECYCLE_READ);
        ReadOnlyShadowJobLifecyclePage page =
                service.lifecycle(
                        jobId,
                        afterSequence,
                        limit,
                        identity);
        return IntegrationEnvelope.of(
                "READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE",
                page.schemaVersion(),
                page);
    }
}
