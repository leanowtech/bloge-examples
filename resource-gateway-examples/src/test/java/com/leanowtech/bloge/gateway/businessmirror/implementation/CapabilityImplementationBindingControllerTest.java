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

class CapabilityImplementationBindingControllerTest {
    @Test
    void authenticatesBindingAndReportsIdempotentReplay() {
        CapabilityImplementationBindingService service = mock(
                CapabilityImplementationBindingService.class);
        IntegrationRequestAuthenticator authenticator = mock(
                IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        CapabilityImplementationBindingRequest request = mock(
                CapabilityImplementationBindingRequest.class);
        StoredCapabilityImplementationBinding stored = mock(
                StoredCapabilityImplementationBinding.class);
        com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding binding =
                mock(com.leanowtech.bloge.gateway.businessmirror.domain
                        .CapabilityImplementationBinding.class);
        when(stored.binding()).thenReturn(binding);
        when(binding.fingerprint()).thenReturn("sha256:" + "a".repeat(64));
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_BIND))
                .thenReturn(identity);
        when(service.bind("proposal-1", 2, "binding-1", request, identity))
                .thenReturn(new CapabilityImplementationBindingRepository.CreateResult(
                        stored, false));
        CapabilityImplementationBindingController controller =
                new CapabilityImplementationBindingController(service, authenticator);

        var response = controller.bind(
                "proposal-1", 2, "binding-1", request, headers);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_BIND);
    }

    @Test
    void authenticatesExactBindingReadSeparately() {
        CapabilityImplementationBindingService service = mock(
                CapabilityImplementationBindingService.class);
        IntegrationRequestAuthenticator authenticator = mock(
                IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        StoredCapabilityImplementationBinding stored = mock(
                StoredCapabilityImplementationBinding.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_READ))
                .thenReturn(identity);
        when(service.find("binding-1", identity)).thenReturn(stored);
        CapabilityImplementationBindingController controller =
                new CapabilityImplementationBindingController(service, authenticator);

        assertThat(controller.find("binding-1", headers)).isSameAs(stored);
        verify(authenticator).authenticate(headers,
                IntegrationOperation.BUSINESS_MIRROR_IMPLEMENTATION_READ);
    }
}
