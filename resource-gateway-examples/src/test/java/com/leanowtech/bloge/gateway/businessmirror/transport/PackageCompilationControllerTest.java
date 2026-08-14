package com.leanowtech.bloge.gateway.businessmirror.transport;

import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationCoordinator;
import com.leanowtech.bloge.gateway.businessmirror.application.PackageCompilationService;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageCompilationControllerTest {
    @Test
    void authenticatesCompileAndReturnsReplayReadinessMetadata() {
        PackageCompilationService service = mock(PackageCompilationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        PackageCompilationReceipt receipt = mock(PackageCompilationReceipt.class);
        PackageReadinessReport readiness = mock(PackageReadinessReport.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_COMPILE))
                .thenReturn(identity);
        when(receipt.snapshot()).thenReturn(null);
        when(receipt.readiness()).thenReturn(readiness);
        when(readiness.status()).thenReturn(PackageReadinessReport.Status.BLOCKED);
        when(readiness.fingerprint()).thenReturn(fingerprint('a'));
        when(service.compile("cancellation-fee", 3, "compile:key", identity))
                .thenReturn(new PackageCompilationCoordinator.Outcome(receipt, true));
        PackageCompilationController controller = new PackageCompilationController(service, authenticator);

        var response = controller.compile("cancellation-fee", 3, "compile:key", headers);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isSameAs(receipt);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(response.getHeaders().getFirst("Compilation-Status")).isEqualTo("BLOCKED");
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + fingerprint('a') + '"');
        verify(authenticator).authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_COMPILE);
        verify(service).compile("cancellation-fee", 3, "compile:key", identity);
    }

    @Test
    void authenticatesExactCompilationReadSeparately() {
        PackageCompilationService service = mock(PackageCompilationService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = mock(IntegrationRequestContext.class);
        PackageCompilationReceipt receipt = mock(PackageCompilationReceipt.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ))
                .thenReturn(identity);
        when(service.find("cancellation-fee", 4, identity)).thenReturn(receipt);
        PackageCompilationController controller = new PackageCompilationController(service, authenticator);

        assertThat(controller.find("cancellation-fee", 4, headers)).isSameAs(receipt);
        verify(authenticator).authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_PACKAGE_READ);
        verify(service).find("cancellation-fee", 4, identity);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
