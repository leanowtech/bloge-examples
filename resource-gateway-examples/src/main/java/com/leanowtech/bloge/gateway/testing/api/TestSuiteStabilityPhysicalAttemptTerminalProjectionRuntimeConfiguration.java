package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityAttemptCancellationJournal;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityJobRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptRegistry;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptStartJournal;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
 * Explicit Spring composition root for physical-attempt terminal projection.
 *
 * <p>The runtime is physically absent when a {@code production} profile is active and inert in
 * {@code test}/{@code staging} until explicitly enabled. Enabling it requires all three pinned
 * provider-attestation verifiers and the database queue implementation from the same isolated
 * test-runtime datasource. There is no permissive trust default.</p>
 *
 * <p>This composition owns one work journal, exact-source coordinator, zero-queue call
 * supervisor, bounded polling scheduler, fixed-cardinality telemetry, and aggregate health
 * indicator. It processes already registered terminal work. The separately gated observation-
 * reconciliation runtime discovers retained starts and atomically registers new terminal work
 * into this lane.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration
        .Properties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(
        TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration.Properties.class)
public class TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration {

    /** Creates the profile- and property-gated composition root. */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionRuntimeConfiguration() {
    }

    /** Verifies the database queue implementation required for atomic terminal projection. */
    @Bean
    ValidatedDatabaseQueue physicalAttemptTerminalProjectionDatabaseQueue(
            TestSuiteStabilityJobRepository jobs) {
        if (!(Objects.requireNonNull(jobs, "jobs")
                instanceof DatabaseTestSuiteStabilityJobRepository databaseJobs)
                || !databaseJobs.physicalAttemptFencingEnabled()) {
            throw Properties.invalid();
        }
        return new ValidatedDatabaseQueue(databaseJobs);
    }

    /** Creates the database-time physical-attempt reservation authority. */
    @Bean
    @ConditionalOnMissingBean(TestSuiteStabilityPhysicalAttemptRegistry.class)
    DatabaseTestSuiteStabilityPhysicalAttemptRegistry
            testSuiteStabilityPhysicalAttemptRegistry(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ValidatedDatabaseQueue queue) {
        return new DatabaseTestSuiteStabilityPhysicalAttemptRegistry(
                database.jdbc(), objectMapper, queue.jobs(), database.transactionManager());
    }

    /** Creates the integrity-verifying provider-start journal. */
    @Bean
    @ConditionalOnMissingBean(TestSuiteStabilityPhysicalAttemptStartJournal.class)
    DatabaseTestSuiteStabilityPhysicalAttemptStartJournal
            testSuiteStabilityPhysicalAttemptStartJournal(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ValidatedDatabaseQueue queue,
            TestSuiteStabilityPhysicalAttemptStartVerifier verifier) {
        return new DatabaseTestSuiteStabilityPhysicalAttemptStartJournal(
                database.jdbc(), objectMapper, queue.jobs(), verifier,
                database.transactionManager());
    }

    /** Creates the integrity-verifying provider-observation journal. */
    @Bean
    @ConditionalOnMissingBean(TestSuiteStabilityPhysicalAttemptObservationJournal.class)
    DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal
            testSuiteStabilityPhysicalAttemptObservationJournal(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationVerifier verifier) {
        return new DatabaseTestSuiteStabilityPhysicalAttemptObservationJournal(
                database.jdbc(), objectMapper, starts, verifier,
                database.transactionManager());
    }

    /** Creates the integrity-verifying provider-cancellation journal. */
    @Bean
    @ConditionalOnMissingBean(TestSuiteStabilityAttemptCancellationJournal.class)
    DatabaseTestSuiteStabilityAttemptCancellationJournal
            testSuiteStabilityAttemptCancellationJournal(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttemptCancellationVerifier verifier) {
        return new DatabaseTestSuiteStabilityAttemptCancellationJournal(
                database.jdbc(), objectMapper, verifier, database.transactionManager());
    }

