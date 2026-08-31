package com.leanowtech.bloge.gateway.visualadapter.authoring.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFacade;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Opt-in bean boundary for the API Connection authoring HTTP surface. */
class ApiConnectionAuthoringTransportConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ApiConnectionAuthoringController.class, ApiResourceAuthoringProblemHandler.class)
            .withBean(ApiConnectionAuthoringFacade.class, () -> mock(ApiConnectionAuthoringFacade.class))
            .withBean(IntegrationRequestAuthenticator.class, () -> mock(IntegrationRequestAuthenticator.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledRuntimeExposesNoConnectionTransport() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ApiConnectionAuthoringController.class);
            assertThat(context).doesNotHaveBean(ApiResourceAuthoringProblemHandler.class);
        });
    }

    @Test
    void enabledRuntimeExposesOneConnectionControllerAndSharedProblemMapper() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiConnectionAuthoringController.class);
                    assertThat(context).hasSingleBean(ApiResourceAuthoringProblemHandler.class);
                });
    }
}
