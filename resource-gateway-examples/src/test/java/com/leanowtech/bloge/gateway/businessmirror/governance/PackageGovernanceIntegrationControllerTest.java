package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageGovernanceIntegrationControllerTest {
    @Test
    void separatesRegistryExportGovernanceFeedbackAndReadPurposes() {
        PackageGovernanceIntegrationService service =
                mock(PackageGovernanceIntegrationService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext exportIdentity = mock(IntegrationRequestContext.class);
        IntegrationRequestContext feedbackIdentity = mock(IntegrationRequestContext.class);
        IntegrationRequestContext readIdentity = mock(IntegrationRequestContext.class);
        PackageRegistryIngestBundle bundle = mock(PackageRegistryIngestBundle.class);
        DomainCapabilityPackageGovernanceProjection projection =
                mock(DomainCapabilityPackageGovernanceProjection.class);
        PackageGovernanceProjectionReceipt receipt =
                mock(PackageGovernanceProjectionReceipt.class);
        DomainCapabilityPackageGovernanceView view =
                mock(DomainCapabilityPackageGovernanceView.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_REGISTRY_EXPORT))
                .thenReturn(exportIdentity);
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_GOVERNANCE_FEEDBACK))
                .thenReturn(feedbackIdentity);
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_GOVERNANCE_READ))
                .thenReturn(readIdentity);
        when(service.exportBundle("package-a", 7, exportIdentity)).thenReturn(bundle);
        when(service.ingest("package-a", projection, feedbackIdentity)).thenReturn(receipt);
        when(service.view("package-a", readIdentity)).thenReturn(view);
        PackageGovernanceIntegrationController controller =
                new PackageGovernanceIntegrationController(service, authenticator);

        assertThat(controller.exportBundle("package-a", 7, headers)).isSameAs(bundle);
        assertThat(controller.ingest("package-a", projection, headers)).isSameAs(receipt);
        assertThat(controller.view("package-a", headers)).isSameAs(view);
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_REGISTRY_EXPORT);
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_GOVERNANCE_FEEDBACK);
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_GOVERNANCE_READ);
    }
}
