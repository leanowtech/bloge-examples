package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation.config;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in application assembly for exact Fixture Case simulations. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.authoring.api-resource", name = "enabled", havingValue = "true")
public class ApiSimulationApplicationConfiguration {
    /** Fails startup when any Resource, Fixture or V013 authority is absent. */
    @Bean
    @ConditionalOnMissingBean
    SimulationModule simulationModule(ApiResourceCommitStore resources, ApiFixtureSetCommitStore fixtures,
                                      SimulationRunStore runs,
                                      ObjectProvider<ReusableFlowPublicationStore> flows) {
        ReusableFlowPublicationStore flowAuthority = flows.getIfAvailable();
        return flowAuthority == null
                ? new SimulationModule(resources, fixtures, runs)
                : new SimulationModule(resources, fixtures, flowAuthority, runs);
    }
}
