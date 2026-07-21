package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateRotationConvergenceConfigurationTest {

    @Test
    void strictSpringCompositionCreatesOneMonitorRuntimeAndFleetFencedFloor() {
        String target = ControlPlaneCertificateRotationTargets.TEST_SECRET_NOTARY;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("test");
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("certificate-rotation-convergence", properties(target)));
            context.registerBean(ObjectMapper.class,
                    () -> new ObjectMapper().findAndRegisterModules());
            context.registerBean(TestRuntimeDatabase.class,
                    () -> new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                            "jdbc:h2:mem:rotation-composition-" + UUID.randomUUID()
                                    + ";DB_CLOSE_DELAY=-1",
                            "sa", "", 2)), bean -> bean.setDestroyMethodName("close"));
            context.registerBean(ControlPlaneCertificateRotationTrustStore.class,
                    ControlPlaneCertificateRotationTrustStore::unavailable);
            context.registerBean(ControlPlaneCertificateRotationMaterialSource.class,
                    () -> (targetId, generation, materialId) -> {
                        throw new IllegalStateException("not needed by composition test");
                    });
            context.registerBean(ControlPlaneHttpTransport.SecretResolver.class,
                    () -> reference -> "unused".toCharArray());
            context.register(ControlPlaneCertificateRotationRuntimeConfiguration.class);

            context.refresh();

            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationConvergenceMonitor.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationRuntime.class)).hasSize(1);
            assertThat(context.getBean(ControlPlaneCertificateRotationRuntime.class).descriptor())
                    .satisfies(descriptor -> {
                        assertThat(descriptor.convergenceIntegrated()).isTrue();
                        assertThat(descriptor.convergenceAvailable()).isTrue();
                        assertThat(descriptor.servingReady()).isFalse();
                        assertThat(descriptor.productionReady()).isFalse();
                    });
            ControlPlaneCertificateRotationFloor floor = context.getBean(
                    ControlPlaneCertificateRotationFloorFactory.class).create(
                    "rg-staging", Map.of(target,
                            new ControlPlaneCertificateRotationFloor.InitialTarget(
                                    1, "initial", fingerprint('a'))));
            assertThat(floor.durable()).isTrue();
        }
    }

    private static Map<String, Object> properties(String target) {
        String rotation = ControlPlaneCertificateRotationRuntimeProperties.PREFIX;
        String convergence = ControlPlaneCertificateRotationConvergenceProperties.PREFIX;
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put(rotation + ".enabled", "true");
        values.put(rotation + ".required", "true");
        values.put(rotation + ".deployment-scope-id", "rg-staging");
        values.put(rotation + ".trust-domain", "enterprise-pki");
        values.put(rotation + ".accepted-policy-fingerprints", fingerprint('b'));
        values.put(rotation + ".signature-threshold", "1");
        values.put(rotation + ".authority-keys-json", "[{}]");
        values.put(rotation + ".initial-generations-json", "{\"" + target + "\":1}");
        values.put(rotation + ".material-catalog-json", "[{}]");
        values.put(convergence + ".enabled", "true");
        values.put(convergence + ".required", "true");
        values.put(convergence + ".fleet-id", "fleet-2026-07");
        values.put(convergence + ".instance-id", "replica-a");
        values.put(convergence + ".startup-id", UUID.randomUUID().toString());
        values.put(convergence + ".artifact-fingerprint", fingerprint('c'));
        values.put(convergence + ".expected-instance-ids", "replica-a");
        values.put(convergence + ".protocol-version", "convergence-v1");
        values.put(convergence + ".activation-mode", "ALL_REPLICAS");
        values.put(convergence + ".required-staged-replicas", "1");
        values.put(convergence + ".heartbeat-interval-seconds", "1");
        values.put(convergence + ".lease-duration-seconds", "3");
        return values;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
