package com.leanowtech.bloge.gateway.visual.authoring.simulation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.JdbcSimulationRunStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/** Opt-in V013 persistence assembly for immutable Simulation runs. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class SimulationRunRuntimeConfiguration {
    /** Creates the read-only V013 startup probe. */
    @Bean
    @ConditionalOnMissingBean
    SimulationRunSchemaReadiness simulationRunSchemaReadiness(JdbcTemplate jdbc) {
        return new SimulationRunSchemaReadiness(jdbc);
    }

    /** Creates the JDBC run authority over the application transaction manager. */
    @Bean
    @ConditionalOnMissingBean(SimulationRunStore.class)
    SimulationRunStore simulationRunStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                          ObjectMapper mapper, SimulationRunSchemaReadiness readiness,
                                          @Value("${gateway.authoring.api-resource.simulation-lease-seconds:30}")
                                          long leaseSeconds) {
        if (readiness == null) throw new IllegalStateException("Simulation schema is not ready");
        if (leaseSeconds < 1 || leaseSeconds > 3_600) {
            throw new IllegalStateException("Simulation lease must be between 1 and 3600 seconds");
        }
        return new JdbcSimulationRunStore(jdbc, new TransactionTemplate(transactionManager), mapper,
                Duration.ofSeconds(leaseSeconds));
    }
}
