package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateRotationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateRotationConvergenceRepository;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Spring composition root for the profile-gated signed certificate rotation runtime. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@EnableConfigurationProperties({ControlPlaneCertificateRotationRuntimeProperties.class,
        ControlPlaneCertificateRotationConvergenceProperties.class})
public class ControlPlaneCertificateRotationRuntimeConfiguration {

    /** Creates the profile-gated composition root. */
    public ControlPlaneCertificateRotationRuntimeConfiguration() {
    }

    /** Creates the canonical path- and credential-free settings fingerprinter. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateSettingsFingerprint.class)
    ControlPlaneCertificateSettingsFingerprint controlPlaneCertificateSettingsFingerprint(
            ObjectMapper objectMapper) {
        return new ControlPlaneCertificateSettingsFingerprint(objectMapper);
    }

    /** Creates a public-key-only external authorization trust store. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationTrustStore.class)
    ControlPlaneCertificateRotationTrustStore controlPlaneCertificateRotationTrustStore(
            ObjectMapper objectMapper,
            ControlPlaneCertificateRotationRuntimeProperties properties) {
        if (!properties.enabled()) {
            return ControlPlaneCertificateRotationTrustStore.unavailable();
        }
        return ConfiguredControlPlaneCertificateRotationTrustStore.fromJson(
                objectMapper, Clock.systemUTC(), properties.trustDomain(),
                properties.acceptedPolicyFingerprints(), properties.signatureThreshold(),
                properties.authorityKeysJson());
    }

    /** Creates the strict deployment-owned candidate catalog. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationMaterialSource.class)
    ControlPlaneCertificateRotationMaterialSource controlPlaneCertificateRotationMaterialSource(
            ObjectMapper objectMapper,
            ControlPlaneCertificateSettingsFingerprint fingerprinter,
            ControlPlaneCertificateRotationRuntimeProperties properties) {
        if (!properties.enabled()) {
            return (targetId, generation, materialId) -> {
                throw new IllegalStateException(
                        "Control-plane certificate rotation material is unavailable");
            };
        }
        return ConfiguredControlPlaneCertificateRotationMaterialSource.fromJson(
                objectMapper, fingerprinter, properties.materialCatalogJson());
    }

    /** Creates database-clock floors in the isolated testing control-plane database. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationFloorFactory.class)
    ControlPlaneCertificateRotationFloorFactory controlPlaneCertificateRotationFloorFactory(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ObjectProvider<ControlPlaneCertificateRotationConvergenceMonitor> monitors) {
        return (deploymentScopeId, initialTargets) -> {
            ControlPlaneCertificateRotationConvergenceMonitor monitor = monitors.getIfAvailable();
            var floor = new DatabaseControlPlaneCertificateRotationFloor(database.jdbc(),
                    objectMapper, deploymentScopeId, initialTargets,
                    database.transactionManager(), monitor == null
                    ? ControlPlaneCertificateRotationActivationAuthority.localOnly() : monitor);
            floor.init();
            return floor;
        };
    }

    /**
     * Creates the database-clock heartbeat monitor only for an explicitly enabled fleet policy.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationConvergenceMonitor.class)
    @ConditionalOnProperty(
            prefix = ControlPlaneCertificateRotationConvergenceProperties.PREFIX,
            name = "enabled", havingValue = "true")
    ControlPlaneCertificateRotationConvergenceMonitor
            controlPlaneCertificateRotationConvergenceMonitor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ControlPlaneCertificateRotationRuntimeProperties rotationProperties,
            ControlPlaneCertificateRotationConvergenceProperties convergenceProperties) {
        if (!rotationProperties.enabled()
                || !rotationProperties.deploymentScopeId().equals(
                convergenceProperties.policy(rotationProperties.deploymentScopeId())
                        .deploymentScopeId())) {
            throw new IllegalStateException(
                    "Certificate rotation convergence requires the rotation runtime");
        }
        ControlPlaneCertificateRotationFleetPolicy policy =
                convergenceProperties.policy(rotationProperties.deploymentScopeId());
        var repository = new DatabaseControlPlaneCertificateRotationConvergenceRepository(
                database.jdbc(), objectMapper, policy, database.transactionManager());
        repository.init();
        return new ControlPlaneCertificateRotationConvergenceMonitor(
                repository, policy, objectMapper);
    }

    /** Creates the single runtime used by every control-plane transport composition root. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationRuntime.class)
    ControlPlaneCertificateRotationRuntime controlPlaneCertificateRotationRuntime(
            ObjectMapper objectMapper,
            ControlPlaneCertificateRotationRuntimeProperties properties,
            ControlPlaneCertificateRotationTrustStore trustStore,
            ControlPlaneCertificateRotationMaterialSource materialSource,
            ControlPlaneHttpTransport.SecretResolver secretResolver,
            ControlPlaneCertificateSettingsFingerprint fingerprinter,
            ControlPlaneCertificateRotationFloorFactory floorFactory,
            ObjectProvider<ControlPlaneCertificateRotationConvergenceMonitor> monitors,
            ControlPlaneCertificateRotationConvergenceProperties convergenceProperties) {
        ControlPlaneCertificateRotationConvergenceMonitor monitor = monitors.getIfAvailable();
        if (convergenceProperties.required() && monitor == null) {
            throw new IllegalStateException(
                    "Required certificate rotation convergence monitor is unavailable");
        }
        return new ControlPlaneCertificateRotationRuntime(properties,
                properties.initialTargets(objectMapper), trustStore, materialSource,
                secretResolver, fingerprinter, floorFactory, Clock.systemUTC(), monitor);
    }

    /** Exposes bounded durable local readiness without claiming fleet convergence. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationHealth.class)
    ControlPlaneCertificateRotationHealth controlPlaneCertificateRotationHealth(
            ControlPlaneCertificateRotationRuntime runtime) {
        return new ControlPlaneCertificateRotationHealth(runtime);
    }
}
