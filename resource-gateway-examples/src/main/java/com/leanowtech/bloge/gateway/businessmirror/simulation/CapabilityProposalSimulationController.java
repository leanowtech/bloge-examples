package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
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

/** Protected HTTP surface for Proposal simulation and aggregate evidence reads. */
@RestController
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
@RequestMapping("/api/business-mirror/proposals")
public final class CapabilityProposalSimulationController {
    private final CapabilityProposalSimulationService service;
    private final IntegrationRequestAuthenticator authenticator;

    public CapabilityProposalSimulationController(
            CapabilityProposalSimulationService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping("/{proposalId}/revisions/{revision}/simulations")
    public StoredCapabilityProposalSimulation simulate(
            @PathVariable String proposalId,
            @PathVariable long revision,
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String simulationId,
            @RequestBody CapabilityProposalSimulationRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.simulate(proposalId, revision, simulationId, request,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_SIMULATE));
    }

    @GetMapping("/{proposalId}/revisions/{revision}/simulations/{simulationId}")
    public StoredCapabilityProposalSimulation find(
            @PathVariable String proposalId,
            @PathVariable long revision,
            @PathVariable String simulationId,
            @RequestHeader HttpHeaders headers) {
        return service.find(proposalId, revision, simulationId,
                context(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_EVIDENCE_READ));
    }

    private IntegrationRequestContext context(
            HttpHeaders headers, IntegrationOperation operation) {
        return authenticator.authenticate(headers, operation);
    }
}
