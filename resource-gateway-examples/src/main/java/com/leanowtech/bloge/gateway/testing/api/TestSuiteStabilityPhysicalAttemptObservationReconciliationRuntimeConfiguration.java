package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptStartJournal;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Explicit Spring composition root for autonomous physical-attempt observation reconciliation.
 *
 * <p>The runtime is physically absent when {@code production} is active and remains inert in
 * {@code test}/{@code staging} until explicitly enabled. Enabling it requires the already enabled
 * database terminal-projection chain plus an exact provider/deployment authority resolver. No
 * local provider, permissive resolver, or trust fallback is created.</p>
 *
 * <p>The composition owns database discovery/lease/retry state, a zero-queue provider-call
 * supervisor, reconciler, bounded scheduler, fixed-cardinality telemetry, and aggregate health.
 * Verified terminal completion and terminal-projection work registration share the reconciliation
 * journal transaction.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix =
        TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfiguration.Properties
                .PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(
        TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfiguration.Properties
                .class)
public class TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfiguration {

    /** Creates the profile- and property-gated composition root. */
    public TestSuiteStabilityPhysicalAttemptObservationReconciliationRuntimeConfiguration() {
    }

    /**
     * Rejects adapters that cannot participate in the default same-database completion boundary.
     */
    @Bean
    ValidatedDependencies physicalAttemptObservationReconciliationDependencies(
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationJournal observations,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal terminalWork) {
        if (!(Objects.requireNonNull(starts, "starts")
                instanceof DatabaseTestSuiteStabilityPhysicalAttemptStartJournal databaseStarts)
                || !(Objects.requireNonNull(observations, "observations")
                instanceof DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal
                databaseObservations)
                || !(Objects.requireNonNull(terminalWork, "terminalWork")
                instanceof DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                databaseTerminalWork)) {
            throw Properties.invalid();
        }
        return new ValidatedDependencies(
                databaseStarts, databaseObservations, databaseTerminalWork);
    }

    /** Creates fair database discovery, lease, retry, and atomic work-registration state. */
    @Bean
    DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal
            testSuiteStabilityPhysicalAttemptObservationReconciliationJournal(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ValidatedDependencies dependencies,
            Properties properties) {
        return new DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal(
                database.jdbc(), objectMapper, dependencies.starts(), properties.workPolicy(),
                database.transactionManager(), dependencies.terminalWork());
    }

    /** Creates the fixed-capacity zero-queue descriptor and observation boundary. */
    @Bean(destroyMethod = "close")
    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
            testSuiteStabilityPhysicalAttemptObservationCallSupervisor(Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor(
                properties.callPolicy());
    }

    /** Creates the only allowed descriptor/prepare/authorize/observe/accept ordering boundary. */
    @Bean
    TestSuiteStabilityPhysicalAttemptObservationCoordinator
            testSuiteStabilityPhysicalAttemptObservationCoordinator(
            ValidatedDependencies dependencies,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor) {
        return new TestSuiteStabilityPhysicalAttemptObservationCoordinator(
                dependencies.observations(), supervisor);
    }

    /** Creates one bounded single-target reconciler using the embedder's exact resolver. */
    @Bean
    TestSuiteStabilityPhysicalAttemptObservationReconciler
            testSuiteStabilityPhysicalAttemptObservationReconciler(
            ObjectMapper objectMapper,
            DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal work,
            ValidatedDependencies dependencies,
            TestSuiteStabilityPhysicalAttemptObservationCoordinator coordinator,
            TestSuiteStabilityPhysicalAttemptObservationReconciler.AuthorityResolver authorities,
            Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciler(
                objectMapper, work, dependencies.starts(), dependencies.observations(),
                coordinator, authorities, properties.reconcilerPolicy());
    }

    /** Registers only fixed reconciliation-stage and lifecycle metrics. */
    @Bean
    TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry
            testSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Starts bounded local lanes and owns their graceful drain lifecycle. */
    @Bean(destroyMethod = "close")
    TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler
            testSuiteStabilityPhysicalAttemptObservationReconciliationScheduler(
            TestSuiteStabilityPhysicalAttemptObservationReconciler reconciler,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationTelemetry telemetry,
            Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler(
                reconciler, properties.workerId(), properties.schedulePolicy(), telemetry);
    }

    /** Exposes aggregate discovery, backlog, scheduler, and provider-capacity readiness. */
    @Bean
    TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth
            testSuiteStabilityPhysicalAttemptObservationReconciliationHealth(
            DatabaseTestSuiteStabilityPhysicalAttemptObservationReconciliationJournal work,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler scheduler,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor,
            Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth(
                work, scheduler, supervisor, properties.healthPolicy());
    }

