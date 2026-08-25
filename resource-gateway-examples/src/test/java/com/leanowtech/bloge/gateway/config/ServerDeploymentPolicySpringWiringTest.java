package com.leanowtech.bloge.gateway.config;

import com.leanowtech.bloge.gateway.visual.simulation.VisualProductionAdmissionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that production admission evidence remains server-owned at the Spring wiring boundary.
 *
 * <p>These tests intentionally register ordinary application beans of the same policy types. The
 * policy beans in {@link GatewayConfiguration} must not silently yield to them; an ambiguous final
 * guard is safer than a context that starts with a caller-controlled non-production decision.</p>
 */
class ServerDeploymentPolicySpringWiringTest {

    @Test
    void productionProfileRemainsProductionEvidence() {
        try (ConfigurableApplicationContext context = launch("production", "test", null)) {
            assertThat(context.getBean(GatewayConfiguration.ServerDeploymentPolicy.class))
                    .satisfies(policy -> {
                        assertThat(policy.productionDeployment()).isTrue();
                        assertThat(policy.environmentId()).isEqualTo("test");
                    });
            assertThat(context.getBean(VisualProductionAdmissionPolicy.class))
                    .satisfies(policy -> {
                        assertThat(policy.productionDeployment()).isTrue();
                        assertThat(policy.environmentId()).isEqualTo("test");
                    });
        }
    }

    @Test
    void productionServerEnvironmentRemainsProductionEvidenceWithoutProfile() {
        try (ConfigurableApplicationContext context = launch(null, "prod", null)) {
            assertThat(context.getBean(GatewayConfiguration.ServerDeploymentPolicy.class))
                    .extracting(GatewayConfiguration.ServerDeploymentPolicy::productionDeployment)
                    .isEqualTo(true);
            assertThat(context.getBean(VisualProductionAdmissionPolicy.class))
                    .extracting(VisualProductionAdmissionPolicy::productionDeployment)
                    .isEqualTo(true);
        }
    }

    @Test
    void ordinaryNonProductionServerPolicyCannotReplaceServerOwnedEvidence() {
        assertAmbiguousFinalGuard(NonProductionServerPolicyConfiguration.class,
                GatewayConfiguration.ServerDeploymentPolicy.class);
    }

    @Test
    void ordinaryNonProductionVisualPolicyCannotReplaceServerOwnedEvidence() {
        assertAmbiguousFinalGuard(NonProductionVisualPolicyConfiguration.class,
                VisualProductionAdmissionPolicy.class);
    }

    private static void assertAmbiguousFinalGuard(Class<?> applicationConfiguration,
                                                  Class<?> guardedType) {
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext context =
                         launch("production", "test", applicationConfiguration)) {
                context.getBean(guardedType);
            }
        })
                .isInstanceOf(NoUniqueBeanDefinitionException.class)
                .hasMessageContaining(guardedType.getSimpleName());
    }

    private static ConfigurableApplicationContext launch(String profile,
                                                          String environmentId,
                                                          Class<?> applicationConfiguration) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(GatewayConfiguration.class)
                .web(WebApplicationType.NONE)
                .lazyInitialization(true)
                .properties(
                        "gateway.integration.identity.environment-id=" + environmentId,
                        "spring.main.allow-bean-definition-overriding=false");
        if (profile != null) {
            builder.profiles(profile);
        }
        if (applicationConfiguration != null) {
            builder.sources(applicationConfiguration);
        }
        if (environmentId == null) {
            return builder.run();
        }
        return builder.run("--gateway.integration.identity.environment-id=" + environmentId);
    }

    @Configuration(proxyBeanMethods = false)
    static class NonProductionServerPolicyConfiguration {
        @Bean
        GatewayConfiguration.ServerDeploymentPolicy businessServerDeploymentPolicy() {
            return GatewayConfiguration.ServerDeploymentPolicy.nonProductionTest();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonProductionVisualPolicyConfiguration {
        @Bean
        VisualProductionAdmissionPolicy businessVisualProductionAdmissionPolicy() {
            return VisualProductionAdmissionPolicy.nonProductionTest();
        }
    }
}
