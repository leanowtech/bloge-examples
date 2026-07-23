package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.MirrorStatefulRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStateDataPlane;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStatePayloadProtector;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import javax.sql.DataSource;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorStatefulRuntimeConfigurationTest {

    @Test
    void requiresBothSwitchesAndNonProductionProfile() {
        try (var disabledParent = context(
                false, true, true, "test");
             var disabledStateful = context(
                     true, false, true, "test");
             var production = context(
                     true, true, true, "production");
             var mixed = context(
                     true, true, true, "production", "test")) {
            assertAbsent(disabledParent);
            assertAbsent(disabledStateful);
            assertAbsent(production);
            assertAbsent(mixed);
        }
    }

    @Test
    void assemblesDedicatedEncryptedDataPlaneInTestAndStaging() {
        try (var test = context(true, true, true, "test");
             var staging = context(true, true, true, "staging")) {
            assertPresent(test);
            assertPresent(staging);
        }
    }

    @Test
    void refusesControlDatabaseReuseAndMissingEncryptionAuthority() {
        assertThatThrownBy(() -> context(
                true, true, true, true, "test"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("must not reuse");
        assertThatThrownBy(() -> context(
                true, true, false, "test"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("must not be blank");
    }

    @Test
    void availabilityRechecksStoreAndFailsClosed() {
        AtomicBoolean ready = new AtomicBoolean(false);
        MirrorStatefulRuntimeAvailability availability =
                new MirrorStatefulRuntimeAvailability(
                        true, ready::get);

        assertThat(availability.sessionApi()).isTrue();
        assertThat(availability.stateStoreReady()).isFalse();
        ready.set(true);
        assertThat(availability.stateStoreReady()).isTrue();
        assertThat(new MirrorStatefulRuntimeAvailability(
                true, () -> {
                    throw new IllegalStateException("database unavailable");
                }).stateStoreReady()).isFalse();
    }

    private static AnnotationConfigApplicationContext context(
            boolean mirrorEnabled,
            boolean statefulEnabled,
            boolean keysPresent,
            String... profiles) {
        return context(mirrorEnabled, statefulEnabled, keysPresent,
                false, profiles);
    }

    private static AnnotationConfigApplicationContext context(
            boolean mirrorEnabled,
            boolean statefulEnabled,
            boolean keysPresent,
            boolean reuseControlDatabase,
            String... profiles) {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        String id = UUID.randomUUID().toString();
        String stateUrl = "jdbc:h2:mem:mirror-state-" + id
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false";
        String controlUrl = reuseControlDatabase
                ? stateUrl : "jdbc:h2:mem:control-" + id;
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 5);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("gateway.testing.mirror.enabled",
                mirrorEnabled);
        properties.put("gateway.testing.mirror.stateful.enabled",
                statefulEnabled);
        properties.put("gateway.testing.mirror.stateful.datasource.url",
                stateUrl);
        properties.put("spring.datasource.url", controlUrl);
        properties.put(
                "gateway.testing.mirror.stateful.encryption.active-key-id",
                keysPresent ? "test" : "");
        properties.put(
                "gateway.testing.mirror.stateful.encryption.key-ring",
                keysPresent
                        ? "test=" + Base64.getEncoder()
                        .encodeToString(key)
                        : "");
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource(
                        "mirror-stateful-runtime-test", properties));
        context.registerBean(
                ObjectMapper.class,
                () -> new ObjectMapper().findAndRegisterModules());
        context.register(MirrorStatefulRuntimeConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    private static void assertPresent(
            AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                MirrorStatefulRuntimeConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorStateDataPlane.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorStatePayloadProtector.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorSessionStateStore.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorStateBaselineResolver.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorSessionIntegrationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorStatefulRuntimeAvailability.class).values())
                .singleElement()
                .satisfies(availability -> {
                    assertThat(availability.sessionApi()).isTrue();
                    assertThat(availability.stateStoreReady()).isTrue();
                });
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }

    private static void assertAbsent(
            AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                MirrorStatefulRuntimeConfiguration.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorStateDataPlane.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorSessionStateStore.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorSessionIntegrationService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorStatefulRuntimeAvailability.class)).isEmpty();
    }
}
