package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioDemoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withUserConfiguration(
                    CapabilityStudioDemoConfiguration.class,
                    CapabilityStudioDemoController.class);

    @Test
    void isAbsentWithoutTheExplicitDemoProperty() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityStudioGoldenDemoPack.class)
                        .doesNotHaveBean(CapabilityStudioDemoController.class));
    }

    @Test
    void isAbsentOutsideTestAndStagingEvenWhenExplicitlyEnabled() {
        runner.withPropertyValues("gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityStudioGoldenDemoPack.class)
                        .doesNotHaveBean(CapabilityStudioDemoController.class));
    }

    @Test
    void isAbsentInProductionEvenWhenExplicitlyEnabled() {
        runner.withPropertyValues(
                        "spring.profiles.active=production",
                        "gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityStudioGoldenDemoPack.class)
                        .doesNotHaveBean(CapabilityStudioDemoController.class));
    }

    @Test
    void isAvailableOnlyInTestOrStagingWhenExplicitlyEnabled() {
        runner.withPropertyValues(
                        "spring.profiles.active=test",
                        "gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(CapabilityStudioGoldenDemoPack.class)
                        .hasSingleBean(CapabilityStudioDemoController.class));

        runner.withPropertyValues(
                        "spring.profiles.active=staging",
                        "gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(CapabilityStudioGoldenDemoPack.class));
    }
}
