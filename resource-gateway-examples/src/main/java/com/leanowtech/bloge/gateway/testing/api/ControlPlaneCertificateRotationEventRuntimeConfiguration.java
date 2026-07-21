package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateRotationEventCursor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.util.Objects;

/**
 * Spring composition root for durable authenticated certificate-rotation event delivery.
 *
 * <p>The root exists only in non-production test/staging profiles and only when explicitly
 * enabled. Startup requires the signed rotation runtime and all-replica convergence policy to be
 * enabled and required. The durable cursor is keyed by the convergence policy's stable serving
 * slot, never its process startup id.</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = ControlPlaneCertificateRotationEventSourceProperties.PREFIX,
        name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ControlPlaneCertificateRotationEventSourceProperties.class,
        ControlPlaneCertificateRotationRuntimeProperties.class,
        ControlPlaneCertificateRotationConvergenceProperties.class})
public class ControlPlaneCertificateRotationEventRuntimeConfiguration {

    /** Creates the profile-gated composition root. */
    public ControlPlaneCertificateRotationEventRuntimeConfiguration() {
    }

    /** Creates the stable serving-slot database cursor and verifies its deployment baseline. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationEventCursor.class)
    ControlPlaneCertificateRotationEventCursor controlPlaneCertificateRotationEventCursor(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper,
            ControlPlaneCertificateRotationEventSourceProperties eventProperties,
            ControlPlaneCertificateRotationRuntimeProperties rotationProperties,
            ControlPlaneCertificateRotationConvergenceProperties convergenceProperties) {
        requireProductDependencies(eventProperties, rotationProperties, convergenceProperties);
        var cursor = new DatabaseControlPlaneCertificateRotationEventCursor(
                database.jdbc(), objectMapper, rotationProperties.deploymentScopeId(),
                convergenceProperties.instanceId(), eventProperties.baselineSequence(),
                eventProperties.baselinePageFingerprint(), database.transactionManager());
        cursor.init();
        return cursor;
    }

    /** Creates the strict independent private-PKIX/SPKI/mTLS event-page source. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationEventSource.class)
    ControlPlaneCertificateRotationEventSource controlPlaneCertificateRotationEventSource(
            ObjectMapper objectMapper,
            ControlPlaneHttpTransport.SecretResolver secretResolver,
            ControlPlaneCertificateRotationEventSourceProperties eventProperties,
            ControlPlaneCertificateRotationRuntimeProperties rotationProperties,
            ControlPlaneCertificateRotationConvergenceProperties convergenceProperties) {
        requireProductDependencies(eventProperties, rotationProperties, convergenceProperties);
        RecoveryFleetPublicationTransport transport = eventProperties.transport().create(
                secretResolver);
        return new HttpControlPlaneCertificateRotationEventSource(
                objectMapper, Clock.systemUTC(), transport, eventProperties.sourceSettings());
    }

    /** Creates the bounded stage/apply/commit watcher behind the live convergence serving fence. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationEventWatcher.class)
    ControlPlaneCertificateRotationEventWatcher controlPlaneCertificateRotationEventWatcher(
            ControlPlaneCertificateRotationEventSource source,
            ControlPlaneCertificateRotationEventCursor cursor,
            ControlPlaneCertificateRotationRuntime runtime,
            ControlPlaneCertificateRotationConvergenceMonitor convergenceMonitor,
            ControlPlaneCertificateRotationEventSourceProperties eventProperties,
            ControlPlaneCertificateRotationRuntimeProperties rotationProperties,
            ControlPlaneCertificateRotationConvergenceProperties convergenceProperties) {
        requireProductDependencies(eventProperties, rotationProperties, convergenceProperties);
        Objects.requireNonNull(convergenceMonitor, "convergenceMonitor");
        return new ControlPlaneCertificateRotationEventWatcher(
                source, cursor, runtime, eventProperties.pollInterval(),
                eventProperties.maximumPagesPerPoll());
    }

    /** Creates fixed-cardinality Actuator truth for the delivery path. */
    @Bean
    @ConditionalOnMissingBean(ControlPlaneCertificateRotationEventWatcherHealth.class)
    ControlPlaneCertificateRotationEventWatcherHealth
            controlPlaneCertificateRotationEventWatcherHealth(
            ControlPlaneCertificateRotationEventWatcher watcher,
            ControlPlaneCertificateRotationEventSourceProperties properties) {
        return new ControlPlaneCertificateRotationEventWatcherHealth(watcher, properties);
    }

    private static void requireProductDependencies(
            ControlPlaneCertificateRotationEventSourceProperties eventProperties,
            ControlPlaneCertificateRotationRuntimeProperties rotationProperties,
            ControlPlaneCertificateRotationConvergenceProperties convergenceProperties) {
        if (!eventProperties.enabled() || !rotationProperties.enabled()
                || !rotationProperties.required() || !convergenceProperties.enabled()
                || !convergenceProperties.required()) {
            throw new IllegalStateException(
                    "Certificate rotation event delivery requires signed rotation and convergence");
        }
    }
}
