package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.MirrorStatefulRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorSessionStateStore;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCapacityPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCapacityTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCommandAdmission;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStateDataPlane;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStatePayloadProtector;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/**
 * Physically isolated composition root for encrypted stateful mirror sessions.
 *
 * <p>The root requires the parent mirror switch, a dedicated stateful switch, and a non-production
 * profile. It owns a separate connection pool and key ring; none of those beans can replace the
 * Resource Gateway control-plane data source. The baseline resolver remains fail closed until the
 * governed Session-State resolver stage is assembled.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = {"enabled", "stateful.enabled"},
        havingValue = "true")
public class MirrorStatefulRuntimeConfiguration {

    /**
     * Creates the separately pooled state data plane and rejects an exact control-DB alias.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public MirrorStateDataPlane mirrorStateDataPlane(
            @Value("${gateway.testing.mirror.stateful.datasource.url}")
            String stateUrl,
            @Value("${gateway.testing.mirror.stateful.datasource.username:}")
            String username,
            @Value("${gateway.testing.mirror.stateful.datasource.password:}")
            String password,
            @Value("${gateway.testing.mirror.stateful.datasource.maximum-pool-size:4}")
            int maximumPoolSize,
            @Value("${spring.datasource.url:}") String controlUrl) {
        if (!stateUrl.isBlank() && stateUrl.trim().equals(controlUrl.trim())) {
            throw new IllegalArgumentException(
                    "Mirror state data plane must not reuse the control database URL");
        }
        return new MirrorStateDataPlane(
                stateUrl, username, password, maximumPoolSize);
    }

    /** Creates the explicit active/decrypt-only AES-256-GCM key ring. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorStatePayloadProtector mirrorStatePayloadProtector(
            @Value("${gateway.testing.mirror.stateful.encryption.active-key-id}")
            String activeKeyId,
            @Value("${gateway.testing.mirror.stateful.encryption.key-ring}")
            String keyRing) {
        return MirrorStatePayloadProtector.fromConfiguration(
                activeKeyId, keyRing);
    }

    /** Creates validated deployment-wide and per-scope state-plane hard limits. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionCapacityPolicy mirrorSessionCapacityPolicy(
            @Value("${gateway.testing.mirror.stateful.capacity.maximum-active-sessions:1000}")
            long maximumActiveSessions,
            @Value("${gateway.testing.mirror.stateful.capacity.maximum-scope-active-sessions:100}")
            long maximumScopeActiveSessions,
            @Value("${gateway.testing.mirror.stateful.capacity.maximum-retained-payload-bytes:4294967296}")
            long maximumRetainedPayloadBytes,
            @Value("${gateway.testing.mirror.stateful.capacity.maximum-scope-retained-payload-bytes:536870912}")
            long maximumScopeRetainedPayloadBytes) {
        return new MirrorSessionCapacityPolicy(
                maximumActiveSessions,
                maximumScopeActiveSessions,
                maximumRetainedPayloadBytes,
                maximumScopeRetainedPayloadBytes);
    }

    /** Registers fixed-cardinality session admission and capacity telemetry. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionCapacityTelemetry mirrorSessionCapacityTelemetry(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new MirrorSessionCapacityTelemetry(
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Creates the durable full-scope lease-fenced encrypted state store. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionStateStore mirrorSessionStateStore(
            MirrorStateDataPlane dataPlane,
            ObjectMapper mapper,
            MirrorStatePayloadProtector protector,
            MirrorSessionCapacityPolicy capacityPolicy,
            MirrorSessionCapacityTelemetry capacityTelemetry) {
        return new DatabaseMirrorSessionStateStore(
                dataPlane.jdbc(), mapper, protector,
                dataPlane.transactionManager(),
                capacityPolicy, capacityTelemetry);
    }

    /**
     * Keeps copy-on-write baseline resolution fail closed until RG-MIR-STATE-008 is assembled.
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorStateBaselineResolver mirrorStateBaselineResolver() {
        return MirrorStateBaselineResolver.none();
    }

    /** Creates the fair fail-fast replica command backpressure boundary. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionCommandAdmission mirrorSessionCommandAdmission(
            MirrorSessionCapacityTelemetry capacityTelemetry,
            @Value("${gateway.testing.mirror.stateful.capacity.maximum-concurrent-commands:32}")
            int maximumConcurrentCommands) {
        return new MirrorSessionCommandAdmission(
                maximumConcurrentCommands, capacityTelemetry);
    }

    /** Creates the authenticated session application boundary. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionIntegrationService mirrorSessionIntegrationService(
            ObjectMapper mapper,
            MirrorSessionStateStore store,
            MirrorStateBaselineResolver baselineResolver,
            MirrorSessionCommandAdmission commandAdmission,
            MirrorSessionCapacityTelemetry capacityTelemetry,
            ObjectProvider<MirrorSessionCheckpointIntegrityService>
                    checkpointIntegrities,
            VisualEvidenceSigner evidenceSigner,
            @Value("${gateway.testing.mirror.stateful.instance-id:}")
            String instanceId,
            @Value("${gateway.testing.mirror.stateful.lease-duration-seconds:30}")
            long leaseDurationSeconds) {
        MirrorSessionCheckpointIntegrityService checkpointIntegrity =
                checkpointIntegrities.getIfAvailable(
                        () -> new MirrorSessionCheckpointIntegrityService(
                                mapper, evidenceSigner, Clock.systemUTC()));
        return new MirrorSessionIntegrationService(
                mapper, store, baselineResolver, Clock.systemUTC(),
                instanceId, leaseDurationSeconds,
                commandAdmission, capacityTelemetry,
                checkpointIntegrity);
    }

    /** Publishes aggregate-only state-plane capacity and connectivity health. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionCapacityHealth mirrorSessionCapacityHealth(
            MirrorSessionStateStore store) {
        return new MirrorSessionCapacityHealth(store);
    }

    /** Creates the bounded cross-replica-safe expiry trigger. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionExpiryScheduler mirrorSessionExpiryScheduler(
            MirrorSessionStateStore store,
            MirrorSessionCapacityTelemetry capacityTelemetry,
            @Value("${gateway.testing.mirror.stateful.expiry.batch-size:100}")
            int batchSize,
            @Value("${gateway.testing.mirror.stateful.expiry.sweep-interval-millis:30000}")
            long sweepIntervalMillis) {
        return new MirrorSessionExpiryScheduler(
                store, capacityTelemetry, batchSize,
                sweepIntervalMillis);
    }

    /** Creates the bounded cross-replica-safe stale write-intent reconciler. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionWriteAttemptReconciliationScheduler
    mirrorSessionWriteAttemptReconciliationScheduler(
            MirrorSessionStateStore store,
            MirrorSessionCapacityTelemetry capacityTelemetry,
            @Value("${gateway.testing.mirror.stateful.write-attempt-reconciliation.batch-size:100}")
            int batchSize,
            @Value("${gateway.testing.mirror.stateful.write-attempt-reconciliation.sweep-interval-millis:5000}")
            long sweepIntervalMillis) {
        return new MirrorSessionWriteAttemptReconciliationScheduler(
                store, capacityTelemetry, batchSize,
                sweepIntervalMillis);
    }

    /** Publishes route assembly and dynamic encrypted-store readiness independently. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorStatefulRuntimeAvailability
    mirrorStatefulRuntimeAvailability(
            MirrorSessionStateStore store,
            VisualEvidenceSigner evidenceSigner) {
        return new MirrorStatefulRuntimeAvailability(
                true, store::ready, evidenceSigner::available,
                store::writeAttemptReconciliationReady);
    }
}
