package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MirrorIntegrationRouteIsolationTest {

    @Test
    void exposesPlanRunAndEvidenceRoutesOnlyInTheExplicitIsolatedComposition() {
        try (var test = context(true, "test");
             var staging = context(true, "staging");
             var disabled = context(false, "test")) {
            assertThat(routes(test)).contains(
                    "POST /api/mirror/plans",
                    "GET /api/mirror/plans/{planId}",
                    "POST /api/mirror/executions",
                    "GET /api/mirror/runs/{runId}",
                    "GET /api/mirror/runs/{runId}/evidence");
            assertThat(routes(staging)).contains(
                    "POST /api/mirror/plans",
                    "GET /api/mirror/plans/{planId}",
                    "POST /api/mirror/executions",
                    "GET /api/mirror/runs/{runId}",
                    "GET /api/mirror/runs/{runId}/evidence");
            assertThat(routes(disabled)).noneMatch(route -> route.contains("/api/mirror/"));
        }
    }

    @Test
    void productionProfilePhysicallyRemovesEveryMirrorRouteEvenWhenTestIsAlsoActive() {
        try (var production = context(true, "production");
             var mixed = context(true, "production", "test")) {
            assertThat(routes(production)).noneMatch(route -> route.contains("/api/mirror/"));
            assertThat(routes(mixed)).noneMatch(route -> route.contains("/api/mirror/"));
            assertThat(production.getBeansOfType(MirrorIntegrationController.class)).isEmpty();
            assertThat(mixed.getBeansOfType(MirrorIntegrationController.class)).isEmpty();
            assertThat(production.getBeansOfType(MirrorRunIntegrationController.class)).isEmpty();
            assertThat(mixed.getBeansOfType(MirrorRunIntegrationController.class)).isEmpty();
        }
    }

    private static AnnotationConfigWebApplicationContext context(
            boolean enabled, String... profiles) {
        AnnotationConfigWebApplicationContext context =
                new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "mirror-route-test", Map.of(
                "gateway.testing.mirror.enabled", Boolean.toString(enabled))));
        context.register(WebConfiguration.class, MirrorIntegrationController.class,
                MirrorRunIntegrationController.class);
        context.refresh();
        return context;
    }

    private static java.util.List<String> routes(
            ApplicationContext context) {
        RequestMappingHandlerMapping mappings =
                context.getBean(RequestMappingHandlerMapping.class);
        return mappings.getHandlerMethods().entrySet().stream()
                .flatMap(entry -> entry.getKey().getPatternValues().stream()
                        .flatMap(path -> entry.getKey().getMethodsCondition().getMethods().stream()
                                .map(method -> method.name() + " " + path)))
                .sorted().toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfiguration {
        @Bean
        MirrorPlanIntegrationService mirrorPlanIntegrationService() {
            return mock(MirrorPlanIntegrationService.class);
        }

        @Bean
        MirrorRunIntegrationService mirrorRunIntegrationService() {
            return mock(MirrorRunIntegrationService.class);
        }

        @Bean
        IntegrationRequestAuthenticator integrationRequestAuthenticator() {
            return mock(IntegrationRequestAuthenticator.class);
        }

        @Bean
        MirrorPlanRequestDecoder mirrorPlanRequestDecoder() {
            return new MirrorPlanRequestDecoder(
                    new ObjectMapper().findAndRegisterModules());
        }

        @Bean
        MirrorExecutionRequestDecoder mirrorExecutionRequestDecoder() {
            return new MirrorExecutionRequestDecoder(
                    new ObjectMapper().findAndRegisterModules());
        }
    }
}
