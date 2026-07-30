package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Non-production composition root for governed progressive-authoring fixtures. */
@Configuration(proxyBeanMethods = false)
@Profile("!production & (test | staging)")
public class AuthoringFixtureRuntimeConfiguration {

    @Bean
    AuthoringFixturePayloadProtector authoringFixturePayloadProtector(
            @Value("${gateway.testing.authoring-fixtures.payload-protection.active-key-id}")
            String activeKeyId,
            @Value("${gateway.testing.authoring-fixtures.payload-protection.key-ring}")
            String keyRing) {
        return AuthoringFixturePayloadProtector.fromConfiguration(
                activeKeyId, keyRing);
    }

    @Bean
    AuthoringFixtureRepository authoringFixtureRepository(
            TestRuntimeDatabase database,
            ObjectMapper objectMapper) {
        return new DatabaseAuthoringFixtureRepository(
                database.jdbc(),
                database.transactionManager(),
                objectMapper);
    }

    @Bean
    AuthoringFixtureRetentionScheduler authoringFixtureRetentionScheduler(
            AuthoringFixtureRepository fixtures,
            @Value("${gateway.testing.authoring-fixtures.sweep-batch-size:250}")
            int batchSize) {
        return new AuthoringFixtureRetentionScheduler(
                fixtures, Clock.systemUTC(), batchSize);
    }
}
