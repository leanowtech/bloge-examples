package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protects the Scenario HTTP surface from leaking governed failures as generic 500 responses.
 */
class ScenarioIntegrationProblemHandlerTest {

    @Test
    void appliesTheStableProblemContractToAllScenarioControllers() {
        RestControllerAdvice advice =
                IntegrationProblemHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(Arrays.asList(advice.assignableTypes()))
                .contains(
                        ScenarioDraftSetController.class,
                        ScenarioImportController.class,
                        ScenarioPublicationController.class);

        var response = new IntegrationProblemHandler().handle(
                new IntegrationProblemException(IntegrationProblem.retryableConflict(
                        "RG.SCENARIO.DRAFT_REVISION_CONFLICT",
                        "Scenario draft set changed after it was loaded.",
                        "correlation-a",
                        Map.of())));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("RG.SCENARIO.DRAFT_REVISION_CONFLICT");
        assertThat(response.getBody().retryable()).isTrue();
    }
}
