package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.TestabilityAvailability;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseFixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRunRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/** Profile-gated composition root for the isolated test control plane. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
public class TestRuntimeConfiguration {

    @Bean(destroyMethod = "close")
    TestRuntimeDatabase testRuntimeDatabase(
            @Value("${gateway.testing.store.jdbc-url:jdbc:h2:file:./data/resource-gateway-test-runtime;AUTO_SERVER=TRUE}")
            String jdbcUrl,
            @Value("${gateway.testing.store.username:sa}") String username,
            @Value("${gateway.testing.store.password:}") String password,
            @Value("${gateway.testing.store.maximum-pool-size:4}") int maximumPoolSize) {
        return new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                jdbcUrl, username, password, maximumPoolSize));
    }

    @Bean
    FixtureBundleRepository fixtureBundleRepository(TestRuntimeDatabase database, ObjectMapper objectMapper) {
        return new DatabaseFixtureBundleRepository(database.jdbc(), objectMapper);
    }

    @Bean
    TestRunRepository testRunRepository(TestRuntimeDatabase database, ObjectMapper objectMapper) {
        return new DatabaseTestRunRepository(database.jdbc(), objectMapper);
    }

    @Bean
    TestSecurityEventRepository testSecurityEventRepository(TestRuntimeDatabase database,
                                                            ObjectMapper objectMapper) {
        return new DatabaseTestSecurityEventRepository(database.jdbc(), objectMapper);
    }

    @Bean
    TestExecutionApiService testExecutionApiService(
            GatewayGraphService graphService,
            OperatorRegistry operatorRegistry,
            ResourceRegistry resourceRegistry,
            BlgeExpressionEvaluator expressionEvaluator,
            ObjectMapper objectMapper,
            FixtureBundleRepository fixtureRepository,
            TestRunRepository runRepository,
            TestSecurityEventRepository securityEvents,
            @Value("${gateway.testing.store.retention-days:30}") long retentionDays) {
        return new TestExecutionApiService(graphService, operatorRegistry, resourceRegistry,
                expressionEvaluator, objectMapper, fixtureRepository, runRepository, securityEvents,
                Duration.ofDays(Math.max(1, Math.min(3650, retentionDays))));
    }

    /** Marker consumed by the unauthenticated capability probe. */
    @Bean
    TestabilityAvailability testabilityAvailability() {
        return new TestabilityAvailability(true);
    }
}
