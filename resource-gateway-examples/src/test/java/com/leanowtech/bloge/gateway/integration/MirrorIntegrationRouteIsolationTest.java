package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioArtifactRegistryService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusGovernanceService;
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
                    "GET /api/mirror/runs/{runId}/evidence",
                    "POST /api/mirror/sessions",
                    "GET /api/mirror/sessions/{sessionId}",
                    "POST /api/mirror/sessions/{sessionId}/commands",
                    "DELETE /api/mirror/sessions/{sessionId}",
                    "POST /api/mirror/scenarios/assertions",
                    "POST /api/mirror/scenarios/checkpoints",
                    "POST /api/mirror/scenarios/cases",
                    "POST /api/mirror/scenarios/packs",
                    "GET /api/mirror/scenarios/packs/{packId}",
                    "POST /api/mirror/scenarios/packs/{packId}/compiled-plans",
                    "GET /api/mirror/scenarios/compiled-plans/{planId}",
                    "POST /api/mirror/trust/deployment-isolation/authority-key-sets",
                    "GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest",
                    "GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/generations/{generation}",
                    "POST /api/mirror/trust/deployment-isolation/attestations",
                    "GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest",
                    "GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revisions/{revision}",
                    "POST /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations",
                    "POST /api/mirror/observations",
                    "POST /api/mirror/observations/{observationId}/reviews",
                    "POST /api/mirror/corpus-candidates",
                    "POST /api/mirror/corpus-publications");
            assertThat(routes(staging)).contains(
                    "POST /api/mirror/plans",
                    "GET /api/mirror/plans/{planId}",
                    "POST /api/mirror/executions",
                    "GET /api/mirror/runs/{runId}",
                    "GET /api/mirror/runs/{runId}/evidence",
                    "POST /api/mirror/sessions",
                    "GET /api/mirror/sessions/{sessionId}",
                    "POST /api/mirror/sessions/{sessionId}/commands",
                    "DELETE /api/mirror/sessions/{sessionId}",
                    "POST /api/mirror/scenarios/assertions",
                    "POST /api/mirror/scenarios/checkpoints",
                    "POST /api/mirror/scenarios/cases",
                    "POST /api/mirror/scenarios/packs",
                    "GET /api/mirror/scenarios/packs/{packId}",
                    "POST /api/mirror/scenarios/packs/{packId}/compiled-plans",
                    "GET /api/mirror/scenarios/compiled-plans/{planId}",
                    "POST /api/mirror/trust/deployment-isolation/authority-key-sets",
                    "GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest",
                    "GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/generations/{generation}",
                    "POST /api/mirror/trust/deployment-isolation/attestations",
                    "GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest",
                    "GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revisions/{revision}",
                    "POST /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations",
                    "POST /api/mirror/observations",
                    "POST /api/mirror/observations/{observationId}/reviews",
                    "POST /api/mirror/corpus-candidates",
                    "POST /api/mirror/corpus-publications");
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
            assertThat(production.getBeansOfType(
                    MirrorSessionController.class)).isEmpty();
            assertThat(mixed.getBeansOfType(
                    MirrorSessionController.class)).isEmpty();
            assertThat(production.getBeansOfType(
                    ScenarioRehearsalController.class)).isEmpty();
            assertThat(mixed.getBeansOfType(
                    ScenarioRehearsalController.class)).isEmpty();
            assertThat(production.getBeansOfType(
                    MirrorDeploymentIsolationAuthorityPublicationController.class)).isEmpty();
            assertThat(mixed.getBeansOfType(
                    MirrorDeploymentIsolationAuthorityPublicationController.class)).isEmpty();
            assertThat(production.getBeansOfType(
                    MirrorDeploymentIsolationAttestationController.class)).isEmpty();
            assertThat(mixed.getBeansOfType(
                    MirrorDeploymentIsolationAttestationController.class)).isEmpty();
            assertThat(production.getBeansOfType(
                    CapabilityObservationController.class)).isEmpty();
            assertThat(mixed.getBeansOfType(
                    CapabilityObservationController.class)).isEmpty();
            assertThat(production.getBeansOfType(
                    CapabilityCorpusGovernanceController.class)).isEmpty();
            assertThat(mixed.getBeansOfType(
                    CapabilityCorpusGovernanceController.class)).isEmpty();
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
                "gateway.testing.mirror.enabled", Boolean.toString(enabled),
                "gateway.testing.mirror.stateful.enabled",
                Boolean.toString(enabled))));
        context.register(WebConfiguration.class, MirrorIntegrationController.class,
                MirrorRunIntegrationController.class,
                MirrorSessionController.class,
                ScenarioRehearsalController.class,
                MirrorDeploymentIsolationAuthorityPublicationController.class,
                MirrorDeploymentIsolationAttestationController.class,
                CapabilityObservationController.class,
                CapabilityCorpusGovernanceController.class);
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
        MirrorSessionIntegrationService mirrorSessionIntegrationService() {
            return mock(MirrorSessionIntegrationService.class);
        }

        @Bean
        ScenarioArtifactRegistryService scenarioArtifactRegistryService() {
            return mock(ScenarioArtifactRegistryService.class);
        }

        @Bean
        ScenarioRehearsalIntegrationService
        scenarioRehearsalIntegrationService() {
            return mock(ScenarioRehearsalIntegrationService.class);
        }

        @Bean
        MirrorDeploymentIsolationAuthorityPublicationService
        mirrorDeploymentIsolationAuthorityPublicationService() {
            return mock(MirrorDeploymentIsolationAuthorityPublicationService.class);
        }

        @Bean
        MirrorDeploymentIsolationAttestationService
        mirrorDeploymentIsolationAttestationService() {
            return mock(MirrorDeploymentIsolationAttestationService.class);
        }

        @Bean
        CapabilityObservationAdmissionService
        capabilityObservationAdmissionService() {
            return mock(CapabilityObservationAdmissionService.class);
        }

        @Bean
        CapabilityCorpusGovernanceService capabilityCorpusGovernanceService() {
            return mock(CapabilityCorpusGovernanceService.class);
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

        @Bean
        MirrorSessionRequestDecoder mirrorSessionRequestDecoder() {
            return new MirrorSessionRequestDecoder(
                    new ObjectMapper().findAndRegisterModules());
        }

        @Bean
        ScenarioArtifactRequestDecoder scenarioArtifactRequestDecoder() {
            return new ScenarioArtifactRequestDecoder(
                    new ObjectMapper().findAndRegisterModules());
        }

        @Bean
        MirrorDeploymentIsolationAuthorityPublicationDecoder
        mirrorDeploymentIsolationAuthorityPublicationDecoder() {
            return new MirrorDeploymentIsolationAuthorityPublicationDecoder(
                    new ObjectMapper().findAndRegisterModules());
        }

        @Bean
        MirrorDeploymentIsolationAttestationDecoder
        mirrorDeploymentIsolationAttestationDecoder() {
            return new MirrorDeploymentIsolationAttestationDecoder(
                    new ObjectMapper().findAndRegisterModules());
        }

        @Bean
        CapabilityObservationDecoder capabilityObservationDecoder() {
            return new CapabilityObservationDecoder(
                    new ObjectMapper().findAndRegisterModules());
        }

        @Bean
        CapabilityCorpusGovernanceDecoder capabilityCorpusGovernanceDecoder() {
            return new CapabilityCorpusGovernanceDecoder(
                    new ObjectMapper().findAndRegisterModules());
        }
    }
}
