package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.AuthoritativeOutcomeSourceControlService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeSourceControllerTest {
    @Test
    void authenticatesBeforeParsingUntrustedCommandBytes() {
        AuthoritativeOutcomeSourceControlService service = mock(
                AuthoritativeOutcomeSourceControlService.class);
        IntegrationRequestAuthenticator authenticator = mock(
                IntegrationRequestAuthenticator.class);
        AuthoritativeOutcomeSourceCommandDecoder decoder = mock(
                AuthoritativeOutcomeSourceCommandDecoder.class);
        var rejected = new IntegrationProblemException(IntegrationProblem.unauthorized(
                "RG.INTEGRATION.AUTHENTICATION_REQUIRED",
                "Authentication is required.", "correlation-source", Map.of()));
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_OUTCOME_SOURCE_CONTROL)))
                .thenThrow(rejected);
        var controller = new AuthoritativeOutcomeSourceController(
                service, authenticator, decoder);

        assertThatThrownBy(() -> controller.registerBackfill(
                "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new HttpHeaders()))
                .isSameAs(rejected);

        verify(authenticator).authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_OUTCOME_SOURCE_CONTROL));
        verify(decoder, never()).decode(any(), any());
        verifyNoInteractions(service);
    }
}
