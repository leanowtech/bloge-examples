package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in durable drift governance; no process-local production fallback is installed. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "resource-gateway.world-fidelity", name = "persistence", havingValue = "database")
@ConditionalOnBean(JdbcTemplate.class)
public class WorldFidelityDatabaseConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorldFidelityDriftRepository.class)
    WorldFidelityDriftRepository worldFidelityDriftRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new DatabaseWorldFidelityDriftRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnBean(WorldFidelityDriftRepository.class)
    WorldFidelityPolicyService worldFidelityPolicyService(WorldFidelityDriftRepository repository,
                                                           ObjectMapper mapper) {
        return new WorldFidelityPolicyService(repository, mapper);
    }
}
