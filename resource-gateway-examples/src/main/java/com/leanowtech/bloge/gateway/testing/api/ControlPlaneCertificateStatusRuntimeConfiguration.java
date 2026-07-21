package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateStatusFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Spring composition root for signed certificate-status ingestion and request admission. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@EnableConfigurationProperties(ControlPlaneCertificateStatusRuntimeProperties.class)
@ConditionalOnProperty(
        prefix = ControlPlaneCertificateStatusRuntimeProperties.PREFIX,
        name = "enabled", havingValue = "true")
public class ControlPlaneCertificateStatusRuntimeConfiguration {

    /** Creates the profile-gated composition root. */
    public ControlPlaneCertificateStatusRuntimeConfiguration() {
    }

    /** Creates the public-key-only status publication trust boundary. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusTrustStore.class)
    ControlPlaneCertificateStatusTrustStore controlPlaneCertificateStatusTrustStore(
            ObjectMapper objectMapper,
            ControlPlaneCertificateStatusRuntimeProperties properties) {
        return ConfiguredControlPlaneCertificateStatusTrustStore.fromJson(
                objectMapper, Clock.systemUTC(), properties.trustDomain(),
                properties.acceptedPolicyFingerprints(), properties.signatureThreshold(),
                properties.authorityKeysJson());
    }

    /** Creates the database-time complete-snapshot and monotonic cursor floor. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusFloor.class)
    ControlPlaneCertificateStatusFloor controlPlaneCertificateStatusFloor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ControlPlaneCertificateStatusTrustStore trustStore,
            ControlPlaneCertificateStatusRuntimeProperties properties) {
        var floor = new DatabaseControlPlaneCertificateStatusFloor(database.jdbc(), objectMapper,
                trustStore, properties.deploymentScopeId(), properties.baselineSequence(),
                properties.baselinePublicationFingerprint(),
                ControlPlaneCertificateRotationTargets.values().stream()
                        .map(ControlPlaneCertificateStatusFloor.ExpectedTarget::new)
                        .toList(), database.transactionManager());
        floor.init();
        return floor;
    }

    /** Registers fixed-cardinality refresh, admission, freshness, and SLO metrics. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusTelemetry.class)
    ControlPlaneCertificateStatusTelemetry controlPlaneCertificateStatusTelemetry(
            MeterRegistry meterRegistry) {
        return new ControlPlaneCertificateStatusTelemetry(meterRegistry);
    }

    /** Creates the local dual-clock hard-expiry request admission cache. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusAdmission.class)
    ControlPlaneCertificateStatusAdmission controlPlaneCertificateStatusAdmission(
            ControlPlaneCertificateStatusTelemetry telemetry) {
        return new ControlPlaneCertificateStatusAdmission(
                Clock.systemUTC(), System::nanoTime, telemetry);
    }

    /** Creates the strict private-PKIX/SPKI/mTLS normalized-publication source. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusSource.class)
    ControlPlaneCertificateStatusSource controlPlaneCertificateStatusSource(
            ObjectMapper objectMapper,
            ControlPlaneHttpTransport.SecretResolver secretResolver,
            ControlPlaneCertificateStatusRuntimeProperties properties) {
        RecoveryFleetPublicationTransport transport = properties.transport().create(
                secretResolver);
        return new HttpControlPlaneCertificateStatusSource(objectMapper, Clock.systemUTC(),
                transport, properties.sourceSettings());
    }

    /** Creates the bounded durable-to-local status refresh pipeline. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusMonitor.class)
    ControlPlaneCertificateStatusMonitor controlPlaneCertificateStatusMonitor(
            ControlPlaneCertificateStatusFloor floor,
            ControlPlaneCertificateStatusSource source,
            ControlPlaneCertificateStatusAdmission admission,
            ControlPlaneCertificateStatusRuntimeProperties properties,
            ControlPlaneCertificateStatusTelemetry telemetry) {
        return new ControlPlaneCertificateStatusMonitor(floor, source, admission,
                Clock.systemUTC(), properties.maximumBatch(), telemetry);
    }

    /** Creates the local fixed-policy SLO assessor and Actuator health contributor. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusSloMonitor.class)
    ControlPlaneCertificateStatusSloMonitor controlPlaneCertificateStatusSloMonitor(
            ControlPlaneCertificateStatusMonitor monitor,
            ControlPlaneCertificateStatusAdmission admission,
            ControlPlaneCertificateStatusTelemetry telemetry,
            ControlPlaneCertificateStatusRuntimeProperties runtimeProperties) {
        return new ControlPlaneCertificateStatusSloMonitor(monitor, admission, telemetry,
                Clock.systemUTC(), runtimeProperties.slo().policy());
    }

    /** Creates the fixed-delay autonomous refresh trigger. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusScheduler.class)
    ControlPlaneCertificateStatusScheduler controlPlaneCertificateStatusScheduler(
            ControlPlaneCertificateStatusMonitor monitor,
            ControlPlaneCertificateStatusSloMonitor sloMonitor) {
        return new ControlPlaneCertificateStatusScheduler(monitor, sloMonitor);
    }

    /** Creates bounded Actuator truth for the status ingestion and admission path. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateStatusHealth.class)
    ControlPlaneCertificateStatusHealth controlPlaneCertificateStatusHealth(
            ControlPlaneCertificateStatusMonitor monitor,
            ControlPlaneCertificateStatusSource source,
            ControlPlaneCertificateStatusTrustStore trustStore,
            ControlPlaneCertificateStatusAdmission admission) {
        return new ControlPlaneCertificateStatusHealth(
                monitor, source, trustStore, admission);
    }
}
