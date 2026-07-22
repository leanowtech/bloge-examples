package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorRuntimeConfigurationTest {

    @Test
    void disabledSwitchInstallsNoMirrorKernelInTestProfile() {
        try (var context = context(false, "test")) {
            assertMirrorKernelAbsent(context);
        }
    }

    @Test
    void explicitSwitchAssemblesMirrorKernelInTestAndStagingOnly() {
        try (var test = context(true, "test");
             var staging = context(true, "staging")) {
            assertMirrorKernelPresent(test);
            assertMirrorKernelPresent(staging);
        }
    }

    @Test
    void productionPresencePhysicallyExcludesMirrorEvenWhenTestIsAlsoActive() {
        try (var production = context(true, "production");
             var mixed = context(true, "production", "test")) {
            assertMirrorKernelAbsent(production);
            assertMirrorKernelAbsent(mixed);
        }
    }

    private static AnnotationConfigApplicationContext context(boolean enabled,
                                                              String... profiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "mirror-runtime-test", Map.of("gateway.testing.mirror.enabled", enabled)));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(OperatorRegistry.class, DefaultOperatorRegistry::new);
        context.registerBean(ResourceRegistry.class, EmptyResourceRegistry::new);
        context.registerBean(BlgeExpressionEvaluator.class,
                () -> new BlgeExpressionEvaluator());
        context.registerBean(VisualEvidenceSigner.class, VisualEvidenceSigner::unavailable);
        context.register(MirrorRuntimeConfiguration.class);
        context.refresh();
        return context;
    }

    private static void assertMirrorKernelPresent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(MirrorRuntimeConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorPlanCompiler.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunService.class)).hasSize(1);
        assertThat(context.getBean(MirrorRunService.class).engineConfiguration())
                .satisfies(configuration -> {
                    assertThat(configuration.interceptorTypes()).isEmpty();
                    assertThat(configuration.listenerTypes())
                            .containsExactly("com.leanowtech.bloge.gateway.testing.runtime.InvocationRecorder");
                    assertThat(configuration.durableStores()).isFalse();
                    assertThat(configuration.productionContextCarriers()).isFalse();
                    assertThat(configuration.productionExtensionListeners()).isFalse();
                });
    }

    private static void assertMirrorKernelAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(MirrorRuntimeConfiguration.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorPlanCompiler.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorRunService.class)).isEmpty();
    }

    private static final class EmptyResourceRegistry implements ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            throw new ResourceNotFoundException(resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return false;
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of();
        }
    }
}