    private record ValidatedDependencies(
            DatabaseTestSuiteStabilityPhysicalAttemptStartJournal starts,
            DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal observations,
            DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal terminalWork) {
        private ValidatedDependencies {
            starts = Objects.requireNonNull(starts, "starts");
            observations = Objects.requireNonNull(observations, "observations");
            terminalWork = Objects.requireNonNull(terminalWork, "terminalWork");
        }
    }

    /**
     * Strict local observation-reconciliation runtime configuration.
     *
     * @param enabled explicit test/staging activation switch
     * @param workerId stable pre-authenticated replica identity
     * @param maximumPollers fixed local reconciliation lanes
     * @param initialDelayMillis delay before first local poll
     * @param pollIntervalMillis fixed delay after each local poll
     * @param drainTimeoutMillis bounded scheduler shutdown wait
     * @param descriptorTimeoutMillis maximum provider descriptor call duration
     * @param observationTimeoutMillis maximum provider observation call duration
     * @param maximumConcurrentCalls fixed zero-queue provider-call capacity
     * @param confirmationWindowMillis challenge-bound observation command lifetime
     * @param leaseSafetyMarginMillis lease headroom reserved after the command window
     * @param leaseDurationMillis database reconciliation target lease
     * @param activePollDelayMillis delay after a verified active observation
     * @param initialRetryDelayMillis first uncertainty or local-backpressure retry delay
     * @param maximumRetryDelayMillis exponential retry ceiling
     * @param maximumConsecutiveUncertainty provider-uncertainty quarantine threshold
     * @param maximumHorizonSeconds total automatic reconciliation horizon
     * @param discoveryPageSize missing-target discovery page bound
     * @param maximumActionableAgeSeconds readiness SLO for due or expired targets
     * @param maximumQuarantinedTargets accepted quarantined target count
     * @param maximumUndiscoveredSources accepted retained starts awaiting target materialization
     */
    @ConfigurationProperties(prefix = Properties.PREFIX, ignoreUnknownFields = false)
    public record Properties(
            Boolean enabled,
            String workerId,
            Integer maximumPollers,
            Long initialDelayMillis,
            Long pollIntervalMillis,
            Long drainTimeoutMillis,
            Long descriptorTimeoutMillis,
            Long observationTimeoutMillis,
            Integer maximumConcurrentCalls,
            Long confirmationWindowMillis,
            Long leaseSafetyMarginMillis,
            Long leaseDurationMillis,
            Long activePollDelayMillis,
            Long initialRetryDelayMillis,
            Long maximumRetryDelayMillis,
            Integer maximumConsecutiveUncertainty,
            Long maximumHorizonSeconds,
            Integer discoveryPageSize,
            Long maximumActionableAgeSeconds,
            Long maximumQuarantinedTargets,
            Long maximumUndiscoveredSources) {

        /** Prefix shared by Spring configuration, deployment examples, and startup tests. */
        public static final String PREFIX =
                "gateway.testing.stability-physical-attempt.observation-reconciliation";
        private static final Pattern WORKER_ID =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Applies finite defaults and rejects partial, unsafe, or unbounded enabled policy. */
        public Properties {
            enabled = Boolean.TRUE.equals(enabled);
            workerId = normalized(workerId);
            maximumPollers = defaulted(maximumPollers, 2);
            initialDelayMillis = defaulted(initialDelayMillis, 5_000L);
            pollIntervalMillis = defaulted(pollIntervalMillis, 5_000L);
            drainTimeoutMillis = defaulted(drainTimeoutMillis, 5_000L);
            descriptorTimeoutMillis = defaulted(descriptorTimeoutMillis, 5_000L);
            observationTimeoutMillis = defaulted(observationTimeoutMillis, 20_000L);
            maximumConcurrentCalls = defaulted(maximumConcurrentCalls, 4);
            confirmationWindowMillis = defaulted(confirmationWindowMillis, 30_000L);
            leaseSafetyMarginMillis = defaulted(leaseSafetyMarginMillis, 5_000L);
            leaseDurationMillis = defaulted(leaseDurationMillis, 60_000L);
            activePollDelayMillis = defaulted(activePollDelayMillis, 30_000L);
            initialRetryDelayMillis = defaulted(initialRetryDelayMillis, 1_000L);
            maximumRetryDelayMillis = defaulted(maximumRetryDelayMillis, 300_000L);
            maximumConsecutiveUncertainty = defaulted(maximumConsecutiveUncertainty, 10);
            maximumHorizonSeconds = defaulted(maximumHorizonSeconds, 86_400L);
            discoveryPageSize = defaulted(discoveryPageSize, 100);
            maximumActionableAgeSeconds = defaulted(maximumActionableAgeSeconds, 60L);
            maximumQuarantinedTargets = defaulted(maximumQuarantinedTargets, 0L);
            maximumUndiscoveredSources = defaulted(maximumUndiscoveredSources, 0L);
            try {
                TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Policy work =
                        workPolicy(leaseDurationMillis, activePollDelayMillis,
                                initialRetryDelayMillis, maximumRetryDelayMillis,
                                maximumConsecutiveUncertainty, maximumHorizonSeconds,
                                discoveryPageSize);
                TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy calls =
                        callPolicy(descriptorTimeoutMillis, observationTimeoutMillis,
                                maximumConcurrentCalls);
                TestSuiteStabilityPhysicalAttemptObservationReconciler.Policy reconciler =
                        reconcilerPolicy(confirmationWindowMillis, leaseSafetyMarginMillis);
                TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy
                        schedule = schedulePolicy(maximumPollers, initialDelayMillis,
                        pollIntervalMillis, drainTimeoutMillis);
                TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.Policy health =
                        healthPolicy(maximumActionableAgeSeconds, maximumQuarantinedTargets,
                                maximumUndiscoveredSources);
                Duration providerBudget = calls.descriptorTimeout()
                        .plus(calls.observationTimeout());
                Duration requiredLease = reconciler.confirmationWindow()
                        .plus(reconciler.leaseSafetyMargin());
                if (enabled && !WORKER_ID.matcher(workerId).matches()
                        || maximumPollers > maximumConcurrentCalls
                        || providerBudget.compareTo(reconciler.confirmationWindow()) >= 0
                        || work.leaseDuration().compareTo(requiredLease) < 0
                        || schedule.pollInterval().compareTo(
                        health.maximumActionableAge()) > 0) {
                    throw invalid();
                }
            } catch (RuntimeException invalid) {
                throw invalid();
            }
        }

        private TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Policy
                workPolicy() {
            return workPolicy(leaseDurationMillis, activePollDelayMillis,
                    initialRetryDelayMillis, maximumRetryDelayMillis,
                    maximumConsecutiveUncertainty, maximumHorizonSeconds, discoveryPageSize);
        }

        private TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy callPolicy() {
            return callPolicy(descriptorTimeoutMillis, observationTimeoutMillis,
                    maximumConcurrentCalls);
        }

        private TestSuiteStabilityPhysicalAttemptObservationReconciler.Policy
                reconcilerPolicy() {
            return reconcilerPolicy(confirmationWindowMillis, leaseSafetyMarginMillis);
        }

        private TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy
                schedulePolicy() {
            return schedulePolicy(maximumPollers, initialDelayMillis, pollIntervalMillis,
                    drainTimeoutMillis);
        }

        private TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.Policy
                healthPolicy() {
            return healthPolicy(maximumActionableAgeSeconds, maximumQuarantinedTargets,
                    maximumUndiscoveredSources);
        }

        private static TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Policy
                workPolicy(long lease, long activeDelay, long retryDelay, long maximumRetry,
                int uncertainty, long horizonSeconds, int pageSize) {
            return new TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal.Policy(
                    Duration.ofMillis(lease), Duration.ofMillis(activeDelay),
                    Duration.ofMillis(retryDelay), Duration.ofMillis(maximumRetry), uncertainty,
                    Duration.ofSeconds(horizonSeconds), pageSize);
        }

        private static TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy
                callPolicy(long descriptor, long observation, int capacity) {
            return new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                    Duration.ofMillis(descriptor), Duration.ofMillis(observation), capacity);
        }

        private static TestSuiteStabilityPhysicalAttemptObservationReconciler.Policy
                reconcilerPolicy(long confirmation, long margin) {
            return new TestSuiteStabilityPhysicalAttemptObservationReconciler.Policy(
                    Duration.ofMillis(confirmation), Duration.ofMillis(margin));
        }

        private static TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy
                schedulePolicy(int pollers, long initial, long interval, long drain) {
            return new TestSuiteStabilityPhysicalAttemptObservationReconciliationScheduler.Policy(
                    pollers, Duration.ofMillis(initial), Duration.ofMillis(interval),
                    Duration.ofMillis(drain));
        }

        private static TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.Policy
                healthPolicy(long ageSeconds, long quarantine, long undiscovered) {
            return new TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.Policy(
                    Duration.ofSeconds(ageSeconds), quarantine, undiscovered);
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "Physical-attempt observation reconciliation runtime configuration is "
                            + "invalid");
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }

        private static int defaulted(Integer value, int defaultValue) {
            return value == null ? defaultValue : value;
        }

        private static long defaulted(Long value, long defaultValue) {
            return value == null ? defaultValue : value;
        }
    }
}
