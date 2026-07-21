package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.AcquisitionCommand;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.FleetManifest;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Duration;
import java.util.Objects;

/**
 * Explicit test/staging Spring composition root for durable bootstrap-root recovery fleets.
 *
 * <p>The runtime consumes exactly one locally available
 * {@link ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory} and owns the path from that
 * snapshot to a database-clock coordinator, bounded worker, fixed-delay scheduler, and aggregate
 * health indicator. The companion dynamic configuration can construct that inventory from a
 * public-only witnessed HTTPS source; otherwise the embedder owns discovery, authorization,
 * signature verification, and atomic publication. Neither configuration accepts signer private
 * keys or provider credentials.</p>
 *
 * <p>The runtime is physically absent whenever a {@code production} profile is active and remains
 * disabled by default in {@code test}/{@code staging}. Fleet mode and the legacy single-root lane
 * are mutually exclusive because running both would duplicate polling without adding a new write
 * fence. Spring dependency destruction closes the scheduler before the worker; the caller-owned
 * lane services, authority resolvers, isolated database, and any caller-supplied inventory remain
 * outside their ownership boundary. The companion configuration owns only the dynamic authority
 * that it creates.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration
        .FleetProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(
        {ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration
                .FleetProperties.class,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration
                        .RecoveryFleetSloProperties.class,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                        .DynamicInventoryProperties.class})
public class ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration {

    /** Creates the profile-gated durable fleet composition root. */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration() {
    }

    /**
     * Rejects local topology and mode conflicts before any floor table or remote source is used.
     *
     * @param environment active profile and mutually exclusive single-lane configuration
     * @param properties strict durable fleet runtime policy
     * @param sloProperties strict local progress SLO policy
     * @param dynamicInventory strict dynamic-source policy and staging requirement
     * @return immutable token required by dynamic inventory and stateful runtime beans
     */
    @Bean
    ValidatedFleetConfiguration externalSequenceAnchorBootstrapRootRecoveryFleetConfiguration(
            Environment environment,
            FleetProperties properties,
            RecoveryFleetSloProperties sloProperties,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .DynamicInventoryProperties dynamicInventory) {
        try {
            Boolean singleLaneEnabled = environment.getProperty(
                    ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfiguration
                            .RecoveryProperties.PREFIX + ".enabled",
                    Boolean.class, false);
            if (Boolean.TRUE.equals(singleLaneEnabled)) {
                throw FleetProperties.invalid();
            }
            if (environment.acceptsProfiles(Profiles.of("staging"))
                    && !dynamicInventory.required()) {
                throw FleetProperties.invalid();
            }
            if (environment.acceptsProfiles(Profiles.of("staging"))
                    && !dynamicInventory.trustRoots().required()) {
                throw FleetProperties.invalid();
            }
            if (environment.acceptsProfiles(Profiles.of("staging"))
                    && !sloProperties.enabled()) {
                throw FleetProperties.invalid();
            }
            if (sloProperties.enabled()
                    && (sloProperties.startupGrace().compareTo(
                    properties.initialDelay().plus(properties.pollInterval())) < 0
                    || sloProperties.maximumPollSuccessAge().compareTo(
                    properties.pollInterval().multipliedBy(2L)) < 0)) {
                throw FleetProperties.invalid();
            }
            return new ValidatedFleetConfiguration(
                    properties.fleetId(), properties.partitionCount());
        } catch (RuntimeException invalid) {
            throw FleetProperties.invalid();
        }
    }

    /**
     * Freezes startup invariants before any coordinator table or background scheduler is created.
     *
     * @param inventory caller-owned, already-authorized bounded local inventory
     * @param properties strict durable fleet runtime policy
     * @param dynamicInventory strict dynamic-source policy and staging requirement
     * @param configured successful stateless fleet configuration preflight
     * @return validated startup token consumed by every stateful bean
     */
    @Bean
    ValidatedFleetRuntime externalSequenceAnchorBootstrapRootRecoveryFleetPreflight(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            FleetProperties properties,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .DynamicInventoryProperties dynamicInventory,
            ValidatedFleetConfiguration configured) {
        try {
            Objects.requireNonNull(configured, "configured");
            Snapshot snapshot = Objects.requireNonNull(
                    inventory.snapshot(), "fleet inventory snapshot");
            if (inventory instanceof
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority) {
                var observed = Objects.requireNonNull(
                        authority.observation(), "fleet inventory authority observation");
                var binding = Objects.requireNonNull(
                        authority.verifiedBinding(), "fleet inventory authority binding");
                var descriptor = Objects.requireNonNull(
                        authority.descriptor(), "fleet inventory authority descriptor");
                if (!observed.available()
                        || observed.generation() != snapshot.generation()
                        || observed.laneCount() != snapshot.lanes().size()
                        || !properties.fleetId().equals(binding.fleetId())
                        || properties.partitionCount() != binding.partitionCount()) {
                    throw FleetProperties.invalid();
                }
                if (dynamicInventory.required()
                        && !dynamicAuthority(observed, descriptor)) {
                    throw FleetProperties.invalid();
                }
                if (dynamicInventory.trustRoots().required()
                        && !managedTrustRoots(descriptor)) {
                    throw FleetProperties.invalid();
                }
            } else if (dynamicInventory.required()) {
                throw FleetProperties.invalid();
            }
            FleetManifest manifest = FleetManifest.from(
                    properties.fleetId(), snapshot, properties.partitionCount());
            return new ValidatedFleetRuntime(manifest);
        } catch (RuntimeException invalid) {
            throw FleetProperties.invalid();
        }
    }

    private static boolean managedTrustRoots(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
                    descriptor) {
        var properties = descriptor.properties();
        return Boolean.TRUE.equals(properties.get("managedTrustRootRefresh"))
                && Boolean.TRUE.equals(properties.get("managedTrustRootAvailable"))
                && "HEALTHY".equals(properties.get("managedTrustRootStatus"))
                && properties.get("managedTrustRootSequence") instanceof Number sequence
                && sequence.longValue() > 0L
                && Boolean.TRUE.equals(properties.get("atomicDualTrustRootPublication"))
                && Boolean.TRUE.equals(properties.get("durableTrustRootFloor"));
    }

    private static boolean dynamicAuthority(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation
                    observation,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
                    descriptor) {
        var properties = descriptor.properties();
        return observation.sourceType().equals(
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .SOURCE_TYPE)
                && descriptor.available() == observation.available()
                && descriptor.status().equals(observation.status())
                && descriptor.generation() == observation.generation()
                && descriptor.laneCount() == observation.laneCount()
                && Boolean.TRUE.equals(properties.get("automaticRefresh"))
                && Boolean.TRUE.equals(properties.get("signedRevocation"))
                && Boolean.TRUE.equals(properties.get("durableGenerationFloor"))
                && Boolean.TRUE.equals(properties.get("witnessedPublications"));
    }

    /** Creates the shared database-clock partition coordinator unless supplied by the embedder. */
    @Bean
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator.class)
    DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator
            externalSequenceAnchorBootstrapRootRecoveryFleetCoordinator(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ValidatedFleetRuntime validated) {
        Objects.requireNonNull(validated, "validated");
        return new DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator(
                database.jdbc(), objectMapper, database.transactionManager());
    }

    /** Creates the bounded fixed-partition worker over the caller-owned inventory. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker
            externalSequenceAnchorBootstrapRootRecoveryFleetWorker(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCoordinator coordinator,
            FleetProperties properties,
            ValidatedFleetRuntime validated) {
        Objects.requireNonNull(validated, "validated");
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker(
                inventory, properties.workerId(), properties.workerPolicy(), coordinator,
                properties.fleetId(), properties.partitionCount());
    }

    /** Starts one local fixed-delay scheduler for the durable fleet worker. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler
            externalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            FleetProperties properties) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
                worker, properties.schedulePolicy());
    }

    /** Exposes aggregate-only readiness for the configured durable fleet runtime. */
    @Bean
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth
            externalSequenceAnchorBootstrapRootRecoveryFleetHealth(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler scheduler) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetHealth(worker, scheduler);
    }

    /** Registers identity-free fixed-cardinality recovery-fleet SLO gauges. */
    @Bean
    @ConditionalOnProperty(prefix = RecoveryFleetSloProperties.PREFIX,
            name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry
            externalSequenceAnchorBootstrapRootRecoveryFleetTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Exposes versioned local progress SLO truth separately from instantaneous readiness. */
    @Bean
    @ConditionalOnProperty(prefix = RecoveryFleetSloProperties.PREFIX,
            name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor
            externalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler scheduler,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry telemetry,
            RecoveryFleetSloProperties properties,
            ValidatedFleetRuntime validated) {
        Objects.requireNonNull(validated, "validated");
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor(
                inventory, worker, scheduler, telemetry, properties.policy());
    }

    /** Exposes signed-inventory validity separately from fleet execution readiness. */
    @Bean
    @ConditionalOnBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.class)
    @ConditionalOnMissingBean(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.class)
    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth
            externalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority,
            ValidatedFleetRuntime validated) {
        Objects.requireNonNull(validated, "validated");
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(authority);
    }

    private record ValidatedFleetRuntime(FleetManifest startupManifest) {

        private ValidatedFleetRuntime {
            startupManifest = Objects.requireNonNull(startupManifest, "startupManifest");
        }
    }

    record ValidatedFleetConfiguration(String fleetId, int partitionCount) {

        ValidatedFleetConfiguration {
            fleetId = Objects.requireNonNull(fleetId, "fleetId");
            if (fleetId.isBlank() || partitionCount < 1) {
                throw FleetProperties.invalid();
            }
        }
    }

    /**
     * Strict durable recovery-fleet runtime policy.
     *
     * @param enabled explicit test/staging activation switch
     * @param fleetId stable deployment-wide fleet and topology identity
     * @param workerId stable pre-authenticated replica worker identity
     * @param partitionCount immutable fixed partition count for this fleet identity
     * @param leaseDurationSeconds database-clock partition lease and heartbeat duration
     * @param maximumLanesPerCycle bounded number of lanes visited by one acquired partition cycle
     * @param initialDelayMillis delay before the first background cycle
     * @param pollIntervalMillis fixed delay after each completed background cycle
     * @param maximumCycleDurationMillis readiness budget for one bounded cycle
     * @param drainTimeoutMillis bounded scheduler-thread shutdown wait
     */
    @ConfigurationProperties(prefix = FleetProperties.PREFIX, ignoreUnknownFields = false)
    public record FleetProperties(
            Boolean enabled,
            String fleetId,
            String workerId,
            Integer partitionCount,
            Long leaseDurationSeconds,
            Integer maximumLanesPerCycle,
            Long initialDelayMillis,
            Long pollIntervalMillis,
            Long maximumCycleDurationMillis,
            Long drainTimeoutMillis) {

        /** Prefix shared by profile configuration, startup tests, and operator documentation. */
        public static final String PREFIX =
                "gateway.testing.external-sequence-anchor.bootstrap-root-recovery-fleet";

        /** Applies finite defaults and rejects every enabled partial or unsafe policy. */
        public FleetProperties {
            enabled = Boolean.TRUE.equals(enabled);
            fleetId = normalized(fleetId);
            workerId = normalized(workerId);
            partitionCount = partitionCount == null ? 8 : partitionCount;
            leaseDurationSeconds = defaulted(leaseDurationSeconds, 30L);
            maximumLanesPerCycle = maximumLanesPerCycle == null
                    ? 16 : maximumLanesPerCycle;
            initialDelayMillis = defaulted(initialDelayMillis, 5_000L);
            pollIntervalMillis = defaulted(pollIntervalMillis, 5_000L);
            maximumCycleDurationMillis = defaulted(
                    maximumCycleDurationMillis, 600_000L);
            drainTimeoutMillis = defaulted(drainTimeoutMillis, 5_000L);
            try {
                var manifest = new FleetManifest(FleetManifest.SCHEMA_VERSION,
                        enabled ? fleetId : "disabled-fleet", 1L,
                        "sha256:" + "0".repeat(64), partitionCount);
                var workerPolicy = new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker
                        .Policy(leaseDurationSeconds, maximumLanesPerCycle);
                new AcquisitionCommand(AcquisitionCommand.SCHEMA_VERSION, manifest,
                        enabled ? workerId : "disabled-worker", "0".repeat(32),
                        workerPolicy.leaseDurationSeconds());
                new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.SchedulePolicy(
                        Duration.ofMillis(initialDelayMillis),
                        Duration.ofMillis(pollIntervalMillis),
                        Duration.ofMillis(maximumCycleDurationMillis),
                        Duration.ofMillis(drainTimeoutMillis));
                if (enabled && (fleetId.isEmpty() || workerId.isEmpty())) {
                    throw invalid();
                }
            } catch (RuntimeException invalid) {
                throw invalid();
            }
        }

        private ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy workerPolicy() {
            return new ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.Policy(
                    leaseDurationSeconds, maximumLanesPerCycle);
        }

        private ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.SchedulePolicy
                schedulePolicy() {
            return new ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.SchedulePolicy(
                    Duration.ofMillis(initialDelayMillis),
                    Duration.ofMillis(pollIntervalMillis),
                    Duration.ofMillis(maximumCycleDurationMillis),
                    Duration.ofMillis(drainTimeoutMillis));
        }

        private Duration initialDelay() {
            return Duration.ofMillis(initialDelayMillis);
        }

        private Duration pollInterval() {
            return Duration.ofMillis(pollIntervalMillis);
        }

        private static long defaulted(Long value, long fallback) {
            return value == null ? fallback : value;
        }

        private static String normalized(String value) {
            return Objects.requireNonNullElse(value, "").trim();
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "Bootstrap-root recovery fleet runtime configuration is invalid");
        }
    }

    /**
     * Strict process-local recovery-fleet progress SLO policy.
     *
     * @param enabled local assessment and metric activation; mandatory in staging fleet mode
     * @param observationIntervalMillis fixed local assessment refresh interval
     * @param startupGraceMillis grace before the first missing success violates SLO
     * @param maximumPollSuccessAgeMillis oldest acceptable latest successful poll
     * @param minimumSamples minimum denominator before cumulative ratios are enforced
     * @param maximumPollFailureBasisPoints maximum inclusive scheduler failure ratio
     * @param maximumCycleFailureBasisPoints maximum inclusive worker-cycle failure ratio
     * @param maximumLaneFailureBasisPoints maximum inclusive lane failure ratio
     */
    @ConfigurationProperties(prefix = RecoveryFleetSloProperties.PREFIX,
            ignoreUnknownFields = false)
    public record RecoveryFleetSloProperties(
            Boolean enabled,
            Long observationIntervalMillis,
            Long startupGraceMillis,
            Long maximumPollSuccessAgeMillis,
            Integer minimumSamples,
            Integer maximumPollFailureBasisPoints,
            Integer maximumCycleFailureBasisPoints,
            Integer maximumLaneFailureBasisPoints) {

        /** Prefix shared by profile configuration, scheduling, tests, and operations docs. */
        public static final String PREFIX = "gateway.testing.external-sequence-anchor."
                + "bootstrap-root-recovery-fleet-slo";

        /** Applies conservative finite defaults and validates the complete policy eagerly. */
        public RecoveryFleetSloProperties {
            enabled = enabled == null || enabled;
            observationIntervalMillis = defaulted(observationIntervalMillis, 30_000L);
            startupGraceMillis = defaulted(startupGraceMillis, 30_000L);
            maximumPollSuccessAgeMillis = defaulted(
                    maximumPollSuccessAgeMillis, 30_000L);
            minimumSamples = minimumSamples == null ? 20 : minimumSamples;
            maximumPollFailureBasisPoints = maximumPollFailureBasisPoints == null
                    ? 500 : maximumPollFailureBasisPoints;
            maximumCycleFailureBasisPoints = maximumCycleFailureBasisPoints == null
                    ? 500 : maximumCycleFailureBasisPoints;
            maximumLaneFailureBasisPoints = maximumLaneFailureBasisPoints == null
                    ? 1_000 : maximumLaneFailureBasisPoints;
            try {
                if (observationIntervalMillis < 1_000L
                        || observationIntervalMillis > Duration.ofHours(1).toMillis()) {
                    throw FleetProperties.invalid();
                }
                new ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Policy(
                        Duration.ofMillis(startupGraceMillis),
                        Duration.ofMillis(maximumPollSuccessAgeMillis), minimumSamples,
                        maximumPollFailureBasisPoints, maximumCycleFailureBasisPoints,
                        maximumLaneFailureBasisPoints);
            } catch (RuntimeException invalid) {
                throw FleetProperties.invalid();
            }
        }

        private Duration startupGrace() {
            return Duration.ofMillis(startupGraceMillis);
        }

        private Duration maximumPollSuccessAge() {
            return Duration.ofMillis(maximumPollSuccessAgeMillis);
        }

        private ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Policy policy() {
            return new ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.Policy(
                    startupGrace(), maximumPollSuccessAge(), minimumSamples,
                    maximumPollFailureBasisPoints, maximumCycleFailureBasisPoints,
                    maximumLaneFailureBasisPoints);
        }

        private static long defaulted(Long value, long fallback) {
            return value == null ? fallback : value;
        }
    }
}
