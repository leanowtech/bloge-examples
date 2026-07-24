package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchCancellationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchItemPage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchJob;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * Protected strict transport for durable multi-plan Scenario rehearsal batches.
 */
@RestController
@RequestMapping("/api/mirror/rehearsal-jobs")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ScenarioRehearsalBatchController {
    private final ScenarioRehearsalBatchService batches;
    private final IntegrationRequestAuthenticator authenticator;
    private final ScenarioArtifactRequestDecoder decoder;

    /** Creates the protected batch transport. */
    public ScenarioRehearsalBatchController(
            ScenarioRehearsalBatchService batches,
            IntegrationRequestAuthenticator authenticator,
            ScenarioArtifactRequestDecoder decoder) {
        this.batches = Objects.requireNonNull(
                batches, "batches");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(
                decoder, "decoder");
    }

    /** Resolves exact plans and admits one payload-free durable batch. */
    @PostMapping
    public IntegrationEnvelope<ScenarioRehearsalBatchJob> submit(
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_SUBMIT);
        ScenarioRehearsalBatchRequest command =
                decoder.decodeBatchRequest(
                        request, identity);
        ScenarioRehearsalBatchJob value =
                batches.submit(command, identity).job();
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_JOB",
                value.schemaVersion(),
                value);
    }

    /** Reads one integrity-verified durable batch projection. */
    @GetMapping("/{jobId}")
    public IntegrationEnvelope<ScenarioRehearsalBatchJob> find(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_READ);
        ScenarioRehearsalBatchJob value =
                batches.find(jobId, identity)
                        .orElseThrow(() ->
                                new IntegrationProblemException(
                                        IntegrationProblem.notFound(
                                                "RG.MIRROR.REHEARSAL_BATCH.JOB_NOT_FOUND",
                                                "Scenario rehearsal batch was not found.",
                                                identity.correlationId(),
                                                Map.of())));
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_JOB",
                value.schemaVersion(),
                value);
    }

    /** Reads one bounded stable manifest-index page. */
    @GetMapping("/{jobId}/items")
    public IntegrationEnvelope<ScenarioRehearsalBatchItemPage> page(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "0") int startIndex,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_READ);
        ScenarioRehearsalBatchItemPage value =
                batches.page(
                        jobId,
                        startIndex,
                        limit,
                        identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_ITEM_PAGE",
                value.schemaVersion(),
                value);
    }

    /** Records one exactly replayable cooperative cancellation intent. */
    @PostMapping("/{jobId}/cancellations")
    public IntegrationEnvelope<ScenarioRehearsalBatchJob> cancel(
            @PathVariable String jobId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_CANCEL);
        ScenarioRehearsalBatchCancellationRequest command =
                decoder.decodeBatchCancellationRequest(
                        request, identity);
        ScenarioRehearsalBatchJob value =
                batches.cancel(
                        jobId,
                        command.commandId(),
                        command.reasonCode(),
                        identity).job();
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_JOB",
                value.schemaVersion(),
                value);
    }
}
