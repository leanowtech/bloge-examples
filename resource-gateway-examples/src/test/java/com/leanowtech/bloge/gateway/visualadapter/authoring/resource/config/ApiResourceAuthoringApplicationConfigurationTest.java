package com.leanowtech.bloge.gateway.visualadapter.authoring.resource.config;

import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionProjectionResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Conditional adapter-side application-assembly contract for the Resource tracer. */
class ApiResourceAuthoringApplicationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiResourceAuthoringApplicationConfiguration.class))
            .withBean(ApiResourceDecisions.class, ApiResourceDecisions::new)
            .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class));

    @Test
    void disabledRuntimeCreatesNoApplicationBeans() {
        runner.withBean(ApiConnectionAuthoringStore.class, () -> mock(ApiConnectionAuthoringStore.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ApiResourceAuthoringFacade.class);
                    assertThat(context).doesNotHaveBean(ApiResourceConnectionProjectionResolver.class);
                });
    }

    @Test
    void enabledRuntimeCreatesOneFacadeAndResolver() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiConnectionAuthoringStore.class, () -> mock(ApiConnectionAuthoringStore.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiResourceAuthoringFacade.class);
                    assertThat(context).hasSingleBean(ApiResourceConnectionProjectionResolver.class);
                });
    }

    @Test
    void enabledRuntimeFailsClosedWithoutConnectionAuthority() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("ApiConnectionAuthoringStore");
                });
    }
}
