package com.leanowtech.bloge.gateway.visualadapter.authoring.flow.config;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowModule;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visualadapter.authoring.flow.ApiResourceComposableCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReusableFlowAuthoringApplicationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReusableFlowAuthoringApplicationConfiguration.class);

    @Test
    void disabledRuntimeCreatesNoApplicationBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ComposableCatalog.class);
            assertThat(context).doesNotHaveBean(ReusableFlowModule.class);
        });
    }

    @Test
    void enabledRuntimeRequiresResourceAuthorityAndCreatesExactCatalogCompilerAndModule() {
        runner.withPropertyValues("gateway.authoring.reusable-flow.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ReusableFlowDraftStore.class, () -> mock(ReusableFlowDraftStore.class))
                .withBean(ReusableFlowPublicationStore.class, () -> mock(ReusableFlowPublicationStore.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(ComposableCatalog.class)
                            .isInstanceOf(ApiResourceComposableCatalog.class);
                    assertThat(context).hasSingleBean(ReusableFlowCompiler.class);
                    assertThat(context).hasSingleBean(ReusableFlowModule.class);
                });

        runner.withPropertyValues("gateway.authoring.reusable-flow.enabled=true")
                .withBean(ReusableFlowDraftStore.class, () -> mock(ReusableFlowDraftStore.class))
                .withBean(ReusableFlowPublicationStore.class, () -> mock(ReusableFlowPublicationStore.class))
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues("gateway.authoring.reusable-flow.enabled=true")
                .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class))
                .withBean(ReusableFlowDraftStore.class, () -> mock(ReusableFlowDraftStore.class))
                .run(context -> assertThat(context).hasFailed());
    }
}
