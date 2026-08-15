package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authenticated no-store adapters for proposed calibration and ANEKE feedback projection. */
@RestController
@ConditionalOnBean(CorrectnessGovernanceService.class)
@RequestMapping("/api")
public final class CorrectnessGovernanceController {

    private final CorrectnessGovernanceService governance;
    private final IntegrationRequestAuthenticator authenticator;

    public CorrectnessGovernanceController(
            CorrectnessGovernanceService governance,
            IntegrationRequestAuthenticator authenticator) {
        this.governance = governance;
        this.authenticator = authenticator;
    }

    @PostMapping("/visual/correctness-outcome-calibration-proposals")
    public ResponseEntity<CorrectnessApiEnvelope<StoredOutcomeCalibrationProposal>> propose(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) OutcomeCalibrationRequest request) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_OUTCOME_PROPOSAL_WRITE);
        try {
            return noStore(envelope(identity, "OUTCOME_CALIBRATION_PROPOSAL_V1",
                    governance.propose(request, identity)));
        } catch (CorrectnessGovernanceException failure) {
            throw problem(failure, identity);
        }
    }

    @GetMapping("/visual/correctness-outcome-calibration-proposals/{proposalId}")
    public ResponseEntity<CorrectnessApiEnvelope<StoredOutcomeCalibrationProposal>> proposal(
            @RequestHeader HttpHeaders headers,
            @PathVariable String proposalId) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_OUTCOME_PROPOSAL_READ);
        try {
            return noStore(envelope(identity, "OUTCOME_CALIBRATION_PROPOSAL_V1",
                    governance.findProposal(proposalId, identity)));
        } catch (CorrectnessGovernanceException failure) {
            throw problem(failure, identity);
        }
    }

    @PostMapping("/integration/correctness-publications/{publicationId}/governance-feedback")
    public ResponseEntity<CorrectnessApiEnvelope<StoredCorrectnessGovernanceFeedback>> feedback(
            @RequestHeader HttpHeaders headers,
            @PathVariable String publicationId,
            @RequestBody(required = false) CorrectnessGovernanceFeedbackRequest request) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_GOVERNANCE_FEEDBACK_WRITE);
        try {
            return noStore(envelope(identity, "CORRECTNESS_GOVERNANCE_FEEDBACK_V1",
                    governance.receiveFeedback(publicationId, request, identity)));
        } catch (CorrectnessGovernanceException failure) {
            throw problem(failure, identity);
        }
    }

    @GetMapping("/visual/correctness-publications/{publicationId}/governance-feedback")
    public ResponseEntity<CorrectnessApiEnvelope<StoredCorrectnessGovernanceFeedback>> feedback(
            @RequestHeader HttpHeaders headers,
            @PathVariable String publicationId) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_GOVERNANCE_FEEDBACK_READ);
        try {
            return noStore(envelope(identity, "CORRECTNESS_GOVERNANCE_FEEDBACK_V1",
                    governance.latestFeedback(publicationId, identity)));
        } catch (CorrectnessGovernanceException failure) {
            throw problem(failure, identity);
        }
    }

    private static <T> CorrectnessApiEnvelope<T> envelope(
            IntegrationRequestContext identity, String capability, T data) {
        return CorrectnessApiEnvelope.of(identity.correlationId(), scope(identity),
                List.of(capability), data);
    }

    private static <T> ResponseEntity<CorrectnessApiEnvelope<T>> noStore(
            CorrectnessApiEnvelope<T> envelope) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(envelope);
    }

    private static IntegrationProblemException problem(
            CorrectnessGovernanceException failure, IntegrationRequestContext identity) {
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:correctness-governance", failure.getMessage(),
                failure.status(), failure.code(), failure.retryable(),
                identity.correlationId(), Map.of()));
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }
}
