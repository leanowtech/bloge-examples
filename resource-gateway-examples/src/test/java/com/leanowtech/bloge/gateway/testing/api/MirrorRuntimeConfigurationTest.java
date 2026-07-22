package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanRepository;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

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
        context.registerBean(EmbeddedDatabase.class,
                () -> new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true).build(),
                definition -> definition.setDestroyMethodName("shutdown"));
        context.registerBean(JdbcTemplate.class,
                () -> new JdbcTemplate(context.getBean(EmbeddedDatabase.class)));
        context.register(TransactionConfiguration.class);
        context.register(MirrorRuntimeConfiguration.class);
        context.refresh();
        return context;
    }

    private static void assertMirrorKernelPresent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(MirrorRuntimeConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorPlanCompiler.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorEvidenceIntegrityService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorPlanRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorEvidenceRepository.class)).hasSize(1);
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorPlanRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorEvidenceRepository.class))).isTrue();
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
        assertThat(context.getBeansOfType(MirrorEvidenceIntegrityService.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorPlanRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorEvidenceRepository.class)).isEmpty();
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

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean
        PlatformTransactionManager transactionManager(EmbeddedDatabase database) {
            return new DataSourceTransactionManager(database);
        }
    }
}
