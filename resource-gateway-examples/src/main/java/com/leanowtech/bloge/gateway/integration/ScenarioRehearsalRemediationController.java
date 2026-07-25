package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationApproval;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationApprovalCommand;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationComparison;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationLineage;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationPlan;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationPreviewRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationReceipt;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationSubmitCommand;
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

import java.util.Map;
import java.util.Objects;

/**
 * Protected strict transport for human-reviewed Scenario rehearsal remediation.
 *
 * <p>Authentication always precedes JSON decoding. Wire commands cannot provide actor identity,
 * delegated authority, trusted time, runtime controls, fixture values, or arbitrary comments.</p>
 */
@RestController
@RequestMapping("/api/mirror")
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public final class ScenarioRehearsalRemediationController {
    private final ScenarioRehearsalRemediationService remediations;
    private final IntegrationRequestAuthenticator authenticator;
    private final ScenarioArtifactRequestDecoder decoder;

    /**
     * Creates the protected reviewed-remediation transport.
     *
     * @param remediations role-authorizing application service
     * @param authenticator protected integration identity boundary
     * @param decoder strict bounded command decoder
     */
    public ScenarioRehearsalRemediationController(
            ScenarioRehearsalRemediationService remediations,
            IntegrationRequestAuthenticator authenticator,
            ScenarioArtifactRequestDecoder decoder) {
        this.remediations = Objects.requireNonNull(
                remediations, "remediations");
        this.authenticator = Objects.requireNonNull(
                authenticator, "authenticator");
        this.decoder = Objects.requireNonNull(
                decoder, "decoder");
    }

    /** Freezes one blocked signed predecessor and its exact proposed successor for review. */
    @PostMapping("/rehearsal-jobs/{jobId}/remediations")
    public IntegrationEnvelope<ScenarioRehearsalRemediationPlan>
    preview(
            @PathVariable String jobId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_REMEDIATION_PREVIEW);
        ScenarioRehearsalRemediationPreviewRequest command =
                decoder.decodeRemediationPreviewRequest(
                        request, identity);
        ScenarioRehearsalRemediationPlan value =
                remediations.preview(
                        jobId, command, identity).plan();
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_REMEDIATION_PLAN",
                value.schemaVersion(),
                value);
    }

    /** Reads one content-addressed plan, approval chain, state, and optional receipt. */
    @GetMapping("/rehearsal-remediations/{remediationId}")
    public IntegrationEnvelope<ScenarioRehearsalRemediationLineage>
    find(
            @PathVariable String remediationId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_REMEDIATION_READ);
        ScenarioRehearsalRemediationLineage value =
                remediations.find(
                        remediationId, identity)
                        .orElseThrow(() ->
                                new IntegrationProblemException(
                                        IntegrationProblem.notFound(
                                                "RG.MIRROR.REMEDIATION.NOT_FOUND",
                                                "Scenario remediation was not found.",
                                                identity.correlationId(),
                                                Map.of())));
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_REMEDIATION_LINEAGE",
                value.schemaVersion(),
                value);
    }

    /** Compares the predecessor and successor using only verified root-signed workbooks. */
    @GetMapping(
            "/rehearsal-remediations/{remediationId}/comparison")
    public IntegrationEnvelope<ScenarioRehearsalRemediationComparison>
    compare(
            @PathVariable String remediationId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_REMEDIATION_COMPARISON_READ);
        ScenarioRehearsalRemediationComparison value =
                remediations.compare(
                        remediationId, identity);
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_REMEDIATION_COMPARISON",
                value.schemaVersion(),
                value);
    }

    /** Appends one server-authorized owner or independent-reviewer decision. */
    @PostMapping(
            "/rehearsal-remediations/{remediationId}/approvals")
    public IntegrationEnvelope<ScenarioRehearsalRemediationApproval>
    approve(
            @PathVariable String remediationId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_REMEDIATION_APPROVE);
        ScenarioRehearsalRemediationApprovalCommand command =
                decoder.decodeRemediationApprovalCommand(
                        request, identity);
        ScenarioRehearsalRemediationApproval value =
                remediations.approve(
                        remediationId,
                        command,
                        identity).approval();
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_REMEDIATION_APPROVAL",
                value.schemaVersion(),
                value);
    }

    /** Atomically admits the exact frozen successor after complete two-person approval. */
    @PostMapping(
            "/rehearsal-remediations/{remediationId}/submissions")
    public IntegrationEnvelope<ScenarioRehearsalRemediationReceipt>
    submit(
            @PathVariable String remediationId,
            @RequestBody byte[] request,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity =
                authenticator.authenticate(
                        headers,
                        IntegrationOperation
                                .MIRROR_REHEARSAL_REMEDIATION_SUBMIT);
        ScenarioRehearsalRemediationSubmitCommand command =
                decoder.decodeRemediationSubmitCommand(
                        request, identity);
        ScenarioRehearsalRemediationReceipt value =
                remediations.submit(
                        remediationId,
                        command,
                        identity).receipt();
        return IntegrationEnvelope.of(
                "SCENARIO_REHEARSAL_REMEDIATION_RECEIPT",
                value.schemaVersion(),
                value);
    }
}
