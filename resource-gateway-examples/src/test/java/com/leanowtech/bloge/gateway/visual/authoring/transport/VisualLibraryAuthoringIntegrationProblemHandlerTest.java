package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualLibraryAuthoringIntegrationProblemHandlerTest {

    @Test
    void mapsAuthenticationFailuresForBothAuthoringControllers() {
        RestControllerAdvice advice =
                IntegrationProblemHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(Arrays.asList(advice.assignableTypes())).contains(
                VisualLibraryAuthoringDraftController.class,
                VisualLibraryAuthoringTestController.class);

        var response = new IntegrationProblemHandler().handle(
                new IntegrationProblemException(IntegrationProblem.unauthorized(
                        "RG.INTEGRATION.AUTHENTICATION_REQUIRED",
                        "Bearer authentication is required.",
                        "correlation-a",
                        Map.of())));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"resource-gateway-integration\"");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("RG.INTEGRATION.AUTHENTICATION_REQUIRED");
    }
}
