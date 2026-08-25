package com.leanowtech.bloge.gateway.visual.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunnerFactory;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationExecutor;
import com.leanowtech.bloge.gateway.visualadapter.DynamicGatewayComposerVisualDslRunner;
import com.leanowtech.bloge.gateway.visualadapter.VisualSimulationKernelAdapter;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the default service endpoint is wired to the kernel adapter. */
class VisualGraphSimulationSpringWiringTest {

    @Test
    void defaultServiceReceivesScannedKernelAdapter() {
        try (AnnotationConfigApplicationContext context = context(false)) {
            VisualGraphSimulationService service = context.getBean(VisualGraphSimulationService.class);
            VisualSimulationKernelAdapter adapter = context.getBean(VisualSimulationKernelAdapter.class);
            assertThat(context.getBean(VisualSimulationExecutor.class))
                    .isInstanceOf(VisualSimulationKernelAdapter.class)
                    .isSameAs(adapter);

            VisualGraphSimulationResponse response = service.simulate(eligibilityDraft(), Map.of(), "");

            assertThat(response.success()).isTrue();
            assertThat(response.mockedNodeIds()).containsExactly("eligibility");
            assertThat(response.mockedNodeIds()).allMatch(id -> !id.startsWith("__sim_"));
            assertThat(response.realNodeIds()).allMatch(id -> !id.startsWith("__sim_"));
        }
    }

    @Test
    void productionDefaultServiceRejectsSimulation() {
        try (AnnotationConfigApplicationContext context = context(true)) {
            assertThat(context.getBean(VisualProductionAdmissionPolicy.class).productionDeployment())
                    .isTrue();

            assertThatThrownBy(() -> context.getBean(VisualGraphSimulationService.class)
                    .simulate(eligibilityDraft(), Map.of("secret", "must-not-run"), ""))
                    .isInstanceOf(VisualSimulationProductionAdmissionException.class);
        }
    }

    private static AnnotationConfigApplicationContext context(boolean production) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "visual-wiring-test", Map.of("visual.test.production", production)));
        context.register(WiringConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = {VisualGraphSimulationService.class, VisualSimulationKernelAdapter.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {VisualGraphSimulationService.class, VisualSimulationKernelAdapter.class}))
    static class WiringConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        DefaultVisualOperatorCatalog catalog() {
            return VisualCatalogTestSupport.catalogWithLibrary(
                    VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        }

        @Bean
        GraphDraftValidator validator(VisualOperatorCatalog catalog) {
            return new GraphDraftValidator(catalog);
        }

        @Bean
        JsonSchemaSampleGenerator sampleGenerator() {
            return new JsonSchemaSampleGenerator();
        }

        @Bean
        VisualDslRunnerFactory legacyRunnerFactory() {
            return new DynamicGatewayComposerVisualDslRunner(new DefaultOperatorRegistry());
        }

        @Bean
        VisualProductionAdmissionPolicy deploymentPolicy(Environment environment) {
            boolean production = environment.getProperty("visual.test.production", Boolean.class, false);
            return production
                    ? VisualProductionAdmissionPolicy.productionDefault()
                    : VisualProductionAdmissionPolicy.nonProductionTest();
        }
    }

    private static GraphDraft eligibilityDraft() {
        return new GraphDraft(
                "", "", 0, "eligibilityPolicy", "", "", "", "", null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility", "risk:eligibility", "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")),
                        Map.of(), null)),
                List.of(), Map.of(), new GraphDraft.OutputSelection("eligibility", ""));
    }
}
