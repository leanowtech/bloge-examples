package com.leanowtech.bloge.gateway.testing.world.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in durable impact index wiring; no in-memory production fallback is installed. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "resource-gateway.world-impact", name = "persistence", havingValue = "database")
@ConditionalOnBean(JdbcTemplate.class)
public class WorldImpactDatabaseConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorldImpactSnapshotRepository.class)
    WorldImpactSnapshotRepository worldImpactSnapshotRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new DatabaseWorldImpactSnapshotRepository(jdbc, mapper);
    }

    @Bean
    @ConditionalOnBean(WorldImpactSnapshotRepository.class)
    WorldImpactIndexService worldImpactIndexService(WorldImpactSnapshotRepository repository) {
        return new WorldImpactIndexService(repository);
    }
}
