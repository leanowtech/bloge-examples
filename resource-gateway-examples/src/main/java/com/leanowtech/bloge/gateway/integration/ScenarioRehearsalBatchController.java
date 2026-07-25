package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchCancellationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationRemediationReceipt;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationRemediationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationHealth;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationStatus;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchItemPage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchJob;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchJobPage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRetentionService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchRetentionState;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchWorkbookSeed;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchWorkbookService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalLegalHoldCommand;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalPurgeCommand;
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
    private final ScenarioRehearsalBatchWorkbookService workbooks;
    private final ScenarioRehearsalBatchRetentionService retention;
    private final IntegrationRequestAuthenticator authenticator;
    private final ScenarioArtifactRequestDecoder decoder;

    /** Creates the protected batch transport. */
    public ScenarioRehearsalBatchController(
            ScenarioRehearsalBatchService batches,
            ScenarioRehearsalBatchWorkbookService workbooks,
            ScenarioRehearsalBatchRetentionService retention,
            IntegrationRequestAuthenticator authenticator,
            ScenarioArtifactRequestDecoder decoder) {
        this.batches = Objects.requireNonNull(
                batches, "batches");
        this.workbooks = Objects.requireNonNull(
                workbooks, "workbooks");
        this.retention = Objects.requireNonNull(
                retention, "retention");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(
                decoder, "decoder");
    }

    /** Projects one signed terminal batch into an ANEKE correctness-workbook seed. */
    @GetMapping("/{jobId}/workbook-seed")
    public IntegrationEnvelope<ScenarioRehearsalBatchWorkbookSeed>
    workbookSeed(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_WORKBOOK_READ);
        ScenarioRehearsalBatchWorkbookSeed value =
                workbooks.workbookSeed(jobId, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED",
                value.schemaVersion(),
                value);
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

    /** Lists newest exact-scope jobs with an immutable keyset cursor. */
    @GetMapping
    public IntegrationEnvelope<ScenarioRehearsalBatchJobPage> list(
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "") String beforeCreatedAt,
            @RequestParam(defaultValue = "") String beforeJobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_READ);
        ScenarioRehearsalBatchJobPage value =
                batches.list(
                        beforeCreatedAt,
                        beforeJobId,
                        limit,
                        identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_JOB_PAGE",
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

    /** Reads one independently verified terminal batch evidence index. */
    @GetMapping("/{jobId}/evidence")
    public IntegrationEnvelope<ScenarioRehearsalBatchEvidenceBundle>
    evidence(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_EVIDENCE_READ);
        ScenarioRehearsalBatchEvidenceBundle value =
                batches.evidence(jobId, identity)
                        .orElseThrow(() ->
                                new IntegrationProblemException(
                                        IntegrationProblem.notFound(
                                                "RG.MIRROR.REHEARSAL_BATCH.EVIDENCE_NOT_FOUND",
                                                "Scenario rehearsal batch evidence was not found.",
                                                identity.correlationId(),
                                                Map.of())));
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE",
                value.schemaVersion(),
                value);
    }

    /** Reads payload-free finalization retry, lease, quarantine, and completion state. */
    @GetMapping("/{jobId}/finalization")
    public IntegrationEnvelope<ScenarioRehearsalBatchFinalizationStatus>
    finalization(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_READ);
        ScenarioRehearsalBatchFinalizationStatus value =
                batches.finalization(
                        jobId, identity)
                        .orElseThrow(() ->
                                new IntegrationProblemException(
                                        IntegrationProblem.notFound(
                                                "RG.MIRROR.REHEARSAL_BATCH.FINALIZATION_NOT_FOUND",
                                                "Scenario rehearsal batch finalization was not found.",
                                                identity.correlationId(),
                                                Map.of())));
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_FINALIZATION_STATUS",
                value.schemaVersion(),
                value);
    }

    /** Re-queues one exact quarantined finalization and returns its immutable receipt. */
    @PostMapping("/{jobId}/finalization/remediations")
    public IntegrationEnvelope<
            ScenarioRehearsalBatchFinalizationRemediationReceipt>
    remediateFinalization(
            @PathVariable String jobId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_FINALIZATION_REMEDIATE);
        ScenarioRehearsalBatchFinalizationRemediationRequest
                command =
                decoder.decodeBatchFinalizationRemediationRequest(
                        request, identity);
        ScenarioRehearsalBatchFinalizationRemediationReceipt
                value = batches.remediateFinalization(
                jobId, command, identity).receipt();
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_FINALIZATION_REMEDIATION_RECEIPT",
                value.schemaVersion(),
                value);
    }

    /** Reads exact-scope aggregate finalization backlog, failure, and SLO health. */
    @GetMapping("/finalization-health")
    public IntegrationEnvelope<
            ScenarioRehearsalBatchFinalizationHealth>
    finalizationHealth(
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_BATCH_FINALIZATION_HEALTH_READ);
        ScenarioRehearsalBatchFinalizationHealth value =
                batches.finalizationHealth(identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_FINALIZATION_HEALTH",
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

    /** Reads one verified batch-retention projection and its latest signed event. */
    @GetMapping("/{jobId}/retention")
    public IntegrationEnvelope<ScenarioRehearsalBatchRetentionState>
    retention(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_RETENTION_READ);
        ScenarioRehearsalBatchRetentionState value =
                retention.find(jobId, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_RETENTION_STATE",
                value.schemaVersion(), value);
    }

    /** Places one independent legal hold on a retained terminal batch. */
    @PostMapping("/{jobId}/retention/holds")
    public IntegrationEnvelope<ScenarioRehearsalBatchRetentionState>
    placeHold(
            @PathVariable String jobId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_LEGAL_HOLD);
        ScenarioRehearsalLegalHoldCommand command =
                decoder.decodeLegalHoldCommand(
                        request, identity);
        ScenarioRehearsalBatchRetentionState value =
                retention.placeHold(jobId, command, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_RETENTION_STATE",
                value.schemaVersion(), value);
    }

    /** Releases one exact batch legal hold without affecting other active holds. */
    @PostMapping("/{jobId}/retention/hold-releases")
    public IntegrationEnvelope<ScenarioRehearsalBatchRetentionState>
    releaseHold(
            @PathVariable String jobId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_LEGAL_HOLD);
        ScenarioRehearsalLegalHoldCommand command =
                decoder.decodeLegalHoldCommand(
                        request, identity);
        ScenarioRehearsalBatchRetentionState value =
                retention.releaseHold(
                        jobId, command, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_RETENTION_STATE",
                value.schemaVersion(), value);
    }

    /** Deletes one eligible batch aggregate and returns its signed logical-deletion proof. */
    @PostMapping("/{jobId}/retention/purge")
    public IntegrationEnvelope<ScenarioRehearsalBatchRetentionState>
    purge(
            @PathVariable String jobId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_RETENTION_ADMIN);
        ScenarioRehearsalPurgeCommand command =
                decoder.decodePurgeCommand(
                        request, identity);
        ScenarioRehearsalBatchRetentionState value =
                retention.purge(jobId, command, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_BATCH_RETENTION_STATE",
                value.schemaVersion(), value);
    }
}
