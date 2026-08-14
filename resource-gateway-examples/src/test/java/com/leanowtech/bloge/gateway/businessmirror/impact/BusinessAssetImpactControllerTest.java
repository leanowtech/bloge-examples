package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessAssetImpactControllerTest {
    @Test
    void authenticatesImpactReadAndMaintenanceRebuildSeparately() {
        BusinessAssetImpactService service = mock(BusinessAssetImpactService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        BusinessAssetImpactReport report = mock(BusinessAssetImpactReport.class);
        BusinessAssetImpactRebuildReport rebuild = mock(BusinessAssetImpactRebuildReport.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_IMPACT_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_IMPACT_REBUILD))
                .thenReturn(identity);
        when(service.query("RESOURCE", "trip-api", "registry", "package-a", 25, identity))
                .thenReturn(report);
        when(service.rebuild("package-a", 25, identity)).thenReturn(rebuild);
        BusinessAssetImpactController controller =
                new BusinessAssetImpactController(service, authenticator);

        assertThat(controller.impact(
                "RESOURCE", "trip-api", "registry", "package-a", 25, headers))
                .isSameAs(report);
        assertThat(controller.rebuild("package-a", 25, headers)).isSameAs(rebuild);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.BUSINESS_MIRROR_IMPACT_READ);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.BUSINESS_MIRROR_IMPACT_REBUILD);
    }
}
