package com.leanowtech.bloge.gateway.visualadapter.authoring.fixture.config;

import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ApiFixtureSetAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiFixtureSetApplicationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ApiFixtureSetApplicationConfiguration.class);

    @Test
    void disabledFeatureDoesNotExposeTheFixtureReadModule() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ApiFixtureSetAuthoringFacade.class));
    }

    @Test
    void enabledFeatureRequiresAndUsesTheFixtureAuthorityStore() {
        ApiFixtureSetCommitStore store = mock(ApiFixtureSetCommitStore.class);
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiFixtureSetCommitStore.class, () -> store)
                .run(context -> assertThat(context).hasSingleBean(ApiFixtureSetAuthoringFacade.class));
    }

    @Test
    void enabledFeatureFailsClosedWhenTheFixtureAuthorityStoreIsMissing() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
