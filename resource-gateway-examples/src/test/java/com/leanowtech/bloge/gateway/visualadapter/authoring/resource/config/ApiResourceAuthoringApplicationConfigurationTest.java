package com.leanowtech.bloge.gateway.visualadapter.authoring.resource.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.resource.ApiResourceAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewFailure;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewIdentity;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.OpenApiPreviewModule;
import com.leanowtech.bloge.gateway.visual.authoring.resource.openapi.RemoteOpenApiDocumentGateway;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionProjectionResolver;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImporter;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Conditional adapter-side application-assembly contract for the Resource tracer. */
class ApiResourceAuthoringApplicationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiResourceAuthoringApplicationConfiguration.class))
            .withBean(ApiResourceDecisions.class, ApiResourceDecisions::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(OpenApiResourceDesignContractImporter.class, OpenApiResourceDesignContractImporter::new)
            .withBean(JsonSchemaSampleGenerator.class, JsonSchemaSampleGenerator::new)
            .withBean(ApiResourceCommitStore.class, () -> mock(ApiResourceCommitStore.class));

    @Test
    void disabledRuntimeCreatesNoApplicationBeans() {
        runner.withBean(ApiConnectionAuthoringStore.class, () -> mock(ApiConnectionAuthoringStore.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ApiResourceAuthoringFacade.class);
                    assertThat(context).doesNotHaveBean(ApiResourceConnectionProjectionResolver.class);
                    assertThat(context).doesNotHaveBean(OpenApiPreviewModule.class);
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
                    assertThat(context).hasSingleBean(OpenApiPreviewModule.class);
                });
    }

    @Test
    void enabledRuntimeUsesAnExplicitGovernedRemoteGatewayAndDefaultsToUnavailable() {
        OpenApiPreviewCommand command = new OpenApiPreviewCommand(OpenApiPreviewCommand.SCHEMA_VERSION,
                new OpenApiPreviewCommand.Remote("https://api.example.test/openapi.yaml", null), List.of());
        OpenApiPreviewIdentity identity = new OpenApiPreviewIdentity(
                new AuthoringScope("tenant-a", "project-a", "test"), "author-a",
                "API_RESOURCE_AUTHORING");

        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiConnectionAuthoringStore.class, () -> mock(ApiConnectionAuthoringStore.class))
                .run(context -> assertThatThrownBy(() -> context.getBean(OpenApiPreviewModule.class)
                        .preview(command, identity))
                        .isInstanceOf(OpenApiPreviewFailure.class)
                        .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                        .isEqualTo(OpenApiPreviewFailure.Code.CAPABILITY_UNAVAILABLE));

        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiConnectionAuthoringStore.class, () -> mock(ApiConnectionAuthoringStore.class))
                .withBean(RemoteOpenApiDocumentGateway.class, () -> request -> {
                    throw new OpenApiPreviewFailure(OpenApiPreviewFailure.Code.REMOTE_FETCH_FAILED);
                })
                .run(context -> assertThatThrownBy(() -> context.getBean(OpenApiPreviewModule.class)
                        .preview(command, identity))
                        .isInstanceOf(OpenApiPreviewFailure.class)
                        .extracting(failure -> ((OpenApiPreviewFailure) failure).code())
                        .isEqualTo(OpenApiPreviewFailure.Code.REMOTE_FETCH_FAILED));
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
