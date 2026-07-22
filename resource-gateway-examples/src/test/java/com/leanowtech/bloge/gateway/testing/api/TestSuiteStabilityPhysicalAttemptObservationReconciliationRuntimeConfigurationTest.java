package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityJobRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptStartJournal;
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

class TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfigurationTest {

    private static final String PREFIX =
            TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfiguration
                    .Properties.PREFIX + ".";
    private static final String TERMINAL_PREFIX =
            TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration.Properties
                    .PREFIX + ".";
    private static final String POLLER_THREAD =
            "resource-gateway-physical-attempt-observation-reconciliation-poller-";

    @Test
    void disabledRuntimeInstallsNoObservationReconciliationBeans() {
        try (var context = context(properties(false, true), true, true, "test")) {
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.class))
                    .hasSize(1);
            assertRuntimeAbsent(context);
        }
    }

    @Test
    void productionPresencePhysicallyExcludesRuntime() {
        try (var production = context(properties(true, true), true, true, "production");
             var mixed = context(properties(true, true), true, true,
                     "production", "test")) {
            assertRuntimeAbsent(production);
            assertRuntimeAbsent(mixed);
        }
    }

    @Test
    void enabledTestProfileAssemblesDatabaseRuntimeAndClosesIt() throws Exception {
        var context = context(properties(true, true), true, true, "test");
        var scheduler = context.getBean(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.class);
        var supervisor = context.getBean(
                TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.class);
        try {
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptObservationReconciler.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.class))
                    .hasSize(1);
            assertThat(context.getBean(
                    TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.class)
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
    void enabledRuntimeRequiresAnExternallyAttestedProviderInventoryResolver() {
        var context = unrefreshedContext(properties(true, true), false, true, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining(
                            TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                                    .class.getName());
        } finally {
            context.close();
        }
    }

    @Test
    void arbitraryLegacyMapResolverCannotBypassSignedInventoryAdmission() {
        var context = unrefreshedContext(properties(true, true), false, true, "test");
        context.registerBean(
                TestSuiteStabilityPhysicalAttemptObservationReconciler.AuthorityResolver.class,
                () -> mock(TestSuiteStabilityPhysicalAttemptObservationReconciler
                        .AuthorityResolver.class));
        try {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining(
                            TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                                    .class.getName());
        } finally {
            context.close();
        }
    }

    @Test
    void observationRuntimeCannotStartWithoutTerminalProjectionChain() {
        var context = unrefreshedContext(properties(true, false), true, false, "test");
        context.registerBean(TestSuiteStabilityPhysicalAttemptStartJournal.class,
                () -> mock(DatabaseTestSuiteStabilityPhysicalAttemptStartJournal.class));
        context.registerBean(TestSuiteStabilityPhysicalAttemptObservationJournal.class,
                () -> mock(DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal.class));
        try {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining(
                            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                    .class.getName());
        } finally {
            context.close();
        }
    }

    @Test
    void observationRuntimeRejectsNonDatabaseJournalDependencies() {
        var context = unrefreshedContext(properties(true, false), true, false, "test");
        context.registerBean(TestSuiteStabilityPhysicalAttemptStartJournal.class,
                () -> mock(TestSuiteStabilityPhysicalAttemptStartJournal.class));
        context.registerBean(TestSuiteStabilityPhysicalAttemptObservationJournal.class,
                () -> mock(TestSuiteStabilityPhysicalAttemptObservationJournal.class));
        context.registerBean(TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.class,
                () -> mock(TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.class));
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Physical-attempt observation reconciliation runtime "
                            + "configuration is invalid");
        } finally {
            context.close();
        }
    }

    @Test
    void unsafeDeadlineLeaseBudgetFailsWithoutEchoingWorkerIdentity() {
        Map<String, Object> unsafe = properties(true, true);
        unsafe.put(PREFIX + "worker-id", "sensitive-worker-identity");
        unsafe.put(PREFIX + "confirmation-window-millis", "200");
        var context = unrefreshedContext(unsafe, true, true, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Physical-attempt observation reconciliation runtime "
                            + "configuration is invalid")
                    .hasMessageNotContaining("sensitive-worker-identity");
        } finally {
            context.close();
        }
    }

    @Test
    void unknownConfigurationFailsStrictBinding() {
        Map<String, Object> unknown = properties(true, true);
        unknown.put(PREFIX + "provider-private-key", "must-not-bind");
        var context = unrefreshedContext(unknown, true, true, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining("provider-private-key");
        } finally {
            context.close();
        }
    }

    private static AnnotationConfigApplicationContext context(
            Map<String, Object> properties,
            boolean resolver,
            boolean terminalRuntime,
            String... profiles) {
        var context = unrefreshedContext(properties, resolver, terminalRuntime, profiles);
        context.refresh();
        return context;
    }

    private static AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            boolean resolver,
            boolean terminalRuntime,
            String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("observation-reconciliation-runtime",
                        new LinkedHashMap<>(properties)));
        context.registerBean(ObjectMapper.class,
                () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(TestRuntimeDatabase.class,
                () -> new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:observation-reconciliation-runtime-" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2)),
                definition -> definition.setDestroyMethodName("close"));
        DatabaseTestSuiteStabilityJobRepository jobs =
                mock(DatabaseTestSuiteStabilityJobRepository.class);
        when(jobs.physicalAttemptFencingEnabled()).thenReturn(true);
        context.registerBean(TestSuiteStabilityJobRepository.class, () -> jobs);
        context.registerBean(TestSuiteStabilityRunRepository.class,
                () -> mock(TestSuiteStabilityRunRepository.class));
        context.registerBean(TestSuiteStabilityJobParentAuthority.class,
                () -> mock(TestSuiteStabilityJobParentAuthority.class));
        context.registerBean(TestSuiteStabilityQueuePolicy.class,
                TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfigurationTest
                        ::queuePolicy);
        context.registerBean(TestSuiteStabilityPhysicalAttemptStartVerifier.class,
                () -> mock(TestSuiteStabilityPhysicalAttemptStartVerifier.class));
        context.registerBean(TestSuiteStabilityPhysicalAttemptObservationVerifier.class,
                () -> mock(TestSuiteStabilityPhysicalAttemptObservationVerifier.class));
        context.registerBean(TestSuiteStabilityAttemptCancellationVerifier.class,
                () -> mock(TestSuiteStabilityAttemptCancellationVerifier.class));
        if (resolver) {
            context.registerBean(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class,
                    () -> mock(TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class));
        }
        if (terminalRuntime) {
            context.register(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration.class);
        }
        context.register(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfiguration
                        .class);
        return context;
    }

    private static TestSuiteStabilityQueuePolicy queuePolicy() {
        return new TestSuiteStabilityQueuePolicy(
                1, 100, 10, 4, 2, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1), 3, Duration.ofDays(7),
                Duration.ofDays(30));
    }

    private static Map<String, Object> properties(
            boolean observationEnabled, boolean terminalEnabled) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(TERMINAL_PREFIX + "enabled", Boolean.toString(terminalEnabled));
        properties.put(TERMINAL_PREFIX + "worker-id", "terminal-projection-test-worker");
        properties.put(TERMINAL_PREFIX + "maximum-pollers", "1");
        properties.put(TERMINAL_PREFIX + "initial-delay-millis", "0");
        properties.put(TERMINAL_PREFIX + "poll-interval-millis", "100");
        properties.put(TERMINAL_PREFIX + "drain-timeout-millis", "1000");
        properties.put(TERMINAL_PREFIX + "maximum-projection-timeout-millis", "100");
        properties.put(TERMINAL_PREFIX + "maximum-concurrent-calls", "1");
        properties.put(TERMINAL_PREFIX + "completion-reserve-millis", "100");
        properties.put(TERMINAL_PREFIX + "work-lease-duration-millis", "1000");
        properties.put(TERMINAL_PREFIX + "maximum-actionable-age-seconds", "1");
        properties.put(PREFIX + "enabled", Boolean.toString(observationEnabled));
        properties.put(PREFIX + "worker-id", "observation-reconciliation-test-worker");
        properties.put(PREFIX + "maximum-pollers", "1");
        properties.put(PREFIX + "initial-delay-millis", "0");
        properties.put(PREFIX + "poll-interval-millis", "100");
        properties.put(PREFIX + "drain-timeout-millis", "1000");
        properties.put(PREFIX + "descriptor-timeout-millis", "100");
        properties.put(PREFIX + "observation-timeout-millis", "100");
        properties.put(PREFIX + "maximum-concurrent-calls", "1");
        properties.put(PREFIX + "confirmation-window-millis", "300");
        properties.put(PREFIX + "lease-safety-margin-millis", "100");
        properties.put(PREFIX + "lease-duration-millis", "1000");
        properties.put(PREFIX + "active-poll-delay-millis", "100");
        properties.put(PREFIX + "initial-retry-delay-millis", "100");
        properties.put(PREFIX + "maximum-retry-delay-millis", "100");
        properties.put(PREFIX + "maximum-consecutive-uncertainty", "3");
        properties.put(PREFIX + "maximum-horizon-seconds", "60");
        properties.put(PREFIX + "discovery-page-size", "10");
        properties.put(PREFIX + "maximum-actionable-age-seconds", "1");
        properties.put(PREFIX + "maximum-quarantined-targets", "0");
        properties.put(PREFIX + "maximum-undiscovered-sources", "0");
        return properties;
    }

    private static void assertRuntimeAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.class))
                .isEmpty();
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
