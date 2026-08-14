package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityImplementationConformanceControllerTest {
    @Test
    void authenticatesConformanceRunWithItsFixedOperation() {
        CapabilityImplementationConformanceService service = mock(
                CapabilityImplementationConformanceService.class);
        IntegrationRequestAuthenticator authenticator = mock(
                IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        CapabilityImplementationConformanceRequest request = mock(
                CapabilityImplementationConformanceRequest.class);
        StoredCapabilityImplementationConformance stored = mock(
                StoredCapabilityImplementationConformance.class);
        CapabilityImplementationConformanceReport report = mock(
                CapabilityImplementationConformanceReport.class);
        when(stored.report()).thenReturn(report);
        when(report.fingerprint()).thenReturn("sha256:" + "a".repeat(64));
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_CONFORM))
                .thenReturn(identity);
        when(service.conform("proposal-1", 2, "conformance-1", request, identity))
                .thenReturn(stored);
        CapabilityImplementationConformanceController controller =
                new CapabilityImplementationConformanceController(service, authenticator);

        var response = controller.conform(
                "proposal-1", 2, "conformance-1", request, headers);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("ETag"))
                .isEqualTo('"' + report.fingerprint() + '"');
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_CONFORM);
    }

    @Test
    void authenticatesExactConformanceReadSeparately() {
        CapabilityImplementationConformanceService service = mock(
                CapabilityImplementationConformanceService.class);
        IntegrationRequestAuthenticator authenticator = mock(
                IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        StoredCapabilityImplementationConformance stored = mock(
                StoredCapabilityImplementationConformance.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_CONFORMANCE_READ))
                .thenReturn(identity);
        when(service.findByBinding("binding-1", 1, identity)).thenReturn(stored);
        CapabilityImplementationConformanceController controller =
                new CapabilityImplementationConformanceController(service, authenticator);

        assertThat(controller.find("binding-1", 1, headers)).isSameAs(stored);
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_CONFORMANCE_READ);
    }
}