    /** Creates database-leased terminal-projection work and retry state. */
    @Bean
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.class)
    DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
            testSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            Properties properties) {
        return new DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
                database.jdbc(), objectMapper, properties.workPolicy(),
                database.transactionManager());
    }

    /** Creates the single-transaction queue terminal projector. */
    @Bean
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.class)
    DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal
            testSuiteStabilityPhysicalAttemptTerminalProjectionJournal(
            ValidatedDatabaseQueue queue,
            TestSuiteStabilityPhysicalAttemptRegistry attempts,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationJournal observations,
            TestSuiteStabilityAttemptCancellationJournal cancellations) {
        return new DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionJournal(
                queue.jobs(), attempts, starts, observations, cancellations);
    }

    /** Creates the authoritative cancellation and signed-parent proof resolver. */
    @Bean
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.class)
    AuthoritativeTestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver
            testSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver(
            ObjectMapper objectMapper,
            TestSuiteStabilityAttemptCancellationJournal cancellations,
            TestSuiteStabilityJobRepository jobs,
            TestSuiteStabilityRunRepository parentRuns,
            TestSuiteStabilityJobParentAuthority parentAuthority) {
        return new AuthoritativeTestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver(
                objectMapper, cancellations, jobs, parentRuns, parentAuthority);
    }

    /** Creates the exact-source projection coordinator. */
    @Bean
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class)
    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
            testSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator(
            ObjectMapper objectMapper,
            TestSuiteStabilityPhysicalAttemptRegistry attempts,
            TestSuiteStabilityPhysicalAttemptStartJournal starts,
            TestSuiteStabilityPhysicalAttemptObservationJournal observations,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver proofs,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal projections) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator(
                objectMapper, attempts, starts, observations, proofs, projections);
    }

    /** Creates the fixed-capacity zero-queue coordinator-call boundary. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.class)
    TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
            testSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor(
            Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor(
                properties.callPolicy());
    }

    /** Creates the bounded one-shot durable worker. */
    @Bean
    @ConditionalOnMissingBean(TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.class)
    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker
            testSuiteStabilityPhysicalAttemptTerminalProjectionWorker(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal works,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor,
            TestSuiteStabilityQueuePolicy queuePolicy,
            Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker(
                works, coordinator, supervisor, queuePolicy, properties.workerPolicy(),
                properties.workerId());
    }

    /** Registers only fixed worker-outcome, local-disposition, and lifecycle metrics. */
    @Bean
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry.class)
    TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry
            testSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Starts one bounded local scheduler and owns its drain lifecycle. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.class)
    TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler
            testSuiteStabilityPhysicalAttemptTerminalProjectionScheduler(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry telemetry,
            Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler(
                worker, properties.schedulePolicy(), telemetry);
    }

    /** Exposes aggregate-only lifecycle, capacity, and database-clock backlog readiness. */
    @Bean
    @ConditionalOnMissingBean(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.class)
    TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth
            testSuiteStabilityPhysicalAttemptTerminalProjectionHealth(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal works,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler scheduler,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor,
            Properties properties) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth(
                works, scheduler, supervisor, properties.healthPolicy());
    }

    private record ValidatedDatabaseQueue(DatabaseTestSuiteStabilityJobRepository jobs) {
        private ValidatedDatabaseQueue {
            jobs = Objects.requireNonNull(jobs, "jobs");
        }
    }

    /**
     * Strict local terminal-projection runtime configuration.
     *
     * @param enabled explicit test/staging activation switch
     * @param workerId stable pre-authenticated replica/worker identity
     * @param maximumPollers fixed local poll lanes
     * @param initialDelayMillis delay before first local poll
     * @param pollIntervalMillis fixed delay after each local poll
     * @param drainTimeoutMillis bounded scheduler shutdown wait
     * @param maximumProjectionTimeoutMillis maximum one coordinator call duration
     * @param maximumConcurrentCalls fixed zero-queue coordinator-call capacity
     * @param completionReserveMillis lease time reserved for fenced completion
     * @param workLeaseDurationMillis database work lease duration
     * @param initialProofPendingDelayMillis initial authoritative-proof retry delay
     * @param initialUnavailableDelayMillis initial infrastructure retry delay
     * @param maximumRetryDelayMillis exponential retry ceiling
     * @param claimInspectionLimit maximum raced work candidates inspected per claim
     * @param maximumActionableAgeSeconds readiness SLO for due or expired work
     * @param maximumQuarantinedWork accepted quarantined-work count
     */
    @ConfigurationProperties(prefix = Properties.PREFIX, ignoreUnknownFields = false)
    public record Properties(
            Boolean enabled,
            String workerId,
            Integer maximumPollers,
            Long initialDelayMillis,
            Long pollIntervalMillis,
            Long drainTimeoutMillis,
            Long maximumProjectionTimeoutMillis,
            Integer maximumConcurrentCalls,
            Long completionReserveMillis,
            Long workLeaseDurationMillis,
            Long initialProofPendingDelayMillis,
            Long initialUnavailableDelayMillis,
            Long maximumRetryDelayMillis,
            Integer claimInspectionLimit,
            Long maximumActionableAgeSeconds,
            Long maximumQuarantinedWork) {

        /** Prefix shared by Spring configuration, deployment examples, and startup tests. */
        public static final String PREFIX =
                "gateway.testing.stability-physical-attempt.terminal-projection";
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
            maximumProjectionTimeoutMillis = defaulted(
                    maximumProjectionTimeoutMillis, 20_000L);
            maximumConcurrentCalls = defaulted(maximumConcurrentCalls, 4);
            completionReserveMillis = defaulted(completionReserveMillis, 5_000L);
            workLeaseDurationMillis = defaulted(workLeaseDurationMillis, 30_000L);
            initialProofPendingDelayMillis = defaulted(
                    initialProofPendingDelayMillis, 1_000L);
            initialUnavailableDelayMillis = defaulted(
                    initialUnavailableDelayMillis, 1_000L);
            maximumRetryDelayMillis = defaulted(maximumRetryDelayMillis, 300_000L);
            claimInspectionLimit = defaulted(claimInspectionLimit, 32);
            maximumActionableAgeSeconds = defaulted(maximumActionableAgeSeconds, 60L);
            maximumQuarantinedWork = defaulted(maximumQuarantinedWork, 0L);
            try {
                var work = new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .Policy(Duration.ofMillis(workLeaseDurationMillis),
                        Duration.ofMillis(initialProofPendingDelayMillis),
                        Duration.ofMillis(initialUnavailableDelayMillis),
                        Duration.ofMillis(maximumRetryDelayMillis), claimInspectionLimit);
                var calls = new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                        .Policy(Duration.ofMillis(maximumProjectionTimeoutMillis),
                        maximumConcurrentCalls);
                var worker = new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Policy(
                        Duration.ofMillis(completionReserveMillis));
                var schedule = new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler
                        .Policy(maximumPollers, Duration.ofMillis(initialDelayMillis),
                        Duration.ofMillis(pollIntervalMillis),
                        Duration.ofMillis(drainTimeoutMillis));
                var health = new TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.Policy(
                        Duration.ofSeconds(maximumActionableAgeSeconds),
                        maximumQuarantinedWork);
                if (enabled && !WORKER_ID.matcher(workerId).matches()
                        || maximumPollers > maximumConcurrentCalls
                        || calls.maximumProjectionTimeout().plus(worker.completionReserve())
                        .compareTo(work.leaseDuration()) >= 0
                        || schedule.pollInterval().compareTo(
                        health.maximumActionableAge()) > 0) {
                    throw invalid();
                }
            } catch (RuntimeException invalid) {
                throw invalid();
            }
        }

        private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Policy
                workPolicy() {
            return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Policy(
                    Duration.ofMillis(workLeaseDurationMillis),
                    Duration.ofMillis(initialProofPendingDelayMillis),
                    Duration.ofMillis(initialUnavailableDelayMillis),
                    Duration.ofMillis(maximumRetryDelayMillis), claimInspectionLimit);
        }

        private TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy
                callPolicy() {
            return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                    Duration.ofMillis(maximumProjectionTimeoutMillis), maximumConcurrentCalls);
        }

        private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Policy workerPolicy() {
            return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Policy(
                    Duration.ofMillis(completionReserveMillis));
        }

        private TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Policy
                schedulePolicy() {
            return new TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.Policy(
                    maximumPollers, Duration.ofMillis(initialDelayMillis),
                    Duration.ofMillis(pollIntervalMillis), Duration.ofMillis(drainTimeoutMillis));
        }

        private TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.Policy healthPolicy() {
            return new TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.Policy(
                    Duration.ofSeconds(maximumActionableAgeSeconds), maximumQuarantinedWork);
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "Physical-attempt terminal projection runtime configuration is invalid");
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
