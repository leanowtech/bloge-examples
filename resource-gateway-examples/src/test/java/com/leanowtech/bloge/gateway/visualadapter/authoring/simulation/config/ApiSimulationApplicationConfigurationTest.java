package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation.config;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FixtureAssetSimulationResolver;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiSimulationApplicationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ApiSimulationApplicationConfiguration.class);

    @Test
    void disabledModuleIsAbsentAndEnabledModuleRequiresAllThreeAuthorities() {
        runner.run(context -> assertThat(context).doesNotHaveBean(SimulationModule.class));
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ApiFixtureSetCommitStore.class, () -> mock(ApiFixtureSetCommitStore.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledModuleUsesResourceFixtureAndRunAuthorities() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ApiFixtureSetCommitStore.class, () -> mock(ApiFixtureSetCommitStore.class))
                .withBean(SimulationRunStore.class, () -> mock(SimulationRunStore.class))
                .run(context -> assertThat(context).hasSingleBean(SimulationModule.class));
    }

    @Test
    void enabledModuleAcceptsTheOptionalFlowPublicationAuthority() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ApiFixtureSetCommitStore.class, () -> mock(ApiFixtureSetCommitStore.class))
                .withBean(SimulationRunStore.class, () -> mock(SimulationRunStore.class))
                .withBean(ReusableFlowPublicationStore.class,
                        () -> mock(ReusableFlowPublicationStore.class))
                .withBean(ParentFlowApplyCaseCompiler.class,
                        () -> mock(ParentFlowApplyCaseCompiler.class))
                .run(context -> assertThat(context).hasSingleBean(SimulationModule.class));
    }

    @Test
    void enabledModuleAcceptsTheExactFlowDraftAuthority() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ApiFixtureSetCommitStore.class, () -> mock(ApiFixtureSetCommitStore.class))
                .withBean(SimulationRunStore.class, () -> mock(SimulationRunStore.class))
                .withBean(ReusableFlowDraftStore.class, () -> mock(ReusableFlowDraftStore.class))
                .run(context -> assertThat(context).hasSingleBean(SimulationModule.class));
    }

    @Test
    void enabledModuleAcceptsOneCustomProtectedFixtureResolver() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ApiFixtureSetCommitStore.class, () -> mock(ApiFixtureSetCommitStore.class))
                .withBean(SimulationRunStore.class, () -> mock(SimulationRunStore.class))
                .withBean(FixtureAssetSimulationResolver.class,
                        () -> mock(FixtureAssetSimulationResolver.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SimulationModule.class);
                    assertThat(context).hasSingleBean(FixtureAssetSimulationResolver.class);
                });
    }
}
