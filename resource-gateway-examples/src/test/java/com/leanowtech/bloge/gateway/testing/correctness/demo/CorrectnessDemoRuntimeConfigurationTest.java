package com.leanowtech.bloge.gateway.testing.correctness.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.CorrectnessAuthoringRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceQuery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrectnessDemoRuntimeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withConfiguration(AutoConfigurations.of(CorrectnessDemoRuntimeConfiguration.class));

    @Test
    void remainsAbsentUnlessExplicitlyEnabledInANonProductionProfile() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CorrectnessWorkspaceQuery.class));

        runner.withPropertyValues(
                        "spring.profiles.active=production",
                        "gateway.testing.correctness.demo.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CorrectnessWorkspaceQuery.class));
    }

    @Test
    void exposesARealScopeExactPayloadFreeWorkspaceWithoutAdvertisingCommands() {
        runner.withPropertyValues(
                        "spring.profiles.active=test",
                        "gateway.testing.correctness.demo.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CorrectnessWorkspaceQuery.class);
                    assertThat(context.getBean(CorrectnessAuthoringRuntimeAvailability.class))
                            .isEqualTo(new CorrectnessAuthoringRuntimeAvailability(
                                    true, false, false, false, false, false,
                                    false, false, false, false, false));

                    var workspace = context.getBean(CorrectnessWorkspaceQuery.class).get(
                            TargetKind.GRAPH,
                            CorrectnessDemoRuntimeConfiguration.TARGET_ID,
                            CorrectnessDemoRuntimeConfiguration.TARGET_FINGERPRINT,
                            CorrectnessDemoRuntimeConfiguration.DEFINITION_ID,
                            "",
                            100,
                            identity("tenant-a"));

                    assertThat(workspace.definition().title())
                            .isEqualTo("Loan decision correctness");
                    assertThat(workspace.coverage().total()).isEqualTo(9);
                    assertThat(workspace.coverage().fulfilled()).isEqualTo(7);
                    assertThat(workspace.cases().rows()).hasSize(8);
                    assertThat(workspace.fixtures().rows()).hasSize(5);
                    assertThat(workspace.lastPublication()).isNotNull();
                    assertThat(workspace.lastRun()).isNotNull();
                    assertThat(workspace.commandPolicy().commands())
                            .allSatisfy((command, availability) -> {
                                assertThat(availability.allowed()).isFalse();
                                assertThat(availability.reasonCode()).isEqualTo("DEMO_READ_ONLY");
                            });
                    assertThat(context.getBean(ObjectMapper.class)
                            .valueToTree(workspace).toString())
                            .doesNotContain("fixture_payload", "request_payload", "secret");
                });
    }

    @Test
    void refusesTheSameCoordinateAcrossEnterpriseScopes() {
        runner.withPropertyValues(
                        "spring.profiles.active=test",
                        "gateway.testing.correctness.demo.enabled=true")
                .run(context -> assertThatThrownBy(() ->
                        context.getBean(CorrectnessWorkspaceQuery.class).get(
                                TargetKind.GRAPH,
                                CorrectnessDemoRuntimeConfiguration.TARGET_ID,
                                CorrectnessDemoRuntimeConfiguration.TARGET_FINGERPRINT,
                                CorrectnessDemoRuntimeConfiguration.DEFINITION_ID,
                                "",
                                100,
                                identity("tenant-b")))
                        .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                                assertThat(failure.problem().code())
                                        .isEqualTo("RG.CORRECTNESS.DEFINITION_NOT_FOUND")));
    }

    private static IntegrationRequestContext identity(String tenantId) {
        return new IntegrationRequestContext(
                tenantId, "knowledge-governance", "tool-studio", "test", "local",
                "WORKLOAD", "aneke-sync", "", "CORRECTNESS_READ", "corr-demo");
    }
}
