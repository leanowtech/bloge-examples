package com.leanowtech.bloge.gateway.visual.authoring.resource.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceAuthoringSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionSnapshotSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceProjectionCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.JdbcApiResourceCommitStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/**
 * Opt-in production assembly for the durable API Resource authoring protocol.
 *
 * <p>The feature is deliberately absent unless
 * {@code gateway.authoring.api-resource.enabled=true}. When enabled, bean
 * creation is fail-closed: the read-only schema gate and the required
 * projection compiler must both be available before the JDBC store is
 * constructed. This configuration never runs migrations or creates tables.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "gateway.authoring.api-resource",
        name = "enabled",
        havingValue = "true")
public class ApiResourceAuthoringRuntimeConfiguration {

    /** Creates the read-only schema gate; schema installation remains external. */
    @Bean
    @ConditionalOnMissingBean
    ApiResourceAuthoringSchemaReadiness apiResourceAuthoringSchemaReadiness(JdbcTemplate jdbc) {
        return new ApiResourceAuthoringSchemaReadiness(jdbc);
    }

    /** Creates the V011 gate for exact Connection snapshot provenance. */
    @Bean
    @ConditionalOnMissingBean
    ApiResourceConnectionSnapshotSchemaReadiness apiResourceConnectionSnapshotSchemaReadiness(
            JdbcTemplate jdbc) {
        return new ApiResourceConnectionSnapshotSchemaReadiness(jdbc);
    }

    /** Creates the stateless decision engine used by all API Resource mutations. */
    @Bean
    @ConditionalOnMissingBean
    ApiResourceDecisions apiResourceDecisions(ObjectMapper mapper) {
        return new ApiResourceDecisions(mapper);
    }

    /**
     * Creates the JDBC commit store only after schema readiness and projection
     * compilation have been resolved. The transaction manager is intentionally
     * supplied by the application so transaction and JDBC DataSources cannot
     * silently diverge.
     */
    @Bean
    @ConditionalOnMissingBean(ApiResourceCommitStore.class)
    ApiResourceCommitStore apiResourceCommitStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper mapper,
            ApiResourceAuthoringSchemaReadiness readiness,
            ApiResourceConnectionSnapshotSchemaReadiness snapshotReadiness,
            ApiResourceDecisions decisions,
            ApiResourceProjectionCompiler compiler,
            @Value("${gateway.authoring.api-resource.lease-seconds:30}") long leaseSeconds) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException(
                    "gateway.authoring.api-resource.lease-seconds must be positive");
        }
        // Keep readiness in the method signature: resolving it is the startup gate.
        if (readiness == null || snapshotReadiness == null) {
            throw new IllegalStateException("API Resource authoring schema is not ready");
        }
        return new JdbcApiResourceCommitStore(
                jdbc,
                new TransactionTemplate(transactionManager),
                mapper,
                Duration.ofSeconds(leaseSeconds),
                decisions,
                compiler);
    }
}
