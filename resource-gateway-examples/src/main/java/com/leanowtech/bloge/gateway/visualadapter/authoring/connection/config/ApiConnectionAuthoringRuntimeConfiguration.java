package com.leanowtech.bloge.gateway.visualadapter.authoring.connection.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.JdbcApiConnectionAuthoringStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * Opt-in JDBC adapter assembly for standalone Connection authoring.
 *
 * <p>Schema installation remains external. Enabling the feature resolves the
 * read-only readiness gate before constructing a lifecycle-complete store over
 * one {@link DataSource}; missing or stale migrations therefore fail startup
 * instead of surfacing on the first authoring request.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiConnectionAuthoringRuntimeConfiguration {
    /** Creates the read-only V001–V010 Connection schema gate. */
    @Bean
    @ConditionalOnMissingBean
    ApiConnectionAuthoringSchemaReadiness apiConnectionAuthoringSchemaReadiness(JdbcTemplate jdbc) {
        return new ApiConnectionAuthoringSchemaReadiness(jdbc);
    }

    /** Creates the lifecycle-complete JDBC store after schema readiness succeeds. */
    @Bean
    @ConditionalOnMissingBean(ApiConnectionAuthoringStore.class)
    ApiConnectionAuthoringStore apiConnectionAuthoringStore(
            DataSource dataSource,
            ObjectMapper mapper,
            ApiConnectionDecisions decisions,
            ApiConnectionAuthoringSchemaReadiness readiness,
            @Value("${gateway.authoring.api-resource.lease-seconds:30}") long leaseSeconds) {
        if (readiness == null) throw new IllegalStateException("API Connection schema is not ready");
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("gateway.authoring.api-resource.lease-seconds must be positive");
        }
        return new JdbcApiConnectionAuthoringStore(
                dataSource, mapper, Duration.ofSeconds(leaseSeconds), decisions, Clock.systemUTC());
    }
}
