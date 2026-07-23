package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.MirrorStatefulRuntimeAvailability;
import com.leanowtech.bloge.gateway.integration.mirror.DatabaseMirrorSessionStateStore;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStore;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStateDataPlane;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStatePayloadProtector;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /** Creates the durable full-scope lease-fenced encrypted state store. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionStateStore mirrorSessionStateStore(
            MirrorStateDataPlane dataPlane,
            ObjectMapper mapper,
            MirrorStatePayloadProtector protector) {
        return new DatabaseMirrorSessionStateStore(
                dataPlane.jdbc(), mapper, protector,
                dataPlane.transactionManager());
    }

    /**
     * Keeps copy-on-write baseline resolution fail closed until RG-MIR-STATE-008 is assembled.
     */
    @Bean
    @ConditionalOnMissingBean
    public MirrorStateBaselineResolver mirrorStateBaselineResolver() {
        return MirrorStateBaselineResolver.none();
    }

    /** Creates the authenticated session application boundary. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorSessionIntegrationService mirrorSessionIntegrationService(
            ObjectMapper mapper,
            MirrorSessionStateStore store,
            MirrorStateBaselineResolver baselineResolver,
            @Value("${gateway.testing.mirror.stateful.instance-id:}")
            String instanceId,
            @Value("${gateway.testing.mirror.stateful.lease-duration-seconds:30}")
            long leaseDurationSeconds) {
        return new MirrorSessionIntegrationService(
                mapper, store, baselineResolver, Clock.systemUTC(),
                instanceId, leaseDurationSeconds);
    }

    /** Publishes route assembly and dynamic encrypted-store readiness independently. */
    @Bean
    @ConditionalOnMissingBean
    public MirrorStatefulRuntimeAvailability
    mirrorStatefulRuntimeAvailability(MirrorSessionStateStore store) {
        return new MirrorStatefulRuntimeAvailability(true, store::ready);
    }
}
