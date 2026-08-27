package com.leanowtech.bloge.gateway.visualadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.config.GatewayConfiguration;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the visual adapter receives the production descriptor runtime bean. */
class VisualSimulationRuntimeSpringWiringTest {

    @Test
    void adapterContextContainsDescriptorRuntime() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(WiringConfiguration.class)) {
            assertThat(context.getBean(ResourceFixtureRuntime.class)).isNotNull();
            VisualSimulationKernelAdapter adapter = context.getBean(VisualSimulationKernelAdapter.class);
            assertThat(adapter).isNotNull();
            assertThat(ReflectionTestUtils.getField(adapter, "resourceRuntime"))
                    .isSameAs(context.getBean(ResourceFixtureRuntime.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = VisualSimulationKernelAdapter.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = VisualSimulationKernelAdapter.class))
    static class WiringConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        BlgeExpressionEvaluator evaluator() {
            return new BlgeExpressionEvaluator();
        }

        @Bean
        ResourceRegistry resourceRegistry() {
            return new EmptyResourceRegistry();
        }

        @Bean
        ResourceFixtureRuntime resourceFixtureRuntime(ResourceRegistry registry,
                                                       BlgeExpressionEvaluator evaluator,
                                                       ObjectMapper mapper) {
            return new GatewayConfiguration().resourceFixtureRuntime(registry, evaluator, mapper);
        }
    }

    private static final class EmptyResourceRegistry implements ResourceRegistry {
        @Override
        public com.leanowtech.bloge.gateway.resource.ResourceDescriptor resolve(String resourceId) {
            throw new IllegalArgumentException("No test resource: " + resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return false;
        }

        @Override
        public java.util.Collection<com.leanowtech.bloge.gateway.resource.ResourceDescriptor> all() {
            return java.util.List.of();
        }
    }
}
