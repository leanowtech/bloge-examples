package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateRotationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Spring composition root for the profile-gated signed certificate rotation runtime. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@EnableConfigurationProperties(ControlPlaneCertificateRotationRuntimeProperties.class)
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
            ObjectMapper objectMapper) {
        return (deploymentScopeId, initialTargets) -> {
            var floor = new DatabaseControlPlaneCertificateRotationFloor(database.jdbc(),
                    objectMapper, deploymentScopeId, initialTargets,
                    database.transactionManager());
            floor.init();
            return floor;
        };
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
            ControlPlaneCertificateRotationFloorFactory floorFactory) {
        return new ControlPlaneCertificateRotationRuntime(properties,
                properties.initialTargets(objectMapper), trustStore, materialSource,
                secretResolver, fingerprinter, floorFactory, Clock.systemUTC());
    }

    /** Exposes bounded durable local readiness without claiming fleet convergence. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationHealth.class)
    ControlPlaneCertificateRotationHealth controlPlaneCertificateRotationHealth(
            ControlPlaneCertificateRotationRuntime runtime) {
        return new ControlPlaneCertificateRotationHealth(runtime);
    }
}
