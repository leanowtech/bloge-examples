package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityJobRepository;
import com.leanowtech.bloge.gateway.testing.persistence.TestSuiteStabilityJobRequestKeyProtector;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfigurationTest {

    private static final String PREFIX =
            TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration.Properties
                    .PREFIX + ".";
    private static final String POLLER_THREAD =
            "resource-gateway-physical-attempt-terminal-projection-poller-";

    @Test
    void disabledRuntimeInstallsNoTerminalProjectionBeans() {
        try (var context = context(Map.of(), true, true, "test")) {
            assertRuntimeAbsent(context);
        }
    }

    @Test
    void productionPresencePhysicallyExcludesRuntimeEvenWhenTestIsActive() {
        try (var production = context(enabledProperties(), true, true, "production");
             var mixed = context(enabledProperties(), true, true, "production", "test")) {
            assertRuntimeAbsent(production);
            assertRuntimeAbsent(mixed);
        }
    }

    @Test
    void enabledTestProfileAssemblesOneDatabaseFencedRuntimeAndClosesIt() throws Exception {
        var context = context(enabledProperties(), true, true, "test");
        var scheduler = context.getBean(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.class);
        var supervisor = context.getBean(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.class);
        try {
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.class))
                    .hasSize(1);
            assertThat(context.getBean(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.class)
                    .health().getStatus()).isEqualTo(Status.UP);
            assertThat(scheduler.snapshot().policy().maximumPollers()).isEqualTo(1);
            assertThat(supervisor.snapshot().policy().maximumConcurrentCalls()).isEqualTo(1);
        } finally {
            context.close();
        }

        assertThat(scheduler.snapshot().closed()).isTrue();
        assertThat(supervisor.snapshot().closed()).isTrue();
        awaitNoPollerThreads();
    }

    @Test
    void enabledRuntimeRequiresAllPinnedProviderVerifiers() {
        var context = unrefreshedContext(enabledProperties(), false, true, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining(
                            TestSuiteStabilityPhysicalAttemptStartVerifier.class.getName());
        } finally {
            context.close();
        }
    }

    @Test
    void enabledRuntimeRejectsANonDatabaseQueueAuthority() {
        var context = unrefreshedContext(enabledProperties(), true, false, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "Physical-attempt terminal projection runtime configuration is invalid");
        } finally {
            context.close();
        }
    }

    @Test
    void enabledRuntimeRejectsDatabaseQueueWithoutPhysicalAttemptFencing() {
        var context = unrefreshedContext(
                enabledProperties(), true, true, false, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "Physical-attempt terminal projection runtime configuration is invalid");
        } finally {
            context.close();
        }
    }

    @Test
    void testRuntimeQueueActivatesPhysicalFenceFromTerminalProjectionSwitch() {
        try (var database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:terminal-projection-queue-switch-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 2))) {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            var parentAuthority = mock(TestSuiteStabilityJobParentAuthority.class);
            var requestKeys = TestSuiteStabilityJobRequestKeyProtector.fromConfiguration(
                    "terminal-projection-test-key",
                    "terminal-projection-test-key=QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=");
            var configuration = new TestRuntimeConfiguration();

            var enabled = (DatabaseTestSuiteStabilityJobRepository)
                    configuration.testSuiteStabilityJobRepository(
                            database, mapper, parentAuthority, requestKeys, "retention-owner-a",
                            120, true);
            var disabled = (DatabaseTestSuiteStabilityJobRepository)
                    configuration.testSuiteStabilityJobRepository(
                            database, mapper, parentAuthority, requestKeys, "retention-owner-b",
                            120, false);

            assertThat(enabled.physicalAttemptFencingEnabled()).isTrue();
            assertThat(disabled.physicalAttemptFencingEnabled()).isFalse();
        }
    }

    @Test
    void unsafeLeaseBudgetAndUnknownPropertiesFailWithoutEchoingWorkerIdentity() {
        Map<String, Object> unsafe = enabledProperties();
        unsafe.put(PREFIX + "worker-id", "sensitive-worker-identity");
        unsafe.put(PREFIX + "work-lease-duration-millis", "200");
        var unsafeContext = unrefreshedContext(unsafe, true, true, "test");
        try {
            assertThatThrownBy(unsafeContext::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "Physical-attempt terminal projection runtime configuration is invalid")
                    .hasMessageNotContaining("sensitive-worker-identity");
        } finally {
            unsafeContext.close();
        }

        Map<String, Object> unknown = enabledProperties();
        unknown.put(PREFIX + "provider-private-key", "must-not-bind");
        var unknownContext = unrefreshedContext(unknown, true, true, "test");
        try {
            assertThatThrownBy(unknownContext::refresh)
                    .hasStackTraceContaining("provider-private-key");
        } finally {
            unknownContext.close();
        }
    }

    private static AnnotationConfigApplicationContext context(
            Map<String, Object> properties,
            boolean verifiers,
            boolean databaseQueue,
            String... profiles) {
        var context = unrefreshedContext(properties, verifiers, databaseQueue, profiles);
        context.refresh();
        return context;
    }

    private static AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            boolean verifiers,
            boolean databaseQueue,
            String... profiles) {
        return unrefreshedContext(
                properties, verifiers, databaseQueue, true, profiles);
    }

    private static AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            boolean verifiers,
            boolean databaseQueue,
            boolean physicalAttemptFencing,
            String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("terminal-projection-runtime",
                        new LinkedHashMap<>(properties)));
        context.registerBean(ObjectMapper.class,
                () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(TestRuntimeDatabase.class,
                () -> new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:terminal-projection-runtime-" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2)),
                definition -> definition.setDestroyMethodName("close"));
        TestSuiteStabilityJobRepository jobs = databaseQueue
                ? mock(DatabaseTestSuiteStabilityJobRepository.class)
                : mock(TestSuiteStabilityJobRepository.class);
        if (jobs instanceof DatabaseTestSuiteStabilityJobRepository databaseJobs) {
            when(databaseJobs.physicalAttemptFencingEnabled())
                    .thenReturn(physicalAttemptFencing);
        }
        context.registerBean(TestSuiteStabilityJobRepository.class, () -> jobs);
        context.registerBean(TestSuiteStabilityRunRepository.class,
                () -> mock(TestSuiteStabilityRunRepository.class));
        context.registerBean(TestSuiteStabilityJobParentAuthority.class,
                () -> mock(TestSuiteStabilityJobParentAuthority.class));
        context.registerBean(TestSuiteStabilityQueuePolicy.class,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfigurationTest
                        ::queuePolicy);
        if (verifiers) {
            context.registerBean(TestSuiteStabilityPhysicalAttemptStartVerifier.class,
                    () -> mock(TestSuiteStabilityPhysicalAttemptStartVerifier.class));
            context.registerBean(TestSuiteStabilityPhysicalAttemptObservationVerifier.class,
                    () -> mock(TestSuiteStabilityPhysicalAttemptObservationVerifier.class));
            context.registerBean(TestSuiteStabilityAttemptCancellationVerifier.class,
                    () -> mock(TestSuiteStabilityAttemptCancellationVerifier.class));
        }
        context.register(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration.class);
        return context;
    }

    private static TestSuiteStabilityQueuePolicy queuePolicy() {
        return new TestSuiteStabilityQueuePolicy(
                1, 100, 10, 4, 2, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1), 3, Duration.ofDays(7),
                Duration.ofDays(30));
    }

    private static Map<String, Object> enabledProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PREFIX + "enabled", "true");
        properties.put(PREFIX + "worker-id", "terminal-projection-test-worker");
        properties.put(PREFIX + "maximum-pollers", "1");
        properties.put(PREFIX + "initial-delay-millis", "0");
        properties.put(PREFIX + "poll-interval-millis", "100");
        properties.put(PREFIX + "drain-timeout-millis", "1000");
        properties.put(PREFIX + "maximum-projection-timeout-millis", "100");
        properties.put(PREFIX + "maximum-concurrent-calls", "1");
        properties.put(PREFIX + "completion-reserve-millis", "100");
        properties.put(PREFIX + "work-lease-duration-millis", "1000");
        properties.put(PREFIX + "maximum-actionable-age-seconds", "1");
        return properties;
    }

    private static void assertRuntimeAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.class)).isEmpty();
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.class)).isEmpty();
    }

    private static void awaitNoPollerThreads() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && pollerThreads() > 0) {
            Thread.sleep(10);
        }
        assertThat(pollerThreads()).isZero();
    }

    private static long pollerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith(POLLER_THREAD))
                .count();
    }
}
