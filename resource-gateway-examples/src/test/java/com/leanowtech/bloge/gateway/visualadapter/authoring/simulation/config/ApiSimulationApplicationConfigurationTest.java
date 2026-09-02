package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation.config;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FixtureAssetSimulationResolver;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.FixturePlanCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModuleV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunV2Store;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiSimulationApplicationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ApiSimulationApplicationConfiguration.class);

    @Test
    void disabledModuleIsAbsentAndEnabledModuleRequiresAllThreeAuthorities() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SimulationModule.class);
            assertThat(context).doesNotHaveBean(SimulationModuleV2.class);
        });
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
                .withBean(SimulationRunV2Store.class, () -> mock(SimulationRunV2Store.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(FixturePlanCompiler.class);
                    assertThat(context).hasSingleBean(SimulationModule.class);
                    assertThat(context).hasSingleBean(SimulationModuleV2.class);
                });
    }

    @Test
    void enabledModuleAcceptsTheOptionalFlowPublicationAuthority() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ApiFixtureSetCommitStore.class, () -> mock(ApiFixtureSetCommitStore.class))
                .withBean(SimulationRunStore.class, () -> mock(SimulationRunStore.class))
                .withBean(SimulationRunV2Store.class, () -> mock(SimulationRunV2Store.class))
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
                .withBean(SimulationRunV2Store.class, () -> mock(SimulationRunV2Store.class))
                .withBean(ReusableFlowDraftStore.class, () -> mock(ReusableFlowDraftStore.class))
                .run(context -> assertThat(context).hasSingleBean(SimulationModule.class));
    }

    @Test
    void enabledModuleAcceptsOneCustomProtectedFixtureResolver() {
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ApiFixtureSetCommitStore.class, () -> mock(ApiFixtureSetCommitStore.class))
                .withBean(SimulationRunStore.class, () -> mock(SimulationRunStore.class))
                .withBean(SimulationRunV2Store.class, () -> mock(SimulationRunV2Store.class))
                .withBean(FixtureAssetSimulationResolver.class,
                        () -> mock(FixtureAssetSimulationResolver.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SimulationModule.class);
                    assertThat(context).hasSingleBean(SimulationModuleV2.class);
                    assertThat(context).hasSingleBean(FixtureAssetSimulationResolver.class);
                });
    }
}
