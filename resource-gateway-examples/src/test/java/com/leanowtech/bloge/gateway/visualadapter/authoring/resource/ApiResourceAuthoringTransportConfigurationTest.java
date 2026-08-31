package com.leanowtech.bloge.gateway.visualadapter.authoring.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFacade;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Opt-in bean boundary for the API Resource authoring HTTP surface. */
class ApiResourceAuthoringTransportConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ApiResourceAuthoringController.class, ApiResourceAuthoringProblemHandler.class)
            .withBean(ApiResourceAuthoringFacade.class, () -> mock(ApiResourceAuthoringFacade.class))
            .withBean(IntegrationRequestAuthenticator.class, () -> mock(IntegrationRequestAuthenticator.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledRuntimeExposesNoTransportBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ApiResourceAuthoringController.class);
            assertThat(context).doesNotHaveBean(ApiResourceAuthoringProblemHandler.class);
        });
    }

    @Test
    void enabledRuntimeExposesOneControllerAndProblemMapper() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiResourceAuthoringController.class);
                    assertThat(context).hasSingleBean(ApiResourceAuthoringProblemHandler.class);
                });
    }
}
