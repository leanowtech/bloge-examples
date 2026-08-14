package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityProposalSimulationControllerTest {
    @Test
    void authenticatesSimulationWithTheDedicatedOperation() {
        CapabilityProposalSimulationService service = mock(
                CapabilityProposalSimulationService.class);
        IntegrationRequestAuthenticator authenticator = mock(
                IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        CapabilityProposalSimulationRequest request = mock(
                CapabilityProposalSimulationRequest.class);
        StoredCapabilityProposalSimulation result = mock(
                StoredCapabilityProposalSimulation.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_SIMULATE))
                .thenReturn(identity);
        when(service.simulate("proposal-1", 3, "simulation-1", request, identity))
                .thenReturn(result);
        CapabilityProposalSimulationController controller =
                new CapabilityProposalSimulationController(service, authenticator);

        assertThat(controller.simulate(
                "proposal-1", 3, "simulation-1", request, headers)).isSameAs(result);
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_SIMULATE);
        verify(service).simulate("proposal-1", 3, "simulation-1", request, identity);
    }

    @Test
    void authenticatesEvidenceReadSeparately() {
        CapabilityProposalSimulationService service = mock(
                CapabilityProposalSimulationService.class);
        IntegrationRequestAuthenticator authenticator = mock(
                IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        StoredCapabilityProposalSimulation result = mock(
                StoredCapabilityProposalSimulation.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_EVIDENCE_READ))
                .thenReturn(identity);
        when(service.find("proposal-1", 3, "simulation-1", identity)).thenReturn(result);
        CapabilityProposalSimulationController controller =
                new CapabilityProposalSimulationController(service, authenticator);

        assertThat(controller.find(
                "proposal-1", 3, "simulation-1", headers)).isSameAs(result);
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_EVIDENCE_READ);
        verify(service).find("proposal-1", 3, "simulation-1", identity);
    }
}
